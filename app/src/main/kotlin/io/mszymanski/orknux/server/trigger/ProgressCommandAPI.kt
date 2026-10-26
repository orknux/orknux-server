package io.mszymanski.orknux.server.trigger

import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The progress commands, for the chat composer's command menu: read from the
 * one list in [ProgressCommands] rather than spelled again in the interface.
 */
@Controller
class ProgressCommandAPI(private val access: WorkspaceAccess) {

    @QueryMapping
    fun progressCommands(): List<ProgressCommandView> {
        access.requireSignedIn()
        return ProgressCommands.ALL.map { (key, level) -> ProgressCommandView(key, level) }
    }
}

/** One progress command: the word after the marker, and the level it asks for. */
data class ProgressCommandView(val key: String, val level: Chattiness)
