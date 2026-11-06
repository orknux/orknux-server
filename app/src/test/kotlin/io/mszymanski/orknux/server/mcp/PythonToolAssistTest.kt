package io.mszymanski.orknux.server.mcp

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.action.CodeLanguage
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The wand on a Python tool: the assistant is told Python and only Python,
 * and an offer that is not Python is refused before anybody is shown it.
 *
 * Without both halves a model briefed on TypeScript tools offered TypeScript
 * for a Python one - drawn as a diff, accepted, and then refused by the save,
 * which is the wrong place to find out.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PythonToolAssistTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val quickChat: QuickChat,
    @Autowired val tools: OrknuxTools,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        agentTools.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private val python = "def main(text):\n    return {'said': text.upper()}\n"

    private fun store(name: String, language: CodeLanguage) = agentTools.save(
        if (language == CodeLanguage.PYTHON) {
            AgentTool(workspaceId = workspaceId, name = name, language = language, source = python, typescript = python)
        } else {
            AgentTool(
                workspaceId = workspaceId,
                name = name,
                source = "export default function $name(text) { return text; }",
                typescript = "export default function $name(text: string) { return text; }",
            )
        },
    )

    @Test
    fun `on a Python tool's page the briefing is Python and says nothing of TypeScript`() {
        val held = store("shout", CodeLanguage.PYTHON)

        val briefing = systemTurn(ask("/workspace/$workspaceId/tools/${held.id}"))

        assertThat(briefing).contains("shout, is written in Python")
        assertThat(briefing).contains("def main(")
        assertThat(briefing).contains("orknux.slack.thread(connection, channel, thread_ts")
        assertThat(briefing).doesNotContainIgnoringCase("typescript")
        assertThat(briefing).doesNotContain("declare const", "type SlackThread", "default export", "Record<")
    }

    @Test
    fun `a TypeScript tool's page is briefed as it always was`() {
        val held = store("shout", CodeLanguage.TYPESCRIPT)

        val briefing = systemTurn(ask("/workspace/$workspaceId/tools/${held.id}"))

        assertThat(briefing).contains("complete new TypeScript").contains("declare const orknux")
        assertThat(briefing).doesNotContain("is written in Python")
    }

    @Test
    fun `TypeScript offered for a Python tool is refused and never shown`() {
        val held = store("shout", CodeLanguage.PYTHON)
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, watched = true)
        val typescript = mapper.writeValueAsString(
            mapOf("tool" to "shout", "code" to "export default function shout(text: string) { return text.toUpperCase(); }"),
        )

        assertThat(tools.toolSuggestionIn(scope, typescript)).isNull()
        assertThat(tools.run(scope, "orknux_suggest_tool_code", typescript))
            .contains("shout is written in Python, and that code does not parse as Python")
            .contains("Nothing was shown")

        val fixed = mapper.writeValueAsString(mapOf("tool" to "shout", "code" to "def main(text):\n    return text.upper()\n"))
        assertThat(tools.toolSuggestionIn(scope, fixed)?.toolId).isEqualTo(held.id)
        assertThat(tools.run(scope, "orknux_suggest_tool_code", fixed)).contains("shown")
    }

    private fun systemTurn(request: String): String {
        val messages = mapper.readTree(request).path("messages")
        return messages.values().first { it.path("role").asString() == "system" }.path("content").asString()
    }

    private fun ask(path: String): String {
        val modelId = model(serve())
        quickChat.answer(
            modelId = modelId,
            workspaceId = workspaceId,
            mayWrite = true,
            page = PageContext(path = path, label = "Tool"),
            said = listOf(ChatTurn("user", "Make it shout twice.")),
            asker = "alice",
        )
        return requireNotNull(received.firstOrNull()) { "the model was never asked anything" }
    }

    private fun serve(): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Done."}}],"usage":{"prompt_tokens":9,"completion_tokens":1}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}"
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
