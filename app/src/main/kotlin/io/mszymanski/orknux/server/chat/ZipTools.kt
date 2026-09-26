package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.memory.ToolDescriptor
import io.mszymanski.orknux.server.memory.ToolParameter
import io.mszymanski.orknux.server.llm.SessionScratchpadService
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Putting several files into one, for an agent that made several. Issue #467.
 *
 * An agent that has written four scratchpads and drawn two pictures has six
 * things and one message to put them in. Slack takes one file well and six
 * badly; a person asking for "the report" wants one attachment. Until this, the
 * only way to answer that was to send six messages or to paste the text of each
 * into the reply - which is the output cap and a wall of characters, both.
 *
 * **What goes in is what the session already holds.** Each file names a
 * scratchpad, or a key in the session store - which is what `save_artifact`,
 * `scratchpad_keep`, a drawn picture and a subagent's answer all hand back. So
 * nothing is retyped into the call: the whole point is that the bytes never go
 * through the model. Text may still be given outright for a small file, because
 * refusing a two-line README would send somebody back to a scratchpad for no
 * reason.
 *
 * **What comes out is a key, never the bytes.** A zip is binary and base64 of a
 * megabyte is a megabyte and a half of characters, none of which a model can
 * read - so the archive is put in the session store and its key is the answer,
 * for whatever sends, uploads or saves a file.
 */
@Service
class ZipTools(
    private val pads: SessionScratchpadService,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
) {

    fun descriptors(): List<ToolDescriptor> = listOf(ZIP)

    fun handles(name: String): Boolean = name == ZIP_FILES

    /**
     * One archive from the files named, put in the session store.
     *
     * Every entry is resolved before anything is written, so a call that names
     * one missing file is refused whole rather than producing an archive with a
     * hole in it - an agent that then sent it would be sending something wrong,
     * quietly, which is worse than being told.
     */
    fun run(arguments: String, sessionId: Long?): String {
        if (sessionId == null) return refusal("There is no session here to read files from or write one into.")

        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val wanted = asked.path(FILES).takeIf { it.isArray && !it.isEmpty }
            ?: return refusal("Say which files to put in: $FILES is a list, each with a $NAME and where to read it.")
        if (wanted.size() > MOST_FILES) {
            return refusal("That is ${wanted.size()} files, and at most $MOST_FILES go in one archive.")
        }

        val entries = mutableListOf<Pair<String, ByteArray>>()
        var total = 0L
        wanted.forEach { one ->
            val name = text(one, NAME)?.trim().orEmpty()
            if (name.isEmpty()) return refusal("Every file needs a $NAME: what it is called inside the archive.")
            if (name.startsWith("/") || name.contains("..")) {
                return refusal("\"$name\" is not a name inside an archive: no leading slash and no \"..\".")
            }

            val bytes = read(one, sessionId) ?: return refusal(
                "Nothing was found for \"$name\". Give a $PAD this session has, a $KEY something handed back, " +
                    "or the $TEXT itself.",
            )
            total += bytes.size
            if (total > MOST_BYTES) {
                return refusal("Those files come to more than ${MOST_BYTES / (1024 * 1024)} MB, which is too much " +
                    "for one archive. Send them in two.")
            }
            entries += name to bytes
        }

        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val packed = archive.toByteArray()
        val named = text(asked, NAME)?.trim()?.ifEmpty { null } ?: "files.zip"
        val key = text(asked, KEY)?.trim()?.ifEmpty { null } ?: named
        /*
         * JSON, not the bare base64. Issue #493: every key in this store holds a
         * JSON value - `save_artifact`, a kept pad, a drawn picture all write
         * `writeValueAsString(content)` - and every reader parses it. Writing
         * the raw string put a zip's own header in there, so Slack's upload
         * answered "Unexpected token U in JSON at position 0": UEsDBBQ is what
         * a zip looks like in base64.
         */
        scratch.put(sessionId, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(packed)))

        return mapper.writeValueAsString(
            linkedMapOf(
                "zipped" to entries.size,
                "name" to named,
                "contentKey" to key,
                "bytes" to packed.size,
                "base64" to true,
                "note" to "Pass contentKey to whatever sends, uploads or saves a file. The bytes are not text.",
            ),
        )
    }

    /** One entry's content: a pad, a key this session holds, or text given outright. */
    private fun read(one: JsonNode, sessionId: Long): ByteArray? {
        text(one, PAD)?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            val pad = pads.find(sessionId, name) ?: return null
            /*
             * A pad that holds a file holds its base64, so it is decoded on the
             * way in - which is what makes a report and its pictures one call.
             * Issue #490.
             */
            return if (pad.contentType == null) {
                pad.content.toByteArray()
            } else {
                runCatching { Base64.getDecoder().decode(pad.content) }.getOrNull()
            }
        }
        text(one, KEY)?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
            val held = scratch.get(sessionId, key) ?: return null
            /*
             * A key holds text or base64 and nothing says which, so this reads
             * it as base64 only where it decodes cleanly and is long enough to
             * have been bytes. A short piece of text that happens to be valid
             * base64 - "report" is not, "data" is - would otherwise be written
             * into the archive as four mangled bytes.
             */
            return runCatching { Base64.getDecoder().decode(held) }
                .getOrNull()
                ?.takeIf { held.length >= SHORTEST_BASE64 }
                ?: held.toByteArray()
        }
        return text(one, TEXT)?.toByteArray()
    }

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val ZIP_FILES = "zip_files"
        const val FILES = "files"
        const val NAME = "name"
        const val PAD = "scratchpad"
        const val KEY = "contentKey"
        const val TEXT = "text"

        /** Enough for a report and its pictures; past this it is a folder, not an attachment. */
        const val MOST_FILES = 50

        /** What one archive may come to before it is somebody's disk rather than a message. */
        const val MOST_BYTES = 25L * 1024 * 1024

        /** Below this, a string that decodes as base64 is far likelier to be short text. */
        const val SHORTEST_BASE64 = 32

        val ZIP = ToolDescriptor(
            name = ZIP_FILES,
            description = "Puts several files into one zip and answers with a key for it - for sending, " +
                "uploading or saving one attachment instead of six. Each file says what it is called inside " +
                "the archive and where to read it: a scratchpad in this session, a contentKey something " +
                "handed you - a saved artifact, a kept scratchpad, a picture, a subagent's answer - or short " +
                "text given outright. Nothing is retyped through you: pass the key or the pad name, never " +
                "the file's content. The answer is a contentKey for the archive itself, which is binary, so " +
                "pass that key on rather than trying to read it.",
            parameters = listOf(
                ToolParameter(
                    name = FILES,
                    description = "The files, as a JSON list. Each is an object with \"$NAME\" - what it is " +
                        "called inside the archive, like report.pdf - and one of \"$PAD\" (a scratchpad in " +
                        "this session), \"$KEY\" (a key something handed you) or \"$TEXT\" (short text " +
                        "written out here). At most $MOST_FILES files.",
                    required = true,
                ),
                ToolParameter(
                    name = NAME,
                    description = "What to call the archive, like report.zip. Left out, it is files.zip.",
                    required = false,
                ),
                ToolParameter(
                    name = KEY,
                    description = "The key to keep the archive under in this session. Left out, the " +
                        "archive's own name is used.",
                    required = false,
                ),
            ),
        )
    }
}
