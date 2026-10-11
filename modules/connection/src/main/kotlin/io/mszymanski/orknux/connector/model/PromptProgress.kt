package io.mszymanski.orknux.connector.model

/**
 * How far a server has got reading a prompt before it writes a word.
 *
 * llama.cpp reports it when asked - `return_progress` on a streamed request -
 * as `prompt_progress` on the stream's first frames. Nothing else does, and on a
 * long session whose cached prefix no longer matches (a tool description
 * changed, a restart) the read is minutes of a turn that shows nothing at all.
 * Issue #627.
 *
 * @property total the prompt's length in tokens.
 * @property cached how many of those were reused from the server's cache.
 * @property processed how many have been read so far, the cached ones included.
 */
data class PromptProgress(val total: Long, val cached: Long, val processed: Long) {

    companion object {
        /**
         * The `prompt_progress` object of one frame, or null where there is none
         * or it does not read as one. Read leniently: a field missing or of the
         * wrong kind is a frame that says nothing, not a stream that failed.
         */
        fun of(field: Map<String, Any?>?): PromptProgress? {
            if (field == null) return null
            fun number(name: String): Long? = (field[name] as? Number)?.toLong()
            val total = number("total") ?: return null
            val processed = number("processed") ?: return null
            return PromptProgress(total = total, cached = number("cache") ?: 0, processed = processed)
        }
    }
}
