package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.task.TaskPicture
import io.mszymanski.orknux.server.task.TaskPictureRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.page
import io.mszymanski.orknux.server.workspace.pageRequest
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything a workspace has drawn, in one place.
 *
 * A picture was reachable from the thing that drew it and nowhere else - a run
 * for an image node's, a task for an agent's - which is fine while somebody
 * remembers which run or which task that was, and useless afterwards. They
 * accumulate, they take up disk, and the only way to find one was to go through
 * runs opening steps. So they are listed here, newest first, with the two
 * things somebody wants from a file they are looking at: a copy of it, and the
 * ability to be rid of it.
 *
 * Two tables, one list. They hold the same seven columns and differ only in
 * what they hang off, so what reaches this page is one kind of thing with two
 * origins rather than two lists somebody has to check in turn.
 *
 * Only what this installation hosts. Both tables carry a `location` in the
 * [AttachmentStore] and are served from this server's own address, which is
 * what makes Download and Delete mean anything here: the bytes are ours to
 * hand over and ours to remove. A file living somewhere else - one uploaded to
 * Slack, a picture behind a link a plugin produced - is that service's, and
 * listing it would offer a Delete this server cannot perform and a copy it
 * does not have. So a third source belongs here only if its bytes are in the
 * store; anything else is a link, and a link belongs where it was posted.
 */
