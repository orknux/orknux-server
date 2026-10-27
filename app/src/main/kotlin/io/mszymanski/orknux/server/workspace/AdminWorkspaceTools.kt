package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.security.KnownSecrets
import io.mszymanski.orknux.server.security.SecurityProperties
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * What an admin workspace's agents may call that nobody else's may: reading
 * other workspaces, and reading the installation's admin pages.
 *
 * **Decided here, at the call, from the agent's own workspace row.** Nothing a
 * model passes says whether it may: [run] reads the workspace the agent belongs
 * to and that workspace's [AdminWorkspace] again on every call, so a workspace
 * switched off, a scope switched off or a workspace taken out of reach is
 * refused from the next call, whatever the session's frozen tool array still
 * declares. A workspace a model names is checked against the reach list, and a
 * workspace that is not there and one that is out of reach are answered alike.
 *
 * **Read only.** Every scope is a list and a read, delegated to the reader an
 * agent in that workspace would use ([OrknuxTools]), handed that workspace and
 * never anything that writes. The admin pages are read through their own
 * controllers' queries - the same answer the page draws - and nothing here
 * calls a mutation.
 *
 * **No secret crosses.** Every answer passes [KnownSecrets.masked], which takes
 * out every credential the installation holds by value and anything shaped like
 * one, and [scrubbed], which drops any member named like a credential.
 *
 * **Not on the MCP endpoint.** These are not orknux_* tools: an MCP token is
 * scoped to one workspace and its owner, and nothing here is reachable through
 * one. Only [io.mszymanski.orknux.server.chat.AgentTools] offers them, and only
 * to an agent whose workspace is an admin workspace.
 */
