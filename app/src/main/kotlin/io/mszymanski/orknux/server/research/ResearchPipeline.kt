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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * How far a research digs: the knobs of its steps, which a mode's workflow
 * writes on each step's node. Issue #659.
 *
 * @param subQuestions how many parts the question is broken into first. Plan's.
 * @param breadth how many follow-up questions one finding leads to, at the
 *   first level below the plan; halved at each level after, as gpt-researcher
 *   narrows its deep mode. Follow up's.
 * @param depth how many levels of searching there are; 1 is the plan's own
 *   questions and nothing after. Follow up's.
 * @param resultsPerQuery how many of a search's results are looked at. Gather's.
 * @param maxSources the most sources read in all; the research stops looking
 *   when it has this many. Gather's.
 * @param maxPagesPerSource how many pages are read of one source: addresses on
 *   one website, or pages of one document. Gather's.
 */
data class ResearchKnobs(
    val subQuestions: Int,
    val breadth: Int,
    val depth: Int,
    val resultsPerQuery: Int,
    val maxSources: Int,
    val maxPagesPerSource: Int,
) {
    val limits get() = ResearchLimits(resultsPerQuery, maxSources, maxPagesPerSource)
}

/**
 * How much a research reads. Set on Gather and handed on with what it
 * gathered, so Follow up keeps to the same numbers without a second copy of
 * them on its own node to disagree with the first.
 */
data class ResearchLimits(val resultsPerQuery: Int, val maxSources: Int, val maxPagesPerSource: Int)

