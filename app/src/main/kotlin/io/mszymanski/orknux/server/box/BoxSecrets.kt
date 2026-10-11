package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The workspace secrets a box session's commands were given, and the masking
 * that keeps them out of everything shown from it.
 *
 * A secret goes into a box as an environment variable so the model never sees
 * it - and a command that prints it (`env`, a verbose `curl`, a stack trace)
 * would undo that the moment its output came back. So everything read from a
 * session that holds secrets passes through [mask] on its way out: what a tool
 * answers the model, which is also what the session log and the chat stream
 * record; the box's event log and kept output as they are copied here; and the
 * log and output the session page reads live.
 *
 * The session row keeps which variables, by id and never by value, so masking
 * outlives a restart; the values themselves are read back from the variables
 * and held here as well, so a secret changed after it was used is still masked
 * in output printed before the change, for as long as this server runs.
 *
 * Masked as itself, base64 and URL-encoded, which is how a printed credential
 * usually appears. A value cut across two reads of the output is not caught,
 * and nor is one the command transformed some other way - masking keeps an
 * honest command honest, it does not contain a hostile one.
 */
@Service
class BoxSecrets(
    private val sessions: BoxSessionRepository,
    private val variables: WorkspaceVariableRepository,
) {

    /** Values handed to each box session in this process, by session row id. */
    private val used = ConcurrentHashMap<Long, MutableSet<String>>()

    /** Records that [row]'s commands were given these secrets: their ids on the row, their values here. */
    fun given(row: BoxSession, secrets: Map<Long, String>) {
        if (secrets.isEmpty()) return
        val id = requireNotNull(row.id)
        used.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }.addAll(secrets.values.filter(::maskable))
        val held = idsOf(row)
        val all = (held + secrets.keys).distinct()
        if (all.size != held.size) {
            row.secretVariableIds = all.joinToString("\n")
            sessions.save(row)
        }
    }

    /** [text] with every secret [row] was given replaced by `***`. */
    fun mask(row: BoxSession, text: String): String = maskWith(valuesOf(row), text)

    /** [text] with every secret any box session of this conversation was given replaced. */
    fun maskConversation(llmSessionId: Long, text: String): String {
        val values = sessions.findByLlmSessionIdOrderByCreatedAtDescIdDesc(llmSessionId)
            .filter { it.secretVariableIds != null || used.containsKey(it.id) }
            .flatMap(::valuesOf)
            .toSet()
        return maskWith(values, text)
    }

    /** Whether [row] has been given any secret. */
    fun holds(row: BoxSession): Boolean = row.secretVariableIds != null || used.containsKey(row.id)

    private fun valuesOf(row: BoxSession): Set<String> {
        val ids = idsOf(row)
        val remembered = row.id?.let { used[it] }.orEmpty()
        if (ids.isEmpty()) return remembered
        val current = variables.findAllById(ids).mapNotNull { it.value }.filter(::maskable)
        return remembered + current
    }

    private fun idsOf(row: BoxSession): List<Long> =
        row.secretVariableIds.orEmpty().lines().mapNotNull { it.trim().toLongOrNull() }

    private fun maskWith(values: Set<String>, text: String): String {
        if (values.isEmpty() || text.isEmpty()) return text
        // Longest first, so a secret that contains another is masked whole.
        val forms = values.flatMap(::formsOf).distinct().sortedByDescending { it.length }
        return forms.fold(text) { held, form -> held.replace(form, MASK) }
    }

    private fun formsOf(value: String): List<String> = listOf(
        value,
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8)),
        URLEncoder.encode(value, StandardCharsets.UTF_8),
        // As a JSON string carries it, which is how a tool's answer holds it.
        value.replace("\\", "\\\\").replace("\"", "\\\""),
    ).filter(::maskable)

    companion object {
        const val MASK = "***"

        /**
         * Shorter than this is not masked: "1" or "on" would star out half of
         * every listing and protect nothing a secret that short could.
         */
        private const val SHORTEST = 4

        private fun maskable(value: String) = value.length >= SHORTEST
    }
}
