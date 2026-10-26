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
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
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
    private val steps: ExecutionStepRepository,
    private val estimates: ResearchEstimates,
    private val drafts: ResearchDrafts,
    private val pipeline: ResearchPipeline,
    private val followUps: ResearchFollowUps,
    private val agents: io.mszymanski.orknux.server.agent.AgentRepository,
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

    /** One research; the one being looked at, so it is the one told how long it has left. */
    @QueryMapping
    fun research(@Argument id: Long): ResearchView? {
        val held = researches.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(caughtUp(held), estimated = true)
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
        /*
         * The agents it runs with, and the model each thinks with: the ones
         * asked for, or the Research planner and writer. Checked here rather
         * than at the step, so a research that could never think is refused
         * before it starts instead of failing at Plan. Issue #678.
         */
        val planner = picked(input.workspaceId, input.plannerAgentId, ResearchModes.PLANNER)
        val writer = picked(input.workspaceId, input.writerAgentId, ResearchModes.WRITER)
        val useWebSearch = input.useWebSearch ?: true
        if (!useWebSearch && included.isEmpty() && websitesInclude.isEmpty()) throw ResearchNothingToReadException()
        // On unless said otherwise: a report with its pictures is what most research is asked for.
        val includeImages = input.includeImages ?: true
        // A review is a second agent reading the report: the one asked for, or the Research reviewer.
        val review = input.review ?: false
        val reviewer = if (!review) {
            null
        } else {
            // A review asked of a workflow with no Review step would be a research that says it was reviewed and was not.
            if (!seeding.reviews(requireNotNull(assignment.workflow.id))) throw ResearchModeNotReviewedException(mode.name)
            picked(input.workspaceId, input.reviewerAgentId, ResearchModes.REVIEWER)
        }
        // How many questions to suggest once it is finished: what the start said, or the workspace's number.
        val bounds = io.mszymanski.orknux.server.tuning.Tunable.RESEARCH_FOLLOW_UPS.let { it.least..it.most }
        val followUpCount = input.followUps?.also {
            if (it !in bounds) throw ResearchFollowUpsOutOfRangeException(it, bounds.first, bounds.last)
        } ?: followUps.countFor(input.workspaceId)
        // The research it follows up, which has to be one of this workspace's.
        val parent = input.parentId?.let { parentId ->
            researches.findByIdOrNull(parentId)?.takeIf { it.workspaceId == input.workspaceId } ?: throw ResearchNotFoundException(parentId)
        }

        val research = researches.save(
            Research(
                workspaceId = input.workspaceId,
                question = question,
                modeId = mode.id,
                modeName = mode.name,
                workflowId = requireNotNull(assignment.workflow.id),
                workflowName = assignment.workflow.name,
                // The Writer's model: what the notes on each source are taken with, and what it wrote with.
                modelId = writer.model.id,
                modelName = writer.model.name,
                language = input.language,
                outputFormat = input.outputFormat,
                sources = mapper.writeValueAsString(sources),
                useWebSearch = useWebSearch,
                includeImages = includeImages,
                review = review,
                reviewerModelId = reviewer?.model?.id,
                reviewerModelName = reviewer?.model?.name,
                plannerAgentId = planner.agent.id,
                plannerAgentName = planner.agent.name,
                writerAgentId = writer.agent.id,
                writerAgentName = writer.agent.name,
                reviewerAgentId = reviewer?.agent?.id,
                reviewerAgentName = reviewer?.agent?.name,
                followUpCount = followUpCount,
                parentResearchId = parent?.id,
                startedBy = SecurityContextHolder.getContext().authentication?.name ?: "system",
            ),
        )
        auditRecorder.record(
            input.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Research started in ${mode.name} mode" +
                (parent?.let { " as a follow-up of #${it.id}" } ?: "") +
                ": ${question.replace(Regex("\\s+"), " ").take(MOST_AUDITED)}",
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
                /*
                 * The Writer's model, which Gather and Follow up take their
                 * notes on each source with: the notes are what the writer
                 * writes from, so they are read the way it would read them.
                 */
                ResearchCapability.MODEL to writer.model.id.toString(),
                // Who answers Plan and Write, and whose instructions Review reads; see ResearchModes.
                ResearchCapability.PLANNER_AGENT to planner.agent.id.toString(),
                ResearchCapability.WRITER_AGENT to writer.agent.id.toString(),
                ResearchCapability.REVIEWER_AGENT to reviewer?.agent?.id?.toString(),
                ResearchCapability.WEBSITES_INCLUDE to websitesInclude,
                ResearchCapability.WEBSITES_EXCLUDE to websitesExclude,
                ResearchCapability.DOCUMENTS_INCLUDE to included.map { requireNotNull(it.id).toString() },
                ResearchCapability.DOCUMENTS_EXCLUDE to excluded.map { requireNotNull(it.id).toString() },
                // By name as well, for the writer agent's prompt: an id says nothing to a model.
                ResearchModes.DOCUMENTS_EXCLUDE_NAMES to (excluded.joinToString(", ") { it.name }.ifEmpty { "none" }),
                ResearchCapability.WEB_SEARCH to useWebSearch,
                ResearchCapability.INCLUDE_IMAGES to includeImages,
                // Whether Review report runs, and with what: read by the question before it and by the step itself.
                ResearchCapability.REVIEW_ASKED to review,
                ResearchCapability.REVIEWER_MODEL to reviewer?.model?.id?.toString(),
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

    /** An agent a research runs with, and the model it thinks with. */
    private class Picked(val agent: io.mszymanski.orknux.server.agent.Agent, val model: io.mszymanski.orknux.connector.model.LlmModelView)

    /**
     * The agent asked for a part of a research, or the seeded one for it, and
     * the model it thinks with - refused where it is not this workspace's, is
     * switched off, or has no chat model this workspace can use. Issue #678.
     */
    private fun picked(workspaceId: Long, agentId: Long?, seeded: ResearchModes.SeededAgent): Picked {
        val agent = if (agentId != null) {
            agents.findByIdOrNull(agentId)?.takeIf { it.workspaceId == workspaceId } ?: throw ResearchAgentNotFoundException(agentId)
        } else {
            agents.findByWorkspaceIdAndName(workspaceId, seeded.name) ?: throw ResearchAgentMissingException(seeded.name)
        }
        if (!agent.enabled) throw ResearchAgentDisabledException(agent.name)
        val model = agent.modelId?.let { chatModels.usable(workspaceId, it) } ?: throw ResearchAgentModelMissingException(agent.name)
        return Picked(agent, model)
    }

    /**
     * Runs a research again from one of its steps, as the same research.
     *
     * A research that failed at Gather - the model gave up, the server was
     * restarted under it - had planned and searched and read, and starting a
     * new one threw all of that away and asked the model to plan again. This
     * restarts its run from the step asked for, or by default from the step
     * that failed or was cut short, through the same start a rerun of a step
     * takes: the steps before it are carried over from the run as they were,
     * and refused where that cannot honestly be done, in the execution
     * module's own words. The research moves onto the new run - see
     * [ResearchRuns.started] - and keeps what the steps before it found.
     */
    @MutationMapping
    fun restartResearch(@Argument id: Long, @Argument fromStage: ResearchStage?): ResearchView {
        val held = researches.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ResearchNotFoundException(id)
        access.requireVisible(held.workspaceId)
        val research = caughtUp(held)
        if (research.status == ResearchStatus.RUNNING) throw ResearchStillRunningException(id)
        val previous = research.executionId?.let { executions.findByIdOrNull(it) } ?: throw ResearchNeverRanException(id)

        val recorded = steps.findByExecutionIdOrderByOrderAsc(requireNotNull(previous.id))
        val from = restartPoint(recorded, fromStage)
        try {
            following.restart(research, previous, from?.nodeKey)
        } catch (refused: ResearchStillRunningException) {
            throw refused
        } catch (refused: RuntimeException) {
            throw ResearchRestartRefusedException(refused.message ?: "the run could not be started again")
        }
        auditRecorder.record(
            research.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Research restarted from ${from?.name ?: "the beginning"}: " +
                research.question.replace(Regex("\\s+"), " ").take(MOST_AUDITED),
        )
        return describe(researches.findByIdOrNull(id) ?: research, estimated = true)
    }

    /**
     * The step of [recorded] a restart begins at; null for the beginning,
     * which is a rerun of the whole run.
     *
     * Asked for by stage, that stage's step. Otherwise the step the run
     * failed at, or the one a stop or a restart cut short - left SKIPPED - and
     * where a run was stopped between two steps, the one it had last finished,
     * because a step that never began has nothing recorded to begin from.
     */
    private fun restartPoint(recorded: List<ExecutionStep>, stage: ResearchStage?): ExecutionStep? {
        if (stage != null) {
            val key = ResearchRuns.NODES.getValue(stage)
            val at = recorded.indexOfFirst { it.nodeKey == key }
            if (at < 0) throw ResearchStageNotInRunException(stage)
            return recorded[at].takeIf { at > 0 }
        }
        recorded.indexOfFirst { it.status == StepStatus.FAILED }
            .takeIf { it >= 0 }
            ?.let { return recorded[it].takeIf { _ -> it > 0 } }
        // The last such: a step a condition went past is SKIPPED too, and is not where the run stopped.
        recorded.indexOfLast { it.status != StepStatus.COMPLETED && it.status != StepStatus.PENDING }
            .takeIf { it >= 0 }
            ?.let { return recorded[it].takeIf { _ -> it > 0 } }
        val waiting = recorded.indexOfFirst { it.status == StepStatus.PENDING }
        if (waiting < 0) throw ResearchNothingToRestartException()
        return recorded.take(waiting).lastOrNull { it.status == StepStatus.COMPLETED }?.takeIf { waiting > 1 }
    }

    /**
     * A research on one of a finished research's follow-up questions: the same
     * mode, agents, language, output, websites, documents, web search, images,
     * review and number of follow-ups, with the question as its topic and the
     * parent as where it came from. Through [startResearch], so it is checked
     * and refused exactly as any start is - a model the workspace has since
     * lost, a mode removed.
     */
    @MutationMapping
    fun startFollowUpResearch(@Argument parentId: Long, @Argument question: String): ResearchView {
        val parent = researches.findByIdOrNull(parentId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ResearchNotFoundException(parentId)
        return startResearch(followUpOf(parent, question))
    }

    /** What starting a follow-up of [parent] on [question] asks for: everything the parent was started with. */
    fun followUpOf(parent: Research, question: String): StartResearchInput {
        val sources = runCatching { mapper.readTree(parent.sources) }.getOrNull()
        fun texts(key: String) = sources?.path(key)?.toList()?.mapNotNull { one ->
            one.takeIf { it.isTextual }?.stringValue() ?: one.takeIf { it.isNumber }?.asString()
        }.orEmpty()
        return StartResearchInput(
            workspaceId = parent.workspaceId,
            question = question,
            modeId = parent.modeId ?: throw ResearchModeNotFoundException(0),
            // The agents it ran with; one started before there were agents to pick runs with the seeded ones.
            plannerAgentId = parent.plannerAgentId,
            writerAgentId = parent.writerAgentId,
            language = parent.language,
            outputFormat = parent.outputFormat,
            websitesInclude = texts("websitesInclude"),
            websitesExclude = texts("websitesExclude"),
            documentsInclude = texts("documentsInclude").mapNotNull { it.toLongOrNull() },
            documentsExclude = texts("documentsExclude").mapNotNull { it.toLongOrNull() },
            useWebSearch = parent.useWebSearch,
            includeImages = parent.includeImages,
            review = parent.review,
            reviewerAgentId = parent.reviewerAgentId,
            followUps = parent.followUpCount,
            parentId = parent.id,
        )
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
            reviewable = assignment?.workflow?.id?.let { seeding.reviews(it) } ?: false,
        )
    }

    private fun describe(research: Research, estimated: Boolean = false): ResearchView {
        val id = requireNotNull(research.id)
        val running = research.status == ResearchStatus.RUNNING
        val sources = runCatching { mapper.readValue(research.sources, Map::class.java) }.getOrNull().orEmpty()
        fun list(key: String): List<String> = (sources[key] as? List<*>).orEmpty().map { it.toString() }
        val names = (list("documentsInclude") + list("documentsExclude")).mapNotNull { it.toLongOrNull() }
            .let { ids -> if (ids.isEmpty()) emptyMap() else saved.findAllById(ids).associate { requireNotNull(it.id) to it.name } }
        fun documents(key: String) = list(key).mapNotNull { it.toLongOrNull() }.map { names[it] ?: "#$it" }
        val artifact = research.artifactId?.let { saved.findByIdOrNull(it) }
        return ResearchView(
            id = id,
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
            artifactKey = artifact?.id?.let { "${io.mszymanski.orknux.server.workflow.ArtifactKind.SAVED.name}-$it" },
            previewUrl = artifact?.let { "/api/artifacts/${it.id}/preview" },
            error = research.error,
            startedBy = research.startedBy,
            startedAt = research.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            finishedAt = research.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            earlierExecutionIds = following.earlierRunsOf(research),
            review = research.review,
            reviewerModelId = research.reviewerModelId,
            reviewerModelName = research.reviewerModelName,
            plannerAgentId = research.plannerAgentId,
            plannerAgentName = research.plannerAgentName,
            writerAgentId = research.writerAgentId,
            writerAgentName = research.writerAgentName,
            reviewerAgentId = research.reviewerAgentId,
            reviewerAgentName = research.reviewerAgentName,
            reviewRounds = pipeline.reviewRoundsFrom(research.reviewRounds),
            followUps = followUps.of(research.followUps),
            followUpCount = research.followUpCount,
            parentId = research.parentResearchId,
            parentQuestion = research.parentResearchId?.takeIf { estimated }?.let { researches.findByIdOrNull(it)?.question },
            children = if (!estimated) {
                emptyList()
            } else {
                researches.findByParentResearchIdOrderByStartedAtAscIdAsc(id).map { ResearchChildView(requireNotNull(it.id), it.question, it.status) }
            },
            draft = if (running) drafts.of(id) else null,
            estimatedSecondsLeft = if (running && estimated) estimates.secondsLeft(research) else null,
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
            reading = read.path("reading").takeIf { it.isObject }?.let { reading ->
                fun text(key: String) = reading.path(key).takeIf { it.isTextual }?.stringValue()
                text("url")?.let { url -> ResearchReadingView(title = text("title") ?: url, url = url, kind = text("kind") ?: "WEB") }
            },
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
    /** The agents that plan and write; absent, the Research planner and writer. Issue #678. */
    val plannerAgentId: Long? = null,
    val writerAgentId: Long? = null,
    val language: ResearchLanguage,
    val outputFormat: ResearchOutputFormat,
    val websitesInclude: List<String>? = null,
    val websitesExclude: List<String>? = null,
    val documentsInclude: List<Long>? = null,
    val documentsExclude: List<Long>? = null,
    /** Absent is on: a research searches the web unless told not to. */
    val useWebSearch: Boolean? = null,
    /** Absent is on: a research looks for images unless told not to. */
    val includeImages: Boolean? = null,
    /** Absent is off: a research is reviewed only when asked to. */
    val review: Boolean? = null,
    /** The agent that reviews where it is; absent, the Research reviewer. */
    val reviewerAgentId: Long? = null,
    /** How many follow-up questions to suggest once finished; absent is the workspace's number. */
    val followUps: Int? = null,
    /** The research this one follows up. */
    val parentId: Long? = null,
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
    /** Whether its workflow has a Review step, so a research in it may be asked to be reviewed. */
    val reviewable: Boolean,
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
    /**
     * The artifact as the Artifacts page addresses it, kind and row: the bare
     * row number opened nothing there, and the page fell back to its list.
     */
    val artifactKey: String? = null,
    val previewUrl: String?,
    val error: String?,
    val startedBy: String,
    val startedAt: String,
    val finishedAt: String?,
    /** The runs it was on before [executionId], oldest first. */
    val earlierExecutionIds: List<Long> = emptyList(),
    /** The report as its writer is writing it, while it runs; see [ResearchDrafts]. */
    val draft: String? = null,
    /** About how long it has left; see [ResearchEstimates]. */
    val estimatedSecondsLeft: Int? = null,
    val review: Boolean = false,
    val reviewerModelId: Long? = null,
    val reviewerModelName: String? = null,
    /** The agents it was started with, and what each was called then; null on one started before. */
    val plannerAgentId: Long? = null,
    val plannerAgentName: String? = null,
    val writerAgentId: Long? = null,
    val writerAgentName: String? = null,
    val reviewerAgentId: Long? = null,
    val reviewerAgentName: String? = null,
    /** What each round of review said; empty for one not reviewed, or not yet. */
    val reviewRounds: List<ResearchReviewRound> = emptyList(),
    val followUps: List<String> = emptyList(),
    val followUpCount: Int? = null,
    val parentId: Long? = null,
    val parentQuestion: String? = null,
    val children: List<ResearchChildView> = emptyList(),
)

/** A research started from another as a follow-up, as the parent lists it. */
data class ResearchChildView(val id: Long, val question: String, val status: ResearchStatus)

data class ResearchProgressView(
    val step: String,
    val subQuestions: List<String>,
    val searched: List<String>,
    val level: Int,
    val sourcesRead: Int,
    val stage: ResearchStage?,
    /** What is being read this moment, before it is a source. */
    val reading: ResearchReadingView? = null,
)

data class ResearchReadingView(val title: String, val url: String, val kind: String)

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

class ResearchAgentNotFoundException(val id: Long) : RuntimeException("No agent with id $id in this workspace"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchAgentMissingException(val agent: String) :
    RuntimeException("This workspace has no $agent; choose the agent to use instead"), Refusal {
    override val arguments get() = mapOf("agent" to agent)
}

class ResearchAgentDisabledException(val agent: String) :
    RuntimeException("$agent is switched off; switch it on or choose another agent"), Refusal {
    override val arguments get() = mapOf("agent" to agent)
}

class ResearchAgentModelMissingException(val agent: String) :
    RuntimeException("$agent has no chat model this workspace can use; choose one on its page"), Refusal {
    override val arguments get() = mapOf("agent" to agent)
}

class ResearchFollowUpsOutOfRangeException(val count: Int, val least: Int, val most: Int) :
    RuntimeException("$count is not a number of follow-up questions; choose between $least and $most"), Refusal {
    override val arguments get() = mapOf("count" to count, "least" to least, "most" to most)
}

class ResearchModeNotReviewedException(val mode: String) :
    RuntimeException("The research mode $mode has no Review step in its workflow, so it cannot be reviewed"), Refusal {
    override val arguments get() = mapOf("mode" to mode)
}

class ResearchReviewRoundsOutOfRangeException(val rounds: Int, val least: Int, val most: Int) :
    RuntimeException("$rounds is not a number of review rounds; choose between $least and $most"), Refusal {
    override val arguments get() = mapOf("rounds" to rounds, "least" to least, "most" to most)
}

class ResearchNotFoundException(val id: Long) : RuntimeException("No research with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchStillRunningException(val id: Long) :
    RuntimeException("Research $id is still running; it can be restarted once it has finished, failed or been stopped"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchNeverRanException(val id: Long) : RuntimeException("Research $id never started a run to restart"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ResearchStageNotInRunException(val stage: ResearchStage) :
    RuntimeException("This research's run has no $stage step to restart from"), Refusal {
    override val arguments get() = mapOf("stage" to stage.name)
}

class ResearchNothingToRestartException :
    RuntimeException("Every step of this research finished; say which step to restart from")

class ResearchRestartRefusedException(val reason: String) :
    RuntimeException("The research cannot be restarted there: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}

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
