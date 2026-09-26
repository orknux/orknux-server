package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The size and quality an agent's draw tool offers, and what a call asked for.
 *
 * Issue #436. The three draw tools - `chat_draw_picture`, `task_draw_picture`
 * and a run's `draw_picture` - took a description and nothing else, so every
 * picture an agent drew came out at the model's default: square, at whatever
 * quality the provider chose. An agent asked for a banner had no way to say
 * "wide", and an agent illustrating a report had no way to say "cheaply". The
 * image node had both since #423, and #431 made the list of what may be asked
 * the *model's*; this puts the same list in front of the agent.
 *
 * ### Width and height, not a size word
 *
 * The node stores `1792x1024` because the editor offers a list and a list is
 * picked from. A model calling a tool is not picking from a list - every
 * parameter reaches it as a string with a description - and a model told "size:
 * one of 1024x1024, 1792x1024, 1024x1792" will one day write `1792 x 1024`, or
 * `1792×1024`, or `landscape`. Two whole numbers are harder to misspell, and
 * they are what the model is thinking in when it wants a picture wider than it
 * is tall. The pair is joined into the provider's `WIDTHxHEIGHT` here, once.
 *
 * ### Only what this model takes
 *
 * Built per call from [ImageModelCapabilities.specFor], not from a fixed list:
 * a DALL-E 3 is offered its three sizes and its two qualities; a gpt-image-1 its
 * own; a self-hosted server a range and a step and no quality at all. A
 * parameter the model does not take is not in the descriptor, because a tool
 * that offers `quality` to a model whose endpoint refuses the whole request
 * over it is a tool teaching the agent a lesson at somebody's expense. Style is
 * never offered: it is DALL-E 3's alone, it is an aesthetic the agent's own
 * description already carries, and one more knob is one more thing to get wrong.
 *
 * ### Refused in a sentence, before the provider is asked
 *
 * A size the model does not draw is answered to the agent as a refusal naming
 * what it does draw - the same sentence the editor's save gives a person,
 * through the same [ImageModelCapabilities.held] - rather than sent on to
 * arrive back as a provider's 400 about a field. The agent reads the list and
 * asks again; nothing was charged for.
 */
