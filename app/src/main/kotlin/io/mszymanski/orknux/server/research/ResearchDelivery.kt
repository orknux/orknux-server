package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.embedded.PageBlocks
import io.mszymanski.orknux.server.embedded.PdfWriter
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * A research's report, saved as the file it was asked for on the Artifacts
 * page. Issue #659.
 *
 * **The report is the server's to lay out.** A workflow answers in markdown -
 * one field, whatever the workflow does to get there - and turning that into a
 * PDF or a document is done here, the same way every time, with the libraries
 * the rest of the server already uses: commonmark for the HTML and the PDF
 * writer `pdf_fromHtml` uses for the page. A workflow that had to produce the
 * file itself would be one more thing for every custom mode to get right.
 *
 * **Pictures are the research's own, and nothing is fetched.** A report names
 * a picture Gather kept as `![caption](image:2)`; here that becomes the
 * picture, with its caption and where it came from under it - in a PDF from
 * the bytes kept on the Artifacts page, in a document as a link to them. Any
 * other picture the markdown names, an address or a number nothing was kept
 * under, is left as its words: a report is written by a model from pages it
 * read, and a layout that fetched what it named would be those pages deciding
 * what this server connects to.
 *
 * Asked from two places: the seeded workflows' Save step, `research_save`,
 * which is where a person sees it happen; and [ResearchRuns], for a workflow
 * that ended without one - a custom mode, or one drawn before the steps were
 * nodes - so a research is saved however its workflow was drawn.
 */
