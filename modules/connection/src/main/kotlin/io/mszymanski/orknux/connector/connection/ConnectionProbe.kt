package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.security.SecretCipher
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

@ConfigurationProperties(prefix = "orknux.connection")
data class ConnectionProperties(
    /** How long a probe may take before it counts as a failure. */
    val probeTimeoutSeconds: Long = 5,
    /**
     * Link-local addresses reach cloud instance metadata, so a connection URL
     * resolving to one is refused rather than fetched. Private and loopback
     * addresses stay allowed: internal services are the point of the feature.
     */
    val allowLinkLocal: Boolean = false,


    /**
     * How long a workflow's own HTTP request may take.
     *
     * Longer than a probe's, which only asks whether anything is listening: a
     * request a workflow makes is doing work at the other end, and five seconds is
     * a short time to build a report in. Bounded all the same, because a step that
     * never returns holds the run that made it.
     */
    val requestTimeoutSeconds: Long = 30,

    /**
     * Where a service principal's token comes from.
     *
     * Configurable because the global cloud is not the only one — the US
     * government and China clouds each have their own authority — and because a
     * test cannot point at Microsoft.
     */
    val entraAuthority: String = "https://login.microsoftonline.com",

    /**
     * How long sending one mail may take.
     *
     * Its own setting rather than the HTTP one, because the conversation is a
     * different shape: an SMTP exchange is several round trips before the body
     * is written, and a greylisting server can be slow on purpose. Shorter than
     * a workflow's HTTP call all the same - a mail server that has not answered
     * in twenty seconds is one to come back to.
     */
    val mailTimeoutSeconds: Long = 20,
)

/** What the last probe found. */
enum class CheckOutcome {
    CONNECTED,
    FAILED,
}

data class CheckResult(val outcome: CheckOutcome, val message: String)

/**
 * Everything needed to talk to the other end, resolved in one place so the
 * stored credentials are read here and nowhere else. This is why the connector
 * exists: orknux-workflow asks for a target rather than for a secret, and the
 * providers built on top of it will make their calls through one.
 */
data class ConnectionTarget(
    val url: String,
    val authType: AuthType,
    val secret: String?,
    val headers: List<HttpHeader>,
) {

    /**
     * The Basic credential for [secret]: `username:password` encoded, as it is
     * meant to be typed. One already encoded - somebody who knew the header and
     * wrapped it themselves - is sent as it is rather than encoded twice, which
     * the server would read as a user named after the base64.
     */
    private fun basicCredential(secret: String): String {
        val typed = secret.trim()
        if (':' !in typed) {
            val decoded = runCatching { String(Base64.getDecoder().decode(typed), Charsets.UTF_8) }.getOrNull()
            if (decoded != null && ':' in decoded) return typed
        }
        return Base64.getEncoder().encodeToString(typed.toByteArray())
    }

    /** The headers to send, credentials included. */
    fun requestHeaders(): List<Pair<String, String>> {
        val credential = when {
            secret.isNullOrBlank() -> null
            authType == AuthType.BEARER_TOKEN -> "Authorization" to "Bearer $secret"
            authType == AuthType.API_KEY -> "Authorization" to secret
            authType == AuthType.BASIC -> "Authorization" to "Basic " + basicCredential(secret)
            else -> null
        }
        return headers.map { it.name to it.value } + listOfNotNull(credential)
    }
}

/**
 * Checks that a connection actually answers, so the workspace screen reports what was
 * observed rather than merely that credentials were typed in.
 */
