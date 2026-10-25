package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ToolCall
import tools.jackson.databind.ObjectMapper
import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.AuditRedaction
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Somebody watching a round happen, rather than reading it afterwards.
 *
 * The round already writes everything down — into an LLM session, where a task's
 * page follows it live and a transcript keeps it for good. This is for the
 * caller that has a reader waiting on the other end of an open connection and
 * wants the same facts as they occur: the chat window, where an agent's answer
 * used to be a spinner for a minute and then a paragraph, with no account of the
 * three lookups in between.
 *
 * Not a replacement for the recording, and pointedly not a second one. Every
 * method here is called beside the [LlmSessionRecorder] call that keeps the
 * same fact, so a watcher that throws or a caller that provides none changes
 * what is kept by nothing at all.
 *
 * **A call is identified by where it came in the round**, counted from nought
 * across every round the answer took. Not by the provider's call id, which is
 * the model's to choose and has been an empty string on more than one
 * OpenAI-compatible server, and not by the session line's id, which is null
 * whenever nothing is being recorded — a chat with a bare model, or a session
 * write that failed. A reader pairing a result with the call it belongs to
 * needs a handle that always exists.
 *
 * Every method does nothing by default, so a watcher implements the part it has
 * a place to put.
 *
 * **What arrives here has had its credentials taken out**, by the same
 * [io.mszymanski.orknux.server.workspace.AuditRedaction] and in the same two
 * strengths the session is written with: the full rule set over a call's
 * arguments, which are a command line, and only what is a credential on sight
 * over a result, which is arbitrary output a model has to be able to read. It
 * is the *same string* the recorder is handed, computed once where the round
 * forks - not a second redaction that could come to a different answer. So a
 * lookup reads the same on a screen watching it happen as it does on the page
 * that reads it back tomorrow, which is issue #291. A watcher does not redact
 * again and must not put any of this in front of a model: what the model is
 * given is the round's own `conversation`, unredacted, and that is deliberate.
 */
interface RoundWatch {

    /** What the model thought before it did anything. Empty is never sent. */
    fun thinking(text: String) = Unit

    /**
     * The model has begun writing its answer, so it has stopped thinking.
     *
     * The other way a round's reasoning ends, and the one nothing used to say.
     * [called] covers the model that decided to look something up; this covers
     * the model that decided to answer - and for a prompt whose answer is long,
     * that is the whole of the round. Without it a watcher drawing the thinking
     * has no way to know it is over until the round is, so a block of reasoning
     * sat unfinished on the screen for however many minutes the answer took,
     * counting up and cut off wherever the last flush happened to fall. See
     * [io.mszymanski.orknux.server.llm.SessionThinking].
     *
     * Sent on the first piece of the answer and on every one after it, because
     * it is a fact about the round rather than an event to be counted - a
     * watcher acts on the first and ignores the rest.
     */
    fun answering() = Unit

    /**
     * A round's thinking is over: all of it, and how long it went on for as
     * the client measured it.
     *
     * The pieces arrive through [thinking] and say nothing about time, so a
     * watcher drawing each round's thinking had only its own clock - which
     * read "0.0 seconds" for reasoning that arrived in one burst and nothing
     * at all for a round it never saw go live. This is the round's own
     * figure, sent once the round has ended and before anything it went on to
     * do. Never sent for a round that thought nothing.
     */
    fun thought(text: String, millis: Long) = Unit

    /**
     * A piece of the answer, as it arrives. For a reader that shows the answer
     * being written - a research's report on its page - rather than how much
     * of it there is, which is [writing].
     */
    fun answered(piece: String) = Unit

    /**
     * A picture the round drew, as the markdown that shows it.
     *
     * Written into the thread the moment it is drawn - see [ChatPictures.draw]
     * for why - which is a fact the open chat has no other way of learning.
     * Nothing announced it before, so a drawn picture sat in the conversation
     * unseen until somebody reloaded the page, and a model told not to repeat
     * the link left the round looking as though it had described a picture
     * rather than drawn one.
     */
    fun drew(markdown: String) = Unit

    /**
     * A file the round put on the chat, the moment it is filed.
     *
     * The chat draws each file under the message it came with, and one filed
     * during a turn belongs to that turn's answer - which the open chat has no
     * other way to learn about until somebody reloads the page.
     */
    fun filed(id: Long, filename: String, contentType: String, sizeBytes: Long) = Unit

    /**
     * A sound to play to the person now, through their speakers, at [url]. Only
     * a voice conversation is lent the tool that says this, and only the voice
     * panel listening to the round does anything with it.
     */
    fun played(url: String, filename: String, contentType: String) = Unit

    /**
     * The chat's widgets as they stand after a widget tool changed them - all of
     * them, so a page replaces what it draws rather than patching it.
     */
    fun widgets(widgets: List<io.mszymanski.orknux.server.widget.WidgetView>) = Unit

    /**
     * What the model wrote beside the calls it asked for - "Let me read the
     * tests next" - before any of them run.
     *
     * The progress it reports between steps. The session kept it, so a reload
     * showed it, but nothing told the open chat: somebody watching a long piece
     * of work saw a thinking block and a column of lookups, while the agent's
     * own account of what it was doing waited for a refresh.
     */
    fun said(text: String) = Unit

    /**
     * How far the model has got writing: [tool] is the call it is spelling out,
     * or null for the answer, and [chars] how much of it has arrived.
     *
     * Told at most about once a second, by [WritingProgress]. A model putting a
     * whole file into one call writes for minutes after its thinking ends and
     * before the call is made, and until this nothing arrived in that time to
     * say it was working at all.
     */
    fun writing(tool: String?, chars: Int) = Unit

    /**
     * How far the server has got reading the prompt, before the model writes
     * anything: [processed] of [total] tokens, [cached] of them reused from its
     * cache. Told at most as often as [writing], by [WritingProgress], and only
     * by a llama.cpp provider - nothing else reports it. Issue #627.
     */
    fun reading(total: Long, cached: Long, processed: Long) = Unit

    /** A call, the moment it is dispatched and before its tool has run. */
    fun called(at: Int, tool: String, arguments: String) = Unit

    /**
     * And what that call gave back.
     *
     * @param failed whether the tool could not be run, as opposed to running
     *   and answering unhelpfully. The distinction is the model's already — it
     *   is told either way and can try something else — and it is the reader's
     *   too: a lookup that failed explains an answer that a lookup which merely
     *   returned nothing does not.
     */
    fun returned(at: Int, result: String, failed: Boolean) = Unit
}

/**
 * One round's writing, told to a [RoundWatch] at most once a [everyMillis].
 *
 * The stream reports every frame, and a model writes forty a second; a page
 * redrawn that often is a page that cannot be read, and a session told that
 * often is a lock taken that often. The first report goes at once, so the line
 * appears the moment writing starts.
 */
internal class WritingProgress(
    private val watch: RoundWatch,
    /** The Admin setting Writing Progress Interval, read when the round starts. */
    private val everyMillis: Long = EVERY_MILLIS,
    /**
     * The Admin setting Reading Progress Shown After: how long the server has to
     * have been reading before anybody is told. A read that ends sooner is never
     * shown - most do, once the prompt cache holds, and the card flashed for each.
     */
    private val readingShownAfterMillis: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var said = 0
    private var told = Long.MIN_VALUE
    private var read = Long.MIN_VALUE
    private var readingSince = Long.MIN_VALUE

    /**
     * The server has read this much of the prompt. Throttled on its own clock,
     * so the first word of the answer is told at once rather than waiting out
     * the last report of reading; the report that finishes the read always goes.
     */
    fun reading(progress: io.mszymanski.orknux.connector.model.PromptProgress) {
        val now = clock()
        if (readingSince == Long.MIN_VALUE) readingSince = now
        if (now - readingSince < readingShownAfterMillis) return
        val done = progress.processed >= progress.total
        if (!done && read != Long.MIN_VALUE && now - read < everyMillis) return
        read = now
        watch.reading(progress.total, progress.cached, progress.processed)
    }

    /** A piece of the answer arrived. */
    fun answer(piece: String) {
        said += piece.length
        tell(null, said)
    }

    /** The arguments of [tool] have got to [chars], counted across the round's calls. */
    fun calling(tool: String, chars: Int) = tell(tool, chars)

    private fun tell(tool: String?, chars: Int) {
        val now = clock()
        if (told != Long.MIN_VALUE && now - told < everyMillis) return
        told = now
        watch.writing(tool, chars)
    }

    private companion object {
        /** The setting's own default, for a caller with no settings to read: often enough to read as moving. */
        const val EVERY_MILLIS = 1000L
    }
}

/**
 * Somebody who may have something to add between the rounds of one answer.
 *
 * A round is not one call. An agent with tools asks for a lookup, is told what
 * came back, asks for another, and only then answers - and that can be minutes
 * of wall clock in which the caller learns something the model ought to know.
 * For a task it is the obvious case: a person watching it work says "actually,
 * make it about hobbits", and the whole point of saying it *now* is that the
 * next three pictures should be of hobbits.
 *
 * Asked once per round, after that round's tool results have been threaded in
 * and before the model is called again, which is the only moment in the loop
 * where a new user turn is both safe and useful: safe because a tool result has
 * to follow its call immediately and by then they all have, and useful because
 * the very next thing that happens is the model reading the conversation.
 *
 * Whatever is returned is put in front of the model as a turn from a person, in
 * order. An empty list is the ordinary answer and costs nothing - which is why
 * this is a callback rather than a list: the caller cannot know in advance
 * whether anybody will say anything, and asking is a query it can make cheap.
 *
 * The implementer records what it hands over. This loop does not write it into
 * any session - it cannot know whose name it is under, or what marks it as
 * delivered - and a caller that hands out the same message twice will have it
 * read twice.
 */
fun interface Interjections {

    /** What has been said since the last time this was asked; empty is ordinary. */
    fun waiting(): List<String>
}

/**
 * Asking an agent something, and letting it use its tools before it answers.
 *
 * A model with tools does not answer in one round: it asks for a lookup, is told
 * what came back, and either asks again or answers. This runs that to a
 * conclusion and hands back the one thing the caller wanted — what the agent
 * finally said.
 *
 * The intermediate turns are not written to a chat's thread, which is the
 * conversation somebody had. They are kept for the model, though, where the
 * round has a session: every request is written to
 * [io.mszymanski.orknux.server.llm.SessionPrompt] just before it goes, so the
 * next turn sends exactly what this one sent and adds to it. A later turn that
 * re-reads three skill pages it already read costs a provider with a prompt
 * cache next to nothing; a later turn that rebuilt the conversation without
 * them made the provider read the whole of it again.
 *
 * No transaction is held while this runs. It calls a model repeatedly and can
 * take minutes; a database connection held for that long is one nobody else has.
 *
 * A caller that named an LLM session gets the round written down as it happens —
 * the tools that were called, what each of them gave back, anything the agent
 * said on the way, and what was finally said. A round is allowed to be both:
 * providers answer with a message and tool calls in one reply, and [record] says
 * what happens to that text and why the blank case is not written down. That is
 * not the same record as the chat history and does not contradict
 * the paragraph above: the history is the conversation somebody had, while a
 * session is the conversation the agent had, working included. A caller that
 * named no session pays for a null check and touches no table at all.
 *
 * The results are in that record because nothing else keeps them. They are
 * threaded into this round and the round is thrown away; what reaches the
 * history is the text the model wrote out of them. Kept only there, the next
 * turn is answered from what the model said about a lookup rather than from the
 * lookup — which is how two models running one conversation came to insist that
 * labelled issues were unlabelled, each correcting itself only when it called
 * the tool again.
 */
