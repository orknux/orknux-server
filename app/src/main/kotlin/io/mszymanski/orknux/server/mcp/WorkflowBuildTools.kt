package io.mszymanski.orknux.server.mcp

import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLType
import graphql.schema.idl.SchemaPrinter
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.action.ActionAPI
import io.mszymanski.orknux.server.action.CreateActionInput
import io.mszymanski.orknux.server.condition.ConditionAPI
import io.mszymanski.orknux.server.condition.CreateConditionInput
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.trigger.CreateTriggerInput
import io.mszymanski.orknux.server.trigger.TriggerAPI
import io.mszymanski.orknux.server.workflow.CreateWorkflowInput
import io.mszymanski.orknux.server.workflow.WorkflowAPI
import io.mszymanski.orknux.server.workflow.WorkflowGraphAPI
import io.mszymanski.orknux.server.workflow.WorkflowGraphInput
import io.mszymanski.orknux.server.workflow.WorkflowGraphView
import io.mszymanski.orknux.server.workflow.WorkflowStatus
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import kotlin.math.abs
import org.springframework.context.annotation.Lazy
import org.springframework.core.ResolvableType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.GraphQlArgumentBinder
import org.springframework.graphql.execution.GraphQlSource
import org.springframework.stereotype.Service
import org.springframework.validation.BindException
import tools.jackson.databind.ObjectMapper

/**
 * The orknux_* tools that build a workflow: read one, see what its nodes can
 * point at, create the triggers, actions and conditions it needs, save its
 * graph and publish it.
 *
 * Every write goes through the same controller the editor calls, so a graph an
 * agent saves is checked by the same validator, audited in the same words and
 * versioned by the same recorder - a second way to save a graph would be a
 * second set of rules about what a graph may be. Arguments are bound by Spring
 * GraphQL's own binder into the same input classes, which is what keeps a
 * field the editor can send and a field a model can send from ever differing.
 *
 * And what those fields mean is read from the schema at the moment of asking
 * (`orknux_workflow_reference`), rather than written out again here.
 */
