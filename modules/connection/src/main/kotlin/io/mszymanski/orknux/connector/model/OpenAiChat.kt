package io.mszymanski.orknux.connector.model

import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionContentPart
import com.openai.models.chat.completions.ChatCompletionContentPartImage
import com.openai.models.chat.completions.ChatCompletionContentPartText
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionFunctionTool
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.chat.completions.ChatCompletionUserMessageParam
import org.springframework.stereotype.Component
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * The OpenAI-shaped half of a chat, spoken through the official SDK.
 *
 * **Why this exists as its own object.** Every provider but Anthropic answers
 * this shape, and until now the request was assembled here as a Jackson tree and
 * the answer read back out of one. That is the code AGENTS.md now forbids, and
 * Azure is why: it serves two URL layouts from one resource and changes API
 * versions quarterly, so a request built by hand is right until the day it is
 * not, and the day it is not it returns `404 Resource not found` - which names
 * no field and blames the wrong one. The SDK carries the shapes, the layouts and
 * the versions, and is updated by the people who move them.
 *
 * **Why Anthropic is not here.** It is a different wire format with a different
 * SDK, and folding it in would mean this object choosing between two libraries
 * rather than speaking one. [ModelChatClient] keeps that fork and sends
 * everything else here.
 *
 * **Two APIs behind one door.** An Azure OpenAI provider is asked through the
 * Responses API unless it has been set back to chat completions, because chat
 * completions refuse a reasoning model its tools; [OpenAiResponses] carries
 * that shape and [OpenAiResponses.speaks] says which road a provider takes.
 * Both hand back the same [Outcome], so nothing above knows which was spoken.
 *
 * What this returns is the application's own [Outcome] rather than the SDK's
 * types: what a caller gets back does not change because of what is underneath,
 * which is the only reason this can replace the hand-built path without every
 * screen above it moving too.
 */
