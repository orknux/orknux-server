package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventRepository
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent asks to expose a port, a person decides, and only then is it open.
 *
 * Against the local box on the `local` runtime, whose forward reaches a port on
 * this machine - so a server socket the test holds open stands in for what an
 * agent would serve inside a container.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BoxPortsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val settings: BoxSettings,
    @Autowired val local: LocalBox,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val sessions: BoxSessionRepository,
    @Autowired val ports: BoxPortMappingRepository,
    @Autowired val requests: InteractionRequestRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    @TempDir
    lateinit var root: Path

    private var workspaceId = 0L
    private var conversation = 0L
    private lateinit var agent: Agent
    private lateinit var server: ServerSocket
    private val held = CopyOnWriteArrayList<Socket>()

    @BeforeEach
    fun seed() {
        clean()
        settings.setLocalLocation(root.toString(), "alice")
        settings.setLocalRuntime("local", "alice")
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "ports")).id)
        conversation = recorder.open(workspaceId, "chat", "serve")
        agent = agents.save(Agent(workspaceId = workspaceId, name = "Builder", type = AgentType.LLM).apply { boxLabels = listOf("local") })
        // What the agent "serves": takes a connection and holds it, as a server waiting for a request does.
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        Thread.ofVirtual().start {
            while (!server.isClosed) runCatching { held += server.accept() }
        }
    }

    @AfterEach
    fun clean() {
        if (::server.isInitialized) runCatching { server.close() }
        held.forEach { runCatching { it.close() } }
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
        requests.deleteAll()
        ports.deleteAll()
        inbox.deleteAll()
        sessions.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap()): JsonNode =
        mapper.readTree(tools.run(agent, ToolCall("call", name, mapper.writeValueAsString(arguments)), conversation))

    private fun opened(): Long = call("orkbox_open").path("session").asLong()

    private fun told(): List<String> = inbox.findAll().filter { it.sessionId == conversation }.map { it.body }

    private fun requested(): Map<String, Any?> = graphQlTester.document(
        "{ sessionRequests(sessionId: $conversation) { id kind askedBy question options { id label style } chosen outcome } }",
    ).execute().path("sessionRequests[0]").entity(Map::class.java).get().mapKeys { it.key.toString() }

    private fun panel(): List<Map<*, *>> = graphQlTester.document(
        "{ sessionBoxPorts(sessionId: $conversation) { id port state url boxLabel reason alive checkedAt } }",
    ).execute().path("sessionBoxPorts").entityList(Map::class.java).get()

    @Test
    fun `a port is asked for, allowed in the conversation, opened, and the agent is told where`() {
        val session = opened()
        val asked = call("orkbox_expose", mapOf("session" to session, "port" to server.localPort, "why" to "the dev UI"))
        assertThat(asked.path("state").stringValue()).isEqualTo("pending")
        assertThat(asked.path("note").stringValue()).contains("allow or reject").contains("end your turn")

        // Nothing is open, and the question is in the conversation with its options.
        assertThat(panel().single()["state"]).isEqualTo("PENDING")
        assertThat(panel().single()["alive"]).isEqualTo(false)
        assertThat(panel().single()["boxLabel"]).isEqualTo("local")
        val request = requested()
        assertThat(request["kind"]).isEqualTo("box.port")
        assertThat(request["question"] as String).contains("Builder wants to expose port ${server.localPort}").contains("the dev UI")
        assertThat((request["options"] as List<*>).map { (it as Map<*, *>)["id"] }).containsExactly("allow", "reject")

        val answered = graphQlTester.document("""mutation { answerRequest(id: ${request["id"]}, option: "allow") { chosen outcome } }""")
            .execute().path("answerRequest").entity(Map::class.java).get()
        assertThat(answered["chosen"]).isEqualTo("allow")
        val url = panel().single()["url"] as String
        assertThat(answered["outcome"]).isEqualTo("Allowed - $url")
        assertThat(panel().single()["state"]).isEqualTo("ENABLED")
        assertThat(told().single()).contains("is now reachable at $url")

        // It really is open: a connection through it lands on what the agent serves.
        val before = held.size
        Socket(URI(url).host, URI(url).port).use { }
        Thread.sleep(300)
        assertThat(held.size).isGreaterThan(before)
        assertThat(panel().single()["alive"]).isEqualTo(true)
        assertThat(panel().single()["checkedAt"]).isNotNull()

        // Answered once.
        graphQlTester.document("""mutation { answerRequest(id: ${request["id"]}, option: "reject") { chosen } }""")
            .execute().errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("InteractionAlreadyAnswered") }
    }

    @Test
    fun `a rejected port stays listed, can be enabled from the panel, disabled and removed - and the question follows`() {
        val session = opened()
        call("orkbox_expose", mapOf("session" to session, "port" to server.localPort))
        val request = requested()
        graphQlTester.document("""mutation { answerRequest(id: ${request["id"]}, option: "reject") { outcome } }""")
            .execute().path("answerRequest.outcome").entity(String::class.java).isEqualTo("Rejected")
        assertThat(panel().single()["state"]).isEqualTo("REFUSED")
        assertThat(told().single()).contains("did not allow port ${server.localPort}")

        val id = panel().single()["id"]
        graphQlTester.document("mutation { enableBoxPort(id: $id) { state url } }").execute()
            .path("enableBoxPort.state").entity(String::class.java).isEqualTo("ENABLED")
        assertThat(requested()["outcome"] as String).startsWith("Allowed - http://localhost:")

        graphQlTester.document("mutation { disableBoxPort(id: $id) { state alive } }").execute()
            .path("disableBoxPort.state").entity(String::class.java).isEqualTo("DISABLED")
            .path("disableBoxPort.alive").entity(Boolean::class.java).isEqualTo(false)
        assertThat(requested()["outcome"]).isEqualTo("Disabled")

        graphQlTester.document("mutation { deleteBoxPort(id: $id) }").execute()
        assertThat(panel()).isEmpty()
        assertThat(requested()["outcome"]).isEqualTo("Removed")
        assertThat(audit.findAll().map { it.message }).anyMatch { it.contains("exposed at http://localhost:") }
            .anyMatch { it.contains("closed") }.anyMatch { it.contains("removed") }
    }

    @Test
    fun `asking for more ports than the setting allows is refused`() {
        graphQlTester.document("mutation { setBoxMaxPorts(count: 1) { boxMaxPorts } }").execute()
        val session = opened()
        call("orkbox_expose", mapOf("session" to session, "port" to 3000))
        assertThat(call("orkbox_expose", mapOf("session" to session, "port" to 3001)).path("error").stringValue())
            .contains("1 of the 1 ports")
        val listed = call("orkbox_ports", mapOf("session" to session)).path("ports")
        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0].path("port").asInt()).isEqualTo(3000)
        assertThat(listed[0].path("state").stringValue()).isEqualTo("pending")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who cannot see the conversation's workspace cannot see or decide its ports`() {
        val session = opened()
        call("orkbox_expose", mapOf("session" to session, "port" to 3000))
        val id = ports.findAll().single().id
        listOf(
            "{ sessionBoxPorts(sessionId: $conversation) { id } }",
            "mutation { enableBoxPort(id: $id) { id } }",
            "mutation { answerRequest(id: ${requests.findAll().single().id}, option: \"allow\") { id } }",
        ).forEach { document ->
            graphQlTester.document(document).execute().errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo("WorkspaceNotFound")
            }
        }
        assertThat(ports.findAll().single().state).isEqualTo(BoxPortState.PENDING)
    }
}
