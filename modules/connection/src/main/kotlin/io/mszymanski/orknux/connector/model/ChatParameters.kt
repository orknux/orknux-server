package io.mszymanski.orknux.connector.model

/** How a chat setting is asked for: a number in a box, or one word off a list. */
enum class ChatParameterKind {
    /** A number; its range is checked where it is saved. */
    NUMBER,

    /** One of [ChatParameterSpec.choices], as the endpoint spells it. */
    CHOICE,
}

/**
 * One setting a chat model takes that not every provider does.
 *
 * [name] is the model's field as the API spells it (`topK`, `reasoningEffort`),
 * so the form draws a control per entry and saves it under that name.
 */
data class ChatParameterSpec(
    val name: String,
    val kind: ChatParameterKind,
    /** What a [ChatParameterKind.CHOICE] may be; empty for a number. Unset is always allowed and sends nothing. */
    val choices: List<String> = emptyList(),
)

/**
 * Which sampling and reasoning settings a chat model's provider takes.
 *
 * The chat counterpart of the image models' capabilities. The model page draws
 * only what its provider declares here, a save refuses a value the provider
 * does not take, and a request sends only what is declared - so a model moved
 * from a llama.cpp server to Azure keeps its top-k in the database and never
 * sends it, where Azure would refuse the request over it.
 *
 * What each entry rests on - the request each client actually builds, and the
 * API that request goes to:
 *
 * - **AZURE_OPENAI** - [OpenAiChat] through openai-java, to Azure's Responses
 *   API by default or its chat completions where the provider says so (see
 *   [ChatApi]): `temperature`, `top_p`, and a reasoning effort (o-series and
 *   GPT-5 deployments) - `reasoning.effort` on one, `reasoning_effort` on the
 *   other. Azure has no `top_k`, `min_p` or `repeat_penalty`.
 * - **OPENAI on OpenAI's own host** - the same SDK call to api.openai.com:
 *   `temperature` and `top_p`, and none of the three llama.cpp additions.
 * - **OPENAI anywhere else** - a llama.cpp server, a gateway, vLLM behind the
 *   OpenAI shape, which is where [ProviderType] says such a server belongs.
 *   llama.cpp's `/v1/chat/completions` reads `top_k`, `min_p` and
 *   `repeat_penalty` beside the OpenAI fields, and those are what
 *   [OpenAiChat] puts in the body. (vLLM reads `top_k` and `min_p` too, but
 *   calls its penalty `repetition_penalty`.)
 * - **OLLAMA** - the same SDK call to Ollama's OpenAI-compatible `/v1`, whose
 *   `ChatCompletionRequest` (ollama/openai/openai.go) carries `temperature`
 *   and `top_p` and no `top_k`, `min_p` or `repeat_penalty`: sent there, they
 *   were silently dropped. Those three are Ollama's native `/api/chat`
 *   options, which this product does not call.
 * - **ANTHROPIC** - the Messages body built in [ModelChatClient]:
 *   `temperature` (held to 1.0 at most), `top_p` and `top_k`; it has no
 *   min-p and no repeat penalty.
 * - **SYSTEM_ONE** - answers questions rather than chatting; nothing.
 *
 * A provider that comes to take a setting is one entry here; a new setting is
 * that entry, a column on [LlmModel], and the line in each client that sends it.
 */
object ChatParameters {

    const val TEMPERATURE = "temperature"
    const val TOP_P = "topP"
    const val TOP_K = "topK"
    const val MIN_P = "minP"
    const val REPEAT_PENALTY = "repeatPenalty"
    const val REASONING_EFFORT = "reasoningEffort"

    private val temperature = ChatParameterSpec(TEMPERATURE, ChatParameterKind.NUMBER)
    private val topP = ChatParameterSpec(TOP_P, ChatParameterKind.NUMBER)
    private val topK = ChatParameterSpec(TOP_K, ChatParameterKind.NUMBER)
    private val minP = ChatParameterSpec(MIN_P, ChatParameterKind.NUMBER)
    private val repeatPenalty = ChatParameterSpec(REPEAT_PENALTY, ChatParameterKind.NUMBER)