@Service
class DrawToolParameters(
    private val mapper: ObjectMapper,
    private val capabilities: ImageModelCapabilities,
) {

    /**
     * The tool as it is offered when this model is what draws: its own
     * parameters, then a width and a height, then a quality where the model
     * takes one.
     *
     * @param modelId the workspace's image model, or null where it has none or
     *   it has gone. The tool is then offered with a width and height described
     *   generally, since there is no spec to describe them from; the call is
     *   refused for want of a model before any of this matters.
     */
    fun offering(tool: ToolSpec, modelId: Long?): ToolSpec =
        tool.copy(parameters = tool.parameters + parameters(modelId))

    /** Width and height always; quality only where this model's endpoint takes one. */
    fun parameters(modelId: Long?): List<ToolParameterSpec> {
        val spec = modelId?.let { capabilities.specFor(it) }
        val size = spec?.firstOrNull { it.name == SIZE }
        val quality = spec?.firstOrNull { it.name == QUALITY }

        val sizes = size?.let(::sizesOf)
        val width = ToolParameterSpec(
            name = WIDTH,
            description = "The picture's width in pixels. Give width and height together, or leave both out " +
                "for the model's default. " + (sizes ?: "Only a size the model draws is accepted."),
        )
        val height = ToolParameterSpec(
            name = HEIGHT,
            description = "The picture's height in pixels; give it with width. " +
                (sizes ?: "Only a size the model draws is accepted."),
        )
        return listOfNotNull(
            width,
            height,
            quality?.let {
                ToolParameterSpec(
                    name = QUALITY,
                    description = "How carefully the picture is drawn: one of ${it.describeChoices()}. " +
                        "Leave it out for the model's default; a higher quality costs more.",
                )
            },
        )
    }

    /**
     * What the model may be told about its sizes, from the spec.
     *
     * A choice list is spelled out in full so the agent can match a pair to it,
     * with the one entry that is not a pair left out: gpt-image-1's `auto` is
     * what leaving width and height out already means, and a model told it may
     * write `auto` into a field described as pixels will. A range says its
     * bounds and its step, because "any size" is what a model reads a range as
     * and 1000x1000 is not a multiple of 8.
     */
    private fun sizesOf(size: ImageParameterSpec): String = when (size.kind) {
        ImageParameterKind.CHOICE -> {
            val pairs = size.choices.filter { ImageParameterSpec.DIMENSIONS.matches(it) }
            "Together they must be one of the sizes this model draws, as width x height: " +
                pairs.joinToString(", ") + "."
        }
        ImageParameterKind.DIMENSIONS ->
            "Each side is from ${size.minSide} to ${size.maxSide} pixels and a multiple of ${size.step}."
    }

    /**
     * What a call asked for beyond its description, held to what the model
     * takes, or why it cannot be drawn as asked.
     *
     * The arguments are parsed here rather than handed over as a tree because
     * every tool already holds them as the string the provider sent, and a
     * string that is not JSON is a call with nothing asked - not an exception.
     *
     * @param modelId what will draw, or null where nothing will. With no model
     *   there is no spec to hold anything to, so whatever was asked is passed
     *   through and the drawing itself refuses for want of a model, in the
     *   sentence it already has for that.
     */
    fun asked(modelId: Long?, arguments: String): Asked {
        val tree = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return Asked.Options(ImageOptions.NONE)

        val width = when (val read = pixels(tree, WIDTH)) {
            is Pixels.Unreadable -> return Asked.Refused(read.reason)
            else -> read
        }
        val height = when (val read = pixels(tree, HEIGHT)) {
            is Pixels.Unreadable -> return Asked.Refused(read.reason)
            else -> read
        }

        // One without the other is not half a size; it is no size, and saying
        // so costs one round where guessing the other side costs a picture.
        val size = when {
            width is Pixels.Given && height is Pixels.Given -> "${width.value}x${height.value}"
            width is Pixels.Given || height is Pixels.Given ->
                return Asked.Refused("Give both width and height, or neither: a size is the two together.")
            else -> null
        }

        val quality = word(tree, QUALITY)
        val options = ImageOptions(size = size, quality = quality)

        return try {
            val held = capabilities.held(modelId, options, refusing = true)
            Asked.Options(modelId?.let { capabilities.narrow(it, held) } ?: held)
        } catch (refused: ImageParameterInvalidException) {
            Asked.Refused(refused.message ?: "That is not a size or quality this model takes.")
        }
    }

    /**
     * One side, as the model wrote it: absent, a whole number, or neither.
     *
     * Every tool parameter is declared a string, so a model may send `1024` or
     * `"1024"` and both are the number. `"1024px"` is not, and is refused in
     * words rather than read as nothing - a model that wrote a width meant one,
     * and drawing at the default would be answering a question it did not ask.
     */
    private fun pixels(tree: JsonNode, name: String): Pixels {
        val node = tree.path(name)
        if (node.isMissingNode || node.isNull) return Pixels.Absent
        val value = when {
            node.isNumber -> node.numberValue().toDouble().takeIf { it == Math.floor(it) }?.toInt()
            node.isTextual -> node.stringValue().trim().ifEmpty { return Pixels.Absent }.toIntOrNull()
            else -> null
        }
        return if (value == null || value <= 0) {
            Pixels.Unreadable("$name is a whole number of pixels; leave it out for the model's default.")
        } else {
            Pixels.Given(value)
        }
    }

    /** A word off the call, or null where there is none; whitespace is not a quality. */
    private fun word(tree: JsonNode, name: String): String? {
        val node = tree.path(name)
        if (node.isMissingNode || node.isNull) return null
        val text = if (node.isTextual) node.stringValue() else node.toString()
        return text.trim().ifEmpty { null }
    }

    private sealed interface Pixels {
        data object Absent : Pixels
        data class Given(val value: Int) : Pixels
        data class Unreadable(val reason: String) : Pixels
    }

    /** What a call asked for, or why it will not be drawn as asked. */
    sealed interface Asked {
        /** Size and quality as the provider takes them, narrowed to what this model's endpoint takes. */
        data class Options(val options: ImageOptions) : Asked

        /**
         * Why not, in the sentence the agent is handed: what was asked, and
         * what the model does take instead.
         */
        data class Refused(val reason: String) : Asked
    }

    companion object {
        const val WIDTH = "width"
        const val HEIGHT = "height"
        const val QUALITY = "quality"
        private const val SIZE = "size"
    }
}
