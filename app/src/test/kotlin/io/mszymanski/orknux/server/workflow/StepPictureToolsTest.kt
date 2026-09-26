package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper
import java.util.Optional

/**
 * The tool an agent inside a run is lent, and what it answers.
 *
 * What is pinned here is the three things that decide whether a picture happens
 * at all - an installation that keeps attachments, a workspace that has chosen
 * a model, and a description the model actually wrote - and the shape of the
 * answer, because the answer is what the next round reasons from. A refusal is
 * a sentence the agent reads and works around, never an exception that loses
 * the round. Issue #349.
 *
 * Whether the agent was granted pictures at all is decided one level up, in
 * [io.mszymanski.orknux.server.agent.AgentNodeRunner], because that is where
 * the agent is: this knows about a run and a workspace, not about whose round
 * it is.
 */
class StepPictureToolsTest {

    private val workspaces = mock(WorkspaceRepository::class.java)
    private val drawing = mock(ModelImageClient::class.java)
    private val pictures = mock(ExecutionPictureRepository::class.java)
    private val store = mock(AttachmentStore::class.java)
    private val settings = mock(InstallationSettings::class.java)
    /**
     * Where this installation is, which a picture's link has to carry: a model
     * pastes what it was handed, and a path has no host behind it wherever it
     * lands. Named here rather than left at the default so the assertions say
     * which half of the address they are about.
     */
    private val web = io.mszymanski.orknux.server.security.WebProperties(baseUrl = "https://orknux.example")

    private val mapper = ObjectMapper()

    private val steps = StepPictures(workspaces, web, drawing, pictures, store, settings)
    /**
     * The session store, in memory: what is under test is that a drawn picture
     * is *put* somewhere a plugin can read it, and under what name.
     */
    private val scratch = object : io.mszymanski.orknux.workflow.script.SessionScratch {
        val held = mutableMapOf<Pair<Long, String>, String>()
        var refuse: String? = null

        override fun put(sessionId: Long, key: String, json: String): String? {
            refuse?.let { return it }
            held[sessionId to key] = json
            return null
        }

        override fun get(sessionId: Long, key: String): String? = held[sessionId to key]

        override fun remove(sessionId: Long, key: String) {
            held.remove(sessionId to key)
        }
    }

    private val tools = StepPictureTools(mapper, steps, scratch)

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(): T = Mockito.any<T>() ?: (null as T)

    private fun workspace(imageModelId: Long? = 5) = Workspace(id = 9, name = "Acme")
        .also { it.imageModelId = imageModelId }

    private fun call(description: String?) = ToolCall(
        id = "call-1",
        name = "draw_picture",
        arguments = if (description == null) "{}" else mapper.writeValueAsString(mapOf("description" to description)),
    )