/** Everything one research is asked, as every step takes it. */
data class ResearchAsked(
    val workspaceId: Long,
    /** The research row to keep up to date, or null for a run of the functions on their own. */
    val researchId: Long?,
    val question: String,
    val language: ResearchLanguage,
    val modelId: Long,
    val websitesInclude: List<String> = emptyList(),
    val websitesExclude: List<String> = emptyList(),
    val documentsInclude: List<Long> = emptyList(),
    val documentsExclude: List<Long> = emptyList(),
    /** Whether to search the web; off, only the documents and the websites to keep to are read. */
    val webSearch: Boolean = true,
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

/** One query searched, and the sources it led to by number: what a follow-up question is asked from. */
data class ResearchFinding(val query: String, val sources: List<Int>)

/**
 * What Gather hands Follow up, and Follow up hands Write: everything read so
 * far, and enough of how it was read for the next step to go on from there
 * rather than start again - what was searched, which addresses were taken,
 * how many pages of each website, and what the deepest level found.
 */
data class ResearchGathered(
    val sources: List<ResearchSource>,
    val findings: List<ResearchFinding>,
    val searched: List<String>,
    val seen: List<String>,
    val pages: Map<String, Int>,
    /** The deepest level searched; 1 is the plan's own queries. */
    val level: Int,
    val limits: ResearchLimits,
    /**
     * What the search answered where it answered a refusal rather than
     * results - no key, a spent quota - each said once, in its own words.
     * Handed on so that a research which read nothing can say what the
     * search told it.
     */
    val refused: List<String> = emptyList(),
)

/** Which of a mode workflow's steps a research is in. */
enum class ResearchStage {
    PLAN,
    GATHER,
    FOLLOW_UP,
    WRITE,
    SAVE,
}

/** Where a research has got to, as the page draws it. */
data class ResearchProgress(
    val step: String,
    val subQuestions: List<String> = emptyList(),
    val searched: List<String> = emptyList(),
    val level: Int = 0,
    val sourcesRead: Int = 0,
    /** The step doing it; null on a progress written before the steps were nodes. */
    val stage: ResearchStage? = null,
)

/** What a research came to. */
data class ResearchFound(val report: String, val sources: List<ResearchSource>)

/** The model would not answer, so the research cannot go on. */
class ResearchModelFailedException(reason: String) : RuntimeException(reason)

/**
 * Nothing was read, so there is nothing to write a report from. Settled: the
 * same request reads the same nothing until somebody changes what it reads
 * with - the search's key, the documents, the websites.
 *
 * Worded as the verb phrase a failed function's reason is, after its name.
 */
class ResearchNothingReadException(reason: String) : RuntimeException(reason)

/**
 * A research, in the steps a mode's workflow draws: plan, gather, follow up,
 * write - and save, which [ResearchDelivery] does. Issue #659.
 *
 * The shape gpt-researcher and Perplexica's deep mode share, written out here
 * so every knob of it is one a step's node sets:
 *
 * 1. **Plan.** The model breaks the question into [ResearchKnobs.subQuestions]
 *    search queries.
 * 2. **Gather.** The documents asked for are read, a page at a time; then each
 *    query is searched with the workspace's own search, the websites to keep
 *    to and to avoid applied to what comes back; every page kept is read, and
 *    the model condenses what it says about the question into notes held with
 *    its title and address.
 * 3. **Follow up.** Each finding leads to follow-up questions -
 *    [ResearchKnobs.breadth] of them, halving at each level - searched and
 *    read the same way, to [ResearchKnobs.depth] levels: the recursion
 *    gpt-researcher's deep mode is. At depth 1 it hands on what it was given.
 * 4. **Write.** The model writes the report from the notes alone, citing them
 *    as [n] against a numbered Sources list, in the register asked for. The
 *    list is the pipeline's, not the model's, so it is always there and always
 *    right.
 *
 * Each step is a function of its own - see [ResearchCapability] - and hands the
 * next what it found through the run, the way any node hands on its output. A
 * mode's workflow plans and writes with agent nodes instead, told [PLAN] and
 * [WRITE] as their instructions; this keeps the gathering, the notes on each
 * source, and the numbered list the report ends with.
 * [run] is the four in one call, which is what `research_run` still is. Every
 * step is written to the research as it is taken, which is what the page
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

    /** All four steps, one after another: what `research_run` does. */
    fun run(asked: ResearchAsked, knobs: ResearchKnobs): ResearchFound {
        val plan = plan(asked, knobs.subQuestions)
        val gathered = gather(asked, plan, knobs.limits)
        val deepened = followUp(asked, gathered, knobs.breadth, knobs.depth)
        val found = write(asked, deepened)
        note(asked.researchId, ResearchStage.WRITE, "Finished")
        return found
    }

    /** Plan: the question as [subQuestions] search queries. */
    fun plan(asked: ResearchAsked, subQuestions: Int): List<String> {
        val tracker = Tracker(asked.researchId, ResearchStage.PLAN)
        tracker.step("Planning the research")
        val plan = listFrom(
            ask(asked, PLAN, buildString {
                appendLine("Question: ${asked.question}")
                append("Write exactly $subQuestions search queries.")
            }),
        ).take(subQuestions).ifEmpty { listOf(asked.question) }
        tracker.planned(plan)
        return plan
    }

    /**
     * The queries a plan came to, written on the research: what Plan says when
     * it is an agent node, which has no research to write on. Said by Gather,
     * the step that reads them.
     */
    fun planned(researchId: Long?, queries: List<String>) {
        Tracker(researchId, ResearchStage.PLAN).planned(queries)
    }

    /**
     * Refuses to go on to the writing where nothing was read: a report from no
     * sources is the model's own belief written up as research. Asked by
     * Follow up, the last step before the writing, because where the writing is
     * an agent node nothing on its way stops it.
     */
    fun requireRead(gathered: ResearchGathered) {
        if (gathered.sources.isEmpty()) throw ResearchNothingReadException(nothingRead(gathered))
    }

    /** Gather: the documents asked for, then every query searched and what it found read - the first level. */
    fun gather(asked: ResearchAsked, queries: List<String>, limits: ResearchLimits): ResearchGathered {
        val tracker = Tracker(asked.researchId, ResearchStage.GATHER)
        val reading = Reading(limits)

        val held = saved.findAllById(asked.documentsInclude).filter { it.workspaceId == asked.workspaceId }
        for (document in held) {
            if (reading.full) break
            tracker.step("Reading ${document.name}")
            val text = documents.text(document)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val pages = text.chunked(tuning[Tunable.RESEARCH_PAGE_CHARACTERS]).take(limits.maxPagesPerSource)
            val notes = noted(asked, asked.question, document.name, pages.joinToString("\n\n")) ?: continue
            reading.sources += ResearchSource(reading.sources.size + 1, document.name, "/api/artifacts/${document.id}", "DOCUMENT", notes)
            tracker.read(reading.sources)
        }

        if (!asked.webSearch) {
            visit(asked, reading, tracker)
            return reading.gathered(emptyList(), level = 1)
        }
        val distinct = queries.distinct()
        val findings = search(asked, reading, distinct, level = 1, tracker)
        /*
         * Every query refused and nothing read: going on would hand Write an
         * empty list and a model that writes a report anyway, from what it
         * already believed. Research 4 on the development server was exactly
         * that - three searches each answered "no search key set", and a PDF.
         */
        if (reading.sources.isEmpty() && distinct.isNotEmpty() && reading.refusals >= distinct.size) {
            throw ResearchNothingReadException(
                "read no source: the search answered ${reading.refused.joinToString("; ") { "\"$it\"" }} " +
                    "to ${if (distinct.size == 1) "the query" else "all ${distinct.size} queries"}.",
            )
        }
        return reading.gathered(findings, level = 1)
    }

    /**
     * Without web search: each website to keep to read at the address it was
     * typed as, with no search asked for. What it finds leads nowhere further,
     * because a follow-up question is a search.
     */
    private fun visit(asked: ResearchAsked, reading: Reading, tracker: Tracker) {
        for (entry in asked.websitesInclude) {
            if (reading.full) break
            val url = entry.trim().let { if ("://" in it) it else "https://$it" }
            val host = hostOf(url) ?: continue
            if (!allowed(host, emptyList(), asked.websitesExclude)) continue
            if (!reading.seen.add(url)) continue
            if ((reading.pages[host] ?: 0) >= reading.limits.maxPagesPerSource) continue
            reading.pages[host] = (reading.pages[host] ?: 0) + 1

            tracker.step("Reading $url")
            val read = web.read(asked.workspaceId, url) as? PageRead.Read ?: continue
            val page = read.text.take(tuning[Tunable.RESEARCH_PAGE_CHARACTERS])
            if (page.isBlank()) continue
            val title = read.title?.trim()?.ifEmpty { null } ?: host
            val notes = noted(asked, asked.question, title, page) ?: continue
            reading.sources += ResearchSource(reading.sources.size + 1, title, url, "WEB", notes)
            tracker.read(reading.sources)
        }
    }

    /**
     * Follow up: from each finding, [breadth] follow-up questions - halved at
     * every level after the first below the plan - searched and read, down to
     * [depth] levels in all. What was gathered is handed back as it was where
     * there is nothing deeper to go.
     */
    fun followUp(asked: ResearchAsked, gathered: ResearchGathered, breadth: Int, depth: Int): ResearchGathered {
        val reading = Reading(gathered)
        var findings = gathered.findings
        var level = gathered.level + 1
        if (!asked.webSearch || level > depth || findings.isEmpty() || reading.full) return gathered

        val tracker = Tracker(asked.researchId, ResearchStage.FOLLOW_UP)
        while (level <= depth && findings.isNotEmpty() && !reading.full) {
            val followUps = maxOf(1, breadth shr (level - 2))
            tracker.step("Asking follow-up questions")
            val next = findings.flatMap { finding ->
                val learnt = finding.sources.mapNotNull { number -> reading.sources.firstOrNull { it.number == number }?.notes }
                if (learnt.isEmpty()) return@flatMap emptyList()
                listFrom(
                    ask(asked, FOLLOW_UP, buildString {
                        appendLine("Question: ${asked.question}")
                        appendLine("Query searched: ${finding.query}")
                        appendLine("What was learnt:")
                        appendLine(learnt.joinToString("\n\n"))
                        append("Write exactly $followUps follow-up search queries.")
                    }),
                ).take(followUps)
            }
            val queries = next.distinct().filter { it !in reading.searched }
            if (queries.isEmpty()) break
            findings = search(asked, reading, queries, level, tracker)
            level += 1
        }
        return reading.gathered(findings, level = level - 1)
    }

    /** Write: the report from the notes, citing them, with the numbered list of sources after it. */
    fun write(asked: ResearchAsked, gathered: ResearchGathered): ResearchFound {
        val tracker = Tracker(asked.researchId, ResearchStage.WRITE)
        val sources = gathered.sources
        // A report from no sources is the model's own belief written up as research.
        if (sources.isEmpty()) throw ResearchNothingReadException(nothingRead(gathered))
        val excludedDocuments = saved.findAllById(asked.documentsExclude)
            .filter { it.workspaceId == asked.workspaceId }
            .map { it.name }
        tracker.step("Writing the report")
        val written = ask(asked, WRITE, buildString {
            appendLine("Question: ${asked.question}")
            appendLine()
            appendLine(register(asked.language))
            if (excludedDocuments.isNotEmpty()) appendLine("Do not use: ${excludedDocuments.joinToString(", ")}.")
            appendLine()
            appendLine("Sources, by number:")
            appendLine()
            append(notesOf(sources))
        })
        val report = withSources(written.trim(), sources)
        tracker.step("Report written")
        return ResearchFound(report, sources)
    }

    /** Why Write has nothing to go on, in what was done rather than a guess at why it came to nothing. */
    private fun nothingRead(gathered: ResearchGathered): String = buildString {
        append("has no source to write a report from: nothing was read")
        val searches = gathered.searched.size
        if (searches > 0) append(" in ").append(searches).append(if (searches == 1) " search" else " searches")
        append('.')
        if (gathered.refused.isNotEmpty()) {
            append(" The search answered: ").append(gathered.refused.joinToString("; ") { "\"$it\"" }).append('.')
        }
    }

    /**
     * The sources as the writing reads them: each under its number, title and
     * address, with the notes taken from it. What Write is handed, whether it
     * is this pipeline or an agent node reading Follow up's `notes`.
     */
    fun notesOf(sources: List<ResearchSource>): String = sources.joinToString("\n\n") { source ->
        "[${source.number}] ${source.title} - ${source.url}\n${source.notes}"
    }

    /** Says a step on the research where there is one: what [ResearchDelivery] writes while it saves. */
    fun note(researchId: Long?, stage: ResearchStage, said: String) {
        Tracker(researchId, stage).step(said)
    }

    /**
     * One level of searching: every query searched, what it found read and
     * noted, until the research has as many sources as it may. Answers what
     * each query led to, which is what the next level is asked from.
     */
    private fun search(
        asked: ResearchAsked,
        reading: Reading,
        queries: List<String>,
        level: Int,
        tracker: Tracker,
    ): List<ResearchFinding> {
        val limits = reading.limits
        val findings = mutableListOf<ResearchFinding>()
        for (query in queries) {
            if (reading.full) break
            reading.searched += query
            tracker.searching(reading.searched, level)
            val found = web.search(asked.workspaceId, scoped(query, asked.websitesInclude), limits.resultsPerQuery)
                .getOrElse { why ->
                    val said = why.message?.trim()?.ifEmpty { null } ?: "no results and no reason"
                    reading.refusals += 1
                    if (said !in reading.refused) reading.refused += said
                    tracker.step("The search answered: $said")
                    emptyList()
                }
            val learnt = mutableListOf<Int>()
            for (hit in found.take(limits.resultsPerQuery)) {
                if (reading.full) break
                val host = hostOf(hit.url) ?: continue
                if (!allowed(host, asked.websitesInclude, asked.websitesExclude)) continue
                if (!reading.seen.add(hit.url)) continue
                if ((reading.pages[host] ?: 0) >= limits.maxPagesPerSource) continue
                reading.pages[host] = (reading.pages[host] ?: 0) + 1

                tracker.step("Reading ${hit.title}")
                val page = when (val read = web.read(asked.workspaceId, hit.url)) {
                    is PageRead.Read -> read.text
                    // A page that cannot be fetched still has what the search said of it.
                    is PageRead.Unread -> hit.snippet
                }.take(tuning[Tunable.RESEARCH_PAGE_CHARACTERS])
                if (page.isBlank()) continue
                val notes = noted(asked, query, hit.title, page) ?: continue
                val source = ResearchSource(reading.sources.size + 1, hit.title, hit.url, "WEB", notes)
                reading.sources += source
                learnt += source.number
                tracker.read(reading.sources)
            }
            if (learnt.isNotEmpty()) findings += ResearchFinding(query, learnt)
        }
        return findings
    }

    /** What one step has read so far, picked up where the step before it left off. */
    private class Reading(val limits: ResearchLimits) {
        val sources = mutableListOf<ResearchSource>()
        val searched = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val pages = mutableMapOf<String, Int>()
        val refused = mutableListOf<String>()

        /** Searches this step asked that answered a refusal rather than results. */
        var refusals = 0

        constructor(gathered: ResearchGathered) : this(gathered.limits) {
            sources += gathered.sources
            searched += gathered.searched
            seen += gathered.seen
            pages += gathered.pages
            refused += gathered.refused
        }

        val full get() = sources.size >= limits.maxSources

        fun gathered(findings: List<ResearchFinding>, level: Int) =
            ResearchGathered(sources.toList(), findings, searched.toList(), seen.toList(), pages.toMap(), level, limits, refused.toList())
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
    fun listFrom(said: String): List<String> {
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
    fun withSources(report: String, sources: List<ResearchSource>): String {
        if (sources.isEmpty()) return report
        val kept = report.substringBefore("\n## Sources").trimEnd()
        return buildString {
            append(kept)
            append("\n\n## Sources\n\n")
            sources.forEach { append(it.number).append(". [").append(it.title.replace("]", ")")).append("](").append(it.url).append(")\n") }
        }.trimEnd()
    }

    /**
     * Keeps the research row up to date, where there is one.
     *
     * Each step is a call of its own, so a tracker starts from what the row
     * already says - the plan, what was searched, the sources - rather than
     * from nothing, and the page goes on showing one research rather than
     * five that each forgot the one before.
     */
    private inner class Tracker(private val researchId: Long?, stage: ResearchStage) {
        private var now = ResearchProgress(step = "Starting", stage = stage)
        private var sources: List<ResearchSource> = emptyList()

        init {
            researchId?.let { researches.findByIdOrNull(it) }?.let { held ->
                progressFrom(held.progress)?.let { now = it.copy(stage = stage) }
                sources = sourcesFrom(held.foundSources)
            }
        }

        fun step(said: String) = write(now.copy(step = said))
        fun planned(plan: List<String>) = write(now.copy(step = "Planned ${plan.size} questions", subQuestions = plan))
        fun searching(searched: List<String>, level: Int) =
            write(now.copy(step = "Searching: ${searched.last()}", searched = searched.toList(), level = level))
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

    /*
     * Read as trees, here and below: the application's mapper does not bind a
     * Kotlin constructor, and what an older server wrote should read as what
     * it has rather than stop the research.
     */
    private fun progressFrom(held: String?): ResearchProgress? {
        val read = held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isObject } ?: return null
        fun texts(key: String) = read.path(key).mapNotNull { one -> one.takeIf { it.isTextual }?.stringValue() }
        return ResearchProgress(
            step = read.path("step").takeIf { it.isTextual }?.stringValue().orEmpty(),
            subQuestions = texts("subQuestions"),
            searched = texts("searched"),
            level = read.path("level").takeIf { it.isNumber }?.intValue() ?: 0,
            sourcesRead = read.path("sourcesRead").takeIf { it.isNumber }?.intValue() ?: 0,
        )
    }

    private fun sourcesFrom(held: String?): List<ResearchSource> =
        held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }?.let(::sourcesOf).orEmpty()

    /** What a step handed on, as the next one takes it; null where it is not that. */
    fun gatheredFrom(node: JsonNode?): ResearchGathered? {
        if (node == null || !node.isObject) return null
        fun texts(key: String) = node.path(key).mapNotNull { one -> one.takeIf { it.isTextual }?.stringValue() }
        fun number(at: JsonNode, key: String, default: Int) = at.path(key).takeIf { it.isNumber }?.intValue() ?: default
        val limits = node.path("limits")
        return ResearchGathered(
            sources = sourcesOf(node.path("sources")),
            findings = node.path("findings").mapNotNull { one ->
                val query = one.path("query").takeIf { it.isTextual }?.stringValue() ?: return@mapNotNull null
                ResearchFinding(query, one.path("sources").mapNotNull { n -> n.takeIf { it.isNumber }?.intValue() })
            },
            searched = texts("searched"),
            seen = texts("seen"),
            pages = node.path("pages").properties()
                .mapNotNull { (host, n) -> n.takeIf { it.isNumber }?.let { host to it.intValue() } }
                .toMap(),
            level = number(node, "level", 1).coerceAtLeast(1),
            limits = ResearchLimits(
                resultsPerQuery = number(limits, "resultsPerQuery", 5).coerceAtLeast(1),
                maxSources = number(limits, "maxSources", 10).coerceIn(1, tuning[Tunable.RESEARCH_SOURCES_MOST]),
                maxPagesPerSource = number(limits, "maxPagesPerSource", 1).coerceAtLeast(1),
            ),
            refused = texts("refused"),
        )
    }

    private fun sourcesOf(list: JsonNode): List<ResearchSource> = list.mapNotNull { one ->
        val url = one.path("url").takeIf { it.isTextual }?.stringValue() ?: return@mapNotNull null
        ResearchSource(
            number = one.path("number").takeIf { it.isNumber }?.intValue() ?: return@mapNotNull null,
            title = one.path("title").takeIf { it.isTextual }?.stringValue() ?: url,
            url = url,
            kind = one.path("kind").takeIf { it.isTextual }?.stringValue() ?: "WEB",
            notes = one.path("notes").takeIf { it.isTextual }?.stringValue().orEmpty(),
        )
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
