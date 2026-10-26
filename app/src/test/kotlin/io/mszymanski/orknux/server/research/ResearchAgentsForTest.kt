package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.chat.BuiltInTools

/**
 * Makes the workspace a planner and a writer thinking with [modelId], and a
 * reviewer thinking with [reviewer], named as the agents this product once
 * seeded - nothing is seeded now, so a test that researches brings its own.
 * Given again, the same agents take the new models.
 */
internal fun AgentRepository.researchWith(workspaceId: Long, modelId: Long, reviewer: Long = modelId) {
    listOf(ResearchModes.PLANNER to modelId, ResearchModes.WRITER to modelId, ResearchModes.REVIEWER to reviewer).forEach { (part, model) ->
        // Made as the product once seeded them: every built-in hidden, nothing to ask for, silent while it works.
        val agent = findByWorkspaceIdAndName(workspaceId, part.name)
            ?: Agent(workspaceId = workspaceId, name = part.name, type = AgentType.LLM, systemPrompt = part.instructions).apply {
                chattiness = Chattiness.NONE
                hiddenTools = BuiltInTools.hiddenBy(emptyList())
                orknuxAccess = false
                mayAskForTools = false
            }
        agent.modelId = model
        save(agent)
    }
}

/**
 * The agents [researchWith] made, as a start's input names them - one for
 * each step - for an input that names none of its own.
 */
internal fun AgentRepository.researchAgents(workspaceId: Long): String {
    fun id(part: ResearchModes.SeededAgent) = findByWorkspaceIdAndName(workspaceId, part.name)?.id
    return listOfNotNull(
        "agentMode: PER_STEP",
        id(ResearchModes.PLANNER)?.let { "plannerAgentId: $it" },
        id(ResearchModes.WRITER)?.let { "writerAgentId: $it" },
        id(ResearchModes.REVIEWER)?.let { "reviewerAgentId: $it" },
    ).joinToString(", ")
}

/**
 * [input], a start's GraphQL input object, given each of [researchAgents]
 * it does not name itself - none where it runs with one agent.
 */
internal fun AgentRepository.withResearchAgents(workspaceId: Long, input: String): String {
    if (input.contains("agentId:") || input.contains("SINGLE")) return input
    val open = input.indexOf('{')
    if (open < 0) return input
    val missing = researchAgents(workspaceId).split(", ").filter { part -> !input.contains(part.substringBefore(':') + ":") }
    if (missing.isEmpty()) return input
    return input.substring(0, open + 1) + " ${missing.joinToString(", ")}," + input.substring(open + 1)
}
