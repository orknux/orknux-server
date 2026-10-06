package io.mszymanski.orknux.server.dependency

import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.action.ActionHeaders
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentGrants
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.library.ScriptLibraryRepository
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.workflow.WorkflowReferences
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Where a component is used — the one place that answers it.
 *
 * Every delete guard in this application had grown its own answer to the same
 * question, and each of them threw the answer away: it was joined into a
 * sentence, the sentence was thrown, and the reader was told *"That library is
 * imported by slugify in Backend"* with nothing to click. Two issues came out of
 * that — #258 asking for the list on the component's own page, #268 asking for a
 * link in the refusal — and they are one question with two audiences.
 *
 * So the set is computed here and formatted twice. A delete guard asks for it and
 * joins [Dependant.phrase]; a screen asks for it and draws a link per row. The
 * guard's wording does not change, because it is the same words assembled from
 * the same rows — what changes is that the rows survive.
 *
 * **What counts as a dependant is what would break.** That is deliberately the
 * delete guard's definition and not a wider one: a workflow node holding an id, a
 * function importing another by id, an agent holding a grant by name, a
 * connection reading a variable for its credential. A workflow that merely *ran*
 * something last Tuesday is history and is not here.
 *
 * **Only one half is asked twice.** A workflow names a definition in its draft
 * and in its published copy; [WorkflowReferences] reports the published one in
 * preference, because that is the harder of the two to be rid of, and
 * [Dependant.published] carries which so a screen can say so.
 *
 * Nothing here checks access. That is the caller's, and the two callers want
 * different things: a delete guard must count every dependant, including one in a
 * workspace the deleter cannot see, or it would let a library be removed out from
 * under a workspace merely because the administrator removing it was looking the
 * other way. A screen must not *name* those. [DependencyAPI] is where that line
 * is drawn.
 */
