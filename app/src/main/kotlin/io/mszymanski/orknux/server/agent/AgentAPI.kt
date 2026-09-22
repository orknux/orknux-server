package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.llm.CHARS_PER_TOKEN
import io.mszymanski.orknux.server.llm.ResolvedMemoryBudget
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.attachment.ChatRoundsOutOfRangeException
import io.mszymanski.orknux.server.attachment.MAX_CHAT_ROUNDS
import io.mszymanski.orknux.server.attachment.MIN_CHAT_ROUNDS
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import io.mszymanski.orknux.server.workspace.sortBy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.core.context.SecurityContextHolder
import java.time.OffsetDateTime

@Controller
class AgentAPI(
    private val agents: AgentRepository,
    private val workspaces: WorkspaceRepository,
    private val dependants: ComponentDependants,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val models: ModelService,
    private val revisions: ComponentRevisionRecorder,
    private val budgets: SessionMemoryBudgets,
    private val connections: WorkspaceConnectionService,
) {

    /** The agent, with what its model is called: the screen shows the name. */
    private fun describe(agent: Agent) = AgentView(agent, agent.modelId?.let { models.model(it)?.name })

    /**
     * What this agent's memory share works out to against the model it uses.
     *
     * A field of its own rather than part of [describe], so the list of a
     * workspace's agents does not resolve a model and a budget per row to fill
     * in something only the settings screen asks for.
     *
     * Its own share where it has one and its workspace's default where it does
     * not, which is why the answer can carry a share while `memoryShare` beside
     * it is null: `inherited` on the budget says which of the two it is.
     */
    @SchemaMapping(typeName = "Agent", field = "memoryBudget")
    fun memoryBudget(agent: AgentView): SessionMemoryBudgetView =
        SessionMemoryBudgetView(budgets.resolve(agent.memoryShare, agent.workspaceId, agent.modelId))

    /**
     * What a share would work out to, before anybody saves it.
     *
     * Asked of a workspace and a model rather than of an agent, because the
     * screen setting this has an unsaved form in front of it: the model may
     * have been changed in the same edit, and a preview that read the stored
     * agent would answer for the model it used to have.
     *
     * It never fails on a share that cannot work - it reports the refusal that
     * saving would raise, in the same words, so the slider can say why while it
     * is being dragged instead of only once Save has been pressed. The mutation
     * is what actually refuses, from the same calculation.
     *
     * The workspace settings form asks the same question about the same
     * setting, so it asks it here rather than through a query of its own - with
     * [workspaceDefault] set, because the two questions differ in what may be
     * refused. A share being set as the workspace's default is not tied to one
     * model and is judged on the bounds alone; the reasoning is on
     * `SessionMemoryBudgets.resolveDefault`. A form wanting to show what that
     * default would mean against one particular model asks this same query
     * again without the flag and with that model, which is exactly the
     * per-agent question and comes back with the per-agent answer.
     */
    @QueryMapping
    fun memoryBudget(
        @Argument workspaceId: Long,
        @Argument modelId: Long?,
        @Argument share: Int?,
        @Argument workspaceDefault: Boolean?,
    ): SessionMemoryBudgetView {
        requireWorkspaceAccess(workspaceId)
        if (workspaceDefault == true) return SessionMemoryBudgetView(budgets.resolveDefault(share))
        val model = modelId?.takeIf { models.model(it)?.workspaceId == workspaceId }
        return SessionMemoryBudgetView(budgets.resolve(share, workspaceId, model))
    }

    /**
     * The columns this list can be put in the order of. Issue #358.
     *
     * Status is whether the agent is switched on, which is what the column
     * draws; nothing else on the row is stored anywhere else.
     */
    private val AGENT_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "DESCRIPTION" to listOf("description", "name"),
        "STATUS" to listOf("enabled", "name"),
    )

    @QueryMapping
    /** @param search what to look for in the name and the description, or null for all of them. */
    fun workspaceAgents(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): AgentPage {
        requireWorkspaceAccess(workspaceId)
        val pageable = pageRequest(page, size, sortBy(order, ascending, AGENT_ORDERS, "NAME"))
        val looking = search?.trim().orEmpty()

        return AgentPage(
            if (looking.isEmpty()) {
                agents.findByWorkspaceId(workspaceId, pageable)
            } else {
                agents.searching(workspaceId, looking, pageable)
            },
            ::describe,
        )
    }

    @QueryMapping
    fun agent(@Argument id: Long): AgentView? {
        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(agent)
    }

    @MutationMapping
    @Transactional
    fun createAgent(@Argument input: CreateAgentInput): AgentView {
        val name = input.name.trim()
        if (name.isEmpty()) throw AgentNameInvalidException()
        requireWorkspaceAccess(input.workspaceId)
        if (agents.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw AgentNameTakenException(name)

        val agent = agents.save(
            Agent(
                workspaceId = input.workspaceId,
                name = name,
                type = input.type,
                description = input.description?.trim()?.ifEmpty { null },
                systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null },
                icon = input.icon?.trim()?.ifEmpty { null },
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.AGENT, "Agent $name created")
        return describe(agent)
    }

    /**
     * Makes an agent out of a model, in one press, from the Models screen.
     *
     * The other half of issue #295. Taking the bare model away closed the short
     * path somebody had for finding out whether a model they had just added
     * actually works, and what was left in its place was: build an agent by
     * hand, name it, choose its model, save it, chat to it, delete it. This is
     * that path, one press long, and it leaves behind a real agent rather than
     * something to throw away.
     *
     * **What it is granted: nothing.** No tools, no skills, no MCP servers, no
     * catalogues, no shell, no orknux access. Granting is a deliberate act - it
     * is what an agent's whole settings page is for - and an action that quietly
     * handed out capabilities because it was convenient would be the worst
     * possible place in this product to be generous. What comes back is a bare
     * agent, which is a thing you then dress. It is not a bare *model*: it has a
     * name, a page, a system prompt it can be given, memory, and somewhere for
     * every grant to go, and those are the whole of the difference.
     *
     * **What it is called: the model's own name**, and where that is taken, the
     * same with a number after it. Derived rather than asked for, because asking
     * would be a dialog and a dialog is the thing this replaces; the name is on
     * the agent's page to be changed the moment it is wrong. The retry is not
     * decoration - `uk_agent_workspace_name` is a unique constraint and pressing
     * this twice on the same model is the obvious thing to do.
     *
     * **Where it lands** is the agent's page, and that is the caller's to do
     * with what comes back. Not a chat: this would then open a conversation on
     * every press, and a sidebar filling with untitled chats because somebody
     * was checking their models is a worse answer than one more click. The page
     * is also where the derived name and the empty grants are visible, which is
     * what somebody who has just pressed an unfamiliar button wants to see.
     */
    @MutationMapping
    @Transactional
    fun createAgentForModel(@Argument modelId: Long): AgentView {
        val model = models.model(modelId) ?: throw AgentModelUnusableException("That model no longer exists")
        requireWorkspaceAccess(model.workspaceId)
        // The same sentence a task refuses a transcription model with. An agent
        // whose model cannot hold a conversation is an agent that cannot answer,
        // and finding that out on the first message is finding it out too late.
        if (model.kind != ModelKind.CHAT) {
            throw AgentModelUnusableException("${model.name} does not answer questions")
        }

        val agent = agents.save(
            Agent(
                workspaceId = model.workspaceId,
                name = available(model.workspaceId, model.name),
                type = AgentType.LLM,
                modelId = model.id,
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(model.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} created")
        return describe(agent)
    }

    /**
     * [wanted], or the first "[wanted] n" nobody has taken.
     *
     * Read rather than caught. A `DataIntegrityViolationException` from the
     * unique constraint arrives with the transaction already marked for
     * rollback, so catching it to try again inside one is catching something
     * that cannot be recovered from here - the retry has to happen before the
     * insert, not after it. Two people pressing at the same instant still race,
     * and the loser gets the constraint's refusal, which is the right answer for
     * a collision this cannot see.
     *
     * Bounded, because an unbounded loop against a database is a way to hang a
     * request. A hundred agents named after one model is somebody's script, not
     * somebody's afternoon, and it is told so.
     */
    private fun available(workspaceId: Long, wanted: String): String {
        val taken = agents.findByWorkspaceId(workspaceId, Sort.by("name")).map { it.name }.toSet()
        if (wanted !in taken) return wanted
        for (n in 2..MOST_OF_ONE_NAME) {
            val tried = "$wanted $n"
            if (tried !in taken) return tried
        }
        throw AgentNameTakenException(wanted)
    }

    /** Backs the agent settings form. */
    @MutationMapping
    @Transactional
    fun updateAgent(@Argument id: Long, @Argument input: UpdateAgentInput): AgentView {
        val name = input.name.trim()
        if (name.isEmpty()) throw AgentNameInvalidException()

        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw AgentNotFoundException(id)
        if (name != agent.name && agents.findByWorkspaceIdAndName(agent.workspaceId, name) != null) {
            throw AgentNameTakenException(name)
        }

        // What it is about to stop being. An agent has no draft, so a save is a
        // version; the recorder holds that rule, this door only reports.
        revisions.saved(agent)

        val previousName = agent.name
        val previousDescription = agent.description
        val previousPrompt = agent.systemPrompt
        val previousServers = agent.mcpServers.toList()
        val previousOrknux = agent.orknuxAccess
        val previousShell = agent.shellAccess
        val previousCatalogs = agent.memoryCatalogs.toList()
        val previousSkillCatalogs = agent.skillCatalogs.toList()
        val previousTools = agent.tools.toList()
        val previousConnections = agent.connections.toList()
        val previousShare = agent.memoryShare

        agent.name = name
        agent.description = input.description?.trim()?.ifEmpty { null }
        agent.systemPrompt = input.systemPrompt?.trim()?.ifEmpty { null }
        // Sent whenever the form saves, so null is "no icon" rather than "not
        // mentioned" — which is what lets Clear clear it.
        agent.icon = input.icon?.trim()?.ifEmpty { null }
        if (input.type != null) agent.type = input.type
        // A model from another workspace is not this agent's to use.
        val previousModel = agent.modelId
        agent.modelId = input.modelId?.let {
            val model = models.model(it) ?: throw AgentModelUnusableException("That model no longer exists")
            if (model.workspaceId != agent.workspaceId) {
                throw AgentModelUnusableException("That model belongs to another workspace")
            }
            model.id
        }

        /*
         * Refused here rather than found out at the provider.
         *
         * Raising this is not free and not obviously bounded: a share too large
         * for the window is a request the provider rejects, on somebody's turn,
         * after the money for the tokens that did fit has been spent. The
         * refusal needs the model, so it is judged after the model is set and
         * against the one being saved rather than the one that was there.
         *
         * Sent whenever the form saves, so null is "the default" rather than
         * "not mentioned" - the same rule the icon follows, and what lets the
         * screen put it back to the default it started on.
         */
        agent.memoryShare = input.memoryShare
        if (input.memoryShare != null) {
            budgets.resolve(input.memoryShare, agent.workspaceId, agent.modelId).refusal
                ?.let { throw AgentMemoryShareUnusableException(it) }
        }

        /*
         * The same rule for the rounds: sent on every save, so null is "follow
         * the installation" and a number is this agent's own. Refused outside
         * the bounds the installation setting uses, because an agent talking to
         * itself for ever is the thing the ceiling exists to stop and a number
         * below two is an agent that cannot use the tools it was given.
         */
        val roundsWere = agent.maxRounds
        input.maxRounds?.let {
            if (it !in MIN_CHAT_ROUNDS..MAX_CHAT_ROUNDS) throw ChatRoundsOutOfRangeException(it)
        }
        agent.maxRounds = input.maxRounds

        if (input.mcpServers != null) {
            // Keep the given order, dropping blanks and repeats.
            agent.mcpServers = input.mcpServers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.orknuxAccess != null) agent.orknuxAccess = input.orknuxAccess
        if (input.shellAccess != null) agent.shellAccess = input.shellAccess
        if (input.artifactAccess != null) agent.artifactAccess = input.artifactAccess
        if (input.finishAccess != null) agent.finishAccess = input.finishAccess
        if (input.pictureLinkAccess != null) agent.pictureLinkAccess = input.pictureLinkAccess
        if (input.memoryCatalogs != null) {
            agent.memoryCatalogs =
                input.memoryCatalogs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.skillCatalogs != null) {
            agent.skillCatalogs =
                input.skillCatalogs.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.tools != null) {
            agent.tools = input.tools.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        }
        if (input.connectionIds != null) {
            // Another workspace's connection is not this agent's to be granted,
            // so the id is checked here rather than trusted into the briefing.
            agent.connections = input.connectionIds.distinct().onEach { id ->
                connections.workspaceConnection(id)?.takeIf { it.workspaceId == agent.workspaceId }
                    ?: throw AgentConnectionUnusableException(id)
            }.toMutableList()
        }
        agent.lastModifiedAt = OffsetDateTime.now()
        agent.lastModifiedBy = currentUser()

        recordChanges(
            agent,
            previousName,
            previousDescription,
            previousPrompt,
            previousServers,
            previousOrknux,
            previousShell,
        )
        if (agent.modelId != previousModel) {
            val named = agent.modelId?.let { models.model(it)?.name }
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (named == null) "Agent ${agent.name} model cleared" else "Agent ${agent.name} model set to $named",
            )
        }
        /*
         * Its own entry, because it changes what every turn costs.
         *
         * A share raised is more of the window bought on every request this
         * agent makes from then on, and a bill that grew is a question somebody
         * asks the audit log rather than the agent.
         */
        if (agent.maxRounds != roundsWere) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                agent.maxRounds?.let { "Agent ${agent.name} given $it rounds of tool calls" }
                    ?: "Agent ${agent.name} rounds reset to the installation's",
            )
        }
        if (agent.memoryShare != previousShare) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                agent.memoryShare?.let { "Agent ${agent.name} memory set to $it% of its model's context window" }
                    ?: "Agent ${agent.name} memory reset to the default",
            )
        }
        // A grant is worth an entry of its own: it changes what an agent can read.
        (agent.memoryCatalogs - previousCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given memory catalog $catalog",
            )
        }
        (previousCatalogs - agent.memoryCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} no longer reads memory catalog $catalog",
            )
        }
        (agent.skillCatalogs - previousSkillCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given skill catalog $catalog",
            )
        }
        (previousSkillCatalogs - agent.skillCatalogs.toSet()).forEach { catalog ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} no longer draws on skill catalog $catalog",
            )
        }
        // Worth its own entry above all the others: this one changes what an
        // agent can do, not just what it can read.
        (agent.tools - previousTools.toSet()).forEach { tool ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given tool $tool",
            )
        }
        (previousTools - agent.tools.toSet()).forEach { tool ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} can no longer call tool $tool",
            )
        }
        // By name where the connection still answers to its id, because a log
        // line saying "connection 9" answers nobody's question.
        (agent.connections - previousConnections.toSet()).forEach { granted ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} given connection ${connections.workspaceConnection(granted)?.name ?: granted}",
            )
        }
        (previousConnections - agent.connections.toSet()).forEach { taken ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Agent ${agent.name} no longer holds connection ${connections.workspaceConnection(taken)?.name ?: taken}",
            )
        }
        return describe(agent)
    }

    @MutationMapping
    @Transactional
    fun setAgentEnabled(@Argument id: Long, @Argument enabled: Boolean): AgentView {
        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw AgentNotFoundException(id)
        // The toggle is a save: it changes what the workspace has.
        revisions.saved(agent)
        agent.enabled = enabled
        agent.lastModifiedAt = OffsetDateTime.now()
        agent.lastModifiedBy = currentUser()
        auditRecorder.record(
            agent.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Agent ${agent.name} ${if (enabled) "enabled" else "disabled"}",
        )
        return describe(agent)
    }

    @MutationMapping
    @Transactional
    /**
     * Refused while a workflow node instances it.
     *
     * The same rule a condition follows, and for the same reason: the node
     * would be left pointing at nothing, and a run reaching it could only report
     * that the agent it was supposed to ask is gone. Better to say which
     * workflows are using it while there is still something to change.
     *
     * This paragraph described a guard that was not here. [AgentInUseException]
     * existed, unthrown; `findByAgentId` existed, with a comment saying it was
     * what kept an agent from being deleted from under a node, and nothing
     * called it. So the sentence is now true, and it covers the published copy
     * as well as the drawn one - that is the half a node taken off the canvas
     * cannot reach.
     */
    fun deleteAgent(@Argument id: Long): Boolean {
        val agent = agents.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val users = dependants.of(DependencyKind.AGENT, id)
        if (users.isNotEmpty()) throw AgentInUseException(agent.name, users.phrases())

        agents.delete(agent)
        auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} deleted")
        return true
    }

    /** One entry per thing that actually changed, worded as the audit view shows it. */
    private fun recordChanges(
        agent: Agent,
        previousName: String,
        previousDescription: String?,
        previousPrompt: String?,
        previousServers: List<String>,
        previousOrknux: Boolean,
        previousShell: Boolean,
    ) {
        if (agent.name != previousName) {
            auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent $previousName renamed to ${agent.name}")
        }
        if (agent.description != previousDescription) {
            auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} description updated")
        }
        if (agent.systemPrompt != previousPrompt) {
            auditRecorder.record(agent.workspaceId, WorkspaceAuditCategory.AGENT, "Agent ${agent.name} system prompt updated")
        }
        (agent.mcpServers - previousServers.toSet()).forEach { server ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server $server added to ${agent.name}",
            )
        }
        (previousServers - agent.mcpServers.toSet()).forEach { server ->
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server $server removed from ${agent.name}",
            )
        }
        /*
         * The widest grant on this screen, and the only one that reaches outside
         * the application at all. Its own line for that reason: somebody reading
         * the log later needs to know when an agent first became able to run
         * commands on a machine, not merely that its settings were saved.
         */
        if (agent.shellAccess != previousShell) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (agent.shellAccess) {
                    "Shell access granted to ${agent.name}"
                } else {
                    "Shell access withdrawn from ${agent.name}"
                },
            )
        }
        // Worth a line of its own too: this is the grant that lets an agent start
        // workflows, which is the widest thing an agent can be given inside.
        if (agent.orknuxAccess != previousOrknux) {
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                if (agent.orknuxAccess) {
                    "Orknux access granted to ${agent.name}"
                } else {
                    "Orknux access withdrawn from ${agent.name}"
                },
            )
        }
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /** Whoever is asking, for the stamp a revision of this state will carry. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {

        /** How many agents may be named after one model before it is somebody's script. */
        const val MOST_OF_ONE_NAME = 100
    }
}

