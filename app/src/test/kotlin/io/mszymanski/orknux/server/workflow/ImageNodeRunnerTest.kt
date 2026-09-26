package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Optional

/**
 * An image node draws, files what it drew, and hands on where it is.
 *
 * The collaborators are the ones a chat's draw button and a task's tool already
 * use; what this pins is the node's own decisions - a missing model or a blank
 * prompt is a skipped step, a drawing is filed against the run and its url is
 * the step's output. [NodeExpressions] is the real one, so the prompt mapping is
 * resolved the way it is at run time. Issue #333.
 */
class ImageNodeRunnerTest {

    private val drawing = mock(ModelImageClient::class.java)
    private val store = mock(AttachmentStore::class.java)
    private val pictures = mock(ExecutionPictureRepository::class.java)
    private val executions = mock(WorkflowExecutionRepository::class.java)
    private val settings = mock(InstallationSettings::class.java)
    private val workspaces = mock(io.mszymanski.orknux.server.workspace.WorkspaceRepository::class.java)
    /**
     * Where this installation is, which a picture's link has to carry: a model
     * pastes what it was handed, and a path has no host behind it wherever it
     * lands. Named here rather than left at the default so the assertions say
     * which half of the address they are about.
     */
    private val web = io.mszymanski.orknux.server.security.WebProperties(baseUrl = "https://orknux.example")

    private val mapper = ObjectMapper()
    private val expressions = NodeExpressions(mapper)

    /*
     * The real one over mocked collaborators, not a mock of it: the filing is
     * what this test is about, and a mocked StepPictures would assert that the
     * node called something rather than that a picture was filed against the
     * run - which is the thing that has broken before.
     */
    private val steps = StepPictures(workspaces, web, drawing, pictures, store, settings)

    private val runner = ImageNodeRunner(steps, executions, settings, expressions, mapper)

    /** Kotlin-typed `any()`, so a null matcher does not trip a non-null parameter. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(): T = Mockito.any<T>() ?: (null as T)

    /** Kotlin-typed `eq()`, for the same reason: it must return the value, not null. */
    private fun <T> eqOf(value: T): T = Mockito.eq(value) ?: value

    private fun execution() = WorkflowExecution(
        workspaceId = 9,
        workflowId = 1,
        workflowName = "Draw things",
        status = ExecutionStatus.RUNNING,
        trigger = ExecutionTrigger.MANUAL,
        startedAt = OffsetDateTime.now(),
    )

    /** A prompt mapping, as the planner writes one onto a step. */
    private fun step(prompt: String? = "a red bicycle", model: Long? = 5) = ExecutionStep(
        executionId = 100,
        nodeKey = "draw",
        kind = NodeKind.IMAGE,
        name = "Draw",
        imageModelId = model,
        outputName = "image",
        mappings = prompt?.let { """{"prompt":{"expression":"$it","reference":false,"from":null}}""" },
        order = 0,
        x = 0.0,
        y = 0.0,
    )

