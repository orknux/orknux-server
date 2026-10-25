package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.embedded.MidiCapability
import io.mszymanski.orknux.server.embedded.MidiRenderer
import org.springframework.stereotype.Service

/**
 * A MIDI file attached to a chat, as sound a browser can play.
 *
 * No browser plays MIDI - it is a score, not a recording - so the chat's Play on
 * one asks for this, and `audio_play` reaches it too when an agent plays an
 * attached `.mid` to somebody in a voice conversation. Rendered by
 * [MidiRenderer], the same path `midi_render` takes, as an MP3: the format every
 * browser's `<audio>` plays and the smallest of the three. Made once and kept
 * beside the file ([ChatAttachment.audioLocation]), because a second press is
 * the ordinary case and synthesising a minute of music is not free.
 */
@Service
class AttachmentAudio(
    private val attachments: ChatAttachmentRepository,
    private val store: AttachmentStore,
    private val renderer: MidiRenderer,
) {

    /** Sound ready to serve, or why there is none. */
    sealed interface Played {
        data class Ready(val filename: String, val contentType: String, val bytes: Long, val location: String) : Played
        data class Refused(val reason: String) : Played
    }

    fun isMidi(attachment: ChatAttachment): Boolean =
        attachment.contentType.substringBefore(';').trim().lowercase() in MidiCapability.MIDI_TYPES

    /** The rendering of [attachment], made now if it was not made before. */
    fun of(attachment: ChatAttachment): Played {
        if (!isMidi(attachment)) return Played.Refused("\"${attachment.filename}\" is not a MIDI file, and plays as it is.")
        val name = attachment.filename.substringBeforeLast('.').ifEmpty { "music" } + "." + FORMAT
        attachment.audioLocation?.takeIf { store.exists(it) }?.let { held ->
            val size = store.open(held).use { it.readAllBytes().size.toLong() }
            return Played.Ready(name, TYPE, size, held)
        }
        val midi = runCatching { store.open(attachment.location).use { it.readAllBytes() } }
            .getOrElse { return Played.Refused("\"${attachment.filename}\" is no longer kept on this server.") }
        return when (val made = renderer.render(midi, FORMAT)) {
            is MidiRenderer.Rendered.Refused -> Played.Refused(made.reason)
            is MidiRenderer.Rendered.Made -> {
                val location = store.put(attachment.workspaceId, name, made.bytes)
                attachment.audioLocation = location
                attachments.save(attachment)
                Played.Ready(name, made.contentType, made.bytes.size.toLong(), location)
            }
        }
    }

    private companion object {
        const val FORMAT = "mp3"
        const val TYPE = AttachmentTypes.MP3
    }
}

/** A file that cannot be played, said in a sentence: not MIDI, gone, or a render the server cannot make. */
class AttachmentNotPlayableException(reason: String) : RuntimeException(reason)
