package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * One agent putting a question to another.
 *
 * ### What it is for
 *
 * An agent that needs something doing in a system it holds no tools for had two
 * ways out, and both are bad. Grant it those tools as well - and it carries
 * forty descriptions it mostly does not use, and spends its rounds on the chain
 * of lookups one job needs before the work it was actually asked about begins.
 * Or hand the job back to whoever asked, which is the agent saying no to the
 * thing it exists for.
 *
 * So an agent may be granted other agents, and gets one tool: ask one of them.
 * The specialist answers in a conversation of its own, with its own briefing,
 * its own model and its own tools, and what comes back to the agent that asked
 * is the answer. Not the twelve rounds of looking things up that produced it -
 * which is the whole saving, because those rounds are what a context window is
 * actually spent on.
 *
 * ### One level, and why it is a rule rather than a number
 *
 * An agent reached this way is granted no agents of its own, whatever its row
 * says. A depth counter would be a number somebody has to choose, and every
 * value of it is wrong for something: two is arbitrary, five is a bill nobody
 * approved, and any of them leaves a ring of specialists calling each other
 * until the count runs out. No depth at all cannot be got round, costs nothing
 * to explain, and rules out the one failure that would be expensive and silent.
 *
 * What it costs is a chain three deep, which nobody has asked for and which the
 * grant list can express another way: an agent granted both specialists asks
 * each in turn.
 *
 * ### What it is not
 *
 * Not a way to reach an agent that was not granted. The names offered are the
 * grant list, and a name that is not in it is refused by the thing that would
 * otherwise run it - the rule the orknux tools and the shells already keep.
 *
 * Not a workflow either. This is a question and an answer inside one round of
 * one turn; a run that has to survive a restart, retry a step or be watched from
 * a page is a workflow, and the graph is where that belongs.
 */
