package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/**
 * About how long a running research has left, measured rather than guessed.
 *
 * Measured off the workspace's own finished research in the same mode - the
 * same model's where there are enough of those, any model's where there are
 * not - and per step where their runs say how long each step took: the median
 * of each step still to come, and of the one it is in less how long it has
 * been in it. Where the steps cannot be told apart, a workflow somebody drew
 * their own way, it is the median of the whole less how long this run has
 * been going. Fewer finished research than [Tunable.RESEARCH_ESTIMATE_RUNS]
 * and there is nothing to measure from, so there is no estimate at all.
 *
 * The median rather than the mean: one research whose model sat in a queue
 * for ten minutes says nothing about the next one.
 */
@Component
class ResearchEstimates(
    private val researches: ResearchRepository,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) {

    /** Seconds left, as of [now]; null where it is not running or there is not enough to go on. */
    fun secondsLeft(research: Research, now: OffsetDateTime = OffsetDateTime.now()): Int? {
        if (research.status != ResearchStatus.RUNNING) return null
        val modeId = research.modeId ?: return null
        val run = research.executionId?.let { executions.findByIdOrNull(it) } ?: return null
        val least = tuning[Tunable.RESEARCH_ESTIMATE_RUNS]

        val finished = researches.findByWorkspaceIdAndModeIdAndStatusAndExecutionIdIsNotNullOrderByFinishedAtDesc(
            research.workspaceId, modeId, ResearchStatus.COMPLETED, PageRequest.of(0, tuning[Tunable.RESEARCH_ESTIMATE_LOOKBACK]),
        ).filter { it.id != research.id && it.finishedAt != null }
        val sameModel = finished.filter { it.modelId != null && it.modelId == research.modelId }
        val basis = if (sameModel.size >= least) sameModel else finished
        if (basis.size < least) return null

        stagesLeft(research, basis, least, now)?.let { return it }

        val wholes = basis.mapNotNull { one -> one.finishedAt?.let { seconds(one.startedAt, it) } }
        if (wholes.size < least) return null
        return (median(wholes) - seconds(run.startedAt, now)).coerceAtLeast(0).toInt()
    }

    /** Step by step, where the research says which step it is in and each one ahead has been timed often enough. */
    private fun stagesLeft(research: Research, basis: List<Research>, least: Int, now: OffsetDateTime): Int? {
        val stage = stageOf(research) ?: return null
        val taken = mutableMapOf<ResearchStage, MutableList<Long>>()
        basis.forEach { one ->
            steps.findByExecutionIdOrderByOrderAsc(requireNotNull(one.executionId)).forEach { step ->
                val at = ResearchRuns.STAGES[step.nodeKey] ?: return@forEach
                if (at == ResearchStage.REVIEW && !one.review) return@forEach
                val began = step.startedAt ?: return@forEach
                val ended = step.finishedAt ?: return@forEach
                if (step.status == StepStatus.COMPLETED) taken.getOrPut(at) { mutableListOf() } += seconds(began, ended)
            }
        }
        // Review is a step only of a research that asked for it; one that did not goes past it in no time.
        val ahead = ResearchStage.entries.dropWhile { it != stage }.filter { it != ResearchStage.REVIEW || research.review }
        if (ahead.any { (taken[it]?.size ?: 0) < least }) return null

        val node = ResearchRuns.NODES[stage]
        val inIt = research.executionId?.let { steps.findByExecutionIdOrderByOrderAsc(it) }.orEmpty()
            .firstOrNull { it.nodeKey == node && it.status in OPEN }
            ?.startedAt?.let { seconds(it, now) } ?: 0L
        val current = (median(taken.getValue(stage)) - inIt).coerceAtLeast(0)
        return (current + ahead.drop(1).sumOf { median(taken.getValue(it)) }).toInt()
    }

    private fun stageOf(research: Research): ResearchStage? =
        research.progress?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?.path("stage")?.takeIf { it.isTextual }?.stringValue()
            ?.let { held -> ResearchStage.entries.firstOrNull { it.name == held } }

    private companion object {
        val OPEN = setOf(StepStatus.RUNNING, StepStatus.WAITING)

        fun seconds(from: OffsetDateTime, to: OffsetDateTime): Long = Duration.between(from, to).seconds.coerceAtLeast(0)

        fun median(values: List<Long>): Long {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }
    }
}