@Service
class ConnectionProbe(
    private val properties: ConnectionProperties,
    private val proxies: ProxyRouter,
    /** Only to recognise a credential that never came out of its envelope. */
    private val cipher: SecretCipher,
) {

    // Built by the router, so a probe obeys the same proxy rules a real call
    // does. A check that reached an endpoint by a route the calls cannot take
    // would report a connection nothing else can use.
    private val client: HttpClient = proxies.builder()
        // Cleartext HTTP/2 negotiation hangs against servers that ignore the
        // upgrade, and a probe has no reason to care which version answers.
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
        // A redirect can leave the host the caller configured, taking the
        // credentials with it, so the first response is the answer.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Whether a URL is one this service is willing to call at all, and why not
     * when it is not.
     *
     * Public because more than one kind of check needs the same answer, and
     * what is safe to call should be decided in one place — a second copy of
     * the link-local rule is a second copy to forget.
     */
    fun vet(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "The URL is not valid"
        }
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return "Only http and https URLs can be checked"
        val host = uri.host ?: return "The URL has no host"
        // Asked of the same rules the call itself will be routed by, so the
        // question below is "who resolves this name" and not a guess.
        return vetHost(host, viaProxy = proxies.resolve(url) != null)
    }

    /**
     * The same question for something that is a host and not a URL - a mail
     * server, which is configured by name and port rather than by address.
     *
     * Public so that sending mail asks this rather than carrying its own copy of
     * the link-local rule; a second copy is a second one to forget.
     *
     * [viaProxy] says whether a proxy rule carries this host, and it defaults to
     * false because the callers that pass nothing are asking about a host this
     * process dials itself: the proxy named by a rule, and an SSH server, which
     * no HTTP proxy carries. Those should still be resolved here.
     */
    fun vetHost(host: String, viaProxy: Boolean = false): String? =
        resolutionProblem(host.trim().ifEmpty { return "The host is empty" }, viaProxy)

    /**
     * Checks a connection, asking whatever question that kind of service can
     * actually answer.
     *
     * Slack is the reason this takes a type. Its Web API base is not an
     * endpoint — a HEAD on `https://slack.com/api` is permanently redirected to
     * the documentation site, which says nothing about the token and reads as a
     * problem when there is none. `auth.test` is the question worth asking: it
     * is what the token is for, and the answer says whether it works.
     */
    fun check(target: ConnectionTarget, type: ConnectionType?): CheckResult =
        if (type == ConnectionType.SLACK) {
            unreadable(target) ?: checkSlack(target)
        } else {
            check(target)
        }

    /**
     * The failure to report when the stored credential never came out of its
     * envelope, and null when there is no such problem.
     *
     * Sending it as it stands would put the envelope in the header and come back
     * a 401, which reads as a wrong credential rather than an unreadable one -
     * and those two want opposite things done about them. One is retyped; the
     * other is the secret key this installation is running with. The doctor page
     * says this plainly and there is no reason for this screen to guess when it
     * can ask the same question.
     */
    private fun unreadable(target: ConnectionTarget): CheckResult? =
        if (cipher.isEncrypted(target.secret)) {
            CheckResult(
                CheckOutcome.FAILED,
                "The stored credential cannot be read with the current secret key. " +
                    "Enter it again, or restore the key it was saved with.",
            )
        } else {
            null
        }

    /**
     * `auth.test` with the bot token: 200 and `"ok":true` means the credential
     * works and Slack knows the team. `"ok":false` carries Slack's own error —
     * `invalid_auth`, `token_revoked` — which is the useful thing to show.
     *
     * A webhook connection has no token to test with, so it falls back to
     * asking whether the endpoint is there at all.
     */
    private fun checkSlack(target: ConnectionTarget): CheckResult {
        val token = target.secret?.trim()?.ifEmpty { null } ?: return check(target)
        val url = "${target.url.trimEnd('/')}/auth.test"
        vet(url)?.let { return CheckResult(CheckOutcome.FAILED, it) }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return CheckResult(CheckOutcome.FAILED, "Slack answered ${response.statusCode()}")
            }
            val body = response.body()
            when {
                OK_TRUE.containsMatchIn(body) -> {
                    val team = TEAM.find(body)?.groupValues?.get(1)
                    CheckResult(CheckOutcome.CONNECTED, if (team == null) "Connected" else "Connected to $team")
                }
                else -> {
                    val error = SLACK_ERROR.find(body)?.groupValues?.get(1) ?: "it refused the token"
                    CheckResult(CheckOutcome.FAILED, "Slack rejected the token: $error")
                }
            }
        } catch (failure: Exception) {
            CheckResult(CheckOutcome.FAILED, failure.message ?: "Slack could not be reached")
        }
    }

    fun check(target: ConnectionTarget): CheckResult {
        unreadable(target)?.let { return it }
        vet(target.url)?.let { return CheckResult(CheckOutcome.FAILED, it) }
        val uri = URI(target.url)

        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            // A HEAD asks whether the endpoint is there without acting on it.
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
        target.requestHeaders().forEach { (name, value) ->
            runCatching { builder.header(name, value) }
        }

        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
            // Reachable is not the same as working, and a check that says
            // "successful" about an answer nobody would call success is worse
            // than no check. What the endpoint said is read instead: nothing
            // there, or broken, is a failure however well the socket opened.
            when (val status = response.statusCode()) {
                in 200..299 -> CheckResult(CheckOutcome.CONNECTED, "Answered $status")
                // A redirect is a service answering, and this probe does not
                // follow one — a redirect can leave the host the credentials
                // were meant for. So it counts as reachable and says where it
                // was sent, which is usually the URL that should have been
                // configured. Slack answers 301 to a HEAD on its API.
                in 300..399 -> CheckResult(
                    CheckOutcome.CONNECTED,
                    response.headers().firstValue("location").map { "Reachable; it redirects to $it" }
                        .orElse("Reachable; it answered $status"),
                )
                401, 403 -> CheckResult(CheckOutcome.FAILED, "The service rejected the credentials ($status)")
                404, 410 -> CheckResult(CheckOutcome.FAILED, "Nothing is served at that URL ($status)")
                // A HEAD is not what these endpoints are for — an MCP server or
                // a webhook takes a POST and says so. Refusing the question is
                // still an answer from the right service, so it counts.
                in NOT_FOR_A_HEAD -> CheckResult(CheckOutcome.CONNECTED, "Reachable; it does not answer a HEAD ($status)")
                in 500..599 -> CheckResult(CheckOutcome.FAILED, "The service is failing ($status)")
                else -> CheckResult(CheckOutcome.FAILED, "The service answered $status")
            }
        } catch (failure: Exception) {
            CheckResult(CheckOutcome.FAILED, reasonFor(failure))
        }
    }

    /**
     * What to tell somebody about a call that never got an answer.
     *
     * Passing `failure.message` through is right for most of these - a refused
     * connection and an unknown host both say so in words anybody can act on -
     * and wrong for exactly one. A certificate the JVM cannot chain to a root it
     * knows arrives as *unable to find certification path to requested target*,
     * which is eight words naming no service, no certificate and nothing to do
     * about it, and it is the ordinary case for anything run inside somebody's
     * own network behind a private authority. Issue #322.
     */
    private fun reasonFor(failure: Exception): String {
        val untrusted = generateSequence(failure as Throwable?) { it.cause }
            .any { it is SSLException || it is CertificateException || it is CertPathBuilderException }
        if (untrusted) {
            return "It presented a certificate this installation does not trust. If it is signed by an " +
                "internal authority, that authority's certificate has to be given to whatever calls it."
        }
        return failure.message ?: "The service could not be reached"
    }

    /** Null when the host is fine to call. */
    private fun resolutionProblem(host: String, viaProxy: Boolean): String? =
        if (viaProxy) literalAddressProblem(host) else resolvedAddressProblem(host)

    /**
     * The same question asked of a host a proxy will dial, which is answered
     * without a resolver.
     *
     * **Why this is not the check below.** On a network that requires a proxy,
     * the proxy is usually the only thing that can resolve an external name -
     * that is most of the reason it is there. Resolving here first meant every
     * such call failed with "The host could not be resolved" before the rules
     * were ever consulted, so an installation whose proxy rules were entirely
     * correct could not make a single call, and nothing on the screen that
     * lists those rules said why. The name is the proxy's to resolve, and a
     * `CONNECT` carries it there unresolved.
     *
     * **Why anything is still checked.** The rule below exists to stop a URL
     * being pointed at cloud instance metadata, and a proxy does not make that
     * harmless - it makes it somebody else's network. What can still be
     * answered without a resolver is an address that was written as an address,
     * so that is what is answered; a name that resolves to one somewhere out
     * there is beyond this process's knowledge either way.
     */
    private fun literalAddressProblem(host: String): String? {
        // Written as an address or not written as one: ofLiteral refuses a name
        // rather than looking it up, which is the whole point of asking it here.
        val literal = runCatching { InetAddress.ofLiteral(host.removeSurrounding("[", "]")) }.getOrNull()
            ?: return null
        return if (!properties.allowLinkLocal && (literal.isLinkLocalAddress || literal.isAnyLocalAddress)) {
            "That host resolves to a link-local address"
        } else {
            null
        }
    }

    private fun resolvedAddressProblem(host: String): String? = try {
        val addresses = InetAddress.getAllByName(host)
        if (!properties.allowLinkLocal && addresses.any { it.isLinkLocalAddress || it.isAnyLocalAddress }) {
            "That host resolves to a link-local address"
        } else {
            null
        }
    } catch (_: Exception) {
        "The host could not be resolved"
    }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")

        /**
         * Answers that mean "not like that" rather than "not here": the method,
         * the body type or the negotiation was refused, which only something
         * listening at that URL can refuse.
         */
        val NOT_FOR_A_HEAD = setOf(400, 405, 406, 415, 501)

        /** Slack answers every call with `ok`, and says why when it is false. */
        val OK_TRUE = Regex(""""ok"\s*:\s*true""")
        val SLACK_ERROR = Regex(""""error"\s*:\s*"([^"]+)"""")
        val TEAM = Regex(""""team"\s*:\s*"([^"]+)"""")
    }
}
