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
            name = SAVE,
            description = "A research's last step: saves the report on the Artifacts page as a PDF or a document. " +
                "Answers the file's id and name, or why it could not be saved.",
            params = listOf(P_RESEARCH, P_QUESTION, P_REPORT, P_OUTPUT_FORMAT),
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
        fun failed(reason: String) = ScriptResult.Failed(reason, System.currentTimeMillis() - started)
        fun answered(value: Any?) = ScriptResult.Returned(mapper.writeValueAsString(value), System.currentTimeMillis() - started)

        val question = given.text(QUESTION) ?: return failed("needs a question.")
        val researchId = given.text(RESEARCH)?.toLongOrNull()
            ?.takeIf { id -> researches.findByIdOrNull(id)?.workspaceId == workspaceId }

        if (name == SAVE) return save(given, workspaceId, researchId, question).let(::answered)

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
        )

        return try {
            when (name) {
                PLAN -> answered(mapOf(QUERIES to pipeline.plan(asked, given.number(SUB_QUESTIONS).coerceAtLeast(1))))
                GATHER -> {
                    val queries = given.texts(QUERIES).ifEmpty { listOf(question) }
                    answered(shapeOf(pipeline.gather(asked, queries, limits(given))))
                }
                FOLLOW_UP -> {
                    val gathered = pipeline.gatheredFrom(given.node(GATHERED))
                        ?: return failed("needs what Gather found: point its gathered at that step's output.")
                    answered(shapeOf(pipeline.followUp(asked, gathered, given.number(BREADTH).coerceAtLeast(0), given.number(DEPTH).coerceAtLeast(1))))
                }
                WRITE -> {
                    val gathered = pipeline.gatheredFrom(given.node(GATHERED))
                        ?: return failed("needs what was gathered: point its gathered at that step's output.")
                    answered(reportOf(pipeline.write(asked, gathered)))
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
                    answered(reportOf(pipeline.run(asked, knobs)))
                }
            }
        } catch (nothing: ResearchNothingReadException) {
            failed(nothing.message ?: "read nothing.")
        } catch (refused: ResearchModelFailedException) {
            // A model that refused may answer later; anything else is settled.
            ScriptResult.Failed(refused.message ?: "the model did not answer", System.currentTimeMillis() - started, settled = false)
        }
    }

    /**
     * Save: the report as the file the research asked for. A refusal is
     * written on the research and answered rather than thrown, as it always
     * was: the report is written and kept, and only the file is missing.
     */
    private fun save(given: Given, workspaceId: Long, researchId: Long?, question: String): Map<String, Any?> {
        val report = given.text(REPORT) ?: return mapOf("error" to "There is no report to save.")
        val format = given.text(OUTPUT_FORMAT)?.let { runCatching { ResearchOutputFormat.valueOf(it.uppercase()) }.getOrNull() }
            ?: researchId?.let { researches.findByIdOrNull(it)?.outputFormat }
            ?: ResearchOutputFormat.DOCUMENT
        pipeline.note(researchId, ResearchStage.SAVE, if (format == ResearchOutputFormat.PDF) "Saving the report as a PDF" else "Saving the report")
        return when (val delivered = delivery.deliver(workspaceId, question, format, report)) {
            is ResearchDelivery.Delivered.Saved -> {
                researchId?.let { researches.noteDelivered(it, delivered.artifactId, null) }
                pipeline.note(researchId, ResearchStage.SAVE, "Finished")
                mapOf("artifact" to delivered.artifactId, "file" to delivered.fileName)
            }
            is ResearchDelivery.Delivered.Refused -> {
                researchId?.let { researches.noteDelivered(it, null, delivered.reason) }
                pipeline.note(researchId, ResearchStage.SAVE, "Finished without a file")
                mapOf("error" to delivered.reason)
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

    /** What Gather and Follow up hand on, as plain maps: the shape [ResearchPipeline.gatheredFrom] reads. */
    private fun shapeOf(gathered: ResearchGathered) = linkedMapOf(
        "sources" to gathered.sources.map {
            linkedMapOf("number" to it.number, "title" to it.title, "url" to it.url, "kind" to it.kind, "notes" to it.notes)
        },
        "findings" to gathered.findings.map { linkedMapOf("query" to it.query, "sources" to it.sources) },
        "searched" to gathered.searched,
        "seen" to gathered.seen,
        "pages" to gathered.pages,
        "level" to gathered.level,
        "refused" to gathered.refused,
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

        /** What one step hands the next. */
        const val QUERIES = "queries"
        const val GATHERED = "gathered"
        const val REPORT = "report"

        /** The request's own fields, read off what started the run; every one of them is in the run's payload. */
        val REQUEST = listOf(
            RESEARCH, QUESTION, LANGUAGE, MODEL, OUTPUT_FORMAT, WEB_SEARCH,
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
        private val P_QUERIES = EmbeddedParam(QUERIES, ValueType.ARRAY, "The search queries Plan wrote.", required = true)
        private val P_GATHERED = EmbeddedParam(GATHERED, ValueType.MAP, "What Gather, or Follow up, answered.", required = true)
        private val P_REPORT = EmbeddedParam(REPORT, ValueType.STRING, "The report Write answered, in Markdown.", required = true)

        private val RUN_PARAMS = listOf(
            P_RESEARCH, P_QUESTION, P_LANGUAGE, P_MODEL, P_WEBSITES_INCLUDE, P_WEBSITES_EXCLUDE, P_DOCUMENTS_INCLUDE,
            P_DOCUMENTS_EXCLUDE, P_SUB_QUESTIONS, P_BREADTH, P_DEPTH, P_RESULTS_PER_QUERY, P_MAX_SOURCES, P_MAX_PAGES_PER_SOURCE,
            // Last, so a node drawn before it was a parameter reads every other one where it always did.
            P_WEB_SEARCH,
        )
    }
}
