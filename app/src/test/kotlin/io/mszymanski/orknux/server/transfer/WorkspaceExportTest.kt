package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.HttpHeader
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.security.AdminRequiredException
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpHeaders
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * A workspace exported to a file and imported as a new one. Issue #590.
 *
 * What is pinned: the import brings what the source had - its components, its
 * connections, MCP servers, providers and models field for field without their
 * credentials, and its settings with the model choices pointed at the imported
 * models - and the file never holds a credential. A file of another kind or a
 * newer format is refused before anything is made, an import given no name
 * takes the next free one, and only an administrator may do either.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceExportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val functions: io.mszymanski.orknux.server.action.WorkflowFunctionRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val audits: WorkspaceAuditRepository,
    @Autowired val exportApi: WorkspaceExportAPI,
    @Autowired val mapper: ObjectMapper,
) {

    private val stamp = System.nanoTime()
    private val providerKey = "sk-roundtrip-provider-$stamp"
    private val connectionToken = "xoxb-roundtrip-connection-$stamp"
    private val appToken = "xapp-roundtrip-app-$stamp"
    private val serverKey = "mcp-roundtrip-server-$stamp"

    /** A workspace with something of everything Duplicate carries, credentials included. */
    private fun seeded(name: String): Long {
        val source = requireNotNull(workspaces.save(Workspace(name = name, description = "Desk $stamp")).id)
        graphQlTester.document(
            """mutation { createFunction(input: { workspaceId: $source, name: "greet$stamp" }) { id } }""",
        ).execute().errors().verify()
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $source, name: "Local $stamp", endpoint: "http://localhost:9/v1", secret: "$providerKey"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Gemma $stamp", modelId: "gemma", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        models.findByIdOrNull(modelId)!!.let {
            it.temperature = 0.3
            it.inputCostPerMillion = BigDecimal("1.25")
            it.contextWindow = 32_000
            models.save(it)
        }
        agents.save(Agent(workspaceId = source, name = "Tester $stamp", type = AgentType.LLM, modelId = modelId))
        connections.save(
            WorkspaceConnection(
                workspaceId = source, name = "Slack $stamp", type = ConnectionType.SLACK, url = "https://slack.com/api",
                authType = AuthType.BEARER_TOKEN, secret = connectionToken, appToken = appToken,
                headers = mutableListOf(HttpHeader("X-Team", "desk")),
            ),
        )
        mcpServers.save(
            McpServer(
                workspaceId = source, name = "Search $stamp", address = "http://search.invalid/mcp",
                authType = AuthType.API_KEY, secret = serverKey,
            ),
        )
        workspaces.findByIdOrNull(source)!!.let {
            it.quickChatModelId = modelId
            it.companionModelId = modelId
            it.taskMaxTurns = 17
            it.commandMarker = "!"
            it.functionTimeoutSeconds = 45
            workspaces.save(it)
        }
        return source
    }

    private fun exported(id: Long): Pair<String, String> {
        val answer = exportApi.export(id)
        val disposition = answer.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION).orEmpty()
        return disposition to String(requireNotNull(answer.body), Charsets.UTF_8)
    }

    private fun imported(content: String, name: String? = null) = graphQlTester.document(
        """mutation(${'$'}content: String!, ${'$'}name: String) { importWorkspace(content: ${'$'}content, name: ${'$'}name) {
             workspace { id name } carried { kind count } credentialsToSet variablesToSet problems } }""",
    ).variable("content", content).variable("name", name).execute()

    @Test
    fun `a workspace exported and imported is the same workspace, without its credentials`() {
        val source = seeded("export-source-$stamp")
        val (disposition, file) = exported(source)

        assertThat(disposition).contains("attachment").contains("export-source-$stamp.orkx-workspace.json")
        // Not one credential, in any of its forms.
        listOf(providerKey, connectionToken, appToken, serverKey).forEach { assertThat(file).doesNotContain(it) }
        assertThat(audits.findAll().map { it.message }).contains("Workspace export-source-$stamp exported")

        val answer = imported(file, "export-copy-$stamp")
        answer.errors().verify()
        val copyId = answer.path("importWorkspace.workspace.id").entity(Long::class.java).get()
        assertThat(answer.path("importWorkspace.problems").entityList(String::class.java).get()).isEmpty()
        assertThat(answer.path("importWorkspace.credentialsToSet").entityList(String::class.java).get())
            .containsExactlyInAnyOrder("connection Slack $stamp", "MCP server Search $stamp", "model provider Local $stamp")
        assertThat(audits.findAll().map { it.message }).contains("Workspace export-copy-$stamp imported")

        // The components.
        assertThat(functions.findByWorkspaceIdAndName(copyId, "greet$stamp")).isNotNull()
        val copiedProvider = requireNotNull(providers.findByWorkspaceIdAndName(copyId, "Local $stamp"))
        val copiedModel = requireNotNull(models.findByProviderIdAndName(copiedProvider.id!!, "Gemma $stamp"))
        assertThat(agents.findByWorkspaceIdAndName(copyId, "Tester $stamp")!!.modelId).isEqualTo(copiedModel.id)

        // The externals, field for field, less what is left on purpose.
        val byName = Sort.by("name")
        sameFields(connections.findByWorkspaceId(source, byName).single(), connections.findByWorkspaceId(copyId, byName).single())
        sameFields(mcpServers.findByWorkspaceId(source, byName).single(), mcpServers.findByWorkspaceId(copyId, byName).single())
        sameFields(providers.findByWorkspaceIdAndName(source, "Local $stamp")!!, copiedProvider)
        val sourceProvider = providers.findByWorkspaceIdAndName(source, "Local $stamp")!!
        sameFields(models.findByProviderIdAndName(sourceProvider.id!!, "Gemma $stamp")!!, copiedModel)
        assertThat(copiedProvider.secret).isNull()
        connections.findByWorkspaceId(copyId, byName).single().let {
            assertThat(it.secret).isNull()
            assertThat(it.appToken).isNull()
        }
        assertThat(mcpServers.findByWorkspaceId(copyId, byName).single().secret).isNull()

        // The settings, and the model choices pointed at the imported model.
        val original = workspaces.findByIdOrNull(source)!!
        val copy = workspaces.findByIdOrNull(copyId)!!
        assertThat(settingsOf(copy)).isEqualTo(settingsOf(original))
        assertThat(copy.quickChatModelId).isEqualTo(copiedModel.id)
        assertThat(copy.companionModelId).isEqualTo(copiedModel.id)
        assertThat(copy.imageModelId).isNull()
        assertThat(copy.taskMaxTurns).isEqualTo(17)
    }

    @Test
    fun `an import given no name takes the file's, and then the next free one`() {
        val source = seeded("export-named-$stamp")
        val (_, file) = exported(source)
        // The source still holds the file's name, so the first import is already the second.
        assertThat(imported(file).path("importWorkspace.workspace.name").entity(String::class.java).get())
            .isEqualTo("export-named-$stamp 2")
        assertThat(imported(file).path("importWorkspace.workspace.name").entity(String::class.java).get())
            .isEqualTo("export-named-$stamp 3")
        // A name chosen and taken is refused rather than changed.
        imported(file, "export-named-$stamp").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("WorkspaceNameTaken")
        }
    }

    @Test
    fun `a file from a newer format, or of another kind, is refused before anything is made`() {
        val source = seeded("export-future-$stamp")
        val (_, file) = exported(source)
        val before = workspaces.count()

        val future = (mapper.readTree(file) as ObjectNode).put("formatVersion", WORKSPACE_FILE_VERSION + 1)
        imported(mapper.writeValueAsString(future), "export-future-copy-$stamp").errors().satisfy { errors ->
            val refusal = errors.single()
            assertThat(refusal.extensions["code"]).isEqualTo("WorkspaceFileVersionUnknown")
            assertThat(refusal.message).contains("format version ${WORKSPACE_FILE_VERSION + 1}")
        }

        val functionId = functions.findByWorkspaceIdAndName(source, "greet$stamp")!!.id
        val component = graphQlTester.document(
            """{ exportComponent(workspaceId: $source, kind: FUNCTION, id: $functionId) { json } }""",
        ).execute().path("exportComponent.json").entity(String::class.java).get()
        imported(component, "export-component-$stamp").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("WorkspaceFileUnreadable")
            assertThat(errors.single().message).contains("component export")
        }
        imported("not json", "export-garbage-$stamp").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("WorkspaceFileUnreadable")
        }

        assertThat(workspaces.count()).isEqualTo(before)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `only an administrator exports or imports a workspace`() {
        val source = requireNotNull(workspaces.save(Workspace(name = "export-refused-$stamp")).id)
        assertThatThrownBy { exportApi.export(source) }.isInstanceOf(AdminRequiredException::class.java)
        imported("""{"format":"orknux-workspace","formatVersion":1,"workspace":{"name":"x"}}""", "export-refused-copy-$stamp")
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("AdminRequired") }
        assertThat(workspaces.findByName("export-refused-copy-$stamp")).isNull()
    }

    /** The workspace's settings as a file would write them, which is every one a copy carries. */
    private fun settingsOf(workspace: Workspace): ObjectNode =
        mapper.createObjectNode().also { node -> WORKSPACE_SETTINGS.forEach { it.write(workspace, node) } }

    /**
     * Every field of two rows alike, except what a copy leaves on purpose - the
     * list `WorkspaceDuplicateTest` keeps. Read by reflection, so a field the
     * file forgets fails here even though nobody listed it.
     */
    private fun sameFields(original: Any, copy: Any) {
        val left = setOf("id", "workspaceId", "providerId", "secret", "secretVariableId", "appToken",
            "appTokenVariableId", "userToken", "userTokenVariableId", "lastCheckStatus", "lastCheckMessage",
            "lastCheckedAt", "status", "reachable", "checkDetail", "toolCount")
        original.javaClass.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.name !in left }
            .forEach { field ->
                field.isAccessible = true
                assertThat(comparable(field.get(copy)))
                    .describedAs("${original.javaClass.simpleName}.${field.name}")
                    .isEqualTo(comparable(field.get(original)))
            }
    }

    private fun comparable(value: Any?): Any? = when (value) {
        is BigDecimal -> value.stripTrailingZeros()
        is Collection<*> -> value.map { if (it is HttpHeader) it.name to it.value else it }
        else -> value
    }
}
