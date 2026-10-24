package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.embedded.PdfWriter
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
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
 * Asked from two places: the seeded workflows' Save step, `research_save`,
 * which is where a person sees it happen; and [ResearchRuns], for a workflow
 * that ended without one - a custom mode, or one drawn before the steps were
 * nodes - so a research is saved however its workflow was drawn.
 */
@Component
class ResearchDelivery(
    private val saved: SavedArtifacts,
    private val pdf: PdfWriter,
) {

    /** What came of saving a report. */
    sealed interface Delivered {
        data class Saved(val artifactId: Long, val fileName: String) : Delivered
        data class Refused(val reason: String) : Delivered
    }

    fun deliver(workspaceId: Long, question: String, format: ResearchOutputFormat, report: String): Delivered {
        val title = titleOf(report, question)
        val description = "Research: ${question.trim().replace(Regex("\\s+"), " ").take(MOST_DESCRIPTION)}"
        val saving = when (format) {
            ResearchOutputFormat.DOCUMENT ->
                saved.save(workspaceId, SAVED_BY, fileName(title, "md"), description, report, base64 = false)
            ResearchOutputFormat.PDF -> when (val written = pdf.fromHtml(html(report), title)) {
                is PdfWriter.Written.Refused ->
                    return Delivered.Refused("The report could not be laid out as a PDF: ${written.reason}")
                is PdfWriter.Written.Made -> saved.save(
                    workspaceId, SAVED_BY, fileName(title, "pdf"), description,
                    Base64.getEncoder().encodeToString(written.pdf), base64 = true,
                )
            }
        }
        return when (saving) {
            is SavedArtifacts.Saving.Saved -> Delivered.Saved(requireNotNull(saving.artifact.id), saving.artifact.name)
            is SavedArtifacts.Saving.Refused -> Delivered.Refused("The report could not be saved as a file: ${saving.reason}")
        }
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

        fun html(markdown: String): String = renderer.render(parser.parse(markdown))

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
