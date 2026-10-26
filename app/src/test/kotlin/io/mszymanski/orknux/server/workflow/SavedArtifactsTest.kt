package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.Base64

/**
 * The one door between an agent deciding it made something and that thing
 * appearing on the Artifacts page.
 *
 * Every bound is here rather than at the tool, so a second caller written later
 * cannot be given a laxer version of the same rules by being written later.
 */
@SpringBootTest
class SavedArtifactsTest(
    @Autowired val artifacts: SavedArtifacts,
    @Autowired val rows: SavedArtifactRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun seed() {
        rows.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    private fun save(
        name: String = "diagram.svg",
        description: String = "The login flow, as a diagram.",
        content: String = "<svg/>",
        base64: Boolean = false,
    ) = artifacts.save(
        workspaceId = workspaceId,
        savedBy = "Support responder",
        name = name,
        description = description,
        content = content,
        base64 = base64,
    )

    @Test
    fun `it files the bytes and the row, and says where they went`() {
        val saving = save()

        assertThat(saving).isInstanceOf(SavedArtifacts.Saving.Saved::class.java)
        val artifact = (saving as SavedArtifacts.Saving.Saved).artifact
        assertThat(artifact.name).isEqualTo("diagram.svg")
        assertThat(artifact.savedBy).isEqualTo("Support responder")
        assertThat(artifact.sizeBytes).isEqualTo(6)
        // The type is read off the extension rather than asked for: it decides
        // whether a browser draws the file or offers it.
        assertThat(artifact.contentType).isEqualTo("image/svg+xml")
        assertThat(store.exists(artifact.location)).isTrue()
    }

    /** Binary means base64, which is the only way bytes reach a tool call. */
    @Test
    fun `base64 content is decoded before it is stored`() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val saving = save(name = "shot.png", content = Base64.getEncoder().encodeToString(bytes), base64 = true)

        val artifact = (saving as SavedArtifacts.Saving.Saved).artifact
        assertThat(artifact.sizeBytes).isEqualTo(4)
        assertThat(artifact.contentType).isEqualTo("image/png")
        assertThat(store.open(artifact.location).use { it.readBytes() }).isEqualTo(bytes)
    }

    @Test
    fun `base64 that is not base64 is refused rather than stored as its own text`() {
        val saving = save(content = "not base64 at all!!", base64 = true)

        assertThat(saving).isInstanceOf(SavedArtifacts.Saving.Refused::class.java)
        assertThat((saving as SavedArtifacts.Saving.Refused).reason).contains("valid base64")
        assertThat(rows.count()).isZero()
    }

    /**
     * A name, not a path.
     *
     * The store files bytes under a name of its own so a slash never reached
     * the disk - but the name is shown and offered as a download, and a path in
     * a download attribute is a thing to refuse rather than to render.
     */
    @Test
    fun `a name holding a path is refused`() {
        val saving = save(name = "../../etc/passwd")

        assertThat(saving).isInstanceOf(SavedArtifacts.Saving.Refused::class.java)
        assertThat((saving as SavedArtifacts.Saving.Refused).reason).contains("cannot contain a path")
        assertThat(rows.count()).isZero()
    }

    @Test
    fun `an empty file and a nameless one are both refused`() {
        assertThat(save(content = "")).isInstanceOf(SavedArtifacts.Saving.Refused::class.java)
        assertThat(save(name = "  ")).isInstanceOf(SavedArtifacts.Saving.Refused::class.java)
        assertThat(save(description = " ")).isInstanceOf(SavedArtifacts.Saving.Refused::class.java)
        assertThat(rows.count()).isZero()
    }

    /**
     * A file too large leaves nothing behind.
     *
     * Checked before the store is touched, so a refusal cannot leave bytes on
     * the disk that no row points at.
     */
    @Test
    fun `a file past the upload limit is refused before anything is written, and one under it kept`() {
        // Held to the installation's upload limit, set to 7 MB here, not the 5 MB an agent's file used to stop at -
        // and kept small, since the suite's heap is shared: a 26 MB string here helped run CI's out of memory.
        settings.setMaxFileSizeMb(7, "test")
        try {
            assertThat(save(content = "x".repeat(6 * 1024 * 1024))).isInstanceOf(SavedArtifacts.Saving.Saved::class.java)
            rows.deleteAll()

            val saving = save(content = "x".repeat(8 * 1024 * 1024))
            assertThat(saving).isInstanceOf(SavedArtifacts.Saving.Refused::class.java)
            assertThat((saving as SavedArtifacts.Saving.Refused).reason).contains("as large as")
            assertThat(rows.count()).isZero()
        } finally {
            settings.setMaxFileSizeMb(25, "test")
        }
    }

    /** An unknown extension is bytes to download, which is right for a file nothing can draw. */
    @Test
    fun `an extension nothing knows is handed back as bytes`() {
        val artifact = (save(name = "model.bin") as SavedArtifacts.Saving.Saved).artifact

        assertThat(artifact.contentType).isEqualTo("application/octet-stream")
    }
}
