package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.mcp.WireframeTools
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wireframes in the Design perspective - issue #655.
 *
 * Pinned: the editor's own door (make, rename, save, remove, each audited), the
 * shapes a save refuses and says why, a save over a drawing changed since it was
 * opened, the Tunable that caps a drawing, the orknux_* tools that let an agent
 * or a model draw through the same door, and the editor's chat - a stub model
 * that draws a button, which has to be on the stored drawing afterwards.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WireframeAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val wireframes: WireframeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
    @Autowired val tools: OrknuxTools,
    @Autowired val chat: WireframeChat,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        wireframes.deleteAll()
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "wireframes-${System.nanoTime()}")).id)
        tunables.set(Tunable.WIREFRAME_ELEMENTS_MOST, null, "alice")
        tunables.set(Tunable.WIREFRAME_HTML_INLINE_CHARACTERS, null, "alice")
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        tunables.set(Tunable.WIREFRAME_ELEMENTS_MOST, null, "alice")
        tunables.set(Tunable.WIREFRAME_HTML_INLINE_CHARACTERS, null, "alice")
    }

    private fun create(name: String, document: String? = null): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}n: String!, ${'$'}d: String) { createWireframe(workspaceId: ${'$'}w, name: ${'$'}n, document: ${'$'}d) { id } }""",
    ).variable("w", workspaceId).variable("n", name).variable("d", document)
        .execute().path("createWireframe.id").entity(Long::class.java).get()

    private fun save(id: Long, document: String, revision: Int? = null) = graphQlTester.document(
        """mutation(${'$'}id: ID!, ${'$'}d: String!, ${'$'}r: Int) { saveWireframe(id: ${'$'}id, document: ${'$'}d, revision: ${'$'}r) { revision elementCount document } }""",
    ).variable("id", id).variable("d", document).variable("r", revision).execute()

    private fun elementsOf(id: Long): JsonNode =
        mapper.readTree(requireNotNull(wireframes.findById(id).orElse(null)).document).path("elements")

    @Test
    fun `a wireframe is made, listed, renamed, saved and removed, and each is audited`() {
        val id = create("Sign in")
        graphQlTester.document("""{ wireframes(workspaceId: $workspaceId) { name elementCount revision createdBy } }""")
            .execute()
            .path("wireframes[*].name").entityList(String::class.java).containsExactly("Sign in")
            .path("wireframes[0].elementCount").entity(Int::class.java).isEqualTo(0)
            .path("wireframes[0].createdBy").entity(String::class.java).isEqualTo("alice")

        graphQlTester.document("""mutation { renameWireframe(id: $id, name: "Log in") { name } }""")
            .execute().path("renameWireframe.name").entity(String::class.java).isEqualTo("Log in")

        save(
            id,
            """{"elements":[{"id":"f","type":"frame","x":0,"y":0,"w":390,"h":844,"text":"Log in"},
               {"type":"button","x":24.4,"y":"700","w":342,"h":48,"text":"Continue","props":{"variant":"primary","nested":{"no":1}}}]}""",
            revision = 1,
        ).path("saveWireframe.revision").entity(Int::class.java).isEqualTo(2)
            .path("saveWireframe.elementCount").entity(Int::class.java).isEqualTo(2)

        val stored = elementsOf(id)
        assertThat(stored.get(1).path("type").stringValue()).isEqualTo("button")
        // Rounded to whole pixels, read from a string, given an id, and only flat props kept.
        assertThat(stored.get(1).path("x").asInt()).isEqualTo(24)
        assertThat(stored.get(1).path("y").asInt()).isEqualTo(700)
        assertThat(stored.get(1).path("id").stringValue()).isNotBlank()
        assertThat(stored.get(1).path("props").path("variant").stringValue()).isEqualTo("primary")
        assertThat(stored.get(1).path("props").has("nested")).isFalse()

        graphQlTester.document("""{ wireframe(id: $id) { name revision document } }""")
            .execute().path("wireframe.revision").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""mutation { deleteWireframe(id: $id) }""")
            .execute().path("deleteWireframe").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""{ wireframe(id: $id) { name } }""").execute().path("wireframe").valueIsNull()

        assertThat(audit.findAll().map { it.message }).contains(
            "Wireframe Sign in added",
            "Wireframe Sign in renamed to Log in",
            "Wireframe Log in saved with 2 elements",
            "Wireframe Log in removed",
        )
    }

    @Test
    fun `a save made against an older revision is refused rather than written over the newer one`() {
        val id = create("Home")
        save(id, """{"elements":[{"type":"rect","x":0,"y":0,"w":10,"h":10}]}""", revision = 1)
            .path("saveWireframe.revision").entity(Int::class.java).isEqualTo(2)
        save(id, """{"elements":[]}""", revision = 1).errors()
            .expect { it.message == "Wireframe Home was changed since it was opened; open it again before saving" }.verify()
        assertThat(elementsOf(id).size()).isEqualTo(1)
    }

    @Test
    fun `a drawing that is not one is refused, saying what is wrong with it`() {
        val id = create("Broken")
        save(id, "not json").errors().expect { it.message == "The wireframe cannot be saved: it is not JSON" }.verify()
        save(id, """{"elements":[{"type":"circle"}]}""").errors()
            .expect { it.message?.startsWith("The wireframe cannot be saved: element 1 has type circle, which is not one of frame, rect") == true }
            .verify()
        save(id, """{"elements":[{"id":"a","type":"rect"},{"id":"a","type":"text"}]}""").errors()
            .expect { it.message == "The wireframe cannot be saved: two elements have the id a" }.verify()
        save(id, """{"elements":[{"type":"rect","x":"left"}]}""").errors()
            .expect { it.message?.contains("has x \"left\", which is not a number") == true }.verify()
        graphQlTester.document("""mutation { createWireframe(workspaceId: $workspaceId, name: "  ") { id } }""")
            .execute().errors().expect { it.message?.contains("needs a name") == true }.verify()
    }

    @Test
    fun `a drawing past the elements an installation allows is refused with the count`() {
        tunables.set(Tunable.WIREFRAME_ELEMENTS_MOST, 10, "alice")
        val id = create("Crowded")
        val eleven = (1..11).joinToString(",", "{\"elements\":[", "]}") { """{"type":"rect","x":$it}""" }
        save(id, eleven).errors()
            .expect { it.message == "A wireframe holds at most 10 elements, and this one would hold 11" }.verify()
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace cannot see or draw its wireframes`() {
        val id = requireNotNull(
            wireframes.save(Wireframe(workspaceId = workspaceId, name = "Secret", document = "{\"elements\":[]}", createdBy = "alice", updatedBy = "alice")).id,
        )
        graphQlTester.document("""{ wireframes(workspaceId: $workspaceId) { name } }""").execute().errors().expect { true }.verify()
        save(id, """{"elements":[]}""").errors().expect { true }.verify()
        assertThat(wireframes.findById(id).get().revision).isEqualTo(1)
    }

    /** An agent with nobody signed in draws through the orknux_* tools, under its own name. */
    @Test
    fun `the orknux tools make, change and redraw a wireframe through the same door`() {
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Sketcher")
        fun call(name: String, arguments: String): JsonNode = mapper.readTree(tools.run(scope, name, arguments))

        assertThat(tools.specs(scope).map { it.name }).containsAll(WireframeTools.NAMES)
        assertThat(tools.specs(scope.copy(mayWrite = false)).map { it.name }.filter { it in WireframeTools.NAMES })
            .containsExactlyInAnyOrder(WireframeTools.LIST, WireframeTools.READ, WireframeTools.HTML)

        val made = call(
            WireframeTools.CREATE,
            """{"name":"Checkout","add":[{"id":"screen","type":"frame","x":0,"y":0,"w":390,"h":844,"text":"Checkout"},
               {"id":"pay","type":"button","x":24,"y":760,"w":342,"h":48,"text":"Pay"}]}""",
        )
        assertThat(made.has("error")).describedAs(made.toString()).isFalse()
        val id = made.path("id").asLong()
        assertThat(made.path("url").stringValue()).endsWith("/workspace/$workspaceId/design/wireframes/$id")

        val changed = call(
            WireframeTools.CHANGE,
            """{"wireframe":"checkout","update":[{"id":"pay","text":"Pay now","props":{"variant":"primary"}}],
               "add":[{"type":"input","x":24,"y":120,"w":342,"h":40,"text":"Card number"}]}""",
        )
        assertThat(changed.has("error")).describedAs(changed.toString()).isFalse()
        val elements = elementsOf(id)
        assertThat((0 until elements.size()).map { elements.get(it).path("text").stringValue() })
            .containsExactly("Checkout", "Pay now", "Card number")
        assertThat(elements.get(1).path("props").path("variant").stringValue()).isEqualTo("primary")
        assertThat(elements.get(1).path("y").asInt()).isEqualTo(760)

        val removed = call(WireframeTools.CHANGE, """{"wireframe":"$id","remove":["pay"]}""")
        assertThat(removed.path("elements").size()).isEqualTo(2)
        assertThat(call(WireframeTools.CHANGE, """{"wireframe":"$id","remove":["nothing"]}""").path("error").stringValue())
            .contains("has no element nothing")

        val redrawn = call(WireframeTools.DRAW, """{"wireframe":"$id","elements":"[{\"type\":\"note\",\"text\":\"Start again\"}]"}""")
        assertThat(redrawn.path("elements").size()).describedAs(redrawn.toString()).isEqualTo(1)
        assertThat(call(WireframeTools.DRAW, """{"wireframe":"$id","elements":[{"type":"blob"}]}""").path("error").stringValue())
            .contains("not one of")

        val read = call(WireframeTools.READ, """{"wireframe":"Checkout"}""")
        assertThat(read.path("elements").get(0).path("type").stringValue()).isEqualTo("note")
        assertThat(call(WireframeTools.LIST, "{}").path("wireframes").get(0).path("name").stringValue()).isEqualTo("Checkout")

        assertThat(audit.findAll().filter { it.message.startsWith("Wireframe Checkout") }.map { it.userId }.toSet())
            .containsExactly("Sketcher")
    }

    /**
     * A checkbox and a style, through the tools: the descriptions say what
     * exists, read off the declarations; a change merges a style into what is
     * there, a hostile one is refused saying why, and what is kept is the one
     * spelling the exports write.
     */
    @Test
    fun `the tools draw checkboxes and styles, say which there are, and refuse a colour that is not one`() {
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Sketcher")
        fun call(name: String, arguments: String): JsonNode = mapper.readTree(tools.run(scope, name, arguments))

        val drawing = tools.specs(scope).filter { it.name in setOf(WireframeTools.CREATE, WireframeTools.CHANGE, WireframeTools.DRAW) }
        assertThat(drawing).hasSize(3)
        drawing.forEach { spec ->
            WireframeElementType.entries.forEach { assertThat(spec.description).describedAs(spec.name).contains("${it.key} - ") }
            (WireframeStyle.NAMES - WireframeStyle.CHECKED).forEach { assertThat(spec.description).describedAs(spec.name).contains(it) }
        }

        val made = call(
            WireframeTools.CREATE,
            """{"name":"Preferences","add":[{"id":"news","type":"checkbox","x":0,"y":0,"w":200,"h":24,"text":"Newsletter","props":{"checked":true}},
               {"id":"save","type":"button","x":0,"y":40,"w":120,"h":40,"text":"Save"}]}""",
        )
        assertThat(made.has("error")).describedAs(made.toString()).isFalse()
        val id = made.path("id").asLong()

        val styled = call(
            WireframeTools.CHANGE,
            """{"wireframe":"$id","update":[{"id":"save","props":{"fill":"linear-gradient(45deg, Navy, #F80)","color":"white","shadow":"Small","radius":"20"}}]}""",
        )
        assertThat(styled.has("error")).describedAs(styled.toString()).isFalse()
        val props = elementsOf(id).get(1).path("props")
        assertThat(props.path("fill").stringValue()).isEqualTo("linear-gradient(45deg, #000080 0%, #ff8800 100%)")
        assertThat(props.path("color").stringValue()).isEqualTo("#ffffff")
        assertThat(props.path("shadow").stringValue()).isEqualTo("small")
        assertThat(props.path("radius").asInt()).isEqualTo(20)
        assertThat(elementsOf(id).get(0).path("props").path("checked").booleanValue()).isTrue()

        val refused = call(WireframeTools.CHANGE, """{"wireframe":"$id","update":[{"id":"save","props":{"fill":"red;background:url(javascript:alert(1))"}}]}""")
        assertThat(refused.path("error").stringValue()).contains("element save has fill").contains("not a colour")
        assertThat(elementsOf(id).get(1).path("props").path("fill").stringValue()).startsWith("linear-gradient(45deg")

        val html = call(WireframeTools.HTML, """{"wireframe":"$id"}""").path("html").stringValue()
        assertThat(html).contains("<input type=\"checkbox\" checked><span>Newsletter</span>")
            .contains("background:linear-gradient(45deg, #000080 0%, #ff8800 100%);color:#ffffff;border-radius:20px;box-shadow:0 1px 3px rgba(0,0,0,0.2)")
    }

    /** The editor's export: the drawing it holds, unsaved or not, as a page; checked as a save is, and audited. */
    @Test
    fun `the editor exports the drawing it holds as HTML, and the export is audited`() {
        val id = create("Sign up", """{"elements":[{"id":"f","type":"frame","x":0,"y":0,"w":390,"h":844,"text":"Sign up"}]}""")
        fun export(document: String?) = graphQlTester.document(
            """mutation(${'$'}id: ID!, ${'$'}d: String) { exportWireframeHtml(id: ${'$'}id, document: ${'$'}d) }""",
        ).variable("id", id).variable("d", document).execute()

        val stored = export(null).path("exportWireframeHtml").entity(String::class.java).get()
        assertThat(stored).startsWith("<!doctype html>").contains("<h2 class=\"screen-title\">Sign up</h2>").doesNotContain("<button")

        val held = export(
            """{"elements":[{"id":"f","type":"frame","x":0,"y":0,"w":390,"h":844,"text":"Sign up"},
               {"type":"button","x":24,"y":700,"w":342,"h":48,"text":"<Create>"}]}""",
        ).path("exportWireframeHtml").entity(String::class.java).get()
        assertThat(held).contains(">&lt;Create&gt;</button>")
        // Written nowhere: the stored drawing is as it was.
        assertThat(elementsOf(id).size()).isEqualTo(1)
        assertThat(wireframes.findById(id).get().revision).isEqualTo(1)

        export("""{"elements":[{"type":"circle"}]}""").errors()
            .expect { it.message?.startsWith("The wireframe cannot be saved: element 1 has type circle") == true }.verify()
        assertThat(audit.findAll().filter { it.message == "Wireframe Sign up exported as HTML" }.map { it.userId })
            .containsExactly("alice", "alice")
    }

    /** The link the tool hands out: a download, named after the wireframe, and never a page on this origin. */
    @Test
    fun `the HTML downloads from its link, named after the wireframe`() {
        val id = create("Log in / out", """{"elements":[{"type":"note","x":0,"y":0,"w":100,"h":60,"text":"Hello & bye"}]}""")
        val answer = mvc().perform(get("/api/wireframes/$id/html")).andReturn().response
        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.contentType).startsWith("text/html")
        assertThat(answer.getHeader("Content-Disposition")).startsWith("attachment").contains("Log in out.html")
        assertThat(answer.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(answer.getHeader("Content-Security-Policy")).startsWith("sandbox")
        assertThat(answer.getContentAsString(StandardCharsets.UTF_8)).contains(">Hello &amp; bye</aside>")
        assertThat(audit.findAll().map { it.message }).contains("Wireframe Log in / out exported as HTML")

        assertThat(mvc().perform(get("/api/wireframes/999999999/html")).andReturn().response.status).isEqualTo(404)
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace cannot export its wireframes`() {
        val id = requireNotNull(
            wireframes.save(Wireframe(workspaceId = workspaceId, name = "Secret", document = "{\"elements\":[]}", createdBy = "alice", updatedBy = "alice")).id,
        )
        graphQlTester.document("""mutation { exportWireframeHtml(id: $id) }""").execute().errors().expect { true }.verify()
        val answer = mvc().perform(get("/api/wireframes/$id/html")).andReturn().response
        assertThat(answer.status).isEqualTo(404)
        assertThat(answer.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("Secret")
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("exported") }
    }

    /** orknux_wireframe_html: the page and its link, or the link alone past the Tunable; read-only, and audited. */
    @Test
    fun `the tool hands a model the page and its link, and only the link past the Tunable`() {
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = false, actor = "Builder")
        fun call(arguments: String): JsonNode = mapper.readTree(tools.run(scope, WireframeTools.HTML, arguments))
        val id = requireNotNull(
            wireframes.save(
                Wireframe(
                    workspaceId = workspaceId, name = "Pricing", createdBy = "alice", updatedBy = "alice",
                    document = """{"elements":[{"id":"buy","type":"button","x":0,"y":0,"w":120,"h":40,"text":"Buy"}]}""",
                ),
            ).id,
        )

        val answered = call("""{"wireframe":"pricing"}""")
        assertThat(answered.has("error")).describedAs(answered.toString()).isFalse()
        assertThat(answered.path("html").stringValue()).contains(">Buy</button>")
        assertThat(answered.path("download").stringValue()).endsWith("/api/wireframes/$id/html")
        assertThat(answered.path("url").stringValue()).endsWith("/workspace/$workspaceId/design/wireframes/$id")

        tunables.set(Tunable.WIREFRAME_HTML_INLINE_CHARACTERS, 1_000, "alice")
        val long = call("""{"wireframe":"$id"}""")
        assertThat(long.has("html")).isFalse()
        assertThat(long.path("characters").asInt()).isGreaterThan(1_000)
        assertThat(long.path("note").stringValue()).contains("past the 1000")
        assertThat(long.path("download").stringValue()).endsWith("/api/wireframes/$id/html")

        assertThat(call("""{"wireframe":"Nothing"}""").path("error").stringValue()).contains("no wireframe called Nothing")
        assertThat(audit.findAll().filter { it.message == "Wireframe Pricing exported as HTML" }.map { it.userId })
            .containsExactly("Builder", "Builder")
    }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

    /** The briefing names every tool it mentions off what is offered, and the chat is offered drawing alone. */
    @Test
    fun `the chat names only the tools it is offered`() {
        val view = WireframeView(1, workspaceId, "Home", "{}", 0, 1, "alice", "", "alice", "")
        val offered = chat.offered(OrknuxScope(workspaceId = workspaceId, mayWrite = true))
        assertThat(offered.map { it.name }).containsExactlyInAnyOrderElementsOf(WireframeTools.DRAWING)
        val briefing = chat.briefing(view, offered)
        WireframeTools.DRAWING.forEach { assertThat(briefing).contains("`$it`") }
    }

    /** A model asked in the editor draws a button; it is on the stored drawing, and the answer says the drawing changed. */
    @Test
    fun `a model asked in the editor draws into the stored wireframe`() {
        val id = create("Landing", """{"elements":[{"id":"screen","type":"frame","x":0,"y":0,"w":1440,"h":900,"text":"Landing"}]}""")
        val modelId = model(
            serve { body ->
                if (received.size == 1) {
                    val arguments = mapper.writeValueAsString(
                        mapOf(
                            "wireframe" to id.toString(),
                            "add" to listOf(mapOf("type" to "button", "x" to 640, "y" to 420, "w" to 160, "h" to 48, "text" to "Get started")),
                        ),
                    )
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"${WireframeTools.CHANGE}","arguments":${mapper.writeValueAsString(arguments)}}}
                     ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                } else {
                    assertThat(body).contains("Get started")
                    """{"choices":[{"message":{"role":"assistant","content":"Added a Get started button."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )

        graphQlTester.document(
            """mutation { askWireframe(id: $id, modelId: $modelId, messages: [{role: "user", content: "Add a call to action"}]) {
                 answer changed wireframe { revision elementCount } } }""",
        ).execute()
            .path("askWireframe.answer").entity(String::class.java).isEqualTo("Added a Get started button.")
            .path("askWireframe.changed").entity(Boolean::class.java).isEqualTo(true)
            .path("askWireframe.wireframe.elementCount").entity(Int::class.java).isEqualTo(2)

        // Offered the drawing tools and no others.
        assertThat(received.first()).contains(WireframeTools.CHANGE).doesNotContain("orknux_workflows")
        val elements = elementsOf(id)
        assertThat(elements.get(1).path("text").stringValue()).isEqualTo("Get started")
        assertThat(audit.findAll().filter { it.message == "Wireframe Landing saved with 2 elements" }.map { it.userId })
            .containsExactly("alice")
    }

    private fun serve(answer: (String) -> String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
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
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
