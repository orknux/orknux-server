package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.obj.PropertyKind
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

enum class WorkflowStatus {
    DRAFT,
    PUBLISHED,
}

/**
 * Which side of a node its input and output sit on.
 *
 * Named for where the work goes rather than for an angle: "left to right" is
 * what somebody means, and a number of degrees would have to be translated
 * back into that at every reading.
 */
enum class NodeOrientation {
    LEFT_TO_RIGHT,
    TOP_TO_BOTTOM,
    RIGHT_TO_LEFT,
    BOTTOM_TO_TOP,
}

enum class NodeKind {
    TRIGGER,
    AGENT,

    /** An instance of one of the workspace's actions. */
    ACTION,

    /** Asks one of the workspace's conditions; the run stops when it does not hold. */
    CONDITION,

    /** Makes an object out of what the run is carrying, and hands it on. */
    OBJECT,

    /** Draws a picture from a prompt, with one of the workspace's image models. */
    IMAGE,

    /**
     * Names an LLM session, for the agent nodes wired to it to talk into.
     *
     * A declaration rather than a step: nothing runs it, and it produces
     * nothing a later node could read. It holds the two parameters a session is
     * identified by - `sessionKey`, and the optional `sessionKeyPrefix` it is
     * filed under - and every agent node an edge leads from it to writes into
     * that one conversation. Two agents sharing a session is two edges from one
     * of these, rather than the same key typed into both.
     *
     * Its parameters are resolved where they are used, in the agent, so a key
     * read off what the run is carrying still reads what that agent was handed.
     */
    SESSION,

    /**
     * Asks a decision model typed questions about what the run carries - Jev,
     * or a self-hosted Laya - and hands on the answers with their probabilities.
     * Its choice question may branch the graph, one edge per option, with an
     * edge of its own for an answer too unsure to take. Issue #577.
     */
    DECISION,
}

/**
 * One parameter of a node, and where its value comes from.
 *
 * Either the value itself, used exactly as written, or the name of a field the
 * run is carrying, read when the step runs. Which of the two it is, is the
 * mode — not something guessed from how the text looks.
 */
@Embeddable
class NodeMapping(
    @Column(name = "name", nullable = false, length = 64)
    var name: String = "",

    /**
     * The value itself, or the field it is read from.
     *
     * Which one depends on [mode]. A written value is used as it stands; a
     * reference names a field the run is carrying — `reply`, `message.channel` —
     * and is read when the step runs.
     */
    @Column(name = "expression", nullable = false, columnDefinition = "text")
    var expression: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    var mode: MappingMode = MappingMode.VALUE,

    /**
     * Which node produces the referenced field, on a reference.
     *
     * Not used to read the value — the run carries everything under one set of
     * names, so the field name is enough — but it is what lets the canvas draw a
     * line from the node that made it, and what makes a reference to a node that
     * has been deleted something we can point at rather than a name that quietly
     * resolves to nothing.
     */
    @Column(name = "source_node_key", length = 64)
    var sourceNodeKey: String? = null,

    /**
     * What this field holds, where the node is the one naming it.
     *
     * An Object node with no saved shape carries fields of its own, and those
     * had a name and a value and nothing else: a number written into one
     * arrived downstream as the string `"3"`. This is the same question a saved
     * object's property answers, asked in the other place somebody names a
     * field - so it is answered in the same three parts and the same words.
     *
     * Null is untyped, and untyped is what every other mapping is: an action's
     * parameter is typed by the function it belongs to, a condition's by its
     * own. Nothing here overrides a definition; there is only ever something
     * here where nothing else was going to say.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "field_kind", length = 16)
    var fieldKind: PropertyKind? = null,

    /** What a list holds, where [fieldKind] is `ARRAY` and it holds scalars. */
    @Enumerated(EnumType.STRING)
    @Column(name = "field_element_kind", length = 16)
    var fieldElementKind: PropertyKind? = null,

    /** The shape it points at, where it is one of those or a list of them. */
    @Column(name = "field_ref_object_id")
    var fieldRefObjectId: Long? = null,
) {

    /** A separate one saying the same thing, for a node being copied. */
    fun copy() = NodeMapping(
        name = name,
        expression = expression,
        mode = mode,
        sourceNodeKey = sourceNodeKey,
        fieldKind = fieldKind,
        fieldElementKind = fieldElementKind,
        fieldRefObjectId = fieldRefObjectId,
    )
}

/** Whether a parameter holds something written or something read. */
enum class MappingMode {
    VALUE,
    REFERENCE,
}

