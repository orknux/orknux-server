package io.mszymanski.orknux.connector.model

/**
 * Whether a video may be put in front of a model, and the sentence saying why
 * not.
 *
 * Two different answers, because they are put right in two different places.
 * A model whose provider carries video but whose own switch is off is one tick
 * on its page away; a model on a provider that has no video part at all -
 * OpenAI's own chat completions, Azure, Ollama, Anthropic - cannot be made to
 * take one, and telling somebody to switch on a box that is not drawn for it
 * would send them looking for it. See [ChatParameters] for which providers
 * carry one, and the sources that say so.
 */
object VideoInput {

    /** Whether [provider] has a video part at all, for a chat model. */
    fun carried(provider: ModelProvider, model: LlmModel): Boolean =
        model.kind == ModelKind.CHAT && ChatParameters.takes(provider, ChatParameters.TAKES_VIDEO)

    /** Null where [model] may be sent a video; otherwise the sentence a person reads. */
    fun refusal(provider: ModelProvider, model: LlmModel): String? = when {
        ChatParameters.takesVideo(provider, model) -> null
        !carried(provider, model) -> notCarried(model.name, provider.name)
        else -> notTaken(model.name)
    }

    fun notTaken(model: String): String =
        "$model does not take video. Switch on Takes video on its page, or choose a model that does."

    fun notCarried(model: String, provider: String): String =
        "$model is reached through $provider, whose API takes no video. Choose a model on a provider that does."
}
