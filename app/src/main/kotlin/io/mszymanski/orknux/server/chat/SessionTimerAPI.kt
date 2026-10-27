package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * A session's timers, read beside its transcript: the reminders its agent set
 * with `timer_set`, and a chat timer widget's wake-up, which goes into the same
 * inbox. Read-only, like the to-do list - a reminder is the agent's, and it
 * calls one off with `timer_cancel`.
 *
 * Without this a reminder was invisible from the page of the session it would
 * wake: the transcript shows the call that set it and nothing until it rings.
 */
@Controller
class SessionTimerAPI(
    private val events: SessionEventRepository,
    /** Whose chat a session is; somebody else's is answered as not there. */
    private val visibility: io.mszymanski.orknux.server.llm.SessionVisibility,
) {

    /** Waiting and rung alike, newest first; a hundred at most. */
    @QueryMapping
    fun sessionTimers(@Argument sessionId: Long): List<SessionTimerView> {
        visibility.requireReadable(sessionId)
        return events.findTop100BySessionIdAndKindOrderByCreatedAtDescIdDesc(sessionId, SessionEventKind.TIMER).map {
            SessionTimerView(
                id = requireNotNull(it.id),
                note = TimerTools.noteOf(it),
                createdAt = it.createdAt.toString(),
                dueAt = it.dueAt.toString(),
                rangAt = it.deliveredAt?.toString(),
            )
        }
    }
}

data class SessionTimerView(
    val id: Long,
    val note: String,
    val createdAt: String,
    val dueAt: String,
    val rangAt: String?,
)
