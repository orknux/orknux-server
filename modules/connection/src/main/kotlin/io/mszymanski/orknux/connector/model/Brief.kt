package io.mszymanski.orknux.connector.model

/**
 * A call that wants less of a model than the model's own settings allow.
 *
 * A model is configured for the conversations it holds: an output limit long
 * enough for an answer, a reasoning effort that suits thinking a question
 * through. A caller asking for a few words - the continuation of a sentence
 * somebody is still typing - wants neither, and waiting on a reasoning model's
 * thinking for six words is the difference between a suggestion and one that
 * arrives after the person has typed them.
 *
 * Applied to a detached copy of the model, never to the stored row, so nothing
 * here can be saved back over what an administrator chose.
 *
 * @param maxOutput the most tokens the answer may have; the model's own limit
 *   still applies where it is lower.
 * @param leastReasoning whether a model that carries a reasoning effort is
 *   asked for the least the API knows. A model that carries none is sent none,
 *   as before: a deployment that does not reason refuses the field, and only a
 *   provider that declares it in [ChatParameters] could ever hold one.
 */
data class Brief(val maxOutput: Int, val leastReasoning: Boolean = true) {

    /** [model] as this call sends it. */
    fun applied(model: LlmModel): LlmModel = model.copied(model.providerId, model.name).also { copy ->
        copy.maxOutput = model.maxOutput?.coerceAtMost(maxOutput) ?: maxOutput
        if (leastReasoning && model.reasoningEffort != null) copy.reasoningEffort = LEAST_EFFORT
    }

    private companion object {
        /**
         * The lowest of [ChatParameters.REASONING_EFFORT_SPEC]'s words, and one
         * [ChatParameters.OPEN_REASONING_EFFORT_SPEC] holds too. Not `none`: that
         * turns thinking off, where a brief asks only for less of it.
         */
        const val LEAST_EFFORT = "minimal"
    }
}
