package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
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
 *
 * The key is a secret field like any other: its own encrypted copy or a
 * workspace variable secret, masked on the page and shown on request through
 * [revealWorkspaceSearchKey], which is recorded.
 */
@Controller
class SearchSettingsAPI(
    private val settings: SearchSettings,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun workspaceSearch(@Argument workspaceId: Long): WorkspaceSearchView {
        access.requireVisible(workspaceId)
        return view(workspaceId)
    }

    @MutationMapping
    fun setWorkspaceSearch(
        @Argument workspaceId: Long,
        @Argument engine: String?,
        @Argument apiKey: String?,
        @Argument apiKeyVariableId: Long?,
        @Argument composeAnswer: Boolean?,
    ): WorkspaceSearchView {
        access.requireAdministers(workspaceId)
        val before = settings.of(workspaceId)
        val engineBefore = before.engine
        val keyBefore = before.apiKey to before.apiKeyVariableId
        val composeBefore = before.composeAnswer

        settings.set(workspaceId, engine, apiKey, apiKeyVariableId, composeAnswer, currentUser())

        val after = settings.of(workspaceId)
        val changed = buildList {
            if (after.engine != engineBefore) add("engine ${SearchEngine.of(after.engine)?.title ?: after.engine}")
            if ((after.apiKey to after.apiKeyVariableId) != keyBefore) {
                add(
                    when {
                        after.apiKeyVariableId != null ->
                            "key read from ${settings.variableOf(after)?.name ?: "a workspace secret"}"
                        after.apiKey != null -> "key set"
                        else -> "key removed"
                    },
                )
            }
            if (after.composeAnswer != composeBefore) add(if (after.composeAnswer) "composed answer on" else "composed answer off")
        }
        if (changed.isNotEmpty()) {
            auditRecorder.record(
                workspaceId,
                WorkspaceAuditCategory.INTEGRATION,
                "Web search changed: ${changed.joinToString(", ")}",
            )
        }
        return view(workspaceId)
    }

    /**
     * Hands the stored key to the settings page, behind its Show - the way a
     * connection's credentials and a plugin's secret parameter are revealed,
     * under the same rule: reading a secret back takes a role that reads this
     * workspace's secrets, or one that administers it. Recorded, because a
     * secret read back is the one read worth knowing about.
     *
     * Null where the key is read from a variable: revealing that is
     * `revealVariable`, recorded against the variable.
     */
    @MutationMapping
    fun revealWorkspaceSearchKey(@Argument workspaceId: Long): String? {
        access.requireSecretReader(workspaceId)
        val key = settings.revealKey(workspaceId) ?: return null
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.INTEGRATION, "Web search key revealed")
        return key
    }

    private fun view(workspaceId: Long): WorkspaceSearchView {
        val held = settings.of(workspaceId)
        val variable = settings.variableOf(held)
        return WorkspaceSearchView(
            engine = held.engine,
            keySet = !held.apiKey.isNullOrBlank(),
            apiKeyVariableId = held.apiKeyVariableId,
            apiKeyVariableName = variable?.name,
            apiKeyVariableCatalog = variable?.catalog,
            apiKeyVariableMissing = held.apiKeyVariableId != null && variable == null,
            composeAnswer = held.composeAnswer,
            engines = SearchEngine.entries.map { EngineView(it.asked, it.title, it.describe()) },
        )
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

/** What a workspace searches with, as a screen reads it. */
data class WorkspaceSearchView(
    val engine: String,
    /** Whether the row holds a key of its own; false for one reading a variable. Never the key. */
    val keySet: Boolean,
    val apiKeyVariableId: Long?,
    val apiKeyVariableName: String?,
    val apiKeyVariableCatalog: String?,
    val apiKeyVariableMissing: Boolean,
    val composeAnswer: Boolean,
    val engines: List<EngineView>,
)

/** One index a workspace may choose, what it is called, and why it would. */
data class EngineView(val name: String, val title: String, val description: String)

/** The sentence a form shows beside each choice. */
fun SearchEngine.describe(): String = when (this) {
    SearchEngine.TAVILY ->
        "Built for models: results come back as cleaned, quotable content rather than the fragment a " +
            "search page shows. The default."

    SearchEngine.BRAVE ->
        "An independent index answering ranked results the way a search page does. Cheaper per query."
}
