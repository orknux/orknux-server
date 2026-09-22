package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.Hangup
import io.mszymanski.orknux.connector.model.ModelChatClient
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
    private val agents: AgentRepository,
    private val sessions: LlmSessionRecorder,
    /** Where the installation's ceiling on tool rounds is kept and changed. */
    private val settings: InstallationSettings,
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
        val offered = tools.specsFor(agent) + shed?.specs().orEmpty()
        if (offered.isEmpty()) {
            /*
             * An agent granted nothing answers in one call, and it streams for
             * a watcher on the same rule as the loop below: what it thinks
             * should appear while it is thinking it, not once it has finished.
             * Told at the end only where it was not streamed, or the thinking
             * would be drawn twice.
             */
            val once = if (watch == null) {
                models.complete(modelId, turns).also { told(watch, it) }
            } else {
                models.stream(
                    modelId,
                    turns,
                    onThinking = { watch.thinking(it) },
                    hangup = hangup,
                ) { watch.answering() }
            }
            return once.also { record(into, agent, it) }
        }

        val conversation = turns.toMutableList()
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
        repeat(rounds) {
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
            val answer = if (watch == null) {
                models.complete(modelId, conversation, offered)
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
                is ChatCompletion.Failed -> return answer.also { record(into, agent, it) }

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
                    conversation += answer.turn
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

                    answer.calls.forEach { call ->
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
                        val got = try {
                            if (shed != null && shed.handles(call.name)) shed.run(call) else tools.run(agent, call, into)
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
                        val said = picture?.let { AgentTools.withoutPicture(got, it) } ?: got
                        picture?.let { shown.add(call.name to it) }

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
        val log = LoggerFactory.getLogger(AgentConversation::class.java)
    }
}
