package io.mszymanski.orknux.server.embedded

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A picture at an address, looked at. An agent searched for reference photos,
 * passed an address to picture_view as a key, was told nothing was kept under
 * it, and said it could not see pictures.
 *
 * The pictures are served on loopback by this test, which records what
 * arrived: a refusal is asserted by the request never having been made, as
 * well as by the sentence. The search index is stood in for by a router whose
 * client answers what tavily would, so nothing leaves the machine.
 */
@SpringBootTest
class PictureAddressesTest(
    @Autowired val tools: AgentTools,
    @Autowired val pictures: PictureAddresses,
    @Autowired val search: SearchSettings,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val store: LlmSessionStore,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val tunables: Tunables,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val policy: HttpToolPolicy,
    @Autowired val mapper: ObjectMapper,
) {
    private lateinit var server: HttpServer
    private val arrived = CopyOnWriteArrayList<String>()
    private lateinit var base: String
    private var workspaceId = 0L
    private var session = 0L

    /** A one-pixel PNG, served as octet-stream so its type can only have come from its bytes. */
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @BeforeEach
    fun start() {
        clean()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            arrived += path
            val (type, body) = when (path) {
                "/page" -> "text/html" to "<html><body>a gallery</body></html>".toByteArray()
                "/big" -> "image/png" to (png + ByteArray(2 * 1024 * 1024))
                else -> "application/octet-stream" to png
            }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "pictures-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "chat", "pictures-${System.nanoTime()}")
        search.set(workspaceId, null, "tvly-test", null, null, "alice")
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        workspaces.deleteById(workspaceId)
        clean()
    }

    private fun clean() {
        settings.deleteAll(settings.findAll().filter { it.name.startsWith("http.tools.") })
        if (settings.existsById(Tunable.PICTURE_URL_MB.key)) tunables.set(Tunable.PICTURE_URL_MB, null, "test")
    }

    private fun agent(vararg reaching: String) = Agent(workspaceId = workspaceId, name = "looker", type = AgentType.LLM).apply {
        tools = (BuiltInTools.GRANTED + reaching).toMutableList()
    }

    private fun view(agent: Agent, arguments: Map<String, String>): String =
        tools.run(agent, ToolCall("v", AgentTools.VIEW, mapper.writeValueAsString(arguments)), session)

    private fun error(answer: String): String? = mapper.readTree(answer).path("error").takeIf { it.isTextual }?.stringValue()

    /** A picture search answering these addresses, through the door a model's search goes through. */
    private fun searched(vararg addresses: String): JsonNode {
        val answered = """{"images":[${addresses.joinToString(",") { "{\"url\":\"$it\",\"description\":\"a photo\"}" }}]}"""
        val capability = SearchCapability(search, router(answered), mapper, Tuning.DEFAULTS, pictures)
        return mapper.readTree(capability.run("searchImages", """{"query":"reference photos"}""", workspaceId, session))
    }

    @Test
    fun `a picture a search answered is fetched into a key by its bytes, and shown`() {
        val answer = searched("$base/photo")
        // The note names the viewer and its parameter as they are declared.
        assertThat(answer.path("note").stringValue())
            .contains(AgentTools.VIEW_TOOL.name)
            .contains(AgentTools.VIEW_URL)
        assertThat(AgentTools.VIEW_TOOL.parameters.map { it.name }).contains(AgentTools.VIEW_URL)
        assertThat(AgentTools.VIEW_TOOL.description).contains(AgentTools.VIEW_URL)
        assertThat(arrived).isEmpty()

        // An agent with no HTTP tool at all, which is the one that could not look.
        val looker = agent()
        val seen = view(looker, mapOf(AgentTools.VIEW_URL to "$base/photo"))
        assertThat(error(seen)).describedAs(seen).isNull()
        assertThat(AgentTools.pictureIn(seen)?.type).isEqualTo("image/png")
        val key = mapper.readTree(seen).path("contentKey").stringValue()
        assertThat(key).startsWith("picture.").doesNotContain("http")
        // Typed by its bytes: the server said octet-stream.
        val kind = store.kindOf(session, key)
        assertThat(kind?.contentType).isEqualTo("image/png")
        assertThat(kind?.binary).isTrue()
        // And nothing the model is handed is an address to paste.
        assertThat(AgentTools.withoutPicture(seen, requireNotNull(AgentTools.pictureIn(seen)))).doesNotContain(base)

        // The address put in the key, as the model first did, is the same picture, fetched once.
        val again = view(looker, mapOf("contentKey" to "$base/photo"))
        assertThat(AgentTools.pictureIn(again)).isNotNull()
        assertThat(arrived).containsExactly("/photo")

        // And the key is a key like any other.
        assertThat(AgentTools.pictureIn(view(looker, mapOf("contentKey" to key)))).isNotNull()
    }

    @Test
    fun `a page or a picture larger than the limit is refused in a sentence, and nothing is kept`() {
        searched("$base/page", "$base/big")
        val looker = agent()

        val page = view(looker, mapOf(AgentTools.VIEW_URL to "$base/page"))
        assertThat(error(page)).contains("a web page", "not a picture")
        assertThat(store.get(session, PictureAddresses.keyFor("$base/page"))).isNull()

        tunables.set(Tunable.PICTURE_URL_MB, 1, "test")
        val big = view(looker, mapOf(AgentTools.VIEW_URL to "$base/big"))
        assertThat(error(big)).contains("more than 1 MB")
        assertThat(store.get(session, PictureAddresses.keyFor("$base/big"))).isNull()
    }

    @Test
    fun `an address no search answered is fetched only for an agent holding an HTTP tool, under its allow list`() {
        val bare = view(agent(), mapOf(AgentTools.VIEW_URL to "$base/own"))
        assertThat(error(bare)).contains("not an address a picture search answered", "no HTTP tool")
        assertThat(arrived).isEmpty()

        val fetcher = agent(BuiltInTools.HTTP_DOWNLOAD)
        assertThat(AgentTools.pictureIn(view(fetcher, mapOf(AgentTools.VIEW_URL to "$base/own")))).isNotNull()
        assertThat(arrived).containsExactly("/own")

        policy.save(HttpToolPolicyKind.LIST, listOf(HttpToolRule(Regex.escape(base) + "/allowed.*", listOf("GET"))), "test")
        assertThat(error(view(fetcher, mapOf(AgentTools.VIEW_URL to "$base/elsewhere")))).contains("allow list")
        assertThat(AgentTools.pictureIn(view(fetcher, mapOf(AgentTools.VIEW_URL to "$base/allowed")))).isNotNull()

        policy.setEnabled(false, "test")
        assertThat(error(view(fetcher, mapOf(AgentTools.VIEW_URL to "$base/other")))).describedAs("switched off")
            .contains("not an address a picture search answered")
        assertThat(arrived).containsExactly("/own", "/allowed")
    }

    @Test
    fun `an address the guard refuses is refused, whoever answered it`() {
        val metadata = "http://169.254.169.254/latest/meta-data/photo.png"
        searched(metadata)
        val refused = view(agent(BuiltInTools.HTTP_DOWNLOAD), mapOf(AgentTools.VIEW_URL to metadata))
        assertThat(error(refused)).contains("may not be reached")
        assertThat(store.get(session, PictureAddresses.keyFor(metadata))).isNull()
    }

    /**
     * Where a tool that reads a key is handed an address, it says how an
     * address becomes a key rather than that nothing is kept there - which is
     * true, and is what taught an agent it could not see pictures.
     */
    @Test
    fun `a key reader handed an address says how to bring it in`() {
        val saved = tools.run(
            agent(),
            ToolCall("s", AgentTools.SAVE_ARTIFACT, """{"name":"a.png","contentKey":"https://example.invalid/a.png"}"""),
            session,
        )
        assertThat(error(saved))
            .contains("is an address, not a key")
            .contains(AgentTools.VIEW_TOOL.name)
            .contains(AgentTools.VIEW_URL)
            .contains(BuiltInTools.HTTP_DOWNLOAD)
            .doesNotContain("Nothing is kept")
        assertThat(PictureAddresses.nothingUnder("shot.png")).isEqualTo("Nothing in this session is kept under \"shot.png\".")
        // The names it gives are names something declares.
        assertThat(tools.specsFor(agent(BuiltInTools.HTTP_DOWNLOAD)).map { it.name })
            .contains(BuiltInTools.HTTP_DOWNLOAD, AgentTools.VIEW_TOOL.name)
    }

    /** A router whose client answers [body] to every request, so the search never leaves the machine. */
    private fun router(body: String): ProxyRouter {
        val answer = mock(HttpResponse::class.java, Answer { asked ->
            when (asked.method.name) {
                "statusCode" -> 200
                "body" -> body
                else -> null
            }
        })
        val client = mock(HttpClient::class.java, Answer { asked -> if (asked.method.name == "send") answer else null })
        return mock(ProxyRouter::class.java, Answer { asked ->
            when (asked.method.name) {
                "client" -> client
                "authorized" -> asked.getArgument<HttpRequest>(0)
                else -> null
            }
        })
    }
}
