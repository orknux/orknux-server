package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.AgentStepDrafts
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * A research's report while its writer is writing it, for the page to show.
 *
 * The Write step's own output is still the whole report, saved when the step
 * ends - the seeded node does not stream, so Save is never handed a draft in
 * pieces. This is the other copy: the words as the model writes them, kept in
 * memory by research and answered as the research's `draft` while it runs.
 * Memory and not a column because it is worth nothing once the step has ended
 * - the report it becomes is on the row - and a column written forty times a
 * second is a lock taken forty times a second. A server that stops loses it,
 * and the step that wrote it with it.
 */
@Component
class ResearchDrafts(
    private val executions: WorkflowExecutionRepository,
    private val researches: ResearchRepository,
    private val mapper: ObjectMapper,
) : AgentStepDrafts {

    private val held = ConcurrentHashMap<Long, StringBuffer>()

    /** A seeded mode's Write step, on a run a research is on: its answer is the report. */
    override fun follow(step: ExecutionStep): ((String) -> Unit)? {
        if (step.nodeKey != ResearchModes.WRITE) return null
        val run = executions.findByIdOrNull(step.executionId) ?: return null
        val id = ResearchRuns.idIn(run.input, mapper) ?: return null
        val research = researches.findByIdOrNull(id)
            ?.takeIf { it.workspaceId == run.workspaceId && it.executionId == run.id }
            ?: return null
        val draft = StringBuffer()
        held[requireNotNull(research.id)] = draft
        return { piece -> draft.append(piece) }
    }

    /**
     * The writer revising the report for the reviewer: the draft is started
     * again and filled as the revision is written, so the page shows the
     * report being rewritten rather than the one being replaced.
     */
    fun revising(researchId: Long?): (String) -> Unit {
        if (researchId == null) return {}
        val draft = StringBuffer()
        held[researchId] = draft
        return { piece -> draft.append(piece) }
    }

    /** What the writer has written so far, or null where nothing is being written. */
    fun of(researchId: Long): String? = held[researchId]?.toString()?.takeIf { it.isNotBlank() }

    /** The research's run has ended, or it is starting again: what was being written is gone. */
    fun forget(researchId: Long) {
        held.remove(researchId)
    }
}
