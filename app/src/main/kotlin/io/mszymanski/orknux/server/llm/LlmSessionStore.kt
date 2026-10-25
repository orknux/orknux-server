package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.workflow.script.SessionScratch
import io.mszymanski.orknux.workflow.script.StoredKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.util.Base64

/**
 * One thing a session's store holds. The row's whole identity is which session
 * and which name, which is also the whole of the isolation: a lookup carries
 * its session id, and no API takes one from anywhere a plugin can reach.
 */
@Entity
@Table(name = "llm_session_store")
@IdClass(LlmSessionStoreKey::class)
class LlmSessionStoreEntry(
    @Id
    @Column(name = "session_id")
    val sessionId: Long = 0,

    @Id
    @Column(name = "name", length = 200)
    val name: String = "",

    @Column(name = "value", nullable = false, columnDefinition = "text")
    var value: String = "",

    /** What the value is, where whoever put it said. Issue #559. */
    @Column(name = "content_type")
    var contentType: String? = null,

    /** Whether the value is base64 bytes; null where nobody said. Issue #559. */
    @Column(name = "is_binary")
    var binary: Boolean? = null,

    /**
     * The chat attachment this key stands for, where it is one: the row then
     * holds no value of its own, and the bytes are read from the attachment
     * store when a tool asks. A file a person attached is not copied in, so
     * Session Value Size - a bound on what tools write - says nothing about it.
     */
    @Column(name = "attachment_id")
    var attachmentId: Long? = null,
)

data class LlmSessionStoreKey(val sessionId: Long = 0, val name: String = "") : Serializable

interface LlmSessionStoreRepository : JpaRepository<LlmSessionStoreEntry, LlmSessionStoreKey> {

    fun findBySessionId(sessionId: Long): List<LlmSessionStoreEntry>

    fun countBySessionId(sessionId: Long): Long

    /** The attachments a session's store stands for, without reading any value. */
    @org.springframework.data.jpa.repository.Query(
        "select e.attachmentId from LlmSessionStoreEntry e where e.sessionId = :sessionId and e.attachmentId is not null",
    )
    fun attachmentIdsOf(sessionId: Long): List<Long>

    /** The keys a session keeps an attachment under. */
    @org.springframework.data.jpa.repository.Query(
        "select e.name from LlmSessionStoreEntry e where e.sessionId = :sessionId and e.attachmentId = :attachmentId",
    )
    fun keysOf(sessionId: Long, attachmentId: Long): List<String>
}

/**
 * The server's half of `orknux.session.store` - see [SessionScratch] for what
 * the contract promises and why it is an interface.
 *
 * A table rather than a map, because a session is a table row: the store has
 * to live exactly as long as the session does, and rows that go with it by
 * foreign key do that without a sweeper, across restarts, and without this
 * class ever being told a session ended. The bounds are here rather than in
 * the sandbox because this is the side that pays for them.
 */
