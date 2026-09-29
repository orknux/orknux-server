package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import com.openai.errors.OpenAIServiceException
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

/** What came back from a drawing, or why nothing did. */
sealed interface Picture {

    /**
     * The picture itself, and what it is.
     *
     * The bytes are handed to the caller rather than kept here, the way
     * [Speech.Spoke] hands its audio over. Unlike that one they are then filed:
     * a picture is the whole of what was asked for and has to be there when the
     * chat is opened again, so the caller writes it into attachment storage.
     */
    data class Drawn(val image: ByteArray, val contentType: String, val millis: Long) : Picture {

        /*
         * By hand for the reason [Speech.Spoke] gives: the array makes the
         * compiler's version wrong, and identity is what this actually has.
         */
        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class Failed(val reason: String) : Picture
}

/**
 * What a caller may ask of the drawing beyond the prompt.
 *
 * The three fields every OpenAI-shaped image endpoint understands, and nothing
 * provider-specific: `size` as `WIDTHxHEIGHT` in pixels, `quality` (DALL-E 3
 * says `standard|hd`, gpt-image-1 says `low|medium|high`) and `style`
 * (`vivid|natural`). Each is passed through as given, and null is left out of
 * the request altogether so the model draws at its own default - which is what
 * every caller before this asked for, and what [NONE] still asks for.
 * Issue #423.
 */
data class ImageOptions(
    val size: String? = null,
    val quality: String? = null,
    val style: String? = null,
) {
    companion object {
        /** The model's own defaults throughout; what a chat's button and a task's tool send. */
        val NONE = ImageOptions()
    }
}

/**
 * Draws a picture, using one of the workspace's models.
 *
 * The fourth client beside the chat, the reader and the dictation, and built
 * the same way: the same providers, the same credential through
 * [ModelProviderProbe.credentials], the same [ConnectionProbe] question about
 * where the endpoint is taking the key, and the same failure vocabulary — a
 * sentence naming what went wrong, because the caller puts it in front of the
 * person who set the provider up.
 *
 * **Which API this is.** Not chat completions. OpenAI's image generation is
 * `POST /images/generations`, a JSON body naming the model and the prompt,
 * answering with `data[0].b64_json` or `data[0].url`. That is the shape spoken
 * by OpenAI itself, by an Azure OpenAI deployment — which, as everywhere else
 * here, puts the deployment and the API version in the path — and by the local
 * servers that imitate OpenAI, which is what [ProviderType.OPENAI] covers: the
 * type names the shape being spoken, not the company answering.
 *
 * **Which providers are refused, and why.** Two, and both before a request is
 * built rather than after a 404:
 *
 * - [ProviderType.ANTHROPIC]. The Messages API generates text. Claude reads a
 *   picture perfectly well — `ModelChatClient` sends it one — and cannot draw
 *   one, and there is no other endpoint on that host to ask.
 * - [ProviderType.OLLAMA]. A local Ollama is a first-class target for this
 *   product and it is worth being exact about what it can do: it runs chat
 *   models, it runs embedding models, and models that see are among them, so
 *   the chat, and pictures sent *to* a model, work against a local box. Its
 *   OpenAI-compatible surface under `/v1` covers chat completions, completions,
 *   embeddings and the model list; it serves no image generation at all, and
 *   its own `/api` surface has no equivalent either. A local installation that
 *   wants to draw runs something else beside Ollama — a server that speaks
 *   `/images/generations` — and adds it as an [ProviderType.OPENAI] provider
 *   pointed at that server's own address, which is the answer this refusal
 *   points at. The type is about the shape, so naming OpenAI there is not a
 *   claim about who is answering.
 *
 * Refusing by name is deliberate. Sending the request anyway would produce a
 * 404 that reads as a broken endpoint, and somebody would go and check the URL
 * they had typed correctly.
 */
@Service
class ModelImageClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val media: OpenAiMedia,
    private val connections: ConnectionProbe,
    private val mapper: ObjectMapper,
    private val usage: ModelUsageRecorder,
    private val proxies: ProxyRouter,
) {

    private val http: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        // Said out loud rather than left to the default, the same as every other
        // client here: a redirect can leave the host somebody configured and
        // take the key with it.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * @param modelId one of the workspace's models, which has to be an
     *   [ModelKind.IMAGE] one: a chat model handed this would write about the
     *   picture rather than draw it.
     * @param options size, quality and style where the caller has a say; an
     *   image node names its own. Left out, the model's defaults stand.
     */
    fun draw(modelId: Long, prompt: String, options: ImageOptions = ImageOptions.NONE): Picture {
        val model = models.findByIdOrNull(modelId)
            ?: return Picture.Failed("That model no longer exists")
        if (model.kind != ModelKind.IMAGE) {
            return Picture.Failed("${model.name} is not an image model")
        }
        if (!model.enabled) return Picture.Failed("${model.name} is turned off")

        val asked = prompt.trim()
        if (asked.isEmpty()) return Picture.Failed("There is nothing to draw")

        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Picture.Failed("The provider ${model.name} belongs to has been removed")

        cannotDraw(provider)?.let { return Picture.Failed(it) }

        /*
         * The base, because the SDK builds the path.
         *
         * This used to assemble Azure's `/openai/deployments/{name}/images/
         * generations?api-version=...` itself and hand it over. The request is
         * the SDK's now - see [OpenAiMedia] - so the only thing still wanted
         * here is somewhere to vet and something to name in a log, and the base
         * is what both of those are about. Keeping the old string would have
         * meant vetting one address and calling another, and printing a URL
         * nobody had asked for.
         */
        val endpoint = provider.openAiBase()
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return Picture.Failed("The provider endpoint is not a usable URL")
        }

        // Before the credential is resolved, so a call that will not be made
        // decrypts nothing — the rule every client here follows.
        connections.vet(endpoint)?.let { return Picture.Failed("${provider.name} cannot be called: $it") }

        val started = System.currentTimeMillis()
        return try {
            when (val drawn = media.draw(provider, model, asked, options)) {
                is OpenAiMedia.Drawn.Failed -> Picture.Failed(drawn.reason)
                is OpenAiMedia.Drawn.Bytes -> {
                    val bytes = runCatching { Base64.getDecoder().decode(drawn.base64) }.getOrNull()
                        ?: return Picture.Failed("${model.name} answered a picture that could not be read")
                    counted(modelId, Picture.Drawn(bytes, PNG, System.currentTimeMillis() - started))
                }
                // Parked somewhere rather than sent, which is one more call to a
                // host the provider chose - so it goes through the guard.
                is OpenAiMedia.Drawn.Elsewhere ->
                    counted(modelId, fetched(drawn.url, System.currentTimeMillis() - started, model.name, provider))
            }
        } catch (refused: OpenAIServiceException) {
            // The provider's own words where it gave any. A prompt refused for
            // its content is answered here, and "answered 400" would hide the
            // one thing worth knowing about it.
            log.warn("Drawing by {} at {} answered {}", model.name, endpoint, refused.statusCode())
            Picture.Failed(refused.message ?: "${model.name} answered ${refused.statusCode()}")
        } catch (failure: Exception) {
            // A timeout arrives here, and as a sentence rather than as nothing:
            // the picture is the whole of the request, so a caller with no
            // reason to show has nothing at all to show.
            log.warn("Drawing by {} at {} could not be done", model.name, endpoint, failure)
            Picture.Failed(failure.message ?: "The picture could not be drawn")
        }
    }

    /**
     * Whether this provider has an image endpoint at all.
     *
     * @return why not, in a sentence a person can act on, or null to go ahead.
     */
    private fun cannotDraw(provider: ModelProvider): String? = when (provider.type) {
        ProviderType.ANTHROPIC ->
            "${provider.name} generates text, not pictures. Add a provider that offers image generation."

        ProviderType.OLLAMA ->
            "${provider.name} runs Ollama, which has no image generation. " +
                "Run an image server beside it and add that as an OpenAI provider."

        ProviderType.SYSTEM_ONE ->
            "${provider.name} is a decision model: it answers questions with probabilities and draws nothing."

        ProviderType.OPENAI, ProviderType.AZURE_OPENAI -> null
    }


    /**
     * The picture out of what the server answered.
     *
     * Both shapes, because the providers disagree and neither can be asked for
     * the other reliably — see the body above. `b64_json` is bytes already here;
     * a `url` is one more call, and one more call carrying no credential, to a
     * host that gets vetted like any other. A 200 carrying an error object is
     * read as the refusal it is: several of these report an unusable prompt that
     * way.
     */
    /**
     * A picture the provider parked somewhere rather than sent.
     *
     * OpenAI's older image models answer with a link by default, and those links
     * expire within the hour — so it is fetched now, once, and filed by the
     * caller. Vetted first, because the host is chosen by the provider's answer
     * and not by anybody here, which makes it exactly the kind of URL the guard
     * is for. No credential goes with it: these are signed links, and sending
     * the workspace's key to a host named in a response would be handing it to
     * whoever wrote the response.
     */
    private fun fetched(url: String, millis: Long, name: String, provider: ModelProvider): Picture {
        connections.vet(url)?.let { return Picture.Failed("${provider.name} put the picture somewhere unreachable: $it") }

        return try {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(FETCH_SECONDS))
                .GET()
                .build()
            val answer = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (answer.statusCode() !in 200..299) {
                return Picture.Failed("The picture $name drew could not be collected (${answer.statusCode()})")
            }
            val bytes = answer.body()
            if (bytes.isEmpty()) return Picture.Failed("The picture $name drew was empty")

            val contentType = answer.headers().firstValue("content-type").orElse("").lowercase()
                .substringBefore(';')
                .trim()
            Picture.Drawn(bytes, contentType.takeIf { it.startsWith("image/") } ?: PNG, millis)
        } catch (failure: Exception) {
            log.warn("The picture {} drew could not be collected", name, failure)
            Picture.Failed(failure.message ?: "The picture could not be collected")
        }
    }

    /**
     * One string off a node, or null where there is not one.
     *
     * Asked for rather than read straight, because Jackson 3's `stringValue()`
     * throws on a node that is absent or is not a string - and both are ordinary
     * here. A provider answers with `b64_json` or with `url` and never with
     * both, so one of the two is always the missing node.
     */
    private fun text(node: JsonNode, field: String): String? =
        runCatching { node.path(field).stringValue() }.getOrNull()

    /** What the provider said went wrong, where it said anything. */
    private fun refusal(body: String): String? = runCatching {
        mapper.readTree(body).path("error").path("message").stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Every drawn picture is counted, here rather than in the caller.
     *
     * The same rule `ModelChatClient.counted` states, and the same reason: this
     * is the only thing that calls an image model, so a second caller added
     * later cannot forget. Zero tokens, because an image call reports none —
     * what the metrics card can honestly say about this model is how many
     * requests it answered and how long they took, and the money is worked out
     * from the requests and the per-image price. A failure counts as nothing:
     * nothing was drawn.
     */
    private fun counted(modelId: Long, picture: Picture): Picture {
        if (picture is Picture.Drawn) {
            runCatching { usage.record(modelId, inputTokens = 0, outputTokens = 0, millis = picture.millis) }
        }
        return picture
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelImageClient::class.java)

        /**
         * What a picture is assumed to be when nobody said.
         *
         * `b64_json` carries no type with it and every one of these providers
         * encodes PNG there. It is also on `AttachmentDownloads.SHOWABLE`, which
         * is what lets the chat display the file rather than download it.
         */
        const val PNG = "image/png"

        const val CONNECT_SECONDS = 10L

        /** Drawing is slow; a hosted model takes tens of seconds and a local one more. */
        const val REQUEST_SECONDS = 180L

        /** Collecting bytes that already exist, which is not slow. */
        const val FETCH_SECONDS = 60L
    }
}
