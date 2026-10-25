package io.mszymanski.orknux.server.attachment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Where the bytes of an attachment live.
 *
 * An interface for one implementation, deliberately: the second one — object
 * storage, most likely — should be a class beside this rather than an
 * `if` inside every place that touches a file. What is stored on the row is the
 * location this hands back, so nothing else needs to know the shape of it.
 */
interface AttachmentStore {

    /** @return where it went, in whatever form this storage understands. */
    fun put(workspaceId: Long, filename: String, bytes: ByteArray): String

    /**
     * The same, from a stream: an upload may be hundreds of megabytes where the
     * desktop app allows it, and reading one into an array to write it out again
     * is that much heap for nothing. A storage that cannot stream reads it whole.
     */
    fun putFrom(workspaceId: Long, filename: String, input: InputStream): String = put(workspaceId, filename, input.readAllBytes())

    fun open(location: String): InputStream

    /**
     * Whether the bytes are still there.
     *
     * Asked before serving, so a row whose file has gone is a 404 rather than
     * an exception thrown while the response body was already being written.
     * On the interface it is the difference between one line saying the picture
     * is gone and a broken-image icon saying the page is.
     */
    fun exists(location: String): Boolean

    fun remove(location: String)

    /**
     * Files bytes that are already here under a different workspace.
     *
     * Here rather than in the caller because where a workspace's files sit is
     * this storage's business and nowhere else's - a caller that read the bytes
     * back and put them again would work, and would read a hundred megabytes
     * through the application to write it a directory across. An issue moved
     * between workspaces is the only thing that asks for this, and it asks
     * because the folder is what decides who may open the file.
     *
     * @return where the bytes are now, which the row has to be told.
     */
    fun move(location: String, workspaceId: Long): String
}

/**
 * Attachments on the disk of the machine running this, filed by workspace.
 *
 * One directory per workspace, which is the point: a workspace's files are its
 * own, and a mistake in a query should not be able to hand somebody another
 * workspace's document. The name on disk is not the name it was uploaded with —
 * that is on the row — because two people uploading `report.pdf` is normal, and
 * a filename from a browser is not something to trust with a path.
 */
@Component
class FilesystemAttachmentStore(private val settings: InstallationSettings) : AttachmentStore {

    override fun put(workspaceId: Long, filename: String, bytes: ByteArray): String =
        putFrom(workspaceId, filename, bytes.inputStream())

    override fun putFrom(workspaceId: Long, filename: String, input: InputStream): String {
        val folder = root().resolve(workspaceId.toString())
        Files.createDirectories(folder)

        val stored = "${UUID.randomUUID()}${extensionOf(filename)}"
        val target = folder.resolve(stored)
        input.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }

        log.debug("Attachment {} written for workspace {}", stored, workspaceId)
        // Relative to the configured root, so moving the root is a change to
        // one line of configuration rather than an update of every row.
        return "$workspaceId/$stored"
    }

    override fun open(location: String): InputStream = Files.newInputStream(resolve(location))

    override fun exists(location: String): Boolean =
        runCatching { Files.isRegularFile(resolve(location)) }.getOrDefault(false)

    override fun remove(location: String) {
        runCatching { Files.deleteIfExists(resolve(location)) }
            .onFailure { log.warn("Attachment {} could not be removed", location, it) }
    }

    /**
     * The same bytes, in the other workspace's folder.
     *
     * The stored name is kept rather than minted again, so the extension the
     * upload worked out and anything holding the file by name still read the
     * same. A rename on the same disk, which is what makes this cheap enough to
     * do inside the transaction that moves the row.
     *
     * Failures are not swallowed the way a removal's are. A file that could not
     * be deleted is a wasted block; a file that could not be moved and whose
     * row was updated anyway is an attachment nobody can open again, so this
     * throws and lets the move be abandoned.
     */
    override fun move(location: String, workspaceId: Long): String {
        val from = resolve(location)
        val stored = from.fileName.toString()
        val folder = root().resolve(workspaceId.toString())
        Files.createDirectories(folder)

        val target = folder.resolve(stored)
        if (from != target) Files.move(from, target, StandardCopyOption.REPLACE_EXISTING)

        log.debug("Attachment {} moved to workspace {}", stored, workspaceId)
        return "$workspaceId/$stored"
    }

    /**
     * The file this location names, and only ever under the root.
     *
     * Checked rather than trusted: the location comes from a row today, but a
     * path that escapes its root is the sort of bug that is discovered from the
     * outside.
     */
    private fun resolve(location: String): Path {
        val root = root()
        val target = root.resolve(location).normalize()
        require(target.startsWith(root)) { "That attachment is not where it says it is" }
        return target
    }

    private fun root(): Path = Path.of(settings.location()).toAbsolutePath().normalize()

    /** Kept from the uploaded name, since it is how anything opening it decides what it is. */
    private fun extensionOf(filename: String): String {
        val dot = filename.lastIndexOf('.')
        if (dot <= 0 || dot == filename.length - 1) return ""

        val extension = filename.substring(dot)
        return if (extension.length <= MAX_EXTENSION && extension.drop(1).all(Char::isLetterOrDigit)) {
            extension.lowercase()
        } else {
            ""
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FilesystemAttachmentStore::class.java)

        const val MAX_EXTENSION = 12
    }
}
