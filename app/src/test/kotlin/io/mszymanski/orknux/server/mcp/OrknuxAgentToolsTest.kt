package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * An agent reading, making and configuring agents through the orknux_* tools.
 *
 * Asked for from Slack: an agent holding these tools with write scope was asked
 * to make a narrow "PR searcher" and answered that it could only list agents and
 * switch them on and off - and once a person had made it by hand, it could not
 * read back the prompt or the tools to confirm it. What is pinned here is that
 * the write goes through the browser's own door - its refusals, its audit
 * entries, its revisions - and that reading one shows what the page shows.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class OrknuxAgentToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        agentTools.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        revisions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "engineering")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    private fun writing() = OrknuxScope(workspaceId = workspaceId, mayWrite = true)

    private fun reading() = OrknuxScope(workspaceId = workspaceId)

    private fun call(scope: OrknuxScope, name: String, arguments: Map<String, Any?>) =
        mapper.readTree(tools.run(scope, name, mapper.writeValueAsString(arguments)))

    private fun tool(name: String, workspace: Long = workspaceId) = agentTools.save(
        AgentTool(
            workspaceId = workspace,
            name = name,
            description = "Finds pull requests.",
            source = "export default async function $name(input) { return []; }",
            typescript = "export default async function $name(input: unknown) { return []; }",
        ),
    )

    private fun model(name: String, kind: String = "CHAT", workspace: Long = workspaceId): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspace, name: "Stub for $name", endpoint: "http://models.invalid",
                 secret: "sk-never-shown"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name", modelId: "${name.lowercase()}", kind: $kind
               }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private fun messages() = audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }

    @Test
    fun `one agent reads in full - prompt, model, tools - and no secret`() {
        tool("searchPullRequests")
        val chat = model("Sonnet")
        call(
            writing(), "orknux_create_agent",
            mapOf(
                "name" to "PR searcher",
                "systemPrompt" to "Find the pull requests somebody asks about.",
                "modelId" to chat,
                "tools" to listOf("searchPullRequests"),
            ),
        )

        val read = call(reading(), "orknux_agent", mapOf("agent" to "PR searcher"))

        assertThat(read.path("systemPrompt").asString()).isEqualTo("Find the pull requests somebody asks about.")
        assertThat(read.path("model").path("id").asLong()).isEqualTo(chat)
        assertThat(read.path("model").path("name").asString()).isEqualTo("Sonnet")
        assertThat(read.path("model").path("provider").asString()).isEqualTo("Stub for Sonnet")
        assertThat(read.path("tools").path("workspace").values().map { it.asString() })
            .containsExactly("searchPullRequests")
        // The built-ins a fresh agent holds are listed as such, not lost.
        assertThat(read.path("tools").path("builtIn").values()).isNotEmpty()
        assertThat(read.path("url").asString()).endsWith("/agents/${read.path("id").asLong()}/settings")
        assertThat(read.toString()).doesNotContain("sk-never-shown")
    }

    @Test
    fun `an agent in another workspace reads as one that is not there`() {
        graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $elsewhereId, name: "Billing bot", type: LLM }) { id } }""",
        ).execute().errors().verify()

        val read = call(reading(), "orknux_agent", mapOf("agent" to "Billing bot"))

        assertThat(read.path("error").asString()).isEqualTo("There is no agent called Billing bot here")
    }

    @Test
    fun `the models are listed with their ids and kinds`() {
        val chat = model("Sonnet")
        model("Whisper", kind = "TRANSCRIPTION")
        model("Elsewhere", workspace = elsewhereId)

        val listed = call(reading(), "orknux_models", emptyMap()).path("models").values()

        assertThat(listed.map { it.path("name").asString() }).containsExactlyInAnyOrder("Sonnet", "Whisper")
        assertThat(listed.first { it.path("name").asString() == "Sonnet" }.path("id").asLong()).isEqualTo(chat)
        assertThat(listed.first { it.path("name").asString() == "Whisper" }.path("kind").asString())
            .isEqualTo("TRANSCRIPTION")
    }

    @Test
    fun `creating writes the browser's audit entries and the defaults a hand-made agent gets`() {
        tool("searchPullRequests")
        val chat = model("Sonnet")

        val made = call(
            writing(), "orknux_create_agent",
            mapOf(
                "name" to "PR searcher",
                "description" to "Searches pull requests.",
                "modelId" to chat.toString(),
                "tools" to "searchPullRequests",
            ),
        )

        assertThat(made.path("error").isMissingNode).describedAs(made.toString()).isTrue()
        val agent = requireNotNull(agents.findByWorkspaceIdAndName(workspaceId, "PR searcher"))
        assertThat(agent.modelId).isEqualTo(chat)
        assertThat(agent.tools).containsExactly("searchPullRequests")
        assertThat(agent.hiddenTools).describedAs("no built-in or orknux_ tool hidden").isEmpty()
        assertThat(agent.orknuxAccess).isTrue()
        assertThat(agent.description).isEqualTo("Searches pull requests.")
        assertThat(messages()).contains(
            "Agent PR searcher created",
            "Agent PR searcher model set to Sonnet",
            "Agent PR searcher given tool searchPullRequests",
        )
    }

    @Test
    fun `an unknown tool, a model that cannot chat and a taken name are refused, and nothing is made`() {
        tool("searchPullRequests")
        tool("chargeCard", workspace = elsewhereId)
        val whisper = model("Whisper", kind = "TRANSCRIPTION")
        call(writing(), "orknux_create_agent", mapOf("name" to "PR searcher"))

        val unknown = call(writing(), "orknux_create_agent", mapOf("name" to "Payer", "tools" to listOf("chargeCard")))
        val deaf = call(writing(), "orknux_create_agent", mapOf("name" to "Listener", "modelId" to whisper))
        val taken = call(writing(), "orknux_create_agent", mapOf("name" to "PR searcher"))

        assertThat(unknown.path("error").asString()).startsWith("There is no tool called chargeCard in this workspace")
        assertThat(deaf.path("error").asString()).isEqualTo("Whisper does not answer questions; an agent needs a CHAT model")
        assertThat(taken.path("error").asString())
            .isEqualTo("An agent named \"PR searcher\" already exists in this workspace")
        assertThat(agents.findAll().map { it.name }).containsExactly("PR searcher")
    }

    @Test
    fun `the save itself refuses a model that cannot chat, from every door`() {
        val whisper = model("Whisper", kind = "TRANSCRIPTION")
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Listener", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Listener", modelId: $whisper }) { id } }""",
        ).execute().errors().expect { it.message?.contains("Whisper does not answer questions") == true }
    }

    @Test
    fun `an update changes only what it was given, and is a version`() {
        tool("searchPullRequests")
        tool("searchIssues")
        val chat = model("Sonnet")
        call(
            writing(), "orknux_create_agent",
            mapOf(
                "name" to "PR searcher",
                "description" to "Searches pull requests.",
                "systemPrompt" to "Be brief.",
                "modelId" to chat,
                "tools" to listOf("searchPullRequests"),
            ),
        )
        revisions.deleteAll()
        audit.deleteAll()

        val answer = call(
            writing(), "orknux_update_agent",
            mapOf("agent" to "pr SEARCHER", "systemPrompt" to "Be thorough.", "tools" to listOf("searchIssues")),
        )

        assertThat(answer.path("updated").asString()).isEqualTo("PR searcher")
        val agent = requireNotNull(agents.findByWorkspaceIdAndName(workspaceId, "PR searcher"))
        assertThat(agent.systemPrompt).isEqualTo("Be thorough.")
        assertThat(agent.description).describedAs("not sent, so not cleared").isEqualTo("Searches pull requests.")
        assertThat(agent.modelId).describedAs("not sent, so not cleared").isEqualTo(chat)
        assertThat(agent.tools).containsExactly("searchIssues")
        assertThat(agent.hiddenTools).isEmpty()
        assertThat(messages()).containsExactlyInAnyOrder(
            "Agent PR searcher system prompt updated",
            "Agent PR searcher given tool searchIssues",
            "Agent PR searcher can no longer call tool searchPullRequests",
        )
        assertThat(revisions.findAll()).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(ComponentRevisionKind.AGENT)
            assertThat(it.snapshot).contains("Be brief.")
        })
    }

    @Test
    fun `an agent with no session changes an agent under its own name`() {
        call(writing(), "orknux_create_agent", mapOf("name" to "PR searcher"))
        SecurityContextHolder.clearContext()

        val answer = call(
            OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Assistant"),
            "orknux_update_agent",
            mapOf("agent" to "PR searcher", "description" to "Searches pull requests."),
        )

        assertThat(answer.path("error").isMissingNode).describedAs(answer.toString()).isTrue()
        val agent = requireNotNull(agents.findByWorkspaceIdAndName(workspaceId, "PR searcher"))
        assertThat(agent.description).isEqualTo("Searches pull requests.")
        assertThat(agent.lastModifiedBy).isEqualTo("Assistant")
    }

    @Test
    fun `reading cannot see the writing tools, nor call them`() {
        val offered = tools.specs(reading()).map { it.name }

        assertThat(offered).contains("orknux_agent", "orknux_models")
        assertThat(offered).doesNotContain("orknux_create_agent", "orknux_update_agent")
        assertThat(call(reading(), "orknux_create_agent", mapOf("name" to "Sneaky")).path("error").asString())
            .isEqualTo("This conversation may read what is here, but not change it")
        assertThat(agents.findAll()).isEmpty()
        assertThat(tools.specs(writing()).map { it.name }).contains("orknux_create_agent", "orknux_update_agent")
    }

    /**
     * Asked for from Slack: an agent told to fill in Azure prices answered that
     * it had no tool to edit a price and that reading the models showed none.
     */
    @Test
    fun `an agent reads a model's prices and changes only the ones it names`() {
        val id = model("GPT Luna")
        graphQlTester.document(
            """mutation { updateModel(id: $id, input: { name: "GPT Luna", modelId: "gpt-luna", contextWindow: 400000,
                 inputCostPerMillion: 1.0, outputCostPerMillion: 8.0 }) { id } }""",
        ).execute().errors().verify()

        assertThat(tools.specs(reading()).map { it.name }).doesNotContain("orknux_update_model")
        assertThat(call(reading(), "orknux_update_model", mapOf("model" to "GPT Luna")).path("error").isMissingNode).isFalse()

        val listed = call(reading(), "orknux_models", emptyMap()).path("models").get(0)
        assertThat(listed.path("providerModel").asString()).isEqualTo("gpt-luna")
        assertThat(listed.path("inputCostPerMillion").asDouble()).isEqualTo(1.0)

        val saved = call(
            writing(),
            "orknux_update_model",
            mapOf("model" to "gpt luna", "cachedInputCostPerMillion" to "0.25", "outputCostPerMillion" to 10, "inputCostPerMillion" to null),
        )
        assertThat(saved.path("error").isMissingNode).isTrue()
        val now = requireNotNull(models.findById(id).orElse(null))
        assertThat(now.cachedInputCostPerMillion?.toDouble()).isEqualTo(0.25)
        assertThat(now.outputCostPerMillion?.toDouble()).isEqualTo(10.0)
        assertThat(now.inputCostPerMillion).isNull()
        // What was not named is kept.
        assertThat(now.contextWindow).isEqualTo(400000)
        assertThat(now.modelId).isEqualTo("gpt-luna")
        assertThat(audit.findAll().map { it.message }).contains("Model GPT Luna updated")

        assertThat(call(writing(), "orknux_update_model", mapOf("model" to id, "outputCostPerMillion" to -1)).path("error").asString())
            .contains("0 or more")
        assertThat(call(writing(), "orknux_update_model", mapOf("model" to "nothing")).path("error").asString())
            .contains("orknux_models")
    }
}
