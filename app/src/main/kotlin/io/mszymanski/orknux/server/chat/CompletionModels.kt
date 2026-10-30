package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import org.springframework.stereotype.Service

/**
 * Which model suggests words in a workspace: its own choice, or - only where it
 * is set to Default - the one chosen under Admin -> Settings.
 *
 * That one belongs to a workspace too, so choosing it sends what is typed in
 * every workspace on Default to that workspace's provider. The administrator's
 * decision, and the reason a workspace set to None or to a model of its own is
 * never overridden.
 */
@Service
class CompletionModels(
    private val settings: InstallationSettings,
    private val models: ModelService,
) {

    fun of(workspace: Workspace): Long? =
        workspace.completionModelId ?: if (workspace.completionModelDefault) installations() else null

    /** Null once the chosen model has been removed, which leaves nothing suggested rather than a failing call. */
    private fun installations(): Long? =
        settings.chatCompletionModel()?.takeIf { models.model(it)?.kind == ModelKind.CHAT }
}