@Entity
@Table(name = "workflow_node")
class WorkflowNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    /** Stable within a workflow; what edges refer to. */
    @Column(name = "node_key", nullable = false, length = 64)
    val nodeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: NodeKind,

    @Column(nullable = false)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    /**
     * The agent an agent node instances; ignored on any other kind.
     *
     * The agent supplies the model, the instructions and the catalogs it was
     * granted, which is why nothing else about it is stored here.
     */
    @Column(name = "agent_id")
    var agentId: Long? = null,

    /**
     * The trigger definition this node is an instance of; only a
     * [NodeKind.TRIGGER] node has one, and it is what wires an arriving event to
     * this workflow. Null while the node names no trigger yet.
     */
    @Column(name = "trigger_id")
    var triggerId: Long? = null,

    /**
     * What this node calls what it produces, so a later node can ask for it by
     * name.
     *
     * An agent answers with prose, which has no fields to address. Naming the
     * answer is what a later node points a reference at: the step's output
     * becomes an object with this one key. Null means the answer is
     * handed on as it always was, unwrapped, which is what a node drawn before
     * this existed still does.
     */
    @Column(name = "output_name", length = 60)
    var outputName: String? = null,

    /**
     * Which way round the node faces on the canvas.
     *
     * Layout rather than meaning: it moves where the handles sit and nothing
     * else, so a graph can run down a screen instead of off the side of it.
     * Null is the way it always was, left to right.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var orientation: NodeOrientation? = null,

    /**
     * Which icon the canvas draws on this node.
     *
     * A name from the interface's own set rather than a file or a URL: a graph
     * is read at a glance, and a node that draws whatever someone pasted is a
     * node that can draw nothing, or something enormous. Null keeps the plain
     * node the kind already gives.
     */
    @Column(name = "icon", length = 40)
    var icon: String? = null,

    /**
     * What this node passes to the thing it points at, one entry per parameter.
     *
     * Seeded from the catalogue entry when the node first points at one, and
     * authoritative afterwards: nothing downstream reads the action's own
     * mappings, so editing a node cannot change a definition that other nodes
     * are using.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_node_mapping", joinColumns = [JoinColumn(name = "workflow_node_id")])
    @OrderColumn(name = "position")
    var mappings: MutableList<NodeMapping> = mutableListOf(),

    /**
     * The action this node is an instance of; only a [NodeKind.ACTION] node has
     * one, and it is what the node does when the run reaches it.
     */
    @Column(name = "action_id")
    var actionId: Long? = null,

    /** The condition a [NodeKind.CONDITION] node asks of the run it is in. */
    @Column(name = "condition_id")
    var conditionId: Long? = null,

    /**
     * The image model an [NodeKind.IMAGE] node draws with; only that kind has
     * one. Picked on the node itself, like an agent node picks an agent, rather
     * than taken from a workspace default. Issue #333.
     */
    @Column(name = "image_model_id")
    var imageModelId: Long? = null,

    /**
     * What an [NodeKind.IMAGE] node asks of the drawing beyond the prompt: the
     * size in pixels as `WIDTHxHEIGHT`, the quality and the style, each as the
     * word the image endpoint takes and each held to the list the editor
     * offers (see `ImageNodeParameters`). Null is the model's own default,
     * which is what every image node drawn before these existed asked for and
     * goes on asking for. Only that kind has them. Issue #423.
     */
    @Column(name = "image_size", length = 16)
    var imageSize: String? = null,

    @Column(name = "image_quality", length = 16)
    var imageQuality: String? = null,

    @Column(name = "image_style", length = 16)
    var imageStyle: String? = null,

    /**
     * The decision model a [NodeKind.DECISION] node asks; only that kind has
     * one. Picked on the node, the way an image node picks its model, rather
     * than taken from a workspace default. Issue #577.
     */
    @Column(name = "decision_model_id")
    var decisionModelId: Long? = null,

    /**
     * What a [NodeKind.DECISION] node asks: its questions, which one branches,
     * and the threshold an answer has to clear - see [DecisionSpec]. Null asks
     * nothing. Only that kind has one.
     */
    @Column(name = "decision_spec", columnDefinition = "text")
    var decisionSpec: String? = null,

    /**
     * The shape an object node makes, when it uses one the workspace has saved.
     *
     * Null is a shape of the node's own: its fields are whatever it holds. A
     * saved one fixes the field names, the way an action fixes its parameters,
     * and the node only decides what goes in them.
     */
    @Column(name = "object_id")
    var objectId: Long? = null,

    /**
     * The shape an agent node's answer is held to; only that kind has one.
     *
     * Null is prose, which is what every agent node answered until now. A
     * chosen object makes the answer a JSON document matching that shape -
     * asked of the model, checked by the runner, and retried under the node's
     * own policy where the answer did not comply - so downstream nodes can
     * address its fields the way they address any object's.
     *
     * Its own column rather than [objectId], which means "the shape an object
     * node makes" and seeds that kind's mapping rows.
     */
    @Column(name = "output_object_id")
    var outputObjectId: Long? = null,

    /**
     * The object node on this graph an agent node's answer is saved into; only
     * an agent has one, and it names another node by its key.
     *
     * The other way to shape an answer: instead of picking a shape inline, the
     * agent points at an object node already on the canvas, its answer is held
     * to that node's shape, and the node's unmapped fields are filled from it -
     * so what stands downstream is the object node, not a fan of dotted paths.
     * The editor draws the pointing as a dependency line, not a flow edge: the
     * object node still receives its values through the run like any other
     * reference. When this is set, [outputObjectId] is derived from the target
     * at every save rather than chosen; the target losing its shape is refused.
     */
    @Column(name = "output_node_key", length = 64)
    var outputNodeKey: String? = null,

    @Column(name = "position_x", nullable = false)
    var positionX: Double,

    @Column(name = "position_y", nullable = false)
    var positionY: Double,

    /**
     * What this node's two ways out are called.
     *
     * Null means the default, which the interface supplies: "Yes" and "No" for
     * a condition, and "If works" and "If fails" for an action that handles its
     * own failure. A question like "is it urgent" reads better as "Escalate"
     * and "File it", and those words are most of what makes a graph legible at
     * a glance, so they belong to the node rather than to the edges.
     *
     * One pair for both, rather than a second pair for actions, because there
     * is only ever one question a node answers: which of my two exits did this
     * run leave by.
     */
    @Column(name = "yes_label", length = 40)
    var yesLabel: String? = null,

    @Column(name = "no_label", length = 40)
    var noLabel: String? = null,

    /**
     * Whether this node has a second way out for the case where it fails.
     *
     * Kept on the node rather than inferred from an edge carrying
     * [EdgeBranch.FAILURE], because the handle has to be there for somebody to
     * draw from before any such edge exists — and because turning it off should
     * be a decision recorded on the node, not a graph that quietly loses its
     * fallback when the last edge is deleted.
     */
    @Column(name = "fallback_enabled", nullable = false)
    var fallbackEnabled: Boolean = false,

    /**
     * How many times in all a run may attempt this node; null is once.
     *
     * Attempts rather than retries, so the number on the node is the number of
     * times the work is performed at worst. A failure the runner has already
     * called final is never one of them: nothing about a channel that does not
     * exist, or a request the model refused for what it said, changes between
     * one attempt and the next.
     */
    @Column(name = "retry_attempts")
    var retryAttempts: Int? = null,

    /** The wait before the second attempt, in seconds; null is none. */
    @Column(name = "retry_backoff_seconds")
    var retryBackoffSeconds: Int? = null,

    /**
     * What that wait is multiplied by after each attempt; null is one.
     *
     * One is the fixed wait, two is the doubling that a boolean used to say, and
     * the numbers between are the curves it could not. Null rather than 1.0 so
     * every node saved before this existed goes on meaning what it meant, and so
     * a node the panel only looked at comes back off it unedited.
     */
    @Column(name = "retry_multiplier")
    var retryMultiplier: Double? = null,

    /**
     * The most any one of this node's waits may come to, in seconds.
     *
     * The reason a multiplier is safe to offer: six attempts at three times the
     * last is nearly an hour of run, and neither "6" nor "3" looks like an hour.
     * Null is the engine's own hour, which no wait passes however this is set.
     */
    @Column(name = "retry_max_wait_seconds")
    var retryMaxWaitSeconds: Int? = null,

    /**
     * The fraction of a wait that may be taken off it at random; null is none.
     *
     * Downward only, so every other number here stays the upper bound it reads
     * as. What it is for is the hundred runs that failed on one outage and would
     * otherwise all come back at the same instant, which is how a service that
     * is struggling gets held down.
     */
    @Column(name = "retry_jitter")
    var retryJitter: Double? = null,

    /**
     * The longest this node may go on being attempted for, in seconds, work
     * included; null is no limit beyond the attempts.
     *
     * The bound the waits cannot express between them: what happens between two
     * waits is a call to something outside this installation, and how long that
     * takes is not in any of the other numbers.
     */
    @Column(name = "retry_budget_seconds")
    var retryBudgetSeconds: Int? = null,

    /**
     * Whether a run does this node's work.
     *
     * Off, the node stays on the graph with every edge it had and the run
     * walks straight through it: the step is recorded as skipped and what
     * reached it is handed on unchanged. For switching one piece off while
     * the rest is tried - a Slack post nobody wants sent forty times while the
     * agent before it is being tuned - without deleting the node and redrawing
     * its lines afterwards.
     *
     * Kept on the node rather than inferred from anything else, because there
     * is nothing else that says it: a disabled node is otherwise exactly the
     * node it was. A trigger cannot be disabled - a run has to start somewhere,
     * and a trigger that does not fire is the trigger's own switch. Issue #439.
     */
    @Column(nullable = false)
    var enabled: Boolean = true,
) {

    /**
     * The same node, drawn in another workflow.
     *
     * Here rather than beside the duplicating so that a column added above is
     * one edit and not two: a copy that quietly stopped carrying a field would
     * look like a duplicate that worked, and be a workflow that did something
     * else. The key is kept, because it is only ever unique within a workflow
     * and the edges being copied name it.
     */
    fun copyInto(workflowId: Long) = WorkflowNode(
        workflowId = workflowId,
        nodeKey = nodeKey,
        kind = kind,
        name = name,
        description = description,
        agentId = agentId,
        triggerId = triggerId,
        outputName = outputName,
        orientation = orientation,
        icon = icon,
        mappings = mappings.map { it.copy() }.toMutableList(),
        actionId = actionId,
        conditionId = conditionId,
        objectId = objectId,
        outputObjectId = outputObjectId,
        outputNodeKey = outputNodeKey,
        imageModelId = imageModelId,
        imageSize = imageSize,
        imageQuality = imageQuality,
        imageStyle = imageStyle,
        decisionModelId = decisionModelId,
        decisionSpec = decisionSpec,
        positionX = positionX,
        positionY = positionY,
        yesLabel = yesLabel,
        noLabel = noLabel,
        fallbackEnabled = fallbackEnabled,
        retryAttempts = retryAttempts,
        retryBackoffSeconds = retryBackoffSeconds,
        retryMultiplier = retryMultiplier,
        retryMaxWaitSeconds = retryMaxWaitSeconds,
        retryJitter = retryJitter,
        retryBudgetSeconds = retryBudgetSeconds,
        enabled = enabled,
    )
}

