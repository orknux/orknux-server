package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Takes away the Research planner, writer and reviewer that versions before
 * 1.2.3 made in every workspace, where they are still exactly what was made.
 *
 * 1.2.3 stopped making them - a research runs with agents somebody picks - but
 * left the ones already made, so every installation that had run an earlier
 * version still listed three agents nobody had asked for. The product owner
 * had said Orknux should not create them; leaving them behind is the same as
 * creating them.
 *
 * Only an agent that is still the product's is taken: the name and the
 * description it was made with, never saved since (no revision recorded), and
 * nothing pointing at it. One somebody renamed, edited, put in a workflow or
 * gave another agent to ask is theirs and stays. Each is deleted on its own,
 * so one that cannot go - a chat that still holds it - leaves the rest to go.
 */
@Component
class SeededResearchAgents(
    private val agents: AgentRepository,
    private val revisions: ComponentRevisionRepository,
    private val dependants: ComponentDependants,
    private val audit: WorkspaceAuditRecorder,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val writing = TransactionTemplate(transactions)

    /** @return the names of those taken away in [workspaceId]. */
    fun retire(workspaceId: Long): List<String> =
        agents.findByWorkspaceId(workspaceId, Pageable.unpaged()).content
                        /*
             * By the description's first sentence, which every version made
             * them with; the sentence after it, about which model the agent
             * thinks with, was reworded between versions, and matching it whole
             * found none of the agents an installation actually had.
             */
            .filter { agent -> SEEDED.any { it.name == agent.name && agent.description?.startsWith(firstSentence(it.description)) == true } }
            .mapNotNull { agent ->
                val id = agent.id ?: return@mapNotNull null
                if (revisions.idsNewestFirst(ComponentRevisionKind.AGENT, id).isNotEmpty()) return@mapNotNull null
                if (dependants.of(DependencyKind.AGENT, id).isNotEmpty()) return@mapNotNull null
                runCatching {
                    // At start-up nobody is signed in; the audit says the product did it.
                    io.mszymanski.orknux.server.security.ActingIn.run(workspaceId, ACTOR) {
                        writing.executeWithoutResult {
                            agents.deleteById(id)
                            audit.record(
                                workspaceId,
                                WorkspaceAuditCategory.AGENT,
                                "Agent ${agent.name} deleted: made by an earlier version for research, and never used",
                            )
                        }
                    }
                    agent.name
                }.onFailure { log.info("Kept agent {} of workspace {}: {}", agent.name, workspaceId, it.message) }
                    .getOrNull()
            }

    private companion object {
        fun firstSentence(description: String): String = description.substringBefore(". ") + "."

        const val ACTOR = "Orknux"
        val SEEDED = listOf(ResearchModes.PLANNER, ResearchModes.WRITER, ResearchModes.REVIEWER)
    }
}
