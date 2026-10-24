package io.mszymanski.orknux.connector.model

import com.openai.core.MultipartField
import com.openai.errors.OpenAIServiceException
import com.openai.models.videos.Video
import com.openai.models.videos.VideoCreateParams
import com.openai.models.videos.VideoDownloadContentParams
import com.openai.models.videos.VideoSeconds
import com.openai.models.videos.VideoSize
import io.mszymanski.orknux.connector.Unreachable
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/** How a [VideoSetting] is asked for. */
enum class VideoSettingKind {
    /** One of [VideoSetting.choices], as the backend spells it. */
    CHOICE,

    /** A whole number between [VideoSetting.least] and [VideoSetting.most]. */
    NUMBER,

    /** A picture of the workspace's, sent as the first frame. */
    PICTURE,
}

/**
 * One thing a video model takes beyond the prompt, as the backend says it
 * does: what the editor offers, and the only settings a request may carry.
 *
 * @param preset what the backend uses when the setting is left out, where it
 *   says; shown as the box's placeholder, never sent on its own.
 */
data class VideoSetting(
    val name: String,
    val kind: VideoSettingKind,
    val choices: List<String> = emptyList(),
    val least: Long? = null,
    val most: Long? = null,
    val preset: String? = null,
)

/** The names [VideoSetting]s go by, which are also the fields of a request. */
object VideoSettingNames {
    const val SECONDS = "seconds"
    const val SIZE = "size"
    const val FRAMES = "frames"
    const val FPS = "fps"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val SEED = "seed"
    const val START_IMAGE = "startImage"
}

/** What a video model takes, or why that could not be learnt. */
sealed interface VideoSettings {
    data class Taken(val settings: List<VideoSetting>) : VideoSettings
    data class Failed(val reason: String) : VideoSettings
}

/**
 * A video asked for. Every field but the prompt is null where it is not asked,
 * which leaves the backend's own default standing.
 */
