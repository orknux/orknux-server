package io.mszymanski.orknux.connector.model

import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseError
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.ResponseOutputItem
import com.openai.models.responses.ResponseReasoningItem

/**
 * The same chat as [OpenAiChat], asked through the Responses API.
 *
 * **Why there are two.** Azure's chat completions refuse a reasoning model the
 * combination an agent needs most: `Function tools with reasoning_effort are
 * not supported for gpt-6-sol in /v1/chat/completions. To use function tools,
 * use /v1/responses or set reasoning_effort to none`. Dropping the effort is
 * dropping the reason the model was chosen, so the request goes where the
 * vendor says it will be taken.
 *
 * **When it is used** is the provider's choice, not the model's: an
 * [ProviderType.AZURE_OPENAI] provider holds a [ChatApi], Responses by default,
 * and every chat through it is asked the way it says - see [speaks]. Chat
 * completions stays on the list so an installation can go back to the old road
 * the day the new one misbehaves, without waiting for a release. Every other
 * provider type keeps chat completions, which is what the servers imitating
 * the OpenAI shape actually serve.
 *
 * **Stateless.** Nothing is stored at the provider (`store: false`) and no
 * earlier response is referred to by id: the whole conversation goes out on
 * every round, as it does on chat completions, so a round can be retried, a
 * conversation compacted, or a provider switched without a server somewhere
 * holding a thread this application does not know about.
 *
 * **How a tool loop survives that.** A reasoning model's thinking before a call
 * is an item of the response, and the next round reasons better - and, on some
 * models, is only accepted - with that item in front of its call. Stateless,
 * the item cannot be referred to, so it is asked for encrypted
 * (`include: reasoning.encrypted_content`), carried on the assistant turn as
 * [ChatTurn.reasoningItems], and handed back verbatim ahead of the calls it
 * led to. The calls themselves go back by `call_id` alone, without the item id
 * the provider gave them: an item id is what ties a call to its reasoning item,
 * and a call sent with one and without the other is refused - so a turn whose
 * reasoning was not kept still makes a request the provider takes.
 *
 * **Reasoning is asked for only where the model has an effort.** The effort,
 * the summary that becomes thinking on the screen, and the encrypted items are
 * all refused by a model that does not reason, and an Azure provider on
 * Responses serves those too. A model with a [LlmModel.reasoningEffort] set is
 * one somebody has declared a reasoning model; one without is left to the
 * deployment's defaults, and a reasoning model left there still works - it just
 * shows no thinking and carries none between rounds.
 *
 * **Azure's address.** The SDK reaches Responses from Azure's v1 surface,
 * `…/openai/v1/responses`, with the model named in the body. Given a bare
 * resource host it would build the older deployment path instead,
 * `/openai/deployments/{name}/responses?api-version=…`, which Azure does not
 * serve. So [ModelClients.responsesClientFor] hands it the v1 base of the same
 * resource, and the SDK does the rest.
 */
internal class OpenAiResponses(private val clients: ModelClients) {

    /** One answer, waited for; see [OpenAiChat.complete] for [hangup]. */
    fun complete(
        client: OpenAIClient,
        provider: ModelProvider,
        model: LlmModel,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>,
        hangup: Hangup?,
    ): OpenAiChat.Outcome {
        val params = params(provider, model, turns, tools)
        val answer = awaited(clients, hangup) { client.async().responses().create(params) }
            ?: return OpenAiChat.Outcome.Failed(OpenAiChat.HUNG_UP)
        if (hangup?.hungUp == true) return OpenAiChat.Outcome.Failed(OpenAiChat.HUNG_UP)
        return outcome(answer)
    }

