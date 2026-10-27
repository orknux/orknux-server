package io.mszymanski.orknux.server.flag

import io.mszymanski.orknux.server.chat.RoundWatch

/**
 * A round's watcher with the concealed tools' calls taken out: never told one
 * was made, what it answered, or that the model is writing one.
 *
 * Applied once, where [io.mszymanski.orknux.server.chat.AgentConversation]
 * takes the watcher it was handed, so every watcher behind it - a chat's
 * stream to the browser, the session page's live view, a task's page, a
 * workflow step's draft - learns nothing about a flag. Before every one of them
 * rather than in each: a watcher added next year would otherwise draw the call
 * until somebody remembered this.
 *
 * An administrator watching live does not see one either; they read it on a
 * reload, and on the report. Telling the two apart here would mean every
 * watcher knowing who is behind it, and most of them do not.
 */
class ConcealingWatch(private val inner: RoundWatch) : RoundWatch by inner {

    /** The places in the round of the calls that were left out, so their answers are left out too. */
    private val hidden: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun called(at: Int, tool: String, arguments: String) {
        if (ConcealedTools.conceals(tool)) {
            hidden += at
            return
        }
        inner.called(at, tool, arguments)
    }

    override fun returned(at: Int, result: String, failed: Boolean) {
        if (at in hidden) return
        inner.returned(at, result, failed)
    }

    /** "Writing flag_session…" names it as surely as the call does. */
    override fun writing(tool: String?, chars: Int) {
        if (ConcealedTools.conceals(tool)) return
        inner.writing(tool, chars)
    }

    companion object {
        /** The watcher as a round may tell it; null stays null, since a round streams only for a watcher. */
        fun of(watch: RoundWatch?): RoundWatch? = watch?.let { it as? ConcealingWatch ?: ConcealingWatch(it) }
    }
}
