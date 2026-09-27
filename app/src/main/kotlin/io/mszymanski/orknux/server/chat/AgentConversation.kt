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
 * The intermediate turns are deliberately not written to the history. What is
 * kept is the conversation somebody had; that an agent read three skills on the
 * way to an answer is how it worked, not what was said, and putting it in the
 * thread would mean every later round re-reads it and pays for it again.
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
    private val tools: AgentTools,
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
) {

    /**
     * How many rounds this agent gets: its own number, or the installation's.
     *
     * Eight was written into the code here, and it was right for an agent with
     * two tools and wrong for one holding twenty - a list, a load and a lookup
     * is three rounds before the work starts, and what the agent had gathered by
     * then was thrown away with the refusal. The number is a setting now, and an
     * agent whose work is longer than the rest carries its own.
     */
    private fun roundsFor(agent: Agent): Int = agent.maxRounds ?: settings.chatMaxRounds()

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
    private fun shrinkToFit(agent: Agent, modelId: Long, turns: List<ChatTurn>): List<ChatTurn>? {
        val workspace = workspaces.findByIdOrNull(agent.workspaceId)
        /*
         * The turn's own model unless a workspace named another. A summary of
         * what an agent has been doing is ordinary work, so the model that was
         * already answering can write one; naming a cheaper one is a saving
         * somebody may want and not a default worth assuming for them.
         */
        val summariser = workspace?.sessionCompactionModelId ?: modelId
        val keep = workspace?.sessionCompactionKeepTurns ?: settings.sessionCompactionKeepTurns()
        val budget = workspace?.sessionCompactionSummaryTokens ?: settings.sessionCompactionSummaryTokens()
        return runCatching { compaction.shrink(turns, summariser, budget, keep) }
            .onFailure { log.warn("A turn could not be shrunk to fit", it) }
            .getOrNull()
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
    ): ChatCompletion {
        val agent = agents.findByIdOrNull(agentId)
            ?: return ChatCompletion.Failed("That agent no longer exists")
        return answer(modelId, agent, turns, into, shed, watch, hangup, interjections)
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
    ): ChatCompletion {
        val holding = tools.offeringFor(agent)

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
        val lending = BuiltInTools.lentTo(agent, shed)
        val (lent, lentFindable) = lending?.specs().orEmpty().partition { BuiltInTools.carried(agent, it.name) }
        /*
         * And what the lent tools want said about themselves, put with the
         * briefing. A shed's descriptions say what its tools do; its briefing
         * says that the agent has them and when to reach for one, which is the
         * half a model acts on - see [ToolShed.briefing]. Issue #445.
         */
        // And what it lends, put into the briefing's list of tools. Issue #546.
        val told = briefed(turns, lending?.briefing()).map { turn ->
            if (turn.role == "system") {
                turn.copy(content = BuiltInTools.listed(turn.content, agent, lending?.specs().orEmpty()) ?: turn.content)
            } else {
                turn
            }
        }

        /*
         * Whether everything fits in one request.
         *
         * Asked of the provider rather than assumed: OpenAI and Azure refuse
         * the whole request over 128 tools, Anthropic bounds it differently,
         * and nothing counted them until the provider did - so an agent granted
         * more than the ceiling could not answer at all, and what reached the
         * person who asked was the provider's own sentence.
         *
         * Under the ceiling this is every round there has ever been: the two
         * halves are put back together, the array is built once, and nothing
         * about the turn changes. Which is most installations, and the reason
         * the search is not simply always on - finding costs a round, and
         * spending one to discover a tool that would have fitted is a round
         * spent on nothing.
         */
        /*
         * The agent's own ceiling where it carries one, and the provider's
         * otherwise. Issue #372.
         *
         * The provider's is the number at which a request *fails*; an agent's is
         * the number at which somebody decided it was choosing badly, which is
         * always the smaller of the two and is a judgement rather than a limit.
         * The lower of them wins, because an agent given a ceiling above what
         * the provider takes has been given a number that cannot be honoured.
         */
        val limit = minOf(models.toolLimit(modelId), agent.maxTools ?: Int.MAX_VALUE)
        val hunting = holding.core.size + holding.searchable.size + lent.size + lentFindable.size > limit

        /*
         * Everything that is found rather than carried: the agent's own
         * searchable half, and the lent built-ins left at Offer. One list,
         * because the finder holds one shelf and a tool it cannot see is a tool
         * it cannot find.
         */
        val findable = holding.searchable + lentFindable

        /*
         * What this agent has already found, and where it is kept.
         *
         * A tool searched for in one turn stays declared for the rest of the
         * session rather than being searched for again: an agent asked a
         * follow-up would otherwise spend a round rediscovering the tool it
         * used a minute ago, and the second search is not guaranteed to return
         * what the first did.
         */
        val found = if (hunting) sessions.toolsFound(into).toMutableSet() else mutableSetOf()

        /*
         * And the tool that does the finding, lent beside whatever the caller
         * lent. `room` is read when a search runs rather than now, because by
         * then this round may already have found some.
         */
        val finder = if (!hunting) {
            null
        } else {
            searching.shed(
                findable,
                found,
                /*
                 * What is left of the array once the core, what was lent and
                 * find_tools itself have taken their places. Negative where the
                 * core alone fills it, which the search reads as no room at all.
                 */
                room = { limit - holding.core.size - lent.size - ToolSearchTools.OFFERED - found.size },
                carried = (holding.core + lent).map { it.name }.toSet(),
                /*
                 * And what to give up to make room. Issue #372: an agent that
                 * has filled its budget and needs something else should lose the
                 * tool it looked up longest ago rather than be told it is full -
                 * "you cannot have any more" is a dead end for a model, where
                 * forgetting is what a person does without noticing.
                 *
                 * The oldest first, which is what insertion order gives: `found`
                 * is a LinkedHashSet, so the first name in it is the one looked
                 * up furthest back. What the agent is using now was found most
                 * recently and is the last thing to go.
                 */
                forget = { wanted ->
                    val going = found.take(wanted)
                    found.removeAll(going.toSet())
                    going
                },
            )
        }
        val hunt = if (finder == null) lending else sheds(lending, finder)

        /** What one round declares: everything, or the core and what has been found. */
        fun offering(): List<ToolSpec> = if (finder == null) {
            holding.core + holding.searchable + lent + lentFindable
        } else {
            holding.core + lent + finder.specs() + findable.filter { it.name in found }
        }

        var offered = offering()
        /** How many finds were last written down; see the loop. */
        var recorded = found.size
        if (offered.isEmpty()) {
            /*
             * An agent granted nothing answers in one call, and it streams for
             * a watcher on the same rule as the loop below: what it thinks
             * should appear while it is thinking it, not once it has finished.
             * Told at the end only where it was not streamed, or the thinking
             * would be drawn twice.
             */
            val once = if (watch == null) {
                models.complete(modelId, told, hangup = hangup).also { told(watch, it) }
            } else {
                models.stream(
                    modelId,
                    told,
                    onThinking = { watch.thinking(it) },
                    hangup = hangup,
                ) { watch.answering() }
            }
            return once.also { record(into, agent, it) }
        }

        val conversation = told.toMutableList()
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
        var lastCall: String? = null
        /*
         * When each of the identical calls in the current run happened, so the
         * window can be applied. A list rather than a count because the
         * question is how many fall *inside* the window, and the oldest fall
         * out of it as time passes.
         */
        val repeats = mutableListOf<Long>()
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
             * Rebuilt every round, because a search changes what the next one
             * declares. That is the whole of how discovery works: a model can
             * only call what was in the request it is answering, so a tool
             * found in this round has to appear in the next request or it was
             * never found at all. Under the ceiling this is the same list every
             * time and the work is a list concatenation.
             */
            offered = offering()
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
            // The hangup reaches the blocking call as well as the stream: a
            // workflow's agent node is stopped this way, and most of them keep
            // no session and so nobody watching. Issue #440.
            val answer = if (watch == null) {
                models.complete(modelId, conversation, offered, hangup = hangup)
            } else {
                models.stream(
                    modelId,
                    conversation,
                    offered,
                    onThinking = { watch.thinking(it) },
                    hangup = hangup,
                ) { watch.answering() }
            }
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
                    if (tooLarge(answer.reason) && compactions < compactionsFor(agent)) {
                        val smaller = shrinkToFit(agent, modelId, conversation)
                        if (smaller != null) {
                            compactions += 1
                            into?.let { session ->
                                sessions.note(
                                    session,
                                    "The turn outgrew the model's window, so its earlier steps were " +
                                        "replaced by a summary and it carried on.",
                                )
                            }
                            conversation.clear()
                            conversation.addAll(smaller)
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
                    if (answer.reason == NO_MESSAGE && !answer.permanent && emptied < MOST_RETRIES) {
                        emptied += 1
                        log.warn("Agent {} got an empty answer; asking again ({} of {})", agent.name, emptied, MOST_RETRIES)
                        into?.let { session ->
                            sessions.note(session, "The model answered with nothing, so the same round was asked again.")
                        }
                        return@repeat
                    }
                    if (!answer.replyFault || answer.permanent || retried >= MOST_RETRIES) {
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
                    return answer
                        .copy(
                            millis = spent + answer.millis,
                            inputTokens = input + answer.inputTokens,
                            outputTokens = output + answer.outputTokens,
                            reasoning = thinking.toString(),
                            reasoningMillis = thoughtFor,
                        )
                        .also { record(into, agent, it) }
                }

                is ChatCompletion.CalledTools -> {
                    spent += answer.millis
                    input += answer.inputTokens
                    output += answer.outputTokens
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
                    answer.calls.filterNot { it.id in cut }.forEach { call ->
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
                         * The call's arguments are a command line - the shell
                         * tool's literally so - and they leave this loop by two
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
                            if (hunt != null && hunt.handles(call.name)) hunt.run(call) else tools.run(agent, call, into)
                        } catch (halted: AgentRoundHalted) {
                            // The lent tool ended the round. What it did is
                            // still written down, or the transcript would stop
                            // on a call that never came back. The halt itself
                            // is rethrown untouched: the summary a task ends on
                            // is the product's, not an account of it.
                            val ended = AuditRedaction.redactObvious(halted.message.orEmpty())
                            sessions.toolReturned(line, ended)
                            watch?.returned(here, ended, failed = false)
                            throw halted
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
                        val whole = picture?.let { AgentTools.withoutPicture(got, it) } ?: got
                        val said = whole
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
                        if (signature == lastCall) repeats += now else { repeats.clear(); repeats += now }
                        lastCall = signature
                        /*
                         * Only the ones close enough together to be a cycle.
                         * A model told to watch something calls the same tool
                         * every minute for an hour and is working; the same
                         * call four times in four seconds is not. Dropping the
                         * old ones is what tells them apart, and is why a
                         * deliberate wait clears the count.
                         */
                        repeats.removeAll { now - it > repeatWindow }

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
                        val meant = if (hunting && repeats.size >= 2) unloadedBeside(call.name, findable, found) else null

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
         * How many times one turn may put a refused reply back to the model.
         * Twice: a model that has been told twice what was wrong with its last
         * two replies is not about to get the third right, and every attempt is
         * a paid call. Issue #465.
         */
        const val MOST_RETRIES = 2

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
        )

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
        ". Call " + ToolSearchTools.FIND + " with the name you need, then call it in your next message."
}
