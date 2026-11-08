package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/** How a tool given to a temporary agent is held by it: the three visibilities a tool row has past Hide. */
enum class TemporaryToolVisibility { ALWAYS, OFFER, DELEGATE }

/** One tool [TemporaryAgents.create] is asked to give, and how. */
data class TemporaryTool(val name: String, val visibility: TemporaryToolVisibility)

/**
 * Agents an agent makes inside one conversation, for that conversation.
 *
 * An agent allowed to - one with [Agent.creatableModelIds] - can make a
 * specialist for the job in front of it rather than asking for one to be
 * configured: a name, instructions, one of the models it was allowed, and tools
 * picked from its own. What it makes is a real [Agent] row flagged
 * [Agent.temporary], so it is asked through `ask_agent` exactly as a configured
 * agent is, and its conversations are recorded and read like any other.
 *
 * Four rules hold it in, and they are the whole of the design.
 *
 * - **It inherits nothing.** No catalogs, connections, MCP servers, boxes or
 *   Orknux access beyond the orknux_* tools named; only the tools it was given,
 *   and the built-ins it was not given are hidden. A creator that wants it to
 *   know something says so in the instructions.
 * - **Only what the creator holds.** A tool the creator holds - in its grant,
 *   among its built-ins, or at Delegate, held only to be handed on - may be
 *   given; anything else is refused by name. Delegate is what makes this
 *   useful: a person can give an orchestrator a tool it must never call itself
 *   but may put in a specialist's hands.
 * - **No nesting.** A temporary agent is given no [Agent.creatableModelIds], so
 *   it cannot create one in turn, whatever tools it holds; and an asked agent
 *   is asked with no agents of its own, so it cannot reach one either.
 * - **It does not outlive its conversation.** At most
 *   [Tunable.TEMPORARY_AGENTS_PER_CONVERSATION] per conversation; removed by
 *   `agent_dismiss`, or by [sweep] once the conversation has been idle for
 *   [Tunable.TEMPORARY_AGENTS_IDLE_HOURS].
 */
