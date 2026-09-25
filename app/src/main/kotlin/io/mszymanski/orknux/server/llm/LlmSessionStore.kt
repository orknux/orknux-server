package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.workflow.script.SessionScratch
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
)

data class LlmSessionStoreKey(val sessionId: Long = 0, val name: String = "") : Serializable

interface LlmSessionStoreRepository : JpaRepository<LlmSessionStoreEntry, LlmSessionStoreKey> {

    fun findBySessionId(sessionId: Long): List<LlmSessionStoreEntry>

    fun countBySessionId(sessionId: Long): Long
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
class LlmSessionStore(private val entries: LlmSessionStoreRepository) : SessionScratch {

    @Transactional
    override fun put(sessionId: Long, key: String, json: String): String? {
        if (key.isBlank()) return "a key has to be a non-empty string"
        if (key.length > MOST_KEY_CHARS) {
            return "a key is at most $MOST_KEY_CHARS characters, and that one is ${key.length}"
        }
        if (json.length > MOST_VALUE_CHARS) {
            return "a value is at most ${MOST_VALUE_CHARS / 1024} KB of JSON, and that one is ${json.length / 1024} KB"
        }
        val held = entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key))
        if (held == null && entries.countBySessionId(sessionId) >= MOST_KEYS) {
            return "this session's store already holds $MOST_KEYS keys"
        }

        return runCatching {
            if (held == null) {
                entries.save(LlmSessionStoreEntry(sessionId, key, json))
            } else {
                held.value = json
                entries.save(held)
            }
            null
        }.getOrElse {
            // The one honest failure left is the session being gone, which the
            // foreign key is what notices.
            "this session no longer exists, so there is nothing to store into"
        }
    }

    @Transactional(readOnly = true)
    override fun get(sessionId: Long, key: String): String? =
        entries.findByIdOrNull(LlmSessionStoreKey(sessionId, key))?.value

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
        var room = (MOST_KEYS - entries.countBySessionId(into)).coerceAtLeast(0)
        var copied = 0
        entries.findBySessionId(from).forEach { held ->
            if (room == 0L) return@forEach
            if (entries.findByIdOrNull(LlmSessionStoreKey(into, held.name)) != null) return@forEach
            entries.save(LlmSessionStoreEntry(into, held.name, held.value))
            room -= 1
            copied += 1
        }
        return copied
    }

    private companion object {
        const val MOST_KEYS = 200
        const val MOST_KEY_CHARS = 200
        /**
         * As much as one key may hold: 8 MB of JSON, which is about 6 MB of
         * bytes.
         *
         * It was 256 KB, which is smaller than the things this store exists to
         * carry. A drawn picture is most of a megabyte of base64 and a PDF a
         * plugin just wrote is more; both were refused, so the tool that made
         * them had nothing to hand to the tool that uploads them - which is
         * the whole point of the store.
         *
         * Still a ceiling, because the column is a row in a table read whole:
         * what this stops is a plugin putting a video in a session, not a
         * plugin putting its own output there.
         */
        const val MOST_VALUE_CHARS = 8 * 1024 * 1024
    }
}
