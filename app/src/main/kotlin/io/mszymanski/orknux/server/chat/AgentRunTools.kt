package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.workflow.execution.RetryPolicy
import io.mszymanski.orknux.workflow.execution.retryPolicy
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import org.springframework.beans.factory.ObjectProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * One agent putting a question to another.
 *
 * ### What it is for
 *
 * An agent that needs something doing in a system it holds no tools for had two
 * ways out, and both are bad. Grant it those tools as well - and it carries
 * forty descriptions it mostly does not use, and spends its rounds on the chain
 * of lookups one job needs before the work it was actually asked about begins.
 * Or hand the job back to whoever asked, which is the agent saying no to the
 * thing it exists for.
 *
 * So an agent may be granted other agents, and gets one tool: ask one of them.
 * The specialist answers in a conversation of its own, with its own briefing,
 * its own model and its own tools, and what comes back to the agent that asked
 * is the answer. Not the twelve rounds of looking things up that produced it -
 * which is the whole saving, because those rounds are what a context window is
 * actually spent on.
 *
 * ### One level, and why it is a rule rather than a number
 *
 * An agent reached this way is granted no agents of its own, whatever its row
 * says. A depth counter would be a number somebody has to choose, and every
 * value of it is wrong for something: two is arbitrary, five is a bill nobody
 * approved, and any of them leaves a ring of specialists calling each other
 * until the count runs out. No depth at all cannot be got round, costs nothing
 * to explain, and rules out the one failure that would be expensive and silent.
 *
 * What it costs is a chain three deep, which nobody has asked for and which the
 * grant list can express another way: an agent granted both specialists asks
 * each in turn.
 *
 * ### What it is not
 *
 * Not a way to reach an agent that was not granted. The names offered are the
 * grant list, and a name that is not in it is refused by the thing that would
 * otherwise run it - the rule the orknux tools already keep.
 *
 * Not a workflow either. This is a question and an answer inside one round of
 * one turn; a run that has to survive a restart, retry a step or be watched from
 * a page is a workflow, and the graph is where that belongs.
 */
