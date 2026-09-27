package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * The picture a name stands for in this session: a scratchpad first, then a key.
 *
 * One lookup for every place a page may name a picture by what the session
 * calls it: the PDF writer, which puts it in, and since issue #545 an HTML page
 * kept or saved as a file, which cannot and says so.
 */
@Component
class SessionPictures(
    private val pads: SessionScratchpadService,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
) {

    fun find(named: String, sessionId: Long?): PageBlocks.Picture? {
        if (sessionId == null) return null
        pads.find(sessionId, named)?.let { pad ->
            val bytes = if (pad.contentType == null) {
                pad.content.toByteArray()
            } else {
                runCatching { Base64.getDecoder().decode(pad.content) }.getOrNull()
            }
            if (bytes != null) return PageBlocks.Picture(bytes, pad.contentType)
        }
        val held = scratch.get(sessionId, named) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(parsed(held)) }.getOrNull() ?: return null
        return PageBlocks.Picture(bytes, null)
    }

    /**
     * What the store holds is a JSON value, so it is parsed before it is
     * anything else - issue #499, where reading the raw row put the quotes
     * into the base64 and every picture came out as text.
     */
    private fun parsed(stored: String): String =
        runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: stored

    companion object {
        /**
         * What a model is told when an HTML page it is handing on names pictures
         * by key. Issue #545: a key means something only in this session, so
         * the page opens with those pictures broken anywhere else.
         */
        fun keyedWarning(keys: List<String>): String =
            "This page names " + keys.joinToString(", ") + " in an <img src>. That is a key in this " +
                "session, not an address, so wherever the page is opened those pictures are broken. Either " +
                "host each picture somewhere and put its web address in the src, or send the page and its " +
                "pictures together as one zip with zip_files - each picture under a file name like " +
                "chart.png, and the src changed to that name."

        /** Whether a file is a web page, by its name or by how it starts. */
        fun isHtml(name: String, content: String): Boolean {
            val lower = name.lowercase()
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return true
            val start = content.trimStart().take(15).lowercase()
            return start.startsWith("<!doctype html") || start.startsWith("<html")
        }
    }
}