    /**
     * A link, when the model asks for one.
     *
     * The drawing answers with a key and never a link: a key is what a tool
     * that uploads a file takes, and a link handed over unasked is a link
     * pasted into a chat that cannot resolve it, printing the construction
     * where the picture should have been. What that left out is the model that
     * wants the picture *inside* what it is writing, which has to name it
     * somehow. This is that door, and being asked for is the whole difference.
     */
    @Test
    fun `a model that wants the picture in its answer can ask for the markdown`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.findByExecutionIdOrderByDrawnAtAscIdAsc(100)).thenReturn(listOf(filed()))

        val shed = requireNotNull(tools.shed(100, "ask", 9, sessionId = 55))
        val answer = mapper.readTree(shed.run(linkCall("picture.77")))

        assertThat(answer.path("markdown").stringValue())
            .describedAs("markdown, so the model does not compose a link around an id")
            .isEqualTo("![a red bicycle](https://orknux.example/api/execution-pictures/77)")
        // Absolute, for the reason every link handed to a model is: a path has
        // no host behind it once it has been copied somewhere else.
        assertThat(answer.path("url").stringValue())
            .isEqualTo("https://orknux.example/api/execution-pictures/77")
    }

    /** The bare id is the same id, and is not worth a round trip to refuse. */
    @Test
    fun `the key may be written without its prefix`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.findByExecutionIdOrderByDrawnAtAscIdAsc(100)).thenReturn(listOf(filed()))

        val shed = requireNotNull(tools.shed(100, "ask", 9, sessionId = 55))

        assertThat(mapper.readTree(shed.run(linkCall("77"))).path("markdown").stringValue())
            .contains("/api/execution-pictures/77")
    }

    /**
     * And a key this run has nothing under is refused.
     *
     * A key is a row id, so a model that guessed a number would otherwise be
     * handed another run's picture - and an agent in one workspace could show
     * somebody a drawing made in another.
     */
    @Test
    fun `a key from somewhere else gets nothing`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.findByExecutionIdOrderByDrawnAtAscIdAsc(100)).thenReturn(listOf(filed()))

        val shed = requireNotNull(tools.shed(100, "ask", 9, sessionId = 55))
        val answer = mapper.readTree(shed.run(linkCall("picture.404")))

        assertThat(answer.has("markdown")).isFalse()
        assertThat(answer.path("reason").stringValue()).contains("No picture of this run has that key")
    }

    /**
     * Unticked, the tool is not there and the name is not answered.
     *
     * Both halves matter: a model is only offered tools that will run, and a
     * name a shed does not claim falls through to the agent's own tools rather
     * than being answered by this.
     */
    @Test
    fun `an agent that may not link is offered no such tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        val shed = requireNotNull(tools.shed(100, "ask", 9, sessionId = 55, mayLink = false))

        assertThat(shed.specs().map { it.name }).containsExactly("draw_picture")
        assertThat(shed.handles("picture_link")).isFalse()
    }

    private fun linkCall(key: String) = ToolCall(
        id = "call-2",
        name = "picture_link",
        arguments = mapper.writeValueAsString(mapOf("key" to key)),
    )

    private fun drawn() = Picture.Drawn(
        image = byteArrayOf(1, 2, 3),
        contentType = "image/png",
        millis = 40,
    )

    private fun filed(id: Long = 77) = ExecutionPicture(
        id = id,
        executionId = 100,
        nodeKey = "ask",
        workspaceId = 9,
        prompt = "a red bicycle",
        filename = "a-red-bicycle.png",
        contentType = "image/png",
        sizeBytes = 3,
        location = "9/a-red-bicycle.png",
    )

    @Test
    fun `an installation that keeps no attachments is offered no tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(false)

        assertThat(tools.shed(100, "ask", 9)).isNull()
    }

    @Test
    fun `a workspace that has chosen no image model is offered no tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace(imageModelId = null)))

        assertThat(tools.shed(100, "ask", 9)).isNull()
    }

    @Test
    fun `an agent that was not granted pictures is offered no tool`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        assertThat(tools.shed(100, "ask", 9, granted = false)).isNull()
        // And the installation being able to draw is not enough on its own.
        assertThat(tools.shed(100, "ask", 9, granted = true)).isNotNull()
    }

    @Test
    fun `a workspace that can draw is offered the drawing and the link, by name`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        val shed = requireNotNull(tools.shed(100, "ask", 9))

        // Two, and the second is a door rather than a second way to draw: the
        // drawing answers with a key, and this is where a model that wants the
        // picture inside what it writes asks for an address.
        assertThat(shed.specs().map { it.name }).containsExactly("draw_picture", "picture_link")
        assertThat(shed.specs().first().parameters.map { it.name }).containsExactly("description")
        assertThat(shed.specs().last().parameters.map { it.name }).containsExactly("key")
        assertThat(shed.handles("draw_picture")).isTrue()
        assertThat(shed.handles("chat_draw_picture")).isFalse()
    }

    @Test
    fun `a drawing is filed against this step of this run, and the answer says where it is`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(5, "a red bicycle")).thenReturn(drawn())
        `when`(store.put(anyLong(), anyString(), anyOf())).thenReturn("9/a-red-bicycle.png")
        `when`(pictures.save(anyOf<ExecutionPicture>())).thenReturn(filed())

        val answer = mapper.readTree(
            requireNotNull(tools.shed(100, "ask", 9, sessionId = 55)).run(call("a red bicycle")),
        )

        assertThat(answer.path("drawn").booleanValue()).isTrue()
        /*
         * A key, because a key is the thing that can be delivered.
         *
         * The bytes are in the session's store under it, which is what every
         * tool that uploads a file takes. Handed only a link, a model that
         * wanted to show somebody the picture pasted the markdown - and a chat
         * with no document to resolve an address against printed the
         * construction instead.
         */
        assertThat(answer.path("key").stringValue()).isEqualTo("picture.77")
        assertThat(scratch.held[55L to "picture.77"]).isEqualTo(mapper.writeValueAsString("AQID"))
        assertThat(answer.path("note").stringValue()).contains("pass it to whichever of your tools")

        /*
         * And the note names no tool.
         *
         * It named `slack_uploadBinary`, which is a tool the Slack plugin
         * brings: core telling a model to call something that is only there on
         * an installation that loaded that plugin. What tools an agent has is
         * the agent's own business, and it can read its own list - so the note
         * says what kind of tool to look for and stops.
         */
        assertThat(answer.path("note").stringValue())
            .describedAs("core naming a plugin's tool")
            .doesNotContain("slack")

        /*
         * And no markdown at all.
         *
         * It was there so the run's own interface had a line to draw - but the
         * interface draws the picture from the row, and the model, handed a
         * link and no other way to deliver anything, pasted the link into a
         * chat that printed the construction instead of a picture. What comes
         * back is the one thing that can be acted on.
         */
        assertThat(answer.has("markdown")).isFalse()
        assertThat(answer.has("url")).isFalse()

        // Against the step that drew it, so the run graph draws it under that
        // node whatever the agent goes on to say.
        val saved = org.mockito.ArgumentCaptor.forClass(ExecutionPicture::class.java)
        verify(pictures).save(saved.capture())
        val row = requireNotNull(saved.value)
        assertThat(row.executionId).isEqualTo(100)
        assertThat(row.nodeKey).isEqualTo("ask")
        assertThat(row.workspaceId).isEqualTo(9)
        assertThat(row.prompt).isEqualTo("a red bicycle")
    }

    /**
     * An installation that has not said where it is still writes a link.
     *
     * The opposite of the rule a mail follows. `IssueNewsMail` writes no link
     * at all on a blank base, because a broken link in somebody's inbox is
     * worse than none - but a picture handed to a model with no host in front
     * of it is not a worse link, it is the markdown printed out in a Slack
     * thread. The development address is a guess; the construction is a bug.
     */
    @Test
    fun `a blank base url still produces a link, not a path`() {
        val unsaid = StepPictures(
            workspaces,
            io.mszymanski.orknux.server.security.WebProperties(baseUrl = ""),
            drawing,
            pictures,
            store,
            settings,
        )

        assertThat(unsaid.urlOf(7)).isEqualTo("http://localhost:5173/api/execution-pictures/7")
    }

    /** A base with a trailing slash does not become a double one. */
    @Test
    fun `a base url is joined once`() {
        val trailing = StepPictures(
            workspaces,
            io.mszymanski.orknux.server.security.WebProperties(baseUrl = "https://orknux.example/"),
            drawing,
            pictures,
            store,
            settings,
        )

        assertThat(trailing.urlOf(7)).isEqualTo("https://orknux.example/api/execution-pictures/7")
    }

    /**
     * A node that keeps no session has nowhere to leave the bytes.
     *
     * Said by not carrying a key, and by a note that tells the model to say
     * where the picture is rather than promise to send it - a promise it
     * cannot keep is worse than a sentence naming a page.
     */
    @Test
    fun `a node with no session answers no key, and says why`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(5, "a red bicycle")).thenReturn(drawn())
        `when`(store.put(anyLong(), anyString(), anyOf())).thenReturn("9/a-red-bicycle.png")
        `when`(pictures.save(anyOf<ExecutionPicture>())).thenReturn(filed())

        val answer = mapper.readTree(
            requireNotNull(tools.shed(100, "ask", 9, sessionId = null)).run(call("a red bicycle")),
        )

        assertThat(answer.path("drawn").booleanValue()).isTrue()
        assertThat(answer.has("key")).isFalse()
        assertThat(answer.path("note").stringValue()).contains("nothing here can upload it")
        // The picture is still filed and still drawn under the node; what is
        // missing is only the way to hand the bytes to something else.
        assertThat(scratch.held).isEmpty()
    }

    /**
     * And a store that refused them says so the same way.
     *
     * It has a size of its own and a picture is large; an answer that carried
     * a key to nothing would have the model upload an empty file and report
     * success.
     */
    @Test
    fun `a store that refused the bytes answers no key either`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(5, "a red bicycle")).thenReturn(drawn())
        `when`(store.put(anyLong(), anyString(), anyOf())).thenReturn("9/a-red-bicycle.png")
        `when`(pictures.save(anyOf<ExecutionPicture>())).thenReturn(filed())
        scratch.refuse = "a value is at most 8192 KB of JSON"

        val answer = mapper.readTree(
            requireNotNull(tools.shed(100, "ask", 9, sessionId = 55)).run(call("a red bicycle")),
        )

        assertThat(answer.has("key")).isFalse()
        assertThat(answer.path("drawn").booleanValue()).isTrue()
        scratch.refuse = null
    }

    @Test
    fun `a call with nothing to draw is refused in words, and nothing is drawn`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("   ")))

        assertThat(answer.path("drawn").booleanValue()).isFalse()
        assertThat(answer.path("reason").stringValue()).contains("draw_picture takes a description")
        verify(drawing, never()).draw(anyLong(), anyString(), anyOf())
    }

    @Test
    fun `a provider that refused says so to the agent rather than failing the run`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(5, "a red bicycle")).thenReturn(Picture.Failed("The description was rejected."))

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("a red bicycle")))

        assertThat(answer.path("drawn").booleanValue()).isFalse()
        assertThat(answer.path("reason").stringValue()).isEqualTo("The description was rejected.")
        verify(pictures, never()).save(anyOf<ExecutionPicture>())
    }

    @Test
    fun `a run that has drawn its fill is refused, and the sentence says how many`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(StepPictures.MOST_PICTURES.toLong())

        val answer = mapper.readTree(requireNotNull(tools.shed(100, "ask", 9)).run(call("a red bicycle")))

        assertThat(answer.path("drawn").booleanValue()).isFalse()
        assertThat(answer.path("reason").stringValue()).contains("${StepPictures.MOST_PICTURES} pictures")
        verify(drawing, never()).draw(anyLong(), anyString(), anyOf())
    }

    @Test
    fun `a bracket in the description does not close the alt text early`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(workspaces.findById(9)).thenReturn(Optional.of(workspace()))
        `when`(pictures.countByExecutionId(100)).thenReturn(0)
        `when`(drawing.draw(anyLong(), anyString(), anyOf())).thenReturn(drawn())
        `when`(store.put(anyLong(), anyString(), anyOf())).thenReturn("9/x.png")
        val awkward = filed().let {
            ExecutionPicture(
                id = 78,
                executionId = 100,
                nodeKey = "ask",
                workspaceId = 9,
                prompt = "a bicycle [red]\nand a hill",
                filename = it.filename,
                contentType = it.contentType,
                sizeBytes = it.sizeBytes,
                location = it.location,
            )
        }
        `when`(pictures.save(anyOf<ExecutionPicture>())).thenReturn(awkward)

        requireNotNull(tools.shed(100, "ask", 9)).run(call("a bicycle [red]\nand a hill"))

        /*
         * Asked of the service rather than of the answer: the tool hands back
         * a key and nothing else, and the markdown is what the run's own
         * interface composes from the row. This pins the alt text where it is
         * actually made.
         */
        assertThat(steps.linkTo(awkward))
            .isEqualTo("![a bicycle red and a hill](https://orknux.example/api/execution-pictures/78)")
    }
}
