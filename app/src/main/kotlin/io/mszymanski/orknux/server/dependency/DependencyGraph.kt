package io.mszymanski.orknux.server.dependency

import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.library.ScriptLibraryRepository
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/** One part of a workspace on the graph; `section` tells a workspace's settings apart. */
data class DependencyGraphNode(
    val kind: DependencyKind,
    val id: Long,
    val name: String,
    val published: Boolean = false,
    val section: String? = null,
) {
    val key: String get() = listOfNotNull(kind.name, id.toString(), section).joinToString(":")
}

/** `from` uses `to`: removing `to` would break `from`. Both are node keys. */
data class DependencyGraphEdge(val from: String, val to: String)

data class DependencyGraphView(
    val nodes: List<DependencyGraphNode>,
    val edges: List<DependencyGraphEdge>,
)

/**
 * Every part of a workspace and what uses what, drawn as one picture.
 *
 * Not a second answer to "what uses this": each component is asked
 * [ComponentDependants.of], the same question its own Used by panel and its
 * delete guard ask, and the arrows are those rows turned around. A third
 * definition of a dependency, kept here, would be how the graph and the
 * refusal came to disagree.
 *
 * Every part is a node whether anything points at it or not, because an
 * unused agent is half of what somebody looks at this picture to find. A
 * workflow and a connection are never asked - nothing points at them - but they
 * are drawn, the workflow from the workspace's list and the connection where a
 * variable is its credential.
 *
 * Two kinds reach outside the workspace. A plugin's function is drawn when
 * something here calls it, and a library when something here imports it; their
 * answers are installation-wide, so only the rows in this workspace are kept.
 */
@Component
class DependencyGraph(
    private val dependants: ComponentDependants,
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val triggers: WorkflowTriggerRepository,
    private val objects: WorkflowObjectRepository,
    private val tools: AgentToolRepository,
    private val agents: AgentRepository,
    private val skillCatalogs: SkillCatalogRepository,
    private val memoryCatalogs: MemoryCatalogRepository,
    private val variables: WorkspaceVariableRepository,
    private val libraries: ScriptLibraryRepository,
    private val workflows: WorkspaceWorkflowRepository,
    private val models: ModelService,
    private val mcpServers: McpServerService,
) {

    fun of(workspaceId: Long): DependencyGraphView {
        val nodes = linkedMapOf<String, DependencyGraphNode>()
        val edges = linkedSetOf<DependencyGraphEdge>()
        fun node(kind: DependencyKind, id: Long?, name: String) =
            DependencyGraphNode(kind, requireNotNull(id), name).also { nodes.putIfAbsent(it.key, it) }

        val workspaceActions = actions.findByWorkspaceId(workspaceId)
        val workspaceConditions = conditions.findByWorkspaceId(workspaceId)
        val workspaceTriggers = triggers.findByWorkspaceId(workspaceId, Pageable.unpaged()).content
        val asked = buildList {
            workflows.findByWorkspaceId(workspaceId).forEach { node(DependencyKind.WORKFLOW, it.workflow.id, it.workflow.name) }
            agents.findByWorkspaceId(workspaceId, Sort.by("name")).forEach { add(node(DependencyKind.AGENT, it.id, it.name)) }
            workspaceActions.forEach { add(node(DependencyKind.ACTION, it.id, it.name)) }
            workspaceConditions.forEach { add(node(DependencyKind.CONDITION, it.id, it.name)) }
            workspaceTriggers.forEach { add(node(DependencyKind.TRIGGER, it.id, it.name)) }
            functions.findByWorkspaceId(workspaceId).forEach { add(node(DependencyKind.FUNCTION, it.id, it.name)) }
            tools.findByWorkspaceId(workspaceId, Pageable.unpaged()).content.forEach { add(node(DependencyKind.TOOL, it.id, it.name)) }
            objects.findByWorkspaceId(workspaceId).forEach { add(node(DependencyKind.OBJECT, it.id, it.name)) }
            skillCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).forEach { add(node(DependencyKind.SKILL_CATALOG, it.id, it.name)) }
            memoryCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).forEach { add(node(DependencyKind.MEMORY_CATALOG, it.id, it.name)) }
            variables.findByWorkspaceId(workspaceId).forEach { add(node(DependencyKind.VARIABLE, it.id, it.name)) }
            mcpServers.mcpServers(workspaceId).forEach { add(node(DependencyKind.MCP_SERVER, it.id, it.name)) }
            models.models(workspaceId).forEach { add(node(DependencyKind.MODEL, it.id, it.name)) }
            models.providers(workspaceId).forEach { add(node(DependencyKind.MODEL_PROVIDER, it.id, it.name)) }
        }

        fun arrows(target: DependencyGraphNode, rows: List<Dependant>) {
            rows.filter { it.workspaceId == workspaceId }.forEach { row ->
                val from = DependencyGraphNode(row.kind, row.id, row.name, row.published, row.section)
                // A workflow's draft and its publication are one box; the published row wins, as it does everywhere.
                val key = from.key
                val held = nodes[key]
                if (held == null || (from.published && !held.published)) nodes[key] = from
                if (key != target.key) edges += DependencyGraphEdge(key, target.key)
            }
        }
        asked.forEach { arrows(it, dependants.of(it.kind, it.id)) }

        // A plugin's functions, where this workspace's actions, conditions and webhooks call them.
        val called = (workspaceActions.mapNotNull { it.functionId } +
            workspaceConditions.mapNotNull { it.functionId } +
            workspaceTriggers.mapNotNull { it.authFunctionId }).toSet()
        functions.findAllById(called).filter { it.workspaceId == null }.forEach {
            val target = DependencyGraphNode(DependencyKind.FUNCTION, requireNotNull(it.id), it.name)
            val rows = dependants.callersOfFunction(target.id)
            if (rows.any { row -> row.workspaceId == workspaceId }) {
                nodes.putIfAbsent(target.key, target)
                arrows(target, rows)
            }
        }
        // The installation's libraries, where code in this workspace imports them.
        libraries.findAll().forEach {
            val target = DependencyGraphNode(DependencyKind.LIBRARY, requireNotNull(it.id), it.name)
            val rows = dependants.of(DependencyKind.LIBRARY, target.id)
            if (rows.any { row -> row.workspaceId == workspaceId }) {
                nodes.putIfAbsent(target.key, target)
                arrows(target, rows)
            }
        }
        return DependencyGraphView(nodes.values.toList(), edges.toList())
    }
}
