package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Reading a workspace's secrets back, tested from both sides of the line.
 *
 * Seeing a workspace is using its secrets, not reading them: somebody who only
 * opens it is refused a secret's value, and is still handed a plain value,
 * which the list shows anyway. A workspace administrator reads them, and so
 * does a role with the SECRETS scope - but only in a workspace that role
 * opens, never in one another of the person's roles opens.
 *
 * Roles are granted by name, as in [WorkspaceAdminRoleTest]: ROLE_BILLING holds
 * the role called `billing`.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class SecretReaderRoleTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
) {

    private var billingId: Long = 0
    private var billingSecret: Long = 0
    private var billingValue: Long = 0
    private var opsSecret: Long = 0

    @BeforeEach
    fun seed() {
        variables.deleteAll()
        catalogs.deleteAll()
        workspaces.deleteAll()
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val billing = roles.save(Role(name = "billing"))
        val leads = roles.save(Role(name = "leads"))
        val readers = roles.save(Role(name = "readers", scopes = mutableSetOf(RoleScope.USER, RoleScope.SECRETS)))
        val ops = roles.save(Role(name = "ops"))

        // billing opens Billing; leads administers it; readers reads secrets only where it is assigned - Ops.
        billingId = requireNotNull(
            workspaces.save(Workspace(name = "Billing", roles = mutableSetOf(billing, leads), adminRoles = mutableSetOf(leads))).id,
        )
        val opsId = requireNotNull(workspaces.save(Workspace(name = "Ops", roles = mutableSetOf(ops, readers))).id)

        billingSecret = secret(billingId, "STRIPE_KEY", VariableKind.SECRET)
        billingValue = secret(billingId, "REGION", VariableKind.VALUE)
        opsSecret = secret(opsId, "PAGER_TOKEN", VariableKind.SECRET)
    }

    private fun secret(workspaceId: Long, name: String, kind: VariableKind): Long {
        val catalog = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "keys-$name")).id)
        return requireNotNull(
            variables.save(WorkspaceVariable(workspaceId = workspaceId, catalogId = catalog, name = name, kind = kind, value = "v-$name")).id,
        )
    }

    private fun reveal(id: Long) = graphQlTester.document("mutation { revealVariable(id: $id) }").execute()

    private fun readsSecrets(workspaceId: Long): Boolean =
        graphQlTester.document("query { workspace(id: $workspaceId) { readsSecrets } }").execute()
            .path("workspace.readsSecrets").entity(Boolean::class.java).get()

    @Test
    @WithMockUser(username = "bob", roles = ["BILLING"])
    fun `seeing a workspace does not read its secrets, though a plain value is still handed back`() {
        reveal(billingSecret).errors().expect { it.message?.contains("needs a role that reads secrets") == true }.verify()
        reveal(billingValue).path("revealVariable").entity(String::class.java).isEqualTo("v-REGION")
        org.assertj.core.api.Assertions.assertThat(readsSecrets(billingId)).isFalse()
    }

    @Test
    @WithMockUser(username = "lena", roles = ["LEADS"])
    fun `administering a workspace reads its secrets`() {
        reveal(billingSecret).path("revealVariable").entity(String::class.java).isEqualTo("v-STRIPE_KEY")
        org.assertj.core.api.Assertions.assertThat(readsSecrets(billingId)).isTrue()
    }

    @Test
    @WithMockUser(username = "rita", roles = ["BILLING", "READERS"])
    fun `a role that reads secrets reads them where it is assigned, and only there`() {
        reveal(opsSecret).path("revealVariable").entity(String::class.java).isEqualTo("v-PAGER_TOKEN")
        // Billing is opened by another of her roles; the reader role is not assigned to it.
        reveal(billingSecret).errors().expect { it.message?.contains("needs a role that reads secrets") == true }.verify()
    }
}
