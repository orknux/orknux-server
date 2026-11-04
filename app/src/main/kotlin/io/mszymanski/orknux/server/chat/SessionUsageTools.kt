package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * What this conversation has cost so far, as its page shows it: the input
 * tokens, how many of them the provider served from its cache, the output
 * tokens and the price.
 *
 * The page showed it to the person and the agent had no way to read it, so a
 * person asking in Slack how many tokens a job had taken got a guess, and an
 * agent told to stay within a budget had nothing to measure against. Read off
 * the same columns the page reads - [io.mszymanski.orknux.server.llm.SessionSpend]
 * adds every call to them as it lands - so the two cannot disagree, and the
 * count includes every call made so far in this turn.
 *
 * And what the agents it asked have cost, separately: each ask is a session of
 * its own, and its page counts it there, not here.
 */
@Service
class SessionUsageTools(
    private val sessions: LlmSessionRepository,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn in [session]; none where there is no session to have spent anything. */
    fun shed(session: Long?): ToolShed? = session?.let { Shed(it) }

    private inner class Shed(private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = USAGE,
                readOnly = true,
                description = "Says how many tokens this conversation has used so far and what they cost - the " +
                    "numbers on its page: input tokens, how many of those the provider served from its cache, " +
                    "output tokens, and the price where the models carry one. Counts every model call up to the " +
                    "last one, this turn's included. The agents you asked are counted separately, under subagents.",
                parameters = emptyList(),
            ),
        )

        override fun handles(name: String): Boolean = name == USAGE

        override fun run(call: ToolCall): String {
            val here = sessions.findByIdOrNull(session)
                ?: return mapper.writeValueAsString(mapOf("error" to "This conversation is not recorded, so nothing was counted."))
            val asked = sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(session)
            return mapper.writeValueAsString(
                linkedMapOf<String, Any?>("conversation" to spentIn(listOf(here))).apply {
                    if (asked.isNotEmpty()) {
                        put("subagents", spentIn(asked) + ("asks" to asked.size))
                    }
                },
            )
        }
    }

    /** The sums over [rows], in the shape the page shows them. */
    private fun spentIn(rows: List<LlmSession>): Map<String, Any?> {
        val input = rows.sumOf { it.spentInputTokens }
        val cached = rows.sumOf { it.spentCachedInputTokens }
        val cost = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.spentCost }
        val unpriced = rows.any { it.spentPartlyUnpriced }
        return linkedMapOf(
            "inputTokens" to input,
            "cachedInputTokens" to cached,
            "cachedShare" to if (input > 0) "${Math.round(cached * 100.0 / input)}%" else null,
            "outputTokens" to rows.sumOf { it.spentOutputTokens },
            "cost" to cost.takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString(),
            "partlyUnpriced" to unpriced,
        ).apply {
            if (unpriced) put("note", "Some calls were to a model with no price set, so cost is only part of the whole.")
        }
    }

    companion object {
        const val USAGE = "session_usage"
    }
}