@Component
class ResearchDelivery(
    private val saved: SavedArtifacts,
    private val pdf: PdfWriter,
    private val artifacts: SavedArtifactRepository,
    private val store: AttachmentStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What came of saving a report. */
    sealed interface Delivered {
        data class Saved(val artifactId: Long, val fileName: String) : Delivered
        data class Refused(val reason: String) : Delivered
    }

    fun deliver(
        workspaceId: Long,
        question: String,
        format: ResearchOutputFormat,
        report: String,
        images: List<ResearchImage> = emptyList(),
    ): Delivered {
        val title = titleOf(report, question)
        val description = "Research: ${question.trim().replace(Regex("\\s+"), " ").take(MOST_DESCRIPTION)}"
        val saving = when (format) {
            ResearchOutputFormat.DOCUMENT ->
                saved.save(workspaceId, SAVED_BY, fileName(title, "md"), description, placed(report, images, ::linkOf), base64 = false)
            ResearchOutputFormat.PDF -> {
                val named = images.associateBy(::nameOf)
                val page = html(placed(report, images, ::nameOf), named.keys)
                when (val written = pdf.fromHtml(page, title) { name -> named[name]?.let(::picture) }) {
                    is PdfWriter.Written.Refused ->
                        return Delivered.Refused("The report could not be laid out as a PDF: ${written.reason}")
                    is PdfWriter.Written.Made -> saved.save(
                        workspaceId, SAVED_BY, fileName(title, "pdf"), description,
                        Base64.getEncoder().encodeToString(written.pdf), base64 = true,
                    )
                }
            }
        }
        return when (saving) {
            is SavedArtifacts.Saving.Saved -> Delivered.Saved(requireNotNull(saving.artifact.id), saving.artifact.name)
            is SavedArtifacts.Saving.Refused -> Delivered.Refused("The report could not be saved as a file: ${saving.reason}")
        }
    }

    /** A kept picture's bytes, read from where Gather saved them; null where they are no longer there. */
    private fun picture(image: ResearchImage): PageBlocks.Picture? {
        val held = artifacts.findByIdOrNull(image.artifactId) ?: return null
        return runCatching { PageBlocks.Picture(store.open(held.location).use { it.readBytes() }, image.contentType) }
            .onFailure { log.warn("Could not read research image {} for a PDF", image.artifactId, it) }
            .getOrNull()
    }

    companion object {
        /** Who the Artifacts page says saved a report. */
        const val SAVED_BY = "Research"

        private const val MOST_DESCRIPTION = 400
        private const val MOST_FILE_NAME = 80

        private val extensions = listOf(TablesExtension.create())
        private val parser: Parser = Parser.builder().extensions(extensions).build()

        /**
         * Markdown to HTML, with any HTML the model wrote escaped rather than
         * kept: what a model writes is untrusted, and the PDF writer would
         * otherwise lay out whatever markup a page it read talked it into.
         */
        private val renderer: HtmlRenderer =
            HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true).build()

        /**
         * Markdown to HTML. A picture whose address is not one of [pictures] -
         * the names [placed] gave the research's own - is laid out as its
         * words: this is where a reference-style picture, which [placed] does
         * not read, is stopped too.
         */
        fun html(markdown: String, pictures: Set<String> = emptySet()): String {
            val document = parser.parse(markdown)
            val strangers = mutableListOf<Image>()
            document.accept(object : AbstractVisitor() {
                override fun visit(image: Image) {
                    if (image.destination !in pictures) strangers += image
                }
            })
            strangers.forEach { image ->
                var child: Node? = image.firstChild
                while (child != null) {
                    val next = child.next
                    image.insertBefore(child)
                    child = next
                }
                image.unlink()
            }
            return renderer.render(document)
        }

        /** What the PDF's page calls a kept picture, which the PDF writer reads the bytes behind. */
        fun nameOf(image: ResearchImage) = "research-image-${image.number}"

        /** Where a kept picture is read from in the browser: its own artifact. */
        fun linkOf(image: ResearchImage) = "/api/artifacts/${image.artifactId}"

        /** `![alt](address "title")`, the inline form; the reference form is left to [html]. */
        private val PICTURE = Regex("""!\[((?:[^\]\\\n]|\\.)*)]\(\s*<?([^\s)>]*)>?(?:\s+(?:"[^"]*"|'[^']*'))?\s*\)""")
        private val PLACEHOLDER = Regex("""${ResearchPipeline.IMAGE_SCHEME}:(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * The report with each `![caption](image:n)` it places made the
         * picture kept under that number, addressed as [address] says, with
         * its caption and source under it; and every other picture it names
         * made its words. The captions are a page's words and are escaped,
         * so nothing in one is read as markdown.
         */
        fun placed(report: String, images: List<ResearchImage>, address: (ResearchImage) -> String): String {
            val kept = images.associateBy { it.number }
            return PICTURE.replace(report) { found ->
                val number = PLACEHOLDER.matchEntire(found.groupValues[2])?.groupValues?.get(1)?.toIntOrNull()
                // Put in as it is: what a transform answers is not read for group references.
                val image = number?.let { kept[it] } ?: return@replace found.groupValues[1]
                buildString {
                    append("![").append(escaped(image.caption)).append("](").append(address(image)).append(")\\\n")
                    append('*').append(escaped("Image ${image.number}: ${image.caption}")).append('*')
                    val from = image.page?.takeIf(::linkable) ?: image.url.takeIf(::linkable)
                    if (from != null) {
                        val host = ResearchPipeline.hostOf(from) ?: "source"
                        append(" ([").append(escaped(host)).append("](<").append(from).append(">))")
                    }
                }
            }
        }

        /** The numbers of the kept pictures a report places, by the placeholders it writes. */
        fun numbersIn(report: String): Set<Int> = PICTURE.findAll(report)
            .mapNotNull { PLACEHOLDER.matchEntire(it.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() }
            .toSet()

        /** An address a link may carry as it is: the web, or the Artifacts page, with nothing in it that ends a link. */
        private fun linkable(url: String): Boolean =
            (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("/api/artifacts/")) &&
                url.none { it.isWhitespace() || it == '<' || it == '>' }

        /** Words with every character markdown would read as markup escaped. */
        private fun escaped(words: String): String = buildString {
            words.forEach { c ->
                if (c in MARKUP) append('\\')
                append(if (c == '\n' || c == '\r') ' ' else c)
            }
        }

        private const val MARKUP = "\\`*_{}[]()<>#+-.!|~&"

        /** The report's own first heading, or the question where it has none. */
        fun titleOf(report: String, question: String): String =
            report.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: question.trim().lineSequence().first()

        /** A file name a download can carry: letters, digits and dashes. */
        fun fileName(title: String, extension: String): String {
            val stem = title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(MOST_FILE_NAME).trim('-')
            return "${stem.ifEmpty { "research" }}.$extension"
        }
    }
}
