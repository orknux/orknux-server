package io.mszymanski.orknux.server.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

/**
 * Nothing outbound is built anywhere but the seam.
 *
 * **Why a test that reads source code.** `ProxyRouter` documents an invariant -
 * that every outbound call is built from it - and for a long time that sentence
 * was simply untrue. Slack brought its own HTTP and websocket stacks and went
 * round the rules entirely; Spring Security built four more clients of its own
 * for OIDC and did the same; the mail session had no proxy settings at all. Each
 * was found by a person reading call sites one at a time, months after it was
 * written, and each had passed every test in this repository.
 *
 * The individual fixes are elsewhere. This is the part that keeps them fixed: a
 * proxy rule that covers some calls and not others is worse than no rule, and
 * the failure it produces - an endpoint that cannot be reached, on an
 * installation whose rules are correct - says nothing about its own cause. So a
 * new client built outside the seam should fail here, in the minute it is
 * written, rather than in somebody's network six months later.
 *
 * **Why an allow-list rather than a ban.** Three places genuinely must construct
 * a client, because the library they are handing it to will not accept ours.
 * They are listed with the reason, and the list is short on purpose: adding to
 * it should feel like a decision, which is the whole mechanism.
 */
class OutboundClientSeamTest {

    /**
     * What building something that talks to the network looks like, in every
     * spelling this repository has reason to encounter.
     */
    private val construction = listOf(
        "HttpClient.newBuilder", "HttpClient.newHttpClient",
        "RestTemplate(", "RestClient.builder", "RestClient.create",
        "WebClient.builder", "WebClient.create",
        "OkHttpClient(", "OkHttpClient.Builder",
        ".openConnection(",
    ).map(String::trim)

    /**
     * The three that must build their own, and why each is allowed to.
     *
     * Each still ends at the same compiled rules: what differs is only how the
     * answer is delivered to a library that will not take an `HttpClient`.
     */
    private val allowed = mapOf(
        "modules/connection/src/main/kotlin/io/mszymanski/orknux/connector/proxy/ProxyRouter.kt"
            to "the seam itself - this is the one place a client is built",
        "modules/connection/src/main/kotlin/io/mszymanski/orknux/connector/connection/SlackClients.kt"
            to "Slack's SDK brings its own OkHttp and Tyrus stacks; both are given proxySelector()",
        "app/src/main/kotlin/io/mszymanski/orknux/server/security/OidcTransport.kt"
            to "Spring Security's own clients, each built on a request factory that wraps the routed client",
        "modules/connection/src/main/kotlin/io/mszymanski/orknux/connector/model/DecisionModelClient.kt"
            to "a RestClient on a JdkClientHttpRequestFactory over ProxyRouter.builder()'s own HttpClient",
    )

    @Test
    fun `every outbound client is built from the router`() {
        val offenders = mainSources()
            .filter { it.invariantSeparatorsPathString.removePrefix(root().invariantSeparatorsPathString + "/") !in allowed }
            .flatMap { file ->
                val relative = file.invariantSeparatorsPathString
                    .removePrefix(root().invariantSeparatorsPathString + "/")
                code(file.readText())
                    .filter { (_, line) -> construction.any { it in line } }
                    .map { (number, line) -> "$relative:$number  ${line.trim()}" }
            }

        assertThat(offenders)
            .describedAs(
                "These build a client that the proxy rules do not reach. Build it from ProxyRouter.builder() " +
                    "instead, or - if the library will not take one - hand it proxySelector() and add it to " +
                    "the allow-list in this test with the reason.",
            )
            .isEmpty()
    }

    @Test
    fun `the allow-list names files that exist`() {
        // A stale entry is an exemption nobody can see the end of: the file it
        // pardoned is gone, and the name it pardons could be taken by something
        // new that should never have been let through.
        assertThat(allowed.keys.filterNot { Files.exists(root().resolve(it)) }).isEmpty()
    }

    /**
     * The lines that are code, numbered from one.
     *
     * Comments are dropped because this repository explains itself at length,
     * and several of those explanations name the very clients being banned -
     * including the ones describing why they were banned.
     */
    private fun code(source: String): List<Pair<Int, String>> {
        var inBlock = false
        return source.lines().mapIndexed { index, line -> index + 1 to line }.filter { (_, line) ->
            val trimmed = line.trim()
            when {
                inBlock -> {
                    if (trimmed.contains("*/")) inBlock = false
                    false
                }

                trimmed.startsWith("//") -> false
                trimmed.startsWith("/*") -> {
                    if (!trimmed.contains("*/")) inBlock = true
                    false
                }

                trimmed.startsWith("*") -> false
                else -> true
            }
        }
    }

    private fun mainSources(): List<Path> = listOf(root().resolve("modules"), root().resolve("app/src/main"))
        .filter(Files::exists)
        .flatMap { start -> Files.walk(start).use { paths -> paths.toList() } }
        .filter { Files.isRegularFile(it) }
        .filter { it.extension == "kt" || it.extension == "java" }
        .filter { "/src/main/" in it.invariantSeparatorsPathString }
        .filterNot { "/target/" in it.invariantSeparatorsPathString }

    /** The repository, found by walking up rather than by counting `..`. */
    private fun root(): Path {
        var here: Path? = Path.of("").toAbsolutePath()
        while (here != null) {
            if (Files.isDirectory(here.resolve("modules")) && Files.isDirectory(here.resolve("app"))) return here
            here = here.parent
        }
        error("Could not find the repository root from ${Path.of("").toAbsolutePath()}")
    }
}
