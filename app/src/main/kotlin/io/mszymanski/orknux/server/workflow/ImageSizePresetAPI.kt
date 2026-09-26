package io.mszymanski.orknux.server.workflow

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
 * The size presets a workspace's image nodes pick from, and who may change them.
 *
 * Read by anybody who can see the workspace, because the editor's Preset menu
 * draws the list; changed by whoever administers it, as the other workspace
 * settings are. Every change is a line in the workspace's log under WORKSPACE,
 * beside the issue statuses and types it sits with. Issue #431.
 */
@Controller
class ImageSizePresetAPI(
    private val presets: ImageSizePresetRepository,
    private val catalogue: ImageSizePresetCatalogue,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
) {

    /**
     * A workspace's presets in menu order.
     *
     * Not read-only, deliberately: a workspace whose presets were never written
     * down gets them written here, so the dialog that manages them has rows
     * with ids to manage. See [ImageSizePresetCatalogue.ensure].
     */
    @QueryMapping
    @Transactional
    fun imageSizePresets(@Argument workspaceId: Long): List<ImageSizePresetView> {
        access.requireVisible(workspaceId)
        return catalogue.ensure(workspaceId).map(::ImageSizePresetView)
    }

    @MutationMapping
    @Transactional
    fun addImageSizePreset(
        @Argument workspaceId: Long,
        @Argument name: String,
        @Argument width: Int,
        @Argument height: Int,
    ): ImageSizePresetView {
        access.requireAdministers(workspaceId)
        val held = catalogue.ensure(workspaceId)
        val made = presets.save(
            ImageSizePreset(
                workspaceId = workspaceId,
                name = cleanName(name),
                width = cleanSide("width", width),
                height = cleanSide("height", height),
                // At the end: a new preset is somewhere an administrator moves
                // into place, not somewhere every node's menu starts.
                position = (held.maxOfOrNull { it.position } ?: -1) + 1,
            ),
        )
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Image size preset ${made.name} (${made.width}x${made.height}) added")
        return ImageSizePresetView(made)
    }

    /** Changes the name, the width or the height; each is left alone when absent. */
    @MutationMapping
    @Transactional
    fun updateImageSizePreset(
        @Argument id: Long,
        @Argument name: String?,
        @Argument width: Int?,
        @Argument height: Int?,
    ): ImageSizePresetView {
        val held = presets.findByIdOrNull(id) ?: throw ImageSizePresetNotFoundException(id)
        access.requireAdministers(held.workspaceId)

        val was = "${held.name} (${held.width}x${held.height})"
        name?.let { held.name = cleanName(it) }
        width?.let { held.width = cleanSide("width", it) }
        height?.let { held.height = cleanSide("height", it) }
        val saved = presets.save(held)
        audit.record(
            saved.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Image size preset $was is now ${saved.name} (${saved.width}x${saved.height})",
        )
        return ImageSizePresetView(saved)
    }

    /**
     * Puts the presets in the order given, which has to name each of them once.
     *
     * The whole list rather than one move, for the reason the statuses take
     * theirs: "move up" and "move down" are what the dialog offers and the
     * list is what it holds, and one round trip cannot leave two rows on the
     * same position.
     */
    @MutationMapping
    @Transactional
    fun reorderImageSizePresets(@Argument workspaceId: Long, @Argument ids: List<Long>): List<ImageSizePresetView> {
        access.requireAdministers(workspaceId)
        val held = catalogue.ensure(workspaceId).associateBy { requireNotNull(it.id) }
        if (ids.toSet() != held.keys || ids.size != held.size) throw ImageSizePresetReorderException()

        val ordered = ids.mapIndexed { at, id -> requireNotNull(held[id]).apply { position = at } }
        presets.saveAll(ordered)
        audit.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Image size presets reordered: ${ordered.joinToString(", ") { it.name }}",
        )
        return ordered.map(::ImageSizePresetView)
    }

    /**
     * Takes a preset out of the menu. Nothing holds a preset - a node stores
     * the numbers it filled in - so nothing stands in the way.
     */
    @MutationMapping
    @Transactional
    fun removeImageSizePreset(@Argument id: Long): Boolean {
        val held = presets.findByIdOrNull(id) ?: throw ImageSizePresetNotFoundException(id)
        access.requireAdministers(held.workspaceId)
        presets.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Image size preset ${held.name} (${held.width}x${held.height}) removed")
        return true
    }

    private fun cleanName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= ImageSizePresets.NAME_LENGTH }
            ?: throw ImageSizePresetNameInvalidException()

    private fun cleanSide(side: String, value: Int): Int {
        if (value < 1 || value > ImageSizePresets.MAX_SIDE) throw ImageSizePresetSideInvalidException(side, value)
        return value
    }
}

/** A preset as the editor's menu and the dialog that manages it read it. */
data class ImageSizePresetView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val position: Int,
) {
    constructor(preset: ImageSizePreset) : this(
        id = requireNotNull(preset.id),
        workspaceId = preset.workspaceId,
        name = preset.name,
        width = preset.width,
        height = preset.height,
        position = preset.position,
    )
}