@Controller
class WorkspaceArtifactAPI(
    private val pictures: ExecutionPictureRepository,
    private val taskPictures: TaskPictureRepository,
    private val saved: SavedArtifactRepository,
    private val designImages: io.mszymanski.orknux.server.design.DesignImageRepository,
    private val store: AttachmentStore,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    /** A picture's layers in the image editor, which go with it. */
    private val layers: io.mszymanski.orknux.server.design.DesignImageLayers,
    /** The image catalogs the Images page files its pictures in. Lazy: the catalogs are Design's, and Design reaches here. */
    @param:org.springframework.context.annotation.Lazy private val catalogs: io.mszymanski.orknux.server.design.DesignCatalogs,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param search what to look for in the prompt and the filename, or null for everything.
     *
     * Merged and paged here rather than by the database, which cannot page
     * across two tables without a union this does not have. The cost is reading
     * a workspace's rows to sort them; the alternative is either two paged
     * lists somebody flips between, or one that silently omits the older half.
     * Both were worse than the read.
     *
     * The search is applied before the merge so neither half can crowd the
     * other out of the page, and it is asked of the server rather than sieved
     * in the browser because the list is paged: narrowing what arrived on page
     * one would hide matches on page four and call that "no results".
     */
    @QueryMapping
    fun workspaceArtifacts(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument drawn: Boolean?,
        @Argument catalogId: Long?,
    ): ArtifactPage {
        access.requireVisible(workspaceId)
        val looking = search?.trim().orEmpty()
        val keptFiles = if (drawn == true) emptyList() else saved.findByWorkspaceId(workspaceId).map(::describe)

        /*
         * Which image catalog each drawn picture is in - asked only by Design's
         * Images page (drawn), whose catalogs these are; the Artifacts page
         * lists files and knows nothing of them.
         */
        val filedIn = mutableMapOf<String, Long?>()
        val drawnPictures =
            pictures.findByWorkspaceId(workspaceId).map { describe(it).also { view -> filedIn[view.id] = it.catalogId } } +
                taskPictures.findByWorkspaceId(workspaceId).map { describe(it).also { view -> filedIn[view.id] = it.catalogId } } +
                designImages.findByWorkspaceIdAndVersionOfIsNull(workspaceId).let { held ->
                    // Which of them are hosted as artifacts, read once for the list rather than once a picture.
                    val hosted = if (held.isEmpty()) emptyMap() else hostedIn(workspaceId)
                    held.map { describe(it).copy(hostedAs = hosted[it.id]).also { view -> filedIn[view.id] = it.catalogId } }
                }
        val filed: (List<ArtifactView>) -> List<ArtifactView> = if (drawn != true) {
            { it }
        } else {
            val fallback = requireNotNull(catalogs.defaultOf(workspaceId, io.mszymanski.orknux.server.design.DesignCatalogKind.IMAGE).id)
            val names = catalogs.names(workspaceId, io.mszymanski.orknux.server.design.DesignCatalogKind.IMAGE)
            ;{ listed ->
                listed.mapNotNull { view ->
                    val catalog = filedIn[view.id] ?: fallback
                    if (catalogId != null && catalog != catalogId) null else view.copy(catalogId = catalog.toString(), catalogName = names[catalog])
                }
            }
        }

        val all = (drawnPictures + keptFiles)
            .filter { looking.isEmpty() || it.matches(looking) }
            .let(filed)
            .sortedWith(compareByDescending<ArtifactView> { it.at }.thenByDescending { it.id })
            .let { listed ->
                // Which of them carry a draft: unsaved changes kept until Save or Discard.
                val drafts = draftsIn(workspaceId)
                if (drafts.isEmpty()) listed else listed.map { it.withDraft(drafts[it.id]) }
            }

        val pageable = pageRequest(page, size, Sort.unsorted())
        return ArtifactPage(
            content = all.page(pageable),
            page = pageable.pageNumber,
            size = pageable.pageSize,
            totalElements = all.size,
            totalPages = if (all.isEmpty()) 0 else (all.size + pageable.pageSize - 1) / pageable.pageSize,
        )
    }

    /**
     * One artifact, by the id the list gave it.
     *
     * So a link to one is a link: the page can be opened at an artifact rather
     * than opened at the list and clicked. Without this the only way to find
     * one was to page through until it appeared, which is not something an
     * address can do on somebody's behalf.
     *
     * Null for one that is not here or not this caller's to see - the same
     * answer for both, so a number over HTTP cannot be used to count what
     * other teams have produced.
     */
    @QueryMapping
    fun workspaceArtifact(@Argument workspaceId: Long, @Argument id: String): ArtifactView? {
        access.requireVisible(workspaceId)
        return artifactAt(workspaceId, id)?.let { found -> found.withDraft(draftsIn(workspaceId)[found.id]) }
    }

    /**
     * Its draft named, and where the draft's picture is: a picture with a draft
     * is shown as its draft, or a gallery card marked Draft shows the picture as
     * it was before the changes the mark is about.
     */
    private fun ArtifactView.withDraft(draft: io.mszymanski.orknux.server.design.DesignImage?): ArtifactView =
        if (draft == null) this else copy(draft = "${ArtifactKind.DESIGN.name}-${draft.id}", draftUrl = describe(draft).url)

    /**
     * The newest unsaved version of each picture that has any, by the picture's
     * artifact id: its draft, which the editor opens at and the gallery marks.
     */
    private fun draftsIn(workspaceId: Long): Map<String, io.mszymanski.orknux.server.design.DesignImage> =
        designImages.findByWorkspaceIdAndVersionOfIsNotNull(workspaceId)
            .groupBy { requireNotNull(it.versionOf) }
            .mapValues { (_, versions) -> versions.maxBy { requireNotNull(it.id) } }

    private fun artifactAt(workspaceId: Long, id: String): ArtifactView? {
        val (kind, rowId) = parse(id) ?: return null

        return when (kind) {
            ArtifactKind.IMAGE -> pictures.findByIdOrNull(rowId)
                ?.takeIf { it.workspaceId == workspaceId }
                ?.let(::describe)

            ArtifactKind.TASK -> taskPictures.findByIdOrNull(rowId)
                ?.takeIf { it.workspaceId == workspaceId }
                ?.let(::describe)

            ArtifactKind.SAVED -> saved.findByIdOrNull(rowId)
                ?.takeIf { it.workspaceId == workspaceId }
                ?.let(::describe)

            ArtifactKind.DESIGN -> designImages.findByIdOrNull(rowId)
                ?.takeIf { it.workspaceId == workspaceId }
                ?.let { image -> describe(image).copy(hostedAs = saved.findByHostedImageId(rowId)?.let { "${ArtifactKind.SAVED.name}-${it.id}" }) }
        }
    }

    /**
     * A picture drawn in Design hosted as an artifact: the saved artifact that
     * refers to it, made where there is none and the one there is where there
     * is, so hosting twice shows the address it already has. It shares the
     * picture's file rather than copying it; [replaceBytes] keeps it on the
     * picture's file as the picture is saved. Null for a picture that is not
     * a Design picture of this workspace, or is an unsaved version of one.
     * Not held to the ceiling on saved artifacts, which counts files on disk:
     * this adds none. Access is the caller's to have checked.
     *
     * @return the artifact, and whether it was made now.
     */
    fun host(workspaceId: Long, imageId: String): Pair<SavedArtifact, Boolean>? {
        val (kind, rowId) = parse(imageId) ?: return null
        if (kind != ArtifactKind.DESIGN) return null
        val image = designImages.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId && it.versionOf == null } ?: return null
        saved.findByHostedImageId(rowId)?.let { return it to false }
        val made = saved.save(
            SavedArtifact(
                workspaceId = workspaceId,
                name = image.filename,
                description = image.name ?: image.prompt,
                contentType = image.contentType,
                sizeBytes = image.sizeBytes,
                location = image.location,
                savedBy = HOSTED_BY,
                hostedImageId = rowId,
            ),
        )
        return made to true
    }

    /** The artifact hosting a picture removed, and never its file, which is the picture's. Null where it was not hosted. */
    fun unhost(workspaceId: Long, imageId: String): SavedArtifact? {
        val (kind, rowId) = parse(imageId) ?: return null
        if (kind != ArtifactKind.DESIGN) return null
        val host = saved.findByHostedImageId(rowId)?.takeIf { it.workspaceId == workspaceId } ?: return null
        saved.delete(host)
        return host
    }

    /** The artifact hosting a picture takes the picture's new file name, so the two are called the same. */
    fun hostRenamed(imageRowId: Long, filename: String) {
        saved.findByHostedImageId(imageRowId)?.let { host ->
            host.name = filename
            saved.save(host)
        }
    }

    /** The pictures of a workspace hosted as artifacts: the picture's row id, to the artifact's id. */
    private fun hostedIn(workspaceId: Long): Map<Long, String> =
        saved.findByWorkspaceIdAndHostedImageIdIsNotNull(workspaceId)
            .associate { requireNotNull(it.hostedImageId) to "${ArtifactKind.SAVED.name}-${it.id}" }

    /**
     * The image catalog a drawn picture is filed in, as stored - null where it
     * is in the default, or is not a drawn picture of this workspace.
     */
    fun catalogOf(workspaceId: Long, id: String): Long? {
        val (kind, rowId) = parse(id) ?: return null
        return when (kind) {
            ArtifactKind.IMAGE -> pictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.catalogId
            ArtifactKind.TASK -> taskPictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.catalogId
            ArtifactKind.DESIGN -> designImages.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.catalogId
            ArtifactKind.SAVED -> null
        }
    }

    /**
     * A drawn picture filed in an image catalog, whatever drew it. False for
     * one that is not a drawn picture of this workspace - a saved file has no
     * place on the Images page to be filed on. Access, and that the catalog is
     * this workspace's, are the caller's to have checked.
     */
    fun fileIn(workspaceId: Long, id: String, catalogId: Long): Boolean {
        val (kind, rowId) = parse(id) ?: return false
        return when (kind) {
            ArtifactKind.IMAGE -> pictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }
                ?.also { it.catalogId = catalogId; pictures.save(it) } != null
            ArtifactKind.TASK -> taskPictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }
                ?.also { it.catalogId = catalogId; taskPictures.save(it) } != null
            ArtifactKind.DESIGN -> designImages.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId && it.versionOf == null }
                ?.also { it.catalogId = catalogId; designImages.save(it) } != null
            ArtifactKind.SAVED -> false
        }
    }

    /**
     * Where one artifact's bytes are, and what it is - for the image editor,
     * which changes any picture in the gallery whatever drew it. Null for one
     * not in this workspace. Access is the caller's to have checked.
     */
    fun storedOf(workspaceId: Long, id: String): StoredArtifact? {
        val (kind, rowId) = parse(id) ?: return null
        return when (kind) {
            ArtifactKind.IMAGE -> pictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }
                ?.let { StoredArtifact(it.location, it.contentType, describe(it)) }
            ArtifactKind.TASK -> taskPictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }
                ?.let { StoredArtifact(it.location, it.contentType, describe(it)) }
            ArtifactKind.SAVED -> saved.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }
                ?.let { StoredArtifact(it.location, it.contentType, describe(it)) }
            ArtifactKind.DESIGN -> designImages.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }
                ?.let { StoredArtifact(it.location, it.contentType, describe(it)) }
        }
    }

    /**
     * Another picture's bytes put in place of an artifact's own, for the image
     * editor's Save - whatever drew the artifact: a run's, a task's, a saved
     * file's. The row keeps everything else; the file it held is removed.
     * False for one not in this workspace. Access is the caller's to have checked.
     */
    fun replaceBytes(workspaceId: Long, id: String, location: String, contentType: String, sizeBytes: Long): Boolean {
        val (kind, rowId) = parse(id) ?: return false
        val before: String = when (kind) {
            ArtifactKind.IMAGE -> pictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.let {
                val was = it.location
                it.location = location
                it.contentType = contentType
                it.sizeBytes = sizeBytes
                pictures.save(it)
                was
            }
            ArtifactKind.TASK -> taskPictures.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.let {
                val was = it.location
                it.location = location
                it.contentType = contentType
                it.sizeBytes = sizeBytes
                taskPictures.save(it)
                was
            }
            ArtifactKind.SAVED -> saved.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.let {
                // A wireframe's page is written from the wireframe, and nothing else is put in its place.
                if (it.hostedWireframeId != null) return false
                // A hosted picture has no file of its own: what it is changed into is the picture's.
                it.hostedImageId?.let { image ->
                    return replaceBytes(workspaceId, "${ArtifactKind.DESIGN.name}-$image", location, contentType, sizeBytes)
                }
                val was = it.location
                it.location = location
                it.contentType = contentType
                it.sizeBytes = sizeBytes
                saved.save(it)
                was
            }
            ArtifactKind.DESIGN -> designImages.findByIdOrNull(rowId)?.takeIf { it.workspaceId == workspaceId }?.let {
                val was = it.location
                it.location = location
                it.contentType = contentType
                it.sizeBytes = sizeBytes
                designImages.save(it)
                /*
                 * The artifact hosting it shares its file, so it moves to the
                 * new one here - the one place a picture's file is replaced -
                 * and serves the picture as it now is, with nothing copied.
                 */
                saved.findByHostedImageId(rowId)?.let { host ->
                    host.location = location
                    host.contentType = contentType
                    host.sizeBytes = sizeBytes
                    saved.save(host)
                }
                was
            }
        } ?: return false
        runCatching { store.remove(before) }
            .onFailure { log.warn("Artifact {} took new bytes but its old file at {} was not removed", id, before, it) }
        return true
    }

    /**
     * Gone from the list and gone from the disk.
     *
     * The row first, then the bytes: a row with no file behind it is a broken
     * thumbnail somebody has to delete again, where a file with no row is disk
     * nobody is looking at - so if only one of the two can happen, the second is
     * the better one to be left with. A store that cannot remove the file is
     * logged and not raised: the caller asked for the artifact to go, and it
     * has, as far as anything that reads the list is concerned.
     */
    @MutationMapping
    @Transactional
    fun deleteArtifact(@Argument id: String): Boolean {
        val (kind, rowId) = parse(id) ?: return false

        val gone = when (kind) {
            ArtifactKind.IMAGE -> pictures.findByIdOrNull(rowId)
                ?.takeIf { access.canSee(it.workspaceId) }
                ?.also { pictures.delete(it) }
                ?.let { Removed(it.workspaceId, it.filename, it.location) }

            ArtifactKind.TASK -> taskPictures.findByIdOrNull(rowId)
                ?.takeIf { access.canSee(it.workspaceId) }
                ?.also { taskPictures.delete(it) }
                ?.let { Removed(it.workspaceId, it.filename, it.location) }

            ArtifactKind.SAVED -> saved.findByIdOrNull(rowId)
                ?.takeIf { access.canSee(it.workspaceId) }
                ?.also { saved.delete(it) }
                // One hosting a picture shares the picture's file, which stays with the picture.
                ?.let { Removed(it.workspaceId, it.name, it.location.takeIf { _ -> it.hostedImageId == null }) }

            ArtifactKind.DESIGN -> designImages.findByIdOrNull(rowId)
                ?.takeIf { access.canSee(it.workspaceId) }
                ?.also { image ->
                    // The artifact hosting it serves its file, which goes now: said here rather than left to the cascade alone.
                    saved.findByHostedImageId(rowId)?.let { host ->
                        saved.delete(host)
                        auditRecorder.record(image.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image ${image.name ?: image.filename} no longer hosted as artifact")
                    }
                    designImages.delete(image)
                }
                ?.let { Removed(it.workspaceId, it.filename, it.location) }
        } ?: return false

        gone.location?.let { location ->
            runCatching { store.remove(location) }
                .onFailure { log.warn("Artifact {} row deleted but its file at {} was not", id, location, it) }
        }
        // Its draft goes with it: unsaved changes to a picture that is gone have nothing to be saved over.
        designImages.findByWorkspaceIdAndVersionOf(gone.workspaceId, id).forEach { version ->
            designImages.delete(version)
            runCatching { store.remove(version.location) }
            layers.forget(gone.workspaceId, "${ArtifactKind.DESIGN.name}-${version.id}")
        }
        // And its layers, the picture's and every draft's.
        layers.forget(gone.workspaceId, id)

        auditRecorder.record(
            gone.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Artifact ${gone.filename} deleted",
        )
        return true
    }

    /**
     * An artifact's address with a mark of which file it holds: the image
     * editor's Save puts new bytes behind the same artifact, and an address that
     * stayed the same was answered from the browser's cache with the picture as
     * it was before. The server reads no part of the mark.
     */
    private fun versioned(path: String, location: String): String = "$path?v=${Integer.toHexString(location.hashCode())}"

    /** What a delete took, so the audit and the sweep read one shape for both kinds. */
    private data class Removed(val workspaceId: Long, val filename: String, val location: String?)

    /**
     * An id that says which table it came from.
     *
     * The two tables number their rows separately, so `7` is a picture in each
     * of them and a bare number cannot say which was meant - the kind of
     * ambiguity that deletes the wrong file rather than failing.
     */
    private fun parse(id: String): Pair<ArtifactKind, Long>? {
        val kind = ArtifactKind.entries.firstOrNull { id.startsWith("${it.name}-") } ?: return null
        val rowId = id.removePrefix("${kind.name}-").toLongOrNull() ?: return null
        return kind to rowId
    }

    private fun describe(picture: ExecutionPicture) = ArtifactView(
        id = "${ArtifactKind.IMAGE.name}-${requireNotNull(picture.id)}",
        kind = ArtifactKind.IMAGE,
        source = "Run #${picture.executionId}",
        sourcePath = "executions/${picture.executionId}",
        prompt = picture.prompt,
        filename = picture.filename,
        contentType = picture.contentType,
        sizeBytes = picture.sizeBytes,
        url = versioned("/api/execution-pictures/${picture.id}", picture.location),
        drawnAt = picture.drawnAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        at = picture.drawnAt,
    )

    /**
     * Whether a browser will read this rather than save it.
     *
     * The same list `AttachmentDownloads` serves inline, asked here so the
     * page can draw a way in only where there is one. A picture is not on it:
     * a picture has a thumbnail and a viewer, which is a better way of looking
     * at one than a tab.
     */
    private fun readable(contentType: String): Boolean =
        contentType.lowercase().substringBefore(';').trim() in
            setOf("text/html", "text/plain", "text/markdown", "application/pdf")

    private fun describe(artifact: SavedArtifact) = ArtifactView(
        id = ArtifactKind.SAVED.name + "-" + requireNotNull(artifact.id),
        kind = ArtifactKind.SAVED,
        // The agent, because that is the whole of where this came from: there
        // is no run and no task behind it, only something that decided this
        // was worth keeping.
        source = artifact.savedBy,
        // Nothing to open. The page draws a link only where there is one.
        sourcePath = "",
        prompt = artifact.description,
        filename = artifact.name,
        contentType = artifact.contentType,
        sizeBytes = artifact.sizeBytes,
        url = versioned("/api/artifacts/" + artifact.id, artifact.location),
        hostOf = artifact.hostedImageId?.let { "${ArtifactKind.DESIGN.name}-$it" }
            ?: artifact.hostedWireframeId?.let { "$WIREFRAME-$it" },
        // Marked as the file's own address is: a replaced artifact keeps both addresses, and the viewer's frame reloads only when its src changes.
        previewUrl = versioned("/api/artifacts/${artifact.id}/preview", artifact.location).takeIf { readable(artifact.contentType) },
        drawnAt = artifact.savedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        at = artifact.savedAt,
    )

    /**
     * Drawn on Design's Images page, so it carries what it was drawn with: the
     * model, the parameters asked for, and who asked.
     */
    fun describe(image: io.mszymanski.orknux.server.design.DesignImage) = ArtifactView(
        id = "${ArtifactKind.DESIGN.name}-${requireNotNull(image.id)}",
        kind = ArtifactKind.DESIGN,
        source = "Design",
        sourcePath = "design/images/${ArtifactKind.DESIGN.name}-${image.id}",
        prompt = image.prompt,
        filename = image.filename,
        contentType = image.contentType,
        sizeBytes = image.sizeBytes,
        url = versioned("/api/design-images/${image.id}", image.location),
        drawnAt = image.drawnAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        at = image.drawnAt,
        drawnWith = ArtifactDrawing(image.modelId?.toString(), image.modelName, image.size, image.quality, image.style, image.drawnBy),
        name = image.name ?: io.mszymanski.orknux.server.attachment.PictureFilenames.nameOf(image.prompt),
        versionOf = image.versionOf,
    )

    private fun describe(picture: TaskPicture) = ArtifactView(
        id = "${ArtifactKind.TASK.name}-${requireNotNull(picture.id)}",
        kind = ArtifactKind.TASK,
        source = "Task #${picture.taskId}",
        sourcePath = "tasks/${picture.taskId}",
        prompt = picture.prompt,
        filename = picture.filename,
        contentType = picture.contentType,
        sizeBytes = picture.sizeBytes,
        url = versioned("/api/task-pictures/${picture.id}", picture.location),
        drawnAt = picture.drawnAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        at = picture.drawnAt,
    )

    companion object {
        /** Who the Artifacts page says a hosted picture came from. */
        const val HOSTED_BY = "Design"

        /** How an artifact that is a wireframe's page names the wireframe, as WIREFRAME-n. */
        const val WIREFRAME = "WIREFRAME"
    }
}

