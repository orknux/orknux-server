package io.mszymanski.orknux.connector.model

import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.core.MultipartField
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import com.openai.models.images.ImageGenerateParams
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * Drawing, reading aloud and listening, spoken through the official SDK.
 *
 * The three sit together because they are the same shape of call - one request,
 * one artefact back - and the same three lines of setup. Chat is the one that
 * differs, having a stream and tool calls to carry, and lives in [OpenAiChat].
 *
 * **What this replaces.** Three hand-built requests, one of which assembled a
 * multipart body by hand with its own boundary string, and one of which built
 * `/openai/deployments/{name}/images/generations?api-version=...` for Azure from
 * an assumption about which of that service's two URL layouts was in play. See
 * AGENTS.md: the SDK carries the layouts and the multipart encoding, and is
 * updated by the people who move them.
 *
 * **What it does not replace.** A provider that answers a picture as a URL is
 * still fetched separately by [ModelImageClient], because that is a second call
 * to a host nobody vetted and it goes through the same address guard everything
 * else here does. The SDK hands over the URL; it does not decide whether this
 * application should follow it.
 */
@Component
class OpenAiMedia(private val clients: ModelClients, private val probe: ModelProviderProbe) {

    /**
     * A picture, as bytes or as a URL to fetch - whichever the provider gave.
     *
     * @param options what to ask for beyond the prompt. Each field that is set
     *   goes into the body under its standard name - `size`, `quality`,
     *   `style` - and each that is null is left out, so the model's own
     *   default stands rather than a guess at it made here.
     */
    fun draw(provider: ModelProvider, model: LlmModel, prompt: String, options: ImageOptions = ImageOptions.NONE): Drawn {
        val client = when (val ready = ready(provider)) {
            is Ready.No -> return Drawn.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }

        /*
         * `n` is sent, and is 1. Not a matter of taste: it is what makes one
         * recorded request one picture, which is what the per-image price is
         * multiplied by.
         *
         * No format is asked for. The providers disagree - some answer bytes,
         * some a URL, some refuse a request that names the one they do not do -
         * so both shapes are read back instead.
         *
         * Size, quality and style are passed through as the words they were
         * given rather than mapped onto the SDK's constants: `of(...)` takes
         * any string, and which words a model accepts is the model's business.
         * DALL-E 3 knows `hd` and gpt-image-1 knows `high`; a word the model
         * does not know is answered 400 with the provider's own sentence, which
         * the caller shows.
         */
        val params = ImageGenerateParams.builder().model(model.modelId).prompt(prompt).n(1)
        options.size?.let { params.size(ImageGenerateParams.Size.of(it)) }
        options.quality?.let { params.quality(ImageGenerateParams.Quality.of(it)) }
        options.style?.let { params.style(ImageGenerateParams.Style.of(it)) }
        val answer = clients.again { client.images().generate(params.build()) }

        val first = answer.data().orElse(null)?.firstOrNull()
            ?: return Drawn.Failed("${model.name} answered without a picture")

        first.b64Json().orElse(null)?.takeIf { it.isNotBlank() }?.let { return Drawn.Bytes(it) }
        first.url().orElse(null)?.takeIf { it.isNotBlank() }?.let { return Drawn.Elsewhere(it) }
        return Drawn.Failed("${model.name} answered without a picture")
    }

    /** What drawing produced. */
    sealed interface Drawn {
        /** The picture itself, base64 as the provider sent it. */
        data class Bytes(val base64: String) : Drawn

        /** An address to fetch it from, once somebody has decided that is allowed. */
        data class Elsewhere(val url: String) : Drawn

        data class Failed(val reason: String) : Drawn
    }

    /**
     * The text read aloud, as audio bytes.
     *
     * MP3 because it is the one format every one of these services produces and
     * every browser plays.
     *
     * **A voice is always sent, which the hand-built request did not do.** It
     * left the field out when nobody had configured one, so each server used
     * its own default - the providers disagree about which voices exist, and
     * naming one that a server has never heard of is a refusal. The SDK makes
     * the field required, so something is always sent. Which voice that is
     * belongs to the model: it is a field on the model's own settings, beside
     * the name of the model it reads with, because a voice is a property of the
     * thing doing the reading and not of the installation running it. The name
     * below is only what a row created before that field existed falls back to.
     */
    fun speak(provider: ModelProvider, model: LlmModel, text: String, voice: String?): Spoken {
        val client = when (val ready = ready(provider)) {
            is Ready.No -> return Spoken.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }

        val params = SpeechCreateParams.builder()
            .model(model.modelId)
            .input(text)
            .responseFormat(SpeechCreateParams.ResponseFormat.MP3)
        params.voice(voice?.trim()?.ifBlank { null } ?: LAST_RESORT_VOICE)

        return clients.again { client.audio().speech().create(params.build()) }.use { answer ->
            Spoken.Audio(answer.body().readBytes())
        }
    }

    /** What reading aloud produced. */
    sealed interface Spoken {
        data class Audio(val bytes: ByteArray) : Spoken {
            override fun equals(other: Any?): Boolean = this === other
            override fun hashCode(): Int = System.identityHashCode(this)
        }

        data class Failed(val reason: String) : Spoken
    }

    /**
     * What was said in a recording.
     *
     * The multipart body this used to build by hand - boundary string and all -
     * is the SDK's business now. What is not the SDK's business is the name of
     * the part: handed raw bytes and nothing else it sends them as an ordinary
     * form field, and a Whisper server answers `Expected UploadFile, received
     * str` - which is what happened when this was first migrated and the
     * filename was left behind with the hand-written encoder.
     *
     * The name carries the extension, and the extension is how most of these
     * servers decide what they have been given, so it is [filename] as the
     * caller had it rather than something invented here.
     */
    fun transcribe(
        provider: ModelProvider,
        model: LlmModel,
        audio: ByteArray,
        filename: String,
        contentType: String,
    ): Heard {
        val client = when (val ready = ready(provider)) {
            is Ready.No -> return Heard.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }

        val recording = MultipartField.builder<InputStream>()
            .value(audio.inputStream())
            .filename(filename)
            .contentType(contentType)
            .build()

        val answer = clients.again { client.audio().transcriptions().create(
            TranscriptionCreateParams.builder().model(model.modelId).file(recording).build(),
        ) }
        return Heard.Words(answer.asTranscription().text())
    }

    /** What listening produced. */
    sealed interface Heard {
        data class Words(val text: String) : Heard
        data class Failed(val reason: String) : Heard
    }

    private sealed interface Ready {
        data class Yes(val client: com.openai.client.OpenAIClient) : Ready
        data class No(val reason: String) : Ready
    }

    private companion object {
        /** For a speech model saved before its own voice could be set. */
        const val LAST_RESORT_VOICE = "alloy"
    }

    private fun ready(provider: ModelProvider): Ready =
        when (val credential = probe.sdkCredential(provider)) {
            is ModelProviderProbe.SdkCredential.Failed -> Ready.No(credential.reason)
            is ModelProviderProbe.SdkCredential.Ready -> Ready.Yes(clients.clientFor(provider, credential.credential))
        }
}
