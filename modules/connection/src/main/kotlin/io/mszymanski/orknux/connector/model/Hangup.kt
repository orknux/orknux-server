package io.mszymanski.orknux.connector.model

/**
 * A model call somebody may hang up on while it is still running.
 *
 * **Why a handle rather than an interrupt.** A streaming call spends nearly all
 * of its life blocked on a socket read, and `Thread.interrupt` does not end one
 * of those: the SDK reads through an ordinary socket stream, which is not an
 * interruptible channel, so the flag is set and the thread goes on waiting for a
 * model that is still writing. What does end it is closing the thing being read,
 * and only the client that opened it knows what that is - a `StreamResponse` for
 * the SDK path and a response body for the hand-built one. So the client hands
 * over the closing rather than the caller guessing at it.
 *
 * **It is used from another thread, always.** The thread making the call is
 * inside the read; the thread deciding to give up is whoever noticed that nobody
 * is listening any more. Every field here is therefore behind one lock, and
 * [holding] deliberately closes straight away when it arrives after [hangUp] -
 * a call that opened a stream a moment too late must not be left running for
 * the same reason the ordinary one must not.
 *
 * Hanging up is not an answer and is not reported as one. What the caller gets
 * back is whatever the torn read produced, which is a failure; nobody is waiting
 * for it, which is the whole reason it was hung up on.
 */
class Hangup {

    private val lock = Any()
    private var gone = false
    private var close: (() -> Unit)? = null
    private var expected = false

    /** Whether somebody has given up on this call. */
    val hungUp: Boolean
        get() = synchronized(lock) { gone }

    /**
     * Whether it was hung up on as an ordinary part of a conversation rather
     * than stopped: a voice turn whose reader cut in or walked away. The torn
     * call is not a failure to record then. Issue #671.
     */
    val ordinary: Boolean
        get() = synchronized(lock) { gone && expected }

    /**
     * What tears this call down, handed over by the client that opened it.
     *
     * Called with the stream already closed where the decision was made first,
     * so a client that opens one is never left holding it.
     */
    fun holding(close: () -> Unit) {
        val already = synchronized(lock) {
            this.close = close
            gone
        }
        if (already) runCatching(close)
    }

    /**
     * Lets go of whatever was held: the call ended on its own.
     *
     * Without this a hangup arriving after a stream has closed normally would
     * close it a second time, which is harmless for every client here and is
     * still the sort of thing that is only harmless until it is not.
     */
    fun letGo() {
        synchronized(lock) { close = null }
    }

    /**
     * Gives up on the call, closing whatever is being read.
     *
     * Safe to call twice, and safe to call before the call has been made at
     * all - a client checks [hungUp] before it opens anything.
     */
    fun hangUp() = end(ordinary = false)

    /**
     * Hangs up as [hangUp] does, as the ordinary end of such a turn rather than
     * a stop - see [ordinary]. Whichever comes first decides which it was.
     */
    fun hangUpOrdinarily() = end(ordinary = true)

    private fun end(ordinary: Boolean) {
        val held = synchronized(lock) {
            if (gone) return
            gone = true
            expected = ordinary
            close.also { close = null }
        }
        held?.let { runCatching(it) }
    }
}
