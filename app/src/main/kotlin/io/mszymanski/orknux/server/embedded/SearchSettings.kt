package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
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
 * know the value is encrypted at all. It is never read back to a screen - the
 * page is told a key is set, and not what it is.
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

    @Convert(converter = SecretConverter::class)
    @Column(name = "api_key", length = SECRET_COLUMN_LENGTH)
    var apiKey: String? = null,

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
enum class SearchEngine(val asked: String) {
    /**
     * Built for models: what comes back per result is cleaned, quotable content
     * rather than the marketing fragment a search page shows. The default,
     * because the point of the results is a model reading them.
     */
    TAVILY("tavily"),

    /** An independent index answering ranked results the way a search page does. Cheaper per query. */
    BRAVE("brave"),
    ;

    companion object {
        fun of(named: String?): SearchEngine? =
            entries.firstOrNull { it.asked.equals(named?.trim(), ignoreCase = true) }

        fun offered(): String = entries.joinToString(", ") { it.asked }
    }
}

/** Reading and writing what a workspace searches with. */
@Service
class SearchSettings(private val rows: WorkspaceSearchRepository) {

    fun of(workspaceId: Long): WorkspaceSearch =
        rows.findByIdOrNull(workspaceId) ?: WorkspaceSearch(workspaceId = workspaceId)

    fun engineOf(workspaceId: Long): SearchEngine =
        SearchEngine.of(of(workspaceId).engine) ?: SearchEngine.TAVILY

    fun keyOf(workspaceId: Long): String? = of(workspaceId).apiKey?.takeIf { it.isNotBlank() }

    @Transactional
    fun set(workspaceId: Long, engine: String?, apiKey: String?, composeAnswer: Boolean?, by: String) {
        val held = of(workspaceId)
        engine?.let { named ->
            val wanted = SearchEngine.of(named)
                ?: throw IllegalArgumentException(
                    "There is no search engine called \"$named\"; this searches ${SearchEngine.offered()}.",
                )
            held.engine = wanted.asked
        }
        /*
         * An empty string clears the key and null leaves it alone, which is
         * what lets a page save the rest of the form without having to send a
         * secret it was never shown.
         */
        apiKey?.let { given -> held.apiKey = given.trim().ifEmpty { null } }
        composeAnswer?.let { held.composeAnswer = it }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        rows.save(held)
    }
}
