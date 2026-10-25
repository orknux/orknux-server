package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

/**
 * A file put onto the Artifacts page by a person: kept as a saved artifact with
 * the description given, answered with the address the page opens it at, and
 * recorded as an upload - not as one "for research", which the only upload
 * there was called every file.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceArtifactUploadTest(
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val context: WebApplicationContext,
    @Autowired val mapper: ObjectMapper,
) {

    @Test
    fun `a file uploaded is a saved artifact with its description, at the address the page opens it at`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "artifact-upload-${System.nanoTime()}")).id)
        val page = "<title>Report</title><p>Hello</p>".toByteArray()
        val signedIn = SecurityContextHolder.getContext().authentication
        val answered = try {
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
                .perform(
                    multipart("/api/workspaces/$workspaceId/artifacts")
                        .file(MockMultipartFile("file", "unreleased.html", "text/html", page))
                        .param("description", "Unreleased since v1.1.9")
                        .with(csrf()),
                ).andReturn().response
        } finally {
            SecurityContextHolder.getContext().authentication = signedIn
        }
        assertThat(answered.status).describedAs(answered.contentAsString).isEqualTo(200)
        val said = mapper.readTree(answered.contentAsString)
        val kept = saved.findAll().single { it.workspaceId == workspaceId }
        assertThat(said.path("id").asString()).isEqualTo("SAVED-${kept.id}")
        assertThat(said.path("url").asString()).isEqualTo("/workspace/$workspaceId/artifacts/SAVED-${kept.id}")
        assertThat(kept.name).isEqualTo("unreleased.html")
        assertThat(kept.description).isEqualTo("Unreleased since v1.1.9")
        assertThat(audit.findAll().map { it.message }).contains("Artifact unreleased.html uploaded").noneMatch { it.contains("for research") }

        // From the page's Upload there is no description: the file's name stands for one, which the store asks for.
        val bare = try {
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
                .perform(multipart("/api/workspaces/$workspaceId/artifacts").file(MockMultipartFile("file", "notes.txt", "text/plain", "x".toByteArray())).with(csrf()))
                .andReturn().response
        } finally {
            SecurityContextHolder.getContext().authentication = signedIn
        }
        assertThat(bare.status).describedAs(bare.contentAsString).isEqualTo(200)
        assertThat(saved.findAll().single { it.workspaceId == workspaceId && it.name == "notes.txt" }.description).isEqualTo("notes.txt")
    }
}
