package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.io.BufferedReader
import java.net.Socket
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The stream a task's page follows, over real HTTP.
 *
 * Real HTTP because the thing being tested is the wire: a page is told what
 * happened by frames arriving one at a time on a connection that stays open, and
 * a test that called the controller method would be asserting about a
 * `StreamingResponseBody` it then ran itself - which is every part of this
 * except the part that has ever gone wrong.
 *
 * The three promises in order: a line written reaches a connection that is
 * already open; a connection that says where it got to is not sent the beginning
 * again; and a task that ends says so and lets go, rather than leaving a page
 * claiming to be live for ever on a task that finished last week.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskStreamAPITest(
    @LocalServerPort val port: Int,
    @Autowired val tasks: TaskRepository,
    @Autowired val messages: TaskMessageRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
) {

    /**
     * Daemons, and shut down after every test.
     *
     * A reader is blocked on a socket that stays open for as long as the task
     * is going, so an ordinary pool thread here holds the forked JVM open after
     * the suite has finished - which does not fail anything, it simply never
     * ends, which is worse.
     */
    private val readers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "task-stream-reader").apply { isDaemon = true }
    }

    /** Every stream this test opened, so none is left held at the end of one. */
    private val opened = mutableListOf<Frames>()

    private var workspaceId: Long = 0
    private var sessionId: Long = 0
    private var taskId: Long = 0
    private lateinit var token: String

    /**
     * A workspace, a task and a token to reach it with, each time.
     *
     * The task row is written here rather than through [TaskService], on
     * purpose: `start` hands the task to an engine, and what is being tested is
     * what a page sees rather than what an agent does. Written this way the
     * session is filled in a line at a time by the test, which is exactly the
     * shape a real turn produces and is the only way to say *when* each line
     * appeared.
     *
     * Nothing is deleted. This class shares a database with the rest of the
     * suite, and a fixture that empties tables it does not own is how a test
     * starts breaking its neighbours.
     */
    @BeforeEach
    fun arrive() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "task-stream-${System.nanoTime()}")).id)

        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        val watcher = users.findByUsername(WATCHER)
            ?: users.save(
                AppUser(
                    username = WATCHER,
                    displayName = "The Watcher",
                    type = UserType.INTERNAL,
                    roles = mutableSetOf(admins),
                ),
            )
        token = internal.mint(watcher, "test-${System.nanoTime()}").second

        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Find out why it is slow",
                prompt = "Find out why it is slow",
                status = TaskStatus.RUNNING,
                createdBy = WATCHER,
                turnsAllowed = 10,
                secondsAllowed = 600,
            ),
        )
        taskId = requireNotNull(task.id)
        sessionId = recorder.open(workspaceId, "task", taskId.toString())
        task.sessionId = sessionId
        tasks.save(task)
    }

    /**
     * A step reaches a page that was already watching.
     *
     * The whole feature. The connection is opened first and the line written
     * afterwards, which is the ordering that matters: a test that wrote first
     * would pass on a stream that only ever sends a catch-up and never sends
     * anything live.
     */
    @Test
    fun `a step written reaches a connection that is already open`() {
        val frames = follow(after = 0)
        try {
            // The state is sent on opening, before anything has happened, so a
            // page always knows what it is looking at.
            assertThat(nextOf(frames, "state")).contains("\"status\":\"RUNNING\"")

            recorder.agentSaid(sessionId, "Ada", "I have started looking")

            val step = nextOf(frames, "step")
            assertThat(step).contains("I have started looking")
            assertThat(step).contains("\"kind\":\"AGENT\"")
        } finally {
            frames.close()
        }
    }

    /**
     * A call arrives when it is made, and again once its tool answers.
     *
     * Both halves on the wire, which is what lets the page draw a lookup as
     * running and then as returned without asking for anything. Recorded before
     * the tool runs, so the first frame carries a null result on purpose.
     */
    @Test
    fun `a call is sent when it is made and again when it returns`() {
        val frames = follow(after = 0)
        try {
            nextOf(frames, "state")

            val line = recorder.toolCalled(sessionId, "orknux_issues", """{"status":"OPEN"}""")
            val made = nextOf(frames, "step")
            assertThat(made).contains("orknux_issues")
            assertThat(made).contains("\"result\":null")

            recorder.toolReturned(line, "four open issues")
            val answered = nextOf(frames, "step")
            assertThat(answered).contains("four open issues")
            /*
             * The same line, filled in - not a second one.
             *
             * And the id is *text*, which is the half of that the page depends
             * on. It draws the tail with `llmSessionEvents`, where the id is a
             * GraphQL `ID!` and arrives as a string, then merges what the stream
             * sends by `===`. A number here matched nothing, so every line the
             * stream sent twice was drawn twice: the lookup once running and
             * once returned, the reasoning once frozen and once growing.
             */
            assertThat(answered).contains("\"id\":\"$line\"")
        } finally {
            frames.close()
        }
    }

    /**
     * A page that says where it got to is given the rest and not the beginning.
     *
     * This is what makes a dropped connection cost nothing, and it is the reason
     * the cursor is an event id rather than a moment: a turn writes its
     * question, its calls and its answer inside one millisecond, so a clock
     * cannot say where a reader stopped.
     */
    @Test
    fun `a connection that names a cursor is not sent what came before it`() {
        recorder.userSaid(sessionId, WATCHER, "the first thing")
        val held = requireNotNull(recorder.agentSaid(sessionId, "Ada", "the second thing"))
        recorder.agentSaid(sessionId, "Ada", "the third thing")

        val frames = follow(after = held)
        try {
            nextOf(frames, "state")
            val step = nextOf(frames, "step")
            assertThat(step).contains("the third thing")
            assertThat(step).doesNotContain("the first thing")
        } finally {
            frames.close()
        }
    }

    /**
     * A message reaches a page that is already watching, and so does its being
     * read.
     *
     * Neither of those moves the task's status, which is the whole point of the
     * test. The state is followed rather than pushed - it is a row, read on a
     * timer and shared between everybody watching - so it is sent again only
     * when something on it has changed, and "something" used to mean the status
     * and the open request and nothing else. A message sent to a task that goes
     * on running for another twenty minutes would have sat on the page saying
     * "not read yet" for those twenty minutes, and then for ever on the ones
     * where the reader never reloads. Which is the one thing this page exists
     * not to need.
     */
    @Test
    fun `a message and its being read reach a page without the status moving`() {
        val frames = follow(after = 0)
        try {
            assertThat(nextOf(frames, "state")).contains("\"messages\":[]")

            val said = messages.save(
                TaskMessage(taskId = taskId, saidBy = WATCHER, body = "Make it a table rather than prose."),
            )
            val sent = nextOf(frames, "state")
            assertThat(sent).contains("Make it a table rather than prose.")
            assertThat(sent).contains("\"readAt\":null")
            // Still working. Nothing about being told something ends a task.
            assertThat(sent).contains("\"status\":\"RUNNING\"")

            said.deliveredAt = OffsetDateTime.now()
            messages.save(said)

            val read = nextOf(frames, "state")
            assertThat(read).doesNotContain("\"readAt\":null")
            assertThat(read).contains("\"status\":\"RUNNING\"")
        } finally {
            frames.close()
        }
    }

    /**
     * A task that is over says so, and the connection ends.
     *
     * Both halves. Without the `end` a page would keep a connection open on a
     * task that will never write another line; without the state before it, a
     * page that reconnected to a task which finished while it was away would sit
     * on "Working" for ever, because nothing else is ever going to happen.
     */
    @Test
    fun `a finished task is reported and the stream lets go`() {
        val task = requireNotNull(tasks.findById(taskId).orElse(null))
        task.status = TaskStatus.DONE
        task.endedBecause = "finished"
        task.outcome = "It was the index"
        tasks.save(task)

        val frames = follow(after = 0)
        try {
            assertThat(nextOf(frames, "state")).contains("\"status\":\"DONE\"")
            assertThat(nextOf(frames, "end")).contains("over")
            // And the body closes rather than being held: the reader runs out.
            assertThat(frames.closed(WAIT_MILLIS)).isTrue()
        } finally {
            frames.close()
        }
    }

    /** A stream on a task nobody may see is refused before a byte is written. */
    @Test
    fun `a task that is not there is refused rather than streamed`() {
        val refused = request(taskId = 0, after = 0)
        try {
            assertThat(refused.status).isEqualTo(404)
        } finally {
            refused.close()
        }
    }

    /**
     * Opens a stream and reads its frames on a thread of its own.
     *
     * On its own thread because that is the point of the endpoint: the frames
     * arrive while the test goes on doing things, and a reader on the calling
     * thread would turn every one of these into "write, then read", which is the
     * one ordering that proves nothing.
     */
    private fun follow(after: Long): Frames {
        val frames = request(taskId, after)
        opened.add(frames)
        assertThat(frames.status)
            .describedAs("the stream did not open: %s", frames.statusLine)
            .isEqualTo(200)
        readers.execute(frames::pump)
        return frames
    }

    /**
     * One request, over a socket this test opened itself.
     *
     * A raw socket rather than a client library, and the reason is the thing
     * being tested. What this endpoint promises is that the first frame is on
     * the wire before the second one exists - and every HTTP client between here
     * and that promise has an opinion about when to hand a response over: the
     * JDK's own asks for HTTP/2 over cleartext by default and holds everything
     * back through a negotiation that a connector without HTTP/2 never
     * completes, so the test hangs rather than fails, which is the worst way for
     * a test to be wrong. Sixteen lines of HTTP/1.1 have no opinions.
     */
    private fun request(taskId: Long, after: Long): Frames {
        val socket = Socket("localhost", port)
        socket.soTimeout = SOCKET_TIMEOUT
        socket.getOutputStream().apply {
            write(
                (
                    /*
                     * HTTP/1.0, so the body arrives as itself.
                     *
                     * On 1.1 a response with no length is chunked, and every
                     * frame would come wrapped in a size line this would then
                     * have to unwrap - a decoder in a test, to check something
                     * that is not about encoding. On 1.0 there is no chunking
                     * to do: the server writes the frames and closes at the end,
                     * which is exactly what is being read.
                     */
                    "GET /api/tasks/$taskId/stream?after=$after HTTP/1.0\r\n" +
                        "Host: localhost:$port\r\n" +
                        "Authorization: Bearer $token\r\n" +
                        "Accept: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            flush()
        }

        val body = socket.getInputStream().bufferedReader()
        // The status line, then the headers, then the frames. Read here rather
        // than in the pump so a refusal is an assertion and not a silence - and
        // kept verbatim, because "expected 200 but was 0" says nothing about
        // whether the server refused, redirected or never answered at all.
        val statusLine = body.readLine()
        val status = statusLine?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
        while (true) {
            val line = body.readLine() ?: break
            if (line.isEmpty()) break
        }
        return Frames(socket, body, status, statusLine ?: "the connection closed without answering")
    }

    /**
     * Ends the task, so every stream this test opened lets go.
     *
     * Not tidying for its own sake. A stream on a task that is still going is
     * held for its whole stint by design, and a test that walked away from three
     * of them would leave the server writing to nobody for four minutes - and
     * the reader threads waiting on them would hold the forked JVM open long
     * after the last assertion passed. Finishing the task is also what a real
     * page's connection ends on, so the teardown exercises the ending rather
     * than working around it.
     */
    @AfterEach
    fun letGo() {
        tasks.findById(taskId).orElse(null)?.let { held ->
            if (!held.status.over) {
                held.status = TaskStatus.STOPPED
                held.endedBecause = "the test finished"
                tasks.save(held)
            }
        }
        opened.forEach { frames ->
            frames.closed(WAIT_MILLIS)
            frames.close()
        }
        opened.clear()
        readers.shutdownNow()
    }

    /**
     * The next frame of this kind, or a failure saying what did arrive instead.
     *
     * The "instead" is the whole value of it. A stream that sends `end` where a
     * step was expected and one that sends nothing at all are two completely
     * different faults, and "no step frame arrived" describes both - so the
     * failure that says only that sends somebody looking in the wrong place.
     */
    private fun nextOf(frames: Frames, event: String): String {
        val seen = mutableListOf<String>()
        val began = System.currentTimeMillis()
        val until = began + WAIT_MILLIS
        while (System.currentTimeMillis() < until) {
            val frame = frames.next(WAIT_SLICE) ?: continue
            // Matched on the line and not on the start of the frame: a step
            // carries its `id:` first, so anything anchored at the front finds
            // the state frames and misses every one of the steps.
            if (frame.lineSequence().any { it == "event: $event" }) return frame
            seen.add(frame.replace(System.lineSeparator(), " "))
        }
        /*
         * How long it actually waited, and whether the stream was still open.
         * Issue #373: this goes red on a release build and green on a rerun of
         * the same commit, and the message it failed with could not tell a
         * stream that said the wrong thing from one that said nothing at all
         * and from one the server had already let go of. The next red is worth
         * something only if it says which of the three it was.
         */
        val waited = System.currentTimeMillis() - began
        throw AssertionError(
            "No $event frame arrived in ${waited}ms (waiting up to ${WAIT_MILLIS}ms). " +
                "The stream was ${if (frames.closed(0)) "closed by the server" else "still open"}. " +
                "What did arrive: " + if (seen.isEmpty()) "nothing at all" else seen.joinToString(" | "),
        )
    }

    /**
     * One open stream, read into a queue.
     *
     * A queue rather than a list, so a test can wait for the next frame instead
     * of sleeping and hoping - which is the difference between a check that
     * fails when the feature breaks and one that fails on a slow morning.
     */
    private class Frames(
        private val socket: Socket,
        private val body: BufferedReader,
        val status: Int,
        /** What the server actually said, for a failure that has to explain itself. */
        val statusLine: String,
    ) {

        private val arrived = LinkedBlockingQueue<String>()

        @Volatile
        private var ended = false

        fun pump() {
            try {
                val held = StringBuilder()
                while (true) {
                    val line = body.readLine() ?: break
                    if (line.isEmpty()) {
                        if (held.isNotEmpty()) arrived.add(held.toString().trim())
                        held.setLength(0)
                    } else {
                        held.append(line).append('\n')
                    }
                }
            } catch (closed: Exception) {
                // The test closed it, or the server did. Either way there is
                // nothing left to read and nothing to report.
            } finally {
                ended = true
            }
        }

        fun next(millis: Long): String? = arrived.poll(millis, TimeUnit.MILLISECONDS)

        /** Whether the server let go, waited on rather than assumed. */
        fun closed(millis: Long): Boolean {
            val until = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < until) {
                if (ended) return true
                Thread.sleep(50)
            }
            return ended
        }

        fun close() {
            runCatching { socket.close() }
            runCatching { body.close() }
        }
    }

    private companion object {
        const val ADMINS = "ADMINS"
        const val WATCHER = "task-stream-watcher"

        /**
         * How long a read may block before the socket gives up.
         *
         * A ceiling and not a wait: nothing here should ever reach it. It exists
         * so that a stream which stops sending fails this test in half a minute
         * rather than holding the suite open until somebody notices.
         */
        const val SOCKET_TIMEOUT = 30_000

        /**
         * Long enough for a stir and the backstop behind it, with room for a
         * machine under load. Issue #373.
         *
         * Eight seconds was enough on every machine this has been run on by
         * hand, and not enough twice on the box that builds a release - where
         * the whole suite is running beside it and a pass that takes half a
         * second here takes several. A ceiling nothing reaches costs nothing
         * while the feature works, and the failure above now says how long it
         * really waited, so a red at twenty seconds is a different report from
         * a red at eight.
         */
        const val WAIT_MILLIS = 20_000L

        const val WAIT_SLICE = 250L
    }
}
