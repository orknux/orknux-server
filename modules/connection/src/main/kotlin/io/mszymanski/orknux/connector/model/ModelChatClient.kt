package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import com.openai.errors.OpenAIServiceException
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * One turn of a conversation, as the caller has it. Framework-free on purpose.
 *
 * A turn is usually text. Two kinds are more than that: an assistant turn that
 * asked for tools carries [asked], and the turn answering one carries
 * [respondingTo] — the id of the call it answers. Both shapes need those
 * threaded back on the next request or the model cannot match its own question
 * to the answer.
 *
 * A turn that asked for tools may carry [content] as well, and usually does not.
 * Providers are entitled to answer with a message and calls in one reply and
 * several do it habitually, so the text is kept rather than dropped: it is what
 * the model said, and whoever records the round writes it down beside the calls.
 */
data class ChatTurn(
    val role: String,
    val content: String,
    /**
     * Pictures sent with this turn, as `data:` URLs.
     *
     * Carried beside the text rather than described in it, because a model that
     * can see takes them as part of the message: the OpenAI shape is a content
     * array of `text` and `image_url` parts, which llama.cpp, vLLM and OpenAI
     * itself all read. Empty for every turn that is only words, which keeps the
     * request the plain string shape everything understands.
     */
    val images: List<String> = emptyList(),
    /** Set on an assistant turn that asked for tools. */
    val asked: List<ToolCall> = emptyList(),
    /** Set on a turn answering one, naming the call it answers. */
    val respondingTo: String? = null,
)

/** A model asking for a tool, with the arguments it chose, as JSON. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * What a tool looks like to a model deciding whether to call it.
 *
 * Declared here rather than taken from the app, because this is the layer that
 * turns it into whichever request shape the provider wants. The schema is
 * deliberately small — a name, a description, and named string parameters —
 * because everything crossing this boundary is JSON text anyway.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameterSpec> = emptyList(),
    /**
     * The phrase this tool gets where every tool is listed at once, rather than
     * the description a model reads at the moment of calling. Issue #481.
     *
     * Never sent to a provider: the request carries [description], as it always
     * has. This is for the agent's briefing, which names everything it holds so
     * it knows what it has instead of guessing words for a search - a list that
     * is only affordable if each line is a phrase. Null where nobody wrote one,
     * and the description's own first words stand in.
     */
    val summary: String? = null,
)

data class ToolParameterSpec(val name: String, val description: String, val required: Boolean = false)

/** What a model answered, or why it did not. */
sealed interface ChatCompletion {
    data class Answered(
        val content: String,
        val millis: Long,
        /** What the provider said it charged for; zero when it said nothing. */
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        /**
         * What the model thought on the way, where it emitted any.
         *
         * Empty for every model that is not a reasoning one, and for a
         * reasoning model whose provider does not hand the thinking over — see
         * [ThinkTags] for the three shapes it arrives in. Never part of
         * [content]: it is not what the model said, and a caller that puts the
         * two together has undone the whole of the separation.
         */
        val reasoning: String = "",
        /**
         * How long that thinking went on for, in milliseconds, measured over
         * the reasoning frames alone.
         *
         * Not the round's own [millis], which also covers the answer being
         * written and the request going out. Nought where the reasoning did not
         * arrive as a stream - a blocking call hands it over whole and there is
         * no duration to measure - and the screen draws nothing rather than
         * presenting the turn's time as the thinking's.
         */
        val reasoningMillis: Long = 0,
    ) : ChatCompletion

    /**
     * The model wants tools run before it will answer.
     *
     * Its own turn is handed back with it, so the caller can put the question
     * and the answers into the next request without reconstructing either.
     */
    data class CalledTools(
        val calls: List<ToolCall>,
        val turn: ChatTurn,
        val millis: Long,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        /**
         * What it thought before deciding to look something up. A reasoning
         * model does most of its thinking here rather than in the round that
         * finally answers, which is why this is on both shapes.
         */
        val reasoning: String = "",
        /** How long it thought, measured the same way [Answered.reasoningMillis] is. */
        val reasoningMillis: Long = 0,
    ) : ChatCompletion

    /**
     * @param permanent whether asking again could ever come out differently.
     *   A request the provider refused for what it said will be refused again
     *   in the same words, and so will a model nobody has configured; a
     *   provider that timed out, rate limited the call or fell over has said
     *   nothing about the request at all. Read by a caller with a retry policy
     *   to decide whether to spend an attempt on it.
     *
     *   Settled by default, because that is the answer that costs nothing when
     *   it is wrong: a failure nobody classified is asked once and reported,
     *   rather than billed for three times on the way to the same message.
     */
    data class Failed(
        val reason: String,
        val permanent: Boolean = true,
        /**
         * Whether what went wrong was the model's own reply rather than the
         * asking of it. Issue #465.
         *
         * A tool call it did not finish, arguments that were not JSON, a
         * payload the provider refused to parse: things the model can put right
         * itself on the next round if somebody tells it what happened - which
         * is what the round does with this. False for everything about
         * reaching the provider at all, a rate limit included: being told "that
         * reply could not be used" about a 429 would be a lie, and the step's
         * own retry policy is what answers those.
         */
        val replyFault: Boolean = false,
    ) : ChatCompletion
}

@ConfigurationProperties(prefix = "orknux.model")
data class ModelChatProperties(
    /** How long a model has to answer before the request is given up on. */
    val timeout: Duration = Duration.ofMinutes(2),
)

/**
 * Calls a model and returns what it said.
 *
 * This lives here for the same reason the probe does: it needs the credential,
 * and credentials are read in one place. The caller hands over turns and a model
 * id and gets text back — it never sees a key, and it never learns which shape
 * of request the provider wanted.
 *
 * Two shapes are spoken. Anthropic has its own; everything else here speaks the
 * OpenAI chat-completions shape, which Azure OpenAI, Ollama and most
 * self-hosted servers also answer.
 */