    @Test
    fun `it draws, files the picture, and returns where it is`() {
        val bytes = byteArrayOf(1, 2, 3)
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(executions.findById(100)).thenReturn(Optional.of(execution()))
        // Nothing asked beyond the prompt, so the model's defaults are what is sent.
        `when`(drawing.draw(eq(5L), eqOf("a red bicycle"), eqOf(ImageOptions.NONE)))
            .thenReturn(Picture.Drawn(bytes, "image/png", 12))
        // The same array the drawing returned, matched by identity.
        `when`(store.put(eq(9L), anyString(), eqOf(bytes))).thenReturn("workspace-9/abc.png")
        `when`(pictures.save(anyOf())).thenAnswer { invocation ->
            val given = invocation.arguments[0] as ExecutionPicture
            ExecutionPicture(
                id = 7,
                executionId = given.executionId,
                nodeKey = given.nodeKey,
                workspaceId = given.workspaceId,
                prompt = given.prompt,
                filename = given.filename,
                contentType = given.contentType,
                sizeBytes = given.sizeBytes,
                location = given.location,
            )
        }

        val result = runner.run(step(), input = null, trigger = null)

        assertThat(result.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(result.output).contains("/api/execution-pictures/7")
        assertThat(result.output).contains("\"contentType\":\"image/png\"")
        verify(store).put(eq(9L), anyString(), eqOf(bytes))
        verify(pictures).save(anyOf())
    }

    /**
     * What reached the step is still there afterwards, with the picture added.
     *
     * A picture is something a run gains on its way past, not an answer that
     * replaces what it was carrying: the reply after an image node usually
     * wants both the agent's words and the picture to attach. Replacing the
     * payload left the picture as the only field there was, so every reference
     * the reply held read as a field nothing before it produces - which is
     * exactly what the graph then said, at the reply node.
     */
    @Test
    fun `the picture is handed on beside what reached the step`() {
        val bytes = byteArrayOf(1, 2, 3)
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(executions.findById(100)).thenReturn(Optional.of(execution()))
        `when`(drawing.draw(eq(5L), eqOf("a red bicycle"), eqOf(ImageOptions.NONE)))
            .thenReturn(Picture.Drawn(bytes, "image/png", 12))
        `when`(store.put(eq(9L), anyString(), eqOf(bytes))).thenReturn("workspace-9/abc.png")
        `when`(pictures.save(anyOf())).thenAnswer { invocation ->
            val given = invocation.arguments[0] as ExecutionPicture
            ExecutionPicture(
                id = 7,
                executionId = given.executionId,
                nodeKey = given.nodeKey,
                workspaceId = given.workspaceId,
                prompt = given.prompt,
                filename = given.filename,
                contentType = given.contentType,
                sizeBytes = given.sizeBytes,
                location = given.location,
            )
        }

        val result = runner.run(step(), input = """{"ai_out_1":"here it is"}""", trigger = null)

        assertThat(result.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(result.output).contains(""""ai_out_1":"here it is"""")
        assertThat(result.output).contains("/api/execution-pictures/7")
    }

    /**
     * What the node asked for beyond the prompt reaches the drawing as it was
     * asked, and what it left alone reaches it as nothing. The step's copy is
     * what is read - not the node's - so a node re-sized mid-run draws what the
     * run was started with. Issue #423.
     */
    @Test
    fun `the step's size, quality and style are handed to the drawing`() {
        val bytes = byteArrayOf(1, 2, 3)
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(executions.findById(100)).thenReturn(Optional.of(execution()))
        val asked = ImageOptions(size = "1024x1536", quality = "hd", style = null)
        `when`(drawing.draw(eq(5L), eqOf("a red bicycle"), eqOf(asked))).thenReturn(Picture.Drawn(bytes, "image/png", 12))
        `when`(store.put(eq(9L), anyString(), eqOf(bytes))).thenReturn("workspace-9/abc.png")
        `when`(pictures.save(anyOf())).thenAnswer { invocation ->
            val given = invocation.arguments[0] as ExecutionPicture
            ExecutionPicture(
                id = 7,
                executionId = given.executionId,
                nodeKey = given.nodeKey,
                workspaceId = given.workspaceId,
                prompt = given.prompt,
                filename = given.filename,
                contentType = given.contentType,
                sizeBytes = given.sizeBytes,
                location = given.location,
            )
        }

        val sized = ExecutionStep(
            executionId = 100,
            nodeKey = "draw",
            kind = NodeKind.IMAGE,
            name = "Draw",
            imageModelId = 5,
            imageSize = "1024x1536",
            imageQuality = "hd",
            outputName = "image",
            mappings = """{"prompt":{"expression":"a red bicycle","reference":false,"from":null}}""",
            order = 0,
            x = 0.0,
            y = 0.0,
        )
        val result = runner.run(sized, input = null, trigger = null)

        assertThat(result.status).isEqualTo(StepStatus.COMPLETED)
        verify(drawing).draw(eq(5L), eqOf("a red bicycle"), eqOf(asked))
    }

    @Test
    fun `a node with no model draws nothing and says so`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)

        val result = runner.run(step(model = null), input = null, trigger = null)

        assertThat(result.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(result.output).contains("names no image model")
    }

    @Test
    fun `a blank prompt draws nothing and says so`() {
        `when`(settings.attachmentsEnabled()).thenReturn(true)
        `when`(executions.findById(100)).thenReturn(Optional.of(execution()))

        val result = runner.run(step(prompt = "   "), input = null, trigger = null)

        assertThat(result.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(result.output).contains("nothing to draw")
    }
}
