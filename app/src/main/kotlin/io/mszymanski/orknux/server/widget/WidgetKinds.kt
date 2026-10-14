package io.mszymanski.orknux.server.widget

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.OffsetDateTime

/**
 * One kind of widget: how its state changes and how it reads at a moment.
 *
 * The one thing a new kind needs, as an [io.mszymanski.orknux.server.interaction.InteractionHandler]
 * is for a new kind of question: [Widgets] keeps the rows, the tools and the
 * page call into the kind by its name, and nothing else knows its shape.
 */
interface WidgetKind {
    val kind: String

    /** What [act] takes for this kind; anything else is refused before it is asked. */
    val actions: Set<String>

    /** [state] after [action] at [now]. Throws [WidgetRefusedException] for what cannot be done. */
    fun act(state: ObjectNode, action: String, now: OffsetDateTime): ObjectNode

    /** The widget as it reads at [now]. */
    fun view(widget: ChatWidget, state: JsonNode, now: OffsetDateTime): WidgetView

    /** When the agent is to be told this widget finished, or null for never. */
    fun ringsAt(state: JsonNode): OffsetDateTime? = null
}

internal object WidgetState {
    val json: JsonMapper = JsonMapper.builder().build()

    fun read(text: String): ObjectNode = json.readTree(text) as? ObjectNode ?: json.createObjectNode()

    fun moment(node: JsonNode, field: String): OffsetDateTime? =
        node.path(field).takeIf { it.isString }?.stringValue()?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
}

const val PAUSE = "pause"
const val RESUME = "resume"
const val RESET = "reset"
const val LAP = "lap"

/**
 * A countdown. Runs from when it is started; paused, it keeps what was left.
 *
 * State: `durationSeconds`, `endsAt` while running, `remainingSeconds` while
 * paused, `wake`, `sound` where the agent chose for this timer whether it
 * sounds - absent, it follows the workspace - and `eventId` - the inbox entry
 * that tells the agent it ended, called off when the timer is paused or
 * removed before it rings.
 */
@Component
class TimerWidget : WidgetKind {
    override val kind = TIMER
    override val actions = setOf(PAUSE, RESUME, RESET)

    fun started(seconds: Long, wake: Boolean, now: OffsetDateTime, sound: Boolean? = null): ObjectNode =
        WidgetState.json.createObjectNode()
            .put("durationSeconds", seconds)
            .put("endsAt", now.plusSeconds(seconds).toString())
            .put("wake", wake)
            .also { state -> sound?.let { state.put("sound", it) } }

    private fun remaining(state: JsonNode, now: OffsetDateTime): Long {
        val endsAt = WidgetState.moment(state, "endsAt")
        return if (endsAt != null) {
            // Whole seconds, rounded up: a timer with 200 ms left has not finished.
            maxOf(0, (Duration.between(now, endsAt).toMillis() + 999) / 1000)
        } else {
            state.path("remainingSeconds").asLong(state.path("durationSeconds").asLong())
        }
    }

    override fun act(state: ObjectNode, action: String, now: OffsetDateTime): ObjectNode {
        val left = remaining(state, now)
        val running = WidgetState.moment(state, "endsAt") != null && left > 0
        when (action) {
            PAUSE -> {
                if (!running) throw WidgetRefusedException("That timer is not running")
                state.remove("endsAt")
                state.put("remainingSeconds", left)
            }
            RESUME -> {
                if (running) throw WidgetRefusedException("That timer is running already")
                if (left <= 0) throw WidgetRefusedException("That timer has finished; reset it to run it again")
                state.put("endsAt", now.plusSeconds(left).toString())
                state.remove("remainingSeconds")
            }
            RESET -> {
                // Back to its full length, stopped: resume starts it again.
                state.remove("endsAt")
                state.put("remainingSeconds", state.path("durationSeconds").asLong())
            }
            else -> throw WidgetRefusedException("A timer can be paused, resumed or reset")
        }
        return state
    }

    override fun view(widget: ChatWidget, state: JsonNode, now: OffsetDateTime): WidgetView {
        val left = remaining(state, now)
        val endsAt = WidgetState.moment(state, "endsAt")
        return WidgetView(
            id = requireNotNull(widget.id),
            kind = kind,
            label = widget.label,
            createdBy = widget.createdBy,
            running = endsAt != null && left > 0,
            done = left <= 0,
            asOf = now.toString(),
            durationSeconds = state.path("durationSeconds").asLong(),
            endsAt = endsAt?.toString(),
            remainingSeconds = left,
            wake = state.path("wake").asBoolean(false),
            sound = state.path("sound").takeIf { it.isBoolean }?.asBoolean(),
        )
    }

    override fun ringsAt(state: JsonNode): OffsetDateTime? =
        if (state.path("wake").asBoolean(false)) WidgetState.moment(state, "endsAt") else null

    companion object {
        const val TIMER = "TIMER"
    }
}

/**
 * A stopwatch. Counts up from when it is started; paused, it keeps what it had.
 *
 * State: `startedAt` while running, `accumulatedMillis` from the runs before
 * it, and `laps` - the elapsed time each lap was taken at.
 */
@Component
class StopwatchWidget : WidgetKind {
    override val kind = STOPWATCH
    override val actions = setOf(PAUSE, RESUME, RESET, LAP)

    fun started(now: OffsetDateTime): ObjectNode =
        WidgetState.json.createObjectNode().put("startedAt", now.toString()).put("accumulatedMillis", 0L).also { it.putArray("laps") }

    private fun elapsed(state: JsonNode, now: OffsetDateTime): Long {
        val since = WidgetState.moment(state, "startedAt")
        val before = state.path("accumulatedMillis").asLong(0)
        return before + (since?.let { maxOf(0, Duration.between(it, now).toMillis()) } ?: 0)
    }

    override fun act(state: ObjectNode, action: String, now: OffsetDateTime): ObjectNode {
        val running = WidgetState.moment(state, "startedAt") != null
        val elapsed = elapsed(state, now)
        when (action) {
            PAUSE -> {
                if (!running) throw WidgetRefusedException("That stopwatch is not running")
                state.remove("startedAt")
                state.put("accumulatedMillis", elapsed)
            }
            RESUME -> {
                if (running) throw WidgetRefusedException("That stopwatch is running already")
                state.put("startedAt", now.toString())
            }
            RESET -> {
                state.remove("startedAt")
                state.put("accumulatedMillis", 0L)
                state.putArray("laps")
            }
            LAP -> {
                val laps = (state.get("laps") as? tools.jackson.databind.node.ArrayNode) ?: state.putArray("laps")
                laps.add(elapsed)
            }
            else -> throw WidgetRefusedException("A stopwatch can be paused, resumed, reset or lapped")
        }
        return state
    }

    override fun view(widget: ChatWidget, state: JsonNode, now: OffsetDateTime): WidgetView {
        val since = WidgetState.moment(state, "startedAt")
        return WidgetView(
            id = requireNotNull(widget.id),
            kind = kind,
            label = widget.label,
            createdBy = widget.createdBy,
            running = since != null,
            done = false,
            asOf = now.toString(),
            startedAt = since?.toString(),
            accumulatedMillis = state.path("accumulatedMillis").asLong(0),
            elapsedMillis = elapsed(state, now),
            laps = state.path("laps").values().map { it.asLong() },
        )
    }

    companion object {
        const val STOPWATCH = "STOPWATCH"
    }
}
