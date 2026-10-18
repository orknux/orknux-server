package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.nio.charset.StandardCharsets

/**
 * Models carried between workspaces as a file: the ones chosen, under their
 * providers, with no key - and read in without making a second copy of what is
 * already there.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelTransferTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val api: ModelTransferAPI,
) {

    @Test
    fun `the chosen models travel under their providers without a key, and read in once`() {
        val from = requireNotNull(workspaces.save(Workspace(name = "models from ${System.nanoTime()}")).id)
        val into = requireNotNull(workspaces.save(Workspace(name = "models into ${System.nanoTime()}")).id)
        val provider = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $from, name: "Lab", type: OPENAI, endpoint: "http://lab.invalid/v1", secret: "sk-secret"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        fun model(name: String, kind: String) = graphQlTester.document(
            """mutation { createModel(input: { providerId: $provider, name: "$name", modelId: "$name-id", kind: $kind }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val chat = model("Talker", "CHAT")
        val image = model("Drawer", "IMAGE")
        model("Left behind", "CHAT")

        val file = String(requireNotNull(api.export(from, "$chat,$image").body), StandardCharsets.UTF_8)
        assertThat(file).contains("orknux-models", "Talker", "Drawer", "http://lab.invalid/v1").doesNotContain("sk-secret", "Left behind")

        fun import() = graphQlTester.document("mutation(\$w: ID!, \$c: String!) { importModels(workspaceId: \$w, content: \$c) { providers needsKey models skipped } }")
            .variable("w", into).variable("c", file).execute()
        import()
            .path("importModels.providers").entityList(String::class.java).containsExactly("Lab")
            .path("importModels.needsKey").entityList(String::class.java).containsExactly("Lab")
            .path("importModels.models").entityList(String::class.java).containsExactly("Lab / Drawer", "Lab / Talker")
        val arrived = requireNotNull(providers.findByWorkspaceIdAndName(into, "Lab"))
        assertThat(arrived.secret).isNull()
        assertThat(models.findByProviderId(requireNotNull(arrived.id)).map { it.name to it.kind.name })
            .containsExactlyInAnyOrder("Talker" to "CHAT", "Drawer" to "IMAGE")

        // Read again: the provider here is used, and its models are left as they are.
        import()
            .path("importModels.providers").entityList(String::class.java).hasSize(0)
            .path("importModels.skipped").entityList(String::class.java).containsExactly("Lab / Drawer", "Lab / Talker")
        assertThat(providers.findByWorkspaceId(into, org.springframework.data.domain.Sort.unsorted())).hasSize(1)

        graphQlTester.document("""mutation { importModels(workspaceId: $into, content: "{\"format\":\"orknux-workspace\"}") { models } }""")
            .execute().errors().satisfy {
                assertThat(it.single().message).isEqualTo("That is not a models file.")
                assertThat(it.single().extensions["code"]).isEqualTo("ModelFileRefused")
            }
    }
}
