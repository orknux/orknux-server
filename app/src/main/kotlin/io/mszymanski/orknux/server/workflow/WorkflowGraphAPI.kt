package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.action.ActionParameters
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.obj.PropertyKind
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import io.mszymanski.orknux.server.revision.RevisionSubject
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.action.ActionParamView
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.VALUE_SUBJECT
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.format.DateTimeFormatter

@Controller
class WorkflowGraphAPI(
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    private val triggers: WorkflowTriggerRepository,
    private val actions: WorkflowActionRepository,
    private val conditions: WorkflowConditionRepository,
    private val workspaces: WorkspaceRepository,
    private val validator: GraphValidator,
    private val parameters: ActionParameters,
    private val agents: AgentRepository,
    private val objects: WorkflowObjectRepository,
    // Only for a condition node's parameters: the list is the function's, and
    // this is where a node's mappings are resolved against a definition.
    private val functions: WorkflowFunctionRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val publications: WorkflowPublicationRepository,
    private val revisions: ComponentRevisionRecorder,
    private val graphSource: AppWorkflowGraphSource,
    private val mapper: ObjectMapper,
    /** What an image node's model takes beyond a prompt, which its size, quality and style are held to. Issue #431. */
    private val imageModels: ImageModelCapabilities,
) {

    @QueryMapping
    fun workflowGraph(@Argument workspaceId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)
        return graphOf(workspaceId, workflowId)
    }

    /**
     * What a node would pass if it were pointed at this action right now.
     *
     * The editor asks when somebody picks an action, so the panel can show the
     * parameters that action has instead of an empty box. It is a suggestion:
     * what the node keeps is whatever is saved on it.
     */
    @QueryMapping
    fun actionParameterDefaults(@Argument workspaceId: Long, @Argument actionId: Long): List<NodeMappingView> {
        val workspace = access.requireVisible(workspaceId)
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.workspaceId != workspaceId) throw ActionNotInCatalogueException(actionId)
        return parameters.defaultsFor(action).map { NodeMappingView(it.name, it.expression, it.mode) }
    }

    /**
     * The same answer a save gives, for a graph that has not been saved.
     *
     * What a node needs and produces follows from what it points at and what it
     * was told to pass, so it changes the moment either does — but it was only
     * ever worked out when the graph was written down, which left the editor
     * showing the ports and the problems of the graph as it was some edits ago.
     *
     * Nothing is written and nothing is refused: this is what the graph would be,
     * asked of a graph somebody is still drawing.
     */
    @QueryMapping
    fun workflowGraphPreview(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: WorkflowGraphInput,
    ): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)

        val known = input.nodes.map { it.key }.toSet()
        val proposed = shapedByTargets(input.nodes.map { nodeOf(workflowId, it, refusing = false) }, refusing = false)
        // An edge to a node that is not there is something a half-drawn graph
        // has; the validator is given the graph, not an argument about it.
        val drawn = input.edges
            .filter { it.source in known && it.target in known }
            .map { WorkflowEdge(workflowId = workflowId, sourceKey = it.source, targetKey = it.target, branch = it.branch) }

        return WorkflowGraphView(
            workflowId = workflowId,
            name = workflow.name,
            description = workflow.description,
            status = workflow.status,
            enabled = enabledIn(workspaceId, workflowId),
            assignmentId = assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.id,
            nodes = proposed.map { node ->
                val ports = validator.portsOf(node, proposed)
                WorkflowNodeView(node, ports.inputs, ports.outputs)
            },
            edges = drawn.map(::WorkflowEdgeView),
            problems = validator.problems(proposed, drawn, workspaceId = workspaceId),
        )
    }

    /** Replaces the whole graph: the editor always sends the full picture. */
    @MutationMapping
    @Transactional
    fun saveWorkflowGraph(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: WorkflowGraphInput,
    ): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)

        val keys = input.nodes.map { it.key }
        require(keys.size == keys.toSet().size) { "Node keys have to be unique within a workflow" }
        input.edges.forEach { edge ->
            require(edge.source in keys && edge.target in keys) {
                "Edge ${edge.source} -> ${edge.target} refers to a node that is not in the graph"
            }
        }

        input.nodes.forEach { requireTriggerBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireActionBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireConditionBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireAgentBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireObjectBelongsToWorkspace(workspaceId, it) }
        input.nodes.forEach { requireOutputShapeBelongsToWorkspace(workspaceId, it) }

        // A graph is drawn before it is finished, so only the shapes that could
        // never run are refused; everything else comes back as advice.
        val proposed = input.nodes.map { node ->
            WorkflowNode(
                workflowId = workflowId,
                nodeKey = node.key,
                kind = node.kind,
                name = node.name,
                // Carried into the check because two nodes claiming one output
                // name is one of the shapes a save refuses; without it here the
                // rule would only ever be evaluated against a blank.
                outputName = node.outputName?.trim()?.ifEmpty { null },
                orientation = node.orientation,
                // Carried into the check for the same reason: a failure edge
                // out of a node that does not handle failure is one of the
                // shapes a save refuses, and without this it would be judged
                // against a node that never handles it.
                fallbackEnabled = node.fallbackEnabled && handlesFailure(node.kind),
                positionX = node.x,
                positionY = node.y,
                // Carried into the check for the same reason again: a skill id
                // written on an agent node that names no skill is a shape a
                // save refuses, and it is only checkable with the id in hand.
                // Only that one; the rest are read and refused by mappingsFor.
                mappings = node.mappings.orEmpty()
                    .filter { it.name == SKILL_IDS && it.mode == MappingMode.VALUE }
                    .map { NodeMapping(name = it.name, expression = it.expression, mode = it.mode) }
                    .toMutableList(),
            )
        }
        val proposedEdges = input.edges.map {
            WorkflowEdge(workflowId = workflowId, sourceKey = it.source, targetKey = it.target, branch = it.branch)
        }
        val refusals = validator.problems(proposed, proposedEdges, hardOnly = true, workspaceId = workspaceId)
        if (refusals.isNotEmpty()) throw GraphInvalidException(refusals)

        nodes.deleteByWorkflowId(workflowId)
        edges.deleteByWorkflowId(workflowId)
        nodes.flush()
        edges.flush()

        nodes.saveAll(shapedByTargets(input.nodes.map { nodeOf(workflowId, it) }, refusing = true))
        edges.saveAll(
            input.edges.map { edge ->
                WorkflowEdge(workflowId = workflowId, sourceKey = edge.source, targetKey = edge.target, branch = edge.branch)
            },
        )

        // Editing puts a published workflow back into draft.
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        workflow.status = WorkflowStatus.DRAFT

        /*
         * Said, and deliberately not decided here.
         *
         * A workflow has a draft, so by the rule the recorder holds this writes
         * nothing - a draft is a draft, and the version is what gets published.
         * The call is here anyway because the rule is one rule: if a workflow's
         * kind ever stopped having a draft, or another kind gained one, the
         * recorder would change its answer and this door would already be
         * right. Nothing is serialised while the answer is no.
         */
        revisions.saved(ComponentRevisionKind.WORKFLOW) {
            RevisionSubject(
                workspaceId = workspaceId,
                componentId = workflowId,
                name = workflow.name,
                savedAt = java.time.OffsetDateTime.now(),
                savedBy = currentUser(),
                snapshot = WorkflowSnapshot.write(graphSource.drafted(workflowId, workflow.name), mapper),
            )
        }

        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow ${workflow.name} graph updated")
        return graphOf(workspaceId, workflowId)
    }

    @MutationMapping
    @Transactional
    fun publishWorkflow(@Argument workspaceId: Long, @Argument workflowId: Long): WorkflowGraphView {
        requireAssignment(workspaceId, workflowId)

        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        if (nodes.findByWorkflowId(workflowId).isEmpty()) throw WorkflowGraphEmptyException()

        /*
         * Publishing is the copy, not the badge.
         *
         * The status is what a person reads; this is what a trigger runs. They
         * are written together so that a graph can never be marked live without
         * something live to point at - and from here, editing and saving change
         * the draft alone, which is what makes it safe to leave one half-drawn.
         */
        revisions.published(
            workflowId = workflowId,
            graph = WorkflowSnapshot.write(graphSource.drafted(workflowId, workflow.name), mapper),
            by = currentUser(),
        )
        workflow.status = WorkflowStatus.PUBLISHED
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Workflow ${workflow.name} published")
        return graphOf(workspaceId, workflowId)
    }

    /**
     * Every publication of this workflow, newest first — its version history.
     *
     * Without the graphs. A publication's snapshot is the whole runnable graph
     * and a workflow published daily for a month is a month of them; the list
     * says who published what and when, and [workflowPublicationGraph] fetches
     * one when somebody opens it.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workflowPublications(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument limit: Int?,
    ): List<WorkflowPublicationView> {
        requireAssignment(workspaceId, workflowId)
        val held = publications.findByWorkflowIdOrderByIdDesc(
            workflowId,
            PageRequest.of(0, (limit ?: DEFAULT_PUBLICATIONS).coerceIn(1, MOST_PUBLICATIONS)),
        )
        // The first row of a list ordered newest-first is what runs, by the
        // same rule the runner uses - so the two cannot disagree about which.
        val running = held.firstOrNull()?.id
        return held.map { WorkflowPublicationView(it, current = it.id == running) }
    }

    /** One publication's graph, as it was published. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workflowPublicationGraph(@Argument workspaceId: Long, @Argument publicationId: Long): String {
        val held = publicationIn(workspaceId, publicationId)
        return held.graph
    }

    /**
     * Puts an older publication back into service.
     *
     * By publishing it again, rather than by deleting what came after: the
     * newest publication is what runs, so a restore that removed rows would be
     * a history that rewrites itself, and there would be no record that
     * somebody rolled anything back. This is the shape of reverting a commit —
     * a new entry, saying which older one it copied.
     *
     * **It does not touch the draft.** The draft is what somebody is in the
     * middle of and it is not versioned, so overwriting it with a month-old
     * graph would destroy unpublished work with nothing to get it back from.
     * What changes is what runs. The badge follows from that: it says Published
     * when the draft and the restored graph are the same picture, and Draft
     * when they are not, which is exactly what it has always meant.
     */
    @MutationMapping
    @Transactional
    fun restoreWorkflowPublication(
        @Argument workspaceId: Long,
        @Argument publicationId: Long,
    ): WorkflowGraphView {
        val held = publicationIn(workspaceId, publicationId)
        val workflowId = held.workflowId
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)

        revisions.published(
            workflowId = workflowId,
            graph = held.graph,
            by = currentUser(),
            restoredFrom = publicationId,
        )
        workflow.status = if (samePicture(held.graph, graphSource.drafted(workflowId, workflow.name))) {
            WorkflowStatus.PUBLISHED
        } else {
            WorkflowStatus.DRAFT
        }
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Workflow ${workflow.name} restored to the publication of " +
                DateTimeFormatter.ISO_INSTANT.format(held.publishedAt.toInstant()),
        )
        return graphOf(workspaceId, workflowId)
    }

    /**
     * Whether a stored graph and a drawn one are the same picture.
     *
     * Compared as parsed trees rather than as text, because the stored half has
     * been through a `jsonb` column: Postgres rewrites the object it was given -
     * key order and whitespace are its own - so two identical graphs come back
     * as two different strings, and the badge would say Draft for every restore.
     *
     * Nodes keep the order the draft is read in, so two graphs that differ only
     * in that read as different and the badge says Draft. That is the safe way
     * round: it asks somebody to publish a graph that already is, rather than
     * telling them one is live when it is not.
     */
    private fun samePicture(stored: String, drawn: RunnableGraph): Boolean =
        mapper.readTree(stored) == mapper.readTree(WorkflowSnapshot.write(drawn, mapper))

    /** One publication, checked to be this workspace's before it is handed out. */
    private fun publicationIn(workspaceId: Long, publicationId: Long): WorkflowPublication {
        val held = publications.findByIdOrNull(publicationId)
            ?: throw WorkflowPublicationNotFoundException(publicationId)
        requireAssignment(workspaceId, held.workflowId)
        return held
    }

    /** Whoever is asking, for the record of who made a graph live. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun graphOf(workspaceId: Long, workflowId: Long): WorkflowGraphView {
        val workflow = workflows.findByIdOrNull(workflowId) ?: throw WorkflowNotFoundException(workflowId)
        val held = nodes.findByWorkflowId(workflowId)
        val drawn = edges.findByWorkflowId(workflowId)
        return WorkflowGraphView(
            workflowId = workflowId,
            name = workflow.name,
            description = workflow.description,
            status = workflow.status,
            enabled = enabledIn(workspaceId, workflowId),
            assignmentId = assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.id,
            nodes = held.map { node ->
                val ports = validator.portsOf(node, held)
                WorkflowNodeView(node, ports.inputs, ports.outputs)
            },
            edges = drawn.map(::WorkflowEdgeView),
            problems = validator.problems(held, drawn, workspaceId = workspaceId),
        )
    }

    /**
     * What a session node is: a key, and what it is filed under.
     *
     * In this order because that is the order they read in - the prefix names
     * the conversation, the key names which one of it. Both are always present,
     * and the optional one is the prefix.
     */
    private val SESSION_PARAMETERS = listOf("sessionKeyPrefix", "sessionKey")

    /**
     * What an image node holds, and the name is the runner's: [ImageNodeRunner]
     * looks for `prompt`, and a node without one is a node it skips.
     */
    private val IMAGE_PARAMETERS = listOf("prompt")

    /**
     * The node a save would write, which is also the node a preview describes.
     *
     * One place, so what the editor is shown and what it gets when it saves
     * cannot be two different nodes.
     *
     * @param refusing whether a setting that could never work stops the caller.
     *   A save refuses; a preview is asked about a graph somebody is still
     *   typing into, where refusing would be arguing with them mid-word.
     */
    private fun nodeOf(workflowId: Long, node: WorkflowNodeInput, refusing: Boolean = true): WorkflowNode {
        // What the drawing is asked for beyond the prompt, kept only on the kind
        // that draws. Held to what the node's own model takes when saving, so a
        // size DALL-E 3 does not draw or a style gpt-image-1 does not take is
        // refused here, naming what the model does take, rather than as a 400
        // mid-run; a preview is asked about a graph still being typed into, and
        // lets it by. Issue #431.
        val drawing = if (node.kind == NodeKind.IMAGE) {
            imageModels.held(node.imageModelId, ImageOptions(node.imageSize, node.imageQuality, node.imageStyle), refusing)
        } else {
            ImageOptions.NONE
        }
        return WorkflowNode(
            workflowId = workflowId,
            nodeKey = node.key,
            kind = node.kind,
            name = node.name.trim().ifEmpty { "Untitled node" },
            description = node.description?.trim()?.ifEmpty { null },
            agentId = node.agentId.takeIf { node.kind == NodeKind.AGENT },
            triggerId = node.triggerId.takeIf { node.kind == NodeKind.TRIGGER },
            actionId = node.actionId.takeIf { node.kind == NodeKind.ACTION },
            conditionId = node.conditionId.takeIf { node.kind == NodeKind.CONDITION },
            objectId = node.objectId.takeIf { node.kind == NodeKind.OBJECT },
            // Only an agent's answer has a shape to be held to; on any other kind
            // the id is dropped the way an object node's would be on an action.
            outputObjectId = node.outputObjectId.takeIf { node.kind == NodeKind.AGENT },
            // Which object node the answer is saved into; the shape above is then
            // derived from it after the whole list is built - see shapedByTargets.
            outputNodeKey = node.outputNodeKey?.trim()?.ifEmpty { null }?.takeIf { node.kind == NodeKind.AGENT },
            imageModelId = node.imageModelId.takeIf { node.kind == NodeKind.IMAGE },
            imageSize = drawing.size,
            imageQuality = drawing.quality,
            imageStyle = drawing.style,
            outputName = node.outputName?.trim()?.ifEmpty { null }
                // Only a node that produces something can name it; a trigger names
                // its own fields and a condition passes through what it was given.
                ?.takeIf {
                    node.kind == NodeKind.AGENT || node.kind == NodeKind.ACTION ||
                        node.kind == NodeKind.OBJECT || node.kind == NodeKind.IMAGE
                }
                ?.also { if (refusing) requireReferenceable(it) },
            icon = node.icon?.trim()?.ifEmpty { null },
            orientation = node.orientation,
            positionX = node.x,
            positionY = node.y,
            // Only a node with two ways out has them to name: a condition, or a
            // node that handles its own failure.
            yesLabel = node.yesLabel?.trim()?.ifEmpty { null }?.takeIf { forks(node) },
            noLabel = node.noLabel?.trim()?.ifEmpty { null }?.takeIf { forks(node) },
            fallbackEnabled = node.fallbackEnabled && handlesFailure(node.kind),
            retryAttempts = node.retryAttempts?.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)
                ?.takeIf { it > MIN_ATTEMPTS && handlesFailure(node.kind) },
            retryBackoffSeconds = node.retryBackoffSeconds?.coerceIn(0, MAX_BACKOFF_SECONDS)
                ?.takeIf { handlesFailure(node.kind) },
            // The four below are kept only where the wait they shape is kept, and
            // only where there is a second attempt for it to sit between: a curve on
            // a node that runs once describes nothing, and one saved on a kind that
            // cannot retry is a setting nothing will ever read.
            retryMultiplier = node.retryMultiplier?.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
                // One is what no multiplier means, so it is stored as no multiplier:
                // a node that was never given a curve should come back off the panel
                // as the row it went in as.
                ?.takeIf { it > MIN_MULTIPLIER && retries(node) },
            // Dropped on a flat curve rather than kept and ignored, because a
            // ceiling under a wait that never grows does not bound it - it cuts it,
            // and a fixed wait quietly shortened by a field the panel had greyed out
            // is the worst of the two.
            retryMaxWaitSeconds = node.retryMaxWaitSeconds?.coerceIn(1, MAX_BACKOFF_SECONDS)
                ?.takeIf { retries(node) && (node.retryMultiplier ?: MIN_MULTIPLIER) > MIN_MULTIPLIER },
            retryJitter = node.retryJitter?.coerceIn(NO_JITTER, FULL_JITTER)
                ?.takeIf { it > NO_JITTER && retries(node) },
            retryBudgetSeconds = node.retryBudgetSeconds?.coerceIn(1, MAX_BUDGET_SECONDS)?.takeIf { retries(node) },
            mappings = mappingsFor(node, refusing),
        )
    }

    /**
     * Whether this node has a second attempt for a backoff to sit between.
     *
     * Everything past the attempt count describes the gap between two of them,
     * so on a node with one attempt there is nothing for any of it to describe.
     */
    private fun retries(node: WorkflowNodeInput): Boolean =
        handlesFailure(node.kind) && (node.retryAttempts ?: MIN_ATTEMPTS) > MIN_ATTEMPTS

    /**
     * Whether a failure here is the node's own business rather than the run's.
     *
     * An action calls something outside this installation, and an agent calls a
     * model, which is the same bet with a longer wait and a bill attached: both
     * fail for reasons that have nothing to do with the graph, and both fail in
     * ways a second go or a different edge is the honest answer to. The rest
     * cannot. A condition that does not hold has answered, not failed; a
     * trigger is what started the run; an object node assembles what it was
     * already handed, so a second attempt assembles the same thing.
     */
    private fun handlesFailure(kind: NodeKind): Boolean =
        kind == NodeKind.ACTION || kind == NodeKind.AGENT

    /** Whether this node has two ways out, and so two labels worth keeping. */
    private fun forks(node: WorkflowNodeInput): Boolean =
        node.kind == NodeKind.CONDITION || (handlesFailure(node.kind) && node.fallbackEnabled)

    /**
     * What this node will pass, resolved against the action it points at.
     *
     * Taken from what was sent where it names a parameter the action actually
     * has, and seeded from the action otherwise. Resolving it this way keeps a
     * node honest when its action changes underneath it: parameters the new
     * action does not have are dropped, ones it gained arrive with the action's
     * own suggestion, and nothing has to remember to tidy up.
     *
     * The action is read here and nowhere else. Once a node holds its mappings
     * they are what runs, so editing them cannot reach back into a definition
     * other nodes are using.
     */
    private fun mappingsFor(node: WorkflowNodeInput, refusing: Boolean): MutableList<NodeMapping> {
        val sent = node.mappings.orEmpty().associateBy { it.name }

        /*
         * An agent's parameters are the node's own.
         *
         * There is no definition to seed them from — an agent declares no
         * parameters — so what was sent is what is kept. This used to return
         * nothing for every kind but ACTION, which meant a prompt typed into an
         * agent node was accepted by the form, saved as nothing, and gone when
         * the page was reopened.
         */
        if (node.kind == NodeKind.AGENT) {
            return sent.values.map { mappingOf(it, refusing) }.toMutableList()
        }

        /*
         * A session node has exactly two parameters, and always both.
         *
         * They are not a catalogue's and not the node's own invention: a session
         * is identified by a key and the prefix it is filed under, and that is
         * the whole of what this kind is. Fixing the list here means the panel
         * cannot be talked into saving a third one, and a node saved before one
         * of them was filled in still comes back with both boxes to fill.
         */
        if (node.kind == NodeKind.SESSION) {
            return SESSION_PARAMETERS
                .map { name -> sent[name]?.let { mappingOf(it, refusing) } ?: NodeMapping(name = name) }
                .toMutableList()
        }

        /*
         * An image node has exactly one parameter, and always it.
         *
         * `prompt` is what it draws from and the whole of what the node is
         * told, so the list is fixed here the way a session's two are - the
         * panel cannot be talked into saving a second one, and a node saved
         * before it was filled in comes back with the box to fill.
         *
         * Without this branch an image node fell through to the action one,
         * which reads an actionId it does not have and kept nothing: a prompt
         * was accepted by the form, saved as nothing, and gone when the page
         * was reopened - so every run skipped the node for having no prompt
         * and the graph looked like it went straight past it. Which is the
         * same bug the agent branch above exists to describe, arriving a
         * second time in a kind added later.
         */
        if (node.kind == NodeKind.IMAGE) {
            return IMAGE_PARAMETERS
                .map { name -> sent[name]?.let { mappingOf(it, refusing) } ?: NodeMapping(name = name) }
                .toMutableList()
        }

        /*
         * An object node's parameters are its fields.
         *
         * A saved shape decides which there are — a field the shape does not
         * have is dropped, and one it has that the node did not fill arrives
         * empty — so a shape edited afterwards is reflected without anything
         * having to tidy up. A shape of the node's own is whatever it sent.
         */
        if (node.kind == NodeKind.OBJECT) {
            val shape = node.objectId?.let { objects.findByIdOrNull(it) }
                ?: return sent.values.map { mappingOf(it, refusing) }.toMutableList()

            return shape.properties
                .map { property -> sent[property.name]?.let { mappingOf(it, refusing) } ?: NodeMapping(name = property.name) }
                .toMutableList()
        }

        /*
         * A condition node's parameters are the condition's function's.
         *
         * The declaration decides which there are, the same way an action's
         * does: a parameter the function does not have is dropped, and one it
         * has that the node did not fill arrives empty. A condition that asks
         * no function - a comparison, an any-of - has none, and a node that
         * fills nothing in keeps nothing, which is what makes the evaluator
         * fall back to the condition's own arguments.
         */
        if (node.kind == NodeKind.CONDITION) {
            val asks = node.conditionId?.let { conditions.findByIdOrNull(it) } ?: return mutableListOf()
            /*
             * A VALUE condition takes one thing, the value, and the node is
             * where it is picked - the same row a function's parameter gets,
             * kept by the same rule: filled in is kept, empty is nothing.
             * Issue #378.
             */
            if (asks.type == ConditionType.VALUE) {
                val picked = sent[VALUE_SUBJECT]?.let { mappingOf(it, refusing) }
                return if (picked != null && picked.expression.isNotEmpty()) mutableListOf(picked) else mutableListOf()
            }
            val function = asks.functionId?.let { functions.findByIdOrNull(it) } ?: return mutableListOf()

            val filled = function.params
                .map { declared -> sent[declared.name]?.let { mappingOf(it, refusing) } ?: NodeMapping(name = declared.name) }
            // Nothing filled in is nothing kept: an empty list is what the
            // evaluator reads as "this node says nothing, use the condition's".
            return if (filled.any { it.expression.isNotEmpty() }) filled.toMutableList() else mutableListOf()
        }

        if (node.kind != NodeKind.ACTION) return mutableListOf()
        val action = node.actionId?.let { actions.findByIdOrNull(it) } ?: return mutableListOf()

        // The action decides which parameters exist; the node decides what fills
        // them. A name the action does not have is dropped, and one it has that
        // the node did not send falls back to the action's own suggestion.
        return parameters.defaultsFor(action)
            .map { parameter ->
                sent[parameter.name]
                    ?.let { mappingOf(it, refusing) }
                    ?: NodeMapping(name = parameter.name, expression = parameter.expression, mode = parameter.mode)
            }
            .toMutableList()
    }

    /**
     * A value is text, so text is all it may be.
     *
     * `{{something}}` in a value is somebody expecting a substitution that no
     * longer happens — and the way it fails is silent: the braces are sent, into
     * a Slack message or a channel name, and nothing reports a problem. Refused
     * at the save, where the person who typed it is still looking, with the
     * thing they should have used instead.
     */
    private fun requireNoPlaceholder(sent: NodeMappingInput) {
        if (sent.mode == MappingMode.REFERENCE) return
        if (PLACEHOLDER_IN_VALUE.containsMatchIn(sent.expression)) throw ValueHoldsPlaceholderException(sent.name)
    }

    private fun mappingOf(sent: NodeMappingInput, refusing: Boolean): NodeMapping {
        if (refusing) requireNoPlaceholder(sent)
        return NodeMapping(
        name = sent.name,
        expression = sent.expression,
        mode = sent.mode,
        // Only meaningful on a reference; kept off a value so a switch back does
        // not leave a node key nothing points at.
            sourceNodeKey = sent.sourceNodeKey?.takeIf { sent.mode == MappingMode.REFERENCE },
            fieldKind = sent.fieldKind,
            // The two halves of a type, each kept only where the kind they
            // belong to is the one chosen: a field switched from a list of
            // Tickets to a number would otherwise go on naming the Ticket.
            fieldElementKind = sent.fieldElementKind?.takeIf { sent.fieldKind == PropertyKind.ARRAY },
            fieldRefObjectId = sent.fieldRefObjectId
                ?.takeIf { sent.fieldKind == PropertyKind.OBJECT || sent.fieldKind == PropertyKind.ARRAY },
        )
    }

    /**
     * A trigger node is an instance of a definition from the workspace's catalogue,
     * so the definition has to be one that workspace holds — otherwise a workflow
     * could listen to another workspace's connection.
     */
    private fun requireTriggerBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val triggerId = node.triggerId ?: return
        if (node.kind != NodeKind.TRIGGER) return
        val trigger = triggers.findByIdOrNull(triggerId) ?: throw TriggerNotInCatalogueException(triggerId)
        if (trigger.workspaceId != workspaceId) throw TriggerNotInCatalogueException(triggerId)
    }

    /**
     * An output name has to be something a later node can actually write.
     *
     * A reference names one field, so a name with a space or a dot in it could
     * be typed here and never referred to anywhere — a setting that looks
     * accepted and quietly does nothing. Refusing it at the save is the only
     * moment the person who typed it is still looking.
     */
    private fun requireReferenceable(outputName: String) {
        if (!REFERENCEABLE.matches(outputName)) throw OutputNameInvalidException(outputName)
    }

    /** An action node runs one of the workspace's actions, and only its own workspace's. */
    private fun requireActionBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val actionId = node.actionId ?: return
        if (node.kind != NodeKind.ACTION) return
        val action = actions.findByIdOrNull(actionId) ?: throw ActionNotInCatalogueException(actionId)
        if (action.workspaceId != workspaceId) throw ActionNotInCatalogueException(actionId)
    }

    /** An object node makes one of the workspace's shapes, and only its own workspace's. */
    private fun requireObjectBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val objectId = node.objectId ?: return
        if (node.kind != NodeKind.OBJECT) return
        val shape = objects.findByIdOrNull(objectId) ?: throw ObjectNotInCatalogueException(objectId)
        if (shape.workspaceId != workspaceId) throw ObjectNotInCatalogueException(objectId)
    }

    /**
     * The shape of an agent that saves into an object node, derived from it.
     *
     * Every save, so the two cannot disagree: the target picking a different
     * object on a later save carries the agent's shape with it, and whatever
     * `outputObjectId` the editor sent beside the reference is overridden. A
     * reference that cannot be derived from - no such node, not an object
     * node, or an object node with a shape of its own rather than a saved
     * one - is refused on a save and left standing on a preview, where the
     * agent simply has no shape until the graph says otherwise.
     */
    private fun shapedByTargets(built: List<WorkflowNode>, refusing: Boolean): List<WorkflowNode> {
        val byKey = built.associateBy { it.nodeKey }
        built.forEach { node ->
            val targetKey = node.outputNodeKey ?: return@forEach
            val target = byKey[targetKey]
            val objectId = target?.takeIf { it.kind == NodeKind.OBJECT }?.objectId
            if (objectId == null) {
                if (refusing) throw AgentOutputNodeInvalidException(node.name, targetKey, target)
                node.outputObjectId = null
            } else {
                node.outputObjectId = objectId
            }
        }
        return built
    }

    /** An agent node's answer is held to one of the workspace's shapes, and only its own workspace's. */
    private fun requireOutputShapeBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val objectId = node.outputObjectId ?: return
        if (node.kind != NodeKind.AGENT) return
        val shape = objects.findByIdOrNull(objectId) ?: throw ObjectNotInCatalogueException(objectId)
        if (shape.workspaceId != workspaceId) throw ObjectNotInCatalogueException(objectId)
    }

    /** An agent node runs one of the workspace's agents, and only its own workspace's. */
    private fun requireAgentBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val agentId = node.agentId ?: return
        if (node.kind != NodeKind.AGENT) return
        val agent = agents.findByIdOrNull(agentId) ?: throw AgentNotInCatalogueException(agentId)
        if (agent.workspaceId != workspaceId) throw AgentNotInCatalogueException(agentId)
    }

    /** A condition node asks one of the workspace's conditions, and only its own workspace's. */
    private fun requireConditionBelongsToWorkspace(workspaceId: Long, node: WorkflowNodeInput) {
        val conditionId = node.conditionId ?: return
        if (node.kind != NodeKind.CONDITION) return
        val condition = conditions.findByIdOrNull(conditionId) ?: throw ConditionNotInCatalogueException(conditionId)
        if (condition.workspaceId != workspaceId) throw ConditionNotInCatalogueException(conditionId)
    }

    /**
     * Whether the workspace has this workflow switched on.
     *
     * The editor is told because Run still works on one that is switched off -
     * trying a graph by hand is how it gets fixed - and somebody who cannot see
     * the switch from here would otherwise publish, walk away, and wait for a
     * trigger that is never going to start it.
     */
    private fun enabledIn(workspaceId: Long, workflowId: Long): Boolean =
        assignments.findByWorkspaceIdAndWorkflowId(workspaceId, workflowId)?.enabled != false

    /** The workflow has to be assigned to a workspace the caller can see. */
    private fun requireAssignment(workspaceId: Long, workflowId: Long) {
        val workspace = access.requireVisible(workspaceId)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) {
            throw WorkflowNotFoundException(workflowId)
        }
    }
}

