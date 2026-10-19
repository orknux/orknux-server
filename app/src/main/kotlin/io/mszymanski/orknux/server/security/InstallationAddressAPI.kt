package io.mszymanski.orknux.server.security

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/** Admin -> Settings -> Address: where the installation's links point. */
@Controller
class InstallationAddressAPI(
    private val address: InstallationAddress,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun installationAddress(): InstallationAddressView {
        access.requireAdmin()
        return view()
    }

    @MutationMapping
    fun setInstallationAddress(@Argument url: String?): InstallationAddressView {
        access.requireAdmin()
        address.set(url, SecurityContextHolder.getContext().authentication?.name ?: "system")
        val now = address.stored()
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (now == null) "Installation address reset to ${address.configured().ifEmpty { "none" }}" else "Installation address set to $now",
        )
        return view()
    }

    private fun view() = InstallationAddressView(
        url = address.base(),
        stored = address.stored(),
        configured = address.configured(),
    )
}

data class InstallationAddressView(
    /** The address links are built on now. */
    val url: String,
    /** What Admin Settings set, or null where the configuration rules. */
    val stored: String?,
    /** ORKNUX_BASE_URL. */
    val configured: String,
)

@Component
class InstallationAddressExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is InstallationAddressInvalidException -> refused(exception, ErrorType.BAD_REQUEST, environment)
            else -> null
        }
}
