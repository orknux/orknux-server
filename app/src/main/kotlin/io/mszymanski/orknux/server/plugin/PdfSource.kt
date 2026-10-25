package io.mszymanski.orknux.server.plugin

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessRead
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A PDF to open, wherever it is.
 *
 * Bytes in hand are opened as they are. A file on disk is opened in place:
 * PDFBox reads a document by seeking, so asking what pages 3 and 4 of a 30 MB
 * attachment say reads those pages' objects and not the 30 MB - where the
 * document handed over as base64 JSON, parsed, decoded and loaded was held four
 * times over. A stream, from a storage with no file, is read once into
 * PDFBox's own buffer.
 *
 * [inMemory] is whether the caller already holds the whole document, which is
 * what the renderer's size bound is about; a file the upload limit admitted is
 * not held, and that limit is its bound.
 */
class PdfSource private constructor(
    val size: Long,
    val inMemory: Boolean,
    private val identity: String?,
    private val bytes: ByteArray?,
    private val opener: () -> RandomAccessRead,
) {
    /** The document, opened; closing it closes what it was read from. */
    fun load(): PDDocument {
        val read = opener()
        return try {
            Loader.loadPDF(read)
        } catch (failure: Throwable) {
            runCatching { read.close() }
            throw failure
        }
    }

    /** What tells this document apart from another: the name it was given, else its content's hash. */
    fun identity(): String = identity ?: MessageDigest.getInstance("SHA-256")
        .digest(requireNotNull(bytes))
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun of(bytes: ByteArray): PdfSource =
            PdfSource(bytes.size.toLong(), inMemory = true, identity = null, bytes = bytes) { RandomAccessReadBuffer(bytes) }

        fun of(file: Path, size: Long, identity: String): PdfSource =
            PdfSource(size, inMemory = false, identity = identity, bytes = null) { RandomAccessReadBufferedFile(file.toFile()) }

        fun of(size: Long, identity: String, open: () -> InputStream): PdfSource =
            PdfSource(size, inMemory = false, identity = identity, bytes = null) { open().use { RandomAccessReadBuffer(it) } }
    }
}
