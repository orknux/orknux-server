package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.workspace.Workspace
import org.springframework.stereotype.Service

/**
 * Which model answers Ask AI in a workspace: its own choice, or - where it is
 * set to Any - the first chat model it has that is switched on.
 *
 * First by the order the models page lists them in, provider and then name, so
 * the answer is one somebody can find by looking rather than whichever row the
 * database returned. Asked on every question, so a model added, switched off or
 * removed changes the answer at the next one, and a workspace with no chat
 * model at all offers no button rather than one that fails on every press.
 */
@Service
class QuickChatModels(private val models: ModelService) {

    fun of(workspace: Workspace): Long? {
        workspace.quickChatModelId?.let { return it }
        if (!workspace.quickChatModelAny) return null
        return models.models(requireNotNull(workspace.id))
            .firstOrNull { it.kind == ModelKind.CHAT && it.enabled }
            ?.id
    }
}
