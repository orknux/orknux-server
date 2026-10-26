package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Links between documents (#662).
 *
 * Pinned: a link is drawn with the linked document's name as it is now, not as
 * it was when the link was put in; one whose document is gone keeps the old
 * name and says so; a document knows which others mention it, and stops being
 * mentioned when the link goes; a new link has to go to a document of the same
 * workspace; and the Markdown a model reads and writes carries a link both ways.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentLinksTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "links-${System.nanoTime()}")).id)
    }

    private fun create(name: String, content: String? = null, workspace: Long = workspaceId): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}n: String!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: ${'$'}n, content: ${'$'}d) { id } }""",
    ).variable("w", workspace).variable("n", name).variable("d", content).execute().path("createDocument.id").entity(Long::class.java).get()

    private fun linkingTo(id: Long, name: String = "whatever it was called") =
        """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"See "},
            {"type":"documentLink","attrs":{"document":$id,"name":"$name"}}]}]}"""

    private fun link(of: Long): JsonNode {
        val content = graphQlTester.document("""{ document(id: $of) { content } }""").execute().path("document.content").entity(String::class.java).get()
        return mapper.readTree(content).path("content").get(0).path("content").get(1)
    }

    private fun mentionedBy(id: Long): List<String> =
        graphQlTester.document("""{ document(id: $id) { mentionedBy { name } } }""").execute()
            .path("document.mentionedBy[*].name").entityList(String::class.java).get()

    @Test
    fun `a link shows the linked document's current name, and a gone one keeps its own and says so`() {
        val target = create("Pricing")
        val notes = create("Notes", linkingTo(target, "Pricing"))

        graphQlTester.document("""mutation { renameDocument(id: $target, name: "Pricing 2027") { name } }""").execute()
        assertThat(link(notes).path("attrs").path("name").stringValue()).isEqualTo("Pricing 2027")
        assertThat(link(notes).path("attrs").has("missing")).isFalse()

        graphQlTester.document("""mutation { deleteDocument(id: $target) }""").execute()
        // Not renamed back to anything: the last name it was saved with, and a word that it is gone.
        assertThat(link(notes).path("attrs").path("name").stringValue()).isEqualTo("Pricing")
        assertThat(link(notes).path("attrs").path("missing").asBoolean()).isTrue()
    }

    @Test
    fun `a document knows who mentions it, and stops being mentioned when the link goes`() {
        val target = create("Pricing")
        val notes = create("Notes", linkingTo(target))
        create("Brief", linkingTo(target))
        create("Unrelated")

        assertThat(mentionedBy(target)).containsExactly("Brief", "Notes")
        assertThat(mentionedBy(notes)).isEmpty()

        graphQlTester.document("""mutation(${'$'}d: String!) { saveDocument(id: $notes, content: ${'$'}d) { revision } }""")
            .variable("d", """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Nothing linked"}]}]}""")
            .execute().path("saveDocument.revision").hasValue()
        assertThat(mentionedBy(target)).containsExactly("Brief")
    }

    @Test
    fun `a new link has to go to one of this workspace's documents`() {
        val elsewhere = create("Theirs", workspace = requireNotNull(workspaces.save(Workspace(name = "other-${System.nanoTime()}")).id))
        val notes = create("Notes")

        graphQlTester.document("""mutation(${'$'}d: String!) { saveDocument(id: $notes, content: ${'$'}d) { revision } }""")
            .variable("d", linkingTo(elsewhere)).execute().errors()
            .expect { it.message?.contains("there is no document $elsewhere in this workspace to link to") == true }.verify()
    }

    @Test
    fun `Markdown carries a link to a document both ways`() {
        val markdown = DocumentMarkdown(mapper)
        val read = markdown.document("Read [the pricing](document:12) and [the brief](/workspace/3/design/documents/14/view) first.")
        val inline = read.path("content").get(0).path("content")
        val links = (0 until inline.size()).map { inline.get(it) }.filter { it.path("type").stringValue() == "documentLink" }
        assertThat(links.map { it.path("attrs").path("document").asLong() }).containsExactly(12L, 14L)
        assertThat(links.map { it.path("attrs").path("name").stringValue() }).containsExactly("the pricing", "the brief")

        assertThat(markdown.write(read)).contains("[the pricing](document:12)", "[the brief](document:14)")
    }
}
