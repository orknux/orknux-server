package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * The box tools against a real box: the local one, embedded, on the `local`
 * runtime under a temporary directory - so a session is a directory and a
 * command is a process on this machine, on Windows and Linux alike. Through
 * [AgentTools], so the menu and the dispatch are what is tested, not only the
 * tools behind them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BoxToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val settings: BoxSettings,
    @Autowired val local: LocalBox,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val sessions: BoxSessionRepository,
    @Autowired val boxes: BoxRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val events: BoxSessionEventRepository,
    @Autowired val outputs: BoxSessionOutputRepository,
    @Autowired val web: io.mszymanski.orknux.server.security.WebProperties,
    @Autowired val jdbc: org.springframework.jdbc.core.JdbcTemplate,
    @Autowired val store: io.mszymanski.orknux.server.llm.LlmSessionStore,
    @Autowired val pads: io.mszymanski.orknux.server.llm.SessionScratchpadService,
    @Autowired val savedArtifacts: io.mszymanski.orknux.server.workflow.SavedArtifactRepository,
    @Autowired val attachmentStore: io.mszymanski.orknux.server.attachment.AttachmentStore,
) {

    @TempDir
    lateinit var root: Path

    private var workspaceId = 0L
    private var conversation = 0L
    private lateinit var agent: Agent

    @BeforeEach
    fun seed() {
        clean()
        settings.setLocalLocation(root.toString(), "alice")
        settings.setLocalRuntime("local", "alice")
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "boxes")).id)
        conversation = recorder.open(workspaceId, "chat", "build")
        agent = agents.save(Agent(workspaceId = workspaceId, name = "Builder", type = AgentType.LLM).apply { boxLabels = listOf("local") })
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
        sessions.deleteAll()
        boxes.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        savedArtifacts.deleteAll()
        workspaces.deleteAll()
    }

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap(), who: Agent = agent, into: Long? = conversation): JsonNode =
        mapper.readTree(tools.run(who, ToolCall("call", name, mapper.writeValueAsString(arguments)), into))

    private fun opened(): Long {
        val answer = call("orkbox_open")
        assertThat(answer.path("error").isMissingNode).describedAs(answer.toString()).isTrue()
        return answer.path("session").asLong()
    }

    @Test
    fun `an agent granted no label is offered no box tool, and one granted a label all of them`() {
        val without = agents.save(Agent(workspaceId = workspaceId, name = "Plain", type = AgentType.LLM))
        assertThat(tools.specsFor(without).map { it.name }).noneMatch { it.startsWith("orkbox_") }
        assertThat(call("orkbox_open", who = without).path("error").stringValue()).contains("not been given any boxes")

        assertThat(tools.specsFor(agent).map { it.name }).containsAll(BoxTools.NAMES)
    }

    @Test
    fun `a label no box has reaches nothing, and a box with no label is nobody's`() {
        agent.boxLabels = listOf("elsewhere")
        agents.save(agent)
        assertThat(call("orkbox_list").path("boxes").size()).isZero()
        assertThat(call("orkbox_open").path("error").stringValue()).contains("No box you may use")

        boxes.save(Box(name = "unlabelled", label = null, address = "http://unlabelled.invalid"))
        agent.boxLabels = listOf("local")
        agents.save(agent)
        assertThat(call("orkbox_open", mapOf("box" to "unlabelled")).path("error").stringValue()).contains("may not use")
    }

    @Test
    fun `a command runs in a session and answers with its exit code and output`() {
        val listed = call("orkbox_list").path("boxes")
        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0].path("name").stringValue()).isEqualTo("local")
        assertThat(listed[0].path("runtimes")[0].path("name").stringValue()).isEqualTo("local")

        val session = opened()
        // The agent is told how much room is left, counted as the box counts when it refuses one.
        val room = call("orkbox_list").path("boxes")[0]
        assertThat(room.path("activeSessions").asInt()).isPositive()
        // Opened and nothing run in it yet: idle, and an idle session takes no place.
        assertThat(room.path("idleSessions").asInt()).isPositive()
        assertThat(room.path("busySessions").asInt() + room.path("idleSessions").asInt()).isEqualTo(room.path("activeSessions").asInt())
        assertThat(room.path("freeSessions").asInt()).isEqualTo(room.path("maxSessions").asInt() - room.path("busySessions").asInt())
        val row = sessions.findById(session).get()
        assertThat(row.boxKind).isEqualTo(BoxKind.LOCAL)
        assertThat(row.llmSessionId).isEqualTo(conversation)
        assertThat(row.agentId).isEqualTo(agent.id)
        assertThat(row.status).isEqualTo("ready")

        val ran = call("orkbox_exec", mapOf("session" to session, "command" to "echo hello"))
        assertThat(ran.path("status").stringValue()).describedAs(ran.toString()).isEqualTo("succeeded")
        assertThat(ran.path("exitCode").asInt()).isZero()
        assertThat(ran.path("stdout").stringValue()).contains("hello")

        // Environment variables are `variables`, not `env`: the word env is the environment itself.
        val reading = if (System.getProperty("os.name").startsWith("Windows")) "echo %GREETING%" else "echo \$GREETING"
        val greeted = call("orkbox_exec", mapOf("session" to session, "command" to reading, "variables" to mapOf("GREETING" to "ahoy")))
        assertThat(greeted.path("stdout").stringValue()).describedAs(greeted.toString()).contains("ahoy")

        val failed = call("orkbox_exec", mapOf("session" to session, "command" to "exit 3"))
        assertThat(failed.path("status").stringValue()).isEqualTo("failed")
        assertThat(failed.path("exitCode").asInt()).isEqualTo(3)

        // Not waited for, then waited for.
        val started = call("orkbox_exec", mapOf("session" to session, "command" to "echo later", "wait" to false))
        val execution = started.path("execution").stringValue()
        val finished = call("orkbox_execution", mapOf("session" to session, "execution" to execution, "wait_seconds" to 30))
        assertThat(finished.path("status").stringValue()).isEqualTo("succeeded")
        val output = call("orkbox_output", mapOf("session" to session, "execution" to execution))
        assertThat(output.path("text").stringValue()).contains("later")
        assertThat(output.path("nextOffset").asLong()).isPositive()

        val log = call("orkbox_log", mapOf("session" to session))
        assertThat(log.path("events").values().map { it.path("type").stringValue() })
            .contains("session.created", "execution.started", "execution.finished")
    }

    @Test
    fun `files are written, read, edited, found, moved and deleted`() {
        val session = opened()
        call("orkbox_write", mapOf("session" to session, "path" to "src/app.txt", "content" to "port = 8080\nname = demo\n"))
            .also { assertThat(it.path("size").asInt()).isEqualTo(24) }
        assertThat(call("orkbox_read", mapOf("session" to session, "path" to "src/app.txt")).path("content").stringValue())
            .isEqualTo("port = 8080\nname = demo\n")
        assertThat(call("orkbox_read", mapOf("session" to session, "path" to "src/app.txt", "start_line" to 2)).path("content").stringValue())
            .isEqualTo("name = demo\n")

        call("orkbox_edit", mapOf("session" to session, "path" to "src/app.txt", "edits" to listOf(mapOf("old_text" to "8080", "new_text" to "9090"))))
            .also { assertThat(it.path("replacements")[0].asInt()).isEqualTo(1) }
        val missed = call("orkbox_edit", mapOf("session" to session, "path" to "src/app.txt", "edits" to listOf(mapOf("old_text" to "nothing", "new_text" to "x"))))
        assertThat(missed.path("code").stringValue()).isEqualTo("edit_no_match")

        val found = call("orkbox_search", mapOf("session" to session, "pattern" to "9090"))
        assertThat(found.path("matches")[0].path("path").stringValue()).isEqualTo("src/app.txt")

        assertThat(call("orkbox_files", mapOf("session" to session, "path" to "src")).path("entries")[0].path("path").stringValue())
            .isEqualTo("src/app.txt")
        assertThat(call("orkbox_stat", mapOf("session" to session, "path" to "src/app.txt")).path("type").stringValue()).isEqualTo("file")

        call("orkbox_move", mapOf("session" to session, "from" to "src/app.txt", "to" to "app.txt"))
        assertThat(call("orkbox_stat", mapOf("session" to session, "path" to "src/app.txt")).path("code").stringValue())
            .isEqualTo("file_not_found")
        call("orkbox_delete", mapOf("session" to session, "path" to "app.txt"))
        assertThat(call("orkbox_read", mapOf("session" to session, "path" to "app.txt")).path("code").stringValue())
            .isEqualTo("file_not_found")
        assertThat(call("orkbox_read", mapOf("session" to session, "path" to "../outside")).path("code").stringValue())
            .isEqualTo("path_outside_workspace")
    }

    private fun read(session: Long, path: String): JsonNode = call("orkbox_read", mapOf("session" to session, "path" to path))

    private fun edit(session: Long, path: String, vararg edits: Map<String, Any?>): JsonNode =
        call("orkbox_edit", mapOf("session" to session, "path" to path, "edits" to edits.toList()))

    @Test
    fun `an edit replaces lines by number, or a regular expression's matches`() {
        val session = opened()
        call("orkbox_write", mapOf("session" to session, "path" to "app.txt", "content" to "one\ntwo\nthree\nfour\n"))

        edit(session, "app.txt", mapOf("start_line" to 2, "end_line" to 3, "new_text" to "middle\n")).also {
            assertThat(it.path("error").isMissingNode).describedAs(it.toString()).isTrue()
        }
        assertThat(read(session, "app.txt").path("content").stringValue()).isEqualTo("one\nmiddle\nfour\n")

        // old_text beside a line number is a guard, and a guard that does not hold refuses.
        assertThat(edit(session, "app.txt", mapOf("start_line" to 1, "old_text" to "nope", "new_text" to "x\n")).path("code").stringValue())
            .isEqualTo("edit_no_match")
        assertThat(edit(session, "app.txt", mapOf("start_line" to 9, "new_text" to "x\n")).path("code").stringValue())
            .isEqualTo("edit_bad_lines")
        // The old camelCase spelling still works.
        edit(session, "app.txt", mapOf("startLine" to 1, "endLine" to 1, "oldText" to "one", "newText" to "first\n"))
        assertThat(read(session, "app.txt").path("content").stringValue()).isEqualTo("first\nmiddle\nfour\n")

        edit(session, "app.txt", mapOf("old_text" to "^(\\w+)$", "new_text" to "<$1>", "regex" to true, "replace_all" to true)).also {
            assertThat(it.path("replacements")[0].asInt()).describedAs(it.toString()).isEqualTo(3)
        }
        assertThat(read(session, "app.txt").path("content").stringValue()).isEqualTo("<first>\n<middle>\n<four>\n")
        assertThat(edit(session, "app.txt", mapOf("old_text" to "(", "new_text" to "x", "regex" to true)).path("code").stringValue())
            .isEqualTo("invalid_regex")

        assertThat(edit(session, "app.txt", mapOf("new_text" to "x")).path("error").stringValue()).contains("old_text", "start_line")
    }

    @Test
    fun `an edit and a write are undone and redone, and a file changed since is left alone`() {
        val session = opened()
        call("orkbox_write", mapOf("session" to session, "path" to "app.txt", "content" to "port = 8080\n"))
        edit(session, "app.txt", mapOf("old_text" to "8080", "new_text" to "9090"))
        call("orkbox_write", mapOf("session" to session, "path" to "created.txt", "content" to "fresh"))

        // The write that created a file is undone by removing it.
        call("orkbox_undo", mapOf("session" to session)).also {
            assertThat(it.path("undone")[0].path("path").stringValue()).describedAs(it.toString()).isEqualTo("created.txt")
            assertThat(it.path("canRedo").asInt()).isEqualTo(1)
        }
        assertThat(read(session, "created.txt").path("code").stringValue()).isEqualTo("file_not_found")
        call("orkbox_undo", mapOf("session" to session))
        assertThat(read(session, "app.txt").path("content").stringValue()).isEqualTo("port = 8080\n")

        // Redone, the most recently undone first.
        call("orkbox_redo", mapOf("session" to session, "steps" to 2)).also {
            assertThat(it.path("redone").size()).describedAs(it.toString()).isEqualTo(2)
        }
        assertThat(read(session, "app.txt").path("content").stringValue()).isEqualTo("port = 9090\n")
        assertThat(read(session, "created.txt").path("content").stringValue()).isEqualTo("fresh")
        assertThat(call("orkbox_redo", mapOf("session" to session)).path("code").stringValue()).isEqualTo("nothing_to_redo")

        // A command changed it since: undo refuses rather than clobber, and drops the step.
        call("orkbox_exec", mapOf("session" to session, "command" to "echo changed> created.txt"))
        call("orkbox_undo", mapOf("session" to session)).also {
            assertThat(it.path("code").stringValue()).describedAs(it.toString()).isEqualTo("file_changed")
            assertThat(it.path("error").stringValue()).contains("created.txt")
        }
        assertThat(read(session, "created.txt").path("content").stringValue()).startsWith("changed")
        // The step before it is still there.
        call("orkbox_undo", mapOf("session" to session)).also {
            assertThat(it.path("undone")[0].path("path").stringValue()).describedAs(it.toString()).isEqualTo("app.txt")
        }
        assertThat(read(session, "app.txt").path("content").stringValue()).isEqualTo("port = 8080\n")

        // A new change ends what can be redone.
        edit(session, "app.txt", mapOf("start_line" to 1, "new_text" to "port = 1\n"))
        assertThat(call("orkbox_redo", mapOf("session" to session)).path("code").stringValue()).isEqualTo("nothing_to_redo")

        // Closing the session drops its history with it.
        call("orkbox_close", mapOf("session" to session))
        assertThat(call("orkbox_undo", mapOf("session" to session)).path("code").stringValue()).isEqualTo("session_closed")
    }

    @Test
    fun `the history holds as many steps as Admin Settings says, and none at 0`() {
        graphQlTester.document("mutation { setBoxEditUndoSteps(steps: 2) { boxEditUndoSteps boxEditUndoStepsConfigured } }").execute().also {
            it.path("setBoxEditUndoSteps.boxEditUndoSteps").entity(Int::class.java).isEqualTo(2)
            it.path("setBoxEditUndoSteps.boxEditUndoStepsConfigured").entity(Int::class.java).isEqualTo(DEFAULT_BOX_EDIT_UNDO_STEPS)
        }
        graphQlTester.document("mutation { setBoxEditUndoSteps(steps: 101) { boxEditUndoSteps } }").execute()
            .errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo("BoxEditUndoStepsOutOfRange")
            }
        assertThat(audit.findAll().map { it.message }).contains("An agent can undo its last 2 box edits")

        val session = opened()
        (1..4).forEach { call("orkbox_write", mapOf("session" to session, "path" to "n.txt", "content" to "$it")) }
        call("orkbox_undo", mapOf("session" to session, "steps" to 10)).also {
            assertThat(it.path("undone").size()).describedAs(it.toString()).isEqualTo(2)
            assertThat(it.path("canUndo").asInt()).isZero()
        }
        assertThat(read(session, "n.txt").path("content").stringValue()).isEqualTo("2")

        // A file larger than an agent is handed is not kept.
        settings.setOutputBytes(MIN_BOX_OUTPUT_BYTES, "alice")
        call("orkbox_write", mapOf("session" to session, "path" to "big.txt", "content" to "x".repeat(MIN_BOX_OUTPUT_BYTES + 1))).also {
            assertThat(it.path("undo").stringValue()).describedAs(it.toString()).contains("cannot be taken back")
        }

        settings.setEditUndoSteps(0, "alice")
        call("orkbox_write", mapOf("session" to session, "path" to "n.txt", "content" to "5"))
        assertThat(call("orkbox_undo", mapOf("session" to session)).path("code").stringValue()).isEqualTo("undo_off")
        assertThat(read(session, "n.txt").path("content").stringValue()).isEqualTo("5")
    }

    /**
     * A session an agent opens without naming an idle timeout gets the
     * administrator's, not its box's fifteen minutes: session 638's box was
     * stopped while its agent waited for a person to try what it had served.
     */
    @Test
    fun `a session opened without an idle timeout gets the administrator's, and one that names its own keeps it`() {
        val defaulted = call("orkbox_open")
        assertThat(defaulted.path("idleTimeoutSeconds").asLong()).describedAs(defaulted.toString()).isEqualTo(7200)

        settings.setSessionIdleSeconds(5400, "alice")
        assertThat(call("orkbox_open").path("idleTimeoutSeconds").asLong()).isEqualTo(5400)

        val named = call("orkbox_open", mapOf("idle_timeout_seconds" to 600))
        assertThat(named.path("idleTimeoutSeconds").asLong()).isEqualTo(600)
    }

    @Test
    fun `a directory goes out as an archive in the session store and back in`() {
        val session = opened()
        call("orkbox_write", mapOf("session" to session, "path" to "out/report.txt", "content" to "done"))
        val exported = call("orkbox_export", mapOf("session" to session, "path" to "out", "key" to "report.tgz"))
        assertThat(exported.path("error").isMissingNode).describedAs(exported.toString()).isTrue()
        assertThat(exported.path("bytes").asInt()).isPositive()

        val imported = call("orkbox_import", mapOf("session" to session, "key" to "report.tgz", "path" to "copy"))
        assertThat(imported.path("error").isMissingNode).describedAs(imported.toString()).isTrue()
        assertThat(call("orkbox_read", mapOf("session" to session, "path" to "copy/report.txt")).path("content").stringValue())
            .isEqualTo("done")
    }

    /** A zip as a session store holds it: base64 bytes, kept as a binary value. */
    private fun storeZip(key: String, entries: Map<String, String>) {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        store.put(
            conversation,
            key,
            mapper.writeValueAsString(java.util.Base64.getEncoder().encodeToString(bytes)),
            io.mszymanski.orknux.workflow.script.StoredKind("application/zip", true),
        )
    }

    /**
     * A zip goes in as well as a tar. A box takes only a tar, and an agent that
     * saved a zip was answered archive_invalid and went on to try unzip inside a
     * box the file had never reached.
     */
    @Test
    fun `a zip in the store unpacks into a session, folders and all`() {
        val session = opened()
        storeZip("artifact.18", mapOf("site/index.html" to "<h1>hi</h1>", "site/css/app.css" to "body{}"))

        val imported = call("orkbox_import", mapOf("session" to session, "key" to "artifact.18", "path" to "staging"))
        assertThat(imported.path("error").isMissingNode).describedAs(imported.toString()).isTrue()
        assertThat(imported.path("format").stringValue()).isEqualTo("zip")
        assertThat(read(session, "staging/site/index.html").path("content").stringValue()).isEqualTo("<h1>hi</h1>")
        assertThat(read(session, "staging/site/css/app.css").path("content").stringValue()).isEqualTo("body{}")
    }

    @Test
    fun `a zip naming a path outside where it unpacks is refused whole`() {
        val session = opened()
        storeZip("evil.zip", mapOf("ok.txt" to "fine", "../escaped.txt" to "no"))

        val imported = call("orkbox_import", mapOf("session" to session, "key" to "evil.zip", "path" to "in"))
        assertThat(imported.path("error").stringValue()).contains("outside")
        assertThat(read(session, "in/ok.txt").path("error").isMissingNode).isFalse()
    }

    /**
     * A draft in a scratchpad goes into a box by name. Before, the only way in
     * was to send its content again, whole - an agent zipped one instead, failed
     * to import the zip and tried to download it from inside the container.
     */
    @Test
    fun `a scratchpad is written into a box by name, and undo takes it back`() {
        val session = opened()
        call("orkbox_write", mapOf("session" to session, "path" to "ui.html", "content" to "old"))
        val draft = "<html>\n  <body>draft</body>\n</html>\n"
        assertThat(pads.create(conversation, "ui.html", "The page, drafted", draft)).isInstanceOf(io.mszymanski.orknux.server.llm.ScratchpadResult.Ok::class.java)

        val written = call("orkbox_write", mapOf("session" to session, "path" to "ui.html", "from_scratchpad" to "ui.html"))
        assertThat(written.path("error").isMissingNode).describedAs(written.toString()).isTrue()
        assertThat(read(session, "ui.html").path("content").stringValue()).isEqualTo(draft)

        call("orkbox_undo", mapOf("session" to session))
        assertThat(read(session, "ui.html").path("content").stringValue()).isEqualTo("old")
        assertThat(call("orkbox_write", mapOf("session" to session, "path" to "x", "from_scratchpad" to "nope")).path("error").stringValue())
            .contains("no scratchpad")
    }

    private fun artifact(workspace: Long, name: String, bytes: ByteArray): Long {
        val location = attachmentStore.put(workspace, name, bytes)
        return requireNotNull(
            savedArtifacts.save(
                io.mszymanski.orknux.server.workflow.SavedArtifact(
                    workspaceId = workspace,
                    name = name,
                    description = "test",
                    contentType = "application/octet-stream",
                    sizeBytes = bytes.size.toLong(),
                    location = location,
                    savedBy = "Builder",
                ),
            ).id,
        )
    }

    /** The box cannot reach this server, so an artifact goes in through the server: as a file, or unpacked. */
    @Test
    fun `a saved artifact goes into a box, as a file or unpacked`() {
        val session = opened()
        val page = artifact(workspaceId, "ui.html", "<h1>hi</h1>".toByteArray())
        val fetched = call("orkbox_fetch_artifact", mapOf("session" to session, "artifact" to "http://localhost:5173/api/artifacts/$page"))
        assertThat(fetched.path("error").isMissingNode).describedAs(fetched.toString()).isTrue()
        assertThat(read(session, "ui.html").path("content").stringValue()).isEqualTo("<h1>hi</h1>")

        val zipped = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("site/app.css"))
                zip.write("body{}".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val archive = artifact(workspaceId, "site.zip", zipped)
        val unpacked = call("orkbox_fetch_artifact", mapOf("session" to session, "artifact" to archive, "unpack" to true, "path" to "staging"))
        assertThat(unpacked.path("error").isMissingNode).describedAs(unpacked.toString()).isTrue()
        assertThat(read(session, "staging/site/app.css").path("content").stringValue()).isEqualTo("body{}")
    }

    @Test
    fun `another workspace's artifact is not fetched`() {
        val session = opened()
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "elsewhere")).id)
        val theirs = artifact(elsewhere, "secret.txt", "theirs".toByteArray())
        assertThat(call("orkbox_fetch_artifact", mapOf("session" to session, "artifact" to theirs)).path("error").stringValue())
            .contains("no artifact")
    }

    @Test
    fun `a session is this conversation's alone, and closing it destroys its workspace`() {
        val session = opened()
        val other = recorder.open(workspaceId, "chat", "elsewhere")
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x"), into = other).path("error").stringValue())
            .contains("no box session")

        assertThat(call("orkbox_sessions").path("sessions")[0].path("session").asLong()).isEqualTo(session)

        call("orkbox_extend", mapOf("session" to session, "ttl_seconds" to 600)).also {
            assertThat(it.path("status").stringValue()).isEqualTo("ready")
        }

        // The conversation's Box panel sees it, newest first.
        val second = opened()
        val panel = graphQlTester.document(
            "{ sessionBoxes(sessionId: $conversation) { id boxId boxName boxKind remoteSessionId active status createdAt lastUsedAt } }",
        ).execute()
        panel.path("sessionBoxes[*].id").entityList(Long::class.java).containsExactly(second, session)
        panel.path("sessionBoxes[0].boxId").entity(String::class.java).isEqualTo("local")
        panel.path("sessionBoxes[0].active").entity(Boolean::class.java).isEqualTo(true)
        val log = graphQlTester.document(
            "{ sessionBoxLog(sessionId: $conversation, sessionBoxId: $session) { events { seq type data } nextSeq available message } }",
        ).execute()
        log.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(true)
        log.path("sessionBoxLog.events[0].type").entity(String::class.java).isEqualTo("session.created")

        val closed = call("orkbox_close", mapOf("session" to session))
        assertThat(closed.path("closed").asBoolean()).isTrue()
        assertThat(sessions.findById(session).get().closedAt).isNotNull()
        assertThat(Files.list(root).use { files -> files.filter { it.fileName.toString() == sessions.findById(session).get().remoteSessionId }.count() })
            .isZero()
        assertThat(call("orkbox_sessions").path("sessions").size()).isEqualTo(1)
        assertThat(call("orkbox_sessions", mapOf("include_closed" to true)).path("sessions").size()).isEqualTo(2)

        graphQlTester.document("{ sessionBoxes(sessionId: $conversation) { id active status } }").execute()
            .path("sessionBoxes[1].active").entity(Boolean::class.java).isEqualTo(false)

        assertThat(audit.findAll().map { it.message }).contains(
            "Box session $session opened on local by Builder",
            "Box session $session on local closed by Builder after 0 commands",
        )
    }

    /**
     * A subagent is the conversation asking somebody to do part of it, so the
     * sandbox is the conversation's: it works in the session its asker opened,
     * and the asker carries on in one it opened - while a conversation outside
     * the family is refused as before.
     */
    @Test
    fun `a subagent works in its asker's box session, and the asker in the subagent's`() {
        val parents = opened()
        val child = recorder.openUnder(conversation, "helper")

        call("orkbox_write", mapOf("session" to parents, "path" to "from-parent.txt", "content" to "hello"))
        assertThat(call("orkbox_read", mapOf("session" to parents, "path" to "from-parent.txt"), into = child).toString())
            .contains("hello")
        call("orkbox_sessions", into = child).path("sessions").also { listed ->
            assertThat(listed.toList().map { it.path("session").asLong() }).containsExactly(parents)
            assertThat(listed[0].path("openedBy").stringValue()).isEqualTo("Builder")
        }

        // What the subagent takes out lands in its own store, where its own tools look.
        call("orkbox_export", mapOf("session" to parents, "key" to "work"), into = child)
        assertThat(store.get(child, "work")).isNotNull()
        assertThat(store.get(conversation, "work")).isNull()

        val childs = call("orkbox_open", into = child).path("session").asLong()
        assertThat(call("orkbox_exec", mapOf("session" to childs, "command" to "echo shared")).path("stdout").stringValue())
            .contains("shared")
        assertThat(call("orkbox_sessions").path("sessions").toList().map { it.path("session").asLong() }).containsExactly(childs, parents)

        val stranger = recorder.open(workspaceId, "chat", "elsewhere")
        assertThat(call("orkbox_exec", mapOf("session" to parents, "command" to "echo x"), into = stranger).path("error").stringValue())
            .contains("no box session")
        assertThat(call("orkbox_sessions", into = stranger).path("sessions").size()).isZero()
    }

    /**
     * A subagent granted other boxes works in its asker's session on the
     * asker's grant - asking it in is handing it the sandbox - but opens none of
     * its own there, and loses the session when the asker loses the box.
     */
    @Test
    fun `a subagent without the box works in its asker's session on the asker's grant`() {
        val parents = opened()
        val child = recorder.openUnder(conversation, "helper")
        val helper = agents.save(Agent(workspaceId = workspaceId, name = "Helper", type = AgentType.LLM).apply { boxLabels = listOf("other") })

        assertThat(call("orkbox_exec", mapOf("session" to parents, "command" to "echo helped"), who = helper, into = child).path("stdout").stringValue())
            .contains("helped")
        assertThat(call("orkbox_open", mapOf("box" to "local"), who = helper, into = child).path("error").stringValue())
            .contains("may not use a box called local")

        agent.boxLabels = listOf("other")
        agents.save(agent)
        assertThat(call("orkbox_exec", mapOf("session" to parents, "command" to "echo x"), who = helper, into = child).path("error").stringValue())
            .contains("no longer use")
    }

    /**
     * A turn holds the agent it loaded when it began, and can run for an hour.
     * A label granted part way through has to reach the tools in that turn: it
     * reached the watcher, which reads the agent fresh, and the watcher fired on
     * a box that orkbox_list and orkbox_open then said was not there.
     */
    @Test
    fun `a label granted while a turn runs reaches that turn's box tools`() {
        val stale = agents.save(Agent(workspaceId = workspaceId, name = "Waiting", type = AgentType.LLM).apply { boxLabels = listOf("elsewhere") })
        assertThat(call("orkbox_list", who = stale).path("boxes").size()).isZero()

        agents.findById(requireNotNull(stale.id)).get().also {
            it.boxLabels = listOf("elsewhere", "local")
            agents.save(it)
        }

        assertThat(stale.boxLabels).containsExactly("elsewhere")
        val listed = call("orkbox_list", who = stale).path("boxes")
        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0].path("name").stringValue()).isEqualTo("local")
        val answer = call("orkbox_open", who = stale)
        assertThat(answer.path("error").isMissingNode).describedAs(answer.toString()).isTrue()
    }

    @Test
    fun `a grant taken back is taken back for a session already open`() {
        val session = opened()
        agent.boxLabels = emptyList()
        agents.save(agent)
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x")).path("error").stringValue())
            .contains("not been given any boxes")
        agent.boxLabels = listOf("other")
        agents.save(agent)
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x")).path("error").stringValue())
            .contains("no longer use")
    }

    @Test
    fun `switching the local box off stops it, and its sessions with it`() {
        val session = opened()
        settings.setLocalEnabled(false, "alice")
        local.refresh()
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x")).path("error").stringValue())
            .contains("no longer configured")
        // What was recorded while it ran is still the log.
        graphQlTester.document("{ sessionBoxLog(sessionId: $conversation, sessionBoxId: $session) { available message } }").execute()
            .also { it.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(true) }
            .path("sessionBoxLog.message").entity(String::class.java)
            .isEqualTo("Box local is switched off; this is the log as last recorded")

        settings.setLocalEnabled(true, "alice")
        // A new instance, which never heard of the old session.
        assertThat(call("orkbox_exec", mapOf("session" to session, "command" to "echo x")).path("code").stringValue())
            .isEqualTo("session_not_found")
    }

    private fun logOf(session: Long, after: Int? = null) = graphQlTester.document(
        "{ sessionBoxLog(sessionId: $conversation, sessionBoxId: $session${after?.let { ", after: $it" } ?: ""}) " +
            "{ events { seq type data } nextSeq available message } }",
    ).execute()

    private fun outputOf(session: Long, execution: String, offset: Int = 0) = graphQlTester.document(
        """{ sessionBoxOutput(sessionId: $conversation, sessionBoxId: $session, executionId: "$execution", offset: $offset) """ +
            "{ text offset nextOffset done available message } }",
    ).execute()

    @Suppress("UNCHECKED_CAST")
    private fun statusOf(session: Long): Map<String, Any?> = graphQlTester.document("{ sessionBoxes(sessionId: $conversation) { id active status boxLabel } }")
        .execute().path("sessionBoxes").entityList(Map::class.java).get().single { it["id"].toString().toLong() == session } as Map<String, Any?>

    @Test
    fun `a session's log is recorded as it is used, and outlives a restart of the local box`() {
        val session = opened()
        val execution = call("orkbox_exec", mapOf("session" to session, "command" to "echo hello")).let {
            assertThat(it.path("exitCode").asInt()).describedAs(it.toString()).isZero()
            it.path("execution").stringValue()
        }
        outputOf(session, execution).also {
            it.path("sessionBoxOutput.text").entity(String::class.java).satisfies({ text -> assertThat(text).contains("hello") })
            it.path("sessionBoxOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxOutput.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxOutput.message").valueIsNull()
        }
        assertThat(outputs.findByBoxSessionIdAndExecutionId(session, execution)?.text).contains("hello")
        val recorded = events.findAll().filter { it.boxSessionId == session }.sortedBy { it.seq }
        assertThat(recorded.map { it.type }).contains("session.created", "execution.started", "execution.finished")
        assertThat(recorded.map { it.type }).doesNotContain("execution.output")
        assertThat(recorded.map { it.seq }).doesNotHaveDuplicates()

        // A setting the running instance was not built from closes it, and
        // every session it held in memory with it.
        settings.setLocalMaxSessions(settings.localMaxSessions() + 1, "alice")
        local.refresh()
        assertThat(local.runningSince()).isNull()

        val restarted = logOf(session)
        restarted.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(true)
        restarted.path("sessionBoxLog.message").entity(String::class.java)
            .isEqualTo("Box local has restarted since; this is the log as last recorded")
        restarted.path("sessionBoxLog.events[*].seq").entityList(Int::class.java).containsExactly(*recorded.map { it.seq.toInt() }.toTypedArray())
        restarted.path("sessionBoxLog.events[0].type").entity(String::class.java).isEqualTo("session.created")
        restarted.path("sessionBoxLog.nextSeq").entity(Int::class.java).isEqualTo(recorded.last().seq.toInt())

        // Past the end: nothing more, and still a log rather than an absence.
        logOf(session, recorded.last().seq.toInt()).also {
            it.path("sessionBoxLog.events").entityList(Any::class.java).hasSize(0)
            it.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxLog.nextSeq").entity(Int::class.java).isEqualTo(recorded.last().seq.toInt())
        }

        assertThat(statusOf(session)).containsEntry("status", "stopped").containsEntry("active", false)
        assertThat(sessions.findById(session).get().closedAt).isNotNull()
        assertThat(statusOf(session)).containsEntry("boxLabel", "local")

        // The Boxes page still lists it, from the record, though the box holds nothing.
        graphQlTester.document("""{ boxSessions(id: "local") { id status active orknuxSessionId agentName } }""")
            .execute().path("boxSessions").entityList(Map::class.java).get().also { listed ->
                assertThat(listed).hasSize(1)
                assertThat(listed.single()["id"]).isEqualTo(sessions.findById(session).get().remoteSessionId)
                assertThat(listed.single()["status"]).isEqualTo("stopped")
                assertThat(listed.single()["active"]).isEqualTo(false)
                assertThat(listed.single()["orknuxSessionId"]).isEqualTo(conversation.toString())
                assertThat(listed.single()["agentName"]).isEqualTo("Builder")
            }

        outputOf(session, execution).also {
            it.path("sessionBoxOutput.text").entity(String::class.java).satisfies({ text -> assertThat(text).contains("hello") })
            it.path("sessionBoxOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxOutput.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxOutput.message").entity(String::class.java)
                .isEqualTo("Box local has restarted since; this is the output as last recorded")
        }

        // Started again, the local box is a new instance that never held it.
        val later = opened()
        assertThat(local.runningSince()).isNotNull()
        logOf(session).path("sessionBoxLog.message").entity(String::class.java)
            .isEqualTo("Box local has restarted since; this is the log as last recorded")
        assertThat(statusOf(session)).containsEntry("status", "stopped")
        assertThat(statusOf(later)).containsEntry("status", "ready").containsEntry("active", true)
        logOf(later).also {
            it.path("sessionBoxLog.message").valueIsNull()
            it.path("sessionBoxLog.events[0].type").entity(String::class.java).isEqualTo("session.created")
        }
    }

    @Test
    fun `a session the local box no longer holds is stopped, and with nothing recorded its log is unavailable`() {
        assertThat(local.runningSince()).isNull()
        val row = sessions.save(
            BoxSession(
                workspaceId = workspaceId,
                llmSessionId = conversation,
                agentId = agent.id,
                agentName = agent.name,
                boxKind = BoxKind.LOCAL,
                boxName = LOCAL_BOX_NAME,
                remoteSessionId = "from-before-a-restart",
                status = "ready",
            ),
        )
        val session = requireNotNull(row.id)

        logOf(session).also {
            it.path("sessionBoxLog.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionBoxLog.message").entity(String::class.java).isEqualTo("Box local has restarted since this session")
            it.path("sessionBoxLog.events").entityList(Any::class.java).hasSize(0)
        }
        assertThat(statusOf(session)).containsEntry("status", "stopped").containsEntry("active", false)
        assertThat(sessions.findById(session).get().closedAt).isNotNull()
    }

    @Test
    fun `a session's recorded log goes with its conversation`() {
        val session = opened()
        assertThat(events.findAll().filter { it.boxSessionId == session }).isNotEmpty()
        jdbc.update("DELETE FROM llm_session WHERE id = ?", conversation)
        assertThat(sessions.findById(session)).isEmpty()
        assertThat(events.findAll().filter { it.boxSessionId == session }).isEmpty()
    }

    @Test
    fun `a local command's output is kept up to the setting, and says where the rest was not`() {
        settings.setLocalOutputKeptBytes(3, "alice")
        val session = opened()
        val cut = call("orkbox_exec", mapOf("session" to session, "command" to "echo hello")).path("execution").stringValue()
        assertThat(outputs.findByBoxSessionIdAndExecutionId(session, cut)).satisfies({
            assertThat(it!!.text).isEqualTo("hel")
            assertThat(it.truncated).isTrue()
        })
        settings.setLocalOutputKeptBytes(0, "alice")
        val none = call("orkbox_exec", mapOf("session" to session, "command" to "echo again")).path("execution").stringValue()
        assertThat(outputs.findByBoxSessionIdAndExecutionId(session, none)).isNull()

        settings.setLocalMaxSessions(settings.localMaxSessions() + 1, "alice")
        local.refresh()

        outputOf(session, cut).also {
            it.path("sessionBoxOutput.text").entity(String::class.java).isEqualTo("hel")
            it.path("sessionBoxOutput.nextOffset").entity(Int::class.java).isEqualTo(3)
            it.path("sessionBoxOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionBoxOutput.message").entity(String::class.java)
                .isEqualTo("Box local has restarted since; this is the output as last recorded, and the rest of it was not kept")
        }
        outputOf(session, cut, 1).path("sessionBoxOutput.text").entity(String::class.java).isEqualTo("el")
        outputOf(session, none).also {
            it.path("sessionBoxOutput.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionBoxOutput.done").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionBoxOutput.message").entity(String::class.java)
                .isEqualTo("Box local has restarted since, and this command's output was not kept")
        }
    }

    @Test
    fun `a session answers with an absolute link to the conversation with it open, and says to send it`() {
        val answer = call("orkbox_open")
        val session = answer.path("session").asLong()
        val expected = web.baseUrl.trimEnd('/') + "/workspace/$workspaceId/sessions/$conversation?box=$session"
        assertThat(expected).startsWith("http")
        assertThat(answer.path("url").stringValue()).isEqualTo(expected)
        assertThat(answer.path("note").stringValue()).contains("url").contains("sesję w skrzynce").contains("<url|sesję w skrzynce>")
        assertThat(call("orkbox_sessions").path("sessions")[0].path("url").stringValue()).isEqualTo(expected)
    }
}
