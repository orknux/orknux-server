package io.mszymanski.orknux.server.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A file an agent saved, because it made something worth keeping.
 *
 * The third kind of artifact, and the first that is not a picture drawn from a
 * description. An agent that renders a diagram, formats a report or produces a
 * CSV has, until now, had nowhere to put it: it could say the text back to
 * whoever asked and that was the end of it. This is the place, and the
 * Artifacts page is where it shows up.
 *
 * A table of its own for the reason [io.mszymanski.orknux.server.task.TaskPicture]
 * is one: what it holds that the other two do not is that nobody drew it. There
 * is no prompt and no image model - there is a name the agent chose and bytes
 * it handed over - so folding it into either would mean a `prompt` column
 * holding something that is not one.
 *
 * The bytes are in the installation's own store, as both picture tables' are.
 * That is the rule the Artifacts page keeps: what is listed there is what this
 * server hosts, so Download has something to hand over and Delete has something
 * to remove.
 */
@Entity
@Table(name = "workspace_artifact")
class SavedArtifact(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Whose it is, and so both who may open it and where on the disk it sits.
     * On the row rather than read back through whatever made it, the reason
     * every other attachment here gives: it decides access, and the storage
     * files by workspace.
     */
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** What the agent called it, extension and all. */
    @Column(nullable = false, length = 255)
    val name: String,

    /**
     * What it is, in the agent's words.
     *
     * The caption on the Artifacts page, and what a search reads - the same job
     * a picture's prompt does. An agent saving a file is asked for one because
     * a grid of filenames says nothing about which is which.
     */
    @Column(nullable = false, columnDefinition = "text")
    val description: String,

    @Column(name = "content_type", nullable = false, length = 120)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    /** Where the bytes are, as the storage that wrote them understands it. */
    @Column(name = "location", nullable = false, length = 1000)
    var location: String,

    /**
     * Which agent saved it, by the name somebody chose for it.
     *
     * A name rather than an id because it is read, not followed: the Artifacts
     * page says where a file came from, and an agent deleted afterwards should
     * not turn its files into rows pointing at nothing.
     */
    @Column(name = "saved_by", nullable = false, length = 255)
    val savedBy: String,

    @Column(name = "saved_at", nullable = false)
    val savedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface SavedArtifactRepository : JpaRepository<SavedArtifact, Long> {

    /**
     * A workspace's saved files: what the Artifacts page lists, before the merge.
     *
     * Unpaged and unsorted, because that page holds three of these tables and
     * decides the order and the page across all of them - a page taken from
     * this one alone would be a page of a third of the artifacts.
     */
    fun findByWorkspaceId(workspaceId: Long): List<SavedArtifact>

    /** How many this workspace holds, which is what the ceiling is compared against. */
    fun countByWorkspaceId(workspaceId: Long): Long
}
