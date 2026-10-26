package io.mszymanski.orknux.server.live

import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * What an editor that saves as it goes reads beside its live room. Issue #683.
 *
 * Open to anybody signed in: it is the pause before a save, said to everybody
 * who edits, and the installation's other settings stay an administrator's.
 */
@Controller
class LiveSettingsAPI(private val settings: InstallationSettings) {

    /** Admin Settings' Workflow Autosave: one pause for every editor, so they all feel the same. */
    @QueryMapping
    fun editorAutosaveMillis(): Int = settings.workflowAutosaveMillis()
}
