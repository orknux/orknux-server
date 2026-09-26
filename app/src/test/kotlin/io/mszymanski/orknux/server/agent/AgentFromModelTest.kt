package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Making an agent out of a model, in one press, from the Models screen.
 *
 * The other half of issue #295. Taking the bare model away closed the short path
 * somebody had for finding out whether a model they had just added actually
 * works, and what was left in its place was: build an agent by hand, name it,
 * choose its model, save it, chat to it, delete it. This is that path, one press
 * long, and what it leaves behind is a real agent rather than something to throw
 * away.
 *
 * Three things are worth holding, and each of them is a way the shortcut could
 * have been a mistake rather than a convenience.
 *
 * It is granted **nothing**. An action that quietly handed out tools because it
 * was being helpful would be the worst place in this product to be generous, and
 * "bare agent" has to mean bare or the word is doing no work.
 *
 * It **does not collide**. `uk_agent_workspace_name` is a unique constraint and
 * pressing a button twice is the most ordinary thing anybody does, so the name
 * is derived and then retried rather than derived and hoped for.
 *
 * It **refuses a model that cannot hold a conversation**. An agent pointed at a
 * transcription model is an agent that fails on its first message, and finding
 * that out then is finding it out too late.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentFromModelTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `an agent is made on the model, named after it, and granted nothing`() {
        val modelId = model("Gemma 3")

        val agentId = graphQlTester.document(
            """mutation { createAgentForModel(modelId: $modelId) {
                 id workspaceId name type enabled modelId modelName icon
               } }""",
        ).execute().errors().verify()
            .path("createAgentForModel.name").entity(String::class.java).isEqualTo("Gemma 3")
            .path("createAgentForModel.modelId").entity(Long::class.java).isEqualTo(modelId)
            .path("createAgentForModel.modelName").entity(String::class.java).isEqualTo("Gemma 3")
            // The bot icon, the same default a hand-made agent takes. Issue #415.
            .path("createAgentForModel.icon").entity(String::class.java).isEqualTo("bot")
            // Switched on, because an agent made to be talked to and arriving
            // switched off is a second press for nothing.
            .path("createAgentForModel.enabled").entity(Boolean::class.java).isEqualTo(true)
            .path("createAgentForModel.workspaceId").entity(Long::class.java).isEqualTo(workspaceId)
            .path("createAgentForModel.id").entity(Long::class.java).get()

        val made = requireNotNull(agents.findByIdOrNull(agentId))
        assertThat(made.systemPrompt).isNull()
        assertThat(made.description).isNull()
        // Nothing of the workspace's. The server's own built-ins are on, as
        // they are for an agent made by hand - they reach nothing of the
        // workspace's, and an agent without a clock or a way to finish its
        // turn is one nobody asked for. Issue #444.
        // Nothing granted and nothing hidden, which since #455 is how "every
        // built-in, and none of the workspace's tools" is stored.
        assertThat(made.tools).isEmpty()
        assertThat(made.hiddenTools).isEmpty()
        assertThat(io.mszymanski.orknux.server.chat.BuiltInTools.grantedTo(made))
            .containsExactlyElementsOf(io.mszymanski.orknux.server.chat.BuiltInTools.GRANTED)
        assertThat(made.skillCatalogs).isEmpty()
        assertThat(made.memoryCatalogs).isEmpty()
        assertThat(made.mcpServers).isEmpty()
        assertThat(made.orknuxAccess).isFalse()
        assertThat(made.shellAccess).isFalse()

        // It is a change to the workspace's agents, so it is in the log the same
        // as one built by hand. A door that wrote nothing down would be a way of
        // creating agents that the audit trail does not know about.
        assertThat(audit.findAll().map { it.category }).contains(WorkspaceAuditCategory.AGENT)
    }

    /**
     * Pressing it twice.
     *
     * The obvious thing to do, and the thing the unique constraint refuses. The
     * second agent is real and distinct rather than the first one handed back:
     * somebody who pressed twice wanted two, and quietly returning the existing
     * one would be a different action wearing this one's name.
     */
    @Test
    fun `a second agent on the same model is numbered rather than refused`() {
        val modelId = model("Gemma 3")

        val first = made(modelId)
        val second = made(modelId)
        val third = made(modelId)

        assertThat(listOf(first, second, third)).doesNotHaveDuplicates()
        assertThat(agents.findAll().map { it.name })
            .containsExactlyInAnyOrder("Gemma 3", "Gemma 3 2", "Gemma 3 3")
    }

    /**
     * And it steps over a name somebody took by hand, not only over its own.
     *
     * The collision is with whatever is in the workspace, which is why the check
     * reads the agents rather than counting how many times this has been
     * pressed.
     */
    @Test
    fun `a name already taken by hand is stepped over`() {
        val modelId = model("Gemma 3")
        byHand("Gemma 3")

        made(modelId)

        assertThat(agents.findAll().map { it.name }).containsExactlyInAnyOrder("Gemma 3", "Gemma 3 2")
    }

    /**
     * A model that does not answer questions is refused, in the words a task
     * refuses one with.
     *
     * The picker never offered these and the button never will, but a button is
     * a courtesy and a check is a rule.
     */
    @Test
    fun `a model that does not answer questions is refused`() {
        val ears = model("Whisper", kind = "TRANSCRIPTION")

        graphQlTester.document("""mutation { createAgentForModel(modelId: $ears) { id } }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("Whisper").contains("does not answer questions")
            }

        assertThat(agents.findAll()).isEmpty()
    }

    /** A model that is not there is not one to make an agent on. */
    @Test
    fun `a model that no longer exists is refused`() {
        graphQlTester.document("""mutation { createAgentForModel(modelId: 987654) { id } }""")
            .execute()
            .errors().satisfy { errors ->
                assertThat(errors.first().message).contains("no longer exists")
            }

        assertThat(agents.findAll()).isEmpty()
    }

    private fun made(modelId: Long): Long = graphQlTester.document(
        """mutation { createAgentForModel(modelId: $modelId) { id } }""",
    ).execute().errors().verify().path("createAgentForModel.id").entity(Long::class.java).get()

    private fun byHand(name: String): Long = graphQlTester.document(
        """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
    ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()

    private fun model(name: String, kind: String = "CHAT"): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub for $name", endpoint: "http://models.invalid",
                 secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name", modelId: "${name.lowercase()}", kind: $kind
               }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }
}
