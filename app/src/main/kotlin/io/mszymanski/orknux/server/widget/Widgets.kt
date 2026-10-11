package io.mszymanski.orknux.server.widget

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/**
 * A chat's widgets: made, changed and removed by its agent through
 * [WidgetTools] and by the person through [WidgetAPI], read the same way by
 * both.
 *
 * A timer the agent asked to be woken by leaves an entry in the session's
 * inbox for the moment it ends - the same inbox and the same wake-up a
 * `timer_set` reminder uses - and calls it off when the timer is paused, reset
 * or removed first, so a timer that stopped does not ring.
 */
@Service
class Widgets(
    private val widgets: ChatWidgetRepository,
    private val settings: InstallationSettings,
    private val inbox: SessionInbox,
    kinds: List<WidgetKind>,
) {
    private val byKind = kinds.associateBy { it.kind }

    /** The clock widgets read; a test moves it. */
    internal var clock: Clock = Clock.systemDefaultZone()

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    fun list(chatId: Long): List<WidgetView> = widgets.findByChatSessionIdOrderByIdAsc(chatId).map(::view)

    fun view(widget: ChatWidget): WidgetView {
        val kind = byKind[widget.kind] ?: throw WidgetRefusedException("No widget of kind ${widget.kind} is known here")
        return kind.view(widget, WidgetState.read(widget.state), now())
    }

    @Transactional
    fun startTimer(chat: ChatSession, label: String, seconds: Long, wake: Boolean, by: String): WidgetView {
        val longest = settings.widgetTimerMaxSeconds().toLong()
        if (seconds <= 0) throw WidgetRefusedException("A timer runs for a number of seconds greater than zero")
        if (seconds > longest) throw WidgetRefusedException("A timer can run for at most $longest seconds on this installation")
        if (wake && chat.llmSessionId == null) throw WidgetRefusedException("This chat has no session to wake yet")
        val state = (byKind.getValue(TimerWidget.TIMER) as TimerWidget).started(seconds, wake, now())
        return made(chat, TimerWidget.TIMER, label, state, by)
    }

    @Transactional
    fun startStopwatch(chat: ChatSession, label: String, by: String): WidgetView {
        val state = (byKind.getValue(StopwatchWidget.STOPWATCH) as StopwatchWidget).started(now())
        return made(chat, StopwatchWidget.STOPWATCH, label, state, by)
    }

    private fun made(chat: ChatSession, kind: String, label: String, state: tools.jackson.databind.node.ObjectNode, by: String): WidgetView {
        val chatId = requireNotNull(chat.id)
        val most = settings.widgetsPerChat()
        if (widgets.countByChatSessionId(chatId) >= most) {
            throw WidgetRefusedException("This chat holds $most widgets already, the most it may; remove one first")
        }
        val named = label.trim().take(LABEL_LENGTH).ifEmpty { throw WidgetRefusedException("Give the widget a label") }
        val saved = widgets.save(ChatWidget(chatSessionId = chatId, kind = kind, label = named, state = state.toString(), createdBy = by))
        rearm(chat, saved)
        return view(saved)
    }

    /** One of the kind's actions, on [id] in [chat]. */
    @Transactional
    fun act(chat: ChatSession, id: Long, action: String): WidgetView {
        val widget = owned(chat, id)
        val kind = byKind[widget.kind] ?: throw WidgetRefusedException("No widget of kind ${widget.kind} is known here")
        if (action !in kind.actions) {
            throw WidgetRefusedException("A ${widget.kind.lowercase()} takes ${kind.actions.sorted().joinToString(", ")}, not $action")
        }
        widget.state = kind.act(WidgetState.read(widget.state), action, now()).toString()
        widget.updatedAt = OffsetDateTime.now()
        val saved = widgets.save(widget)
        rearm(chat, saved)
        return view(saved)
    }

    @Transactional
    fun remove(chat: ChatSession, id: Long): WidgetView {
        val widget = owned(chat, id)
        val last = view(widget)
        WidgetState.read(widget.state).path("eventId").takeIf { it.isNumber }?.let { inbox.withdraw(it.asLong()) }
        widgets.delete(widget)
        return last
    }

    private fun owned(chat: ChatSession, id: Long): ChatWidget =
        widgets.findByIdOrNull(id)?.takeIf { it.chatSessionId == chat.id } ?: throw ChatWidgetNotFoundException(id)

    /**
     * The inbox entry that tells the agent a timer ended, as the timer now
     * stands: the old one called off, and a new one left for when it will end,
     * if it is running and the agent asked to be told.
     */
    private fun rearm(chat: ChatSession, widget: ChatWidget) {
        val kind = byKind[widget.kind] ?: return
        val state = WidgetState.read(widget.state)
        state.path("eventId").takeIf { it.isNumber }?.let { inbox.withdraw(it.asLong()) }
        state.remove("eventId")
        val rings = kind.ringsAt(state)
        val session = chat.llmSessionId
        if (rings != null && session != null && rings.isAfter(now())) {
            val id = inbox.posted(session, SessionEventKind.TIMER, "Timer '${widget.label}' finished.", rings)
            state.put("eventId", id)
        }
        widget.state = state.toString()
        widgets.save(widget)
    }

    companion object {
        const val LABEL_LENGTH = 200
    }
}
