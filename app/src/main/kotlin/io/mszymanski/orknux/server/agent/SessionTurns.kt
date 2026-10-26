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
 * Held in this process, which is where the inline engine runs every turn. A
 * Temporal deployment with workers in several processes keeps one turn per
 * session per process, not across them.
 */
@Component
class SessionTurns(private val inbox: SessionInbox) {

    private class Turn(val executionId: Long) {
        val ended = CompletableFuture<Unit>()
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
     */
    fun <T> oneAtATime(session: Long?, executionId: Long, question: () -> String, turn: () -> T): Outcome<T> {
        if (session == null) return Outcome.Ran(turn())
        while (true) {
            val mine = Turn(executionId)
            val other = running.putIfAbsent(session, mine)
            if (other == null) {
                try {
                    return Outcome.Ran(turn())
                } finally {
                    running.remove(session, mine)
                    mine.ended.complete(Unit)
                }
            }
            val left = inbox.postArrived(session, question())
            other.ended.join()
            if (!inbox.withdraw(session, left)) return Outcome.Joined(other.executionId)
            // The turn ended before reading it, so this run takes the session itself - or, if a
            // third run got there first, leaves it with that one.
        }
    }
}