/**
 * Where it came from.
 *
 * [IMAGE] and [TASK] are drawn - by a workflow's image node and by an agent
 * working a task. [SAVED] is not drawn at all: it is a file an agent made and
 * decided was worth keeping, which is why it has a name it chose rather than a
 * prompt it was given. [DESIGN] is drawn by a person, on Design's Images page.
 */
enum class ArtifactKind { IMAGE, TASK, SAVED, DESIGN }

data class ArtifactView(
    /** Prefixed with the kind: the two tables number their rows separately. */
    val id: String,
    val kind: ArtifactKind,
    /** Where it came from, named the way the page says it: "Run #12", "Task #4". */
    val source: String,
    /**
     * The rest of the address of that, under the workspace; blank where there
     * is nothing to open.
     *
     * A saved artifact has no run and no task behind it - an agent made it -
     * so the page names where it came from without linking anywhere.
     */
    val sourcePath: String,
    /** What was asked for - the picture's caption, and what a search reads. */
    val prompt: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    /** Where the bytes are: the same address the run graph's `<img>` uses. */
    val url: String,
    /**
     * Where the same bytes can be *read* rather than saved, or null for a file
     * nothing here will render.
     *
     * Its own address, so what a link is does not depend on what the file
     * inside it turns out to be: [url] hands the file over, always, and this
     * is the one that opens. Null is the answer for a zip, an executable, or
     * anything else nobody has decided to render - and the page draws no way
     * to open one.
     */
    val previewUrl: String? = null,
    /**
     * When, as the page shows it.
     *
     * Beside [at] rather than derived from it at the field, because GraphQL
     * reads a property and Kotlin will not let a data class carry both a
     * `drawnAt` property and a `getDrawnAt()` of a different type.
     */
    val drawnAt: String,
    /**
     * The same moment, kept as one, because the merge sorts on it.
     *
     * As text, `2026-09-19T09:00+02:00` sorts after `2026-09-19T10:00Z` - the
     * same instant an hour earlier - so the two halves would interleave wrongly
     * wherever an installation wrote both offsets. Not in the schema: it exists
     * for the sort and nothing asks for it.
     */
    val at: OffsetDateTime,
    /** What drew it and how, where that was kept - a picture drawn in Design; null elsewhere. */
    val drawnWith: ArtifactDrawing? = null,
    /** What a picture drawn in Design is called; null for anything else, which goes by its prompt. */
    val name: String? = null,
    /** The picture this is an unsaved version of, in the image editor; null for a picture of its own. */
    val versionOf: String? = null,
    /** This picture's draft - its newest unsaved version, by id - or null where it has none. */
    val draft: String? = null,
    /** Where that draft's picture is - what a picture with a draft is shown as; null where it has none. */
    val draftUrl: String? = null,
    /** The image catalog of a picture on Design's Images page, in a list asked for with drawn; null otherwise. */
    val catalogId: String? = null,
    val catalogName: String? = null,
    /** For a picture drawn in Design that is hosted as an artifact: that artifact, SAVED-n. Null otherwise. */
    val hostedAs: String? = null,
    /** For an artifact hosting something made in Design: the picture, DESIGN-n, or the wireframe, WIREFRAME-n. Null otherwise. */
    val hostOf: String? = null,
) {
    /** What the page shows is what a search reads: the caption and the name. */
    fun matches(looking: String): Boolean =
        prompt.contains(looking, ignoreCase = true) || filename.contains(looking, ignoreCase = true) ||
            name?.contains(looking, ignoreCase = true) == true
}

/** An artifact's bytes on the store, and how the page shows it. */
data class StoredArtifact(val location: String, val contentType: String, val view: ArtifactView)

data class ArtifactDrawing(
    /** Null once that model is removed; [model] still names it. */
    val modelId: String?,
    val model: String,
    val size: String?,
    val quality: String?,
    val style: String?,
    val drawnBy: String,
)

data class ArtifactPage(
    val content: List<ArtifactView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)
