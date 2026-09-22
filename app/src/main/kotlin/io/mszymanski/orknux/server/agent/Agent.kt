package io.mszymanski.orknux.server.agent

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * There is one kind of agent.
 *
 * REACT was the other one, and the distinction never earned its place: every
 * agent reaches the model the same way and every agent may call tools. What an
 * agent is allowed to call is configured per agent, which is the setting that
 * was actually doing the work all along.
 *
 * The enum is kept rather than the column dropped so that the shape of an agent
 * does not change for one withdrawn value.
 */
enum class AgentType {
    LLM,
}

/** An AI agent configured by a workspace. */
@Entity
@Table(name = "agent")
class Agent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var type: AgentType,

    @Column(length = 500)
    var description: String? = null,

    @Column(name = "system_prompt", columnDefinition = "text")
    var systemPrompt: String? = null,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /**
     * The model this agent thinks with.
     *
     * Null is none chosen. Nothing is substituted for it: which model an agent
     * uses changes what it costs and what it answers, and that is the
     * workspace's decision rather than one to make on its behalf.
     */
    @Column(name = "model_id")
    var modelId: Long? = null,

    /**
     * How much of that model's context window a session may take back, as a
     * percentage of it.
     *
     * Null falls through to `workspace.default_memory_share`, and where that is
     * null too, to the built-in default - which is the five numbers this used
     * to be five constants for, so an agent nobody has touched in a workspace
     * nobody has touched carries exactly what it carried before there was a
     * setting.
     *
     * A share rather than a count of tokens, and on the agent rather than
     * anywhere else, because a budget is two things owned by two rows: the
     * window is the model's and is already recorded on it, and how much of that
     * window is worth spending on remembering depends on what this agent's
     * tools give back. One agent reading whole files and another reading issue
     * lists can point at the same model and want different answers, and neither
     * of them wants the answer an installation-wide setting would give both.
     * `SessionMemoryBudget` holds the whole of the reasoning and the
     * arithmetic; this column holds the one number a person sets.
     */
    @Column(name = "memory_share")
    var memoryShare: Int? = null,

    /**
     * How many rounds of tool calls this agent gets before it has to answer.
     *
     * Null is the installation's own number, which is where every agent starts
     * and where most of them stay. On the agent rather than only on the
     * installation for the reason the memory share is: an agent with two tools
     * and one holding a catalogue of twenty want different answers, and the
     * number that suits both is the number that suits neither. A list, a load
     * and a lookup is three rounds before the work begins.
     */
    @Column(name = "max_rounds")
    var maxRounds: Int? = null,

    /**
     * Whether this agent may ask orknux about orknux.
     *
     * The built-in server, which is not one of [mcpServers] and never appears
     * among them: those are addresses somebody registered, and this one is the
     * application the agent is already running inside. A boolean rather than a
     * name in that list, because there is no server to name.
     *
     * Granted, it can also start workflows — which is the point, and worth
     * knowing before granting it: an agent that starts a workflow which asks an
     * agent is a loop nothing here breaks.
     */
    @Column(name = "orknux_access", nullable = false)
    var orknuxAccess: Boolean = false,

    /**
     * Whether this agent may open a shell on one of the installation's machines
     * and run commands there.
     *
     * Plural and unnamed, which is the owner's design and the right one: from
     * where an agent sits the question is "can I run a command somewhere", not
     * "may I run one on build-box-3". Naming a machine here would make every
     * agent's configuration stale the day that machine is replaced, and would
     * put a decision about infrastructure in a workspace's settings when the
     * shells themselves are installation-wide and an administrator's.
     *
     * Which shell a session lands on is decided at the moment it opens; see
     * `ShellService.choose` for the rule and why it is that rule.
     *
     * Worth knowing before granting it: what contains this is the machine on
     * the other end of the SSH connection, and nothing in this application. An
     * agent given this can run any command the account on that machine can.
     */
    @Column(name = "shell_access", nullable = false)
    var shellAccess: Boolean = false,

    /**
     * Whether it may keep a file it made, on the workspace's Artifacts page.
     *
     * On by default, which is the one grant here that is. The others open a
     * door onto something that already exists and could be damaged through it
     * - the workspace's own data, a machine, a catalog - so the safe default
     * for those is off, and turning one on is a decision somebody makes.
     *
     * This one only lets an agent keep its own output where a person can find
     * it. Refusing that by default would mean every agent that draws a
     * diagram or writes a report has nowhere to put it until somebody notices
     * a setting, and the common answer to "may it save what it made" is yes.
     * The bounds that matter are on the saving - a size, a count per workspace
     * - and they hold whoever is asking. It is here at all so that an agent
     * which should not be filling the disk can be told so.
     */
    @Column(name = "artifact_access", nullable = false)
    var artifactAccess: Boolean = true,

    /**
     * Whether it may end its turn by saying so, rather than by writing prose.
     *
     * On, like the one above, and for a plainer reason: this takes nothing and
     * reaches nothing. See [io.mszymanski.orknux.server.agent.FinishAnswerTools]
     * for what a turn with nothing left to say does without it. The switch is
     * for the workflow whose next node needs an answer to work with, where an
     * agent finishing early hands it an empty one.
     */
    @Column(name = "finish_access", nullable = false)
    var finishAccess: Boolean = true,

    /**
     * Whether it may ask for an address for a picture it drew.
     *
     * On, like the two above. What it buys is placing a picture inside what
     * the agent writes; what it costs is a link the agent could put somewhere
     * this installation is not, where it resolves to nothing for the reader -
     * which is the reason there is a switch at all. See
     * [io.mszymanski.orknux.server.workflow.StepPictureTools].
     */
    @Column(name = "picture_link_access", nullable = false)
    var pictureLinkAccess: Boolean = true,


    /** MCP servers this agent may connect to, in the order they were added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_mcp_server", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var mcpServers: MutableList<String> = mutableListOf(),

    /**
     * Memory catalogs this agent may read, by name.
     *
     * By name rather than by id, the same way the MCP servers are: an agent is
     * configured against what the workspace calls things, and the list is a
     * grant — what an agent may look in, not everything the workspace knows.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_memory_catalog", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var memoryCatalogs: MutableList<String> = mutableListOf(),

    /**
     * Which skill catalogs this agent may draw on.
     *
     * The same kind of grant as [memoryCatalogs], and by name for the same
     * reason. Granted per catalog rather than per skill: what an agent is
     * expected to know is a decision worth making once, not once per skill.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_skill_catalog", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var skillCatalogs: MutableList<String> = mutableListOf(),

    /**
     * Which of the workspace's tools this agent may call.
     *
     * The same grant as the rest, and the strictest of them in effect: a skill
     * is a page an agent reads, and a tool is code that does something. An agent
     * granted none calls none.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_granted_tool", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var tools: MutableList<String> = mutableListOf(),

    /**
     * Which of the workspace's connections this agent may name when a tool
     * takes one, by id.
     *
     * By id and not by name, unlike every other grant here: a connection
     * parameter is stored by id everywhere else - a plugin's setting, a node's
     * choice - and a grant that renamed itself out of meaning when somebody
     * renamed the connection would be the trap the others avoid by naming
     * things a workspace renames rarely.
     *
     * The briefing lists these with a standing instruction: use one only when
     * explicitly told to, and otherwise leave a tool to its configured
     * default. A grant is permission, not encouragement.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_connection", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "connection_id", nullable = false)
    var connections: MutableList<Long> = mutableListOf(),

    /**
     * Which icon a node drawn from this starts with.
     *
     * A seed, not a rule: the node owns its icon once it has one, the same way
     * it owns the parameters this seeded. Null draws whatever the kind draws.
     */
    @Column(length = 40)
    var icon: String? = null,

    /**
     * When this agent was last saved, and by whom.
     *
     * The two stamps every other versioned component already carried. A
     * revision holds the state that was displaced and says when *that* state
     * was saved, so without these an agent's history could only report when
     * each version stopped being current — which is not the question anybody
     * asks of one.
     *
     * Every door that changes an agent writes them, the MCP tools included:
     * an agent switched off by another agent was still switched off by
     * somebody, and a stamp that only the browser updated would be a stamp
     * that quietly lied about the changes nobody watched.
     */
    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)