    /**
     * How hard a reasoning model thinks before it answers. The SDK knows more
     * words than these (`none`, `xhigh`), but these are the four every Azure
     * reasoning deployment takes.
     */
    val REASONING_EFFORT_SPEC = ChatParameterSpec(
        REASONING_EFFORT,
        ChatParameterKind.CHOICE,
        listOf("minimal", "low", "medium", "high"),
    )

    private val AZURE = listOf(temperature, topP, REASONING_EFFORT_SPEC)
    private val OPENAI_HOSTED = listOf(temperature, topP)
    private val OPENAI_SHAPED = listOf(temperature, topP, topK, minP, repeatPenalty)
    private val OLLAMA = listOf(temperature, topP)
    private val ANTHROPIC = listOf(temperature, topP, topK)

    /**
     * What a chat model on this provider takes beyond the settings every
     * provider shares.
     *
     * @param endpoint where the provider is reached: an OPENAI provider is
     *   OpenAI itself only on OpenAI's host (or an Azure one registered under
     *   the plain type); anywhere else it is a server imitating the shape.
     */
    fun forProvider(type: ProviderType, endpoint: String?): List<ChatParameterSpec> = when (type) {
        ProviderType.AZURE_OPENAI -> AZURE
        ProviderType.OPENAI -> if (hostedByOpenAi(endpoint)) OPENAI_HOSTED else OPENAI_SHAPED
        ProviderType.OLLAMA -> OLLAMA
        ProviderType.ANTHROPIC -> ANTHROPIC
        ProviderType.SYSTEM_ONE -> emptyList()
    }

    fun forProvider(provider: ModelProvider): List<ChatParameterSpec> = forProvider(provider.type, provider.endpoint)

    /** Only a chat model takes any: these shape a chat request, and nothing else sends one. */
    fun forModel(provider: ModelProvider, kind: ModelKind): List<ChatParameterSpec> =
        if (kind == ModelKind.CHAT) forProvider(provider) else emptyList()

    /** Whether a request to this provider may carry the setting; what a client asks before sending one. */
    fun takes(provider: ModelProvider, parameter: String): Boolean = forProvider(provider).any { it.name == parameter }

    /** The word as it is stored: trimmed, null where blank, refused where not taken or not on the list. */
    fun held(provider: ModelProvider, kind: ModelKind, parameter: String, given: String?): String? {
        val value = given?.trim()?.ifEmpty { null } ?: return null
        val spec = taken(provider, kind, parameter)
        if (value !in spec.choices) throw ModelParameterValueInvalidException(parameter, value, spec.choices)
        return value
    }

    /** A number as it is stored: null stays null, and a value is refused where the provider does not take it. */
    fun <T : Number> held(provider: ModelProvider, kind: ModelKind, parameter: String, given: T?): T? {
        if (given == null) return null
        taken(provider, kind, parameter)
        return given
    }

    private fun taken(provider: ModelProvider, kind: ModelKind, parameter: String): ChatParameterSpec =
        forModel(provider, kind).firstOrNull { it.name == parameter }
            ?: throw ModelParameterNotTakenException(parameter, provider.type)

    /** OpenAI's own API host, or an Azure one; an endpoint that cannot be read counts as OpenAI's. */
    private fun hostedByOpenAi(endpoint: String?): Boolean {
        val host = endpoint?.let { runCatching { java.net.URI(it.trim()).host }.getOrNull() }?.lowercase() ?: return true
        return host.endsWith("openai.com") || host.contains("azure")
    }
}

/** A provider-specific setting given to a model whose provider does not take it. */
class ModelParameterNotTakenException(parameter: String, type: ProviderType) :
    RuntimeException("This $type provider's chat models do not take $parameter; leave it unset")

/** A provider-specific setting given a word its provider does not take for it. */
class ModelParameterValueInvalidException(parameter: String, value: String, choices: List<String>) :
    RuntimeException("\"$value\" is not a $parameter this model takes. Choose one of ${choices.joinToString(", ")}, or leave it unset")
