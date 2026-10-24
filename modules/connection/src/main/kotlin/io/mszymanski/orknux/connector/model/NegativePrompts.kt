package io.mszymanski.orknux.connector.model

import tools.jackson.databind.json.JsonMapper

/**
 * What a drawn picture must not contain, and how each server that takes one is
 * handed it. Issue #657.
 *
 * Which servers take one is [ProviderType.takesNegativePrompt]'s answer, and
 * everything here asks it rather than knowing a type itself. Where the answer
 * is no, a negative prompt is refused in a sentence rather than left out of the
 * request: a picture drawn with the very thing somebody asked to keep out of
 * it, and nothing saying so, is worse than no picture.
 */
object NegativePrompts {

    private val json = JsonMapper.builder().build()

    /**
     * The negative prompt a drawing sends: the one it was given, else the
     * model's own default - or why it cannot be sent.
     *
     * The model's default is only read where its provider takes one; a default
     * left on a model that can no longer use it is not a request somebody made.
     */
    fun resolved(provider: ModelProvider, model: LlmModel, given: String?): Resolved {
        val asked = given?.trim()?.ifEmpty { null }
        if (!provider.type.takesNegativePrompt) {
            return if (asked == null) Resolved.Sent(null) else Resolved.Refused(notTaken(model.name))
        }
        return Resolved.Sent(asked ?: model.negativePrompt?.trim()?.ifEmpty { null })
    }

    /** A model's default as it is stored: trimmed, null where blank, refused where its provider takes none. */
    fun held(provider: ModelProvider, modelName: String, given: String?): String? {
        val value = given?.trim()?.ifEmpty { null } ?: return null
        if (!provider.type.takesNegativePrompt) throw NegativePromptNotTakenException(modelName)
        return value
    }

    /**
     * The prompt as stable-diffusion.cpp's server takes a negative prompt on its
     * OpenAI-shaped routes: inside it, as `<sd_cpp_extra_args>` holding the
     * native field, which the server takes out of the prompt before drawing.
     *
     * Its `/v1/images/generations` reads only `prompt`, `n`, `size`,
     * `output_format` and `output_compression` from the body, and
     * `/v1/images/edits` the same from its form, so a top-level
     * `negative_prompt` would be ignored; the extra arguments are the one door
     * both routes read. See `examples/server/routes_openai.cpp` and `api.md` in
     * leejet/stable-diffusion.cpp. The JSON is written by a mapper, so a quote or
     * a line break in the negative prompt cannot end the block early - the
     * server's pattern does not cross a line.
     */
    fun carried(prompt: String, negative: String?): String {
        val value = negative?.trim()?.ifEmpty { null } ?: return prompt
        return "$prompt $OPEN${json.writeValueAsString(mapOf(FIELD to value))}$CLOSE"
    }

    fun notTaken(modelName: String): String =
        "$modelName does not take a negative prompt; leave it out"

    /** The name the servers that take one read it under. */
    const val FIELD = "negative_prompt"

    private const val OPEN = "<sd_cpp_extra_args>"
    private const val CLOSE = "</sd_cpp_extra_args>"

    /** What a drawing sends, or why it cannot. */
    sealed interface Resolved {
        /** Null is none. */
        data class Sent(val value: String?) : Resolved
        data class Refused(val reason: String) : Resolved
    }
}

/** A negative prompt given to a model whose provider takes none. */
class NegativePromptNotTakenException(modelName: String) : RuntimeException(NegativePrompts.notTaken(modelName))
