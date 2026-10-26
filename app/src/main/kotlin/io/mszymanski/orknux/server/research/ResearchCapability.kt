package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.agent.WorkspaceChatModels
import io.mszymanski.orknux.server.embedded.EmbeddedCapability
import io.mszymanski.orknux.server.embedded.EmbeddedFunction
import io.mszymanski.orknux.server.embedded.EmbeddedParam
import io.mszymanski.orknux.server.embedded.EmbeddedTool
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * A research's steps, as functions a workflow's action nodes call. Issue #659.
 *
 * Functions rather than a node kind of their own, so the research modes are
 * ordinary workflows: each step - `research_plan`, `research_gather`,
 * `research_followUp`, `research_write`, `research_save` - is an action node
 * like any other, and its knobs are its parameters, written on the node where
 * anybody editing the workflow sees and changes them. Each hands the next what
 * it found as its output, under the name its node gives it, which is how every
 * node in this product hands anything on.
 *
 * A mode's workflow calls only Gather, Follow up and Save now: its Plan and
 * Write are agent nodes (see [ResearchModes]), so that the thinking is a model
 * on the canvas rather than one inside a function. `research_plan` and
 * `research_write` stay for a workflow drawn as the five actions and kept
 * that way, and Gather reads the planner agent's answer as well as Plan's
 * list; Save, handed what was gathered, puts the numbered sources after the
 * writer agent's draft.
 *
 * `research_run` is the first four in one call. It is what a mode's workflow
 * was before the steps were nodes, and a workflow somebody drew around it, or
 * a copy of one, still calls it.
 *
 * Not offered to agents: an agent that wants to research has the search and
 * the HTTP tools itself, and a tool that runs for many minutes inside one
 * turn is the wrong shape for a chat.
 */
