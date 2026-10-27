package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Removing a member from one workspace keeps them out of that workspace and
 * nowhere else.
 *
 * Driven as the people involved, each in turn: the workspace admin who removes,
 * the member removed - who must lose the workspace from the list, from a query
 * by id and from every resolver that asks [WorkspaceAccess.requireVisible],
 * while the other workspace the same role opens stays theirs - and the
 * installation administrator, who cannot be removed because they see every
 * workspace by definition.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class WorkspaceMemberExclusionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val adminUsers: WorkspaceAdminUserRepository,
    @Autowired val exclusions: WorkspaceExcludedUserRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val access: WorkspaceAccess,
) {

    private var acmeId = 0L
    private var otherId = 0L
    private lateinit var acmeName: String
    private lateinit var dana: String
    private lateinit var lead: String
    private lateinit var boss: String

    /** Names of its own each time: this class shares the database with the rest of the suite. */
    @BeforeEach
    fun seed() {
        val n = System.nanoTime()
        val crew = roles.save(Role(name = "exclusion-crew-$n"))
        val admins = roles.save(Role(name = "exclusion-admins-$n", scopes = mutableSetOf(RoleScope.ADMIN)))
        acmeName = "Acme Support $n"
        acmeId = requireNotNull(workspaces.save(Workspace(name = acmeName, roles = mutableSetOf(crew))).id)
        otherId = requireNotNull(workspaces.save(Workspace(name = "Other $n", roles = mutableSetOf(crew))).id)
        dana = users.save(AppUser(username = "dana-$n", displayName = "Dana Whitfield", type = UserType.INTERNAL, roles = mutableSetOf(crew))).username
        lead = users.save(AppUser(username = "lead-$n", displayName = "Lee Ader", type = UserType.INTERNAL, roles = mutableSetOf(crew))).username
        boss = users.save(AppUser(username = "boss-$n", displayName = "Alice", type = UserType.INTERNAL, roles = mutableSetOf(admins))).username
        adminUsers.save(WorkspaceAdminUser(id = WorkspaceAdminUserId(acmeId, lead), grantedBy = boss))
    }

    @AfterEach
    fun leave() = SecurityContextHolder.clearContext()

    private fun signIn(username: String) {
        val token = UsernamePasswordAuthenticationToken(username, "n/a", listOf(SimpleGrantedAuthority("ROLE_USER")))
        SecurityContextHolder.getContext().authentication = token
    }

    private fun exclude(username: String) = graphQlTester.document(
        """mutation { excludeWorkspaceMember(workspaceId: $acmeId, username: "$username") { username excluded role } }""",
    ).execute()

    private fun allow(username: String) = graphQlTester.document(
        """mutation { allowWorkspaceMember(workspaceId: $acmeId, username: "$username") { username excluded } }""",
    ).execute()

    private fun visibleIds(): List<String> = graphQlTester.document("query { workspaces(size: 1000) { content { id } } }")
        .execute().path("workspaces.content[*].id").entityList(String::class.java).get()

    private fun auditLines(): List<String> =
        audit.findByWorkspaceId(acmeId, PageRequest.of(0, 50)).content.map { it.message }

    @Test
    fun `a removed member loses this workspace everywhere and keeps the other one`() {
        signIn(dana)
        assertThat(visibleIds()).contains(acmeId.toString(), otherId.toString())

        signIn(lead)
        exclude(dana).path("excludeWorkspaceMember.excluded").entity(Boolean::class.java).isEqualTo(true)
        assertThat(auditLines()).contains("Dana Whitfield removed from $acmeName")
        graphQlTester.document("query { workspaceMembers(workspaceId: $acmeId) { username excluded } }").execute()
            .path("workspaceMembers[?(@.username == '$dana')].excluded").entityList(Boolean::class.java).containsExactly(true)

        signIn(dana)
        assertThat(visibleIds()).contains(otherId.toString()).doesNotContain(acmeId.toString())
        graphQlTester.document("query { workspace(id: $acmeId) { id } }").execute()
            .path("workspace").valueIsNull()
        graphQlTester.document("query { variableTypes(workspaceId: $acmeId) { key } }").execute()
            .errors().expect { it.extensions["code"] == "WorkspaceNotFound" }.verify()
        graphQlTester.document("query { variableTypes(workspaceId: $otherId) { key } }").execute().errors().verify()
        assertThat(access.canSee(acmeId)).isFalse()
        assertThat(access.canSee(otherId)).isTrue()
        assertThatThrownBy { access.requireVisible(acmeId) }.isInstanceOf(WorkspaceNotFoundException::class.java)
        // Their roles are untouched: removing is the workspace's decision, not a change to the person.
        assertThat(requireNotNull(users.findByUsername(dana)).roles).hasSize(1)

        signIn(lead)
        allow(dana).path("allowWorkspaceMember.excluded").entity(Boolean::class.java).isEqualTo(false)
        assertThat(auditLines()).contains("Dana Whitfield allowed back into $acmeName")
        signIn(dana)
        assertThat(visibleIds()).contains(acmeId.toString(), otherId.toString())
        graphQlTester.document("query { workspace(id: $acmeId) { id } }").execute()
            .path("workspace.id").entity(String::class.java).isEqualTo(acmeId.toString())
    }

    @Test
    fun `an installation administrator cannot be removed`() {
        signIn(lead)
        exclude(boss).errors().expect { it.extensions["code"] == "WorkspaceMemberNotRemovable" }.verify()
        assertThat(exclusions.existsByIdWorkspaceIdAndIdUsername(acmeId, boss)).isFalse()
    }

    @Test
    fun `somebody who does not administer the workspace cannot remove anyone`() {
        signIn(dana)
        exclude(lead).errors().expect { it.extensions["code"] == "WorkspaceAdminRequired" }.verify()
        allow(lead).errors().expect { it.extensions["code"] == "WorkspaceAdminRequired" }.verify()
        assertThat(exclusions.existsByIdWorkspaceIdAndIdUsername(acmeId, lead)).isFalse()
    }

    @Test
    fun `a named workspace admin who is removed stops administering, and comes back an ordinary member`() {
        signIn(boss)
        exclude(lead).path("excludeWorkspaceMember.role").entity(String::class.java).isEqualTo("USER")
        assertThat(adminUsers.existsByIdWorkspaceIdAndIdUsername(acmeId, lead)).isFalse()
        assertThat(auditLines()).contains("Lee Ader removed from $acmeName, and no longer a workspace admin")
        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $acmeId, username: "$lead", role: ADMIN) { role } }""")
            .execute().errors().expect { it.extensions["code"] == "WorkspaceMemberExcluded" }.verify()

        signIn(lead)
        graphQlTester.document("query { workspaceMembers(workspaceId: $acmeId) { username } }").execute()
            .errors().expect { it.extensions["code"] == "WorkspaceNotFound" }.verify()

        signIn(boss)
        allow(lead)
        signIn(lead)
        graphQlTester.document("query { workspace(id: $acmeId) { administered } }").execute()
            .path("workspace.administered").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `the last workspace admin cannot remove themselves, and can once there is another`() {
        signIn(lead)
        exclude(lead).errors().expect { it.extensions["code"] == "WorkspaceLastAdministrator" }.verify()
        assertThat(exclusions.existsByIdWorkspaceIdAndIdUsername(acmeId, lead)).isFalse()

        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $acmeId, username: "$dana", role: ADMIN) { role } }""")
            .execute().path("setWorkspaceMemberRole.role").entity(String::class.java).isEqualTo("ADMIN")
        exclude(lead).path("excludeWorkspaceMember.excluded").entity(Boolean::class.java).isEqualTo(true)
        assertThat(access.canSee(acmeId)).isFalse()
    }

    @Test
    fun `deleting the workspace takes its removals with it`() {
        exclusions.save(WorkspaceExcludedUser(id = WorkspaceExcludedUserId(acmeId, dana), excludedBy = boss))
        workspaces.deleteById(acmeId)
        assertThat(exclusions.findAllByIdWorkspaceId(acmeId)).isEmpty()
    }
}
