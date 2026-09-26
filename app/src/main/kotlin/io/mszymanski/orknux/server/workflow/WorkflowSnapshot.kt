package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeBinding
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.WorkflowGraph as RunnableGraph
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The published graph, written down and read back.
 *
 * Both directions are spelled out here rather than left to the mapper's
 * reflection, for two reasons. The mapper this application shares cannot
 * construct a Kotlin data class - there is no Kotlin module registered, so
 * writing works and reading fails - and a shape that lives in a database
 * outlives the class it came from: renaming a field in [GraphNode] would
 * quietly stop old snapshots loading if the field names were the format.
 *
 * So this is the format, and it is dull on purpose. A field absent from an
 * older snapshot reads as the default it always had, which is what lets a
 * graph published last month still run after a node gains a property.
 */
object WorkflowSnapshot {

    fun write(graph: RunnableGraph, mapper: ObjectMapper): String = mapper.writeValueAsString(
        mapOf(
            "workflowId" to graph.workflowId,
            "name" to graph.name,
            "nodes" to graph.nodes.map { node ->
                mapOf(
                    "key" to node.key,
                    "kind" to node.kind.name,
                    "name" to node.name,
                    "description" to node.description,
                    "agentId" to node.agentId,
                    "actionId" to node.actionId,
                    "conditionId" to node.conditionId,
                    "imageModelId" to node.imageModelId,
                    "imageSize" to node.imageSize,
                    "imageQuality" to node.imageQuality,
                    "imageStyle" to node.imageStyle,
                    "outputObjectId" to node.outputObjectId,
                    "triggerId" to node.triggerId,
                    "outputName" to node.outputName,
                    "mappings" to node.mappings.mapValues { (_, binding) ->
                        mapOf(
                            "expression" to binding.expression,
                            "reference" to binding.reference,
                            "from" to binding.from,
                            "type" to binding.type,
                        )
                    },
                    "retryAttempts" to node.retryAttempts,
                    "retryBackoffSeconds" to node.retryBackoffSeconds,
                    "retryMultiplier" to node.retryMultiplier,
                    "retryMaxWaitSeconds" to node.retryMaxWaitSeconds,
                    "retryJitter" to node.retryJitter,
                    "retryBudgetSeconds" to node.retryBudgetSeconds,
                    "enabled" to node.enabled,
                    "x" to node.x,
                    "y" to node.y,
                )
            },
            "edges" to graph.edges.map { edge ->
                mapOf("source" to edge.source, "target" to edge.target, "branch" to edge.branch?.name)
            },
        ),
    )

    fun read(json: String, mapper: ObjectMapper): RunnableGraph {
        val held = mapper.readTree(json)
        return RunnableGraph(
            workflowId = held.path("workflowId").asLong(),
            name = text(held, "name").orEmpty(),
            // `.values()` rather than iterating the node directly: Jackson 3.1 gave
            // JsonNode a `map` member, and a member wins over Kotlin's Iterable.map.
            nodes = held.path("nodes").values().map { node ->
                GraphNode(
                    key = text(node, "key").orEmpty(),
                    kind = NodeKind.valueOf(text(node, "kind") ?: NodeKind.ACTION.name),
                    name = text(node, "name").orEmpty(),
                    description = text(node, "description"),
                    agentId = number(node, "agentId"),
                    actionId = number(node, "actionId"),
                    conditionId = number(node, "conditionId"),
                    imageModelId = number(node, "imageModelId"),
                    // Absent from every snapshot published before an image node
                    // could say a size, and read as the model's default - which
                    // is what such a node drew at.
                    imageSize = text(node, "imageSize"),
                    imageQuality = text(node, "imageQuality"),
                    imageStyle = text(node, "imageStyle"),
                    // Absent from every snapshot published before answers had
                    // shapes, and read as prose - which is what it was.
                    outputObjectId = number(node, "outputObjectId"),
                    /*
                     * Absent from every snapshot published before a run could
                     * tell two triggers apart, and read as the graph declining
                     * to say. A workflow published then goes on doing what it
                     * did — both triggers are beginnings, both branches run —
                     * until somebody publishes it again, which is the only
                     * moment a snapshot is ever rewritten.
                     */
                    triggerId = number(node, "triggerId"),
                    outputName = text(node, "outputName"),
                    mappings = node.path("mappings").properties().associate { (name, binding) ->
                        name to NodeBinding(
                            expression = text(binding, "expression").orEmpty(),
                            reference = binding.path("reference").asBoolean(false),
                            from = text(binding, "from"),
                            // Absent from every snapshot published before a
                            // field could say what it holds, and read as what it
                            // meant then: text, whatever was written.
                            type = text(binding, "type"),
                        )
                    },
                    // Absent from every snapshot published before a node could
                    // be told to try again, which reads as the once it had.
                    retryAttempts = number(node, "retryAttempts")?.toInt(),
                    retryBackoffSeconds = number(node, "retryBackoffSeconds")?.toInt(),
                    /*
                     * A snapshot is what a workflow was on the day it was
                     * published and is never rewritten, so the ones already on
                     * disk say `retryBackoff: EXPONENTIAL` and always will.
                     * EXPONENTIAL was a multiplier of two, and is read as one -
                     * so a workflow published last week goes on waiting what it
                     * waited last week. Anything else absent is what it meant
                     * when there was no field for it: a wait that does not grow,
                     * the engine's own ceiling, no jitter and no budget.
                     */
                    retryMultiplier = decimal(node, "retryMultiplier")
                        ?: EXPONENTIAL.takeIf { text(node, "retryBackoff") == "EXPONENTIAL" },
                    retryMaxWaitSeconds = number(node, "retryMaxWaitSeconds")?.toInt(),
                    retryJitter = decimal(node, "retryJitter"),
                    retryBudgetSeconds = number(node, "retryBudgetSeconds")?.toInt(),
                    // Absent from every snapshot published before a node could
                    // be switched off, and read as on - which is what it was.
                    enabled = node.path("enabled").asBoolean(true),
                    x = node.path("x").asDouble(),
                    y = node.path("y").asDouble(),
                )
            },
            edges = held.path("edges").values().map { edge ->
                GraphEdge(
                    source = text(edge, "source").orEmpty(),
                    target = text(edge, "target").orEmpty(),
                    branch = text(edge, "branch")?.let { EdgeBranch.valueOf(it) },
                )
            },
        )
    }

    /** Null where the field is missing or written as null, rather than throwing. */
    private fun text(node: JsonNode, name: String): String? =
        node.path(name).let { if (it.isString) it.stringValue() else null }

    private fun number(node: JsonNode, name: String): Long? =
        node.path(name).let { if (it.isNumber) it.asLong() else null }

    private fun decimal(node: JsonNode, name: String): Double? =
        node.path(name).let { if (it.isNumber) it.asDouble() else null }

    /** What the word EXPONENTIAL meant, for the snapshots that still say it. */
    private const val EXPONENTIAL = 2.0
}
