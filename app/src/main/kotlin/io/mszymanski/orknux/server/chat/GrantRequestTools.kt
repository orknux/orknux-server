package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentNotFoundException
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.interaction.InteractionHandler
import io.mszymanski.orknux.server.interaction.InteractionItem
import io.mszymanski.orknux.server.interaction.InteractionItems
import io.mszymanski.orknux.server.interaction.InteractionOption
import io.mszymanski.orknux.server.interaction.InteractionOptions
import io.mszymanski.orknux.server.interaction.InteractionRequest
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.interaction.Interactions
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * What an agent could be granted and is not, and a way to ask a person for it.
 *
 * An agent that lacked a tool could only say it could not do the thing - and
 * the person, who would have granted it in a click, had to go and find the
 * agent's page, find the tool among hundreds, tick it and ask again. Two
 * built-ins close that loop: [LIST] says what is grantable and not granted,
 * [ASK] puts the ones it wants to the person as a question in the
 * conversation, with each tool named, its kind and what it does. Accepted, the
 * grant is written to the agent itself - the same lists the agent page edits,
 * with a revision and an audit line - so it is permanent; the agent hears the
 * outcome through its inbox and is offered the tools from its next round.
 *
 * Switched by [Agent.mayAskForTools], on by default: asking grants nothing by
 * itself, a person still decides.
 *
 * What counts as a tool here is what the agent's Tools list grants by name -
 * built-ins it has hidden or that are off until asked for, the workspace's own
 * tools, the loaded plugins' - plus the two grants that bring a family of tools
 * at once: an MCP server, and Orknux access - and the workspace's connections,
 * which a tool names to reach a service: an agent holding a Jira tool and no
 * Jira connection could only say it was not let through. And boxes, by label as
 * the page grants them: an agent with no box was never offered the box tools,
 * so it could not even say what it lacked. Catalogs are grants of what to reach
 * rather than of what to call, and stay the page's.
 */
