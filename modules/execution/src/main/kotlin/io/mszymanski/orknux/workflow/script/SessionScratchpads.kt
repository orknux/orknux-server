package io.mszymanski.orknux.workflow.script

/**
 * A session's scratchpads, as a script reaches them - `orknux.scratchpad`.
 *
 * A scratchpad is a working file kept for the life of one AI session: a script
 * can list what the session holds, read a file or a fragment, write one, add to
 * it, edit a piece in place, describe it, share it with the agents the session
 * asks, search across them, and delete. It is the same store the session's own
 * agent reaches through its tools; a plugin or a function that runs inside a
 * session works on the very same files.
 *
 * One method rather than nine for the same reason [SessionScratch] keeps its
 * doors few: everything crosses the sandbox as text, and a single request in
 * and answer out is a smaller boundary than a method per operation. The request
 * names the operation and its arguments as JSON; the answer is JSON too -
 * `{ "ok": ... }` for what a call produced, or `{ "error": "..." }` for why it
 * could not. The server is the one place that spends the bounds, so both the
 * agent's tools and this go through it and cannot disagree.
 *
 * An interface here for the reason [SessionScratch] is one: the sessions are
 * the server's tables and this module holds none. The runners bind it only
 * where a call belongs to a session, so a function tried from its editor page
 * is told there is nowhere to keep a file rather than writing into nowhere.
 */
interface SessionScratchpads {

    /**
     * Runs one scratchpad operation for this session.
     *
     * @param request JSON naming the operation and its arguments.
     * @return JSON: `{ "ok": ... }` on success, `{ "error": "..." }` otherwise.
     */
    fun act(sessionId: Long, request: String): String
}
