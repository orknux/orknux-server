package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.AudioInput
import io.mszymanski.orknux.connector.model.ChatParameters
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Whether the sounds on a chat message may go to the model that will answer it.
 *
 * [ChatVideos]' twin, asked at the same moment for the same reason: before the
 * message is written down, so a refusal is a sentence and not a turn that half
 * happened. One question more than a video has - whether the model's server
 * decodes the file's format, which is where flac sent to OpenAI's own API is
 * refused (see [AudioInput] for which server reads what). A MIDI file is not a
 * sound here: it is read by `midi_read`, never sent.
 */
@Service
class ChatAudios(
    private val chats: ChatService,
    private val models: LlmModelRepository,
    private val providers: ModelProviderRepository,
    private val tuning: Tuning,
) {

    /**
     * Throws where [attached] holds a sound [session]'s model may not be sent,
     * one in a format its server does not decode, or one over the limit. Says
     * nothing where there is no sound, and nothing where there is no model.
     */
    fun refuseUnheard(session: ChatSession, attached: List<ChatAttachment>) {
        val sounds = attached.filter { AudioInput.formatOf(it.contentType) != null }
        if (sounds.isEmpty()) return
        val model = chats.modelAnswering(session)?.let { models.findByIdOrNull(it) } ?: return
        val provider = providers.findByIdOrNull(model.providerId) ?: return

        if (!ChatParameters.takesAudio(provider, model)) {
            if (AudioInput.carried(provider, model)) throw ChatAudioNotTakenException(model.name)
            throw ChatAudioNotCarriedException(model.name, provider.name)
        }

        val formats = AudioInput.formats(provider)
        sounds.forEach { sound ->
            val format = requireNotNull(AudioInput.formatOf(sound.contentType))
            if (format !in formats) throw ChatAudioFormatNotTakenException(format, provider.name, formats.joinToString(", "))
        }

        val limit = tuning[Tunable.CHAT_AUDIO_MAX_MB]
        sounds.firstOrNull { it.sizeBytes > limit * MEGABYTE }?.let { sound ->
            throw ChatAudioTooLargeException(sound.filename, (sound.sizeBytes + MEGABYTE - 1) / MEGABYTE, limit)
        }
    }

    private companion object {
        const val MEGABYTE = 1024L * 1024L
    }
}
