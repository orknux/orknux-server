package io.mszymanski.orknux.server.chat

import tools.jackson.databind.ObjectMapper

/**
 * What a tool answers when it has put something in front of a person and the
 * answer will come later: `"status": "pending"`.
 *
 * Read by [AgentConversation] as well as by the model. A model told "their
 * answer reaches you later; end your turn now" often does exactly that and
 * writes nothing, and a provider then reports a reply with no message - which
 * the loop used to ask again five times, identically, and then fail the turn
 * with "could not answer". In a Slack thread that was a failed step for an
 * agent that had done precisely what it was told, and a person waiting for an
 * answer that only came when they typed again. An empty reply straight after
 * such a round is the end of the turn instead; see the Failed arm there.
 *
 * Two things have to hold, and [asked] is where they are held together: the
 * tool is one of ours that asks a person ([TOOLS]), and its answer says the
 * person has not answered yet. The shape alone is not enough - `"status":
 * "pending"` is what a build, a payment or a ticket answers too, from an MCP
 * server or a plugin nobody here wrote, and an empty reply after one of those
 * is the provider fault it always was and is asked again. The next tool that
 * waits on a person joins [TOOLS] and answers with [STATUS] and [PENDING].
 */
object AwaitingPerson {

    const val STATUS = "status"
    const val PENDING = "pending"

    private val mapper = ObjectMapper()

    /** The tools that put something in front of a person and answer before the person does. */
    val TOOLS: Set<String> = setOf(QuestionTools.ASK, GrantRequestTools.ASK)

    /** Whether [tool] is one that asks a person and [result], its answer, says they have not answered yet. */
    fun asked(tool: String, result: String): Boolean = tool in TOOLS && saidIn(result)

    /** Whether [result] has the shape: `"status": "pending"` at its top. */
    fun saidIn(result: String): Boolean {
        val trimmed = result.trimStart()
        if (!trimmed.startsWith("{") || !trimmed.contains(PENDING)) return false
        val node = runCatching { mapper.readTree(trimmed) }.getOrNull() ?: return false
        val status = node.path(STATUS)
        return status.isString && status.asString() == PENDING
    }
}
