package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Takes away an agent's grant of a memory or variable catalog that names no
 * catalog in its workspace.
 *
 * A grant is a name. A memory catalog renamed before its agents followed it,
 * or an agent copied from a workspace whose catalogs this one does not have,
 * left a grant naming nothing: harmless to the agent, which finds no catalog
 * by it, but drawn on its form as "not in this workspace" and counted as a
 * grant ("3 of 2 granted"). Renames carry their grants now; this clears what
 * they left behind, once per start, each removal in the workspace's audit.
 *
 * Skill catalogs are left alone: an agent also holds skills the server and
 * its plugins bring, and a name that is no workspace catalog here may be one
 * of those.
 */
@Component
class DanglingCatalogGrants(
    private val agents: AgentRepository,
    private val memoryCatalogs: MemoryCatalogRepository,
    private val variableCatalogs: VariableCatalogRepository,
    private val audit: WorkspaceAuditRecorder,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val writing = TransactionTemplate(transactions)

    @EventListener(ApplicationReadyEvent::class)
    fun sweep() {
        agents.findAll().forEach { agent ->
            runCatching { clear(agent) }
                .onFailure { log.warn("Could not look for agent {}'s dangling catalog grants", agent.id, it) }
        }
    }

    /** @return the grants taken away, as the audit words them. */
    fun clear(agent: Agent): List<String> {
        val workspaceId = agent.workspaceId
        val memory = memoryCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map { it.name }.toSet()
        val variable = variableCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map { it.name }.toSet()
        val gone = agent.memoryCatalogs.filter { it !in memory }.map { "memory catalog $it" } +
            agent.secretCatalogs.filter { it !in variable }.map { "secrets of $it" } +
            agent.variableCatalogs.filter { it !in variable }.map { "values of $it" }
        if (gone.isEmpty()) return gone

        ActingIn.run(workspaceId, ACTOR) {
            writing.executeWithoutResult {
                val held = agents.findById(requireNotNull(agent.id)).orElse(null) ?: return@executeWithoutResult
                held.memoryCatalogs = held.memoryCatalogs.filter { it in memory }.toMutableList()
                held.secretCatalogs = held.secretCatalogs.filter { it in variable }.toMutableList()
                held.variableCatalogs = held.variableCatalogs.filter { it in variable }.toMutableList()
                agents.save(held)
                gone.forEach {
                    audit.record(workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${held.name} no longer granted $it: not in this workspace")
                }
            }
        }
        return gone
    }

    private companion object {
        const val ACTOR = "Orknux"
    }
}
