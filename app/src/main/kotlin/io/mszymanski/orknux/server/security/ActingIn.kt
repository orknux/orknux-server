package io.mszymanski.orknux.server.security

/**
 * One workspace, and who is acting in it, for the length of one call on this
 * thread.
 *
 * For the orknux_* tools, whose callers were checked before their scope was
 * built - an agent's workspace is its own, and the MCP endpoint refuses a
 * workspace its caller cannot see - but which reach the workspace's own
 * controllers, and those check again. An agent working a task has no session
 * for that second check to find, so without this it could read a workflow and
 * not build one; and the audit recorder, which names whoever is signed in, had
 * nobody to name.
 *
 * Nothing wider than the one workspace is granted and nothing administrative at
 * all. The actor names the change only where nobody is signed in: a session
 * wins, so a name passed in can never sign somebody else's name to anything.
 */
object ActingIn {

    private val held = ThreadLocal<Pair<Long, String?>?>()

    fun <T> run(workspaceId: Long, actor: String?, block: () -> T): T {
        val before = held.get()
        held.set(workspaceId to actor)
        try {
            return block()
        } finally {
            if (before == null) held.remove() else held.set(before)
        }
    }

    /** The workspace granted on this thread, or null outside a call. */
    fun workspace(): Long? = held.get()?.first

    /** Who is acting, where a call named somebody. */
    fun actor(): String? = held.get()?.second
}
