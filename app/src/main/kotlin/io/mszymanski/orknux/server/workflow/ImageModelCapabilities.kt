package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.server.model.ModelNotFoundException
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service

/** How a parameter is asked for: one word off a list, or a width and a height. */
enum class ImageParameterKind {
    /** One of [ImageParameterSpec.choices], as the endpoint spells it. */
    CHOICE,

    /** Any `WIDTHxHEIGHT` with both sides in [minSide, maxSide] and multiples of [step]. */
    DIMENSIONS,
}

/**
 * One parameter an image model's endpoint takes, and the values it takes for it.
 *
 * Only the parameters the endpoint takes are ever listed: a model whose spec
 * has no `style` entry does not take one, and a node that sends one anyway is
 * refused at save rather than by the provider mid-run.
 */
data class ImageParameterSpec(
    /** `size`, `quality` or `style`: the name the endpoint takes it under. */
    val name: String,
    val kind: ImageParameterKind,
    /** What a [ImageParameterKind.CHOICE] may be; empty for dimensions. */
    val choices: List<String> = emptyList(),
    val minSide: Int? = null,
    val maxSide: Int? = null,
    val step: Int? = null,
) {

    /** Whether this word, or this `WIDTHxHEIGHT`, is one the endpoint takes here. */
    fun accepts(value: String): Boolean = when (kind) {
        ImageParameterKind.CHOICE -> value in choices
        ImageParameterKind.DIMENSIONS -> {
            val sides = DIMENSIONS.matchEntire(value)?.destructured?.let { (w, h) -> w.toIntOrNull() to h.toIntOrNull() }
            val (width, height) = sides ?: (null to null)
            width != null && height != null && fits(width) && fits(height)
        }
    }

    private fun fits(side: Int): Boolean =
        side >= (minSide ?: 1) && side <= (maxSide ?: Int.MAX_VALUE) && side % (step ?: 1) == 0

    /** What a refusal names as the values this parameter does take. */
    fun describeChoices(): String = when (kind) {
        ImageParameterKind.CHOICE -> choices.joinToString(", ")
        ImageParameterKind.DIMENSIONS -> "WIDTHxHEIGHT with each side from $minSide to $maxSide, a multiple of $step"
    }

    companion object {
        val DIMENSIONS = Regex("""(\d+)x(\d+)""")
    }
}

/**
 * What each image model's endpoint takes beyond a prompt, by provider and model.
 *
 * Issue #431. The editor used to offer one fixed trio of pickers - a size list
 * that was the union of every OpenAI size, a quality list that was two
 * vocabularies glued together, a style every model was assumed to take - and the
 * value went to the provider to accept or refuse a week later. The knowledge of
 * which model takes what lives here instead, so the editor draws only the
 * controls the chosen model has, the save refuses what the model does not take,
 * and the run sends nothing the endpoint would object to.
 *
 * Matched on the model id case-insensitively by substring, because a deployment
 * on Azure is called whatever somebody called it and `gpt-image-1-mini` is a
 * gpt-image-1. An OpenAI-shaped id nothing here knows is read as the newest
 * family, gpt-image-1, since that is what a new id on those providers is most
 * likely to be. Every other provider type - Ollama, anything self-hosted behind
 * an OpenAI-shaped `/images/generations` - takes free dimensions and nothing
 * else, because the servers that imitate the endpoint take a `WIDTHxHEIGHT` and
 * ignore or refuse the rest.
 */
