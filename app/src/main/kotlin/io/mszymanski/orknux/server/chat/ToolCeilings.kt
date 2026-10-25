package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import org.springframework.stereotype.Service

/**
 * How many tools an agent carries before the rest are found, as one turn reads it.
 *
 * An agent's own ceiling ([Agent.maxTools]) is a judgement that a model chooses
 * badly among too many, and it puts the rest behind `tool_find` and `tool_load`.
 * The provider's own cap on a request ([ModelChatClient.toolLimit]) applies on
 * top of it, and the lower of the two wins.
 *
 * A provider set to offer every tool up front
 * ([io.mszymanski.orknux.connector.model.ModelProvider.offerAllTools]) switches
 * the agent's ceiling off, so its Offer marks are set aside and every tool goes
 * out from the first request, up to the provider's cap. Off by default: it
 * makes the first prompt as large as the agent's whole toolset. What it buys
 * is an array that never grows, on a server where a tool loaded and joined at
 * the next turn re-reads the whole conversation - see [loading].
 *
 * Asked by everything that decides what is carried - the round, the briefing's
 * list and the setup a session records - so the three cannot disagree. [split]
 * is that decision, made once here rather than three times: it was three, and
 * the briefing marked a tool "(load it first)" that the request carried, so the
 * model loaded what it held, or the other way about.
 */
@Service
class ToolCeilings(private val models: ModelChatClient) {

    /** The ceiling for [agent] on [modelId]: its own, or null where the provider offers every tool up front. */
    fun of(agent: Agent, modelId: Long? = agent.modelId): Int? =
        if (models.toolLoading(modelId).offersAll) null else agent.maxTools

    /** How a tool loaded on [modelId] is called from then on, and what the model is told of it. See [LoadedTools]. */
    fun loading(modelId: Long?): LoadedTools = LoadedTools.of(models.toolLoading(modelId))

    /** The most tools one request to [modelId] may carry. */
    fun cap(modelId: Long?): Int? = modelId?.let(models::toolLimit)

    /**
     * What a turn on [modelId] starts out carrying and what it leaves to be
     * found - before anything is loaded, so it is the same in every round and
     * every turn of a conversation while the agent stays as it is.
     *
     * @param holding the agent's own tools, split at [of]'s ceiling.
     * @param lending what the caller lends, already held to the agent's grants.
     * @param capped the agent's own tools split at a given ceiling: asked only
     *   where the request has to be cut down without a ceiling of the agent's.
     */
    fun split(
        agent: Agent,
        modelId: Long?,
        holding: Offering,
        lending: List<ToolSpec>,
        capped: (Int) -> Offering,
    ): Carrying {
        val ceiling = of(agent, modelId)
        val (lent, lentFindable) = lending.partition { BuiltInTools.carried(agent, it.name, ceiling) }
        /*
         * The agent's own ceiling where it carries one, and the provider's
         * otherwise. Issue #372: the provider's is the number at which a
         * request fails, an agent's the number at which somebody decided it
         * was choosing badly, and the lower wins.
         */
        val limit = minOf(modelId?.let(models::toolLimit) ?: Int.MAX_VALUE, ceiling ?: Int.MAX_VALUE)
        val holds = holding.core.size + holding.searchable.size + lent.size + lentFindable.size
        // And tool_run, which every array carries for the tools that arrive after it was made.
        if (holds + ToolRun.SLOTS <= limit) {
            // Everything fits: every tool goes out, and nothing is found.
            return Carrying(false, limit, holding.core + holding.searchable, lent + lentFindable, emptyList())
        }
        if (ceiling != null) {
            /*
             * The agent's own judgement: Always carried, the rest found, and
             * what is carried cut to the request where even that overflows.
             */
            val carried = holding.core + lent
            val kept = carried.take((limit - ToolSearchTools.OFFERED - ToolRun.SLOTS).coerceAtLeast(0)).map { it.name }.toSet()
            return Carrying(
                true,
                limit,
                holding.core.filter { it.name in kept },
                lent.filter { it.name in kept },
                carried.filterNot { it.name in kept } + holding.searchable + lentFindable,
            )
        }
        /*
         * No ceiling of the agent's, and more than the provider takes: as many
         * as fit go up front, Always first, and only the overflow is found.
         *
         * This carried the Always ones and nothing else, as a ceiling would -
         * so an agent holding 218 tools on a llama.cpp that takes 200 carried
         * a few dozen and loaded the rest one search at a time, and every load
         * changed the tools at the top of the prompt. Measured against a Qwen
         * hybrid model on llama.cpp, any change to the array - a tool appended
         * at its end as much as one inserted in its middle - and the whole
         * prompt is read again: 11,077 of 11,077 tokens, where an unchanged
         * request reads 4. So the array is filled, and a load becomes the rare
         * case of a tool past the cap.
         *
         * Filled to the brim, less the finder and tool_run. It was kept short
         * of the cap so a load had somewhere to go; a loaded tool joins the
         * array at the next turn only where the cap leaves room (see
         * [ConversationFrame.joined]), and past it is called through tool_run
         * - see [io.mszymanski.orknux.server.llm.SessionFrames].
         */
        val split = capped(limit)
        val (splitLent, splitLentFindable) = lending.partition { BuiltInTools.carried(agent, it.name, limit) }
        val ordered = split.core + splitLent + split.searchable + splitLentFindable
        val room = (limit - ToolSearchTools.OFFERED - ToolRun.SLOTS).coerceAtLeast(0)
        val kept = ordered.take(room).map { it.name }.toSet()
        return Carrying(
            true,
            limit,
            (split.core + split.searchable).filter { it.name in kept },
            (splitLent + splitLentFindable).filter { it.name in kept },
            ordered.filterNot { it.name in kept },
        )
    }
}

/**
 * What a turn starts out carrying, and what it leaves to be found. See [ToolCeilings.split].
 *
 * @property hunting whether anything is left to be found, and so whether the
 *   finder is lent at all.
 * @property limit the most one request takes.
 * @property core the agent's own tools that go out from the first round.
 * @property lent what the caller lends that goes out from the first round.
 * @property findable everything else, own and lent: what `tool_load` reaches.
 */
data class Carrying(
    val hunting: Boolean,
    val limit: Int,
    val core: List<ToolSpec>,
    val lent: List<ToolSpec>,
    val findable: List<ToolSpec>,
) {
    /** The names that go out from the first round. */
    val carried: Set<String> get() = (core + lent).map { it.name }.toSet()
}