data class CreateAgentInput(
    val workspaceId: Long,
    val name: String,
    val type: AgentType,
    val description: String? = null,
    val systemPrompt: String? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
)

data class UpdateAgentInput(
    val name: String,
    val description: String? = null,
    val systemPrompt: String? = null,
    val type: AgentType? = null,
    /** Null clears the model, the way the form sends an unchosen select. */
    val modelId: Long? = null,
    /** Null leaves the current list alone; an empty list clears it. */
    val mcpServers: List<String>? = null,
    /** Whether it may ask orknux about orknux; null leaves the grant alone. */
    val orknuxAccess: Boolean? = null,
    /** Whether it may open a shell on a machine; null leaves the grant alone. */
    val shellAccess: Boolean? = null,
    /** Whether it may keep a file it made; on by default, see [Agent.artifactAccess]. */
    val artifactAccess: Boolean? = null,
    /** Whether it may end its turn by saying so; on by default, see [Agent.finishAccess]. */
    val finishAccess: Boolean? = null,
    /** Whether it may ask for a picture's address; on by default, see [Agent.pictureLinkAccess]. */
    val pictureLinkAccess: Boolean? = null,
    /** Same rule: null leaves it alone, an empty list clears it. */
    val memoryCatalogs: List<String>? = null,
    /** Which skill catalogs it may draw on; null leaves the grant alone. */
    val skillCatalogs: List<String>? = null,
    /** Which of the workspace's tools it may call; null leaves the grant alone. */
    val tools: List<String>? = null,
    /** Which of the workspace's connections it may name; null leaves the grant alone. */
    val connectionIds: List<Long>? = null,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String? = null,
    /**
     * How much of its model's context window a session may take back, as a
     * percentage.
     *
     * Sent whenever the form saves, so null is "whatever the workspace says"
     * rather than "leave it alone" - the icon's rule, and what lets the screen
     * put it back.
     */
    val memoryShare: Int? = null,

    /**
     * How many rounds of tool calls this agent gets before it must answer.
     *
     * Sent whenever the form saves, like the share above, so null is "whatever
     * the installation says" rather than "leave it alone".
     */
    val maxRounds: Int? = null,
)