@Service
class AgentConversation(
    private val models: ModelChatClient,
    private val spend: io.mszymanski.orknux.server.llm.SessionSpend,
    private val tools: AgentTools,
    /** Whether the provider hands over every tool up front; see [ToolCeilings]. */
    private val ceilings: ToolCeilings,
    /** What lets an agent find a tool rather than carry it; see [ToolSearchTools]. */
    private val searching: ToolSearchTools,
    private val agents: AgentRepository,
    private val sessions: LlmSessionRecorder,
    /** Where the installation's ceiling on tool rounds is kept and changed. */
    private val settings: InstallationSettings,
    /** For a workspace's own ceiling on repeated calls; see [repeatsFor]. Issue #516. */
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    /** Summarises a turn that has outgrown its model, rather than losing it. Issue #522. */
    private val compaction: ChatCompaction,
    /** What arrived for the session while it worked: answers, timers; see [SessionInbox]. */
    private val inbox: io.mszymanski.orknux.server.llm.SessionInbox,
    /** The walls between one turn and the rest of the server; see [io.mszymanski.orknux.server.agent.Bulkheads]. #616. */
    private val bulkheads: io.mszymanski.orknux.server.agent.Bulkheads,
    /**
     * What the session's model was sent, kept so the next turn sends it again
     * exactly; see [io.mszymanski.orknux.server.llm.SessionPrompt]. Written
     * here and nowhere else, just before each call, so what is kept is what
     * went.
     */
    private val prompt: io.mszymanski.orknux.server.llm.SessionPrompt,
    /** Where an agent that could not answer is written down, for Admin > Reports > Errors. */
    private val errors: io.mszymanski.orknux.server.errors.ErrorRecorder,
    /** Which tools change nothing, for a turn held to those; see [ToolEffects]. Issue #653. */
    private val effects: ToolEffects,
    /** The model's window, and the cut that holds a tool result to a share of it; see [TurnWindow]. */
    private val window: TurnWindow,
    /**
     * The system turn and tool array a session sends in front of its messages,
     * the same in every request; see [io.mszymanski.orknux.server.llm.SessionFrames].
     */
    private val frames: io.mszymanski.orknux.server.llm.SessionFrames,
    /** How many tools that arrived late are described whole past the provider's cap: as many as one tool_load loads. */
    private val tuning: io.mszymanski.orknux.server.tuning.Tuning = io.mszymanski.orknux.server.tuning.Tuning.DEFAULTS,
) {

    /**
     * A new frame: the system turn and tool array this turn would send if the
     * session had never sent one.
     *
     * What [ToolCeilings.split] carries up front, the finder where it left
     * anything to be found, what was already loaded where it still fits - a
     * frame made again after a compaction takes the tools that arrived late
     * into its array, since the prompt is read again from the start anyway -
     * and [ToolRun] last, for whatever arrives after. Nothing at all for an
     * agent holding nothing, which answers in one call.
     */
    private fun framed(
        agent: Agent,
        modelId: Long,
        stamp: Long,
        start: Carrying,
        lent: List<ToolSpec>,
        briefedTurns: List<ChatTurn>,
        found: Set<String>,
        heldNow: List<ToolSpec>,
    ): io.mszymanski.orknux.server.llm.SessionFrames.Frame {
        val up = start.core + start.lent
        val room = (start.limit - up.size - ToolSearchTools.OFFERED - ToolRun.SLOTS).coerceAtLeast(0)
        val folded = if (start.hunting) {
            found.mapNotNull { name -> start.findable.firstOrNull { it.name == name } }.take(room)
        } else {
            emptyList()
        }
        val left = start.findable.filterNot { spec -> folded.any { it.name == spec.name } }
        val finding = if (start.hunting) searching.shed(left, mutableSetOf(), room = { 0 }).specs() else emptyList()
        val array = if (up.isEmpty() && !start.hunting) emptyList() else up + finding + folded + ToolRun.SPEC
        val carried = array.map { it.name }.toSet()
        val system = BuiltInTools.listed(ConversationFrame.systemOf(briefedTurns), lent, carried, start.hunting)
        return io.mszymanski.orknux.server.llm.SessionFrames.Frame(
            // An agent never saved answers only outside a session, where the frame is not kept.
            agentId = agent.id ?: -1L,
            modelId = modelId,
            agentStamp = stamp,
            system = system,
            tools = array,
            hunting = start.hunting,
            seenSystem = system,
            seenTools = heldNow.associate { it.name to io.mszymanski.orknux.server.llm.SessionFrames.shapeOf(it) },
        )
    }

    /** Sessions already told that not every tool could go up front; told once each, while this server runs. */
    private val overCap: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * How many rounds this agent gets: its own number, its workspace's, or the installation's.
     *
     * Eight was written into the code here, and it was right for an agent with
     * two tools and wrong for one holding twenty - a list, a load and a lookup
     * is three rounds before the work starts, and what the agent had gathered by
     * then was thrown away with the refusal. The number is a setting now, and an
     * agent whose work is longer than the rest carries its own.
     */
    private fun roundsFor(agent: Agent): Int = agent.maxRounds
        // Then the workspace's own number, where it has decided one; then the installation's.
        ?: workspaces.findByIdOrNull(agent.workspaceId)?.chatMaxRounds
        ?: settings.chatMaxRounds()

    /**
     * How many identical calls in a row this agent's workspace allows.
     * Issue #516.
     */
    private fun repeatsFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.maxRepeatedToolCalls ?: settings.maxRepeatedToolCalls()

    /** And how close together they have to be to count. Issue #516. */
    private fun repeatWindowFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.repeatedToolCallsWindowSeconds
            ?: settings.repeatedToolCallsWindowSeconds()

    /** How many times a turn here may be compacted before it gives up. Issue #522. */
    private fun compactionsFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.sessionCompactionAttempts
            ?: settings.sessionCompactionAttempts()

    /**
     * Whether this is the window rather than the request.
     *
     * Matched on the words because that is all a provider gives: there is no
     * code for it in the OpenAI shape and every server words it differently -
     * llama.cpp says "exceeds the available context size", OpenAI says "maximum
     * context length". Matched loosely and on purpose: the cost of reading one
     * of these wrongly is one summary that was not needed, and the cost of
     * missing one is the turn.
     */
    private fun tooLarge(why: String): Boolean {
        val said = why.lowercase()
        return OVERSIZED.any { it in said }
    }

    /**
     * The same turn, with its middle summarised. Null where nothing can be done
     * and the caller should fail as it used to.
     */
    private fun shrinkToFit(
        agent: Agent,
        modelId: Long,
        turns: List<ChatTurn>,
        overflow: ContextOverflow?,
        attempt: Int,
        harder: Boolean,
    ): Shrunk? {
        val workspace = workspaces.findByIdOrNull(agent.workspaceId)
        /*
         * The turn's own model unless a workspace named another. A summary of
         * what an agent has been doing is ordinary work, so the model that was
         * already answering can write one; naming a cheaper one is a saving
         * somebody may want and not a default worth assuming for them.
         */
        val summariser = workspace?.sessionCompactionModelId ?: modelId
        val keep = if (harder) 1 else workspace?.sessionCompactionKeepTurns ?: settings.sessionCompactionKeepTurns()
        val budget = workspace?.sessionCompactionSummaryTokens ?: settings.sessionCompactionSummaryTokens()
        /*
         * What the turn must come to, from the model's real window: the
         * provider's own numbers where its refusal gave them, its row or a
         * learned window otherwise. Halved each attempt after the first.
         */
        val sent = turns.sumOf { it.content.length + it.reasoning.orEmpty().length + it.asked.sumOf { call -> call.arguments.length } }
        val chars = window.budget(modelId, sent, overflow, attempt)
        return runCatching { compaction.shrink(turns, summariser, budget, keep, chars) }
            .onFailure { log.warn("A turn could not be shrunk to fit", it) }
            .getOrNull()
    }

    /**
     * What the session says about a shrink. With the summary itself: the steps
     * it stands in for are gone from what the model sees, so what it was told
     * instead is the one account of them a reader can check.
     */
    private fun shrunkNote(shrunk: Shrunk): String {
        val did = listOfNotNull(
            shrunk.summary?.let { "its earlier steps were replaced by a summary" },
            shrunk.trimmed.takeIf { it > 0 }?.let { n -> "$n of its longest " + (if (n == 1) "step was" else "steps were") + " cut to fit" },
        ).joinToString(" and ")
        return "The turn outgrew the model's window, so $did and it carried on." +
            (shrunk.summary?.let { "\n\n$it" } ?: "")
    }

    /** How many calls one message from this agent may ask for. Issue #518. */
    private fun callsAtOnceFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.maxToolCallsAtOnce ?: settings.maxToolCallsAtOnce()

    /** And how often it is told before the turn ends. Issue #516. */
    private fun loopWarningsFor(agent: Agent): Int =
        workspaces.findByIdOrNull(agent.workspaceId)?.repeatedToolCallWarnings
            ?: settings.repeatedToolCallWarnings()

    /**
     * The same thing, for a caller holding only an id — the streaming endpoint,
     * which is outside the transaction that read the session.
     */
    fun answer(
        modelId: Long,
        agentId: Long,
        turns: List<ChatTurn>,
        into: Long? = null,
        shed: ToolShed? = null,
        watch: RoundWatch? = null,
        hangup: Hangup? = null,
        interjections: Interjections? = null,
        /** See the same parameter on the other [answer]. */
        reasoningEffort: String? = null,
        /** Whether only the tools that change nothing are offered and run; see the other overload. */
        readOnly: Boolean = false,
    ): ChatCompletion {
        val agent = agents.findByIdOrNull(agentId)
            ?: return ChatCompletion.Failed("That agent no longer exists")
        return answer(modelId, agent, turns, into, shed, watch, hangup, interjections, reasoningEffort = reasoningEffort, readOnly = readOnly)
    }

    /**
     * @param turns the conversation so far, briefing included.
     * @param into the LLM session this round is recorded in, or null for a round
     *   nobody is keeping. Null is the ordinary case — a chat keeps its own
     *   history and needs none of this.
     * @param shed tools the caller is lending the agent for this round only,
     *   offered alongside its own and asked first. Null is the ordinary case.
     *   See [ToolShed] for what one is for and why it is a parameter here rather
     *   than something [AgentTools] knows about.
     * @return what the agent said, or why it could not say anything.
     * @param watch somebody following the round as it happens, or null for the
     *   ordinary caller that only wants the answer. See [RoundWatch]: it is
     *   told nothing that is not also written down, so a round with a watcher
     *   and a round without keep exactly the same record.
     * @throws AgentRoundHalted where a [shed] ended the round. The agent's own
     *   tools never throw — a tool that failed is a fact the model is told — so
     *   this can only happen to a caller that lent it one.
     * @param hangup somebody who may give up on the whole answer while it is
     *   still being worked out, or null for the caller that cannot. It reaches
     *   every round rather than only the one in flight, because an answer takes
     *   as many rounds as the agent wants and stopping the current call while
     *   letting the next one be made is not stopping anything. A round that
     *   finds it pulled comes back [ChatCompletion.Failed], which is where the
     *   loop already ends.
     */
    fun answer(
        modelId: Long,
        agent: Agent,
        turns: List<ChatTurn>,
        into: Long? = null,
        shed: ToolShed? = null,
        watch: RoundWatch? = null,
        hangup: Hangup? = null,
        interjections: Interjections? = null,
        /**
         * Whether this turn takes a place among the turns the server runs at once.
         * False for a subagent, which runs inside the turn that asked for it: a
         * parent holding the last place would otherwise wait on a child waiting
         * on it. Issue #616.
         */
        gated: Boolean = true,
        /**
         * Told every piece of prose the model writes before its last one: what
         * it said beside the tools it called, and an answer it was asked again
         * after because something arrived. Not its thinking, not its calls.
         * A workflow step hands all of it on, with the last answer, because
         * that is everything the agent wrote to whoever reads the step's output.
         */
        spoke: ((String) -> Unit)? = null,
        /**
         * Whether what the model writes beside its tool calls is kept from
         * whoever is reading: the agent's progress messages are None. It is
         * still recorded in the session; it is only not shown or handed on,
         * because a model told to write nothing there does not always listen.
         * A workflow node passes its own level's answer.
         */
        quiet: Boolean = agent.chattiness == io.mszymanski.orknux.server.agent.Chattiness.NONE,
        /**
         * An effort asked for in place of the model's own in every round of this
         * answer, or null for the model as configured: what a chat's composer
         * chose (#654). Not used by the summariser, which is another call.
         */
        reasoningEffort: String? = null,
        /**
         * Whether this turn may only look: offered the tools [ToolEffects] says
         * change nothing - its own and what was lent alike - and refused any
         * other it names anyway. A chat in plan mode. Issue #653.
         */
        readOnly: Boolean = false,
    ): ChatCompletion {
        fun refused(why: String) = ChatCompletion.Failed(why, permanent = false).also { record(into, agent, it) }
        val run = {
            bulkheads.heapRefusal()?.let { refused(it) }
                ?: held(modelId, agent, turns, into, shed, watch, hangup, interjections, spoke, quiet, reasoningEffort, readOnly)
        }
        return if (gated) bulkheads.turn(::refused, run) else run()
    }

    private fun held(
        modelId: Long,
        agent: Agent,
        turns: List<ChatTurn>,
        into: Long?,
        shed: ToolShed?,
        watch: RoundWatch?,
        hangup: Hangup?,
        interjections: Interjections?,
        spoke: ((String) -> Unit)?,
        quiet: Boolean,
        reasoningEffort: String?,
        readOnly: Boolean,
    ): ChatCompletion {
        /*
         * The agent's own ceiling, or none where the provider is handed every
         * tool up front - see [ToolCeilings]. Read once, here, so the list this
         * turn sends is decided once and is the same in every round.
         */
        val offersAll = ceilings.offersAll(modelId)
        val ceiling = if (offersAll) null else agent.maxTools
        /*
         * Less everything that changes something, where this turn may only look:
         * what it may run, and what a call to anything else is refused by.
         * Issue #653.
         */
        fun held(offering: Offering) = if (readOnly) effects.readOnly(offering) else offering
        /*
         * Everything it holds, and the part of that this turn may run. The tool
         * array is made from the whole: it is the session's, sent the same in
         * every turn (see [io.mszymanski.orknux.server.llm.SessionFrames]), and
         * a chat leaves plan mode without a new one. What this turn may not run
         * is refused by what runs a call, as a name never offered always was.
         */
        val whole = tools.offeringFor(agent, ceiling, into)
        val holding = held(whole)

        /*
         * What the caller lent, as this agent may use it. Issue #444.
         *
         * A lender lends what it always did - a note, a to-do list, the clock, a
         * scratchpad, the ending - and the agent's Tools list decides here, once,
         * which of those it is offered: a built-in hidden on the agent's page is
         * neither declared nor answered, whoever lent it. Then the same split
         * every other tool gets under a ceiling: marked Always and carried, or
         * left at Offer and found. A shed's own names - `task_done`, the chat's
         * drawing - are not the list's to switch and are carried as they were.
         */
        val lendingWhole = BuiltInTools.lentTo(agent, shed)
        // What of that this turn may run; the whole goes into the array, as the agent's own does.
        val lending = lendingWhole.let { if (readOnly) effects.readOnly(it) else it }
        val lent = lendingWhole?.specs().orEmpty()
        /*
         * What goes out from the first round and what is found, decided once,
         * before anything is loaded and by the rule the briefing and the
         * session's record use - see [ToolCeilings.split]. Asked of the
         * provider rather than assumed: OpenAI and Azure refuse the whole
         * request over 128 tools, and an agent granted more than that could not
         * answer at all. Under the cap every tool goes out and nothing about
         * the turn changes, which is most installations, and the reason the
         * search is not simply always on - finding costs a round.
         */
        val start = ceilings.split(agent, modelId, whole, lent) { tools.offeringFor(agent, it, into) }
        val limit = start.limit
        /** Everything this turn's agent holds, its own and lent, once each. */
        val heldNow = (lent + whole.core + whole.searchable).distinctBy { it.name }
        /*
         * And what the lent tools want said about themselves, put with the
         * briefing. A shed's descriptions say what its tools do; its briefing
         * says that the agent has them and when to reach for one, which is the
         * half a model acts on - see [ToolShed.briefing]. Issue #445.
         *
         * The lent tools go into the briefing's list (issue #546), and every
         * line of it is marked by what this turn starts out carrying - not by
         * what has been loaded since, so a tool_load leaves the system turn as
         * it was: a provider caching the prompt by its prefix reads all of it
         * again for one changed byte.
         */
        val briefedTurns = briefed(turns, lendingWhole?.briefing())

        /*
         * Every tool was meant to go up front and the provider's cap will not
         * take them: the rest are found as they would be anywhere else, and the
         * session says so once, because the reason it re-reads its prompt after
         * a tool_load is otherwise invisible.
         */
        if (offersAll && start.hunting && into != null && overCap.add(into)) {
            val holds = start.core.size + start.lent.size + start.findable.size
            sessions.note(
                into,
                "This agent holds $holds tools and its provider takes $limit in one request, so not every tool " +
                    "goes up front: the rest are found with ${ToolSearchTools.SEARCH} and loaded with ${ToolSearchTools.FIND}.",
            )
        }

        /*
         * What this agent has already loaded, and where it is kept.
         *
         * A tool searched for in one turn stays loaded for the rest of the
         * session rather than being searched for again: an agent asked a
         * follow-up would otherwise spend a round rediscovering the tool it
         * used a minute ago, and the second search is not guaranteed to return
         * what the first did. Kept whatever the frame, since a frame made again
         * puts what was loaded into its array.
         */
        val found = sessions.toolsFound(into).toMutableSet()

        /*
         * The frame: the system turn and the tool array, sent byte for byte the
         * same in every round and every turn of the session. See
         * [io.mszymanski.orknux.server.llm.SessionFrames] for why and for when
         * it is made again. Kept as it was where it is this agent's, on this
         * model, with the agent unedited since - a grant accepted in this
         * conversation counts as unedited, and is said instead.
         */
        val stamp = io.mszymanski.orknux.server.llm.SessionFrames.stampOf(agent.lastModifiedAt)
        val kept0 = into?.let { frames.read(it) }
            ?.takeIf { it.agentId == (agent.id ?: -1L) && it.modelId == modelId && it.agentStamp == stamp }
        var frame = kept0 ?: framed(agent, modelId, stamp, start, lent, briefedTurns, found, heldNow)
            .also { made -> into?.let { frames.write(it, made) } }
        val carriedNames = frame.tools.map { it.name }.toSet()
        /*
         * What changed since the frame last said so - tools granted, gone, and
         * what the agent would be told now - as a message after the rest. Not
         * for a frame just made: it already says all of it.
         */
        val since = if (kept0 == null) {
            null
        } else {
            val fresh = BuiltInTools.listed(ConversationFrame.systemOf(briefedTurns), lent, carriedNames, frame.hunting)
            val (note, updated) = ConversationFrame.changes(frame, fresh, heldNow, found, tuning[io.mszymanski.orknux.server.tuning.Tunable.TOOLS_LOADED_AT_ONCE])
            if (updated != frame) {
                frame = updated
                into?.let { frames.write(it, updated) }
            }
            note
        }
        val told = ConversationFrame.withSystem(briefedTurns, frame.system)

        /*
         * The tool that does the finding, lent beside whatever the caller lent,
         * where the frame left things to be found. It reaches everything held
         * that the array does not carry; what it loads is called through
         * tool_run, so it never needs room in the array and never gives up a
         * tool to make some.
         */
        val findable = heldNow.filter { it.name !in carriedNames }
        val finder = if (!frame.hunting) {
            null
        } else {
            searching.shed(findable, found, room = { Int.MAX_VALUE }, carried = carriedNames)
        }
        val hunt = if (finder == null) lending else sheds(lending, finder)

        /** What every round declares: the frame's array, which does not move. */
        val offered = frame.tools
        /*
         * This turn's record of what it sends, for a turn kept in a session.
         * Every request below is written to it just before it goes, and the
         * final answer after it lands, so the next turn starts from exactly
         * what the model has already read rather than from a rebuild of it.
         */
        val kept = into?.let { prompt.turn(it) }
        /** How many finds were last written down; see the loop. */
        var recorded = found.size
        /** What changed since the frame last said so, as the turn it is read in. */
        val sinceTurn = since?.let { ChatTurn(role = "user", content = it) }
        if (offered.isEmpty()) {
            /*
             * An agent granted nothing answers in one call, and it streams for
             * a watcher on the same rule as the loop below: what it thinks
             * should appear while it is thinking it, not once it has finished.
             * Told at the end only where it was not streamed, or the thinking
             * would be drawn twice.
             */
            val told = told + listOfNotNull(sinceTurn)
            kept?.sent(told)
            val once = if (watch == null) {
                models.complete(modelId, told, hangup = hangup, reasoningEffort = reasoningEffort).also { told(watch, it) }
            } else {
                val writing = WritingProgress(watch, settings.writingProgressMillis().toLong(), settings.readingShownAfterSeconds() * 1000L)
                models.stream(
                    modelId,
                    told,
                    onThinking = { watch.thinking(it) },
                    hangup = hangup,
                    onCalling = writing::calling,
                    onReading = writing::reading,
                    reasoningEffort = reasoningEffort,
                ) {
                    watch.answering()
                    watch.answered(it)
                    writing.answer(it)
                }
            }
            into?.let { spend.add(it, modelId, once) }
            record(into, agent, once)
            if (once is ChatCompletion.Answered) kept?.sent(told + ChatTurn(role = "assistant", content = once.content))
            return once
        }

        val conversation = told.toMutableList()
        // What arrived while the session was not running - the answer that woke
        // it, a timer that came due - is the first thing the model reads.
        into?.let { session ->
            inbox.take(session).forEach { arrived -> conversation += ChatTurn(role = "user", content = arrived) }
        }
        // Then what changed since the frame last said so: a grant just accepted is answered above, and made usable here.
        sinceTurn?.let { conversation += it }
        var spent = 0L
        /*
         * And what the rounds cost, added up the same way the time is.
         *
         * What a turn cost is every round it took: a lookup the agent made
         * before it could answer was read by the model and charged for, and the
         * last round's own counts are a fraction of that - the same fraction
         * the last round's stopwatch is of what somebody waited. Reporting
         * either one alone would be a number that is smaller than the bill,
         * which is the worst kind of wrong for a number about money.
         */
        var input = 0L
        var output = 0L
        var cached = 0L
        var written = 0L
        /*
         * Where a call came in the whole round, not in the round it was made
         * in. See [RoundWatch]: it is the handle a reader pairs a result with
         * its call by, and an agent that looks something up twice over two
         * rounds must not hand out the same one twice.
         */
        var at = 0
        /*
         * What has been thought so far, added up across the rounds.
         *
         * A reasoning model does most of its thinking in the round where it
         * decides to look something up, and none of that reaches the caller
         * through the answer - which is the last round only. Kept here so the
         * completion that goes back carries the whole of it.
         */
        val thinking = StringBuilder()

        /* And how long it went on for, added up the same way. */
        var thoughtFor = 0L

        /*
         * Kept, but not always announced.
         *
         * A streamed round has already handed every piece of its reasoning to
         * the watcher as it arrived — that is the whole point of streaming it —
         * so saying it again at the end of the round would draw the thinking
         * twice. A blocking round hands over nothing on the way, and this is
         * the only chance it gets, which is why the choice is a parameter
         * rather than a rule.
         */
        fun thought(reasoning: String, millis: Long, announce: Boolean) {
            if (reasoning.isBlank()) return
            if (thinking.isNotEmpty()) thinking.append("\n\n")
            thinking.append(reasoning)
            thoughtFor += millis
            if (announce) watch?.thinking(reasoning)
            watch?.thought(reasoning, millis)
        }

        val rounds = roundsFor(agent)
        /*
         * How many rounds have been spent putting a refused reply back to the
         * model. Issue #465; see the Failed arm below for why there is a bound
         * at all and why it is small.
         */
        var retried = 0

        /*
         * How many times this turn has already been shrunk to fit. Issue #522.
         *
         * Separate from [retried] because it is a different failure with a
         * different remedy: a reply that could not be used is the model's
         * mistake and is put back to it, while a request too large for the
         * window is nobody's mistake and putting it back would send the same
         * oversized request again.
         */
        var compactions = 0

        /*
         * How many times this turn has re-asked after an empty answer. Its own
         * count, because it is neither the model's mistake nor a turn too big:
         * the provider sent nothing back at all.
         */
        var emptied = 0

        /*
         * The skills this turn has already read, by what they were asked
         * with, and the call that read each. Issue #531: a skill's page does
         * not change inside a turn and is already in the conversation, so a
         * second load answers with where it is rather than with the page again.
         */
        val skillsRead = mutableMapOf<String, String>()

        /*
         * What the last tool call answered with, where it failed, and whether
         * the model has already been told not to end on it. Issue #494.
         */
        var failedLast: String? = null

        /*
         * The same call, over and over. Issue #516.
         *
         * Two sessions in one afternoon spent their whole round budget in a
         * cycle: `todo_list {}` and `current_time {"timezone":"UTC"}` a hundred
         * and fifty-four times each, and `find_tools` with one query a hundred
         * and sixty-nine times. In the first the agent had already reasoned its
         * way to the answer - it had the tool, the chart kind and the data -
         * then second-guessed itself with "Wait, I should check" and never came
         * back.
         *
         * What makes these cycles is that nothing in them changes anything. The
         * same call with the same arguments answers the same thing, so the
         * context that produced the call is the context on the next turn, and
         * there is no way out from inside at any model size.
         *
         * The rounds ceiling does not help: it bounds total work, so it cannot
         * tell three hundred rounds of progress from one round three hundred
         * times, and a loop spends the whole budget before it stops. This
         * counts repetition instead - the call and what it answered, because a
         * clock read twice with two different answers is somebody working.
         */
        /*
         * When each identical call happened, per call, so the window can be
         * applied. Per call rather than for the last one only: a cycle of two
         * calls taking turns - read, edit, read, edit - never repeats the call
         * just before it, and counting only consecutive ones let it run the
         * whole budget. And per call rather than one list for all of them,
         * which counts a model doing ten different things quickly as one
         * repeating itself. A list rather than a count because the question is
         * how many fall *inside* the window, and the oldest fall out of it as
         * time passes.
         */
        val repeatsOf = HashMap<String, MutableList<Long>>()
        /*
         * How many times the loop guard has spoken up. Issue #516.
         *
         * Once is a warning: the turn has usually done real work before the
         * cycle started, and what is wanted is for the model to finish with
         * what it has - which it can only do if it is asked. Twice is the model
         * not listening, and then the turn ends rather than spending the rest
         * of the budget proving the point.
         */
        var loopWarnings = 0
        val warningsAllowed = loopWarningsFor(agent)
        val repeatsAllowed = repeatsFor(agent)
        val repeatWindow = repeatWindowFor(agent) * 1000L
        val callsAllowed = callsAtOnceFor(agent)
        var warnedOfFailure = false
        repeat(rounds) {
            /*
             * Told once and still going round, so the turn ends here. Issue
             * #516: a model that ignores being told it is repeating itself will
             * go on ignoring it, and the whole point of the guard is not to
             * spend the budget finding that out.
             */
            if (loopWarnings >= warningsAllowed) {
                into?.let { session ->
                    sessions.note(session, "The turn was ended: the same call was repeated after being told.")
                }
                return ChatCompletion.Failed(
                    "it repeated the same tool call after being told the limit of " + repeatsAllowed +
                        " identical calls within " + (repeatWindow / 1000) + " seconds, and the turn was ended",
                    permanent = false,
                ).also { record(into, agent, it) }
            }
            /*
             * The walls again, every round: the heap may have filled since the
             * turn began, and the results gathered so far are held to what one
             * turn may keep before they are sent once more. Issue #616.
             */
            bulkheads.heapRefusal()?.let { why ->
                into?.let { session -> sessions.note(session, "The turn was stopped: $why.") }
                return ChatCompletion.Failed(why, permanent = false).also { record(into, agent, it) }
            }
            if (bulkheads.bound(conversation) > 0) {
                into?.let { session ->
                    sessions.note(session, "Older tool results in this turn were cut to keep it within its memory.")
                }
            }
            // What this round found, written down before the next turn asks.
            // Only where it moved: most rounds find nothing, and a session row
            // saved every round for no change is a write per round.
            if (finder != null && found.size != recorded) {
                sessions.toolsFound(into, found)
                recorded = found.size
            }

            /*
             * Streamed when somebody is watching, asked for whole when nobody
             * is.
             *
             * Only how the response is read differs: the same request, the same
             * tools, and the same rule about what a round that asked for tools
             * means. A reasoning model does most of its thinking *before* it
             * decides to look something up, and read as one blocking call that
             * thinking cannot appear until the round is over — a block of
             * reasoning arriving complete, seconds after the model finished
             * having it. Watching a model think is most of the reason for
             * showing the thinking at all, so a round with a reader is read a
             * frame at a time.
             *
             * A round nobody is watching stays blocking. Streaming to no
             * listener buys nothing, and it keeps every caller that is not a
             * chat — a task's loop, a workflow's agent — on the path they were
             * already on.
             */
            /*
             * A turn that cannot fit is shrunk before it is sent, not after the
             * provider says so: at four characters a token - about the most a
             * token holds of prose, and far more than of markup or numbers - more
             * characters than that times the window is over it. Not every
             * provider refuses; some cut the front off the prompt and answer
             * from what is left.
             */
            window.tokens(modelId)?.let { tokens ->
                val carried = conversation.sumOf { it.content.length.toLong() + it.reasoning.orEmpty().length }
                if (carried > tokens.toLong() * MOST_CHARS_PER_TOKEN && compactions < compactionsFor(agent)) {
                    shrinkToFit(agent, modelId, conversation, null, attempt = compactions, harder = false)?.let { smaller ->
                        compactions += 1
                        into?.let { session -> sessions.note(session, shrunkNote(smaller)) }
                        conversation.clear()
                        conversation.addAll(smaller.turns)
                    }
                }
            }
            // Kept as it goes: this request is the next turn's prefix.
            kept?.sent(conversation)
            // The hangup reaches the blocking call as well as the stream: a
            // workflow's agent node is stopped this way, and most of them keep
            // no session and so nobody watching. Issue #440.
            val answer = if (watch == null) {
                models.complete(modelId, conversation, offered, hangup = hangup, reasoningEffort = reasoningEffort)
            } else {
                val writing = WritingProgress(watch, settings.writingProgressMillis().toLong(), settings.readingShownAfterSeconds() * 1000L)
                models.stream(
                    modelId,
                    conversation,
                    offered,
                    onThinking = { watch.thinking(it) },
                    hangup = hangup,
                    onCalling = writing::calling,
                    onReading = writing::reading,
                    reasoningEffort = reasoningEffort,
                ) {
                    watch.answering()
                    watch.answered(it)
                    writing.answer(it)
                }
            }
            // What the round cost, whatever it answered: a round that asked for tools is charged too.
            into?.let { spend.add(it, modelId, answer) }
            when (answer) {
                /*
                 * A round that could not be used is put back to the model
                 * rather than ending the turn. Issue #465.
                 *
                 * An agent put three kilobytes of CSS into a tool call, the
                 * provider refused the whole request over the JSON that made,
                 * and the run ended - while the model, told what had happened,
                 * fixes it on the next round: this is the same kind of thing as
                 * a tool that failed, which it is always told about and carries
                 * on from. So it is told, in the turn a person would otherwise
                 * have had to type, and asked again.
                 *
                 * Only what can be recovered from, and only twice: a permanent
                 * failure - a model that no longer exists, a refusal the
                 * provider will repeat - ends the turn as it did, and a model
                 * that cannot get it right gives up rather than spending the
                 * whole round budget on one mistake. The sentence names the way
                 * out of the case this keeps happening for, because "not valid
                 * JSON" leaves a model to guess that its own payload was what
                 * broke.
                 */
                is ChatCompletion.Failed -> {
                    /*
                     * A turn too big for the window is shrunk and asked again,
                     * rather than thrown away. Issue #522.
                     *
                     * An agent that has called forty tools holds all forty
                     * answers, was never measured against anything, and the
                     * provider refuses the next round outright - `request
                     * (69015 tokens) exceeds the available context size (65536
                     * tokens)`. The turn ended there having done all of that
                     * work, and whoever asked got nothing: the one outcome
                     * where everything was available and none of it was used.
                     *
                     * The chat compaction cannot help. It measures a stored
                     * thread before a turn is built, and this conversation is
                     * in memory and already past the line. So the middle of it
                     * is summarised in place and the same round is asked again.
                     *
                     * Bounded, because a turn still too large after two
                     * summaries is not long, it is looping - and compacting a
                     * loop for ever is a way of never saying anything is wrong.
                     */
                    /*
                     * And once more past the setting, harder: chat 981 was
                     * shrunk twice, still 452,827 tokens against 262,144, and
                     * failed. The last attempt keeps only the newest step and
                     * aims at half of what the one before it aimed at.
                     */
                    if (tooLarge(answer.reason) && compactions <= compactionsFor(agent)) {
                        val overflow = ContextOverflow.of(answer.reason + " " + answer.detail.orEmpty())
                        window.learn(modelId, overflow)
                        val smaller = shrinkToFit(
                            agent, modelId, conversation, overflow,
                            attempt = compactions, harder = compactions == compactionsFor(agent),
                        )
                        if (smaller != null) {
                            compactions += 1
                            into?.let { session -> sessions.note(session, shrunkNote(smaller)) }
                            conversation.clear()
                            conversation.addAll(smaller.turns)
                            return@repeat
                        }
                    }
                    /*
                     * An answer with nothing in it is asked for again, as it
                     * was. Issue #527.
                     *
                     * The provider sent no message - a stream that closed
                     * before anything arrived, a server that dropped the
                     * connection mid-way - and the turn ended there with
                     * "could not answer", in the middle of work that had gone
                     * fine. Nothing was said, so there is nothing to tell the
                     * model about: the same round goes back unchanged.
                     */
                    val emptyRetries = settings.emptyAnswerRetries()
                    if (answer.reason == NO_MESSAGE && !answer.permanent && emptied < emptyRetries) {
                        emptied += 1
                        log.warn("Agent {} got an empty answer; asking again ({} of {})", agent.name, emptied, emptyRetries)
                        into?.let { session ->
                            sessions.note(
                                session,
                                "The model answered with nothing, so the same round was asked again ($emptied of $emptyRetries)." +
                                    (answer.detail?.let { " The provider said $it." } ?: ""),
                            )
                        }
                        return@repeat
                    }
                    if (!answer.replyFault || answer.permanent || retried >= settings.unusableReplyRetries()) {
                        return answer.also { record(into, agent, it) }
                    }
                    retried += 1
                    into?.let { session -> sessions.note(session, "Could not be used: ${answer.reason}") }
                    conversation += ChatTurn(
                        role = "user",
                        content = "That reply could not be used: ${answer.reason}. Try again. If you were " +
                            "passing a lot of text to a tool, do not type it into the call - put it in a " +
                            "scratchpad and pass the key " + ScratchpadTools.KEEP + " answers with, or a key " +
                            "another tool has already given you.",
                    )
                    return@repeat
                }

                is ChatCompletion.Answered -> {
                    thought(answer.reasoning, answer.reasoningMillis, announce = watch == null)
                    /*
                     * Not over while something has just arrived for it. An
                     * answer that landed, or a timer that came due, while the
                     * model was writing this would otherwise wait for a turn
                     * that may never come - which is the fault the inbox exists
                     * for. What it wrote stands in the transcript, and it is
                     * asked again with what arrived.
                     */
                    val arrived = into?.let { inbox.take(it) }.orEmpty()
                    if (arrived.isNotEmpty()) {
                        // Asked again, but this round was read and written all the same: it counts.
                        spent += answer.millis
                        input += answer.inputTokens
                        output += answer.outputTokens
                        cached += answer.cachedInputTokens
                        written += answer.cacheWriteTokens
                        into?.let { sessions.agentSaid(it, agent.name, answer.content) }
                        if (answer.content.isNotBlank()) spoke?.invoke(answer.content)
                        conversation += ChatTurn(role = "assistant", content = answer.content)
                        arrived.forEach { conversation += ChatTurn(role = "user", content = it) }
                        return@repeat
                    }
                    val whole = answer.copy(
                        millis = spent + answer.millis,
                        inputTokens = input + answer.inputTokens,
                        outputTokens = output + answer.outputTokens,
                        cachedInputTokens = cached + answer.cachedInputTokens,
                        cacheWriteTokens = written + answer.cacheWriteTokens,
                        reasoning = thinking.toString(),
                        reasoningMillis = thoughtFor,
                    )
                    record(into, agent, whole)
                    /*
                     * And the answer onto what was sent, after it is recorded,
                     * so the next turn starts where this one ended and adds
                     * only what was said in the log after it.
                     */
                    kept?.sent(conversation + ChatTurn(role = "assistant", content = answer.content))
                    return whole
                }

                is ChatCompletion.CalledTools -> {
                    spent += answer.millis
                    input += answer.inputTokens
                    output += answer.outputTokens
                    cached += answer.cachedInputTokens
                    written += answer.cacheWriteTokens
                    /*
                     * A call whose arguments are not JSON goes back as `{}`.
                     * Issue #528.
                     *
                     * A model that runs out of tokens mid-call leaves the last
                     * one cut off - `{"name":"posting-to-slack` - and this
                     * echoed it back exactly as it came. llama.cpp then refused
                     * the whole next request, "failed to parse tool call
                     * arguments as JSON", and every retry carried the same
                     * broken call, so the turn died of a mistake the model made
                     * once and could not take back. That was the "column 27"
                     * of sessions 474 to 503. The call keeps its id and name so
                     * the answer below still lines up with it; it is not run.
                     */
                    val cut = answer.calls.filterNot { wholeArguments(it.arguments) }.map { it.id }.toSet()
                    /*
                     * With what it thought before asking, so the next round
                     * sees its reasoning and not only its calls. Issue #532.
                     */
                    val thought = answer.reasoning.takeIf { it.isNotBlank() }
                    conversation += answer.turn.copy(
                        asked = if (cut.isEmpty()) {
                            answer.turn.asked
                        } else {
                            answer.turn.asked.map { if (it.id in cut) it.copy(arguments = "{}") else it }
                        },
                        reasoning = thought,
                    )
                    thought(answer.reasoning, answer.reasoningMillis, announce = watch == null)
                    /*
                     * Before the calls, because that is the order it happened
                     * in: the model wrote the text and then asked for the
                     * tools, in one message. Written after the thinking for the
                     * same reason - a round reads think, speak, look up - and
                     * recorded at all because until this it was not: the model
                     * saw its own remark for the rest of the round and nobody
                     * else ever did. See [record] for what is written and what
                     * is not.
                     */
                    record(into, agent, answer)
                    if (answer.turn.content.isNotBlank() && !quiet) {
                        watch?.said(answer.turn.content)
                        spoke?.invoke(answer.turn.content)
                    }

                    /*
                     * Pictures this round's tools made, waiting for the calls
                     * to be answered. See below for why they wait.
                     */
                    val shown = mutableListOf<Pair<String, AgentTools.Companion.Picture>>()


                    /*
                     * The batch, cut to what one message is allowed to ask for.
                     * Issue #518.
                     *
                     * Session 474 arrived as a hundred and forty-one
                     * `skill_load` calls in a single assistant message - one
                     * thinking event, then the same call over and over, the
                     * first of them with its arguments cut off mid-JSON. That
                     * is a decode that has come apart, not a plan, and the loop
                     * guard cannot help: it counts between rounds and this is
                     * all inside one.
                     *
                     * The ones over the line are refused rather than dropped.
                     * Every call a model makes has to come back with an answer
                     * - a provider requires it, and a transcript that shows a
                     * call with nothing under it is unreadable - so they are
                     * answered with a refusal that says the cap, what it is,
                     * and what to do instead.
                     */
                    /*
                     * Identical calls collapse first, before the cap and before
                     * anything runs. Issue #518.
                     *
                     * It used to cap the raw batch and deduplicate inside what
                     * was left, so session 501's 279 calls - three calls, round
                     * and round - ran fifty, answered forty-seven of those as
                     * duplicates carrying the whole result again, and refused
                     * the other 229 one by one. Three distinct calls is three
                     * calls: those run, and every repeat is pointed at the one
                     * that did.
                     */
                    val firstOf = LinkedHashMap<String, ToolCall>()
                    val echoes = mutableListOf<Pair<ToolCall, ToolCall>>()
                    /*
                     * A tool_run call is the call it stands for from here on,
                     * under the same id: the grant, the approval, the guards,
                     * the session's line and the watcher all see the real tool.
                     * See [ToolRun]. What the model wrote stays in the
                     * conversation as it wrote it, which is what the next
                     * request has to repeat.
                     */
                    answer.calls.filterNot { it.id in cut }.map(ToolRun::unwrap).forEach { call ->
                        val same = call.name + 0.toChar() + call.arguments
                        val first = firstOf[same]
                        if (first == null) firstOf[same] = call else echoes += call to first
                    }
                    val distinct = firstOf.values.toList()
                    val over = distinct.drop(callsAllowed)
                    if (over.isNotEmpty()) {
                        log.warn(
                            "Agent {} asked for {} distinct tool calls in one message; {} allowed, {} refused",
                            agent.name, distinct.size, callsAllowed, over.size,
                        )
                        into?.let { session ->
                            sessions.note(
                                session,
                                "That message asked for " + distinct.size + " different tool calls at once. " +
                                    callsAllowed + " were run and " + over.size + " were refused.",
                            )
                        }
                    }
                    if (echoes.isNotEmpty()) {
                        log.warn(
                            "Agent {} repeated {} tool calls inside one message; {} distinct",
                            agent.name, echoes.size, distinct.size,
                        )
                        /*
                         * One line for all of them rather than a row each. The
                         * repeats never ran, and a transcript of 279 rows for
                         * three calls is how this looked like it had not worked.
                         */
                        into?.let { session ->
                            sessions.note(
                                session,
                                "That message repeated the same " + distinct.size +
                                    (if (distinct.size == 1) " call" else " calls") + " " +
                                    answer.calls.size + " times in all. Each ran once, and the " +
                                    echoes.size + " repeats were answered by the one that ran.",
                            )
                        }
                    }
                    distinct.take(callsAllowed).forEach { call ->
                        log.debug("Agent {} called {}", agent.name, call.name)
                        val here = at++
                        /*
                         * One string, told to both, and that is the whole of
                         * issue #291.
                         *
                         * The call's arguments can carry a command line, and
                         * they leave this loop by two
                         * roads: into the session, where [LlmSessionRecorder]
                         * strips the credentials before the row is saved, and
                         * to the watcher, which the chat's stream forwards
                         * straight to a browser. Only the first was stripped,
                         * so one `git push https://alice:s3cr3t@host/repo.git`
                         * read `alice:***@host` after a reload and
                         * `alice:s3cr3t@host` while it was being watched - the
                         * password on the screen, and a difference that makes
                         * somebody doubt the redaction works at all.
                         *
                         * Redacted here rather than in either road. Doing it in
                         * the watcher that serves the browser would fix the one
                         * watcher that exists today and leave the next to
                         * rediscover it, and it would leave two independent
                         * decisions about what a credential looks like free to
                         * drift apart - which is the bug, not a consequence of
                         * it. Computed once at the fork, there is one string
                         * and the two cannot disagree. [LlmSessionRecorder]
                         * still redacts what it is handed, because it is the
                         * one door into `llm_session_event` and that is what
                         * makes it worth anything; [AuditRedaction] says
                         * applying it twice gives the same answer as applying
                         * it once, so passing it the redacted form costs a pass
                         * over the text and changes nothing.
                         *
                         * The model is not shown this. `conversation` keeps
                         * `call.arguments` on [ChatCompletion.CalledTools.turn]
                         * and the result goes back below as the tool sent it,
                         * because the agent has to be able to do the work. What
                         * is redacted is the account of the work.
                         */
                        val asked = AuditRedaction.redact(call.arguments)
                        // Written before the tool runs, so one that hangs still
                        // leaves the transcript saying what was asked of it -
                        // and told to anybody watching for the same reason.
                        val line = into?.let { sessions.toolCalled(it, call.name, asked) }
                        watch?.called(here, call.name, asked)
                        /*
                         * A turn does not end on a failure nobody acknowledged.
                         * Issue #494: a tool answered with an error, and the
                         * very next call was finish_answer with "the work was
                         * delivered" - which is the worst shape a failure can
                         * take, because it removes the only signal anybody had.
                         *
                         * Refused once, with the error quoted back, and then
                         * never again this round: an agent that genuinely
                         * cannot get past something has to be able to end its
                         * turn and say so, and a guard that would not let it is
                         * a loop rather than a rule.
                         */
                        if (call.name == io.mszymanski.orknux.server.agent.FinishAnswerTools.FINISH &&
                            failedLast != null && !warnedOfFailure
                        ) {
                            warnedOfFailure = true
                            val said = "The last thing you did failed: $failedLast. " +
                                "Do not finish saying the work is done. Either try another way, " +
                                "or finish with an answer that says plainly what went wrong."
                            sessions.toolReturned(line, said)
                            watch?.returned(here, said, failed = true)
                            conversation += ChatTurn(role = "user", content = said, respondingTo = call.id)
                            return@forEach
                        }
                        /*
                         * The same call twice in one message is run once.
                         * Issue #518.
                         *
                         * A model can ask for a hundred and forty-one identical
                         * calls in a single assistant message, and one did:
                         * session 474 emitted that many `skill_load` calls for
                         * the same skill, having reasoned exactly once. That is
                         * a decoding failure rather than a reasoning one - it
                         * began emitting a call and did not stop - and nothing
                         * about it wants the tool run a hundred and forty-one
                         * times.
                         *
                         * So the answer is remembered by what was asked, and
                         * every later copy in the same batch gets it back
                         * without the tool running again. Each call is still
                         * answered, because a provider requires a reply to
                         * every one it asked for and leaving any unanswered
                         * makes some refuse the whole request.
                         *
                         * Within one batch only. Across rounds the same call
                         * may legitimately give a new answer - that is what
                         * polling is - and telling those apart is the repeat
                         * guard's job, with its window.
                         */
                        /*
                         * And said, so a repeat that was meant is not mistaken
                         * for one that worked.
                         *
                         * A model asking twice on purpose - the same call, a
                         * moment apart, to see whether something moved - would
                         * otherwise read two identical answers and conclude
                         * nothing had changed, when in truth the second was
                         * never made. Saying which it was costs a sentence and
                         * leaves the model able to ask again in the next round,
                         * where a fresh call is what it would get.
                         */
                        /*
                         * A skill already read in this turn is pointed at, not
                         * read again. Issue #531.
                         *
                         * Limited to one call per reply, session 509 loaded the
                         * same skill once a round, thirty-two times, each load
                         * putting the whole page back - the prompt grew from ten
                         * thousand tokens to twenty-nine thousand, and every
                         * copy made the next reload likelier. The page is
                         * already above, in the answer to the first load, and
                         * inside one turn it cannot have changed.
                         */
                        val readAs = if (call.name == SKILL_LOAD) {
                            call.arguments.filterNot { it.isWhitespace() }
                        } else {
                            null
                        }
                        val readBefore = readAs?.let { skillsRead[it] }
                        val got = if (readBefore != null) {
                            alreadyRead(call, readBefore)
                        } else try {
                            if (call.name == ToolRun.NAME) {
                                // One that named no tool, or itself: nothing to run.
                                ToolRun.refusal(call)
                            } else if (hunt != null && hunt.handles(call.name)) {
                                hunt.run(call)
                            } else if (readOnly && (holding.core + holding.searchable).none { it.name == call.name }) {
                                /*
                                 * A tool this turn holds back, named anyway - from the
                                 * briefing's list, or from an earlier turn. Refused by
                                 * what would run it, not only by the menu, and said as
                                 * the reason it is: nothing ran. Issue #653.
                                 */
                                jackson.writeValueAsString(
                                    mapOf("error" to "${call.name} changes something, and this conversation is only looking for now: it was not run"),
                                )
                            } else {
                                tools.run(agent, call, into)
                            }
                        } catch (halted: AgentRoundHalted) {
                            // The lent tool ended the round. What it did is
                            // still written down, or the transcript would stop
                            // on a call that never came back. The halt itself
                            // is rethrown untouched: the summary a task ends on
                            // is the product's, not an account of it.
                            val ended = AuditRedaction.redactObvious(halted.message.orEmpty())
                            sessions.toolReturned(line, ended)
                            watch?.returned(here, ended, failed = false)
                            /*
                             * Kept with every call of the message answered: the
                             * next turn sends this again, and a call with no
                             * answer under it is a request providers refuse.
                             */
                            kept?.sent(closed(conversation, call.id, halted.message.orEmpty()))
                            throw halted
                        } catch (broken: LinkageError) {
                            /*
                             * A tool whose code could not be loaded - a jar
                             * replaced under a running server. An Error, so
                             * nothing below caught it: the turn died on the
                             * call and the transcript showed it with no answer
                             * and nothing else, which read as a tool still
                             * running. Answered as a failure instead, so the
                             * model and the reader both see what happened.
                             */
                            jackson.writeValueAsString(mapOf(
                                "error" to "The server could not load part of its own code (" + broken.javaClass.simpleName + " " +
                                    broken.message.orEmpty() + "), so this call's answer could not be read. It may have " +
                                    "taken effect anyway - check before you try it again. The server needs a restart.",
                            ))
                        }
                        // Remembered only once it loaded: a refusal is not a page to point at.
                        if (readAs != null && readBefore == null && !AgentTools.failed(got)) skillsRead[readAs] = call.id
                        /*
                         * And what came back, onto that same line.
                         *
                         * This round threads it into `conversation`, which is
                         * gone the moment the round ends: the provider's thread
                         * keeps only the text the model produced out of it. So
                         * a later turn asking about the same data had the
                         * model's summary of it and not the data, and answered
                         * out of the summary. The session is where it survives.
                         *
                         * The narrow pass, and the difference from the
                         * arguments above is the whole of the decision.
                         * Arguments are a command line and take the full rule
                         * set; this is arbitrary output - a build log, a
                         * `--help`, a config dump - where that rule set would
                         * replace every `key=`, `--token` and `password` the
                         * model has to read. `[ERROR] cannot find symbol ***`
                         * is a functional regression, and a live view that
                         * showed it while the stored copy read correctly would
                         * be this bug with the sides swapped. See
                         * [AuditRedaction.redactObvious] for what that leaves
                         * in a transcript, which is most secrets.
                         *
                         * Whether the call failed is asked of the raw text.
                         * [AgentTools.failed] reads a JSON shape rather than
                         * words, so nothing turns on it here - but it is a
                         * question about what the tool answered, and the
                         * answer is `got`.
                         */
                        /*
                         * A picture the tool made, taken out of what it said.
                         *
                         * A model that can see does not read base64 - it reads
                         * an image part, which is a different thing in the
                         * request - and a tool's answer cannot be one: the
                         * answer to a call is a `tool` message and its content
                         * is a string. So the bytes come out here and go back
                         * in below, on a turn of their own, and what the model
                         * reads as the tool's answer keeps a sentence where
                         * four hundred kilobytes of base64 would have been.
                         *
                         * Which is also what the transcript keeps: the
                         * alternative is a session holding every picture any
                         * tool ever made, twice.
                         */

                        val picture = AgentTools.pictureIn(got)
                        // Put where the reader is, where the round has somewhere to put it; the model is told so.
                        // Not one made for the model to look at: that one is shown to the model and nobody else.
                        val placed = picture?.takeUnless { it.forModel }?.let { made ->
                            runCatching { shed?.pictured(call.name, made) }
                                .onFailure { log.warn("A picture {} made could not be put in the conversation", call.name, it) }
                                .getOrNull()
                        }
                        val whole = picture?.let { AgentTools.withoutPicture(got, it, placed) } ?: got
                        /*
                         * Held to a share of the model's window. Chat 981: one
                         * svg_read answer was 523,768 characters, under the
                         * memory wall and nearly twice the model's window, and
                         * nothing after it could be sent. The note names how to
                         * read the rest from what this agent holds.
                         */
                        val said = window.longestResult(modelId)
                            ?.let { longest -> window.fit(whole, call, offered + findable, into, longest) }
                            ?: whole
                        picture?.let { shown.add(call.name to it) }

                        /*
                         * The call, its arguments and its answer together.
                         * Issue #516: all three, because two of them repeating
                         * is ordinary - a clock read twice, a list read after
                         * something was added to it - and it is the answer
                         * being identical as well that says nothing moved.
                         */
                        /*
                         * Nothing more from a batch that has already tripped:
                         * the remaining calls are the same call, and running
                         * them only buys more warnings nobody reads.
                         */
                        if (loopWarnings >= warningsAllowed) {
                            val refused = refusalAnswer(
                                call,
                                "This turn is ending because the same call was repeated after being told, " +
                                    "so this call was not run.",
                                mapOf(
                                    "identicalCallsAllowed" to repeatsAllowed,
                                    "withinSeconds" to (repeatWindow / 1000),
                                ),
                            )
                            sessions.toolReturned(line, refused)
                            watch?.returned(here, refused, failed = true)
                            conversation += ChatTurn(role = "user", content = refused, respondingTo = call.id)
                            return@forEach
                        }

                        val signature = call.name + 0.toChar() + call.arguments + 0.toChar() + got
                        val now = System.currentTimeMillis()
                        val repeats = repeatsOf.getOrPut(signature) { mutableListOf() }
                        repeats += now
                        /*
                         * Only the ones close enough together to be a cycle.
                         * A model told to watch something calls the same tool
                         * every minute for an hour and is working; the same
                         * call four times in four seconds is not. Dropping the
                         * old ones is what tells them apart, and is why a
                         * deliberate wait clears the count.
                         */
                        repeats.removeAll { now - it > repeatWindow }
                        repeatsOf.values.removeIf { held -> held.removeAll { now - it > repeatWindow }; held.isEmpty() }

                        /*
                         * What it may have meant instead. Issue #548.
                         *
                         * Session 521 wanted github_listFiles, which was still to
                         * be loaded, and llama.cpp lets a model write only the
                         * tools it was offered - so each attempt came out as the
                         * nearest one that was, github_listRepos, six times, the
                         * model saying in its thinking that it meant listFiles.
                         * Nothing on our side ever sees the name it meant, so on
                         * the first repeat the not-yet-loaded tools of the same
                         * family are named, with the way to load them.
                         */
                        val meant = if (frame.hunting && repeats.size >= 2) unloadedBeside(call.name, findable, found) else null

                        /*
                         * And stopped, with the reason put where the model
                         * reads it. Issue #516.
                         *
                         * Told rather than cut off silently: the turn has
                         * usually done real work before the cycle started - in
                         * session 470 the agent had already chosen the chart
                         * and its data - so what is wanted is for it to finish
                         * with what it has, which it can only do if it knows
                         * the loop is why it was interrupted.
                         */
                        if (repeats.size >= repeatsAllowed) {
                            /*
                             * What the rule is, not only that it was hit.
                             *
                             * A refusal that says "stop" leaves a model to work
                             * out what it may do instead, and the usual guess
                             * is the same call once more. Saying the policy -
                             * how many, in how long - turns it into something
                             * it can plan around: a model that genuinely needs
                             * to watch something now knows to wait rather than
                             * to keep asking.
                             */
                            val note = "You have called " + call.name + " with the same arguments " +
                                repeats.size + " times within " + (repeatWindow / 1000) + " seconds and got " +
                                "the same answer each time. Nothing will change by calling it again." +
                                PARAGRAPH +
                                "The limit here is " + repeatsAllowed + " identical calls within " +
                                (repeatWindow / 1000) + " seconds - the same tool, the same arguments and " +
                                "the same answer. Calls further apart than that do not count, so if you are " +
                                "waiting for something to change, do other work first or finish and let the " +
                                "next turn check." + PARAGRAPH +
                                "Use what you already have and finish your answer." +
                                meant.orEmpty().let { if (it.isEmpty()) "" else PARAGRAPH + it }
                            into?.let { session -> sessions.note(session, note) }
                            /*
                             * Recorded as well as sent. Without this the row
                             * written before the call ran keeps a null result
                             * for ever and the session page says "no answer was
                             * recorded" under a call that was answered - which
                             * is how this looked from the outside for a week.
                             */
                            sessions.toolReturned(line, AuditRedaction.redactObvious(said))
                            watch?.returned(here, said, failed = false)
                            conversation += ChatTurn(role = "user", content = said, respondingTo = call.id)
                            conversation += ChatTurn(role = "user", content = note)
                            loopWarnings += 1
                            /*
                             * And the rest of this batch with it. Issue #515.
                             *
                             * A model can put a hundred identical calls in one
                             * message, and it did: session 472 emitted enough
                             * `skill_load` calls to trip this a hundred and
                             * thirty times inside a single round, because
                             * skipping one call carries on through the others
                             * and the check that ends a turn sits at the top of
                             * the *rounds* loop - which that round never
                             * reached. Warning a hundred and thirty times and
                             * escalating none of them is the guard working and
                             * shouting into a void.
                             */
                            return@forEach
                        }

                        // The first repeat, with a likelier meaning beside it. Issue #548.
                        if (repeats.size == 2 && meant != null) {
                            into?.let { session -> sessions.note(session, meant) }
                            sessions.toolReturned(line, AuditRedaction.redactObvious(said))
                            watch?.returned(here, said, failed = false)
                            conversation += ChatTurn(role = "user", content = said, respondingTo = call.id)
                            conversation += ChatTurn(role = "user", content = meant)
                            return@forEach
                        }

                        // What the next call is judged against, where that call
                        // is the one that ends the turn. Issue #494.
                        failedLast = if (AgentTools.failed(got)) {
                            AgentTools.reasonIn(got) ?: "it answered with an error"
                        } else {
                            null
                        }

                        val gave = AuditRedaction.redactObvious(said)
                        sessions.toolReturned(line, gave)
                        watch?.returned(here, gave, failed = AgentTools.failed(got))
                        conversation += ChatTurn(
                            role = "user",
                            content = said,
                            respondingTo = call.id,
                        )
                    }

                    /*
                     * The calls that were cut off, answered as not run - after
                     * the ones that were, so every id still has its answer.
                     */
                    if (cut.isNotEmpty()) {
                        into?.let { session ->
                            sessions.note(
                                session,
                                "That message ended in the middle of " +
                                    (if (cut.size == 1) "a tool call" else cut.size.toString() + " tool calls") +
                                    ", so " + (if (cut.size == 1) "it was" else "they were") + " not run.",
                            )
                        }
                        answer.calls.filter { it.id in cut }.forEach { call ->
                            val refused = refusalAnswer(
                                call,
                                "This call was not run: its arguments were cut off or were not JSON, most " +
                                    "likely because your message ran out of room. If you still need it, ask " +
                                    "again with the whole of its arguments.",
                                emptyMap(),
                            )
                            conversation += ChatTurn(role = "user", content = refused, respondingTo = call.id)
                        }
                    }

                    /*
                     * The repeats, answered by pointing at the call that ran.
                     *
                     * Every call id still gets an answer, because a provider
                     * refuses a request with a call left unanswered. But the
                     * answer is the pointer and not the result again: the
                     * result is already in the conversation under the first
                     * call, and repeating it is what put the same skill page
                     * back into session 501 forty-seven times.
                     */
                    val refusedIds = over.map { it.id }.toSet()
                    echoes.forEach { (call, first) ->
                        val pointer = if (first.id in refusedIds) {
                            refusalAnswer(
                                call,
                                "This call was not run: it repeats one that was over the limit of " +
                                    callsAllowed + " different calls in one message.",
                                mapOf("callsAllowedAtOnce" to callsAllowed, "sameAsCall" to first.id),
                            )
                        } else {
                            duplicateAnswer(call, first.id)
                        }
                        conversation += ChatTurn(role = "user", content = pointer, respondingTo = call.id)
                    }

                    /*
                     * And the refusals for what was over the cap, after the
                     * calls that ran and in the order they were asked.
                     *
                     * Every one of them, because a request carrying a call with
                     * no answer under it is one a provider refuses outright -
                     * and because a model reading its own message back with
                     * nothing where an answer should be has no way to tell that
                     * from a tool that hung. That is how session 474 kept going:
                     * calls were dropped in silence, and it asked again.
                     */
                    over.forEach { call ->
                        val refused = refusalAnswer(
                            call,
                            "This call was not run. Your message asked for " + distinct.size +
                                " different tool calls at once and the first " + callsAllowed + " were run. " +
                                "Ask for fewer calls in one message: ask for what you need now, read the " +
                                "answers, and then ask for the next thing.",
                            mapOf(
                                "callsAsked" to distinct.size,
                                "callsAllowedAtOnce" to callsAllowed,
                                "callsRefused" to over.size,
                            ),
                        )
                        val where = into?.let { sessions.toolCalled(it, call.name, AuditRedaction.redact(call.arguments)) }
                        sessions.toolReturned(where, refused)
                        conversation += ChatTurn(role = "user", content = refused, respondingTo = call.id)
                    }

                    /*
                     * A batch that went round in circles ends the turn here,
                     * rather than at the top of the next round. Issue #515.
                     *
                     * The check used to sit up there, which is fine for a model
                     * that loops one call per round and useless for one that
                     * puts a hundred identical calls in a single message - and
                     * that is what happens: 472 tripped the guard a hundred and
                     * thirty times without ever reaching a next round.
                     *
                     * Every call is still answered before this, because a
                     * provider requires it: leaving one unanswered is a request
                     * some of them refuse outright, and the turn would fail
                     * for the wrong reason.
                     */
                    if (loopWarnings >= warningsAllowed) {
                        into?.let { session ->
                            sessions.note(session, "The turn was ended: the same call was repeated after being told.")
                        }
                        return ChatCompletion.Failed(
                            "it repeated the same tool call after being told the limit of " + repeatsAllowed +
                                " identical calls within " + (repeatWindow / 1000) + " seconds, and the turn " +
                                "was ended",
                            permanent = false,
                        ).also { record(into, agent, it) }
                    }

                    /*
                     * And then the pictures, after every call in the round has
                     * been answered.
                     *
                     * Not between them: a provider requires every call to be
                     * answered before anything else is said, and a turn slipped
                     * in the middle is a request some of them refuse outright.
                     * So they queue and land together, in the order the tools
                     * made them.
                     */
                    shown.forEach { (tool, picture) ->
                        conversation += ChatTurn(
                            role = "user",
                            content = picture.noteFor(tool),
                            images = listOf(picture.dataUrl),
                        )
                    }
                    shown.clear()

                    /*
                     * And anything a person said while those tools ran.
                     *
                     * Here and nowhere else in the loop. Every tool result is
                     * threaded in by this point, which providers require to
                     * follow their call immediately, and the next thing that
                     * happens is the model being asked again - so a turn added
                     * here is read at the top of the very next call rather than
                     * at the end of a round that may be several more lookups
                     * away. A task drawing three pictures reads a correction
                     * before the second one.
                     *
                     * As a turn from a person, which is what it is, and in the
                     * order it was said in. The caller is what writes it down
                     * and what marks it delivered; see [Interjections].
                     */
                    interjections?.waiting()?.forEach { said ->
                        conversation += ChatTurn(role = "user", content = said)
                    }
                    // And what arrived for the session meanwhile - an agent it
                    // asked answering, a timer coming due - read at the same
                    // point and for the same reason. See [SessionInbox].
                    into?.let { session ->
                        inbox.take(session).forEach { arrived ->
                            conversation += ChatTurn(role = "user", content = arrived)
                        }
                    }
                }
            }
        }

        // Out of rounds. Said plainly rather than returning whatever the last
        // round happened to contain: an agent stuck in a loop of lookups has not
        // answered, and pretending otherwise hides the loop.
        log.warn("Agent {} was still calling tools after {} rounds", agent.name, rounds)
        return ChatCompletion.Failed(
            "${agent.name} kept looking things up without reaching an answer, and was stopped after $rounds rounds",
            // Settled, and deliberately so. What put the agent in the loop is
            // its instructions and the tools it was granted, and those are the
            // same on the next attempt; a retry policy here buys another round
            // of the same billing on the way to the same sentence. What does
            // help is more rounds, and that is a setting now - on the agent, or
            // on the installation behind it.
            permanent = true,
            outOfRounds = true,
        ).also { record(into, agent, it) }
    }

    /**
     * The thinking off a round that took no tools at all, handed to a watcher.
     *
     * An agent with no tools granted answers in one call, so there is no loop
     * to thread the reasoning through and this is the only place it can be
     * passed on. Written out rather than folded into [record], which is about
     * what is kept rather than about who is watching.
     */
    /**
     * The turns with a lent shed's briefing added to the system turn.
     *
     * Appended to the system turn where there is one and put first as one where
     * there is not, because that is where a model reads standing instructions
     * from; a paragraph in the user's turn would read as the user's. Nothing to
     * add hands the turns back as they came, so a round with nothing lent is
     * the round it always was. Issue #445.
     */
    private fun briefed(turns: List<ChatTurn>, advice: String?): List<ChatTurn> {
        if (advice.isNullOrBlank()) return turns
        val system = turns.indexOfFirst { it.role == "system" }
        if (system == -1) return listOf(ChatTurn(role = "system", content = advice)) + turns
        // Through [briefedWith], because the record of what the model was
        // working under is composed by the same rule and the two must not
        // disagree about it. Issue #454.
        return turns.mapIndexed { index, turn ->
            if (index == system) turn.copy(content = requireNotNull(briefedWith(turn.content, advice))) else turn
        }
    }

    /**
     * The conversation with every call of its last assistant message answered,
     * for a turn a lent tool ended part-way through a batch. The call that ended
     * it answers with what it said; any after it were never run, and say so.
     */
    private fun closed(conversation: List<ChatTurn>, ending: String, said: String): List<ChatTurn> {
        val asking = conversation.indexOfLast { it.asked.isNotEmpty() }
        if (asking < 0) return conversation
        val answered = conversation.drop(asking + 1).mapNotNull { it.respondingTo }.toSet()
        return conversation + conversation[asking].asked.filter { it.id !in answered }.map { call ->
            ChatTurn(
                role = "user",
                content = if (call.id == ending) {
                    said
                } else {
                    refusalAnswer(call, "This call was not run: the turn ended before it.", emptyMap())
                },
                respondingTo = call.id,
            )
        }
    }

    private fun told(watch: RoundWatch?, answer: ChatCompletion) {
        if (watch == null) return
        val reasoning = when (answer) {
            is ChatCompletion.Answered -> answer.reasoning
            is ChatCompletion.CalledTools -> answer.reasoning
            is ChatCompletion.Failed -> ""
        }
        if (reasoning.isNotBlank()) watch.thinking(reasoning)
    }

    /**
     * What the agent said in a round, written into the session that asked for
     * one.
     *
     * A failure becomes a system note rather than an answer, because it is not
     * something the agent said — it is something that happened to the
     * conversation. A transcript that simply stopped would leave whoever reads
     * it looking for words that were never spoken.
     *
     * **A round can carry text and calls at once, and both are written down.**
     * Providers are entitled to answer with a message and tool calls in the
     * same reply, and several do it habitually - "Let me check the open issues
     * first" alongside the call that checks them. [ModelChatClient] keeps that
     * text on [ChatCompletion.CalledTools.turn], so the model reads its own
     * remark for the rest of the round; until this recorded it, nobody else
     * ever did. It was off the task page and off the chat page,
     * [LlmSessionRecorder.remembered] reads what was *said* so it was gone from
     * the agent's own memory by the next turn, and a task whose outcome was
     * written in one of those remarks described work whose only copy had been
     * thrown away.
     *
     * **Nothing is written where there is no text**, which is the overwhelming
     * majority of tool-calling rounds. A blank line recorded for each of them
     * would put an empty speech bubble under every lookup on every page in the
     * product, for ever. Blank rather than empty, and for the reason
     * [ModelChatClient] gives where it makes the same test: a closing reasoning
     * tag routinely leaves a newline behind it.
     *
     * What is written is what the model wrote, untrimmed and unreformatted
     * beyond that test. Reformatting it would be this deciding what the model
     * meant.
     *
     * The caller places the call, and the placing matters: the text was written
     * before the tools were asked for, so it has to be recorded before the
     * lines for those calls or the transcript reads as though the agent spoke
     * after looking things up.
     */
    /**
     * The mapper these two build their answers with.
     *
     * Its own, and plain: what goes through it is three or four named fields
     * around a result that is already a string, so nothing here depends on the
     * application's configuration - and a structured answer to a model must not
     * change shape because somebody adjusted serialization elsewhere.
     */
    private val jackson = ObjectMapper()

    /**
     * What a call that was not run answers with. Issue #518.
     *
     * Structured, and it has to be: this is the model being told about its own
     * behaviour, and a sentence in the middle of what is otherwise a tool result
     * reads as part of the result. Named fields say plainly that nothing ran,
     * why, and what the rule was - so the model can act on the rule rather than
     * guess at it from prose.
     */
    private fun refusalAnswer(call: ToolCall, why: String, policy: Map<String, Any>): String {
        val envelope = jackson.createObjectNode()
        envelope.put("ran", false)
        envelope.put("tool", call.name)
        envelope.put("refused", why)
        val rule = envelope.putObject("policy")
        policy.forEach { (name, value) ->
            when (value) {
                is Int -> rule.put(name, value)
                is Long -> rule.put(name, value)
                else -> rule.put(name, value.toString())
            }
        }
        return jackson.writeValueAsString(envelope)
    }

    /**
     * Whether a call's arguments are something a provider will take back.
     * Nothing at all counts, as `{}` does; a JSON object counts; anything else -
     * text cut off mid-way above all - does not. Issue #528.
     */
    private fun wholeArguments(arguments: String): Boolean {
        if (arguments.isBlank()) return true
        return runCatching { jackson.readTree(arguments).isObject }.getOrDefault(false)
    }

    /**
     * What a skill already read in this turn answers with. Issue #531. A
     * pointer, structured like the other two, because the page is already in
     * the conversation and a copy of it is what fed session 509's loop.
     */
    private fun alreadyRead(call: ToolCall, readAs: String): String {
        val envelope = jackson.createObjectNode()
        envelope.put("alreadyLoaded", true)
        envelope.put("sameAsCall", readAs)
        envelope.put("tool", call.name)
        envelope.put(
            "note",
            "You loaded this skill earlier in this turn and its page is above, in the answer to that call. It " +
                "has not changed. Follow it rather than loading it again, and get on with what you were asked.",
        )
        return jackson.writeValueAsString(envelope)
    }

    /**
     * What a repeated call answers with. Issue #518.
     *
     * Structured, and a pointer rather than a copy: which call ran and that
     * this one did not. The result is already in the conversation under that
     * call, and carrying it again made a repeated skill_load put the same page
     * back into the context once per repeat.
     */
    private fun duplicateAnswer(call: ToolCall, ranAs: String): String {
        val envelope = jackson.createObjectNode()
        envelope.put("duplicateOfCall", ranAs)
        envelope.put("tool", call.name)
        envelope.put("ran", false)
        envelope.put("note", DUPLICATE_NOTE)
        return jackson.writeValueAsString(envelope)
    }

    private fun record(into: Long?, agent: Agent, answer: ChatCompletion) {
        if (answer is ChatCompletion.Failed) {
            errors.record(agent.workspaceId, io.mszymanski.orknux.server.errors.ErrorSource.AGENT, agent.name, answer.reason, sessionId = into)
        }
        val session = into ?: return
        when (answer) {
            is ChatCompletion.Answered -> sessions.agentSaid(session, agent.name, answer.content)
            is ChatCompletion.Failed -> sessions.note(session, "${agent.name} could not answer: ${answer.reason}")
            is ChatCompletion.CalledTools ->
                if (answer.turn.content.isNotBlank()) sessions.agentSaid(session, agent.name, answer.turn.content)
        }
    }

    private companion object {


        /**
         * What the client says when a provider sent back nothing. Matched here
         * rather than flagged there because the client is shared and this is
         * the one caller that re-asks. Issue #527.
         */
        const val NO_MESSAGE = "The provider answered with no message"

        /** The one tool whose answer cannot change inside a turn. Issue #531. */
        const val SKILL_LOAD = "skill_load"

        /**
         * What a provider says when the request will not fit. Issue #522.
         *
         * Lowercased fragments rather than whole sentences: the numbers in them
         * differ every time, and so does everything around them.
         */
        val OVERSIZED = listOf(
            "exceeds the available context size",
            "maximum context length",
            "context length exceeded",
            "too many tokens",
            "reduce the length of the messages",
            // OpenAI's code, where a provider puts it in the text, and Anthropic's wording.
            "context_length_exceeded",
            "prompt is too long",
            "input is too long",
            "exceeds the context window",
        )

        /** The most characters one token holds of anything a turn carries; see the check before each round. */
        const val MOST_CHARS_PER_TOKEN = 4

        /** A blank line, built rather than typed: an escape does not survive every editor. */
        val PARAGRAPH = 10.toChar().toString() + 10.toChar().toString()

        /** What a duplicated call is told it was. Issue #518. */
        const val DUPLICATE_NOTE =
            "This exact call appeared more than once in your last message. It ran once and this is that " +
                "same answer, not a fresh one. To check whether something has changed, ask again in your " +
                "next message and it will run for real."

        val log = LoggerFactory.getLogger(AgentConversation::class.java)
    }
}

/**
 * The findable tools not yet loaded that share a family with [called] - the
 * part of the name before its first underscore - said as a note, or null
 * where there are none. Issue #548.
 */
internal fun unloadedBeside(called: String, findable: List<ToolSpec>, found: Set<String>): String? {
    val family = called.substringBefore('_') + "_"
    if (family == "_" || family.length > called.length) return null
    val near = findable.map { it.name }.filter { it.startsWith(family) && it !in found && it != called }.sorted()
    if (near.isEmpty()) return null
    return "You called " + called + " again with the same arguments and got the same answer. If you " +
        "meant a different tool, it may be one that is not loaded yet - a tool that is not loaded cannot " +
        "be called, and the call comes out as one that is. Not loaded yet: " + near.joinToString(", ") +
        ". Call " + ToolSearchTools.FIND + " with the name you need, then call it with " + ToolRun.NAME + "."
}
