package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.Unreachable
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Makes a picture larger, using one of the workspace's [ModelKind.UPSCALE]
 * models.
 *
 * **Why the request is written by hand.** There is no standard interface for
 * upscaling - OpenAI's image API has none - and no SDK for the one this speaks:
 * the Stable Diffusion WebUI's `POST /sdapi/v1/extra-single-image`, which
 * AUTOMATIC1111, Forge and SD.Next all serve unchanged. A picture and an
 * algorithm's name go in as JSON, the picture comes back as base64, and that is
 * the whole of the protocol.
 *
 * The algorithms are the server's own - its `/sdapi/v1/upscalers` - so they are
 * asked of the server rather than listed here: they differ between the three
 * and with the models each was given.
 */
@Service
class ModelUpscaleClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val probe: ModelProviderProbe,
    private val connections: ConnectionProbe,
    private val mapper: ObjectMapper,
    private val usage: ModelUsageRecorder,
    proxies: ProxyRouter,
    private val limits: ModelCallLimits = ModelCallLimits.FRESH,
) {

    private val http: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        // A redirect can leave the host somebody configured and take the key with it.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** The algorithms the model's server offers, or why they could not be had. */
    fun algorithms(modelId: Long): Algorithms {
        val (model, provider) = when (val found = upscaler(modelId)) {
            is Found.No -> return Algorithms.Failed(found.reason)
            is Found.Yes -> found.model to found.provider
        }
        return when (val listing = probe.list(provider)) {
            is ModelProviderProbe.Listing.Failed -> Algorithms.Failed(listing.reason)
            // The model's own first: it is the one it was set up to use.
            is ModelProviderProbe.Listing.Models ->
                Algorithms.Offered((listOf(model.modelId) + listing.ids).distinct().filter { it.isNotBlank() }, model.modelId)
        }
    }

    /**
     * The picture, [scale] times larger, by [algorithm] - or by the model's own
     * where none is named.
     */
    fun upscale(modelId: Long, image: ByteArray, algorithm: String?, scale: Double): Picture {
        val (model, provider) = when (val found = upscaler(modelId)) {
            is Found.No -> return Picture.Failed(found.reason)
            is Found.Yes -> found.model to found.provider
        }
        if (scale !in MIN_SCALE..MAX_SCALE) return Picture.Failed("A picture can be made $MIN_SCALE to $MAX_SCALE times larger")
        val base = provider.endpoint.trimEnd('/')
        val url = "$base$EXTRA_SINGLE_IMAGE"
        // Before the credential is read, so a call that will not be made decrypts nothing.
        connections.vet(url)?.let { return Picture.Failed("${provider.name} cannot be called: $it") }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Picture.Failed(credential.reason)
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        val body = mapper.writeValueAsString(
            mapOf(
                "image" to Base64.getEncoder().encodeToString(image),
                "resize_mode" to 0,
                "upscaling_resize" to scale,
                "upscaler_1" to (algorithm?.takeIf { it.isNotBlank() } ?: model.modelId),
            ),
        )
        val started = System.currentTimeMillis()
        return try {
            val answer = http.send(
                HttpRequest.newBuilder(URI(url))
                    .timeout(limits.answer())
                    .header(header.name, header.value)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (answer.statusCode() !in 200..299) {
                log.warn("Upscaling by {} at {} answered {}", model.name, base, answer.statusCode())
                return Picture.Failed("${model.name} answered ${answer.statusCode()}" + detail(answer.body()))
            }
            val encoded = runCatching { mapper.readTree(answer.body()).path("image").stringValue() }.getOrNull()
                ?.substringAfter("base64,")
                ?.takeIf { it.isNotBlank() }
                ?: return Picture.Failed("${model.name} answered without a picture")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: return Picture.Failed("${model.name} answered a picture that could not be read")
            val millis = System.currentTimeMillis() - started
            runCatching { usage.record(modelId, inputTokens = 0, outputTokens = 0, millis = millis) }
            Picture.Drawn(bytes, PNG, millis)
        } catch (failure: Exception) {
            log.warn("Upscaling by {} at {} could not be done", model.name, base, failure)
            Picture.Failed(Unreachable.describe(base, failure, limits.answer()))
        }
    }

    private fun upscaler(modelId: Long): Found {
        val model = models.findByIdOrNull(modelId) ?: return Found.No("That model no longer exists")
        if (model.kind != ModelKind.UPSCALE) return Found.No("${model.name} is not an upscale model")
        if (!model.enabled) return Found.No("${model.name} is turned off")
        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Found.No("The provider ${model.name} belongs to has been removed")
        if (provider.type != ProviderType.STABLE_DIFFUSION_WEBUI) {
            return Found.No("${provider.name} is not a Stable Diffusion WebUI, the one kind of server that upscales here")
        }
        return Found.Yes(model, provider)
    }

    /** The WebUI's own words for a refusal: FastAPI's `detail`, or its `error` and `errors`. */
    private fun detail(body: String): String = runCatching {
        val tree = mapper.readTree(body)
        listOf("detail", "errors", "error").firstNotNullOfOrNull { field ->
            tree.path(field).takeIf { !it.isMissingNode && !it.isNull }?.let { if (it.isString) it.stringValue() else it.toString() }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" } ?: ""

    private sealed interface Found {
        data class Yes(val model: LlmModel, val provider: ModelProvider) : Found
        data class No(val reason: String) : Found
    }

    /** What an upscale model's server can make a picture larger with. */
    sealed interface Algorithms {
        /** By name, the model's own first. */
        data class Offered(val names: List<String>, val preferred: String) : Algorithms
        data class Failed(val reason: String) : Algorithms
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelUpscaleClient::class.java)

        const val EXTRA_SINGLE_IMAGE = "/sdapi/v1/extra-single-image"

        /** What the WebUI answers in: its extras save PNG unless set otherwise. */
        const val PNG = "image/png"

        const val CONNECT_SECONDS = 10L

        /** What the WebUI's own form allows. */
        const val MIN_SCALE = 1.0
        const val MAX_SCALE = 8.0
    }
}