@Service
class AgentRunTools(
    private val agents: AgentRepository,
    private val briefing: AgentBriefing,
    /**
     * The conversation, fetched when it is needed rather than injected.
     *
     * [AgentConversation] builds its tools from [AgentTools], which holds this -
     * so asking for it here directly is a cycle Spring refuses at startup. The
     * provider breaks it, and the call is made from inside a round rather than
     * while the beans are being wired, by which time everything exists.
     */
    private val conversations: ObjectProvider<AgentConversation>,
    /**
     * The asked agent's setup, written into its session before the question.
     * Issue #456.
     *
     * Fetched rather than injected for the same reason the conversation is:
     * [io.mszymanski.orknux.server.agent.AgentDetails] asks [AgentTools] what the
     * agent holds, and [AgentTools] holds this - so wiring it in directly is a
     * cycle Spring refuses at startup. By the time a question is being asked,
     * everything exists.
     */
    private val details: ObjectProvider<io.mszymanski.orknux.server.agent.AgentDetails>,
    /** Where the asked agent's own conversation is written; see [run]. Issue #379. */
    private val sessions: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
    /**
     * The session's tools the asked agent is lent, the same set every turn
     * takes - so a pad the asker shared reaches it (#411), and so does a note,
     * a to-do list and the clock. Fetched rather than injected: the watcher
     * tools in it reach the agent's own tools, which hold this.
     */
    private val sessionSheds: ObjectProvider<SessionSheds>,
    /** The conversations already started from one, for the count in [run]. Issue #380. */
    private val held: io.mszymanski.orknux.server.llm.LlmSessionRepository,
    /** Whether a line in one of those is still open, for [asked]. Issue #477. */
    private val lines: io.mszymanski.orknux.server.llm.LlmSessionEventRepository,
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
    /** Where an answer, and whatever the asked agent's tools kept, is put for the asker. Issue #393. */
    private val scratch: io.mszymanski.orknux.server.llm.LlmSessionStore,
    private val mapper: ObjectMapper,
    /** Where a finished ask is left for the asker, which wakes it if it is not running. */
    private val inbox: io.mszymanski.orknux.server.llm.SessionInbox,
    /** Announces what came due while a turn was ending; see [launch]. */
    private val published: org.springframework.context.ApplicationEventPublisher,
    /** What an asked agent's session was sent before, for an ask that continues it; see [conversationIn]. */
    private val prompt: ObjectProvider<io.mszymanski.orknux.server.llm.SessionPrompt>,
    private val budgets: ObjectProvider<io.mszymanski.orknux.server.llm.SessionMemoryBudgets>,
    /** Whether a conversation is a workflow step's, whose words go out only when its turn ends; see [waited]. */
    private val steps: io.mszymanski.orknux.workflow.execution.ExecutionStepRepository,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * Where an ask actually runs. Issue #462.
     *
     * Built on each start rather than held, for the reason #504 wrote down: a
     * stopped executor cannot be started again, and a context that is stopped
     * and started - which the suite does between classes - would otherwise
     * leave every ask throwing.
     */
    private var asking: ExecutorService? = null

    @jakarta.annotation.PostConstruct
    fun open() {
        asking = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "agent-ask").apply { isDaemon = true }
        }
    }

    @jakarta.annotation.PreDestroy
    fun close() {
        asking?.shutdownNow()
        asking = null
    }

    /**
     * What each ask is doing, by the session it runs in.
     *
     * Only the ones still going and the ones that finished in this process:
     * the durable account is the sessions themselves, which [asked] reads, and
     * this is what lets a wait return the moment something lands rather than
     * on the next poll.
     */
    private val running = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.Future<*>>()

    /**
     * What ends each running ask's model call, by the session it runs in, for
     * [stop]. An interrupt alone does not end a call blocked reading a stream;
     * see [io.mszymanski.orknux.connector.model.Hangup].
     */
    private val hangups = java.util.concurrent.ConcurrentHashMap<Long, io.mszymanski.orknux.connector.model.Hangup>()

    /**
     * How many of one conversation's asks may be working at once. Issue #461.
     *
     * A permit per asking conversation rather than a global pool: a global one
     * would let a single busy conversation starve every other, and what anybody
     * wants to bound is how wide *one* question fans out.
     *
     * An ask past the ceiling waits its turn rather than being refused. A
     * refusal would send the model round again with the same ask in other
     * words - the failure mode this whole feature keeps running into - and the
     * wait is invisible to it anyway, because it was never going to block.
     */
    private val atOnce = java.util.concurrent.ConcurrentHashMap<Long, Gate>()

    /**
     * One conversation's permits, and how many of its asks still hold a claim on
     * them - waiting or working. Issue #616: the semaphore used to stay in
     * [atOnce] for ever, one per conversation that had ever asked anything. It
     * goes when the last of its asks is over; `users` is only ever read and
     * written inside the map's `compute`, so the count and the removal cannot
     * race an ask arriving for the same conversation.
     */
    private class Gate(val size: Int) {
        val permits = java.util.concurrent.Semaphore(size)
        var users = 0
    }

    /**
     * Asks started and not yet given a permit: waiting for one of the asker's
     * others to finish. Kept apart so `agent_asks` and the ask's own answer can
     * say "waiting" rather than "working" - an agent told five asks were working
     * when two were queued believed it had five running, and an agent that had
     * been told to run one at a time could not see that it was.
     */
    private val waitingForSlot = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * How many of [agent]'s asks may be working at once in one conversation: its
     * own number where it has one, Admin -> Settings otherwise.
     */
    fun atOnceFor(agent: Agent): Int = agent.maxSubagentsAtOnce ?: installation.agentMaxSubagentsAtOnce()

    /**
     * The same for the conversation [parent], read off the agent whose session
     * it is - for a subagent woken by its own timer, where nobody is asking.
     */
    private fun atOnceIn(parent: Long): Int {
        val asker = held.findByIdOrNull(parent)?.agentDetails?.let { details ->
            runCatching { mapper.readTree(details).path("agentId").takeIf { it.isNumber }?.asLong() }.getOrNull()
        }?.let { agents.findByIdOrNull(it) }
        return asker?.let(::atOnceFor) ?: installation.agentMaxSubagentsAtOnce()
    }

    /**
     * Whether an ask started in [conversation] now would wait for a permit. Read
     * before it is started, so it can be told; a guess, because another ask may
     * finish in between - in which case it starts at once and was only told it
     * might wait.
     */
    private fun wouldWait(conversation: Long, limit: Int): Boolean {
        val gate = atOnce[conversation] ?: return limit < 1
        return gate.users >= gate.size
    }

    /** What an ask's answer adds where it has to wait its turn. */
    private fun waitNote(limit: Int): String =
        " It is waiting its turn: you already have $limit asked agent${if (limit == 1) "" else "s"} working, " +
            "which is as many as you may have at once, so it starts when one of them finishes."

    /** An ask of this conversation is over; the last one out takes the gate with it. */
    private fun letGo(conversation: Long) {
        atOnce.computeIfPresent(conversation) { _, held ->
            held.users--
            if (held.users > 0) held else null
        }
    }

    /** Whether this process still holds an ask's future - for the test that it lets go. Issue #616. */
    internal fun holdsAsk(session: Long): Boolean = running.containsKey(session)

    /** Whether a conversation still has permits kept for it - for the test that they go. Issue #616. */
    internal fun holdsGate(conversation: Long): Boolean = atOnce.containsKey(conversation)

    /**
     * Whether this agent has anybody to ask. A grant list of none offers
     * nothing, and so does a workspace - or an installation - that allows no
     * asks at all: a tool that refuses every call is a tool the model should
     * not be shown. Issue #380.
     */
    /*
     * Or that may create somebody to ask: the tool array is fixed for the
     * session, so ask_agent has to be in it from the start for an agent made
     * halfway through to be askable at all.
     */
    fun offered(agent: Agent): Boolean =
        (granted(agent).isNotEmpty() || (agent.creatableModelIds.isNotEmpty() && !agent.temporary)) && limitFor(agent) > 0

    /**
     * How many other agents this one may ask in a conversation: the
     * workspace's own number where it has one, Admin -> Settings otherwise.
     */
    fun limitFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.agentMaxSubagents ?: installation.agentMaxSubagents()

    fun specFor(agent: Agent, canWait: Boolean = true): ToolSpec {
        val named = granted(agent)
        return ToolSpec(
            name = ASK,
            // Started, not answered, since #462 - and it said "answers with what it said" for a
            // release after, so a model expected the answer to come back to it by itself.
            description = "Puts a question to one of the other agents you have been given. It starts the " +
                "agent and returns at once; the answer is not in this call's result. " +
                (if (canWait) {
                    "To get it you must wait for it yourself - " + WAIT + " now, or finish_answer with a " +
                        "wake-up to be started again later - and then read it with " + ASKS + ". "
                } else {
                    "When you have nothing else to do, end your turn saying what you are doing: the answer " +
                        "starts you again in this conversation, and you read it then with " + ASKS + ". "
                }) + "Use it where the work needs something you have no tool " +
                "for and one of them does - it will look things up in its own conversation, so ask " +
                "for what you want to know rather than for the steps. It cannot see this " +
                "conversation, so say everything it needs in the question - but a key of this " +
                "conversation, such as an attached file's contentKey, works in its conversation too, so " +
                "name the key rather than pasting what it holds. The answer comes back " +
                "as text and under a contentKey as well, and any key the answer names - a file it " +
                "saved, a picture it drew - works in this conversation too: to upload or send what " +
                "it made, pass the key to the tool that takes one rather than typing the text back, " +
                "which is what cuts a long file off. Where you want something long made - a page, a " +
                "file, a list of a hundred things - say in the question that it should be written " +
                "into a scratchpad, and the answer will name the pad: you then read and " +
                "edit that pad yourself instead of it being typed back at you. To see what each " +
                "of them can do - its tools, skills and connections - call " + LIST + " first. " +
                atOnceFor(agent).let { n ->
                    if (n == 1) "Only one of them works at a time: a second ask waits until the first has answered. "
                    else "At most $n of them work at once; an ask past that waits until one has answered. "
                } + "You may ask: " +
                (if (named.isEmpty()) "none yet" else named.joinToString(", ") { "${it.name} (${it.description ?: "no description"})" }) +
                // Said rather than listed: the array is fixed, and the agents made later are not in it.
                (if (agent.creatableModelIds.isNotEmpty() && !agent.temporary) ", and any agent you create with ${TemporaryAgentTools.CREATE}." else ""),
            parameters = listOf(
                ToolParameterSpec(
                    name = AGENT,
                    description = "Which of them to ask, by name: " + named.joinToString(", ") { it.name } +
                        (if (agent.creatableModelIds.isNotEmpty() && !agent.temporary) " - or one you created" else ""),
                    required = true,
                ),
                ToolParameterSpec(
                    name = QUESTION,
                    description = "What to ask it, in full. It has none of your context, so a " +
                        "question that refers to \"the issue\" or \"that file\" cannot be answered.",
                    required = true,
                ),
                ToolParameterSpec(
                    name = FRESH,
                    description = "Asking an agent you have already asked in this conversation continues the " +
                        "conversation you had with it, so it remembers what it was asked and what it answered - " +
                        "ask a follow-up as one. true starts a new conversation with it instead: for an " +
                        "unrelated question, or a new job - the next repository, the next document - where what " +
                        "it did before is only something it would have to read again on every round.",
                    required = false,
                    schema = mapOf("type" to "boolean"),
                ),
                ToolParameterSpec(
                    name = TITLE,
                    description = "A few words naming the task, shown as the title of the conversation " +
                        "the agent has about it - \"Summarise the incident thread\". Left out, the " +
                        "first line of the question is used.",
                    required = false,
                ),
            ),
        )
    }

    /** What `agent_list` is, to a model. Issue #552. */
    fun listSpec(): ToolSpec = ToolSpec(
        name = LIST,
        readOnly = true,
        description = "Lists the agents you may ask, with what each can do: its description, model, " +
            "tools, skills, memories and connections. Call it before asking, to pick the agent that " +
            "holds the tool the work needs, rather than asking one and hearing it cannot. Under " +
            "fromThisConversation are the tools it is lent when you ask it from here - this " +
            "conversation's scratchpads among them. An agent that says it cannot do something these " +
            "list has not tried: tell it which tool to use, rather than doing its work yourself.",
        parameters = emptyList(),
    )

    /**
     * The agents this one may ask, each with its setup. Issue #552.
     *
     * The names and descriptions on ask_agent say what an agent is for, not
     * what it holds: asked for commit statistics, an agent went to the one
     * whose description sounded right, which had no GitHub tool, and heard
     * back that it could not. The setup is read the way the session records
     * it - [io.mszymanski.orknux.server.agent.AgentDetails] - for the agent as
     * it would be asked, so with no agents of its own, and without its prompt,
     * which is its instructions and not the asker's business.
     */
    fun listed(agent: Agent, parent: Long? = null): String {
        val described = granted(agent, parent).map { asked ->
            val sub = without(asked)
            val setup = mapper.readTree(details.getObject().snapshot(sub, null, null))
            val tools = (setup.path("tools").toList().map { it.stringValue() } + setup.path("findable").toList().map { it.stringValue() })
                .distinct().sorted()
            /*
             * And what it is lent by the conversation it answers in - the
             * scratchpads above all, which are this conversation's. Described
             * without a conversation it held none of them, so when a subagent
             * claimed it could not write a pad, the asker looked here, read
             * "no tools", and saved every answer itself for the rest of the
             * day. Only the specs are read; nothing is opened or written.
             */
            val lent = parent?.let { handing(sub, it)?.specs()?.map { spec -> spec.name } }.orEmpty()
                .filterNot { it in tools }.distinct().sorted()
            linkedMapOf(
                "name" to asked.name,
                "description" to asked.description,
                "model" to setup.path("model").takeIf { it.isTextual }?.stringValue(),
                "tools" to tools,
                "fromThisConversation" to lent,
                "skills" to setup.path("skills").toList().map { it.stringValue() },
                "memory" to setup.path("memory").toList().map { it.stringValue() },
                "connections" to setup.path("connections").toList().map { it.stringValue() },
            )
        }
        return mapper.writeValueAsString(linkedMapOf("agents" to described))
    }

    /** What `agent_asks` is, to a model. Issue #477. */
    fun asksSpec(): ToolSpec = ToolSpec(
        name = ASKS,
        readOnly = true,
        description = "Lists the agents you have asked in this conversation and how each one is going: " +
            "what it was asked about, whether it is still working, and when it last did anything. " +
            "Also says how many asks you have left. Call it when somebody asks what your agents are " +
            "doing, and before asking again where you are not sure whether an earlier ask is finished.",
        parameters = emptyList(),
    )

    /** What `agent_wait` is, to a model. Issue #462. */
    fun waitSpec(): ToolSpec = ToolSpec(
        name = WAIT,
        readOnly = true,
        description = "Waits for the agents you have asked. Call it when you have nothing else to do and " +
            "something you asked for has not come back yet. It returns as soon as one of them finishes, " +
            "or after the seconds you gave, whichever is first - so waiting costs nothing when the work " +
            "is already done. Then call " + ASKS + " to see what came back. Do not call this before you " +
            "have run out of other work: an ask runs while you carry on. Nor once you have told the person " +
            "what you are doing: nothing you write reaches them until your turn ends, so end it - an answer " +
            "still to come wakes you again, and what you say then is sent after.",
        parameters = listOf(
            ToolParameterSpec(
                SECONDS,
                "How long to wait at most, in seconds. Up to " + tuning[Tunable.AGENT_WAIT_LONGEST_SECONDS] + "; left out, " +
                    tuning[Tunable.AGENT_WAIT_DEFAULT_SECONDS] + ".",
                required = false,
            ),
        ),
    )

    /** What `agent_stop` is, to a model. */
    fun stopSpec(): ToolSpec = ToolSpec(
        name = STOP,
        description = "Stops agents you asked that are still working, when their answer is no longer wanted: " +
            "the person changed the question, you found the answer another way, or one is taking far too long. " +
            "Give the session " + ASKS + " lists for one ask, or an agent's name for every ask of it still " +
            "working; give neither to stop all of them. A stopped agent sends nothing back, and what it did " +
            "before it was stopped stays done.",
        parameters = listOf(
            ToolParameterSpec(
                SESSION,
                "The session of one ask, as " + ASKS + " gives it.",
                required = false,
            ),
            ToolParameterSpec(
                AGENT,
                "An agent's name, to stop every ask of it that is still working.",
                required = false,
            ),
        ),
    )

    /**
     * Stops what this conversation asked and no longer wants.
     *
     * An asked agent ran until it answered or ran out of rounds, and nothing
     * the asker did could end it: the person in a Slack thread changed the
     * subject, the agent said it would drop the audit, and Summarizer went on
     * looking things up for another twenty minutes and then reported back,
     * into a turn about something else. Only the asker's own asks - the
     * sessions under its conversation - can be stopped, so naming another
     * conversation's session stops nothing.
     *
     * The model call is hung up on rather than interrupted, because a thread
     * blocked reading a stream does not notice an interrupt; and the thread is
     * interrupted as well, for a tool call or a retry's wait in between. Hung up
     * ordinarily, so the torn call is not recorded as a failure: the line the
     * transcript gets is that the asker stopped it.
     */
    fun stop(agent: Agent, parent: Long?, arguments: String): String {
        if (parent == null) return refusal("This conversation is not one that can ask, so there is nothing to stop.")
        val given = runCatching { mapper.readTree(arguments) }.getOrNull()
        val session = given?.path(SESSION)?.let { node ->
            when {
                node.isNumber -> node.longValue()
                node.isString -> node.stringValue().trim().removePrefix("#").toLongOrNull()
                else -> null
            }
        }
        val named = given?.path(AGENT)?.takeIf { it.isString }?.stringValue()?.trim()?.ifEmpty { null }
        val children = held.findByParentSessionIdOrderByCreatedAtAscIdAsc(parent)
        val chosen = when {
            session != null -> children.filter { it.id == session }.ifEmpty {
                return refusal("You asked nothing in session $session. $ASKS lists what you asked, each with its session.")
            }
            named != null -> children.filter { nameIn(it.agentDetails).equals(named, ignoreCase = true) }.ifEmpty {
                return refusal("You have not asked $named in this conversation. $ASKS lists who you asked.")
            }
            else -> children
        }
        val stopping = chosen.filter { child -> child.id?.let { running[it] }?.isDone == false }
        stopping.forEach { child ->
            val id = requireNotNull(child.id)
            hangups[id]?.hangUpOrdinarily()
            running[id]?.cancel(true)
            sessions.note(id, "$STOPPED_BY${agent.name} before it answered.")
        }
        val listed = stopping.map { child ->
            linkedMapOf("asked" to (nameIn(child.agentDetails) ?: "an agent"), "about" to child.title, "session" to child.id)
        }
        return mapper.writeValueAsString(
            linkedMapOf(
                "stopped" to listed,
                "note" to if (listed.isEmpty()) {
                    "Nothing of that was still working, so nothing was stopped. Call $ASKS for what came back."
                } else {
                    "Stopped. Nothing more will come back from ${if (listed.size == 1) "it" else "them"}; what " +
                        "was done before stays done."
                },
            ),
        )
    }

    fun handles(name: String): Boolean = name == ASK || name == ASKS || name == WAIT || name == LIST || name == STOP

    /**
     * Whether [WAIT] can wait in this conversation. Not in the step of an agent
     * node that streams its output: a wait there holds back what the agent
     * already said, and the answer starts it again anyway. So it is not offered
     * there, rather than offered and refused on every call, which cost a round
     * each time a model believed the description over the refusal. A node that
     * does not stream sends only its last answer, so there it waits.
     */
    fun waits(session: Long?): Boolean =
        session == null || steps.findFirstBySessionIdOrderByIdDesc(session)?.streamOutput != true

    /** What an ask's result tells the asker to do once it runs out of other work. */
    private fun whenNothingLeft(parent: Long?): String =
        if (waits(parent)) {
            "call " + WAIT + " when you have nothing left, then " + ASKS + " to read what came back. Do not " +
                "end your turn before you have read it: nothing brings you back to an answer you finished without."
        } else {
            "when you have nothing left, end your turn saying what you are doing. Its answer starts you " +
                "again in this conversation, and you read it then with " + ASKS + "."
        }

    /**
     * Waits for something asked to finish. Issue #462.
     *
     * A sleep that ends early, which is the whole of what an asking agent needs
     * once asks stop blocking: it has started three things, done what it could,
     * and has nothing to do but wait. Ending early matters more than the number
     * - a wait that always ran its full time would make asking two agents cost
     * the same as asking them one after another, which is the fault this is
     * fixing.
     *
     * Bounded, because a model that may sleep for an hour will. What it gets
     * back is what [asked] answers, so the same shape says both "here is what
     * you asked" and "here is what happened while you waited".
     */
    fun waited(agent: Agent, parent: Long?, arguments: String): String {
        if (parent == null) {
            return mapper.writeValueAsString(
                mapOf("waited" to 0, "note" to "This conversation is not one that can ask, so there is nothing to wait for."),
            )
        }

        val wanted = waitSecondsIn(arguments)
        val seconds = wanted.coerceIn(1, tuning[Tunable.AGENT_WAIT_LONGEST_SECONDS])

        val children = held.findByParentSessionIdOrderByCreatedAtAscIdAsc(parent).mapNotNull { it.id }
        val outstanding = children.mapNotNull { running[it] }.filterNot { it.isDone }
        if (outstanding.isEmpty()) {
            return mapper.writeValueAsString(
                linkedMapOf(
                    "waited" to 0,
                    "note" to "Nothing is still working. Call " + ASKS + " to read what came back.",
                ),
            )
        }

        /*
         * Not in a streaming workflow step's conversation. What its agent writes goes
         * to the person - the Slack reply after it - only when its turn ends, so
         * a wait held back everything it had said: "I'm checking this" reached
         * the thread when the answer did, minutes later. The tool's own words
         * told the model to end its turn instead, and it waited anyway. So here
         * it does not wait: the answer, when it lands, starts the agent again in
         * this conversation and its reply follows the first.
         */
        if (!waits(parent)) {
            return mapper.writeValueAsString(
                linkedMapOf(
                    "waited" to 0,
                    "working" to outstanding.size,
                    "note" to "Not waited: nothing you write reaches the person until your turn ends, so waiting " +
                        "here would hold back what you have already said. End your turn now, saying what you " +
                        "are doing. When the answer lands you are started again in this conversation, and " +
                        "what you say then follows.",
                ),
            )
        }

        /*
         * Waited on one at a time, longest-first by what is left: any of them
         * finishing is the thing worth waking for, and the first to finish
         * always leaves the others still running for the next call.
         */
        val until = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < until && outstanding.any { !it.isDone }) {
            val left = until - System.nanoTime()
            if (left <= 0) break
            runCatching {
                outstanding.first { !it.isDone }
                    .get(minOf(left, POLL_NANOS), java.util.concurrent.TimeUnit.NANOSECONDS)
            }
        }

        val slept = seconds - ((until - System.nanoTime()) / 1_000_000_000L).coerceAtLeast(0)
        val stillWorking = outstanding.count { !it.isDone }
        return mapper.writeValueAsString(
            linkedMapOf(
                "waited" to slept,
                "working" to stillWorking,
                "note" to if (stillWorking == 0) {
                    "They have all finished. Call " + ASKS + " to read what came back."
                } else {
                    "$stillWorking still working. Call " + ASKS + " for what has landed, or wait again."
                },
            ),
        )
    }

    /**
     * What this conversation has handed out, and how each of them is going.
     * Issue #477.
     *
     * The asks are sessions under this one, so the list is the children. An
     * agent that has asked three things and is waiting on them had no way of
     * seeing that: it knew what it had sent, in its own transcript, and nothing
     * about what came of it - so `::agents` was a question nobody could answer
     * and a person asking what the subagents were doing got a guess.
     *
     * Still working is read off the future this process holds for it, and
     * with none, off a line opened and not finished. Not the sessions list's
     * other half - something written within the installation's window - since
     * #616: an ask's future goes the moment it is done, and that half would
     * then call a finished ask working for a minute after it answered.
     */
    fun asked(agent: Agent, parent: Long?): String {
        if (parent == null) {
            return mapper.writeValueAsString(
                mapOf("asks" to emptyList<Any>(), "note" to "This conversation is not one that can ask."),
            )
        }
        val children = held.findByParentSessionIdOrderByCreatedAtAscIdAsc(parent)
        val ids = children.mapNotNull { it.id }
        val open = if (ids.isEmpty()) emptySet() else lines.unfinishedAmong(ids).toSet()

        val listed = children.map { session ->
            val id = session.id
            val future = id?.let { running[it] }
            /*
             * Still going where its future says so. With no future it is not
             * running here, and asks run nowhere else: either it finished - its
             * future is dropped the moment it is done (#616) - or a restart
             * took it. So only a line left open counts then, and not a line
             * written recently, which would call an ask that has just answered
             * still working for the whole of the window.
             */
            val working = if (future != null) !future.isDone else id in open
            linkedMapOf<String, Any?>(
                "asked" to (nameIn(session.agentDetails) ?: "an agent"),
                "about" to session.title,
                // What agent_stop is given to stop this one.
                "session" to id,
                "working" to working,
                // Started and not yet given a slot: one of the others has to finish first.
                "waiting" to (working && id != null && id in waitingForSlot),
                // How far it has grown: see grownIn, and `fresh` for a job that has changed.
                "questions" to id?.let { grownIn(it).questions },
                "inputTokensSoFar" to session.spentInputTokens,
                "startedAt" to session.createdAt.toString(),
                "lastAt" to session.lastEventAt?.toString(),
            ).apply {
                /*
                 * And what came back, once it has. Issue #536.
                 *
                 * Since asks stopped blocking (#462) an ask answered "it has
                 * started", agent_wait only waited, and this listed who and
                 * what and whether it was working - never the answer. It sat in
                 * a future nothing read, so the asking agent was told to read
                 * what came back and nothing came back.
                 */
                if (!working && id != null) putAll(cameBack(parent, id, future))
            }
        }
        val allowed = limitFor(agent)
        return mapper.writeValueAsString(
            linkedMapOf(
                "asks" to listed,
                "working" to listed.count { it["working"] == true },
                "waiting" to listed.count { it["waiting"] == true },
                "atOnce" to atOnceFor(agent),
                "left" to (allowed - listed.size).coerceAtLeast(0),
            ),
        )
    }

    /** The agent's name out of the details line the session opens with. Issue #456. */
    /**
     * A finished ask's answer, and the key it is kept under. From the future
     * where this server started it, and from the asked agent's own transcript
     * where it did not - after a restart, the future is gone and the answer is
     * still written down.
     */
    private fun cameBack(parent: Long, child: Long, future: java.util.concurrent.Future<*>?): Map<String, Any?> {
        /*
         * What the asked agent kept, brought across whenever its answer is read,
         * not only when the ask itself ends: an answer it gave in a later turn -
         * one a reminder of its own woke - named a drawing's key that the asker
         * then could not open, though it had been told any key an answer names
         * works in its conversation too.
         */
        runCatching { scratch.copy(from = child, into = parent) }
            .onFailure { log.warn("Could not bring what session {} kept across to session {}: {}", child, parent, it.message) }
        val held = future?.takeIf { !it.isCancelled }?.let { runCatching { it.get() as? String }.getOrNull() }
        if (held != null) {
            val read = runCatching { mapper.readTree(held) }.getOrNull()
            if (read != null) {
                return buildMap {
                    read.path("answer").takeIf { it.isString }?.let { put("answer", it.stringValue()) }
                    read.path("contentKey").takeIf { it.isString }?.let { put("contentKey", it.stringValue()) }
                    read.path("error").takeIf { it.isString }?.let { put("error", it.stringValue()) }
                }
            }
        }
        /*
         * No answer held - the ask ran before a restart. Read how it ended off its
         * own conversation: the last line it said, or the note that it could not
         * answer. Only the answer lines were read, so an ask that said "I'll start
         * by gathering..." and then failed came back as though that were its answer.
         */
        val last = lines.latest(
            child,
            listOf(io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT, io.mszymanski.orknux.server.llm.LlmSessionEventKind.SYSTEM),
            org.springframework.data.domain.PageRequest.of(0, 1),
        ).firstOrNull() ?: return emptyMap()
        val said = last.content?.takeIf { it.isNotBlank() } ?: return emptyMap()
        if (last.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.SYSTEM) {
            // The ending the conversation writes on a failure; any other note is about a round, not the ask.
            if (said.contains(" could not answer: ")) return mapOf("error" to said)
            // Or the asker's own stop, which is not an answer however much it said before.
            if (said.startsWith(STOPPED_BY)) return mapOf("stopped" to said)
            val answered = lines.latest(
                child, listOf(io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT), org.springframework.data.domain.PageRequest.of(0, 1),
            ).firstOrNull()?.content?.takeIf { it.isNotBlank() } ?: return emptyMap()
            return withKey(parent, child, mapOf("answer" to answered))
        }
        return withKey(parent, child, mapOf("answer" to said))
    }

    /**
     * The key the answer was kept under, where it still is. The future that
     * named it is gone once the ask is over (#616), and the key is the one
     * [keyFor] always writes, so the store is what says whether it was kept.
     */
    private fun withKey(parent: Long, child: Long, answer: Map<String, Any?>): Map<String, Any?> {
        val key = "answer.$child"
        return if (scratch.has(parent, key)) answer + ("contentKey" to key) else answer
    }

    /**
     * How many asks this conversation made that are still running, read off the
     * futures this process holds - the one thing that knows for certain.
     *
     * For a turn ending with work out: a workflow step parks on it rather than
     * finishing, and the answer landing wakes it. See [SessionInbox].
     */
    /** How long `agent_wait` was asked to wait, however the model wrote it: "300" arrives as often as 300. */
    internal fun waitSecondsIn(arguments: String): Int {
        val node = runCatching { mapper.readTree(arguments) }.getOrNull()?.path(SECONDS)
        return when {
            node == null -> null
            node.isNumber -> node.intValue()
            node.isTextual -> node.stringValue().trim().toIntOrNull()
            else -> null
        } ?: tuning[Tunable.AGENT_WAIT_DEFAULT_SECONDS]
    }

    /** Whether the ask that opened [session] is still working: its turn is going, and reads its inbox between rounds. */
    fun working(session: Long): Boolean = running[session]?.isDone == false

    fun stillWorking(parent: Long?): Int {
        if (parent == null) return 0
        return held.findByParentSessionIdOrderByCreatedAtAscIdAsc(parent)
            .count { session -> session.id?.let { running[it] }?.isDone == false }
    }

    /** The asked agent's name off its setup record, which says `agent` - `name` is read for older rows. */
    private fun nameIn(details: String?): String? = details?.let { held ->
        runCatching {
            val read = mapper.readTree(held)
            (read.path("agent").takeIf { it.isTextual } ?: read.path("name")).stringValue()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Asks one of them, and answers with what it said.
     *
     * A specialist that could not answer is reported rather than thrown: the
     * agent that asked is mid-round and can say so, try another of them, or
     * finish - and a failure that ended the whole turn would lose the work it
     * had already done.
     */
    /**
     * @param parent the session the asking agent is in, or null where it is in
     *   none. The asked agent gets a session of its own under it - see
     *   [io.mszymanski.orknux.server.llm.LlmSessionRecorder.openUnder] - so what
     *   it did can be read, beside what asked for it. Issue #379.
     */
    fun run(agent: Agent, arguments: String, parent: Long? = null): String {
        val asked = text(arguments, AGENT)?.trim().orEmpty()
        val question = text(arguments, QUESTION)?.trim().orEmpty()
        val title = text(arguments, TITLE)?.trim()?.ifEmpty { null } ?: titleOf(question)

        if (asked.isEmpty() || question.isEmpty()) {
            return refusal("Say which agent to ask and what to ask it: $AGENT and $QUESTION are both needed.")
        }

        val named = granted(agent, parent)
        val wanted = named.firstOrNull { it.name.equals(asked, ignoreCase = true) }
            ?: return refusal(
                "You have not been given an agent called \"$asked\". " +
                    "You may ask: ${named.joinToString(", ") { it.name }}.",
            )

        if (!wanted.enabled) return refusal("${wanted.name} is not active, so it was not asked.")
        val modelId = wanted.modelId ?: return refusal("${wanted.name} has no model chosen, so it cannot answer.")

        /*
         * The same agent asked again in the same conversation carries on where
         * it left off. A follow-up - "refine what you found" - went to a new
         * session that had never seen the first answer, so the agent searched
         * from nothing and the asker read two sessions where one conversation
         * had happened. Asked with `fresh`, it starts over instead.
         */
        val fresh = flag(arguments, FRESH)
        val earlier = if (parent != null && !fresh) earlierAsk(parent, wanted) else null
        if (parent != null && earlier != null) return askAgain(agent, wanted, modelId, earlier, parent, question)

        /*
         * The bound on fan-out, counted against the conversation the asker is
         * in: every ask so far is a session under it, so the sessions are the
         * count. Told as a number and a reason, so the model finishes with
         * what it has rather than trying the same ask in other words. A round
         * in no session has nothing to count against and is not bounded here;
         * the rounds bound still holds it. Issue #380. A continued ask opens
         * no session, so it is not counted.
         */
        val allowed = limitFor(agent)
        val spent = parent?.let { held.findByParentSessionIdOrderByCreatedAtAscIdAsc(it).size } ?: 0
        if (parent != null && spent >= allowed) {
            return refusal(
                "You have asked $spent other agents in this conversation, which is all this workspace " +
                    "allows. Answer with what you have.",
            )
        }

        /*
         * Its own briefing, and a turn holding nothing but the question. The
         * specialist is not shown the conversation it was asked from: it has no
         * business reading it, and a question that needed it would be a question
         * the agent asking should have written out.
         *
         * Composed once: the same string is the system turn and the record of
         * what this agent was working under. Issues #454, #456 - see below.
         */
        val system = briefing.of(wanted)
        val turns = buildList {
            system?.let { add(ChatTurn("system", it)) }
            add(ChatTurn("user", question))
        }

        /*
         * Asked as itself, with no agents of its own. `sub` is the same row with
         * the grant list emptied - the one level this feature has, enforced by
         * there being nothing to call rather than by a counter to get round.
         *
         * Not saved, and not the row Spring is managing: a detached copy, so
         * emptying the list here cannot reach the database.
         */
        val sub = without(wanted)

        /*
         * Its own conversation, written down. The question goes in under the
         * asking agent's name, because that is who said it, and what the asked
         * agent does with it - the tools it calls, what it answers - is
         * recorded by the round the same way any agent's is. Issue #379.
         */
        val into = parent?.let { above -> sessions.openUnder(above, title) }

        // The asked agent gets the session's tools, scoped to its own session,
        // so a pad the asker shared is one it can read and add to - the same
        // document, worked on by both. Issue #411. A subagent in no session gets
        // only what needs none.
        //
        // Built before the question goes in, because the account of the setup
        // below names what was lent as well as what was granted.
        val lent = handing(sub, into)

        if (into != null) {
            /*
             * The setup this agent answered under, first thing in its own
             * session. Issue #456: an agent node and a task both write this line
             * where an agent starts responding, and a subagent's session did not
             * - so the one conversation in the family whose agent nobody chose
             * opened with a tool call and never said which agent had been asked,
             * on which model, with what prompt or holding what. Which is the
             * first question anybody reading a subagent's log has.
             *
             * Of `sub` rather than of the row: that is the agent the round was
             * given, and the one difference between them - no agents of its own
             * to ask - is a difference in the tools the model was handed, which
             * is precisely what this record is for.
             */
            sessions.describeAgent(into, details.getObject().snapshot(sub, lent, system))
            sessions.userSaid(into, agent.name, question)
        }

        /*
         * Started, not waited for. Issue #462.
         *
         * This used to run the whole conversation here and hand back what came
         * of it, which meant an agent asking three specialists asked them one
         * after another and waited out all three - and could do nothing in
         * between, because its own turn was inside the first call. Two asks
         * that have nothing to do with each other took as long as the sum of
         * them.
         *
         * So the work goes onto a thread and the tool answers at once with the
         * session it runs in. The asker carries on, and when it has nothing
         * left it calls `agent_wait`, which is a sleep that ends early when
         * something lands. Reading what came back is `agent_asks`, which
         * already existed.
         *
         * Where there is no session there is nothing to hand back a handle to
         * and nothing to poll, so that case is answered the old way, and once:
         * with no session there is no step to read a retry policy from, so
         * [retrying] asks it a single time.
         */
        if (into == null) {
            val ask = { conversations.getObject().answer(modelId, sub, turns, into = null, shed = lent, gated = false) }
            return answerOf(wanted, retrying(parent, null, first = ask, again = ask), parent, null)
        }

        val askedAt = Instant.now()
        val failed: (Throwable) -> Unit = { why ->
            log.warn("An ask of {} did not finish: {}", wanted.name, why.message)
            parent?.let { asker ->
                runCatching {
                    inbox.post(
                        asker,
                        io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                        "${wanted.name} could not finish what you asked about \"$title\"${asked(askedAt)}: ${why.message}",
                    )
                }
            }
        }
        val limit = atOnceFor(agent)
        val waits = wouldWait(parent ?: into, limit)
        val handed = launch(into, gateKey = parent ?: into, limit = limit, failed = failed) { hangup ->
            /*
             * Its reasoning written into its own session, as a task's and an
             * agent node's are. Without it the asked agent's session read as a
             * request, a lookup and an answer, with the half-minute it spent
             * thinking in between shown as nothing at all (session 569).
             */
            val thinking = io.mszymanski.orknux.server.llm.SessionThinking(into, wanted.name, sessions)
            val said = try {
                retrying(
                    parent, into, hangup = hangup,
                    first = { conversations.getObject().answer(modelId, sub, turns, into = into, shed = lent, watch = thinking, gated = false, hangup = hangup) },
                    again = { conversations.getObject().answer(modelId, sub, resumed(into, sub, modelId, system), into = into, shed = lent, watch = thinking, gated = false, hangup = hangup) },
                )
            } finally {
                thinking.settle()
            }
            /*
             * The answer is put where the asker will look for it rather than
             * returned: nobody is waiting on this thread. `agent_asks` reports
             * it and the key is what carries the text. And told to the asker,
             * which is what wakes it if its turn has ended - an agent that
             * finished without waiting still gets the answer it was owed.
             */
            answerOf(wanted, said, parent, into).also { answered ->
                // Stopped by the asker: it knows, and an answer now would only wake it again.
                if (hangup.hungUp) return@also
                parent?.let { asker ->
                    inbox.post(
                        asker,
                        io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                        "${wanted.name} has answered what you asked about \"$title\"${asked(askedAt)}:\n$answered",
                    )
                }
            }
        }
        if (!handed) return refusal("Asks are not running just now; try again in a moment.")

        return mapper.writeValueAsString(
            linkedMapOf(
                "agent" to wanted.name,
                "about" to title,
                "session" to into,
                "working" to true,
                "waiting" to waits,
                "note" to (if (waits) "It is not started yet, and its answer is not here." + waitNote(limit) + " "
                else "It has started, and its answer is not here yet. ") +
                    "Carry on with anything else you have; " + whenNothingLeft(parent),
            ),
        )
    }

    /**
     * A turn of the asked agent's, in its own session, on the asking pool.
     *
     * The ask's first turn and every turn it is woken for later go through
     * here, so they share the one account of what is working - [running], which
     * `agent_asks`, `agent_wait` and a parked step all read - and the asking
     * conversation's permits, so a subagent woken by its timer is counted
     * against the same `agentMaxSubagentsAtOnce` as one being asked.
     *
     * Answers false where it was not started: the pool is shut, or a turn is
     * already running in [into] - which reads its inbox between rounds itself,
     * so a second one beside it would only answer the same thing twice.
     */
    private fun launch(into: Long, gateKey: Long, limit: Int, failed: (Throwable) -> Unit, work: (io.mszymanski.orknux.connector.model.Hangup) -> String): Boolean {
        val hangup = io.mszymanski.orknux.connector.model.Hangup()
        /*
         * Sized by the asking agent when the conversation's first ask starts,
         * and kept while any of its asks hold a claim: a number changed
         * mid-conversation applies from the next time all of them are over.
         */
        val gate = requireNotNull(
            atOnce.compute(gateKey) { _, held ->
                (held ?: Gate(limit.coerceAtLeast(1))).apply { users++ }
            },
        )
        /*
         * Who prompted the ask, carried onto the thread that answers it: the
         * person is held per thread, and the asked agent's calls were booked
         * to nobody because they ran on another one.
         */
        val prompter = io.mszymanski.orknux.server.usage.Prompting.current()
        val prompted: () -> String = if (prompter == null) { { work(hangup) } } else { { io.mszymanski.orknux.server.usage.Prompting.by(prompter) { work(hangup) } } }
        // A Callable by name: a bare lambda resolves to the Runnable constructor, whose future holds null. Issue #536.
        val guarded = java.util.concurrent.Callable {
            val permits = gate.permits
            if (!permits.tryAcquire()) {
                waitingForSlot += into
                try {
                    permits.acquire()
                } finally {
                    waitingForSlot -= into
                }
            }
            permitHeld.set(permits)
            runCatching(prompted)
                // Stopped by the asker, it is not news to the asker: an interrupted wait throws here.
                .onFailure { if (!hangup.hungUp) failed(it) }
                .also {
                    // Released only where still held: a wait between tries may have given it back.
                    val still = permitHeld.get()
                    permitHeld.remove()
                    still?.release()
                }
                // The answer itself, for agent_asks to hand back - not the Result around it. Issue #536.
                .getOrNull()
        }
        /*
         * Forgotten the moment it is over, finished or cancelled. Issue #616:
         * the future held the whole answer and nothing ever took it out, so a
         * server that answered asks for a week held every answer of the week.
         * Once it is done the answer is in the asker's store and the asked
         * agent's transcript, which is where [asked] reads it from.
         *
         * `done()` rather than a `finally` in the work, because it runs after
         * the future is marked done and also for a task cancelled before it
         * ever ran - and registered before it is handed over, so even an ask
         * that is over at once cannot finish before it is in the map.
         *
         * And a look at the inbox on the way out: a reminder that came due
         * after the turn's last look at it, and before it ended, was announced
         * while the turn still counted as running, so nothing would bring the
         * agent back to it. Only after a turn that answered - one that threw
         * may have thrown before taking what was due, and would be woken into
         * the same throw for ever.
         */
        val started = object : java.util.concurrent.FutureTask<String?>(guarded) {
            override fun done() {
                running.remove(into, this)
                hangups.remove(into, hangup)
                letGo(gateKey)
                if (!isCancelled && runCatching { get() }.getOrNull() != null) {
                    runCatching { if (owed(into)) published.publishEvent(io.mszymanski.orknux.server.llm.SessionEventDue(into)) }
                }
            }
        }
        val claimed = running.compute(into) { _, there -> if (there != null && !there.isDone) there else started }
        if (claimed !== started) {
            letGo(gateKey)
            return false
        }
        hangups[into] = hangup
        val handed = runCatching { requireNotNull(asking).execute(started) }.isSuccess
        if (!handed) started.cancel(false)
        return handed
    }

    /** The session [wanted] was last asked in under [parent], or null where it has not been. */
    private fun earlierAsk(parent: Long, wanted: Agent): Long? =
        held.findByParentSessionIdOrderByCreatedAtAscIdAsc(parent)
            .lastOrNull { agentIdIn(it.agentDetails) == wanted.id }
            ?.id

    /** The agent a session's setup line names - `agentId`, written before the first question. */
    private fun agentIdIn(details: String?): Long? = details?.let { setup ->
        runCatching { mapper.readTree(setup).path("agentId").takeIf { it.isNumber }?.longValue() }.getOrNull()
    }

    /**
     * When an ask was put, said beside its answer once it is old enough to be
     * mistaken for something else.
     *
     * An answer is left in the asker's inbox and read at the asker's next
     * turn, which may be a turn for something else entirely: a subagent that
     * spent twenty minutes failing came back just as the person wrote a new
     * message in the Slack thread, and its answer sat under that message
     * looking like the reply to it. Saying it was asked at 12:58, twenty
     * minutes ago, is what tells the asker it belongs to an earlier question.
     * Nothing for an answer that came back within [Tunable.AGENT_ANSWER_DATED_AFTER_SECONDS] - that is the
     * ordinary case of an agent waiting for it.
     */
    internal fun asked(at: Instant, now: Instant = Instant.now()): String {
        val age = Duration.between(at, now)
        if (age < Duration.ofSeconds(tuning[Tunable.AGENT_ANSWER_DATED_AFTER_SECONDS].toLong())) return ""
        val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(at.atZone(installation.workingZone()))
        val minutes = age.toMinutes()
        val ago = if (minutes < 120) "$minutes minutes ago" else "${age.toHours()} hours ago"
        return ", which you asked at $clock, $ago"
    }

    /**
     * An asked agent's turn, and the turns again that the asker's retry policy
     * allows, on a failure that may pass.
     *
     * A subagent's model call that ran into a rate limit - Azure's, inside a
     * stream that had already answered 200 - came back as "could not answer"
     * to the agent that asked, and nothing asked again: the retry policy on the
     * workflow node is the node's, and what failed was not the node's own turn
     * but a turn it had asked for. So the asker answered without it, or gave
     * up, and the retries somebody had set on the node never ran.
     *
     * The policy is the one the ask came from: the step running in the asker's
     * session, or in the nearest session above it, so an agent a subagent asks
     * is held to the same node. Where none is - a chat, a task - there is no
     * policy and nothing is asked again, as for the asker's own turn. A failure
     * the provider says is permanent is never asked again.
     *
     * Waited out on the ask's own thread, which nobody is blocked on: the asker
     * carries on or waits in `agent_wait`, as it would for a slow answer. And
     * the wait is written into the asked agent's session, where whoever reads
     * it would otherwise see a failure and then, minutes later, an answer.
     */
    internal fun retrying(
        parent: Long?,
        into: Long?,
        first: () -> ChatCompletion,
        again: () -> ChatCompletion,
        pause: (Duration) -> Unit = ::pauseAside,
        /** Pulled by [stop]: nothing more is asked once the asker has given up on it. */
        hangup: io.mszymanski.orknux.connector.model.Hangup? = null,
    ): ChatCompletion {
        var answer = first()
        if (answer !is ChatCompletion.Failed || answer.permanent) return answer
        val policy = policyAbove(parent) ?: return answer
        val began = System.nanoTime()
        var spent = 1
        while (spent < policy.attempts && hangup?.hungUp != true) {
            val failed = answer as? ChatCompletion.Failed ?: break
            if (failed.permanent) break
            val wait = policy.waitAfter(spent, Random)
            val budget = policy.budget
            if (budget != null && Duration.ofNanos(System.nanoTime() - began) + wait > budget) break
            log.info("An asked agent's turn failed ({}); asking again in {}s", failed.reason, wait.toSeconds())
            into?.let {
                sessions.note(
                    it,
                    io.mszymanski.orknux.server.watcher.WatcherFollowUp.retryNote(
                        failed.reason, spent + 1, policy.attempts, wait, Instant.now(), installation.workingZone(),
                    ),
                )
            }
            pause(wait)
            if (hangup?.hungUp == true) break
            spent++
            answer = again()
        }
        return answer
    }

    /**
     * The retry policy of the step an ask was made from: the step in [session],
     * or in the nearest session above it. A few levels at most - asks go one
     * deep, and a session's parents are not a chain anybody should walk far.
     */
    private fun policyAbove(session: Long?): RetryPolicy? {
        var at = session
        repeat(POLICY_LEVELS) {
            val here = at ?: return null
            steps.findFirstBySessionIdOrderByIdDesc(here)?.retryPolicy()?.let { return it }
            at = held.findByIdOrNull(here)?.parentSessionId
        }
        return null
    }

    /**
     * What a retried ask is sent: its briefing and the session as it now
     * stands. The question is already in it, written before the first attempt,
     * and so is whatever its tools returned before the call failed - so the
     * agent carries on from there rather than doing the work twice, which on a
     * subagent that had read forty files is most of the cost of the failure.
     */
    private fun resumed(session: Long, sub: Agent, modelId: Long, system: String?): List<ChatTurn> = buildList {
        system?.let { add(ChatTurn("system", it)) }
        addAll(conversationIn(session, sub, modelId))
    }

    /**
     * What an asked agent's session has said so far, as turns to send again:
     * what was sent last time where it was kept, or the log rebuilt within the
     * agent's share of its model. Shared with [SubagentWake], which wakes the
     * same sessions and needs the same history.
     */
    fun conversationIn(session: Long, sub: Agent, modelId: Long): List<ChatTurn> {
        val shares = budgets.getObject()
        val ceiling = shares.promptCeiling(sub.memoryShare, sub.workspaceId, modelId)
        return prompt.getObject().history(session, ceiling) ?: run {
            val budget = shares.budget(sub.memoryShare, sub.workspaceId, modelId)
            listOfNotNull(sessionSheds.getObject().standing(session)?.let { ChatTurn("user", it) }) +
                sessions.remembered(session, budget) + sessions.recalled(session, budget)
        }
    }

    /**
     * A follow-up put to an agent already asked in this conversation, in the
     * session it answered in - so it reads what it was asked and what it said,
     * and the asker's family shows one conversation with it rather than two.
     */
    private fun askAgain(agent: Agent, wanted: Agent, modelId: Long, session: Long, parent: Long, question: String): String {
        if (running[session]?.isDone == false) {
            return refusal(
                "${wanted.name} is still working on what you asked it before. " +
                    (if (waits(parent)) "Call $WAIT, read it with $ASKS, and then ask your follow-up."
                    else "End your turn saying what you are doing; its answer starts you again, and you ask your follow-up then."),
            )
        }
        val sub = without(wanted)
        val lent = handing(sub, session)
        val title = held.findByIdOrNull(session)?.title ?: "what you asked"
        val askedAt = Instant.now()
        // Read before the question is written into it, so the question is sent once.
        val history = conversationIn(session, sub, modelId)
        sessions.userSaid(session, agent.name, question)
        val turns = buildList {
            add(ChatTurn("system", briefing.of(wanted).orEmpty()))
            addAll(history)
            add(ChatTurn("user", question))
        }
        val failed: (Throwable) -> Unit = { why ->
            log.warn("A follow-up to {} did not finish: {}", wanted.name, why.message)
            runCatching {
                inbox.post(
                    parent,
                    io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                    "${wanted.name} could not finish your follow-up about \"$title\"${asked(askedAt)}: ${why.message}",
                )
            }
        }
        val limit = atOnceFor(agent)
        val waits = wouldWait(parent, limit)
        val grown = grownIn(session)
        val handed = launch(session, gateKey = parent, limit = limit, failed = failed) { hangup ->
            val thinking = io.mszymanski.orknux.server.llm.SessionThinking(session, wanted.name, sessions)
            val said = try {
                retrying(
                    parent, session, hangup = hangup,
                    first = { conversations.getObject().answer(modelId, sub, turns, into = session, shed = lent, watch = thinking, gated = false, hangup = hangup) },
                    again = { conversations.getObject().answer(modelId, sub, resumed(session, sub, modelId, briefing.of(wanted)), into = session, shed = lent, watch = thinking, gated = false, hangup = hangup) },
                )
            } finally {
                thinking.settle()
            }
            answerOf(wanted, said, parent, session).also { answered ->
                if (hangup.hungUp) return@also
                inbox.post(
                    parent,
                    io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                    "${wanted.name} has answered your follow-up about \"$title\"${asked(askedAt)}:\n$answered",
                )
            }
        }
        if (!handed) return refusal("Asks are not running just now; try again in a moment.")

        return mapper.writeValueAsString(
            linkedMapOf(
                "agent" to wanted.name,
                "about" to title,
                "session" to session,
                "continued" to true,
                "working" to true,
                "waiting" to waits,
                "questions" to grown.questions,
                "inputTokensSoFar" to grown.inputTokens,
                "note" to "It carries on the conversation you already had with it, so it knows what it " +
                    "answered before." + (if (waits) waitNote(limit) else "") +
                    " This is question ${grown.questions} in that conversation, which has taken " +
                    "${grown.inputTokens} input tokens so far, and every round of it reads the whole of it " +
                    "again. A follow-up on the same job belongs here; for a job unrelated to what it was asked " +
                    "before, ask with $FRESH: true instead. Its answer is not here yet: " + whenNothingLeft(parent),
            ),
        )
    }

    /**
     * The permit the ask running on this thread holds, while it holds one -
     * so a wait between tries can give it back. See [pauseAside].
     */
    private val permitHeld = ThreadLocal<java.util.concurrent.Semaphore?>()

    /**
     * Waits out a retry's backoff without holding a slot. A rate-limited ask
     * slept thirty seconds and then sixty on its permit, so with one at a time
     * allowed every other ask of the conversation waited the whole of it for
     * an ask that was doing nothing. Given back for the wait and taken again
     * after it - behind any that started meanwhile, which is the order a
     * second try deserves.
     */
    private fun pauseAside(wait: Duration) {
        val held = permitHeld.get() ?: return Thread.sleep(wait.toMillis())
        // Not held while asleep: stopped in its sleep, there is nothing to give back.
        permitHeld.set(null)
        held.release()
        Thread.sleep(wait.toMillis())
        held.acquire()
        permitHeld.set(held)
    }

    /** Whether [session]'s asker stopped it with agent_stop, and has asked it nothing since. */
    private fun stoppedBefore(session: Long): Boolean {
        val stop = lines.findFirstBySessionIdAndKindAndContentStartingWithOrderByIdDesc(
            session, io.mszymanski.orknux.server.llm.LlmSessionEventKind.SYSTEM, STOPPED_BY,
        )?.id ?: return false
        // A follow-up after the stop is the asker wanting it again.
        return !lines.existsBySessionIdAndKindAndIdGreaterThan(session, io.mszymanski.orknux.server.llm.LlmSessionEventKind.USER, stop)
    }

    /** How far one ask's conversation has grown: the questions put to it, and the input tokens it has taken. */
    private data class Grown(val questions: Long, val inputTokens: Long)

    /**
     * Said to the asker beside every follow-up and in `agent_asks`. One
     * subagent conversation on prod was continued for twenty unrelated
     * repository audits over six hours, each follow-up reading all of the
     * earlier ones again, until every round took forty seconds. Not capped -
     * a follow-up is what a long job wants - but said, so the asker can see
     * when the job has changed and start over with `fresh`.
     */
    private fun grownIn(session: Long): Grown = Grown(
        questions = lines.countBySessionIdAndKind(session, io.mszymanski.orknux.server.llm.LlmSessionEventKind.USER),
        inputTokens = held.findByIdOrNull(session)?.spentInputTokens ?: 0,
    )

    /** Whether something is due and unread in a subagent's session. */
    private fun owed(session: Long): Boolean =
        inbox.nextDue(session)?.isAfter(java.time.OffsetDateTime.now()) == false

    /**
     * Runs the asked agent again in its own session, for what arrived there
     * after its turn ended - a reminder it set coming due, a watcher it set
     * firing. See [SubagentWake], which decides when; this is the how, and it
     * is the ask's own: the same pool, the same permits, and the answer left
     * for the agent that asked, whose own waker brings it back - a chat by
     * [ChatWake], a task by its service, a parked step by
     * [io.mszymanski.orknux.server.llm.SessionWake].
     *
     * @param turns what the turn is sent, built on the turn's own thread - so
     *   the inbox it takes is only taken once the turn is really going.
     * @return false where nothing was started: the session is not an ask's, a
     *   turn is running in it already, or the pool is shut.
     */
    fun resume(session: Long, wanted: Agent, modelId: Long, turns: (Agent) -> List<ChatTurn>): Boolean {
        val row = held.findByIdOrNull(session) ?: return false
        val parent = row.parentSessionId ?: return false
        if (running[session]?.isDone == false) return false
        /*
         * Stopped by the agent that asked: a timer or a watcher it had set
         * coming up afterwards is not a reason to start it again. It would
         * answer, and the asker that was told "nothing more will come back"
         * would be woken by it - and would read the ask as answered, not stopped.
         * A follow-up the asker puts to it later is a new question, and goes
         * through askAgain, not here.
         */
        if (stoppedBefore(session)) {
            runCatching { inbox.take(session) }
            log.info("Session {} was stopped by its asker; what came up there wakes nobody", session)
            return false
        }
        val title = row.title ?: "what you asked"
        val sub = without(wanted)
        val lent = handing(sub, session)
        val failed: (Throwable) -> Unit = { why ->
            log.warn("{} could not be woken in session {}: {}", wanted.name, session, why.message)
        }
        return launch(session, gateKey = parent, limit = atOnceIn(parent), failed = failed) { hangup ->
            val thinking = io.mszymanski.orknux.server.llm.SessionThinking(session, wanted.name, sessions)
            val said = try {
                retrying(
                    parent, session, hangup = hangup,
                    first = { conversations.getObject().answer(modelId, sub, turns(sub), into = session, shed = lent, watch = thinking, gated = false, hangup = hangup) },
                    again = { conversations.getObject().answer(modelId, sub, resumed(session, sub, modelId, briefing.of(wanted)), into = session, shed = lent, watch = thinking, gated = false, hangup = hangup) },
                )
            } finally {
                thinking.settle()
            }
            answerOf(wanted, said, parent, session).also { answered ->
                if (hangup.hungUp) return@also
                inbox.post(
                    parent,
                    io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                    "${wanted.name} has come back about \"$title\" on its own - a reminder or watcher it " +
                        "set went off - and says:\n$answered",
                )
            }
        }
    }

    /**
     * What an ask came to, written where the asker will find it.
     *
     * The same three cases as before, and the same key: a subagent that wrote a
     * page answers with the page, and the only handle the asker had was the
     * text - so it typed ten thousand characters into an upload and the output
     * cap cut them off. Issue #393.
     */
    private fun answerOf(wanted: Agent, said: ChatCompletion, parent: Long?, into: Long?): String = when (said) {
        is ChatCompletion.Answered -> mapper.writeValueAsString(
            buildMap {
                put("agent", wanted.name)
                put("answer", said.content)
                keyFor(parent, child = into, said.content)?.let { put("contentKey", it) }
            },
        )

        is ChatCompletion.Failed -> refusal("${wanted.name} could not answer: ${said.reason}")

        /*
         * The loop runs tools to a conclusion, so nothing that comes back here
         * is still asking for one. A round that ended this way ended on the
         * specialist's own tools rather than on the moment.
         */
        is ChatCompletion.CalledTools -> refusal("${wanted.name} asked for a tool that could not be run.")
    }

    /**
     * The key the answer is kept under in the asker's session, or null where
     * there is no session to keep it in or the store would not take it - said
     * by leaving the key out, so the tool never promises a key it cannot keep.
     */
    private fun keyFor(parent: Long?, child: Long?, answer: String): String? {
        if (parent == null || answer.isEmpty()) return null
        /*
         * First what the asked agent's own tools kept - a picture it drew, a
         * file it saved - because the keys its answer names are in *its*
         * store, and the asker's tools read the asker's. Copied up before the
         * answer is keyed, so the answer's key is the last one in.
         */
        if (child != null) scratch.copy(from = child, into = parent)
        val key = "answer." + (child ?: System.nanoTime())
        // A JSON-encoded *string*: the sandbox parses what it reads, and an
        // upload door requires what comes out to be the text itself.
        val refused = scratch.put(parent, key, mapper.writeValueAsString(answer), io.mszymanski.orknux.workflow.script.StoredKind("text/markdown", false))
        return if (refused == null) key else null
    }

    /**
     * The session's tools the asked agent works with - the same set every turn
     * takes, see [SessionSheds] - and what it is told about handing a
     * scratchpad back. Issue #458.
     *
     * A subagent asked for something long writes it into a pad and then puts
     * the whole of it in its answer as well, because the answer is the only
     * thing it believes reaches the asker - so a page is paid for twice and
     * trimmed once. It is not the only thing: a pad it shares is one the agent
     * that asked can read and edit, the same document rather than a copy. So
     * the paragraph the scratchpad shed already says about having working files
     * gains the part only a subagent needs, and it is added here rather than in
     * [ScratchpadTools] because it is true of nothing else that is lent them.
     */
    private fun handing(sub: Agent, into: Long?): ToolShed? = sheds(
        sessionSheds.getObject().of(sub, into),
        into?.let { Handing },
    )

    /**
     * The paragraph alone, lent beside the session's tools: it holds no tool,
     * so the grant filter has nothing to take from it and the briefing is
     * carried through as any shed's is.
     */
    private object Handing : ToolShed {
        override fun specs(): List<ToolSpec> = emptyList()
        override fun handles(name: String): Boolean = false
        override fun run(call: ToolCall): String = error("lends no tool")
        override fun briefing(): String =
            "You are answering another agent, not a person. Anything long you make - a page, a file, " +
                "a list - goes in a scratchpad, and the scratchpads are the conversation's: the agent " +
                "that asked you reads and edits the same files, and a file it already has is one you " +
                "edit rather than copy. Nothing has to be shared. So your answer says which pad the " +
                "work is in and what is in it in a line or two, and never repeats the whole of it. " +
                "What you write twice is paid for twice and cut off once."
    }

    /**
     * The agents this one may ask: its grant list, in its own workspace, as
     * rows - and, in a conversation, the temporary agents it created there.
     */
    private fun granted(agent: Agent, session: Long? = null): List<Agent> = agent.agents
        .mapNotNull { agents.findByIdOrNull(it) }
        .filter { it.workspaceId == agent.workspaceId && it.id != agent.id } +
        (session?.takeIf { !agent.temporary }?.let { agents.findTemporaryBySession(it) }.orEmpty()
            .filter { it.createdByAgentId == agent.id && it.workspaceId == agent.workspaceId })

    /**
     * The same agent with nothing to delegate to.
     *
     * A copy rather than the row: emptying the managed entity's list would be
     * the grant being deleted the next time the transaction flushed, which is a
     * feature quietly removing itself from the database.
     */
    private fun without(agent: Agent): Agent = Agent(
        id = agent.id,
        workspaceId = agent.workspaceId,
        name = agent.name,
        type = agent.type,
        description = agent.description,
        modelId = agent.modelId,
        systemPrompt = agent.systemPrompt,
        enabled = agent.enabled,
        maxRounds = agent.maxRounds,
        memoryShare = agent.memoryShare,
        // Asked, it thinks as hard as it was set to.
        reasoningEffort = agent.reasoningEffort,
        orknuxAccess = agent.orknuxAccess,
        boxLabelsText = agent.boxLabelsText,
        // The built-ins ride in these two like every other tool now (#444), so
        // the copy carries both or the specialist is offered a different set.
        tools = agent.tools.toMutableList(),
        requiredTools = agent.requiredTools.toMutableList(),
        /*
         * And what it hides and holds only to hand on, or an asked agent is
         * offered every built-in it hid and every tool it was to pass on - and
         * a temporary agent, which hides every built-in it was not given, would
         * be handed all of them.
         */
        hiddenTools = agent.hiddenTools.toMutableList(),
        delegatedTools = agent.delegatedTools.toMutableList(),
        connections = agent.connections.toMutableList(),
        skillCatalogs = agent.skillCatalogs.toMutableList(),
        memoryCatalogs = agent.memoryCatalogs.toMutableList(),
        secretCatalogs = agent.secretCatalogs.toMutableList(),
        variableCatalogs = agent.variableCatalogs.toMutableList(),
        mcpServers = agent.mcpServers.toMutableList(),
        // The whole of the rule: nothing to ask, so nothing further to reach.
        agents = mutableListOf(),
    )

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    /** The first line of the question, cut to a title's length, where no title was given. */
    private fun titleOf(question: String): String =
        question.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_LENGTH) ?: "Asked"

    private fun text(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).takeIf { it.isTextual }?.stringValue()
    }.getOrNull()

    /** A yes, sent as a boolean or as the word a model sometimes writes instead. */
    private fun flag(arguments: String, name: String): Boolean = runCatching {
        mapper.readTree(arguments).path(name).let { it.asBoolean(false) || it.asString("").equals("true", ignoreCase = true) }
    }.getOrDefault(false)

    companion object {
        /** How far up from the asker [policyAbove] looks for a step. */
        private const val POLICY_LEVELS = 4


        const val ASK = "ask_agent"

        /** What this conversation has asked, and how it is going. Issue #477. */
        const val ASKS = "agent_asks"

        /** The agents this one may ask, with their setups. Issue #552. */
        const val LIST = "agent_list"

        /** Waiting for one of them, now that asking does not wait. Issue #462. */
        const val WAIT = "agent_wait"

        /** Stopping one, or all, of them. */
        const val STOP = "agent_stop"
        const val SESSION = "session"

        /** How the line a stop writes into the asked agent's transcript begins; [cameBack] reads it. */
        const val STOPPED_BY = "Stopped by "
        const val SECONDS = "seconds"

        // How long a wait is when not told, and the longest it may be, are
        // Tunable.AGENT_WAIT_DEFAULT_SECONDS and AGENT_WAIT_LONGEST_SECONDS: long
        // enough to be worth calling, and bounded because a model that may
        // sleep for an hour will. Issue #453.

        /** How often the wait looks up, so it notices the others finishing too. */
        const val POLL_NANOS = 500_000_000L
        const val AGENT = "agent"
        const val QUESTION = "question"
        const val TITLE = "title"
        const val FRESH = "fresh"

        /** What a title falls back to being cut at, matching the column. */
        const val TITLE_LENGTH = 200
    }
}
