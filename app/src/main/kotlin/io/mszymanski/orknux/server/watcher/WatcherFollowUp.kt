package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AnswerFinished
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.chat.DateTools
import io.mszymanski.orknux.server.chat.NoteTools
import io.mszymanski.orknux.server.chat.ScratchpadTools
import io.mszymanski.orknux.server.chat.TimerTools
import io.mszymanski.orknux.server.chat.TodoTools
import io.mszymanski.orknux.server.chat.sheds
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.llm.SessionThinking
import io.mszymanski.orknux.server.task.TaskRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A watcher that ends after the workflow step that set it has finished wakes its
 * agent for a turn of its own. Issue #618.
 *
 * A workflow step used to park for as long as its agent had a watcher running,
 * the way it parks for an ask - and a watcher may run for a week, so the run sat
 * suspended and the node after it never got the answer. The step completes now,
 * and this is the half that keeps the watcher worth setting: when it fires, times
 * out or is stopped, the agent is woken in the session it set it from, outside
 * the graph, and acts with its own tools - replies in the thread it was working
 * in, files an issue. What it writes as prose goes into the session and nowhere
 * else, and the system turn tells it so.
 *
 * The third of the session's wakers, and only for what the other two do not own:
 * a chat's session is [io.mszymanski.orknux.server.chat.ChatWake]'s, a task's is
 * the task's, and a step parked on the session - or mid-turn in it, which reads
 * the inbox itself - is [io.mszymanski.orknux.server.llm.SessionWake]'s. Only for
 * a watcher's event, because a watcher is the one thing in an inbox that names the
 * agent to wake; an ask or a timer still holds its step, which is waiting for it.
 *
 * One turn at a time per session, off the thread that posted, as a chat's is.
 */
@Component
class WatcherFollowUp(
    private val watchers: WatcherRepository,
    private val service: ObjectProvider<WatcherService>,
    private val inbox: SessionInbox,
    private val steps: ExecutionStepRepository,
    private val chats: ChatSessionRepository,
    private val tasks: TaskRepository,
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    private val conversation: ObjectProvider<AgentConversation>,
    private val sessions: LlmSessionRecorder,
    private val budgets: SessionMemoryBudgets,
    private val notes: NoteTools,
    private val scratchpads: ScratchpadTools,
    private val todos: TodoTools,
    private val dates: DateTools,
    private val timers: TimerTools,
    private val watcherTools: WatcherTools,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Sessions a follow-up turn is running in, so a second event does not start another. */
    private val running = ConcurrentHashMap.newKeySet<Long>()

    private val turns: ExecutorService =
        Executors.newCachedThreadPool { Thread(it, "watcher-follow-up").apply { isDaemon = true } }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun due(event: SessionEventDue) = wake(event.sessionId)

    /**
     * And when a step in the session finishes: a watcher that ended while the
     * step was mid-turn, after its last look at the inbox, found the session
     * busy and left its news for a turn that is not coming.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun finished(event: io.mszymanski.orknux.workflow.execution.StepFinished) = wake(event.sessionId)

    private fun wake(session: Long) {
        if (!owned(session)) return
        if (!running.add(session)) return
        turns.execute {
            try {
                follow(session)
            } catch (failure: Exception) {
                log.warn("Session {} could not be followed up after its watcher: {}", session, failure.message)
            } finally {
                running.remove(session)
            }
        }
    }

    /**
     * Whether this session is this class's to wake: a watcher's event unread in
     * it, and nothing else that would read it - no chat, no task, no step mid-turn
     * or parked on it.
     */
    fun owned(session: Long): Boolean =
        inbox.pending(session, SessionEventKind.WATCHER) &&
            chats.findFirstByLlmSessionId(session) == null &&
            tasks.findFirstBySessionId(session) == null &&
            !steps.existsBySessionIdAndStatusIn(session, BUSY)

    /** The turn itself, on this thread. Public for a test that wants it now. */
    fun follow(session: Long): ChatCompletion? {
        // The one that looked last: what it posted - a firing, a timeout or a
        // look it was asked for - is what is waiting in the inbox.
        val watcher = watchers.findFirstBySessionIdAndAgentIdIsNotNullOrderByLastCheckedAtDescIdDesc(session)
            ?: return null
        val agent = service.getObject().agentFor(watcher)?.takeIf { it.enabled }
            ?: agents.findByIdOrNull(requireNotNull(watcher.agentId))?.takeIf { it.enabled }
            ?: return null.also { log.info("Watcher #{}'s agent is gone or switched off; nobody to wake", watcher.id) }
        val modelId = agent.modelId
            ?: return null.also { log.info("{} has no model, so its watcher #{} wakes nobody", agent.name, watcher.id) }

        val budget = budgets.budget(agent.memoryShare, agent.workspaceId, modelId)
        val system = listOfNotNull(
            briefing.of(agent),
            FOLLOW_UP,
            notes.recalled(session).takeIf { it.isNotBlank() },
            todos.recalled(session).takeIf { it.isNotBlank() },
        ).joinToString("\n\n")
        // The watcher's own message is not added here: the conversation takes it
        // from the inbox as the first thing the model reads, and marks it read.
        val said = buildList {
            add(ChatTurn("system", system))
            addAll(sessions.remembered(session, budget))
            addAll(sessions.recalled(session, budget))
        }
        val shed = sheds(
            notes.shed(session, agent.name),
            scratchpads.shed(session),
            todos.shed(session),
            dates.shed(),
            timers.shed(session),
            watcherTools.shed(agent, session),
        )

        sessions.note(session, "${agent.name} was woken by watcher #${watcher.id}, after the step that set it had finished.")
        val thinking = SessionThinking(session, agent.name, sessions)
        return try {
            conversation.getObject().answer(modelId, agent, said, session, shed = shed, watch = thinking)
        } catch (finished: AnswerFinished) {
            // finish_answer is not lent here, but a shed may still end the round;
            // nothing waits on what it said.
            null
        } finally {
            thinking.settle()
        }
    }

    companion object {
        /** A step in either of these reads the inbox by itself, or is woken by [io.mszymanski.orknux.server.llm.SessionWake]. */
        private val BUSY = listOf(StepStatus.RUNNING, StepStatus.WAITING)

        /**
         * What the woken agent is told about where it is. Without it the
         * briefing reads as though a person or a workflow were waiting on the
         * answer, and the model writes its news into a reply nobody receives.
         */
        const val FOLLOW_UP =
            "You were woken because a watcher you set has ended; what it found is the next message. " +
                "The step that set it has already finished and nobody is waiting on this turn: nothing you " +
                "write as an answer reaches anyone. If somebody should hear about it, tell them with your " +
                "tools - the same way you would have replied when you set the watcher - and then stop."
    }
}
