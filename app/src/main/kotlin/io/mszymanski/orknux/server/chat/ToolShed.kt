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

    /**
     * The briefing, for an agent offered only [offered] of these tools; null
     * where the paragraph would name tools the agent was not given.
     *
     * The agent's Tools list holds a lent built-in back, and the paragraph
     * used to go out whole whatever it held back: an agent with every
     * scratchpad tool hidden but `scratchpad_keep`, which no list switches,
     * was told to write its long work into scratchpads it could not write to.
     * Research's writer is that agent, and once its step kept a session
     * (#668) it would have been told it. A shed whose paragraph is about
     * tools a list may withhold answers here for what is left; one none of
     * whose tools is offered says nothing. A shed that lends only its
     * paragraph - auto mode, an asked agent's - holds no tool to withhold,
     * and is said whole.
     */
    fun briefing(offered: Set<String>): String? =
        briefing()?.takeIf { specs().let { held -> held.isEmpty() || held.any { it.name in offered } } }

    /**
     * Tools of this shed that are no use once the rest of [offered] is what
     * the agent's list left: taken back too, rather than offered alone.
     *
     * `scratchpad_keep` is switched by no list, and hands on a pad; an agent
     * with every pad tool hidden has no pad to hand on, and was offered it
     * anyway - the one tool in an array that had held none, once Research's
     * planner and writer kept sessions (#668).
     */
    fun withdrawn(offered: Set<String>): Set<String> = emptySet()

    /** Whether this is one of the shed's, by exact name. */
    fun handles(name: String): Boolean

    /**
     * Whether a built-in of this name is lent whatever the agent's Tools list
     * says of it. False for every shed but one lending a tool because the run
     * asked for it - web search, for a research that searches the web (#678):
     * the list is the agent's standing grants, and this is not one. See
     * [BuiltInTools.lentTo], which otherwise holds a lent built-in to the list.
     */
    fun lentRegardless(name: String): Boolean = false

    /**
     * Runs one of them.
     *
     * May return text for the model to go on with, or throw [AgentRoundHalted]
     * to end the round there and then — which is what "I have finished" and "I
     * am stuck" both are. An agent's own tools never do either: a tool that
     * failed is a fact the model is told about and the conversation carries on.
     */
    fun run(call: ToolCall): String

    /**
     * A picture one of the round's tools made - any tool, the agent's own or
     * lent - offered to whoever is running the round to put where its reader
     * is, with a sentence for the model saying where it went. Null where it
     * went nowhere, which is every shed but a chat's.
     *
     * A diagram, a chart and an SVG drawing were handed to the model alone and
     * kept under a key, so in a chat the agent described a picture the person
     * never saw - and believed they had, because the one drawing tool a chat
     * lends does file its picture into the thread.
     */
    fun pictured(tool: String, picture: AgentTools.Companion.Picture): String? = null

    /**
     * The turn is over: put down whatever was held back until it ended.
     *
     * Called by whoever ran the turn once the model has finished, before its
     * answer is written, so what is put here comes above the answer. A chat
     * holds a picture with a [AgentTools.Companion.Picture.key] until now,
     * keeping the last of each key - chat 979. Nothing for every other shed.
     */
    fun settle() = Unit
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

            override fun briefing(offered: Set<String>): String? =
                held.mapNotNull { it.briefing(offered) }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

            override fun withdrawn(offered: Set<String>): Set<String> = held.flatMapTo(mutableSetOf()) { it.withdrawn(offered) }

            override fun handles(name: String): Boolean = held.any { it.handles(name) }

            override fun run(call: ToolCall): String =
                held.first { it.handles(call.name) }.run(call)

            override fun lentRegardless(name: String): Boolean = held.any { it.handles(name) && it.lentRegardless(name) }

            /** The first shed that puts it somewhere. */
            override fun pictured(tool: String, picture: AgentTools.Companion.Picture): String? =
                held.firstNotNullOfOrNull { it.pictured(tool, picture) }

            override fun settle() = held.forEach { it.settle() }
        }
    }
}
