package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How many pictures this server is drawing at once, held against the limit
 * Admin > Settings sets - shared by everything that asks an image or upscale
 * model for work. A video being upscaled is that model's work too, frame after
 * frame, so it takes one of these for as long as it runs rather than having a
 * limit of its own the images' would not know about.
 *
 * Whoever waits for a slot says how to be told one is free: each queue's
 * dispatch, which takes what the limit then allows, oldest first in its own
 * queue.
 */
@Component
class DesignDrawSlots(private val settings: InstallationSettings) {

    private var taken = 0

    private val waiting = CopyOnWriteArrayList<() -> Unit>()

    /** A slot, if the limit leaves one. */
    @Synchronized
    fun take(): Boolean {
        if (taken >= settings.imageDrawsAtOnce()) return false
        taken++
        return true
    }

    /** A slot taken and not used - the work was claimed elsewhere first. Nobody is told: nothing has finished. */
    @Synchronized
    fun giveBack() {
        if (taken > 0) taken--
    }

    /** Work finished: its slot is given back, and every queue waiting for one is told. */
    fun release() {
        giveBack()
        waiting.forEach { dispatch -> runCatching { dispatch() } }
    }

    /** [dispatch] is called whenever a slot comes free. */
    fun whenFreed(dispatch: () -> Unit) {
        waiting += dispatch
    }

    @Synchronized
    fun inUse(): Int = taken
}
