package io.mszymanski.orknux.server.tin

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
 * The tin tools against a real tin: the local one, embedded, on the `local`
 * runtime under a temporary directory - so a session is a directory and a
 * command is a process on this machine, on Windows and Linux alike. Through
 * [AgentTools], so the menu and the dispatch are what is tested, not only the
 * tools behind them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TinToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val settings: TinSettings,
    @Autowired val local: LocalTin,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val sessions: TinSessionRepository,
    @Autowired val tins: TinRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val events: TinSessionEventRepository,
    @Autowired val outputs: TinSessionOutputRepository,
    @Autowired val web: io.mszymanski.orknux.server.security.WebProperties,
    @Autowired val jdbc: org.springframework.jdbc.core.JdbcTemplate,
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
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "tins")).id)
        conversation = recorder.open(workspaceId, "chat", "build")
        agent = agents.save(Agent(workspaceId = workspaceId, name = "Builder", type = AgentType.LLM).apply { tinLabels = listOf("local") })
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("tin.") })
        local.refresh()
        sessions.deleteAll()
        tins.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap(), who: Agent = agent, into: Long? = conversation): JsonNode =
        mapper.readTree(tools.run(who, ToolCall("call", name, mapper.writeValueAsString(arguments)), into))

    private fun opened(): Long {
        val answer = call("tin_open")
        assertThat(answer.path("error").isMissingNode).describedAs(answer.toString()).isTrue()
        return answer.path("session").asLong()
    }

    @Test
    fun `an agent granted no label is offered no tin tool, and one granted a label all of them`() {
        val without = agents.save(Agent(workspaceId = workspaceId, name = "Plain", type = AgentType.LLM))
        assertThat(tools.specsFor(without).map { it.name }).noneMatch { it.startsWith("tin_") }
        assertThat(call("tin_open", who = without).path("error").stringValue()).contains("not been given any tins")

        assertThat(tools.specsFor(agent).map { it.name }).containsAll(TinTools.NAMES)
    }

    @Test
    fun `a label no tin has reaches nothing, and a tin with no label is nobody's`() {
        agent.tinLabels = listOf("elsewhere")
        assertThat(call("tin_list").path("tins").size()).isZero()
        assertThat(call("tin_open").path("error").stringValue()).contains("No tin you may use")

        tins.save(Tin(name = "unlabelled", label = null, address = "http://unlabelled.invalid"))
        agent.tinLabels = listOf("local")
        assertThat(call("tin_open", mapOf("tin" to "unlabelled")).path("error").stringValue()).contains("may not use")
    }

    @Test
    fun `a command runs in a session and answers with its exit code and output`() {
        val listed = call("tin_list").path("tins")
        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0].path("name").stringValue()).isEqualTo("local")
        assertThat(listed[0].path("runtimes")[0].path("name").stringValue()).isEqualTo("local")

        val session = opened()
        val row = sessions.findById(session).get()
        assertThat(row.tinKind).isEqualTo(TinKind.LOCAL)
        assertThat(row.llmSessionId).isEqualTo(conversation)
        assertThat(row.agentId).isEqualTo(agent.id)
        assertThat(row.status).isEqualTo("ready")

        val ran = call("tin_exec", mapOf("session" to session, "command" to "echo hello"))
        assertThat(ran.path("status").stringValue()).describedAs(ran.toString()).isEqualTo("succeeded")
        assertThat(ran.path("exitCode").asInt()).isZero()
        assertThat(ran.path("stdout").stringValue()).contains("hello")

        // Environment variables are `variables`, not `env`: the word env is the environment itself.
        val reading = if (System.getProperty("os.name").startsWith("Windows")) "echo %GREETING%" else "echo \$GREETING"
        val greeted = call("tin_exec", mapOf("session" to session, "command" to reading, "variables" to mapOf("GREETING" to "ahoy")))
        assertThat(greeted.path("stdout").stringValue()).describedAs(greeted.toString()).contains("ahoy")

        val failed = call("tin_exec", mapOf("session" to session, "command" to "exit 3"))
        assertThat(failed.path("status").stringValue()).isEqualTo("failed")
        assertThat(failed.path("exitCode").asInt()).isEqualTo(3)

        // Not waited for, then waited for.
        val started = call("tin_exec", mapOf("session" to session, "command" to "echo later", "wait" to false))
        val execution = started.path("execution").stringValue()
        val finished = call("tin_execution", mapOf("session" to session, "execution" to execution, "wait_seconds" to 30))
        assertThat(finished.path("status").stringValue()).isEqualTo("succeeded")
        val output = call("tin_output", mapOf("session" to session, "execution" to execution))
        assertThat(output.path("text").stringValue()).contains("later")
        assertThat(output.path("nextOffset").asLong()).isPositive()

        val log = call("tin_log", mapOf("session" to session))
        assertThat(log.path("events").values().map { it.path("type").stringValue() })
            .contains("session.created", "execution.started", "execution.finished")
    }

    @Test
    fun `files are written, read, edited, found, moved and deleted`() {
        val session = opened()
        call("tin_write", mapOf("session" to session, "path" to "src/app.txt", "content" to "port = 8080\nname = demo\n"))
            .also { assertThat(it.path("size").asInt()).isEqualTo(24) }
        assertThat(call("tin_read", mapOf("session" to session, "path" to "src/app.txt")).path("content").stringValue())
            .isEqualTo("port = 8080\nname = demo\n")
        assertThat(call("tin_read", mapOf("session" to session, "path" to "src/app.txt", "start_line" to 2)).path("content").stringValue())
            .isEqualTo("name = demo\n")

        call("tin_edit", mapOf("session" to session, "path" to "src/app.txt", "edits" to listOf(mapOf("old_text" to "8080", "new_text" to "9090"))))
            .also { assertThat(it.path("replacements")[0].asInt()).isEqualTo(1) }
        val missed = call("tin_edit", mapOf("session" to session, "path" to "src/app.txt", "edits" to listOf(mapOf("old_text" to "nothing", "new_text" to "x"))))
        assertThat(missed.path("code").stringValue()).isEqualTo("edit_no_match")

        val found = call("tin_search", mapOf("session" to session, "pattern" to "9090"))
        assertThat(found.path("matches")[0].path("path").stringValue()).isEqualTo("src/app.txt")

        assertThat(call("tin_files", mapOf("session" to session, "path" to "src")).path("entries")[0].path("path").stringValue())
            .isEqualTo("src/app.txt")
        assertThat(call("tin_stat", mapOf("session" to session, "path" to "src/app.txt")).path("type").stringValue()).isEqualTo("file")

        call("tin_move", mapOf("session" to session, "from" to "src/app.txt", "to" to "app.txt"))
        assertThat(call("tin_stat", mapOf("session" to session, "path" to "src/app.txt")).path("code").stringValue())
            .isEqualTo("file_not_found")
        call("tin_delete", mapOf("session" to session, "path" to "app.txt"))
        assertThat(call("tin_read", mapOf("session" to session, "path" to "app.txt")).path("code").stringValue())
            .isEqualTo("file_not_found")
        assertThat(call("tin_read", mapOf("session" to session, "path" to "../outside")).path("code").stringValue())
            .isEqualTo("path_outside_workspace")
    }

    @Test
    fun `a directory goes out as an archive in the session store and back in`() {
        val session = opened()
        call("tin_write", mapOf("session" to session, "path" to "out/report.txt", "content" to "done"))
        val exported = call("tin_export", mapOf("session" to session, "path" to "out", "key" to "report.tgz"))
        assertThat(exported.path("error").isMissingNode).describedAs(exported.toString()).isTrue()
        assertThat(exported.path("bytes").asInt()).isPositive()

        val imported = call("tin_import", mapOf("session" to session, "key" to "report.tgz", "path" to "copy"))
        assertThat(imported.path("error").isMissingNode).describedAs(imported.toString()).isTrue()
        assertThat(call("tin_read", mapOf("session" to session, "path" to "copy/report.txt")).path("content").stringValue())
            .isEqualTo("done")
    }

    @Test
    fun `a session is this conversation's alone, and closing it destroys its workspace`() {
        val session = opened()
        val other = recorder.open(workspaceId, "chat", "elsewhere")
        assertThat(call("tin_exec", mapOf("session" to session, "command" to "echo x"), into = other).path("error").stringValue())
            .contains("no tin session")

        assertThat(call("tin_sessions").path("sessions")[0].path("session").asLong()).isEqualTo(session)

        call("tin_extend", mapOf("session" to session, "ttl_seconds" to 600)).also {
            assertThat(it.path("status").stringValue()).isEqualTo("ready")
        }

        // The conversation's Tin panel sees it, newest first.
        val second = opened()
        val panel = graphQlTester.document(
            "{ sessionTins(sessionId: $conversation) { id tinId tinName tinKind remoteSessionId active status createdAt lastUsedAt } }",
        ).execute()
        panel.path("sessionTins[*].id").entityList(Long::class.java).containsExactly(second, session)
        panel.path("sessionTins[0].tinId").entity(String::class.java).isEqualTo("local")
        panel.path("sessionTins[0].active").entity(Boolean::class.java).isEqualTo(true)
        val log = graphQlTester.document(
            "{ sessionTinLog(sessionId: $conversation, sessionTinId: $session) { events { seq type data } nextSeq available message } }",
        ).execute()
        log.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(true)
        log.path("sessionTinLog.events[0].type").entity(String::class.java).isEqualTo("session.created")

        val closed = call("tin_close", mapOf("session" to session))
        assertThat(closed.path("closed").asBoolean()).isTrue()
        assertThat(sessions.findById(session).get().closedAt).isNotNull()
        assertThat(Files.list(root).use { files -> files.filter { it.fileName.toString() == sessions.findById(session).get().remoteSessionId }.count() })
            .isZero()
        assertThat(call("tin_sessions").path("sessions").size()).isEqualTo(1)
        assertThat(call("tin_sessions", mapOf("include_closed" to true)).path("sessions").size()).isEqualTo(2)

        graphQlTester.document("{ sessionTins(sessionId: $conversation) { id active status } }").execute()
            .path("sessionTins[1].active").entity(Boolean::class.java).isEqualTo(false)

        assertThat(audit.findAll().map { it.message }).contains(
            "Tin session $session opened on local by Builder",
            "Tin session $session on local closed by Builder after 0 commands",
        )
    }

    @Test
    fun `a grant taken back is taken back for a session already open`() {
        val session = opened()
        agent.tinLabels = emptyList()
        assertThat(call("tin_exec", mapOf("session" to session, "command" to "echo x")).path("error").stringValue())
            .contains("not been given any tins")
        agent.tinLabels = listOf("other")
        assertThat(call("tin_exec", mapOf("session" to session, "command" to "echo x")).path("error").stringValue())
            .contains("no longer use")
    }

    @Test
    fun `switching the local tin off stops it, and its sessions with it`() {
        val session = opened()
        settings.setLocalEnabled(false, "alice")
        local.refresh()
        assertThat(call("tin_exec", mapOf("session" to session, "command" to "echo x")).path("error").stringValue())
            .contains("no longer configured")
        // What was recorded while it ran is still the log.
        graphQlTester.document("{ sessionTinLog(sessionId: $conversation, sessionTinId: $session) { available message } }").execute()
            .also { it.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(true) }
            .path("sessionTinLog.message").entity(String::class.java)
            .isEqualTo("Tin local is switched off; this is the log as last recorded")

        settings.setLocalEnabled(true, "alice")
        // A new instance, which never heard of the old session.
        assertThat(call("tin_exec", mapOf("session" to session, "command" to "echo x")).path("code").stringValue())
            .isEqualTo("session_not_found")
    }

    private fun logOf(session: Long, after: Int? = null) = graphQlTester.document(
        "{ sessionTinLog(sessionId: $conversation, sessionTinId: $session${after?.let { ", after: $it" } ?: ""}) " +
            "{ events { seq type data } nextSeq available message } }",
    ).execute()

    private fun outputOf(session: Long, execution: String, offset: Int = 0) = graphQlTester.document(
        """{ sessionTinOutput(sessionId: $conversation, sessionTinId: $session, executionId: "$execution", offset: $offset) """ +
            "{ text offset nextOffset done available message } }",
    ).execute()

    @Suppress("UNCHECKED_CAST")
    private fun statusOf(session: Long): Map<String, Any?> = graphQlTester.document("{ sessionTins(sessionId: $conversation) { id active status tinLabel } }")
        .execute().path("sessionTins").entityList(Map::class.java).get().single { it["id"].toString().toLong() == session } as Map<String, Any?>

    @Test
    fun `a session's log is recorded as it is used, and outlives a restart of the local tin`() {
        val session = opened()
        val execution = call("tin_exec", mapOf("session" to session, "command" to "echo hello")).let {
            assertThat(it.path("exitCode").asInt()).describedAs(it.toString()).isZero()
            it.path("execution").stringValue()
        }
        outputOf(session, execution).also {
            it.path("sessionTinOutput.text").entity(String::class.java).satisfies({ text -> assertThat(text).contains("hello") })
            it.path("sessionTinOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinOutput.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinOutput.message").valueIsNull()
        }
        assertThat(outputs.findByTinSessionIdAndExecutionId(session, execution)?.text).contains("hello")
        val recorded = events.findAll().filter { it.tinSessionId == session }.sortedBy { it.seq }
        assertThat(recorded.map { it.type }).contains("session.created", "execution.started", "execution.finished")
        assertThat(recorded.map { it.type }).doesNotContain("execution.output")
        assertThat(recorded.map { it.seq }).doesNotHaveDuplicates()

        // A setting the running instance was not built from closes it, and
        // every session it held in memory with it.
        settings.setLocalMaxSessions(settings.localMaxSessions() + 1, "alice")
        local.refresh()
        assertThat(local.runningSince()).isNull()

        val restarted = logOf(session)
        restarted.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(true)
        restarted.path("sessionTinLog.message").entity(String::class.java)
            .isEqualTo("Tin local has restarted since; this is the log as last recorded")
        restarted.path("sessionTinLog.events[*].seq").entityList(Int::class.java).containsExactly(*recorded.map { it.seq.toInt() }.toTypedArray())
        restarted.path("sessionTinLog.events[0].type").entity(String::class.java).isEqualTo("session.created")
        restarted.path("sessionTinLog.nextSeq").entity(Int::class.java).isEqualTo(recorded.last().seq.toInt())

        // Past the end: nothing more, and still a log rather than an absence.
        logOf(session, recorded.last().seq.toInt()).also {
            it.path("sessionTinLog.events").entityList(Any::class.java).hasSize(0)
            it.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinLog.nextSeq").entity(Int::class.java).isEqualTo(recorded.last().seq.toInt())
        }

        assertThat(statusOf(session)).containsEntry("status", "stopped").containsEntry("active", false)
        assertThat(sessions.findById(session).get().closedAt).isNotNull()
        assertThat(statusOf(session)).containsEntry("tinLabel", "local")

        // The Tins page still lists it, from the record, though the tin holds nothing.
        graphQlTester.document("""{ tinSessions(id: "local") { id status active orknuxSessionId agentName } }""")
            .execute().path("tinSessions").entityList(Map::class.java).get().also { listed ->
                assertThat(listed).hasSize(1)
                assertThat(listed.single()["id"]).isEqualTo(sessions.findById(session).get().remoteSessionId)
                assertThat(listed.single()["status"]).isEqualTo("stopped")
                assertThat(listed.single()["active"]).isEqualTo(false)
                assertThat(listed.single()["orknuxSessionId"]).isEqualTo(conversation.toString())
                assertThat(listed.single()["agentName"]).isEqualTo("Builder")
            }

        outputOf(session, execution).also {
            it.path("sessionTinOutput.text").entity(String::class.java).satisfies({ text -> assertThat(text).contains("hello") })
            it.path("sessionTinOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinOutput.available").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinOutput.message").entity(String::class.java)
                .isEqualTo("Tin local has restarted since; this is the output as last recorded")
        }

        // Started again, the local tin is a new instance that never held it.
        val later = opened()
        assertThat(local.runningSince()).isNotNull()
        logOf(session).path("sessionTinLog.message").entity(String::class.java)
            .isEqualTo("Tin local has restarted since; this is the log as last recorded")
        assertThat(statusOf(session)).containsEntry("status", "stopped")
        assertThat(statusOf(later)).containsEntry("status", "ready").containsEntry("active", true)
        logOf(later).also {
            it.path("sessionTinLog.message").valueIsNull()
            it.path("sessionTinLog.events[0].type").entity(String::class.java).isEqualTo("session.created")
        }
    }

    @Test
    fun `a session the local tin no longer holds is stopped, and with nothing recorded its log is unavailable`() {
        assertThat(local.runningSince()).isNull()
        val row = sessions.save(
            TinSession(
                workspaceId = workspaceId,
                llmSessionId = conversation,
                agentId = agent.id,
                agentName = agent.name,
                tinKind = TinKind.LOCAL,
                tinName = LOCAL_TIN_NAME,
                remoteSessionId = "from-before-a-restart",
                status = "ready",
            ),
        )
        val session = requireNotNull(row.id)

        logOf(session).also {
            it.path("sessionTinLog.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionTinLog.message").entity(String::class.java).isEqualTo("Tin local has restarted since this session")
            it.path("sessionTinLog.events").entityList(Any::class.java).hasSize(0)
        }
        assertThat(statusOf(session)).containsEntry("status", "stopped").containsEntry("active", false)
        assertThat(sessions.findById(session).get().closedAt).isNotNull()
    }

    @Test
    fun `a session's recorded log goes with its conversation`() {
        val session = opened()
        assertThat(events.findAll().filter { it.tinSessionId == session }).isNotEmpty()
        jdbc.update("DELETE FROM llm_session WHERE id = ?", conversation)
        assertThat(sessions.findById(session)).isEmpty()
        assertThat(events.findAll().filter { it.tinSessionId == session }).isEmpty()
    }

    @Test
    fun `a local command's output is kept up to the setting, and says where the rest was not`() {
        settings.setLocalOutputKeptBytes(3, "alice")
        val session = opened()
        val cut = call("tin_exec", mapOf("session" to session, "command" to "echo hello")).path("execution").stringValue()
        assertThat(outputs.findByTinSessionIdAndExecutionId(session, cut)).satisfies({
            assertThat(it!!.text).isEqualTo("hel")
            assertThat(it.truncated).isTrue()
        })
        settings.setLocalOutputKeptBytes(0, "alice")
        val none = call("tin_exec", mapOf("session" to session, "command" to "echo again")).path("execution").stringValue()
        assertThat(outputs.findByTinSessionIdAndExecutionId(session, none)).isNull()

        settings.setLocalMaxSessions(settings.localMaxSessions() + 1, "alice")
        local.refresh()

        outputOf(session, cut).also {
            it.path("sessionTinOutput.text").entity(String::class.java).isEqualTo("hel")
            it.path("sessionTinOutput.nextOffset").entity(Int::class.java).isEqualTo(3)
            it.path("sessionTinOutput.done").entity(Boolean::class.java).isEqualTo(true)
            it.path("sessionTinOutput.message").entity(String::class.java)
                .isEqualTo("Tin local has restarted since; this is the output as last recorded, and the rest of it was not kept")
        }
        outputOf(session, cut, 1).path("sessionTinOutput.text").entity(String::class.java).isEqualTo("el")
        outputOf(session, none).also {
            it.path("sessionTinOutput.available").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionTinOutput.done").entity(Boolean::class.java).isEqualTo(false)
            it.path("sessionTinOutput.message").entity(String::class.java)
                .isEqualTo("Tin local has restarted since, and this command's output was not kept")
        }
    }

    @Test
    fun `a session answers with an absolute link to the conversation with it open, and says to send it`() {
        val answer = call("tin_open")
        val session = answer.path("session").asLong()
        val expected = web.baseUrl.trimEnd('/') + "/workspace/$workspaceId/sessions/$conversation?tin=$session"
        assertThat(expected).startsWith("http")
        assertThat(answer.path("url").stringValue()).isEqualTo(expected)
        assertThat(answer.path("note").stringValue()).contains("url").contains("sesję w puszce").contains("<url|sesję w puszce>")
        assertThat(call("tin_sessions").path("sessions")[0].path("url").stringValue()).isEqualTo(expected)
    }
}
