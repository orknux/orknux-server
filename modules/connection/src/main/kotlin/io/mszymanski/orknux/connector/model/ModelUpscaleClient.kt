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
 * models - and redraws one lightly through the same model's server, which is
 * what [harmonize] is for.
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
 *
 * An upscale model stands for its WebUI as a whole, so the light redraw is
 * asked through it too, by `POST /sdapi/v1/img2img`: a second provider for the
 * same server would be a second key to keep and a second address to get wrong.
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

    /**
     * The checkpoints the model's WebUI can redraw with, by the title
     * `sd_model_checkpoint` takes - the one it has loaded first, so choosing
     * nothing costs no reload.
     */
    fun checkpoints(modelId: Long): Checkpoints {
        val provider = when (val found = upscaler(modelId)) {
            is Found.No -> return Checkpoints.Failed(found.reason)
            is Found.Yes -> found.provider
        }
        val base = provider.endpoint.trimEnd('/')
        connections.vet("$base$SD_MODELS")?.let { return Checkpoints.Failed("${provider.name} cannot be called: $it") }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Checkpoints.Failed(credential.reason)
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        fun ask(path: String): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI("$base$path"))
                .timeout(limits.answer())
                .header(header.name, header.value)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return try {
            val answer = ask(SD_MODELS)
            if (answer.statusCode() !in 200..299) {
                return Checkpoints.Failed("${provider.name} answered ${answer.statusCode()}" + detail(answer.body()))
            }
            val titles = mapper.readTree(answer.body()).mapNotNull { runCatching { it.path("title").stringValue() }.getOrNull() }
                .filter { it.isNotBlank() }
            // What it has loaded, where it says; not knowing is no reason to offer nothing.
            val loaded = runCatching {
                ask(SD_OPTIONS).takeIf { it.statusCode() in 200..299 }
                    ?.let { mapper.readTree(it.body()).path("sd_model_checkpoint").stringValue() }
            }.getOrNull()?.takeIf { it in titles }
            Checkpoints.Offered((listOfNotNull(loaded) + titles).distinct(), loaded ?: titles.firstOrNull())
        } catch (failure: Exception) {
            log.warn("Asking {} for its checkpoints failed: {}", base, failure.toString())
            Checkpoints.Failed(Unreachable.describe(base, failure, limits.answer()))
        }
    }

    /**
     * The whole picture redrawn lightly - img2img at [strength], its own size,
     * from [prompt] - so the seams between parts redrawn apart become one
     * picture. [checkpoint] null is whatever the WebUI has loaded.
     */
    fun harmonize(modelId: Long, image: ByteArray, prompt: String, strength: Double, checkpoint: String?): Picture {
        val (model, provider) = when (val found = upscaler(modelId)) {
            is Found.No -> return Picture.Failed(found.reason)
            is Found.Yes -> found.model to found.provider
        }
        // The WebUI's own bounds; the installation's narrower ones are the caller's.
        if (strength !in 0.0..1.0) return Picture.Failed("A strength is between 0 and 1")
        val size = runCatching { javax.imageio.ImageIO.read(image.inputStream()) }.getOrNull()
            ?: return Picture.Failed("The picture could not be read")
        val base = provider.endpoint.trimEnd('/')
        val url = "$base$IMG2IMG"
        connections.vet(url)?.let { return Picture.Failed("${provider.name} cannot be called: $it") }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Picture.Failed(credential.reason)
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        val asked = buildMap<String, Any> {
            put("init_images", listOf(Base64.getEncoder().encodeToString(image)))
            put("prompt", prompt)
            put("denoising_strength", strength)
            // Its own size: the WebUI otherwise draws 512 square and the picture changes shape.
            put("width", size.width)
            put("height", size.height)
            checkpoint?.takeIf { it.isNotBlank() }?.let { put("override_settings", mapOf("sd_model_checkpoint" to it)) }
        }
        val started = System.currentTimeMillis()
        return try {
            val answer = http.send(
                HttpRequest.newBuilder(URI(url))
                    .timeout(limits.answer())
                    .header(header.name, header.value)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(asked)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (answer.statusCode() !in 200..299) {
                log.warn("Harmonizing by {} at {} answered {}", model.name, base, answer.statusCode())
                return Picture.Failed("${provider.name} answered ${answer.statusCode()}" + detail(answer.body()))
            }
            val encoded = runCatching { mapper.readTree(answer.body()).path("images").path(0).stringValue() }.getOrNull()
                ?.substringAfter("base64,")
                ?.takeIf { it.isNotBlank() }
                ?: return Picture.Failed("${provider.name} answered without a picture")
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                ?: return Picture.Failed("${provider.name} answered a picture that could not be read")
            val millis = System.currentTimeMillis() - started
            runCatching { usage.record(modelId, inputTokens = 0, outputTokens = 0, millis = millis) }
            Picture.Drawn(bytes, PNG, millis)
        } catch (failure: Exception) {
            log.warn("Harmonizing by {} at {} could not be done", model.name, base, failure)
            Picture.Failed(Unreachable.describe(base, failure, limits.answer()))
        }
    }

    /**
     * Asks the model's WebUI to stop what it is drawing, by `POST
     * /sdapi/v1/interrupt`. Null once it said yes, otherwise why not. The
     * WebUI stops its current job, whichever request that came from: it has
     * no way to name one.
     */
    fun interrupt(modelId: Long): String? {
        val provider = when (val found = upscaler(modelId)) {
            is Found.No -> return found.reason
            is Found.Yes -> found.provider
        }
        val base = provider.endpoint.trimEnd('/')
        val url = "$base$INTERRUPT"
        connections.vet(url)?.let { return "${provider.name} cannot be called: $it" }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return credential.reason
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        return try {
            val answer = http.send(
                HttpRequest.newBuilder(URI(url))
                    .timeout(limits.answer())
                    .header(header.name, header.value)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (answer.statusCode() in 200..299) null else "${provider.name} answered ${answer.statusCode()}" + detail(answer.body())
        } catch (failure: Exception) {
            log.warn("Interrupting {} could not be done: {}", base, failure.toString())
            Unreachable.describe(base, failure, limits.answer())
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

    /** What a WebUI can redraw with. */
    sealed interface Checkpoints {
        /** By title, the loaded one first. */
        data class Offered(val titles: List<String>, val preferred: String?) : Checkpoints
        data class Failed(val reason: String) : Checkpoints
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelUpscaleClient::class.java)

        const val EXTRA_SINGLE_IMAGE = "/sdapi/v1/extra-single-image"
        const val IMG2IMG = "/sdapi/v1/img2img"
        const val INTERRUPT = "/sdapi/v1/interrupt"
        const val SD_MODELS = "/sdapi/v1/sd-models"
        const val SD_OPTIONS = "/sdapi/v1/options"

        /** What the WebUI answers in: its extras save PNG unless set otherwise. */
        const val PNG = "image/png"

        const val CONNECT_SECONDS = 10L

        /** What the WebUI's own form allows. */
        const val MIN_SCALE = 1.0
        const val MAX_SCALE = 8.0
    }
}