@Component
class OpenAiChat(
    private val clients: ModelClients,
    private val probe: ModelProviderProbe,
    private val mapper: tools.jackson.databind.ObjectMapper,
) {

    /**
     * One answer, waited for.
     *
     * @param hangup somebody who may give up on the call while it is still
     *   running, or null for the caller that cannot. There is no stream to
     *   close here, so what is torn down is the request: it is made through
     *   the SDK's asynchronous client and waited on, and cancelling that wait
     *   is what the SDK turns into cancelling the HTTP call underneath - a
     *   blocking `create` offers nothing to cancel, and a socket read does not
     *   wake on an interrupt. Issue #440.
     */
    fun complete(
        provider: ModelProvider,
        model: LlmModel,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>,
        hangup: Hangup? = null,
    ): Outcome {
        if (hangup?.hungUp == true) return Outcome.Failed(HUNG_UP)

        val client = when (val ready = ready(provider)) {
            is Ready.No -> return Outcome.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }
        if (OpenAiResponses.speaks(provider)) return responses.complete(client, provider, model, turns, tools, hangup)

        val params = params(provider, model, turns, tools).build()
        // Hung up on: the wait was cancelled and the request with it. Not an
        // answer, and said in the words every hung-up call uses.
        val answer = awaited(clients, hangup) { client.async().chat().completions().create(params) }
            ?: return Outcome.Failed(HUNG_UP)
        if (hangup?.hungUp == true) return Outcome.Failed(HUNG_UP)

        val said = answer.choices().firstOrNull()?.message()
        val calls = said?.toolCalls()?.orElse(null).orEmpty()
            .filter { it.isFunction() }
            .map { it.asFunction() }
            .map { ToolCall(it.id(), it.function().name(), it.function().arguments()) }

        /*
         * A tool call the model did not finish is handed on, as the streaming
         * path already hands it on. Issue #528.
         *
         * This used to fail the whole answer, permanently, over one cut-off
         * call - so a message holding three good calls and one truncated one
         * ended the turn, while the same message streamed went through. The
         * agent loop now deals with it in one place: the good calls run, the
         * cut one is answered as not run, and it goes back as `{}` so the next
         * request is one the provider will take. #392's point - say the cause
         * in words - is kept there, in what the model is told.
         */

        val usage = answer.usage().orElse(null)

        return Outcome.Answered(
            said = said?.content()?.orElse(null).orEmpty(),
            calls = calls,
            thought = reasoning(said?._additionalProperties()),
            inputTokens = usage?.promptTokens() ?: 0,
            outputTokens = usage?.completionTokens() ?: 0,
        )
    }

    /**
     * The same answer, delivered as it is written.
     *
     * [onChunk] takes each piece as it lands and the whole is accumulated too,
     * so a caller gets both without reassembling one from the other. The counts
     * arrive in a final frame carrying no choices, which is why
     * [ChatCompletionStreamOptions] is set: a stream sends no usage otherwise,
     * and the chat window - which always streams - recorded every answer it ever
     * showed as nought tokens.
     *
     * @param hangup somebody who may decide, part way through, that nobody is
     *   listening any more. Closing the [com.openai.core.http.StreamResponse] is
     *   how one of these is torn down and it is the only thing that does tear
     *   one down, so the closing is handed over rather than left to a caller
     *   that cannot reach it. Null for every caller with nobody to walk away -
     *   a workflow, a task loop - which is the ordinary case.
     */
    fun stream(
        provider: ModelProvider,
        model: LlmModel,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>,
        onThinking: (String) -> Unit,
        hangup: Hangup? = null,
        onChunk: (String) -> Unit,
    ): Outcome {
        // Given up on before it was made. A round of an agent's loop reaches
        // this after the reader has already gone, and asking the provider for an
        // answer nobody will read is the whole of what is being avoided.
        if (hangup?.hungUp == true) return Outcome.Failed(HUNG_UP)

        val client = when (val ready = ready(provider)) {
            is Ready.No -> return Outcome.Failed(ready.reason)
            is Ready.Yes -> ready.client
        }
        if (OpenAiResponses.speaks(provider)) {
            return responses.stream(client, provider, model, turns, tools, onThinking, hangup, onChunk)
        }

        val whole = StringBuilder()
        val thinking = StringBuilder()
        val gathered = sortedMapOf<Long, Gathering>()
        var input = 0L
        var output = 0L

        /*
         * One splitter for the whole stream, because a tag arrives in pieces.
         * See [ThinkTags]: a provider is free to send `<thi` and `nk>` in two
         * frames, and a splitter built per frame would put both on the screen.
         */
        val tags = ThinkTags()

        /*
         * When the thinking stopped, measured from the request going out rather
         * than from the first reasoning frame. A model that emits its whole
         * reasoning in one frame has its first and last frame at the same
         * instant, so the difference between them is nought and the screen
         * draws no time at all. What somebody waited through is the request,
         * the prompt loading and then the reasoning - all of it before there
         * was a word to read, which is the wait the block explains.
         */
        val started = System.nanoTime()
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

        val params = params(provider, model, turns, tools)
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
            .build()

        /*
         * Asked again only while nothing has been said. A connection closed
         * before the first frame is the stale-socket case and costs nothing to
         * repeat; one closed halfway through has already put words on somebody's
         * screen, and asking again would write them twice.
         */
        val stream = if (whole.isEmpty() && thinking.isEmpty()) {
            clients.again { client.chat().completions().createStreaming(params) }
        } else {
            client.chat().completions().createStreaming(params)
        }

        stream.use { response ->
            /*
             * The one thing that ends this call early, handed over the moment
             * there is one to hand over.
             *
             * `use` closes it when the loop below ends, and that is what happens
             * to a call that finishes; this is for the call that must not
             * finish. Closing it from the other thread makes the read underneath
             * throw, which comes back out of `forEach` as any other broken
             * stream would - so there is one way out of here rather than two.
             */
            hangup?.holding { response.close() }
            response.stream().forEach { chunk ->
                chunk.usage().orElse(null)?.let {
                    input = it.promptTokens()
                    output = it.completionTokens()
                }
                /*
                 * Read as optional, though the SDK calls it required. Azure
                 * OpenAI sends choices that carry only their content-filter
                 * results and no delta, and `delta()` on one throws "`delta` is
                 * not set" - which failed every streamed turn behind Azure. A
                 * choice with nothing said in it is skipped like an empty one.
                 */
                val delta = chunk.choices().firstOrNull()?._delta()?.asKnown()?.orElse(null) ?: return@forEach
                // Thinking the provider named is thinking: it does not go
                // through the tag splitter, which is only for the shape where
                // nobody named it. Not part of the OpenAI shape either way, so
                // it rides along as an extra property on the delta.
                reasoning(delta._additionalProperties()).takeIf { it.isNotEmpty() }?.let { piece ->
                    hand(ModelPiece(thought = piece))
                }
                delta.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let { piece ->
                    hand(tags.feed(piece))
                }
                // A call arrives in pieces too: the name once, the arguments a
                // fragment at a time, paired up by index rather than by id.
                delta.toolCalls().orElse(null).orEmpty().forEach { call ->
                    val into = gathered.getOrPut(call.index()) { Gathering() }
                    call.id().orElse(null)?.let { into.id = it }
                    call.function().orElse(null)?.let { function ->
                        function.name().orElse(null)?.let { into.name = it }
                        function.arguments().orElse(null)?.let { into.arguments.append(it) }
                    }
                }
            }
        }
        hangup?.letGo()

        // Hung up on part way through, so what was gathered is half an answer
        // to a question nobody is waiting on. Said as a failure rather than
        // handed back, or a caller would keep it.
        if (hangup?.hungUp == true) return Outcome.Failed(HUNG_UP)

        hand(tags.finish())

        /*
         * A stream that carried nothing at all, from a provider that answered
         * the request anyway.
         *
         * `stream: true` is a request, not a guarantee. A local server, or a
         * proxy in front of one, may answer a streaming call with the whole
         * completion in one ordinary body - and the SDK, reading for frames
         * that are not there, finds nothing and hands back an empty answer.
         * That reaches the run as "the provider answered with no message",
         * which is a silence this reader invented rather than one the model
         * produced.
         *
         * So it is asked once more, without streaming, and whatever that says
         * is the answer. Only where *nothing* arrived: a stream that carried a
         * word, a thought or a tool call was a stream, and asking again would
         * be asking a model to do its work twice.
         */
        if (whole.isEmpty() && thinking.isEmpty() && gathered.isEmpty()) {
            return complete(provider, model, turns, tools).also {
                if (it is Outcome.Answered && it.said.isNotEmpty()) onChunk(it.said)
            }
        }

        return Outcome.Answered(
            said = whole.toString(),
            calls = gathered.values.mapNotNull { it.asCall() },
            thought = thinking.toString(),
            thoughtMillis = if (!sawThought) 0L else (thoughtTo - started) / 1_000_000,
            inputTokens = input,
            outputTokens = output,
        )
    }

    /** A call being assembled from the fragments a stream sends it in. */
    private class Gathering {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun asCall(): ToolCall? {
            val id = id ?: return null
            val name = name ?: return null
            return ToolCall(id, name, arguments.toString())
        }
    }

    /** What a call produced, in this application's own words. */
    sealed interface Outcome {
        data class Answered(
            val said: String,
            val calls: List<ToolCall>,
            val thought: String,
            /** How long the thinking went on for; nought where there was none. */
            val thoughtMillis: Long = 0,
            val inputTokens: Long = 0,
            val outputTokens: Long = 0,
            /** What the Responses API asked to be handed back next round; see [ChatTurn.reasoningItems]. */
            val reasoningItems: List<String> = emptyList(),
        ) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    private sealed interface Ready {
        data class Yes(val client: com.openai.client.OpenAIClient) : Ready
        data class No(val reason: String) : Ready
    }

    /** The Responses path, for the providers set to it; see [OpenAiResponses.speaks]. */
    private val responses = OpenAiResponses(clients)

    private fun ready(provider: ModelProvider): Ready =
        when (val credential = probe.sdkCredential(provider)) {
            is ModelProviderProbe.SdkCredential.Failed -> Ready.No(credential.reason)
            is ModelProviderProbe.SdkCredential.Ready -> Ready.Yes(
                if (OpenAiResponses.speaks(provider)) {
                    clients.responsesClientFor(provider, credential.credential)
                } else {
                    clients.clientFor(provider, credential.credential)
                },
            )
        }

    private fun params(
        provider: ModelProvider,
        model: LlmModel,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>,
    ): ChatCompletionCreateParams.Builder {
        val builder = ChatCompletionCreateParams.builder().model(model.modelId)
        model.maxOutput?.let { builder.maxTokens(it.toLong()) }
        turns.forEach { turn -> add(builder, turn) }
        tools.forEach { tool -> builder.addTool(declared(tool)) }
        // Only beside tools: a provider refuses the field in a request that offers none. Issue #530.
        if (tools.isNotEmpty()) model.parallelToolCalls?.let { builder.parallelToolCalls(it) }
        /*
         * Each only when set - null leaves the server's own default, issue #533 -
         * and only where the provider takes it. A model moved from a llama.cpp
         * server to Azure may still hold a top-k; Azure refuses a request
         * carrying one, so a stored value the provider does not take is left
         * where it is rather than sent. See ChatParameters.
         */
        fun takes(parameter: String) = ChatParameters.takes(provider, parameter)
        model.reasoningEffort?.takeIf { takes(ChatParameters.REASONING_EFFORT) }?.let {
            builder.reasoningEffort(com.openai.models.ReasoningEffort.of(it))
        }
        model.temperature?.takeIf { takes(ChatParameters.TEMPERATURE) }?.let { builder.temperature(it) }
        model.topP?.takeIf { takes(ChatParameters.TOP_P) }?.let { builder.topP(it) }
        model.topK?.takeIf { takes(ChatParameters.TOP_K) }?.let {
            builder.putAdditionalBodyProperty("top_k", com.openai.core.JsonValue.from(it))
        }
        model.minP?.takeIf { takes(ChatParameters.MIN_P) }?.let {
            builder.putAdditionalBodyProperty("min_p", com.openai.core.JsonValue.from(it))
        }
        model.repeatPenalty?.takeIf { takes(ChatParameters.REPEAT_PENALTY) }?.let {
            builder.putAdditionalBodyProperty("repeat_penalty", com.openai.core.JsonValue.from(it))
        }
        return builder
    }

    private fun add(builder: ChatCompletionCreateParams.Builder, turn: ChatTurn) {
        when {
            // The answer to a call is its own role and has to name the call it
            // answers, or the model cannot pair them up.
            turn.respondingTo != null -> builder.addMessage(
                ChatCompletionToolMessageParam.builder()
                    .toolCallId(turn.respondingTo)
                    .content(turn.content)
                    .build(),
            )

            turn.asked.isNotEmpty() -> {
                val assistant = ChatCompletionAssistantMessageParam.builder()
                // A turn that only asked may carry no text at all.
                if (turn.content.isNotEmpty()) assistant.content(turn.content)
                // Its thinking, for a template that shows it in front of the calls. Issue #532.
                turn.reasoning?.takeIf { it.isNotBlank() }?.let {
                    assistant.putAdditionalProperty("reasoning_content", com.openai.core.JsonValue.from(it))
                }
                turn.asked.forEach { asked ->
                    assistant.addToolCall(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id(asked.id)
                            .function(
                                ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(asked.name)
                                    .arguments(asked.arguments)
                                    .build(),
                            )
                            .build(),
                    )
                }
                builder.addMessage(assistant.build())
            }

            // A turn with pictures is a list of parts rather than a string. A
            // model that cannot see ignores the image part rather than failing,
            // which is why this does not need to know whether the model can.
            turn.images.isNotEmpty() -> builder.addMessage(
                ChatCompletionUserMessageParam.builder()
                    .contentOfArrayOfContentParts(parts(turn))
                    .build(),
            )

            turn.role == "system" -> builder.addSystemMessage(turn.content)
            turn.role == "assistant" -> builder.addAssistantMessage(turn.content)
            else -> builder.addUserMessage(turn.content)
        }
    }

    private fun parts(turn: ChatTurn): List<ChatCompletionContentPart> = buildList {
        if (turn.content.isNotEmpty()) {
            add(
                ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(turn.content).build(),
                ),
            )
        }
        turn.images.forEach { image ->
            add(
                ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(image).build())
                        .build(),
                ),
            )
        }
    }

    private fun declared(tool: ToolSpec): ChatCompletionFunctionTool {
        val properties = tool.parameters.associate { parameter ->
            parameter.name to mapOf("type" to "string", "description" to parameter.description)
        }
        val schema = FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty(
                "required",
                JsonValue.from(tool.parameters.filter { it.required }.map { it.name }),
            )
            .build()

        return ChatCompletionFunctionTool.builder()
            .function(
                FunctionDefinition.builder()
                    .name(tool.name)
                    .description(tool.description)
                    .parameters(schema)
                    .build(),
            )
            .build()
    }

    /**
     * What a model said about its own thinking, where it says anything.
     *
     * Not part of the OpenAI shape, so it arrives as an extra property under one
     * of several names - each vendor picked its own - and is read off the
     * message rather than parsed out of the text. Absent is the ordinary case.
     */
    private fun reasoning(properties: Map<String, JsonValue>?): String {
        if (properties == null) return ""
        return REASONING_FIELDS
            .firstNotNullOfOrNull { name -> properties[name]?.asString()?.orElse(null)?.takeIf { it.isNotBlank() } }
            .orEmpty()
    }

    internal companion object {
        /** Three spellings, because three vendors chose three. */
        private val REASONING_FIELDS = listOf("reasoning", "reasoning_content", "thinking")

        /**
         * What a call that was given up on says.
         *
         * Nobody reads it - the reader walking away is what produced it - and it
         * is written for the log, where a torn stream would otherwise look like
         * a provider that fell over.
         */
        const val HUNG_UP = "Nobody was left to read the answer"
    }
}

/**
 * A request made through the SDK's asynchronous client, waited for, and torn
 * down if [hangup] gives up on it. Null when it was given up on.
 *
 * There is no stream to close on a waited-for call, so what is torn down is the
 * request: cancelling the future is what the SDK turns into cancelling the HTTP
 * call underneath - a blocking `create` offers nothing to cancel, and a socket
 * read does not wake on an interrupt. Issue #440. Shared by both APIs, so a
 * hangup means the same thing on either.
 */
internal fun <T> awaited(clients: ModelClients, hangup: Hangup?, ask: () -> java.util.concurrent.CompletableFuture<T>): T? =
    try {
        clients.again {
            val asked = ask()
            hangup?.holding { asked.cancel(true) }
            try {
                asked.get()
            } catch (failed: ExecutionException) {
                // What the call threw, not the future's wrapper around it:
                // `again` reads the SDK's own exception to decide whether a
                // closed connection is worth one more go.
                throw failed.cause ?: failed
            } finally {
                hangup?.letGo()
            }
        }
    } catch (cancelled: CancellationException) {
        null
    }
