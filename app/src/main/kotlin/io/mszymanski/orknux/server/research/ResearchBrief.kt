package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.SavedArtifact
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Where a research may look, as it was asked for. Issue #659.
 *
 * Websites are domains or addresses, kept as typed. Documents are the files on
 * the workspace's Artifacts page - what an agent saved, and every report a
 * research has finished - by their id there.
 */
data class ResearchSources(
    val websitesInclude: List<String> = emptyList(),
    val websitesExclude: List<String> = emptyList(),
    val documentsInclude: List<Long> = emptyList(),
    val documentsExclude: List<Long> = emptyList(),
)

/**
 * The workspace's documents a research can be pointed at, and what one says.
 *
 * "Documents" here are the saved files on the Artifacts page - the only files a
 * workspace holds that are documents rather than pictures. A file is offered
 * where its text can be read: text of any kind, HTML, JSON, and PDF, whose text
 * PDFBox reads out.
 */
@Component
class ResearchDocuments(
    private val saved: SavedArtifactRepository,
    private val store: AttachmentStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The workspace's readable documents, newest first. */
    fun of(workspaceId: Long): List<SavedArtifact> =
        saved.findByWorkspaceId(workspaceId).filter { readable(it.contentType) }
            .sortedWith(compareByDescending<SavedArtifact> { it.savedAt }.thenByDescending { it.id })

    /** What the document says, or null where it cannot be read. */
    fun text(document: SavedArtifact): String? = runCatching {
        val bytes = store.open(document.location).use { it.readBytes() }
        when (typeOf(document.contentType)) {
            PDF -> Loader.loadPDF(bytes).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }
            HTML -> Jsoup.parse(String(bytes, Charsets.UTF_8)).text()
            else -> String(bytes, Charsets.UTF_8)
        }
    }.onFailure { log.warn("Could not read document {} for a research", document.id, it) }.getOrNull()

    companion object {
        private const val PDF = "application/pdf"
        private const val HTML = "text/html"

        private fun typeOf(contentType: String) = contentType.lowercase().substringBefore(';').trim()

        fun readable(contentType: String): Boolean = typeOf(contentType).let {
            it.startsWith("text/") || it == PDF || it == "application/json" || it == "application/xml"
        }
    }
}

/**
 * The brief a research's workflow is handed: everything that was asked, said
 * once, in words a model reads. Issue #659.
 *
 * Written here rather than assembled in the graph out of placeholders, so the
 * three workflows need only point their agent at one field - and a workflow
 * somebody writes for a custom mode gets the same brief without having to
 * learn which of a dozen fields says what. The fields themselves travel beside
 * it in the payload, for a workflow that would rather read them one by one.
 */
@Component
class ResearchBriefs(
    private val documents: ResearchDocuments,
    private val tuning: Tuning,
) {

    fun of(
        question: String,
        language: ResearchLanguage,
        format: ResearchOutputFormat,
        sources: ResearchSources,
        included: List<SavedArtifact>,
        excluded: List<SavedArtifact>,
        useWebSearch: Boolean = true,
    ): String = buildString {
        appendLine("Research this question and write a report on it.")
        appendLine()
        appendLine("Question:")
        appendLine(question.trim())
        appendLine()
        appendLine(
            when (language) {
                ResearchLanguage.ACADEMIC ->
                    "Write in an academic register: precise and measured, every claim attributed to its source, " +
                        "with the limits of the evidence stated."
                ResearchLanguage.COLLOQUIAL ->
                    "Write in a colloquial register: plain everyday words and short sentences, as if explaining " +
                        "it to a friend, without losing accuracy."
                ResearchLanguage.FORMAL ->
                    "Write in a formal register: professional and impersonal, complete sentences, no slang."
            },
        )
        appendLine()
        if (!useWebSearch) {
            appendLine("Do not search the web: read only the documents given and the pages of the websites named.")
        }
        if (sources.websitesInclude.isNotEmpty()) {
            appendLine("Keep to these websites; search and cite nothing else on the web: ${sources.websitesInclude.joinToString(", ")}.")
        }
        if (sources.websitesExclude.isNotEmpty()) {
            appendLine("Never search, read or cite these websites: ${sources.websitesExclude.joinToString(", ")}.")
        }
        if (excluded.isNotEmpty()) {
            appendLine("Do not use or cite these documents: ${excluded.joinToString(", ") { it.name }}.")
        }
        if (included.isNotEmpty()) {
            appendLine()
            appendLine("These documents are sources for this research. Read them first and cite them by name:")
            var left = tuning[Tunable.RESEARCH_DOCUMENT_CHARACTERS]
            included.forEach { document ->
                appendLine()
                appendLine("--- Document: ${document.name} ---")
                val text = documents.text(document)?.trim()
                when {
                    text == null -> appendLine("(This document could not be read.)")
                    left <= 0 -> appendLine("(Left out: the documents handed to one research reached their limit.)")
                    text.length > left -> {
                        appendLine(text.take(left))
                        appendLine("(The rest of this document was left out: the documents handed to one research reached their limit.)")
                        left = 0
                    }
                    else -> {
                        appendLine(text)
                        left -= text.length
                    }
                }
                appendLine("--- End of ${document.name} ---")
            }
        }
        appendLine()
        appendLine(
            when (format) {
                ResearchOutputFormat.PDF -> "The report will be laid out as a PDF from your Markdown."
                ResearchOutputFormat.DOCUMENT -> "The report will be shown as a document from your Markdown."
            },
        )
        append("Answer with the report itself, in Markdown, and nothing else.")
    }
}
