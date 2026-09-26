package io.mszymanski.orknux.server.task

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.issue.IssueNewsRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A task that draws: the tool it is offered, the picture it produces, and where
 * that picture ends up.
 *
 * Issue #283. #240 gave this installation a model that draws and gave a chat a
 * button that asks it; an agent working a task on its own could reach neither,
 * so "make me a diagram" came back as a paragraph about a diagram. What is
 * pinned here is the other end of that.
 *
 * The loop is driven a turn at a time rather than through an engine, for the
 * reason [TaskLoopTest] gives: an engine runs a task on a thread of its own, and
 * a test that waits for one is either slow or flaky. One stub serves both APIs,
 * because from the model's point of view they are one provider - the chat
 * completions the agent thinks with, and the `/images/generations` it draws at -
 * and having them on one server is what lets both "the request was made" and
 * "the request was not made" be assertions rather than absences of evidence.
 *
 * Its own attachment directory under `target`, because these tests write real
 * bytes to a real disk: that is the point of them, and a development
 * installation's attachment folder is not the place to do it.
 */
/**
 * What a drawn picture's answer says, which is how the stub knows the round it
 * is in.
 *
 * Not the word "picture": the tool's own description names an example key -
 * `![a tower](picture.22)`, which is how a model is told to place one - so
 * every request carries that string, including the first. The quotes are
 * escaped because what this looks at is the request body, where the tool's
 * answer is a JSON string inside JSON.
 */
private const val DREW = """\"drawn\":true"""

