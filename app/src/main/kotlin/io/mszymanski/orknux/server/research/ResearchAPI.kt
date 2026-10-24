package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.agent.WorkspaceChatModels
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workflow.WorkflowAPI
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.format.DateTimeFormatter

/**
 * Explore > Research: a question researched by one of the workspace's own
 * workflows, and the modes that say which. Issue #659.
 *
 * What a research *does* is its mode's workflow, and nothing here reads the
 * graph: this writes down what was asked, hands the workflow a brief and the
 * fields beside it, and keeps the row the list reads. [ResearchRuns] follows
 * the run and lays out what it wrote.
 */
@Controller
class ResearchAPI(
    private val researches: ResearchRepository,
    private val modes: ResearchModeRepository,
    private val seeding: ResearchModes,
    private val documents: ResearchDocuments,
    private val briefs: ResearchBriefs,
    private val following: ResearchRuns,
    private val saved: SavedArtifactRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val workflowApi: WorkflowAPI,
    private val executions: WorkflowExecutionRepository,
    private val chatModels: WorkspaceChatModels,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
) {

    /**
     * The workspace's modes, the seeded three first. Seeded here too, for a
     * workspace that came into being some way that made none - a copy, a file.
     */
    @QueryMapping
    fun researchModes(@Argument workspaceId: Long): List<ResearchModeView> {
        access.requireVisible(workspaceId)
        seeding.ensure(workspaceId)
        return modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).map(::describe)
    }

    /** The workspace's research, newest first. */
    @QueryMapping
    fun researches(@Argument workspaceId: Long, @Argument page: Int?, @Argument size: Int?): ResearchPage {
        access.requireVisible(workspaceId)
        val found = researches.findByWorkspaceIdOrderByStartedAtDescIdDesc(workspaceId, pageRequest(page, size, Sort.unsorted()))
        return ResearchPage(
            content = found.content.map { describe(caughtUp(it)) },
            page = found.number,
            size = found.size,
            totalElements = found.totalElements.toInt(),
            totalPages = found.totalPages,
        )
    }

    @QueryMapping
    fun research(@Argument id: Long): ResearchView? {
        val held = researches.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(caughtUp(held))
    }

    /**
     * What a research may be pointed at: the workspace's readable documents,
     * newest first, narrowed to what a word appears in - the name or what the
     * file is - and no more than [limit] of them, because a picker shows a few
     * and a workspace may hold thousands.
     */
    @QueryMapping
    fun researchDocuments(
        @Argument workspaceId: Long,
        @Argument search: String?,
        @Argument limit: Int?,
    ): List<ResearchDocumentView> {
        access.requireVisible(workspaceId)
        val looking = search?.trim()?.lowercase().orEmpty()
        return documents.of(workspaceId)
            .asSequence()
            .filter { looking.isEmpty() || it.name.lowercase().contains(looking) || it.description.lowercase().contains(looking) }
            .take((limit ?: DOCUMENTS_SHOWN).coerceIn(1, MOST_DOCUMENTS_SHOWN))
            .map(::describeDocument)
            .toList()
    }

    private fun describeDocument(it: io.mszymanski.orknux.server.workflow.SavedArtifact) = ResearchDocumentView(
        id = requireNotNull(it.id),
        name = it.name,
        description = it.description,
        contentType = it.contentType,
        sizeBytes = it.sizeBytes,
        savedAt = it.savedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

    /**
     * Writes the research down and starts its mode's workflow.
     *
     * Answers at once, with the research running: the run goes on on a thread
     * of its own, and the list follows it.
     */
    @MutationMapping
    fun startResearch(@Argument input: StartResearchInput): ResearchView {
        access.requireVisible(input.workspaceId)
        val question = input.question.trim()
        if (question.isEmpty()) throw ResearchQuestionMissingException()
        if (question.length > MOST_QUESTION) throw ResearchQuestionTooLongException(MOST_QUESTION)

        val mode = modes.findByIdOrNull(input.modeId)?.takeIf { it.workspaceId == input.workspaceId }
            ?: throw ResearchModeNotFoundException(input.modeId)
        val assignment = mode.workflowId?.let { assignments.findByWorkspaceIdAndWorkflowId(input.workspaceId, it) }
            ?: throw ResearchModeWorkflowMissingException(mode.name)
        val model = input.modelId?.let {
            chatModels.usable(input.workspaceId, it) ?: throw ResearchModelUnusableException()
        }

        val websitesInclude = websites(input.websitesInclude)
        val websitesExclude = websites(input.websitesExclude)
        val held = saved.findByWorkspaceId(input.workspaceId).associateBy { requireNotNull(it.id) }
        fun picked(ids: List<Long>?) = ids.orEmpty().distinct().map { id ->
            held[id]?.takeIf { ResearchDocuments.readable(it.contentType) } ?: throw ResearchDocumentNotFoundException(id)
        }
        val included = picked(input.documentsInclude)
        val excluded = picked(input.documentsExclude).filter { it !in included }
        val sources = ResearchSources(
            websitesInclude = websitesInclude,
            websitesExclude = websitesExclude,
            documentsInclude = included.map { requireNotNull(it.id) },
            documentsExclude = excluded.map { requireNotNull(it.id) },
        )
        // A research always has its model: no step of a mode's workflow holds one of its own.
        if (model == null) throw ResearchModelMissingException()
        val useWebSearch = input.useWebSearch ?: true
        if (!useWebSearch && included.isEmpty() && websitesInclude.isEmpty()) throw ResearchNothingToReadException()
        val includeImages = input.includeImages ?: false

        val research = researches.save(
            Research(
                workspaceId = input.workspaceId,
                question = question,
                modeId = mode.id,
                modeName = mode.name,
                workflowId = requireNotNull(assignment.workflow.id),
                workflowName = assignment.workflow.name,
                modelId = model.id,
                modelName = model.name,
                language = input.language,
                outputFormat = input.outputFormat,
                sources = mapper.writeValueAsString(sources),
                useWebSearch = useWebSearch,
                includeImages = includeImages,
                startedBy = SecurityContextHolder.getContext().authentication?.name ?: "system",
            ),
        )
        auditRecorder.record(
            input.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Research started in ${mode.name} mode: ${question.replace(Regex("\\s+"), " ").take(MOST_AUDITED)}",
        )

        /*
         * The request as the mode's workflow reads it: one field per thing
         * asked, under the names the research functions take, so each seeded
         * step points a parameter at the ones it needs. The brief beside them says all of it in
         * prose, for a workflow somebody writes around an agent instead.
         */
        val payload = mapper.writeValueAsString(
            linkedMapOf(
                ResearchCapability.RESEARCH to research.id,
                ResearchCapability.QUESTION to question,
                "mode" to mode.name,
                ResearchCapability.LANGUAGE to input.language.name,
                ResearchCapability.OUTPUT_FORMAT to input.outputFormat.name,
                ResearchCapability.MODEL to model.id.toString(),
                ResearchCapability.WEBSITES_INCLUDE to websitesInclude,
                ResearchCapability.WEBSITES_EXCLUDE to websitesExclude,
                ResearchCapability.DOCUMENTS_INCLUDE to included.map { requireNotNull(it.id).toString() },
                ResearchCapability.DOCUMENTS_EXCLUDE to excluded.map { requireNotNull(it.id).toString() },
                // By name as well, for the writer agent's prompt: an id says nothing to a model.
                ResearchModes.DOCUMENTS_EXCLUDE_NAMES to (excluded.joinToString(", ") { it.name }.ifEmpty { "none" }),
                ResearchCapability.WEB_SEARCH to useWebSearch,
                ResearchCapability.INCLUDE_IMAGES to includeImages,
                ResearchModes.BRIEF to briefs.of(question, input.language, input.outputFormat, sources, included, excluded, useWebSearch, includeImages),
            ),
        )
        /*
         * Where Plan is an agent node, nothing writes on the research until
         * Gather does: the agent has no research to write on. So the page is
         * told here what the first step is doing.
         */
        if (seeding.plansWithAnAgent(research.workflowId)) {
            following.planning(requireNotNull(research.id))
        }
        following.launch(research, payload)
        return describe(research)
    }

    /** Takes a research off the list. Its run and its file stay where they are. */
    @MutationMapping
    @Transactional
    fun deleteResearch(@Argument id: Long): Boolean {
        val held = researches.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
        researches.delete(held)
        auditRecorder.record(
            held.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Research removed: ${held.question.replace(Regex("\\s+"), " ").take(MOST_AUDITED)}",
        )
        return true
    }

    /**
     * A mode of the workspace's own: a name and a workflow it picked, or a copy
     * of another mode's workflow to change - made hidden, like the ones it was
     * copied from.
     */
    @MutationMapping
    @Transactional
    fun createResearchMode(@Argument input: CreateResearchModeInput): ResearchModeView {
        access.requireVisible(input.workspaceId)
        val name = input.name.trim()
        if (name.isEmpty()) throw ResearchModeNameInvalidException()
        if (name.length > MOST_MODE_NAME) throw ResearchModeNameInvalidException()
        if (modes.existsByWorkspaceIdAndName(input.workspaceId, name)) throw ResearchModeNameTakenException(name)

        val workflowId = when {
            input.workflowId != null ->
                input.workflowId.takeIf { assignments.existsByWorkspaceIdAndWorkflowId(input.workspaceId, it) }
                    ?: throw ResearchModeWorkflowNotFoundException(input.workflowId)
            input.copyOfModeId != null -> {
                val from = modes.findByIdOrNull(input.copyOfModeId)?.takeIf { it.workspaceId == input.workspaceId }
                    ?: throw ResearchModeNotFoundException(input.copyOfModeId)
                val assignment = from.workflowId?.let { assignments.findByWorkspaceIdAndWorkflowId(input.workspaceId, it) }
                    ?: throw ResearchModeWorkflowMissingException(from.name)
                // Named after the mode where that is free, so the Workflows list says what it is for.
                val wanted = "Research: $name".takeUnless { assignments.existsByWorkspaceIdAndWorkflowName(input.workspaceId, it) }
                val copy = workflowApi.duplicateWorkflow(requireNotNull(assignment.id), wanted)
                assignments.findByIdOrNull(copy.id)?.workflow?.hidden = true
                copy.workflowId
            }
            else -> throw ResearchModeWorkflowNotChosenException()
        }

        val position = (modes.findByWorkspaceIdOrderByPositionAscIdAsc(input.workspaceId).maxOfOrNull { it.position } ?: -1) + 1
        val made = modes.save(
            ResearchMode(
                workspaceId = input.workspaceId,
                kind = ResearchModeKind.CUSTOM,
                name = name,
                workflowId = workflowId,
                position = position,
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Research mode $name added")
        return describe(made)
    }

    /** Removes a mode somebody added. The three seeded ones stay; their workflows are where to change them. */
    @MutationMapping
    @Transactional
    fun deleteResearchMode(@Argument id: Long): Boolean {
        val mode = modes.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
        if (mode.kind != ResearchModeKind.CUSTOM) throw ResearchModeSeededException(mode.name)
        modes.delete(mode)
        auditRecorder.record(mode.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Research mode ${mode.name} removed")
        return true
    }

    /** A research still running behind a run that has ended is told now. */
    private fun caughtUp(research: Research): Research {
        if (research.status != ResearchStatus.RUNNING) return research
        val executionId = research.executionId ?: return research
        val run = executions.findByIdOrNull(executionId) ?: return research
        if (run.status == ExecutionStatus.RUNNING) return research
        following.settle(executionId)
        return researches.findByIdOrNull(requireNotNull(research.id)) ?: research
    }

    private fun websites(given: List<String>?): List<String> {
        val kept = given.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (kept.size > MOST_WEBSITES) throw ResearchTooManyWebsitesException(MOST_WEBSITES)
        kept.firstOrNull { it.length > MOST_WEBSITE }?.let { throw ResearchWebsiteTooLongException(MOST_WEBSITE) }
        return kept
    }

    private fun describe(mode: ResearchMode): ResearchModeView {
        val assignment = mode.workflowId?.let { assignments.findByWorkspaceIdAndWorkflowId(mode.workspaceId, it) }
        return ResearchModeView(
            id = requireNotNull(mode.id),
            kind = mode.kind,
            name = mode.name,
            workflowId = assignment?.workflow?.id,
            workflowName = assignment?.workflow?.name,
        )
    }

    private fun describe(research: Research): ResearchView {
        val sources = runCatching { mapper.readValue(research.sources, Map::class.java) }.getOrNull().orEmpty()
        fun list(key: String): List<String> = (sources[key] as? List<*>).orEmpty().map { it.toString() }
        val names = (list("documentsInclude") + list("documentsExclude")).mapNotNull { it.toLongOrNull() }
            .let { ids -> if (ids.isEmpty()) emptyMap() else saved.findAllById(ids).associate { requireNotNull(it.id) to it.name } }
        fun documents(key: String) = list(key).mapNotNull { it.toLongOrNull() }.map { names[it] ?: "#$it" }
        val artifact = research.artifactId?.let { saved.findByIdOrNull(it) }
        return ResearchView(
            id = requireNotNull(research.id),
            workspaceId = research.workspaceId,
            question = research.question,
            modeId = research.modeId,
            modeName = research.modeName,
            workflowId = research.workflowId,
            workflowName = research.workflowName,
            modelId = research.modelId,
            modelName = research.modelName,
            language = research.language,
            outputFormat = research.outputFormat,
            websitesInclude = list("websitesInclude"),
            websitesExclude = list("websitesExclude"),
            documentsInclude = documents("documentsInclude"),
            documentsExclude = documents("documentsExclude"),
            useWebSearch = research.useWebSearch,
            includeImages = research.includeImages,
            images = imagesOf(research.foundImages),
            status = research.status,
            executionId = research.executionId,
            result = research.result,
            progress = progressOf(research.progress),
            sources = sourcesOf(research.foundSources),
            fileName = artifact?.name,
            fileUrl = artifact?.let { "/api/artifacts/${it.id}" },
            artifactId = artifact?.id,
            previewUrl = artifact?.let { "/api/artifacts/${it.id}/preview" },
            error = research.error,
            startedBy = research.startedBy,
            startedAt = research.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            finishedAt = research.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    /*
     * Read as trees and built by hand: the application's mapper does not bind
     * a Kotlin constructor, and a progress written by an older pipeline should
     * read as what it has rather than fail the whole list.
     */
    private fun progressOf(held: String?): ResearchProgressView? {
        val read = held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isObject } ?: return null
        fun texts(key: String) = read.path(key).mapNotNull { one -> one.takeIf { it.isTextual }?.stringValue() }
        return ResearchProgressView(
            step = read.path("step").takeIf { it.isTextual }?.stringValue().orEmpty(),
            subQuestions = texts("subQuestions"),
            searched = texts("searched"),
            level = read.path("level").takeIf { it.isNumber }?.intValue() ?: 0,
            sourcesRead = read.path("sourcesRead").takeIf { it.isNumber }?.intValue() ?: 0,
            stage = read.path("stage").takeIf { it.isTextual }
                ?.let { runCatching { ResearchStage.valueOf(it.stringValue()) }.getOrNull() },
        )
    }

    /** The pictures kept, each with the links the page shows it from and opens it at. */
    private fun imagesOf(held: String?): List<ResearchImageView> =
        held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }
            ?.let(ResearchPipeline::imagesOf).orEmpty()
            .map { image ->
                ResearchImageView(
                    number = image.number,
                    caption = image.caption,
                    url = image.url,
                    page = image.page,
                    contentType = image.contentType,
                    sizeBytes = image.sizeBytes,
                    width = image.width,
                    height = image.height,
                    artifactId = image.artifactId,
                    fileUrl = ResearchDelivery.linkOf(image),
                    previewUrl = ResearchDelivery.linkOf(image) + "/preview",
                )
            }

    private fun sourcesOf(held: String?): List<ResearchSourceView> =
        held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }?.mapNotNull { one ->
            val url = one.path("url").takeIf { it.isTextual }?.stringValue() ?: return@mapNotNull null
            ResearchSourceView(
                number = one.path("number").takeIf { it.isNumber }?.intValue() ?: 0,
                title = one.path("title").takeIf { it.isTextual }?.stringValue() ?: url,
                url = url,
                kind = one.path("kind").takeIf { it.isTextual }?.stringValue() ?: "WEB",
                notes = one.path("notes").takeIf { it.isTextual }?.stringValue().orEmpty(),
            )
        }.orEmpty()

    private companion object {
        const val MOST_QUESTION = 20_000

        /** How many documents a picker is answered with where it does not say; the page of a list, not a behaviour. */
        const val DOCUMENTS_SHOWN = 20
        const val MOST_DOCUMENTS_SHOWN = 100
        const val MOST_MODE_NAME = 100
        const val MOST_WEBSITES = 50
        const val MOST_WEBSITE = 500
        const val MOST_AUDITED = 120
    }
}

data class StartResearchInput(
    val workspaceId: Long,
    val question: String,
    val modeId: Long,
    val modelId: Long? = null,
    val language: ResearchLanguage,
    val outputFormat: ResearchOutputFormat,
    val websitesInclude: List<String>? = null,
    val websitesExclude: List<String>? = null,
    val documentsInclude: List<Long>? = null,
    val documentsExclude: List<Long>? = null,
    /** Absent is on: a research searches the web unless told not to. */
    val useWebSearch: Boolean? = null,
    /** Absent is off: a research looks for images only when asked to. */
    val includeImages: Boolean? = null,
)

data class CreateResearchModeInput(
    val workspaceId: Long,
    val name: String,
    /** The workflow the mode runs; or [copyOfModeId] to make one. */
    val workflowId: Long? = null,
    /** Another mode, whose workflow is copied for this one to run. */
    val copyOfModeId: Long? = null,
)

data class ResearchModeView(
    val id: Long,
    val kind: ResearchModeKind,
    val name: String,
    /** Null where the workflow it named is no longer in the workspace. */
    val workflowId: Long?,
    val workflowName: String?,
)

data class ResearchDocumentView(
    val id: Long,
    val name: String,
    val description: String,
    val contentType: String,
    val sizeBytes: Long,
    val savedAt: String,
)

data class ResearchView(
    val id: Long,
    val workspaceId: Long,
    val question: String,
    val modeId: Long?,
    val modeName: String,
    val workflowId: Long,
    val workflowName: String,
    val modelId: Long?,
    val modelName: String?,
    val language: ResearchLanguage,
    val outputFormat: ResearchOutputFormat,
    val websitesInclude: List<String>,
    val websitesExclude: List<String>,
    val documentsInclude: List<String>,
    val documentsExclude: List<String>,
    val useWebSearch: Boolean,
    val includeImages: Boolean = false,
    /** The pictures it kept, numbered as the report places them. */
    val images: List<ResearchImageView> = emptyList(),
    val status: ResearchStatus,
    val executionId: Long?,
    val result: String?,
    /** Where it has got to, while it runs and after; null before the pipeline has said anything. */
    val progress: ResearchProgressView?,
    /** What it read, numbered as the report cites them. */
    val sources: List<ResearchSourceView>,
    val fileName: String?,
    val fileUrl: String?,
    val artifactId: Long? = null,
    val previewUrl: String?,
    val error: String?,
    val startedBy: String,
    val startedAt: String,
    val finishedAt: String?,
)

data class ResearchProgressView(
    val step: String,
    val subQuestions: List<String>,
    val searched: List<String>,
    val level: Int,
    val sourcesRead: Int,
    val stage: ResearchStage?,
)

data class ResearchSourceView(
    val number: Int,
    val title: String,
    val url: String,
    val kind: String,
    val notes: String,
)

data class ResearchImageView(
    val number: Int,
    val caption: String,
    val url: String,
    val page: String?,
    val contentType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val artifactId: Long,
    val fileUrl: String,
    val previewUrl: String,
)

data class ResearchPage(
    val content: List<ResearchView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

class ResearchQuestionMissingException : RuntimeException("Say what to research")

class ResearchQuestionTooLongException(val most: Int) :
    RuntimeException("A research question is at most $most characters"), Refusal {
    override val arguments get() = mapOf("most" to most)
}

class ResearchModeNotFoundException(val id: Long) : RuntimeException("No research mode with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchModeWorkflowMissingException(val mode: String) :
    RuntimeException("The research mode $mode has no workflow in this workspace to run"), Refusal {
    override val arguments get() = mapOf("mode" to mode)
}

class ResearchModelUnusableException : RuntimeException("That model cannot be used for research in this workspace")

class ResearchModelMissingException : RuntimeException("Choose the chat model to research with")

class ResearchNothingToReadException :
    RuntimeException("Without web search, pick documents or websites to read")

class ResearchDocumentNotFoundException(val id: Long) :
    RuntimeException("No readable document with id $id in this workspace"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchTooManyWebsitesException(val most: Int) :
    RuntimeException("A research takes at most $most websites in each list"), Refusal {
    override val arguments get() = mapOf("most" to most)
}

class ResearchWebsiteTooLongException(val most: Int) :
    RuntimeException("A website is at most $most characters"), Refusal {
    override val arguments get() = mapOf("most" to most)
}

class ResearchModeNameInvalidException : RuntimeException("A research mode needs a name of up to 100 characters")

class ResearchModeNameTakenException(val name: String) :
    RuntimeException("A research mode named \"$name\" already exists"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class ResearchModeWorkflowNotFoundException(val id: Long) :
    RuntimeException("No workflow with id $id in this workspace"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchModeWorkflowNotChosenException : RuntimeException("Choose the workflow the mode runs, or a mode to copy")

class ResearchModeSeededException(val mode: String) :
    RuntimeException("$mode is one of the three research modes every workspace has, and stays"), Refusal {
    override val arguments get() = mapOf("mode" to mode)
}
