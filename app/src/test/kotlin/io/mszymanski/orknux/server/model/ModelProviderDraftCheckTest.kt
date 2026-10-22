package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Sort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Test on the provider form checks what the form holds and saves nothing.
 * Asked: why does Test save the provider - it did, because the check could read
 * only a stored row.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelProviderDraftCheckTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val providers: ModelProviderRepository,
) {

    @Test
    fun `a draft is checked without being saved, and an edit is checked without changing the stored one`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "draft check ${System.nanoTime()}")).id)

        graphQlTester.document(
            """mutation { checkModelProviderDraft(input: {
                 workspaceId: $workspace, name: "Lab", type: OPENAI, endpoint: "http://lab.invalid/v1", secret: "sk-x"
               }) { connected message } }""",
        ).execute().path("checkModelProviderDraft.connected").entity(Boolean::class.java).isEqualTo(false)
        assertThat(providers.findByWorkspaceId(workspace, Sort.unsorted())).isEmpty()

        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspace, name: "Lab", type: OPENAI, endpoint: "http://lab.invalid/v1"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { checkModelProviderDraft(id: $id, input: {
                 workspaceId: $workspace, name: "Renamed", type: OPENAI, endpoint: "http://other.invalid/v1"
               }) { connected message } }""",
        ).execute().path("checkModelProviderDraft.connected").entity(Boolean::class.java).isEqualTo(false)
        val stored = requireNotNull(providers.findByWorkspaceIdAndName(workspace, "Lab"))
        assertThat(stored.endpoint).isEqualTo("http://lab.invalid/v1")
    }
}
