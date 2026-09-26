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
    /**
     * The asked agent's setup, written into its session before the question.
     * Issue #456.
     *
     * Fetched rather than injected for the same reason the conversation is:
     * [io.mszymanski.orknux.server.agent.AgentDetails] asks [AgentTools] what the
     * agent holds, and [AgentTools] holds this - so wiring it in directly is a
     * cycle Spring refuses at startup. By the time a question is being asked,
     * everything exists.
     */
    private val details: ObjectProvider<io.mszymanski.orknux.server.agent.AgentDetails>,
    /** Where the asked agent's own conversation is written; see [run]. Issue #379. */
    private val sessions: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
    /** The working files lent to the asked agent, so a shared pad reaches it. Issue #411. */
    private val scratchpads: ScratchpadTools,
    /** The conversations already started from one, for the count in [run]. Issue #380. */
    private val held: io.mszymanski.orknux.server.llm.LlmSessionRepository,
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
    /** Where an answer, and whatever the asked agent's tools kept, is put for the asker. Issue #393. */
    private val scratch: io.mszymanski.orknux.server.llm.LlmSessionStore,
    private val mapper: ObjectMapper,
) {

    /**
     * Whether this agent has anybody to ask. A grant list of none offers
     * nothing, and so does a workspace - or an installation - that allows no
     * asks at all: a tool that refuses every call is a tool the model should
     * not be shown. Issue #380.
     */
    fun offered(agent: Agent): Boolean = granted(agent).isNotEmpty() && limitFor(agent) > 0

    /**
     * How many other agents this one may ask in a conversation: the
     * workspace's own number where it has one, Admin -> Settings otherwise.
     */
    fun limitFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.agentMaxSubagents ?: installation.agentMaxSubagents()

    fun specFor(agent: Agent): ToolSpec {
        val named = granted(agent)
        return ToolSpec(
            name = ASK,
            description = "Puts a question to one of the other agents you have been given, and " +
                "answers with what it said. Use it where the work needs something you have no tool " +
                "for and one of them does - it will look things up in its own conversation, so ask " +
                "for what you want to know rather than for the steps. It cannot see this " +
                "conversation, so say everything it needs in the question. The answer comes back " +
                "as text and under a contentKey as well, and any key the answer names - a file it " +
                "saved, a picture it drew - works in this conversation too: to upload or send what " +
                "it made, pass the key to the tool that takes one rather than typing the text back, " +
                "which is what cuts a long file off. Where you want something long made - a page, a " +
                "file, a list of a hundred things - say in the question that it should be written " +
                "into a scratchpad and shared, and the answer will name the pad: you then read and " +
                "edit that pad yourself instead of it being typed back at you. You may ask: " +
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

        /*
         * The bound on fan-out, counted against the conversation the asker is
         * in: every ask so far is a session under it, so the sessions are the
         * count. Told as a number and a reason, so the model finishes with
         * what it has rather than trying the same ask in other words. A round
         * in no session has nothing to count against and is not bounded here;
         * the rounds bound still holds it. Issue #380.
         */
        val allowed = limitFor(agent)
        val spent = parent?.let { held.findByParentSessionIdOrderByCreatedAtAscIdAsc(it).size } ?: 0
        if (parent != null && spent >= allowed) {
            return refusal(
                "You have asked $spent other agents in this conversation, which is all this workspace " +
                    "allows. Answer with what you have.",
            )
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
        /*
         * Composed once: the same string is the system turn and the record of
         * what this agent was working under. Issue #456 - see below.
         */
        val system = briefing.of(wanted)
        val turns = buildList {
            system?.let { add(ChatTurn("system", it)) }
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
        val into = parent?.let { above -> sessions.openUnder(above, title) }

        // The asked agent gets scratchpad tools scoped to its own session, so a
        // pad the asker shared is one it can read and add to - the same document,
        // worked on by both. A subagent in no session gets none. Issue #411.
        //
        // Built before the question goes in, because the account of the setup
        // below names what was lent as well as what was granted.
        val lent = handing(into)

        if (into != null) {
            /*
             * The setup this agent answered under, first thing in its own
             * session. Issue #456: an agent node and a task both write this line
             * where an agent starts responding, and a subagent's session did not
             * - so the one conversation in the family whose agent nobody chose
             * opened with a tool call and never said which agent had been asked,
             * on which model, with what prompt or holding what. Which is the
             * first question anybody reading a subagent's log has.
             *
             * Of `sub` rather than of the row: that is the agent the round was
             * given, and the one difference between them - no agents of its own
             * to ask - is a difference in the tools the model was handed, which
             * is precisely what this record is for.
             */
            sessions.describeAgent(into, details.getObject().snapshot(sub, lent, system))
            sessions.userSaid(into, agent.name, question)
        }

        return when (
            val said = conversations.getObject().answer(modelId, sub, turns, into = into, shed = lent)
        ) {
            /*
             * The answer, and a key it is kept under in the asker's session. A
             * subagent that wrote a page answers with the page, and the only
             * handle the asker had was the text - so it typed ten thousand
             * characters back into an upload and the model's output cap cut
             * them off. Kept the way a drawn picture is, so the key can go to
             * whichever tool takes one and the text never leaves the server
             * twice. Issue #393.
             */
            is ChatCompletion.Answered -> mapper.writeValueAsString(
                buildMap {
                    put("agent", wanted.name)
                    put("answer", said.content)
                    keyFor(parent, child = into, said.content)?.let { put("contentKey", it) }
                },
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

    /**
     * The key the answer is kept under in the asker's session, or null where
     * there is no session to keep it in or the store would not take it - said
     * by leaving the key out, so the tool never promises a key it cannot keep.
     */
    private fun keyFor(parent: Long?, child: Long?, answer: String): String? {
        if (parent == null || answer.isEmpty()) return null
        /*
         * First what the asked agent's own tools kept - a picture it drew, a
         * file it saved - because the keys its answer names are in *its*
         * store, and the asker's tools read the asker's. Copied up before the
         * answer is keyed, so the answer's key is the last one in.
         */
        if (child != null) scratch.copy(from = child, into = parent)
        val key = "answer." + (child ?: System.nanoTime())
        // A JSON-encoded *string*: the sandbox parses what it reads, and an
        // upload door requires what comes out to be the text itself.
        val refused = scratch.put(parent, key, mapper.writeValueAsString(answer))
        return if (refused == null) key else null
    }

    /**
     * The scratchpads the asked agent works in, and what it is told about
     * handing one back. Issue #458.
     *
     * A subagent asked for something long writes it into a pad and then puts
     * the whole of it in its answer as well, because the answer is the only
     * thing it believes reaches the asker - so a page is paid for twice and
     * trimmed once. It is not the only thing: a pad it shares is one the agent
     * that asked can read and edit, the same document rather than a copy. So
     * the paragraph the scratchpad shed already says about having working files
     * gains the part only a subagent needs, and it is added here rather than in
     * [ScratchpadTools] because it is true of nothing else that is lent them.
     */
    private fun handing(into: Long?): ToolShed? {
        val pads = scratchpads.shed(into) ?: return null
        return object : ToolShed by pads {
            override fun briefing(): String = listOfNotNull(
                pads.briefing(),
                "You are answering another agent, not a person. Anything long you make - a page, a file, " +
                    "a list - goes in a scratchpad which you then share with " + ScratchpadTools.SHARE +
                    ": a shared pad is the same document the agent that asked reads and edits, so your " +
                    "answer should say which pad it is in and what is in it in a line or two, and never " +
                    "repeat the whole of it. What you write twice is paid for twice and cut off once.",
            ).joinToString(separator = System.lineSeparator() + System.lineSeparator())
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
        // The built-ins ride in these two like every other tool now (#444), so
        // the copy carries both or the specialist is offered a different set.
        tools = agent.tools.toMutableList(),
        requiredTools = agent.requiredTools.toMutableList(),
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
