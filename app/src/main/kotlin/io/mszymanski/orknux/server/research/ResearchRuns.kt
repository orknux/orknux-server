package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.embedded.PdfWriter
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.RunEnded
import io.mszymanski.orknux.workflow.execution.RunStarted
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import jakarta.annotation.PreDestroy
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A research's run, from the moment it is started to the report it leaves.
 * Issue #659.
 *
 * **Started on a thread of its own.** The inline engine walks a run on the
 * thread that asked for it and answers when the walk is over, and a deep
 * research is many minutes of a model reading pages - so the page that pressed
 * Start would wait that long for its answer. The run is handed to a thread here
 * instead, and the research row is what the page reads in the meantime.
 *
 * **Found by its payload.** The run carries the research's id, which is how
 * [RunStarted] and [RunEnded] are matched to a research: the inline engine
 * gives the run's id back only once it has ended, so there is nothing else to
 * match on while it runs.
 *
 * **The report is the server's to lay out.** The workflow answers in markdown -
 * one field, whatever the workflow does to get there - and turning that into a
 * PDF or a document is done here, the same way every time, with the libraries
 * the rest of the server already uses: commonmark for the HTML and the PDF
 * writer `pdf_fromHtml` uses for the page. A workflow that had to produce the
 * file itself would be one more thing for every custom mode to get right.
 */