data class WorkflowNodeInput(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String? = null,
    /** The agent an agent node instances; ignored on any other kind. */
    val agentId: Long? = null,
    /** The trigger definition a trigger node instances; ignored on any other kind. */
    val triggerId: Long? = null,
    /** The action an action node instances; ignored on any other kind. */
    val actionId: Long? = null,
    /** The condition a condition node asks; ignored on any other kind. */
    val conditionId: Long? = null,
    /** The saved shape an object node makes; null is a shape of its own. */
    val objectId: Long? = null,
    /** The shape an agent node's answer is held to; null is prose. Ignored on any other kind. */
    val outputObjectId: Long? = null,
    /**
     * The object node this agent's answer is saved into, named by its key.
     *
     * The other way to shape an answer: the shape becomes the target node's
     * object - derived at every save, so sending [outputObjectId] beside this
     * is overridden - and the target's unmapped fields are filled from the
     * answer when the run reaches it. Ignored on any other kind.
     */
    val outputNodeKey: String? = null,
    /** The image model an image node draws with; ignored on any other kind. */
    val imageModelId: Long? = null,
    /**
     * What an image node asks of the drawing beyond the prompt; null or blank
     * is the model's default, and each is held to what the node's model takes
     * (see [ImageModelCapabilities]). Ignored on any other kind.
     */
    val imageSize: String? = null,
    val imageQuality: String? = null,
    val imageStyle: String? = null,
    val outputName: String? = null,
    val icon: String? = null,
    val orientation: NodeOrientation? = null,
    /**
     * What this node passes to its action. Null leaves it to the action's own
     * suggestions, which is what a node freshly pointed at one wants.
     */
    val mappings: List<NodeMappingInput>? = null,
    /**
     * What a node's two ways out are called; null leaves it to the interface,
     * which says Yes and No for a condition and If works / If fails for an
     * action that handles its failure.
     */
    val yesLabel: String? = null,
    val noLabel: String? = null,
    /**
     * Whether this node has a second way out for when it fails; kept on an
     * action and on an agent, ignored on every other kind.
     */
    val fallbackEnabled: Boolean = false,
    /** How many times in all this node may be attempted; null or 1 is once. */
    val retryAttempts: Int? = null,
    /** The wait before the second attempt, in seconds; null is none. */
    val retryBackoffSeconds: Int? = null,
    /** What that wait is multiplied by after each attempt; null or 1 is a wait that does not grow. */
    val retryMultiplier: Double? = null,
    /** The most one wait may come to, in seconds; null is the engine's own hour. */
    val retryMaxWaitSeconds: Int? = null,
    /** The fraction of a wait that may be taken off it at random; null or 0 is none. */
    val retryJitter: Double? = null,
    /** The longest this node may go on being attempted for, in seconds; null is no limit. */
    val retryBudgetSeconds: Int? = null,
    val x: Double,
    val y: Double,
)