data class AgentView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: AgentType,
    val description: String?,
    val systemPrompt: String?,
    val enabled: Boolean,
    val modelId: Long?,
    /** Null when the model it named has been removed. */
    val modelName: String?,
    val mcpServers: List<String>,
    /** Whether it may ask orknux about orknux. */
    val orknuxAccess: Boolean,
    /** Whether it may open a shell on one of the installation's machines. */
    val shellAccess: Boolean,
    val artifactAccess: Boolean,
    /** Whether it may end its turn by saying so rather than by writing prose. */
    val finishAccess: Boolean,
    /** Whether it may ask for an address for a picture it drew. */
    val pictureLinkAccess: Boolean,
    val memoryCatalogs: List<String>,
    val skillCatalogs: List<String>,
    val tools: List<String>,
    /** Which of the workspace's connections it may name when a tool takes one. */
    val connectionIds: List<Long>,
    /** Which icon a node drawn from this starts with; null draws the kind's own. */
    val icon: String?,
    /** Its own share of the model's window; null follows the workspace default. */
    val memoryShare: Int?,
    /** Its own ceiling on tool rounds; null follows the installation's. */
    val maxRounds: Int?,
) {
    constructor(agent: Agent, modelName: String? = null) : this(
        id = requireNotNull(agent.id),
        workspaceId = agent.workspaceId,
        name = agent.name,
        type = agent.type,
        description = agent.description,
        systemPrompt = agent.systemPrompt,
        enabled = agent.enabled,
        modelId = agent.modelId,
        modelName = modelName,
        mcpServers = agent.mcpServers.toList(),
        orknuxAccess = agent.orknuxAccess,
        shellAccess = agent.shellAccess,
        artifactAccess = agent.artifactAccess,
        finishAccess = agent.finishAccess,
        pictureLinkAccess = agent.pictureLinkAccess,
        memoryCatalogs = agent.memoryCatalogs.toList(),
        skillCatalogs = agent.skillCatalogs.toList(),
        tools = agent.tools.toList(),
        connectionIds = agent.connections.toList(),
        icon = agent.icon,
        memoryShare = agent.memoryShare,
        maxRounds = agent.maxRounds,
    )
}

