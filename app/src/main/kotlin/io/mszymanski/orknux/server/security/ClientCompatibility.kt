package io.mszymanski.orknux.server.security

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Whether a client of some version may use this server: the one rule every kind
 * of client asks about - the desktop app now, a terminal or a web client later.
 *
 * Within a major version a server works with every client of that major, older or
 * newer, so nobody has to upgrade both together. That is a promise about what a
 * server may change in a minor or a patch release: AGENTS.md says what it costs.
 * Across majors they refuse each other in words, before anybody signs in, rather
 * than half working.
 *
 * Asked of the server rather than worked out by the client, so a client already
 * in somebody's hands follows the rule the server it reached holds.
 */
object ClientCompatibility {

    /** The major of `1.2.9`, `1.2.9-SNAPSHOT` or `v1`; null where it does not start with a number. */
    fun majorOf(version: String?): Int? =
        version?.trim()?.removePrefix("v")?.substringBefore('.')?.substringBefore('-')?.toIntOrNull()

    /** Null where they work together, or what to tell the person where they do not. */
    fun refusal(server: String, client: String?): String? {
        // A server built outside a release has no number to hold anybody to.
        val ours = majorOf(server) ?: return null
        // Asked with no version, the answer is the server's own; a version nobody can read is not a match.
        if (client.isNullOrBlank()) return null
        val theirs = majorOf(client)
            ?: return "This server cannot read the client version \"${client.take(40)}\"."
        return when {
            theirs == ours -> null
            theirs < ours -> "This server runs Orknux $server, which needs a client of version $ours. This client is $client: update it."
            else -> "This server runs Orknux $server, and this client is $client, which needs a server of version $theirs. Update the server, or use a client of version $ours."
        }
    }
}

/** Open, as the sign-in screen's own question is: it is asked before anybody has signed in, and says only the version that screen prints. */
@RestController
class ClientCompatibilityAPI(@Value("\${orknux.version:unknown}") private val version: String) {

    @GetMapping(CLIENT_COMPATIBILITY_PATH)
    fun compatibility(@RequestParam(required = false) client: String?): CompatibilityAnswer {
        val refusal = ClientCompatibility.refusal(version, client)
        return CompatibilityAnswer(
            product = "orknux",
            server = version,
            serverMajor = ClientCompatibility.majorOf(version),
            compatible = refusal == null,
            reason = refusal,
        )
    }
}

data class CompatibilityAnswer(
    @JsonProperty("product") val product: String,
    @JsonProperty("server") val server: String,
    @JsonProperty("serverMajor") val serverMajor: Int?,
    @JsonProperty("compatible") val compatible: Boolean,
    @JsonProperty("reason") val reason: String?,
)