    /** The same answer as it is written; see [OpenAiChat.stream]. */
    fun stream(
        client: OpenAIClient,
        provider: ModelProvider,
        model: LlmModel,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>,
        onThinking: (String) -> Unit,
        hangup: Hangup?,
        onChunk: (String) -> Unit,
    ): OpenAiChat.Outcome {
        val whole = StringBuilder()
        val thinking = StringBuilder()
        // By the item id the stream names them with, in the order they began.
        val gathered = linkedMapOf<String, Gathering>()
        var finished: Response? = null
        var failed: OpenAiChat.Outcome.Failed? = null
        var heard = false

        val started = System.nanoTime()
        var thoughtTo = 0L

        fun thought(piece: String) {
            if (piece.isEmpty()) return
            thoughtTo = System.nanoTime()
            thinking.append(piece)
            onThinking(piece)
        }

        fun said(piece: String) {
            if (piece.isEmpty()) return
            whole.append(piece)
            onChunk(piece)
        }

        val params = params(provider, model, turns, tools)
        // Asked again only before a word was said; see OpenAiChat.stream.
        val stream = clients.again { client.responses().createStreaming(params) }

        stream.use { response ->
            hangup?.holding { response.close() }
            response.stream().forEach { event ->
                heard = true
                when {
                    event.isOutputTextDelta() -> said(event.asOutputTextDelta().delta())
                    event.isRefusalDelta() -> said(event.asRefusalDelta().delta())
                    event.isReasoningSummaryTextDelta() -> thought(event.asReasoningSummaryTextDelta().delta())
                    // Raw reasoning, where a model shares it rather than a summary of it.
                    event.isReasoningTextDelta() -> thought(event.asReasoningTextDelta().delta())
                    // A second summary part is a new paragraph of the same thinking.
                    event.isReasoningSummaryPartAdded() ->
                        if (event.asReasoningSummaryPartAdded().summaryIndex() > 0 && thinking.isNotEmpty()) thought("\n\n")

                    event.isOutputItemAdded() -> event.asOutputItemAdded().item().functionCallOrNull()?.let { call ->
                        gathered[call.id().orElse(call.callId())] = Gathering(call.callId(), call.name())
                    }
                    event.isFunctionCallArgumentsDelta() -> event.asFunctionCallArgumentsDelta().let { delta ->
                        gathered[delta.itemId()]?.arguments?.append(delta.delta())
                    }
                    // The finished call is the word on it, whatever the deltas made.
                    event.isOutputItemDone() -> event.asOutputItemDone().item().functionCallOrNull()?.let { call ->
                        gathered[call.id().orElse(call.callId())] = Gathering(call.callId(), call.name()).apply {
                            arguments.append(call.arguments())
                        }
                    }

                    event.isCompleted() -> finished = event.asCompleted().response()
                    // Cut short - by the output limit, usually. What arrived is handed on, as on chat completions.
                    event.isIncomplete() -> finished = event.asIncomplete().response()
                    event.isFailed() -> failed = event.asFailed().response().error().orElse(null)
                        ?.let { failure(it) }
                        ?: OpenAiChat.Outcome.Failed("The provider could not finish the answer", permanent = false)
                    event.isError() -> failed = event.asError().let { failure(it.code().orElse(null), it.message()) }
                }
            }
        }
        hangup?.letGo()

        if (hangup?.hungUp == true) return OpenAiChat.Outcome.Failed(OpenAiChat.HUNG_UP)
        failed?.let { return it }

        /*
         * Not one event, from a provider that answered anyway: `stream: true`
         * answered with an ordinary body. Asked once more without streaming,
         * as on chat completions - but only where nothing at all was heard. A
         * stream that ran to its end and said nothing is a model that said
         * nothing, and asking again would ask it to do the work twice.
         */
        if (!heard) {
            return complete(client, provider, model, turns, tools, hangup).also {
                if (it is OpenAiChat.Outcome.Answered && it.said.isNotEmpty()) onChunk(it.said)
            }
        }

        val usage = finished?.usage()?.orElse(null)
        return OpenAiChat.Outcome.Answered(
            said = whole.toString(),
            calls = gathered.values.map { ToolCall(it.callId, it.name, it.arguments.toString()) },
            thought = thinking.toString(),
            thoughtMillis = if (thinking.isEmpty()) 0L else (thoughtTo - started) / 1_000_000,
            inputTokens = usage?.inputTokens() ?: 0,
            outputTokens = usage?.outputTokens() ?: 0,
            reasoningItems = finished?.let { carried(it.output()) }.orEmpty(),
        )
    }

