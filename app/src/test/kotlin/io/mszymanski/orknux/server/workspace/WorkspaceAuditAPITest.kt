package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.EntityLoads
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceAuditAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val repository: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val factory: EntityManagerFactory,
) {

    private val start = OffsetDateTime.parse("2026-08-14T09:00:00+02:00")

    // Filtering by workspace resolves the workspace first, so the ids have to be real.
    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    @BeforeEach
    fun seedAudit() {
        repository.deleteAll()
        workspaces.deleteAll()
        // The roles these tests make; the built-in one is the whole suite's.
        roles.deleteAll(roles.findAll().filterNot { it.builtin })
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "research")).id)
        repository.saveAll(
            listOf(
                WorkspaceAudit(
                    workspaceId = workspaceId,
                    newWorkspaceName = "backend",
                    message = "seeded",
                    operationType = WorkspaceOperationType.ADD,
                    date = start,
                    userId = "alice",
                ),
                WorkspaceAudit(
                    workspaceId = workspaceId,
                    oldWorkspaceName = "backend",
                    newWorkspaceName = "core",
                    message = "seeded",
                    operationType = WorkspaceOperationType.RENAME,
                    date = start.plusHours(1),
                    userId = "bob",
                ),
                WorkspaceAudit(
                    workspaceId = otherWorkspaceId,
                    oldWorkspaceName = "research",
                    message = "seeded",
                    operationType = WorkspaceOperationType.REMOVE,
                    date = start.plusHours(2),
                    userId = "alice",
                ),
            ),
        )
    }

    @Test
    fun `the admin log leaves a workspace's own business to that workspace`() {
        // What a workspace did inside itself: the workspace's log has it, and the
        // the admin log's does not.
        repository.save(
            WorkspaceAudit(
                workspaceId = workspaceId,
                category = WorkspaceAuditCategory.WORKFLOW,
                message = "Condition Is Workspacemate Message created",
                date = start.plusHours(2),
                userId = "alice",
            ),
        )

        val admin = graphQlTester.document(
            """query { workspaceAudit(size: 50) { content { message } } }""",
        ).execute().path("workspaceAudit.content[*].message").entityList(String::class.java).get()
        assertThat(admin).noneMatch { it.contains("Condition") }

        val workspace = graphQlTester.document(
            """query { workspaceActivity(workspaceId: $workspaceId, size: 50) { content { message } } }""",
        ).execute().path("workspaceActivity.content[*].message").entityList(String::class.java).get()
        assertThat(workspace).anyMatch { it.contains("Condition") }
    }

    @Test
    fun `the admin log leaves the issue tracker to the workspace`() {
        // A tracker event is filed under the WORKSPACE category but carries no
        // operationType and belongs to a workspace, so it is that workspace's
        // business - not administration. The old filter matched the category and
        // swept it into the admin log. Issue #410.
        repository.save(
            WorkspaceAudit(
                workspaceId = workspaceId,
                category = WorkspaceAuditCategory.WORKSPACE,
                message = "Issue #42 \"the parser drops a token\" opened",
                date = start.plusHours(3),
                userId = "alice",
            ),
        )

        val admin = graphQlTester.document(
            """query { workspaceAudit(size: 50) { content { message } } }""",
        ).execute().path("workspaceAudit.content[*].message").entityList(String::class.java).get()
        assertThat(admin).noneMatch { it.contains("Issue #42") }

        // But the workspace's own log still has it.
        val workspace = graphQlTester.document(
            """query { workspaceActivity(workspaceId: $workspaceId, size: 50) { content { message } } }""",
        ).execute().path("workspaceActivity.content[*].message").entityList(String::class.java).get()
        assertThat(workspace).anyMatch { it.contains("Issue #42") }
    }

    @Test
    fun `an admin change belonging to no workspace still shows`() {
        // The other half of admin-level: something that belongs to no workspace
        // at all, filed under WORKSPACE with a null id. It has no operationType
        // either, so this is what the null-workspace clause is for. Issue #410.
        repository.save(
            WorkspaceAudit(
                workspaceId = null,
                category = WorkspaceAuditCategory.WORKSPACE,
                message = "Commands in a message are marked with !",
                date = start.plusHours(3),
                userId = "alice",
            ),
        )

        val admin = graphQlTester.document(
            """query { workspaceAudit(size: 50) { content { message } } }""",
        ).execute().path("workspaceAudit.content[*].message").entityList(String::class.java).get()
        assertThat(admin).anyMatch { it.contains("Commands in a message") }
    }

    @Test
    fun `returns every entry newest first`() {
        graphQlTester.document(
            """
            query {
              workspaceAudit {
                content { workspaceId oldWorkspaceName newWorkspaceName operationType date userId }
                totalElements
              }
            }
            """,
        ).execute()
            .path("workspaceAudit.content[*].operationType").entityList(String::class.java)
            .containsExactly("REMOVE", "RENAME", "ADD")
            .path("workspaceAudit.content[0].userId").entity(String::class.java).isEqualTo("alice")
            .path("workspaceAudit.content[0].oldWorkspaceName").entity(String::class.java).isEqualTo("research")
            .path("workspaceAudit.content[0].newWorkspaceName").valueIsNull()
            // timestamptz keeps the instant, not the original offset, so it reads back as UTC.
            .path("workspaceAudit.content[0].date").entity(String::class.java)
            .isEqualTo("2026-08-14T09:00:00Z")
            .path("workspaceAudit.totalElements").entity(Int::class.java).isEqualTo(3)
    }

    @Test
    fun `filters by workspace`() {
        graphQlTester.document("""query { workspaceAudit(workspaceId: $workspaceId) { content { newWorkspaceName } totalElements } }""")
            .execute()
            .path("workspaceAudit.content[*].newWorkspaceName").entityList(String::class.java)
            .containsExactly("core", "backend")
            .path("workspaceAudit.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `pages through entries`() {
        graphQlTester.document("""query { workspaceAudit(page: 0, size: 2) { content { operationType } totalPages } }""")
            .execute()
            .path("workspaceAudit.content[*].operationType").entityList(String::class.java)
            .containsExactly("REMOVE", "RENAME")
            .path("workspaceAudit.totalPages").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""query { workspaceAudit(page: 1, size: 2) { content { operationType } } }""")
            .execute()
            .path("workspaceAudit.content[*].operationType").entityList(String::class.java)
            .containsExactly("ADD")
    }

    /**
     * The people the filter offers: everybody in the log, to an administrator,
     * named once each and in order - the entry of no workspace and the entry of
     * a workspace since deleted included. Issue #616.
     *
     * And named without reading the log: the answer was always right, and it
     * was worked out from every entry ever written, on every load of the
     * filter. The count of entries loaded is what a regression changes.
     */
    @Test
    fun `the users in the log are asked of the database, not read off every entry`() {
        repository.saveAll(
            listOf(
                WorkspaceAudit(workspaceId = null, message = "Default connection changed", date = start, userId = "carol"),
                WorkspaceAudit(workspaceId = 999_999, message = "seeded", date = start, userId = "dave"),
            ),
        )

        val measured = EntityLoads(factory).of(WorkspaceAudit::class) {
            graphQlTester.document("""query { auditUsers }""")
                .execute().path("auditUsers").entityList(String::class.java).get()
        }

        assertThat(measured.answer).containsExactly("alice", "bob", "carol", "dave")
        assertThat(measured.loaded).describedAs("audit entries read to name the users").isZero()
    }

    /**
     * Somebody short of administrator is offered the people of the workspaces
     * they can open, and nobody from an entry that belongs to none.
     */
    @Test
    @WithMockUser(username = "erin", roles = ["BACKEND"])
    fun `the users in the log are only those of the workspaces the reader can open`() {
        val backend = roles.save(Role(name = "backend"))
        val visible = requireNotNull(workspaces.save(Workspace(name = "visible", roles = mutableSetOf(backend))).id)
        repository.saveAll(
            listOf(
                WorkspaceAudit(workspaceId = visible, message = "seeded", date = start, userId = "zed"),
                WorkspaceAudit(workspaceId = visible, message = "seeded", date = start, userId = "erin"),
                WorkspaceAudit(workspaceId = visible, message = "seeded again", date = start, userId = "zed"),
                WorkspaceAudit(workspaceId = null, message = "Default connection changed", date = start, userId = "carol"),
            ),
        )

        graphQlTester.document("""query { auditUsers }""")
            .execute().path("auditUsers").entityList(String::class.java).containsExactly("erin", "zed")
    }
}
