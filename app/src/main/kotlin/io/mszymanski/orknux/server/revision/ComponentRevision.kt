package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * What turns a component's state into a version of it.
 *
 * The rule the owner gave, in one place rather than as a branch at every call
 * site: *if a component has a draft, only publishing releases a version; if it
 * has no draft, a save is one.* Their analogy was commits and tags — the
 * changes going onto main are snapshots and are not versions, and the version
 * is the tag.
 *
 * It is a property of the kind rather than a check somewhere, so the day a
 * function or a tool gains a draft the rule is already right: the kind changes
 * its answer here and the recorder stops writing on save without a caller
 * changing.
 */
enum class ComponentRelease {

    /**
     * There is nothing to press but Save, so every save is a version.
     *
     * The four kinds that are edited in place. A save is the only moment their
     * state changes at all, so it is the only moment there is to record.
     */
    SAVE,

    /**
     * There is a draft, and only publishing releases a version.
     *
     * A draft is not a version — it is what somebody is in the middle of, and
     * recording every keystroke's worth of it would fill the history with
     * states nobody chose. What was published is what ran, and that is what a
     * restore has to be able to bring back.
     */
    PUBLISH,
}

/**
 * A kind of component that has a history, and what gives it one.
 *
 * [WORKFLOW] is in this enum and is not in [ComponentRevision]'s table: its
 * versions are its publications, which `workflow_publication` already holds
 * exactly and which the runner reads. Writing a second copy of a published
 * graph into a snapshot column would be two accounts of the same fact, and the
 * one the history showed would be the one nothing runs.
 */
enum class ComponentRevisionKind(val release: ComponentRelease) {
    FUNCTION(ComponentRelease.SAVE),
    TOOL(ComponentRelease.SAVE),
    SKILL(ComponentRelease.SAVE),
    AGENT(ComponentRelease.SAVE),

    /** The one kind with a draft today. Publishing is what versions it. */
    WORKFLOW(ComponentRelease.PUBLISH),
    ;

    /** Whether this kind's history lives in [ComponentRevision]'s own table. */
    val stored: Boolean get() = this != WORKFLOW
}

/**
 * A component as it was before something replaced it.
 *
 * The state recorded is the *displaced* one, written just before a save
 * overwrites it, and stamped with when that state was itself saved rather than
 * with the moment it stopped being current. Two things follow, and both are
 * why it is done this way rather than by copying every new state in.
 *
 * The live row is the newest version, always, with nothing to keep in step —
 * so a history is the rows here with the component itself on top, and the two
 * cannot disagree. And a component that existed before this table did gets its
 * first entry the first time somebody edits it: no backfill to write, and no
 * row claiming to hold a state that was never saved.
 *
 * Deleting the component takes its revisions with it, by hand rather than by a
 * foreign key — [component_id] points into whichever of four tables [kind]
 * names, so there is no column to reference.
 */
@Entity
@Table(name = "component_revision")
class ComponentRevision(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: ComponentRevisionKind,

    @Column(name = "component_id", nullable = false)
    val componentId: Long,

    /** What it was called then; a rename is a thing a history should show. */
    @Column(nullable = false, length = 120)
    val name: String,

    /** When this state was saved — the component's own stamp, not this row's. */
    @Column(name = "saved_at", nullable = false)
    val savedAt: OffsetDateTime,

    @Column(name = "saved_by", nullable = false, length = 120)
    val savedBy: String,

    /**
     * When it stopped being current, which is what retention measures.
     *
     * Deliberately not [savedAt]: a prompt written a year ago and replaced this
     * morning is a fortnight of history, not a year-old row to sweep away the
     * moment it is written.
     */
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: OffsetDateTime = OffsetDateTime.now(),

    /** The whole component, as [ComponentSnapshot] writes it. */
    @Column(nullable = false, columnDefinition = "text")
    val snapshot: String,
)

interface ComponentRevisionRepository : JpaRepository<ComponentRevision, Long> {

    /**
     * One component's history, newest first and only as much of it as was
     * asked for, without what each revision held.
     *
     * Paged rather than whole, because a tool edited fifty times in an
     * afternoon has fifty rows of source in it and a screen shows ten. And a
     * list is read for its dates and its names, while every row of it read as
     * an entity carried the whole snapshot - an agent's prompt, a tool's source -
     * for a screen that shows none of it. The snapshot is read when one row is
     * opened. Issue #616.
     */
    @Query(
        """
        SELECT new io.mszymanski.orknux.server.revision.RevisionSummary(
          r.id, r.kind, r.componentId, r.name, r.savedAt, r.savedBy, r.recordedAt)
        FROM ComponentRevision r
        WHERE r.kind = :kind AND r.componentId = :componentId
        ORDER BY r.recordedAt DESC, r.id DESC
        """,
    )
    fun summaries(
        @Param("kind") kind: ComponentRevisionKind,
        @Param("componentId") componentId: Long,
        pageable: Pageable,
    ): List<RevisionSummary>

    /** Everything kept about a component, which is what deleting it removes. */
    fun deleteByKindAndComponentId(kind: ComponentRevisionKind, componentId: Long)

    /** What the retention sweep takes: history that has been history too long. */
    @Query("select r.id from ComponentRevision r where r.recordedAt < :before")
    fun idsRecordedBefore(before: OffsetDateTime): List<Long>
}

class ComponentRevisionNotFoundException(val id: Long) : RuntimeException("No revision with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/**
 * Somebody asked to restore a component that is no longer there.
 *
 * Its revisions outlive it only for as long as it takes the delete to remove
 * them, so this is the narrow case of a revision read just before a delete and
 * restored just after.
 */
class RevisionComponentGoneException(kind: ComponentRevisionKind, id: Long) :
    RuntimeException("The ${kind.name.lowercase()} this revision belongs to (id $id) no longer exists")

/**
 * A revision was asked to be restored onto a component nothing may edit.
 *
 * A plugin's function is the case: the plugin that declared it is the only
 * thing that changes it, and putting an old snapshot back would be this
 * application overwriting what a plugin owns.
 */
class RevisionNotRestorableException(val name: String) :
    RuntimeException("\"$name\" is not editable here, so a revision of it cannot be put back"), Refusal {

    override val arguments get() = mapOf("name" to name)
}


/** One line of a component's history, as read for a list: everything but the snapshot. */
data class RevisionSummary(
    val id: Long,
    val kind: ComponentRevisionKind,
    val componentId: Long,
    val name: String,
    val savedAt: OffsetDateTime,
    val savedBy: String,
    val recordedAt: OffsetDateTime,
)
