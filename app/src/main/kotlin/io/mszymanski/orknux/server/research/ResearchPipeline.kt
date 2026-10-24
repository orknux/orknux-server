package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * How far a research digs: what a mode's workflow sets on its Research node.
 * Issue #659.
 *
 * @param subQuestions how many parts the question is broken into first.
 * @param breadth how many follow-up questions one finding leads to, at the
 *   first level below the plan; halved at each level after, as gpt-researcher
 *   narrows its deep mode.
 * @param depth how many levels of searching there are; 1 is the plan's own
 *   questions and nothing after.
 * @param resultsPerQuery how many of a search's results are looked at.
 * @param maxSources the most sources read in all; the research stops looking
 *   when it has this many.
 * @param maxPagesPerSource how many pages are read of one source: addresses on
 *   one website, or pages of one document.
 */
data class ResearchKnobs(
    val subQuestions: Int,
    val breadth: Int,
    val depth: Int,
    val resultsPerQuery: Int,
    val maxSources: Int,
    val maxPagesPerSource: Int,
)

/** Everything one research is asked, as the pipeline takes it. */
data class ResearchAsked(
    val workspaceId: Long,
    /** The research row to keep up to date, or null for a run of the function on its own. */
    val researchId: Long?,
    val question: String,
    val language: ResearchLanguage,
    val modelId: Long,
    val websitesInclude: List<String>,
    val websitesExclude: List<String>,
    val documentsInclude: List<Long>,
    val documentsExclude: List<Long>,
    val knobs: ResearchKnobs,
)

/** One source the report may cite, numbered as it cites it. */
data class ResearchSource(
    val number: Int,
    val title: String,
    /** An address, or the Artifacts page's link to a document. */
    val url: String,
    val kind: String,
    /** What was learnt from it, which the writing reads instead of the page. */
    val notes: String,
)

/** Where a research has got to, as the page draws it. */
data class ResearchProgress(
    val step: String,
    val subQuestions: List<String> = emptyList(),
    val searched: List<String> = emptyList(),
    val level: Int = 0,
    val sourcesRead: Int = 0,
)

/** What a research came to. */
data class ResearchFound(val report: String, val sources: List<ResearchSource>)

/** The model would not answer, so the research cannot go on. */
class ResearchModelFailedException(reason: String) : RuntimeException(reason)

/**
 * A research, done: plan, gather, go deeper, write. Issue #659.
 *
 * The shape gpt-researcher and Perplexica's deep mode share, written out here
 * so every knob of it is one a mode's workflow sets:
 *
 * 1. **Plan.** The model breaks the question into [ResearchKnobs.subQuestions]
 *    search queries.
 * 2. **Gather.** Each query is searched with the workspace's own search, the
 *    websites to keep to and to avoid applied to what comes back; every page
 *    kept is read, and the model condenses what it says about the question
 *    into notes held with its title and address. The documents asked for are
 *    read the same way, a page at a time.
 * 3. **Deeper.** Below the first level, each finding leads to follow-up
 *    questions - [ResearchKnobs.breadth] of them, halving at each level - to
 *    [ResearchKnobs.depth] levels, the recursion gpt-researcher's deep mode is.
 * 4. **Write.** The model writes the report from the notes alone, citing them
 *    as [n] against a numbered Sources list, in the register asked for. The
 *    list is the pipeline's, not the model's, so it is always there and always
 *    right.
 *
 * Every step is written to the research as it is taken, which is what the page
 * shows while it runs and what a finished research keeps.
 */