@Component
class ResearchRuns(
    private val researches: ResearchRepository,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val runs: ExecutionService,
    private val saved: SavedArtifacts,
    private val pdf: PdfWriter,
    private val mapper: ObjectMapper,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val alone = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val starting: ExecutorService =
        Executors.newCachedThreadPool { Thread(it, "research-run").apply { isDaemon = true } }

    @PreDestroy
    fun stop() {
        starting.shutdownNow()
    }

    /** Starts the research's workflow on a thread of its own; a refusal to start ends the research. */
    fun launch(research: Research, payload: String) {
        val id = requireNotNull(research.id)
        starting.execute {
            try {
                runs.startExecution(
                    StartExecutionInput(
                        workspaceId = research.workspaceId,
                        workflowId = research.workflowId,
                        // A person pressed Start, so it is theirs, and it runs
                        // the workflow as it is drawn now: an edit to a mode's
                        // workflow is in the next research without publishing.
                        trigger = ExecutionTrigger.MANUAL,
                        payload = payload,
                        version = GraphVersion.DRAFT,
                    ),
                )
            } catch (refused: Exception) {
                log.warn("Research {} could not be started", id, refused)
                researches.findByIdOrNull(id)?.takeIf { it.status == ResearchStatus.RUNNING }?.let {
                    it.status = ResearchStatus.FAILED
                    it.error = refused.message ?: "The workflow could not be started"
                    it.finishedAt = OffsetDateTime.now()
                    researches.save(it)
                }
            }
        }
    }

    /*
     * Both listeners run once the run's own writing has committed, and in a
     * transaction of their own: what goes wrong with a research - a PDF that
     * will not lay out, a file the store refuses - must not be able to roll
     * back the run it is reading.
     */

    /** Writes the run's id on the research as soon as there is one, so the list can link to it. */
    @TransactionalEventListener(fallbackExecution = true)
    fun started(event: RunStarted) {
        runCatching {
            alone.executeWithoutResult {
                val run = executions.findByIdOrNull(event.executionId) ?: return@executeWithoutResult
                val research = researchOf(run) ?: return@executeWithoutResult
                if (research.executionId == null) researches.attachRun(requireNotNull(research.id), event.executionId)
            }
        }.onFailure { log.warn("Could not note run {} on its research", event.executionId, it) }
    }

    @TransactionalEventListener(fallbackExecution = true)
    fun ended(event: RunEnded) {
        runCatching { alone.executeWithoutResult { settle(event.executionId) } }
            .onFailure { log.warn("Could not finish the research of run {}", event.executionId, it) }
    }

    /**
     * Writes what came of the run on its research, where the run has ended and
     * the research has not heard. Also asked by a reader that finds a research
     * still running behind a run that is not, which is a server that stopped
     * between the two.
     */
    fun settle(executionId: Long) {
        val run = executions.findByIdOrNull(executionId) ?: return
        val research = researchOf(run) ?: return
        if (research.status != ResearchStatus.RUNNING) return

        research.executionId = executionId
        when (run.status) {
            ExecutionStatus.RUNNING -> return
            ExecutionStatus.COMPLETED -> {
                val report = reportOf(run)?.trim()?.takeIf { it.isNotEmpty() }
                if (report == null) {
                    research.status = ResearchStatus.FAILED
                    research.error = "The workflow finished without writing a report."
                } else {
                    research.status = ResearchStatus.COMPLETED
                    research.result = report
                    deliver(research, report)
                }
            }
            ExecutionStatus.FAILED -> {
                research.status = ResearchStatus.FAILED
                research.error = run.error ?: "The workflow failed without saying why."
            }
            ExecutionStatus.STOPPED -> research.status = ResearchStatus.STOPPED
        }
        research.finishedAt = run.finishedAt ?: OffsetDateTime.now()
        researches.save(research)
    }

    /** The research this run is doing, read off the id it carries. */
    private fun researchOf(run: WorkflowExecution): Research? {
        val id = run.input
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.path(RESEARCH)
            ?.takeIf { it.isNumber || it.isTextual }
            ?.asString()?.toLongOrNull()
            ?: return researches.findByExecutionId(requireNotNull(run.id))
        return researches.findByIdOrNull(id)?.takeIf {
            it.workspaceId == run.workspaceId && (it.executionId == null || it.executionId == run.id)
        }
    }

    /**
     * What the workflow wrote: the field the seeded workflows name their
     * answer, or, for a workflow that names it otherwise, what its last step
     * to finish said.
     */
    private fun reportOf(run: WorkflowExecution): String? {
        run.carried?.let { carried ->
            val read = runCatching { mapper.readTree(carried) }.getOrNull()
            // Under the seeded node's name, or straight on the run for a workflow that put it there.
            listOfNotNull(read?.path(ResearchModes.OUTPUT)?.path(ResearchModes.REPORT), read?.path(ResearchModes.REPORT))
                .firstOrNull { it.isTextual }
                ?.let { return it.stringValue() }
        }
        val last = steps.findByExecutionIdOrderByOrderAsc(requireNotNull(run.id))
            .filter { it.status == StepStatus.COMPLETED && !it.output.isNullOrBlank() }
            .maxByOrNull { it.finishedAt ?: OffsetDateTime.MIN }
            ?.output
            ?: return null
        val read = runCatching { mapper.readTree(last) }.getOrNull()
        return when {
            read == null -> last
            read.isTextual -> read.stringValue()
            read.isObject -> read.properties().singleOrNull()?.value?.takeIf { it.isTextual }?.stringValue() ?: last
            else -> last
        }
    }

    /** Saves the report as the file it was asked for, on the Artifacts page. */
    private fun deliver(research: Research, report: String) {
        val title = titleOf(report, research.question)
        val description = "Research: ${research.question.trim().replace(Regex("\\s+"), " ").take(MOST_DESCRIPTION)}"
        val saving = when (research.outputFormat) {
            ResearchOutputFormat.DOCUMENT ->
                saved.save(research.workspaceId, SAVED_BY, fileName(title, "md"), description, report, base64 = false)
            ResearchOutputFormat.PDF -> when (val written = pdf.fromHtml(html(report), title)) {
                is PdfWriter.Written.Refused -> {
                    research.error = "The report could not be laid out as a PDF: ${written.reason}"
                    return
                }
                is PdfWriter.Written.Made -> saved.save(
                    research.workspaceId, SAVED_BY, fileName(title, "pdf"), description,
                    Base64.getEncoder().encodeToString(written.pdf), base64 = true,
                )
            }
        }
        when (saving) {
            is SavedArtifacts.Saving.Saved -> research.artifactId = saving.artifact.id
            is SavedArtifacts.Saving.Refused -> research.error = "The report could not be saved as a file: ${saving.reason}"
        }
    }

    companion object {
        /** What the run carries the research's id under. */
        const val RESEARCH = "research"

        /** Who the Artifacts page says saved a report. */
        const val SAVED_BY = "Research"

        private const val MOST_DESCRIPTION = 400
        private const val MOST_FILE_NAME = 80

        private val extensions = listOf(TablesExtension.create())
        private val parser: Parser = Parser.builder().extensions(extensions).build()

        /**
         * Markdown to HTML, with any HTML the model wrote escaped rather than
         * kept: what a model writes is untrusted, and the PDF writer would
         * otherwise lay out whatever markup a page it read talked it into.
         */
        private val renderer: HtmlRenderer =
            HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()

        fun html(markdown: String): String = renderer.render(parser.parse(markdown))

        /** The report's own first heading, or the question where it has none. */
        fun titleOf(report: String, question: String): String =
            report.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: question.trim().lineSequence().first()

        /** A file name a download can carry: letters, digits and dashes. */
        fun fileName(title: String, extension: String): String {
            val stem = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(MOST_FILE_NAME).trim('-')
            return "${stem.ifEmpty { "research" }}.$extension"
        }
    }
}
