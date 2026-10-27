package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretColumns
import io.mszymanski.orknux.server.workspace.AuditRedaction
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Every credential this installation holds, by value, for taking out of text
 * that is about to leave the workspace it was written in.
 *
 * What an admin workspace reads of another workspace - a conversation, a run's
 * steps, a chat - is what happened there, and what happened can include a
 * secret: an agent granted a variable catalog reads the value to do its work,
 * and a step can print what a function was handed. Those logs already pass
 * [AuditRedaction.redactObvious], which catches a secret by its shape. This
 * catches it by its value, whatever its shape, because the value is known: it
 * is in a column [SecretColumns] can name.
 *
 * Read whole, on every call, rather than per workspace and cached. A secret
 * copied from one workspace into another is still a secret, a cache is a
 * secret changed and not masked, and the columns are few and short.
 */
@Service
class KnownSecrets(
    private val columns: SecretColumns,
    private val jdbc: JdbcTemplate,
    private val cipher: SecretCipher,
    private val mapper: ObjectMapper,
) {

    /** Every credential value held, decrypted, long enough to mask without masking ordinary words. */
    fun values(): Set<String> = columns.all
        .filterNot { it.table in NOT_CREDENTIALS }
        .flatMap { column ->
            val where = ONLY_WHERE[column.table]?.let { " and $it" }.orEmpty()
            runCatching {
                jdbc.queryForList(
                    "select ${column.column} from ${column.table} where ${column.column} is not null$where",
                    String::class.java,
                )
            }.onFailure { log.warn("Could not read {} to mask its values", column, it) }
                .getOrDefault(emptyList())
                .mapNotNull { stored -> runCatching { cipher.decrypt(stored) }.getOrNull() }
        }
        .map { it.trim() }
        .filter { it.length in SHORTEST..LONGEST }
        .toSet()

    /**
     * [text] with every known credential replaced by [MARK], and then with
     * anything shaped like one replaced too. Longest first, so a secret that
     * contains another is masked whole rather than leaving its remainder.
     */
    fun masked(text: String): String {
        var held = text
        values().sortedByDescending { it.length }.forEach { secret ->
            held = held.replace(secret, MARK)
            // And as it reads inside a JSON string, where a quote or a backslash is escaped.
            val escaped = mapper.writeValueAsString(secret).removeSurrounding("\"")
            if (escaped != secret) held = held.replace(escaped, MARK)
        }
        return AuditRedaction.redactObvious(held)
    }

    companion object {
        private val log = LoggerFactory.getLogger(KnownSecrets::class.java)

        /** What stands where a secret was. */
        const val MARK = "[secret]"

        /** Shorter than this is a word as often as a key, and masking it would mask the word everywhere. */
        private const val SHORTEST = 6

        /** Longer than this is a document, not a credential: a key is a line, a PEM block a few dozen. */
        private const val LONGEST = 16_384

        /**
         * Encrypted, and not a credential: a session's prompt is the
         * conversation itself, sealed at rest because of what people say in
         * one, and decrypting every one of them on every read would be the
         * whole history read to mask nothing.
         */
        private val NOT_CREDENTIALS = setOf("llm_session_prompt")

        /** A variable's value is encrypted whatever it is; only a secret one is a credential. */
        private val ONLY_WHERE = mapOf("workspace_variable" to "kind = 'SECRET'")
    }
}