    /** A call being assembled from a stream: its `call_id`, its name, and its arguments as they arrive. */
    private class Gathering(val callId: String, val name: String) {
        val arguments = StringBuilder()
    }

    /**
     * A failure the provider reported inside an answer it had begun, and whether
     * asking again could come out differently. Issue #583: these arrive after a
     * 200, so there is no status code for [ModelChatClient] to settle them by,
     * and they were all taken as final - an Azure server error mid-stream was
     * never retried by a workflow step's policy, however many attempts it had.
     * The code is the provider's own word on what went wrong; an absent one says
     * nothing against trying again, which is how an unrecognised 5xx is read.
     */
    private fun failure(error: ResponseError): OpenAiChat.Outcome.Failed =
        failure(error.code().asString(), error.message())

    private fun failure(code: String?, message: String): OpenAiChat.Outcome.Failed =
        OpenAiChat.Outcome.Failed(message, permanent = code != null && code !in PASSING)

    private fun outcome(answer: Response): OpenAiChat.Outcome {
        answer.error().orElse(null)?.let { return failure(it) }

        val output = answer.output()
        val said = output.filter { it.isMessage() }
            .flatMap { it.asMessage().content() }
            .joinToString("") { part ->
                when {
                    part.isOutputText() -> part.asOutputText().text()
                    part.isRefusal() -> part.asRefusal().refusal()
                    else -> ""
                }
            }
        val calls = output.mapNotNull { it.functionCallOrNull() }
            .map { ToolCall(it.callId(), it.name(), it.arguments()) }
        val thought = output.filter { it.isReasoning() }
            .map { thoughtOf(it.asReasoning()) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val usage = answer.usage().orElse(null)
        return OpenAiChat.Outcome.Answered(
            said = said,
            calls = calls,
            thought = thought,
            inputTokens = usage?.inputTokens() ?: 0,
            outputTokens = usage?.outputTokens() ?: 0,
            reasoningItems = carried(output),
        )
    }

    /** The summary a model wrote of its thinking, or the thinking itself where it shares that instead. */
    private fun thoughtOf(item: ResponseReasoningItem): String {
        val summary = item.summary().joinToString("\n\n") { it.text() }
        if (summary.isNotBlank()) return summary
        return item.content().orElse(null).orEmpty().joinToString("\n\n") { it.text() }
    }

    /**
     * The reasoning items worth handing back next round: those that came
     * encrypted. One without its encrypted content was stored nowhere (`store`
     * is off), so sending it back would name an item the provider cannot find.
     */
    private fun carried(output: List<ResponseOutputItem>): List<String> = output
        .filter { it.isReasoning() }
        .map { it.asReasoning() }
        .filter { it.encryptedContent().isPresent }
        .map { jsonMapper().writeValueAsString(it) }

    private fun params(
        provider: ModelProvider,
        model: LlmModel,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>,
    ): ResponseCreateParams {
        val builder = ResponseCreateParams.builder()
            .model(model.modelId)
            .inputOfResponse(turns.flatMap { items(it) })
            .store(false)
        model.maxOutput?.let { builder.maxOutputTokens(it.toLong()) }
        tools.forEach { builder.addTool(declared(it)) }
        // Only beside tools, as on chat completions. Issue #530.
        if (tools.isNotEmpty()) model.parallelToolCalls?.let { builder.parallelToolCalls(it) }

        fun takes(parameter: String) = ChatParameters.takes(provider, parameter)
        model.reasoningEffort?.takeIf { takes(ChatParameters.REASONING_EFFORT) }?.let { effort ->
            builder.reasoning(
                Reasoning.builder()
                    .effort(ReasoningEffort.of(effort))
                    .summary(Reasoning.Summary.AUTO)
                    .build(),
            )
            builder.addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
        }
        // Each only when set: a reasoning model refuses both. Issue #533.
        model.temperature?.takeIf { takes(ChatParameters.TEMPERATURE) }?.let { builder.temperature(it) }
        model.topP?.takeIf { takes(ChatParameters.TOP_P) }?.let { builder.topP(it) }
        return builder.build()
    }

    /** One turn of the conversation as the input items the Responses API reads. */
    private fun items(turn: ChatTurn): List<ResponseInputItem> = when {
        // The answer to a call names the call it answers, by the call's own id.
        turn.respondingTo != null -> listOf(
            ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(turn.respondingTo)
                    .output(turn.content)
                    .build(),
            ),
        )

        turn.asked.isNotEmpty() -> buildList {
            // The thinking that led to the calls, in front of them, as it was produced.
            turn.reasoningItems.forEach { carried ->
                add(ResponseInputItem.ofReasoning(jsonMapper().readValue(carried, ResponseReasoningItem::class.java)))
            }
            if (turn.content.isNotEmpty()) add(message(EasyInputMessage.Role.ASSISTANT, turn.content))
            turn.asked.forEach { asked ->
                add(
                    ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                            .callId(asked.id)
                            .name(asked.name)
                            .arguments(asked.arguments)
                            .build(),
                    ),
                )
            }
        }

