package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A workspace's settings are its admins' to change, and who its admins are is
 * chosen on its own settings page, from among its members.
 *
 * Tested from both sides of the line, as the role-based half is: a member can
 * see the workspace and still cannot change a setting; made a workspace admin,
 * they can; and an installation administrator cannot be demoted there, because
 * they administer everywhere by a role nobody here gave them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class WorkspaceMemberTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val adminUsers: WorkspaceAdminUserRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun seed() {
        adminUsers.deleteAll()
        val suffix = System.nanoTime()
        val crew = roles.findByName(CREW) ?: roles.save(Role(name = CREW))
        val admins = roles.findByName(ADMINS) ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(io.mszymanski.orknux.server.security.RoleScope.ADMIN)))
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "crew-$suffix", roles = mutableSetOf(crew))).id)
        users.findByUsername(MEMBER) ?: users.save(AppUser(username = MEMBER, displayName = "Dana", type = UserType.INTERNAL, roles = mutableSetOf(crew)))
        users.findByUsername(BOSS) ?: users.save(AppUser(username = BOSS, displayName = "Alice", type = UserType.INTERNAL, roles = mutableSetOf(admins)))
    }

    private fun flipTimestamps() = graphQlTester.document(
        """mutation { setWorkspaceChatTimestamps(workspaceId: $workspaceId, shown: true) { id } }""",
    ).execute()

    @Test
    @WithMockUser(username = MEMBER, roles = ["CREW"])
    fun `a member sees the workspace and cannot change a setting`() {
        graphQlTester.document("query { workspace(id: $workspaceId) { id administered } }").execute()
            .path("workspace.administered").entity(Boolean::class.java).isEqualTo(false)
        flipTimestamps().errors().expect { it.extensions["code"] == "WorkspaceAdminRequired" }.verify()
        graphQlTester.document("query { workspaceMembers(workspaceId: $workspaceId) { username } }").execute()
            .errors().expect { it.extensions["code"] == "WorkspaceAdminRequired" }.verify()
    }

    @Test
    @WithMockUser(username = BOSS, roles = ["ADMINS"])
    fun `an administrator lists the members and makes one a workspace admin`() {
        val listed = graphQlTester.document("query { workspaceMembers(workspaceId: $workspaceId) { username role fixed through } }").execute()
        listed.path("workspaceMembers[*].username").entityList(String::class.java).contains(MEMBER, BOSS)
        listed.path("workspaceMembers[0].fixed").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $workspaceId, username: "$MEMBER", role: ADMIN) { role } }""")
            .execute().path("setWorkspaceMemberRole.role").entity(String::class.java).isEqualTo("ADMIN")
        assertThat(adminUsers.existsByIdWorkspaceIdAndIdUsername(workspaceId, MEMBER)).isTrue()

        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $workspaceId, username: "$BOSS", role: USER) { role } }""")
            .execute().errors().expect { it.extensions["code"] == "WorkspaceMemberFixed" }.verify()
        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $workspaceId, username: "nobody-$workspaceId", role: ADMIN) { role } }""")
            .execute().errors().expect { it.extensions["code"] == "WorkspaceMemberUnknown" }.verify()
    }

    @Test
    @WithMockUser(username = BOSS, roles = ["ADMINS"])
    fun `somebody let in by a directory group is a member once they have signed in`() {
        val crew = requireNotNull(roles.findByName(CREW)?.id)
        val visitor = "visitor-${System.nanoTime()}"
        users.save(AppUser(username = visitor, displayName = "Visitor", type = UserType.EXTERNAL, resolvedRoles = crew.toString()))

        graphQlTester.document("query { workspaceMembers(workspaceId: $workspaceId) { username through } }").execute()
            .path("workspaceMembers[*].username").entityList(String::class.java).contains(visitor)
        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $workspaceId, username: "$visitor", role: ADMIN) { role } }""")
            .execute().path("setWorkspaceMemberRole.role").entity(String::class.java).isEqualTo("ADMIN")
    }

    @Test
    @WithMockUser(username = MEMBER, roles = ["CREW"])
    fun `made a workspace admin, a member changes its settings and its other members`() {
        adminUsers.save(WorkspaceAdminUser(id = WorkspaceAdminUserId(workspaceId, MEMBER), grantedBy = BOSS))

        graphQlTester.document("query { workspace(id: $workspaceId) { administered } }").execute()
            .path("workspace.administered").entity(Boolean::class.java).isEqualTo(true)
        flipTimestamps().path("setWorkspaceChatTimestamps.id").hasValue()
        graphQlTester.document("""mutation { setWorkspaceMemberRole(workspaceId: $workspaceId, username: "$MEMBER", role: USER) { role } }""")
            .execute().path("setWorkspaceMemberRole.role").entity(String::class.java).isEqualTo("USER")
        // And having stepped down, cannot step back up.
        flipTimestamps().errors().expect { it.extensions["code"] == "WorkspaceAdminRequired" }.verify()
    }

    private companion object {
        const val CREW = "crew"
        const val ADMINS = "Admins"
        const val MEMBER = "dana-member"
        const val BOSS = "alice-boss"
    }
}
