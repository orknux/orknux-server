package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * The statuses a workspace's issues move through, and who may change the list.
 *
 * Per workspace, like the types beside it and for the same reason: one team is
 * done at Closed and the next wants Review, Released and Won't fix, and a list
 * fixed in code is a list somebody works around with labels. Reading is for
 * anybody who can see the workspace, because the filter bar and the status
 * button both draw it; changing it is for whoever administers the workspace,
 * exactly as the other settings on that page are.
 *
 * Three rules hold the list together, and each is refused in words rather than
 * quietly worked around. There is exactly one initial status - where a new
 * issue lands - and it can be neither removed nor made to count as closed. At
 * least one status counts as closed, or nothing could ever be done. And a status
 * that issues hold cannot be removed while they hold it, for the reason a type
 * in use cannot: the number is what tells an administrator whether they meant
 * it, and the filter is one click from showing which issues.
 */
@Controller
class IssueStatusAPI(
    private val statuses: IssueStatusRepository,
    private val issues: IssueRepository,
    private val catalogue: IssueStatusCatalogue,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
) {

    /**
     * A workspace's statuses in order, with how many issues hold each.
     *
     * Not read-only, deliberately: a workspace whose statuses were never written
     * down gets them written here, so the page that administers them has rows
     * with ids to administer. See [IssueStatusCatalogue.ensure].
     */
    @QueryMapping
    @Transactional
    fun issueStatuses(@Argument workspaceId: Long): List<IssueStatusView> {
        access.requireVisible(workspaceId)
        return describeAll(workspaceId, catalogue.ensure(workspaceId))
    }

    @MutationMapping
    @Transactional
    fun addIssueStatus(
        @Argument workspaceId: Long,
        @Argument key: String,
        @Argument label: String,
        @Argument color: String?,
    ): IssueStatusView {
        access.requireAdministers(workspaceId)
        val held = catalogue.ensure(workspaceId)

        val wanted = cleanKey(key)
        val name = cleanLabel(label)
        val paint = cleanColor(color)
        if (held.any { it.key.equals(wanted, ignoreCase = true) }) throw IssueStatusKeyTakenException(wanted)

        val made = statuses.save(
            IssueStatusDefinition(
                workspaceId = workspaceId,
                key = wanted,
                label = name,
                color = paint,
                // At the end, and before nothing: a new status is somewhere an
                // administrator drags into place, not somewhere issues start.
                position = (held.maxOfOrNull { it.position } ?: -1) + 1,
            ),
        )
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue status $name ($wanted) added")
        return describe(made, 0)
    }

    /**
     * Changes what a status is called, what colour it wears, or whether it
     * counts as done. Each is left alone when absent; an empty colour clears it.
     *
     * The key is not among these on purpose. Two hundred issues hold it, and a
     * key that could change is a label with a second spelling.
     */
    @MutationMapping
    @Transactional
    fun updateIssueStatus(
        @Argument id: Long,
        @Argument label: String?,
        @Argument color: String?,
        @Argument closed: Boolean?,
    ): IssueStatusView {
        val held = statuses.findByIdOrNull(id) ?: throw IssueStatusNotFoundException(id)
        access.requireAdministers(held.workspaceId)
        val all = statuses.findByWorkspaceIdOrderByPositionAscIdAsc(held.workspaceId)

        val was = held.label
        label?.let { held.label = cleanLabel(it) }
        color?.let { held.color = cleanColor(it) }
        closed?.let { wanted ->
            if (wanted && held.initial) throw IssueStatusInitialClosedException(held.label)
            if (!wanted && held.closed && all.none { it.closed && it.id != held.id }) {
                throw IssueStatusLastClosedException(held.label)
            }
            if (wanted != held.closed) {
                audit.record(
                    held.workspaceId,
                    WorkspaceAuditCategory.WORKSPACE,
                    if (wanted) "Issue status ${held.label} now counts as closed" else "Issue status ${held.label} no longer counts as closed",
                )
            }
            held.closed = wanted
        }
        if (was != held.label) {
            audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue status $was renamed to ${held.label}")
        } else if (color != null && closed == null) {
            audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue status ${held.label} recoloured")
        }

        val saved = statuses.save(held)
        return describe(saved, issues.countByWorkspaceIdAndStatus(saved.workspaceId, saved.key))
    }

    /**
     * Puts the statuses in the order given, which has to name each of them once.
     *
     * The whole list rather than one move, because "move up" and "move down"
     * are what the page offers and what the page holds is the list: sending the
     * list is one round trip that cannot leave two rows on the same position.
     */
    @MutationMapping
    @Transactional
    fun reorderIssueStatuses(@Argument workspaceId: Long, @Argument ids: List<Long>): List<IssueStatusView> {
        access.requireAdministers(workspaceId)
        val held = catalogue.ensure(workspaceId).associateBy { requireNotNull(it.id) }
        if (ids.toSet() != held.keys || ids.size != held.size) throw IssueStatusReorderException()

        val ordered = ids.mapIndexed { at, id ->
            requireNotNull(held[id]).apply { position = at }
        }
        statuses.saveAll(ordered)
        audit.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue statuses reordered: ${ordered.joinToString(", ") { it.label }}",
        )
        return describeAll(workspaceId, ordered)
    }

    /**
     * Takes a status out of the list, or says why it cannot go.
     *
     * Three refusals, each naming the rule: not the one new issues start in, not
     * the last that counts as closed, and not one that issues hold - which says
     * how many, so an administrator can decide whether they meant it.
     */
    @MutationMapping
    @Transactional
    fun removeIssueStatus(@Argument id: Long): Boolean {
        val held = statuses.findByIdOrNull(id) ?: throw IssueStatusNotFoundException(id)
        access.requireAdministers(held.workspaceId)

        if (held.initial) throw IssueStatusInitialException(held.label)
        val others = statuses.findByWorkspaceIdOrderByPositionAscIdAsc(held.workspaceId).filter { it.id != held.id }
        if (held.closed && others.none { it.closed }) throw IssueStatusLastClosedException(held.label)
        val carried = issues.countByWorkspaceIdAndStatus(held.workspaceId, held.key)
        if (carried > 0) throw IssueStatusInUseException(held.label, carried)

        statuses.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue status ${held.label} (${held.key}) removed")
        return true
    }

    /** Upper-cased and checked against the shape a key has; the case it was typed in is not an error. */
    private fun cleanKey(key: String): String {
        val wanted = key.trim().uppercase()
        if (wanted.isEmpty() || wanted.length > IssueStatuses.KEY_LENGTH || !IssueStatuses.KEY.matches(wanted)) {
            throw IssueStatusKeyInvalidException(key.trim())
        }
        return wanted
    }

    private fun cleanLabel(label: String): String =
        label.trim().takeIf { it.isNotEmpty() && it.length <= IssueStatuses.LABEL_LENGTH }
            ?: throw IssueStatusLabelInvalidException()

    /** Trimmed; empty is no colour; anything that could not be one colour is refused. */
    private fun cleanColor(color: String?): String? {
        val wanted = color?.trim()?.ifEmpty { null } ?: return null
        if (wanted.length > IssueStatuses.COLOR_LENGTH || !IssueStatuses.COLOR.matches(wanted)) {
            throw IssueStatusColorInvalidException(wanted)
        }
        return wanted
    }

    private fun describeAll(workspaceId: Long, held: List<IssueStatusDefinition>): List<IssueStatusView> {
        val carried = issues.statusCounts(workspaceId).associate { it.status to it.issues }
        return held.map { describe(it, carried[it.key] ?: 0L) }
    }

    private fun describe(status: IssueStatusDefinition, inUse: Long) = IssueStatusView(
        id = requireNotNull(status.id),
        key = status.key,
        label = status.label,
        color = status.color,
        position = status.position,
        initial = status.initial,
        closed = status.closed,
        inUse = inUse.toInt(),
    )
}

/** A status as the settings page, the filter bar and the status button read it. */
data class IssueStatusView(
    val id: Long,
    val key: String,
    val label: String,
    val color: String?,
    val position: Int,
    val initial: Boolean,
    val closed: Boolean,
    /** How many issues in this workspace hold it. */
    val inUse: Int,
)