@Entity
@Table(name = "workflow_edge")
class WorkflowEdge(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "source_key", nullable = false, length = 64)
    val sourceKey: String,

    @Column(name = "target_key", nullable = false, length = 64)
    val targetKey: String,

    /**
     * Which way out of its node this edge leaves by, or null for an edge that
     * is not answering anything.
     *
     * Null is what every edge between two ordinary nodes is, and what every
     * edge was before branches existed - so a graph drawn last week means
     * exactly what it meant then.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    val branch: EdgeBranch? = null,

    /**
     * Which option an [EdgeBranch.OPTION] edge leaves by: one of the names the
     * decision node's branching question offers. Null on every other edge.
     * Issue #577.
     */
    @Column(name = "branch_option", length = 64)
    val branchOption: String? = null,
) {

    /** The same edge, drawn in another workflow. See [WorkflowNode.copyInto]. */
    fun copyInto(workflowId: Long) = WorkflowEdge(
        workflowId = workflowId,
        sourceKey = sourceKey,
        targetKey = targetKey,
        branch = branch,
        branchOption = branchOption,
    )
}

/** The ways out of a node, as the edges leaving it are labelled. */
enum class EdgeBranch {
    /** A condition's two answers. */
    YES,
    NO,

    /**
     * The exit an action takes when it could not do its work.
     *
     * Only the exception is marked. An action's happy path stays the unmarked
     * edge it has always been, so enabling a fallback adds an edge instead of
     * rewriting the one already drawn, and a graph saved without this is
     * untouched by it.
     */
    FAILURE,

    /**
     * One option of a decision node's choice, named by [WorkflowEdge.branchOption].
     * The options are the node's to name, so the branch says only that the
     * edge is one of them. Issue #577.
     */
    OPTION,

    /** The way out of a decision node whose answer did not clear its threshold. */
    UNSURE,
}

interface WorkflowNodeRepository : JpaRepository<WorkflowNode, Long> {
    fun findByWorkflowId(workflowId: Long): List<WorkflowNode>
    fun deleteByWorkflowId(workflowId: Long)

    /** What an arriving event asks: which workflows instance this trigger? */
    fun findByTriggerId(triggerId: Long): List<WorkflowNode>

    fun findByActionId(actionId: Long): List<WorkflowNode>

    fun findByConditionId(conditionId: Long): List<WorkflowNode>

    /** Which nodes run this agent, so it cannot be deleted from under them. */
    fun findByAgentId(agentId: Long): List<WorkflowNode>
}

interface WorkflowEdgeRepository : JpaRepository<WorkflowEdge, Long> {
    fun findByWorkflowId(workflowId: Long): List<WorkflowEdge>
    fun deleteByWorkflowId(workflowId: Long)
}
