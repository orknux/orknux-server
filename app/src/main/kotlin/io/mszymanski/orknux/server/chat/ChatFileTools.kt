package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.attachment.AttachmentAudio
import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.embedded.MidiCapability
import io.mszymanski.orknux.server.embedded.MidiRenderer
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Putting a file in front of the person a chat agent is answering, and playing
 * one to them.
 *
 * **`chat_attach_file`** files something a tool made - a key in the session's
 * store, a song `midi_render` rendered, a PDF - as an attachment of the answer
 * being written, where the chat draws it: a sound with a player, a MIDI file
 * with Play, anything else as a file to download. Before it a key could reach a
 * chat only as a link `save_artifact` made, which is a page somewhere else.
 *
 * **`audio_play`** plays a sound through the person's speakers now, which is
 * what a voice conversation is for: "play me what you wrote". Lent only to a
 * voice turn, because only the voice panel has a speaker queue to put it in -
 * offered anywhere else it would be a tool that succeeds and does nothing, which
 * is the one thing a model must not be offered. A MIDI file is rendered first,
 * as the chat's own Play does.
 *
 * Both are the chat's, lent per round like `chat_draw_picture`, because where a
 * file goes is the loop's knowledge and not the agent's.
 */
@Service
class ChatFileTools(
    private val mapper: ObjectMapper,
    private val scratch: LlmSessionStore,
    private val attachments: ChatAttachmentRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val renderer: MidiRenderer,
    private val audio: AttachmentAudio,
    private val chats: ChatService,
    private val sessions: ChatSessionRepository,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /** What a round is lent: attaching where files may be kept, and playing only in a voice turn. */
    fun specs(voice: Boolean): List<ToolSpec> =
        if (!settings.attachmentsEnabled()) emptyList() else listOf(ATTACHING) + (if (voice) listOf(PLAYING) else emptyList())

    fun handles(name: String): Boolean = name == ATTACH || name == PLAY

    fun run(chat: ChatSession, call: ToolCall, watch: RoundWatch, voice: Boolean): String {
        val asked = runCatching { mapper.readTree(call.arguments) }.getOrNull() ?: return refuse("That is not valid JSON.")
        if (!settings.attachmentsEnabled()) return refuse("Attachments are turned off on this installation, so a file has nowhere to be kept.")
        return when (call.name) {
            ATTACH -> attach(chat, asked, watch)
            PLAY -> if (voice) play(chat, asked, watch) else refuse("$PLAY plays only in a voice conversation, and this turn is not one.")
            else -> refuse("There is no tool called ${call.name}.")
        }
    }

    private fun attach(chat: ChatSession, asked: JsonNode, watch: RoundWatch): String {
        val key = text(asked, KEY) ?: return refuse("Give the $KEY of the file to attach.")
        val held = held(chat, key) ?: return refuse(nothingUnder(chat, key))
        val name = text(asked, NAME)?.substringAfterLast('/')?.substringAfterLast('\\') ?: key
        val filed = file(chat, name, held.bytes, AttachmentTypes.of(name, held.contentType), watch)
            ?: return refuse(tooLarge(name))
        return mapper.writeValueAsString(
            linkedMapOf(
                "attached" to filed.filename,
                "contentType" to filed.contentType,
                "note" to "The file is under your answer in this conversation, where the person can open, play or " +
                    "download it. Do not paste a link to it; say what it is.",
            ),
        )
    }

    private fun play(chat: ChatSession, asked: JsonNode, watch: RoundWatch): String {
        val given = text(asked, KEY)
        // A file the person attached is kept under attachment.<id>; played as that attachment rather than filed again.
        val attachmentId = text(asked, ATTACHMENT)?.toLongOrNull()
            ?: given?.removePrefix(io.mszymanski.orknux.server.attachment.ChatAttachmentShelf.KEY_PREFIX)
                ?.takeIf { given.startsWith(io.mszymanski.orknux.server.attachment.ChatAttachmentShelf.KEY_PREFIX) }?.toLongOrNull()
        val key = given
        val (url, filename, contentType) = when {
            attachmentId != null -> {
                val one = attachments.findByIdOrNull(attachmentId)?.takeIf { it.chatSessionId == chat.id }
                    ?: return refuse("This conversation has no attachment $attachmentId.")
                when {
                    audio.isMidi(one) -> when (val played = audio.of(one)) {
                        is AttachmentAudio.Played.Refused -> return refuse(played.reason)
                        is AttachmentAudio.Played.Ready -> Triple("/api/attachments/${one.id}/audio", played.filename, played.contentType)
                    }
                    audible(one.contentType) -> Triple("/api/attachments/${one.id}", one.filename, one.contentType)
                    else -> return refuse("\"${one.filename}\" is ${one.contentType}, not a sound or a MIDI file.")
                }
            }
            key != null -> {
                val held = held(chat, key) ?: return refuse(nothingUnder(chat, key))
                val type = AttachmentTypes.of(key, held.contentType)
                val (bytes, name, kind) = when {
                    type in MidiCapability.MIDI_TYPES -> when (val made = renderer.render(held.bytes, RENDERED)) {
                        is MidiRenderer.Rendered.Refused -> return refuse(made.reason)
                        is MidiRenderer.Rendered.Made -> Triple(made.bytes, key.substringBefore('.') + "." + made.extension, made.contentType)
                    }
                    audible(type) -> Triple(held.bytes, key, type)
                    else -> return refuse("\"$key\" holds $type, not a sound or a MIDI file.")
                }
                val filed = file(chat, name, bytes, kind, watch) ?: return refuse(tooLarge(name))
                Triple("/api/attachments/${filed.id}", filed.filename, filed.contentType)
            }
            else -> return refuse("Give the $KEY of a sound or a MIDI file in this conversation, or the $ATTACHMENT id of one attached to it.")
        }
        watch.played(url, filename, contentType)
        return mapper.writeValueAsString(
            linkedMapOf(
                "playing" to filename,
                "note" to "It is playing to the person now, before your answer is read aloud. You have not heard " +
                    "it, so say what it is rather than how it sounds; if they talk over it, it stops.",
            ),
        )
    }

    private data class Held(val bytes: ByteArray, val contentType: String?)

    /** The bytes under [key] in this chat's session, as the store's kind says to read them. */
    private fun held(chat: ChatSession, key: String): Held? {
        // Read again: a first turn opens the session after the round's chat row was read.
        val session = (chat.id?.let { sessions.findByIdOrNull(it) } ?: chat).llmSessionId ?: return null
        val held = scratch.content(session, key) ?: return null
        return Held(held.bytes(), held.kind?.contentType)
    }

    /** Nothing under [key], and the key to pass where it is the name of a file attached here. */
    private fun nothingUnder(chat: ChatSession, key: String): String {
        val session = (chat.id?.let { sessions.findByIdOrNull(it) } ?: chat).llmSessionId
        return scratch.nothingUnder(session, key, "Nothing in this conversation is kept under \"$key\".")
    }

    /** [bytes] as an attachment of the answer being written, told to whoever is watching; null where too large. */
    private fun file(chat: ChatSession, name: String, bytes: ByteArray, contentType: String, watch: RoundWatch): ChatAttachment? {
        if (bytes.size > settings.maxFileSizeMb() * 1024L * 1024L) return null
        val chatId = requireNotNull(chat.id)
        val saved = attachments.save(
            ChatAttachment(
                workspaceId = chat.workspaceId,
                chatSessionId = chatId,
                filename = name.takeLast(MOST_NAME),
                contentType = contentType,
                sizeBytes = bytes.size.toLong(),
                location = store.put(chat.workspaceId, name, bytes),
                uploadedBy = chat.userId,
            ).apply { messageIndex = chats.answerPlace(chatId) },
        )
        auditRecorder.record(chat.workspaceId, WorkspaceAuditCategory.CHAT, "An agent attached ${saved.filename} to a chat")
        watch.filed(requireNotNull(saved.id), saved.filename, saved.contentType, saved.sizeBytes)
        return saved
    }

    private fun tooLarge(name: String) = "\"$name\" is larger than the ${settings.maxFileSizeMb()} MB an attachment may be."

    private fun audible(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        return type.startsWith("audio/") && type in AttachmentDownloads.SHOWABLE
    }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).let { if (it.isNumber) it.asString() else it.takeIf { one -> one.isString }?.stringValue() }?.trim()?.ifEmpty { null }

    private fun refuse(why: String): String = mapper.writeValueAsString(mapOf("error" to why))

    companion object {
        const val ATTACH = "chat_attach_file"
        const val PLAY = "audio_play"
        const val KEY = "contentKey"
        const val NAME = "name"
        const val ATTACHMENT = "attachmentId"

        /** What a MIDI file is played as: what every browser's `<audio>` plays. */
        private const val RENDERED = "mp3"

        private const val MOST_NAME = 255

        val ATTACHING = ToolSpec(
            name = ATTACH,
            description = "Attaches a file a tool made to your answer in this conversation, where the person can " +
                "open, play or download it: a song or a sound you rendered, a MIDI file, a PDF, an archive - " +
                "anything kept under a $KEY. A sound gets a player and a MIDI file a Play button.",
            parameters = listOf(
                ToolParameterSpec(KEY, "The $KEY the tool that made the file answered.", required = true),
                ToolParameterSpec(NAME, "What to call the file, extension and all, like sunrise.mp3. The key where not given."),
            ),
            readOnly = true,
        )

        val PLAYING = ToolSpec(
            name = PLAY,
            description = "Plays a sound to the person now, through their speakers, in this voice conversation - " +
                "a song or melody you made, or a sound or MIDI file they attached - before your answer is read " +
                "aloud. A MIDI file is rendered to sound first. Give a $KEY a tool made, or the $ATTACHMENT of a " +
                "file in this conversation. It stops if they talk over it.",
            parameters = listOf(
                ToolParameterSpec(KEY, "The $KEY of a sound or a MIDI file in this conversation."),
                ToolParameterSpec(ATTACHMENT, "The id of a sound or MIDI file attached to this conversation, instead of a key."),
            ),
            readOnly = true,
        )
    }
}
