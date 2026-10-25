package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.workflow.script.StoredKind

/**
 * What a file is, for a writer about to put it in the session store with a
 * [StoredKind].
 *
 * **The bytes first, the name second, and a header never.** A server's
 * `content-type` is somebody else's claim - a picture host answers
 * `application/octet-stream` for a JPEG, a CDN answers `text/html` for its
 * error page - and a URL's extension is a guess at what was meant. The first
 * few bytes are what the file is, and they are what a reader such as
 * `picture_view` will hold it to. A name is used only where the bytes say
 * nothing a reader keys on, which is where a box file's extension is the best
 * there is - a `.csv` has no magic.
 */
object StoredTypes {

    /** The type the bytes say, then the one [name]'s extension says; null where neither says anything. */
    fun typeOf(bytes: ByteArray, name: String? = null): String? {
        val said = magic(bytes)
        val named = name?.let(::byExtension)
        // A spreadsheet or a document is a zip by its bytes; its name is what says which.
        if (said == "application/zip" && named != null && named.startsWith("application/vnd.openxmlformats")) return named
        return said ?: named
    }

    /** The type the first few bytes say, or null. */
    fun magic(bytes: ByteArray): String? {
        fun at(i: Int) = if (bytes.size > i) bytes[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        val start = String(bytes, 0, minOf(bytes.size, 300), Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        return when {
            at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "BM") && bytes.size > 14 -> "image/bmp"
            ascii(0, "%PDF") -> "application/pdf"
            ascii(0, "PK") && at(2) == 3 && at(3) == 4 -> "application/zip"
            at(0) == 0x1F && at(1) == 0x8B -> "application/gzip"
            ascii(0, "MThd") -> "audio/midi"
            start.startsWith("<svg") || (start.startsWith("<?xml") && start.contains("<svg")) -> "image/svg+xml"
            else -> null
        }
    }

    /** The type a file name's extension says, or null for one this does not know. */
    fun byExtension(name: String): String? = EXTENSIONS[name.substringAfterLast('/').substringAfterLast('.', "").lowercase()]

    /** Whether [type] is held as text in the store: text itself rather than base64. */
    fun isText(type: String?): Boolean {
        val bare = type?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return bare.startsWith("text/") || bare in TEXT_TYPES || bare.endsWith("+json") || bare.endsWith("+xml")
    }

    /** Whether the bytes read as UTF-8 text: no NUL, and nothing a decoder refuses. */
    fun readsAsText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
        }.isSuccess
    }

    /** The pictures a model is shown as they are; an SVG is drawn first. */
    val SHOWN_PICTURES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    private val TEXT_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/yaml", "image/svg+xml")

    private val EXTENSIONS = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "tgz" to "application/gzip",
        "mid" to "audio/midi",
        "midi" to "audio/midi",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "mp4" to "video/mp4",
        "txt" to "text/plain",
        "log" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}
