package io.mszymanski.orknux.connector.model

/**
 * One sound sent with a turn: the bytes as base64, and the word `input_audio`
 * names its format with.
 *
 * Bare base64 rather than a `data:` URL, because that is the part's shape in
 * OpenAI's API (`input_audio.data`), which llama.cpp's server decodes as given
 * and vLLM turns into `data:audio/<format>;base64,<data>` itself.
 */
data class AudioClip(val base64: String, val format: String)

/**
 * Whether a sound may be put in front of a model, in which formats, and the
 * sentence saying why not.
 *
 * What each server decodes, from its own documentation or source:
 *
 * - **OpenAI** (api.openai.com, and an Azure host registered under the plain
 *   type): `input_audio.format` is `wav` or `mp3` - the API reference's
 *   "Audio content part", and the only two values openai-java's
 *   `ChatCompletionContentPartInputAudio.InputAudio.Format` knows.
 * - **llama.cpp's server**: `input_audio` hands `data` to `handle_media`
 *   without reading `format` ("don't need to validate format, it's redundant",
 *   tools/server/server-common.cpp); mtmd then recognises the bytes by their
 *   magic - `RIFF....WAVE`, `ID3` or an MPEG frame sync, `fLaC` - and decodes
 *   them with miniaudio (`audio_helpers::is_audio_file`,
 *   `decode_audio_from_buf` in tools/mtmd/mtmd-helper.cpp). wav, mp3 and flac.
 * - **vLLM**: `parse_input_audio` builds `data:audio/{format};base64,{data}`
 *   and loads it with librosa (vllm/entrypoints/chat_utils.py, docs.vllm.ai
 *   API reference for 0.10; the multimodal
 *   example's "Any format supported by librosa is supported"), which reads
 *   flac through libsndfile. wav, mp3 and flac.
 *
 * So flac goes as itself to a llama.cpp provider and to an OpenAI-shaped one
 * off OpenAI's host - where vLLM and llama.cpp are registered - and is refused
 * on OpenAI's own, which documents neither flac nor any way to say it. It is
 * refused rather than converted because there is no maintained pure-Java FLAC
 * decoder to convert it with: the JDK's `javax.sound.sampled` reads no FLAC,
 * jflac's last release is 1.5.2 of 2017, the jse-spi-flac fork's source
 * repository is gone, and FFmpeg through JavaCV is a native library per
 * platform for one file type. A person with a flac and OpenAI is told in a
 * sentence, before anything is sent.
 */
object AudioInput {

    const val MP3 = "mp3"
    const val WAV = "wav"
    const val FLAC = "flac"

    /** What OpenAI's API documents; everything else is a server that reads flac too. */
    private val OPENAI_FORMATS = listOf(WAV, MP3)
    private val SHAPED_FORMATS = listOf(WAV, MP3, FLAC)

    /**
     * The `input_audio.format` word for a content type, where it is a sound a
     * model may be sent; null for anything else - a MIDI file most of all,
     * which is notes rather than sound and is read by a tool instead.
     */
    fun formatOf(contentType: String): String? = when (contentType.substringBefore(';').trim().lowercase()) {
        "audio/mpeg", "audio/mp3" -> MP3
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> WAV
        "audio/flac", "audio/x-flac" -> FLAC
        else -> null
    }

    /** Whether [provider] has an audio part at all, for a chat model. */
    fun carried(provider: ModelProvider, model: LlmModel): Boolean =
        model.kind == ModelKind.CHAT && ChatParameters.takes(provider, ChatParameters.TAKES_AUDIO)

    /** The formats [provider]'s server decodes, in `input_audio.format`'s words. */
    fun formats(provider: ModelProvider): List<String> =
        if (ChatParameters.isOpenAiItself(provider)) OPENAI_FORMATS else SHAPED_FORMATS

    /** Null where [model] may be sent [clips]; otherwise the sentence a person reads. */
    fun refusal(provider: ModelProvider, model: LlmModel, clips: List<AudioClip>): String? = when {
        !ChatParameters.takesAudio(provider, model) ->
            if (carried(provider, model)) notTaken(model.name) else notCarried(model.name, provider.name)
        else -> clips.firstOrNull { it.format !in formats(provider) }?.let { formatNotTaken(it.format, provider.name, formats(provider)) }
    }

    fun notTaken(model: String): String =
        "$model does not take audio. Switch on Takes audio on its page, or choose a model that does."

    fun notCarried(model: String, provider: String): String =
        "$model is reached through $provider, whose API takes no audio. Choose a model on a provider that does."

    fun formatNotTaken(format: String, provider: String, formats: List<String>): String =
        "$provider takes ${formats.joinToString(" and ")} audio, not $format. Send it as one of those, or choose a model on a server that reads $format."
}
