package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.agent.Agent
import org.springframework.stereotype.Service

/**
 * How many tools an agent carries before the rest are found, as one turn reads it.
 *
 * An agent's own ceiling ([Agent.maxTools]) is a judgement that a model chooses
 * badly among too many, and it puts the rest behind `tool_find` and `tool_load`.
 * A provider that offers every tool up front overrides it: a local server reuses
 * the prompt a request begins with, the tools are near the top of it, and a tool
 * loaded or dropped mid-turn changes that beginning - so llama.cpp read a long
 * session again after every `tool_load`. There the ceiling is null, and only the
 * provider's own cap on a request ([ModelChatClient.toolLimit]) still puts a tool
 * behind a search.
 *
 * Asked by everything that decides what is carried - the round, the briefing's
 * list and the setup a session records - so the three cannot disagree.
 */
@Service
class ToolCeilings(private val models: ModelChatClient) {

    /** The ceiling for [agent] on [modelId]: its own, or null where the provider offers every tool. */
    fun of(agent: Agent, modelId: Long? = agent.modelId): Int? =
        if (models.offersAllTools(modelId)) null else agent.maxTools

    /** Whether [modelId]'s provider offers every tool up front. */
    fun offersAll(modelId: Long?): Boolean = models.offersAllTools(modelId)

    /** The most tools one request to [modelId] may carry. */
    fun cap(modelId: Long?): Int? = modelId?.let(models::toolLimit)
}
