package io.mszymanski.orknux.server.trigger

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
 * The triggers list narrowed by where a trigger listens, on the server - the
 * list is paged, so a sieve in the browser would hide matches on a later
 * page - and the sources offered for it, which are only the ones in use.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TriggerSourceFilterTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "sources-${System.nanoTime()}")).id)
        listOf("Nightly", "Hourly").forEach { name ->
            graphQlTester.document(
                """mutation { createTrigger(input: { workspaceId: $workspaceId, name: "$name", type: SCHEDULED, cron: "0 2 * * *", timezone: "UTC" }) { id } }""",
            ).execute().path("createTrigger.id").hasValue()
        }
    }

    private fun count(source: String?) = graphQlTester.document(
        """query { workspaceTriggers(workspaceId: $workspaceId, page: 0, size: 50, source: ${source?.let { "\"$it\"" }}) { totalElements content { source } } }""",
    ).execute()

    @Test
    fun `the sieve keeps the triggers of one source, and offers only the sources in use`() {
        count("cron").path("workspaceTriggers.totalElements").entity(Int::class.java).isEqualTo(2)
        count("webhook").path("workspaceTriggers.totalElements").entity(Int::class.java).isEqualTo(0)
        count("connection:999999").path("workspaceTriggers.totalElements").entity(Int::class.java).isEqualTo(0)
        // A value nothing matches sieves to nothing rather than being refused.
        count("elsewhere").path("workspaceTriggers.totalElements").entity(Int::class.java).isEqualTo(0)
        count(null).path("workspaceTriggers.totalElements").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("query { workspaceTriggerSources(workspaceId: $workspaceId) { value label } }").execute()
            .path("workspaceTriggerSources[*].value").entityList(String::class.java).containsExactly("cron")
            .path("workspaceTriggerSources[0].label").entity(String::class.java).isEqualTo("Cron")
    }

    @Test
    fun `the sieve and the search apply together`() {
        graphQlTester.document(
            """query { workspaceTriggers(workspaceId: $workspaceId, page: 0, size: 50, source: "cron", search: "night") { totalElements content { name } } }""",
        ).execute()
            .path("workspaceTriggers.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("workspaceTriggers.content[0].name").entity(String::class.java).isEqualTo("Nightly")
    }
}
