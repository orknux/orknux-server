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
    /** What has arrived at a session and not been read; see [parked]. */
    private val inbox: SessionInbox,
    /** Through a provider: the task service reaches the conversation, which reaches the inbox. */
    private val tasks: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.task.TaskService>,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun due(event: SessionEventDue) {
        // Only a run parked on it. One mid-turn reads the inbox between its rounds;
        // waking it too left a wake pending that cut its next wait short - a
        // five-minute backoff that ended after twenty seconds.
        val ids = steps.executionIdsWaitingOnSession(event.sessionId)
        executions.findAllById(ids)
            .filter { it.status == ExecutionStatus.RUNNING }
            .forEach { engine.wake(requireNotNull(it.id)) }
        // And a task whose conversation it is: nudged, or reopened; see TaskService.wake.
        tasks.getObject().wake(event.sessionId)
    }

    /**
     * The other order: what was due landed before the step it was for had
     * parked. [due] looked for a WAITING step then and found none, so the step
     * would sleep out its whole wait with the answer sitting unread in its
     * inbox. Looked at again once the park is written - something due and
     * undelivered means the turn that parked never read it, so wake the run
     * and the turn it runs next reads it first.
     *
     * Seen on SQLite once a copy's transactions stopped starving everything
     * else (#572): the answer to an ask_agent committed a few milliseconds
     * before the asker's park, and the run took its full minute.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun parked(event: io.mszymanski.orknux.workflow.execution.StepParked) {
        val session = event.sessionId ?: return
        val next = inbox.nextDue(session) ?: return
        if (!next.isAfter(java.time.OffsetDateTime.now())) engine.wake(event.executionId)
    }
}