@Service
class ImageModelCapabilities(
    private val models: LlmModelRepository,
    private val providers: ModelProviderRepository,
) {

    /** The spec for this model, or null where the model or its provider has gone. */
    fun specFor(modelId: Long): List<ImageParameterSpec>? {
        val model = models.findByIdOrNull(modelId) ?: return null
        val provider = providers.findByIdOrNull(model.providerId) ?: return null
        return specFor(provider.type, model.modelId, provider.endpoint)
    }

    /**
     * @param endpoint where the provider is reached. A self-hosted server that
     *   speaks OpenAI's shape is registered as type OPENAI too, and it is the
     *   one that takes a free `WIDTHxHEIGHT` - so the fixed lists apply only
     *   where the host is OpenAI's own (or Azure's); anywhere else gets the
     *   free dimensions. Null reads as OpenAI-hosted.
     */
    fun specFor(type: ProviderType, modelId: String, endpoint: String? = null): List<ImageParameterSpec> {
        val id = modelId.lowercase()
        return when (type) {
            ProviderType.OPENAI, ProviderType.AZURE_OPENAI -> when {
                // A model that names itself takes its own list wherever it is
                // served - through a proxy, on Azure, on a stub.
                "dall-e-2" in id -> DALL_E_2
                "dall-e-3" in id -> DALL_E_3
                "gpt-image" in id -> GPT_IMAGE_1
                // An id OpenAI does not have: on OpenAI's own host (or Azure) it
                // is a newer member of the gpt-image family; anywhere else it is
                // a self-hosted model - sdxl, flux - and those take a free size.
                type == ProviderType.AZURE_OPENAI || endpoint == null || hostedByOpenAi(endpoint) -> GPT_IMAGE_1
                else -> FREE_DIMENSIONS
            }
            else -> FREE_DIMENSIONS
        }
    }

    /** OpenAI's own API host, or an Azure one somebody registered under the plain type. */
    private fun hostedByOpenAi(endpoint: String): Boolean {
        val host = runCatching { java.net.URI(endpoint.trim()).host }.getOrNull()?.lowercase() ?: return true
        return host.endsWith("openai.com") || host.contains("azure")
    }

    /**
     * The node's parameters as they are stored: trimmed, null where blank, and
     * on a save held to what the model takes or refused in a sentence naming it.
     *
     * A node with no model yet, or whose model has gone, keeps what it was given:
     * there is nothing to hold it to, and the run skips a node with no model
     * before any of this is read. A preview keeps an unknown word rather than
     * arguing with somebody mid-edit, since a preview writes nothing down.
     */
    fun held(modelId: Long?, options: ImageOptions, refusing: Boolean): ImageOptions {
        val spec = modelId?.let { specFor(it) }
        val modelName = modelId?.let { models.findByIdOrNull(it)?.name } ?: "This model"
        return ImageOptions(
            size = held(spec, modelName, "size", options.size, refusing),
            quality = held(spec, modelName, "quality", options.quality, refusing),
            style = held(spec, modelName, "style", options.style, refusing),
        )
    }

    private fun held(
        spec: List<ImageParameterSpec>?,
        modelName: String,
        parameter: String,
        given: String?,
        refusing: Boolean,
    ): String? {
        val value = given?.trim()?.ifEmpty { null } ?: return null
        if (spec == null || !refusing) return value
        val wanted = spec.firstOrNull { it.name == parameter }
            ?: throw ImageParameterInvalidException.notTaken(modelName, parameter, spec)
        if (!wanted.accepts(value)) throw ImageParameterInvalidException.offList(modelName, parameter, value, wanted)
        return value
    }

    /**
     * The options with everything the model does not take left out, which is
     * what a run sends. A node saved against one model and re-pointed at another
     * is a node holding a style the new model refuses; the save refuses that
     * today, but a step already planned carries its own copy, and the wire is
     * where the provider's 400 would come from.
     */
    fun narrow(modelId: Long, options: ImageOptions): ImageOptions {
        val spec = specFor(modelId) ?: return options
        val takes = spec.map { it.name }.toSet()
        return ImageOptions(
            size = options.size?.takeIf { "size" in takes },
            quality = options.quality?.takeIf { "quality" in takes },
            style = options.style?.takeIf { "style" in takes },
        )
    }

    companion object {
        val DALL_E_2 = listOf(
            ImageParameterSpec("size", ImageParameterKind.CHOICE, listOf("256x256", "512x512", "1024x1024")),
        )
        val DALL_E_3 = listOf(
            ImageParameterSpec("size", ImageParameterKind.CHOICE, listOf("1024x1024", "1792x1024", "1024x1792")),
            ImageParameterSpec("quality", ImageParameterKind.CHOICE, listOf("standard", "hd")),
            ImageParameterSpec("style", ImageParameterKind.CHOICE, listOf("vivid", "natural")),
        )
        val GPT_IMAGE_1 = listOf(
            ImageParameterSpec("size", ImageParameterKind.CHOICE, listOf("1024x1024", "1536x1024", "1024x1536", "auto")),
            ImageParameterSpec("quality", ImageParameterKind.CHOICE, listOf("low", "medium", "high", "auto")),
        )
        val FREE_DIMENSIONS = listOf(
            ImageParameterSpec("size", ImageParameterKind.DIMENSIONS, minSide = 64, maxSide = 4096, step = 8),
        )
    }
}

/**
 * The parameters an image model takes, for the editor to draw controls from.
 *
 * Authorised like reading the model: another workspace's model is answered as
 * one that does not exist, since a list cannot be null and a refusal would
 * confirm the id is real.
 */
@Controller
class ImageModelParametersAPI(
    private val capabilities: ImageModelCapabilities,
    private val models: LlmModelRepository,
    private val providers: ModelProviderRepository,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    fun imageModelParameters(@Argument modelId: Long): List<ImageParameterSpec> {
        val model = models.findByIdOrNull(modelId) ?: throw ModelNotFoundException(modelId)
        val provider = providers.findByIdOrNull(model.providerId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ModelNotFoundException(modelId)
        // The endpoint too, or the editor and the save disagree: a self-hosted
        // server registered as OPENAI would be drawn with gpt-image-1's lists
        // here and refused for them at save.
        return capabilities.specFor(provider.type, model.modelId, provider.endpoint)
    }
}

/**
 * A size, quality or style the model does not take, refused at save in a
 * sentence naming what it does take. Issue #423, reworded for #431: the list
 * is the model's now, not the editor's.
 */
class ImageParameterInvalidException private constructor(message: String) : RuntimeException(message) {

    companion object {
        fun offList(model: String, parameter: String, value: String, takes: ImageParameterSpec) = ImageParameterInvalidException(
            "\"$value\" is not a $parameter $model takes. Choose one of ${takes.describeChoices()}, or leave it to the model's default",
        )

        fun notTaken(model: String, parameter: String, spec: List<ImageParameterSpec>) = ImageParameterInvalidException(
            "$model does not take a $parameter" +
                (spec.takeIf { it.isNotEmpty() }?.let { "; it takes ${it.joinToString(", ") { one -> one.name }}" } ?: ""),
        )
    }
}