        turn.images.isNotEmpty() -> listOf(
            ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.USER)
                    .contentOfResponseInputMessageContentList(parts(turn))
                    .build(),
            ),
        )

        turn.role == "system" -> listOf(message(EasyInputMessage.Role.SYSTEM, turn.content))
        turn.role == "assistant" -> listOf(message(EasyInputMessage.Role.ASSISTANT, turn.content))
        else -> listOf(message(EasyInputMessage.Role.USER, turn.content))
    }

    private fun message(role: EasyInputMessage.Role, text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(text).build())

    private fun parts(turn: ChatTurn): List<ResponseInputContent> = buildList {
        if (turn.content.isNotEmpty()) {
            add(ResponseInputContent.ofInputText(ResponseInputText.builder().text(turn.content).build()))
        }
        turn.images.forEach { image ->
            add(
                ResponseInputContent.ofInputImage(
                    ResponseInputImage.builder().detail(ResponseInputImage.Detail.AUTO).imageUrl(image).build(),
                ),
            )
        }
    }

    /**
     * A tool as the Responses API declares one.
     *
     * `strict` is said out loud, as false. Chat completions leave it off and
     * mean false; Responses leave it off and mean true, and a strict schema
     * must list every property as required - which an optional parameter is
     * not, so the whole request would be refused over one of them.
     */
    private fun declared(tool: ToolSpec): FunctionTool {
        val properties = tool.parameters.associate { parameter ->
            parameter.name to mapOf("type" to "string", "description" to parameter.description)
        }
        val schema = FunctionTool.Parameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty("required", JsonValue.from(tool.parameters.filter { it.required }.map { it.name }))
            .build()
        return FunctionTool.builder()
            .name(tool.name)
            .description(tool.description)
            .parameters(schema)
            .strict(false)
            .build()
    }

    private fun ResponseOutputItem.functionCallOrNull(): ResponseFunctionToolCall? =
        if (isFunctionCall()) asFunctionCall() else null

    companion object {
        /**
         * Whether a chat through this provider is asked through the Responses
         * API: an Azure OpenAI provider not set back to chat completions. Null
         * on an Azure provider is the default, which is Responses; every other
         * type holds none and speaks chat completions.
         */
        fun speaks(provider: ModelProvider): Boolean =
            provider.type == ProviderType.AZURE_OPENAI && provider.chatApi != ChatApi.CHAT_COMPLETIONS

        /**
         * The error codes that pass: the provider fell over or was busy, and the
         * same request may well be answered next time. Everything else it names -
         * an invalid prompt, an image it could not read, a policy - is about the
         * request and will be said again. Issue #583.
         */
        private val PASSING = setOf(
            ResponseError.Code.SERVER_ERROR.asString(),
            ResponseError.Code.RATE_LIMIT_EXCEEDED.asString(),
            ResponseError.Code.VECTOR_STORE_TIMEOUT.asString(),
        )
    }
}
