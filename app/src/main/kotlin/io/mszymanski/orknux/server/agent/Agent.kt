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
     * How many tools this agent carries at once; null is the provider's ceiling.
     *
     * Issue #372. Tool search starts where the provider's own limit is reached -
     * 128 for OpenAI and Azure - and that is the number at which a request
     * *fails* rather than the number at which an agent starts choosing badly. A
     * model handed eighty tools is already picking from a list it cannot hold in
     * mind, and the context they occupy is paid for on every round of every
     * turn.
     *
     * So an agent can be given a smaller one, and below it the search that was
     * only a way to survive a hard limit becomes a way to keep an agent's
     * attention on a handful of things. [requiredTools] is what always travels;
     * the rest is found and dropped as the room is wanted.
     */
    @Column(name = "max_tools")
    var maxTools: Int? = null,

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
     * The skills inside those catalogs this agent may not see, by id. Issue #480.
     *
     * The catalogs say what is in scope and these two lists say what happens to
     * each skill inside them, the way the tools list works: a skill is Offered
     * by default, Hidden where its id is here, and Always where it is in
     * [requiredSkills].
     *
     * The exception is stored rather than the rule, for the reason
     * [hiddenTools] stores it: a skill added to a granted catalog next month
     * should arrive offered, not switched off because nobody went back and
     * ticked it. So an empty list is what every agent has and means "all of
     * them", which is also what every agent had before this existed.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_hidden_skill", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var hiddenSkills: MutableList<String> = mutableListOf(),

    /**
     * The skills in front of the model every turn, by id. Issue #480.
     *
     * The Always state. An offered skill is a name and a line the agent may
     * load; one marked here is loaded already - its whole page is in the system
     * turn before anybody says anything - because some instructions are not
     * "read this when it applies" but "this is how you work here", and an agent
     * that has to decide whether to read them has already half missed them.
     *
     * Costly on purpose: a page of markdown per turn per marked skill. That is
     * why it is a mark and not the default, and why the screen says what it
     * costs.
     *
     * A mark on a skill the agent cannot see does nothing, the way a mark on a
     * tool it is not granted does nothing: the grant decides whether, and the
     * mark only decides how.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_required_skill", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var requiredSkills: MutableList<String> = mutableListOf(),

    /**
     * Which tools this agent may call, by name: the workspace's, the plugins',
     * and the server's own.
     *
     * The same grant as the rest, and the strictest of them in effect: a skill
     * is a page an agent reads, and a tool is code that does something. An agent
     * granted none calls none.
     *
     * The server's own built-ins are names here too, since issue #444 - the
     * note, the to-do list, the clock, the scratchpad, saving a file, finishing
     * a turn, drawing. Three of those were booleans on this row (`artifact_access`,
     * `finish_access`, `picture_link_access`) and the rest were handed out
     * without asking, so the Tools list that people read to see what an agent
     * may do showed three of them. One list now, and one rule: a name here is
     * offered, a name not here is not. See
     * [io.mszymanski.orknux.server.chat.BuiltInTools] for which names, and V302
     * for how every agent that predates the list kept what it had.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_granted_tool", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var tools: MutableList<String> = mutableListOf(),

    /**
     * Which of the granted tools always travel, where [maxTools] is set.
     *
     * Issue #372. A tool an agent uses constantly should not have to be found:
     * an agent spending a round rediscovering the one thing it does every time
     * pays the cost of the search without the benefit. Everything granted and
     * not named here is loaded when it is looked for and dropped again when the
     * room is wanted for something else.
     *
     * Read only where the agent carries a ceiling of its own. Without one every
     * granted tool travels, which is how every agent worked before this existed
     * and what an agent with a handful of tools should go on doing.
     *
     * By name, like the grant it qualifies: a workspace tool is granted by name,
     * so marking one has to be too or the two come apart at the first rename.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_required_tool", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var requiredTools: MutableList<String> = mutableListOf(),

    /**
     * Which of the server's own tools this agent may *not* use. Issue #455.
     *
     * The one list here that says no rather than yes, and it is the built-ins
     * alone. #444 put them in [tools] with everything else, which reads well on
     * the screen and has a fault that only shows at the next release: a
     * built-in added later is in no agent's list, so it would arrive switched
     * off for everybody, silently, until somebody wrote a migration to
     * remember it - and an agent made by a door that did not know to add the
     * names had none at all.
     *
     * So what is stored is the exception. A built-in not named here is
     * offered, which makes "built-in" mean what it says: it is there unless
     * somebody turned it off, on every agent that exists and every one made
     * afterwards. The screen is unchanged - the form still sends one list of
     * tool names, and the door turns the built-ins among them into this. What
     * [requiredTools] says about a built-in is untouched by any of it: a mark
     * qualifies a tool that is on and says nothing about whether it is.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_hidden_tool", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "name", nullable = false)
    var hiddenTools: MutableList<String> = mutableListOf(),

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
     * Which other agents this one may put a question to, by id.
     *
     * Issue #350. An agent that needs work doing in a system it holds no tools
     * for had two ways out, and both are bad: be granted those tools as well -
     * forty descriptions in its context and a chain of lookups in its rounds -
     * or hand the job back to whoever asked. A specialist asked one question
     * does the looking up in a conversation of its own, and what comes back is
     * the answer rather than the working.
     *
     * By id, like the connections above and for the same reason: an agent is
     * pointed at by id everywhere else, and a grant by name would come apart the
     * first time somebody renamed one.
     *
     * One level. An agent reached this way is asked with its own briefing and
     * its own tools but is granted no agents of its own, however many its row
     * names - see [io.mszymanski.orknux.server.chat.AgentRunTools]. A depth
     * counter would be a number to tune; no depth at all is a rule that cannot
     * be got round, and two specialists in a ring is the failure it prevents.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agent_agent", joinColumns = [JoinColumn(name = "agent_id")])
    @OrderColumn(name = "position")
    @Column(name = "granted_id", nullable = false)
    var agents: MutableList<Long> = mutableListOf(),

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
) {

    /*
     * The three switches that used to be columns, read off the grant list.
     *
     * Issue #444 made saving a file, finishing a turn and asking for a picture's
     * address names in [tools] like every other built-in, and dropped the
     * booleans that had said the same thing a second way. These stay as views of
     * the list so that the loop that lends `finish_answer` and the shed that
     * offers `picture_link` go on asking the question in the words they always
     * did, and so the API's `finishAccess` and its two siblings keep answering.
     * Not columns: Hibernate maps the fields, and a property with no field
     * behind it is exactly what this is.
     */

    /** Whether it may keep a file it made; `save_artifact` not hidden. */
    val artifactAccess: Boolean
        get() = io.mszymanski.orknux.server.chat.BuiltInTools
            .granted(this, io.mszymanski.orknux.server.chat.AgentTools.SAVE_ARTIFACT)

    /** Whether it may end its turn by saying so; `finish_answer` not hidden. */
    val finishAccess: Boolean
        get() = io.mszymanski.orknux.server.chat.BuiltInTools.granted(this, FinishAnswerTools.FINISH)

    /** Whether it may ask for a picture's address; `picture_link` not hidden. */
    val pictureLinkAccess: Boolean
        get() = io.mszymanski.orknux.server.chat.BuiltInTools
            .granted(this, io.mszymanski.orknux.server.workflow.StepPictureTools.LINK)
}
