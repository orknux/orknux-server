package io.mszymanski.orknux.workflow.script

/**
 * An AI session's own store: what one tool call puts, a later one gets, for as
 * long as the session lives - and no other session ever sees it.
 *
 * This is what lets a plugin be stateful without being stateful forever. A
 * plugin instance is constructed per call and thrown away with its context, on
 * purpose - one run must not leave anything behind for the next - and that
 * purpose is exactly right across sessions and exactly wrong within one: a
 * tool that paginates, dedupes or keeps a running count has nowhere to keep
 * its place across the calls of one conversation. So the place is the
 * session's, keyed by it and gone with it.
 *
 * An interface here for the reason [PluginHost] is one: the sessions are the
 * server's tables and this module holds no table. The server implements it;
 * the runners bind `orknux.session.store` over it only where a call belongs to
 * a session, so a function tried from its editor page is told there is no
 * store here instead of writing into nowhere.
 *
 * Values cross as JSON text, like everything that crosses the sandbox.
 */
interface SessionScratch {

    /**
     * Stores one value under [key] for this session, replacing what was there.
     *
     * @return null when it was stored, or a sentence about why it was not.
     */
    fun put(sessionId: Long, key: String, json: String): String?

    /** What [key] holds for this session, as JSON, or null where nothing does. */
    fun get(sessionId: Long, key: String): String?

    /**
     * Removes what [key] holds for this session, if anything. Idempotent: a key
     * that holds nothing is left as it was. Issue #418.
     */
    fun remove(sessionId: Long, key: String)
}
