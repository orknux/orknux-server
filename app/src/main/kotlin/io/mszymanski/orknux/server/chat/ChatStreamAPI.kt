package io.mszymanski.orknux.server.chat

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.attachment.ChatAttachments
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.stream.ReaderWatch
import io.mszymanski.orknux.server.stream.ServerSentEvents
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tools.jackson.databind.ObjectMapper

/**
 * What the browser sends to say something.
 *
 * The creator is bound explicitly for the same reason `LoginRequest` binds its
 * own: Boot 4 ships Jackson 3, which has no Kotlin module on the classpath, so
 * constructor parameter names are not enough to deserialize from.
 */
data class ChatStreamRequest @JsonCreator constructor(
    @JsonProperty("text") val text: String,
    /**
     * What was attached to this message, by id.
     *
     * Sent with the message rather than linked afterwards, because the model is
     * called during the send: a picture that arrives after the answer is a
     * picture the answer could not have seen.
     */
    @JsonProperty("attachmentIds") val attachmentIds: List<Long> = emptyList(),
    /**
     * Whether a lost reader should stop the answer.
     *
     * A voice turn is spoken and then gone, so a reader who walks away wants it
     * stopped - which is what #299 gave voice mode. A text chat is a record: an
     * answer left half-read is wanted when the person comes back, so leaving no
     * longer stops it and it is written to the history whether anybody is still
     * reading or not. That is #335, and this flag is which of the two this turn
     * is. False - a text turn - unless the voice panel says otherwise.
     */
    @JsonProperty("voice") val voice: Boolean = false,
)

/**
 * The one part of the chat that is not GraphQL.
 *
 * A model composes an answer over seconds — a large local one over minutes —
 * and a single mutation can only return when it has finished, which is a blank
 * screen for the whole of that time. This sends the answer as it arrives.
 *
 * Server-sent events over a POST rather than a GraphQL subscription: the browser
 * client here is `fetch`, not a GraphQL client, so a subscription would mean
 * adding a websocket transport and the `graphql-ws` protocol to send one string.
 * Everything else about a chat — starting one, listing them, reading the
 * history — stays where it was.
 */
