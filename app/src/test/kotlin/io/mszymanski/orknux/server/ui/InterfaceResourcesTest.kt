package io.mszymanski.orknux.server.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

/**
 * The interface served by the server, nobody signed in. Issue #585.
 *
 * A stand-in bundle sits at `static/` on the test classpath, where `-Pwith-ui`
 * puts the real one. What these pin is the part nginx used to do: the files, a
 * route of the page answered with the page, the caching - and that none of it
 * leaks into the paths the server answers itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterfaceResourcesTest(@LocalServerPort private val port: Int) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private fun get(path: String): ResponseEntity<String> =
        client.get().uri(path).retrieve().toEntity(String::class.java)

    @Test
    fun `the page loads before anybody has signed in, and is never cached`() {
        val page = get("/")

        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(page.body).contains("Orknux test bundle")
        assertThat(page.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-cache")
    }

    @Test
    fun `a route the page draws is answered with the page`() {
        val route = get("/workspace/9/tasks/28")

        assertThat(route.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(route.body).contains("Orknux test bundle")
    }

    @Test
    fun `a file of the bundle is served as itself, and a hashed asset is kept for a year`() {
        val asset = get("/assets/app-3f2a.js")
        val icon = get("/favicon.svg")

        assertThat(asset.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(asset.body).contains("bundle")
        assertThat(asset.headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("max-age=31536000").contains("immutable")
        assertThat(icon.body).isEqualTo("icon\n")
    }

    @Test
    fun `a missing asset is a 404, not the page`() {
        assertThat(get("/assets/gone-0000.js").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the server's own paths are still the server's, and still ask who is calling`() {
        val api = get("/api/workspaces/nothing-here")
        val graphql = get("/graphql")
        val actuator = get("/actuator/env")

        listOf(api, graphql, actuator).forEach {
            assertThat(it.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(it.body.orEmpty()).doesNotContain("Orknux test bundle")
        }
    }

    @Test
    fun `only a GET is answered with the page`() {
        val posted = client.post().uri("/workspace/9").retrieve().toEntity(String::class.java)

        assertThat(posted.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(posted.body.orEmpty()).doesNotContain("Orknux test bundle")
    }
}
