package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.revision.ComponentRevision
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import java.time.OffsetDateTime

/**
 * The research agents an earlier version made in every workspace are taken
 * away where they are still exactly what was made, and kept where somebody
 * made them theirs.
 */
@SpringBootTest
class SeededResearchAgentsTest(
    @Autowired val retirement: SeededResearchAgents,
    @Autowired val agents: AgentRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private fun workspace(): Long = requireNotNull(workspaces.save(Workspace(name = "seeded-${System.nanoTime()}")).id)

    private fun made(workspaceId: Long, seeded: ResearchModes.SeededAgent, description: String = seeded.description): Long =
        requireNotNull(
            agents.save(
                Agent(workspaceId = workspaceId, name = seeded.name, type = AgentType.LLM).also {
                    it.description = description
                    it.systemPrompt = seeded.instructions
                },
            ).id,
        )

    private fun names(workspaceId: Long) = agents.findByWorkspaceId(workspaceId, Pageable.unpaged()).content.map { it.name }

    @Test
    fun `the three as they were made are taken away, and the workspace's audit says so`() {
        val id = workspace()
        listOf(ResearchModes.PLANNER, ResearchModes.WRITER, ResearchModes.REVIEWER).forEach { made(id, it) }
        agents.save(Agent(workspaceId = id, name = "Desk", type = AgentType.LLM))

        val taken = retirement.retire(id)

        assertThat(taken).containsExactlyInAnyOrder("Research planner", "Research writer", "Research reviewer")
        assertThat(names(id)).containsExactly("Desk")
        assertThat(audit.findAll().filter { it.workspaceId == id }.map { it.message })
            .anyMatch { it.startsWith("Agent Research planner deleted") }
    }

    @Test
    fun `one somebody made theirs stays`() {
        val id = workspace()
        // Its description changed: somebody's.
        made(id, ResearchModes.WRITER, description = "Writes our weekly report.")
        // Saved since it was made: a revision says so.
        val reviewer = made(id, ResearchModes.REVIEWER)
        revisions.save(
            ComponentRevision(
                workspaceId = id, kind = ComponentRevisionKind.AGENT, componentId = reviewer, name = "Research reviewer",
                savedAt = OffsetDateTime.now(), savedBy = "alice", snapshot = "{}",
            ),
        )

        assertThat(retirement.retire(id)).isEmpty()
        assertThat(names(id)).containsExactlyInAnyOrder("Research writer", "Research reviewer")
    }
}
