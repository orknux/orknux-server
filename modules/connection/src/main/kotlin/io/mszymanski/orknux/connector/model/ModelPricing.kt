package io.mszymanski.orknux.connector.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a number of tokens costs, at what a model is recorded as charging.
 *
 * One place, because two of them would disagree. The metrics card costs a
 * thirty-day window and a chat costs a single answer, and the moment those are
 * worked out separately somebody's month stops adding up to the answers in it.
 *
 * The prices are per million tokens and are optional on the model, so the answer
 * is optional too: null means nobody recorded what this model charges, which is
 * not the same as free. A caller shows nothing where it gets null - a zero would
 * be a claim about money, and the one it would make is wrong.
 *
 * A model with one of the two prices and not the other is costed on the half it
 * has. That is deliberately not null: half a price is somebody part way through
 * filling the form in, and the number it gives is a floor rather than a
 * fabrication.
 */
object ModelPricing {

    /**
     * The scale a window is reported at. Two places, because a month of a team's
     * chat is dollars and cents and the fractions under that are noise.
     */
    const val WINDOW_SCALE = 2

    /**
     * The scale a single answer is reported at.
     *
     * Four, because one answer at ordinary prices is a fraction of a cent:
     * rounded to the window's two places every line in a chat would read $0.00,
     * which is the zero this feature exists not to print.
     */
    const val ANSWER_SCALE = 4

    private val MILLION: BigDecimal = BigDecimal(1_000_000)

    fun cost(
        model: LlmModel,
        inputTokens: Long,
        outputTokens: Long,
        scale: Int = ANSWER_SCALE,
        /** How many of [inputTokens] the provider served from its cache. */
        cachedTokens: Long = 0,
        /** How many of [inputTokens] the provider wrote into its cache. */
        cacheWriteTokens: Long = 0,
    ): BigDecimal? =
        cost(
            model.inputCostPerMillion, model.outputCostPerMillion, inputTokens, outputTokens, scale, cachedTokens,
            model.cachedInputCostPerMillion, cacheWriteTokens, model.cacheWriteCostPerMillion,
        )

    /** The same arithmetic for a caller holding the two prices rather than the model. */
    fun cost(
        inputCostPerMillion: BigDecimal?,
        outputCostPerMillion: BigDecimal?,
        inputTokens: Long,
        outputTokens: Long,
        scale: Int = ANSWER_SCALE,
        cachedTokens: Long = 0,
        cachedInputCostPerMillion: BigDecimal? = null,
        cacheWriteTokens: Long = 0,
        cacheWriteCostPerMillion: BigDecimal? = null,
    ): BigDecimal? {
        if (inputCostPerMillion == null && outputCostPerMillion == null) return null

        val perMillion = { tokens: Long, price: BigDecimal? ->
            price?.multiply(BigDecimal(tokens))?.divide(MILLION, scale, RoundingMode.HALF_UP) ?: BigDecimal.ZERO
        }
        /*
         * The input count is every token the model read, the cached ones among
         * them - that is how OpenAI and Azure report it. Where a cached price
         * is recorded, that share is charged at it; where none is, the whole
         * input is at the input price, as it always was.
         */
        val cached = if (cachedInputCostPerMillion == null) 0L else cachedTokens.coerceIn(0L, inputTokens)
        /*
         * The tokens written into the cache are input too, and of what was not
         * read from it: charged at the write price where one is recorded, and
         * at the input price otherwise - which is what they cost before this.
         */
        val written = if (cacheWriteCostPerMillion == null) 0L else cacheWriteTokens.coerceIn(0L, inputTokens - cached)
        return perMillion(inputTokens - cached - written, inputCostPerMillion)
            .add(perMillion(cached, cachedInputCostPerMillion))
            .add(perMillion(written, cacheWriteCostPerMillion))
            .add(perMillion(outputTokens, outputCostPerMillion))
    }

    /**
     * What a number of pictures costs, at what an image model is recorded as
     * charging.
     *
     * Here rather than in a caller for the reason everything above is here: the
     * metrics card costs a window and the chat costs one drawing, and the moment
     * those are worked out separately somebody's month stops adding up.
     *
     * Null means nobody recorded a per-image price, which is not free — and it
     * is the only honest answer for an image model, because the token arithmetic
     * above would return `0.00` for one. An image call reports no tokens at all,
     * so a per-million price multiplied by its counts is nought, and nought is
     * the number this whole arrangement exists not to print.
     *
     * Reported at [WINDOW_SCALE] by default rather than at [ANSWER_SCALE]: one
     * picture at any of these providers' prices is cents rather than a fraction
     * of a cent, so two places is the truth about it and four are noise.
     */
    fun imageCost(model: LlmModel, images: Long, scale: Int = WINDOW_SCALE): BigDecimal? =
        model.imageCostPerImage?.multiply(BigDecimal(images))?.setScale(scale, RoundingMode.HALF_UP)
}
