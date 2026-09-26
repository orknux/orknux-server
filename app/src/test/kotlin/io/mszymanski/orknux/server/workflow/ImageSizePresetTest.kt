package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Image size presets as a workspace's own menu - issue #431.
 *
 * What is pinned is what could go quietly wrong: a workspace arriving without
 * its seven, a side nobody could draw being written down, an order that loses
 * a row, and somebody who does not administer the workspace changing what every
 * image node in it offers.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ImageSizePresetTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val presets: ImageSizePresetRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        presets.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "studio")).id)
    }

    private fun namesOf(workspace: Long): List<String> =
        graphQlTester.document("""{ imageSizePresets(workspaceId: $workspace) { name } }""")
            .execute().path("imageSizePresets[*].name").entityList(String::class.java).get()

    private fun add(name: String, width: Int, height: Int) = graphQlTester.document(
        """mutation { addImageSizePreset(workspaceId: $workspaceId, name: "$name", width: $width, height: $height) { id name width height position } }""",
    ).execute()

    /** A workspace made through the door arrives with its seven, in order, largest and commonest first. */
    @Test
    fun `a new workspace is given the seven presets in order`() {
        val made = graphQlTester.document("""mutation { createWorkspace(input: { name: "gallery" }) { id } }""")
            .execute().path("createWorkspace.id").entity(Long::class.java).get()

        // Straight off the table, so this is the seeding and not the read's own.
        val seeded = presets.findByWorkspaceIdOrderByPositionAscIdAsc(made)
        assertThat(seeded.map { it.name }).containsExactly(
            "Square 1024", "Landscape 1536", "Portrait 1536", "Wide 1792", "Tall 1792", "Square 512", "Square 256",
        )
        assertThat(seeded.map { it.width to it.height }).containsExactly(
            1024 to 1024, 1536 to 1024, 1024 to 1536, 1792 to 1024, 1024 to 1792, 512 to 512, 256 to 256,
        )
        assertThat(seeded.map { it.position }).containsExactly(0, 1, 2, 3, 4, 5, 6)

        graphQlTester.document("""{ imageSizePresets(workspaceId: $made) { name width height position workspaceId } }""")
            .execute()
            .path("imageSizePresets[1].name").entity(String::class.java).isEqualTo("Landscape 1536")
            .path("imageSizePresets[1].width").entity(Int::class.java).isEqualTo(1536)
            .path("imageSizePresets[1].height").entity(Int::class.java).isEqualTo(1024)
            .path("imageSizePresets[6].position").entity(Int::class.java).isEqualTo(6)
    }

    /** A workspace written straight into the table reads as the seven, and asking writes them down. */
    @Test
    fun `a workspace nobody seeded is seeded on first read`() {
        assertThat(presets.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)).isEmpty()
        assertThat(namesOf(workspaceId)).hasSize(7).startsWith("Square 1024")
        assertThat(presets.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)).hasSize(7)
    }

    @Test
    fun `a preset can be added, changed, moved and removed, and each is audited`() {
        namesOf(workspaceId)

        val id = add("Thumbnail", 600, 400)
            .path("addImageSizePreset.position").entity(Int::class.java).isEqualTo(7)
            .path("addImageSizePreset.id").entity(Long::class.java).get()
        assertThat(namesOf(workspaceId)).endsWith("Square 256", "Thumbnail")

        graphQlTester.document("""mutation { updateImageSizePreset(id: $id, name: "Catalogue thumb", width: 640) { name width height } }""")
            .execute()
            .path("updateImageSizePreset.name").entity(String::class.java).isEqualTo("Catalogue thumb")
            .path("updateImageSizePreset.width").entity(Int::class.java).isEqualTo(640)
            .path("updateImageSizePreset.height").entity(Int::class.java).isEqualTo(400)

        val ids = presets.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).map { requireNotNull(it.id) }
        val moved = listOf(ids.last()) + ids.dropLast(1)
        graphQlTester.document("""mutation { reorderImageSizePresets(workspaceId: $workspaceId, ids: [${moved.joinToString()}]) { name position } }""")
            .execute()
            .path("reorderImageSizePresets[0].name").entity(String::class.java).isEqualTo("Catalogue thumb")
            .path("reorderImageSizePresets[0].position").entity(Int::class.java).isEqualTo(0)
        assertThat(namesOf(workspaceId)).startsWith("Catalogue thumb", "Square 1024")

        graphQlTester.document("""mutation { reorderImageSizePresets(workspaceId: $workspaceId, ids: [${ids[0]}, ${ids[1]}]) { name } }""")
            .execute().errors().expect { it.message?.contains("once each") == true }.verify()

        graphQlTester.document("""mutation { removeImageSizePreset(id: $id) }""")
            .execute().path("removeImageSizePreset").entity(Boolean::class.java).isEqualTo(true)
        assertThat(namesOf(workspaceId)).hasSize(7).doesNotContain("Catalogue thumb")

        graphQlTester.document("""mutation { removeImageSizePreset(id: $id) }""")
            .execute().errors().expect { it.message?.contains("No image size preset with id $id") == true }.verify()

        assertThat(audit.findAll().map { it.message }).contains(
            "Image size preset Thumbnail (600x400) added",
            "Image size preset Thumbnail (600x400) is now Catalogue thumb (640x400)",
            "Image size preset Catalogue thumb (640x400) removed",
        ).anyMatch { it.startsWith("Image size presets reordered: Catalogue thumb, Square 1024") }
    }

    /** A side is a whole number of pixels from 1 to 8192; a name is one to sixty characters. */
    @Test
    fun `a bad side or a blank name is refused`() {
        add("Nothing wide", 0, 400).errors()
            .expect { it.message == "A preset's width is a whole number of pixels from 1 to 8192; 0 is not" }.verify()
        add("Too tall", 400, 9000).errors()
            .expect { it.message == "A preset's height is a whole number of pixels from 1 to 8192; 9000 is not" }.verify()
        add("   ", 400, 400).errors()
            .expect { it.message?.contains("needs a name") == true }.verify()
        add("Ceiling", 8192, 8192).path("addImageSizePreset.width").entity(Int::class.java).isEqualTo(8192)

        val id = presets.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).last().id
        graphQlTester.document("""mutation { updateImageSizePreset(id: $id, height: -8) { height } }""")
            .execute().errors().expect { it.message?.contains("-8 is not") == true }.verify()
        assertThat(presets.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).last().height).isEqualTo(8192)
    }

    /** Only somebody who administers the workspace changes the menu. */
    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who does not administer the workspace cannot change its presets`() {
        add("Mine", 400, 400).errors().expect { true }.verify()
        assertThat(presets.findAll().none { it.name == "Mine" }).isTrue()
    }
}