data class VideoAsk(
    val prompt: String,
    val seconds: Int? = null,
    val size: String? = null,
    val frames: Int? = null,
    val fps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val seed: Long? = null,
    val startImage: ByteArray? = null,
    val startImageType: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** A video started at its backend - by the id it is asked about by - or why not. */
sealed interface VideoStart {
    data class Started(val remoteId: String) : VideoStart
    data class Failed(val reason: String) : VideoStart
}

/** Where a video is at its backend. */
sealed interface VideoState {
    /** Still on its way: how far, in percent, and how many are before it, where the backend says. */
    data class Waiting(val progress: Int?, val ahead: Int?) : VideoState

    /** Made: the file, and a picture to show for it where the backend gives one. */
    data class Made(
        val video: ByteArray,
        val contentType: String,
        val poster: ByteArray?,
        val posterType: String?,
        val durationSeconds: Double?,
    ) : VideoState {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Failed(val reason: String) : VideoState
}

/**
 * Makes a video, using one of the workspace's [ModelKind.VIDEO] models.
 * Issue #658.
 *
 * A video is never one call answered: every backend takes it as a job, says
 * an id at once, and is asked about that id until the file is there. So this
 * is three calls - [start], [poll] and [cancel] - and the waiting between them
 * is the caller's, which keeps a row and can survive a restart; here nothing
 * is held.
 *
 * **Two backends, told apart by the provider.**
 *
 * - stable-diffusion.cpp's server, by its native `POST /sdcpp/v1/vid_gen` and
 *   `GET /sdcpp/v1/jobs/{id}` (Wan 2.x and the other video families it loads).
 *   Written by hand, because nothing else speaks it: there is no SDK, and the
 *   OpenAI shape it also serves has no video. What it takes is asked of its
 *   `GET /sdcpp/v1/capabilities`, so a setting is offered only where the
 *   loaded model has it - a start picture only for an image-to-video model.
 *   An [ProviderType.OPENAI] provider pointed at such a server is recognised
 *   the way the image editor recognises one, by asking.
 * - OpenAI's `/videos`, through the official SDK, for an OpenAI or Azure
 *   OpenAI provider. OpenAI's own Sora API was shut down on 24 September
 *   2026 - the SDK carries the notice - so this is for a server that still
 *   serves that shape; the provider's own refusal is what anybody else sees.
 *
 * Every other provider type is refused in a sentence before anything is
 * called, as [ModelImageClient] refuses a drawing.
 *
 * The id handed back carries which backend it belongs to - `sdcpp:` or
 * `openai:` - so asking about it later asks nobody which server it is.
 */
@Service
class ModelVideoClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val probe: ModelProviderProbe,
    private val connections: ConnectionProbe,
    private val clients: ModelClients,
    private val pictures: ModelImageClient,
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

    /** What the model takes beyond a prompt, asked of its backend where the backend says. */
    fun settings(modelId: Long): VideoSettings {
        val (_, provider) = when (val found = videoModel(modelId)) {
            is Found.No -> return VideoSettings.Failed(found.reason)
            is Found.Yes -> found.model to found.provider
        }
        return when (backendOf(provider)) {
            Backend.SDCPP -> when (val asked = capabilities(provider)) {
                is Capabilities.Failed -> VideoSettings.Failed(asked.reason)
                is Capabilities.Known -> VideoSettings.Taken(asked.settings)
            }
            Backend.OPENAI -> VideoSettings.Taken(OPENAI_SETTINGS)
            null -> VideoSettings.Failed(cannotMake(provider) ?: "${provider.name} makes no video")
        }
    }

    /** Asks the backend for the video; answers at once with the id it is then asked about. */
    fun start(modelId: Long, ask: VideoAsk): VideoStart {
        val (model, provider) = when (val found = videoModel(modelId)) {
            is Found.No -> return VideoStart.Failed(found.reason)
            is Found.Yes -> found.model to found.provider
        }
        if (ask.prompt.isBlank()) return VideoStart.Failed("There is nothing to make a video of")
        return when (backendOf(provider)) {
            Backend.SDCPP -> startSdcpp(model, provider, ask)
            Backend.OPENAI -> startOpenAi(model, provider, ask)
            null -> VideoStart.Failed(cannotMake(provider) ?: "${provider.name} makes no video")
        }
    }

    /**
     * Where [remoteId] is now. A video made is collected whole, up to
     * [maxBytes]; a larger one is a failure rather than a file half kept.
     */
    fun poll(modelId: Long, remoteId: String, maxBytes: Long, millis: Long = 0): VideoState {
        val (model, provider) = when (val found = videoModel(modelId)) {
            is Found.No -> return VideoState.Failed(found.reason)
            is Found.Yes -> found.model to found.provider
        }
        val state = when {
            remoteId.startsWith(SDCPP_PREFIX) -> pollSdcpp(model, provider, remoteId.removePrefix(SDCPP_PREFIX), maxBytes)
            remoteId.startsWith(OPENAI_PREFIX) -> pollOpenAi(model, provider, remoteId.removePrefix(OPENAI_PREFIX), maxBytes)
            else -> VideoState.Failed("$remoteId is not a video this server asked for")
        }
        // Counted once, when made, as a drawn picture is: a request answered and how long it took.
        if (state is VideoState.Made) runCatching { usage.record(modelId, inputTokens = 0, outputTokens = 0, millis = millis) }
        return state
    }

    /**
     * Asks the backend to stop making [remoteId]. Null once it said yes or has
     * no way to be asked, otherwise why not. stable-diffusion.cpp stops at its
     * next checkpoint; OpenAI's `/videos` has no cancel, so the video is left
     * to finish and what comes of it is not collected.
     */
    fun cancel(modelId: Long, remoteId: String): String? {
        if (!remoteId.startsWith(SDCPP_PREFIX)) return null
        val provider = when (val found = videoModel(modelId)) {
            is Found.No -> return found.reason
            is Found.Yes -> found.provider
        }
        val url = sdcppUrl(provider, "$JOBS/${remoteId.removePrefix(SDCPP_PREFIX)}/cancel") ?: return "The provider endpoint is not a usable URL"
        connections.vet(url)?.let { return "${provider.name} cannot be called: $it" }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return credential.reason
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        return try {
            val answer = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(limits.answer()).header(header.name, header.value)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (answer.statusCode() in 200..299 || answer.statusCode() == 409) null
            else "${provider.name} answered ${answer.statusCode()}" + detail(answer.body())
        } catch (failure: Exception) {
            log.warn("Cancelling a video at {} could not be done: {}", provider.endpoint, failure.toString())
            Unreachable.describe(url, failure, limits.answer())
        }
    }

    // -- stable-diffusion.cpp --------------------------------------------------

    private sealed interface Capabilities {
        data class Known(val settings: List<VideoSetting>, val format: String, val preview: Boolean) : Capabilities
        data class Failed(val reason: String) : Capabilities
    }

    /**
     * What the loaded model makes, from `GET /sdcpp/v1/capabilities`: refused
     * unless `vid_gen` is among its modes, and its settings from the
     * `vid_gen` defaults, limits and features it states.
     */
    private fun capabilities(provider: ModelProvider): Capabilities {
        val url = sdcppUrl(provider, CAPABILITIES) ?: return Capabilities.Failed("The provider endpoint is not a usable URL")
        connections.vet(url)?.let { return Capabilities.Failed("${provider.name} cannot be called: $it") }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Capabilities.Failed(credential.reason)
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        val tree = try {
            val answer = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(limits.answer()).header(header.name, header.value).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (answer.statusCode() !in 200..299) {
                return Capabilities.Failed("${provider.name} answered ${answer.statusCode()}" + detail(answer.body()))
            }
            mapper.readTree(answer.body())
        } catch (failure: Exception) {
            log.warn("Asking {} what it makes failed: {}", provider.endpoint, failure.toString())
            return Capabilities.Failed(Unreachable.describe(url, failure, limits.answer()))
        }
        val modes = tree.path("supported_modes").mapNotNull { text(it) }
        if (VID_GEN !in modes) {
            return Capabilities.Failed("${provider.name} has no video model loaded; load one there, such as a Wan recipe")
        }
        val defaults = tree.path("defaults_by_mode").path(VID_GEN)
        val features = tree.path("features_by_mode").path(VID_GEN)
        val limits = tree.path("limits")
        val formats = tree.path("output_formats_by_mode").path(VID_GEN).mapNotNull { text(it) }
        // What a browser plays, first: WebM in a <video>, an animated WebP in an <img>; AVI in neither.
        val format = PLAYABLE.firstOrNull { it in formats } ?: formats.firstOrNull() ?: WEBM
        val settings = buildList {
            add(VideoSetting(VideoSettingNames.FRAMES, VideoSettingKind.NUMBER, least = 1, most = MOST_FRAMES, preset = number(defaults, "video_frames")))
            add(VideoSetting(VideoSettingNames.FPS, VideoSettingKind.NUMBER, least = 1, most = MOST_FPS, preset = number(defaults, "fps")))
            add(
                VideoSetting(
                    VideoSettingNames.WIDTH, VideoSettingKind.NUMBER,
                    least = long(limits, "min_width") ?: SMALLEST_SIDE, most = long(limits, "max_width") ?: LARGEST_SIDE,
                    preset = number(defaults, "width"),
                ),
            )
            add(
                VideoSetting(
                    VideoSettingNames.HEIGHT, VideoSettingKind.NUMBER,
                    least = long(limits, "min_height") ?: SMALLEST_SIDE, most = long(limits, "max_height") ?: LARGEST_SIDE,
                    preset = number(defaults, "height"),
                ),
            )
            add(VideoSetting(VideoSettingNames.SEED, VideoSettingKind.NUMBER, least = -1, most = Long.MAX_VALUE, preset = number(defaults, "seed")))
            // Only an image-to-video model says it takes one; a text-to-video one ignores it, so it is not offered.
            if (features.path("init_image").asBoolean(false)) add(VideoSetting(VideoSettingNames.START_IMAGE, VideoSettingKind.PICTURE))
        }
        val preview = tree.path("preview_modes").mapNotNull { text(it) }.contains(PREVIEW)
        return Capabilities.Known(settings, format, preview)
    }

    private fun startSdcpp(model: LlmModel, provider: ModelProvider, ask: VideoAsk): VideoStart {
        val known = when (val asked = capabilities(provider)) {
            is Capabilities.Failed -> return VideoStart.Failed(asked.reason)
            is Capabilities.Known -> asked
        }
        val url = sdcppUrl(provider, VID_GEN_PATH) ?: return VideoStart.Failed("The provider endpoint is not a usable URL")
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return VideoStart.Failed(credential.reason)
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        val body = buildMap<String, Any> {
            put("prompt", ask.prompt)
            ask.frames?.let { put("video_frames", it) }
            ask.fps?.let { put("fps", it) }
            ask.width?.let { put("width", it) }
            ask.height?.let { put("height", it) }
            ask.seed?.let { put("seed", it) }
            ask.startImage?.let { put("init_image", "data:${ask.startImageType ?: PNG};base64," + Base64.getEncoder().encodeToString(it)) }
            put("output_format", known.format)
            // A cheap projection of each step, which is what says how far it has got.
            if (known.preview) {
                put("preview", PREVIEW)
                put("preview_interval", 1)
            }
        }
        return try {
            val answer = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(limits.answer()).header(header.name, header.value)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (answer.statusCode() !in 200..299) {
                log.warn("A video by {} at {} was refused: {}", model.name, provider.endpoint, answer.statusCode())
                return VideoStart.Failed("${model.name} answered ${answer.statusCode()}" + detail(answer.body()))
            }
            val id = text(mapper.readTree(answer.body()).path("id"))?.takeIf { it.isNotBlank() }
                ?: return VideoStart.Failed("${model.name} took the video without saying what it is called")
            log.info("A video by {} started at {} as {}", model.name, provider.endpoint, id)
            VideoStart.Started(SDCPP_PREFIX + id)
        } catch (failure: Exception) {
            log.warn("A video by {} at {} could not be asked for", model.name, provider.endpoint, failure)
            VideoStart.Failed(Unreachable.describe(url, failure, limits.answer()))
        }
    }

    private fun pollSdcpp(model: LlmModel, provider: ModelProvider, id: String, maxBytes: Long): VideoState {
        val url = sdcppUrl(provider, "$JOBS/$id") ?: return VideoState.Failed("The provider endpoint is not a usable URL")
        connections.vet(url)?.let { return VideoState.Failed("${provider.name} cannot be called: $it") }
        val header = when (val credential = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return VideoState.Failed(credential.reason)
            is ModelProviderProbe.Credential.Header -> credential.header
        }
        val answer = try {
            http.send(
                HttpRequest.newBuilder(URI(url)).timeout(limits.answer()).header(header.name, header.value).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (failure: Exception) {
            // Not a failure of the video: the server may be restarting. Asked again next time.
            log.warn("Asking {} about video {} failed: {}", provider.endpoint, id, failure.toString())
            return VideoState.Waiting(null, null)
        }
        // The whole answer is the video in base64, a third again larger than the file.
        val bytes = answer.body().use { it.readNBytes((maxBytes * 4 / 3 + ANSWER_SLACK).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt() + 1) }
        if (answer.statusCode() == 404 || answer.statusCode() == 410) {
            return VideoState.Failed("${provider.name} no longer has this video; it may have restarted or forgotten it")
        }
        if (answer.statusCode() !in 200..299) return VideoState.Failed("${model.name} answered ${answer.statusCode()}" + detail(String(bytes)))
        if (bytes.size.toLong() > maxBytes * 4 / 3 + ANSWER_SLACK) return VideoState.Failed(tooLarge(model, maxBytes))
        val job = mapper.readTree(bytes)
        return when (text(job.path("status"))) {
            "queued" -> VideoState.Waiting(0, job.path("queue_position").asInt(0).takeIf { it > 0 })
            "generating" -> {
                val preview = job.path("preview")
                val step = preview.path("step").asInt(0)
                val total = preview.path("total_steps").asInt(0)
                VideoState.Waiting(if (total > 0) (step * 100 / total).coerceIn(0, 99) else null, null)
            }
            "completed" -> {
                val result = job.path("result")
                val video = text(result.path("b64_json"))?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                    ?: return VideoState.Failed("${model.name} answered a video that could not be read")
                if (video.size.toLong() > maxBytes) return VideoState.Failed(tooLarge(model, maxBytes))
                val fps = result.path("fps").asInt(0)
                val frames = result.path("frame_count").asInt(0)
                VideoState.Made(
                    video = video,
                    contentType = text(result.path("mime_type"))?.takeIf { it.isNotBlank() } ?: WEBM_TYPE,
                    poster = null,
                    posterType = null,
                    durationSeconds = if (fps > 0 && frames > 0) frames.toDouble() / fps else null,
                )
            }
            "cancelled" -> VideoState.Failed("${provider.name} cancelled it")
            "failed" -> VideoState.Failed(
                text(job.path("error").path("message"))?.takeIf { it.isNotBlank() } ?: "${model.name} gave no reason",
            )
            else -> VideoState.Waiting(null, null)
        }
    }

    /** stable-diffusion.cpp's native paths hang off the host's root, whatever the provider's endpoint ends in. */
    private fun sdcppUrl(provider: ModelProvider, path: String): String? =
        runCatching { URI(provider.openAiBase()).resolve(path).toString() }.getOrNull()

    // -- OpenAI /videos ---------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun startOpenAi(model: LlmModel, provider: ModelProvider, ask: VideoAsk): VideoStart {
        val client = when (val ready = openAiClient(provider)) {
            is Ready.No -> return VideoStart.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }
        val params = VideoCreateParams.builder().prompt(ask.prompt).model(model.modelId)
        ask.seconds?.let { params.seconds(VideoSeconds.of(it.toString())) }
        ask.size?.let { params.size(VideoSize.of(it)) }
        ask.startImage?.let { picture ->
            val type = ask.startImageType ?: PNG
            params.inputReference(
                MultipartField.builder<VideoCreateParams.InputReference>()
                    .value(VideoCreateParams.InputReference.ofStream(picture.inputStream()))
                    .filename("start." + (type.substringAfter('/').substringBefore(';').ifBlank { "png" }))
                    .contentType(type)
                    .build(),
            )
        }
        return try {
            val video = clients.again { client.videos().create(params.build()) }
            log.info("A video by {} started at {} as {}", model.name, provider.endpoint, video.id())
            VideoStart.Started(OPENAI_PREFIX + video.id())
        } catch (refused: OpenAIServiceException) {
            log.warn("A video by {} at {} was refused: {}", model.name, provider.endpoint, refused.statusCode())
            VideoStart.Failed(refused.message ?: "${model.name} answered ${refused.statusCode()}")
        } catch (failure: Exception) {
            log.warn("A video by {} at {} could not be asked for", model.name, provider.endpoint, failure)
            VideoStart.Failed(Unreachable.describe(provider.endpoint, failure, limits.answer()))
        }
    }

    @Suppress("DEPRECATION")
    private fun pollOpenAi(model: LlmModel, provider: ModelProvider, id: String, maxBytes: Long): VideoState {
        val client = when (val ready = openAiClient(provider)) {
            is Ready.No -> return VideoState.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }
        return try {
            val video = clients.again { client.videos().retrieve(id) }
            when (video.status()) {
                Video.Status.QUEUED -> VideoState.Waiting(video.progress().toInt(), null)
                Video.Status.IN_PROGRESS -> VideoState.Waiting(video.progress().toInt().coerceIn(0, 99), null)
                Video.Status.FAILED -> VideoState.Failed(
                    video.error().orElse(null)?.message()?.takeIf { it.isNotBlank() } ?: "${model.name} gave no reason",
                )
                Video.Status.COMPLETED -> {
                    val bytes = clients.again { client.videos().downloadContent(id) }.use { answer ->
                        answer.body().use { it.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt()) }
                    }
                    if (bytes.size.toLong() > maxBytes) return VideoState.Failed(tooLarge(model, maxBytes))
                    if (bytes.isEmpty()) return VideoState.Failed("${model.name} answered an empty video")
                    // The poster is a nicety: a video without one is still the video.
                    val poster = runCatching {
                        clients.again {
                            client.videos().downloadContent(
                                id,
                                VideoDownloadContentParams.builder().variant(VideoDownloadContentParams.Variant.THUMBNAIL).build(),
                            )
                        }.use { answer -> answer.body().use { it.readNBytes(MOST_POSTER_BYTES) } }
                    }.getOrNull()?.takeIf { it.isNotEmpty() && it.size < MOST_POSTER_BYTES }
                    VideoState.Made(
                        video = bytes,
                        contentType = MP4_TYPE,
                        poster = poster,
                        posterType = poster?.let { WEBP_TYPE },
                        durationSeconds = runCatching { video.seconds().toString().toDouble() }.getOrNull(),
                    )
                }
                else -> VideoState.Waiting(null, null)
            }
        } catch (refused: OpenAIServiceException) {
            if (refused.statusCode() == 404) return VideoState.Failed("${provider.name} no longer has this video")
            log.warn("Asking {} about video {} was refused: {}", provider.endpoint, id, refused.statusCode())
            VideoState.Failed(refused.message ?: "${model.name} answered ${refused.statusCode()}")
        } catch (failure: Exception) {
            log.warn("Asking {} about video {} failed: {}", provider.endpoint, id, failure.toString())
            VideoState.Waiting(null, null)
        }
    }

    private sealed interface Ready {
        data class Yes(val client: com.openai.client.OpenAIClient) : Ready
        data class No(val reason: String) : Ready
    }

    /**
     * The SDK's client for the provider - for Azure, on the resource's v1
     * surface, where `/videos` is served with the model in the body, as its
     * Responses are.
     */
    private fun openAiClient(provider: ModelProvider): Ready {
        connections.vet(provider.openAiBase())?.let { return Ready.No("${provider.name} cannot be called: $it") }
        return when (val credential = probe.sdkCredential(provider)) {
            is ModelProviderProbe.SdkCredential.Failed -> Ready.No(credential.reason)
            is ModelProviderProbe.SdkCredential.Ready -> Ready.Yes(
                if (provider.type == ProviderType.AZURE_OPENAI) clients.responsesClientFor(provider, credential.credential)
                else clients.clientFor(provider, credential.credential),
            )
        }
    }

    // -- Shared -----------------------------------------------------------------

    private enum class Backend { SDCPP, OPENAI }

    /** Which backend a provider is: stable-diffusion.cpp by its type or by asking it, OpenAI's shape otherwise. */
    private fun backendOf(provider: ModelProvider): Backend? = when (provider.type) {
        ProviderType.STABLE_DIFFUSION_CPP -> Backend.SDCPP
        ProviderType.OPENAI -> if (pictures.servesStableDiffusionCpp(provider)) Backend.SDCPP else Backend.OPENAI
        ProviderType.AZURE_OPENAI -> Backend.OPENAI
        else -> null
    }

    /** Why this provider makes no video, in a sentence a person can act on. */
    private fun cannotMake(provider: ModelProvider): String? = when (provider.type) {
        ProviderType.ANTHROPIC -> "${provider.name} generates text, not video. Add a provider that makes video."
        ProviderType.OLLAMA, ProviderType.LLAMA_CPP ->
            "${provider.name} runs a language model server, which makes no video. " +
                "Run stable-diffusion.cpp's server beside it and add that as a provider."
        ProviderType.SYSTEM_ONE -> "${provider.name} is a decision model: it answers questions with probabilities and makes nothing."
        ProviderType.STABLE_DIFFUSION_WEBUI ->
            "${provider.name} is a Stable Diffusion WebUI, which is held here for upscaling. Add stable-diffusion.cpp's server to make video."
        ProviderType.OPENAI, ProviderType.AZURE_OPENAI, ProviderType.STABLE_DIFFUSION_CPP -> null
    }

    private fun videoModel(modelId: Long): Found {
        val model = models.findByIdOrNull(modelId) ?: return Found.No("That model no longer exists")
        if (model.kind != ModelKind.VIDEO) return Found.No("${model.name} is not a video model")
        if (!model.enabled) return Found.No("${model.name} is turned off")
        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Found.No("The provider ${model.name} belongs to has been removed")
        cannotMake(provider)?.let { return Found.No(it) }
        return Found.Yes(model, provider)
    }

    private sealed interface Found {
        data class Yes(val model: LlmModel, val provider: ModelProvider) : Found
        data class No(val reason: String) : Found
    }

    private fun tooLarge(model: LlmModel, maxBytes: Long) =
        "The video ${model.name} made is larger than this installation keeps (${maxBytes / (1024 * 1024)} MB)"

    private fun text(node: JsonNode): String? = runCatching { node.stringValue() }.getOrNull()

    private fun number(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isNumber }?.asLong()?.toString()

    private fun long(node: JsonNode, field: String): Long? = node.path(field).takeIf { it.isNumber }?.asLong()

    /** The server's own words for a refusal: its `error`, and the `message` beside it. */
    private fun detail(body: String): String = runCatching {
        val tree = mapper.readTree(body)
        listOf("message", "error").firstNotNullOfOrNull { field ->
            tree.path(field).takeIf { !it.isMissingNode && !it.isNull }?.let { if (it.isString) it.stringValue() else it.toString() }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" } ?: ""

    companion object {
        private val log = LoggerFactory.getLogger(ModelVideoClient::class.java)

        const val SDCPP_PREFIX = "sdcpp:"
        const val OPENAI_PREFIX = "openai:"

        private const val CAPABILITIES = "/sdcpp/v1/capabilities"
        private const val VID_GEN_PATH = "/sdcpp/v1/vid_gen"
        private const val JOBS = "/sdcpp/v1/jobs"
        private const val VID_GEN = "vid_gen"
        private const val PREVIEW = "proj"

        private const val WEBM = "webm"
        private val PLAYABLE = listOf(WEBM, "webp")
        private const val WEBM_TYPE = "video/webm"
        private const val MP4_TYPE = "video/mp4"
        private const val WEBP_TYPE = "image/webp"
        private const val PNG = "image/png"

        /** Room in a job's answer for everything around the base64 of the video. */
        private const val ANSWER_SLACK = 2L * 1024 * 1024
        private const val MOST_POSTER_BYTES = 8 * 1024 * 1024

        /** Bounds for what stable-diffusion.cpp does not state: its own form's, wide. */
        private const val MOST_FRAMES = 1024L
        private const val MOST_FPS = 120L
        private const val SMALLEST_SIDE = 64L
        private const val LARGEST_SIDE = 4096L

        private const val CONNECT_SECONDS = 10L

        /**
         * What OpenAI's `/videos` takes, as the SDK names it: a length, a
         * size, and a picture to start from - which must be that size.
         */
        val OPENAI_SETTINGS = listOf(
            VideoSetting(
                VideoSettingNames.SECONDS, VideoSettingKind.CHOICE,
                choices = listOf(VideoSeconds._4, VideoSeconds._8, VideoSeconds._12).map { it.toString() },
            ),
            VideoSetting(
                VideoSettingNames.SIZE, VideoSettingKind.CHOICE,
                choices = listOf(VideoSize._720X1280, VideoSize._1280X720, VideoSize._1024X1792, VideoSize._1792X1024).map { it.toString() },
            ),
            VideoSetting(VideoSettingNames.START_IMAGE, VideoSettingKind.PICTURE),
        )
    }
}
