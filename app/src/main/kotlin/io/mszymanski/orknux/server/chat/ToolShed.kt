package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec

/**
 * Tools lent to an agent for one round, by whatever asked it.
 *
 * [AgentTools] is the one place that knows what an *agent* may call, and this is
 * not a second one: what a shed holds is not a capability the agent was granted
 * and not something it has anywhere else. It exists because a caller sometimes
 * needs the model to be able to say something to it that is not text - "I have
 * finished", "I need permission for this", "which of these did you mean" - and
 * the only channel a model has for saying something structured is a tool call.
 *
 * So a shed is a way for the thing running the loop to be addressed by the model
 * inside it, and its lifetime is that loop. Granting through it would be wrong:
 * anything an agent may still call tomorrow belongs on the agent.
 *
 * A shed's names are asked before the agent's own, so a shed cannot be shadowed
 * by a workspace tool that happens to share a name.
 */
interface ToolShed {

    /** What to offer the model, alongside the agent's own. */
    fun specs(): List<ToolSpec>

    /**
     * What the model is told about these tools in its briefing, or null where
     * the descriptions say it all.
     *
     * A tool description says what a tool does; it does not say that the
     * agent has one, or when to reach for it, and a model reads thirty of them
     * and reaches for none. Memory search went unused until the briefing said
     * there was a memory to search (9031e2e), and scratchpads went the same
     * way until this. A shed that wants to be used says so here, in a
     * paragraph appended to the system turn for the round it is lent. Issue
     * #445.
     */
    fun briefing(): String? = null

    /** Whether this is one of the shed's, by exact name. */
    fun handles(name: String): Boolean

    /**
     * Runs one of them.
     *
     * May return text for the model to go on with, or throw [AgentRoundHalted]
     * to end the round there and then — which is what "I have finished" and "I
     * am stuck" both are. An agent's own tools never do either: a tool that
     * failed is a fact the model is told about and the conversation carries on.
     */
    fun run(call: ToolCall): String
}

/**
 * A lent tool ending the round.
 *
 * Not a failure. The caller lent the tool, so the caller knows what it means and
 * catches it; the message is what gets written into the transcript in the tool's
 * place, so it says what happened in words a person reading the log will
 * understand.
 */
open class AgentRoundHalted(note: String) : RuntimeException(note)

/**
 * The system text a round actually sends: what the caller composed, with a lent
 * shed's [ToolShed.briefing] after it.
 *
 * One function rather than two, because two things need the same answer and they
 * must not disagree. [AgentConversation] applies it to build the turn the
 * provider is sent, and [io.mszymanski.orknux.server.agent.AgentDetails] applies
 * it to write down what the model was working under - and the whole point of
 * that record is that it is what was sent. Issue #454: the record used to be
 * `agent.systemPrompt`, so a node that overrode the prompt, the grants briefing
 * appended after it and every paragraph a lent shed added were all missing from
 * an account that read as complete.
 *
 * Nothing lent hands the caller's own text back unchanged, so a round with no
 * shed is composed exactly as it was before any of this.
 */
fun briefedWith(system: String?, advice: String?): String? = when {
    advice.isNullOrBlank() -> system
    system.isNullOrBlank() -> advice
    // Trimmed at the end rather than joined raw: a briefing that ends in a
    // newline and one that does not must produce the same text, or the same
    // setup would compare as two.
    else -> system.trimEnd() + "\n\n" + advice
}

/**
 * Two sheds lent to one round, as one.
 *
 * A caller may have more than one thing to lend - an agent node lends drawing
 * and lends the ending - and the round takes a single shed. Rather than one
 * shed growing tools that have nothing to do with each other, they stay
 * separate and are put together here: each still decides on its own whether it
 * has anything to offer, and a caller that has only one lends only that one.
 *
 * Names are asked in the order given, so the first shed holding a name answers
 * for it. Nothing in this repository lends two tools of the same name; if
 * anything ever does, the order is the decision rather than an accident.
 */
fun sheds(vararg lent: ToolShed?): ToolShed? {
    val held = lent.filterNotNull()
    return when (held.size) {
        0 -> null
        1 -> held.first()
        else -> object : ToolShed {
            override fun specs(): List<ToolSpec> = held.flatMap { it.specs() }

            /** Each shed's paragraph, in the order lent; null where none has one. */
            override fun briefing(): String? =
                held.mapNotNull { it.briefing() }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

            override fun handles(name: String): Boolean = held.any { it.handles(name) }

            override fun run(call: ToolCall): String =
                held.first { it.handles(call.name) }.run(call)
        }
    }
}
