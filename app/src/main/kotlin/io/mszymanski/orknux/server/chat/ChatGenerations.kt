package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.Hangup
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The answers being written right now, so one can be stopped from outside the
 * request that is writing it.
 *
 * A text chat that is left goes on being answered - that is the whole of issue
 * #335, and it is why a lost reader no longer stops the model there. So stopping
 * on purpose needs a door of its own: the Stop button closes the stream *and*
 * calls the interrupt endpoint, and this is what that endpoint reaches through
 * to the [Hangup] the answering thread is holding. Without it, Stop in an
 * asynchronous chat would close the browser's connection and leave the model
 * writing - which is exactly the waste #299 set out to end.
 *
 * Keyed by chat rather than by turn: a person presses Stop on a chat, not on a
 * turn id they have never seen, and the rare two-in-flight case is stopped
 * together, which is what somebody pressing Stop meant. A [Hangup] removes
 * itself when its turn ends, so what is held here is only ever live ones.
 */
@Component
class ChatGenerations {

    private val inFlight = ConcurrentHashMap<Long, MutableSet<Hangup>>()

    /** Notes that a turn on this chat is being answered, until [release]. */
    fun register(chatId: Long, hangup: Hangup) {
        inFlight.compute(chatId) { _, held ->
            (held ?: ConcurrentHashMap.newKeySet()).apply { add(hangup) }
        }
    }

    /** Forgets a turn that has ended, however it ended. */
    fun release(chatId: Long, hangup: Hangup) {
        inFlight.computeIfPresent(chatId) { _, held ->
            held.remove(hangup)
            held.ifEmpty { null }
        }
    }

    /** Whether a turn on this chat is being answered right now. */
    fun answering(chatId: Long): Boolean = inFlight[chatId]?.isNotEmpty() == true

    /**
     * Stops whatever is being answered on this chat, if anything is.
     *
     * Only hangs up: the answering thread reads that on its next piece and puts
     * itself back the way an interrupted turn is, the same path a voice chat's
     * lost reader already takes. Nothing here waits for that to happen - the
     * caller is a button press, not the turn.
     */
    fun interrupt(chatId: Long) {
        inFlight[chatId]?.forEach { it.hangUp() }
    }
}
