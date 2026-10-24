package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.connector.security.HeldCredential
import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import io.mszymanski.orknux.connector.security.SecretReferences
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Which index a workspace searches, and what it authenticates with. Issue #510.
 *
 * A workspace's own, not the installation's, because the key is billed to
 * whoever set it and two teams in one installation may reasonably answer
 * differently.
 *
 * **The key is encrypted by the column**, through the converter connections and
 * plugin parameters already use. A converter rather than encrypting at the call
 * site: there is no code path that can forget it, and nothing above this has to
 * know the value is encrypted at all. Or the key is not here at all and the row
 * names a workspace variable secret to read it from - the same per-field choice
 * every other credential in the product offers, through [SecretReferences].
 */
@Entity
@Table(name = "workspace_search")
class WorkspaceSearch(
    @Id
    @Column(name = "workspace_id")
    val workspaceId: Long = 0,

    /** `tavily` or `brave`; see [SearchEngine]. */
    @Column(name = "engine", nullable = false, length = 16)
    var engine: String = SearchEngine.TAVILY.asked,

    /** Null when the key is read from [apiKeyVariableId] instead; the two are exclusive. */
    @Convert(converter = SecretConverter::class)
    @Column(name = "api_key", length = SECRET_COLUMN_LENGTH)
    var apiKey: String? = null,

    /**
     * The workspace variable secret the key is read from at the moment of a
     * search, or null when the row keeps its own copy. By id, as every other
     * secret field is since #244, so renaming the variable disturbs nothing.
     */
    @Column(name = "api_key_variable_id")
    var apiKeyVariableId: Long? = null,

    /**
     * Whether tavily composes a short answer over the results as well, which
     * costs more than a plain search. Off unless somebody asks for it.
     */
    @Column(name = "compose_answer", nullable = false)
    var composeAnswer: Boolean = false,

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "orknux",
)

interface WorkspaceSearchRepository : JpaRepository<WorkspaceSearch, Long>

/**
 * The indexes a workspace may search.
 *
 * Two, and they behave differently enough that the choice is worth making
 * rather than inheriting - which is why it is a setting and not a constant.
 */
enum class SearchEngine(val asked: String, val title: String) {
    /**
     * Built for models: what comes back per result is cleaned, quotable content
     * rather than the marketing fragment a search page shows. The default,
     * because the point of the results is a model reading them.
     */
    TAVILY("tavily", "Tavily"),

    /** An independent index answering ranked results the way a search page does. Cheaper per query. */
    BRAVE("brave", "Brave"),
    ;

    companion object {
        fun of(named: String?): SearchEngine? =
            entries.firstOrNull { it.asked.equals(named?.trim(), ignoreCase = true) }

        fun offered(): String = entries.joinToString(", ") { it.asked }
    }
}

/** Reading and writing what a workspace searches with. */
@Service
class SearchSettings(
    private val rows: WorkspaceSearchRepository,
    private val references: SecretReferences,
) {

    fun of(workspaceId: Long): WorkspaceSearch =
        rows.findByIdOrNull(workspaceId) ?: WorkspaceSearch(workspaceId = workspaceId)

    fun engineOf(workspaceId: Long): SearchEngine =
        SearchEngine.of(of(workspaceId).engine) ?: SearchEngine.TAVILY

    /**
     * The key, at the moment a search wants it: the row's own copy, or the
     * value of the variable it reads. The ways there is none are told apart,
     * so the refusal can say which one it is.
     */
    fun keyOf(workspaceId: Long): HeldCredential = of(workspaceId).let {
        references.read(workspaceId, it.apiKey, it.apiKeyVariableId)
    }

    /** The variable the key is read from, for a screen that names it; null for an own copy or a broken reference. */
    fun variableOf(held: WorkspaceSearch): HeldSecret? = references.describe(held.workspaceId, held.apiKeyVariableId)

    /**
     * The row's own key, for the settings page's Show. Null for a row reading a
     * variable: revealing that is `revealVariable`, recorded against the
     * variable rather than here. Null too for a key still sealed with a secret
     * key this installation no longer has - the envelope is not the key.
     */
    fun revealKey(workspaceId: Long): String? {
        val held = rows.findByIdOrNull(workspaceId) ?: return null
        if (held.apiKeyVariableId != null) return null
        return references.read(workspaceId, held.apiKey, null).credential
    }

    /** Whether this workspace's search key reads [variableId]; what the variable's delete guard asks. */
    fun reads(workspaceId: Long, variableId: Long): Boolean =
        rows.findByIdOrNull(workspaceId)?.apiKeyVariableId == variableId

    @Transactional
    fun set(
        workspaceId: Long,
        engine: String?,
        apiKey: String?,
        apiKeyVariableId: Long?,
        composeAnswer: Boolean?,
        by: String,
    ) {
        val held = of(workspaceId)
        engine?.let { named ->
            val wanted = SearchEngine.of(named)
                ?: throw IllegalArgumentException(
                    "There is no search engine called \"$named\"; this searches ${SearchEngine.offered()}.",
                )
            held.engine = wanted.asked
        }
        /*
         * A value given keeps a copy and drops any reference; a variable given
         * reads it and drops any copy; nothing given leaves the key alone, which
         * is what a masked box sends, and is what lets a page save the rest of
         * the form without a secret it was never shown. An empty value clears
         * both. A value and a variable together is refused by the binding.
         */
        val own = apiKey?.trim()
        val variable = references.bind(workspaceId, apiKeyVariableId, own?.ifEmpty { null })
        when {
            variable != null -> {
                held.apiKeyVariableId = variable
                held.apiKey = null
            }

            own != null -> {
                held.apiKey = own.ifEmpty { null }
                held.apiKeyVariableId = null
            }
        }
        composeAnswer?.let { held.composeAnswer = it }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        rows.save(held)
    }
}
