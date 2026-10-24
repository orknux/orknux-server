package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.RunEnded
import io.mszymanski.orknux.workflow.execution.RunStarted
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.StepRetrying
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
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
 * **Saved once.** A seeded mode's Save step saves the report itself; a
 * workflow that ended without one - a custom mode, or one drawn before the
 * steps were nodes - has it saved here when the run ends, by the same
 * [ResearchDelivery].
 */
@Component
class ResearchRuns(
    private val researches: ResearchRepository,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val runs: ExecutionService,
    private val delivery: ResearchDelivery,
    private val pipeline: ResearchPipeline,
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

    /** Says on the research that its plan is being made, as the Plan step would where it is a function. */
    fun planning(researchId: Long) {
        researches.noteProgress(
            researchId,
            mapper.writeValueAsString(ResearchProgress(step = "Planning the research", stage = ResearchStage.PLAN)),
            "[]",
        )
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

    /**
     * Says on the research that a step failed and is being tried again, and
     * when: otherwise its line goes on naming what the failed attempt was
     * doing while the run sits out the wait.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun retrying(event: StepRetrying) {
        runCatching {
            alone.executeWithoutResult {
                val run = executions.findByIdOrNull(event.executionId) ?: return@executeWithoutResult
                val research = researchOf(run)?.takeIf { it.status == ResearchStatus.RUNNING } ?: return@executeWithoutResult
                pipeline.note(
                    research.id,
                    STAGES[event.nodeKey],
                    "${event.stepName} failed on attempt ${event.attempt} of ${event.attempts}; " +
                        "trying again in ${event.wait.toSeconds()}s",
                )
            }
        }.onFailure { log.warn("Could not note a retry on the research of run {}", event.executionId, it) }
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

    /**
     * Saves the report as the file it was asked for, on the Artifacts page -
     * unless the workflow's own Save step has, or has tried and said why not:
     * a research is saved once, however its workflow was drawn.
     */
    private fun deliver(research: Research, report: String) {
        if (research.artifactId != null || research.error != null) return
        when (val delivered = delivery.deliver(research.workspaceId, research.question, research.outputFormat, report)) {
            is ResearchDelivery.Delivered.Saved -> research.artifactId = delivered.artifactId
            is ResearchDelivery.Delivered.Refused -> research.error = delivered.reason
        }
    }

    companion object {
        /** What the run carries the research's id under. */
        const val RESEARCH = "research"

        /** The stage each of a seeded mode's steps is, by its node's key. */
        private val STAGES = mapOf(
            ResearchModes.PLAN to ResearchStage.PLAN,
            ResearchModes.GATHER to ResearchStage.GATHER,
            ResearchModes.FOLLOW_UP to ResearchStage.FOLLOW_UP,
            ResearchModes.WRITE to ResearchStage.WRITE,
            ResearchModes.SAVE to ResearchStage.SAVE,
        )
    }
}