@Component
class ComponentDependants(
    private val workspaces: WorkspaceRepository,
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
    private val headers: ActionHeaders,
    private val references: WorkflowReferences,
    private val grants: AgentGrants,
    private val models: ModelService,
    private val connections: WorkspaceConnectionService,
    private val mcpServers: McpServerService,
) {

    /**
     * Everything that would break if this component went away.
     *
     * @throws DependencyKindNotAskableException for a kind nothing points at.
     */
    fun of(kind: DependencyKind, id: Long): List<Dependant> = when (kind) {
        DependencyKind.FUNCTION -> ofFunction(id)
        DependencyKind.TOOL -> ofTool(id)
        DependencyKind.SKILL_CATALOG -> ofSkillCatalog(id)
        DependencyKind.MEMORY_CATALOG -> ofMemoryCatalog(id)
        DependencyKind.AGENT -> ofAgent(id)
        DependencyKind.ACTION -> ofAction(id)
        DependencyKind.CONDITION -> ofCondition(id)
        DependencyKind.TRIGGER -> ofTrigger(id)
        DependencyKind.OBJECT -> ofObject(id)
        DependencyKind.VARIABLE -> ofVariable(id)
        DependencyKind.LIBRARY -> ofLibrary(id)
        DependencyKind.MCP_SERVER -> ofMcpServer(id)
        DependencyKind.WORKFLOW,
        DependencyKind.CONNECTION,
        DependencyKind.MODEL_PROVIDER,
        -> throw DependencyKindNotAskableException(kind)
    }

    /**
     * The workspace this component belongs to, or null where it belongs to none.
     *
     * What a resolver checks access against before it will admit the id exists. A
     * library belongs to the installation and an organisation function to nobody,
     * so both answer null and the caller decides what that means.
     */
    fun workspaceOf(kind: DependencyKind, id: Long): Long? = when (kind) {
        DependencyKind.FUNCTION -> functions.findByIdOrNull(id)?.workspaceId
        DependencyKind.TOOL -> tools.findByIdOrNull(id)?.workspaceId
        DependencyKind.SKILL_CATALOG -> skillCatalogs.findByIdOrNull(id)?.workspaceId
        DependencyKind.MEMORY_CATALOG -> memoryCatalogs.findByIdOrNull(id)?.workspaceId
        DependencyKind.AGENT -> agents.workspaceIdOf(id)
        DependencyKind.ACTION -> actions.findByIdOrNull(id)?.workspaceId
        DependencyKind.CONDITION -> conditions.findByIdOrNull(id)?.workspaceId
        DependencyKind.TRIGGER -> triggers.findByIdOrNull(id)?.workspaceId
        DependencyKind.OBJECT -> objects.findByIdOrNull(id)?.workspaceId
        DependencyKind.VARIABLE -> variables.findByIdOrNull(id)?.workspaceId
        DependencyKind.LIBRARY -> null
        DependencyKind.MCP_SERVER -> mcpServers.mcpServer(id)?.workspaceId
        else -> null
    }

    /** Whether the component is there at all, so a resolver can answer not-found. */
    fun exists(kind: DependencyKind, id: Long): Boolean = when (kind) {
        DependencyKind.FUNCTION -> functions.existsById(id)
        DependencyKind.TOOL -> tools.existsById(id)
        DependencyKind.SKILL_CATALOG -> skillCatalogs.existsById(id)
        DependencyKind.MEMORY_CATALOG -> memoryCatalogs.existsById(id)
        DependencyKind.AGENT -> agents.existsById(id)
        DependencyKind.ACTION -> actions.existsById(id)
        DependencyKind.CONDITION -> conditions.existsById(id)
        DependencyKind.TRIGGER -> triggers.existsById(id)
        DependencyKind.OBJECT -> objects.existsById(id)
        DependencyKind.VARIABLE -> variables.existsById(id)
        DependencyKind.LIBRARY -> libraries.existsById(id)
        DependencyKind.MCP_SERVER -> mcpServers.mcpServer(id) != null
        else -> false
    }

    /**
     * What calls a function, and what imports it.
     *
     * Two sets in one list, and the wording keeps them apart because the way out
     * differs: a caller is a node somebody repoints, an importer is code somebody
     * has to open and edit. The delete guard still throws them as two exceptions
     * for exactly that reason — see [callersOfFunction] and [importersOfFunction],
     * which are what it asks — while a reader of the list wants the whole answer.
     */
    private fun ofFunction(id: Long): List<Dependant> = callersOfFunction(id) + importersOfFunction(id)

    /**
     * Actions, conditions and the webhooks that authenticate with it.
     *
     * A webhook is said as "the webhook Nightly" because it is in no list a bare
     * name would send the reader to; an action and a condition are bare, because
     * the workspace's own lists are what somebody is looking at when they read it.
     *
     * The conditions are the only half that has to know where the function lives,
     * and it is the half a plugin's function gets wrong if nobody says so. A
     * workspace function can only be asked by a condition in its own workspace, so
     * scoping is the cheaper query and the honest one. A **plugin's** function
     * belongs to no workspace and is offered in every one of them, so the question
     * is installation-wide — and answering it from a null workspace with an empty
     * list would be telling an administrator that nothing calls a function three
     * workspaces are calling. `findByFunctionId` and `findByAuthFunctionId` were
     * already installation-wide, which is why only this one branches.
     */
    fun callersOfFunction(id: Long): List<Dependant> {
        val function = functions.findByIdOrNull(id) ?: return emptyList()
        val asking = function.workspaceId
            ?.let { conditions.findByWorkspaceIdAndFunctionId(it, id) }
            ?: conditions.findByFunctionId(id)
        return actions.findByFunctionId(id).map { plain(DependencyKind.ACTION, it.id, it.name, it.workspaceId) } +
            asking
                .map { plain(DependencyKind.CONDITION, it.id, it.name, it.workspaceId) } +
            triggers.findByAuthFunctionId(id)
                .map { qualified(DependencyKind.TRIGGER, it.id, it.name, it.workspaceId, "the webhook ${it.name}") }
    }

    /** Functions and tools whose code says its name. */
    fun importersOfFunction(id: Long): List<Dependant> =
        functions.findByImportedFunctionId(id).map { plain(DependencyKind.FUNCTION, it.id, it.name, it.workspaceId) } +
            tools.findByImportedFunctionId(id)
                .map { qualified(DependencyKind.TOOL, it.id, it.name, it.workspaceId, "the tool ${it.name}") }

    private fun ofTool(id: Long): List<Dependant> {
        val tool = tools.findByIdOrNull(id) ?: return emptyList()
        return grants.toTool(tool.workspaceId, tool.name)
    }

    private fun ofSkillCatalog(id: Long): List<Dependant> {
        val catalog = skillCatalogs.findByIdOrNull(id) ?: return emptyList()
        return grants.toSkillCatalog(catalog.workspaceId, catalog.name)
    }

    private fun ofMemoryCatalog(id: Long): List<Dependant> {
        val catalog = memoryCatalogs.findByIdOrNull(id) ?: return emptyList()
        return grants.toMemoryCatalog(catalog.workspaceId, catalog.name)
    }

    private fun ofAgent(id: Long): List<Dependant> {
        val workspaceId = agents.workspaceIdOf(id) ?: return emptyList()
        return references.toAgent(workspaceId, id)
    }

    private fun ofAction(id: Long): List<Dependant> {
        val action = actions.findByIdOrNull(id) ?: return emptyList()
        return references.toAction(action.workspaceId, id)
    }

    /** Actions, the condition groups that hold it, workflows, and triggers. */
    private fun ofCondition(id: Long): List<Dependant> {
        val condition = conditions.findByIdOrNull(id) ?: return emptyList()
        val workspaceId = condition.workspaceId
        return actions.findByWorkspaceIdAndConditionId(workspaceId, id)
            .map { plain(DependencyKind.ACTION, it.id, it.name, it.workspaceId) } +
            conditions.groupsHolding(workspaceId, id)
                .map { plain(DependencyKind.CONDITION, it.id, it.name, it.workspaceId) } +
            references.toCondition(workspaceId, id) +
            triggers.findByConditionId(id).map { plain(DependencyKind.TRIGGER, it.id, it.name, it.workspaceId) }
    }

    private fun ofTrigger(id: Long): List<Dependant> {
        val trigger = triggers.findByIdOrNull(id) ?: return emptyList()
        return references.toTrigger(trigger.workspaceId, id)
    }

    private fun ofObject(id: Long): List<Dependant> {
        val held = objects.findByIdOrNull(id) ?: return emptyList()
        /*
         * This owner's shapes and every plugin's. A workspace's property may
         * point at a shape a plugin exports, so the workspace's own list is
         * not the whole of what would break - and a plugin's may point at
         * another plugin's, which is the same question asked the other way.
         */
        return (held.workspaceId?.let(objects::findByWorkspaceId).orEmpty() + objects.findByPluginIdIsNotNull())
            .filter { it.id != id && it.properties.any { property -> property.refObjectId == id } }
            .map { plain(DependencyKind.OBJECT, it.id, it.name, it.workspaceId) } +
            triggers.findByObjectId(id)
                .map { qualified(DependencyKind.TRIGGER, it.id, it.name, it.workspaceId, "the webhook ${it.name}") }
    }

    /**
     * What a variable is part of, and what authenticates with it.
     *
     * Two sets again, and again two refusals — [signatureOfVariable] is a
     * function's argument list, [credentialOfVariable] is somebody's token — with
     * different sentences and different ways out.
     */
    private fun ofVariable(id: Long): List<Dependant> = signatureOfVariable(id) + credentialOfVariable(id)

    /** Functions taking it as an external parameter, and actions whose headers read it. */
    fun signatureOfVariable(id: Long): List<Dependant> {
        val variable = variables.findByIdOrNull(id) ?: return emptyList()
        val workspaceId = variable.workspaceId
        return functions.findByWorkspaceId(workspaceId)
            .filter { function -> function.externals.any { it.variableId == id } }
            .map { plain(DependencyKind.FUNCTION, it.id, it.name, it.workspaceId) } +
            actions.findByWorkspaceId(workspaceId)
                .filter { action -> headers.reads(action, id) }
                .map { plain(DependencyKind.ACTION, it.id, it.name, it.workspaceId) }
    }

    /**
     * Everything reading it for a credential, in kind order and then by name.
     *
     * The noun is carried by the entry rather than by the sentence around it:
     * "the connection Slack, the MCP server brave-search" reads, and a bare
     * "Slack, brave-search" leaves whoever hit the refusal to go and find out what
     * those are. Asked of the connection module, which owns all three kinds of
     * holder — there is no foreign key across that boundary and there is not meant
     * to be one.
     */
    fun credentialOfVariable(id: Long): List<Dependant> {
        val variable = variables.findByIdOrNull(id) ?: return emptyList()
        val workspaceId = variable.workspaceId
        return models.providersReading(workspaceId, id).map {
            qualified(DependencyKind.MODEL_PROVIDER, it.id, it.name, workspaceId, "the model provider ${it.name}")
        } + connections.connectionsReading(workspaceId, id).map {
            qualified(DependencyKind.CONNECTION, it.id, it.name, workspaceId, "the connection ${it.name}")
        } + mcpServers.serversReading(workspaceId, id).map {
            qualified(DependencyKind.MCP_SERVER, it.id, it.name, workspaceId, "the MCP server ${it.name}")
        }
    }

    /**
     * Every function and tool importing a library, across every workspace.
     *
     * The one answer that leaves a workspace. A library belongs to the
     * installation because the question an installation has to be able to answer
     * is what code is running inside it, so the workspace is part of the entry —
     * "slugify in Backend" — rather than assumed from where the reader is
     * standing.
     */
    private fun ofLibrary(id: Long): List<Dependant> {
        val named = workspaces.findAll().associate { it.id to it.name }
        fun named(workspaceId: Long?) = named[workspaceId] ?: ""
        return functions.findByImportedLibraryId(id).map {
            Dependant(
                kind = DependencyKind.FUNCTION,
                id = requireNotNull(it.id),
                name = it.name,
                workspaceId = it.workspaceId,
                workspaceName = named(it.workspaceId),
                published = false,
                phrase = "${it.name} in ${named(it.workspaceId)}",
            )
        } + tools.findByImportedLibraryId(id).map {
            Dependant(
                kind = DependencyKind.TOOL,
                id = requireNotNull(it.id),
                name = it.name,
                workspaceId = it.workspaceId,
                workspaceName = named(it.workspaceId),
                published = false,
                phrase = "${it.name} in ${named(it.workspaceId)}",
            )
        }
    }

    /** An entry a refusal names by its bare name, which is most of them. */
    /**
     * The agents granted this server.
     *
     * The whole answer, because an agent is the only thing that holds one. A
     * task can be given a server for the length of one run, but a run is
     * history rather than something that would break, which is the definition
     * this class keeps.
     *
     * Matched by name, because that is how the grant is stored - see
     * [AgentRepository.findGrantedMcpServer], and the reason it is spelled
     * exactly: a grant differing by a letter's case already resolves to nothing
     * at the moment the tools are gathered, so a list here that was looser than
     * that would name agents which do not in fact have it.
     */
    private fun ofMcpServer(id: Long): List<Dependant> {
        val server = mcpServers.mcpServer(id) ?: return emptyList()
        return agents.findGrantedMcpServer(server.workspaceId, server.name)
            .map { plain(DependencyKind.AGENT, it.id, it.name, it.workspaceId) }
            .sortedBy { it.name }
    }

    private fun plain(kind: DependencyKind, id: Long?, name: String, workspaceId: Long?) =
        qualified(kind, id, name, workspaceId, name)

    private fun qualified(kind: DependencyKind, id: Long?, name: String, workspaceId: Long?, phrase: String) =
        Dependant(
            kind = kind,
            id = requireNotNull(id),
            name = name,
            workspaceId = workspaceId,
            workspaceName = null,
            published = false,
            phrase = phrase,
        )
}

/** The clauses a refusal is assembled from, in the order the rows came back. */
fun List<Dependant>.phrases(): List<String> = map { it.phrase }
