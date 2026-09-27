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

    fun descriptors(): List<ToolDescriptor> = listOf(ZIP, EXTRACT)

    fun handles(name: String): Boolean = name == ZIP_FILES || name == ZIP_EXTRACT

    /** The call, by which of the two it is. */
    fun run(name: String, arguments: String, sessionId: Long?): String =
        if (name == ZIP_EXTRACT) extract(arguments, sessionId) else run(arguments, sessionId)

    /**
     * An archive unpacked into scratchpads. Issue #566.
     *
     * A model handed a zip - an attachment, a report it made in an earlier
     * turn - could not open it, and rebuilt the pages to change one line. Each
     * file becomes a pad named by its path: text as text, so it can be read and
     * edited, and everything else as a pad holding bytes, which zip_files and
     * the upload tools take back as they are. A pad already there is left alone
     * unless asked to be replaced, and says so.
     */
    private fun extract(arguments: String, sessionId: Long?): String {
        if (sessionId == null) return refusal("There is no session here to keep scratchpads in.")
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val key = text(asked, KEY)?.trim()?.ifEmpty { null } ?: return refusal("Give the $KEY of the archive to open.")
        val bytes = read(asked, sessionId) ?: return refusal("Nothing in this session is kept under \"$key\".")
        val entries = unpacked(bytes)
            ?: return refusal(
                "\"$key\" is not a zip this can open: it is not an archive, or it holds more than $MOST_FILES " +
                    "files or ${MOST_BYTES / (1024 * 1024)} MB.",
            )
        /*
         * Every file must fit the session's file budget together, checked before
         * anything is written: the sweep would otherwise drop the first picture
         * to make room for the last, leaving a site with a hole in it. Issue #569.
         */
        val filesSize = entries.filter { (path, content) -> textTypeOf(path, content) == null }
            .values.sumOf { (it.size + 2) / 3 * 4L }
        val fileBudget = pads.fileBudgetBytes()
        if (filesSize > fileBudget) {
            return refusal(
                "The pictures and other files in \"$key\" come to ${filesSize / 1024} KB as scratchpads, over the " +
                    "${fileBudget / 1024} KB a session keeps in files. Pass the archive's key where it is needed " +
                    "instead, or ask for the file budget to be raised in Admin -> Settings -> Scratchpads.",
            )
        }
        val folder = text(asked, FOLDER)?.trim()?.trim('/')?.ifEmpty { null }
        val replace = text(asked, REPLACE)?.trim()?.lowercase() == "true"

        val made = mutableListOf<Map<String, Any?>>()
        val skipped = mutableListOf<String>()
        entries.forEach { (path, content) ->
            val name = if (folder == null) path else "$folder/$path"
            val type = textTypeOf(path, content)
            val (body, bytesType) = if (type != null) {
                String(content, Charsets.UTF_8) to null
            } else {
                Base64.getEncoder().encodeToString(content) to binaryTypeOf(path)
            }
            val held = pads.find(sessionId, name)
            val result = when {
                held == null -> pads.create(sessionId, name, "From $key", body, bytesType)
                replace && held.contentType == bytesType -> pads.write(sessionId, name, body)
                else -> {
                    skipped += name
                    return@forEach
                }
            }
            when (result) {
                is io.mszymanski.orknux.server.llm.ScratchpadResult.Ok ->
                    made += linkedMapOf("name" to name, "bytes" to content.size, "text" to (type != null))
                is io.mszymanski.orknux.server.llm.ScratchpadResult.No -> skipped += "$name (${result.why})"
            }
        }
        // The files are held to their own budget, and anything older that made way is said. Issue #569.
        val swept = pads.sweepFiles(sessionId)
        val answer = linkedMapOf<String, Any?>("extracted" to made)
        if (swept.isNotEmpty()) answer["removed"] = swept
        if (skipped.isNotEmpty()) {
            answer["notExtracted"] = skipped
            answer["note"] = "A scratchpad already there is kept; pass $REPLACE \"true\" to overwrite it."
        }
        return mapper.writeValueAsString(answer)
    }

    /** The text type a file's name says it is, or null for bytes. */
    private fun textTypeOf(path: String, content: ByteArray): String? {
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension in TEXT_EXTENSIONS) return extension
        if (extension in BINARY_EXTENSIONS) return null
        // A name that says nothing: text where the bytes are UTF-8 with no NUL in them.
        if (content.any { it == 0.toByte() }) return null
        return runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)); "txt"
        }.getOrNull()
    }

    private fun binaryTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

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
        val wanted = list(asked.path(FILES))
            ?: return refusal(
                "Say which files to put in. $FILES is a list of objects, each with \"$NAME\" and one of " +
                    "\"$PAD\", \"$KEY\" or \"$TEXT\", like this: $SHAPE",
            )
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
        scratch.put(sessionId, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(packed)), io.mszymanski.orknux.workflow.script.StoredKind("application/zip", true))

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

    /**
     * The list of files, however it arrived. Issue #502.
     *
     * A provider hands arguments over as JSON, and a model writing a list into
     * a field it is told is a list will sometimes write the list *as a string* -
     * `"[{\"name\":...}]"` rather than `[{"name":...}]`. It happened often
     * enough to be worth reading: the string is unambiguous, parsing it costs
     * nothing, and the alternative was a refusal saying "files is a list" to a
     * model that had just sent one and could see no difference.
     */
    private fun list(node: JsonNode): JsonNode? {
        val given = if (node.isTextual) {
            runCatching { mapper.readTree(node.stringValue()) }.getOrNull() ?: return null
        } else {
            node
        }
        return given.takeIf { it.isArray && !it.isEmpty }
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
            val stored = scratch.get(sessionId, key) ?: return null
            // What it is, where the store was told - no guessing. Issue #559.
            val kind = scratch.kindOf(sessionId, key)
            /*
             * What the store holds is a JSON value, so it is parsed before it is
             * anything else. Issue #499: this read the raw row, and since the
             * row is `"iVBORw0KGgo..."` with the quotes, the base64 never
             * decoded and the quoted string itself went into the archive - a
             * PNG that opens as text beginning with a quotation mark.
             *
             * The raw text is still the fallback, for a row written before the
             * store settled on JSON.
             */
            val held = runCatching { mapper.readTree(stored) }
                .getOrNull()
                ?.takeIf { it.isTextual }
                ?.stringValue()
                ?: stored
            /*
             * Then text or base64, with nothing saying which: read as base64
             * only where it decodes cleanly and is long enough to have been
             * bytes, since a short piece of text that happens to be valid
             * base64 - "report" is not, "data" is - would otherwise land in the
             * archive as four mangled bytes.
             */
            if (kind != null) {
                return if (kind.binary) runCatching { Base64.getDecoder().decode(held) }.getOrNull() else held.toByteArray()
            }
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

        /** Unpacking one into scratchpads. Issue #566. */
        const val ZIP_EXTRACT = "zip_extract"
        const val FOLDER = "folder"
        const val REPLACE = "replace"

        private val TEXT_EXTENSIONS = setOf(
            "html", "htm", "css", "js", "mjs", "ts", "json", "md", "markdown", "txt", "csv", "svg", "xml",
            "yml", "yaml", "kt", "java", "py", "sh", "sql", "log",
        )
        private val BINARY_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "pdf", "zip", "ico", "woff", "woff2")

        /**
         * Every file in an archive by its path, or null for one that is not a
         * zip or holds more than [MOST_FILES] files or [MOST_BYTES] - the same
         * bounds as what zip_files packs, so a key cannot unpack into more
         * than an archive made here could hold. Paths lose ./, ../ and leading
         * slashes. Shared with pdf_fromHtmlZip.
         */
        fun unpacked(bytes: ByteArray): Map<String, ByteArray>? = runCatching {
            val held = linkedMapOf<String, ByteArray>()
            var total = 0L
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (held.size >= MOST_FILES) return null
                    val read = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MOST_BYTES) return null
                        read.write(buffer, 0, n)
                    }
                    val path = normalised(entry.name)
                    if (path.isNotEmpty()) held[path] = read.toByteArray()
                }
            }
            held.takeIf { it.isNotEmpty() }
        }.getOrNull()

        /** A path inside an archive, with ./, ../ and leading slashes taken out. */
        fun normalised(path: String): String {
            val parts = ArrayDeque<String>()
            path.replace('\\', '/').split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            return parts.joinToString("/")
        }
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

        /**
         * What one call looks like, written out. Issue #502.
         *
         * In the description and in the refusal both, because a shape described
         * in a sentence is a shape somebody has to reconstruct, and the two
         * mistakes made here - the whole list written as a string, and a file
         * given its content instead of where to read it - are both the kind an
         * example prevents and prose does not.
         */
        const val SHAPE =
            """{"files":[{"name":"report.pdf","contentKey":"pdf.1lt2fm6"},""" +
                """{"name":"notes.md","scratchpad":"notes.md"}],"name":"report.zip"}"""

        val EXTRACT = ToolDescriptor(
            name = ZIP_EXTRACT,
            description = "Unpacks a zip into scratchpads, one per file, named by its path inside the archive " +
                "(index.html, images/orc.png) - so an archive somebody sent, or one you made earlier, can be " +
                "read and edited. Text files become text pads; pictures and other bytes become pads that hold " +
                "bytes, which zip_files and the upload tools take back as they are. Answers the pads made.",
            parameters = listOf(
                ToolParameter(name = KEY, description = "The archive's key.", required = true),
                ToolParameter(
                    name = FOLDER,
                    description = "A folder to put the pads under, like report/. Left out, the archive's own paths.",
                    required = false,
                ),
                ToolParameter(
                    name = REPLACE,
                    description = "\"true\" to overwrite scratchpads already there. Left out, they are kept.",
                    required = false,
                ),
            ),
        )

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
                    description = "The files, as a JSON list - a real list, not a list written out as a " +
                        "string. Each is an object with \"$NAME\" - what it is called inside the archive, " +
                        "like report.pdf - and one of \"$PAD\" (a scratchpad in this session), \"$KEY\" " +
                        "(a key something handed you) or \"$TEXT\" (short text written out here). At most " +
                        "$MOST_FILES files. A whole call looks like this: $SHAPE",
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
