package io.mszymanski.orknux.server.embedded

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.workflow.script.ScriptResult
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The agents' HTTP tools under Admin Settings -> HTTP tools, and what that
 * setting does not reach. Issue #602.
 *
 * Every request goes to a server this test runs on loopback, which records
 * what arrived: a refusal is asserted by the request never having been made,
 * not only by the sentence the model is handed.
 */
@SpringBootTest
class HttpToolsPolicedTest(
    @Autowired val tools: AgentTools,
    @Autowired val policy: HttpToolPolicy,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val embedded: EmbeddedCapabilities,
    @Autowired val httpCapability: HttpCapability,
    @Autowired val scripts: ScriptRunner,
    @Autowired val mapper: ObjectMapper,
) {
    private lateinit var server: HttpServer
    private val arrived = CopyOnWriteArrayList<String>()
    private lateinit var base: String

    @BeforeEach
    fun start() {
        clean()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            arrived += exchange.requestMethod + " " + exchange.requestURI.path
            if (exchange.requestURI.path == "/start") {
                exchange.responseHeaders.add("Location", "$base/elsewhere")
                exchange.sendResponseHeaders(302, -1)
            } else {
                val body = """{"seen":"${exchange.requestURI.path}"}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        clean()
    }

    private fun clean() {
        settings.deleteAll(settings.findAll().filter { it.name.startsWith("http.tools.") })
    }

    private fun agent(vararg granted: String) = Agent(workspaceId = 1, name = "fetcher", type = AgentType.LLM).apply {
        tools = granted.toMutableList()
    }

    private fun call(agent: Agent, name: String, arguments: Map<String, Any?> = emptyMap()) =
        mapper.readTree(tools.run(agent, ToolCall("c", name, mapper.writeValueAsString(arguments))))

    private fun allowOnly(vararg rules: HttpToolRule) =
        policy.save(HttpToolPolicyKind.LIST, rules.toList(), "test")

    private fun pattern(path: String) = Regex.escape(base) + path

    @Test
    fun `under a list, a matching URL and method goes through and anything else is refused unsent`() {
        val fetcher = agent("http_get", "http_request")
        allowOnly(HttpToolRule(pattern("/open/.*"), listOf("GET")))

        val allowed = call(fetcher, "http_get", mapOf("url" to "$base/open/a"))
        assertThat(allowed.path("status").asInt()).isEqualTo(200)

        val elsewhere = call(fetcher, "http_get", mapOf("url" to "$base/closed"))
        assertThat(elsewhere.path("error").asString())
            .contains("no rule's pattern matches this URL", BuiltInTools.HTTP_ALLOW_LIST)

        val posted = call(fetcher, "http_request", mapOf("url" to "$base/open/a", "method" to "POST", "body" to "x"))
        assertThat(posted.path("error").asString())
            .contains("matches rule 1", "allows GET but not POST", BuiltInTools.HTTP_ALLOW_LIST)

        assertThat(arrived).containsExactly("GET /open/a")
    }

    @Test
    fun `a redirect is not followed, so it cannot carry a request past the list`() {
        val fetcher = agent("http_get")
        allowOnly(HttpToolRule(pattern("/start"), listOf("GET")))

        val answered = call(fetcher, "http_get", mapOf("url" to "$base/start"))
        assertThat(answered.path("status").asInt()).isEqualTo(302)
        assertThat(answered.path("headers").path("location").asString()).isEqualTo("$base/elsewhere")

        // Following it is a call of its own, which meets the list again.
        val followed = call(fetcher, "http_get", mapOf("url" to "$base/elsewhere"))
        assertThat(followed.path("error").asString()).contains("no rule's pattern matches this URL")
        assertThat(arrived).containsExactly("GET /start")
    }

    @Test
    fun `http_download is a GET`() {
        val fetcher = agent("http_download")
        allowOnly(HttpToolRule(pattern("/file"), listOf("POST")))
        assertThat(call(fetcher, "http_download", mapOf("url" to "$base/file")).path("error").asString())
            .contains("allows POST but not GET")

        allowOnly(HttpToolRule(pattern("/file"), listOf("GET")))
        assertThat(call(fetcher, "http_download", mapOf("url" to "$base/file")).path("size").asInt()).isGreaterThan(0)
    }

    @Test
    fun `switched off, the tools are not offered and refuse, and every grant is left as it was`() {
        val fetcher = agent("http_get", "http_download")
        val offered = { tools.specsFor(fetcher).map { it.name } }
        assertThat(offered()).contains("http_get", "http_download", BuiltInTools.HTTP_ALLOW_LIST)

        policy.setEnabled(false, "test")
        assertThat(offered()).doesNotContain("http_get", "http_download", "http_request", BuiltInTools.HTTP_ALLOW_LIST)
        assertThat(call(fetcher, "http_get", mapOf("url" to "$base/a")).path("error").asString())
            .contains("switched off")
        assertThat(call(fetcher, BuiltInTools.HTTP_ALLOW_LIST).path("error").asString()).contains("switched off")
        assertThat(fetcher.tools).containsExactly("http_get", "http_download")
        assertThat(arrived).isEmpty()

        policy.setEnabled(true, "test")
        assertThat(offered()).contains("http_get", "http_download", BuiltInTools.HTTP_ALLOW_LIST)
            .doesNotContain("http_request")
    }

    @Test
    fun `the policy is offered only to an agent holding an HTTP tool`() {
        assertThat(tools.specsFor(agent()).map { it.name }).doesNotContain(BuiltInTools.HTTP_ALLOW_LIST)
        assertThat(call(agent(), BuiltInTools.HTTP_ALLOW_LIST).path("error").asString()).contains("There is no tool")
        assertThat(tools.specsFor(agent("http_request")).map { it.name }).contains(BuiltInTools.HTTP_ALLOW_LIST)
        // web_search reaches outside too, but makes no request to an address a model chose.
        assertThat(tools.specsFor(agent("web_search")).map { it.name }).doesNotContain(BuiltInTools.HTTP_ALLOW_LIST)
    }

    @Test
    fun `the allow list tool answers the policy in both modes, with how to read it`() {
        val fetcher = agent("http_get")
        val any = call(fetcher, BuiltInTools.HTTP_ALLOW_LIST)
        assertThat(any.path("policy").asString()).isEqualTo("any")
        assertThat(any.has("rules")).isFalse()
        assertThat(any.path("howToRead").asString()).contains("Any URL")

        allowOnly(
            HttpToolRule("https://api\\.example\\.com/.*", listOf("GET", "POST")),
            HttpToolRule("https://status\\.example\\.com/", listOf("HEAD")),
        )
        val listed = call(fetcher, BuiltInTools.HTTP_ALLOW_LIST)
        assertThat(listed.path("policy").asString()).isEqualTo("list")
        assertThat(listed.path("rules").toList().map { it.path("url").asString() })
            .containsExactly("https://api\\.example\\.com/.*", "https://status\\.example\\.com/")
        assertThat(listed.path("rules").get(0).path("methods").toList().map { it.asString() }).containsExactly("GET", "POST")
        assertThat(listed.path("howToRead").asString())
            .contains("whole URL", "lists its method", "http_get and http_download are GET", "Redirects are not followed")
    }

    /**
     * The user's own words were "http tools (not functions!!!)": what somebody
     * wrote, and chose the address of, is not what this fences.
     */
    @Test
    fun `a function's orknux http and the http_get workflow function are not fenced`() {
        allowOnly(HttpToolRule("https://nowhere\\.example/", listOf("GET")))
        assertThat(policy.decide("$base/written", "GET").allowed).isFalse()

        val ran = scripts.call(
            """
            export default function fetch(url) {
              const r = orknux.http.get(url);
              return r.error ? 'refused: ' + r.error : r.status;
            }
            """.trimIndent(),
            "fetch",
            listOf(mapper.writeValueAsString("$base/written")),
            on = 1,
        )
        assertThat((ran as ScriptResult.Returned).json).isEqualTo("200")

        val function = embedded.callFunction("http_get", listOf(mapper.writeValueAsString("$base/graph")), 1, null)
        assertThat(function).isInstanceOf(ScriptResult.Returned::class.java)

        // And switched off is about the tools too.
        policy.setEnabled(false, "test")
        assertThat(embedded.callFunction("http_get", listOf(mapper.writeValueAsString("$base/graph")), 1, null))
            .isInstanceOf(ScriptResult.Returned::class.java)

        assertThat(arrived).containsExactly("GET /written", "GET /graph", "GET /graph")
    }

    /**
     * Asked where it may send requests, a model answered from the tool pages it
     * had seen, because none of its HTTP tools said that the destinations are
     * this installation's to decide or which tool knows them. Each says so now.
     */
    @Test
    fun `every HTTP tool tells the model where to ask where it may go`() {
        val described = httpCapability.tools().filter { "${BuiltInTools.HTTP_ALLOW_LIST}" != "http_${it.name}" }
        assertThat(described).isNotEmpty()
        described.forEach { tool ->
            assertThat(tool.description)
                .describedAs("${tool.name} names the allow-list tool")
                .contains(BuiltInTools.HTTP_ALLOW_LIST)
        }
    }

    /**
     * Under a tool ceiling with the HTTP tools only found, the policy is still
     * carried: an agent like that, asked where it could send requests, answered
     * from tool pages it had seen and never searched for the one tool that knew.
     */
    @Test
    fun `the allow list is carried even where the HTTP tools are only found`() {
        val ceilinged = agent("http_get", "http_request").apply { maxTools = 5 }
        val offering = tools.offeringFor(ceilinged)
        assertThat(offering.core.map { it.name }).contains(BuiltInTools.HTTP_ALLOW_LIST)
        assertThat(offering.core.map { it.name }).doesNotContain("http_get", "http_request")
    }
}
