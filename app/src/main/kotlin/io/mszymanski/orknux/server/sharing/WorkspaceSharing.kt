package io.mszymanski.orknux.server.sharing

import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One workspace lending another its components. See V405.
 *
 * What a share gives is use, not sight: an agent in the workspace a catalog is
 * lent to reads its variables, and one there answers on a lent provider's
 * models - and the people of that workspace still cannot open the workspace
 * that lent it, nor read the component's page. So everything here is in terms
 * of what an agent may use, and the controller decides who may lend.
 *
 * Distinct from a template, which is a copy that goes its own way: this is
 * the one component, so a price changed or a secret rotated by its owner is
 * what every borrower uses next.
 *
 * Taking a share back takes back its use the same moment: the borrower's
 * agents lose the catalog from their grants and the model they answered on,
 * each with an audit entry in the borrower's own log, rather than keeping a
 * grant to something they may no longer reach.
 */
@Service
class WorkspaceSharing(
    private val shares: WorkspaceShareRepository,
    private val catalogs: VariableCatalogRepository,
    private val providers: ModelProviderRepository,
    private val models: ModelService,
    private val agents: AgentRepository,
    private val workspaces: WorkspaceRepository,
    private val audit: WorkspaceAuditRecorder,
) {

    /** The component's name, or null for one that is gone or is not the owner's. */
    fun componentName(kind: ShareKind, componentId: Long, ownerWorkspaceId: Long): String? = when (kind) {
        ShareKind.VARIABLE_CATALOG -> catalogs.findByIdOrNull(componentId)?.takeIf { it.workspaceId == ownerWorkspaceId }?.name
        ShareKind.MODEL_PROVIDER -> providers.findByIdOrNull(componentId)?.takeIf { it.workspaceId == ownerWorkspaceId }?.name
    }

    @Transactional
    fun share(ownerWorkspaceId: Long, granteeWorkspaceId: Long, kind: ShareKind, componentId: Long, by: String): WorkspaceShare {
        if (ownerWorkspaceId == granteeWorkspaceId) throw ShareToItselfException()
        val name = componentName(kind, componentId, ownerWorkspaceId) ?: throw SharedComponentNotFoundException(componentId)
        val grantee = workspaces.findByIdOrNull(granteeWorkspaceId) ?: throw SharedComponentNotFoundException(granteeWorkspaceId)
        if (shares.existsByGranteeWorkspaceIdAndKindAndComponentId(granteeWorkspaceId, kind, componentId)) {
            throw ShareExistsException(name, grantee.name)
        }
        val owner = workspaces.findByIdOrNull(ownerWorkspaceId)?.name
        val saved = shares.save(
            WorkspaceShare(
                ownerWorkspaceId = ownerWorkspaceId,
                granteeWorkspaceId = granteeWorkspaceId,
                kind = kind,
                componentId = componentId,
                createdBy = by,
            ),
        )
        audit.record(ownerWorkspaceId, WorkspaceAuditCategory.WORKSPACE, "${kind.said()} $name shared with ${grantee.name}")
        audit.record(granteeWorkspaceId, WorkspaceAuditCategory.WORKSPACE, "${kind.said()} $name shared by $owner")
        return saved
    }

    /** Takes a share back, and with it the borrower's use of the component. */
    @Transactional
    fun unshare(share: WorkspaceShare) {
        val name = componentName(share.kind, share.componentId, share.ownerWorkspaceId) ?: "#${share.componentId}"
        val grantee = workspaces.findByIdOrNull(share.granteeWorkspaceId)?.name
        val owner = workspaces.findByIdOrNull(share.ownerWorkspaceId)?.name
        withdraw(share)
        shares.delete(share)
        audit.record(share.ownerWorkspaceId, WorkspaceAuditCategory.WORKSPACE, "${share.kind.said()} $name no longer shared with $grantee")
        audit.record(share.granteeWorkspaceId, WorkspaceAuditCategory.WORKSPACE, "${share.kind.said()} $name no longer shared by $owner")
    }

    /** A component its owner removed: every share of it goes, and its use with it. */
    @Transactional
    fun removed(kind: ShareKind, componentId: Long) {
        shares.findByKindAndComponentId(kind, componentId).forEach { share ->
            withdraw(share)
            shares.delete(share)
        }
    }

    /** The catalogs lent to [workspaceId], each with the share that lends it. */
    fun catalogsLentTo(workspaceId: Long): List<Pair<WorkspaceShare, VariableCatalog>> =
        shares.findByGranteeWorkspaceIdAndKind(workspaceId, ShareKind.VARIABLE_CATALOG).mapNotNull { share ->
            catalogs.findByIdOrNull(share.componentId)?.takeIf { it.workspaceId == share.ownerWorkspaceId }?.let { share to it }
        }

    /** The models of every provider lent to [workspaceId]. */
    fun modelsLentTo(workspaceId: Long): List<LlmModelView> =
        models.modelsOf(shares.findByGranteeWorkspaceIdAndKind(workspaceId, ShareKind.MODEL_PROVIDER).map { it.componentId })

    /** Whether [providerId] is lent to [workspaceId]. */
    fun providerLentTo(workspaceId: Long, providerId: Long): Boolean =
        shares.existsByGranteeWorkspaceIdAndKindAndComponentId(workspaceId, ShareKind.MODEL_PROVIDER, providerId)

    /**
     * The grant a lent catalog is held under in an agent's catalog lists.
     *
     * By id rather than by name: a name is the owner's to change, and agents
     * in another workspace are not rewritten when it does - and a name can be
     * the same as one of the borrower's own catalogs.
     */
    fun grantOf(share: WorkspaceShare): String = "$GRANT_PREFIX${share.componentId}"

    /** The borrower's agents stop using what [share] lent. */
    private fun withdraw(share: WorkspaceShare) {
        val held = agents.findByWorkspaceId(share.granteeWorkspaceId, org.springframework.data.domain.Sort.unsorted())
        when (share.kind) {
            ShareKind.VARIABLE_CATALOG -> {
                val grant = grantOf(share)
                held.filter { grant in it.secretCatalogs || grant in it.variableCatalogs }.forEach { agent ->
                    agent.secretCatalogs.removeAll { it == grant }
                    agent.variableCatalogs.removeAll { it == grant }
                    agents.save(agent)
                }
            }
            ShareKind.MODEL_PROVIDER -> {
                val lent = models.modelsOf(listOf(share.componentId)).map { it.id }.toSet()
                held.filter { it.modelId in lent }.forEach { agent ->
                    agent.modelId = null
                    agents.save(agent)
                    audit.record(
                        share.granteeWorkspaceId,
                        WorkspaceAuditCategory.AGENT,
                        "Agent ${agent.name} has no model: its provider is no longer shared",
                    )
                }
            }
        }
    }

    private fun ShareKind.said(): String = when (this) {
        ShareKind.VARIABLE_CATALOG -> "Variable catalog"
        ShareKind.MODEL_PROVIDER -> "Model provider"
    }

    companion object {
        /** How a lent catalog is named in an agent's grants: `share:<catalog id>`. */
        const val GRANT_PREFIX = "share:"
    }
}

class ShareToItselfException : RuntimeException("A workspace cannot share with itself")

class SharedComponentNotFoundException(val id: Long) : RuntimeException("There is nothing with id $id to share here")

class ShareExistsException(val component: String, val workspace: String) :
    RuntimeException("$component is already shared with $workspace"),
    io.mszymanski.orknux.server.graphql.Refusal {
    override val arguments get() = mapOf("component" to component, "workspace" to workspace)
}
