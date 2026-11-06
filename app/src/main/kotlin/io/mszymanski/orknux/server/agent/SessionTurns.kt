package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.llm.SessionInbox
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * One turn at a time in a session, and what arrives meanwhile joins it.
 *
 * Every Slack mention starts a run of its own, and two in one thread a minute
 * apart - "make a task of this", then "in OKO (Jira)" - were two turns of the
 * same agent in the same session at once. Each read the session as it was when
 * it started, neither saw what the other was doing, and each searched for a
 * duplicate before the other had made its task: two tasks for one request.
 *
 * So the second run does not start a second turn. It leaves its question in
 * the session's inbox, which the running turn reads between rounds as a
 * message that arrived while it worked, and waits for that turn to end. Read,
 * the question was answered there and this run is done. Not read - the turn
 * had made its last call before it arrived - it is taken back, and this run
 * answers it in a turn of its own, after the first and with all of it in the
 * session. Either way there is never more than one turn in a session.
 *
 * Unless the turn running is this same step's own earlier attempt. On
 * Temporal a step is given a fixed time, and an agent still working past it is
 * not stopped: the attempt is written off and the step asked again while the
 * first turn carries on. The retry found the session busy, joined, and
 * completed with nothing - and the first attempt's answer, finished a minute
 * later, went to a step Temporal had stopped listening to. A Slack thread was
 * left without the reply its transcript showed. So a retry that finds its own
 * step's turn waits for that turn and answers with what it answered.
 *
 * Held in this process, which is where the inline engine runs every turn. A
 * Temporal deployment with workers in several processes keeps one turn per
 * session per process, not across them.
 */
@Component
class SessionTurns(private val inbox: SessionInbox) {

    private class Turn(val executionId: Long, val step: String?) {
        /** What the turn came to, or what it threw, for an attempt of the same step that waited on it. */
        val ended = CompletableFuture<Any?>()
    }

    private val running = ConcurrentHashMap<Long, Turn>()

    /** How a run ended up: answered by a turn of its own, or joined to one already going. */
    sealed interface Outcome<T> {
        data class Ran<T>(val result: T) : Outcome<T>
        data class Joined<T>(val executionId: Long) : Outcome<T>
    }

    /**
     * Runs [turn] as the session's only turn, or hands [question] to the one
     * already running. A run with no session is never held: there is nothing
     * for two of them to share.
     *
     * Handed once. Where [delivered] says the session already holds the
     * question - this is the step's retry, or its resume after a restart, and
     * an earlier attempt got it there - it is not left in the inbox again: the
     * running turn has it above, and reading it a second time it was taken for
     * the person writing again. In a Slack thread an hour-old message came back
     * mid-turn that way, after the step's first attempt failed on a rate limit
     * and its retry came round while another run was answering.
     */
    fun <T> oneAtATime(
        session: Long?,
        executionId: Long,
        question: () -> String,
        delivered: () -> Boolean = { false },
        /** Which node of the run this is, so an attempt can know a turn as its own; see the class. */
        step: String? = null,
        turn: () -> T,
    ): Outcome<T> {
        if (session == null) return Outcome.Ran(turn())
        while (true) {
            val mine = Turn(executionId, step)
            val other = running.putIfAbsent(session, mine)
            if (other == null) {
                // Removed before it is said to have ended, so whoever waited on it finds the session free.
                val result = try {
                    turn()
                } catch (failure: Throwable) {
                    running.remove(session, mine)
                    mine.ended.completeExceptionally(failure)
                    throw failure
                }
                running.remove(session, mine)
                mine.ended.complete(result)
                return Outcome.Ran(result)
            }
            if (step != null && other.executionId == executionId && other.step == step) {
                // This step's own earlier attempt, still going: its answer is this attempt's answer.
                @Suppress("UNCHECKED_CAST")
                val theirs = try {
                    other.ended.join() as T
                } catch (failed: java.util.concurrent.CompletionException) {
                    throw failed.cause ?: failed
                }
                return Outcome.Ran(theirs)
            }
            if (delivered()) {
                waitOut(other)
                return Outcome.Joined(other.executionId)
            }
            val left = inbox.postArrived(session, question())
            waitOut(other)
            if (!inbox.withdraw(session, left)) return Outcome.Joined(other.executionId)
            // The turn ended before reading it, so this run takes the session itself - or, if a
            // third run got there first, leaves it with that one.
        }
    }

    /** Until [other] has ended, however it ended: a turn that threw is still a turn that is over. */
    private fun waitOut(other: Turn) {
        runCatching { other.ended.join() }
    }
}
