package io.mszymanski.orknux.server.transfer

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * How far a workspace copy has got, for the page that started it. Issue #572.
 *
 * A large copy takes a while and the mutation answers only at the end, so the
 * page names a key when it asks and reads this while it waits: which kind is
 * being copied and how many of how many. Held in memory, because it is only
 * true while the copy is running on this server - a restart ends the copy too -
 * and forgotten a while after the last word, so an abandoned page leaves
 * nothing behind for long.
 */
@Component
class WorkspaceCopyProgress {

    /** One moment of a copy: the kind under way, how far into it, and overall. */
    data class Step(val kind: String, val done: Int, val total: Int, val overallDone: Int, val overallTotal: Int)

    private data class Held(val step: Step, val at: Instant)

    private val held = ConcurrentHashMap<String, Held>()

    fun report(key: String, step: Step) {
        forgetOld()
        held[key] = Held(step, Instant.now())
    }

    fun read(key: String): Step? = held[key]?.step

    fun forget(key: String) {
        held.remove(key)
    }

    private fun forgetOld() {
        val cutoff = Instant.now().minus(KEPT)
        held.entries.removeIf { it.value.at.isBefore(cutoff) }
    }

    private companion object {
        /** Long enough to outlast any one component; a copy reports after each. */
        val KEPT: Duration = Duration.ofMinutes(10)
    }
}
