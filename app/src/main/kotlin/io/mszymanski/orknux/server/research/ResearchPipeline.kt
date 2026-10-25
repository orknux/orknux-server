package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO

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
    /** Whether Gather looks for images the report may place; off unless asked for. */
    val includeImages: Boolean = false,
)

/**
 * One picture a research kept, numbered as the report places it: what it is
 * said to be, where it was fetched from and the page it was found on or for,
 * and the artifact its bytes were saved as - which is where the PDF reads them
 * from and the research page shows them from. Never fetched again: what was
 * kept is what is laid out.
 */
data class ResearchImage(
    val number: Int,
    val caption: String,
    /** Where the bytes came from: an address, or the Artifacts page's link to the document they were in. */
    val url: String,
    /** The page it was found on or for, where that is known. */
    val page: String?,
    val contentType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val artifactId: Long,
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
    /** The pictures Gather kept, for Write to be told of and Save to lay out. */
    val images: List<ResearchImage> = emptyList(),
)

/** Which of a mode workflow's steps a research is in. */
enum class ResearchStage {
    PLAN,
    GATHER,
    FOLLOW_UP,
    WRITE,
    /** A second model reads the report and the writer revises it; only where the research asked for it. */
    REVIEW,
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
    /**
     * The page or document being read this moment, before the model has noted
     * it: what the page draws as the row on its way, so a source a slow model
     * spends a minute on is on the screen for that minute rather than after it.
     * Not a source yet - one the model notes nothing in never becomes one.
     */
    val reading: ResearchReading? = null,
)

/** A page or document on its way to being a source; see [ResearchProgress.reading]. */
data class ResearchReading(val title: String, val url: String, val kind: String)

/** What a research came to. */
data class ResearchFound(val report: String, val sources: List<ResearchSource>)

/** One thing a reviewer found wrong with a report, and what to do about it. */
data class ResearchReviewIssue(
    /** UNSUPPORTED, MISSING, CITATION, CONTRADICTION, FORMATTING or OTHER. */
    val kind: String,
    val issue: String,
    val fix: String,
)

/**
 * One round of review: what the reviewer made of the report, and whether the
 * writer revised it - with how long it was before and after, which is what
 * the page says changed.
 */
data class ResearchReviewRound(
    val round: Int,
    /** ACCEPT or REVISE. */
    val verdict: String,
    val summary: String,
    val issues: List<ResearchReviewIssue>,
    val revised: Boolean = false,
    val charactersBefore: Int = 0,
    val charactersAfter: Int? = null,
)

/** What the Review step hands Save: the report as the review left it, and every round. */
data class ResearchReviewed(val report: String, val rounds: List<ResearchReviewRound>)

/**
 * What one research step says it did, for the run's log under the step's own
 * node: each search and what it gave, each page read or skipped and why, the
 * follow-up questions asked, the file saved.
 *
 * Handed back the way a function's `orknux.log` lines are - on its answer,
 * each line its level and its words - and written to the run's log by the
 * action node that called it, so the run page shows a research step's lines
 * where it shows any function's. Facts only: a skip says which rule skipped
 * it, a refusal is quoted as the search worded it, and nothing guesses why.
 *
 * Bounded by [most], the number a function's own log lines are bounded by; a
 * step that would say more ends on a line saying how many it left out.
 */
class ResearchLog(private val most: Int) {
    private val held = mutableListOf<String>()
    private var left = 0

    fun info(line: String) = add("info $line")
    fun warn(line: String) = add("warn $line")

    private fun add(line: String) {
        if (most <= 0) return
        if (held.size < most) held += line else left += 1
    }

    /** What was said, in order, as `level words`. */
    fun lines(): List<String> =
        if (left == 0) {
            held.toList()
        } else {
            held + "info $left more ${if (left == 1) "line was" else "lines were"} not kept: this installation keeps $most a step"
        }

