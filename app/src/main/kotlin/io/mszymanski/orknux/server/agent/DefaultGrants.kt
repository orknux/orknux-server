package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The memory catalogs, plugin skills and connections an agent holds without
 * anybody ticking them.
 *
 * A new agent starts with every memory catalog its workspace has and every
 * plugin's skill catalog; a memory catalog made later, or a plugin that brings
 * skills for the first time, is granted to the agents already there. All of it
 * can be unticked on the agent - a grant given by default is still a grant -
 * and a plugin re-uploaded with the same skills does not hand back a catalog
 * somebody took away: only a catalog appearing for the first time is granted.
 *
 * Except to an agent something else in the product made to answer from its
 * prompt alone - see [GrantExemptions]: a catalog granted to it by default
 * is a set of tools it was made not to have.
 */
@Service
class DefaultGrants(
    private val agents: AgentRepository,
    private val memoryCatalogs: MemoryCatalogRepository,
    private val pluginSkills: PluginSkills,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val connections: io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository,
    private val exemptions: org.springframework.beans.factory.ObjectProvider<GrantExemptions>,
) {

    /** The agents of [workspaceId] given nothing by default. */
    private fun exempt(workspaceId: Long): Set<Long> = exemptions.orderedStream().toList().flatMap { it.exempt(workspaceId) }.toSet()

    /** The connections of [workspaceId] marked to be granted to a new agent, by id. */
    fun connectionsFor(workspaceId: Long): MutableList<Long> =
        connections.findByWorkspaceId(workspaceId, org.springframework.data.domain.Sort.by("name"))
            .filter { it.grantNewAgents }
            .mapNotNull { it.id }
            .toMutableList()

    /** Every memory catalog of [workspaceId], by name, for a new agent. */
    fun memoryCatalogsFor(workspaceId: Long): MutableList<String> =
        memoryCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map { it.name }.toMutableList()

    /** Every plugin's skill catalog, for a new agent; the server's own skills reach it another way. */
    fun pluginSkillCatalogs(): MutableList<String> =
        pluginSkills.catalogs().map { it.name }.filter { it != BuiltInSkills.CATALOG }.distinct().toMutableList()

    /** A memory catalog just made in [workspaceId], granted to the agents already there. */
    @Transactional
    fun memoryCatalogAdded(workspaceId: Long, catalog: String) {
        val exempt = exempt(workspaceId)
        val given = agents.findByWorkspaceId(workspaceId, org.springframework.data.domain.Sort.by("id"))
            .filter { catalog !in it.memoryCatalogs && it.id !in exempt }
        given.forEach { it.memoryCatalogs.add(catalog) }
        agents.saveAll(given)
        if (given.isNotEmpty()) {
            auditRecorder.record(
                workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Memory catalog $catalog granted to ${given.size} agent${if (given.size == 1) "" else "s"} by default",
            )
        }
    }

    /** A plugin's skill catalog appearing for the first time, granted to every agent there is. */
    @Transactional
    fun pluginSkillsAdded(catalog: String) {
        val exempt = agents.findAll().map { it.workspaceId }.distinct().flatMap(::exempt).toSet()
        val given = agents.findAll().filter { catalog !in it.skillCatalogs && it.id !in exempt }
        given.forEach { it.skillCatalogs.add(catalog) }
        agents.saveAll(given)
        given.groupBy { it.workspaceId }.forEach { (workspaceId, held) ->
            auditRecorder.record(
                workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Skills of $catalog granted to ${held.size} agent${if (held.size == 1) "" else "s"} by default",
            )
        }
    }
}

/**
 * Agents that are given no catalog by default: made by the product to answer
 * from their prompt, and offered no tools on purpose. A grant ticked on one of
 * them by hand is still theirs; only the grants nobody ticked pass them by.
 */
fun interface GrantExemptions {
    /** The ids of [workspaceId]'s agents to leave out. */
    fun exempt(workspaceId: Long): Set<Long>
}
