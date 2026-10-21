package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SecretVariables
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Variables belong to this module, so it is what answers when the connection
 * module asks what one of them holds.
 *
 * The value comes out of the column through `SecretConverter` like every other
 * credential here, so nothing on this side decrypts anything; the row is read
 * and handed over. The workspace is checked here rather than trusted from the
 * caller, which is what makes a provider in one workspace unable to name a
 * secret in another however its id was arrived at - unless that workspace
 * lends it the catalog the secret is in.
 */
@Service
class AppSecretVariables(
    private val variables: WorkspaceVariableRepository,
    private val catalogs: VariableCatalogRepository,
    @param:org.springframework.context.annotation.Lazy private val sharing: io.mszymanski.orknux.server.sharing.WorkspaceSharing,
) : SecretVariables {

    override fun find(workspaceId: Long, variableId: Long): HeldSecret? {
        val variable = variables.findByIdOrNull(variableId)?.takeIf { sharing.usable(workspaceId, it) } ?: return null
        return HeldSecret(
            id = requireNotNull(variable.id),
            name = variable.name,
            // The dash is what `VariableAPI` shows for a catalog that has gone;
            // a missing folder is not a reason to fail reading a provider.
            catalog = catalogs.findByIdOrNull(variable.catalogId)?.name ?: "—",
            secret = variable.kind == VariableKind.SECRET,
            value = variable.value,
        )
    }
}