@Component
class LlmSessionStore(
    private val entries: LlmSessionStoreRepository,
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
    private val attachments: io.mszymanski.orknux.server.attachment.ChatAttachmentRepository,
    private val files: io.mszymanski.orknux.server.attachment.AttachmentStore,
    private val mapper: tools.jackson.databind.ObjectMapper,
) : SessionScratch {

    @Transactional
    override fun put(sessionId: Long, key: String, json: String): String? = put(sessionId, key, json, null)

    @Transactional(readOnly = true)
    override fun kindOf(sessionId: Long, key: String): StoredKind? =
        entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key))?.let { held ->
            held.binary?.let { StoredKind(held.contentType, it) }
        }

    @Transactional
    override fun put(sessionId: Long, key: String, json: String, kind: StoredKind?): String? {
        if (key.isBlank()) return "a key has to be a non-empty string"
        if (key.length > MOST_KEY_CHARS) {
            return "a key is at most $MOST_KEY_CHARS characters, and that one is ${key.length}"
        }
        val mostValue = settings.sessionValueMaxMb() * 1024 * 1024
        if (json.length > mostValue) {
            return "a value is at most ${mostValue / 1024} KB of JSON, and that one is ${json.length / 1024} KB " +
                "(an administrator sets this as Session Value Size)"
        }
        val held = entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key))
        val mostKeys = settings.sessionStoreMaxKeys()
        if (held == null && entries.countBySessionId(sessionId) >= mostKeys) {
            return "this session's store already holds $mostKeys keys"
        }

        return runCatching {
            if (held == null) {
                entries.save(LlmSessionStoreEntry(sessionId, key, json, kind?.contentType, kind?.binary))
            } else {
                held.value = json
                held.contentType = kind?.contentType
                held.binary = kind?.binary
                // Written over, it is the tool's value now and no longer the attachment.
                held.attachmentId = null
                entries.save(held)
            }
            null
        }.getOrElse {
            // The one honest failure left is the session being gone, which the
            // foreign key is what notices.
            "this session no longer exists, so there is nothing to store into"
        }
    }

    /**
     * The most bytes one binary value can be and still be kept: [put] counts the
     * JSON it is handed, which for bytes is a quoted base64 string, four
     * characters for every three bytes. A writer that streams bytes in stops
     * here rather than finding out from [put] after it has read them all.
     */
    fun mostBinaryBytes(): Long = (settings.sessionValueMaxMb() * 1024L * 1024L - 2) / 4 * 3

    /**
     * Holds a chat attachment under [key] by reference: no bytes are copied, so
     * a file is kept whatever its size - the upload limit has already said how
     * large one may be - and Session Value Size, which bounds what tools
     * write, does not apply. The ceiling on keys still does.
     *
     * @return null when it was kept, or a sentence about why it was not.
     */
    @Transactional
    fun putAttachment(sessionId: Long, key: String, attachmentId: Long, kind: StoredKind): String? {
        val held = entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key))
        val mostKeys = settings.sessionStoreMaxKeys()
        if (held == null && entries.countBySessionId(sessionId) >= mostKeys) {
            return "this session's store already holds $mostKeys keys"
        }
        return runCatching {
            val entry = held ?: LlmSessionStoreEntry(sessionId, key)
            entry.value = ""
            entry.contentType = kind.contentType
            entry.binary = kind.binary
            entry.attachmentId = attachmentId
            entries.save(entry)
            null
        }.getOrElse { "this session no longer exists, so there is nothing to store into" }
    }

    /**
     * What [key] holds, as JSON - for an attachment, made from its bytes as a
     * value a tool wrote would have been, so every reader that reads JSON
     * reads one too. A reader of bytes asks [content] instead, which does not
     * build the base64 at all.
     */
    @Transactional(readOnly = true)
    override fun get(sessionId: Long, key: String): String? {
        val held = entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key)) ?: return null
        if (held.attachmentId == null) return held.value
        val content = attachmentContent(held) ?: return null
        val bytes = content.bytes()
        return if (held.binary == false) {
            mapper.writeValueAsString(bytes.decodeToString())
        } else {
            "\"" + Base64.getEncoder().encodeToString(bytes) + "\""
        }
    }

    /** Whether anything is kept under [key], without reading it. */
    @Transactional(readOnly = true)
    fun has(sessionId: Long, key: String): Boolean = entries.existsById(LlmSessionStoreKey(sessionId, key))

    /**
     * What [key] holds, as bytes - the one place that knows where they are.
     *
     * A value a tool wrote is decoded as its [StoredKind] says: a text type
     * holds the text, anything else base64; a value from before kinds were
     * recorded is base64 where it decodes and its text otherwise. An
     * attachment is opened from the attachment store, with the file it lies in
     * where the storage has one, so nothing is copied to say which key it is
     * or how large. Null where nothing is kept, or the attachment is gone.
     */
    @Transactional(readOnly = true)
    fun content(sessionId: Long, key: String): StoredContent? {
        val held = entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key)) ?: return null
        if (held.attachmentId != null) return attachmentContent(held)
        val kind = held.binary?.let { StoredKind(held.contentType, it) }
        val value = runCatching { mapper.readTree(held.value) }.getOrNull()?.takeIf { it.isString }?.stringValue() ?: held.value
        val bytes = when (held.binary) {
            true -> runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull() ?: return null
            false -> value.toByteArray(Charsets.UTF_8)
            null -> runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()?.takeIf { value.trim().isNotEmpty() }
                ?: value.toByteArray(Charsets.UTF_8)
        }
        return StoredContent.of(bytes, kind)
    }

    private fun attachmentContent(held: LlmSessionStoreEntry): StoredContent? {
        val attachment = held.attachmentId?.let { attachments.findByIdOrNull(it) } ?: return null
        if (!files.exists(attachment.location)) return null
        return StoredContent(
            kind = StoredKind(held.contentType ?: attachment.contentType, held.binary ?: true),
            size = attachment.sizeBytes,
            path = files.pathOf(attachment.location),
            identity = "attachment:${attachment.id}",
        ) { files.open(attachment.location) }
    }

    /**
     * The key of the file attached to this session that is called [name], where
     * one is - for the refusal of a tool handed a file's name instead of its
     * key, so it can say which key to pass. Session 911: told only that nothing
     * was kept under "ZE2601f.pdf", an agent went looking for the file with
     * `find /` on every machine it could reach.
     */
    @Transactional(readOnly = true)
    fun attachmentKeyNamed(sessionId: Long?, name: String): String? {
        if (sessionId == null) return null
        val wanted = name.trim().substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotEmpty() } ?: return null
        val ids = entries.attachmentIdsOf(sessionId).takeIf { it.isNotEmpty() } ?: return null
        val match = attachments.findAllById(ids).firstOrNull { it.filename.equals(wanted, ignoreCase = true) } ?: return null
        return entries.keysOf(sessionId, requireNotNull(match.id)).firstOrNull()
    }

    /**
     * The sentence for a key nothing is kept under: [io.mszymanski.orknux.server.embedded.PictureAddresses.nothingUnder]'s,
     * and, where the key given is the name of a file attached to this session,
     * which key to pass instead.
     */
    fun nothingUnder(sessionId: Long?, key: String, plain: String = "Nothing in this session is kept under \"$key\"."): String {
        val said = io.mszymanski.orknux.server.embedded.PictureAddresses.nothingUnder(key, plain)
        val instead = attachmentKeyNamed(sessionId, key) ?: return said
        return "$said \"${key.trim()}\" is the name of a file attached to this conversation, and its key is $instead: pass that."
    }

    @Transactional
    override fun remove(sessionId: Long, key: String) {
        // Idempotent: a key that holds nothing is left as it was, so a caller
        // clearing something it may or may not have set need not check first.
        entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key))?.let { entries.delete(it) }
    }

    /**
     * Everything one session keeps, copied into another, where the other does
     * not already hold that key.
     *
     * A subagent has a session of its own, so a key a tool answered in its
     * turn - a picture it drew, a file it read - named something the asker
     * could not reach: the asker's tools read the asker's store. Copied up
     * when the subagent's turn ends, so every key its answer names works in
     * the conversation that asked. The asker's own keys win, and the ceiling
     * on keys is the ceiling: what does not fit is left where it was. Answers
     * how many were copied. Issue #393.
     */
    @Transactional
    fun copy(from: Long, into: Long): Int {
        if (from == into) return 0
        var room = (settings.sessionStoreMaxKeys() - entries.countBySessionId(into)).coerceAtLeast(0)
        var copied = 0
        entries.findBySessionId(from).forEach { held ->
            if (room == 0L) return@forEach
            if (entries.findByIdOrNull(LlmSessionStoreKey(into, held.name)) != null) return@forEach
            entries.save(LlmSessionStoreEntry(into, held.name, held.value, held.contentType, held.binary, held.attachmentId))
            room -= 1
            copied += 1
        }
        return copied
    }

    private companion object {
        /*
         * How big a value and how many keys are Admin settings now - Session
         * Value Size and Session Store Keys. The value size began at 256 KB,
         * smaller than the pictures and PDFs this store exists to carry, then
         * 8 MB, smaller than an exported project. Still a ceiling, because the
         * column is a row in a table read whole.
         */
        const val MOST_KEY_CHARS = 200
    }
}