/**
 * A memory budget as the API reports it: in tokens, never in characters.
 *
 * The counts are characters everywhere inside, because that is what the
 * recorder can count and what every model agrees on. They are converted here
 * and nowhere else. Whoever sets this is looking at a context window measured
 * in tokens and will read any number beside it as tokens whatever the label
 * says, so a surface that reported characters would be read wrong by a factor
 * of four every time - and being wrong in that direction means asking for four
 * times the memory and paying for it.
 *
 * It is an approximation and says so in the schema. There is no tokeniser here:
 * it would have to be the provider's, it would differ per model, and it would
 * be run over a whole session on every turn to answer a question that only
 * decides where to cut.
 */
data class SessionMemoryBudgetView(
    val share: Int?,
    val contextWindow: Int?,
    val derived: Boolean,
    /** True when [share] is the workspace's default rather than the agent's own. */
    val inherited: Boolean,
    val totalTokens: Int,
    val conversationTokens: Int,
    val toolResultTokens: Int,
    val longestResultTokens: Int,
    val turns: Int,
    val toolResults: Int,
    val refusal: String?,
) {
    constructor(resolved: ResolvedMemoryBudget) : this(
        share = resolved.share,
        contextWindow = resolved.contextWindow,
        derived = resolved.derived,
        inherited = resolved.inherited,
        totalTokens = resolved.budget.totalChars / CHARS_PER_TOKEN,
        conversationTokens = resolved.budget.memoryChars / CHARS_PER_TOKEN,
        toolResultTokens = resolved.budget.recallChars / CHARS_PER_TOKEN,
        longestResultTokens = resolved.budget.longestResult / CHARS_PER_TOKEN,
        turns = resolved.budget.turns,
        toolResults = resolved.budget.results,
        refusal = resolved.refusal,
    )
}

data class AgentPage(
    val content: List<AgentView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Agent>, describe: (Agent) -> AgentView = ::AgentView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

class AgentInUseException(val name: String, val nodes: List<String>) : RuntimeException(
    "$name is used by ${nodes.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "nodes" to nodes)
}

class AgentNotFoundException(val id: Long) : RuntimeException("No agent with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class AgentNameTakenException(val name: String) :
    RuntimeException("An agent named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class AgentNameInvalidException : RuntimeException("An agent name is required")

/** A model chosen for an agent has to be one this workspace can reach. */
class AgentModelUnusableException(message: String) : RuntimeException(message)

/** A connection grant naming something that is not this workspace's connection. */
class AgentConnectionUnusableException(id: Long) : RuntimeException(
    "Connection $id is not one of this workspace's connections, so this agent cannot be granted it.",
)

/**
 * A share of a context window that could not work, refused where it was set.
 *
 * Carries the sentence `SessionMemoryBudgets` wrote, which names the model, its
 * window and what it reserves for its answer - because "too large" tells nobody
 * what would fit.
 */
class AgentMemoryShareUnusableException(message: String) : RuntimeException(message)

