package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A file somebody attached to a chat.
 *
 * The row is the record and the bytes are elsewhere: what is stored here is
 * where they went, so that moving to another kind of storage is a change in one
 * class rather than a migration of everything anybody has uploaded.
 *
 * The workspace is on the row rather than inferred from the chat, because it is
 * what decides who may read it — and because the bytes are filed by workspace,
 * which is the promise the storage makes.
 */
@Entity
@Table(name = "chat_attachment")
class ChatAttachment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The chat it belongs to; null while it is attached to a message not yet sent. */
    @Column(name = "chat_session_id")
    var chatSessionId: Long? = null,

    /** What it was called on the machine it came from. */
    @Column(nullable = false, length = 255)
    val filename: String,

    @Column(name = "content_type", nullable = false, length = 120)
    val contentType: String,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /**
     * Where the bytes are, as the storage that wrote them understands it.
     *
     * A path under the configured root for a filesystem, a key for anything
     * else. Never sent to a screen: it is the storage's business, and a path is
     * the sort of thing that invites somebody to ask for a different one.
     */
    @Column(name = "location", nullable = false, length = 1000)
    val location: String,

    @Column(name = "uploaded_at", nullable = false)
    val uploadedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "uploaded_by", nullable = false, length = 120)
    val uploadedBy: String = "",

    /**
     * Which message of the chat it came with, by place in the thread - the
     * person's message it was sent with, or the answer of the turn that filed
     * it. Null for a file not yet sent and for rows older than the column,
     * which are placed by when they were uploaded.
     */
    @Column(name = "message_index")
    var messageIndex: Int? = null,

    /**
     * Where a MIDI file is kept rendered as sound, once somebody has pressed
     * Play on it: a browser plays no MIDI, so the chat asks for this instead,
     * and it is made once. Null until then, and for every file that is not MIDI.
     */
    @Column(name = "audio_location", length = 1000)
    var audioLocation: String? = null,
)

interface ChatAttachmentRepository : JpaRepository<ChatAttachment, Long> {

    fun findByChatSessionIdOrderByUploadedAtAsc(chatSessionId: Long): List<ChatAttachment>
}

class AttachmentNotFoundException(val id: Long) : RuntimeException("No attachment with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class AttachmentsDisabledException :
    RuntimeException("Attachments are turned off for this installation")

/**
 * A file past the upload limit. Says the file's size, the limit and where the
 * limit is raised, because the person told no is seldom the one who can change
 * it, and needs to know whom to ask and for what.
 */
class AttachmentTooLargeException(val name: String, val sizeMb: Long, val limitMb: Long) :
    RuntimeException(
        "$name is $sizeMb MB; uploads are limited to $limitMb MB - an administrator can raise it in Admin Settings → Attachments",
    ),
    Refusal {

    override val arguments get() = mapOf("name" to name, "sizeMb" to sizeMb, "limitMb" to limitMb)
}