@RestController
class ChatStreamAPI(
    private val chats: ChatService,
    private val client: ModelChatClient,
    /** Only to cost an answer: the prices are the model's. */
    private val models: ModelService,
    private val titles: ChatTitles,
    private val ownership: ChatOwnership,
    private val attachments: ChatAttachments,
    private val settings: InstallationSettings,
    /** How this endpoint finds out that the person who asked has walked away. */
    private val readers: ReaderWatch,
    /** The in-flight answers, so the Stop button can reach one it is not inside. */
    private val generations: ChatGenerations,
    private val mapper: ObjectMapper,
    private val chatTools: ChatTools,
) {

    /**
     * Says something, and streams the answer back.
     *
     * Six events are sent: `chunk` for each piece of the answer as it lands,
     * `thinking` for each piece of a reasoning model's thinking, `call` for a
     * lookup the moment an agent makes one, `called` for what that lookup gave
     * back, `done` with what the turn took and what it cost, and `error` when it
     * could not answer. The whole answer is written to the history when the
     * stream ends, so a chat reloaded afterwards reads exactly as it did live.
     *
     * Access is checked before anything is written, because after the first byte
     * the status code has already been sent and there is no way to say no.
     */
    @PostMapping("/api/chats/{id}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable id: Long,
        @RequestBody request: ChatStreamRequest,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        // Tied to the chat here, and read for anything the model can look at.
        val sent = attachments.attach(session, request.attachmentIds)
        /*
         * Built inside the stream rather than before it, because compacting a
         * long conversation takes as long as a model takes to read forty turns -
         * and until this that time was spent with the response not yet open and
         * nothing on screen. The frames are the first thing the chat hears.
         *
         * Everything that can be refused cheaply has been refused already, above:
         * the chat exists, it is this person's, and the installation has a chat
         * at all. What is left inside the body is the work.
         */
        return answering(
            id,
            { send -> chats.beginSend(id, request.text, attachments.imagesOf(sent)) { send("compacting", mapOf<String, Any>()) } },
            request.text,
            response,
            session,
            interruptOnLeave = request.voice,
        )
    }

    /**
     * Asks the last answer again, and streams the new one back the same way.
     *
     * A door of its own rather than a flag on [stream], because nothing is
     * being said: there is no text, nothing is attached, and the turn that goes
     * to the model is the conversation with its own last answer taken off.
     * Everything after that is identical, which is why both end in [answering].
     *
     * The chat is not renamed off a regenerate. A name is taken from the first
     * exchange, and this is not one - it is the same exchange, answered again,
     * and letting the second attempt rename a chat somebody has already found
     * in the sidebar would move it out from under them.
     */
    @PostMapping("/api/chats/{id}/regenerate", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun regenerate(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") voice: Boolean,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)

        return answering(id, { chats.beginRegenerate(id) }, said = null, response = response, session = session, interruptOnLeave = voice) {
            chats.abandonRegenerate(id)
        }
    }

    /**
     * Stops the answer being written on this chat, when somebody presses Stop.
     *
     * A text chat no longer stops on a lost reader - it is a record and the
     * answer is wanted when the person returns (#335) - so stopping on purpose
     * is a call of its own rather than the side effect of closing the stream
     * that it used to be. It reaches the answering thread's [Hangup] through
     * [ChatGenerations]; the stream the browser is reading closes on its own
     * once the thread puts the interrupted turn back.
     */
    @PostMapping("/api/chats/{id}/interrupt")
    fun interrupt(@PathVariable id: Long) {
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)
        generations.interrupt(id)
    }

    /**
     * The asking itself, once what to ask has been settled.
     *
     * @param said what the person typed, for naming a chat that has no name
     *   yet, or null where nothing was said - a regenerate.
     * @param giveUp what to undo where no answer arrives. A regenerate has
     *   already taken the old answer off the thread by this point, so a
     *   provider that refuses would otherwise leave the chat ending on the
     *   question - the one outcome worse than the answer somebody did not like.
     * @param shed the chat's own tools, lent to the agent for this round. The
     *   same shed the blocking door lends: what an agent may do must not depend
     *   on which of the two the browser used.
     */
    private fun answering(
        id: Long,
        /**
         * The turn to send, built once the stream is open.
         *
         * A value until compaction needed somewhere to announce itself from: it
         * happens while this is being built, so the thing that builds it has to
         * run where frames can be sent. It is handed `send` for that and for
         * nothing else.
         */
        begin: (send: (String, Any) -> Unit) -> ChatSendStart,
        said: String?,
        response: HttpServletResponse,
        session: ChatSession,
        /**
         * Whether a lost reader stops the answer. A voice turn's does (#299); a
         * text turn's does not, and is written to the history whether anybody is
         * still reading or not (#335). Either way, pressing Stop stops it - that
         * goes through [interrupt] and the [ChatGenerations] registry, not this.
         */
        interruptOnLeave: Boolean,
        giveUp: () -> Unit = {},
    ): StreamingResponseBody {
        // Nothing between here and the browser may hold a piece of the answer
        // back. See the task stream, which sets these for the same reason.
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        /*
         * Who asked, captured while this is still their request thread.
         *
         * The answer is composed on a thread of this endpoint's own (below),
         * and Spring Security's context does not follow it there the way it
         * follows Spring's async machinery. It has to: the turn writes audit
         * lines - a drawn picture is recorded against the person whose chat
         * drew it - and an audit line with nobody to attribute to is refused
         * by the recorder, rightly.
         */
        val asker = SecurityContextHolder.getContext()

        return StreamingResponseBody { _ ->
            /*
             * The frames themselves are [ServerSentEvents]', which is also what
             * put the pieces on the wire.
             *
             * They used to be written here by hand, as `out.write` and
             * `out.flush` - and that flush did nothing. Spring hands a
             * `StreamingResponseBody` a stream whose `flush` is a no-op, so this
             * answer moved when the container's buffer filled at eight kilobytes
             * rather than when the model produced a piece of it. It was
             * invisible because a model writing prose does eventually fill eight
             * kilobytes: a long answer appeared to stream, in lurches, and a
             * short one arrived whole at the end and read as a slow model.
             */
            val stream = ServerSentEvents(response, mapper)

            /*
             * The answer is composed on a thread of its own, and this thread -
             * the container's - only relays frames to the browser.
             *
             * That split is what actually delivers #335. The first build kept
             * the model call on this thread and wrote frames best-effort, on
             * the reasoning that a lost reader only shows up as failed writes -
             * but a lost reader is not a quiet reader: Spring cancels the
             * streaming task when the connection errors, and the cancellation
             * *interrupts this thread*, which is at that moment inside the
             * model call. The interrupt surfaced as an `InterruptedIOException`
             * out of the provider's client, the turn died with it, and the
             * answer the person left to come back to was exactly the thing
             * that was lost. So the work now runs where the container cannot
             * reach it, and what the interrupt kills is only the relay.
             *
             * Frames cross through the [ChatGeneration]. The worker never
             * touches the response - a thread writing into a response the
             * container has completed and recycled could land bytes in
             * somebody else's request, which is a worse bug than the one being
             * fixed - and the generation keeps every frame, because what
             * accumulates after the reader leaves is one answer's worth, and
             * because a page that comes back while the answer is still being
             * written reads it from there, from the first frame (#201).
             *
             * Its [io.mszymanski.orknux.connector.model.Hangup] is what the
             * model call is stopped by on purpose: the Stop button (through
             * [ChatGenerations]) and a voice turn's lost reader both reach for
             * it.
             */
            val generation = ChatGeneration()
            val hangup = generation.hangup
            fun send(event: String, payload: Any) = generation.post(event, payload)
            generations.register(id, generation)

            val worker = Thread.ofPlatform().name("chat-$id-answer").daemon().start {
                // The person who asked, carried onto this thread so the turn's
                // audit lines have somebody to be attributed to.
                SecurityContextHolder.setContext(asker)
                // Set the moment the answer is safely in the history, so the
                // rescue below cannot run on top of one that did arrive.
                var kept = false
                try {
                    /*
                     * The turn, built here so that compacting can be announced
                     * while it happens. `compacting` goes out before the
                     * summariser is asked and `compacted` after it, with what it
                     * came to - and a conversation nowhere near its threshold
                     * produces neither, which is every conversation until
                     * somebody turns it on. Issue #286.
                     */
                    val start = begin { event, payload -> send(event, payload) }
                    start.compacted?.let { held ->
                        send(
                            "compacted",
                            mapOf(
                                "replaced" to held.replaced,
                                "kept" to held.kept,
                                "tokens" to held.tokens,
                                "summary" to held.summary,
                            ),
                        )
                    }

                    /*
                     * An agent's answer still arrives as one chunk, and its
                     * working does not.
                     *
                     * The tool loop cannot stream text: a round that asks for a
                     * lookup produces no answer worth showing, and what to say
                     * is only settled once the loop ends. What it *can* report
                     * is what it is doing - which lookup it just made, what came
                     * back, and what it thought on the way - and those are the
                     * things somebody watching a minute of silence wanted. So
                     * the answer lands whole and the working lands as it
                     * happens. A bare model calls no tools; what it can have is
                     * thinking, and that streams beside the answer.
                     */
                    val answer = if (start.agentId == null) {
                        client.stream(
                            start.modelId,
                            start.turns,
                            onThinking = { piece -> send("thinking", mapOf("text" to piece)) },
                            hangup = hangup,
                        ) { piece -> send("chunk", mapOf("text" to piece)) }
                    } else {
                        /*
                         * The watcher is built once and lent twice: to the round
                         * itself, and to the tools, because the drawing tool has
                         * something to announce - a picture it wrote into the
                         * thread, which the open chat learns about no other way.
                         */
                        val watch = generation.watch()
                        chats.ask(start, watch, chatTools.shed(session, watch), hangup)
                            .also { whole ->
                                if (whole is ChatCompletion.Answered) send("chunk", mapOf("text" to whole.content))
                            }
                    }

                    /*
                     * Given up on, so nothing is made of what came back.
                     *
                     * Not written to the history in particular. What the model
                     * had produced when it was hung up on is part of an answer
                     * that was stopped on purpose - Stop, or a voice reader
                     * walking away - and a chat reopened tomorrow ending in half
                     * a sentence attributed to the model is a worse record than
                     * one ending on the question.
                     */
                    if (hangup.hungUp) {
                        log.debug("Chat {} was given up on while it was being answered", id)
                        if (!kept) runCatching(giveUp).onFailure { log.warn("Chat {} could not be put back", id, it) }
                        return@start
                    }

                    when (answer) {
                        is ChatCompletion.Failed -> {
                            giveUp()
                            send("error", mapOf("reason" to answer.reason))
                        }
                        // The loop runs tools to a conclusion, so nothing here
                        // is still asking for one.
                        is ChatCompletion.CalledTools -> {
                            giveUp()
                            send("error", mapOf("reason" to "The model asked for a tool that could not be run"))
                        }
                        is ChatCompletion.Answered -> {
                            chats.finishSend(
                                id,
                                answer.content,
                                answer.reasoning,
                                answer.reasoningMillis,
                                answer.inputTokens,
                                answer.outputTokens,
                            )
                            kept = true
                            // Naming it is not part of the answer, so a
                            // companion model that will not answer costs the
                            // chat nothing.
                            if (said != null) {
                                runCatching { titles.nameFrom(id, said, answer.content) }
                                    .onFailure { log.warn("Could not name chat {}", id, it) }
                            }
                            /*
                             * What the turn took and what it cost, in one frame.
                             *
                             * Costed here rather than on the screen because the
                             * prices are the model's and the model is the
                             * server's - a browser working it out would need
                             * them sent, and then two places would round money.
                             * Null where the model carries no prices, which the
                             * screen shows as nothing rather than as nought.
                             * The chat's own running total is deliberately not
                             * on this frame: it is on `ChatSession`, which the
                             * screen re-reads at the end of every turn anyway.
                             */
                            send(
                                "done",
                                mapOf(
                                    "millis" to answer.millis,
                                    "inputTokens" to answer.inputTokens,
                                    "outputTokens" to answer.outputTokens,
                                    "cost" to models.costOf(start.modelId, answer.inputTokens, answer.outputTokens),
                                ),
                            )
                        }
                    }
                } catch (failure: Exception) {
                    // The provider, the store - something the turn needed. The
                    // only thing left is not to lose the thread's state in
                    // silence.
                    log.warn("Chat {} could not finish its answer", id, failure)
                    if (!kept) runCatching(giveUp).onFailure { log.warn("Chat {} could not be put back", id, it) }
                } finally {
                    generations.release(id, generation)
                    SecurityContextHolder.clearContext()
                }
            }

            // The relay, which reads this answer exactly as a page that comes
            // back to it does. Only what leaving means differs: see [relay].
            relay(stream, generation) { if (interruptOnLeave) hangup.hangUp() }
            // Left holding nothing: the response is the container's again the
            // moment this returns, and the worker was built to never touch it.
            if (!worker.isAlive) log.debug("Chat {} answered before its reader left", id)
        }
    }

    /**
     * Picks up the answer being written on this chat, for a page that has come
     * back to it.
     *
     * Issue #201. Somebody sent a message, left while the agent was thinking,
     * and came back: the answer was being written into the history all along
     * (#335), but the page that came back read the history once, found only the
     * question, and had no way to hear about the answer still being composed -
     * so from where they sat it was lost. This is that way. It reads the
     * [ChatGeneration] from its first frame, so the page draws the thinking and
     * the lookups it missed and then carries on live, in the same vocabulary as
     * [stream] - the page reads both with one reader.
     *
     * The first frame says which it is: `following` where an answer is being
     * written, `idle` where none is - and then the stream ends, because there is
     * nothing to wait for and the history the page already read is the whole of
     * the chat. Leaving this stream never stops the answer, whatever kind of
     * turn it is: following is looking, and Stop is [interrupt].
     */
    @GetMapping("/api/chats/{id}/follow", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun follow(
        @PathVariable id: Long,
        response: HttpServletResponse,
        @RequestParam(defaultValue = "false") wait: Boolean = false,
    ): StreamingResponseBody {
        if (!settings.chatEnabled()) throw ChatDisabledException()
        val session = chats.session(id) ?: throw ChatSessionNotFoundException(id)
        requireOwn(session)
        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        val current = generations.current(id)
        return StreamingResponseBody { _ ->
            val stream = ServerSentEvents(response, mapper)
            val generation = if (wait) awaitWoken(stream, id) ?: return@StreamingResponseBody else current
            if (generation == null) {
                runCatching { stream.send("idle", mapOf<String, Any>()) }
                return@StreamingResponseBody
            }
            if (runCatching { stream.send("following", mapOf<String, Any>()) }.isFailure) return@StreamingResponseBody
            relay(stream, generation) {}
        }
    }

    /**
     * `wait`: a page open on an idle chat, waiting for the server to start a turn
     * on it by itself - a watcher firing, a reminder coming due. Without it such
     * an answer was written to the history and shown to nobody until a reload.
     * `waiting` says so at once, pings keep the connection and notice the page
     * leaving, and the turn is then followed exactly as an asked one.
     */
    private fun awaitWoken(stream: ServerSentEvents, id: Long): ChatGeneration? {
        if (runCatching { stream.send("waiting", mapOf<String, Any>()) }.isFailure) return null
        val gone = java.util.concurrent.atomic.AtomicBoolean(false)
        return try {
            readers.whileReading(stream, gone = { gone.set(true) }) { generations.awaitWoken(id) { gone.get() } }
        } catch (_: InterruptedException) {
            null
        }
    }

    /**
     * Frames to one reader until the answer is over or the reader is gone.
     *
     * [ReaderWatch] pings between frames, because between the question and the
     * first piece of the answer there is nothing else to write and the container
     * reports nothing on its own - see issue #299. A failed write, a failed ping
     * and the container's interrupt all mean the same person left, and [left]
     * says what that means for the turn: a voice turn is spoken and gone, so its
     * asker leaving hangs the model up; a text turn is a record, and the worker
     * goes on composing, unreached by any of this, for whoever comes back.
     *
     * A reader is only a position in the [ChatGeneration], so any number of them
     * can read one answer and none of them takes a frame from another.
     */
    private fun relay(stream: ServerSentEvents, generation: ChatGeneration, left: () -> Unit) {
        readers.whileReading(stream, gone = left) {
            var at = 0
            while (true) {
                val frame = try {
                    generation.frameAt(at++)
                } catch (_: InterruptedException) {
                    left()
                    break
                } ?: break
                if (runCatching { stream.send(frame.event, frame.payload) }.isFailure) {
                    left()
                    break
                }
            }
        }
    }

    private fun requireOwn(session: ChatSession) = ownership.requireOwn(session)

    private companion object {
        val log = LoggerFactory.getLogger(ChatStreamAPI::class.java)
    }
}

/**
 * The agent's round, turned into frames for whoever is reading.
 *
 * A thin adapter and deliberately nothing more: [RoundWatch] is told these
 * things beside the [io.mszymanski.orknux.server.llm.LlmSessionRecorder]
 * calls that keep them, so nothing here decides what is recorded and
 * nothing here can lose a record by failing. What it does decide is the
 * vocabulary, which is the chat's own — a task's stream says `step` about
 * the same facts, because a task page is following a durable log and this
 * is following one answer being composed.
 *
 * `call` carries `at`, which is where the call came in the round. `called`
 * carries the same `at` and is how the browser finds the line to fill in.
 * See [RoundWatch] for why it is a counter rather than the provider's own
 * call id or the session line's.
 */
internal fun ChatGeneration.watch(): RoundWatch = object : RoundWatch {
    override fun thinking(text: String) = post("thinking", mapOf("text" to text))

    override fun drew(markdown: String) = post("drew", mapOf("markdown" to markdown))

    override fun called(at: Int, tool: String, arguments: String) =
        post("call", mapOf("at" to at, "tool" to tool, "arguments" to arguments))

    override fun returned(at: Int, result: String, failed: Boolean) =
        post("called", mapOf("at" to at, "result" to result, "failed" to failed))
}