@Service
@EnableConfigurationProperties(ModelChatProperties::class)
class ModelChatClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val probe: ModelProviderProbe,
    private val connections: ConnectionProbe,
    private val mapper: ObjectMapper,
    private val properties: ModelChatProperties,
    private val usage: ModelUsageRecorder,
    private val proxies: ProxyRouter,
    private val openAi: OpenAiChat,
    /** Holds a model's calls under its rate, shared across runs; see [ModelThrottle]. Issue #426. */
    private val throttle: ModelThrottle,
) {

    /**
     * Waits as long as this model's throttle asks before a call is made, and
     * hands back the limits so the caller can honour a Retry-After under the
     * same rule. Issue #426.
     *
     * Resolved and awaited here, at the one door every call goes through, so a
     * new caller cannot forget it. A model or provider that has gone throttles
     * nothing - the call is about to fail for a better reason.
     */
    private fun throttled(modelId: Long): ModelThrottle.Limits? {
        val model = models.findByIdOrNull(modelId) ?: return null
        val provider = providers.findByIdOrNull(model.providerId) ?: return null
        val limits = ModelThrottle.Limits.of(model, provider)
        throttle.awaitTurn(modelId, limits)
        return limits
    }

    /**
     * Every answered call is counted, wherever it came from.
     *
     * Here rather than in the callers because this is the only thing that calls
     * a model: a second caller added later would otherwise have to remember,
     * and the metrics would quietly under-report.
     */
    private fun counted(modelId: Long, answer: ChatCompletion): ChatCompletion {
        // A round that only asked for tools still cost tokens and still took
        // time, so it counts: an agent's real cost is every round it took.
        when (answer) {
            is ChatCompletion.Answered -> {
                runCatching { usage.record(modelId, answer.inputTokens, answer.outputTokens, answer.millis) }
                // The tokens this call spent, into the throttle's window, so the
                // rate it holds the model to reflects what was actually used and
                // not just how often it was called. Issue #426.
                runCatching { throttle.recordUsage(modelId, answer.inputTokens + answer.outputTokens) }
            }
            is ChatCompletion.CalledTools -> {
                runCatching { usage.record(modelId, answer.inputTokens, answer.outputTokens, answer.millis) }
                runCatching { throttle.recordUsage(modelId, answer.inputTokens + answer.outputTokens) }
            }
            is ChatCompletion.Failed -> Unit
        }
        return answer
    }

    /**
     * What the provider said the call cost. OpenAI reports
     * `usage.prompt_tokens` / `completion_tokens`; Anthropic reports
     * `usage.input_tokens` / `output_tokens`. A provider that reports neither
     * leaves them zero rather than being guessed at.
     */
    private fun tokensOf(body: String): Pair<Long, Long> = runCatching {
        val usageNode = mapper.readTree(body).path("usage")
        val input = usageNode.path("prompt_tokens").asLong(usageNode.path("input_tokens").asLong(0))
        val output = usageNode.path("completion_tokens").asLong(usageNode.path("output_tokens").asLong(0))
        input to output
    }.getOrDefault(0L to 0L)

    private val client: HttpClient = proxies.builder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Calls a model and hands back what it says as it says it.
     *
     * The same request as [complete] with `stream` set, read frame by frame:
     * each piece goes to [onChunk] as it lands and is also accumulated, so the
     * caller gets the whole answer at the end and does not have to reassemble
     * it. A model that takes a minute to think is the reason this exists —
     * without it the screen is blank for that minute.
     *
     * [onChunk] runs on the calling thread, between reads.
     *
     * @param onThinking the same, for a reasoning model's thinking, which
     *   arrives in frames of its own and is handed over in frames of its own.
     *   Split here rather than by the caller because this is the layer that
     *   knows the provider's shape, and because a caller that had to do the
     *   splitting would be a caller that could forget to — which is the answer
     *   with the thinking read out as part of it.
     *
     *   Default does nothing, which is right for every caller that has nowhere
     *   to put it: the thinking is dropped rather than folded into the answer.
     *
     * @param hangup somebody who may give up on this call while it is still
     *   running, or null for the caller that cannot. See [Hangup]: what ends a
     *   streaming call is closing the thing being read, and which thing that is
     *   depends on which of the two shapes below is being spoken - so each of
     *   them hands its own over.
     */
    fun stream(
        modelId: Long,
        turns: List<ChatTurn>,
        tools: List<ToolSpec> = emptyList(),
        onThinking: (String) -> Unit = {},
        hangup: Hangup? = null,
        onChunk: (String) -> Unit,
    ): ChatCompletion {
        val limits = throttled(modelId)
        spoken(modelId)?.let { shape ->
            return through(modelId, shape, limits) {
                openAi.stream(shape.provider, shape.model, turns, tools, onThinking, hangup, onChunk)
            }
        }

        if (hangup?.hungUp == true) return ChatCompletion.Failed(HUNG_UP, permanent = false)

        val call = prepare(modelId, turns, streaming = true, tools = tools)
        if (call is Prepared.Failed) return ChatCompletion.Failed(call.reason)
        val ready = call as Prepared.Call

        val started = System.nanoTime()
        return try {
            val response = client.send(ready.request, HttpResponse.BodyHandlers.ofInputStream())
            /*
             * The body is what this shape is read from, so the body is what
             * closing it means. Closed from the other thread, the reader below
             * throws and lands in the same catch a dropped provider lands in -
             * which is the point, since a stream nobody is reading and a stream
             * that broke are the same thing to everything downstream.
             */
            hangup?.holding { response.body().close() }
            if (response.statusCode() !in 200..299) {
                val body = response.body().reader().use { it.readText() }
                refused(ready.request, response.statusCode())
                return ChatCompletion.Failed(
                    reason(response.statusCode(), body),
                    permanent = settled(response.statusCode()),
                    replyFault = aboutTheCall(body),
                )
            }

            /*
             * A provider that ignored `stream: true` and sent the whole thing.
             *
             * Allowed, and it happens: a local server, a proxy in front of one,
             * an endpoint that only does blocking. Read as a stream there are no
             * `data:` frames to find, so the answer came back empty and the run
             * failed on a silence this reader had invented. What it sent is a
             * complete response, so it is read as one.
             */
            val kind = response.headers().firstValue("content-type").orElse("")
            if (!kind.contains("event-stream", ignoreCase = true)) {
                val body = response.body().bufferedReader().use { it.readText() }
                hangup?.letGo()
                if (hangup?.hungUp == true) return ChatCompletion.Failed(HUNG_UP, permanent = false)
                return wholeAnswer(
                    modelId,
                    ready,
                    body,
                    (System.nanoTime() - started) / 1_000_000,
                    told = onChunk,
                    thought = onThinking,
                )
            }

            val whole = StringBuilder()
            val thinking = StringBuilder()
            var input = 0L
            var output = 0L
            /*
             * One splitter for the whole stream, because a tag arrives in
             * pieces. See [ThinkTags]: a provider is free to send `<thi` and
             * `nk>` in two frames, and a splitter built per frame would put
             * both of them on screen.
             */
            val tags = ThinkTags()
            /*
             * The calls being spelled out across the frames, gathered by the
             * place the provider gives them.
             *
             * A streamed tool call arrives in pieces the same way text does -
             * the name in one frame and the arguments a few characters at a
             * time after it - so there is nothing to hand back until the stream
             * ends. Keyed by index rather than appended, because a model asking
             * for two tools interleaves their frames.
             */
            val asked = sortedMapOf<Int, StreamedCall>()

            /*
             * When the thinking stopped, so the screen can say how long it went
             * on for.
             *
             * **From the request going out, not from the first reasoning
             * frame.** Measuring between the first and last reasoning frames is
             * what this did first, and it reported nothing on most real
             * providers: a model that emits its whole reasoning in one frame
             * has a first frame and a last frame at the same instant, so the
             * answer was nought and the screen drew no time at all. It was only
             * ever non-zero here because a stub deliberately paused between
             * frames.
             *
             * Time to the end of the thinking is also the more honest number.
             * What somebody waited through is the request going out, the model
             * loading the prompt, and then the reasoning being produced - all
             * of it before there was a word of answer to read. That is the wait
             * the block is explaining.
             */
            var sawThought = false
            var thoughtTo = 0L

            fun hand(piece: ModelPiece) {
                if (piece.thought.isNotEmpty()) {
                    sawThought = true
                    thoughtTo = System.nanoTime()
                    thinking.append(piece.thought)
                    onThinking(piece.thought)
                }
                if (piece.said.isNotEmpty()) {
                    whole.append(piece.said)
                    onChunk(piece.said)
                }
            }

            response.body().bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    // The counts come in a frame of their own, usually the last
                    // one, and carry no text — so they are read from every frame
                    // rather than from the answer.
                    if (line.startsWith(DATA_PREFIX)) {
                        val payload = line.removePrefix(DATA_PREFIX).trim()
                        val (frameInput, frameOutput) = tokensOf(payload)
                        if (frameInput > 0) input = frameInput
                        if (frameOutput > 0) output = frameOutput
                        gatherCalls(payload, ready.anthropic, asked)
                    }
                    val frame = pieceOf(line, ready.anthropic) ?: return@forEach
                    // Thinking the provider named is thinking: it does not go
                    // through the tag splitter, which is only for the shape
                    // where nobody named it.
                    if (frame.thought.isNotEmpty()) hand(ModelPiece(thought = frame.thought))
                    if (frame.said.isNotEmpty()) hand(tags.feed(frame.said))
                }
            }
            hangup?.letGo()
            if (hangup?.hungUp == true) return ChatCompletion.Failed(HUNG_UP, permanent = false)

            hand(tags.finish())
            val thoughtFor = if (!sawThought) 0L else (thoughtTo - started) / 1_000_000

            /*
             * A round that asked for tools has not answered, whichever way it
             * was read. The same rule the blocking path follows, and it has to
             * be the same rule or an agent would behave differently depending
             * on whether anybody was watching it.
             */
            val calls = asked.values.mapNotNull { it.settled() }
            if (calls.isNotEmpty()) {
                val millis = (System.nanoTime() - started) / 1_000_000
                return counted(
                    modelId,
                    ChatCompletion.CalledTools(
                        calls = calls,
                        turn = ChatTurn("assistant", whole.toString(), asked = calls),
                        millis = millis,
                        inputTokens = input,
                        outputTokens = output,
                        reasoning = thinking.toString(),
                        reasoningMillis = thoughtFor,
                    ),
                )
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            if (whole.isBlank()) {
                /*
                 * A stream that carried no text is a round that produced
                 * nothing, not a request the provider objected to - the same
                 * question asked again is as likely to be answered as not.
                 *
                 * Thinking does not count as text, and deliberately: a model
                 * that thought and then said nothing has not answered, and
                 * showing its reasoning under a silence would read as an answer
                 * somebody has to interpret. Blank rather than empty for the
                 * same reason, since a closing tag usually leaves a newline
                 * behind it.
                 */
                ChatCompletion.Failed("The provider answered with no message", permanent = false)
            } else {
                counted(
                    modelId,
                    ChatCompletion.Answered(
                        whole.toString(),
                        millis,
                        input,
                        output,
                        thinking.toString(),
                        thoughtFor,
                    ),
                )
            }
        } catch (failure: Exception) {
            // Nothing came back at all: a socket that closed, a name that did
            // not resolve, the request timeout running out on a model still
            // thinking. None of it is the provider's answer to this request, so
            // none of it is settled.
            log.warn("Calling {} failed", ready.request.uri(), failure)
            ChatCompletion.Failed(failure.message ?: "The provider could not be reached", permanent = false)
        }
    }

    /**
     * One piece of an answer out of one line of the stream, or null when the
     * line carries none — a keep-alive, a blank, an event name, or the `[DONE]`
     * both shapes end with.
     *
     * Anthropic sends the text on `content_block_delta` and other things on
     * other events, so the field is read rather than the event name: a delta
     * with no `text` is a delta about something else. Its thinking arrives on
     * the same event under `delta.thinking`, which is why reading the field is
     * the right rule for both halves rather than a shortcut for one.
     *
     * The OpenAI shape has no thinking in the specification and two spellings
     * in practice. `reasoning_content` is DeepSeek's, which vLLM, SGLang and
     * llama.cpp copied; `reasoning` is what a handful of gateways send instead.
     * Both are read, in that order, because a server that sends neither sends
     * nothing and a server that sends one does not send the other. OpenAI's own
     * chat-completions endpoint sends no reasoning text at all — only a token
     * count — so a model behind it thinks in silence, and that is the
     * provider's decision rather than something this can recover.
     */
    private fun pieceOf(line: String, anthropic: Boolean): ModelPiece? {
        if (!line.startsWith(DATA_PREFIX)) return null
        val payload = line.removePrefix(DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == DONE) return null

        return runCatching {
            val tree = mapper.readTree(payload)
            if (anthropic) {
                val delta = tree.path("delta")
                ModelPiece(said = wordsOf(delta, "text"), thought = wordsOf(delta, "thinking"))
            } else {
                val delta = tree.path("choices").firstOrNull()?.path("delta") ?: return@runCatching null
                ModelPiece(
                    said = wordsOf(delta, "content"),
                    thought = wordsOf(delta, "reasoning_content", "reasoning"),
                )
            }
        }.getOrNull()?.takeIf { it.said.isNotEmpty() || it.thought.isNotEmpty() }
    }

    /**
     * The first of these fields that holds a string, or nothing.
     *
     * **Jackson 3's `stringValue()` throws on a node that is not a string**, and
     * a missing field is not a string — so the obvious spelling of "read two
     * fields off one object" throws the moment either of them is absent, which
     * is every frame. Wrapped in a `runCatching` around the whole frame, as the
     * code here already was, that does not fail: it silently discards the
     * frame. A stream where every frame carries one of two fields would then
     * arrive as no frames at all, and be reported as a provider that answered
     * with no message.
     *
     * Asked field by field for that reason. Several fields also lets one
     * accessor cover a shape spelled two ways, which the OpenAI-compatible
     * world needs for reasoning.
     */
    /**
     * One tool call being spelled out across a stream's frames.
     *
     * The name arrives once and the arguments a few characters at a time, so
     * this is a builder rather than a value: it is only a [ToolCall] once the
     * stream has ended.
     */
    private class StreamedCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        /** The call, or null for a slot the provider opened and never named. */
        fun settled(): ToolCall? =
            if (name.isEmpty()) null else ToolCall(id, name, arguments.toString())
    }

    /**
     * Gathers whatever one frame says about the tools being asked for.
     *
     * Both shapes spell a call out over several frames and both key the pieces
     * by an index, which is what lets a model ask for two tools at once without
     * their arguments being concatenated into one unparseable string.
     *
     * The OpenAI shape puts them in `choices[0].delta.tool_calls`, each with its
     * own `index`; the name comes on the first frame for that index and
     * `function.arguments` in pieces after it. Anthropic opens a block with
     * `content_block_start` carrying `tool_use`, its id and its name, and then
     * sends `input_json_delta` frames whose `partial_json` builds the input.
     */
    private fun gatherCalls(payload: String, anthropic: Boolean, into: MutableMap<Int, StreamedCall>) {
        if (payload.isEmpty() || payload == DONE) return
        runCatching {
            val tree = mapper.readTree(payload)
            if (anthropic) {
                val at = tree.path("index").asInt(0)
                val block = tree.path("content_block")
                if (wordsOf(block, "type") == "tool_use") {
                    val call = into.getOrPut(at) { StreamedCall() }
                    call.id = wordsOf(block, "id")
                    call.name = wordsOf(block, "name")
                }
                val delta = tree.path("delta")
                if (wordsOf(delta, "type") == "input_json_delta") {
                    into.getOrPut(at) { StreamedCall() }.arguments.append(wordsOf(delta, "partial_json"))
                }
            } else {
                val calls = tree.path("choices").firstOrNull()?.path("delta")?.path("tool_calls") as? ArrayNode
                    ?: return@runCatching
                calls.forEach { asked ->
                    val call = into.getOrPut(asked.path("index").asInt(0)) { StreamedCall() }
                    wordsOf(asked, "id").takeIf { it.isNotEmpty() }?.let { call.id = it }
                    val function = asked.path("function")
                    wordsOf(function, "name").takeIf { it.isNotEmpty() }?.let { call.name = it }
                    call.arguments.append(wordsOf(function, "arguments"))
                }
            }
        }
    }

    private fun wordsOf(node: JsonNode?, vararg names: String): String {
        if (node == null) return ""
        names.forEach { name ->
            val held = runCatching { node.path(name).stringValue() }.getOrNull()
            if (!held.isNullOrEmpty()) return held
        }
        return ""
    }

    /**
     * One whole response, read as an answer or as a round that asked for tools.
     *
     * Its own function because two paths arrive at it. The blocking call is the
     * obvious one; the other is a *streaming* call whose provider answered with
     * an ordinary body anyway, which is allowed and happens - `stream: true` is
     * a request, not a guarantee, and a server that ignores it sends the whole
     * completion in one piece. Read as a stream, that body has no `data:` lines
     * in it, so nothing was found and the round came back "the provider
     * answered with no message" - a silence invented by the reader.
     *
     * @param told what to hand the text to, for the caller that is watching a
     *   stream. The answer arrives in one piece rather than in frames, which is
     *   what the provider sent; there is nothing to be done about that but say
     *   it once.
     * @param thought the same for the reasoning.
     */
    private fun wholeAnswer(
        modelId: Long,
        ready: Prepared.Call,
        body: String,
        millis: Long,
        told: (String) -> Unit = {},
        thought: (String) -> Unit = {},
    ): ChatCompletion {
        val (input, output) = tokensOf(body)
        val named = if (ready.anthropic) anthropicReasoning(body) else openAiReasoning(body)

        // A model that asked for tools has not answered yet, and its text —
        // if it sent any — is thinking aloud rather than a reply.
        val asked = if (ready.anthropic) anthropicCalls(body) else openAiCalls(body)
        if (asked.isNotEmpty()) {
            /*
             * A tool call the model did not finish is worse than none: its
             * arguments are truncated JSON, and running it or handing it on
             * turns a clear cause - the answer was cut off at the output limit -
             * into a JSON parse error nobody can act on. Caught here, where the
             * finish reason is still in hand, and said in words. Issue #392.
             */
            asked.firstOrNull { argumentsBroken(it.arguments) }?.let { unfinished ->
                return if (wasCutOff(body, ready.anthropic)) {
                    ChatCompletion.Failed(
                        "The model's answer was cut off at its output limit before it finished the " +
                            "${unfinished.name} tool call, so the call is incomplete. Raise the model's maximum " +
                            "output, or have it do less in one turn.",
                        permanent = false,
                        replyFault = true,
                    )
                } else {
                    ChatCompletion.Failed(
                        "The model asked to call ${unfinished.name} but the arguments it sent were not valid JSON.",
                        permanent = false,
                        replyFault = true,
                    )
                }
            }

            val raw = (if (ready.anthropic) anthropicContent(body) else openAiContent(body)).orEmpty()
            val split = split(raw, named)
            if (split.thought.isNotEmpty()) thought(split.thought)
            return counted(
                modelId,
                ChatCompletion.CalledTools(
                    calls = asked,
                    /*
                     * The turn handed back carries what the model said and not
                     * what it thought. It goes into the next request as the
                     * assistant turn that asked for these tools, and a provider
                     * handed back its own reasoning as ordinary assistant text
                     * either rejects it - Anthropic checks a signature on a
                     * thinking block - or reads it as the model's words, which
                     * is exactly the confusion between thinking and saying that
                     * this whole change is about.
                     */
                    turn = ChatTurn("assistant", split.said, asked = asked),
                    millis = millis,
                    inputTokens = input,
                    outputTokens = output,
                    reasoning = split.thought,
                ),
            )
        }

        val raw = (if (ready.anthropic) anthropicContent(body) else openAiContent(body))
            ?: return ChatCompletion.Failed("The provider answered with no message", permanent = false)
        val split = split(raw, named)
        if (split.said.isBlank()) {
            return ChatCompletion.Failed("The provider answered with no message", permanent = false)
        }
        if (split.thought.isNotEmpty()) thought(split.thought)
        told(split.said)
        return counted(modelId, ChatCompletion.Answered(split.said, millis, input, output, split.thought))
    }

    /**
     * @param tools what the model may call. Empty means it answers or fails —
     *   which is every caller that is not running an agent.
     * @param hangup somebody who may give up on this call while it is still
     *   running, or null for the caller that cannot. A blocking call has no
     *   stream to close, so what is handed over is the request itself: the
     *   future it is waited on is cancelled, which cancels the request under
     *   it, and the wait ends with a failure nobody is left to read. Also
     *   asked before the call is made and after the throttle has been waited
     *   out, because a stop that lands during the wait for the model's turn
     *   must not be followed by the call it was waiting to make. Issue #440.
     */
    fun complete(
        modelId: Long,
        turns: List<ChatTurn>,
        tools: List<ToolSpec> = emptyList(),
        hangup: Hangup? = null,
    ): ChatCompletion {
        if (hangup?.hungUp == true) return ChatCompletion.Failed(HUNG_UP, permanent = false)
        val limits = throttled(modelId)
        if (hangup?.hungUp == true) return ChatCompletion.Failed(HUNG_UP, permanent = false)
        spoken(modelId)?.let { shape ->
            return through(modelId, shape, limits) {
                openAi.complete(shape.provider, shape.model, turns, tools, hangup)
            }
        }

        val call = prepare(modelId, turns, streaming = false, tools = tools)
        if (call is Prepared.Failed) return ChatCompletion.Failed(call.reason)
        val ready = call as Prepared.Call

        // Retried only for a 429 that carried a Retry-After the model accepts;
        // everything else is answered once and handed on. Issue #426.
        var attempt = 0
        while (true) {
            val started = System.nanoTime()
            val response = try {
                /*
                 * Sent and then waited for, rather than `send`, so there is a
                 * handle to cancel: cancelling the future cancels the exchange
                 * under it, which is what a hangup means for a call that has
                 * no stream to close. Waiting on it is what `send` does too,
                 * and it ends the same way on an interrupt. Issue #440.
                 */
                val sent = client.sendAsync(ready.request, HttpResponse.BodyHandlers.ofString())
                hangup?.holding { sent.cancel(true) }
                try {
                    sent.get()
                } finally {
                    hangup?.letGo()
                }
            } catch (failure: Exception) {
                if (hangup?.hungUp == true) return ChatCompletion.Failed(HUNG_UP, permanent = false)
                // Nothing came back at all: a socket that closed, a name that
                // did not resolve, the request timeout running out on a model
                // still thinking. None of it is the provider's answer, so none
                // of it is settled. The future wraps what went wrong, and the
                // sentence wanted is the wrapped one's.
                val cause = (failure as? ExecutionException)?.cause ?: failure
                log.warn("Calling {} failed", ready.request.uri(), cause)
                return ChatCompletion.Failed(cause.message ?: "The provider could not be reached", permanent = false)
            }
            val millis = (System.nanoTime() - started) / 1_000_000

            if (response.statusCode() == HTTP_TOO_MANY_REQUESTS &&
                waitedForRetryAfter(modelId, limits, retryAfterOf(response), attempt)
            ) {
                attempt++
                continue
            }
            if (response.statusCode() !in 200..299) {
                refused(ready.request, response.statusCode())
                return ChatCompletion.Failed(
                    reason(response.statusCode(), response.body()),
                    permanent = settled(response.statusCode()),
                    replyFault = aboutTheCall(response.body()),
                )
            }
            return wholeAnswer(modelId, ready, response.body(), millis)
        }
    }

    /**
     * The provider and model, when this is a call the SDK makes.
     *
     * Null for Anthropic, which is a different wire format with a different
     * library and keeps the hand-built path below; null too where the model or
     * provider has gone, so the sentence about that is still said in one place.
     */
    private fun spoken(modelId: Long): Spoken? {
        val model = models.findByIdOrNull(modelId) ?: return null
        if (!model.enabled) return null
        val provider = providers.findByIdOrNull(model.providerId) ?: return null
        if (provider.type == ProviderType.ANTHROPIC) return null
        return Spoken(provider, model)
    }

    private data class Spoken(val provider: ModelProvider, val model: LlmModel)

    /**
     * The most tools this model's provider will accept on one request.
     *
     * Asked here rather than decided by the caller, because the provider is
     * what knows: OpenAI and Azure refuse the whole request over 128 of them,
     * and the caller would be reading a number out of a hardcoded map it had no
     * way to keep true. See [ProviderType.toolLimit].
     *
     * A model or provider that has gone answers with the lowest of the numbers
     * any of them use. The call is about to fail for a better reason, and
     * guessing high here would turn that into the provider's sentence about an
     * array being too long.
     */
    fun toolLimit(modelId: Long): Int {
        val provider = models.findByIdOrNull(modelId)?.let { providers.findByIdOrNull(it.providerId) }
        return provider?.type?.toolLimit ?: ProviderType.entries.minOf { it.toolLimit }
    }

    /**
     * A call through the SDK, answered in this application's own words.
     *
     * The address is still vetted here. [OpenAiChat] is handed a provider rather
     * than a URL and would happily call whatever was typed into the form, and
     * "where is this request going" is a question this application answers for
     * every outbound call it makes - see [ConnectionProbe]. Before the call
     * rather than after, so one that will not be made fetches no token.
     *
     * The timing is taken here too, because it is what the caller shows and the
     * SDK does not report it.
     */
    private fun through(
        modelId: Long,
        shape: Spoken,
        limits: ModelThrottle.Limits?,
        call: () -> OpenAiChat.Outcome,
    ): ChatCompletion {
        val endpoint = shape.provider.openAiBase()
        connections.vet(endpoint)?.let {
            return ChatCompletion.Failed("${shape.provider.name} cannot be called: $it")
        }

        // Retried only for a 429 whose Retry-After the model accepts - which is
        // the case this SDK path most has to answer, since Azure is what rate
        // limits and Azure is spoken here. Issue #426.
        var attempt = 0
        while (true) {
            val started = System.nanoTime()
            val outcome = try {
                call()
            } catch (refused: OpenAIServiceException) {
                if (refused.statusCode() == HTTP_TOO_MANY_REQUESTS &&
                    waitedForRetryAfter(modelId, limits, retryAfterOf(refused), attempt)
                ) {
                    attempt++
                    continue
                }
                /*
                 * The provider answered, and what it answered decides whether
                 * asking again is worth anything. [settled] is the same rule the
                 * hand-built path applies to a status code, and it has to stay
                 * the same rule: an agent that retried a refusal in one shape and
                 * not the other would behave differently for a reason nobody
                 * could see.
                 */
                log.warn("{} answered {}", endpoint, refused.statusCode())
                return ChatCompletion.Failed(
                    refused.message ?: "The provider refused the request",
                    settled(refused.statusCode()),
                    replyFault = aboutTheCall(refused.message.orEmpty()),
                )
            } catch (failure: Exception) {
                // Nothing came back at all: a socket that closed, a name that
                // did not resolve, a timeout on a model still thinking. None of
                // it is the provider's answer, so none of it is settled.
                log.warn("Calling {} failed", endpoint, failure)
                return ChatCompletion.Failed(failure.message ?: "The provider could not be reached", permanent = false)
            }
            val millis = (System.nanoTime() - started) / 1_000_000

            return when (outcome) {
                is OpenAiChat.Outcome.Failed -> ChatCompletion.Failed(outcome.reason)
                is OpenAiChat.Outcome.Answered -> answered(modelId, outcome, millis)
            }
        }
    }

    private fun answered(modelId: Long, outcome: OpenAiChat.Outcome.Answered, millis: Long): ChatCompletion {
        val split = split(outcome.said, outcome.thought)

        // A model that asked for tools has not answered yet, and its text - if
        // it sent any - is thinking aloud rather than a reply.
        if (outcome.calls.isNotEmpty()) {
            return counted(
                modelId,
                ChatCompletion.CalledTools(
                    calls = outcome.calls,
                    turn = ChatTurn("assistant", split.said, asked = outcome.calls),
                    millis = millis,
                    inputTokens = outcome.inputTokens,
                    outputTokens = outcome.outputTokens,
                    reasoning = split.thought,
                    reasoningMillis = outcome.thoughtMillis,
                ),
            )
        }

        if (split.said.isBlank()) {
            return ChatCompletion.Failed("The provider answered with no message", permanent = false)
        }
        return counted(
            modelId,
            ChatCompletion.Answered(
                split.said,
                millis,
                outcome.inputTokens,
                outcome.outputTokens,
                split.thought,
                outcome.thoughtMillis,
            ),
        )
    }

    /**
     * Everything needed to make the call, or why it cannot be made.
     *
     * Both paths resolve the same model, the same credential and the same body;
     * only what they do with the response differs. Building it twice is how the
     * two drift apart.
     */
    private fun prepare(
        modelId: Long,
        turns: List<ChatTurn>,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): Prepared {
        val model = models.findByIdOrNull(modelId)
            ?: return Prepared.Failed("That model no longer exists")
        if (!model.enabled) return Prepared.Failed("${model.name} is not active")

        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Prepared.Failed("The provider ${model.name} belongs to no longer exists")

        val anthropic = provider.type == ProviderType.ANTHROPIC
        val endpoint = endpointFor(provider, model, anthropic)
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            return Prepared.Failed("The provider endpoint is not a usable URL")
        }

        /*
         * An endpoint is whatever somebody typed into the provider form, and
         * this request carries the stored key, so where it is going is asked
         * about here and not only behind the "Test provider" button - a check
         * nobody has to press is not a check. [ConnectionProbe] decides, the
         * same as it does for the probe and for a workflow's own request, and
         * the refusal is returned rather than logged: it becomes the failure
         * the chat shows, which is where whoever configured the provider is.
         *
         * Before the credential rather than after it, so a call that will not
         * be made does not decrypt a key or fetch a token to carry.
         */
        connections.vet(endpoint)?.let { return Prepared.Failed("${provider.name} cannot be called: $it") }

        val credential = when (val resolved = probe.credentials(provider)) {
            is ModelProviderProbe.Credential.Failed -> return Prepared.Failed(resolved.reason)
            is ModelProviderProbe.Credential.Header -> resolved.header
        }

        val body = try {
            if (anthropic) {
                anthropicBody(model, turns, streaming, tools)
            } else {
                openAiBody(model, turns, streaming, tools)
            }
        } catch (refused: UnusableImage) {
            // A picture that cannot be carried is said out loud. Dropping it and
            // sending the words alone is what this used to do, and the model
            // then answered plausibly about something it had never been shown -
            // which is worse than a refusal by exactly the amount that a
            // confident wrong answer is worse than an error message.
            return Prepared.Failed("${provider.name} cannot be sent this picture: ${refused.message}")
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(properties.timeout)
            .header("Content-Type", "application/json")
            .header(credential.name, credential.value)
        // Anthropic wants its version pinned on every request.
        if (anthropic) builder.header("anthropic-version", ANTHROPIC_VERSION)

        return Prepared.Call(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), anthropic)
    }

    private sealed interface Prepared {
        data class Call(val request: HttpRequest, val anthropic: Boolean) : Prepared
        data class Failed(val reason: String) : Prepared
    }

    /**
     * Where the request goes. Azure OpenAI puts the deployment and the API
     * version in the path rather than in the body, which is the whole of its
     * difference from the shape everything else uses.
     *
     * The OpenAI shape hangs off [ModelProvider.openAiBase] rather than off the
     * endpoint directly, so that a type whose compatible surface is not at the
     * root - Ollama's is under `/v1` - is called where the check reached it.
     * Checking one path and calling another is how a provider comes back
     * Connected and then 404s on every message.
     */
    private fun endpointFor(provider: ModelProvider, model: LlmModel, anthropic: Boolean): String {
        val base = provider.endpoint.trimEnd('/')
        return when {
            anthropic -> "$base/messages"
            provider.type == ProviderType.AZURE_OPENAI -> {
                val deployment = provider.deploymentName?.ifBlank { null } ?: model.modelId
                val version = provider.apiVersion?.ifBlank { null } ?: DEFAULT_AZURE_VERSION
                "$base/openai/deployments/$deployment/chat/completions?api-version=$version"
            }
            else -> "${provider.openAiBase()}/chat/completions"
        }
    }

    private fun openAiBody(
        model: LlmModel,
        turns: List<ChatTurn>,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): String {
        val root = mapper.createObjectNode()
        root.put("model", model.modelId)
        if (streaming) {
            root.put("stream", true)
            /*
             * And ask for the counts, which a stream does not send otherwise.
             *
             * A blocking call comes back with a `usage` object; a stream sends
             * one only when this is set, so every OpenAI-shape answer given in
             * the chat window - which always streams - was recorded as nought
             * tokens while the same model answered with a count anywhere else.
             * The metrics under-reported for exactly the traffic there is most
             * of, and nothing on the screen could say what an answer cost.
             *
             * Part of the shape since 2024-05 and ignored by a server that has
             * not implemented it, which is the ordinary fate of a field an
             * OpenAI-compatible endpoint does not know.
             */
            root.putObject("stream_options").put("include_usage", true)
        }
        model.maxOutput?.let { root.put("max_tokens", it) }
        val messages = root.putArray("messages")
        turns.forEach { turn ->
            val message = messages.addObject()
            when {
                // The answer to a tool call is its own role, and has to name the
                // call it answers or the model cannot pair them up.
                turn.respondingTo != null -> message
                    .put("role", "tool")
                    .put("tool_call_id", turn.respondingTo)
                    .put("content", turn.content)

                turn.asked.isNotEmpty() -> {
                    message.put("role", turn.role)
                    // A turn that only asked may carry no text at all.
                    if (turn.content.isNotEmpty()) message.put("content", turn.content)
                    val calls = message.putArray("tool_calls")
                    turn.asked.forEach { asked ->
                        val call = calls.addObject()
                        call.put("id", asked.id).put("type", "function")
                        call.putObject("function").put("name", asked.name).put("arguments", asked.arguments)
                    }
                }

                /*
                 * A turn with pictures is a list of parts rather than a string.
                 *
                 * `[{type: text}, {type: image_url}]` is the shape OpenAI
                 * defined and llama.cpp, vLLM and Ollama all read; a model that
                 * cannot see ignores the image part rather than failing, which
                 * is why this does not need to know whether the model can.
                 */
                turn.images.isNotEmpty() -> {
                    message.put("role", turn.role)
                    val parts = message.putArray("content")
                    if (turn.content.isNotEmpty()) {
                        parts.addObject().put("type", "text").put("text", turn.content)
                    }
                    turn.images.forEach { image ->
                        parts.addObject()
                            .put("type", "image_url")
                            .putObject("image_url")
                            .put("url", image)
                    }
                }

                else -> message.put("role", turn.role).put("content", turn.content)
            }
        }

        if (tools.isNotEmpty()) {
            declareOpenAiTools(root, tools)
            // Beside the tools and only there: see LlmModel.parallelToolCalls. Issue #530.
            model.parallelToolCalls?.let { root.put("parallel_tool_calls", it) }
        }
        return mapper.writeValueAsString(root)
    }

    private fun declareOpenAiTools(root: ObjectNode, tools: List<ToolSpec>) {
        val declared = root.putArray("tools")
        tools.forEach { tool ->
            val entry = declared.addObject()
            entry.put("type", "function")
            val function = entry.putObject("function")
            function.put("name", tool.name).put("description", tool.description)
            val schema = function.putObject("parameters")
            schema.put("type", "object")
            val properties = schema.putObject("properties")
            tool.parameters.forEach { parameter ->
                properties.putObject(parameter.name).put("type", "string").put("description", parameter.description)
            }
            val required = schema.putArray("required")
            tool.parameters.filter { it.required }.forEach { required.add(it.name) }
        }
    }

    /**
     * Anthropic takes the system turn beside the messages rather than among
     * them - and takes its messages strictly alternating.
     *
     * ## Why consecutive turns are joined here
     *
     * Anthropic's Messages API rejects two messages of the same role in a row.
     * It is a 400 on the request, not a worse answer: the round never reaches
     * the model, so the failure is total and arrives as a provider error rather
     * than as anything a reader could connect to the conversation. The OpenAI
     * shape has no such rule and every other provider this talks to speaks it,
     * which is exactly why the rule cannot live upstream.
     *
     * And there are two ordinary ways a caller produces such a run, neither of
     * them a mistake:
     *
     * - **Two assistant turns.** A round may answer with a message and tool
     *   calls together, so a session holds what the agent said on its way to a
     *   lookup and then what it finally answered. Read back as memory those are
     *   two assistant turns with nothing between them.
     * - **Two or more user turns.** A round that called three tools threads
     *   three results back, each its own turn, and a task's prompt puts what
     *   was recalled after what was remembered. Both are user turns by the time
     *   they get here, and this shape predates any of the above.
     *
     * So the joining is done where the wire rule is, and only for the provider
     * that has it. **Nothing upstream is bent to suit it**: the session is a
     * record of what happened and keeps the turns apart, because an agent that
     * said two things said two things, and a transcript that merged them to
     * please one provider's request format would be lying about the round for
     * the sake of a body it is not even part of. It is the same argument this
     * body already makes for spelling a tool result as a user turn - the shape
     * is the provider's, so the translation is the provider's.
     *
     * Joined as separate parts rather than as concatenated text, so two things
     * the agent said stay two things and are not run together into one
     * sentence that was never spoken.
     */
    private fun anthropicBody(
        model: LlmModel,
        turns: List<ChatTurn>,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
    ): String {
        val root = mapper.createObjectNode()
        root.put("model", model.modelId)
        root.put("max_tokens", model.maxOutput ?: DEFAULT_MAX_TOKENS)
        if (streaming) root.put("stream", true)

        val system = turns.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        if (system.isNotBlank()) root.put("system", system)

        val messages = root.putArray("messages")
        val talking = turns.filter { it.role != "system" }
        var at = 0
        while (at < talking.size) {
            val speaking = anthropicRole(talking[at])
            var next = at + 1
            while (next < talking.size && anthropicRole(talking[next]) == speaking) next++
            val run = talking.subList(at, next)

            val message = messages.addObject()
            message.put("role", speaking)
            /*
             * One turn of plain words is plain words, which is what this sent
             * before any of the merging existed and what the overwhelming
             * majority of messages still are. Blocks only where there is
             * something a string cannot hold, or more than one turn to hold.
             */
            val alone = run.singleOrNull()
                ?.takeIf { it.respondingTo == null && it.asked.isEmpty() && it.images.isEmpty() }
            if (alone != null) {
                message.put("content", alone.content)
            } else {
                val blocks = message.putArray("content")
                run.forEach { anthropicParts(it, blocks) }
            }
            at = next
        }

        if (tools.isNotEmpty()) {
            val declared = root.putArray("tools")
            tools.forEach { tool ->
                val entry = declared.addObject()
                entry.put("name", tool.name).put("description", tool.description)
                val schema = entry.putObject("input_schema")
                schema.put("type", "object")
                val properties = schema.putObject("properties")
                tool.parameters.forEach { parameter ->
                    properties.putObject(parameter.name)
                        .put("type", "string")
                        .put("description", parameter.description)
                }
                val required = schema.putArray("required")
                tool.parameters.filter { it.required }.forEach { required.add(it.name) }
            }
        }
        return mapper.writeValueAsString(root)
    }

    /**
     * Who a turn speaks as on this wire.
     *
     * A tool result is a user turn holding a result block, which is most of how
     * this shape differs from the other one - and it is asked here rather than
     * read off [ChatTurn.role] so that the run-joining above groups it with the
     * turns it will actually be sent beside.
     */
    private fun anthropicRole(turn: ChatTurn) = if (turn.respondingTo != null) "user" else turn.role

    /**
     * One turn, as the content blocks it is made of, appended to a message that
     * may be carrying more than one turn's worth.
     *
     * The order within a turn is the order it was said in: the words first and
     * then whatever they were about, because a picture with the question after
     * it reads as a different question, and a tool call above the sentence
     * introducing it reads as a model that spoke afterwards.
     */
    private fun anthropicParts(turn: ChatTurn, blocks: ArrayNode) {
        if (turn.respondingTo != null) {
            blocks.addObject()
                .put("type", "tool_result")
                .put("tool_use_id", turn.respondingTo)
                .put("content", turn.content)
            return
        }
        if (turn.content.isNotEmpty()) {
            blocks.addObject().put("type", "text").put("text", turn.content)
        }
        turn.asked.forEach { asked ->
            val block = blocks.addObject()
            block.put("type", "tool_use").put("id", asked.id).put("name", asked.name)
            block.set("input", argumentsOf(asked.arguments))
        }
        /*
         * The same turn openAiBody sends as text and image_url parts, in the
         * shape Anthropic reads: a source rather than a URL.
         *
         * This is the whole of issue #151. Without it a turn carrying a picture
         * reached the model as words alone - nothing failed, nothing was
         * logged, and an agent whose entire purpose was reading a screenshot
         * appeared to work.
         */
        turn.images.forEach { image ->
            val block = blocks.addObject().put("type", "image")
            block.set("source", anthropicImageSource(image))
        }
    }

    /**
     * A picture this provider cannot be sent, and the sentence saying why.
     *
     * Thrown rather than returned because it interrupts building a request that
     * must not go out: the alternative - carrying on and leaving the picture
     * behind - is precisely the behaviour this replaces.
     */
    private class UnusableImage(message: String) : IllegalArgumentException(message)

    /**
     * One picture, in the shape Anthropic's messages API takes.
     *
     * A turn's images are `data:` URLs, which is what the OpenAI shape puts
     * straight into `image_url.url`. Anthropic does not take a URL there: it
     * wants the media type and the bytes separately, so the URL is taken apart
     * here. An `http` or `https` address is passed through as a URL source,
     * which Anthropic does accept, so a caller that ever carries one is not
     * broken by this being written for the shape it carries today.
     *
     * Anything else refuses. The media types are Anthropic's own list, and a
     * picture in some other format would otherwise be answered with an opaque
     * 400 from the provider rather than a sentence naming what is wrong.
     */
    private fun anthropicImageSource(image: String): ObjectNode {
        val source = mapper.createObjectNode()

        if (image.startsWith("http://") || image.startsWith("https://")) {
            return source.put("type", "url").put("url", image)
        }

        if (!image.startsWith("data:")) {
            throw UnusableImage("it is neither a data: URL nor an http address")
        }

        val comma = image.indexOf(',')
        if (comma < 0) throw UnusableImage("the data: URL has no data in it")
        val declaration = image.substring("data:".length, comma)
        if (!declaration.endsWith(";base64")) {
            throw UnusableImage("only base64 data: URLs can be carried, and this one is not base64")
        }

        val mediaType = declaration.removeSuffix(";base64").ifEmpty {
            throw UnusableImage("the data: URL does not say what kind of picture it is")
        }
        if (mediaType !in ANTHROPIC_IMAGE_TYPES) {
            throw UnusableImage("$mediaType is not one of the types it accepts (${ANTHROPIC_IMAGE_TYPES.joinToString(", ")})")
        }

        val data = image.substring(comma + 1)
        if (data.isEmpty()) throw UnusableImage("the data: URL has no data in it")

        return source.put("type", "base64").put("media_type", mediaType).put("data", data)
    }

    /** Arguments arrive as a JSON string; this shape wants them as an object. */
    private fun argumentsOf(arguments: String): ObjectNode =
        runCatching { mapper.readTree(arguments) as? ObjectNode }.getOrNull() ?: mapper.createObjectNode()

    /**
     * Whether a tool call's arguments are there but not valid JSON, which is
     * what a call truncated at the output limit looks like. Issue #392.
     *
     * Blank is not broken: a tool that takes nothing is called with no
     * arguments, and reading that as a broken call would refuse every such
     * call. Only a non-blank string that will not parse is the fault.
     */
    private fun argumentsBroken(arguments: String): Boolean =
        arguments.isNotBlank() && runCatching { mapper.readTree(arguments) }.getOrNull() == null

    /**
     * Whether the provider said it stopped because it hit the output limit -
     * OpenAI's `finish_reason: length`, Anthropic's `stop_reason: max_tokens`.
     * Issue #392.
     */
    private fun wasCutOff(body: String, anthropic: Boolean): Boolean = runCatching {
        val tree = mapper.readTree(body)
        if (anthropic) {
            tree.path("stop_reason").stringValue() == "max_tokens"
        } else {
            tree.path("choices").firstOrNull()?.path("finish_reason")?.stringValue() == "length"
        }
    }.getOrDefault(false)

    /** What the model asked for, in the OpenAI shape. */
    private fun openAiCalls(body: String): List<ToolCall> = runCatching {
        val calls = mapper.readTree(body).path("choices").firstOrNull()
            ?.path("message")?.path("tool_calls") as? ArrayNode ?: return emptyList()
        calls.mapNotNull { call ->
            val name = call.path("function").path("name").stringValue() ?: return@mapNotNull null
            ToolCall(
                id = call.path("id").stringValue().orEmpty(),
                name = name,
                arguments = call.path("function").path("arguments").stringValue().orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** And in the Anthropic shape, where it is a block among the content. */
    private fun anthropicCalls(body: String): List<ToolCall> = runCatching {
        val blocks = mapper.readTree(body).path("content") as? ArrayNode ?: return emptyList()
        blocks.filter { it.path("type").stringValue() == "tool_use" }.mapNotNull { block ->
            val name = block.path("name").stringValue() ?: return@mapNotNull null
            ToolCall(
                id = block.path("id").stringValue().orEmpty(),
                name = name,
                arguments = mapper.writeValueAsString(block.path("input")),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * What a whole answer said and what it thought, once both are in hand.
     *
     * @param named the reasoning the provider put in a field of its own, which
     *   is authoritative where there is any: a provider that named it does not
     *   also wrap it in tags. Only where there is none does the leading
     *   `<think>` block get looked for, which is the shape a local server
     *   passing a chat template through produces. See [ThinkTags].
     */
    private fun split(content: String, named: String): ModelPiece {
        if (named.isNotBlank()) return ModelPiece(said = content, thought = named)
        val tags = ThinkTags()
        val piece = tags.feed(content)
        val rest = tags.finish()
        return ModelPiece(said = piece.said + rest.said, thought = piece.thought + rest.thought)
    }

    /**
     * The thinking the OpenAI shape names, under either of the two spellings
     * that exist in the wild. See [pieceOf] for which servers send which.
     */
    private fun openAiReasoning(body: String): String = runCatching {
        wordsOf(
            mapper.readTree(body).path("choices").firstOrNull()?.path("message"),
            "reasoning_content",
            "reasoning",
        )
    }.getOrDefault("")

    /**
     * And Anthropic's, which is a content block beside the text ones rather
     * than a field.
     *
     * Read even though nothing here asks for it. Extended thinking is only
     * emitted when the request carries a `thinking` budget, and this does not
     * send one - that is a per-model setting with a token cost attached and is
     * not something to turn on for everybody from here. So this is the half
     * that costs nothing and is correct the day somebody adds the other half,
     * rather than a second place that would then have to be found.
     */
    private fun anthropicReasoning(body: String): String = runCatching {
        val blocks = mapper.readTree(body).path("content") as? ArrayNode ?: return@runCatching ""
        blocks.filter { wordsOf(it, "type") == "thinking" }
            .joinToString("") { wordsOf(it, "thinking") }
    }.getOrDefault("")

    private fun openAiContent(body: String): String? = runCatching {
        mapper.readTree(body).path("choices").firstOrNull()
            ?.path("message")?.path("content")?.stringValue()
    }.getOrNull()

    /**
     * The text blocks, and only those.
     *
     * A thinking block has no `text` field, and reading one used to throw -
     * Jackson 3's `stringValue()` does that for a node that is not a string -
     * which the `runCatching` around this turned into "the provider answered
     * with no message". Nothing had ever noticed, because nothing here asks
     * Anthropic for extended thinking, so no answer had ever carried one. The
     * moment one did, a perfectly good answer would have been reported as an
     * empty one. Read leniently now, so the blocks this does not understand are
     * skipped rather than fatal - which is what should happen to a block type
     * Anthropic adds next year, too.
     */
    private fun anthropicContent(body: String): String? = runCatching {
        val blocks = mapper.readTree(body).path("content") as? ArrayNode ?: return null
        blocks.joinToString("") { wordsOf(it, "text") }.ifBlank { null }
    }.getOrNull()

    /**
     * Whether a status the provider answered with settles the question.
     *
     * The line is about what the status says. 4xx is the provider having read
     * the request and refused it, and a second identical request is refused
     * identically — an unknown model, a key without access, a body it would not
     * parse. The two exceptions are about the moment rather than the request:
     * 408, the provider saying it ran out of time, and 429, the provider saying
     * not now. 5xx is a provider that failed to answer at all, which says
     * nothing about the request and is the case a second attempt exists for.
     */
    /**
     * Whether what the provider complained about was the model's own tool call.
     * Issue #465.
     *
     * A server that cannot parse the arguments the model sent says so in its
     * body - llama.cpp answers a 500 reading "Failed to parse tool call
     * arguments as JSON" - and that is a reply the model can put right, not a
     * server that is unwell. Read off the words because there is nothing else
     * to read: the shape of an error body is each provider's own, and the
     * question here is only whether to let the model try again.
     */
    private fun aboutTheCall(body: String): Boolean {
        val said = body.lowercase()
        return said.contains("tool call") && (said.contains("parse") || said.contains("json"))
    }

    private fun settled(status: Int): Boolean = when (status) {
        HTTP_TIMEOUT, HTTP_TOO_MANY_REQUESTS -> false
        else -> status < SERVER_ERROR
    }

    /**
     * Waits a 429's Retry-After out and says whether to try the call again.
     * Issue #426.
     *
     * The heart of "retry-after wins": on a 429 the model accepts one for, this
     * waits exactly what the header says and asks again, below any node's own
     * retry policy - the node never sees the 429 if this recovers. The block is
     * recorded on the model as well, so every other run calling it holds off
     * too, not only this one. Bounded: a wait longer than [MAX_RETRY_AFTER] or a
     * few attempts in, and it gives up so the node's policy takes over later
     * rather than one call sitting on a worker for an unbounded time.
     */
    private fun waitedForRetryAfter(
        modelId: Long,
        limits: ModelThrottle.Limits?,
        wait: Duration?,
        attempt: Int,
    ): Boolean {
        if (limits?.acceptRetryAfter != true) return false
        if (attempt >= MAX_RETRY_AFTER_ATTEMPTS) return false
        val delay = wait ?: return false
        if (delay > MAX_RETRY_AFTER) return false
        throttle.blockFor(modelId, delay)
        try {
            Thread.sleep(delay.toMillis())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return true
    }

    private fun retryAfterOf(response: HttpResponse<*>): Duration? =
        retryAfter(response.headers().firstValue("retry-after").orElse(null))

    private fun retryAfterOf(refused: OpenAIServiceException): Duration? =
        retryAfter(refused.headers().values("retry-after").firstOrNull())

    /**
     * A Retry-After header as a duration: seconds is the common form, an
     * HTTP-date the other the spec allows. Null where it is absent or unreadable.
     */
    private fun retryAfter(raw: String?): Duration? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        return runCatching {
            val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            Duration.between(java.time.ZonedDateTime.now(), at).takeIf { !it.isNegative }
        }.getOrNull()
    }

    /**
     * What went wrong, in the provider's own words where it gave any. A refused
     * key and a bad request read very differently to whoever has to fix it.
     */
    /**
     * The address a refusal was refused at.
     *
     * The sentence that reaches the screen carries the status and whatever the
     * provider said about it, which is right for whoever is holding a chat
     * window. It is the wrong thing for whoever is holding the logs: an
     * endpoint, a deployment name and an API version are three fields somebody
     * typed, any of them can be wrong, and every way of being wrong arrives as
     * the same `404: Resource not found`. Printing where the request went is
     * what turns that into one field to look at.
     *
     * The URI and nothing else. The credential travels in a header and the body
     * carries the conversation, so neither is here to be leaked.
     */
    private fun refused(request: HttpRequest, status: Int) {
        log.warn("{} answered {}", request.uri(), status)
    }

    private fun reason(status: Int, body: String): String {
        val message = runCatching {
            val tree = mapper.readTree(body)
            tree.path("error").path("message").stringValue() ?: tree.path("message").stringValue()
        }.getOrNull()
        return if (message.isNullOrBlank()) "The provider answered with $status" else "$status: $message"
    }

    private companion object {
        val log = LoggerFactory.getLogger(ModelChatClient::class.java)

        const val CONNECT_SECONDS = 10L

        /** The two statuses that are about the moment rather than the request. */
        const val HTTP_TIMEOUT = 408
        const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * How patient the client is with a Retry-After before it hands back to
         * the node's own policy. Issue #426. A long wait belongs on a durable
         * timer, not on a worker held here; a couple of attempts covers a
         * provider clearing a brief burst without turning one call into an
         * unbounded hold.
         */
        val MAX_RETRY_AFTER: Duration = Duration.ofSeconds(120)
        const val MAX_RETRY_AFTER_ATTEMPTS = 2

        /** Where the provider stops objecting to the request and starts failing. */
        const val SERVER_ERROR = 500

        /** What Anthropic's messages API will look at. Its list, not a guess. */
        val ANTHROPIC_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        const val DEFAULT_MAX_TOKENS = 4096
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_AZURE_VERSION = "2024-06-01"

        /** Every event in both shapes arrives on a `data:` line. */
        const val DATA_PREFIX = "data:"

        /** What the OpenAI shape closes with; Anthropic simply stops. */
        const val DONE = "[DONE]"

        /**
         * What a call somebody gave up on says, in the same words the SDK path
         * says it in.
         *
         * Never settled, on the same rule every other torn socket follows: the
         * provider has said nothing about the request, and the reader walking
         * away is not an opinion about whether the question can be answered.
         */
        const val HUNG_UP = "Nobody was left to read the answer"
    }
}
