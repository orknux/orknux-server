package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
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
 * Whether a provider is sent the model's own earlier thinking: off unless
 * somebody turns it on, left alone by a save that does not mention it, and
 * audited when it moves, because it changes what every later request carries.
 * What the wire then does with it is `AgentToolCallTest`'s and
 * `PromptStabilityTest`'s.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProviderSendsReasoningTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a provider created without an answer does not send thinking back`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Laptop", endpoint: "http://llama.example.invalid/v1", secret: "k"
               }) { id sendsReasoning } }""",
        ).execute()
            .path("createModelProvider.sendsReasoning").entity(Boolean::class.java).isEqualTo(false)
            .path("createModelProvider.id").entity(Long::class.java).get()

        assertThat(providers.findById(id).get().sendsReasoning).isFalse()
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("thinking") }
    }

    @Test
    fun `the setting is set on create, kept by a save that leaves it out, and changed with an audit entry`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Reasoner", endpoint: "http://llama.example.invalid/v1",
                 secret: "k", sendsReasoning: true
               }) { id sendsReasoning } }""",
        ).execute()
            .path("createModelProvider.sendsReasoning").entity(Boolean::class.java).isEqualTo(true)
            .path("createModelProvider.id").entity(Long::class.java).get()
        assertThat(audit.findAll().map { it.message }).contains("Model provider Reasoner sends thinking back")

        // Left out, it stays: a caller written before the setting cannot move it.
        update(id, "").path("updateModelProvider.sendsReasoning").entity(Boolean::class.java).isEqualTo(true)
        assertThat(audit.findAll().map { it.message }).doesNotContain("Model provider Reasoner no longer sends thinking back")

        update(id, "sendsReasoning: false")
            .path("updateModelProvider.sendsReasoning").entity(Boolean::class.java).isEqualTo(false)
        assertThat(providers.findById(id).get().sendsReasoning).isFalse()
        assertThat(audit.findAll().map { it.message }).contains("Model provider Reasoner no longer sends thinking back")

        update(id, "sendsReasoning: true")
        graphQlTester.document("""{ modelProvider(id: $id) { sendsReasoning } }""").execute()
            .path("modelProvider.sendsReasoning").entity(Boolean::class.java).isEqualTo(true)
        assertThat(audit.findAll().map { it.message }.filter { it == "Model provider Reasoner sends thinking back" })
            .hasSize(2)
    }

    private fun update(id: Long, extra: String) = graphQlTester.document(
        """mutation { updateModelProvider(id: $id, input: {
             name: "Reasoner", endpoint: "http://llama.example.invalid/v1" $extra
           }) { sendsReasoning } }""",
    ).execute()
}
