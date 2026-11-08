package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.TemporaryAgentRefusedException
import io.mszymanski.orknux.server.agent.TemporaryAgents
import io.mszymanski.orknux.server.agent.TemporaryTool
import io.mszymanski.orknux.server.agent.TemporaryToolVisibility
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * `agent_create` and `agent_dismiss`: an agent making a specialist for the
 * conversation it is in, and letting it go. See [TemporaryAgents] for the rules.
 *
 * A shed rather than one of [AgentTools]: what it lends is the conversation's -
 * an agent made here belongs to this session and is asked from it - so it needs
 * the session the way the scratchpads do, and is lent with them by
 * [SessionSheds]. Lent only where the agent has been allowed models to create
 * with, so an agent that may not create agents is never shown the tools.
 *
 * Not in [BuiltInTools.GRANTED] as a switchable row, and therefore no V302-
 * shaped migration: the list of models is already the switch - empty is off -
 * and a second switch for the same thing is how the two would disagree. The
 * Tools list draws the two read-only under [BuiltInGovernance.CREATABLE_MODELS],
 * the way the memory tools are drawn under the memory catalogs.
 */
@Service
class TemporaryAgentTools(
    private val temporary: TemporaryAgents,
    private val mapper: ObjectMapper,
    /** Lazily: asking is [AgentRunTools]' own, and it is the bigger of the two beans to build. */
    private val asking: ObjectProvider<AgentRunTools>,
) {

    /** The shed for [agent] in [session]; none where it may create no agents or there is no conversation to own them. */
    fun shed(agent: Agent, session: Long?): ToolShed? {
        if (session == null || agent.temporary || agent.creatableModelIds.isEmpty()) return null
        return Shed(agent, session)
    }

    private inner class Shed(private val agent: Agent, private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> = SPECS

        /*
         * The models, by id and name, said in the briefing rather than in the
         * tool's description: the description is part of the frozen tool
         * array, and the briefing is where what this agent in particular may
         * do is said.
         */
        override fun briefing(): String {
            val allowed = temporary.creatableModels(agent)
            return "You may create agents for this conversation with $CREATE, and ask them with ${AgentRunTools.ASK} " +
                "like any other. Each thinks with one of these models, by modelId: " +
                allowed.joinToString(", ") { "${it.second} (${it.first})" } + ". An agent you create inherits " +
                "nothing from you - only its instructions and the tools you give it, which must be tools you hold " +
                "yourself" + (if (agent.delegatedTools.isEmpty()) "" else ", including the ones you hold only to " +
                "hand on: " + agent.delegatedTools.joinToString(", ")) + ". It cannot create agents itself. " +
                "Dismiss one with $DISMISS when its work is done."
        }

        override fun handles(name: String): Boolean = name == CREATE || name == DISMISS

        override fun run(call: ToolCall): String = try {
            val args = runCatching { mapper.readTree(call.arguments.ifBlank { "{}" }) }.getOrNull()
                ?: return said(mapOf("error" to "The arguments were not JSON."))
            when (call.name) {
                CREATE -> {
                    val made = temporary.create(
                        creator = agent,
                        session = session,
                        name = text(args, NAME).orEmpty(),
                        instructions = text(args, INSTRUCTIONS).orEmpty(),
                        modelId = args.path(MODEL_ID).let { if (it.isNumber) it.asLong() else text(args, MODEL_ID)?.trim()?.toLongOrNull() },
                        // Null for the parameter left out, which the service refuses: [] is none on purpose.
                        tools = if (args.has(TOOLS)) toolsIn(args.path(TOOLS)) else null,
                        description = text(args, DESCRIPTION),
                    )
                    // A question given with it is put at once, exactly as ask_agent would put it - the same path, the
                    // same session under this one, the same answer to wait for - saving a round that only asked.
                    val question = text(args, QUESTION)?.trim()?.ifEmpty { null }
                    val asked = question?.let {
                        val ask = mapper.writeValueAsString(mapOf(AgentRunTools.AGENT to made.name, AgentRunTools.QUESTION to it))
                        runCatching { mapper.readTree(asking.getObject().run(agent, ask, session)) }.getOrNull()
                    }
                    said(
                        buildMap {
                            put("created", made.name)
                            // Renamed where the name was taken: said first, so the model asks it by the name it has.
                            text(args, NAME)?.trim()?.takeIf { it.isNotEmpty() && it != made.name }?.let { wanted ->
                                put("renamed", "It is called \"${made.name}\" rather than \"$wanted\", which was in use. Ask it by that name.")
                            }
                            put("tools", (made.tools + made.requiredTools).distinct().sorted())
                            put("handOn", made.delegatedTools.sorted())
                            // Said, since a model often leaves tools out by accident and then wonders why nothing was fetched.
                            if ((made.tools + made.requiredTools + made.delegatedTools).isEmpty()) {
                                put("noTools", "It was given no tools, so it can only answer from what it knows. Give it tools by dismissing it and creating it again with them.")
                            }
                            if (asked != null) put("asked", asked)
                            put(
                                "note",
                                (if (asked != null) "It has been asked; what the ask says is under asked. Ask it again with ${AgentRunTools.ASK}, agent \"${made.name}\". "
                                else "Ask it with ${AgentRunTools.ASK}, agent \"${made.name}\". ") +
                                    "It lasts as long as this conversation is in use; dismiss it with $DISMISS when done.",
                            )
                        },
                    )
                }

                else -> {
                    val name = text(args, AGENT).orEmpty()
                    // Its asks still working are stopped first: a dismissed agent whose turn went on fetching and
                    // answering was "active" in every list for as long as it took, and its answer came back to an
                    // asker that had let it go.
                    val stopped = runCatching {
                        mapper.readTree(asking.getObject().stop(agent, session, mapper.writeValueAsString(mapOf(AgentRunTools.AGENT to name))))
                            .path("stopped").size()
                    }.getOrDefault(0)
                    val gone = temporary.dismiss(agent, session, name)
                    said(
                        buildMap {
                            put("dismissed", gone.name)
                            if (stopped > 0) {
                                put("stopped", stopped)
                                put("note", "It was still working on ${if (stopped == 1) "an ask" else "$stopped asks"} of yours; ${if (stopped == 1) "it was" else "they were"} stopped, and nothing more comes back from ${if (stopped == 1) "it" else "them"}.")
                            }
                        },
                    )
                }
            }
        } catch (refused: TemporaryAgentRefusedException) {
            said(mapOf("error" to refused.message))
        }
    }

    /** The tools asked for: an array of {name, visibility}, a plain name meaning Offer, or the same as JSON text. */
    private fun toolsIn(node: JsonNode): List<TemporaryTool> {
        val list = if (node.isString) runCatching { mapper.readTree(node.asString()) }.getOrNull() ?: return emptyList() else node
        if (!list.isArray) return emptyList()
        return list.values().mapNotNull { one ->
            when {
                one.isString -> TemporaryTool(one.asString(), TemporaryToolVisibility.OFFER)
                one.isObject -> {
                    val name = text(one, NAME) ?: return@mapNotNull null
                    val visibility = text(one, VISIBILITY)?.trim()?.uppercase()
                        ?.let { said -> TemporaryToolVisibility.entries.firstOrNull { it.name == said } }
                        ?: TemporaryToolVisibility.OFFER
                    TemporaryTool(name, visibility)
                }

                else -> null
            }
        }
    }

    private fun text(node: JsonNode, name: String): String? = node.path(name).let { if (it.isString) it.asString() else null }

    private fun said(value: Any): String = mapper.writeValueAsString(value)

    companion object {
        const val CREATE = "agent_create"
        const val DISMISS = "agent_dismiss"
        private const val NAME = "name"
        private const val INSTRUCTIONS = "instructions"
        private const val MODEL_ID = "modelId"
        private const val TOOLS = "tools"
        private const val VISIBILITY = "visibility"
        private const val DESCRIPTION = "description"
        private const val AGENT = "agent"
        private const val QUESTION = "question"

        /** The two, as every agent allowed them is offered them; fixed, since the tool array is. */
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                name = CREATE,
                description = "Creates a temporary agent for this conversation, which you then ask with " +
                    "${AgentRunTools.ASK} like any other agent. It inherits nothing from you: no catalogs, " +
                    "connections, servers or grants - only the instructions and the tools you give it here, and " +
                    "you may give only tools you hold yourself, including the ones you hold only to hand on. It " +
                    "cannot create agents itself. It lasts while this conversation is in use; dismiss it with " +
                    "$DISMISS when its work is done, which also stops anything it is still doing for you.",
                parameters = listOf(
                    ToolParameterSpec(NAME, "What to call it - the name you ask it by.", required = true),
                    ToolParameterSpec(
                        INSTRUCTIONS,
                        "Its system prompt: what it is for and how it works. It knows nothing else, so say all of it.",
                        required = true,
                    ),
                    ToolParameterSpec(
                        MODEL_ID,
                        "Which model it thinks with, by id: one of the models your briefing says you may create agents with.",
                        required = true,
                        schema = mapOf("type" to "integer"),
                    ),
                    ToolParameterSpec(
                        TOOLS,
                        "The tools to give it, each {name, visibility}: ALWAYS carried every turn, OFFER found when " +
                            "needed, or DELEGATE held only to hand on. Only tools you hold. Always given: [] for an " +
                            "agent that answers from what it knows alone; left out, nothing is created.",
                        required = false,
                        schema = mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    NAME to mapOf("type" to "string"),
                                    VISIBILITY to mapOf("type" to "string", "enum" to listOf("ALWAYS", "OFFER", "DELEGATE")),
                                ),
                                "required" to listOf(NAME),
                            ),
                        ),
                    ),
                    ToolParameterSpec(DESCRIPTION, "A line saying what it does. Optional.", required = false),
                    ToolParameterSpec(
                        QUESTION,
                        "Optional: what to ask it straight away, in full, as ${AgentRunTools.ASK} would - it starts at " +
                            "once and you wait for its answer the same way. Left out, it is created and waits to be asked.",
                        required = false,
                    ),
                ),
            ),
            ToolSpec(
                name = DISMISS,
                description = "Removes a temporary agent you created in this conversation, by name. An ask of yours it is still working on is stopped, and nothing more comes back from it.",
                parameters = listOf(ToolParameterSpec(AGENT, "Which of the agents you created, by name.", required = true)),
            ),
        )
    }
}
