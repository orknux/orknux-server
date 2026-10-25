package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO

/**
 * A file read into a document: the editor's Import. PDF, Markdown (alone, or
 * the zip Export writes when a document has pictures), ODT, DOCX, HTML and plain
 * text, each read by the library the server already holds for it - PDFBox,
 * commonmark-java, the ODF Toolkit, Apache POI, jsoup - and walked into the
 * editor's vocabulary.
 *
 * **Nothing is written to the document here.** What comes back is a tree the
 * editor puts on the page itself, in place of what is there or at the caret,
 * so an import is an unsaved change like any other: one step Undo goes back
 * over, and Discard throws away. A silent overwrite on the server would be a
 * change nobody could take back, and one the editor's next Save would have to
 * be refused over.
 *
 * The pictures a file carries are kept the way a pasted picture is - each a
 * Design picture of the workspace, through [DesignImageAPI.importDesignImage],
 * so the same checks apply and each one is in the audit log - and the tree
 * names them by artifact id. One the installation will not keep is left out
 * and counted, rather than refusing the words around it. A picture a file only
 * links to on the web is not fetched; it stays a link.
 *
 * Its own controller rather than more of [DocumentAPI], as the files are
 * [DocumentFilesAPI]'s: the reading is the work here, and the document is only
 * looked up through [DocumentAPI.find], which checks who may see it.
 */