@Component
class ResearchPipeline(
    private val models: ModelChatClient,
    private val web: ResearchWeb,
    private val documents: ResearchDocuments,
    private val saved: SavedArtifactRepository,
    private val researches: ResearchRepository,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(asked: ResearchAsked): ResearchFound {
        val tracker = Tracker(asked.researchId)
        val knobs = asked.knobs
        val sources = mutableListOf<ResearchSource>()

        // --- plan ---------------------------------------------------------
        tracker.step("Planning the research")
        val plan = listFrom(
            ask(asked, PLAN, buildString {
                appendLine("Question: ${asked.question}")
                append("Write exactly ${knobs.subQuestions} search queries.")
            }),
        ).take(knobs.subQuestions).ifEmpty { listOf(asked.question) }
        tracker.planned(plan)

        // --- documents ----------------------------------------------------
        val held = saved.findAllById(asked.documentsInclude).filter { it.workspaceId == asked.workspaceId }
        for (document in held) {
            if (sources.size >= knobs.maxSources) break
            tracker.step("Reading ${document.name}")
            val text = documents.text(document)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val pages = text.chunked(tuning[Tunable.RESEARCH_PAGE_CHARACTERS]).take(knobs.maxPagesPerSource)
            val notes = noted(asked, asked.question, document.name, pages.joinToString("\n\n")) ?: continue
            sources += ResearchSource(sources.size + 1, document.name, "/api/artifacts/${document.id}", "DOCUMENT", notes)
            tracker.read(sources)
        }

        // --- gather, and deeper ------------------------------------------
        val excludedDocuments = saved.findAllById(asked.documentsExclude).map { it.name }
        val seen = mutableSetOf<String>()
        val pagesOf = mutableMapOf<String, Int>()
        var level = 1
        var queries = plan
        while (queries.isNotEmpty() && level <= knobs.depth && sources.size < knobs.maxSources) {
            val next = mutableListOf<String>()
            val asksOnward = level < knobs.depth
            val followUps = maxOf(1, knobs.breadth shr (level - 1))
            for (query in queries) {
                if (sources.size >= knobs.maxSources) break
                tracker.searching(query, level)
                val found = web.search(asked.workspaceId, scoped(query, asked.websitesInclude), knobs.resultsPerQuery)
                    .getOrElse { why ->
                        tracker.step("The search answered: ${why.message}")
                        emptyList()
                    }
                val learnt = mutableListOf<String>()
                for (hit in found.take(knobs.resultsPerQuery)) {
                    if (sources.size >= knobs.maxSources) break
                    val host = hostOf(hit.url) ?: continue
                    if (!allowed(host, asked.websitesInclude, asked.websitesExclude)) continue
                    if (!seen.add(hit.url)) continue
                    if ((pagesOf[host] ?: 0) >= knobs.maxPagesPerSource) continue
                    pagesOf[host] = (pagesOf[host] ?: 0) + 1

                    tracker.step("Reading ${hit.title}")
                    val page = when (val read = web.read(asked.workspaceId, hit.url)) {
                        is PageRead.Read -> read.text
                        // A page that cannot be fetched still has what the search said of it.
                        is PageRead.Unread -> hit.snippet
                    }.take(tuning[Tunable.RESEARCH_PAGE_CHARACTERS])
                    if (page.isBlank()) continue
                    val notes = noted(asked, query, hit.title, page) ?: continue
                    sources += ResearchSource(sources.size + 1, hit.title, hit.url, "WEB", notes)
                    learnt += notes
                    tracker.read(sources)
                }
                if (asksOnward && learnt.isNotEmpty()) {
                    next += listFrom(
                        ask(asked, FOLLOW_UP, buildString {
                            appendLine("Question: ${asked.question}")
                            appendLine("Query searched: $query")
                            appendLine("What was learnt:")
                            appendLine(learnt.joinToString("\n\n"))
                            append("Write exactly $followUps follow-up search queries.")
                        }),
                    ).take(followUps)
                }
            }
            queries = next.distinct().filter { it !in tracker.searched }
            level += 1
        }

        // --- write --------------------------------------------------------
        tracker.step("Writing the report")
        val written = ask(asked, WRITE, buildString {
            appendLine("Question: ${asked.question}")
            appendLine()
            appendLine(register(asked.language))
            if (excludedDocuments.isNotEmpty()) appendLine("Do not use: ${excludedDocuments.joinToString(", ")}.")
            appendLine()
            if (sources.isEmpty()) {
                appendLine("No sources could be read. Say so, and say what would be needed to answer.")
            } else {
                appendLine("Sources, by number:")
                sources.forEach { source ->
                    appendLine()
                    appendLine("[${source.number}] ${source.title} - ${source.url}")
                    appendLine(source.notes)
                }
            }
        })
        val report = withSources(written.trim(), sources)
        tracker.step("Finished")
        return ResearchFound(report, sources)
    }

    /** The model's notes on one source, or null where it found nothing that bears on the question. */
    private fun noted(asked: ResearchAsked, query: String, title: String, text: String): String? {
        val said = ask(asked, NOTES, buildString {
            appendLine("Question: ${asked.question}")
            appendLine("Query: $query")
            appendLine("Source: $title")
            appendLine()
            append(text)
        }).trim()
        return said.takeUnless { it.isEmpty() || it.uppercase().startsWith(NOTHING) }
    }

    private fun ask(asked: ResearchAsked, system: String, user: String): String =
        when (val answered = models.complete(asked.modelId, listOf(ChatTurn("system", system), ChatTurn("user", user)))) {
            is ChatCompletion.Answered -> answered.content
            is ChatCompletion.Failed -> throw ResearchModelFailedException("The model did not answer: ${answered.reason}")
            else -> throw ResearchModelFailedException("The model answered with something other than text")
        }

    /**
     * A list the model was asked for as JSON, or as lines where it wrote them
     * as lines anyway: a research is not worth stopping over a missing bracket.
     */
    private fun listFrom(said: String): List<String> {
        val start = said.indexOf('[')
        val end = said.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching { mapper.readTree(said.substring(start, end + 1)) }.getOrNull()
                ?.takeIf { it.isArray }
                ?.mapNotNull { one -> one.takeIf { it.isTextual }?.stringValue()?.trim()?.takeIf { it.isNotEmpty() } }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it.distinct() }
        }
        return said.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim().trim('"') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** The query, kept to the websites asked for where there are any. */
    private fun scoped(query: String, include: List<String>): String =
        if (include.isEmpty()) query else query + " " + include.mapNotNull(::hostOfEntry).joinToString(" OR ") { "site:$it" }

    /** A report always ends with the numbered list it cites, in the pipeline's words rather than the model's. */
    private fun withSources(report: String, sources: List<ResearchSource>): String {
        if (sources.isEmpty()) return report
        val kept = report.substringBefore("\n## Sources").trimEnd()
        return buildString {
            append(kept)
            append("\n\n## Sources\n\n")
            sources.forEach { append(it.number).append(". [").append(it.title.replace("]", ")")).append("](").append(it.url).append(")\n") }
        }.trimEnd()
    }

    /** Keeps the research row up to date, where there is one. */
    private inner class Tracker(private val researchId: Long?) {
        private var now = ResearchProgress(step = "Starting")
        val searched = mutableListOf<String>()
        private var sources: List<ResearchSource> = emptyList()

        fun step(said: String) = write(now.copy(step = said))
        fun planned(plan: List<String>) = write(now.copy(step = "Planned ${plan.size} questions", subQuestions = plan))
        fun searching(query: String, level: Int) {
            searched += query
            write(now.copy(step = "Searching: $query", searched = searched.toList(), level = level))
        }
        fun read(found: List<ResearchSource>) {
            sources = found.toList()
            write(now.copy(sourcesRead = found.size))
        }

        private fun write(next: ResearchProgress) {
            now = next
            val id = researchId ?: return
            runCatching {
                researches.noteProgress(id, mapper.writeValueAsString(next), mapper.writeValueAsString(sources))
            }.onFailure { log.warn("Could not note the progress of research {}", id, it) }
        }
    }

    companion object {
        /** What a model writes for a source with nothing on the question. */
        const val NOTHING = "NOTHING RELEVANT"

        /*
         * The four things a model is asked. Each opens with a line of its own,
         * which is also how the suite's stub tells them apart.
         */
        const val PLAN = "You plan research. Break the question into search queries that together cover it, each " +
            "answerable by a web search and different from the others. Answer with a JSON array of strings and nothing else."

        const val NOTES = "You take research notes. From the source below, write down everything that bears on the " +
            "question - facts, numbers, dates, names, claims and who makes them - as short plain sentences. Do not " +
            "add anything the source does not say. If nothing in it bears on the question, answer $NOTHING."

        const val FOLLOW_UP = "You deepen research. From what was learnt, write the follow-up search queries that " +
            "would fill its gaps or check its claims. Answer with a JSON array of strings and nothing else."

        const val WRITE = "You write research reports in Markdown. Use only the numbered sources given, and cite " +
            "them inline as [n] after each claim they support. Open with a # title, then a short summary, then the " +
            "findings under ## headings, then a ## Conclusion. Where the sources disagree or are thin, say so. Do not " +
            "write a sources list; it is added after you."

        fun register(language: ResearchLanguage): String = when (language) {
            ResearchLanguage.ACADEMIC ->
                "Write in an academic register: precise and measured, the limits of the evidence stated."
            ResearchLanguage.COLLOQUIAL ->
                "Write in a colloquial register: plain everyday words and short sentences, without losing accuracy."
            ResearchLanguage.FORMAL ->
                "Write in a formal register: professional and impersonal, complete sentences, no slang."
        }

        /** The host of an address, lower case and without www. */
        fun hostOf(url: String): String? =
            runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.removePrefix("www.")

        /** A website as somebody typed it - `example.org`, `https://example.org/x` - as a host. */
        fun hostOfEntry(typed: String): String? {
            val trimmed = typed.trim().lowercase()
            if (trimmed.isEmpty()) return null
            return (if ("://" in trimmed) hostOf(trimmed) else trimmed.substringBefore('/')).orEmpty().removePrefix("www.")
                .ifEmpty { null }
        }

        /** Whether a host is one the research may read: not excluded, and among the included where there are any. */
        fun allowed(host: String, include: List<String>, exclude: List<String>): Boolean {
            fun within(entry: String) = hostOfEntry(entry)?.let { host == it || host.endsWith(".$it") } == true
            if (exclude.any(::within)) return false
            return include.isEmpty() || include.any(::within)
        }
    }
}
