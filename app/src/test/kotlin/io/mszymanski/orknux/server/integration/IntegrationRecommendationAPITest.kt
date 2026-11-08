package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The cards on the Integrations page, read through from a stub orknux.ai that
 * answers the contract in `docs/orknux-ai/integrations.graphql`.
 *
 * What is this server's own is what is asserted: the prompt filled in with this
 * installation's address and the workspace, the tool marked as had where an MCP
 * server by its name is already here - without that name being sent away - and
 * a marketplace that has never heard of recommendations answered as "none
 * offered" rather than as a failure.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IntegrationRecommendationAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val icons: IntegrationIconAPI,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mcpServers: McpServerRepository,
) {

    private var workspaceId = 0L

    @BeforeEach
    fun setUp() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "Studio " + System.nanoTime())).id)
        refuse = false
        asked.clear()
    }

    @AfterEach
    fun tearDown() {
        mcpServers.deleteAll(mcpServers.findAll().filter { it.workspaceId == workspaceId })
        workspaces.deleteById(workspaceId)
    }

    private fun recommendations() = graphQlTester.document(
        """
        query { integrationRecommendations(workspaceId: $workspaceId) {
          offered total next
          items { key name kind runsOn transport effort minutes homepage repository claudePrompt reason installed tooOld steps requirements }
        } }
        """.trimIndent(),
    ).execute()

    @Test
    fun `the cards come back with the prompt filled in for this installation`() {
        val answered = recommendations()

        answered.path("integrationRecommendations.offered").entity(Boolean::class.java).isEqualTo(true)
        answered.path("integrationRecommendations.items[*].key").entityList(String::class.java).containsExactly("blender", "github")
        answered.path("integrationRecommendations.items[0].effort").entity(String::class.java).isEqualTo("QUARTER_HOUR")
        answered.path("integrationRecommendations.items[0].minutes").entity(Int::class.java).isEqualTo(20)
        answered.path("integrationRecommendations.items[0].reason").entity(String::class.java).isEqualTo("You draw in 3D.")
        val prompt = answered.path("integrationRecommendations.items[0].claudePrompt").entity(String::class.java).get()
        assertThat(prompt)
            .contains("Studio ")
            .contains("http://localhost:5173/workspace/$workspaceId/integrations")
            .doesNotContain("{{orknuxUrl}}", "{{workspace}}", "{{mcpServersUrl}}")
            // A placeholder this server does not know is left for Claude to read as it is.
            .contains("{{somethingElse}}")
        answered.path("integrationRecommendations.items[0].tooOld").valueIsNull()
        answered.path("integrationRecommendations.items[1].tooOld").entity(String::class.java).satisfies({ assertThat(it).contains("999.0.0") })
    }

    @Test
    fun `an address that is not a web page is not handed to the page as a link`() {
        val answered = recommendations()

        answered.path("integrationRecommendations.items[1].homepage").valueIsNull()
        answered.path("integrationRecommendations.items[0].repository").entity(String::class.java).isEqualTo("https://github.com/example/blender-mcp")
    }

    @Test
    fun `a tool the workspace already has is marked, and its server's name is not sent away`() {
        mcpServers.save(McpServer(workspaceId = workspaceId, name = "Blender", address = "http://studio.invalid:8000/mcp"))

        val answered = recommendations()

        answered.path("integrationRecommendations.items[0].installed").entity(Boolean::class.java).isEqualTo(true)
        answered.path("integrationRecommendations.items[1].installed").entity(Boolean::class.java).isEqualTo(false)
        assertThat(asked.single()).doesNotContain("studio.invalid").doesNotContain("\"Blender\"")
        assertThat(asked.single()).contains("\"server\"")
    }

    @Test
    fun `a marketplace without recommendations is none offered, not a failure`() {
        refuse = true

        val answered = recommendations()

        answered.path("integrationRecommendations.offered").entity(Boolean::class.java).isEqualTo(false)
        answered.path("integrationRecommendations.items").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `the tags come through for the filter`() {
        graphQlTester.document("query { integrationTags { tag label count } }").execute()
            .path("integrationTags[*].label").entityList(String::class.java).containsExactly("3D", "Code")
    }

    @Test
    fun `placeholders, addresses and versions`() {
        assertThat(IntegrationRecommendationAPI.fill("At {{a}} and {{b}}", mapOf("a" to "x"))).isEqualTo("At x and {{b}}")
        assertThat(IntegrationRecommendationAPI.web("javascript:alert(1)")).isFalse()
        assertThat(IntegrationRecommendationAPI.web("https://kicad.org")).isTrue()
        assertThat(IntegrationRecommendationAPI.newer("1.4.0", "1.3.6")).isTrue()
        assertThat(IntegrationRecommendationAPI.newer("1.3.6", "1.3.6")).isFalse()
        assertThat(IntegrationRecommendationAPI.newer("1.3.10", "1.3.9")).isTrue()
        assertThat(IntegrationRecommendationAPI.newer("1.3", "1.3.0-SNAPSHOT")).isFalse()
        assertThat(IntegrationRecommendationAPI.newer("whenever", "1.3.6")).isFalse()
    }

    @Test
    fun `the cards can be ordered by popularity or name, either way, and orknux_ai is asked so`() {
        graphQlTester.document(
            """{ integrationRecommendations(workspaceId: $workspaceId, order: NAME, ascending: false) { items { key } } }""",
        ).execute().path("integrationRecommendations.items").entityList(Map::class.java).hasSizeGreaterThan(0)
        assertThat(asked.last()).contains("order: ${'$'}order").contains("ascending: ${'$'}ascending")
        assertThat(asked.last()).contains("\"order\":\"NAME\"").contains("\"ascending\":false")

        asked.clear()
        graphQlTester.document("""{ integrationRecommendations(workspaceId: $workspaceId) { items { key } } }""")
            .execute().path("integrationRecommendations.items").entityList(Map::class.java).hasSizeGreaterThan(0)
        assertThat(asked.single()).describedAs("orknux.ai's own order asks for none").doesNotContain("order:").doesNotContain("ascending")
    }

    @Test
    fun `GitHub's stars and an archived repository come through for the card`() {
        val repo = graphQlTester.document(
            """{ integrationRecommendations(workspaceId: $workspaceId) { items { key github { fullName url stars archived checkedAt } } } }""",
        ).execute().path("integrationRecommendations.items[?(@.key == 'blender')].github").entityList(Map::class.java).get().single()
        assertThat(repo["stars"]).isEqualTo(12345)
        assertThat(repo["archived"]).isEqualTo(true)
        assertThat(repo["url"]).isEqualTo("https://github.com/ahujasid/blender-mcp")
        assertThat(asked.last()).contains("github { fullName url stars archived checkedAt }")
    }

    @Test
    fun `an icon orknux_ai offers is served from this server, and only a picture`() {
        val card = graphQlTester.document(
            """{ integrationRecommendations(workspaceId: $workspaceId) { items { key icon iconDark } } }""",
        ).execute().path("integrationRecommendations.items[?(@.key == 'blender')]").entityList(Map::class.java).get().single()
        // orknux.ai's file name - its digest - is in the address, so a new logo is a new address.
        assertThat(card["icon"]).isEqualTo("/api/integrations/blender/icon?v=blender.svg")

        val light = icons.icon("blender", dark = false)
        assertThat(light.statusCode.value()).isEqualTo(200)
        assertThat(light.headers.contentType.toString()).isEqualTo("image/svg+xml")
        assertThat(String(light.body ?: ByteArray(0))).isEqualTo(BLENDER_SVG)
        assertThat(light.headers.getFirst("Content-Security-Policy")).contains("sandbox")
        assertThat(light.headers.cacheControl).contains("immutable")

        // Its dark one is a page with a script in it: not passed on as a picture.
        assertThat(icons.icon("blender", dark = true).statusCode.value()).isEqualTo(404)
        assertThat(icons.icon("nothing-like-it", dark = false).statusCode.value()).isEqualTo(404)
    }

    companion object {
        @Volatile private var refuse = false
        private val asked = CopyOnWriteArrayList<String>()

        private const val CARDS = """
            {"data":{"integrations":{"total":2,"next":null,"items":[
              {"reason":"You draw in 3D.","installed":false,"integration":{
                "key":"blender","name":"Blender","vendor":"Blender Foundation","summary":"3D scenes.","description":"",
                "icon":"ICON_BASE/icons/blender.svg","iconDark":"ICON_BASE/icons/script.html",
                "github":{"fullName":"ahujasid/blender-mcp","url":"https://github.com/ahujasid/blender-mcp","stars":12345,"archived":true,"checkedAt":"2026-11-08T00:00:00Z"},
                "tags":["3d"],"homepage":"https://www.blender.org","repository":"https://github.com/example/blender-mcp",
                "license":"MIT","maintainer":"COMMUNITY","popularity":100,"kind":"MCP_SERVER","transport":"STDIO",
                "runsOn":"USER_MACHINE","sampleTools":["get_scene_info"],"minimumServer":null,
                "setup":{"effort":"QUARTER_HOUR","minutes":20,"requirements":["Blender 3.0"],"needsAdmin":false,
                  "steps":"1. Install it.","claudePrompt":"Connect Blender to \"{{workspace}}\" at {{orknuxUrl}}; add it on {{mcpServersUrl}}. {{somethingElse}}"}}},
              {"reason":null,"installed":false,"integration":{
                "key":"github","name":"GitHub","vendor":"GitHub","summary":"Code.","description":"",
                "tags":["code"],"homepage":"javascript:alert(1)","repository":null,
                "license":"MIT","maintainer":"OFFICIAL","popularity":null,"kind":"MCP_SERVER","transport":"STREAMABLE_HTTP",
                "runsOn":"ANYWHERE","sampleTools":[],"minimumServer":"999.0.0",
                "setup":{"effort":"MINUTES","minutes":5,"requirements":[],"needsAdmin":false,"steps":"","claudePrompt":"Hi"}}}
            ]}}}
        """

        private const val BLENDER_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect width="1" height="1"/></svg>"""

        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                fun answer(exchange: HttpExchange, body: String) {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                createContext("/icons/blender.svg") { exchange ->
                    val bytes = BLENDER_SVG.toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "image/svg+xml")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                // An "icon" that is a page: never served on as a picture.
                createContext("/icons/script.html") { exchange -> answer(exchange, "<script>alert(1)</script>") }
                createContext("/graphql") { exchange ->
                    val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    when {
                        refuse -> answer(exchange, """{"errors":[{"message":"Field 'integrations' is undefined"}]}""")
                        "integrationTags" in body ->
                            answer(exchange, """{"data":{"integrationTags":[{"tag":"3d","label":"3D","count":1},{"tag":"code","label":"Code","count":1}]}}""")
                        else -> {
                            asked += body
                            answer(exchange, CARDS.replace("ICON_BASE", "http://${address.hostString}:${address.port}"))
                        }
                    }
                }
                start()
            }

        @JvmStatic
        @AfterAll
        fun stop() = stub.stop(0)

        @JvmStatic
        @DynamicPropertySource
        fun marketplace(registry: DynamicPropertyRegistry) {
            registry.add("orknux.marketplace.url") { "http://${stub.address.hostString}:${stub.address.port}/graphql" }
            registry.add("orknux.marketplace.install-key") { "test-key" }
        }
    }
}
