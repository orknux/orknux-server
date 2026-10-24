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
 * `research_run`: a whole research, as a function a workflow's action node
 * calls. Issue #659.
 *
 * A function rather than a node kind of its own, so the research modes are
 * ordinary workflows: the mode's Research node is an action node like any
 * other, and the knobs - how many sub-questions, how broad and how deep, how
 * many sources and pages - are its parameters, written on the node where
 * anybody editing the workflow sees and changes them. A custom mode copied from
 * Deep is a copy of that node with its own numbers.
 *
 * Not offered to agents: an agent that wants to research has the search and
 * the HTTP tools itself, and a tool that runs for many minutes inside one
 * turn is the wrong shape for a chat.
 */
@Component
class ResearchCapability(
    private val pipeline: ResearchPipeline,
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
            name = RUN,
            description = "Researches a question: plans sub-questions, searches and reads the web and the documents " +
                "named, goes deeper with follow-up questions, and writes a report citing its sources. Answers the " +
                "report in Markdown and the sources it cites.",
            returnType = ValueType.MAP,
            params = PARAMS,
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String =
        mapper.writeValueAsString(mapOf("error" to "research_run is a workflow function, not a tool."))

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        if (name != RUN) return null
        val given = PARAMS.mapIndexed { at, param -> param.name to parsed(arguments.getOrNull(at)) }.toMap()
        fun text(key: String) = given[key]?.takeIf { it.isTextual || it.isNumber }?.asString()?.trim()?.ifEmpty { null }
        fun texts(key: String) = given[key]?.takeIf { it.isArray }?.mapNotNull { one ->
            one.takeIf { it.isTextual || it.isNumber }?.asString()?.trim()?.ifEmpty { null }
        }.orEmpty()
        fun number(key: String, default: Int) = given[key]?.let {
            if (it.isNumber) it.intValue() else if (it.isTextual) it.stringValue().trim().toIntOrNull() else null
        } ?: default

        val question = text(QUESTION) ?: return ScriptResult.Failed("research_run needs a question.", 0)
        val modelId = text(MODEL)?.toLongOrNull()?.let { chatModels.usable(workspaceId, it)?.id }
            ?: return ScriptResult.Failed(
                "research_run needs a chat model this workspace can use: choose one when starting the research, " +
                    "or write its id into the node's model.",
                0,
            )
        val researchId = text(RESEARCH)?.toLongOrNull()
            ?.takeIf { id -> researches.findByIdOrNull(id)?.workspaceId == workspaceId }

        val asked = ResearchAsked(
            workspaceId = workspaceId,
            researchId = researchId,
            question = question,
            language = text(LANGUAGE)?.let { runCatching { ResearchLanguage.valueOf(it.uppercase()) }.getOrNull() }
                ?: ResearchLanguage.FORMAL,
            modelId = modelId,
            websitesInclude = texts(WEBSITES_INCLUDE),
            websitesExclude = texts(WEBSITES_EXCLUDE),
            documentsInclude = texts(DOCUMENTS_INCLUDE).mapNotNull { it.toLongOrNull() },
            documentsExclude = texts(DOCUMENTS_EXCLUDE).mapNotNull { it.toLongOrNull() },
            knobs = ResearchKnobs(
                subQuestions = number(SUB_QUESTIONS, 3).coerceAtLeast(1),
                breadth = number(BREADTH, 2).coerceAtLeast(0),
                depth = number(DEPTH, 1).coerceAtLeast(1),
                resultsPerQuery = number(RESULTS_PER_QUERY, 5).coerceAtLeast(1),
                maxSources = number(MAX_SOURCES, 10).coerceIn(1, tuning[Tunable.RESEARCH_SOURCES_MOST]),
                maxPagesPerSource = number(MAX_PAGES_PER_SOURCE, 1).coerceAtLeast(1),
            ),
        )
        val started = System.currentTimeMillis()
        return try {
            val found = pipeline.run(asked)
            ScriptResult.Returned(
                mapper.writeValueAsString(
                    mapOf(
                        ResearchModes.REPORT to found.report,
                        "sources" to found.sources.map { mapOf("number" to it.number, "title" to it.title, "url" to it.url) },
                    ),
                ),
                System.currentTimeMillis() - started,
            )
        } catch (failed: ResearchModelFailedException) {
            // A model that refused may answer later; anything else is settled.
            ScriptResult.Failed(failed.message ?: "the model did not answer", System.currentTimeMillis() - started, settled = false)
        }
    }

    private fun parsed(argument: String?): JsonNode? =
        argument?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeUnless { it.isNull || it.isMissingNode }

    companion object {
        const val KEY = "research"
        const val RUN = "run"

        /** As a graph names it. */
        const val FUNCTION_NAME = "${KEY}_$RUN"

        const val RESEARCH = "research"
        const val QUESTION = "question"
        const val LANGUAGE = "language"
        const val MODEL = "model"
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

        /** The request's own fields, read off what started the run. */
        val REQUEST = listOf(RESEARCH, QUESTION, LANGUAGE, MODEL, WEBSITES_INCLUDE, WEBSITES_EXCLUDE, DOCUMENTS_INCLUDE, DOCUMENTS_EXCLUDE)

        val PARAMS = listOf(
            EmbeddedParam(RESEARCH, ValueType.NUMBER, "The research to keep up to date as it runs; left out, it is only answered."),
            EmbeddedParam(QUESTION, ValueType.STRING, "What to research.", required = true),
            EmbeddedParam(LANGUAGE, ValueType.STRING, "ACADEMIC, COLLOQUIAL or FORMAL."),
            EmbeddedParam(MODEL, ValueType.STRING, "The id of the chat model that plans, reads and writes.", required = true),
            EmbeddedParam(WEBSITES_INCLUDE, ValueType.ARRAY, "Websites to keep to; empty is the whole web."),
            EmbeddedParam(WEBSITES_EXCLUDE, ValueType.ARRAY, "Websites never to read."),
            EmbeddedParam(DOCUMENTS_INCLUDE, ValueType.ARRAY, "Documents on the Artifacts page to read, by id."),
            EmbeddedParam(DOCUMENTS_EXCLUDE, ValueType.ARRAY, "Documents never to use, by id."),
            EmbeddedParam(SUB_QUESTIONS, ValueType.NUMBER, "How many search queries the question is broken into.", defaultJson = "3"),
            EmbeddedParam(BREADTH, ValueType.NUMBER, "Follow-up queries per finding at the first deeper level; halved at each after.", defaultJson = "2"),
            EmbeddedParam(DEPTH, ValueType.NUMBER, "Levels of searching; 1 is the plan's queries only.", defaultJson = "1"),
            EmbeddedParam(RESULTS_PER_QUERY, ValueType.NUMBER, "Search results looked at per query.", defaultJson = "5"),
            EmbeddedParam(MAX_SOURCES, ValueType.NUMBER, "The most sources read in all.", defaultJson = "10"),
            EmbeddedParam(MAX_PAGES_PER_SOURCE, ValueType.NUMBER, "Pages read of one website or one document.", defaultJson = "1"),
        )
    }
}