@Service
class WorkflowBuildTools(
    private val assignments: WorkspaceWorkflowRepository,
    // Lazy for the reason OrknuxTools' engine is: these reach the execution
    // service, which reaches the agent runner, which reaches the orknux_* tools.
    @param:Lazy private val workflowApi: WorkflowAPI,
    @param:Lazy private val graphApi: WorkflowGraphAPI,
    @param:Lazy private val triggerApi: TriggerAPI,
    @param:Lazy private val actionApi: ActionAPI,
    @param:Lazy private val conditionApi: ConditionAPI,
    @param:Lazy private val graphQl: GraphQlSource,
    private val web: WebProperties,
    private val address: io.mszymanski.orknux.server.security.InstallationAddress,
    private val mapper: ObjectMapper,
) {

    private val binder = GraphQlArgumentBinder()

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = "orknux_workflow",
                description =
                    "Reads one workflow's graph as it is drawn now: every node with its kind, what it points " +
                        "at, what it passes and what it needs and gives, every edge, and the problems the " +
                        "editor would show. Read a working workflow to see how one is built.",
                parameters = listOf(ToolParameterSpec("workflow", "The workflow's name or id.", required = true)),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_workflow_parts",
                description =
                    "Lists what a workflow's nodes can point at: the workspace's triggers, actions (with the " +
                        "inputs each needs and the outputs it gives) and conditions, by id. A TRIGGER node names " +
                        "a trigger, an ACTION node an action, a CONDITION node a condition; an AGENT node names " +
                        "an agent from orknux_agents.",
                parameters = emptyList(),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_workflow_reference",
                description =
                    "Explains every field a workflow is built from, read from the live schema: the node, edge " +
                        "and mapping fields and the node kinds (part graph), or what creating a trigger, action " +
                        "or condition takes (part trigger, action or condition). Read it before building.",
                parameters = listOf(
                    ToolParameterSpec(
                        "part",
                        "graph, trigger, action or condition; graph when left out.",
                        required = false,
                    ),
                ),
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = "orknux_create_workflow",
                description =
                    "Creates an empty workflow in this workspace and answers its id. Then create the triggers, " +
                        "actions and conditions it needs, save its graph with orknux_save_workflow_graph, and " +
                        "publish it - nothing a trigger starts runs a workflow that was never published.",
                parameters = listOf(
                    ToolParameterSpec("name", "Its name, unique in the workspace.", required = true),
                    ToolParameterSpec("description", "What it is for, in a sentence.", required = false),
                ),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_save_workflow_graph",
                description =
                    "Saves a workflow's whole graph, replacing what is drawn: the nodes and the edges between " +
                        "them, as orknux_workflow_reference part graph describes them. Leave x and y out: the " +
                        "graph is laid out left to right, and given positions that overlap are laid out anyway. A graph that could never run is refused with " +
                        "the reason; anything else is saved and answered with the problems still in it, which " +
                        "you should fix and save again. Saving puts a published workflow back into draft until " +
                        "it is published again.",
                parameters = listOf(
                    ToolParameterSpec("workflow", "The workflow's name or id.", required = true),
                    ToolParameterSpec(
                        "nodes",
                        "The nodes: objects with key, kind and name, plus the fields their kind takes.",
                        required = true,
                        schema = mapOf("type" to "array", "items" to mapOf("type" to "object")),
                    ),
                    ToolParameterSpec(
                        "edges",
                        "The edges: objects with source and target node keys, and branch where it leaves by one.",
                        required = true,
                        schema = mapOf("type" to "array", "items" to mapOf("type" to "object")),
                    ),
                    ToolParameterSpec(
                        "overwrite_draft",
                        "True to replace a draft that holds changes nobody has published. Only for a workflow " +
                            "you are building yourself: that draft may be somebody's unfinished work, and it " +
                            "has no history to get it back from.",
                        required = false,
                        schema = mapOf("type" to "boolean"),
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                name = "orknux_publish_workflow",
                description =
                    "Publishes a workflow's graph as it is drawn now, which is what its triggers, its schedule " +
                        "and the API run from then on. Refused while the graph has a problem that stops it running.",
                parameters = listOf(ToolParameterSpec("workflow", "The workflow's name or id.", required = true)),
            ),
        )
        for ((part, what) in listOf(
            "trigger" to "a trigger - what starts a workflow: a schedule, a webhook, a message on a connection",
            "action" to "an action - a step a workflow's ACTION node runs: a function, an HTTP call, a message",
            "condition" to "a condition - a question a CONDITION node asks, which picks its YES or NO edge",
        )) {
            add(
                ToolSpec(
                    name = "orknux_create_$part",
                    description =
                        "Creates $what - and answers its id, for a node to point at.",
                    parameters = listOf(
                        ToolParameterSpec(
                            "fields",
                            "Its fields, as orknux_workflow_reference part $part lists them; workspaceId is " +
                                "filled in.",
                            required = true,
                            // Whatever the reference lists: the binder is what checks them.
                            schema = mapOf("type" to "object"),
                        ),
                    ),
                ),
            )
        }
    }

    fun handles(name: String): Boolean = name in NAMES

    fun run(scope: OrknuxScope, name: String, arguments: String): String = when (name) {
        "orknux_workflow" -> withWorkflow(scope, arguments) { id -> graph(scope, graphApi.workflowGraph(scope.workspaceId, id)) }
        "orknux_workflow_parts" -> parts(scope)
        "orknux_workflow_reference" -> reference(text(arguments, "part") ?: "graph")
        else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
            "orknux_create_workflow" -> createWorkflow(scope, arguments)
            "orknux_save_workflow_graph" -> withWorkflow(scope, arguments) { id -> saveGraph(scope, id, arguments) }
            "orknux_publish_workflow" -> withWorkflow(scope, arguments) { id ->
                graph(scope, graphApi.publishWorkflow(scope.workspaceId, id))
            }
            "orknux_create_trigger" -> created(scope, "trigger", arguments) {
                triggerApi.createTrigger(bind(it, CreateTriggerInput::class.java)).let { made -> made.id to made.name }
            }
            "orknux_create_action" -> created(scope, "action", arguments) {
                actionApi.createAction(bind(it, CreateActionInput::class.java)).let { made -> made.id to made.name }
            }
            "orknux_create_condition" -> created(scope, "condition", arguments) {
                conditionApi.createCondition(bind(it, CreateConditionInput::class.java)).let { made -> made.id to made.name }
            }
            else -> refuse("There is no tool called $name")
        }
    }

    private fun createWorkflow(scope: OrknuxScope, arguments: String): String {
        val name = text(arguments, "name") ?: return refuse("What is it called? Give it a name.")
        val made = workflowApi.createWorkflow(
            CreateWorkflowInput(workspaceId = scope.workspaceId, name = name, description = text(arguments, "description")),
        )
        return mapper.writeValueAsString(
            mapOf("id" to made.workflowId, "name" to made.name, "url" to workflowLink(scope, made.workflowId)),
        )
    }

    private fun saveGraph(scope: OrknuxScope, workflowId: Long, arguments: String): String {
        val asked = mapper.readValue(arguments, Map::class.java)
        val drawn = graphApi.workflowGraph(scope.workspaceId, workflowId)
        val overwrite = asked["overwrite_draft"].let { it == true || it?.toString()?.equals("true", ignoreCase = true) == true }
        if (drawn.status == WorkflowStatus.DRAFT && drawn.nodes.isNotEmpty() && !overwrite) {
            return refuse(
                "${drawn.name} has a draft with changes nobody has published. Read it with orknux_workflow " +
                    "first; if it is a workflow you are building, save again with overwrite_draft true.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        val nodes = (asked["nodes"] as? List<Map<String, Any?>>) ?: return refuse("Give the graph's nodes, as a list.")
        val edges = (asked["edges"] as? List<Map<String, Any?>>) ?: emptyList()
        val input = bind(mapOf("nodes" to laidOut(nodes, edges), "edges" to edges), WorkflowGraphInput::class.java)
        return graph(scope, graphApi.saveWorkflowGraph(scope.workspaceId, workflowId, input))
    }

    /**
     * Positions for the nodes, so the editor opens on a drawing rather than a
     * pile: a column per step from the start, a row per node in its column.
     *
     * Positions a model gave are kept only while no two of them collide. A
     * model places nodes as if they were points, a node is 260 pixels of card,
     * and a graph saved with its nodes touching came back as cards on top of
     * each other with the edges hidden underneath - so one collision and the
     * whole graph is laid out, rather than one node moved into another's place.
     */
    private fun laidOut(nodes: List<Map<String, Any?>>, edges: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val placed = nodes.mapNotNull { node ->
            val x = (node["x"] as? Number)?.toDouble() ?: node["x"]?.toString()?.toDoubleOrNull()
            val y = (node["y"] as? Number)?.toDouble() ?: node["y"]?.toString()?.toDoubleOrNull()
            if (x == null || y == null) null else x to y
        }
        val collide = placed.indices.any { i ->
            (i + 1 until placed.size).any { j ->
                abs(placed[i].first - placed[j].first) < CARD_WIDTH && abs(placed[i].second - placed[j].second) < CARD_HEIGHT
            }
        }
        val asGiven = if (collide) nodes.map { it - "x" - "y" } else nodes
        return laidOutWhereMissing(asGiven, edges)
    }

    private fun laidOutWhereMissing(nodes: List<Map<String, Any?>>, edges: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val keys = nodes.mapNotNull { it["key"]?.toString() }
        val into = edges.groupBy({ it["target"]?.toString() }, { it["source"]?.toString() })
        val depth = mutableMapOf<String, Int>()
        fun depthOf(key: String, seen: Set<String>): Int = depth[key] ?: run {
            val before = into[key].orEmpty().filterNotNull().filter { it in keys && it !in seen }
            (if (before.isEmpty()) 0 else before.maxOf { depthOf(it, seen + key) } + 1).also { depth[key] = it }
        }
        val rows = mutableMapOf<Int, Int>()
        return nodes.map { node ->
            if (node["x"] != null && node["y"] != null) return@map node
            val column = node["key"]?.toString()?.let { depthOf(it, emptySet()) } ?: 0
            val row = rows.merge(column, 1, Int::plus)!! - 1
            node + mapOf("x" to (COLUMN * column).toDouble(), "y" to (ROW * row).toDouble())
        }
    }

    private fun parts(scope: OrknuxScope): String {
        val triggers = triggerApi.workspaceTriggers(scope.workspaceId, 0, MANY, null, null, null, source = null).content
        val actions = actionApi.workspaceActions(scope.workspaceId, 0, MANY, null, null, null).content
        val conditions = conditionApi.workspaceConditions(scope.workspaceId, 0, MANY, null, null, null).content
        return mapper.writeValueAsString(
            mapOf(
                "triggers" to triggers.map { compact(it, "id", "name", "type", "action", "cron", "webhookPath", "connectionName", "enabled") },
                "actions" to actions.map { action ->
                    compact(action, "id", "name", "type", "subtype", "functionName", "connectionName", "pluginKey", "pluginAction") +
                        mapOf(
                            "inputs" to action.inputParams.map { it.display },
                            "outputs" to action.outputParams.map { it.display },
                        )
                },
                "conditions" to conditions.map { compact(it, "id", "name", "type", "expression", "functionName") },
            ),
        )
    }

    /**
     * The fields a part takes, printed from the schema with their descriptions,
     * and every input and enum type they reach - so a model reads what the
     * editor's own API says rather than a summary of it that would drift.
     */
    private fun reference(part: String): String {
        val roots = when (part.trim().lowercase()) {
            "graph" -> listOf("NodeKind", "WorkflowNodeInput", "WorkflowEdgeInput")
            "trigger" -> listOf("CreateTriggerInput")
            "action" -> listOf("CreateActionInput")
            "condition" -> listOf("CreateConditionInput")
            else -> return refuse("Which part? One of graph, trigger, action or condition.")
        }
        val schema = graphQl.schema()
        val reached = linkedMapOf<String, GraphQLNamedType>()
        fun reach(type: GraphQLType?) {
            when (val bare = unwrapped(type)) {
                is GraphQLInputObjectType -> if (reached.putIfAbsent(bare.name, bare) == null) bare.fields.forEach { reach(it.type) }
                is GraphQLEnumType -> reached.putIfAbsent(bare.name, bare)
                else -> Unit
            }
        }
        roots.forEach { reach(schema.getType(it)) }
        val printer = SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeDirectives(false))
        val text = reached.values.joinToString("\n") { printer.print(it).trim() + "\n" }
        val note = if (part.trim().lowercase() == "graph") {
            "workspaceId is filled in for you. A node's key is how edges and later nodes name it. " +
                "An ACTION node passes its action's inputs through mappings; read a working workflow " +
                "with orknux_workflow to see the expressions they use."
        } else {
            "workspaceId is filled in for you; leave workflowId out so the workspace shares it."
        }
        return mapper.writeValueAsString(mapOf("part" to part, "note" to note, "schema" to text))
    }

    private fun unwrapped(type: GraphQLType?): GraphQLType? = when (type) {
        is GraphQLNonNull -> unwrapped(type.wrappedType)
        is GraphQLList -> unwrapped(type.wrappedType)
        else -> type
    }

    private fun created(scope: OrknuxScope, part: String, arguments: String, make: (Map<String, Any?>) -> Pair<Long, String>): String {
        @Suppress("UNCHECKED_CAST")
        val fields = (mapper.readValue(arguments, Map::class.java)["fields"] as? Map<String, Any?>)
            ?: return refuse("Give its fields, as orknux_workflow_reference part $part lists them.")
        val asked = fields + ("workspaceId" to scope.workspaceId)
        val (id, name) = make(asked)
        return mapper.writeValueAsString(mapOf(part to name, "id" to id))
    }

    private fun graph(scope: OrknuxScope, view: WorkflowGraphView): String = mapper.writeValueAsString(
        mapOf(
            "id" to view.workflowId,
            "name" to view.name,
            "status" to view.status.name,
            "enabled" to view.enabled,
            "url" to workflowLink(scope, view.workflowId),
            "nodes" to view.nodes.map { node ->
                @Suppress("UNCHECKED_CAST")
                (mapper.convertValue(node, Map::class.java) as Map<String, Any?>)
                    .filterValues { it != null && it != false && (it !is Collection<*> || it.isNotEmpty()) }
                    .mapValues { (key, value) -> if (key == "inputs" || key == "outputs") node.ports(key) else value }
            },
            "edges" to view.edges,
            "problems" to view.problems.map { mapOf("severity" to it.severity.name, "node" to it.nodeKey, "message" to it.message) },
        ),
    )

    private fun io.mszymanski.orknux.server.workflow.WorkflowNodeView.ports(which: String) =
        (if (which == "inputs") inputs else outputs).map { it.display }

    private fun <T> bind(raw: Map<String, Any?>, type: Class<T>): T = try {
        @Suppress("UNCHECKED_CAST")
        binder.bind(raw, false, ResolvableType.forClass(type)) as T
    } catch (refused: BindException) {
        throw IllegalArgumentException(
            refused.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }.ifEmpty { refused.message },
        )
    }

    private fun withWorkflow(scope: OrknuxScope, arguments: String, then: (Long) -> String): String {
        val asked = text(arguments, "workflow") ?: return refuse("Which workflow? Give its name or id.")
        val matches = assignments
            .findByWorkspaceId(scope.workspaceId, PageRequest.of(0, MANY, Sort.by("workflow.name")))
            .content
            .filter { it.workflow.name.equals(asked, ignoreCase = true) || it.workflow.id?.toString() == asked }
        return when {
            matches.isEmpty() -> refuse("There is no workflow called $asked here")
            matches.size > 1 -> refuse("More than one workflow is called $asked; use its id")
            else -> then(requireNotNull(matches.single().workflow.id))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compact(view: Any, vararg keep: String): Map<String, Any?> =
        (mapper.convertValue(view, Map::class.java) as Map<String, Any?>)
            .filterKeys { it in keep }
            .filterValues { it != null }

    private fun workflowLink(scope: OrknuxScope, id: Long?): String =
        address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() } + "/workspace/${scope.workspaceId}/workflows/$id/editor"

    private fun text(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).let { if (it.isNumber) it.asText() else it.stringValue() }?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    private companion object {
        val NAMES = setOf(
            "orknux_workflow", "orknux_workflow_parts", "orknux_workflow_reference",
            "orknux_create_workflow", "orknux_save_workflow_graph", "orknux_publish_workflow",
            "orknux_create_trigger", "orknux_create_action", "orknux_create_condition",
        )
        const val MANY = 500
        /** A column apart: a card is up to 260 wide, and the edge between two needs room to be seen. */
        const val COLUMN = 380
        const val ROW = 200

        /** Two given positions closer than this, both ways, put one card on another. */
        const val CARD_WIDTH = 280.0
        const val CARD_HEIGHT = 120.0
    }
}