@Service
class AdminWorkspaceTools(
    private val workspaces: WorkspaceRepository,
    private val settings: AdminWorkspaceRepository,
    /** The workspace readers, handed the workspace being read. Lazy: it reaches the controllers that run agents. */
    @param:Lazy private val orknux: OrknuxTools,
    private val chats: io.mszymanski.orknux.server.chat.ChatSessionRepository,
    /** A chat's messages as the chat page reads them. Lazy: chats run agents, and agents are offered these. */
    @param:Lazy private val chatService: io.mszymanski.orknux.server.chat.ChatService,
    @param:Lazy private val researchApi: io.mszymanski.orknux.server.research.ResearchAPI,
    @param:Lazy private val doctorApi: io.mszymanski.orknux.server.admin.DoctorAPI,
    @param:Lazy private val roleApi: io.mszymanski.orknux.server.security.RoleAPI,
    @param:Lazy private val usageApi: io.mszymanski.orknux.server.usage.UsageReportAPI,
    @param:Lazy private val monitoringApi: io.mszymanski.orknux.server.monitoring.MonitoringAPI,
    @param:Lazy private val templateApi: io.mszymanski.orknux.server.transfer.ComponentTemplateAPI,
    @param:Lazy private val pluginApi: io.mszymanski.orknux.server.plugin.PluginAPI,
    @param:Lazy private val auditApi: WorkspaceAuditAPI,
    @param:Lazy private val userApi: io.mszymanski.orknux.server.user.UserAPI,
    private val access: WorkspaceAccess,
    private val security: SecurityProperties,
    private val secrets: KnownSecrets,
    private val mapper: ObjectMapper,
) {

    /** Whether this name is one of these tools, so a caller knows where to send it. */
    fun handles(name: String): Boolean = name in NAMES

    /**
     * What this agent is offered now: nothing unless its workspace is an admin
     * workspace, and then the readers while any scope is on and each admin
     * page whose switch is on.
     */
    fun specsFor(agent: Agent): List<ToolSpec> {
        val held = heldFor(agent.workspaceId) ?: return emptyList()
        return specs(held)
    }

    /** The names an admin workspace's agents are offered, for the rows on an agent's Tools list. */
    fun namesFor(workspaceId: Long): List<String> = heldFor(workspaceId)?.let(::specs).orEmpty().map { it.name }

    /** The line an agent's Tools list shows on hover for one of these. */
    fun summaryOf(name: String): String? =
        specs(AdminWorkspace(scopes = AdminWorkspaceScope.entries.toMutableSet(), tools = AdminWorkspaceTool.entries.toMutableSet()))
            .firstOrNull { it.name == name }?.summary

    private fun specs(held: AdminWorkspace): List<ToolSpec> = buildList {
        val kinds = AdminWorkspaceScope.entries.filter { it in held.scopes }.map { it.kind }
        if (kinds.isNotEmpty()) {
            val kind = ToolParameterSpec(
                KIND,
                "What to read: ${kinds.joinToString(", ")}.",
                required = true,
                schema = mapOf("type" to "string", "enum" to kinds),
            )
            val workspace = ToolParameterSpec(WORKSPACE, "The workspace's name or id, from $WORKSPACES.", required = true)
            add(
                ToolSpec(
                    name = WORKSPACES,
                    readOnly = true,
                    description = "Lists the workspaces this admin workspace may read, and what may be read in them: " +
                        "${kinds.joinToString(", ")}. Read only: nothing in another workspace can be changed or run from here.",
                    parameters = emptyList(),
                    summary = "Lists the workspaces you may read.",
                ),
            )
            add(
                ToolSpec(
                    name = LIST,
                    readOnly = true,
                    description = "Lists one kind of thing in another workspace, newest first: its conversations " +
                        "(sessions), people's chats, agents, actions with the functions behind them, workflows, " +
                        "runs or research reports - whichever this admin workspace may read. Read one with $READ.",
                    parameters = listOf(
                        workspace,
                        kind,
                        ToolParameterSpec("search", "For sessions: only those whose key contains this.", required = false),
                        ToolParameterSpec("status", "For runs: RUNNING, COMPLETED, FAILED or STOPPED.", required = false),
                        ToolParameterSpec("limit", "How many to return; 20 by default, 100 at most.", required = false),
                    ),
                    summary = "Lists sessions, chats, agents, actions, workflows, runs or reports in another workspace.",
                ),
            )
            add(
                ToolSpec(
                    name = READ,
                    readOnly = true,
                    description = "Reads one thing in another workspace in full, by the id or name $LIST gave: a " +
                        "conversation's log, a chat's messages, an agent, a function, a workflow's graph, a run's " +
                        "steps or a research report. Credentials are never shown; where one was, it reads ${KnownSecrets.MARK}.",
                    parameters = listOf(
                        workspace,
                        kind,
                        ToolParameterSpec(ID, "The id, or for an agent, a function or a workflow its name.", required = true),
                        ToolParameterSpec("after", "For a session: only lines after this line id, to read on.", required = false),
                        ToolParameterSpec("limit", "For a session: how many lines; 50 by default, 200 at most.", required = false),
                    ),
                    summary = "Reads one session, chat, agent, function, workflow, run or report in another workspace.",
                ),
            )
        }
        AdminWorkspaceTool.entries.filter { it in held.tools }.forEach { add(ADMIN_SPECS.getValue(it)) }
    }

    /** The admin workspace's settings, where [workspaceId] is an admin workspace now; null otherwise. */
    private fun heldFor(workspaceId: Long): AdminWorkspace? {
        val workspace = workspaces.findByIdOrNull(workspaceId)?.takeIf { it.adminWorkspace } ?: return null
        return settings.findByIdOrNull(requireNotNull(workspace.id))
    }

    /**
     * Runs one call and answers as JSON. Never throws: a refusal is said, in
     * words, as `{"error": ...}`.
     */
    fun run(agent: Agent, name: String, arguments: String): String = try {
        // Read again here, not trusted from whatever offered the tool: the frame is frozen, the switch is not.
        val held = heldFor(agent.workspaceId)
        when {
            held == null -> refuse("There is no tool called $name")
            name in READERS && held.scopes.isEmpty() ->
                refuse("Reading other workspaces is switched off for this admin workspace, in Admin > Workspaces.")
            name == WORKSPACES -> reach(held)
            name == LIST -> list(agent, held, arguments)
            name == READ -> read(agent, held, arguments)
            else -> {
                val tool = AdminWorkspaceTool.named(name) ?: return refuse("There is no tool called $name")
                if (tool !in held.tools) {
                    refuse("$name is switched off for this admin workspace, in Admin > Workspaces.")
                } else {
                    adminRead(tool, arguments)
                }
            }
        }.let(::sealed)
    } catch (failure: Exception) {
        log.warn("Admin workspace tool {} failed for agent {}", name, agent.name, failure)
        refuse(secrets.masked(failure.message ?: "That could not be read"))
    }

    private fun reach(held: AdminWorkspace): String {
        val reached = workspaces.findAll().filter { held.reaches(requireNotNull(it.id)) }.sortedBy { it.name.lowercase() }
        return mapper.writeValueAsString(
            mapOf(
                "workspaces" to reached.map { mapOf("id" to it.id, "name" to it.name, "description" to it.description) },
                "mayRead" to AdminWorkspaceScope.entries.filter { it in held.scopes }.map { it.kind },
            ),
        )
    }

    private fun list(agent: Agent, held: AdminWorkspace, arguments: String): String {
        val (target, scope) = when (val said = asked(held, arguments)) {
            is Asked.Refused -> return said.answer
            is Asked.Reading -> said.target to said.scope
        }
        val limit = (number(arguments, "limit")?.toInt() ?: SOME).coerceIn(1, MANY)
        return reading(target, agent) {
            when (scope) {
                AdminWorkspaceScope.SESSIONS -> orknux.run(scopeOf(target, agent), "orknux_sessions", only(arguments, "search", "limit"))
                AdminWorkspaceScope.AGENTS -> orknux.run(scopeOf(target, agent), "orknux_agents", "{}")
                AdminWorkspaceScope.WORKFLOWS -> orknux.run(scopeOf(target, agent), "orknux_workflows", "{}")
                AdminWorkspaceScope.RUNS -> orknux.run(scopeOf(target, agent), "orknux_executions", only(arguments, "status", "limit"))
                AdminWorkspaceScope.ACTIONS -> {
                    val parts = mapper.readTree(orknux.run(scopeOf(target, agent), "orknux_workflow_parts", "{}"))
                    val functions = mapper.readTree(orknux.run(scopeOf(target, agent), "orknux_functions", "{}"))
                    if (parts.has("error")) parts.toString() else mapper.writeValueAsString(mapOf("parts" to parts, "functions" to functions.path("functions")))
                }
                AdminWorkspaceScope.CHATS -> {
                    val found = chats.findByWorkspaceIdOrderByIdDesc(target, PageRequest.of(0, limit))
                    mapper.writeValueAsString(
                        mapOf(
                            "chats" to found.content.map {
                                mapOf(
                                    "id" to it.id,
                                    "title" to it.title,
                                    "owner" to it.userId,
                                    "agentId" to it.agentId,
                                    "startedAt" to it.createdAt.toString(),
                                    "lastMessageAt" to it.lastMessageAt?.toString(),
                                )
                            },
                            "total" to found.totalElements,
                        ),
                    )
                }
                AdminWorkspaceScope.REPORTS -> {
                    val found = researchApi.researches(target, 0, limit)
                    mapper.writeValueAsString(
                        mapOf(
                            "reports" to found.content.map {
                                mapOf("id" to it.id, "question" to it.question, "mode" to it.modeName, "status" to it.status, "startedAt" to it.startedAt)
                            },
                            "total" to found.totalElements,
                        ),
                    )
                }
            }
        }
    }

    private fun read(agent: Agent, held: AdminWorkspace, arguments: String): String {
        val (target, scope) = when (val said = asked(held, arguments)) {
            is Asked.Refused -> return said.answer
            is Asked.Reading -> said.target to said.scope
        }
        val id = text(arguments, ID) ?: return refuse("Which one? Give its $ID, from $LIST.")
        return reading(target, agent) {
            when (scope) {
                AdminWorkspaceScope.SESSIONS -> {
                    val number = id.toLongOrNull() ?: return@reading refuse("A session is read by its id, a number.")
                    // Never "the conversation you are in": the session given is the one read, in that workspace.
                    val asked = mapper.readTree(only(arguments, "after", "limit")) as ObjectNode
                    asked.put("id", number)
                    orknux.run(scopeOf(target, agent), "orknux_session", asked.toString())
                }
                AdminWorkspaceScope.AGENTS -> orknux.run(scopeOf(target, agent), "orknux_agent", mapper.writeValueAsString(mapOf("agent" to id)))
                AdminWorkspaceScope.ACTIONS -> orknux.run(scopeOf(target, agent), "orknux_function", mapper.writeValueAsString(mapOf("function" to id)))
                AdminWorkspaceScope.WORKFLOWS -> orknux.run(scopeOf(target, agent), "orknux_workflow", mapper.writeValueAsString(mapOf("workflow" to id)))
                AdminWorkspaceScope.RUNS -> orknux.run(scopeOf(target, agent), "orknux_execution", mapper.writeValueAsString(mapOf("id" to id)))
                AdminWorkspaceScope.REPORTS -> orknux.run(scopeOf(target, agent), "orknux_research", mapper.writeValueAsString(mapOf("id" to id)))
                AdminWorkspaceScope.CHATS -> {
                    val chat = id.toLongOrNull()?.let { chats.findByIdOrNull(it) }?.takeIf { it.workspaceId == target }
                        ?: return@reading refuse("There is no chat $id there")
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to chat.id,
                            "title" to chat.title,
                            "owner" to chat.userId,
                            "messages" to chatService.messages(chat).map {
                                mapOf("role" to it.role, "who" to it.actor, "at" to it.at, "said" to it.content)
                            },
                        ),
                    )
                }
            }
        }
    }

    /** What a call to the readers named: a workspace and a scope it may read, or why not. */
    private sealed interface Asked {
        data class Reading(val target: Long, val scope: AdminWorkspaceScope) : Asked
        data class Refused(val answer: String) : Asked
    }

    /**
     * The workspace and the scope a call names, or the refusal. A workspace
     * that is not there and one out of reach are the same answer: this admin
     * workspace's reach is the whole of what exists from where its agents stand.
     */
    private fun asked(held: AdminWorkspace, arguments: String): Asked {
        val named = text(arguments, WORKSPACE)
            ?: return Asked.Refused(refuse("Which workspace? Give its name or id, from $WORKSPACES."))
        val found = (named.toLongOrNull()?.let { workspaces.findByIdOrNull(it) } ?: workspaces.findAll().firstOrNull { it.name.equals(named, ignoreCase = true) })
            ?.takeIf { held.reaches(requireNotNull(it.id)) }
            ?: return Asked.Refused(refuse("There is no workspace $named within this admin workspace's reach."))
        val kinds = AdminWorkspaceScope.entries.filter { it in held.scopes }.map { it.kind }
        val scope = AdminWorkspaceScope.ofKind(text(arguments, KIND))
            ?: return Asked.Refused(refuse("What should be read? One of ${kinds.joinToString(", ")}."))
        if (scope !in held.scopes) {
            return Asked.Refused(refuse("Reading ${scope.kind} in other workspaces is switched off for this admin workspace, in Admin > Workspaces."))
        }
        return Asked.Reading(requireNotNull(found.id), scope)
    }

    /**
     * Runs a read as the workspace being read and nobody else.
     *
     * Whoever is signed in is set aside for the length of it - an administrator
     * chatting with an admin workspace's agent sees every workspace, and the
     * readers below would otherwise answer from that, past the reach list. With
     * nobody signed in and [WorkspaceAccess.actingIn] the target, every check
     * the readers make admits that one workspace and no other.
     */
    private fun reading(target: Long, agent: Agent, read: () -> String): String {
        val before = SecurityContextHolder.getContext()
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext())
        try {
            return access.actingIn(target, agent.name) { read() }
        } finally {
            SecurityContextHolder.setContext(before)
        }
    }

    private fun scopeOf(target: Long, agent: Agent) = OrknuxScope(workspaceId = target, mayWrite = false, actor = agent.name)

    /**
     * One admin page, read through its own query - the answer the page draws -
     * as an installation administrator for the length of that one query.
     *
     * The queries are named here one by one and every one is a read: the
     * authority exists only inside this method and only for a query listed in
     * it, so it cannot reach a mutation, and whoever was signed in is put back
     * afterwards.
     */
    private fun adminRead(tool: AdminWorkspaceTool, arguments: String): String {
        val limit = (number(arguments, "limit")?.toInt() ?: SOME_ROWS).coerceIn(1, MANY)
        val answer: Any = asAdministrator {
            when (tool) {
                AdminWorkspaceTool.DOCTOR -> mapOf("checks" to doctorApi.doctor())
                AdminWorkspaceTool.ROLES -> mapOf("roles" to roleApi.roles())
                AdminWorkspaceTool.MONITORING -> mapOf("components" to monitoringApi.components())
                AdminWorkspaceTool.TEMPLATES -> mapOf("templates" to templateApi.componentTemplates(null))
                AdminWorkspaceTool.PLUGINS -> mapOf("plugins" to pluginApi.plugins())
                AdminWorkspaceTool.USERS -> mapOf("users" to userApi.users(text(arguments, "search")).take(limit))
                AdminWorkspaceTool.AUDIT -> auditApi.workspaceAudit(
                    workspaceId = number(arguments, "workspaceId"),
                    page = 0,
                    size = limit,
                    search = text(arguments, "search"),
                    category = null,
                    userId = null,
                    days = number(arguments, "days")?.toInt(),
                    order = null,
                    ascending = null,
                )
                AdminWorkspaceTool.REPORTS -> usageApi.usageReport(
                    io.mszymanski.orknux.server.usage.UsageReportFilter(
                        from = text(arguments, "from"),
                        to = text(arguments, "to"),
                    ),
                    0,
                    limit,
                    null,
                    null,
                )
            }
        }
        return mapper.writeValueAsString(answer)
    }

    private fun <T> asAdministrator(block: () -> T): T {
        val before = SecurityContextHolder.getContext()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(
            READER,
            null,
            listOf(SimpleGrantedAuthority(security.adminRole)),
        )
        SecurityContextHolder.setContext(context)
        try {
            return block()
        } finally {
            SecurityContextHolder.setContext(before)
        }
    }

    /** An answer on its way out: no member named like a credential, and no credential by value. */
    private fun sealed(answer: String): String {
        val tree = runCatching { mapper.readTree(answer) }.getOrNull()
        val scrubbed = if (tree != null && (tree.isObject || tree.isArray)) mapper.writeValueAsString(scrubbed(tree)) else answer
        return secrets.masked(scrubbed)
    }

    /** [node] without any member whose name says it holds a credential, or a picture nobody reads as text. */
    private fun scrubbed(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { out ->
            node.properties().forEach { (key, value) -> if (key.lowercase() !in DROPPED) out.set(key, scrubbed(value)) }
        }
        node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(scrubbed(it)) } }
        else -> node
    }

    /** Only these members of the call's arguments, for a reader that takes fewer than this tool does. */
    private fun only(arguments: String, vararg names: String): String {
        val given = runCatching { mapper.readTree(arguments) }.getOrNull()
        val out = mapper.createObjectNode()
        names.forEach { name -> given?.get(name)?.takeIf { !it.isNull }?.let { out.set(name, it) } }
        return out.toString()
    }

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    private fun text(arguments: String, name: String): String? = runCatching {
        val node = mapper.readTree(arguments).path(name)
        when {
            node.isString -> node.stringValue()
            node.isNumber -> node.asString()
            else -> null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun number(arguments: String, name: String): Long? = text(arguments, name)?.toLongOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(AdminWorkspaceTools::class.java)

        const val WORKSPACES = "admin_workspaces"
        const val LIST = "admin_workspace_list"
        const val READ = "admin_workspace_read"

        private const val WORKSPACE = "workspace"
        private const val KIND = "kind"
        private const val ID = "id"

        /** Who the admin pages are read as, for the length of one query. */
        private const val READER = "admin-workspace-reader"

        private const val SOME = 20
        private const val SOME_ROWS = 50
        private const val MANY = 100

        private val READERS = setOf(WORKSPACES, LIST, READ)

        /**
         * Members never handed on, by name, wherever they sit in an answer: a
         * credential by what it is called, a capability address a trigger
         * answers at, and an icon that is a picture in base64.
         */
        private val DROPPED = setOf(
            "secret", "secrets", "password", "passphrase", "keypassphrase", "apikey", "apptoken", "usertoken",
            "accesstoken", "refreshtoken", "token", "privatekey", "clientsecret", "webhookpath", "webhooksecret",
            "icon", "icondark",
        )

        private fun admin(tool: AdminWorkspaceTool, description: String, summary: String, parameters: List<ToolParameterSpec> = emptyList()) =
            ToolSpec(name = tool.toolName, readOnly = true, description = "$description Read only.", parameters = parameters, summary = summary)

        private val LIMIT = ToolParameterSpec("limit", "How many rows to return; 50 by default, 100 at most.", required = false)

        private val ADMIN_SPECS: Map<AdminWorkspaceTool, ToolSpec> = mapOf(
            AdminWorkspaceTool.REPORTS to admin(
                AdminWorkspaceTool.REPORTS,
                "The installation's usage report, as Admin > Reports draws it: tokens and cost per person, over a period.",
                "Reads the installation's usage report.",
                listOf(
                    ToolParameterSpec("from", "The first day, as YYYY-MM-DD; the report's own default if left out.", required = false),
                    ToolParameterSpec("to", "The last day, as YYYY-MM-DD.", required = false),
                    LIMIT,
                ),
            ),
            AdminWorkspaceTool.DOCTOR to admin(
                AdminWorkspaceTool.DOCTOR,
                "The installation's health checks, as Admin > Doctor draws them: each check, whether it passed, and what to do about it.",
                "Reads the installation's health checks.",
            ),
            AdminWorkspaceTool.ROLES to admin(
                AdminWorkspaceTool.ROLES,
                "The roles this installation defines, with what each lets somebody do and which directory names grant it.",
                "Reads the installation's roles.",
            ),
            AdminWorkspaceTool.MONITORING to admin(
                AdminWorkspaceTool.MONITORING,
                "What Admin > Monitoring shows: each component the server depends on and whether it is answering.",
                "Reads the installation's monitoring.",
            ),
            AdminWorkspaceTool.TEMPLATES to admin(
                AdminWorkspaceTool.TEMPLATES,
                "The component templates published to the whole installation.",
                "Reads the installation's component templates.",
            ),
            AdminWorkspaceTool.PLUGINS to admin(
                AdminWorkspaceTool.PLUGINS,
                "The plugins installed on this installation, with what each declares and the permissions it was granted.",
                "Reads the installation's plugins.",
            ),
            AdminWorkspaceTool.AUDIT to admin(
                AdminWorkspaceTool.AUDIT,
                "The installation's audit log, newest first: who changed what, in which workspace.",
                "Reads the installation's audit log.",
                listOf(
                    ToolParameterSpec("workspaceId", "Only this workspace's entries.", required = false),
                    ToolParameterSpec("search", "Only entries mentioning this.", required = false),
                    ToolParameterSpec("days", "Only the last this many days.", required = false),
                    LIMIT,
                ),
            ),
            AdminWorkspaceTool.USERS to admin(
                AdminWorkspaceTool.USERS,
                "The people this installation knows, with the roles each holds and when they last signed in.",
                "Reads the installation's users.",
                listOf(ToolParameterSpec("search", "Only people whose name matches this.", required = false), LIMIT),
            ),
        )

        /** Every name these tools answer to. */
        val NAMES: Set<String> = READERS + AdminWorkspaceTool.entries.map { it.toolName }
    }
}
