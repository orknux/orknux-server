package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AnswerFinished
import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.chat.NoteTools
import io.mszymanski.orknux.server.chat.SessionSheds
import io.mszymanski.orknux.server.chat.sheds
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.llm.SessionThinking
import io.mszymanski.orknux.server.task.TaskRepository
import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.ResumePoint
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.retryPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * A watcher that ends after the workflow step that set it has finished wakes its
 * agent for a turn of its own. Issue #618.
 *
 * A workflow step used to park for as long as its agent had a watcher running,
 * the way it parks for an ask - and a watcher may run for a week, so the run sat
 * suspended and the node after it never got the answer. The step completes now,
 * and this is the half that keeps the watcher worth setting: when it fires, times
 * out or is stopped, the agent is woken in the session it set it from, outside
 * the graph. What it answers is handed on the way its answer from the step was:
 * the run carries on past that step with the new answer, and only the nodes it
 * leads to run again - so a reply that reached a Slack thread through the node
 * after the agent reaches it again. It used to be told that nothing it wrote
 * reached anyone and to post with its own tools, and it answered in prose all
 * the same, into a session nobody in Slack could see. `finish_answer` is lent
 * for the turn that has nothing to tell anyone.
 *
 * The third of the session's wakers, and only for what the others do not own:
 * a chat's session is [io.mszymanski.orknux.server.chat.ChatWake]'s, a task's is
 * the task's, an ask's is [io.mszymanski.orknux.server.chat.SubagentWake]'s, and a step parked on the session - or mid-turn in it, which reads
 * the inbox itself - is [io.mszymanski.orknux.server.llm.SessionWake]'s. Only for
 * a watcher's event, because a watcher is the one thing in an inbox that names the
 * agent to wake; an ask still holds its step, which is waiting for it.
 *
 * And for a reminder the agent set with `timer_set` in a workflow step. The
 * step used to park on one as on an ask, so the answer the agent had already
 * written reached the node after it - a Slack reply - only when the reminder
 * came up, fifteen hours later in the case that showed it. The step completes
 * now, and the reminder coming up wakes the agent here the way a watcher does,
 * its answer carried on past the step. A reminder names nobody, so the agent
 * woken is the one whose step ran in the session last - the one that set it,
 * unless two agents share the conversation. Only in a session with no chat on
 * it: there the reminder was always [io.mszymanski.orknux.server.chat.ChatWake]'s,
 * and a chat's own reminders stay where the person is.
 *
 * One turn at a time per session, off the thread that posted, as a chat's is.
 *
 * A turn the model could not answer for a reason that may pass - a rate limit,
 * an outage - is asked again by the retry policy of the step that set the
 * watcher, as that step's own turn would have been. It was answered once and
 * dropped, so the agent a watcher woke into a rate limit never acted on what the
 * watcher found.
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
    /** The tools every turn in a session is lent; see [SessionSheds]. */
    private val sessionSheds: SessionSheds,
    private val finishing: FinishAnswerTools,
    private val expressions: NodeExpressions,
    private val runs: ObjectProvider<ExecutionService>,
    /** What the session's model was sent, sent again as it was; see [SessionPrompt]. */
    private val prompt: io.mszymanski.orknux.server.llm.SessionPrompt,
    /** Which sessions are an ask's, and so [io.mszymanski.orknux.server.chat.SubagentWake]'s. */
    private val held: io.mszymanski.orknux.server.llm.LlmSessionRepository,
    /** The working timezone, which a retry's time is said in. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
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
                followRetrying(session)
            } catch (failure: Exception) {
                log.warn("Session {} could not be followed up after its watcher: {}", session, failure.message)
            } finally {
                running.remove(session)
            }
        }
    }

    /**
     * Whether this session is this class's to wake: a watcher's event unread in
     * it, and nothing else that would read it - no task, no step mid-turn or
     * parked on it, and no chat unless a run wrote here too. A run's watcher
     * wakes the run's agent, whose answer goes on to the run's next node; a chat
     * opened on that conversation with "Continue in chat" does not take it over.
     * Nor an ask's session: no step is in it, so the answer went nowhere, and
     * the agent that asked is where it belongs - see
     * [io.mszymanski.orknux.server.chat.SubagentWake].
     */
    fun owned(session: Long): Boolean =
        (inbox.pending(session, SessionEventKind.WATCHER) || reminded(session)) &&
            held.findByIdOrNull(session)?.parentSessionId == null &&
            (chats.findFirstByLlmSessionId(session) == null || steps.existsBySessionId(session)) &&
            tasks.findFirstBySessionId(session) == null &&
            !steps.existsBySessionIdAndStatusIn(session, BUSY)

    /**
     * Whether a reminder a run's agent set - a timer, or a schedule's run - has come up here - due, not merely
     * set, since a step finishing with one still to come has nothing to wake
     * for yet - in a session that is a run's and not a chat's.
     */
    private fun reminded(session: Long): Boolean =
        // A schedule's run is a reminder that comes back, and wakes the run's agent the same way.
        (inbox.due(session, SessionEventKind.TIMER) || inbox.due(session, SessionEventKind.SCHEDULE)) &&
            steps.existsBySessionId(session) &&
            chats.findFirstByLlmSessionId(session) == null

    /** Whether the turn is for a reminder alone; a watcher's news comes first, being the one that names an agent. */
    private fun remindedOnly(session: Long): Boolean =
        !inbox.pending(session, SessionEventKind.WATCHER) && reminded(session)

    /**
     * The turn, and the turns again that the step's retry policy allows.
     *
     * The watcher's message is not lost between attempts: the first one took it
     * from the inbox and wrote it into the session, which is what every later
     * attempt is built from. Waited out on this thread, which is the follow-up's
     * own and not a worker's; the session stays marked running meanwhile, so
     * another event does not start a second turn beside it.
     */
    fun followRetrying(session: Long, pause: (Duration) -> Unit = { Thread.sleep(it.toMillis()) }): ChatCompletion? {
        val policy = steps.findFirstBySessionIdOrderByIdDesc(session)?.retryPolicy()
        val began = System.nanoTime()
        var spent = 1
        // Decided once: the first attempt takes the inbox, so a retry could no longer tell.
        val reminder = remindedOnly(session)
        var answer = follow(session, reminder = reminder)
        while (answer is ChatCompletion.Failed && !answer.permanent && policy != null && spent < policy.attempts) {
            val wait = policy.waitAfter(spent, Random)
            val budget = policy.budget
            if (budget != null && Duration.ofNanos(System.nanoTime() - began) + wait > budget) break
            log.info("Session {} follow-up failed ({}); trying again in {}s", session, answer.reason, wait.toSeconds())
            sessions.note(session, retryNote(answer.reason, spent + 1, policy.attempts, wait, Instant.now(), zone()))
            pause(wait)
            spent++
            answer = follow(session, again = true, reminder = reminder)
        }
        return answer
    }

    /** The turn itself, on this thread. Public for a test that wants it now. */
    fun follow(session: Long, again: Boolean = false, reminder: Boolean = remindedOnly(session)): ChatCompletion? {
        // The one that looked last: what it posted - a firing, a timeout or a
        // look it was asked for - is what is waiting in the inbox.
        val watcher = if (reminder) {
            null
        } else {
            watchers.findFirstBySessionIdAndAgentIdIsNotNullOrderByLastCheckedAtDescIdDesc(session) ?: return null
        }
        val woken = watcher?.let { "watcher #${it.id}" } ?: "a reminder"
        val agent = if (watcher != null) {
            service.getObject().agentFor(watcher)?.takeIf { it.enabled }
                ?: agents.findByIdOrNull(requireNotNull(watcher.agentId))?.takeIf { it.enabled }
        } else {
            steps.findFirstBySessionIdAndAgentIdIsNotNullOrderByIdDesc(session)
                ?.let { agents.findByIdOrNull(requireNotNull(it.agentId)) }?.takeIf { it.enabled }
        } ?: return null.also { log.info("Session {}: the agent {} would wake is gone or switched off", session, woken) }
        val modelId = agent.modelId
            ?: return null.also { log.info("{} has no model, so {} wakes nobody", agent.name, woken) }

        val budget = budgets.budget(agent.memoryShare, agent.workspaceId, modelId)
        // The briefing alone: notes and the plan go at the head of a
        // conversation started from the log, not into the system turn, where
        // writing one changed every message after it. See [SessionPrompt].
        val system = briefing.of(agent).orEmpty()
        val sent = prompt.history(session, budgets.promptCeiling(agent.memoryShare, agent.workspaceId, modelId))
        // The step the agent set the watcher from, which is where its answer goes.
        val step = agent.id?.let { steps.findFirstBySessionIdAndAgentIdOrderByIdDesc(session, it) }
        val mayFinish = agent.finishAccess && step?.outputObjectId == null
        /*
         * The watcher's message is taken here rather than by the conversation,
         * so that what the agent is told about this turn comes after it - the
         * last thing it reads. In the system prompt it was read past: the agent
         * answered "Copilot is still in_progress; watcher #66 stays active",
         * which then went to the Slack thread as though it were news. Taking
         * writes it into the session, so a retry reads it from there; the
         * reminder is said again on every attempt and recorded on none.
         */
        val history = sent ?: (
            listOfNotNull(sessionSheds.standing(session)?.let { ChatTurn("user", it) }) +
                sessions.remembered(session, budget) + sessions.recalled(session, budget)
            )
        val arrived = inbox.take(session)
        val said = buildList {
            add(ChatTurn("system", system))
            addAll(history)
            arrived.forEach { add(ChatTurn("user", it)) }
            add(ChatTurn("user", followUp(mayFinish, reminder)))
        }
        // The tools every turn in a session is lent, and the ending, which is this door's own.
        val shed = sheds(
            sessionSheds.of(agent, session),
            finishing.shed(granted = mayFinish),
        )

        if (!again) {
            val by = if (watcher != null) "watcher #${watcher.id}" else "a reminder it set"
            sessions.note(session, "${agent.name} was woken by $by, after the step that set it had finished.")
        }
        val thinking = SessionThinking(session, agent.name, sessions)
        // Everything it writes on the way, handed on with its last answer, as a step does.
        val spoken = mutableListOf<String>()
        fun all(last: String) = (spoken + last).filter { it.isNotBlank() }.joinToString("\n\n") { it.trim() }
        val answer = try {
            conversation.getObject().answer(
                modelId, agent, said, session, shed = shed, watch = thinking, spoke = { spoken += it },
            )
        } catch (finished: AnswerFinished) {
            // Nothing to tell anyone, or nothing more than what it wrote and passed.
            all(finished.answer).takeIf { it.isNotBlank() }?.let { handOn(session, step, it) }
            return null
        } finally {
            thinking.settle()
        }
        if (answer is ChatCompletion.Answered) handOn(session, step, all(answer.content))
        return answer
    }

    /**
     * Carries the step's run on past it with [answer], so it reaches whatever
     * the step's own answer reached. Where it cannot, the session says why:
     * the answer is in the transcript either way, and somebody reading it
     * should not have to guess why it went no further.
     */
    private fun handOn(session: Long, step: ExecutionStep?, answer: String) {
        if (answer.isBlank()) return
        if (step == null) {
            sessions.note(session, "This answer went no further: no workflow step of this agent's is in this session.")
            return
        }
        if (step.outputObjectId != null) {
            sessions.note(session, "This answer went no further: ${step.name} answers in a fixed shape, and prose is not it.")
            return
        }
        val service = runs.getObject()
        try {
            val previous = service.execution(step.executionId) ?: return
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
                    ),
                ),
            )
            sessions.note(session, "This answer was handed on past ${step.name} in run #${carried.id}.")
        } catch (refused: RuntimeException) {
            log.warn("Session {} could not hand its answer on past {}: {}", session, step.nodeKey, refused.message)
            sessions.note(session, "This answer went no further: ${refused.message}.")
        }
    }

    private fun zone(): ZoneId = installation.workingZone()

    companion object {

        /**
         * The note a retry leaves in the transcript. The date and time it will
         * happen, not only the wait: "in 125s" read an hour later says nothing
         * about whether it already has.
         */
        fun retryNote(reason: String, attempt: Int, of: Int, wait: Duration, now: Instant, zone: ZoneId): String =
            "The turn could not be answered ($reason); attempt $attempt of $of at " +
                "${io.mszymanski.orknux.workflow.execution.retryAt(now, wait, zone)}, in ${wait.toSeconds()}s."

        /** A step in either of these reads the inbox by itself, or is woken by [io.mszymanski.orknux.server.llm.SessionWake]. */
        private val BUSY = listOf(StepStatus.RUNNING, StepStatus.WAITING)

        /**
         * What the woken agent is told about this turn, after the watcher's
         * message. The person is the reader: a watcher wakes the agent far more
         * often than there is news, and a status line about a watcher number
         * posted into their thread every few minutes is the agent talking to
         * itself in public.
         */
        fun followUp(mayFinish: Boolean, reminder: Boolean = false): String =
            "This turn was started by ${if (reminder) "a reminder you set" else "your watcher"}, not by a person. " +
                "Whatever you answer is sent to the " +
                "person you were answering before, in the same place, so write it for them: what changed and " +
                "what it means for them. Leave your own bookkeeping out of it - watcher numbers, which tool " +
                "you called, what you will look at next - and keep that with ${NoteTools.NOTE}. Say it the " +
                "way a colleague would: \"The build failed in the tests - an import is missing; I've asked " +
                "for a fix and will tell you when it's green.\" " +
                if (mayFinish) {
                    "When something they would want to hear has happened - it finished, it failed, it needs " +
                        "them, or a real step forward - tell them. When nothing has, or you have already told " +
                        "them with your tools, end with ${FinishAnswerTools.FINISH} and no answer, and nothing " +
                        "is sent."
                } else {
                    "When nothing they would want to hear has happened, say so in one short sentence."
                }
    }
}
