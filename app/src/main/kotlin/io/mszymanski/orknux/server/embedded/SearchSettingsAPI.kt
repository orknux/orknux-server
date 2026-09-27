package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller

/**
 * What a workspace searches with, on its own screen. Issue #510.
 *
 * Separate from the installation's settings because the answer is a
 * workspace's: the key is billed to whoever set it, and two teams in one
 * installation may reasonably search different indexes.
 */
@Controller
class SearchSettingsAPI(
    private val settings: SearchSettings,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    fun workspaceSearch(@Argument workspaceId: Long): WorkspaceSearchView {
        access.requireVisible(workspaceId)
        val held = settings.of(workspaceId)
        return WorkspaceSearchView(
            engine = held.engine,
            /*
             * Whether a key is set, never the key. The column encrypts it and
             * this is the other half of that promise: a screen that could read
             * one back is a screen that puts it in somebody's browser history.
             */
            keySet = held.apiKey?.isNotBlank() == true,
            composeAnswer = held.composeAnswer,
            engines = SearchEngine.entries.map { EngineView(it.asked, it.describe()) },
        )
    }

    @MutationMapping
    fun setWorkspaceSearch(
        @Argument workspaceId: Long,
        @Argument engine: String?,
        @Argument apiKey: String?,
        @Argument composeAnswer: Boolean?,
    ): WorkspaceSearchView {
        access.requireAdministers(workspaceId)
        settings.set(workspaceId, engine, apiKey, composeAnswer, currentUser())
        return workspaceSearch(workspaceId)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

/** What a workspace searches with, as a screen reads it. */
data class WorkspaceSearchView(
    val engine: String,
    /** Set or not; the value itself never leaves the database. */
    val keySet: Boolean,
    val composeAnswer: Boolean,
    val engines: List<EngineView>,
)

/** One index a workspace may choose, and why it would. */
data class EngineView(val name: String, val description: String)

/** The sentence a form shows beside each choice. */
fun SearchEngine.describe(): String = when (this) {
    SearchEngine.TAVILY ->
        "Built for models: results come back as cleaned, quotable content rather than the fragment a " +
            "search page shows. The default."

    SearchEngine.BRAVE ->
        "An independent index answering ranked results the way a search page does. Cheaper per query."
}
