package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.CheckOutcome
import io.mszymanski.orknux.connector.connection.CheckResult
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.connection.HttpHeader
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.security.HeldCredential
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import com.openai.credential.Credential as OpenAiCredential
import com.openai.errors.OpenAIServiceException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Checks that a provider answers, so "Connection successful" means the provider
 * said so rather than that a key was typed in.
 *
 * What is safe to call is [ConnectionProbe]'s decision, kept in one place. What
 * is asked is this one's: where each type wants its key, the token request
 * Entra ID needs before there is a credential at all, and a question whose
 * answer is worth reporting.
 */
@Service
class ModelProviderProbe(
    private val probe: ConnectionProbe,
    private val properties: ConnectionProperties,
    private val mapper: ObjectMapper,
    /** Reads the key, whether the provider keeps its own or points at a variable. */
    private val references: SecretReferences,
    private val proxies: ProxyRouter,
    /** Builds the SDK client the listing is asked through; see [ModelClients]. */
    private val clients: ModelClients,
) {

    /**
     * Service principal tokens, while they last. Concurrent because a sweep, a
     * chat and a button press can all want one at the same moment.
     */
    private val cached = ConcurrentHashMap<TokenKey, HeldToken>()

    private val client: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Asks the provider to list its models.
     *
     * Not a HEAD on whatever URL was typed in. "Something answered" is a poor
     * thing to call a connection: a model provider that returns 404 for its
     * model list is one the chat cannot use, and reporting that as success is
     * how you get told "Connection successful — Answered with 404". Listing
     * models proves three things at once — the host is reachable, the
     * credential is accepted, and the thing at the other end is a model API.
     */
    fun check(provider: ModelProvider): CheckResult = when (val listing = list(provider)) {
        is Listing.Failed -> CheckResult(CheckOutcome.FAILED, listing.reason)
        is Listing.Models -> CheckResult(CheckOutcome.CONNECTED, listed(listing.ids))
    }

    /**
     * What the provider says it can run.
     *
     * The same request the check makes, because they are the same question: the
     * check wants to know whether an answer came back, and discovery wants what
     * was in it. Counting the models and throwing the list away would mean
     * asking twice for something already in hand.
     */
    fun list(provider: ModelProvider): Listing {
        /*
         * Whether there is a credential at all is settled before anything is
         * sent, on either road.
         *
         * Both roads below resolve it for themselves, so this looks redundant
         * and is not: the shortcut further down returns "No model list at
         * {base}/models — check the endpoint" without consulting either. A
         * provider pointed at a workspace secret nobody has filled in cannot
         * reach anything, so the SDK's attempt fails as a connection failure -
         * not a refusal - and that shortcut then reports the endpoint. The
         * endpoint is the one part of the configuration that is right, and
         * sending whoever configured it there is the confusion #211 removed.
         *
         * Only the key is read here, not [credentials], because that grants an
         * Entra token and this must not put a request on the wire to answer a
         * question about what is stored. Both roads read it again; reading it
         * twice costs a repository lookup and no network.
         */
        if (!provider.configured()) {
            return Listing.Failed("There are no credentials to call this provider with")
        }
        // A decision model speaks no OpenAI shape and may keep no key at all,
        // so it is asked its own way. Issue #577.
        if (provider.type == ProviderType.SYSTEM_ONE) return listedDecisionModels(provider)
        (key(provider) as? Key.Failed)?.let { return Listing.Failed(it.reason) }

        /*
         * The SDK asks first, for every provider that speaks the OpenAI shape.
         *
         * It knows where each of Azure's two surfaces keeps its listing, which
         * is the thing this cannot work out for itself: an endpoint written
         * through to `/openai/v1` lists at `{base}/models` with no version,
         * while a bare resource host lists at `/openai/models?api-version=...`.
         * Assembled here, the first of those came out as
         * `/openai/v1/openai/models?...` and answered 404 - so a provider that
         * served every chat perfectly sat on the Models screen saying it could
         * not be reached, which is worse than saying nothing.
         *
         * A refusal is the SDK's answer and is returned. Anything else falls
         * through to the request below, because two shapes exist that the SDK
         * cannot read and this can: Anthropic, which has no OpenAI-shaped
         * listing at all, and the self-hosted servers that answer
         * `models[].name` instead of `data[].id`. Falling through costs one
         * request against a provider that was going to answer nothing useful
         * anyway.
         */
        if (provider.type != ProviderType.ANTHROPIC) {
            when (val asked = listedBySdk(provider)) {
                is Listing.Models -> {
                    if (asked.ids.isNotEmpty()) return asked
                    /*
                     * Nothing parsed, but something answered. Ask again at the
                     * same address with this file's own reader, because the
                     * body may be in the shape the SDK cannot see: llama.cpp
                     * and Ollama's own listing answer `models[].name` where the
                     * SDK is looking for `data[].id`, and an empty list is
                     * indistinguishable here from a list it could not read.
                     */
                }

                is Listing.Failed -> {
                    if (asked.refused) return asked
                    /*
                     * Not a refusal, so somewhere else is worth trying - but
                     * only somewhere else. Where the fallback would send the
                     * very same `GET {base}/models` that just failed, sending
                     * it twice asks a provider that could not answer to fail
                     * again, and reports the same sentence either way.
                     */
                    if (modelsUrl(provider) == "${provider.openAiBase()}/models") {
                        return Listing.Failed("No model list at ${provider.openAiBase()}/models — check the endpoint")
                    }
                }
            }
        }

        val credential = when (val resolved = credentials(provider)) {
            is Credential.Failed -> return Listing.Failed(resolved.reason)
            is Credential.Header -> resolved.header
        }

        val url = modelsUrl(provider)
        probe.vet(url)?.let { return Listing.Failed(it) }

        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            .header(credential.name, credential.value)
            .GET()

        if (provider.type == ProviderType.ANTHROPIC) request.header("anthropic-version", ANTHROPIC_VERSION)

        return try {
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) log.warn("{} answered {}", url, response.statusCode())
            when (val status = response.statusCode()) {
                in 200..299 -> Listing.Models(names(response.body()))
                401, 403 -> Listing.Failed(
                    "The provider rejected the credentials ($status)" + said(response.body()),
                )
                404 -> Listing.Failed("No model list at $url — check the endpoint")
                else -> Listing.Failed("The provider answered $status" + said(response.body()))
            }
        } catch (failure: Exception) {
            /*
             * The sentence, not the stack. A provider that cannot be reached is
             * an ordinary answer to an ordinary question - the box is off, the
             * address moved - and it is recorded on the provider's own row
             * where somebody will read it. Printed with sixty frames of okhttp
             * and Spring proxies it is a page of log per provider per sweep,
             * which is how a log stops being read at all. The stack is still
             * there at DEBUG for the failure that is not ordinary.
             */
            log.warn("Asking {} for its models failed: {}", url, failure.toString())
            log.debug("Asking {} for its models failed", url, failure)
            Listing.Failed(failure.message ?: "The provider could not be reached")
        }
    }

    /**
     * The listing as the SDK asks for it, or why it could not.
     *
     * Kept apart from [list] so that "the SDK had nothing to say" and "the
     * provider refused us" stay different answers: the first is worth a second
     * attempt in a shape the SDK does not read, and the second is not - a 401
     * asked twice is a 401 twice, and the second one is a credential sent
     * somewhere for no reason.
     */
    private fun listedBySdk(provider: ModelProvider): Listing {
        val base = provider.openAiBase()
        probe.vet(base)?.let { return Listing.Failed(it, refused = false) }

        val credential = when (val resolved = sdkCredential(provider)) {
            is SdkCredential.Failed -> return Listing.Failed(resolved.reason, refused = true)
            is SdkCredential.Ready -> resolved.credential
        }

        return try {
            val listed = clients.again { clients.clientFor(provider, credential).models().list() }
            Listing.Models(listed.data().map { it.id() }.distinct())
        } catch (refused: OpenAIServiceException) {
            log.warn("{} answered {} when asked for its models", base, refused.statusCode())
            when (refused.statusCode()) {
                401, 403 -> Listing.Failed(
                    "The provider rejected the credentials (${refused.statusCode()})",
                    refused = true,
                )
                // Not a refusal: a surface that lists somewhere else, which the
                // request below may still find.
                else -> Listing.Failed("The provider answered ${refused.statusCode()}", refused = false)
            }
        } catch (failure: Exception) {
            // As above, and doubly so here: this road is tried first and the
            // hand-built one after it, so an unreachable provider produced two
            // of these stacks for one press of one button.
            log.warn("Asking {} for its models through the SDK failed: {}", base, failure.toString())
            log.debug("Asking {} for its models through the SDK failed", base, failure)
            Listing.Failed(failure.message ?: "The provider could not be reached", refused = false)
        }
    }

    /**
     * What an endpoint speaking the Jev format can answer with.
     *
     * The format documents `GET /v1/models` - the names the model field takes -
     * and that is asked first. A server with no listing is not thereby a wrong
     * address: the format's one required route is `POST /v1/systemone`, and a
     * GET on it answers 405 where the route exists and 404 where it does not.
     * So no listing and a 405 is a server speaking the format whose model names
     * are typed by hand. Nothing here knows one server from another - asked for:
     * the integration is the format, not any product implementing it.
     */
    private fun listedDecisionModels(provider: ModelProvider): Listing {
        val header = when (val resolved = systemOneCredential(provider)) {
            null -> null
            is Credential.Failed -> return Listing.Failed(resolved.reason)
            is Credential.Header -> resolved.header
        }
        val base = provider.systemOneBase()

        fun ask(url: String): HttpResponse<String>? {
            probe.vet(url)?.let { throw IllegalStateException(it) }
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
                .GET()
            header?.let { request.header(it.name, it.value) }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        return try {
            val models = requireNotNull(ask("$base/v1/models"))
            when (val status = models.statusCode()) {
                in 200..299 -> Listing.Models(names(models.body()))
                401, 403 -> Listing.Failed("The provider rejected the credentials ($status)" + said(models.body()))
                404, 405 -> {
                    val route = requireNotNull(ask("$base/v1/systemone"))
                    when (route.statusCode()) {
                        // There, and asked the wrong way: a server with no listing. Names are typed.
                        405, in 200..299 -> Listing.Models(emptyList())
                        401, 403 -> Listing.Failed("The provider rejected the credentials (${route.statusCode()})" + said(route.body()))
                        else -> Listing.Failed("No decision API at $base - it has neither /v1/models nor /v1/systemone")
                    }
                }

                else -> Listing.Failed("The provider answered $status" + said(models.body()))
            }
        } catch (failure: Exception) {
            log.warn("Asking {} what it can decide with failed: {}", base, failure.toString())
            log.debug("Asking {} what it can decide with failed", base, failure)
            Listing.Failed(failure.message ?: "The provider could not be reached")
        }
    }

    /**
     * The header a `/v1/systemone` call carries, or null where the provider
     * keeps no key - which a self-hosted server may well not ask for. A key that is configured and cannot be read is still a
     * failure, in the same words as every other provider's.
     */
    fun systemOneCredential(provider: ModelProvider): Credential? {
        val held = references.read(provider.workspaceId, provider.secret, provider.secretVariableId)
        if (held == HeldCredential.Absent) return null
        return when (val resolved = key(provider)) {
            is Key.Failed -> Credential.Failed(resolved.reason)
            is Key.Held -> Credential.Header(HttpHeader("Authorization", "Bearer ${resolved.value}"))
        }
    }

    /** What asking a provider for its models produced. */
    sealed interface Listing {
        /** What it offers. Empty is an answer too: a server with nothing loaded. */
        data class Models(val ids: List<String>) : Listing

        /**
         * @param refused whether the provider itself said no - a credential it
         *   would not take. Anything else is only "not here", and is worth
         *   asking again somewhere else.
         */
        data class Failed(val reason: String, val refused: Boolean = true) : Listing
    }

    /**
     * Where a provider says what it can run.
     *
     * Azure puts the version in the query; everything else lists at `/models` of
     * its OpenAI surface, which is not always the endpoint that was typed in -
     * see [ModelProvider.openAiBase].
     */
    private fun modelsUrl(provider: ModelProvider): String {
        return if (provider.type == ProviderType.AZURE_OPENAI) {
            val base = provider.endpoint.trimEnd('/')
            val version = provider.apiVersion?.ifBlank { null } ?: DEFAULT_AZURE_VERSION
            "$base/openai/models?api-version=$version"
        } else {
            "${provider.openAiBase()}/models"
        }
    }

    /**
     * How many models it offered. Saying the number is worth more than saying
     * "connected": it is the difference between a provider that works and one
     * that works and has nothing on it.
     */
    private fun listed(ids: List<String>): String = when (ids.size) {
        0 -> "Connected; it listed no models"
        1 -> "Connected; 1 model listed"
        else -> "Connected; ${ids.size} models listed"
    }

    /**
     * The model names out of a listing, whichever shape the provider answers in.
     *
     * `data[].id` is the OpenAI shape, which Anthropic, Azure OpenAI and most
     * self-hosted servers also speak. Ollama's own listing is `models[].name`,
     * and llama.cpp answers with both — so `data` is read first and `models`
     * only when there was none, rather than merging two spellings of one list.
     */
    private fun names(body: String): List<String> {
        val tree = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return emptyList()
        }

        val openAi = tree.path("data").mapNotNull { it.path("id").stringValue() }
        if (openAi.isNotEmpty()) return openAi.distinct()

        return tree.path("models")
            .mapNotNull { entry -> entry.path("name").stringValue() ?: entry.path("model").stringValue() }
            .distinct()
    }

    /**
     * What the other end said about the refusal, ready to append, or nothing.
     *
     * A status on its own says the credential was refused but not which
     * credential or why, and every one of these services is willing to say:
     * Entra answers a bad client secret with `AADSTS7000215: Invalid client
     * secret provided`, which is the difference between retyping a secret and
     * hunting a tenant. Three spellings because three vendors chose three -
     * `error_description` is Entra's, `error.message` is OpenAI's and Azure
     * OpenAI's, `message` is what most self-hosted servers answer with - and a
     * body that is none of them, or is not JSON at all, adds nothing rather than
     * putting a page of HTML on the screen.
     *
     * Trimmed to a sentence's worth. Entra's description carries a correlation
     * id, a timestamp and a URL after the part worth reading.
     */
    private fun said(body: String): String {
        val tree = try {
            mapper.readTree(body)
        } catch (_: Exception) {
            return ""
        }

        val error = tree.path("error")
        // stringValueOpt rather than stringValue: the latter throws on a node
        // that is not a string, and every one of these is a guess about a body
        // this code did not write.
        val message = listOf(tree.path("error_description"), error.path("message"), error, tree.path("message"))
            .firstNotNullOfOrNull { it.stringValueOpt().orElse(null) }
            ?: return ""

        val said = message.trim().lineSequence().first().trim().take(MESSAGE_LIMIT)
        return if (said.isEmpty()) "" else ": $said"
    }

    /**
     * The header that authenticates a call to this provider.
     *
     * Both the check and an actual call need it, and it is resolved here so the
     * stored credential is read in one place — including the Entra ID case,
     * where there is no credential to send until a token has been fetched with
     * one, and the case where the provider keeps no credential at all and reads
     * a workspace secret instead. One place, so that adding the second kind of
     * credential did not add a second place to get it wrong.
     */
    fun credentials(provider: ModelProvider): Credential {
        if (!provider.configured()) {
            return Credential.Failed("There are no credentials to call this provider with")
        }

        val key = when (val resolved = key(provider)) {
            is Key.Failed -> return Credential.Failed(resolved.reason)
            is Key.Held -> resolved.value
        }

        return when (provider.authMethod) {
            ProviderAuthMethod.API_KEY -> Credential.Header(keyHeader(provider, key))
            ProviderAuthMethod.ENTRA_ID -> when (val token = entraToken(provider, key)) {
                is EntraToken.Failed -> Credential.Failed(token.reason)
                is EntraToken.Issued -> Credential.Header(HttpHeader("Authorization", "Bearer ${token.value}"))
            }
        }
    }

    /**
     * The credential as the SDK takes it, or why there is not one.
     *
     * The Entra grant is made here and now rather than left to the supplier,
     * because a refusal is worth a sentence on the provider's card - "the client
     * secret is invalid" is a different afternoon from "the tenant is wrong" -
     * and a supplier can only throw, from inside a call somebody else is making.
     * What the supplier is for is the next hour: the client is cached and the
     * token is not, so it re-reads on every call and picks up a rotated secret
     * or an expired token without the client being rebuilt.
     */
    fun sdkCredential(provider: ModelProvider): SdkCredential {
        if (!provider.configured()) {
            return SdkCredential.Failed("There are no credentials to call this provider with")
        }

        val key = when (val resolved = key(provider)) {
            is Key.Failed -> return SdkCredential.Failed(resolved.reason)
            is Key.Held -> resolved.value
        }

        return when (provider.authMethod) {
            ProviderAuthMethod.API_KEY -> SdkCredential.Ready(
                if (provider.type == ProviderType.AZURE_OPENAI) {
                    ModelClients.azureKey(key)
                } else {
                    ModelClients.apiKey(key)
                },
            )

            ProviderAuthMethod.ENTRA_ID -> when (val token = entraToken(provider, key)) {
                is EntraToken.Failed -> SdkCredential.Failed(token.reason)
                is EntraToken.Issued -> SdkCredential.Ready(
                    ModelClients.bearer(Supplier { fresh(provider) }),
                )
            }
        }
    }

    /**
     * A token for right now, for the supplier the SDK holds.
     *
     * Reads the credential again rather than closing over the one the client was
     * built with, so a workspace secret given a new value is used from the next
     * call. Throws where the rest of this returns a reason: there is a request
     * in flight by the time this runs, and the SDK's own failure is the only
     * place left for it to go.
     */
    private fun fresh(provider: ModelProvider): String {
        val key = when (val resolved = key(provider)) {
            is Key.Failed -> throw IllegalStateException(resolved.reason)
            is Key.Held -> resolved.value
        }
        return when (val token = entraToken(provider, key)) {
            is EntraToken.Failed -> throw IllegalStateException(token.reason)
            is EntraToken.Issued -> token.value
        }
    }

    /** The credential the SDK is built with, or why there is none. */
    sealed interface SdkCredential {
        data class Ready(val credential: OpenAiCredential) : SdkCredential
        data class Failed(val reason: String) : SdkCredential
    }

    /**
     * The credential itself: the provider's own copy, or the workspace secret it
     * was pointed at.
     *
     * Every failure here is worded about the credential rather than about the
     * provider, because that is what is wrong. A reference that has come apart
     * used to be unimaginable and now is not, and the difference between "the
     * variable is gone", "the variable is empty" and "the endpoint is wrong" is
     * the difference between three completely different afternoons. None of them
     * says what the value is, and the reason is not logged - it is returned, and
     * becomes the sentence on the provider's card.
     */
    private fun key(provider: ModelProvider): Key {
        val read = references.read(provider.workspaceId, provider.secret, provider.secretVariableId)
        return when (read) {
            is HeldCredential.Held -> Key.Held(read.value)
            HeldCredential.Absent -> Key.Failed("There are no credentials to call this provider with")
            is HeldCredential.Missing -> Key.Failed(
                "This provider reads its credential from a workspace secret that no longer exists " +
                    "(variable ${read.variableId}). Point it at another one, or give it a key of its own.",
            )

            is HeldCredential.Empty -> Key.Failed(
                "The workspace secret \"${read.name}\" has no value yet, so there is nothing to call " +
                    "this provider with.",
            )

            is HeldCredential.Sealed -> Key.Failed(
                if (read.variable == null) {
                    "This provider's credential cannot be read with the current secret key. " +
                        "Enter it again, or restore the key it was saved with."
                } else {
                    "The workspace secret \"${read.variable}\" cannot be read with the current secret key. " +
                        "Enter it again, or restore the key it was saved with."
                },
            )
        }
    }

    /** The credential, or why there is not one to send. */
    private sealed interface Key {
        /** Not a data class: a generated `toString` puts the credential in whatever logs it. */
        class Held(val value: String) : Key
        data class Failed(val reason: String) : Key
    }

    /** What authenticating resolved to: a header to send, or why there is none. */
    sealed interface Credential {
        data class Header(val header: HttpHeader) : Credential
        data class Failed(val reason: String) : Credential
    }

    /** Each service wants the key somewhere different, and none of them are wrong. */
    private fun keyHeader(provider: ModelProvider, key: String): HttpHeader {
        return when (provider.type) {
            ProviderType.ANTHROPIC -> HttpHeader("x-api-key", key)
            ProviderType.AZURE_OPENAI -> HttpHeader("api-key", key)
            else -> HttpHeader("Authorization", "Bearer $key")
        }
    }

    /**
     * A token for the service principal, from cache when one is still good.
     *
     * Entra issues these for about an hour, and a provider authenticating this
     * way needs one on every call — every chat message, every check. Fetching
     * one each time adds a round trip to Microsoft in front of each request and
     * walks into the token endpoint's throttling for no benefit whatsoever.
     *
     * Keyed by the whole credential, so rotating the secret or pointing at a
     * different tenant, application or scope does not go on using the old
     * token. Expiry is what Entra said, less a minute: a token that expires
     * while a request is in the air is a failure nobody can explain.
     */
    private fun entraToken(provider: ModelProvider, secret: String): EntraToken {
        val key = TokenKey(
            tenantId = provider.tenantId.orEmpty(),
            clientId = provider.clientId.orEmpty(),
            secret = secret,
            scope = provider.scope?.ifBlank { null } ?: DEFAULT_SCOPE,
        )
        cached[key]?.let { held ->
            if (held.goodUntil.isAfter(Instant.now())) return EntraToken.Issued(held.value)
            cached.remove(key, held)
        }

        return when (val fetched = fetchEntraToken(provider, secret)) {
            is EntraToken.Failed -> fetched
            is EntraToken.Issued -> {
                cached[key] = HeldToken(fetched.value, Instant.now().plusSeconds(fetched.seconds - EXPIRY_MARGIN))
                fetched
            }
        }
    }

    /**
     * What a token is for: change any of it and the old one is the wrong one.
     *
     * The resolved secret rather than the column, so a provider reading a
     * workspace variable stops using the old token the moment that variable is
     * given a new value - which is the whole point of putting the credential
     * somewhere it can be rotated from.
     */
    private data class TokenKey(
        val tenantId: String,
        val clientId: String,
        val secret: String,
        val scope: String,
    ) {
        /** A credential must not reach a log, and a data class would put it in one. */
        override fun toString(): String = "TokenKey($tenantId/$clientId/$scope)"
    }

    private data class HeldToken(val value: String, val goodUntil: Instant)

    /** The client credentials grant itself. */
    private fun fetchEntraToken(provider: ModelProvider, secret: String): EntraToken {
        val address = "${properties.entraAuthority.trimEnd('/')}/${provider.tenantId}/oauth2/v2.0/token"

        /*
         * Asked where it is going, like every other outbound call here.
         *
         * The authority is an installation's setting rather than something a
         * workspace member types, so whoever can point this somewhere is an
         * administrator already - which made it the easiest one to leave out and
         * the last one that was. It is still a POST carrying the application's
         * client secret, and an address nobody vetted is an address that can be
         * a link-local one, which is where a cloud instance hands out its own
         * credentials. A secret posted there is gone.
         *
         * Before the form is built, so a call that will not be made does not
         * assemble the secret to send. The reason is returned rather than
         * logged, the way [ModelChatClient] returns its own: it travels out
         * through the credential as the failure the provider check and the chat
         * put in front of whoever configured this.
         */
        probe.vet(address)?.let { return EntraToken.Failed("The Entra ID authority cannot be called: $it") }

        val uri = try {
            URI(address)
        } catch (_: Exception) {
            return EntraToken.Failed("The tenant is not usable in a token URL")
        }

        val form = listOf(
            "grant_type" to "client_credentials",
            "client_id" to provider.clientId.orEmpty(),
            "client_secret" to secret,
            "scope" to (provider.scope?.ifBlank { null } ?: DEFAULT_SCOPE),
        ).joinToString("&") { (name, value) -> "$name=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.warn("{} answered {}", address, response.statusCode())
                return EntraToken.Failed(
                    "Entra ID refused the credentials (${response.statusCode()})" + said(response.body()),
                )
            }
            val answer = mapper.readTree(response.body())
            val token = answer.path("access_token").stringValue()
                ?: return EntraToken.Failed("Entra ID answered without a token")
            // How long it is good for, which is what makes caching it safe.
            // Entra always says; a default only covers it never having to.
            val seconds = answer.path("expires_in").asLong(DEFAULT_TOKEN_SECONDS)
            EntraToken.Issued(token, seconds)
        } catch (failure: Exception) {
            log.warn("Asking {} for a token failed", address, failure)
            EntraToken.Failed(failure.message ?: "Entra ID could not be reached")
        }
    }

    private sealed interface EntraToken {
        data class Issued(val value: String, val seconds: Long = DEFAULT_TOKEN_SECONDS) : EntraToken
        data class Failed(val reason: String) : EntraToken
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelProviderProbe::class.java)

        /** What Azure OpenAI asks for when nothing else is said. */
        const val DEFAULT_SCOPE = "https://cognitiveservices.azure.com/.default"

        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_AZURE_VERSION = "2024-06-01"

        /** What Entra says when it says nothing: an hour is its usual answer. */
        const val DEFAULT_TOKEN_SECONDS = 3600L

        /** Held back from the expiry, so a token cannot lapse mid-request. */
        const val EXPIRY_MARGIN = 60L

        /** How much of a provider's own complaint is worth repeating. */
        const val MESSAGE_LIMIT = 200
    }
}
