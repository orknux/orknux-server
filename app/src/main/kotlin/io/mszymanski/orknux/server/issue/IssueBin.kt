package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.recyclebin.RecycleBin
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Base64

/**
 * A deleted issue, written down whole for the recycle bin and put back from it.
 *
 * An issue is more than its row: the comments, the files attached to it and to
 * them, its links, who watches it, what it relates to and its history all go
 * when the row does, by the database's cascades. So all of them are in the
 * snapshot, the files' bytes as base64 because the delete removes the files
 * themselves. Both doors that delete an issue - the browser's and an agent's -
 * call [bin] first, so neither is a way round the bin.
 *
 * Its news is not kept: a notification about an issue that was deleted and put
 * back says something that stopped being true in between.
 */
@Service
class IssueBin(
    private val issues: IssueRepository,
    private val types: IssueTypeRepository,
    private val attachments: IssueAttachmentRepository,
    private val links: IssueLinkRepository,
    private val relations: IssueRelationRepository,
    private val observers: IssueObserverRepository,
    private val events: IssueEventRepository,
    private val statuses: IssueStatusCatalogue,
    private val store: AttachmentStore,
    private val recycleBin: RecycleBin,
    private val mapper: ObjectMapper,
    private val entities: EntityManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Writes the issue into its workspace's bin. Called inside the delete's transaction, before anything is removed. */
    fun bin(issue: Issue) {
        val id = issue.id ?: return
        val encoder = Base64.getEncoder()
        val comments = issue.comments.toList()
        val snapshot = mapOf(
            "name" to "#${issue.number} ${issue.title}",
            "number" to issue.number,
            "title" to issue.title,
            "description" to issue.description,
            "status" to issue.status,
            "type" to issue.type?.name,
            "reporter" to issue.reporter,
            "assigneeKind" to issue.assignee?.kind?.name,
            "assigneeId" to issue.assignee?.id,
            "labels" to issue.labels.sorted(),
            "createdAt" to issue.createdAt.toString(),
            "lastModifiedAt" to issue.lastModifiedAt.toString(),
            "lastModifiedBy" to issue.lastModifiedBy,
            "lastCommentAt" to issue.lastCommentAt?.toString(),
            "comments" to comments.map {
                mapOf(
                    "author" to it.author,
                    "content" to it.content,
                    "createdAt" to it.createdAt.toString(),
                    "editedAt" to it.editedAt?.toString(),
                )
            },
            "attachments" to attachments.findByIssueIdOrderByUploadedAtAsc(id).mapNotNull { held ->
                // A file that cannot be read is left out rather than stopping the delete: the issue is still worth keeping.
                val bytes = runCatching { store.open(held.location).use { it.readBytes() } }
                    .onFailure { log.warn("Issue #{}'s attachment {} is not in the recycle bin: its file cannot be read", issue.number, held.filename, it) }
                    .getOrNull() ?: return@mapNotNull null
                mapOf(
                    "id" to held.id,
                    // Which comment it hangs on, by its place in the list: a comment put back has a new id.
                    "comment" to comments.indexOfFirst { it.id != null && it.id == held.commentId }.takeIf { it >= 0 },
                    "filename" to held.filename,
                    "contentType" to held.contentType,
                    "uploadedAt" to held.uploadedAt.toString(),
                    "uploadedBy" to held.uploadedBy,
                    "bytes" to encoder.encodeToString(bytes),
                )
            },
            "links" to links.findByIssueIdOrderByAddedAtAscIdAsc(id).map {
                mapOf("url" to it.url, "title" to it.title, "addedAt" to it.addedAt.toString(), "addedBy" to it.addedBy)
            },
            "observers" to observers.findByIssueIdOrderByAddedAtAscIdAsc(id).map {
                mapOf("kind" to it.kind.name, "observerId" to it.observerId, "addedAt" to it.addedAt.toString(), "addedBy" to it.addedBy)
            },
            "events" to events.findByIssueIdOrderByAtAscIdAsc(id).map {
                mapOf("kind" to it.kind.name, "actor" to it.actor, "was" to it.was, "became" to it.became, "at" to it.at.toString())
            },
            "relations" to relations.touching(id).map {
                mapOf(
                    // Which end this issue was, since a relation reads differently from each.
                    "from" to (it.issueId == id),
                    "other" to (if (it.issueId == id) it.otherIssueId else it.issueId),
                    "kind" to it.kind.name,
                    "linkedAt" to it.linkedAt.toString(),
                    "linkedBy" to it.linkedBy,
                )
            },
            // The tasks started from it, which the delete leaves pointing at nothing.
            "tasks" to entities.createQuery("select t.id from Task t where t.issueId = :id", java.lang.Long::class.java)
                .setParameter("id", id).resultList,
        )
        recycleBin.bin(issue, mapper.writeValueAsString(snapshot))
    }

    /**
     * Puts an issue back, as a new row carrying everything the old one had.
     *
     * It keeps its number where that is still free - a number is what people
     * and links say - and takes the next one where something has been opened
     * under it since. A status or a type the workspace no longer has becomes
     * the first status and no type, rather than refusing the restore over a
     * setting changed in the meantime.
     */
    @Transactional
    fun recreate(workspaceId: Long, snapshot: String): Issue {
        val tree = mapper.readTree(snapshot)
        val decoder = Base64.getDecoder()
        val asked = tree.path("number").asInt(0)
        val number = asked.takeIf { it > 0 && issues.findByWorkspaceIdAndNumber(workspaceId, it) == null }
            ?: (issues.lastNumber(workspaceId) + 1)
        val status = tree.text("status")?.takeIf { key -> statuses.of(workspaceId).any { it.key == key } }
            ?: statuses.initialOf(workspaceId)
        val assigneeKind = tree.text("assigneeKind")?.let { name -> AssigneeKind.entries.firstOrNull { it.name == name } }

        val issue = issues.saveAndFlush(
            Issue(
                workspaceId = workspaceId,
                number = number,
                title = tree.path("title").asString(),
                description = tree.text("description"),
                status = status,
                type = tree.text("type")?.let { types.named(workspaceId, it) },
                reporter = tree.text("reporter") ?: "system",
                assignee = assigneeKind?.let { Assignee(it, tree.text("assigneeId")) },
                labels = tree.path("labels").values().map { it.asString() }.toMutableSet(),
                comments = tree.path("comments").values().map {
                    IssueComment(
                        author = it.text("author") ?: "system",
                        content = it.text("content").orEmpty(),
                        createdAt = it.time("createdAt") ?: OffsetDateTime.now(),
                        editedAt = it.time("editedAt"),
                    )
                }.toMutableList(),
                createdAt = tree.time("createdAt") ?: OffsetDateTime.now(),
                lastModifiedAt = tree.time("lastModifiedAt") ?: OffsetDateTime.now(),
                lastModifiedBy = tree.text("lastModifiedBy") ?: "system",
                lastCommentAt = tree.time("lastCommentAt"),
            ),
        )
        val id = requireNotNull(issue.id)
        // In the order they were written, which is the order they were saved in.
        val comments = issue.comments.toList()

        // A file's address has its row's id in it, and the text may quote that address.
        val moved = mutableMapOf<Long, Long>()
        tree.path("attachments").values().forEach { held ->
            val bytes = decoder.decode(held.path("bytes").asString())
            val filename = held.text("filename") ?: "attachment"
            val made = attachments.save(
                IssueAttachment(
                    workspaceId = workspaceId,
                    issueId = id,
                    commentId = held.path("comment").takeIf { it.isNumber }?.asInt()?.let { comments.getOrNull(it)?.id },
                    filename = filename,
                    contentType = held.text("contentType") ?: "application/octet-stream",
                    sizeBytes = bytes.size.toLong(),
                    location = store.put(workspaceId, filename, bytes),
                    uploadedAt = held.time("uploadedAt") ?: OffsetDateTime.now(),
                    uploadedBy = held.text("uploadedBy").orEmpty(),
                ),
            )
            held.path("id").takeIf { it.isNumber }?.asLong()?.let { moved[it] = requireNotNull(made.id) }
        }
        if (moved.isNotEmpty()) {
            issue.description = issue.description?.let { readdressed(it, moved) }
            comments.forEach { it.content = readdressed(it.content, moved) }
            issues.save(issue)
        }

        tree.path("links").values().forEach {
            links.save(
                IssueLink(
                    issueId = id,
                    url = it.path("url").asString(),
                    title = it.text("title"),
                    addedAt = it.time("addedAt") ?: OffsetDateTime.now(),
                    addedBy = it.text("addedBy").orEmpty(),
                ),
            )
        }
        tree.path("observers").values().forEach { held ->
            val kind = AssigneeKind.entries.firstOrNull { it.name == held.text("kind") } ?: return@forEach
            observers.save(
                IssueObserver(
                    issueId = id,
                    kind = kind,
                    observerId = held.path("observerId").asString(),
                    addedAt = held.time("addedAt") ?: OffsetDateTime.now(),
                    addedBy = held.text("addedBy").orEmpty(),
                ),
            )
        }
        tree.path("events").values().forEach { held ->
            val kind = IssueEventKind.entries.firstOrNull { it.name == held.text("kind") } ?: return@forEach
            events.save(
                IssueEvent(
                    issueId = id,
                    kind = kind,
                    actor = held.text("actor") ?: "system",
                    was = held.text("was"),
                    became = held.text("became"),
                    at = held.time("at") ?: OffsetDateTime.now(),
                ),
            )
        }
        tree.path("relations").values().forEach { held ->
            val kind = IssueRelationKind.entries.firstOrNull { it.name == held.text("kind") } ?: return@forEach
            val other = held.path("other").takeIf { it.isNumber }?.asLong() ?: return@forEach
            // Only to an issue that is still there, in this workspace, and not already related some other way.
            val still = issues.findById(other).orElse(null)?.takeIf { it.workspaceId == workspaceId } ?: return@forEach
            val otherId = requireNotNull(still.id)
            if (relations.between(id, otherId) != null) return@forEach
            val from = held.path("from").asBoolean(true)
            relations.save(
                IssueRelation(
                    issueId = if (from) id else otherId,
                    otherIssueId = if (from) otherId else id,
                    kind = kind,
                    linkedAt = held.time("linkedAt") ?: OffsetDateTime.now(),
                    linkedBy = held.text("linkedBy").orEmpty(),
                ),
            )
        }
        val tasks = tree.path("tasks").values().filter { it.isNumber }.map { it.asLong() }
        if (tasks.isNotEmpty()) {
            // Only the ones still pointing nowhere: a task given to another issue since is that issue's.
            entities.createQuery("update Task t set t.issueId = :issue where t.id in :ids and t.issueId is null and t.workspaceId = :workspace")
                .setParameter("issue", id)
                .setParameter("ids", tasks)
                .setParameter("workspace", workspaceId)
                .executeUpdate()
        }
        return issue
    }

    private fun readdressed(text: String, moved: Map<Long, Long>): String =
        ADDRESS.replace(text) { match -> moved[match.groupValues[1].toLong()]?.let { "/api/issue-attachments/$it" } ?: match.value }

    private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.asString()

    private fun JsonNode.time(field: String): OffsetDateTime? =
        text(field)?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    private companion object {
        val ADDRESS = Regex("/api/issue-attachments/(\\d{1,18})(?!\\d)")
    }
}
