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
 * A picture an image node drew, kept with the run that drew it.
 *
 * The row is the record and the bytes are elsewhere, exactly as a task's picture
 * and a chat's attachment are: what is stored here is where they went, so a
 * different kind of storage would be a change in one class rather than a
 * migration of everything anybody has ever produced. [TaskPicture] is the twin
 * of this, and drew from the same [io.mszymanski.orknux.connector.model.ModelImageClient];
 * what differs is where it belongs - a task's picture hangs off a task, this
 * hangs off one run's one step, which is [nodeKey] beside [executionId].
 *
 * The pictures of one execution are its dedicated place: the run graph reads
 * them back by execution to show a preview under the node that drew each, and
 * the download link is one per row. Issue #333.
 */
@Entity
@Table(name = "execution_picture")
class ExecutionPicture(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    /** Which step of the run drew it, so the graph shows it under the right node. */
    @Column(name = "node_key", nullable = false, length = 64)
    val nodeKey: String,

    /**
     * Whose it is, and so both who may open it and where on the disk it sits.
     * On the row rather than read back through the run, the reason every other
     * attachment here gives: it decides access, and the storage files by workspace.
     */
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** What the node asked for, which is the picture's alt text. */
    @Column(nullable = false, columnDefinition = "text")
    val prompt: String,

    @Column(nullable = false, length = 255)
    val filename: String,

    @Column(name = "content_type", nullable = false, length = 120)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    /** Where the bytes are, as the storage that wrote them understands it. */
    @Column(name = "location", nullable = false, length = 1000)
    var location: String,

    @Column(name = "drawn_at", nullable = false)
    val drawnAt: OffsetDateTime = OffsetDateTime.now(),

    /** The image catalog it is filed in on Design's Images page; null is the workspace's default, which is where a run's picture lands. */
    @Column(name = "catalog_id")
    var catalogId: Long? = null,
)

interface ExecutionPictureRepository : JpaRepository<ExecutionPicture, Long> {

    /** How many one run has drawn, which is what bounds an agent asking for them. */
    fun countByExecutionId(executionId: Long): Long

    /** One run's pictures, oldest first, which is the order the graph shows them in. */
    fun findByExecutionIdOrderByDrawnAtAscIdAsc(executionId: Long): List<ExecutionPicture>

    /**
     * A workspace's pictures: what the Artifacts page lists, before the merge.
     *
     * Unpaged and unsorted, because that page holds two of these tables and
     * decides the order and the page across both of them - a page taken from
     * this one alone would be a page of half the artifacts.
     */
    fun findByWorkspaceId(workspaceId: Long): List<ExecutionPicture>
}

/**
 * Asked for a picture that is not here, or is not this caller's to see.
 *
 * Not a [io.mszymanski.orknux.server.graphql.Refusal], like [TaskPictureNotFound]
 * is not: the only thing that asks for these bytes is an `<img>` on the run
 * graph, so it is answered with a status, and the interface's one line about a
 * picture that is gone is written off the 404.
 */
class ExecutionPictureNotFoundException(val id: Long) : RuntimeException("No execution picture with id $id")
