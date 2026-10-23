package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.ChatAttachmentShelf
import io.mszymanski.orknux.server.attachment.ChatAttachments
import io.mszymanski.orknux.server.embedded.SvgCapability
import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionPrompt
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * An SVG reaching a model: no provider loads one as a picture - llama.cpp
 * answered 400 "Failed to load image or audio file" - and a chat's record sends
 * every earlier picture again, so one attached SVG made every later message in
 * its chat fail. The model is shown it drawn, and its markup is kept for
 * svg_read.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-svg-to-model"])
class SvgToModelTest(
    @Autowired val prompts: SessionPrompt,
    @Autowired val attachments: ChatAttachments,
    @Autowired val rows: ChatAttachmentRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val shelf: ChatAttachmentShelf,
    @Autowired val svg: SvgCapability,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val drawing = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10"><rect width="20" height="10" fill="#0ea5e9"/></svg>"""
    private var workspaceId = 0L

    @BeforeEach
    fun workspace() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "svg-to-model-${System.nanoTime()}")).id)
    }

    private fun attached(): ChatAttachment = rows.save(
        ChatAttachment(
            workspaceId = workspaceId,
            filename = "board.svg",
            contentType = "image/svg+xml",
            sizeBytes = drawing.length.toLong(),
            location = store.put(workspaceId, "board.svg", drawing.toByteArray()),
            uploadedBy = "alice",
        ),
    )

    @Test
    fun `an attached svg reaches the model drawn as a png`() {
        val sent = attachments.imagesOf(listOf(attached()))
        assertThat(sent).hasSize(1)
        assertThat(sent.single()).startsWith("data:image/png;base64,")
    }

    @Test
    fun `an svg a chat's record already holds is sent again drawn, so the chat recovers`() {
        val kept = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(drawing.toByteArray())
        val turn = prompts.decode(prompts.encode(ChatTurn(role = "user", content = "analyze", images = listOf(kept, "data:image/png;base64,AAAA"))))
        assertThat(turn.images[0]).startsWith("data:image/png;base64,")
        assertThat(turn.images[1]).isEqualTo("data:image/png;base64,AAAA")
    }

    @Test
    fun `its markup is kept for tools, and svg_read reads it`() {
        val session = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "svg-${System.nanoTime()}")).id)
        val file = attached()
        val note = requireNotNull(shelf.shelve(session, listOf(file)))
        val key = "${ChatAttachmentShelf.KEY_PREFIX}${file.id}"
        assertThat(note).contains(key).contains("svg_read")

        val read = mapper.readTree(svg.run(SvgCapability.READ, """{"key":"$key"}""", workspaceId, session))
        assertThat(read.path("svg").stringValue()).isEqualTo(drawing)
        assertThat(mapper.readTree(svg.run(SvgCapability.READ, """{"key":"nothing.here"}""", workspaceId, session)).path("error").stringValue())
            .contains("nothing.here")
    }
}
