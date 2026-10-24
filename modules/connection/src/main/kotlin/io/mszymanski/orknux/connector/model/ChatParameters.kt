package io.mszymanski.orknux.connector.model

/** How a chat setting is asked for: a number in a box, one word off a list, or a switch. */
enum class ChatParameterKind {
    /** A number; its range is checked where it is saved. */
    NUMBER,

    /** One of [ChatParameterSpec.choices], as the endpoint spells it. */
    CHOICE,

    /** On or off; off is always allowed, on only where the provider declares it. */
    SWITCH,
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
 *   `temperature`, `top_p` and `reasoning_effort`
 *   (`ChatCompletionCreateParams.reasoningEffort`), and none of the three
 *   llama.cpp additions. A model that refuses an effort beside function tools
 *   on chat completions - see [OpenAiResponses] - says so in its error; the
 *   field is sent only where somebody set it.
 * - **OPENAI anywhere else** - a llama.cpp server, a gateway, vLLM behind the
 *   OpenAI shape, which is where [ProviderType] says such a server belongs.
 *   llama.cpp's `/v1/chat/completions` reads `top_k`, `min_p` and
 *   `repeat_penalty` beside the OpenAI fields, and those are what
 *   [OpenAiChat] puts in the body. (vLLM reads `top_k` and `min_p` too, but
 *   calls its penalty `repetition_penalty`.) It reads `reasoning_effort` as
 *   well, and so do vLLM's and most gateways' chat completions.
 * - **LLAMA_CPP** - the same: llama.cpp's server reads the OpenAI
 *   `reasoning_effort` off the request body and hands it to the model's chat
 *   template as the `reasoning_effort` variable, `none` turning thinking off
 *   (tools/server/README.md, "`reasoning_effort`: If `none`,
 *   reasoning/thinking is disabled. Otherwise, the value is made available to
 *   the jinja template"; tools/server/server-common.cpp,
 *   `oaicompat_chat_params_parse`). The SDK field is therefore the whole of it:
 *   no `chat_template_kwargs` is written by hand.
 * - **OLLAMA** - the same SDK call to Ollama's OpenAI-compatible `/v1`, whose
 *   `ChatCompletionRequest` (ollama/openai/openai.go) carries `temperature`
 *   and `top_p` and no `top_k`, `min_p` or `repeat_penalty`: sent there, they
 *   were silently dropped. Those three are Ollama's native `/api/chat`
 *   options, which this product does not call. It does carry
 *   `reasoning_effort`, mapped onto Ollama's `think` (docs/api/
 *   openai-compatibility.mdx: "`reasoning_effort` and `reasoning.effort`
 *   control model thinking", `none` asking for no thinking).
 * - **ANTHROPIC** - the Messages body built in [ModelChatClient]:
 *   `temperature` (held to 1.0 at most), `top_p` and `top_k`; it has no
 *   min-p and no repeat penalty.
 * - **SYSTEM_ONE** - answers questions rather than chatting; nothing.
 *
 * **Takes video** and **Takes audio** are the switches here, and they are
 * about what a turn may carry rather than how the model samples. A video goes as a `video_url` content part
 * (see [OpenAiChat]), which vLLM reads (docs.vllm.ai, "Multimodal Inputs":
 * `{"type": "video_url", "video_url": {"url": ...}}`, a `data:video/mp4;base64`
 * URL decoded by `VideoMediaIO.load_base64`) and llama.cpp's server reads as an
 * alias of its `input_video` (tools/server/server-common.cpp,
 * `type == "input_video" || type == "video_url"`, any `data:video/...;base64`
 * URL accepted). So it is declared where those servers live - **LLAMA_CPP** and
 * **OPENAI anywhere but OpenAI's own host**, which is also where Gemini's
 * OpenAI-compatible endpoint is registered. OpenAI's own chat completions,
 * Azure, Ollama's `/v1` and Anthropic have no video part at all, so a model
 * there never holds the switch on and is never sent one.
 *
 * Audio goes as OpenAI's own `input_audio` part, which openai-java types (see
 * [AudioInput] for the formats each server decodes and the sources). OpenAI's
 * chat completions read it for its audio models, llama.cpp's server for a
 * model loaded with an audio projector, and vLLM for its audio models - so it
 * is declared for **OPENAI** on any host and **LLAMA_CPP**. Azure is reached
 * through its Responses API by default, which has no audio part; Ollama's `/v1`
 * and Anthropic have none either.
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
    const val TAKES_VIDEO = "takesVideo"
    const val TAKES_AUDIO = "takesAudio"

    private val temperature = ChatParameterSpec(TEMPERATURE, ChatParameterKind.NUMBER)
    private val topP = ChatParameterSpec(TOP_P, ChatParameterKind.NUMBER)
    private val topK = ChatParameterSpec(TOP_K, ChatParameterKind.NUMBER)
    private val minP = ChatParameterSpec(MIN_P, ChatParameterKind.NUMBER)
    private val repeatPenalty = ChatParameterSpec(REPEAT_PENALTY, ChatParameterKind.NUMBER)
    private val takesVideo = ChatParameterSpec(TAKES_VIDEO, ChatParameterKind.SWITCH)
    private val takesAudio = ChatParameterSpec(TAKES_AUDIO, ChatParameterKind.SWITCH)

    /**
     * How hard a reasoning model thinks before it answers, on Azure. The SDK
     * knows more words than these (`none`, `xhigh`, `max`), but these are the
     * four every Azure reasoning deployment takes.
     */
    val REASONING_EFFORT_SPEC = ChatParameterSpec(
        REASONING_EFFORT,
        ChatParameterKind.CHOICE,
        listOf("minimal", "low", "medium", "high"),
    )

    /**
     * The same setting everywhere else that reads `reasoning_effort`: every
     * word openai-java's `ReasoningEffort` knows. Which of them a model honours
     * is the model's - OpenAI's models differ, and llama.cpp hands the word to
     * the model's template and Ollama maps it onto the model's own levels - so
     * the list is the API's and the choice is whoever configures the model.
     */
    val OPEN_REASONING_EFFORT_SPEC = ChatParameterSpec(
        REASONING_EFFORT,
        ChatParameterKind.CHOICE,
        listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
    )

    private val AZURE = listOf(temperature, topP, REASONING_EFFORT_SPEC)
    private val OPENAI_HOSTED = listOf(temperature, topP, OPEN_REASONING_EFFORT_SPEC, takesAudio)
    private val OPENAI_SHAPED =
        listOf(temperature, topP, topK, minP, repeatPenalty, OPEN_REASONING_EFFORT_SPEC, takesVideo, takesAudio)
    private val OLLAMA = listOf(temperature, topP, OPEN_REASONING_EFFORT_SPEC)
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
        // llama.cpp's own sampler: top-k, min-p and the repeat penalty, as any OpenAI-shaped server takes.
        ProviderType.LLAMA_CPP -> OPENAI_SHAPED
        ProviderType.ANTHROPIC -> ANTHROPIC
        // Neither chats: a decision model answers questions, an image server draws.
        ProviderType.SYSTEM_ONE, ProviderType.STABLE_DIFFUSION_CPP, ProviderType.STABLE_DIFFUSION_WEBUI -> emptyList()
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

    /** A switch as it is stored: off always, on refused where the provider does not take it. */
    fun held(provider: ModelProvider, kind: ModelKind, parameter: String, given: Boolean?): Boolean {
        if (given != true) return false
        taken(provider, kind, parameter)
        return true
    }

    /** Whether [model] may be sent a video, as the request is about to be built: its switch, on a provider that carries one. */
    fun takesVideo(provider: ModelProvider, model: LlmModel): Boolean =
        model.takesVideo && model.kind == ModelKind.CHAT && takes(provider, TAKES_VIDEO)

    /** Whether [model] may be sent audio, as the request is about to be built: its switch, on a provider that carries it. */
    fun takesAudio(provider: ModelProvider, model: LlmModel): Boolean =
        model.takesAudio && model.kind == ModelKind.CHAT && takes(provider, TAKES_AUDIO)

    /** Whether [provider] is OpenAI's own API rather than a server imitating its shape. */
    fun isOpenAiItself(provider: ModelProvider): Boolean =
        provider.type == ProviderType.OPENAI && hostedByOpenAi(provider.endpoint)

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
