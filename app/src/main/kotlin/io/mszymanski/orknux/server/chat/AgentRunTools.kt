package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
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
 * otherwise run it - the rule the orknux tools and the shells already keep.
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
    /** The working files lent to the asked agent, so a shared pad reaches it. Issue #411. */
    private val scratchpads: ScratchpadTools,
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
    private val atOnce = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.Semaphore>()

    /**
     * Whether this agent has anybody to ask. A grant list of none offers
     * nothing, and so does a workspace - or an installation - that allows no
     * asks at all: a tool that refuses every call is a tool the model should
     * not be shown. Issue #380.
     */
    fun offered(agent: Agent): Boolean = granted(agent).isNotEmpty() && limitFor(agent) > 0

    /**
     * How many other agents this one may ask in a conversation: the
     * workspace's own number where it has one, Admin -> Settings otherwise.
     */
    fun limitFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.agentMaxSubagents ?: installation.agentMaxSubagents()

    fun specFor(agent: Agent): ToolSpec {
        val named = granted(agent)
        return ToolSpec(
            name = ASK,
            // Started, not answered, since #462 - and it said "answers with what it said" for a
            // release after, so a model expected the answer to come back to it by itself.
            description = "Puts a question to one of the other agents you have been given. It starts the " +
                "agent and returns at once; the answer is not in this call's result. To get it you must " +
                "wait for it yourself - " + WAIT + " now, or finish_answer with a wake-up to be started " +
                "again later - and then read it with " + ASKS + ". Use it where the work needs something you have no tool " +
                "for and one of them does - it will look things up in its own conversation, so ask " +
                "for what you want to know rather than for the steps. It cannot see this " +
                "conversation, so say everything it needs in the question. The answer comes back " +
                "as text and under a contentKey as well, and any key the answer names - a file it " +
                "saved, a picture it drew - works in this conversation too: to upload or send what " +
                "it made, pass the key to the tool that takes one rather than typing the text back, " +
                "which is what cuts a long file off. Where you want something long made - a page, a " +
                "file, a list of a hundred things - say in the question that it should be written " +
                "into a scratchpad, and the answer will name the pad: you then read and " +
                "edit that pad yourself instead of it being typed back at you. To see what each " +
                "of them can do - its tools, skills and connections - call " + LIST + " first. You may ask: " +
                named.joinToString(", ") { "${it.name} (${it.description ?: "no description"})" },
            parameters = listOf(
                ToolParameterSpec(
                    name = AGENT,
                    description = "Which of them to ask, by name: " + named.joinToString(", ") { it.name },
                    required = true,
                ),
                ToolParameterSpec(
                    name = QUESTION,
                    description = "What to ask it, in full. It has none of your context, so a " +
                        "question that refers to \"the issue\" or \"that file\" cannot be answered.",
                    required = true,
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
        description = "Lists the agents you may ask, with what each can do: its description, model, " +
            "tools, skills, memories and connections. Call it before asking, to pick the agent that " +
            "holds the tool the work needs, rather than asking one and hearing it cannot.",
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
    fun listed(agent: Agent): String {
        val described = granted(agent).map { asked ->
            val setup = mapper.readTree(details.getObject().snapshot(without(asked), null, null))
            val tools = (setup.path("tools").toList().map { it.stringValue() } + setup.path("findable").toList().map { it.stringValue() })
                .distinct().sorted()
            linkedMapOf(
                "name" to asked.name,
                "description" to asked.description,
                "model" to setup.path("model").takeIf { it.isTextual }?.stringValue(),
                "tools" to tools,
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
        description = "Lists the agents you have asked in this conversation and how each one is going: " +
            "what it was asked about, whether it is still working, and when it last did anything. " +
            "Also says how many asks you have left. Call it when somebody asks what your agents are " +
            "doing, and before asking again where you are not sure whether an earlier ask is finished.",
        parameters = emptyList(),
    )

    /** What `agent_wait` is, to a model. Issue #462. */
    fun waitSpec(): ToolSpec = ToolSpec(
        name = WAIT,
        description = "Waits for the agents you have asked. Call it when you have nothing else to do and " +
            "something you asked for has not come back yet. It returns as soon as one of them finishes, " +
            "or after the seconds you gave, whichever is first - so waiting costs nothing when the work " +
            "is already done. Then call " + ASKS + " to see what came back. Do not call this before you " +
            "have run out of other work: an ask runs while you carry on.",
        parameters = listOf(
            ToolParameterSpec(
                SECONDS,
                "How long to wait at most, in seconds. Up to " + MOST_WAIT_SECONDS + "; left out, " +
                    SOME_WAIT_SECONDS + ".",
                required = false,
            ),
        ),
    )

    fun handles(name: String): Boolean = name == ASK || name == ASKS || name == WAIT || name == LIST

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
        val seconds = wanted.coerceIn(1, MOST_WAIT_SECONDS)

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
     * Still working is read the way the sessions list reads it: a line opened
     * and not finished, or something written within the installation's own
     * window. Deliberately the same rule and deliberately not the same code -
     * the screen's copy checks a run is running, which needs two more
     * repositories than this tool has any business holding.
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
        val window = installation.sessionsActiveWindowSeconds().toLong()
        val recently = java.time.OffsetDateTime.now().minusSeconds(window)

        val listed = children.map { session ->
            val id = session.id
            val future = id?.let { running[it] }
            // Still going where its future says so; otherwise by the transcript, as before.
            val working = if (future != null) {
                !future.isDone
            } else {
                id in open || session.lastEventAt?.isAfter(recently) == true
            }
            linkedMapOf<String, Any?>(
                "asked" to (nameIn(session.agentDetails) ?: "an agent"),
                "about" to session.title,
                "working" to working,
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
                if (!working && id != null) putAll(cameBack(id, future))
            }
        }
        val allowed = limitFor(agent)
        return mapper.writeValueAsString(
            linkedMapOf(
                "asks" to listed,
                "working" to listed.count { it["working"] == true },
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
    private fun cameBack(child: Long, future: java.util.concurrent.Future<*>?): Map<String, Any?> {
        val held = future?.let { runCatching { it.get() as? String }.getOrNull() }
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
            val answered = lines.latest(
                child, listOf(io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT), org.springframework.data.domain.PageRequest.of(0, 1),
            ).firstOrNull()?.content?.takeIf { it.isNotBlank() } ?: return emptyMap()
            return mapOf("answer" to answered)
        }
        return mapOf("answer" to said)
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
        } ?: SOME_WAIT_SECONDS
    }

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

        /*
         * The bound on fan-out, counted against the conversation the asker is
         * in: every ask so far is a session under it, so the sessions are the
         * count. Told as a number and a reason, so the model finishes with
         * what it has rather than trying the same ask in other words. A round
         * in no session has nothing to count against and is not bounded here;
         * the rounds bound still holds it. Issue #380.
         */
        val allowed = limitFor(agent)
        val spent = parent?.let { held.findByParentSessionIdOrderByCreatedAtAscIdAsc(it).size } ?: 0
        if (parent != null && spent >= allowed) {
            return refusal(
                "You have asked $spent other agents in this conversation, which is all this workspace " +
                    "allows. Answer with what you have.",
            )
        }

        val named = granted(agent)
        val wanted = named.firstOrNull { it.name.equals(asked, ignoreCase = true) }
            ?: return refusal(
                "You have not been given an agent called \"$asked\". " +
                    "You may ask: ${named.joinToString(", ") { it.name }}.",
            )

        if (!wanted.enabled) return refusal("${wanted.name} is not active, so it was not asked.")
        val modelId = wanted.modelId ?: return refusal("${wanted.name} has no model chosen, so it cannot answer.")

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

        // The asked agent gets scratchpad tools scoped to its own session, so a
        // pad the asker shared is one it can read and add to - the same document,
        // worked on by both. A subagent in no session gets none. Issue #411.
        //
        // Built before the question goes in, because the account of the setup
        // below names what was lent as well as what was granted.
        val lent = handing(into)

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
         * and nothing to poll, so that case is answered the old way - which is
         * the workflow node, where there is no turn to carry on with either.
         */
        if (into == null) {
            return answerOf(wanted, conversations.getObject().answer(modelId, sub, turns, into = null, shed = lent), parent, null)
        }

        val permits = atOnce.computeIfAbsent(parent ?: into) {
            java.util.concurrent.Semaphore(installation.agentMaxSubagentsAtOnce())
        }
        // A Callable by name: a bare lambda resolves to submit(Runnable), whose future holds null. Issue #536.
        val started = asking?.submit(java.util.concurrent.Callable {
            permits.acquire()
            runCatching {
                /*
                 * Its reasoning written into its own session, as a task's and an
                 * agent node's are. Without it the asked agent's session read as
                 * a request, a lookup and an answer, with the half-minute it spent
                 * thinking in between shown as nothing at all (session 569).
                 */
                val thinking = io.mszymanski.orknux.server.llm.SessionThinking(into, wanted.name, sessions)
                val said = try {
                    conversations.getObject().answer(modelId, sub, turns, into = into, shed = lent, watch = thinking)
                } finally {
                    thinking.settle()
                }
                /*
                 * The answer is put where the asker will look for it rather
                 * than returned: nobody is waiting on this thread. `agent_asks`
                 * reports it and the key is what carries the text.
                 */
                answerOf(wanted, said, parent, into).also { answered ->
                    /*
                     * And told to the asker, which is what wakes it if its turn
                     * has ended. An agent that finished without waiting still
                     * gets the answer it was owed.
                     */
                    parent?.let { asker ->
                        inbox.post(
                            asker,
                            io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                            "${wanted.name} has answered what you asked about \"$title\":\n$answered",
                        )
                    }
                }
            }
                .onFailure { why ->
                    log.warn("An ask of {} did not finish: {}", wanted.name, why.message)
                    parent?.let { asker ->
                        runCatching {
                            inbox.post(
                                asker,
                                io.mszymanski.orknux.server.llm.SessionEventKind.ANSWER,
                                "${wanted.name} could not finish what you asked about \"$title\": ${why.message}",
                            )
                        }
                    }
                }
                .also { permits.release() }
                // The answer itself, for agent_asks to hand back - not the Result around it. Issue #536.
                .getOrNull()
        })
        if (started == null) return refusal("Asks are not running just now; try again in a moment.")
        running[into] = started

        return mapper.writeValueAsString(
            linkedMapOf(
                "agent" to wanted.name,
                "about" to title,
                "session" to into,
                "working" to true,
                "note" to "It has started, and its answer is not here yet. Carry on with anything else " +
                    "you have; call " + WAIT + " when you have nothing left, then " + ASKS + " to read " +
                    "what came back. Do not end your turn before you have read it: nothing brings you " +
                    "back to an answer you finished without.",
            ),
        )
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
     * The scratchpads the asked agent works in, and what it is told about
     * handing one back. Issue #458.
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
    private fun handing(into: Long?): ToolShed? {
        val pads = scratchpads.shed(into) ?: return null
        return object : ToolShed by pads {
            override fun briefing(): String = listOfNotNull(
                pads.briefing(),
                "You are answering another agent, not a person. Anything long you make - a page, a file, " +
                    "a list - goes in a scratchpad, and the scratchpads are the conversation's: the agent " +
                    "that asked you reads and edits the same files, and a file it already has is one you " +
                    "edit rather than copy. Nothing has to be shared. So your answer says which pad the " +
                    "work is in and what is in it in a line or two, and never repeats the whole of it. " +
                    "What you write twice is paid for twice and cut off once.",
            ).joinToString(separator = System.lineSeparator() + System.lineSeparator())
        }
    }

    /** The agents this one may ask: its grant list, in its own workspace, as rows. */
    private fun granted(agent: Agent): List<Agent> = agent.agents
        .mapNotNull { agents.findByIdOrNull(it) }
        .filter { it.workspaceId == agent.workspaceId && it.id != agent.id }

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
        orknuxAccess = agent.orknuxAccess,
        shellAccess = agent.shellAccess,
        // The built-ins ride in these two like every other tool now (#444), so
        // the copy carries both or the specialist is offered a different set.
        tools = agent.tools.toMutableList(),
        requiredTools = agent.requiredTools.toMutableList(),
        connections = agent.connections.toMutableList(),
        skillCatalogs = agent.skillCatalogs.toMutableList(),
        memoryCatalogs = agent.memoryCatalogs.toMutableList(),
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

    companion object {

        const val ASK = "ask_agent"

        /** What this conversation has asked, and how it is going. Issue #477. */
        const val ASKS = "agent_asks"

        /** The agents this one may ask, with their setups. Issue #552. */
        const val LIST = "agent_list"

        /** Waiting for one of them, now that asking does not wait. Issue #462. */
        const val WAIT = "agent_wait"
        const val SECONDS = "seconds"

        /** Long enough to be worth calling, short enough that a turn is not lost to it. */
        const val SOME_WAIT_SECONDS = 30

        /** A model that may sleep for an hour will. */
        const val MOST_WAIT_SECONDS = 300

        /** How often the wait looks up, so it notices the others finishing too. */
        const val POLL_NANOS = 500_000_000L
        const val AGENT = "agent"
        const val QUESTION = "question"
        const val TITLE = "title"

        /** What a title falls back to being cut at, matching the column. */
        const val TITLE_LENGTH = 200
    }
}
