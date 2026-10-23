package io.mszymanski.orknux.connector.model

import com.openai.core.JsonValue

/**
 * How fast a provider says it read a prompt and wrote an answer, as it measured
 * them itself.
 *
 * Only ever the provider's own figures. llama.cpp's server answers a `timings`
 * object beside the OpenAI `usage` - on a plain completion at the top level, on
 * a stream on its last frame (`server_slot_stats::to_json`: `prompt_n`,
 * `prompt_ms`, `prompt_per_second`, `predicted_n`, `predicted_ms`,
 * `predicted_per_second`, and `cache_n`). OpenAI, Azure, vLLM, Anthropic and
 * Ollama's OpenAI-compatible endpoint report nothing of the kind, and a call to
 * one of them carries no timings rather than a speed worked out from how long
 * the request took - that would be the network, the queue and the prompt
 * together, presented as the model's.
 *
 * [promptTokens] are the tokens the server actually processed, which leaves out
 * what its cache served (`cache_n`); that is what makes the prompt speed a
 * speed rather than a ratio inflated by a cache hit.
 */
data class ModelTimings(
    val promptTokens: Long,
    val promptMillis: Double,
    /** What the provider said, or null where it said something that is not a number - a prompt it served whole from cache. */
    val promptPerSecond: Double?,
    val generatedTokens: Long,
    val generatedMillis: Double,
    val generatedPerSecond: Double?,
) {
    companion object {

        /** Where llama.cpp puts them, beside `usage`. */
        const val FIELD = "timings"

        /**
         * The timings among a response's or a frame's extra properties, or null
         * where there are none - which is every provider but llama.cpp.
         *
         * Read through the SDK's `_additionalProperties()`, the documented way
         * to reach a field it does not model; nothing here parses the body.
         */
        fun of(properties: Map<String, JsonValue>?): ModelTimings? {
            val held = properties?.get(FIELD)?.asObject()?.orElse(null) ?: return null
            fun number(name: String): Double? =
                held[name]?.asNumber()?.orElse(null)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            return ModelTimings(
                promptTokens = number("prompt_n")?.toLong() ?: return null,
                promptMillis = number("prompt_ms") ?: return null,
                promptPerSecond = number("prompt_per_second"),
                generatedTokens = number("predicted_n")?.toLong() ?: return null,
                generatedMillis = number("predicted_ms") ?: return null,
                generatedPerSecond = number("predicted_per_second"),
            )
        }
    }
}
