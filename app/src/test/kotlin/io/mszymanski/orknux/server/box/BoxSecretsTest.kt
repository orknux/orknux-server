package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.variable.VariableCatalog
import io.mszymanski.orknux.server.variable.VariableCatalogRepository
import io.mszymanski.orknux.server.variable.VariableKind
import io.mszymanski.orknux.server.variable.VariableTools
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path

/**
 * An agent's workspace variables: a secret catalog's secrets handed to a box
 * command as environment variables and masked in everything shown from the
 * session, and a variable catalog's plain values read and left as they are.
 * Against the local box on the `local` runtime, through [AgentTools], as
 * [BoxToolsTest] is.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BoxSecretsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val settings: BoxSettings,
    @Autowired val local: LocalBox,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val sessions: BoxSessionRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val events: BoxSessionEventRepository,
    @Autowired val outputs: BoxSessionOutputRepository,
    @Autowired val catalogs: VariableCatalogRepository,
    @Autowired val variables: WorkspaceVariableRepository,
) {

    @TempDir
    lateinit var root: Path

    private var workspaceId = 0L
    private var conversation = 0L
    private lateinit var agent: Agent

    @BeforeEach
    fun seed() {
        clean()
        settings.setLocalLocation(root.toString(), "alice")
        settings.setLocalRuntime("local", "alice")
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "secrets")).id)
        conversation = recorder.open(workspaceId, "chat", "build")
        val vault = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "Vault")).id)
        val config = requireNotNull(catalogs.save(VariableCatalog(workspaceId = workspaceId, name = "Config")).id)
        variables.save(WorkspaceVariable(workspaceId = workspaceId, catalogId = vault, name = "API_TOKEN", kind = VariableKind.SECRET, value = SECRET))
        variables.save(WorkspaceVariable(workspaceId = workspaceId, catalogId = config, name = "REGION", kind = VariableKind.VALUE, value = "eu-west-1"))
        agent = agents.save(
            Agent(workspaceId = workspaceId, name = "Deployer", type = AgentType.LLM).apply {
                boxLabels = listOf("local")
                secretCatalogs = mutableListOf("Vault")
                variableCatalogs = mutableListOf("Config")
            },
        )
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
        events.deleteAll()
        outputs.deleteAll()
        sessions.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        variables.deleteAll()
        catalogs.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap(), who: Agent = agent): JsonNode =
        mapper.readTree(tools.run(who, ToolCall("call", name, mapper.writeValueAsString(arguments)), conversation))

    private fun opened(): Long {
        val answer = call("orkbox_open")
        assertThat(answer.path("error").isMissingNode).describedAs(answer.toString()).isTrue()
        return answer.path("session").asLong()
    }

    /** A command that prints the variable [name], in the local runtime's own shell. */
    private fun printing(name: String) =
        if (System.getProperty("os.name").startsWith("Windows")) "echo token=%$name%" else "echo token=\$$name"

    @Test
    fun `the variable tools are offered only with a grant`() {
        assertThat(tools.specsFor(agent).map { it.name }).containsAll(VariableTools.NAMES)
        val plain = agents.save(Agent(workspaceId = workspaceId, name = "Plain", type = AgentType.LLM))
        assertThat(tools.specsFor(plain).map { it.name }).noneMatch { it in VariableTools.NAMES }
    }

    @Test
    fun `a plain value is read, a secret is listed by name and never read`() {
        val listed = call("variable_list").path("variables")
        val region = listed.first { it.path("name").stringValue() == "REGION" }
        assertThat(region.path("value").stringValue()).isEqualTo("eu-west-1")
        val token = listed.first { it.path("name").stringValue() == "API_TOKEN" }
        assertThat(token.path("kind").stringValue()).isEqualTo("secret")
        assertThat(token.has("value")).isFalse()
        assertThat(listed.toString()).doesNotContain(SECRET)

        assertThat(call("variable_get", mapOf("name" to "REGION")).path("value").stringValue()).isEqualTo("eu-west-1")
        val refused = call("variable_get", mapOf("name" to "API_TOKEN"))
        assertThat(refused.path("error").stringValue()).contains("secret").contains("orkbox_exec")
        assertThat(refused.toString()).doesNotContain(SECRET)
    }

    @Test
    fun `a secret reaches the command as an environment variable and is masked everywhere it is shown`() {
        val session = opened()

        val ran = call("orkbox_exec", mapOf("session" to session, "command" to printing("API_TOKEN"), "secrets" to listOf("API_TOKEN")))
        assertThat(ran.path("error").isMissingNode).describedAs(ran.toString()).isTrue()
        // The command had it - it printed something where the value goes - and the model reads ***.
        assertThat(ran.path("stdout").stringValue()).contains("token=***").doesNotContain(SECRET)

        // The box's log and kept output, as copied here.
        val row = sessions.findById(session).get()
        assertThat(row.secretVariableIds).isNotBlank()
        assertThat(row.secretVariableIds).doesNotContain(SECRET)
        assertThat(events.findAll().map { it.data }).noneMatch { it.contains(SECRET) }
        assertThat(outputs.findAll().map { it.text }).noneMatch { it.contains(SECRET) }

        // And reading the output again, or the file a command wrote it into.
        val execution = ran.path("execution").stringValue()
        val again = call("orkbox_output", mapOf("session" to session, "execution" to execution))
        assertThat(again.toString()).doesNotContain(SECRET)
        call("orkbox_exec", mapOf("session" to session, "command" to printing("API_TOKEN") + " > leak.txt", "secrets" to listOf("API_TOKEN")))
        assertThat(call("orkbox_read", mapOf("session" to session, "path" to "leak.txt")).toString())
            .contains("***").doesNotContain(SECRET)

        // The session page's live reads mask it too.
        graphQlTester.document(
            """{ sessionBoxOutput(sessionId: $conversation, sessionBoxId: $session, executionId: "$execution") { text } }""",
        ).execute().path("sessionBoxOutput.text").entity(String::class.java).satisfies { text ->
            assertThat(text).doesNotContain(SECRET)
        }
        assertThat(lines.findAll().map { it.content.orEmpty() + it.result.orEmpty() }).noneMatch { it.contains(SECRET) }
    }

    /**
     * A secret is set under its own name and upper-cased too, and the answer
     * names what was set. Session 635 was handed github_token, read
     * ${'$'}GITHUB_TOKEN, found it empty and decided the token was bad.
     */
    @Test
    fun `a lower-case secret is readable by its upper-cased name too, and the answer says which names were set`() {
        variables.save(
            WorkspaceVariable(
                workspaceId = workspaceId,
                catalogId = requireNotNull(variables.findAll().first { it.name == "API_TOKEN" }.catalogId),
                name = "github_token",
                kind = VariableKind.SECRET,
                value = SECRET,
            ),
        )
        val session = opened()

        val ran = call("orkbox_exec", mapOf("session" to session, "command" to printing("GITHUB_TOKEN"), "secrets" to listOf("github_token")))
        assertThat(ran.path("stdout").stringValue()).contains("token=***")
        val names = ran.path("environment").let { node -> (0 until node.size()).map { node[it].stringValue() } }
        assertThat(names).containsExactly("GITHUB_TOKEN", "github_token")
    }

    @Test
    fun `a plain value handed to a command is not masked`() {
        val session = opened()
        val ran = call("orkbox_exec", mapOf("session" to session, "command" to printing("REGION"), "values" to listOf("REGION")))
        assertThat(ran.path("stdout").stringValue()).contains("token=eu-west-1")
    }

    @Test
    fun `a secret the agent was not given, or a value asked for as a secret, is refused before anything runs`() {
        val session = opened()
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x", "secrets" to listOf("OTHER"))).path("error").stringValue())
            .contains("no secret called OTHER")
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x", "secrets" to listOf("REGION"))).path("error").stringValue())
            .contains("plain value")
    }

    @Test
    fun `a secret grant taken back while a turn runs is refused in that turn`() {
        val session = opened()
        agents.findById(requireNotNull(agent.id)).get().also {
            it.secretCatalogs = mutableListOf()
            agents.save(it)
        }
        assertThat(agent.secretCatalogs).containsExactly("Vault")
        val refused = call("orkbox_exec", mapOf("session" to session, "command" to "echo x", "secrets" to listOf("API_TOKEN")))
        assertThat(refused.path("error").stringValue()).contains("no secret called API_TOKEN")
    }

    @Test
    fun `the grants are saved, read back, audited, and follow a renamed catalog`() {
        val id = requireNotNull(agent.id)
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Deployer", secretCatalogs: ["Vault", "Ops"], variableCatalogs: [] }) { secretCatalogs variableCatalogs } }""",
        ).execute()
            .path("updateAgent.secretCatalogs").entityList(String::class.java).containsExactly("Vault", "Ops")
            .path("updateAgent.variableCatalogs").entityList(String::class.java).hasSize(0)
        assertThat(audit.findAll().map { it.message }).contains(
            "Agent Deployer given the secrets of catalog Ops",
            "Agent Deployer no longer reads the variables of catalog Config",
        )

        val vault = requireNotNull(catalogs.findByWorkspaceIdAndName(workspaceId, "Vault")?.id)
        graphQlTester.document("""mutation { renameVariableCatalog(id: $vault, name: "Keys") { name } }""").execute()
        assertThat(agents.secretCatalogsOf(id)).containsExactly("Keys", "Ops")
    }

    companion object {
        const val SECRET = "s3cr3t-T0ken-value"
    }
}
