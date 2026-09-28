package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.workflow.execution.ExecutionEngine
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Wakes whatever is parked on a session when something is due there.
 *
 * The inbox publishes and does not call, because it knows nothing of what owns
 * a session; this is the half that does. A workflow step parked on the session
 * has its wait cut short, and the turn it runs again reads the inbox first. A
 * run that is not parked has nothing to cut short, and waking it is a no-op.
 *
 * After the commit, so the event the woken turn goes looking for is there.
 */
@Component
class SessionWake(
    private val steps: ExecutionStepRepository,
    private val executions: WorkflowExecutionRepository,
    private val engine: ExecutionEngine,
    /** Through a provider: the task service reaches the conversation, which reaches the inbox. */
    private val tasks: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.task.TaskService>,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun due(event: SessionEventDue) {
        val ids = steps.executionIdsForSession(event.sessionId).distinct()
        executions.findAllById(ids)
            .filter { it.status == ExecutionStatus.RUNNING }
            .forEach { engine.wake(requireNotNull(it.id)) }
        // And a task whose conversation it is: nudged, or reopened; see TaskService.wake.
        tasks.getObject().wake(event.sessionId)
    }
}
