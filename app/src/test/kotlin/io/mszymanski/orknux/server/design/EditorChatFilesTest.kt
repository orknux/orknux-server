package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.attachment.AttachmentAPI
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Files sent into the chats down the right of the document, wireframe and
 * diagram editors.
 *
 * Pinned: a picture uploaded through the attachments endpoint reaches the
 * model as an image part of the turn it was sent on; a text file, a PDF and a
 * CSV reach it as their text; a file somebody else uploaded is not read
 * whatever id is sent; a turn of files and no words is a message; and the
 * three editors' chats do the same, because they share one implementation.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class EditorChatFilesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val uploads: AttachmentAPI,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "editor-chat-files-${System.nanoTime()}")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `the document chat hands the model a picture as a picture and a text, a PDF and a CSV as their text`() {
        val modelId = model()
        val id = graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "Brief") { id } }""")
            .execute().path("createDocument.id").entity(Long::class.java).get()
        val picture = upload("red.png", "image/png", DocumentAPITest.png())
        val notes = upload("notes.txt", "text/plain", "The notes say we ship on Friday.".toByteArray())
        val pdf = upload("figures.pdf", "application/pdf", pdf("Quarterly figures rose"))
        val sheet = upload("regions.csv", "text/csv", "Region,Sales\nNorth,12\nSouth,9\n".toByteArray())
        val theirs = theirs("secret.txt", "Somebody else's plans.")

        graphQlTester.document(
            """mutation { askDocument(id: $id, modelId: $modelId, messages: [
                 {role: "user", content: "Write it up", attachmentIds: [$picture, $notes, $pdf, $sheet, $theirs]}
               ]) { answer } }""",
        ).execute().path("askDocument.answer").entity(String::class.java).isEqualTo("Done.")

        val asked = lastUserTurn(received.single())
        assertThat(images(asked)).singleElement().asString().startsWith("data:image/png;base64,")
        assertThat(text(asked)).contains(
            "Write it up",
            "notes.txt", "The notes say we ship on Friday.",
            "figures.pdf", "Quarterly figures rose",
            "regions.csv", "North\t12",
        ).doesNotContain("secret.txt", "Somebody else's plans.")
    }

    @Test
    fun `a file sent with no words is a message, and an earlier turn's picture is sent again`() {
        val modelId = model()
        val id = graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "Brief") { id } }""")
            .execute().path("createDocument.id").entity(Long::class.java).get()
        val picture = upload("red.png", "image/png", DocumentAPITest.png())

        graphQlTester.document(
            """mutation { askDocument(id: $id, modelId: $modelId, messages: [
                 {role: "user", content: "", attachmentIds: [$picture]},
                 {role: "assistant", content: "A red square."},
                 {role: "user", content: "Describe it in the document"}
               ]) { answer } }""",
        ).execute().path("askDocument.answer").entity(String::class.java).isEqualTo("Done.")

        val users = mapper.readTree(received.single()).path("messages").filter { it.path("role").stringValue() == "user" }
        assertThat(users).hasSize(2)
        assertThat(images(users.first())).hasSize(1)
    }

    @Test
    fun `the wireframe and diagram chats take files the same way`() {
        val modelId = model()
        val wireframe = graphQlTester.document("""mutation { createWireframe(workspaceId: $workspaceId, name: "Home") { id } }""")
            .execute().path("createWireframe.id").entity(Long::class.java).get()
        val diagram = graphQlTester.document("""mutation { createDiagram(workspaceId: $workspaceId, name: "Systems") { id } }""")
            .execute().path("createDiagram.id").entity(Long::class.java).get()
        val picture = upload("sketch.png", "image/png", DocumentAPITest.png())
        val brief = upload("brief.md", "text/markdown", "# Home\n\nA search box at the top.".toByteArray())

        graphQlTester.document(
            """mutation { askWireframe(id: $wireframe, modelId: $modelId, messages: [
                 {role: "user", content: "Draw this", attachmentIds: [$picture, $brief]}
               ]) { answer } }""",
        ).execute().path("askWireframe.answer").entity(String::class.java).isEqualTo("Done.")
        graphQlTester.document(
            """mutation { askDiagram(id: $diagram, modelId: $modelId, messages: [
                 {role: "user", content: "Map this", attachmentIds: [$picture, $brief]}
               ]) { answer } }""",
        ).execute().path("askDiagram.answer").entity(String::class.java).isEqualTo("Done.")

        assertThat(received).hasSize(2)
        received.forEach { body ->
            val asked = lastUserTurn(body)
            assertThat(images(asked)).hasSize(1)
            assertThat(text(asked)).contains("brief.md", "A search box at the top.")
        }
    }

    // -- Helpers ------------------------------------------------------------------

    private fun upload(name: String, type: String, bytes: ByteArray): Long {
        val answer = uploads.upload(workspaceId, listOf(MockMultipartFile("files", name, type, bytes))).body
        val held = mapper.valueToTree<JsonNode>(answer).path("attachments").first()
        return held.path("id").stringValue().toLong()
    }

    /** A file in the same workspace that somebody else uploaded. */
    private fun theirs(name: String, text: String): Long {
        val location = store.put(workspaceId, name, text.toByteArray())
        return requireNotNull(
            attachments.save(
                ChatAttachment(workspaceId = workspaceId, filename = name, contentType = "text/plain", sizeBytes = text.length.toLong(), location = location, uploadedBy = "bob"),
            ).id,
        )
    }

    private fun pdf(line: String): ByteArray = PDDocument().use { document ->
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { pen ->
            pen.beginText()
            pen.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            pen.newLineAtOffset(72f, 700f)
            pen.showText(line)
            pen.endText()
        }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    private fun lastUserTurn(body: String): JsonNode =
        mapper.readTree(body).path("messages").last { it.path("role").stringValue() == "user" }

    /** The words of a turn, whether it went as a string or as content parts. */
    private fun text(turn: JsonNode): String {
        val content = turn.path("content")
        return if (content.isString) content.stringValue() else content.filter { it.path("type").stringValue() == "text" }.joinToString("\n") { it.path("text").stringValue() }
    }

    private fun images(turn: JsonNode): List<String> =
        turn.path("content").filter { it.path("type").stringValue() == "image_url" }.map { it.path("image_url").path("url").stringValue() }

    /** A chat model that answers at once, without calling a tool. */
    private fun model(): Long {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Done."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        val endpoint = "http://${started.address.hostString}:${started.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