/** One parameter: the value it holds, or the field it reads. */
data class NodeMappingInput(
    val name: String,
    val expression: String,
    val mode: MappingMode = MappingMode.VALUE,
    val sourceNodeKey: String? = null,
    /**
     * What this field holds, where the node is the one naming it.
     *
     * Absent everywhere else, and absent is untyped: a parameter belonging to a
     * function or a condition is typed by the definition, and a client saying
     * otherwise would be a client overriding it. See [NodeMapping.fieldKind].
     */
    val fieldKind: PropertyKind? = null,
    val fieldElementKind: PropertyKind? = null,
    val fieldRefObjectId: Long? = null,
)

data class WorkflowEdgeInput(
    val source: String,
    val target: String,
    /** Which way out of a condition it leaves by; absent for every other edge. */
    val branch: EdgeBranch? = null,
)

data class WorkflowGraphInput(
    val nodes: List<WorkflowNodeInput>,
    val edges: List<WorkflowEdgeInput>,
)

data class WorkflowNodeView(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String?,
    /** The agent this node instances, when it is an agent node. */
    val agentId: Long?,
    val triggerId: Long?,
    val actionId: Long?,
    val conditionId: Long?,
    /** The saved shape an object node makes; null is a shape of its own. */
    val objectId: Long?,
    /** The shape an agent node's answer is held to; null is prose. */
    val outputObjectId: Long?,
    /** The object node this agent's answer is saved into, by key; null is neither asked nor done. */
    val outputNodeKey: String?,
    /** The image model an image node draws with; ignored on any other kind. */
    val imageModelId: Long?,
    /** What an image node asks of the drawing beyond the prompt; null is the model's default. */
    val imageSize: String?,
    val imageQuality: String?,
    val imageStyle: String?,
    val outputName: String?,
    val icon: String?,
    /** Which way round it faces on the canvas; null is left to right. */
    val orientation: NodeOrientation?,
    /** What a node's two ways out are called; null leaves it to the interface. */
    val yesLabel: String?,
    val noLabel: String?,
    /** Whether this node has a second way out for when it fails. */
    val fallbackEnabled: Boolean,
    /** How many times in all this node may be attempted; null is once. */
    val retryAttempts: Int?,
    val retryBackoffSeconds: Int?,
    /** What the wait is multiplied by after each attempt; null is a wait that does not grow. */
    val retryMultiplier: Double?,
    /** The most one wait may come to; null is the engine's own hour. */
    val retryMaxWaitSeconds: Int?,
    /** The fraction of a wait taken off it at random; null is none. */
    val retryJitter: Double?,
    /** The longest this node may go on being attempted for; null is no limit. */
    val retryBudgetSeconds: Int?,
    val x: Double,
    val y: Double,
    /** What the node needs, read off whatever it points at. */
    val inputs: List<ActionParamView> = emptyList(),
    /** What it hands on. */
    val outputs: List<ActionParamView> = emptyList(),
    /** What this node passes to its action, one entry per parameter it has. */
    val mappings: List<NodeMappingView> = emptyList(),
) {
    constructor(
        node: WorkflowNode,
        inputs: List<ActionParamView> = emptyList(),
        outputs: List<ActionParamView> = emptyList(),
    ) : this(
        key = node.nodeKey,
        kind = node.kind,
        name = node.name,
        description = node.description,
        agentId = node.agentId,
        triggerId = node.triggerId,
        actionId = node.actionId,
        conditionId = node.conditionId,
        objectId = node.objectId,
        outputObjectId = node.outputObjectId,
        outputNodeKey = node.outputNodeKey,
        imageModelId = node.imageModelId,
        imageSize = node.imageSize,
        imageQuality = node.imageQuality,
        imageStyle = node.imageStyle,
        outputName = node.outputName,
        icon = node.icon,
        orientation = node.orientation,
        yesLabel = node.yesLabel,
        noLabel = node.noLabel,
        fallbackEnabled = node.fallbackEnabled,
        retryAttempts = node.retryAttempts,
        retryBackoffSeconds = node.retryBackoffSeconds,
        retryMultiplier = node.retryMultiplier,
        retryMaxWaitSeconds = node.retryMaxWaitSeconds,
        retryJitter = node.retryJitter,
        retryBudgetSeconds = node.retryBudgetSeconds,
        x = node.positionX,
        y = node.positionY,
        inputs = inputs,
        outputs = outputs,
        mappings = node.mappings.map {
            NodeMappingView(
                it.name,
                it.expression,
                it.mode,
                it.sourceNodeKey,
                it.fieldKind,
                it.fieldElementKind,
                it.fieldRefObjectId,
            )
        },
    )
}