@Service
class GrantRequestTools(
    private val agents: AgentRepository,
    private val workspaceTools: AgentToolRepository,
    private val plugins: PluginRepository,
    private val declarations: PluginDeclarations,
    private val mcpServers: McpServerService,
    /** The workspace's connections, which an agent may ask to be let use. */
    private val connections: io.mszymanski.orknux.connector.connection.WorkspaceConnectionService,
    /** The boxes there are, whose labels an agent may ask to be granted. */
    private val boxes: io.mszymanski.orknux.server.box.Boxes,
    private val summaries: BuiltInToolSummaries,
    private val httpPolicy: io.mszymanski.orknux.server.embedded.HttpToolPolicy,
    private val requests: InteractionRequestRepository,
    private val revisions: ComponentRevisionRecorder,
    private val inbox: SessionInbox,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
    /** Where a conversation keeps the tools it has loaded; see [ToolSearchTools]. */
    private val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
    /** The orknux_* names, which granting Orknux access brings and loading it loads. */
    private val builtIns: BuiltInTools,
    /** A provider: the handler that answers these questions is built on this. */
    private val interactions: ObjectProvider<Interactions>,
    /** Which conversations are a task's, where asking goes through the task instead; see [offered]. */
    private val tasks: io.mszymanski.orknux.server.task.TaskRepository,
    /** The conversation's frame, which takes in a grant made there rather than being made again; see [io.mszymanski.orknux.server.llm.SessionFrames]. */
    private val frames: io.mszymanski.orknux.server.llm.SessionFrames,
) {

    /** What sort of grant a [Grantable] is, which is also how it is granted. */
    enum class Kind(val label: String) {
        BUILT_IN("built-in tool"),
        WORKSPACE_TOOL("workspace tool"),
        PLUGIN_TOOL("plugin tool"),
        MCP_SERVER("MCP server"),
        ORKNUX("Orknux access"),
        CONNECTION("connection"),
        BOX("box"),
    }

    /** One thing an agent could be granted and is not. */
    data class Grantable(
        val name: String,
        val kind: Kind,
        val description: String?,
        val connectionId: Long? = null,
        /** For a [Kind.BOX], the label granted, which [name] is said with a prefix of. */
        val boxLabel: String? = null,
    )

    /** Read once: the built-ins and what the model is told about them are fixed for a release. */
    private val summaryByName: Map<String, String> by lazy { summaries.summaries() }

    /**
     * Whether these are offered in [session]. Not in a task's: a task asks with
     * its own `task_request_permission`, which parks the task, tells whoever
     * asked for it and grants to the task alone. Offered there too, a model
     * asked the person in a conversation nobody is in, the task never stopped,
     * and it waited for an answer that could not come.
     */
    fun offered(agent: Agent, session: Long? = null): Boolean =
        agent.mayAskForTools && (session == null || tasks.findFirstBySessionId(session) == null)

    fun handles(name: String): Boolean = name == LIST || name == ASK

    /**
     * Everything this agent could be granted and is not, read fresh: the row it
     * was handed at the start of a turn may be older than a grant made since.
     */
    fun grantables(given: Agent): List<Grantable> {
        val agent = given.id?.let { agents.findByIdOrNull(it) } ?: given
        return buildList {
            // Built-ins: the ones on by default that it has hidden, and the ones off until asked for.
            BuiltInTools.GRANTED.filter { it in agent.hiddenTools }.forEach {
                add(Grantable(it, Kind.BUILT_IN, summaryByName[it]))
            }
            BuiltInTools.REACHING
                .filter { it !in agent.tools && httpPolicy.offers(it) }
                .forEach { add(Grantable(it, Kind.BUILT_IN, summaryByName[it])) }
            workspaceTools.findByWorkspaceId(agent.workspaceId, Pageable.unpaged()).content
                .filter { it.enabled && it.name !in agent.tools }
                .sortedBy { it.name }
                .forEach { add(Grantable(it.name, Kind.WORKSPACE_TOOL, line(it.summary ?: it.description))) }
            plugins.declared().filter { it.enabled }.flatMap { plugin ->
                declarations.readTools(plugin.declaredTools).map { tool ->
                    Grantable("${plugin.key}_${tool.name}", Kind.PLUGIN_TOOL, line(tool.description))
                }
            }.filter { it.name !in agent.tools }.sortedBy { it.name }.forEach(::add)
            mcpServers.mcpServers(agent.workspaceId)
                .filter { it.name !in agent.mcpServers }
                .sortedBy { it.name }
                .forEach { add(Grantable(it.name, Kind.MCP_SERVER, "The tools the MCP server at ${it.address} offers.")) }
            if (!agent.orknuxAccess) {
                add(Grantable(ORKNUX, Kind.ORKNUX, "The orknux_* tools: reading and building this workspace's workflows, runs and issues."))
            }
            // Asked for by name, which is what a tool's error and a person both say; granted by id, as the page holds it.
            connections.workspaceConnections(agent.workspaceId)
                .filter { it.id !in agent.connections }
                .sortedBy { it.name }
                .forEach {
                    add(Grantable(it.name, Kind.CONNECTION, "The ${it.type.name.lowercase().replace('_', ' ')} connection" +
                        (it.description?.let { said -> ": ${line(said)}" } ?: "."), it.id))
                }
            // A label is the grant, as on the page: one name for every box that carries it, and for one added later.
            val held = boxes.labelsNow(agent)
            boxes.all().filter { it.label != null && it.label !in held }.groupBy { it.label!! }.toSortedMap()
                .forEach { (label, carrying) ->
                    val said = if (label == io.mszymanski.orknux.server.box.LOCAL_BOX_LABEL) {
                        "The local box: a sandbox on this server, whose commands run as the server's own user."
                    } else {
                        "Sandboxes to run commands in, labelled $label: ${carrying.joinToString(", ") { it.name }}."
                    }
                    add(Grantable("$BOX_PREFIX$label", Kind.BOX, said, boxLabel = label))
                }
        }
    }

    fun run(agent: Agent, name: String, arguments: String, sessionId: Long?): String = try {
        // Asked of the switch now, as the box tools ask of their grant.
        val fresh = agent.id?.let { agents.findByIdOrNull(it) } ?: agent
        if (!fresh.mayAskForTools) {
            refuse("This agent may not ask for more tools; a person switched that off on its page")
        } else if (!offered(fresh, sessionId)) {
            refuse("In a task, ask with ${io.mszymanski.orknux.server.task.TaskTools.PERMISSION}, which stops the task until whoever asked for it answers")
        } else when (name) {
            LIST -> listed(fresh)
            ASK -> asked(fresh, arguments, sessionId)
            else -> refuse("There is no tool called $name")
        }
    } catch (refused: Refused) {
        refuse(refused.message)
    }

    private fun listed(agent: Agent): String {
        val found = grantables(agent)
        return mapper.writeValueAsString(
            mapOf(
                "tools" to found.map { mapOf("name" to it.name, "kind" to it.kind.label, "description" to it.description) },
                "note" to if (found.isEmpty()) {
                    "You hold everything this workspace could grant you."
                } else {
                    "None of these is yours yet. To have some, call $ASK with their names and why you need them; " +
                        "a person decides."
                },
            ),
        )
    }

    private fun asked(agent: Agent, arguments: String, sessionId: Long?): String {
        val conversation = sessionId ?: throw Refused("There is no conversation to ask in, so there is nobody to ask")
        val args = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: throw Refused("The arguments were not a JSON object")
        val wanted = args.path(TOOLS).let { node ->
            if (node.isArray) node.values().mapNotNull { if (it.isString) it.stringValue().trim() else null } else emptyList()
        }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) throw Refused("Name the tools you want in $TOOLS - $LIST says which you could be given")
        if (wanted.size > MOST_AT_ONCE) throw Refused("Ask for at most $MOST_AT_ONCE tools at once")
        val why = args.path(WHY).takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }
            ?: throw Refused("Say in $WHY what you need them for - the person decides on that")
        val load = args.path(LOAD).let { it.isBoolean && it.asBoolean() }

        val grantable = grantables(agent).associateBy { it.name }
        val unknown = wanted.filter { it !in grantable }
        if (unknown.isNotEmpty()) {
            throw Refused(
                "${unknown.joinToString(", ")} " + (if (unknown.size == 1) "is" else "are") +
                    " not something you could be granted - either you already hold it, or it does not exist here. " +
                    "$LIST says what you could be given.",
            )
        }
        val asking = wanted.map { grantable.getValue(it) }

        // The same tools asked for again while the first question is open answer the first question.
        val open = requests.findByLlmSessionIdOrderByCreatedAtAscIdAsc(conversation).firstOrNull { request ->
            request.kind == KIND && request.chosen == null && request.subjectId == agent.id &&
                InteractionOptions.ids(request.options).contains(acceptOption(load)) &&
                InteractionItems.decode(request.items).map { it.name }.toSet() == wanted.toSet()
        }
        if (open == null) {
            interactions.getObject().ask(
                workspaceId = agent.workspaceId,
                llmSessionId = conversation,
                kind = KIND,
                subjectId = agent.id,
                askedBy = agent.name,
                question = "${agent.name} asks to be given " +
                    (if (asking.size == 1) "this tool" else "these ${asking.size} tools") + " for good" +
                    (if (load) " and loaded now" else "") + ": $why",
                // Whether accepting also loads them rides on the option, so the answer carries it with no column of its own.
                options = listOf(
                    InteractionOption(acceptOption(load), "Accept", "primary"),
                    InteractionOption(REFUSE, "Refuse", "danger"),
                ),
                items = asking.map { InteractionItem(it.name, it.kind.label, it.description) },
            )
            auditRecorder.recordAutomated(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "${agent.name} asked for tools ${wanted.joinToString(", ")}",
                agent.name,
            )
        }
        return mapper.writeValueAsString(
            mapOf(
                "asked" to wanted,
                "status" to "pending",
                "note" to "The person has been asked in the conversation to accept or refuse. You will be told " +
                    "the answer; until then you do not hold these tools" +
                    (if (load) ", and once accepted they are loaded for you" else "") + ". Tell the person what you " +
                    "asked for and why, then carry on without them or end your turn.",
            ),
        )
    }

    /**
     * Writes the tools of [request] to the agent it is about, for good, and
     * tells the agent. Whatever is no longer grantable - removed since, or
     * granted some other way - is left out and said.
     */
    @Transactional
    fun accept(request: InteractionRequest, by: String, load: Boolean = false): String {
        val agent = request.subjectId?.let { agents.findByIdOrNull(it) }
            ?: throw AgentNotFoundException(request.subjectId ?: 0)
        val wanted = InteractionItems.decode(request.items).map { it.name }
        val grantable = grantables(agent).associateBy { it.name }
        val granting = wanted.mapNotNull { grantable[it] }
        val gone = wanted.filter { it !in grantable }

        if (granting.isNotEmpty()) {
            // What it is about to stop being: a grant is a change to the agent like any save on its page.
            revisions.saved(agent)
            granting.forEach { one ->
                when (one.kind) {
                    Kind.BUILT_IN ->
                        if (BuiltInTools.reaches(one.name)) agent.tools.add(one.name) else agent.hiddenTools.remove(one.name)
                    Kind.WORKSPACE_TOOL, Kind.PLUGIN_TOOL -> agent.tools.add(one.name)
                    Kind.MCP_SERVER -> agent.mcpServers.add(one.name)
                    Kind.ORKNUX -> {
                        agent.orknuxAccess = true
                        // At Always, as a new agent starts with them: each may be set to Offer on its page.
                        builtIns.orknuxNames()
                            .filter { it !in agent.hiddenTools && it !in agent.requiredTools }
                            .forEach { agent.requiredTools.add(it) }
                    }
                    Kind.CONNECTION -> one.connectionId?.let { agent.connections.add(it) }
                    Kind.BOX -> one.boxLabel?.let { agent.boxLabels = agent.boxLabels + it }
                }
            }
            val was = agent.lastModifiedAt
            agent.lastModifiedAt = OffsetDateTime.now()
            agent.lastModifiedBy = by
            agents.save(agent)
            /*
             * Granted from inside the conversation, so the conversation keeps
             * its system turn and tool array: the next turn says what is new
             * and the agent calls it through tool_run, where a new frame would
             * have the provider read the whole conversation again - 167,792
             * tokens, and minutes, in the session that showed it.
             */
            frames.absorb(request.llmSessionId, agent.id, was, agent.lastModifiedAt)
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} granted tools ${granting.joinToString(", ") { it.name }} (asked in chat)",
            )
        }
        val names = granting.joinToString(", ") { it.name }
        /*
         * And loaded into this conversation, where the agent asked for that:
         * the names written where tool_load writes what it finds, so the next
         * round declares them without a load of its own. An MCP server's tools
         * are named by the server and not known here, so those are left to load.
         */
        val loaded = if (load && granting.isNotEmpty()) {
            val loading = granting.flatMap { one ->
                when (one.kind) {
                    // Nothing to load: a connection or a box is what a tool reaches, not a tool.
                    Kind.MCP_SERVER, Kind.CONNECTION, Kind.BOX -> emptyList()
                    Kind.ORKNUX -> builtIns.orknuxNames()
                    else -> listOf(one.name)
                }
            }
            recorder.toolsFound(request.llmSessionId, recorder.toolsFound(request.llmSessionId) + loading)
            granting.filter { it.kind !in NOTHING_TO_LOAD }.map { it.name }
        } else {
            emptyList()
        }
        inbox.post(
            request.llmSessionId,
            SessionEventKind.ANSWER,
            buildString {
                if (granting.isNotEmpty()) {
                    append("$by granted you $names for good; they are yours from your next turn, which says how each is called")
                    append(if (loaded.isEmpty()) "." else ", and ${loaded.joinToString(", ")} " +
                        (if (loaded.size == 1) "is" else "are") + " loaded already.")
                }
                if (gone.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("${gone.joinToString(", ")} could not be granted: you hold it already, or it is gone.")
                }
            },
        )
        return when {
            granting.isEmpty() -> "Nothing left to grant"
            gone.isEmpty() -> "Granted - $names"
            else -> "Granted - $names (not ${gone.joinToString(", ")})"
        }
    }

    /** Says no, and tells the agent, which then knows to carry on without them. */
    @Transactional
    fun refuse(request: InteractionRequest, by: String): String {
        val names = InteractionItems.decode(request.items).joinToString(", ") { it.name }
        inbox.post(
            request.llmSessionId,
            SessionEventKind.ANSWER,
            "$by refused to give you $names. Carry on without them, or tell the person what you cannot do instead.",
        )
        auditRecorder.record(
            request.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Tools $names refused to ${request.askedBy ?: "an agent"}",
        )
        return "Refused"
    }

    private fun refuse(message: String): String = mapper.writeValueAsString(mapOf("error" to message))

    private class Refused(override val message: String) : RuntimeException(message)

    /** A description's first sentence, which is what a line in a list has room for. */
    private fun line(text: String?): String? =
        text?.trim()?.ifEmpty { null }?.let { whole ->
            val end = whole.indexOf(". ")
            (if (end in 1 until FIRST_SENTENCE) whole.substring(0, end + 1) else whole).take(FIRST_SENTENCE)
        }

    companion object {
        const val LIST = "tools_ungranted"
        const val ASK = "tools_request"

        /** The kind of [InteractionRequest] these ask, answered by [ToolGrantRequests]. */
        const val KIND = "agent.tools"
        const val ACCEPT = "accept"
        /** Accept, where the agent asked for the tools to be loaded as well. */
        const val ACCEPT_AND_LOAD = "accept-load"
        const val REFUSE = "refuse"

        /** The request's parameters, named once so no description spells them twice. */
        const val TOOLS = "tools"
        const val WHY = "why"
        const val LOAD = "load"

        fun acceptOption(load: Boolean) = if (load) ACCEPT_AND_LOAD else ACCEPT

        /** What [Kind.ORKNUX] is asked for by. */
        const val ORKNUX = "orknux"

        /** What a [Kind.BOX] is asked for by, before its label: a label may be any word, a tool's name included. */
        const val BOX_PREFIX = "box:"

        private val NOTHING_TO_LOAD = setOf(Kind.MCP_SERVER, Kind.CONNECTION, Kind.BOX)

        /** How many tools one question may carry: a person reads every one before saying yes. */
        private const val MOST_AT_ONCE = 20
        private const val FIRST_SENTENCE = 200

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                name = LIST,
                readOnly = true,
                description = "Lists the tools you could be given and have not been: built-in tools switched off " +
                    "for you, this workspace's tools, plugins' tools, MCP servers, Orknux access, the " +
                    "workspace's connections a tool reaches a service through, and boxes - sandboxes to run " +
                    "commands in - each with its kind and what it does. Use it when a task needs something you do " +
                    "not hold, a tool says it may not use a connection, or you need a box to run code in, before " +
                    "telling the person you cannot do it; then ask for what you need with $ASK.",
                summary = "list the tools you could be given and do not hold",
            ),
            ToolSpec(
                name = ASK,
                description = "Asks the person, in this conversation, to give you tools for good - names from " +
                    "$LIST, and why you need them. They are shown each tool and choose Accept or Refuse; nothing " +
                    "is granted until they accept. The answer reaches you later, so after asking tell the person " +
                    "what you asked for and why, and carry on without the tools or end your turn. Ask only for " +
                    "what the task in front of you needs. With $LOAD true, accepted tools are also loaded for you, " +
                    "as ${ToolSearchTools.FIND} would, so you can call them without loading them yourself.",
                parameters = listOf(
                    ToolParameterSpec(
                        name = TOOLS,
                        description = "The names to ask for, exactly as $LIST gives them.",
                        required = true,
                        schema = mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    ),
                    ToolParameterSpec(
                        name = WHY,
                        description = "One sentence on what you need them for, which the person reads before deciding.",
                        required = true,
                    ),
                    ToolParameterSpec(
                        name = LOAD,
                        description = "Whether accepted tools are also loaded into this conversation at once. False if left out.",
                        schema = mapOf("type" to "boolean"),
                    ),
                ),
                summary = "ask the person to give you tools you do not hold",
            ),
        )
    }
}

/** The questions [GrantRequestTools] asks: may this agent have these tools. */
@Component
class ToolGrantRequests(private val grants: GrantRequestTools) : InteractionHandler {
    override val kind = GrantRequestTools.KIND

    override fun answered(request: InteractionRequest, option: String, by: String): String = when (option) {
        GrantRequestTools.ACCEPT -> grants.accept(request, by)
        GrantRequestTools.ACCEPT_AND_LOAD -> grants.accept(request, by, load = true)
        else -> grants.refuse(request, by)
    }
}
