package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * The bytes of a file a session holds under a name: a scratchpad of that name
 * first, then a key in the session's store - an attachment, a drawing, an
 * answer something kept - the order `pdf_read` has always looked in.
 *
 * A value says what it is ([io.mszymanski.orknux.workflow.script.StoredKind]):
 * text types hold the text, everything else base64. Read accordingly, never
 * guessed from the value.
 */
@Component
class StoredFiles(
    private val pads: SessionScratchpadService,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
) {

    /** What a key held, with its content type where one was said. */
    data class Held(val bytes: ByteArray, val contentType: String?)

    fun find(named: String?, sessionId: Long?): Held? {
        val key = named?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (sessionId == null) return null
        pads.find(sessionId, key)?.let { pad ->
            val bytes = if (pad.contentType == null) pad.content.toByteArray(Charsets.UTF_8)
            else runCatching { Base64.getDecoder().decode(pad.content.trim()) }.getOrNull() ?: return null
            return Held(bytes, pad.contentType)
        }
        val stored = scratch.get(sessionId, key) ?: return null
        val kind = scratch.kindOf(sessionId, key)
        val value = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isString }?.stringValue() ?: stored
        val bytes = when (kind?.binary) {
            true -> runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull() ?: return null
            false -> value.toByteArray(Charsets.UTF_8)
            // A value from before kinds were written: base64 where it decodes, its text otherwise.
            null -> runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()?.takeIf { value.trim().isNotEmpty() }
                ?: value.toByteArray(Charsets.UTF_8)
        }
        return Held(bytes, kind?.contentType)
    }
}