    companion object {
        /** Says nothing: a call whose lines nobody reads. */
        val NONE = ResearchLog(0)
    }
}

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
    private val savedArtifacts: SavedArtifacts,
    private val researches: ResearchRepository,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
    /** The reviewer's and the writer's own instructions, as their pages have them; see [review]. */
    private val agents: io.mszymanski.orknux.server.agent.AgentRepository,
    /** Where the writer's revision is shown while it is written. */
    private val drafts: ResearchDrafts,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** All four steps, one after another: what `research_run` does. */
    fun run(asked: ResearchAsked, knobs: ResearchKnobs, said: ResearchLog = ResearchLog.NONE): ResearchFound {
        val plan = plan(asked, knobs.subQuestions, said)
        val gathered = gather(asked, plan, knobs.limits, said)
        val deepened = followUp(asked, gathered, knobs.breadth, knobs.depth, said)
        val found = write(asked, deepened, said)
        note(asked.researchId, ResearchStage.WRITE, "Finished")
        return found
    }

    /** Plan: the question as [subQuestions] search queries. */
    fun plan(asked: ResearchAsked, subQuestions: Int, said: ResearchLog = ResearchLog.NONE): List<String> {
        val tracker = Tracker(asked.researchId, ResearchStage.PLAN)
        tracker.step("Planning the research")
        val plan = listFrom(
            ask(asked, PLAN, buildString {
                appendLine("Question: ${asked.question}")
                append("Write exactly $subQuestions search queries.")
            }),
        ).take(subQuestions).ifEmpty { listOf(asked.question) }
        tracker.planned(plan)
        plan.forEach { said.info("Planned query: $it") }
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
    fun gather(asked: ResearchAsked, queries: List<String>, limits: ResearchLimits, said: ResearchLog = ResearchLog.NONE): ResearchGathered {
        val tracker = Tracker(asked.researchId, ResearchStage.GATHER)
        val reading = Reading(limits, said)

        val held = saved.findAllById(asked.documentsInclude).filter { it.workspaceId == asked.workspaceId }
        for (document in held) {
            if (reading.stopped()) break
            tracker.reading(document.name, "/api/artifacts/${document.id}", "DOCUMENT")
            val text = documents.text(document)?.trim().orEmpty()
            if (text.isEmpty()) {
                said.info("Skipped document ${document.name}: it has no text to read")
                continue
            }
            val pages = text.chunked(tuning[Tunable.RESEARCH_PAGE_CHARACTERS]).take(limits.maxPagesPerSource)
            val notes = noted(asked, asked.question, document.name, pages.joinToString("\n\n"))
            if (notes == null) {
                tracker.notReading()
                said.info("Skipped document ${document.name}: the model noted nothing in it on the question")
                continue
            }
            reading.sources += ResearchSource(reading.sources.size + 1, document.name, "/api/artifacts/${document.id}", "DOCUMENT", notes)
            said.info("Read document ${document.name} (${pages.size} ${if (pages.size == 1) "page" else "pages"}) as source ${reading.sources.size}")
            tracker.read(reading.sources)
            if (asked.includeImages) {
                reading.pictures += documents.pictures(document, tuning[Tunable.RESEARCH_IMAGES_MOST]).map { Picture(it.hit, it.bytes, fromDocument = true) }
            }
        }

        if (!asked.webSearch) {
            said.info("Web search is off: reading only the documents and the websites to keep to")
            visit(asked, reading, tracker)
            if (asked.includeImages) reading.images += pictures(asked, reading, tracker)
            return reading.gathered(emptyList(), level = 1)
        }
        val distinct = queries.distinct()
        said.info("${distinct.size} ${if (distinct.size == 1) "query" else "queries"} to search: ${distinct.joinToString("; ")}")
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
        if (asked.includeImages) reading.images += pictures(asked, reading, tracker)
        return reading.gathered(findings, level = 1)
    }

    /**
     * The pictures a research keeps, where it was asked to look for them: the
     * picture search's answers for the question where the web is searched,
     * then the pictures on the pages and documents it read as sources. Each is
     * fetched once and kept only where it is a PNG, JPEG or GIF - by its bytes,
     * not only by what it was served as - no larger than [Tunable.RESEARCH_IMAGE_MB]
     * and no smaller than [Tunable.RESEARCH_IMAGE_PIXELS] on its shorter side,
     * until there are [Tunable.RESEARCH_IMAGES_MOST]. A kept picture is saved
     * on the Artifacts page there and then, which is where its bytes are held
     * from here on: the report lays out what was kept and fetches nothing.
     * Every picture tried is said in the step's log, kept or skipped and by
     * which rule.
     */
    private fun pictures(asked: ResearchAsked, reading: Reading, tracker: Tracker): List<ResearchImage> {
        val said = reading.said
        val most = tuning[Tunable.RESEARCH_IMAGES_MOST]
        val mostMb = tuning[Tunable.RESEARCH_IMAGE_MB]
        val smallest = tuning[Tunable.RESEARCH_IMAGE_PIXELS]
        // A research that read nothing writes no report, and keeping pictures for it would be files for nothing.
        if (reading.sources.isEmpty()) {
            said.info("Looked for no images: no source was read")
            return emptyList()
        }
        tracker.step("Looking for images")

        val tried = mutableListOf<Picture>()
        if (asked.webSearch) {
            val query = scoped(asked.question.trim().lineSequence().first().take(MOST_IMAGE_QUERY), asked.websitesInclude)
            web.searchImages(asked.workspaceId, query, most * 2)
                .onSuccess { found ->
                    said.info("Searched images for \"$query\": ${found.size} ${if (found.size == 1) "result" else "results"}")
                    tried += found.map { Picture(it) }
                }
                .onFailure { said.warn("Searched images for \"$query\": the search answered \"${it.message?.trim().orEmpty()}\"") }
        } else {
            said.info("Web search is off: looking for images only on the pages and documents read")
        }
        tried += reading.pictures

        val kept = mutableListOf<ResearchImage>()
        val seen = mutableSetOf<String>()
        for (picture in tried) {
            if (kept.size >= most) {
                said.info("Stopped looking for images at ${kept.size}, the most a research keeps")
                break
            }
            val url = picture.hit.url
            if (!seen.add(url)) continue
            fun skip(why: String) = said.info("Skipped image $url: $why")

            if (!picture.fromDocument) {
                val hosts = listOfNotNull(hostOf(url), picture.hit.page?.let(::hostOf))
                hosts.firstOrNull { !allowed(it, emptyList(), asked.websitesExclude) }?.let {
                    skip("$it is among the websites to avoid")
                    continue
                }
                val judged = picture.hit.page?.let(::hostOf) ?: hostOf(url)
                if (judged == null) {
                    skip("it is not an address")
                    continue
                }
                if (!allowed(judged, asked.websitesInclude, emptyList())) {
                    skip("$judged is not among the websites to keep to")
                    continue
                }
            }

            tracker.step("Fetching an image")
            val fetched = picture.bytes?.let { ImageFetched.Fetched(it, PNG) } ?: web.image(asked.workspaceId, url)
            val (bytes, served) = when (fetched) {
                is ImageFetched.Fetched -> fetched.bytes to fetched.contentType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }
                is ImageFetched.Unfetched -> {
                    said.warn("Could not fetch image $url: ${fetched.reason}")
                    continue
                }
            }
            if (served == null || !served.startsWith("image/")) {
                skip("it was served as ${served ?: "nothing it named"}, not an image")
                continue
            }
            val type = typeOf(bytes)
            if (type == null) {
                skip("it was served as $served, and its bytes are not a PNG, JPEG or GIF")
                continue
            }
            if (bytes.size > mostMb * 1024L * 1024L) {
                skip("it is ${megabytes(bytes.size.toLong())} MB, larger than the $mostMb MB an image may be")
                continue
            }
            val size = sizeOf(bytes)
            if (size == null) {
                skip("its bytes could not be read as a picture")
                continue
            }
            val (width, height) = size
            if (minOf(width, height) < smallest) {
                skip("it is $width x $height pixels, smaller than $smallest on its shorter side")
                continue
            }

            val caption = WorkspaceResearchWeb.captionOf(picture.hit.caption) ?: "Image from ${picture.hit.page?.let(::hostOf) ?: hostOf(url) ?: "a document"}"
            val number = kept.size + 1
            val saving = savedArtifacts.save(
                asked.workspaceId, ResearchDelivery.SAVED_BY,
                ResearchDelivery.fileName(caption, EXTENSIONS.getValue(type)),
                "Research image: $caption",
                Base64.getEncoder().encodeToString(bytes), base64 = true,
            )
            val artifact = when (saving) {
                is SavedArtifacts.Saving.Saved -> saving.artifact
                is SavedArtifacts.Saving.Refused -> {
                    said.warn("Could not keep image $url: ${saving.reason}")
                    continue
                }
            }
            kept += ResearchImage(
                number = number,
                caption = caption,
                url = url,
                page = picture.hit.page,
                contentType = type,
                sizeBytes = bytes.size.toLong(),
                width = width,
                height = height,
                artifactId = requireNotNull(artifact.id),
            )
            said.info("Kept image $number ($url) as ${artifact.name}, artifact ${artifact.id}: $width x $height pixels, ${kilobytes(bytes.size.toLong())} KB, \"$caption\"")
            tracker.pictured(kept)
        }
        if (kept.isEmpty()) {
            said.info(if (seen.isEmpty()) "Kept no image: none was found" else "Kept no image: none of the ${seen.size} tried was one to keep")
        }
        return kept
    }

    /**
     * Without web search: each website to keep to read at the address it was
     * typed as, with no search asked for. What it finds leads nowhere further,
     * because a follow-up question is a search.
     */
    private fun visit(asked: ResearchAsked, reading: Reading, tracker: Tracker) {
        val said = reading.said
        for (entry in asked.websitesInclude) {
            if (reading.stopped()) break
            val url = entry.trim().let { if ("://" in it) it else "https://$it" }
            val host = hostOf(url)
            if (host == null) {
                said.info("Skipped $entry: it is not an address")
                continue
            }
            if (!taken(reading, url, host, emptyList(), asked.websitesExclude)) continue

            tracker.reading(host, url, "WEB")
            val read = when (val answered = web.read(asked.workspaceId, url)) {
                is PageRead.Read -> answered
                is PageRead.Unread -> {
                    tracker.notReading()
                    said.warn("Could not read $url: ${answered.reason}")
                    continue
                }
            }
            val page = read.text.take(tuning[Tunable.RESEARCH_PAGE_CHARACTERS])
            if (page.isBlank()) {
                tracker.notReading()
                said.info("Skipped $url: the page has no text")
                continue
            }
            val title = read.title?.trim()?.ifEmpty { null } ?: host
            tracker.reading(title, url, "WEB")
            val notes = noted(asked, asked.question, title, page)
            if (notes == null) {
                tracker.notReading()
                said.info("Skipped $url: the model noted nothing in it on the question")
                continue
            }
            reading.sources += ResearchSource(reading.sources.size + 1, title, url, "WEB", notes)
            said.info("Read $title ($url) as source ${reading.sources.size}")
            tracker.read(reading.sources)
            if (asked.includeImages) reading.pictures += read.images.map { Picture(it) }
        }
    }

    /**
     * Follow up: from each finding, [breadth] follow-up questions - halved at
     * every level after the first below the plan - searched and read, down to
     * [depth] levels in all. What was gathered is handed back as it was where
     * there is nothing deeper to go.
     */
    fun followUp(
        asked: ResearchAsked,
        gathered: ResearchGathered,
        breadth: Int,
        depth: Int,
        said: ResearchLog = ResearchLog.NONE,
    ): ResearchGathered {
        val reading = Reading(gathered, said)
        var findings = gathered.findings
        var level = gathered.level + 1
        val none = when {
            !asked.webSearch -> "No follow-up questions: web search is off"
            level > depth -> "No follow-up questions: the depth set is $depth, and level ${gathered.level} is searched"
            findings.isEmpty() -> "No follow-up questions: no search so far led to a source"
            reading.full -> "No follow-up questions: ${reading.sources.size} sources are read, the most this research reads"
            else -> null
        }
        if (none != null) {
            said.info(none)
            return gathered
        }

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
            if (queries.isEmpty()) {
                said.info("No new follow-up questions at level $level")
                break
            }
            queries.forEach { said.info("Follow-up question at level $level: $it") }
            findings = search(asked, reading, queries, level, tracker)
            level += 1
        }
        return reading.gathered(findings, level = level - 1)
    }

    /** Write: the report from the notes, citing them, with the numbered list of sources after it. */
    fun write(asked: ResearchAsked, gathered: ResearchGathered, said: ResearchLog = ResearchLog.NONE): ResearchFound {
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
            append(writerNotes(gathered))
        })
        val report = withSources(written.trim(), sources)
        said.info("Wrote the report from ${sources.size} ${if (sources.size == 1) "source" else "sources"}: ${report.length} characters")
        tracker.step("Report written")
        return ResearchFound(report, sources)
    }

    /**
     * Review: a second model reads the report against what it was written
     * from, and where it asks for changes the writer makes them - at most
     * [rounds] times, then the report goes on as it stands.
     *
     * The reviewer is the workspace's Research reviewer and the writer its
     * Research writer, by their instructions on the Agents page; the reviewer
     * thinks with the model the research was started with for reviewing, the
     * writer with the research's own. The writer is handed the conversation it
     * wrote the report in - the same request, the report as its answer - with
     * the reviewer's findings after it, so it revises its own work rather than
     * writing a new report from nothing. Its revision is shown on the page as
     * it is written, in place of the draft.
     */
    fun review(
        asked: ResearchAsked,
        reviewerModelId: Long,
        plan: List<String>,
        gathered: ResearchGathered,
        draft: String,
        excluded: String,
        rounds: Int,
        said: ResearchLog = ResearchLog.NONE,
    ): ResearchReviewed {
        val tracker = Tracker(asked.researchId, ResearchStage.REVIEW)
        val reviewer = instructionsOf(asked.workspaceId, ResearchModes.REVIEWER)
        val writer = instructionsOf(asked.workspaceId, ResearchModes.WRITER)
        val request = writerRequest(asked, excluded, gathered)
        var report = draft.trim()
        val held = mutableListOf<ResearchReviewRound>()
        for (round in 1..rounds.coerceAtLeast(1)) {
            tracker.step(if (rounds > 1) "Reviewing the report, round $round of $rounds" else "Reviewing the report")
            val answer = when (
                val reviewed = models.complete(
                    reviewerModelId,
                    listOf(ChatTurn("system", reviewer), ChatTurn("user", reviewMaterial(asked.question, plan, gathered, report))),
                )
            ) {
                is ChatCompletion.Answered -> reviewed.content
                is ChatCompletion.Failed -> throw ResearchModelFailedException("The reviewer did not answer: ${reviewed.reason}")
                else -> throw ResearchModelFailedException("The reviewer answered with something other than text")
            }
            val verdict = verdictFrom(round, answer, report.length)
                ?: throw ResearchModelFailedException("The reviewer did not answer with a verdict: ${answer.take(200)}")
            said.info(
                "Review round $round: ${verdict.verdict.lowercase()}, ${verdict.issues.size} " +
                    (if (verdict.issues.size == 1) "issue" else "issues") +
                    verdict.summary.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty(),
            )
            if (verdict.verdict == ACCEPTED || verdict.issues.isEmpty()) {
                held += verdict
                noteReview(asked.researchId, held)
                break
            }

            tracker.step(if (rounds > 1) "Revising the report, round $round of $rounds" else "Revising the report")
            noteReview(asked.researchId, held + verdict)
            val showing = drafts.revising(asked.researchId)
            val turns = listOf(
                ChatTurn("system", writer),
                ChatTurn("user", request),
                ChatTurn("assistant", report),
                ChatTurn("user", revisionAsked(verdict)),
            )
            val revised = when (val written = models.stream(asked.modelId, turns) { showing(it) }) {
                is ChatCompletion.Answered -> written.content.trim()
                is ChatCompletion.Failed -> throw ResearchModelFailedException("The writer did not revise the report: ${written.reason}")
                else -> throw ResearchModelFailedException("The writer answered with something other than text")
            }
            if (revised.isEmpty()) throw ResearchModelFailedException("The writer answered the review with nothing")
            said.info("Revised the report: ${report.length} characters before, ${revised.length} after")
            held += verdict.copy(revised = true, charactersAfter = revised.length)
            report = revised
            noteReview(asked.researchId, held)
        }
        tracker.step("Report reviewed")
        return ResearchReviewed(report, held)
    }

    /** The same request the writer node is given, so a revision is asked in the conversation the report was written in. */
    private fun writerRequest(asked: ResearchAsked, excluded: String, gathered: ResearchGathered): String = buildString {
        appendLine("Question: ${asked.question}")
        appendLine()
        appendLine("Register: ${asked.language.name}")
        appendLine("Documents not to use: ${excluded.ifBlank { "none" }}")
        appendLine()
        appendLine("Sources, by number:")
        appendLine()
        append(writerNotes(gathered))
    }

    /** What the reviewer is shown: the question, the plan it was broken into, every source and its notes, and the report. */
    private fun reviewMaterial(question: String, plan: List<String>, gathered: ResearchGathered, report: String): String = buildString {
        appendLine("Question: $question")
        appendLine()
        appendLine("Plan, the parts the question was broken into:")
        (plan.ifEmpty { listOf(question) }).forEach { appendLine("- $it") }
        appendLine()
        appendLine("Sources, by number, with the notes taken on each:")
        appendLine()
        appendLine(notesOf(gathered.sources))
        appendLine()
        appendLine("Report:")
        appendLine()
        append(report)
    }

    /** What the writer is told the reviewer found: each issue on a line of its own, with what to do about it. */
    private fun revisionAsked(verdict: ResearchReviewRound): String = buildString {
        appendLine(REVISE)
        appendLine()
        verdict.summary.takeIf { it.isNotBlank() }?.let { appendLine(it).appendLine() }
        verdict.issues.forEachIndexed { at, one ->
            append(at + 1).append(". [").append(one.kind.lowercase()).append("] ").append(one.issue)
            if (one.fix.isNotBlank()) append(" Fix: ").append(one.fix)
            appendLine()
        }
    }

    /**
     * The reviewer's answer as a round: the JSON it was asked for, found where
     * it is in what it said, read field by field. Null where there is no
     * verdict in it to read.
     */
    fun verdictFrom(round: Int, said: String, length: Int): ResearchReviewRound? {
        val start = said.indexOf('{')
        val end = said.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val read = runCatching { mapper.readTree(said.substring(start, end + 1)) }.getOrNull()?.takeIf { it.isObject } ?: return null
        fun text(node: tools.jackson.databind.JsonNode, vararg keys: String) =
            keys.firstNotNullOfOrNull { key -> node.path(key).takeIf { it.isTextual }?.stringValue()?.trim() }.orEmpty()
        val verdict = text(read, "verdict").uppercase().takeIf { it == ACCEPTED || it == REVISED } ?: return null
        val issues = read.path("issues").takeIf { it.isArray }?.mapNotNull { one ->
            val issue = text(one, "issue", "text", "problem").ifEmpty { return@mapNotNull null }
            val kind = text(one, "kind", "type").uppercase().takeIf { it in ISSUE_KINDS } ?: "OTHER"
            ResearchReviewIssue(kind, issue, text(one, "fix", "suggestion"))
        }.orEmpty()
        return ResearchReviewRound(round, verdict, text(read, "summary"), issues, charactersBefore = length)
    }

    private fun noteReview(researchId: Long?, rounds: List<ResearchReviewRound>) {
        val id = researchId ?: return
        runCatching { researches.noteReview(id, mapper.writeValueAsString(rounds)) }
            .onFailure { log.warn("Could not note the review of research {}", id, it) }
    }

    /** An agent's instructions as its page has them, or as it was seeded where the workspace has no such agent. */
    private fun instructionsOf(workspaceId: Long, seeded: ResearchModes.SeededAgent): String =
        agents.findByWorkspaceIdAndName(workspaceId, seeded.name)?.systemPrompt?.takeIf { it.isNotBlank() } ?: seeded.instructions

    /** The rounds a research's Review step has said so far, as a row holds them. */
    fun reviewRoundsFrom(held: String?): List<ResearchReviewRound> =
        held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }?.mapNotNull { one ->
            val verdict = one.path("verdict").takeIf { it.isTextual }?.stringValue() ?: return@mapNotNull null
            fun text(at: tools.jackson.databind.JsonNode, key: String) = at.path(key).takeIf { it.isTextual }?.stringValue().orEmpty()
            ResearchReviewRound(
                round = one.path("round").takeIf { it.isNumber }?.intValue() ?: 0,
                verdict = verdict,
                summary = text(one, "summary"),
                issues = one.path("issues").takeIf { it.isArray }?.toList()?.map { issue ->
                    ResearchReviewIssue(text(issue, "kind"), text(issue, "issue"), text(issue, "fix"))
                } ?: emptyList(),
                revised = one.path("revised").takeIf { it.isBoolean }?.booleanValue() ?: false,
                charactersBefore = one.path("charactersBefore").takeIf { it.isNumber }?.intValue() ?: 0,
                charactersAfter = one.path("charactersAfter").takeIf { it.isNumber }?.intValue(),
            )
        }.orEmpty()

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

    /**
     * What the writing reads: the sources' notes, and after them the pictures
     * kept, where there are any. Said in the notes rather than in the writer
     * agent's instructions, so a writer seeded before there were pictures is
     * told of them too - and told only of these, by number, so a picture it
     * places is one that was kept and never one it made up.
     */
    fun writerNotes(gathered: ResearchGathered): String {
        val notes = notesOf(gathered.sources)
        if (gathered.images.isEmpty()) return notes
        return notes + "\n\n" + imageNotes(gathered.images)
    }

    /** The pictures kept, as the writing is told of them. */
    fun imageNotes(images: List<ResearchImage>): String = buildString {
        appendLine("Images you may place in the report, by number:")
        appendLine()
        images.forEach { image ->
            append("[image ").append(image.number).append("] ").append(image.caption)
            image.page?.let { append(" - from ").append(it) }
            appendLine()
        }
        appendLine()
        append(
            "Place an image only where it helps the reader, on a line of its own, as ![its caption]($IMAGE_SCHEME:n) " +
                "with its number - ![${images.first().caption.replace("]", ")")}]($IMAGE_SCHEME:${images.first().number}) for the first. " +
                "Use no number that is not listed here, and no other picture or address: the report lays out these and nothing else.",
        )
    }

    /**
     * Says a step on the research where there is one: what [ResearchDelivery] writes while it saves.
     * A null [stage] keeps the one already written, for a step that is not one of the five.
     */
    fun note(researchId: Long?, stage: ResearchStage?, said: String) {
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
        val told = reading.said
        for (query in queries) {
            if (reading.stopped()) break
            reading.searched += query
            tracker.searching(reading.searched, level)
            val found = web.search(asked.workspaceId, scoped(query, asked.websitesInclude), limits.resultsPerQuery)
                .getOrElse { why ->
                    val said = why.message?.trim()?.ifEmpty { null } ?: "no results and no reason"
                    reading.refusals += 1
                    if (said !in reading.refused) reading.refused += said
                    tracker.step("The search answered: $said")
                    told.warn("Searched \"$query\": the search answered \"$said\"")
                    null
                }
            if (found != null) told.info("Searched \"$query\": ${found.size} ${if (found.size == 1) "result" else "results"}")
            val learnt = mutableListOf<Int>()
            for (hit in found.orEmpty().take(limits.resultsPerQuery)) {
                if (reading.stopped()) break
                val host = hostOf(hit.url)
                if (host == null) {
                    told.info("Skipped ${hit.url}: it is not an address")
                    continue
                }
                if (!taken(reading, hit.url, host, asked.websitesInclude, asked.websitesExclude)) continue

                tracker.reading(hit.title, hit.url, "WEB")
                val read = web.read(asked.workspaceId, hit.url)
                val page = when (read) {
                    is PageRead.Read -> read.text
                    // A page that cannot be fetched still has what the search said of it.
                    is PageRead.Unread -> {
                        told.warn("Could not read ${hit.url}: ${read.reason}; noting what the search said of it instead")
                        hit.snippet
                    }
                }.take(tuning[Tunable.RESEARCH_PAGE_CHARACTERS])
                if (page.isBlank()) {
                    tracker.notReading()
                    told.info("Skipped ${hit.url}: there is no text to read")
                    continue
                }
                val notes = noted(asked, query, hit.title, page)
                if (notes == null) {
                    tracker.notReading()
                    told.info("Skipped ${hit.url}: the model noted nothing in it on the question")
                    continue
                }
                val source = ResearchSource(reading.sources.size + 1, hit.title, hit.url, "WEB", notes)
                reading.sources += source
                learnt += source.number
                told.info("Read ${hit.title} (${hit.url}) as source ${source.number}")
                tracker.read(reading.sources)
                if (asked.includeImages && read is PageRead.Read) reading.pictures += read.images.map { Picture(it) }
            }
            if (learnt.isNotEmpty()) findings += ResearchFinding(query, learnt)
        }
        return findings
    }

    /**
     * Whether an address is one to read: where a rule passes it over, which
     * one is said in the step's log; where it is taken, it is counted against
     * its website's pages.
     */
    private fun taken(reading: Reading, url: String, host: String, include: List<String>, exclude: List<String>): Boolean {
        val pages = reading.pages[host] ?: 0
        val skipped = when {
            !allowed(host, emptyList(), exclude) -> "$host is among the websites to avoid"
            !allowed(host, include, emptyList()) -> "$host is not among the websites to keep to"
            // Taken once seen, as it always was, whether or not its website has a page left.
            !reading.seen.add(url) -> "already looked at"
            pages >= reading.limits.maxPagesPerSource ->
                "$pages ${if (pages == 1) "page" else "pages"} of $host taken, the most per source"
            else -> null
        }
        if (skipped != null) {
            reading.said.info("Skipped $url: $skipped")
            return false
        }
        reading.pages[host] = pages + 1
        return true
    }

    /** What one step has read so far, picked up where the step before it left off. */
    private class Reading(val limits: ResearchLimits, val said: ResearchLog = ResearchLog.NONE) {
        val sources = mutableListOf<ResearchSource>()
        val searched = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val pages = mutableMapOf<String, Int>()
        val refused = mutableListOf<String>()

        /** Pictures on what was read, to be tried once the reading is done; only where pictures were asked for. */
        val pictures = mutableListOf<Picture>()

        /** The pictures kept. */
        val images = mutableListOf<ResearchImage>()

        /** Searches this step asked that answered a refusal rather than results. */
        var refusals = 0

        constructor(gathered: ResearchGathered, said: ResearchLog) : this(gathered.limits, said) {
            sources += gathered.sources
            searched += gathered.searched
            seen += gathered.seen
            pages += gathered.pages
            refused += gathered.refused
            images += gathered.images
        }

        val full get() = sources.size >= limits.maxSources

        private var saidFull = false

        /** Whether the step has read as many sources as it may; said in its log the first time. */
        fun stopped(): Boolean {
            if (full && !saidFull) {
                saidFull = true
                said.info("Stopped reading at ${sources.size} sources, the most this research reads")
            }
            return full
        }

        fun gathered(findings: List<ResearchFinding>, level: Int) =
            ResearchGathered(sources.toList(), findings, searched.toList(), seen.toList(), pages.toMap(), level, limits, refused.toList(), images.toList())
    }

    /** A picture to try: where it is and what it is said to be, and its bytes already where a document held them. */
    private class Picture(val hit: ImageHit, val bytes: ByteArray? = null, val fromDocument: Boolean = false)

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
    private inner class Tracker(private val researchId: Long?, stage: ResearchStage?) {
        private var now = ResearchProgress(step = "Starting", stage = stage)
        private var sources: List<ResearchSource> = emptyList()

        init {
            researchId?.let { researches.findByIdOrNull(it) }?.let { held ->
                progressFrom(held.progress)?.let { now = it.copy(stage = stage ?: it.stage) }
                sources = sourcesFrom(held.foundSources)
            }
        }

        fun step(said: String) = write(now.copy(step = said, reading = null))

        /** A page or document being read, on the page as a row on its way before the model has noted it. */
        fun reading(title: String, url: String, kind: String) =
            write(now.copy(step = "Reading $title", reading = ResearchReading(title, url, kind)))

        /** What was being read came to nothing: the model noted nothing in it, or there was nothing to note. */
        fun notReading() {
            if (now.reading != null) write(now.copy(reading = null))
        }
        fun planned(plan: List<String>) = write(now.copy(step = "Planned ${plan.size} questions", subQuestions = plan))
        fun searching(searched: List<String>, level: Int) =
            write(now.copy(step = "Searching: ${searched.last()}", searched = searched.toList(), level = level))
        fun read(found: List<ResearchSource>) {
            sources = found.toList()
            write(now.copy(sourcesRead = found.size, reading = null))
        }

        /** The pictures kept so far, on the research as they are kept, so the page shows them while it runs. */
        fun pictured(kept: List<ResearchImage>) {
            val id = researchId ?: return
            runCatching { researches.noteImages(id, mapper.writeValueAsString(kept)) }
                .onFailure { log.warn("Could not note the images of research {}", id, it) }
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
            stage = read.path("stage").takeIf { it.isTextual }?.stringValue()
                ?.let { held -> ResearchStage.entries.firstOrNull { it.name == held } },
        )
    }

    /**
     * What a research row says once a run restarts it at [stage], as JSON for
     * [ResearchRepository.restarted]: what the steps before [stage] found is
     * kept, and what [stage] and the steps after it found is let go, because
     * they are about to find it again.
     *
     * Planning restarted keeps nothing. Gather keeps the plan's sub-questions
     * and starts its sources over. A later step keeps what [gathered] - the
     * earlier run's answer from the step before it - holds: the sources, the
     * searches and the images it handed on, which is exactly what the new run
     * carries over. A [stage] that is none of the five, a workflow somebody
     * drew their own way, keeps the row as it is and says it restarted.
     */
    fun restarted(held: Research, stage: ResearchStage?, gathered: ResearchGathered?): Triple<String, String, String> {
        val was = progressFrom(held.progress) ?: ResearchProgress(step = "")
        val sources = sourcesFrom(held.foundSources)
        val images = imagesFrom(held.foundImages)
        val (progress, keptSources, keptImages) = when (stage) {
            null -> Triple(was.copy(step = RESTARTING, reading = null), sources, images)
            ResearchStage.PLAN -> Triple(ResearchProgress(RESTARTING, stage = stage), emptyList(), emptyList())
            ResearchStage.GATHER ->
                Triple(ResearchProgress(RESTARTING, subQuestions = was.subQuestions, stage = stage), emptyList(), emptyList())
            else -> if (gathered == null) {
                Triple(was.copy(step = RESTARTING, stage = stage, reading = null), sources, images)
            } else {
                Triple(
                    ResearchProgress(
                        step = RESTARTING,
                        subQuestions = was.subQuestions,
                        searched = gathered.searched,
                        level = gathered.level,
                        sourcesRead = gathered.sources.size,
                        stage = stage,
                    ),
                    gathered.sources,
                    gathered.images.ifEmpty { images },
                )
            }
        }
        return Triple(mapper.writeValueAsString(progress), mapper.writeValueAsString(keptSources), mapper.writeValueAsString(keptImages))
    }

    /** The pictures a research row holds; none where it holds none, or something an older server wrote. */
    fun imagesFrom(held: String?): List<ResearchImage> =
        held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }?.let(::imagesOf).orEmpty()

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
            images = imagesOf(node.path("images")),
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

        const val ACCEPTED = "ACCEPT"
        const val REVISED = "REVISE"
        val ISSUE_KINDS = setOf("UNSUPPORTED", "MISSING", "CITATION", "CONTRADICTION", "FORMATTING", "OTHER")

        /** What the reviewer is told; the Research reviewer agent is seeded with it. */
        const val REVIEW = "You review research reports before they are published. You are given the question, the " +
            "plan it was broken into, the sources by number with the notes taken on each, and the report, which cites " +
            "them as [n]. Check that every claim is supported by the notes of the source it cites, that every part " +
            "of the plan is answered, that each citation points at a source that says what is claimed, that nothing " +
            "contradicts the sources, and that the report is well-formed Markdown with a # title and ## headings. " +
            "Answer with JSON and nothing else: {\"verdict\": \"accept\" or \"revise\", \"summary\": one sentence, " +
            "\"issues\": [{\"kind\": \"unsupported\", \"missing\", \"citation\", \"contradiction\" or \"formatting\", " +
            "\"issue\": what is wrong, quoting the passage, \"fix\": what to change}]}. Accept when nothing is worth " +
            "changing; ask to revise only for issues you can name."

        /** What the writer is told above the reviewer's findings. */
        const val REVISE = "A reviewer read your report and asks for these changes. Write the whole report again " +
            "with them made, in the same register and citing the same numbered sources. Answer with the report only."
        /** What a restarted research says it is doing until its first step says otherwise. */
        const val RESTARTING = "Restarting"

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
            "write a sources list; it is added after you. Where images are listed, place only those, as they say; " +
            "where none are, place none."

        /** What a report names a kept picture by, `![caption](image:2)`, until Save puts the picture there. */
        const val IMAGE_SCHEME = "image"

        /** How much of the question the picture search is asked: its first line, as a search engine takes a query. */
        private const val MOST_IMAGE_QUERY = 300

        private const val PNG = "image/png"

        /** The pictures a research keeps, which are what a PDF can lay out, and the extension each is saved under. */
        val EXTENSIONS = mapOf(PNG to "png", "image/jpeg" to "jpg", "image/gif" to "gif")

        /** What the bytes are, by their first few: a PNG, a JPEG or a GIF, or null for anything else. */
        fun typeOf(bytes: ByteArray): String? {
            fun at(i: Int) = if (bytes.size > i) bytes[i].toInt() and 0xFF else -1
            return when {
                at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> PNG
                at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
                at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code -> "image/gif"
                else -> null
            }
        }

        /** A picture's width and height, read from its header without drawing it; null where it cannot be read. */
        fun sizeOf(bytes: ByteArray): Pair<Int, Int>? = runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@use null
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

        private fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
        private fun kilobytes(bytes: Long) = maxOf(1L, (bytes + 512) / 1024)

        /** The pictures a research kept, as a step handed them on or the research row holds them. */
        fun imagesOf(list: JsonNode): List<ResearchImage> = list.mapNotNull { one ->
            fun text(key: String) = one.path(key).takeIf { it.isTextual }?.stringValue()
            fun number(key: String) = one.path(key).takeIf { it.isNumber }
            ResearchImage(
                number = number("number")?.intValue() ?: return@mapNotNull null,
                caption = text("caption") ?: return@mapNotNull null,
                url = text("url") ?: return@mapNotNull null,
                page = text("page"),
                contentType = text("contentType") ?: return@mapNotNull null,
                sizeBytes = number("sizeBytes")?.longValue() ?: 0,
                width = number("width")?.intValue() ?: 0,
                height = number("height")?.intValue() ?: 0,
                artifactId = number("artifactId")?.longValue() ?: return@mapNotNull null,
            )
        }

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
