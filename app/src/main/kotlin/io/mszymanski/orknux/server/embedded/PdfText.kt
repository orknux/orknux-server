package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.regex.AuthoredRegex
import io.mszymanski.orknux.server.regex.RegexTooSlowException
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.plugin.PdfSource
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component

/**
 * A PDF's text page by page, and finding things in it. What `pdf_search`
 * answers from, so an agent can find the clause on page 41 without reading the
 * other ninety pages into its turn.
 *
 * Extracted with PDFBox's [PDFTextStripper], one page at a time in reading
 * order, the way `pdf_read` reads. Kept per session by the document's content
 * hash, the least recently searched leaving first, so a second search of the
 * same file does not read it again; [Tunable.PDF_TEXT_DOCUMENTS] bounds how
 * many are kept.
 */
@Component
class PdfText(private val tuning: Tuning) {

    /** A document's pages, or why it could not be read. */
    sealed interface Pages {
        data class Read(val pages: List<String>) : Pages {
            /** Whether no page carries any text at all: a scan, or pictures only. */
            val textless get() = pages.all { it.isBlank() }
        }
        data class Refused(val reason: String) : Pages
    }

    private val kept = object : LinkedHashMap<String, Pages.Read>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pages.Read>): Boolean =
            size > tuning[Tunable.PDF_TEXT_DOCUMENTS]
    }

    fun pages(pdf: ByteArray, sessionId: Long?): Pages = pages(PdfSource.of(pdf), sessionId)

    /** The same, from wherever the document is; an attachment is known by which it is rather than by hashing it. */
    fun pages(pdf: PdfSource, sessionId: Long?): Pages {
        val key = (sessionId ?: 0L).toString() + ":" + pdf.identity()
        synchronized(kept) { kept[key]?.let { return it } }
        val read = extract(pdf)
        if (read is Pages.Read && tuning[Tunable.PDF_TEXT_DOCUMENTS] > 0) synchronized(kept) { kept[key] = read }
        return read
    }

    private fun extract(pdf: PdfSource): Pages {
        if (pdf.size == 0L) return Pages.Refused("there is nothing to read: the pdf is empty")
        if (pdf.inMemory && pdf.size > MOST_BYTES) return Pages.Refused("that pdf is larger than ${MOST_BYTES / (1024 * 1024)} MB, which is the most this reads")
        return try {
            pdf.load().use { document ->
                if (document.isEncrypted) return Pages.Refused("that pdf is encrypted, and this does not open one")
                Pages.Read(
                    (1..document.numberOfPages).map { page ->
                        PDFTextStripper().apply {
                            startPage = page
                            endPage = page
                            sortByPosition = true
                        }.getText(document)
                    },
                )
            }
        } catch (failure: java.io.IOException) {
            Pages.Refused("that is not a pdf, or it is damaged: the document could not be read")
        }
    }

    /** One match: its page, counted from 1, and the text around it with the match marked. */
    data class Match(val page: Int, val snippet: String)

    /** What a search found: every match counted, the first [Found.listed] of them with their text. */
    data class Found(val total: Int, val listed: List<Match>, val perPage: Map<Int, Int>)

    /**
     * Every match of [query] in [pages]. Plain text unless [regex]; a plain
     * query's spaces match any run of whitespace, because a phrase in a PDF
     * breaks across lines wherever the column did.
     */
    fun search(pages: List<String>, query: String, regex: Boolean, wholeWord: Boolean, caseSensitive: Boolean, most: Int): Result<Found> {
        if (regex) AuthoredRegex.tooLong(query)?.let { return Result.failure(IllegalArgumentException(it)) }
        val body = if (regex) query else query.trim().split(WHITESPACE).joinToString("\\s+") { Regex.escape(it) }
        val pattern = if (wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
        val compiled = runCatching {
            if (caseSensitive) Regex(pattern) else Regex(pattern, setOf(RegexOption.IGNORE_CASE))
        }.getOrElse { return Result.failure(IllegalArgumentException("that is not a regular expression: ${it.message?.lineSequence()?.firstOrNull()}")) }

        val around = tuning[Tunable.PDF_SNIPPET_CHARACTERS]
        val seconds = tuning[Tunable.REGEX_MATCH_SECONDS]
        val deadline = AuthoredRegex.deadlineIn(seconds)
        val listed = mutableListOf<Match>()
        val perPage = linkedMapOf<Int, Int>()
        var total = 0
        try {
            pages.forEachIndexed { index, text ->
                compiled.findAll(AuthoredRegex.bounded(text, deadline, seconds)).forEach { match ->
                    if (match.range.isEmpty()) return@forEach
                    total++
                    perPage[index + 1] = (perPage[index + 1] ?: 0) + 1
                    if (listed.size < most) listed += Match(index + 1, snippet(text, match.range, around))
                }
            }
        } catch (slow: RegexTooSlowException) {
            return Result.failure(IllegalArgumentException(slow.message))
        }
        return Result.success(Found(total, listed, perPage))
    }

    /** About [around] characters of [text] centred on [range], the match between « and », on one line. */
    internal fun snippet(text: String, range: IntRange, around: Int): String {
        val side = ((around - (range.last - range.first + 1)) / 2).coerceAtLeast(0)
        val from = (range.first - side).coerceAtLeast(0)
        // In Long: [around] may be Int.MAX_VALUE, which is "the whole page" and not a negative index.
        val to = (range.last + 1L + side).coerceAtMost(text.length.toLong()).toInt()
        fun flat(part: String) = part.replace(WHITESPACE, " ")
        return (if (from > 0) "…" else "") +
            flat(text.substring(from, range.first)).trimStart() + "«" + flat(text.substring(range.first, range.last + 1)) + "»" +
            flat(text.substring(range.last + 1, to)).trimEnd() + (if (to < text.length) "…" else "")
    }

    private companion object {
        /** PdfRenderer's bound, for the same reason: one size to remember. */
        const val MOST_BYTES = 10 * 1024 * 1024

        val WHITESPACE = Regex("\\s+")
    }
}
