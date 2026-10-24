package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.sharing.WorkspaceSharing
import org.springframework.stereotype.Component

/**
 * Whether a workspace may hold a conversation with a model: one of its own, or
 * one on a provider another workspace lends it, and a model that answers
 * questions. The rule `updateAgent` applies to an agent's model, asked by the
 * places that choose a model for a single turn instead - an agent node that
 * names its own, and a research started with one. Issue #659.
 */
@Component
class WorkspaceChatModels(
    private val models: ModelService,
    private val sharing: WorkspaceSharing,
) {

    /** The model, or null where it is gone, is not this workspace's to use, or does not chat. */
    fun usable(workspaceId: Long, modelId: Long): LlmModelView? =
        models.model(modelId)?.takeIf { model ->
            model.kind == ModelKind.CHAT &&
                (model.workspaceId == workspaceId || sharing.providerLentTo(workspaceId, model.providerId))
        }
}
