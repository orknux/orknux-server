package io.mszymanski.orknux.server.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * One switch per update source, under ORKNUX_SELF_UPDATE. Issue #589.
 *
 * Each subclass turns one source off. That source is refused at its door in a
 * code the interface translates, a release it brought in earlier can no longer
 * be started, and the other two carry on as if nothing were switched - which
 * is the whole point of three switches rather than one.
 */
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
abstract class ServerReleaseSourceSwitchTest(private val off: ServerReleaseSource) {

    @Autowired lateinit var graphQlTester: ExecutionGraphQlServiceTester
    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var updates: ServerReleases
    @Autowired lateinit var releases: ServerReleaseRepository
    @Autowired lateinit var dataSource: DataSource

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun reset() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        val jdbc = JdbcTemplate(dataSource)
        jdbc.update("DELETE FROM server_release_part")
        jdbc.update("DELETE FROM server_release")
    }

    /** Each source, asked to bring in a jar of [version]; true where it was stored. */
    private fun bring(source: ServerReleaseSource, version: String): Any? {
        served = TestReleaseJars.signed(Shape(version = version))
        return when (source) {
            ServerReleaseSource.ORKNUX_AI ->
                graphQlTester.document("""mutation { installServerRelease(version: "$version") { restarting } }""").execute()
            ServerReleaseSource.UPLOAD -> {
                // MockMvc clears the signed-in user on its way out; the GraphQL calls after it need alice back.
                val signedIn = org.springframework.security.core.context.SecurityContextHolder.getContext()
                mockMvc.perform(
                    post("/api/server-releases").contentType(MediaType.APPLICATION_OCTET_STREAM).content(Files.readAllBytes(served)),
                ).also { org.springframework.security.core.context.SecurityContextHolder.setContext(signedIn) }
            }
            ServerReleaseSource.URL ->
                graphQlTester.document("""mutation { installServerReleaseFromUrl(url: "http://${where()}/jar/$version.jar") { id } }""")
                    .execute()
        }
    }

    @Test
    fun `the source turned off is refused, and the other two still bring releases in`() {
        val code = "ServerReleaseSourceDisabled"
        when (val refused = bring(off, "9.6.1")) {
            is org.springframework.test.web.servlet.ResultActions ->
                refused.andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value(code))
            is org.springframework.graphql.test.tester.GraphQlTester.Response ->
                refused.errors().satisfy { errors ->
                    assertThat(errors.single().extensions["code"]).isEqualTo(code)
                    @Suppress("UNCHECKED_CAST")
                    val arguments = errors.single().extensions["arguments"] as Map<String, Any?>
                    assertThat(arguments["variable"]).isEqualTo(ServerReleaseSourceDisabledException.variableOf(off))
                }
        }
        assertThat(releases.findAll()).isEmpty()

        val others = ServerReleaseSource.entries.filter { it != off }
        others.forEachIndexed { index, source ->
            when (val brought = bring(source, "9.6.${index + 2}")) {
                is org.springframework.test.web.servlet.ResultActions -> brought.andExpect(status().isCreated)
                is org.springframework.graphql.test.tester.GraphQlTester.Response ->
                    brought.errors().satisfy { assertThat(it).withFailMessage { "$source: $it" }.isEmpty() }
            }
        }
        assertThat(releases.findAll().map { it.source }).containsExactlyInAnyOrderElementsOf(others)
    }

    @Test
    fun `a release the switched-off source brought in earlier cannot be started, and the page says which are on`() {
        val other = ServerReleaseSource.entries.first { it != off }
        val release = updates.store(TestReleaseJars.signed(Shape(version = "9.6.5")), other, "alice")
        // As if it had arrived while the source was still on.
        JdbcTemplate(dataSource).update("UPDATE server_release SET source = ? WHERE id = ?", off.name, release.id)

        graphQlTester.document("{ serverUpdates { officialEnabled uploadEnabled urlEnabled stored { activatable refusal } } }")
            .execute()
            .path("serverUpdates.officialEnabled").entity(Boolean::class.java).isEqualTo(off != ServerReleaseSource.ORKNUX_AI)
            .path("serverUpdates.uploadEnabled").entity(Boolean::class.java).isEqualTo(off != ServerReleaseSource.UPLOAD)
            .path("serverUpdates.urlEnabled").entity(Boolean::class.java).isEqualTo(off != ServerReleaseSource.URL)
            .path("serverUpdates.stored[0].activatable").entity(Boolean::class.java).isEqualTo(false)
            .path("serverUpdates.stored[0].refusal").entity(String::class.java)
            .isEqualTo("Its source is turned off on this installation (${ServerReleaseSourceDisabledException.variableOf(off)} is false).")
        graphQlTester.document("mutation { activateServerRelease(id: ${release.id}) { restarting } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleaseNotActivatable") }
    }

    companion object {
        @Volatile
        lateinit var served: Path

        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                fun answer(exchange: HttpExchange, body: ByteArray) {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                createContext("/graphql") { exchange ->
                    exchange.requestBody.readBytes()
                    val version = ReleaseJarVerifier.versionOf(served)
                    answer(
                        exchange,
                        """
                        {"data":{"serverReleases":[{"version":"$version","publishedAt":"2026-10-02","changelog":"",
                          "jarUrl":"http://${where()}/jar/$version.jar","sha256":"${ReleaseJarVerifier.sha256(served)}",
                          "size":${Files.size(served)}}]}}
                        """.trimIndent().toByteArray(),
                    )
                }
                createContext("/jar/") { exchange -> answer(exchange, Files.readAllBytes(served)) }
                start()
            }

        fun where() = "${stub.address.hostString}:${stub.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("orknux.marketplace.url") { "http://${where()}/graphql" }
            registry.add("orknux.update.certificate") { TestReleaseJars.trusted.pem.toUri().toString() }
        }
    }
}

@SpringBootTest(properties = ["orknux.update.official=false"])
class OfficialSourceOffTest : ServerReleaseSourceSwitchTest(ServerReleaseSource.ORKNUX_AI)

@SpringBootTest(properties = ["orknux.update.upload=false"])
class UploadSourceOffTest : ServerReleaseSourceSwitchTest(ServerReleaseSource.UPLOAD)

@SpringBootTest(properties = ["orknux.update.url=false"])
class UrlSourceOffTest : ServerReleaseSourceSwitchTest(ServerReleaseSource.URL)
