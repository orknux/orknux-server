package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient

/**
 * Who may use whom: every client of a server's major and no other, asked with
 * nobody signed in, because the question comes before the sign-in screen.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.version=3.4.5"],
)
class ClientCompatibilityTest(@LocalServerPort val port: Int) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @Test
    fun `an older or a newer client of the same major is welcome`() {
        for (version in listOf("3.0.0", "3.4.5", "3.99.1", "3.4.5-SNAPSHOT")) {
            assertThat(ClientCompatibility.refusal("3.4.5", version)).isNull()
        }
    }

    @Test
    fun `another major is refused in words that say which side to update`() {
        assertThat(ClientCompatibility.refusal("3.4.5", "2.9.0")).contains("update it", "2.9.0")
        assertThat(ClientCompatibility.refusal("3.4.5", "4.0.0")).contains("Update the server", "4.0.0")
    }

    @Test
    fun `a version nobody can read is refused, and a server with no number holds nobody to one`() {
        assertThat(ClientCompatibility.refusal("3.4.5", "soon")).contains("cannot read")
        assertThat(ClientCompatibility.refusal("unknown", "9.9.9")).isNull()
    }

    @Test
    fun `it is answered with nobody signed in`() {
        val welcome = client.get().uri("$CLIENT_COMPATIBILITY_PATH?client=3.1.0").retrieve().toEntity(Map::class.java)
        assertThat(welcome.statusCode.value()).isEqualTo(200)
        val said = welcome.body!!
        assertThat(said["product"]).isEqualTo("orknux")
        assertThat(said["server"]).isEqualTo("3.4.5")
        assertThat(said["serverMajor"]).isEqualTo(3)
        assertThat(said["compatible"]).isEqualTo(true)

        val refused = client.get().uri("$CLIENT_COMPATIBILITY_PATH?client=2.0.0").retrieve().body(Map::class.java)!!
        assertThat(refused["compatible"]).isEqualTo(false)
        assertThat(refused["reason"] as String).contains("update it")
    }
}
