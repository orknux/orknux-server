package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.AgentRepository

/**
 * Gives the workspace's Research planner and writer [modelId], and its
 * reviewer [reviewer]: a research thinks with its agents' own models since
 * #678, and the seeded ones are made with none.
 */
internal fun AgentRepository.researchWith(workspaceId: Long, modelId: Long, reviewer: Long = modelId) {
    listOf(ResearchModes.PLANNER to modelId, ResearchModes.WRITER to modelId, ResearchModes.REVIEWER to reviewer).forEach { (seeded, model) ->
        val agent = requireNotNull(findByWorkspaceIdAndName(workspaceId, seeded.name)) { "${seeded.name} is not seeded" }
        agent.modelId = model
        save(agent)
    }
}