@SpringBootTest(properties = ["orknux.attachments.location=target/test-task-pictures"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TaskPictureTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val loop: TaskLoop,
    @Autowired val serving: TaskPictureAPI,
    @Autowired val tasks: TaskRepository,
    @Autowired val pictures: TaskPictureRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val grants: TaskGrantRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Which paths the stub was asked for, so "nothing was drawn" is an assertion. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Every body sent to the chat endpoint, so what was *offered* can be read off it. */
    private val offered = CopyOnWriteArrayList<String>()

    /** Every body sent to the image endpoint, which is the only place the size asked for can be seen. */
    private val drawn = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        pictures.deleteAll()
        grants.deleteAll()
        requests.deleteAll()
        tasks.deleteAll()
        news.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        asked.clear()
        offered.clear()
        drawn.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The whole of it: called, drawn, filed, and under the outcome.
     *
     * Five promises, and each is separate. The drawing went to
     * `/images/generations` rather than being described in a chat completion,
     * which is the entirety of what makes this a second API and not a prompt.
     * The bytes are on the disk under the *workspace*, which is what decides who
     * may open them. The row names the task, which is what makes the picture
     * part of what this task produced rather than a loose file. The outcome
     * carries a markdown image, which is what the page renders it out of. And
     * the summary is still there: a picture is added to what the agent said,
     * never instead of it.
     */
    @Test
    fun `a task draws a picture and the outcome carries it`() {
        val taskId = drawingTask()

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        assertThat(asked).contains("/images/generations")

        val drawn = pictures.findAll().single()
        assertThat(drawn.taskId).isEqualTo(taskId)
        assertThat(drawn.workspaceId).isEqualTo(workspaceId)
        assertThat(drawn.prompt).isEqualTo("A red square")
        assertThat(drawn.contentType).isEqualTo("image/png")
        // Named from the description, so a picture saved to a desktop says what it is.
        assertThat(drawn.filename).isEqualTo("a-red-square.png")
        assertThat(store.open(drawn.location).use { it.readBytes() })
            .isEqualTo(Base64.getDecoder().decode(PIXEL))

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.DONE)
        // The row still holds exactly what the agent said, and nothing composed.
        assertThat(task.outcome).isEqualTo("Here is the diagram.")

        graphQlTester.document("{ task(id: $taskId) { outcome } }").execute()
            .path("task.outcome").entity(String::class.java)
            /*
             * Absolute: what an outcome carries is handed on to whoever
             * reads it - a mail, a Slack message, a model pasting it into
             * an answer - and a path has no host behind it there. The base
             * is `orknux.web.base-url`, the Vite address in a test as in
             * development.
             */
            .isEqualTo("Here is the diagram.\n\n![A red square](http://localhost:5173/api/task-pictures/${drawn.id})")
    }

    /**
     * The tool is offered to a task that can use it, and to no other.
     *
     * `AgentTools` states the rule for every tool in this application - a model
     * is only ever offered tools that will run - and this is the half of it a
     * workspace decides by choosing a model to draw with, or not. A tool offered
     * where there is nothing behind it costs a turn to discover, and the model
     * is told it can do something it cannot; it will believe you.
     *
     * One task across both, because what is offered is decided every turn:
     * choosing a model half way through a task arms it, and a test that made two
     * tasks would not have said so. It is read off the body the chat endpoint was
     * actually sent, because what the model was told is the only thing at issue.
     */
    @Test
    fun `the drawing tool is offered only where the workspace has something to draw with`() {
        val taskId = taskFor(serve { saying("Thinking about it.") })

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)
        assertThat(offered).isNotEmpty
        assertThat(offered.last()).contains("task_done").doesNotContain("task_draw_picture")

        drawWith()

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)
        assertThat(offered.last()).contains("task_draw_picture")
    }

    /**
     * A picture the agent placed itself is not placed a second time.
     *
     * The tool hands back the markdown precisely so an agent producing a report
     * can put the picture where it belongs in it, and appending a second copy
     * underneath would punish it for doing the better thing. The link is what
     * identifies the picture, not the alt text, which is the agent's to choose.
     */
    @Test
    fun `a picture the summary already places is not shown twice`() {
        val taskId = drawingTask { body ->
            /*
             * A model that got the address from somewhere else and put it in
             * its summary. It cannot get one from the tool any more - that
             * answers a key - and what is under test is the outcome refusing
             * to carry the same picture twice, which is a question about the
             * summary rather than about where the link came from.
             */
            val already = pictures.findAll().firstOrNull()
            if (already == null) {
                drawingCall()
            } else {
                finishing("Done. See ![the square](/api/task-pictures/" + already.id + ") above.")
            }
        }

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        val drawn = pictures.findAll().single()
        val outcome = graphQlTester.document("{ task(id: $taskId) { outcome } }").execute()
            .path("task.outcome").entity(String::class.java).get()
        assertThat(outcome.split("/api/task-pictures/${drawn.id}")).hasSize(2)
    }

    /**
     * A task that drew and then ran out of turns still shows what it drew.
     *
     * This is why the outcome is assembled on the way out rather than written
     * down when the agent finishes. Three of the six ways a task ends never
     * reach a summary at all - out of turns, out of time, stopped by somebody -
     * and a picture that was drawn was paid for whichever of them happened.
     */
    @Test
    fun `a task that never finished still shows the picture it drew`() {
        val taskId = drawingTask(turns = 1) { body ->
            // The answer says it drew and names a key; there is no link in it.
            if (body.contains(DREW)) saying("Drew it.") else drawingCall()
        }

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Working)
        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.FAILED)
        assertThat(task.outcome).isNull()

        val drawn = pictures.findAll().single()
        graphQlTester.document("{ task(id: $taskId) { outcome endedBecause } }").execute()
            .path("task.outcome").entity(String::class.java)
            // Absolute; see the note on the first of these.
            .isEqualTo("![A red square](http://localhost:5173/api/task-pictures/${drawn.id})")
            .path("task.endedBecause").entity(String::class.java).isEqualTo("out of turns after 1")
    }

    /**
     * The bytes come back, and come back as something a browser will draw.
     *
     * The picture is only ever asked for by an `<img>` in the outcome's
     * markdown, so what this endpoint answers with is what decides whether the
     * outcome shows a picture or offers a download. The type and the disposition
     * are [io.mszymanski.orknux.server.attachment.AttachmentDownloads]' answer -
     * one list of what is safe to render, for a chat, an issue and a task alike.
     */
    @Test
    fun `the picture is served as a picture`() {
        val taskId = drawingTask()
        loop.advance(taskId)
        val drawn = pictures.findAll().single()

        val answer = serving.download(requireNotNull(drawn.id))

        assertThat(answer.statusCode.value()).isEqualTo(200)
        assertThat(answer.headers.contentType?.toString()).isEqualTo("image/png")
        assertThat(answer.headers.getFirst("Content-Disposition")).startsWith("inline;")
        assertThat(requireNotNull(answer.body).inputStream.use { it.readBytes() })
            .isEqualTo(Base64.getDecoder().decode(PIXEL))
    }

    /**
     * A row whose bytes have gone is a picture that is not there.
     *
     * Left alone the stream is opened inside the response body and throws from
     * in there: a 500 with a stack trace, which says the server broke when what
     * happened is that a file was deleted. The interface has one line for a
     * picture that is gone, and it is written off the 404.
     */
    @Test
    fun `a picture whose bytes have gone is answered as one that is not here`() {
        val taskId = drawingTask()
        loop.advance(taskId)
        val drawn = pictures.findAll().single()
        store.remove(drawn.location)

        assertThatThrownBy { serving.download(requireNotNull(drawn.id)) }
            .isInstanceOf(TaskPictureNotFoundException::class.java)
    }

    /**
     * A provider that will not draw is a sentence the agent reads, not an ending.
     *
     * That distinction is the whole reason the tool answers with an error rather
     * than halting the round the way `task_done` does: a description a provider
     * refused is something the agent can rewrite, and a task killed because one
     * picture could not be drawn would throw away everything else it had done.
     */
    @Test
    fun `a refused drawing is told to the agent and the task carries on`() {
        val taskId = drawingTask(draws = false) { body ->
            if (body.contains("would not draw")) finishing("No picture, then.") else drawingCall()
        }

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        assertThat(pictures.count()).isZero()
        val task = requireNotNull(tasks.findByIdOrNull(taskId))
        assertThat(task.status).isEqualTo(TaskStatus.DONE)
        assertThat(task.outcome).isEqualTo("No picture, then.")
    }

    /**
     * The tool takes a size, offered as the model takes it, and the size
     * reaches the wire. Issue #436.
     *
     * The stub's model is an OpenAI-shaped endpoint on a host that is not
     * OpenAI's, which is a self-hosted server: it is offered a range and no
     * quality, and the two numbers the agent sent arrive joined as the one
     * `size` the provider takes. The list-shaped half - a DALL-E 3's three
     * sizes, and a refusal naming them - is `ChatDrawingTest`'s, over the same
     * `DrawToolParameters`.
     */
    @Test
    fun `a size the agent asks for is offered as the model takes it and reaches the wire`() {
        val taskId = drawingTask { body ->
            if (body.contains(DREW)) {
                finishing("Here is the banner.")
            } else {
                calling("task_draw_picture", """{\"description\":\"A red square\",\"width\":512,\"height\":256}""")
            }
        }

        assertThat(loop.advance(taskId)).isEqualTo(TaskTurn.Over)

        assertThat(offered.first())
            .contains("\"width\"")
            .contains("\"height\"")
            .contains("from 64 to 4096")
            .doesNotContain("\"quality\":{")
        assertThat(drawn.single()).contains("\"size\":\"512x256\"").doesNotContain("\"quality\"")
        assertThat(pictures.count()).isEqualTo(1)
    }

    /* ------------------------------------------------------------- the stubs */

    /** A stub answering chat completions, which is what the agent thinks with. */
    private fun serve(answer: (String) -> String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += exchange.requestURI.path
            offered += body
            reply(exchange, answer(body).toByteArray(StandardCharsets.UTF_8), 200)
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * Gives the workspace something to draw with: the image half of the stub, a
     * provider and model in front of it, and the workspace's choice of it.
     *
     * @param draws false for a provider that refuses, answering the way these
     *   providers actually refuse a description - a 400 carrying the reason in
     *   `error.message`, which is the sentence the agent is handed.
     */
    private fun drawWith(draws: Boolean = true) {
        server.createContext("/images/generations") { exchange ->
            asked += exchange.requestURI.path
            drawn += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            if (draws) {
                reply(exchange, """{"data":[{"b64_json":"$PIXEL"}]}""".toByteArray(StandardCharsets.UTF_8), 200)
            } else {
                reply(
                    exchange,
                    """{"error":{"message":"That is something it would not draw"}}"""
                        .toByteArray(StandardCharsets.UTF_8),
                    400,
                )
            }
        }

        val modelId = model("Drawer", "stub-image", "IMAGE")
        graphQlTester.document(
            """mutation { setWorkspaceImageModel(workspaceId: $workspaceId, modelId: $modelId) { id } }""",
        ).execute().path("setWorkspaceImageModel.id").hasValue()
    }

    private fun reply(exchange: HttpExchange, body: ByteArray, status: Int) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    /* ------------------------------------------------- what a round looks like */

    private fun drawingCall() = calling("task_draw_picture", """{\"description\":\"A red square\"}""")

    private fun finishing(summary: String) = calling("task_done", """{\"summary\":\"$summary\"}""")

    /** A round the stub streams, because a task's rounds are streamed. */
    private fun streamed(vararg frames: String) =
        frames.joinToString("\n\n", postfix = "\n\ndata: [DONE]\n\n") { "data: $it" }

    private fun calling(tool: String, arguments: String) = streamed(
        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
            """"function":{"name":"$tool","arguments":"$arguments"}}]}}]}""",
        """{"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
    )

    private fun saying(said: String) = streamed("""{"choices":[{"delta":{"content":"$said"}}]}""")

    /* ---------------------------------------------------------- the fixtures */

    /**
     * A task on a workspace that draws, answering the ordinary way unless told
     * otherwise: draw once, then finish now that there is a picture in hand.
     */
    private fun drawingTask(
        turns: Int = 10,
        draws: Boolean = true,
        answer: (String) -> String = { body ->
            if (body.contains(DREW)) finishing("Here is the diagram.") else drawingCall()
        },
    ): Long {
        val taskId = taskFor(serve(answer), turns)
        drawWith(draws)
        return taskId
    }

    /** A task, on a workspace that has chosen nothing to draw with. */
    private fun taskFor(endpoint: String, turns: Int = 10): Long {
        val modelId = model("Talker", "stub", "CHAT", endpoint)
        val agentId = agent(modelId)
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Illustrate the report",
                prompt = PROMPT,
                agentId = agentId,
                modelId = modelId,
                createdBy = "alice",
                turnsAllowed = turns,
                secondsAllowed = 600,
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        recorder.userSaid(requireNotNull(task.sessionId), "alice", PROMPT)
        tasks.save(task)
        return id
    }

    private fun agent(modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Worker", modelId: $modelId }) { id } }""",
        ).execute()
        return id
    }

    /**
     * A model of one kind, on a provider of its own at the stub.
     *
     * A provider each rather than one shared, because that is how an
     * installation is actually arranged - the chat model and the image model are
     * different products even where they are the same company - and because it
     * keeps the endpoint the only thing the two have in common.
     */
    private fun model(
        name: String,
        modelId: String,
        kind: String,
        endpoint: String = "http://${server.address.hostString}:${server.address.port}",
    ): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: OPENAI,
                 endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name", modelId: "$modelId", kind: $kind
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        const val PROMPT = "Write up last week's failures and illustrate it."

        /** A one-pixel PNG, base64, which is a real picture and eight bytes of one. */
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
