package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.Base64

/**
 * Filing what an agent made.
 *
 * The one door between a model deciding it has produced something worth keeping
 * and that thing appearing on the Artifacts page. Everything about what may be
 * saved is decided here rather than at the tool, so a second caller - a
 * workflow node, an MCP client - cannot be given a laxer version of the same
 * rules by being written later.
 *
 * The bytes go down before the row, for the reason `ChatPictureAPI` gives: a
 * row pointing at a file that was never written is an artifact nobody can open
 * and everybody can see, where a file with no row is disk nobody is looking at.
 */
@Service
class SavedArtifacts(
    private val artifacts: SavedArtifactRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Whether there is anywhere to put one.
     *
     * Asked before the tool is offered, because `AgentTools` states the rule
     * and it holds here: a model is only ever offered tools that will run. An
     * installation with attachments off has nowhere to file bytes, and offering
     * the tool anyway spends a turn teaching the model that.
     */
    fun offered(): Boolean = settings.attachmentsEnabled()

    /**
     * Files one an agent made, held to the installation's upload limit as one a
     * person uploads is: it kept a 5 MB cap of its own, so an agent could not
     * keep the 28 MB PDF a person had just attached in the same chat.
     *
     * @param content the file itself: text as it stands, or base64 when [base64] is set.
     */
    fun save(
        workspaceId: Long,
        savedBy: String,
        name: String,
        description: String,
        content: String,
        base64: Boolean,
    ): Saving = file(workspaceId, savedBy, name, description, settings.maxFileSizeMb() * 1024 * 1024) {
        if (base64) runCatching { Base64.getDecoder().decode(content) }.getOrNull() else content.toByteArray(Charsets.UTF_8)
    }

    /**
     * Files one a person uploaded - the Artifacts page, a research's sources.
     *
     * Held to the installation's upload limit: a person dropping a file on a
     * page is the same upload a chat's attachment is.
     */
    fun upload(workspaceId: Long, savedBy: String, name: String, description: String, bytes: ByteArray): Saving =
        file(workspaceId, savedBy, name, description, settings.maxFileSizeMb() * 1024 * 1024) { bytes }

    /**
     * Puts a new file behind a saved artifact that already exists, keeping its
     * id - so its key, its page and every link somebody was sent - and removing
     * the file it held once the new one is safely written and the row points at it.
     *
     * The name follows the new file, because the type is read off it; the
     * description stays unless a new one is given. Held to the same limit and
     * the same rules about names as a new one, and not counted against the
     * workspace's ceiling, since nothing is added. Only a saved artifact: a
     * picture a run or a task drew is not a file somebody handed over, and the
     * image editor is how one of those changes.
     *
     * @param id the row's own number, as [numberOf] reads it off a key or a link.
     * @param description the new caption, or null or blank to keep the one it has.
     */
    fun replace(
        workspaceId: Long,
        id: Long,
        name: String,
        description: String?,
        content: String,
        base64: Boolean,
    ): Replacing = swap(workspaceId, id, name, description) {
        if (base64) runCatching { Base64.getDecoder().decode(content) }.getOrNull() else content.toByteArray(Charsets.UTF_8)
    }

    /** [replace] with the bytes a person uploaded. */
    fun replaceUpload(workspaceId: Long, id: Long, name: String, description: String?, bytes: ByteArray): Replacing =
        swap(workspaceId, id, name, description) { bytes }

    private fun swap(workspaceId: Long, id: Long, name: String, description: String?, read: () -> ByteArray?): Replacing {
        val artifact = artifacts.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: return Replacing.Missing("This workspace has no saved artifact ${ArtifactKind.SAVED.name}-$id.")
        // It is a wireframe's page, written again from the wireframe on every save: a file put here would be gone at the next.
        if (artifact.hostedWireframeId != null) {
            return Replacing.Refused(
                "${ArtifactKind.SAVED.name}-$id is the page of a wireframe in Design and is written again each time the wireframe " +
                    "is saved; change the wireframe instead.",
            )
        }
        // Its file is a picture's in Design: replacing it here would leave the two telling different stories.
        if (artifact.hostedImageId != null) {
            return Replacing.Refused(
                "${ArtifactKind.SAVED.name}-$id hosts the picture ${ArtifactKind.DESIGN.name}-${artifact.hostedImageId} from Design " +
                    "and always shows it as it is saved there; change the picture in Design instead.",
            )
        }
        val vetted = when (
            val checked = vet(name, description?.takeIf { it.isNotBlank() } ?: artifact.description, settings.maxFileSizeMb() * 1024 * 1024, read)
        ) {
            is Vetting.Refused -> return Replacing.Refused(checked.reason)
            is Vetting.Vetted -> checked
        }
        val location = runCatching { store.put(workspaceId, vetted.name, vetted.bytes) }
            .onFailure { log.warn("Could not file a replacement for artifact {} in workspace {}", id, workspaceId, it) }
            .getOrNull()
            ?: return Replacing.Refused("The file could not be stored. Somebody should look at the server's log.")

        val was = artifact.name
        val before = artifact.location
        artifact.name = vetted.name
        artifact.description = vetted.description
        artifact.contentType = typeOf(vetted.name)
        artifact.sizeBytes = vetted.bytes.size.toLong()
        artifact.location = location
        val saved = runCatching { artifacts.save(artifact) }.getOrElse {
            // The row still names the old file, so the new one is what has to go.
            runCatching { store.remove(location) }
            throw it
        }
        runCatching { store.remove(before) }
            .onFailure { log.warn("Artifact {} took a new file but its old one at {} was not removed", id, before, it) }
        return Replacing.Replaced(saved, was)
    }

    private fun file(
        workspaceId: Long,
        savedBy: String,
        name: String,
        description: String,
        most: Long,
        read: () -> ByteArray?,
    ): Saving {
        val vetted = when (val checked = vet(name, description, most, read)) {
            is Vetting.Refused -> return Saving.Refused(checked.reason)
            is Vetting.Vetted -> checked
        }

        /*
         * A ceiling on how many one workspace may hold.
         *
         * The same argument `TaskPictures` makes for its per-task limit: a
         * model in a loop will fill whatever it is given, and a disk filled by
         * one is a problem every other workspace on the installation has. A
         * refusal is a sentence the model reads and works around.
         */
        if (artifacts.countByWorkspaceId(workspaceId) >= MOST_PER_WORKSPACE) {
            return Saving.Refused(
                "This workspace already holds $MOST_PER_WORKSPACE saved artifacts, which is as many as it may. " +
                    "Somebody has to delete some on the Artifacts page before anything else can be saved.",
            )
        }

        val location = runCatching { store.put(workspaceId, vetted.name, vetted.bytes) }
            .onFailure { log.warn("Could not file an artifact called {} for workspace {}", vetted.name, workspaceId, it) }
            .getOrNull()
            ?: return Saving.Refused("The file could not be stored. Somebody should look at the server's log.")

        val saved = artifacts.save(
            SavedArtifact(
                workspaceId = workspaceId,
                name = vetted.name,
                description = vetted.description,
                contentType = typeOf(vetted.name),
                sizeBytes = vetted.bytes.size.toLong(),
                location = location,
                savedBy = savedBy,
            ),
        )
        return Saving.Saved(saved)
    }

    /** What a new file and a replacing one are both held to before anything is written. */
    private fun vet(name: String, description: String, most: Long, read: () -> ByteArray?): Vetting {
        if (!settings.attachmentsEnabled()) {
            return Vetting.Refused(
                "A saved artifact is kept as an attachment, and attachments are turned off for this installation.",
            )
        }

        val called = name.trim()
        if (called.isEmpty()) return Vetting.Refused("Give the file a name, extension and all: save_artifact takes one.")
        if (called.length > MOST_NAME) {
            return Vetting.Refused("That name is too long; name the file in under $MOST_NAME characters.")
        }

        /*
         * A name, not a path.
         *
         * The store decides where bytes go and files them under a name of its
         * own, so a slash here never reached the disk - but the name is shown
         * and offered as a download, and `../../etc/passwd` in a download
         * attribute is a thing to refuse rather than to render.
         */
        if (called.any { it in FORBIDDEN }) {
            return Vetting.Refused("A file name cannot contain a path: give just the name, like diagram.svg.")
        }

        val said = description.trim()
        if (said.isEmpty()) {
            return Vetting.Refused("Say what the file is: save_artifact takes a description, which is how anybody finds it again.")
        }
        if (said.length > MOST_DESCRIPTION) {
            return Vetting.Refused("That description is too long; say it in under $MOST_DESCRIPTION characters.")
        }

        val bytes = read()
            ?: return Vetting.Refused("That is not valid base64. Send the file as text, or as base64 with base64 set.")

        if (bytes.isEmpty()) return Vetting.Refused("There is nothing to save: the file is empty.")
        if (bytes.size > most) {
            return Vetting.Refused(
                "That file is ${bytes.size / 1024}KB, and ${most / 1024}KB is as large as a saved artifact may be.",
            )
        }
        return Vetting.Vetted(called, said, bytes)
    }

    private sealed interface Vetting {
        class Vetted(val name: String, val description: String, val bytes: ByteArray) : Vetting
        class Refused(val reason: String) : Vetting
    }

    /**
     * What it is, from what it is called.
     *
     * Guessed rather than asked for: a model handed a content-type parameter
     * fills it in with something plausible and occasionally wrong, and what
     * this is actually for is the browser deciding whether to draw the file or
     * download it. The extension is the thing the model already had to get
     * right for the name to make sense.
     */
    private fun typeOf(name: String): String =
        TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /**
     * Which saved artifact a key, a number or a link names, or null for one
     * that names something else.
     *
     * Every way one is handed around reads the same: `SAVED-97` as the page
     * says it, `97` as the box tools take it, and either address -
     * `/workspace/9/artifacts/SAVED-97` or `/api/artifacts/97?v=…` - since a
     * link is what a person or a model has most often been sent. Another
     * kind's key is not read as a number: `IMAGE-3` is a picture a run drew,
     * and taking its 3 would replace a different file altogether.
     */
    fun numberOf(named: String): Long? {
        val last = named.trim().substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return SAVED_KEY.matchEntire(last)?.groupValues?.get(1)?.toLongOrNull()
    }

    sealed interface Saving {
        data class Saved(val artifact: SavedArtifact) : Saving

        /** Said back to the model as it stands, so it knows what to do differently. */
        data class Refused(val reason: String) : Saving
    }

    sealed interface Replacing {
        /** @param was what it was called before, which the audit line names. */
        data class Replaced(val artifact: SavedArtifact, val was: String) : Replacing

        /** Nothing by that number in this workspace - an HTTP caller's 404. */
        data class Missing(val reason: String) : Replacing

        data class Refused(val reason: String) : Replacing
    }

    private companion object {
        /** A saved artifact's key, or its bare number. */
        val SAVED_KEY = Regex("(?:SAVED-)?(\\d+)")

        const val MOST_NAME = 120
        const val MOST_DESCRIPTION = 500

        /** As many as one workspace may hold before somebody has to tidy up. */
        const val MOST_PER_WORKSPACE = 500L

        /** What may not appear in a name, because a name is not a path. */
        val FORBIDDEN = charArrayOf('/', '\\', '\u0000').toSet()

        /**
         * The types worth naming, by extension.
         *
         * What an agent produces is text of one kind or another and the
         * occasional encoded picture; anything not here is handed back as bytes
         * to download, which is the right answer for a file nothing can draw.
         */
        val TYPES = mapOf(
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "pdf" to "application/pdf",
            "json" to "application/json",
            "csv" to "text/csv",
            "md" to "text/markdown",
            "txt" to "text/plain",
            "html" to "text/html",
            "xml" to "application/xml",
            "yaml" to "application/yaml",
            "yml" to "application/yaml",
            // Music an agent wrote and rendered, so a saved song is served as what it is.
            "mid" to io.mszymanski.orknux.server.attachment.AttachmentTypes.MIDI,
            "midi" to io.mszymanski.orknux.server.attachment.AttachmentTypes.MIDI,
            "mp3" to io.mszymanski.orknux.server.attachment.AttachmentTypes.MP3,
            "flac" to io.mszymanski.orknux.server.attachment.AttachmentTypes.FLAC,
            "wav" to io.mszymanski.orknux.server.attachment.AttachmentTypes.WAV,
        )
    }
}

/**
 * The audit line for a replaced artifact, the same from the page and from an
 * agent: the name it had, and the new one where the file came in under another.
 */
fun replacedLine(replaced: SavedArtifacts.Replacing.Replaced): String =
    if (replaced.was == replaced.artifact.name) "Artifact ${replaced.was} replaced"
    else "Artifact ${replaced.was} replaced with ${replaced.artifact.name}"
