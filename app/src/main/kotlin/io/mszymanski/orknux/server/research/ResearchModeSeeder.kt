package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Gives every workspace that predates the research modes its three, once the
 * server is up. Issue #659.
 *
 * At start-up rather than in a migration: a mode is an agent, three workflows
 * and their nodes, and writing those rows out in SQL twice - once per engine -
 * would be a second copy of [ResearchModes] to keep in step with the entities.
 * [ResearchModes.ensure] does nothing for a workspace that has them, so this
 * costs one query per workspace on every start after the first.
 */
@Component
class ResearchModeSeeder(
    private val workspaces: WorkspaceRepository,
    private val modes: ResearchModes,
    /** The research agents an earlier version made, taken away where untouched. */
    private val seededAgents: SeededResearchAgents,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun seed() {
        workspaces.findAll().forEach { workspace ->
            val id = workspace.id ?: return@forEach
            runCatching { modes.ensure(id) }
                .onFailure { log.warn("Could not give workspace {} its research modes", id, it) }
            // After ensure, which redraws the modes' workflows so they no longer name these agents.
            runCatching { seededAgents.retire(id) }
                .onSuccess { if (it.isNotEmpty()) log.info("Workspace {}: deleted the unused {}", id, it.joinToString(", ")) }
                .onFailure { log.warn("Could not look for workspace {}'s unused research agents", id, it) }
        }
    }
}
