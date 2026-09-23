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
 * Something a run said out loud, kept with the run that said it.
 *
 * Issue #264. The product spoke in a chat and nowhere else, so a run that wanted
 * to hand somebody audio - a summary to listen to on the way in, a message for a
 * channel where nobody reads - had no way to make any. Every other kind of
 * output a run produces is a file it leaves behind, and this was the missing
 * one.
 *
 * A row of its own beside [ExecutionPicture] rather than a column on it. The two
 * are the same kind of thing - bytes a step produced, shown under the node that
 * produced them - but they are shown by different controls and carry different
 * facts: a picture has alt text, this has the words it read and how long it
 * runs. Folding them together would be a table whose columns are half empty
 * whichever kind a row is.
 */
@Entity
@Table(name = "execution_speech")
class ExecutionSpeech(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    /** Which step said it, so the graph shows it under the right node. */
    @Column(name = "node_key", nullable = false, length = 64)
    val nodeKey: String,

    /**
     * Whose it is, and so both who may open it and where on the disk it sits.
     * On the row for the reason every other attachment here gives: it decides
     * access, and the storage files by workspace.
     */
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /**
     * The words it read.
     *
     * Kept because audio cannot be read at a glance: a run that produced four
     * of these is four identical rows without it, and nobody opens a list they
     * cannot skim.
     */
    @Column(nullable = false, columnDefinition = "text")
    val said: String,

    @Column(nullable = false, length = 255)
    val filename: String,

    @Column(name = "content_type", nullable = false, length = 120)
    val contentType: String,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /** Where the bytes are, as the storage that wrote them understands it. */
    @Column(name = "location", nullable = false, length = 1000)
    val location: String,

    @Column(name = "spoken_at", nullable = false)
    val spokenAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ExecutionSpeechRepository : JpaRepository<ExecutionSpeech, Long> {

    /** How many one run has said, which is what bounds a node asking for them. */
    fun countByExecutionId(executionId: Long): Long

    /** One run's audio, oldest first, which is the order the graph shows them in. */
    fun findByExecutionIdOrderBySpokenAtAscIdAsc(executionId: Long): List<ExecutionSpeech>
}

/**
 * Asked for audio that is not here, or is not this caller's to hear.
 *
 * Not a [io.mszymanski.orknux.server.graphql.Refusal], for the reason
 * [ExecutionPictureNotFoundException] is not: the only thing that asks for these
 * bytes is an `<audio>` on the run graph, so it is answered with a status.
 */
class ExecutionSpeechNotFoundException(val id: Long) : RuntimeException("No execution speech with id $id")
