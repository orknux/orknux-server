package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import org.springframework.stereotype.Component
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
        // Decoded as its kind says, or read from the attachment store, by the store's one rule.
        val held = scratch.content(sessionId, key) ?: return null
        return Held(held.bytes(), held.kind?.contentType)
    }

    /** The sentence for a name nothing is kept under, saying the key where it is an attached file's name. */
    fun nothingUnder(named: String, sessionId: Long?): String = scratch.nothingUnder(sessionId, named)
}
