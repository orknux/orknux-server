package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.ResumePoint
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Hands something an agent said on past its workflow step, to whatever the
 * step's own answer reaches - the Slack reply after it.
 *
 * Two callers. An agent woken after its step had finished - by a watcher, a
 * reminder, an answer it asked for - has nowhere else for its reply to go. And
 * an agent node that streams hands on each message its agent writes beside its
 * tool calls while the step is still running ([partial]), so "I'm checking
 * this" reaches the thread when it is written rather than with the answer.
 *
 * Each is a run of its own, carried on from the step's: only the nodes after
 * the step run, handed this text as the step's output. Where it cannot be
 * handed on, the session says why - the text is in the transcript either way,
 * and somebody reading it should not have to guess why it went no further.
 */
@Component
class StepHandOn(
    private val runs: ObjectProvider<ExecutionService>,
    private val expressions: NodeExpressions,
    private val sessions: LlmSessionRecorder,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** @return whether it was handed on; a caller with another place for it keeps it there when not. */
    fun handOn(session: Long?, step: ExecutionStep?, answer: String, partial: Boolean = false): Boolean {
        if (answer.isBlank()) return false
        if (step == null) {
            session?.let { sessions.note(it, "This answer went no further: no workflow step of this agent's is in this session.") }
            return false
        }
        if (step.outputObjectId != null) {
            session?.let {
                sessions.note(
                    it,
                    if (partial) "This was not handed on as it was written: ${step.name} answers in a fixed shape, so it goes on with that answer."
                    else "This answer went no further: ${step.name} answers in a fixed shape, and prose is not it.",
                )
            }
            return false
        }
        val service = runs.getObject()
        return try {
            val previous = service.execution(step.executionId) ?: return false
            val carried = service.startExecution(
                StartExecutionInput(
                    workspaceId = previous.workspaceId,
                    workflowId = previous.workflowId,
                    // The same event, so the run list does not show a person
                    // pressing something nobody pressed.
                    trigger = previous.trigger,
                    payload = previous.input,
                    // The graph the run used, as a re-run picks it.
                    version = if (previous.trigger == ExecutionTrigger.MANUAL) GraphVersion.DRAFT else GraphVersion.PUBLISHED,
                    resumeFrom = ResumePoint(
                        step.executionId,
                        step.nodeKey,
                        answer = expressions.named(step.outputName, answer),
                        partial = partial,
                    ),
                ),
            )
            session?.let { sessions.note(it, "This was handed on past ${step.name} in run #${carried.id}.") }
            true
        } catch (refused: RuntimeException) {
            log.warn("Session {} could not hand {} on past {}: {}", session, if (partial) "a message" else "its answer", step.nodeKey, refused.message)
            /*
             * A message said while working that cannot go on stays with the
             * step's answer, and arrives with it - which on a Slack thread is
             * minutes later, after a subagent has answered. It used to be kept
             * silently, so a message that came late left nothing saying why.
             */
            session?.let {
                sessions.note(
                    it,
                    if (partial) "This was not handed on as it was written (${refused.message}); it goes on with ${step.name}'s answer."
                    else "This went no further: ${refused.message}.",
                )
            }
            false
        }
    }
}