data class NodeMappingView(
    val name: String,
    val expression: String,
    val mode: MappingMode = MappingMode.VALUE,
    val sourceNodeKey: String? = null,
    /** What the field holds, where the node names its own; see [NodeMapping.fieldKind]. */
    val fieldKind: PropertyKind? = null,
    val fieldElementKind: PropertyKind? = null,
    val fieldRefObjectId: Long? = null,
)

data class WorkflowEdgeView(
    val source: String,
    val target: String,
    val branch: EdgeBranch? = null,
) {
    constructor(edge: WorkflowEdge) : this(
        source = edge.sourceKey,
        target = edge.targetKey,
        branch = edge.branch,
    )
}

/**
 * One publication of a workflow: a version of it, and possibly the live one.
 *
 * Without the graph. A list of these is a history somebody scrolls, and every
 * row carrying a whole serialised graph would be a page measured in megabytes;
 * `workflowPublicationGraph` fetches one when a row is opened.
 */
data class WorkflowPublicationView(
    val id: Long,
    val workflowId: Long,
    val publishedAt: String,
    val publishedBy: String,
    /** The one the runner reads. Exactly one publication of each workflow is. */
    val current: Boolean,
    /** The publication this one copied, when it was made by restoring one. */
    val restoredFrom: Long?,
) {
    constructor(publication: WorkflowPublication, current: Boolean) : this(
        id = requireNotNull(publication.id),
        workflowId = publication.workflowId,
        publishedAt = publication.publishedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        publishedBy = publication.publishedBy,
        current = current,
        restoredFrom = publication.restoredFrom,
    )
}