@Component
class ResearchCapability(
    private val pipeline: ResearchPipeline,
    private val delivery: ResearchDelivery,
    private val researches: ResearchRepository,
    private val chatModels: WorkspaceChatModels,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
    private val reviewRounds: ResearchReviewRounds,
    private val followUps: ResearchFollowUps,
) : EmbeddedCapability {

    override val key = KEY
    override val name = "Research"

    override fun tools(): List<EmbeddedTool> = emptyList()

    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = PLAN,
            description = "A research's first step: breaks the question into search queries. Answers them as queries.",
            params = listOf(P_RESEARCH, P_QUESTION, P_MODEL, P_SUB_QUESTIONS),
        ),
        EmbeddedFunction(
            name = GATHER,
            description = "A research's second step: reads the documents named, then searches every query and reads " +
                "and notes what it finds. Answers what it gathered, for Follow up and Write.",
            params = listOf(
                P_RESEARCH, P_QUESTION, P_MODEL, P_WEBSITES_INCLUDE, P_WEBSITES_EXCLUDE, P_DOCUMENTS_INCLUDE,
                P_QUERIES, P_RESULTS_PER_QUERY, P_MAX_SOURCES, P_MAX_PAGES_PER_SOURCE, P_WEB_SEARCH,
                // Last, so a node drawn before it was a parameter reads every other one where it always did.
                P_INCLUDE_IMAGES,
            ),
        ),
        EmbeddedFunction(
            name = FOLLOW_UP,
            description = "A research's third step: asks follow-up questions from what was gathered and searches " +
                "and reads those, to the depth given. Answers everything gathered so far.",
            params = listOf(
                P_RESEARCH, P_QUESTION, P_MODEL, P_WEBSITES_INCLUDE, P_WEBSITES_EXCLUDE, P_GATHERED, P_BREADTH, P_DEPTH, P_WEB_SEARCH,
            ),
        ),
        EmbeddedFunction(
            name = WRITE,
            description = "A research's fourth step: writes the report from what was gathered, citing numbered " +
                "sources. Answers the report in Markdown and the sources it cites.",
            params = listOf(P_RESEARCH, P_QUESTION, P_MODEL, P_LANGUAGE, P_DOCUMENTS_EXCLUDE, P_GATHERED),
        ),
        EmbeddedFunction(
            name = REVIEW,
            description = "A research's review: a second model reads the report against the plan and the notes on " +
                "its sources, and the writer revises it where asked, for as many rounds as the workspace allows. " +
                "Answers the report as the review left it and what each round said.",
            params = listOf(
                P_RESEARCH, P_QUESTION, P_MODEL, P_REVIEWER_MODEL, P_LANGUAGE, P_EXCLUDED_NAMES, P_PLAN, P_GATHERED, P_REPORT,
                // Last, so a Review drawn before they were parameters reads every other one where it did.
                P_WRITER_AGENT, P_REVIEWER_AGENT,
            ),
        ),
        EmbeddedFunction(
            name = SAVE,
            description = "A research's last step: saves the report on the Artifacts page as a PDF or a document, " +
                "with the numbered list of the sources it cites after it where it is handed what was gathered. " +
                "Answers the report, its sources and the file's id and name, or why it could not be saved.",
            // What was gathered, then what the review left, last: a Save drawn before either was a parameter reads every other one where it did.
            params = listOf(P_RESEARCH, P_QUESTION, P_REPORT, P_OUTPUT_FORMAT, P_CITED, P_REVIEWED),
        ),
        EmbeddedFunction(
            name = RUN,
            description = "Researches a question in one step: plans sub-questions, searches and reads the web and " +
                "the documents named, goes deeper with follow-up questions, and writes a report citing its sources. " +
                "Answers the report in Markdown and the sources it cites.",
            params = RUN_PARAMS,
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String =
        mapper.writeValueAsString(mapOf("error" to "research_$name is a workflow function, not a tool."))

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        val declared = functions().firstOrNull { it.name == name } ?: return null
        val given = Given(declared, arguments)
        val started = System.currentTimeMillis()
        /*
         * What the step did, said on its answer the way a function's
         * `orknux.log` lines are, so the action node writes it to the run's
         * log under this step - and bounded by the same number they are.
         */
        val said = ResearchLog(tuning[Tunable.SCRIPT_LOG_LINES])
        fun failed(reason: String) = ScriptResult.Failed(reason, System.currentTimeMillis() - started, logs = said.lines())
        fun answered(value: Any?) =
            ScriptResult.Returned(mapper.writeValueAsString(value), System.currentTimeMillis() - started, logs = said.lines())

        val question = given.text(QUESTION) ?: return failed("needs a question.")
        val researchId = given.text(RESEARCH)?.toLongOrNull()
            ?.takeIf { id -> researches.findByIdOrNull(id)?.workspaceId == workspaceId }

        if (name == SAVE) return save(given, workspaceId, researchId, question, said).let(::answered)

        val modelId = given.text(MODEL)?.toLongOrNull()?.let { chatModels.usable(workspaceId, it)?.id }
            ?: return failed(
                "needs a chat model this workspace can use: the research names the one it was started with.",
            )
        val asked = ResearchAsked(
            workspaceId = workspaceId,
            researchId = researchId,
            question = question,
            language = given.text(LANGUAGE)?.let { runCatching { ResearchLanguage.valueOf(it.uppercase()) }.getOrNull() }
                ?: ResearchLanguage.FORMAL,
            modelId = modelId,
            websitesInclude = given.texts(WEBSITES_INCLUDE),
            websitesExclude = given.texts(WEBSITES_EXCLUDE),
            documentsInclude = given.texts(DOCUMENTS_INCLUDE).mapNotNull { it.toLongOrNull() },
            documentsExclude = given.texts(DOCUMENTS_EXCLUDE).mapNotNull { it.toLongOrNull() },
            webSearch = given.flag(WEB_SEARCH),
            includeImages = given.given(INCLUDE_IMAGES)
                ?: researchId?.let { researches.findByIdOrNull(it)?.includeImages }
                ?: false,
        )

        return try {
            when (name) {
                PLAN -> answered(mapOf(QUERIES to pipeline.plan(asked, given.number(SUB_QUESTIONS).coerceAtLeast(1), said)))
                GATHER -> {
                    /*
                     * A list where Plan is this function, and the planner's
                     * answer as text where Plan is an agent node: read as the
                     * JSON array it was asked for, or as lines. Written on the
                     * research here, because an agent node has no research to
                     * write its plan on.
                     */
                    val queries = given.texts(QUERIES)
                        .ifEmpty { given.text(QUERIES)?.let(pipeline::listFrom).orEmpty() }
                        .ifEmpty { listOf(question) }
                    pipeline.planned(researchId, queries)
                    answered(shapeOf(pipeline.gather(asked, queries, limits(given), said)))
                }
                FOLLOW_UP -> {
                    val gathered = pipeline.gatheredFrom(given.node(GATHERED))
                        ?: return failed("needs what Gather found: point its gathered at that step's output.")
                    val deepened = pipeline.followUp(
                        asked, gathered, given.number(BREADTH).coerceAtLeast(0), given.number(DEPTH).coerceAtLeast(1), said,
                    )
                    // The writing comes next, and may be an agent that would write from nothing.
                    pipeline.requireRead(deepened)
                    pipeline.note(researchId, ResearchStage.WRITE, "Writing the report")
                    answered(shapeOf(deepened))
                }
                WRITE -> {
                    val gathered = pipeline.gatheredFrom(given.node(GATHERED))
                        ?: return failed("needs what was gathered: point its gathered at that step's output.")
                    answered(reportOf(pipeline.write(asked, gathered, said)))
                }
                REVIEW -> {
                    val gathered = pipeline.gatheredFrom(given.node(GATHERED))
                        ?: return failed("needs what was gathered: point its gathered at Follow up's output.")
                    val draft = given.text(REPORT)?.takeIf { it.isNotBlank() }
                        ?: return failed("needs the report to review: point its report at Write's draft.")
                    val reviewer = given.text(REVIEWER_MODEL)?.toLongOrNull()?.let { chatModels.usable(workspaceId, it)?.id }
                        ?: return failed("needs a chat model this workspace can use to review with: the research names the one it was started with.")
                    val plan = given.texts(QUERIES).ifEmpty { given.text(QUERIES)?.let(pipeline::listFrom).orEmpty() }
                    val reviewed = pipeline.review(
                        asked, reviewer, plan, gathered, draft, given.text(EXCLUDED_NAMES).orEmpty(), reviewRounds.of(workspaceId), said,
                        writerAgentId = given.text(WRITER_AGENT)?.toLongOrNull(),
                        reviewerAgentId = given.text(REVIEWER_AGENT)?.toLongOrNull(),
                    )
                    answered(linkedMapOf(REPORT to reviewed.report, "rounds" to reviewed.rounds, "revised" to reviewed.rounds.any { it.revised }))
                }
                else -> {
                    val knobs = ResearchKnobs(
                        subQuestions = given.number(SUB_QUESTIONS).coerceAtLeast(1),
                        breadth = given.number(BREADTH).coerceAtLeast(0),
                        depth = given.number(DEPTH).coerceAtLeast(1),
                        resultsPerQuery = limits(given).resultsPerQuery,
                        maxSources = limits(given).maxSources,
                        maxPagesPerSource = limits(given).maxPagesPerSource,
                    )
                    answered(reportOf(pipeline.run(asked, knobs, said)))
                }
            }
        } catch (nothing: ResearchNothingReadException) {
            failed(nothing.message ?: "read nothing.")
        } catch (refused: ResearchModelFailedException) {
            // A model that refused may answer later; anything else is settled.
            ScriptResult.Failed(
                refused.message ?: "the model did not answer", System.currentTimeMillis() - started, settled = false, logs = said.lines(),
            )
        }
    }

    /**
     * Save: the report as the file the research asked for. A refusal is
     * written on the research and answered rather than thrown, as it always
     * was: the report is written and kept, and only the file is missing.
     */
    private fun save(given: Given, workspaceId: Long, researchId: Long?, question: String, said: ResearchLog): Map<String, Any?> {
        // The report as the review left it, where there was one; Write's draft otherwise.
        val written = given.text(REVIEWED)?.takeIf { it.isNotBlank() } ?: given.text(REPORT) ?: return mapOf("error" to "There is no report to save.").also {
            said.warn("There is no report to save")
        }
        /*
         * Handed what was gathered, the report is a draft - the writer agent's
         * answer - and the numbered list of what it cites goes after it here,
         * in the pipeline's words, so it is always there and always right
         * whoever wrote the prose.
         */
        val gathered = pipeline.gatheredFrom(given.node(GATHERED))
        val cited = gathered?.sources
        val report = if (cited == null) written else pipeline.withSources(written.trim(), cited)
        if (cited != null) pipeline.note(researchId, ResearchStage.WRITE, "Report written")
        said.info(
            "Report of ${report.length} characters" +
                (cited?.let { ", with ${it.size} ${if (it.size == 1) "source" else "sources"} listed after it" } ?: ", saved as it was handed"),
        )
        /*
         * The pictures Gather kept, as Follow up handed them on - or as the
         * research holds them, for a Save drawn without what was gathered.
         */
        val images = gathered?.images?.ifEmpty { null }
            ?: researchId?.let { researches.findByIdOrNull(it) }?.let { pipeline.imagesFrom(it.foundImages) }
            ?: emptyList()
        // The report as the page and the tools read it: each picture it places a link to the one kept.
        val found = linkedMapOf<String, Any?>(ResearchModes.REPORT to ResearchDelivery.placed(report, images, ResearchDelivery::linkOf))
        cited?.let { found["sources"] = it.map { source -> mapOf("number" to source.number, "title" to source.title, "url" to source.url) } }
        val format = given.text(OUTPUT_FORMAT)?.let { runCatching { ResearchOutputFormat.valueOf(it.uppercase()) }.getOrNull() }
            ?: researchId?.let { researches.findByIdOrNull(it)?.outputFormat }
            ?: ResearchOutputFormat.DOCUMENT
        pipeline.note(researchId, ResearchStage.SAVE, if (format == ResearchOutputFormat.PDF) "Saving the report as a PDF" else "Saving the report")
        if (images.isNotEmpty()) {
            val placed = ResearchDelivery.numbersIn(report).count { number -> images.any { it.number == number } }
            said.info("$placed of ${images.size} kept ${if (images.size == 1) "image is" else "images are"} placed in the report")
            found["images"] = images.map { mapOf("number" to it.number, "caption" to it.caption, "artifact" to it.artifactId) }
        }
        // What to research next, once the report is the one that is saved: asked apart from it, so it is never in it.
        followUps.suggest(researchId, report)
        return when (val delivered = delivery.deliver(workspaceId, question, format, report, images)) {
            is ResearchDelivery.Delivered.Saved -> {
                researchId?.let { researches.noteDelivered(it, delivered.artifactId, null) }
                said.info("Saved ${delivered.fileName} on the Artifacts page as artifact ${delivered.artifactId}")
                pipeline.note(researchId, ResearchStage.SAVE, "Finished")
                found + mapOf("artifact" to delivered.artifactId, "file" to delivered.fileName)
            }
            is ResearchDelivery.Delivered.Refused -> {
                researchId?.let { researches.noteDelivered(it, null, delivered.reason) }
                said.warn("Not saved as a ${if (format == ResearchOutputFormat.PDF) "PDF" else "document"}: ${delivered.reason}")
                pipeline.note(researchId, ResearchStage.SAVE, "Finished without a file")
                found + mapOf("error" to delivered.reason)
            }
        }
    }

    private fun limits(given: Given) = ResearchLimits(
        resultsPerQuery = given.number(RESULTS_PER_QUERY).coerceAtLeast(1),
        maxSources = given.number(MAX_SOURCES).coerceIn(1, tuning[Tunable.RESEARCH_SOURCES_MOST]),
        maxPagesPerSource = given.number(MAX_PAGES_PER_SOURCE).coerceAtLeast(1),
    )

    private fun reportOf(found: ResearchFound) = mapOf(
        ResearchModes.REPORT to found.report,
        "sources" to found.sources.map { mapOf("number" to it.number, "title" to it.title, "url" to it.url) },
    )

    /**
     * What Gather and Follow up hand on, as plain maps: the shape
     * [ResearchPipeline.gatheredFrom] reads - and the sources once more as the
     * writing reads them, under `notes`, for a writer agent node's prompt.
     */
    private fun shapeOf(gathered: ResearchGathered) = linkedMapOf(
        NOTES to pipeline.writerNotes(gathered),
        "sources" to gathered.sources.map {
            linkedMapOf("number" to it.number, "title" to it.title, "url" to it.url, "kind" to it.kind, "notes" to it.notes)
        },
        "findings" to gathered.findings.map { linkedMapOf("query" to it.query, "sources" to it.sources) },
        "searched" to gathered.searched,
        "seen" to gathered.seen,
        "pages" to gathered.pages,
        "level" to gathered.level,
        "refused" to gathered.refused,
        "images" to gathered.images,
        "limits" to linkedMapOf(
            RESULTS_PER_QUERY to gathered.limits.resultsPerQuery,
            MAX_SOURCES to gathered.limits.maxSources,
            MAX_PAGES_PER_SOURCE to gathered.limits.maxPagesPerSource,
        ),
    )

    /** One call's arguments, by the names its function declares them. */
    private inner class Given(private val declared: EmbeddedFunction, arguments: List<String>) {
        private val by = declared.params.mapIndexed { at, param -> param.name to parsed(arguments.getOrNull(at)) }.toMap()

        fun node(key: String): JsonNode? = by[key]

        fun text(key: String) = by[key]?.takeIf { it.isTextual || it.isNumber }?.asString()?.trim()?.ifEmpty { null }

        fun texts(key: String) = by[key]?.takeIf { it.isArray }?.mapNotNull { one ->
            one.takeIf { it.isTextual || it.isNumber }?.asString()?.trim()?.ifEmpty { null }
        }.orEmpty()

        /** A switch, where the call was given one at all. */
        fun given(key: String): Boolean? = by[key]?.let {
            if (it.isBoolean) it.booleanValue() else if (it.isTextual) it.stringValue().trim().lowercase().toBooleanStrictOrNull() else null
        }

        /** A switch, or the default the function declares for it; on where it declares none. */
        fun flag(key: String): Boolean = by[key]?.let {
            if (it.isBoolean) it.booleanValue() else if (it.isTextual) it.stringValue().trim().lowercase().toBooleanStrictOrNull() else null
        } ?: declared.params.firstOrNull { it.name == key }?.defaultJson?.toBooleanStrictOrNull() ?: true

        /** A number, or the default the function declares for it. */
        fun number(key: String): Int = by[key]?.let {
            if (it.isNumber) it.intValue() else if (it.isTextual) it.stringValue().trim().toIntOrNull() else null
        } ?: declared.params.firstOrNull { it.name == key }?.defaultJson?.toIntOrNull() ?: 1
    }

    private fun parsed(argument: String?): JsonNode? =
        argument?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeUnless { it.isNull || it.isMissingNode }

    companion object {
        const val KEY = "research"
        const val PLAN = "plan"
        const val GATHER = "gather"
        const val FOLLOW_UP = "followUp"
        const val WRITE = "write"
        const val SAVE = "save"
        const val REVIEW = "review"
        const val RUN = "run"

        /** As a graph names them. */
        const val FUNCTION_NAME = "${KEY}_$RUN"
        fun functionName(step: String) = "${KEY}_$step"

        const val RESEARCH = "research"
        const val QUESTION = "question"
        const val LANGUAGE = "language"
        const val MODEL = "model"
        const val OUTPUT_FORMAT = "outputFormat"
        const val WEBSITES_INCLUDE = "websitesInclude"
        const val WEBSITES_EXCLUDE = "websitesExclude"
        const val DOCUMENTS_INCLUDE = "documentsInclude"
        const val DOCUMENTS_EXCLUDE = "documentsExclude"
        const val SUB_QUESTIONS = "subQuestions"
        const val BREADTH = "breadth"
        const val DEPTH = "depth"
        const val RESULTS_PER_QUERY = "resultsPerQuery"
        const val MAX_SOURCES = "maxSources"
        const val MAX_PAGES_PER_SOURCE = "maxPagesPerSource"
        const val WEB_SEARCH = "webSearch"
        const val INCLUDE_IMAGES = "includeImages"

        /** Whether the research is reviewed, and the model that reviews it, as the run carries them. */
        const val REVIEW_ASKED = "review"
        const val REVIEWER_MODEL = "reviewerModel"

        /**
         * The agents the research was started with, by id, as the run carries
         * them: Plan's and Write's nodes are answered by the first two, and
         * Review reads the Writer's and the Reviewer's instructions. Issue #678.
         */
        const val PLANNER_AGENT = "plannerAgent"
        const val WRITER_AGENT = "writerAgent"
        const val REVIEWER_AGENT = "reviewerAgent"

        /** The documents not to use, by name, as the run carries them for the writer. */
        const val EXCLUDED_NAMES = "documentsExcludeNames"

        /** The report as the review left it, which Save prefers to the draft. */
        const val REVIEWED = "reviewed"

        /** What one step hands the next. */
        const val QUERIES = "queries"
        const val GATHERED = "gathered"
        const val REPORT = "report"

        /** The sources and their notes as text, in what Gather and Follow up hand on. */
        const val NOTES = "notes"

        /** The request's own fields, read off what started the run; every one of them is in the run's payload. */
        val REQUEST = listOf(
            RESEARCH, QUESTION, LANGUAGE, MODEL, OUTPUT_FORMAT, WEB_SEARCH, INCLUDE_IMAGES,
            WEBSITES_INCLUDE, WEBSITES_EXCLUDE, DOCUMENTS_INCLUDE, DOCUMENTS_EXCLUDE,
        )

        private val P_RESEARCH = EmbeddedParam(RESEARCH, ValueType.NUMBER, "The research to keep up to date as it runs; left out, it is only answered.")
        private val P_QUESTION = EmbeddedParam(QUESTION, ValueType.STRING, "What to research.", required = true)
        private val P_LANGUAGE = EmbeddedParam(LANGUAGE, ValueType.STRING, "ACADEMIC, COLLOQUIAL or FORMAL.")
        private val P_MODEL = EmbeddedParam(MODEL, ValueType.STRING, "The id of the chat model that plans, reads and writes.", required = true)
        private val P_OUTPUT_FORMAT = EmbeddedParam(OUTPUT_FORMAT, ValueType.STRING, "PDF or DOCUMENT; left out, what the research asked for.")
        private val P_WEBSITES_INCLUDE = EmbeddedParam(WEBSITES_INCLUDE, ValueType.ARRAY, "Websites to keep to; empty is the whole web.")
        private val P_WEBSITES_EXCLUDE = EmbeddedParam(WEBSITES_EXCLUDE, ValueType.ARRAY, "Websites never to read.")
        private val P_DOCUMENTS_INCLUDE = EmbeddedParam(DOCUMENTS_INCLUDE, ValueType.ARRAY, "Documents on the Artifacts page to read, by id.")
        private val P_DOCUMENTS_EXCLUDE = EmbeddedParam(DOCUMENTS_EXCLUDE, ValueType.ARRAY, "Documents never to use, by id.")
        private val P_SUB_QUESTIONS = EmbeddedParam(SUB_QUESTIONS, ValueType.NUMBER, "How many search queries the question is broken into.", defaultJson = "3")
        private val P_BREADTH = EmbeddedParam(BREADTH, ValueType.NUMBER, "Follow-up queries per finding at the first deeper level; halved at each after.", defaultJson = "2")
        private val P_DEPTH = EmbeddedParam(DEPTH, ValueType.NUMBER, "Levels of searching; 1 is the plan's queries only.", defaultJson = "1")
        private val P_RESULTS_PER_QUERY = EmbeddedParam(RESULTS_PER_QUERY, ValueType.NUMBER, "Search results looked at per query.", defaultJson = "5")
        private val P_MAX_SOURCES = EmbeddedParam(MAX_SOURCES, ValueType.NUMBER, "The most sources read in all.", defaultJson = "10")
        private val P_MAX_PAGES_PER_SOURCE = EmbeddedParam(MAX_PAGES_PER_SOURCE, ValueType.NUMBER, "Pages read of one website or one document.", defaultJson = "1")
        private val P_WEB_SEARCH = EmbeddedParam(WEB_SEARCH, ValueType.BOOLEAN, "Whether to search the web; off reads only the documents and the websites to keep to.", defaultJson = "true")
        private val P_INCLUDE_IMAGES = EmbeddedParam(
            INCLUDE_IMAGES, ValueType.BOOLEAN,
            "Whether to look for images the report may place, kept on the Artifacts page; left out, what the research asked for.",
        )
        private val P_QUERIES = EmbeddedParam(QUERIES, ValueType.ARRAY, "The search queries Plan wrote.", required = true)
        private val P_PLAN = EmbeddedParam(QUERIES, ValueType.ARRAY, "The search queries Plan wrote, for the reviewer to check the report answers.")
        private val P_REVIEWER_MODEL = EmbeddedParam(
            REVIEWER_MODEL, ValueType.STRING, "The id of the chat model that reviews the report.", required = true,
        )
        private val P_WRITER_AGENT = EmbeddedParam(
            WRITER_AGENT, ValueType.STRING, "The id of the agent that wrote the report and revises it; left out, the part's instructions alone, with no agent's own.",
        )
        private val P_REVIEWER_AGENT = EmbeddedParam(
            REVIEWER_AGENT, ValueType.STRING, "The id of the agent that reviews the report; left out, the part's instructions alone, with no agent's own.",
        )
        private val P_EXCLUDED_NAMES = EmbeddedParam(EXCLUDED_NAMES, ValueType.STRING, "The documents not to use, by name, as the writer was told.")
        private val P_REVIEWED = EmbeddedParam(
            REVIEWED, ValueType.STRING,
            "The report as Review left it; where given, it is saved in place of report.",
        )
        private val P_GATHERED = EmbeddedParam(GATHERED, ValueType.MAP, "What Gather, or Follow up, answered.", required = true)
        private val P_REPORT = EmbeddedParam(REPORT, ValueType.STRING, "The report Write answered, in Markdown.", required = true)
        private val P_CITED = EmbeddedParam(
            GATHERED, ValueType.MAP,
            "What Follow up answered, whose numbered sources are listed after the report; left out, the report is saved as it is.",
        )

        private val RUN_PARAMS = listOf(
            P_RESEARCH, P_QUESTION, P_LANGUAGE, P_MODEL, P_WEBSITES_INCLUDE, P_WEBSITES_EXCLUDE, P_DOCUMENTS_INCLUDE,
            P_DOCUMENTS_EXCLUDE, P_SUB_QUESTIONS, P_BREADTH, P_DEPTH, P_RESULTS_PER_QUERY, P_MAX_SOURCES, P_MAX_PAGES_PER_SOURCE,
            // Last, so a node drawn before it was a parameter reads every other one where it always did.
            P_WEB_SEARCH,
        )
    }
}