@Controller
class DocumentImports(
    @param:Lazy private val api: DocumentAPI,
    @param:Lazy private val pictures: DesignImageAPI,
    @param:Lazy private val artifacts: WorkspaceArtifactAPI,
    private val audit: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [file] read into a document's vocabulary, for the editor to put on the
     * page. Not transactional: each picture is kept in a transaction of its own,
     * and one the installation refuses must not take the others with it.
     *
     * @param file the file, base64, or as a data URL.
     * @param filename what it is called, which says what it is.
     */
    @MutationMapping
    fun importDocumentFile(@Argument id: Long, @Argument filename: String, @Argument file: String): DocumentImportView {
        val held = api.find(id)
        val name = filename.trim().substringAfterLast('/').substringAfterLast('\\').ifEmpty { "file" }.take(255)
        val bytes = runCatching { Base64.getDecoder().decode(file.substringAfter("base64,").trim()) }.getOrNull()
            ?: throw DocumentImportUnreadableException(name, "it is not base64")
        val most = tuning[Tunable.DOCUMENT_IMPORT_MB]
        if (bytes.size > most.toLong() * MB) throw DocumentImportTooLargeException(name, (bytes.size + MB - 1) / MB, most)
        val format = DocumentImportFormat.of(name, bytes) ?: throw DocumentImportFormatUnknownException(name)
        val keeper = Keeper(held.workspaceId, tuning[Tunable.DOCUMENT_IMPORT_PICTURES_MOST])
        val read = try {
            read(format, bytes, keeper)
        } catch (refused: DocumentImportUnreadableException) {
            throw refused
        } catch (failed: Exception) {
            log.info("Could not read {} as {}: {}", name, format, failed.toString())
            throw DocumentImportUnreadableException(name, failed.message?.take(300) ?: failed.javaClass.simpleName)
        }
        val tree = api.content.checked(withoutStrangers(read, held.workspaceId), held.workspaceId)
        val written = api.content.write(tree)
        val longest = tuning[Tunable.DOCUMENT_CHARACTERS_MOST]
        if (written.length > longest) throw DocumentTooLargeException(written.length, longest)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Document ${held.name} imported from $name")
        return DocumentImportView(content = written, format = format.said, pictures = keeper.kept, picturesLeftOut = keeper.leftOut)
    }

    /** The file as a document, before it is checked. */
    fun read(format: DocumentImportFormat, bytes: ByteArray, keep: Keeper): ObjectNode = when (format) {
        DocumentImportFormat.PDF -> DocumentPdfReader(mapper, tuning).read(bytes, keep::picture)
        DocumentImportFormat.MARKDOWN -> DocumentMarkdown(mapper).document(text(bytes))
        DocumentImportFormat.MARKDOWN_ZIP -> markdownZip(bytes, keep)
        DocumentImportFormat.ODT -> DocumentOdtReader(mapper).read(bytes, keep::picture)
        DocumentImportFormat.DOCX -> DocumentDocxReader(mapper).read(bytes, keep::picture)
        DocumentImportFormat.HTML -> DocumentHtmlReader(mapper).read(bytes, keep::picture)
        DocumentImportFormat.TEXT -> DocumentHtmlReader(mapper).plain(text(bytes))
    }

    /**
     * The zip Export writes for a document with pictures: one Markdown file,
     * and the pictures its links point into. A link to anything not in the zip
     * is read as Markdown reads it.
     */
    private fun markdownZip(bytes: ByteArray, keep: Keeper): ObjectNode {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val read = zip.readNBytes((ZIP_MOST - total).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + 1)
                total += read.size
                if (total > ZIP_MOST) throw DocumentImportUnreadableException("zip", "it unpacks to more than ${ZIP_MOST / MB} MB")
                files[entry.name.replace('\\', '/').removePrefix("./")] = read
            }
        }
        val markdown = files.entries.firstOrNull { it.key.lowercase().endsWith(".md") || it.key.lowercase().endsWith(".markdown") }
            ?: throw DocumentImportUnreadableException("zip", "it holds no Markdown file")
        val base = markdown.key.substringBeforeLast('/', "")
        val kept = mutableMapOf<String, String?>()
        val resolve: (String?) -> String? = { destination ->
            DocumentMarkdown.artifactOf(destination) ?: destination?.let { asked ->
                val path = runCatching { java.net.URLDecoder.decode(asked.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(asked)
                val inZip = listOf(path, asked, if (base.isEmpty()) path else "$base/$path").firstOrNull { it in files }
                inZip?.let { found -> kept.getOrPut(found) { keep.picture(requireNotNull(files[found]), found.substringAfterLast('/')) } }
            }
        }
        return DocumentMarkdown(mapper, resolve).document(text(markdown.value))
    }

    /**
     * Pictures named by an artifact id that is not this workspace's - a
     * Markdown file written elsewhere - are left out: the editor could not draw
     * them, and the save would be refused over them.
     */
    private fun withoutStrangers(doc: ObjectNode, workspaceId: Long): ObjectNode {
        fun walk(node: JsonNode) {
            val inner = node.path("content")
            if (!inner.isArray) return
            val list = inner as tools.jackson.databind.node.ArrayNode
            var at = 0
            while (at < list.size()) {
                val child = list.get(at)
                if (child.path("type").asText("") == "image") {
                    val artifact = child.path("attrs").path("artifact").asText("")
                    if (artifact.isEmpty() || artifacts.storedOf(workspaceId, artifact) == null) {
                        list.remove(at)
                        continue
                    }
                }
                walk(child)
                at++
            }
        }
        walk(doc)
        return doc
    }

    /** The words of a text file: UTF-8, its byte order mark dropped. */
    private fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix("﻿")

    /**
     * Keeps the pictures one import brings, up to the Tunable, each as a Design
     * picture of the workspace; counts the ones it could not.
     */
    inner class Keeper(private val workspaceId: Long, private val most: Int) {
        var kept = 0
            private set
        var leftOut = 0
            private set

        /** The artifact id the picture is kept as, or null where it is left out. */
        fun picture(bytes: ByteArray, name: String?): String? {
            if (kept >= most) {
                leftOut++
                return null
            }
            val keepable = keepable(bytes)
            if (keepable == null) {
                leftOut++
                return null
            }
            val called = name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: "Imported picture"
            return runCatching {
                pictures.importDesignImage(
                    ImportDesignImageInput(workspaceId = workspaceId.toString(), image = Base64.getEncoder().encodeToString(keepable), name = called),
                ).id
            }.onSuccess { kept++ }
                .onFailure {
                    leftOut++
                    log.info("A picture in an imported document was not kept: {}", it.message)
                }
                .getOrNull()
        }
    }

    companion object {
        private const val MB = 1024 * 1024

        /** How much a Markdown zip may unpack to: a format guard against a zip bomb, not a setting. */
        private const val ZIP_MOST = 512L * MB

        /**
         * A picture as a file Design keeps: a PNG, JPEG, GIF or WebP as it is,
         * and anything else Java can read - a BMP, a TIFF - as a PNG. Null for
         * what it cannot read, such as a Windows metafile.
         */
        fun keepable(bytes: ByteArray): ByteArray? {
            fun starts(vararg head: Int) = bytes.size >= head.size && head.indices.all { (bytes[it].toInt() and 0xFF) == head[it] }
            val kept = starts(0x89, 0x50, 0x4E, 0x47) || starts(0xFF, 0xD8, 0xFF) || starts(0x47, 0x49, 0x46, 0x38) ||
                (bytes.size >= 12 && starts(0x52, 0x49, 0x46, 0x46) && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP")
            if (kept) return bytes
            val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
            return runCatching { ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() }.getOrNull()
        }
    }
}

/** What a file is read as, by its name first and by its first bytes where the name says nothing. */
enum class DocumentImportFormat(val said: String) {
    PDF("PDF"), MARKDOWN("Markdown"), MARKDOWN_ZIP("Markdown"), ODT("ODT"), DOCX("DOCX"), HTML("HTML"), TEXT("Text");

    companion object {
        fun of(filename: String, bytes: ByteArray): DocumentImportFormat? {
            val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (extension) {
                "pdf" -> PDF
                "md", "markdown" -> MARKDOWN
                "zip" -> MARKDOWN_ZIP
                "odt" -> ODT
                "docx" -> DOCX
                "html", "htm", "xhtml" -> HTML
                "txt", "text" -> TEXT
                else -> sniffed(bytes)
            }
        }

        private fun sniffed(bytes: ByteArray): DocumentImportFormat? {
            val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
            return when {
                head.startsWith("%PDF") -> PDF
                head.startsWith("PK") && head.contains("mimetypeapplication/vnd.oasis.opendocument.text") -> ODT
                head.startsWith("PK") && head.contains("[Content_Types].xml") -> DOCX
                head.trimStart().lowercase(Locale.ROOT).let { it.startsWith("<!doctype html") || it.startsWith("<html") } -> HTML
                else -> null
            }
        }
    }
}

/** A file read into a document's vocabulary, for the editor to put on the page. */
data class DocumentImportView(
    /** ProseMirror JSON, `{"type":"doc","content":[...]}`, checked as a save is. */
    val content: String,
    /** What it was read as: PDF, Markdown, ODT, DOCX, HTML or Text. */
    val format: String,
    /** How many pictures it brought, now Design pictures of the workspace. */
    val pictures: Int,
    /** How many it carried that were not kept. */
    val picturesLeftOut: Int,
)

class DocumentImportFormatUnknownException(val filename: String) :
    RuntimeException("A document is imported from PDF, Markdown, ODT, DOCX, HTML or text, and $filename is none of them"), Refusal {
    override val arguments get() = mapOf("filename" to filename)
}

class DocumentImportTooLargeException(val filename: String, val megabytes: Int, val most: Int) :
    RuntimeException("$filename is $megabytes MB, and a document imports at most $most MB"), Refusal {
    override val arguments get() = mapOf("filename" to filename, "megabytes" to megabytes, "most" to most)
}

class DocumentImportUnreadableException(val filename: String, val reason: String) :
    RuntimeException("$filename could not be read: $reason"), Refusal {
    override val arguments get() = mapOf("filename" to filename, "reason" to reason)
}
