package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.embedded.PdfCapability
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * A file a person attaches is readable by key at any size the upload limit
 * takes. Session 911: a 28 MB PDF was copied into the session's store as base64
 * JSON, refused there for being over Session Value Size, and given no key - so
 * `pdf_read` with the file's name answered that nothing was kept under it, and
 * the agent went looking for the file with `find /` on remote machines.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-attachment-keys"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAttachmentKeyTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val rows: ChatAttachmentRepository,
    @Autowired val files: AttachmentStore,
    @Autowired val shelf: ChatAttachmentShelf,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val settings: InstallationSettings,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val pdf: PdfCapability,
    @Autowired val capabilities: EmbeddedCapabilities,
    @Autowired val tuning: Tuning,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId = 0L
    private var session = 0L
    private var sizeBefore = 0

    @BeforeEach
    fun open() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "attachment-keys-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "attachment-keys-${System.nanoTime()}")
        sizeBefore = settings.sessionValueMaxMb()
        // The smallest Session Value Size there is, so a file of a few MB stands for the 28 MB one.
        graphQlTester.document("mutation { setSessionValueMaxMb(megabytes: 1) { sessionValueMaxMb } }").execute()
            .path("setSessionValueMaxMb.sessionValueMaxMb").entity(Int::class.java).isEqualTo(1)
    }

    @AfterEach
    fun restore() {
        settings.setSessionValueMaxMb(sizeBefore, "alice")
    }

    @Test
    fun `an attachment over Session Value Size gets a key, and pdf_read reads two of its pages`() {
        val pages = tuning[Tunable.PDF_LONG_PAGES] + 5
        val bytes = document(pages, noise = true)
        assertThat(bytes.size.toLong()).isGreaterThan(settings.sessionValueMaxMb() * 1024L * 1024L)
        val file = attach("ZE2601f.pdf", "application/pdf", bytes)
        val key = "${ChatAttachmentShelf.KEY_PREFIX}${file.id}"

        val note = requireNotNull(shelf.shelve(session, listOf(file)))

        assertThat(note).doesNotContain("could not be kept")
        assertThat(note).contains("- $key: ZE2601f.pdf (application/pdf, ", ", $pages pages)")
        assertThat(scratch.kindOf(session, key)).isEqualTo(io.mszymanski.orknux.workflow.script.StoredKind("application/pdf", true))

        val read = mapper.readTree(pdf.run(PdfCapability.READ, """{"contentKey":"$key","from":1,"to":2}""", workspaceId, session))
        assertThat(read.path("error").isMissingNode).withFailMessage(read.toString()).isTrue()
        assertThat(read.path("pages").intValue()).isEqualTo(pages)
        assertThat(read.path("from").intValue()).isEqualTo(1)
        assertThat(read.path("to").intValue()).isEqualTo(2)
        assertThat(read.path("html").stringValue()).contains("Chapter 1 begins", "Chapter 2 begins").doesNotContain("Chapter 3 begins")

        // And a search, which reads every page, finds the last one.
        val found = mapper.readTree(pdf.run(PdfCapability.SEARCH, """{"contentKey":"$key","query":"Chapter $pages begins"}""", workspaceId, session))
        assertThat(found.path("count").intValue()).withFailMessage(found.toString()).isEqualTo(1)

        // Nothing was copied into the store: the row names the attachment.
        assertThat(scratch.content(session, key)?.size).isEqualTo(bytes.size.toLong())
        assertThat(scratch.content(session, key)?.path).isNotNull()
    }

    @Test
    fun `the note on a long PDF says to read it a few pages at a time, in the reader's own words`() {
        val pages = tuning[Tunable.PDF_LONG_PAGES]
        val file = attach("long.pdf", "application/pdf", document(pages))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}${file.id}"

        val note = requireNotNull(shelf.shelve(session, listOf(file)))

        val (reader, keyParam) = requireNotNull(capabilities.readerOf("application/pdf"))
        val (from, to) = requireNotNull(capabilities.rangeOf("application/pdf"))
        val step = tuning[Tunable.PDF_STEP_PAGES]
        val line = note.lines().single { it.startsWith("- $key:") }
        assertThat(line).contains(
            "$pages pages)",
            "$step pages at a time with $reader {\"$keyParam\": \"$key\", \"$from\": 1, \"$to\": $step}",
            "\"$from\": ${step + 1}, \"$to\": ${2 * step}",
            "summarise each part before reading the next",
        )
        // The parameters it names are the reader's own.
        assertThat(capabilities.toolSpecs().single { it.name == reader }.parameters.map { it.name }).contains(keyParam, from, to)
    }

    @Test
    fun `a short PDF is told its pages and read whole`() {
        val file = attach("short.pdf", "application/pdf", document(3))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}${file.id}"

        val note = requireNotNull(shelf.shelve(session, listOf(file)))

        val line = note.lines().single { it.startsWith("- $key:") }
        assertThat(line).contains("3 pages)", "read it with").doesNotContain("at a time")
    }

    @Test
    fun `a file's name given for its key is answered with the key`() {
        val file = attach("ZE2601f.pdf", "application/pdf", document(2))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}${file.id}"
        requireNotNull(shelf.shelve(session, listOf(file)))

        val misnamed = mapper.readTree(pdf.run(PdfCapability.READ, """{"contentKey":"ZE2601f.pdf"}""", workspaceId, session))
        assertThat(misnamed.path("error").stringValue())
            .contains("Nothing in this session is kept under \"ZE2601f.pdf\"", "its key is $key")

        // A name that is no file here is answered as before.
        val unknown = mapper.readTree(pdf.run(PdfCapability.READ, """{"contentKey":"other.pdf"}""", workspaceId, session))
        assertThat(unknown.path("error").stringValue()).isEqualTo("Nothing in this session is kept under \"other.pdf\".")
    }

    @Test
    fun `a value a tool writes is still held to Session Value Size`() {
        val big = "\"" + "a".repeat(2 * 1024 * 1024) + "\""
        assertThat(scratch.put(session, "made.txt", big)).contains("Session Value Size")
    }

    private fun attach(name: String, type: String, bytes: ByteArray): ChatAttachment = rows.save(
        ChatAttachment(
            workspaceId = workspaceId, filename = name, contentType = type,
            sizeBytes = bytes.size.toLong(), location = files.put(workspaceId, name, bytes),
        ),
    )

    /** A PDF of [pages] pages, each saying which chapter begins; with [noise], a picture that does not compress. */
    private fun document(pages: Int, noise: Boolean = false): ByteArray = PDDocument().use { document ->
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        repeat(pages) { index ->
            val page = PDPage().also(document::addPage)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, 14f)
                content.newLineAtOffset(72f, 700f)
                content.showText("Chapter ${index + 1} begins here.")
                content.endText()
                if (noise && index == 0) {
                    val random = Random(7)
                    val picture = BufferedImage(900, 800, BufferedImage.TYPE_INT_RGB)
                    for (x in 0 until 900) for (y in 0 until 800) picture.setRGB(x, y, random.nextInt(0xFFFFFF))
                    content.drawImage(LosslessFactory.createFromImage(document, picture), 72f, 72f, 300f, 260f)
                }
            }
        }
        ByteArrayOutputStream().also(document::save).toByteArray()
    }
}
