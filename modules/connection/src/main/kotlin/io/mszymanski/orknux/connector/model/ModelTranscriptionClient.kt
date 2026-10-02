package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.Unreachable
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import com.openai.errors.OpenAIServiceException
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/** What came back from a transcription, or why nothing did. */
sealed interface Transcription {
    data class Heard(val text: String, val millis: Long) : Transcription

    data class Failed(val reason: String) : Transcription
}

/**
 * Turns recorded speech into text, using one of the workspace's models.
 *
 * The same shape as the chat client and for the same reason: a provider is an
 * endpoint and a credential, and what differs per provider is the path and the
 * body. Whisper's API — the one faster-whisper, whisper.cpp's server and OpenAI
 * all speak — takes a multipart form with the audio and the model name, and
 * answers `{"text": "..."}`.
 *
 * The request goes through the official SDK, which carries the multipart
 * encoding this used to write out by hand, boundary string and all. See
 * [OpenAiMedia], and AGENTS.md for why a vendor's own client is preferred to a
 * few lines of bytes that are right until the vendor moves.
 */
@Service
class ModelTranscriptionClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val media: OpenAiMedia,
    private val connections: ConnectionProbe,
    private val proxies: ProxyRouter,
) {

    private val http: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        // Said out loud rather than left to the default, because it is the same
        // rule the chat client and the probe state: a redirect can leave the
        // host somebody configured and take the key with it.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * @param modelId one of the workspace's models, which has to be a
     *   transcription one: a chat model handed audio answers something, and
     *   what it answers is not a transcript.
     * @param audio what was recorded, as it arrived from the browser.
     * @param filename what to call it in the form; the extension is how most
     *   servers decide which decoder to use.
     */
    fun transcribe(modelId: Long, audio: ByteArray, filename: String, contentType: String): Transcription {
        val model = models.findByIdOrNull(modelId)
            ?: return Transcription.Failed("That model no longer exists")
        if (model.kind != ModelKind.TRANSCRIPTION) {
            return Transcription.Failed("${model.name} is not a transcription model")
        }
        if (!model.enabled) return Transcription.Failed("${model.name} is turned off")

        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Transcription.Failed("The provider ${model.name} belongs to has been removed")

        // Off the OpenAI surface rather than off the endpoint, for the reason
        // [ModelProvider.openAiBase] gives: on some types they are not the same place.
        val endpoint = "${provider.openAiBase()}/audio/transcriptions"
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return Transcription.Failed("The provider endpoint is not a usable URL")
        }

        /*
         * Asked before the recording is sent anywhere, and not only behind the
         * "Test provider" button: the credential rides on this request, and a
         * check that only happens when somebody presses something is a check
         * that mostly does not happen. [ConnectionProbe] decides, the same rule
         * the connection probe applies, and the refusal is what the dictation
         * button reports back rather than a line in a log. Before the
         * credential too, so a call that will not be made fetches nothing.
         */
        connections.vet(endpoint)?.let { return Transcription.Failed("${provider.name} cannot be called: $it") }

        val started = System.currentTimeMillis()
        return try {
            when (val heard = media.transcribe(provider, model, audio, filename, contentType)) {
                is OpenAiMedia.Heard.Failed -> Transcription.Failed(heard.reason)
                is OpenAiMedia.Heard.Words -> {
                    val said = heard.text.trim()
                    if (said.isEmpty()) {
                        Transcription.Failed("${model.name} answered something that was not a transcript")
                    } else {
                        Transcription.Heard(said, System.currentTimeMillis() - started)
                    }
                }
            }
        } catch (refused: OpenAIServiceException) {
            log.warn("Transcription by {} at {} answered {}", model.name, endpoint, refused.statusCode())
            Transcription.Failed(refused.message ?: "${model.name} answered ${refused.statusCode()}")
        } catch (failure: Exception) {
            log.warn("Transcription by {} at {} could not be done", model.name, endpoint, failure)
            Transcription.Failed(Unreachable.describe(endpoint, failure))
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelTranscriptionClient::class.java)

        const val CONNECT_SECONDS = 10L
        const val REQUEST_SECONDS = 120L
    }
}