@Service
class AgentRunTools(
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    /**
     * The conversation, fetched when it is needed rather than injected.
     *
     * [AgentConversation] builds its tools from [AgentTools], which holds this -
     * so asking for it here directly is a cycle Spring refuses at startup. The
     * provider breaks it, and the call is made from inside a round rather than
     * while the beans are being wired, by which time everything exists.
     */
    private val conversations: ObjectProvider<AgentConversation>,
    /** Where the asked agent's own conversation is written; see [run]. Issue #379. */
    private val sessions: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
    private val mapper: ObjectMapper,
) {

    /** Whether this agent has anybody to ask. A grant list of none offers nothing. */
    fun offered(agent: Agent): Boolean = granted(agent).isNotEmpty()

    fun specFor(agent: Agent): ToolSpec {
        val named = granted(agent)
        return ToolSpec(
            name = ASK,
            description = "Puts a question to one of the other agents you have been given, and " +
                "answers with what it said. Use it where the work needs something you have no tool " +
                "for and one of them does - it will look things up in its own conversation, so ask " +
                "for what you want to know rather than for the steps. It cannot see this " +
                "conversation, so say everything it needs in the question. You may ask: " +
                named.joinToString(", ") { "${it.name} (${it.description ?: "no description"})" },
            parameters = listOf(
                ToolParameterSpec(
                    name = AGENT,
                    description = "Which of them to ask, by name: " + named.joinToString(", ") { it.name },
                    required = true,
                ),
                ToolParameterSpec(
                    name = QUESTION,
                    description = "What to ask it, in full. It has none of your context, so a " +
                        "question that refers to \"the issue\" or \"that file\" cannot be answered.",
                    required = true,
                ),
                ToolParameterSpec(
                    name = TITLE,
                    description = "A few words naming the task, shown as the title of the conversation " +
                        "the agent has about it - \"Summarise the incident thread\". Left out, the " +
                        "first line of the question is used.",
                    required = false,
                ),
            ),
        )
    }

    fun handles(name: String): Boolean = name == ASK

    /**
     * Asks one of them, and answers with what it said.
     *
     * A specialist that could not answer is reported rather than thrown: the
     * agent that asked is mid-round and can say so, try another of them, or
     * finish - and a failure that ended the whole turn would lose the work it
     * had already done.
     */
    /**
     * @param parent the session the asking agent is in, or null where it is in
     *   none. The asked agent gets a session of its own under it - see
     *   [io.mszymanski.orknux.server.llm.LlmSessionRecorder.openUnder] - so what
     *   it did can be read, beside what asked for it. Issue #379.
     */
    fun run(agent: Agent, arguments: String, parent: Long? = null): String {
        val asked = text(arguments, AGENT)?.trim().orEmpty()
        val question = text(arguments, QUESTION)?.trim().orEmpty()
        val title = text(arguments, TITLE)?.trim()?.ifEmpty { null } ?: titleOf(question)

        if (asked.isEmpty() || question.isEmpty()) {
            return refusal("Say which agent to ask and what to ask it: $AGENT and $QUESTION are both needed.")
        }

        val named = granted(agent)
        val wanted = named.firstOrNull { it.name.equals(asked, ignoreCase = true) }
            ?: return refusal(
                "You have not been given an agent called \"$asked\". " +
                    "You may ask: ${named.joinToString(", ") { it.name }}.",
            )

        if (!wanted.enabled) return refusal("${wanted.name} is not active, so it was not asked.")
        val modelId = wanted.modelId ?: return refusal("${wanted.name} has no model chosen, so it cannot answer.")

        /*
         * Its own briefing, and a turn holding nothing but the question. The
         * specialist is not shown the conversation it was asked from: it has no
         * business reading it, and a question that needed it would be a question
         * the agent asking should have written out.
         */
        val turns = buildList {
            briefing.of(wanted)?.let { add(ChatTurn("system", it)) }
            add(ChatTurn("user", question))
        }

        /*
         * Asked as itself, with no agents of its own. `sub` is the same row with
         * the grant list emptied - the one level this feature has, enforced by
         * there being nothing to call rather than by a counter to get round.
         *
         * Not saved, and not the row Spring is managing: a detached copy, so
         * emptying the list here cannot reach the database.
         */
        val sub = without(wanted)

        /*
         * Its own conversation, written down. The question goes in under the
         * asking agent's name, because that is who said it, and what the asked
         * agent does with it - the tools it calls, what it answers - is
         * recorded by the round the same way any agent's is. Issue #379.
         */
        val into = parent?.let { above ->
            sessions.openUnder(above, title).also { sessions.userSaid(it, agent.name, question) }
        }

        return when (val said = conversations.getObject().answer(modelId, sub, turns, into = into)) {
            is ChatCompletion.Answered -> mapper.writeValueAsString(
                mapOf("agent" to wanted.name, "answer" to said.content),
            )

            is ChatCompletion.Failed -> refusal("${wanted.name} could not answer: ${said.reason}")

            /*
             * The loop runs tools to a conclusion, so nothing that comes back
             * here is still asking for one. A round that ended this way ended on
             * the specialist's own tools rather than on the moment.
             */
            is ChatCompletion.CalledTools -> refusal(
                "${wanted.name} asked for a tool that could not be run.",
            )
        }
    }

    /** The agents this one may ask: its grant list, in its own workspace, as rows. */
    private fun granted(agent: Agent): List<Agent> = agent.agents
        .mapNotNull { agents.findByIdOrNull(it) }
        .filter { it.workspaceId == agent.workspaceId && it.id != agent.id }

    /**
     * The same agent with nothing to delegate to.
     *
     * A copy rather than the row: emptying the managed entity's list would be
     * the grant being deleted the next time the transaction flushed, which is a
     * feature quietly removing itself from the database.
     */
    private fun without(agent: Agent): Agent = Agent(
        id = agent.id,
        workspaceId = agent.workspaceId,
        name = agent.name,
        type = agent.type,
        description = agent.description,
        modelId = agent.modelId,
        systemPrompt = agent.systemPrompt,
        enabled = agent.enabled,
        maxRounds = agent.maxRounds,
        memoryShare = agent.memoryShare,
        orknuxAccess = agent.orknuxAccess,
        shellAccess = agent.shellAccess,
        artifactAccess = agent.artifactAccess,
        finishAccess = agent.finishAccess,
        pictureLinkAccess = agent.pictureLinkAccess,
        tools = agent.tools.toMutableList(),
        connections = agent.connections.toMutableList(),
        skillCatalogs = agent.skillCatalogs.toMutableList(),
        memoryCatalogs = agent.memoryCatalogs.toMutableList(),
        mcpServers = agent.mcpServers.toMutableList(),
        // The whole of the rule: nothing to ask, so nothing further to reach.
        agents = mutableListOf(),
    )

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    /** The first line of the question, cut to a title's length, where no title was given. */
    private fun titleOf(question: String): String =
        question.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_LENGTH) ?: "Asked"

    private fun text(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).takeIf { it.isTextual }?.stringValue()
    }.getOrNull()

    companion object {

        const val ASK = "ask_agent"
        const val AGENT = "agent"
        const val QUESTION = "question"
        const val TITLE = "title"

        /** What a title falls back to being cut at, matching the column. */
        const val TITLE_LENGTH = 200
    }
}
