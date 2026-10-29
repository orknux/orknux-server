package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workflow.ArtifactKind
import io.mszymanski.orknux.server.workflow.SavedArtifact
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.charset.StandardCharsets

/**
 * A wireframe hosted as an HTML page: a saved artifact, SAVED-n, holding the
 * wireframe written out by [WireframeHtml], at an address of its own on the
 * Artifacts page.
 *
 * Kept in step by being written again rather than by being read live, so the
 * page is a file like every other artifact - downloaded, previewed, read by an
 * agent through its key - and costs nothing to serve. [WireframeAPI.write] is
 * the one place a drawing is saved, from the editor, the orknux_* tools and the
 * wireframe's chat alike, and it calls [refresh]; a rename calls it too, since
 * the page carries the name as its title and its file name.
 *
 * Unlike a hosted picture, which shares the picture's file, this row holds a
 * file of its own: a wireframe has none to share. A new file is written before
 * the row moves onto it, and the one it replaces is removed only once the
 * transaction has committed - so a save that rolls back leaves the page as it
 * was, rather than pointing at a file already gone.
 *
 * Access is the caller's to have checked; [WireframeAPI] checks it.
 */
@Component
class WireframeHosting(
    private val saved: SavedArtifactRepository,
    private val store: AttachmentStore,
) {

    /** The artifact that is [wireframeId]'s page, or null where it is not hosted. */
    fun hostOf(wireframeId: Long): SavedArtifact? = saved.findByHostedWireframeId(wireframeId)

    /** The wireframes of a workspace that are hosted: the wireframe's id, to its artifact's, SAVED-n. */
    fun hostedIn(workspaceId: Long): Map<Long, String> =
        saved.findByWorkspaceIdAndHostedWireframeIdIsNotNull(workspaceId)
            .associate { requireNotNull(it.hostedWireframeId) to idOf(it) }

    /**
     * The wireframe's page, made where there is none and the one there is
     * where there is, so hosting twice shows the address it already has. Not
     * held to the ceiling on saved artifacts: one per wireframe at most, and
     * removed with it.
     *
     * @param page the wireframe written out, asked for only where a page is made.
     * @return the artifact, and whether it was made now.
     */
    fun host(held: Wireframe, page: () -> String): Pair<SavedArtifact, Boolean> {
        val id = requireNotNull(held.id)
        hostOf(id)?.let { return it to false }
        val name = WireframeHtml.fileName(held.name, "html")
        val bytes = page().toByteArray(StandardCharsets.UTF_8)
        val location = store.put(held.workspaceId, name, bytes)
        removeIfRolledBack(location)
        val made = saved.save(
            SavedArtifact(
                workspaceId = held.workspaceId,
                name = name,
                description = describe(held),
                contentType = CONTENT_TYPE,
                sizeBytes = bytes.size.toLong(),
                location = location,
                savedBy = WorkspaceArtifactAPI.HOSTED_BY,
                hostedWireframeId = id,
            ),
        )
        return made to true
    }

    /**
     * The page written again from the wireframe as it now is, where it is
     * hosted; nothing at all, and nothing rendered, where it is not.
     */
    fun refresh(held: Wireframe, page: () -> String) {
        val host = hostOf(requireNotNull(held.id)) ?: return
        val name = WireframeHtml.fileName(held.name, "html")
        val bytes = page().toByteArray(StandardCharsets.UTF_8)
        val location = store.put(held.workspaceId, name, bytes)
        removeIfRolledBack(location)
        val was = host.location
        host.name = name
        host.description = describe(held)
        host.location = location
        host.sizeBytes = bytes.size.toLong()
        saved.save(host)
        removeOnceCommitted(was)
    }

    /** The page taken away, file and all; the wireframe stays. Null where it was not hosted. */
    fun unhost(wireframeId: Long): SavedArtifact? {
        val host = hostOf(wireframeId) ?: return null
        saved.delete(host)
        removeOnceCommitted(host.location)
        return host
    }

    private fun describe(held: Wireframe): String = "The wireframe ${held.name}, as it is saved in Design"

    /** A file this transaction wrote, removed again if it does not commit. */
    private fun removeIfRolledBack(location: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) remove(location)
                }
            },
        )
    }

    /** A file the row no longer points at, removed once that is committed - at once where nothing is open. */
    private fun removeOnceCommitted(location: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remove(location)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = remove(location)
            },
        )
    }

    private fun remove(location: String) {
        runCatching { store.remove(location) }
            .onFailure { log.warn("A hosted wireframe's page at {} was not removed", location, it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WireframeHosting::class.java)

        const val CONTENT_TYPE = "text/html"

        /** The artifact's id as every link and tool says it. */
        fun idOf(artifact: SavedArtifact): String = "${ArtifactKind.SAVED.name}-${artifact.id}"
    }
}