class WorkflowPublicationNotFoundException(val id: Long) :
    RuntimeException("No publication with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

data class WorkflowGraphView(
    val workflowId: Long,
    val name: String,
    val description: String?,
    val status: WorkflowStatus,
    /** Whether the workspace has it switched on; see `enabledIn`. */
    val enabled: Boolean = true,
    /**
     * The assignment that `enabled` is a property of, so the editor can change
     * it.
     *
     * `setWorkflowEnabled` takes the assignment rather than the workflow - a
     * definition may be assigned to several workspaces and each switches it on
     * or off for itself - and the editor knew the workflow and not which
     * assignment it was looking at. Null where this workspace has none, which
     * is the state a graph read through an unassigned workflow is already in.
     * Issue #330.
     */
    val assignmentId: Long? = null,
    val nodes: List<WorkflowNodeView>,
    val edges: List<WorkflowEdgeView>,
    /** Everything the graph is missing, worst first; empty when it holds together. */
    val problems: List<GraphProblem> = emptyList(),
)

class WorkflowGraphEmptyException : RuntimeException("Add at least one node before publishing")

/**
 * What a name has to look like to be referred to: a letter or underscore, then
 * letters, digits and underscores. A field picked from a list has to be one
 * name, and this is what makes it one.
 */
private val REFERENCEABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Braces in a value: the substitution somebody still expects and will not get. */
private val PLACEHOLDER_IN_VALUE = Regex("""\{\{[^}]*\}\}""")

/**
 * What a retry policy may be set to.
 *
 * Bounded here rather than refused, because neither end is a mistake worth
 * arguing with somebody over: nought attempts is a typo for one, and a hundred
 * is somebody who has not thought about what a run costs. The ceiling on the
 * backoff is an hour, which is longer than any single wait a workflow has
 * needed and short enough that a run cannot disappear for a day over a typo.
 * A growing curve is bounded by the same hour where it is spent rather than
 * here, because what this holds is the first wait and not what it grows into.
 * The multiplier stops at ten for the same reason the attempts do: past that it
 * is not a curve anybody chose, it is a typo with a long tail. The budget stops
 * at a day, which is longer than any run worth waiting for and short enough that
 * a spare nought cannot park a step for a fortnight.
 */
/**
 * How much of a workflow's history a screen asks for, and the most it may.
 *
 * A page shows a handful and a workflow published every morning for a year has
 * three hundred and sixty five of them; the tail is what anybody reads, so the
 * list is limited rather than paged.
 */
private const val DEFAULT_PUBLICATIONS = 25
private const val MOST_PUBLICATIONS = 200

private const val MIN_ATTEMPTS = 1
private const val MAX_ATTEMPTS = 10
private const val MAX_BACKOFF_SECONDS = 3600
private const val MIN_MULTIPLIER = 1.0
private const val MAX_MULTIPLIER = 10.0
private const val NO_JITTER = 0.0
private const val FULL_JITTER = 1.0
private const val MAX_BUDGET_SECONDS = 86_400

class ValueHoldsPlaceholderException(parameter: String) : RuntimeException(
    "\"$parameter\" is a value holding {{...}}, which is sent as those characters. " +
        "Switch it to a reference and pick the field instead.",
)

class OutputNameInvalidException(name: String) : RuntimeException(
    "\"$name\" cannot be referred to. An output name is letters, digits and underscores, " +
        "starting with a letter — a later node has to be able to point at it",
)

class AgentOutputNodeInvalidException(agent: String, targetKey: String, target: WorkflowNode?) : RuntimeException(
    when {
        target == null ->
            "$agent saves its answer into a node that is not on this graph any more. " +
                "Pick where the answer goes again."
        target.kind != NodeKind.OBJECT ->
            "$agent saves its answer into ${target.name}, which is not an object node. " +
                "An answer is saved into an object node, whose shape it is then held to."
        else ->
            "$agent saves its answer into ${target.name}, which has no saved shape. " +
                "Point that node at one of the workspace's objects first - the answer is held to it."
    },
)

class TriggerNotInCatalogueException(val id: Long) :
    RuntimeException("Trigger $id is not in this workspace's catalogue"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ActionNotInCatalogueException(val id: Long) :
    RuntimeException("Action $id is not in this workspace's catalogue"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ObjectNotInCatalogueException(val id: Long) :
    RuntimeException("Object $id is not in this workspace's catalogue"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class AgentNotInCatalogueException(id: Long) :
    RuntimeException("No agent with id $id in this workspace")

class ConditionNotInCatalogueException(val id: Long) :
    RuntimeException("Condition $id is not in this workspace's catalogue"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

