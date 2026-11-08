package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * `goal_start`: the agent setting itself a goal in the chat it is answering, to
 * be worked on turn after turn until a reviewer judges its condition met. See
 * [ChatGoals] for the loop.
 *
 * Lent where the agent may create agents - the reviewer is one - and only in a
 * chat's conversation, since a goal's turns are the chat's turns. Not a row of
 * its own on the Tools list: drawn under [BuiltInGovernance.CREATABLE_MODELS]
 * with `agent_create`, for the reason [TemporaryAgentTools] gives.
 */
@Service
class GoalTools(
    @param:Lazy private val goals: ChatGoals,
    private val chats: ChatSessionRepository,
    private val mapper: ObjectMapper,
) {

    /** The shed for [agent] in [session]; none outside a chat, or where it may create no agents. */
    fun shed(agent: Agent, session: Long?): ToolShed? {
        if (session == null || agent.temporary || agent.creatableModelIds.isEmpty()) return null
        val chat = chats.findFirstByLlmSessionId(session) ?: return null
        return Shed(requireNotNull(chat.id), agent)
    }

    private inner class Shed(private val chatId: Long, private val agent: Agent) : ToolShed {

        override fun specs(): List<ToolSpec> = SPECS

        override fun handles(name: String): Boolean = name == START

        override fun run(call: ToolCall): String = try {
            val args = runCatching { mapper.readTree(call.arguments.ifBlank { "{}" }) }.getOrNull()
                ?: return mapper.writeValueAsString(mapOf("error" to "The arguments were not JSON."))
            val goal = goals.start(
                chatId,
                GoalRequest(
                    goal = text(args, GOAL).orEmpty(),
                    condition = text(args, CONDITION).orEmpty(),
                    maxIterations = number(args, MAX),
                    reviewerModelId = number(args, REVIEWER)?.toLong(),
                    reviewerInstructions = text(args, INSTRUCTIONS),
                ),
                agent.name,
                working = true,
            )
            mapper.writeValueAsString(
                mapOf(
                    "goal" to goal.goal,
                    "condition" to goal.condition,
                    "iteration" to "1 of ${goal.maxIterations}",
                    "note" to "This turn is the first iteration: work on the goal now. When it ends a reviewer " +
                        "judges the condition, and if it is not met you are given its feedback and another turn.",
                ),
            )
        } catch (refused: ChatGoalRefusedException) {
            mapper.writeValueAsString(mapOf("error" to refused.message))
        }
    }

    private fun text(node: JsonNode, name: String): String? = node.path(name).let { if (it.isString) it.asString() else null }

    private fun number(node: JsonNode, name: String): Int? = node.path(name).let {
        when {
            it.isNumber -> it.asInt()
            it.isString -> it.asString().trim().toIntOrNull()
            else -> null
        }
    }

    companion object {
        const val START = "goal_start"
        private const val GOAL = "goal"
        private const val CONDITION = "condition"
        private const val MAX = "maxIterations"
        private const val REVIEWER = "reviewerModelId"
        private const val INSTRUCTIONS = "reviewerInstructions"

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                name = START,
                description = "Sets a goal in this chat and keeps you working on it until it is met: after each of " +
                    "your turns a reviewer - an agent you do not control - judges whether the condition is met, " +
                    "and if not, you are given its feedback and another turn, up to maxIterations. Use it for work " +
                    "that needs several passes to get right. This turn is the first.",
                parameters = listOf(
                    ToolParameterSpec(GOAL, "What to achieve.", required = true),
                    ToolParameterSpec(CONDITION, "When it counts as met, in words the reviewer can check.", required = true),
                    ToolParameterSpec(
                        MAX,
                        "How many turns at most; left out, the installation's default.",
                        required = false,
                        schema = mapOf("type" to "integer"),
                    ),
                    ToolParameterSpec(
                        REVIEWER,
                        "Which model reviews, by id: one you may create agents with. Left out, your own model.",
                        required = false,
                        schema = mapOf("type" to "integer"),
                    ),
                    ToolParameterSpec(INSTRUCTIONS, "Anything the reviewer should hold the work to. Optional.", required = false),
                ),
            ),
        )
    }
}
