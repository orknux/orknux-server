package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.BuiltInSkills
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.design.WireframeElement
import io.mszymanski.orknux.server.design.WireframeElementType
import io.mszymanski.orknux.server.design.WireframeRepository
import io.mszymanski.orknux.server.design.WireframeSvg
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.embedded.SvgCapability
import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.mcp.WireframeTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO

/**
 * A mockup is a wireframe. Chat 974.
 *
 * Asked to "draw me a mockup of simple user registation form", an agent with
 * Orknux access listed its skills, found none about mockups, and drew a PNG with
 * `svg_draw`; the person answered "ok, but I want wireframe". What it believed,
 * and who told it: `svg_draw` was carried with a description that said nothing
 * about screens, the drawing skill sent "a layout" to it, and the wireframe
 * tools - added after the migration that marked the orknux_* tools Always - sat
 * behind the search at Offer, listed in the briefing as "Makes a new wireframe
 * in this works", with no word a person asking for a mockup uses.
 *
 * Pinned: the briefing and every description an agent reads say a mockup is a
 * wireframe, by the tools' own names; the search finds them by the words a
 * person uses; the drawing skill says so and names only tools that exist; and a
 * wireframe drawn from a chat puts its picture in the chat beside its link.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-mockup-wireframes"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class MockupWireframeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val searching: ToolSearchTools,
    @Autowired val briefing: AgentBriefing,
    @Autowired val skills: SkillTool,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val orknux: OrknuxTools,
    @Autowired val capabilities: EmbeddedCapabilities,
    @Autowired val svgCapability: SvgCapability,
    @Autowired val llmSessions: LlmSessionRepository,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val wireframes: WireframeRepository,
    @Autowired val chatSessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val offered = CopyOnWriteArrayList<String>()

    private val svgDraw: String get() = svgCapability.key + "_" + SvgCapability.DRAW

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mockups-${System.nanoTime()}")).id)
        offered.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    /**
     * The agent of chat 974, as it was: Orknux access, a limit of 75 tools, the
     * built-ins and the orknux_* tools of the time at Always - and the wireframe
     * tools, which came after, at Offer.
     */
    private fun asInChat974(): Agent = agents.save(
        Agent(
            workspaceId = workspaceId,
            name = "Support responder",
            type = AgentType.LLM,
            orknuxAccess = true,
            requiredTools = (BuiltInTools.GRANTED + (builtIns.orknuxNames() - WireframeTools.NAMES)).toMutableList(),
            maxTools = 75,
        ),
    )

    @Test
    fun `the wireframe tools are behind the search there, and the briefing says they draw mockups`() {
        val agent = asInChat974()
        val held = tools.offeringFor(agent)
        assertThat(held.searchable.map { it.name }).describedAs("found, not carried").contains(WireframeTools.CREATE)
        assertThat(held.core.map { it.name }).describedAs("carried").contains(svgDraw)

        val said = requireNotNull(briefing.of(agent))
        val create = said.lines().single { it.startsWith("- ${WireframeTools.CREATE} ") }
        assertThat(create).contains("(load it first)").containsIgnoringCase("mockup")
        val svg = said.lines().single { it.startsWith("- $svgDraw ") }
        assertThat(svg).describedAs("the line svg_draw gets says what it is not for").containsIgnoringCase("mockup")
    }

    @Test
    fun `the words a person uses find the wireframe tools`() {
        val agent = asInChat974()
        val held = tools.offeringFor(agent)
        val shed = searching.shed(held.searchable, mutableSetOf(), room = { 10 }, carried = held.core.map { it.name }.toSet())
        listOf("mockup", "wireframe", "screen mockup", "form layout", "registration form mockup", "UI mockup").forEach { words ->
            val answer = shed.run(ToolCall("1", ToolSearchTools.SEARCH, mapper.writeValueAsString(mapOf(ToolSearchTools.QUERIES to listOf(words)))))
            val names = answer.lines().drop(1).filter { it.isNotBlank() && " - " in it }.map { it.substringBefore(" - ").trim() }
            assertThat(names).describedAs(words).contains(WireframeTools.CREATE)
            assertThat(names.first()).describedAs("$words, best first").isIn(WireframeTools.NAMES)
        }
    }

    @Test
    fun `the descriptions say a mockup is a wireframe, and svg is not for one`() {
        val specs = orknux.specs(OrknuxScope(workspaceId = workspaceId, mayWrite = true)).associateBy { it.name }
        val create = requireNotNull(specs[WireframeTools.CREATE])
        assertThat(create.description).containsIgnoringCase("mockup").contains("form").contains("url")
        assertThat(create.summary).containsIgnoringCase("mockup")
        WireframeTools.NAMES.forEach { name ->
            assertThat(requireNotNull(specs[name]).summary).describedAs(name).isNotBlank.hasSizeLessThanOrEqualTo(50)
        }

        val svg = capabilities.toolSpecs().single { it.name == svgDraw }
        assertThat(svg.description).containsIgnoringCase("mockup").contains(WireframeTools.CREATE)
        assertThat(svg.summary).containsIgnoringCase("mockup")
    }

    /** The page the agent looked for: listed where a mockup is looked for, loadable, and naming real tools. */
    @Test
    fun `the drawing skill sends a mockup to the wireframe tools, by names that exist`() {
        val agent = asInChat974()
        val listed = skills.list(agent).single { it.catalog == BuiltInSkills.CATALOG && it.id == "diagrams-and-charts" }
        assertThat(listed.description).containsIgnoringCase("mockup")
        assertThat(skills.search(agent, "mockup").map { it.id }).contains("diagrams-and-charts")

        val page = requireNotNull(skills.load(agent, "diagrams-and-charts")).content
        assertThat(page).contains("`${WireframeTools.CREATE}`", "`${WireframeTools.CHANGE}`", "`$svgDraw`")
        val known = builtIns.all().map { it.name }.toSet() + builtIns.orknuxNames() + capabilities.toolSpecs().map { it.name }
        val named = Regex("`([a-z]+_[a-zA-Z]+)`").findAll(page).map { it.groupValues[1] }.toSet()
        assertThat(named).isNotEmpty
        assertThat(known).describedAs("every tool the page names is a tool").containsAll(named)
    }

    @Test
    fun `a wireframe drawn in a conversation answers a picture of it, and its svg under a key`() {
        val session = requireNotNull(llmSessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "mockup-${System.nanoTime()}")).id)
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Sketcher", session = session)
        val elements = listOf(
            mapOf("type" to "frame", "x" to 0, "y" to 0, "w" to 360, "h" to 400, "text" to "Sign up"),
            mapOf("type" to "input", "x" to 20, "y" to 40, "w" to 320, "h" to 36, "text" to "Email <you@example.com>"),
            mapOf("type" to "button", "x" to 20, "y" to 100, "w" to 320, "h" to 40, "text" to "Join & \"go\"", "props" to mapOf("variant" to "primary")),
            mapOf("type" to "text", "x" to 20, "y" to 160, "w" to 320, "h" to 20, "text" to "<script>alert(1)</script>"),
        )
        val answer = mapper.readTree(
            orknux.run(scope, WireframeTools.CREATE, mapper.writeValueAsString(mapOf("name" to "Registration", "add" to elements))),
        )
        assertThat(answer.path("url").stringValue()).contains("/design/wireframes/")
        assertThat(answer.path(AgentTools.PICTURE_TYPE).stringValue()).isEqualTo("image/png")
        val png = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(answer.path(AgentTools.PICTURE).stringValue())))
        assertThat(png.width).isGreaterThan(360)
        assertThat(AgentTools.pictureIn(answer.toString())).describedAs("the round lifts it into the conversation").isNotNull

        val key = answer.path("contentKey").stringValue()
        assertThat(scratch.kindOf(session, key)).isEqualTo(StoredKind("image/svg+xml", true))
        val stored = mapper.readTree(requireNotNull(scratch.get(session, key))).stringValue()
        val svg = String(Base64.getDecoder().decode(stored), StandardCharsets.UTF_8)
        assertThat(svg).startsWith("<svg").contains("Sign up", "Email &lt;you@example.com&gt;", "Join &amp; &quot;go&quot;")
            .contains("&lt;script&gt;").doesNotContain("<script")

        // Changed, it is drawn again; through the MCP endpoint there is no conversation, and no picture.
        val changed = mapper.readTree(
            orknux.run(scope, WireframeTools.CHANGE, """{"wireframe":"Registration","add":[{"type":"text","x":20,"y":200,"w":200,"h":20,"text":"Have an account?"}]}"""),
        )
        assertThat(changed.path(AgentTools.PICTURE).stringValue()).isNotEmpty
        val outside = mapper.readTree(
            orknux.run(scope.copy(session = null), WireframeTools.DRAW, """{"wireframe":"Registration","elements":[{"type":"rect","x":0,"y":0,"w":10,"h":10}]}"""),
        )
        assertThat(outside.path("url").stringValue()).isNotEmpty
        assertThat(outside.has(AgentTools.PICTURE)).isFalse()
    }

    @Test
    fun `the svg of a wireframe is grey, escaped and holds what XML cannot be refused for`() {
        val svg = requireNotNull(
            WireframeSvg().drawing(
                listOf(
                    WireframeElement("a", WireframeElementType.NOTE, 0, 0, 200, 60, "a \u0001 b & <c>", emptyMap()),
                    WireframeElement("b", WireframeElementType.ARROW, 0, 80, 100, 0, null, emptyMap()),
                ),
            ),
        )
        assertThat(svg).contains("a b &amp; &lt;c&gt;").doesNotContain("\u0001")
        val colours = Regex("#([0-9a-f]{6})").findAll(svg).map { it.groupValues[1] }.toSet()
        colours.forEach { hex ->
            val (r, g, b) = listOf(0, 2, 4).map { hex.substring(it, it + 2).toInt(16) }
            assertThat(maxOf(r, g, b) - minOf(r, g, b)).describedAs("#$hex is a grey").isLessThanOrEqualTo(12)
        }
        assertThat(WireframeSvg().drawing(emptyList())).isNull()
    }

    /**
     * The whole of it against a stub model: an agent in a chat draws the form as
     * a wireframe, the picture is filed in the chat above its answer, and the
     * model is told it is there and handed the link.
     */
    @Test
    fun `a wireframe an agent draws in a chat is shown in the chat above its answer`() {
        val arguments = mapper.writeValueAsString(
            mapOf(
                "name" to "Sign-up form",
                "add" to listOf(
                    mapOf("type" to "frame", "x" to 0, "y" to 0, "w" to 320, "h" to 300, "text" to "Create account"),
                    mapOf("type" to "button", "x" to 20, "y" to 200, "w" to 280, "h" to 40, "text" to "Sign up"),
                ),
            ),
        )
        val chatId = chatWithAgent(
            serve { body ->
                if (body.contains("inConversation")) null to "Here it is." else WireframeTools.CREATE to arguments
            },
        )
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "draw me a mockup of a registration form") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()

        val made = wireframes.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).single()
        assertThat(made.name).isEqualTo("Sign-up form")
        val filed = attachments.findAll().single { it.chatSessionId == chatId }
        assertThat(filed.contentType).isEqualTo("image/png")
        val conversation = requireNotNull(chatSessions.findByIdOrNull(chatId)).conversationId
        assertThat(history.findByConversationId(conversation).map { "${it.messageType.name.lowercase()}:${it.text.orEmpty()}" })
            .containsExactly(
                "user:draw me a mockup of a registration form",
                "assistant:![](/api/attachments/${filed.id})",
                "assistant:Here it is.",
            )
        assertThat(offered.last()).describedAs("the model is handed the link and told the picture is shown")
            .contains("/design/wireframes/${made.id}").contains("inConversation")
    }

    /* ---------------------------------------------------------- the fixtures */

    /** A stub that answers each request with a tool call (name to arguments) or, where the name is null, words. */
    private fun serve(answer: (String) -> Pair<String?, String>): String {
        val stub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        stub.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            offered += body
            val (tool, said) = answer(body)
            val message = if (tool == null) {
                mapOf("role" to "assistant", "content" to said)
            } else {
                mapOf(
                    "role" to "assistant",
                    "content" to "",
                    "tool_calls" to listOf(mapOf("id" to "call_1", "type" to "function", "function" to mapOf("name" to tool, "arguments" to said))),
                )
            }
            val bytes = mapper.writeValueAsString(
                mapOf("choices" to listOf(mapOf("message" to message)), "usage" to mapOf("prompt_tokens" to 7, "completion_tokens" to 2)),
            ).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        stub.start()
        server = stub
        return "http://${stub.address.hostString}:${stub.address.port}"
    }

    /** A chat handed to an agent with Orknux access, on a model at the stub. */
    private fun chatWithAgent(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub provider", type: OPENAI, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Talker", modelId: "stub-chat", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Designer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Designer", modelId: $modelId, orknuxAccess: true }) { id } }""",
        ).execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Mockups" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }
}
