package io.mszymanski.orknux.server.task

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A picture a task drew.
 *
 * The row is the record and the bytes are elsewhere, exactly as a chat's
 * attachment and an issue's are: what is stored is where they went, so a second
 * kind of storage would be a change in one class rather than a migration of
 * everything anybody has ever produced.
 *
 * What it holds that the other two do not is [prompt], and that is why it is a
 * table of its own. Nothing uploads a file to a task; every row here is
 * something an agent drew from a description, and the description is what makes
 * a picture identifiable without opening it - the alt text under the outcome,
 * and the name of the file if it is saved.
 */
@Entity
@Table(name = "task_picture")
class TaskPicture(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "task_id", nullable = false)
    val taskId: Long,

    /**
     * Whose it is, and so both who may open it and where on the disk it sits.
     *
     * On the row rather than read back through the task, for the reason every
     * other attachment here gives: it is what decides access, and the storage
     * files by workspace.
     */
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** What the agent asked for, which is the picture's alt text. */
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

    /** The image catalog it is filed in on Design's Images page; null is the workspace's default, which is where a task's picture lands. */
    @Column(name = "catalog_id")
    var catalogId: Long? = null,
)

interface TaskPictureRepository : JpaRepository<TaskPicture, Long> {

    /** One task's pictures, in the order they were drawn, which is how they are shown. */
    fun findByTaskIdOrderByDrawnAtAscIdAsc(taskId: Long): List<TaskPicture>

    /** How many this task has drawn, which is what the ceiling is compared against. */
    fun countByTaskId(taskId: Long): Long

    /**
     * A workspace's pictures: what the Artifacts page lists, before the merge.
     *
     * Unpaged and unsorted, because that page holds this table and the runs'
     * and decides the order and the page across both - a page taken from this
     * one alone would be a page of half the artifacts.
     */
    fun findByWorkspaceId(workspaceId: Long): List<TaskPicture>
}

/**
 * Asked for a picture that is not here, or is not this caller's to see.
 *
 * Not a [io.mszymanski.orknux.server.graphql.Refusal], unlike nearly every
 * other exception in this package, because it is never said to a person in a
 * sentence: the only thing that asks for these bytes is an `<img>` in the
 * outcome's markdown, so what it is answered with is a status, and the
 * interface's one line about a picture that is gone is written off the 404.
 */
class TaskPictureNotFoundException(val id: Long) : RuntimeException("No task picture with id $id")
