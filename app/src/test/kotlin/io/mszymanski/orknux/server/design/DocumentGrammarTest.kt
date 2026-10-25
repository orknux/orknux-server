package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The document editor's grammar check, in both languages the interface is read
 * in: LanguageTool inside the server finds a known English and a known Polish
 * mistake and offers what goes there instead, at the right place in the text;
 * and pointed at a LanguageTool server, the text goes there and its answer
 * comes back in the same shape.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentGrammarTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val proxyRules: io.mszymanski.orknux.connector.proxy.ProxyRuleRepository,
    @Autowired val router: io.mszymanski.orknux.connector.proxy.ProxyRouter,
) {

    private var server: HttpServer? = null

    @AfterEach
    fun back() {
        server?.stop(0)
        graphQlTester.document("mutation { setGrammarServer(url: \"\") { server } }").execute().errors().verify()
    }

    private fun check(language: String, text: String): List<Map<*, *>> = graphQlTester.document(
        """query(${'$'}l: String!, ${'$'}t: String!) { grammarCheck(language: ${'$'}l, text: ${'$'}t) { offset length message replacements spelling } }""",
    ).variable("l", language).variable("t", text).execute().errors().verify()
        .path("grammarCheck").entityList(Map::class.java).get()

    @Test
    fun `an English mistake is found where it is, with what goes there instead`() {
        val text = "This sentence has a speling mistake."
        val found = check("en", text)
        val match = found.single { text.substring(it["offset"] as Int, it["offset"] as Int + it["length"] as Int) == "speling" }
        assertThat(match["replacements"] as List<*>).contains("spelling")
        assertThat(match["spelling"]).isEqualTo(true)
    }

    @Test
    fun `a Polish mistake is found where it is, with what goes there instead`() {
        val text = "To jest dom, ktory stoi nad rzeka."
        val found = check("pl", text)
        val match = found.single { text.substring(it["offset"] as Int, it["offset"] as Int + it["length"] as Int) == "ktory" }
        assertThat(match["replacements"] as List<*>).contains("który")
    }

    @Test
    fun `a language the interface is not read in is refused, and the editor is told how to ask`() {
        graphQlTester.document("""{ grammarCheck(language: "de", text: "Hallo") { offset } }""").execute().errors()
            .expect { it.message == "A document's language is en or pl, not de" }.verify()
        graphQlTester.document("{ grammarSettings { pauseMillis textMost languages server } }").execute()
            .path("grammarSettings.pauseMillis").entity(Int::class.java).isEqualTo(800)
            .path("grammarSettings.languages").entityList(String::class.java).containsExactly("en", "pl")
            .path("grammarSettings.server").valueIsNull()
    }

    /** Pointed at a LanguageTool server, the text is sent to its /v2/check and the answer read back in the same shape. */
    @Test
    fun `pointed at a LanguageTool server, the text goes there`() {
        val asked = mutableListOf<String>()
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/v2/check") { exchange ->
            asked += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = """{"software":{"name":"LanguageTool","version":"6.8","buildDate":"","apiVersion":1,"premium":false,"status":""},
                "language":{"name":"English (US)","code":"en-US","detectedLanguage":{"name":"English (US)","code":"en-US"}},
                "matches":[{"message":"Possible spelling mistake found.","shortMessage":"Spelling mistake",
                  "replacements":[{"value":"teh-fixed"}],"offset":4,"length":3,"context":{"text":"Fix teh","offset":4,"length":3},
                  "sentence":"Fix teh","rule":{"id":"STUB_RULE","description":"Stub","issueType":"misspelling",
                  "category":{"id":"TYPOS","name":"Typos"}}}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        started.start()
        server = started
        val url = "http://${started.address.hostString}:${started.address.port}"
        graphQlTester.document("""mutation { setGrammarServer(url: "$url") { server } }""").execute()
            .path("setGrammarServer.server").entity(String::class.java).isEqualTo(url)

        val found = check("en", "Fix teh")
        assertThat(found.single()["replacements"] as List<*>).containsExactly("teh-fixed")
        assertThat(found.single()["offset"]).isEqualTo(4)
        assertThat(asked.single()).contains("language=en-US")
        graphQlTester.document("""mutation { setGrammarServer(url: "ftp://nowhere") { server } }""").execute().errors()
            .expect { it.message?.contains("not an http or https address") == true }.verify()
    }

    /**
     * The server is reached under the networking rules, as every outbound call is:
     * LanguageTool's own client opened its connection itself and went past them.
     */
    @Test
    fun `a LanguageTool server is reached through the proxy a rule names`() {
        val asked = mutableListOf<String>()
        val proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxy.createContext("/") { exchange ->
            asked += exchange.requestURI.toString()
            val body = """{"matches":[{"message":"Possible spelling mistake found.","replacements":[{"value":"the"}],
                "offset":4,"length":3,"rule":{"id":"STUB","issueType":"misspelling"}}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        proxy.start()
        server = proxy
        val rule = proxyRules.save(
            io.mszymanski.orknux.connector.proxy.ProxyRule(
                name = "grammar", pattern = """languagetool-behind-proxy\.invalid""", proxyHost = "127.0.0.1", proxyPort = proxy.address.port, position = 0,
            ),
        )
        router.reload()
        try {
            graphQlTester.document("""mutation { setGrammarServer(url: "http://languagetool-behind-proxy.invalid:8010") { server } }""")
                .execute().errors().verify()
            val found = check("en", "Fix teh")
            assertThat(found.single()["replacements"] as List<*>).containsExactly("the")
            // The host does not resolve, so only the proxy could have answered - and it was asked for the check.
            assertThat(asked.single()).isEqualTo("http://languagetool-behind-proxy.invalid:8010/v2/check")
        } finally {
            proxyRules.delete(rule)
            router.reload()
        }
    }
}