@Service
class TemporaryAgents(
    private val agents: AgentRepository,
    private val models: ModelService,
    private val sessions: LlmSessionRepository,
    private val builtIns: BuiltInTools,
    private val audit: WorkspaceAuditRecorder,
    private val tuning: Tuning,
) {

    /**
     * Every tool name [creator] may hand on: its grant, the built-ins it has not
     * hidden, the orknux_* tools while it has the access, and what it holds at
     * Delegate. Not the tools that come with a grant the new agent will not
     * have - a box, a memory catalog - since handing the name on would hand on
     * nothing that runs.
     */
    fun holds(creator: Agent): Set<String> =
        creator.tools.toSet() + creator.requiredTools + creator.delegatedTools + BuiltInTools.grantedTo(creator) +
            (if (creator.orknuxAccess) builtIns.orknuxNames().filter { it !in creator.hiddenTools } else emptyList())

    /** The models [creator] may make agents with, with their names, in the order it was given them. */
    fun creatableModels(creator: Agent): List<Pair<Long, String>> =
        creator.creatableModelIds.mapNotNull { id -> models.model(id)?.let { id to it.name } }

    /** The temporary agents an agent in [session] created, oldest first; not a goal's reviewer, which nobody chose to make. */
    fun of(session: Long): List<Agent> = agents.findTemporaryBySession(session).filter { it.createdByAgentId != null }

    /**
     * Makes one, or throws [TemporaryAgentRefusedException] saying why not -
     * which the tool hands the model as it is.
     */
    @Transactional
    fun create(
        creator: Agent,
        session: Long,
        name: String,
        instructions: String,
        modelId: Long?,
        tools: List<TemporaryTool>?,
        description: String? = null,
    ): Agent {
        if (creator.temporary) refuse("A temporary agent cannot create agents.")
        val allowed = creatableModels(creator)
        if (allowed.isEmpty()) refuse("You have not been allowed to create agents.")
        val said = name.trim()
        if (said.isEmpty()) refuse("Give the agent a name.")
        if (said.length > NAME_LENGTH) refuse("A name is at most $NAME_LENGTH characters.")
        if (instructions.isBlank()) refuse("Say what the agent is for in instructions: it knows nothing else.")
        // A word or two is a stand-in, not a prompt: "placeholder" made an agent that had to be dismissed and made again.
        if (instructions.trim().length < MIN_INSTRUCTIONS) {
            refuse(
                "Its instructions are all it will know, and \"${instructions.trim()}\" says nothing of what it is for. " +
                    "Write them in full - what it does, how, and what it answers with; nothing was created.",
            )
        }
        val modelsSaid = allowed.joinToString(", ") { "${it.second} (${it.first})" }
        val model = modelId?.takeIf { id -> allowed.any { it.first == id } }?.let(models::model)
            ?: refuse(
                (if (modelId == null) "Say which model it thinks with." else "Model $modelId is not one you may create agents with.") +
                    " You may use: $modelsSaid.",
            )
        if (model.kind != ModelKind.CHAT) refuse("${model.name} does not answer questions, so an agent cannot think with it.")

        val cap = tuning[Tunable.TEMPORARY_AGENTS_PER_CONVERSATION]
        val made = of(session)
        if (made.size >= cap) {
            refuse(
                "This conversation already has ${made.size} agents you created, which is all it may have " +
                    "($cap). Dismiss one with agent_dismiss first, or ask one of them: " +
                    made.joinToString(", ") { it.name } + ".",
            )
        }
        /*
         * A name taken by an agent of the workspace, or by one this conversation made already, is not refused but
         * given a number, and the result says so: a refusal cost a round, and a model guessing at the next name often
         * guessed another taken one.
         */
        // Taken where an agent of the workspace has it, or one this conversation already made; another
        // conversation's temporary agents are theirs, and share names with this one's freely.
        fun taken(n: String) = agents.findByWorkspaceIdAndName(creator.workspaceId, n) != null || agents.findTemporaryNamed(session, n) != null
        val free = if (!taken(said)) said
        else generateSequence { "${said.take(NAME_LENGTH - 5)}-${(1000..9999).random()}" }.take(50).firstOrNull { !taken(it) }
            ?: refuse("No free name like \"$said\" was found. Call agent_create again with another name; nothing was created.")

        val held = holds(creator)
        // No tools said at all is the parameter forgotten, not an agent meant to answer from what it knows: a model
        // that left it out made blind agents twice, asked them, and read "tools: []" as a fault of the server's.
        // An empty list says none on purpose.
        // Its own tools it can see in its array; the ones it holds only to hand on it cannot, so those are named.
        val said0 = tools ?: refuse(
            "Say which tools it gets: tools, a list of {name, visibility} - any tool of yours" +
                (if (creator.delegatedTools.isEmpty()) "" else ", and the ones you hold only to hand on: " + creator.delegatedTools.sorted().joinToString(", ")) +
                " - or tools: [] for none, so it answers from what it knows alone. Nothing was created.",
        )
        val given = said0.distinctBy { it.name.trim() }.map { it.copy(name = it.name.trim()) }.filter { it.name.isNotEmpty() }
        val missing = given.map { it.name }.filterNot { it in held }
        if (missing.isNotEmpty()) {
            refuse(
                "You do not hold " + missing.joinToString(", ") + ", so you cannot give " +
                    (if (missing.size == 1) "it" else "them") + ". You may give only tools you hold yourself, " +
                    "including the ones you hold to hand on.",
            )
        }

        val orknuxNames = builtIns.orknuxNames().toSet()
        val using = given.filter { it.visibility != TemporaryToolVisibility.DELEGATE }.map { it.name }
        val orknux = using.any { it in orknuxNames }
        val agent = agents.save(
            Agent(
                workspaceId = creator.workspaceId,
                name = free,
                type = AgentType.LLM,
                description = description?.trim()?.ifEmpty { null }?.take(DESCRIPTION_LENGTH),
                systemPrompt = instructions.trim(),
                modelId = model.id,
                icon = "bot",
                // What it calls by name: the workspace's and the plugins' tools, and the built-ins that reach out.
                tools = using.filter { (!BuiltInTools.switchable(it) || BuiltInTools.reaches(it)) && it !in orknuxNames }
                    .toMutableList(),
                requiredTools = given.filter { it.visibility == TemporaryToolVisibility.ALWAYS }.map { it.name }.toMutableList(),
                // Every built-in it was not given, and every orknux_* tool it was not: it inherits nothing.
                hiddenTools = (BuiltInTools.hiddenBy(using) + orknuxNames.filterNot { it in using }).toMutableList(),
                delegatedTools = given.filter { it.visibility == TemporaryToolVisibility.DELEGATE }.map { it.name }
                    .toMutableList(),
                orknuxAccess = orknux,
                mayAskForTools = false,
                // None: the rule that makes nesting impossible.
                creatableModelIds = mutableListOf(),
                temporary = true,
                createdBySessionId = session,
                createdByAgentId = creator.id,
                lastModifiedBy = creator.name,
            ),
        )
        audit.recordAutomated(
            creator.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Temporary agent ${agent.name} created by ${creator.name}",
            creator.name,
        )
        return agent
    }

    /** Removes one [session] created, by name however typed, or throws saying which there are. */
    @Transactional
    fun dismiss(creator: Agent, session: Long, name: String): Agent {
        val made = of(session)
        val found = made.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: refuse(
                "You have not created an agent called \"${name.trim()}\" in this conversation." +
                    (if (made.isEmpty()) " You have created none." else " You created: " + made.joinToString(", ") { it.name } + "."),
            )
        agents.delete(found)
        audit.recordAutomated(
            found.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Temporary agent ${found.name} dismissed by ${creator.name}",
            creator.name,
        )
        return found
    }

    /**
     * The reviewer of a goal: a temporary agent of [creator]'s conversation
     * holding no tool at all - it is handed what it judges, and changes
     * nothing. Not counted against the conversation's cap, since nobody chose
     * to make it; whether [modelId] may be used is the goal's to decide.
     */
    @Transactional
    fun createReviewer(creator: Agent, session: Long, name: String, modelId: Long, instructions: String): Agent {
        val agent = agents.save(
            Agent(
                workspaceId = creator.workspaceId,
                name = name.take(NAME_LENGTH),
                type = AgentType.LLM,
                description = "Judges whether a goal's condition is met.",
                systemPrompt = instructions,
                modelId = modelId,
                icon = "bot",
                hiddenTools = (BuiltInTools.hiddenBy(emptyList()) + builtIns.orknuxNames()).toMutableList(),
                mayAskForTools = false,
                temporary = true,
                createdBySessionId = session,
                // No creator: it is the goal's, so it is not the agent's to ask, dismiss or count.
                createdByAgentId = null,
                lastModifiedBy = creator.name,
            ),
        )
        audit.recordAutomated(
            creator.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Temporary agent ${agent.name} created by ${creator.name}",
            creator.name,
        )
        return agent
    }

    /** Removes one by id, where it is still there and temporary; [by] is who is said to have done it. */
    @Transactional
    fun remove(id: Long, by: String) {
        val found = agents.findByIdOrNull(id)?.takeIf { it.temporary } ?: return
        agents.delete(found)
        audit.recordAutomated(found.workspaceId, WorkspaceAuditCategory.AGENT, "Temporary agent ${found.name} dismissed by $by", by)
    }

    /**
     * Removes every temporary agent whose conversation has been idle for
     * [Tunable.TEMPORARY_AGENTS_IDLE_HOURS], or is gone. Called by the
     * scheduler's clock, and by a test with [now] wherever it likes.
     *
     * @return how many were removed
     */
    @Transactional
    fun sweep(now: OffsetDateTime = OffsetDateTime.now()): Int {
        val idle = Duration.ofHours(tuning[Tunable.TEMPORARY_AGENTS_IDLE_HOURS].toLong())
        var removed = 0
        agents.findTemporary().forEach { agent ->
            val session = agent.createdBySessionId?.let { sessions.findByIdOrNull(it) }
            val last = session?.let { it.lastEventAt ?: it.createdAt }
            if (last == null || last.plus(idle).isBefore(now)) {
                agents.delete(agent)
                removed++
                audit.recordAutomated(
                    agent.workspaceId,
                    WorkspaceAuditCategory.AGENT,
                    "Temporary agent ${agent.name} removed: its conversation was idle for ${idle.toHours()} hours",
                    "Orknux",
                )
            }
        }
        if (removed > 0) log.info("Removed {} temporary agent(s) whose conversation went quiet", removed)
        return removed
    }

    private fun refuse(said: String): Nothing = throw TemporaryAgentRefusedException(said)

    companion object {
        /** Shorter instructions than this are a stand-in rather than a system prompt. */
        const val MIN_INSTRUCTIONS = 40

        /** The agent table's name column. */
        const val NAME_LENGTH = 255

        /** The agent table's description column. */
        const val DESCRIPTION_LENGTH = 500

        private val log = LoggerFactory.getLogger(TemporaryAgents::class.java)
    }
}

/** Why `agent_create` or `agent_dismiss` did not, in a sentence the model is handed as it is. */
class TemporaryAgentRefusedException(message: String) : RuntimeException(message)
