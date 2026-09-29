package io.mszymanski.orknux.connector.model

/**
 * One setting a chat model takes that not every provider does, and the words
 * it may be set to.
 *
 * [name] is the model's field as the API spells it (`reasoningEffort`), so the
 * form draws a control per entry and saves it under that name.
 */
data class ChatParameterSpec(
    val name: String,
    /** What it may be, as the endpoint spells it; unset is always allowed and sends nothing. */
    val choices: List<String>,
)

/**
 * Which provider-specific settings a chat model takes, by provider type.
 *
 * The chat counterpart of the image models' capabilities: the model page draws
 * only what its provider declares here, a save refuses a value the provider
 * does not take, and the request sends only what is set. A provider type that
 * comes to take one of these is one entry in [BY_TYPE]; a new setting is that
 * entry, a column on [LlmModel], and the line in [OpenAiChat] that sends it.
 */
object ChatParameters {

    const val REASONING_EFFORT = "reasoningEffort"

    /**
     * How hard a reasoning model thinks before it answers - Azure's o-series and
     * GPT-5 deployments. The SDK knows more words than these (`none`, `xhigh`),
     * but these are the four every such deployment takes.
     */
    val REASONING_EFFORT_SPEC = ChatParameterSpec(REASONING_EFFORT, listOf("minimal", "low", "medium", "high"))

    private val BY_TYPE: Map<ProviderType, List<ChatParameterSpec>> = mapOf(
        ProviderType.AZURE_OPENAI to listOf(REASONING_EFFORT_SPEC),
    )

    /** What a chat model on this provider type takes beyond the settings every provider shares. */
    fun forProvider(type: ProviderType): List<ChatParameterSpec> = BY_TYPE[type].orEmpty()

    /**
     * What a model of this kind on this provider type takes: only a chat model
     * takes any, since these shape a chat request and nothing else sends one.
     */
    fun forModel(type: ProviderType, kind: ModelKind): List<ChatParameterSpec> =
        if (kind == ModelKind.CHAT) forProvider(type) else emptyList()

    /**
     * The value as it is stored: trimmed, null where blank, and refused where
     * this provider does not take the setting or does not take this word for it.
     */
    fun held(type: ProviderType, kind: ModelKind, parameter: String, given: String?): String? {
        val value = given?.trim()?.ifEmpty { null } ?: return null
        val spec = forModel(type, kind).firstOrNull { it.name == parameter }
            ?: throw ModelParameterNotTakenException(parameter, type)
        if (value !in spec.choices) throw ModelParameterValueInvalidException(parameter, value, spec.choices)
        return value
    }
}

/** A provider-specific setting given to a model whose provider type does not take it. */
class ModelParameterNotTakenException(parameter: String, type: ProviderType) :
    RuntimeException("A $type chat model does not take $parameter; leave it unset")

/** A provider-specific setting given a word its provider does not take for it. */
class ModelParameterValueInvalidException(parameter: String, value: String, choices: List<String>) :
    RuntimeException("\"$value\" is not a $parameter this model takes. Choose one of ${choices.joinToString(", ")}, or leave it unset")
