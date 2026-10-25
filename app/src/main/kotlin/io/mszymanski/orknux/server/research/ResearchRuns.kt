package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.RunEnded
import io.mszymanski.orknux.workflow.execution.ResumePoint
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
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
 * **Followed onto a re-run.** A run that restarts one of a research's runs -
 * from the research's page, the `orknux_restart_research` tool, or Rerun on
 * the Runs page - carries the research's id in the payload it repeats, and
 * the research moves onto it when it starts: running again, with what the
 * steps before the restart found kept and the rest let go. Without that the
 * page went on showing the old run's failure while the new one did the work.
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
    private val drafts: ResearchDrafts,
    private val mapper: ObjectMapper,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val alone = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val starting: ExecutorService =
        Executors.newCachedThreadPool { Thread(it, "research-run").apply { isDaemon = true } }

    /**
     * A restart asked for from the page or a tool, waiting to hear that its run
     * has started - or why it could not be. By research id.
     */
    private val restarting = ConcurrentHashMap<Long, CompletableFuture<Long>>()

    @PreDestroy
    fun stop() {
        starting.shutdownNow()
    }

    /**
     * Restarts a research's run from [nodeKey], or from the beginning where it
     * is null, through the same start a rerun of a step takes: the steps ahead
     * of it carried over from [previous], the rest done again.
     *
     * On a thread of its own, for the reason [launch] gives, and answered once
     * the new run has started - [started] moves the research onto it - or with
     * whatever the start refused, which is the execution module's reason for
     * it and is handed back as it is.
     */
    fun restart(research: Research, previous: WorkflowExecution, nodeKey: String?): Long {
        val id = requireNotNull(research.id)
        val waiting = CompletableFuture<Long>()
        if (restarting.putIfAbsent(id, waiting) != null) throw ResearchStillRunningException(id)
        try {
            starting.execute {
                try {
                    val started = runs.startExecution(
                        StartExecutionInput(
                            workspaceId = previous.workspaceId,
                            workflowId = previous.workflowId,
                            trigger = ExecutionTrigger.MANUAL,
                            payload = previous.input,
                            // The graph the earlier run ran, as rerunExecutionStep has it.
                            version = if (previous.trigger == ExecutionTrigger.MANUAL) GraphVersion.DRAFT else GraphVersion.PUBLISHED,
                            resumeFrom = nodeKey?.let { ResumePoint(executionId = requireNotNull(previous.id), nodeKey = it) },
                            startedFrom = previous.id,
                        ),
                    )
                    waiting.complete(started.id)
                } catch (refused: Exception) {
                    waiting.completeExceptionally(refused)
                } finally {
                    restarting.remove(id, waiting)
                }
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            restarting.remove(id, waiting)
            throw rejected
        }
        return try {
            waiting.get()
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
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

    /**
     * Writes the run's id on the research as soon as there is one, so the list
     * can link to it - and moves a research onto a run that restarts one of
     * its runs, whichever door the restart came through.
     */
    @TransactionalEventListener(fallbackExecution = true)
    fun started(event: RunStarted) {
        var claimed: Long? = null
        runCatching {
            alone.executeWithoutResult {
                val run = executions.findByIdOrNull(event.executionId) ?: return@executeWithoutResult
                val research = researchOf(run)
                if (research != null) {
                    claimed = research.id
                    if (research.executionId == null) researches.attachRun(requireNotNull(research.id), event.executionId)
                    return@executeWithoutResult
                }
                claimed = claimedBy(run)?.id
                follow(run, event)
            }
        }.onFailure { log.warn("Could not note run {} on its research", event.executionId, it) }
        // The page that asked for the restart is answered now, while the run goes on.
        claimed?.let { restarting[it]?.complete(event.executionId) }
    }

    /**
     * Moves a research onto [run] where it restarts one of the research's runs:
     * the one it is on, or one it was on before. The research's id is in the
     * payload the re-run repeats, which is how it is found; the run it came
     * from is how it is known to be a restart rather than a second research
     * started by hand with the same payload.
     *
     * Not where the research is running behind a run that is still going: that
     * run is still deciding what the research says, and two runs writing on
     * one row is not a restart of anything. Answers the research's id where it
     * moved it.
     */
    private fun follow(run: WorkflowExecution, event: RunStarted): Long? {
        val from = run.startedFrom ?: return null
        if (event.carriedOn) return null
        val research = claimedBy(run) ?: return null
        val id = requireNotNull(research.id)
        val earlier = earlierRunsOf(research)
        val current = research.executionId
        if (current == run.id) return null
        if (!descendsFrom(from, (earlier + listOfNotNull(current)).toSet())) return null
        if (research.status == ResearchStatus.RUNNING &&
            current?.let { executions.findByIdOrNull(it) }?.status == ExecutionStatus.RUNNING
        ) {
            return null
        }

        // Where the new run starts, as a stage: the whole of it where it starts at the beginning.
        val stage = event.resumedAt?.let { STAGES[it] } ?: if (event.resumedAt == null) ResearchStage.PLAN else null
        val gathered = stage?.let { handedOn(from, it) }
        val (progress, sources, images) = pipeline.restarted(research, stage, gathered)
        val runs = (earlier + listOfNotNull(current)).distinct()
        researches.restarted(
            id, requireNotNull(run.id), ResearchStatus.RUNNING, progress, sources, images, mapper.writeValueAsString(runs),
        )
        drafts.forget(id)
        log.info("Research {} restarted on run {} from {}", id, run.id, event.resumedAt ?: "the beginning")
        return id
    }

    /**
     * Whether run [from] is one of [runs], or a re-run of one of them however
     * many re-runs back: a rerun of a rerun the research was never moved onto -
     * one made before restarts carried a research along - still carries it.
     */
    private fun descendsFrom(from: Long, runs: Set<Long>): Boolean {
        val seen = mutableSetOf<Long>()
        var at: Long? = from
        while (at != null && seen.add(at)) {
            if (at in runs) return true
            at = executions.findByIdOrNull(at)?.startedFrom
        }
        return false
    }

    /**
     * What the step before [stage] handed on in run [executionId]: Gather's
     * answer for Follow up, Follow up's for Write and Save. What a restart
     * carries over, and so what the research keeps.
     */
    private fun handedOn(executionId: Long, stage: ResearchStage): ResearchGathered? {
        val from = when (stage) {
            ResearchStage.FOLLOW_UP -> listOf(ResearchModes.GATHER)
            ResearchStage.WRITE, ResearchStage.SAVE -> listOf(ResearchModes.FOLLOW_UP, ResearchModes.GATHER)
            else -> return null
        }
        val outputs = steps.findByExecutionIdOrderByOrderAsc(executionId)
            .filter { it.status == StepStatus.COMPLETED }
            .associate { it.nodeKey to it.output }
        return from.firstNotNullOfOrNull { key -> outputs[key]?.let(::gatheredIn) }
    }

    /** A step's answer as what was gathered: as it is, or under the one name the step put it under. */
    private fun gatheredIn(output: String): ResearchGathered? {
        val read = runCatching { mapper.readTree(output) }.getOrNull()?.takeIf { it.isObject } ?: return null
        val shaped = if (read.has("sources")) read else read.properties().singleOrNull()?.value
        return shaped?.takeIf { it.isObject && it.has("sources") }?.let(pipeline::gatheredFrom)
    }

    /** The runs a research was on before the one it is on now. */
    fun earlierRunsOf(research: Research): List<Long> =
        research.earlierRuns?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }
            ?.mapNotNull { one -> one.takeIf { it.isNumber }?.longValue() ?: one.takeIf { it.isTextual }?.stringValue()?.toLongOrNull() }
            .orEmpty()

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
        if (run.status != ExecutionStatus.RUNNING) drafts.forget(requireNotNull(research.id))
        when (run.status) {
            ExecutionStatus.RUNNING -> return
            ExecutionStatus.COMPLETED -> {
                val report = reportOf(run)?.trim()?.takeIf { it.isNotEmpty() }
                if (report == null) {
                    research.status = ResearchStatus.FAILED
                    research.error = "The workflow finished without writing a report."
                } else {
                    research.status = ResearchStatus.COMPLETED
                    val images = pipeline.imagesFrom(research.foundImages)
                    /*
                     * As the page reads it: a picture placed is a link to the
                     * one kept, and a picture made up is its words. A Save step
                     * that ran answered it so already, and placing it twice
                     * would take the links it put in for words.
                     */
                    val saved = research.artifactId != null || research.error != null
                    research.result = if (saved) report else ResearchDelivery.placed(report, images, ResearchDelivery::linkOf)
                    deliver(research, report, images)
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
        idIn(run.input, mapper) ?: return researches.findByExecutionId(requireNotNull(run.id))
        return claimedBy(run)?.takeIf { it.executionId == null || it.executionId == run.id }
    }

    /** The research whose id this run carries, whichever run it is on now. */
    private fun claimedBy(run: WorkflowExecution): Research? {
        val id = idIn(run.input, mapper) ?: return null
        return researches.findByIdOrNull(id)?.takeIf { it.workspaceId == run.workspaceId }
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
    private fun deliver(research: Research, report: String, images: List<ResearchImage>) {
        if (research.artifactId != null || research.error != null) return
        when (val delivered = delivery.deliver(research.workspaceId, research.question, research.outputFormat, report, images)) {
            is ResearchDelivery.Delivered.Saved -> research.artifactId = delivered.artifactId
            is ResearchDelivery.Delivered.Refused -> research.error = delivered.reason
        }
    }

    companion object {
        /** What the run carries the research's id under. */
        const val RESEARCH = "research"

        /** The research id a run's payload carries, if it carries one. */
        fun idIn(input: String?, mapper: ObjectMapper): Long? = input
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.path(RESEARCH)
            ?.takeIf { it.isNumber || it.isTextual }
            ?.asString()?.toLongOrNull()

        /** The node a seeded mode's workflow takes each stage in. */
        val NODES: Map<ResearchStage, String> get() = STAGES.entries.associate { (key, stage) -> stage to key }

        /** The stage each of a seeded mode's steps is, by its node's key. */
        val STAGES = mapOf(
            ResearchModes.PLAN to ResearchStage.PLAN,
            ResearchModes.GATHER to ResearchStage.GATHER,
            ResearchModes.FOLLOW_UP to ResearchStage.FOLLOW_UP,
            ResearchModes.WRITE to ResearchStage.WRITE,
            ResearchModes.SAVE to ResearchStage.SAVE,
        )
    }
}
