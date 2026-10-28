package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workflow.SavedArtifacts
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * A picture drawn in Design hosted as an artifact.
 *
 * Pinned: hosting answers a SAVED artifact that serves the picture's bytes;
 * the picture changed and saved in Design is what the artifact serves next,
 * with nothing copied; hosting again answers the same artifact; a hosted
 * artifact cannot be replaced as a file of its own; stopping hosting removes
 * the artifact and leaves the picture, file and all; removing the picture
 * removes its artifact; each is audited; and somebody who cannot see the
 * workspace hosts nothing.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignImageHostingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val savedArtifacts: SavedArtifacts,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "hosting ${System.nanoTime()}")).id)
    }

    @Test
    fun `a hosted picture is served by its artifact as it is saved in Design, and hosting again answers the same`() {
        val red = png(0xC81E1E)
        val picture = import(red, "Poster")

        val hosted = host(picture)
        assertThat(hosted["id"]).asString().startsWith("SAVED-")
        assertThat(hosted["hostOf"]).isEqualTo(picture)
        val number = hosted["id"].toString().removePrefix("SAVED-")
        assertThat(download("/api/artifacts/$number").contentAsByteArray).isEqualTo(red)

        // The picture says where it is hosted, in the gallery and on its own.
        graphQlTester.document("""{ workspaceArtifact(workspaceId: $workspaceId, id: "$picture") { hostedAs } }""")
            .execute().path("workspaceArtifact.hostedAs").entity(String::class.java).isEqualTo(hosted["id"].toString())
        graphQlTester.document("""{ workspaceArtifacts(workspaceId: $workspaceId, drawn: true) { content { id hostedAs } } }""")
            .execute().path("workspaceArtifacts.content[0].hostedAs").entity(String::class.java).isEqualTo(hosted["id"].toString())

        // Changed and saved in Design, the way the image editor saves: a version, then Save over the picture.
        val blue = png(0x1E64C8)
        val version = graphQlTester.document(
            """mutation { keepDesignImageChange(input: {
                 workspaceId: $workspaceId, imageId: "$picture", image: "${Base64.getEncoder().encodeToString(blue)}", change: "Painted blue"
               }) { image { id } } }""",
        ).execute().path("keepDesignImageChange.image.id").entity(String::class.java).get()
        // An unsaved change is the picture's draft, not what it is: the artifact still serves red.
        assertThat(download("/api/artifacts/$number").contentAsByteArray).isEqualTo(red)
        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$version") { id } }""").execute()
        assertThat(download("/api/artifacts/$number").contentAsByteArray).isEqualTo(blue)
        // Its listed address moves with it, so nothing that caches by address shows the old picture.
        assertThat(saved.findById(number.toLong()).get().location)
            .isEqualTo(savedImageLocation(picture))

        // Hosting again is the same artifact, and there is still one.
        assertThat(host(picture)["id"]).isEqualTo(hosted["id"])
        assertThat(saved.findAll().count { it.workspaceId == workspaceId }).isEqualTo(1)

        // Replaced as a file of its own, it would stop being the picture: refused, saying where to change it.
        val replaced = savedArtifacts.replaceUpload(workspaceId, number.toLong(), "other.png", null, png(0x00FF00))
        assertThat(replaced).isInstanceOf(SavedArtifacts.Replacing.Refused::class.java)
        assertThat((replaced as SavedArtifacts.Replacing.Refused).reason).contains("change the picture in Design")

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .contains("Image Poster hosted as artifact")
            .containsOnlyOnce("Image Poster hosted as artifact")
    }

    @Test
    fun `stopping hosting removes the artifact and leaves the picture, and removing the picture removes its artifact`() {
        val bytes = png(0x123456)
        val picture = import(bytes, "Banner")
        val number = host(picture)["id"].toString().removePrefix("SAVED-")

        graphQlTester.document("""mutation { unhostDesignImage(workspaceId: $workspaceId, id: "$picture") }""")
            .execute().path("unhostDesignImage").entity(Boolean::class.java).isEqualTo(true)
        assertThat(saved.findById(number.toLong())).isEmpty()
        assertThat(download("/api/artifacts/$number").status).isEqualTo(404)
        // The picture's own file is still there, and still the picture.
        assertThat(download("/api/design-images/${picture.removePrefix("DESIGN-")}").contentAsByteArray).isEqualTo(bytes)
        graphQlTester.document("""mutation { unhostDesignImage(workspaceId: $workspaceId, id: "$picture") }""")
            .execute().path("unhostDesignImage").entity(Boolean::class.java).isEqualTo(false)

        // Hosted again, then the artifact deleted from the Artifacts page: the same as stopping, the picture kept.
        val again = host(picture)["id"].toString()
        graphQlTester.document("""mutation { deleteArtifact(id: "$again") }""").execute()
        assertThat(download("/api/design-images/${picture.removePrefix("DESIGN-")}").contentAsByteArray).isEqualTo(bytes)

        // Hosted, then the picture removed: the artifact goes with it.
        val last = host(picture)["id"].toString().removePrefix("SAVED-")
        graphQlTester.document("""mutation { deleteArtifact(id: "$picture") }""").execute()
        assertThat(saved.findById(last.toLong())).isEmpty()

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .contains("Image Banner hosted as artifact", "Image Banner no longer hosted as artifact")
    }

    @Test
    fun `only a saved Design picture is hosted`() {
        val picture = import(png(0xABCDEF), "Draft")
        val version = graphQlTester.document(
            """mutation { keepDesignImageChange(input: {
                 workspaceId: $workspaceId, imageId: "$picture", image: "${Base64.getEncoder().encodeToString(png(0x111111))}", change: "Darker"
               }) { image { id } } }""",
        ).execute().path("keepDesignImageChange.image.id").entity(String::class.java).get()
        listOf(version, "SAVED-1", "DESIGN-999999999").forEach { id ->
            graphQlTester.document("""mutation { hostDesignImage(workspaceId: $workspaceId, id: "$id") { id } }""")
                .execute().errors().satisfy { assertThat(it.single().message).describedAs(id).contains("Only a picture saved in Design") }
        }
    }

    @Test
    fun `somebody who cannot see the workspace hosts nothing`() {
        val picture = import(png(0x222222), "Theirs")
        SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "mallory", "n/a", listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USERS")),
            )
        graphQlTester.document("""mutation { hostDesignImage(workspaceId: $workspaceId, id: "$picture") { id } }""")
            .execute().errors().expect { true }.verify()
        assertThat(saved.findAll().count { it.workspaceId == workspaceId }).isZero()
    }

    private fun import(bytes: ByteArray, name: String): String = graphQlTester.document(
        """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "${Base64.getEncoder().encodeToString(bytes)}", name: "$name" }) { id } }""",
    ).execute().path("importDesignImage.id").entity(String::class.java).get()

    private fun host(picture: String): Map<*, *> =
        graphQlTester.document("""mutation { hostDesignImage(workspaceId: $workspaceId, id: "$picture") { id hostOf url } }""")
            .execute().path("hostDesignImage").entity(Map::class.java).get()

    private fun savedImageLocation(picture: String): String =
        requireNotNull(context.getBean(DesignImageRepository::class.java).findById(picture.removePrefix("DESIGN-").toLong()).get().location)

    /** A download through the REST door; MockMvc clears the signed-in context after it, so it is put back. */
    private fun download(path: String): MockHttpServletResponse {
        val signedIn = SecurityContextHolder.getContext()
        try {
            return MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
                .perform(get(path)).andReturn().response
        } finally {
            SecurityContextHolder.setContext(signedIn)
        }
    }

    /** A 4x4 PNG of one colour, each a different file. */
    private fun png(rgb: Int): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 4) for (y in 0 until 4) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
