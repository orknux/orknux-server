package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentStore
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

/**
 * A wireframe hosted as an HTML page.
 *
 * Pinned: hosting answers the wireframe with a SAVED artifact whose preview
 * serves the wireframe as a page; every save writes the page again - from the
 * editor's door and the orknux_* tools' alike - and the file it replaced is
 * removed; a rename renames the page; hosting again keeps the page it has; the
 * page cannot be replaced as a file of its own; stopping hosting removes the
 * page, file and all, and leaves the wireframe; removing the wireframe removes
 * its page and its file; each is audited once; and somebody who cannot see the
 * workspace hosts nothing.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WireframeHostingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val savedArtifacts: SavedArtifacts,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val wireframes: WireframeAPI,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "wireframe hosting ${System.nanoTime()}")).id)
    }

    @Test
    fun `a hosted wireframe's page is written again on every save, from every door, and on a rename`() {
        val id = create("Checkout", button("Pay now"))

        val hostedAs = host(id)
        assertThat(hostedAs).startsWith("SAVED-")
        val number = hostedAs.removePrefix("SAVED-").toLong()
        val first = page(number)
        assertThat(first.status).isEqualTo(200)
        assertThat(first.contentType).startsWith("text/html")
        assertThat(first.contentAsString).contains("Pay now").contains("<title>Checkout</title>")

        // The wireframe says where it is hosted, on its own and in the list; the artifact says what it is the page of.
        graphQlTester.document("""{ wireframes(workspaceId: $workspaceId) { id hostedAs } }""")
            .execute().path("wireframes[0].hostedAs").entity(String::class.java).isEqualTo(hostedAs)
        graphQlTester.document("""{ workspaceArtifact(workspaceId: $workspaceId, id: "$hostedAs") { hostOf previewUrl } }""")
            .execute().path("workspaceArtifact.hostOf").entity(String::class.java).isEqualTo("WIREFRAME-$id")

        // Saved from the editor's door: the page is the new drawing, and the file it was is gone.
        val before = saved.findById(number).get().location
        graphQlTester.document("""mutation { saveWireframe(id: $id, document: ${quoted(button("Pay later"))}) { revision } }""").execute()
        assertThat(page(number).contentAsString).contains("Pay later").doesNotContain("Pay now")
        assertThat(store.exists(before)).isFalse()

        // Saved through the door the orknux_* tools and the chat use.
        wireframes.saveElements(id, listOf(WireframeElement("b", WireframeElementType.BUTTON, 0, 0, 120, 40, "Pay by card", emptyMap())))
        assertThat(page(number).contentAsString).contains("Pay by card").doesNotContain("Pay later")

        // Renamed: the page's title and its file name follow.
        graphQlTester.document("""mutation { renameWireframe(id: $id, name: "Basket") { id } }""").execute()
        assertThat(page(number).contentAsString).contains("<title>Basket</title>")
        assertThat(saved.findById(number).get().name).isEqualTo("Basket.html")

        // Hosting again keeps the page it has, and there is still one.
        assertThat(host(id)).isEqualTo(hostedAs)
        assertThat(saved.findAll().count { it.workspaceId == workspaceId }).isEqualTo(1)

        // Put in place as a file of its own, it would be gone at the next save: refused, saying where to change it.
        val replaced = savedArtifacts.replaceUpload(workspaceId, number, "other.html", null, "<p>other</p>".toByteArray())
        assertThat(replaced).isInstanceOf(SavedArtifacts.Replacing.Refused::class.java)
        assertThat((replaced as SavedArtifacts.Replacing.Refused).reason).contains("change the wireframe instead")

        // Said once each, however often the page is written again.
        val said = audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }
        assertThat(said).containsOnlyOnce("Wireframe Checkout hosted as a page").noneMatch { it.contains("exported as HTML") }
    }

    @Test
    fun `stopping hosting removes the page and its file, and removing the wireframe removes both too`() {
        val id = create("Landing", button("Sign up"))
        val number = host(id).removePrefix("SAVED-").toLong()
        val location = saved.findById(number).get().location

        graphQlTester.document("""mutation { unhostWireframe(id: $id) { hostedAs } }""")
            .execute().path("unhostWireframe.hostedAs").valueIsNull()
        assertThat(saved.findById(number)).isEmpty()
        assertThat(store.exists(location)).isFalse()
        assertThat(page(number).status).isEqualTo(404)
        // The wireframe is as it was, and saving it now writes no page.
        graphQlTester.document("""{ wireframe(id: $id) { elementCount } }""")
            .execute().path("wireframe.elementCount").entity(Int::class.java).isEqualTo(1)
        graphQlTester.document("""mutation { saveWireframe(id: $id, document: ${quoted(button("Join"))}) { revision } }""").execute()
        assertThat(saved.findAll().count { it.workspaceId == workspaceId }).isZero()

        // Hosted again, then the wireframe removed: the page and its file go with it.
        val again = host(id).removePrefix("SAVED-").toLong()
        val file = saved.findById(again).get().location
        graphQlTester.document("""mutation { deleteWireframe(id: $id) }""").execute()
        assertThat(saved.findById(again)).isEmpty()
        assertThat(store.exists(file)).isFalse()

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .contains("Wireframe Landing hosted as a page", "Wireframe Landing no longer hosted as a page")
    }

    @Test
    fun `somebody who cannot see the workspace hosts nothing`() {
        val id = create("Theirs", button("Go"))
        SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "mallory", "n/a", listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USERS")),
            )
        graphQlTester.document("""mutation { hostWireframe(id: $id) { id } }""")
            .execute().errors().expect { true }.verify()
        assertThat(saved.findAll().count { it.workspaceId == workspaceId }).isZero()
    }

    private fun create(name: String, document: String): Long = graphQlTester.document(
        """mutation { createWireframe(workspaceId: $workspaceId, name: "$name", document: ${quoted(document)}) { id } }""",
    ).execute().path("createWireframe.id").entity(Long::class.java).get()

    private fun host(id: Long): String = graphQlTester.document("""mutation { hostWireframe(id: $id) { hostedAs } }""")
        .execute().path("hostWireframe.hostedAs").entity(String::class.java).get()

    private fun button(label: String) = """{"elements":[{"id":"b","type":"button","x":0,"y":0,"w":120,"h":40,"text":"$label"}]}"""

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The page as a browser opening the hosted address reads it; MockMvc clears the signed-in context after it, so it is put back. */
    private fun page(number: Long): MockHttpServletResponse {
        val signedIn = SecurityContextHolder.getContext()
        try {
            return MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
                .perform(get("/api/artifacts/$number/preview")).andReturn().response
        } finally {
            SecurityContextHolder.setContext(signedIn)
        }
    }
}
