package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Duplicating one thing in its own workspace: an agent, an action, a trigger,
 * a condition, an object, a skill, a tool - and a model and a memory, which are
 * not exportable components and are copied by their own services.
 *
 * Each copy arrives under the next free name and leaves the original as it was.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ComponentDuplicateTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "dup-one-${System.nanoTime()}")).id)
    }

    private fun made(mutation: String, path: String): Long =
        graphQlTester.document(mutation).execute().path("$path.id").entity(Long::class.java).get()

    private fun duplicate(kind: String, id: Long): String =
        graphQlTester.document(
            """mutation { duplicateComponent(workspaceId: $workspaceId, kind: $kind, id: $id) { kind name } }""",
        ).execute().path("duplicateComponent.name").entity(String::class.java).get()

    @Test
    fun `every exportable kind is duplicated under the next free name`() {
        val ws = workspaceId
        val agent = made("""mutation { createAgent(input: { workspaceId: $ws, name: "Triage", type: LLM }) { id } }""", "createAgent")
        val tool = made("""mutation { createTool(input: { workspaceId: $ws, name: "forecast" }) { id } }""", "createTool")
        val obj = made("""mutation { createObject(input: { workspaceId: $ws, name: "Ticket" }) { id } }""", "createObject")
        val skill = made("""mutation { createSkill(input: { workspaceId: $ws, name: "Escalating" }) { id } }""", "createSkill")
        val action = made(
            """mutation { createAction(input: { workspaceId: $ws, name: "Pause", type: WAIT, subtype: TIME, durationSeconds: 60 }) { id } }""",
            "createAction",
        )
        val trigger = made(
            """mutation { createTrigger(input: { workspaceId: $ws, name: "Nightly", type: SCHEDULED, cron: "0 2 * * *" }) { id } }""",
            "createTrigger",
        )
        val condition = made(
            """mutation { createCondition(input: {
                 workspaceId: $ws, name: "From alice", type: SLACK, property: MESSAGE_AUTHOR,
                 check: IN_LIST, values: ["alice@example.com"] }) { id } }""",
            "createCondition",
        )

        assertThat(duplicate("AGENT", agent)).isEqualTo("Triage (2)")
        // A name code calls takes the underscore spelling rather than a bracket.
        assertThat(duplicate("TOOL", tool)).isEqualTo("forecast_2")
        assertThat(duplicate("OBJECT", obj)).startsWith("Ticket").isNotEqualTo("Ticket")
        assertThat(duplicate("SKILL", skill)).isEqualTo("Escalating (2)")
        assertThat(duplicate("ACTION", action)).isEqualTo("Pause (2)")
        assertThat(duplicate("TRIGGER", trigger)).isEqualTo("Nightly (2)")
        assertThat(duplicate("CONDITION", condition)).isEqualTo("From alice (2)")
        // And again: the next free one after that.
        assertThat(duplicate("AGENT", agent)).isEqualTo("Triage (3)")
    }

    @Test
    fun `a model and a memory are copied beside the original under (copy)`() {
        val ws = workspaceId
        val provider = made(
            """mutation { createModelProvider(input: { workspaceId: $ws, name: "Stub", endpoint: "https://example.invalid/v1" }) { id } }""",
            "createModelProvider",
        )
        val model = made(
            """mutation { createModel(input: { providerId: $provider, name: "Gemma", modelId: "gemma", kind: CHAT, temperature: 0.3 }) { id } }""",
            "createModel",
        )
        val copy = graphQlTester.document("""mutation { duplicateModel(id: $model) { name modelId temperature } }""").execute()
        copy.path("duplicateModel.name").entity(String::class.java).isEqualTo("Gemma (copy)")
        copy.path("duplicateModel.modelId").entity(String::class.java).isEqualTo("gemma")
        copy.path("duplicateModel.temperature").entity(Double::class.java).isEqualTo(0.3)
        graphQlTester.document("""mutation { duplicateModel(id: $model) { name } }""").execute()
            .path("duplicateModel.name").entity(String::class.java).isEqualTo("Gemma (copy 2)")

        val catalog = made("""mutation { createMemoryCatalog(workspaceId: $ws, name: "Runbooks") { id } }""", "createMemoryCatalog")
        val memory = made(
            """mutation { createMemory(input: { catalogId: $catalog, title: "Deploy steps", content: "Tag, then push." }) { id } }""",
            "createMemory",
        )
        val copied = graphQlTester.document("""mutation { duplicateMemory(id: $memory) { title content } }""").execute()
        copied.path("duplicateMemory.title").entity(String::class.java).isEqualTo("Deploy steps (copy)")
        copied.path("duplicateMemory.content").entity(String::class.java).isEqualTo("Tag, then push.")
    }
}
