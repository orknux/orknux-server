package io.mszymanski.orknux.server.design

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.util.Base64

/**
 * The wireframe editor's Import: a picture from a file kept as a Design
 * picture, or one already in Orknux chosen by its id, each said in the audit
 * log and answered with where the editor draws it from - and an image element
 * naming it carried in the HTML the editor exports.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WireframePicturesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var wireframeId: Long = 0

    @BeforeEach
    fun make() {
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "wireframe-pictures-${System.nanoTime()}")).id)
        wireframeId = graphQlTester.document("""mutation { createWireframe(workspaceId: $workspaceId, name: "Login") { id } }""")
            .execute().path("createWireframe.id").entity(Long::class.java).get()
    }

    private val png get() = "data:image/png;base64," + Base64.getEncoder().encodeToString(DocumentAPITest.png())

    @Test
    fun `a picture from a file is kept as a Design picture and answered with where it is drawn from`() {
        val answer = graphQlTester.document(
            """mutation(${'$'}id: ID!, ${'$'}image: String) { importWireframePicture(id: ${'$'}id, image: ${'$'}image, name: "Logo") { artifact name src } }""",
        ).variable("id", wireframeId).variable("image", png).execute()
        val artifact = answer.path("importWireframePicture.artifact").entity(String::class.java).get()
        assertThat(artifact).startsWith("DESIGN-")
        answer.path("importWireframePicture.name").entity(String::class.java).isEqualTo("Logo")
            .path("importWireframePicture.src").entity(String::class.java).isEqualTo("/api/workspaces/$workspaceId/document-images/$artifact")
        assertThat(audit.findAll().map { it.message }).contains("Picture Logo imported into wireframe Login", "Image logo.png imported into Design")
    }

    @Test
    fun `a picture already in Orknux is chosen by its id, and one that is not this workspace's is refused`() {
        val kept = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}image: String) { importDesignImage(input: { workspaceId: ${'$'}w, image: ${'$'}image, name: "Red" }) { id } }""",
        ).variable("w", workspaceId).variable("image", png).execute().path("importDesignImage.id").entity(String::class.java).get()
        graphQlTester.document("""mutation { importWireframePicture(id: $wireframeId, artifact: "$kept") { artifact name } }""").execute()
            .path("importWireframePicture.artifact").entity(String::class.java).isEqualTo(kept)
            .path("importWireframePicture.name").entity(String::class.java).isEqualTo("Red")
        assertThat(audit.findAll().map { it.message }).contains("Picture Red imported into wireframe Login")

        graphQlTester.document("""mutation { importWireframePicture(id: $wireframeId, artifact: "DESIGN-999999999") { artifact } }""").execute()
            .errors().expect {
                it.message == "The picture cannot be put on the wireframe: there is no picture DESIGN-999999999 in this workspace" &&
                    it.extensions["code"] == "WireframePictureRefused"
            }.verify()
        graphQlTester.document("""mutation { importWireframePicture(id: $wireframeId) { artifact } }""").execute()
            .errors().expect { it.message == "The picture cannot be put on the wireframe: there is no picture to bring in" }.verify()
    }

    @Test
    fun `an image element naming a picture carries it in the HTML the editor exports`() {
        val kept = graphQlTester.document(
            """mutation(${'$'}id: ID!, ${'$'}image: String) { importWireframePicture(id: ${'$'}id, image: ${'$'}image) { artifact } }""",
        ).variable("id", wireframeId).variable("image", png).execute().path("importWireframePicture.artifact").entity(String::class.java).get()
        val drawing = """{"elements":[{"id":"p","type":"image","x":0,"y":0,"w":40,"h":30,"text":"Logo","props":{"artifact":"$kept"}},
            {"id":"q","type":"image","x":50,"y":0,"w":40,"h":30}]}"""
        val page = graphQlTester.document("""mutation(${'$'}id: ID!, ${'$'}d: String) { exportWireframeHtml(id: ${'$'}id, document: ${'$'}d) }""")
            .variable("id", wireframeId).variable("d", drawing).execute().path("exportWireframeHtml").entity(String::class.java).get()
        assertThat(page).contains("<figure class=\"el image picture\" data-id=\"p\"", "<img src=\"data:image/png;base64,", "img-src data:")
        // One with no picture is still the placeholder.
        assertThat(page).contains("<figure class=\"el image\" data-id=\"q\"")
    }

    @Test
    fun `somebody who cannot see the wireframe cannot bring a picture onto it`() {
        val signedIn = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mallory", "x", listOf(SimpleGrantedAuthority("ROLE_USERS")))
        try {
            graphQlTester.document("""mutation(${'$'}id: ID!, ${'$'}image: String) { importWireframePicture(id: ${'$'}id, image: ${'$'}image) { artifact } }""")
                .variable("id", wireframeId).variable("image", png).execute().errors().expect { true }.verify()
        } finally {
            SecurityContextHolder.getContext().authentication = signedIn
        }
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("imported") }
    }
}
