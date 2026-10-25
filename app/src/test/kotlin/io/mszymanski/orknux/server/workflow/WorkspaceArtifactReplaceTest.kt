package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

/**
 * A saved artifact given a new file in place: the key, the page and the
 * download address stay what they were, so a link somebody was sent opens the
 * new file - where every re-publish used to be a new `SAVED-n` and the old link
 * went on showing the old one.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceArtifactReplaceTest(
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val held: InstallationSettingRepository,
    @Autowired val artifactsApi: WorkspaceArtifactAPI,
    @Autowired val context: WebApplicationContext,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun seed() {
        forget()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "artifact-replace-${System.nanoTime()}")).id)
    }

    @AfterEach
    fun forget() {
        held.findAll().filter { it.name == SettingNames.UPLOAD_MAX_MB }.forEach(held::delete)
    }

    /** Sent with the signed-in user put back afterwards, which the security filter clears. */
    private fun send(request: RequestBuilder): MockHttpServletResponse {
        val signedIn = SecurityContextHolder.getContext().authentication
        return try {
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
                .perform(request).andReturn().response
        } finally {
            SecurityContextHolder.getContext().authentication = signedIn
        }
    }

    private fun upload(into: Long, name: String, bytes: ByteArray): SavedArtifact {
        val answered = send(
            multipart("/api/workspaces/$into/artifacts")
                .file(MockMultipartFile("file", name, "text/plain", bytes))
                .param("description", "The first draft")
                .with(csrf()),
        )
        assertThat(answered.status).describedAs(answered.contentAsString).isEqualTo(200)
        return saved.findAll().single { it.workspaceId == into && it.name == name }
    }

    private fun replace(into: Long, key: String, name: String, bytes: ByteArray, description: String? = null) = send(
        multipart("/api/workspaces/$into/artifacts/$key")
            .file(MockMultipartFile("file", name, "text/html", bytes))
            .apply { if (description != null) param("description", description) }
            .with(csrf()),
    )

    @Test
    fun `a replaced artifact keeps its key and its address and serves the new file`() {
        val first = upload(workspaceId, "report.txt", "the old words".toByteArray())
        val id = requireNotNull(first.id)
        val oldLocation = first.location
        val before = requireNotNull(artifactsApi.workspaceArtifact(workspaceId, "SAVED-$id"))

        val answered = replace(workspaceId, "SAVED-$id", "report.html", "<p>the new words</p>".toByteArray())
        assertThat(answered.status).describedAs(answered.contentAsString).isEqualTo(200)
        val said = mapper.readTree(answered.contentAsString)
        assertThat(said.path("id").asString()).isEqualTo("SAVED-$id")
        assertThat(said.path("url").asString()).isEqualTo("/workspace/$workspaceId/artifacts/SAVED-$id")
        assertThat(said.path("download").asString()).isEqualTo("/api/artifacts/$id")
        assertThat(said.path("name").asString()).isEqualTo("report.html")

        val now = saved.findAll().filter { it.workspaceId == workspaceId }
        assertThat(now.map { it.id }).describedAs("the same row, not a second one").containsExactly(id)
        val row = now.single()
        assertThat(row.name).describedAs("the name follows the new file, since the type is read off it").isEqualTo("report.html")
        assertThat(row.contentType).isEqualTo("text/html")
        assertThat(row.description).describedAs("kept when none is sent").isEqualTo("The first draft")
        assertThat(store.exists(oldLocation)).describedAs("the old file is gone from the store").isFalse()

        // The same address hands over the new bytes, and says it must not be kept by the browser.
        val served = send(get("/api/artifacts/$id"))
        assertThat(served.status).isEqualTo(200)
        assertThat(served.contentAsString).isEqualTo("<p>the new words</p>")
        assertThat(served.getHeader("Cache-Control")).contains("no-store")
        val read = send(get("/api/artifacts/$id/preview"))
        assertThat(read.contentAsString).isEqualTo("<p>the new words</p>")
        assertThat(read.getHeader("Cache-Control")).contains("no-store")

        // And what the page is handed for it is marked anew, so the viewer's frame and the picture reload.
        val after = requireNotNull(artifactsApi.workspaceArtifact(workspaceId, "SAVED-$id"))
        assertThat(after.url).startsWith("/api/artifacts/$id?v=").isNotEqualTo(before.url)
        assertThat(after.previewUrl).startsWith("/api/artifacts/$id/preview?v=")

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .contains("Artifact report.txt replaced with report.html")
    }

    @Test
    fun `a description sent with the new file replaces the old one, and a number or a link names the artifact too`() {
        val first = upload(workspaceId, "notes.txt", "one".toByteArray())
        val id = requireNotNull(first.id)

        val answered = replace(workspaceId, "$id", "notes.txt", "two".toByteArray(), description = "The second draft")
        assertThat(answered.status).describedAs(answered.contentAsString).isEqualTo(200)
        val row = saved.findAll().single { it.workspaceId == workspaceId }
        assertThat(row.description).isEqualTo("The second draft")
        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }).contains("Artifact notes.txt replaced")
    }

    @Test
    fun `an unknown key is answered 404 in words, and another kind's key is refused`() {
        val unknown = replace(workspaceId, "SAVED-987654321", "a.txt", "x".toByteArray())
        assertThat(unknown.status).isEqualTo(404)
        assertThat(mapper.readTree(unknown.contentAsString).path("error").asString())
            .isEqualTo("This workspace has no saved artifact SAVED-987654321.")

        val picture = replace(workspaceId, "IMAGE-1", "a.png", "x".toByteArray())
        assertThat(picture.status).isEqualTo(400)
        assertThat(mapper.readTree(picture.contentAsString).path("error").asString())
            .isEqualTo("Only a file saved to Artifacts can be replaced, and IMAGE-1 is not one.")
    }

    @Test
    fun `a file past the upload limit is answered 413 and the old file stays`() {
        val first = upload(workspaceId, "data.csv", "a,b".toByteArray())
        settings.setMaxFileSizeMb(1, "alice")

        val refused = replace(workspaceId, "SAVED-${first.id}", "data.csv", ByteArray(2 * 1024 * 1024))
        assertThat(refused.status).isEqualTo(413)
        assertThat(mapper.readTree(refused.contentAsString).path("error").asString())
            .isEqualTo("data.csv is 2 MB; uploads are limited to 1 MB - an administrator can raise it in Admin Settings → Attachments")
        val row = saved.findAll().single { it.workspaceId == workspaceId }
        assertThat(row.location).isEqualTo(first.location)
        assertThat(store.exists(first.location)).isTrue()
    }

    @Test
    fun `another workspace's artifact is not replaced through this one`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "artifact-replace-other-${System.nanoTime()}")).id)
        val theirs = upload(elsewhere, "theirs.txt", "theirs".toByteArray())

        val answered = replace(workspaceId, "SAVED-${theirs.id}", "mine.txt", "mine".toByteArray())
        assertThat(answered.status).isEqualTo(404)
        val row = saved.findAll().single { it.id == theirs.id }
        assertThat(row.name).isEqualTo("theirs.txt")
        assertThat(store.open(row.location).use { it.readBytes() }).isEqualTo("theirs".toByteArray())
    }

    @Test
    fun `somebody who cannot see the workspace cannot replace its artifact`() {
        val first = upload(workspaceId, "private.txt", "kept".toByteArray())
        val answered = send(
            multipart("/api/workspaces/$workspaceId/artifacts/SAVED-${first.id}")
                .file(MockMultipartFile("file", "private.txt", "text/plain", "changed".toByteArray()))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("bob").roles("USERS"))
                .with(csrf()),
        )
        assertThat(answered.status).isEqualTo(404)
        val row = saved.findAll().single { it.id == first.id }
        assertThat(store.open(row.location).use { it.readBytes() }).isEqualTo("kept".toByteArray())
    }
}
