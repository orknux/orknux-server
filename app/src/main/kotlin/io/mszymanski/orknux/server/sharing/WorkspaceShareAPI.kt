package io.mszymanski.orknux.server.sharing

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import java.time.format.DateTimeFormatter

/**
 * Workspace Settings > Sharing: what a workspace lends others, and what it is
 * lent. See [WorkspaceSharing].
 *
 * Lending is an administrator's of the workspace that owns the component, and
 * only to a workspace that administrator can see - so nobody learns another
 * workspace's name from the picker. What a workspace is lent is readable by
 * anybody who can see it, because its agents use it: the agent form lists
 * the lent catalogs and models beside its own.
 */
@Controller
class WorkspaceShareAPI(
    private val sharing: WorkspaceSharing,
    private val shares: WorkspaceShareRepository,
    private val access: WorkspaceAccess,
    private val workspaces: WorkspaceRepository,
) {

    /** What [workspaceId] lends, and to whom. Its administrators. */
    @QueryMapping
    fun workspaceSharesGiven(@Argument workspaceId: Long): List<WorkspaceShareView> {
        access.requireAdministers(workspaceId)
        return shares.findByOwnerWorkspaceIdOrderByCreatedAtAsc(workspaceId).map(::view)
    }

    /** What other workspaces lend [workspaceId]. Anybody who can see it. */
    @QueryMapping
    fun workspaceSharesReceived(@Argument workspaceId: Long): List<WorkspaceShareView> {
        access.requireVisible(workspaceId)
        return shares.findByGranteeWorkspaceIdOrderByCreatedAtAsc(workspaceId).map(::view)
    }

    /** The models of the providers lent to [workspaceId], for its agents to answer on. */
    @QueryMapping
    fun sharedModels(@Argument workspaceId: Long): List<LlmModelView> {
        access.requireVisible(workspaceId)
        return sharing.modelsLentTo(workspaceId)
    }

    @MutationMapping
    fun shareWithWorkspace(@Argument input: ShareInput): WorkspaceShareView {
        access.requireAdministers(input.workspaceId)
        // A workspace the caller cannot see is one that does not exist, as everywhere else.
        access.requireVisible(input.granteeWorkspaceId)
        val by = SecurityContextHolder.getContext().authentication?.name ?: "system"
        return view(sharing.share(input.workspaceId, input.granteeWorkspaceId, input.kind, input.componentId, by))
    }

    @MutationMapping
    fun removeWorkspaceShare(@Argument id: Long): Boolean {
        val share = shares.findByIdOrNull(id) ?: return false
        access.requireAdministers(share.ownerWorkspaceId)
        sharing.unshare(share)
        return true
    }

    private fun view(share: WorkspaceShare): WorkspaceShareView {
        val names = workspaces.findAllById(listOf(share.ownerWorkspaceId, share.granteeWorkspaceId)).associate { it.id to it.name }
        return WorkspaceShareView(
            id = requireNotNull(share.id),
            kind = share.kind,
            componentId = share.componentId,
            componentName = sharing.componentName(share.kind, share.componentId, share.ownerWorkspaceId),
            grant = sharing.grantOf(share),
            ownerWorkspaceId = share.ownerWorkspaceId,
            ownerWorkspaceName = names[share.ownerWorkspaceId],
            granteeWorkspaceId = share.granteeWorkspaceId,
            granteeWorkspaceName = names[share.granteeWorkspaceId],
            createdAt = share.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            createdBy = share.createdBy,
        )
    }
}

data class ShareInput(
    val workspaceId: Long,
    val granteeWorkspaceId: Long,
    val kind: ShareKind,
    val componentId: Long,
)

data class WorkspaceShareView(
    val id: Long,
    val kind: ShareKind,
    val componentId: Long,
    /** Null for a component that has gone since. */
    val componentName: String?,
    /** What an agent's catalog grant holds for a lent catalog. */
    val grant: String,
    val ownerWorkspaceId: Long,
    val ownerWorkspaceName: String?,
    val granteeWorkspaceId: Long,
    val granteeWorkspaceName: String?,
    val createdAt: String,
    val createdBy: String,
)

@Component
class WorkspaceShareExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val type = when (exception) {
            is ShareToItselfException, is ShareExistsException -> ErrorType.BAD_REQUEST
            is SharedComponentNotFoundException -> ErrorType.NOT_FOUND
            else -> return null
        }
        return refused(exception, type, environment)
    }
}
