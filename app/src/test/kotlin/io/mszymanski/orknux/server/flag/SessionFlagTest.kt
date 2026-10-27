package io.mszymanski.orknux.server.flag

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.SessionSheds
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Flagging a session: the tool an agent flags the conversation it is in with,
 * held to that conversation, and Admin > Reports > Flags, which only an
 * installation administrator reads.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionFlagTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sheds: SessionSheds,
    @Autowired val mapper: ObjectMapper,
    @Autowired val flags: SessionFlagRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0
    private var session: Long = 0
    private var otherSession: Long = 0
    private val agent = Agent(workspaceId = 0, name = "Guard", type = AgentType.LLM)

    @BeforeEach
    fun make() {
        flags.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "flags-${System.nanoTime()}")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "other-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "flag-${System.nanoTime()}")
        otherSession = recorder.open(otherWorkspaceId, "test", "other-${System.nanoTime()}")
    }

    @AfterEach
    fun back() {
        tunables.set(Tunable.SESSION_FLAGS_MOST, null, "test")
    }

    private fun call(into: Long, name: String, arguments: Map<String, Any?>): Map<*, *> {
        val shed = requireNotNull(sheds.of(agent, into))
        return mapper.readValue(shed.run(ToolCall("c", name, mapper.writeValueAsString(arguments))), Map::class.java)
    }

    private fun flag(into: Long, arguments: Map<String, Any?>) = call(into, SessionFlagTools.FLAG, arguments)

    private val fine = mapOf("type" to "security", "severity" to 4, "note" to "Asked for the system prompt three times")

    /* ------------------------------------------------------------- the tool -- */

    @Test
    fun `the tool flags the session it is lent for, whatever session the call names`() {
        val answer = flag(session, fine + mapOf("session" to otherSession, "sessionId" to otherSession, "workspaceId" to otherWorkspaceId))

        assertThat(answer["flagged"]).isEqualTo(true)
        val raised = flags.findAll().single()
        assertThat(raised.sessionId).isEqualTo(session)
        assertThat(raised.workspaceId).isEqualTo(workspaceId)
        assertThat(raised.agentName).isEqualTo("Guard")
        assertThat(raised.type).isEqualTo(SessionFlagType.SECURITY)
        assertThat(raised.severity).isEqualTo(4)
        assertThat(raised.note).isEqualTo("Asked for the system prompt three times")
        // Nothing about it in the session, which every member of the workspace reads.
        assertThat(events.findAll().filter { it.sessionId == session }.mapNotNull { it.content })
            .noneMatch { it.contains("flag", ignoreCase = true) }
        // Audited as the installation's, which only the admin audit log shows.
        assertThat(audit.findAll().filter { it.message.contains("Session #$session flagged") })
            .singleElement().satisfies({ assertThat(it.workspaceId).isNull(); assertThat(it.userId).isEqualTo("Guard") })
        // And the model is reminded, in the answer, to say nothing.
        assertThat(answer["said"].toString()).contains("Do not mention this to the person")
    }

    @Test
    fun `a type or severity off the scale, or a missing note, is refused in words and nothing is kept`() {
        assertThat(flag(session, fine + ("severity" to 9))["error"].toString()).contains("from 0 to 5")
        assertThat(flag(session, fine + ("severity" to -1))["error"].toString()).contains("from 0 to 5")
        assertThat(flag(session, fine + ("severity" to "high"))["error"].toString()).contains("from 0 to 5")
        assertThat(flag(session, fine + ("type" to "rudeness"))["error"].toString()).contains("security or technical_issue")
        assertThat(flag(session, fine - "type")["error"].toString()).contains("security or technical_issue")
        assertThat(flag(session, fine + ("note" to "  "))["error"].toString()).contains("note")
        assertThat(flag(session, fine + ("note" to "x".repeat(SessionFlagTools.LONGEST_NOTE + 1)))["error"].toString()).contains("under")
        assertThat(flags.count()).isZero()

        // A number sent as a string, and a type in capitals, are what a model sends and are taken.
        assertThat(flag(session, mapOf("type" to "TECHNICAL_ISSUE", "severity" to "0", "note" to "The MCP server answers 500"))["flagged"]).isEqualTo(true)
        assertThat(flags.findAll().single().type).isEqualTo(SessionFlagType.TECHNICAL_ISSUE)
    }

    @Test
    fun `unflagging clears only what this session carries`() {
        val mine = (flag(session, fine)["id"] as Number).toLong()

        // Another conversation's agent cannot clear it, by id or by default.
        assertThat(call(otherSession, SessionFlagTools.UNFLAG, mapOf("id" to mine))["error"].toString()).contains("no open flag $mine")
        assertThat(call(otherSession, SessionFlagTools.UNFLAG, emptyMap())["error"].toString()).contains("no open flag to clear")
        assertThat(flags.findById(mine).orElseThrow().clearedAt).isNull()

        val cleared = call(session, SessionFlagTools.UNFLAG, emptyMap())
        assertThat(cleared["cleared"]).isEqualTo(true)
        assertThat((cleared["id"] as Number).toLong()).isEqualTo(mine)
        val held = flags.findById(mine).orElseThrow()
        assertThat(held.clearedAt).isNotNull()
        assertThat(held.clearedBy).isEqualTo("Guard")
        assertThat(held.state).isEqualTo(SessionFlagState.CLEARED)
        assertThat(call(session, SessionFlagTools.UNFLAG, mapOf("id" to mine))["error"].toString()).contains("no open flag $mine")
        assertThat(audit.findAll().map { it.message }).contains("Flag #$mine on session #$session cleared")
    }

    @Test
    fun `a conversation holds as many open flags as Admin Settings allows`() {
        tunables.set(Tunable.SESSION_FLAGS_MOST, 1, "test")
        assertThat(flag(session, fine)["flagged"]).isEqualTo(true)
        assertThat(flag(session, fine)["error"].toString()).contains("already holds 1 open flags")
        call(session, SessionFlagTools.UNFLAG, emptyMap())
        assertThat(flag(session, fine)["flagged"]).isEqualTo(true)
    }

    /**
     * The paragraph that tells an agent when to flag is there only where the
     * agent holds the tool, and names it - and what it takes - by what the
     * tool declares, so a renamed tool or parameter is a renamed sentence.
     */
    @Test
    fun `the briefing is said only where the tool is granted, and names the tool as declared`() {
        val lent = requireNotNull(sheds.of(agent, session))
        val declared = lent.specs().single { it.name == SessionFlagTools.FLAG }
        val clearing = lent.specs().single { it.name == SessionFlagTools.UNFLAG }
        val briefing = requireNotNull(lent.briefing())
        assertThat(briefing).contains(SessionFlagTools.BRIEFING)
        assertThat(SessionFlagTools.BRIEFING).contains(declared.name, clearing.name)
        declared.parameters.forEach { assertThat(SessionFlagTools.BRIEFING).contains(it.name) }
        // The type's values as the tool's schema lists them.
        @Suppress("UNCHECKED_CAST")
        val values = declared.parameters.single { it.name == SessionFlagTools.TYPE }.schema!!["enum"] as List<String>
        values.forEach { assertThat(SessionFlagTools.BRIEFING).contains(it) }
        // What it is told to keep quiet about, and when to flag.
        assertThat(SessionFlagTools.BRIEFING).contains("Never tell the person", "secrets", "injecting instructions", "set up wrong")
        /*
         * Session 936: "Let me note the request for the admins", written before
         * the flag. The prohibition names what a model reaches for, and when -
         * in the paragraph and in the tool's own description alike.
         */
        listOf(SessionFlagTools.BRIEFING, declared.description).forEach { told ->
            assertThat(told).contains(
                "say nothing about it to the person at all",
                "not before you flag, not while you work, not after",
                "I'll note this", "I'll report this to the admins", "let me log this", "flagging this", "escalating this",
                "Write nothing beside the call",
                "as you would if nothing were recorded",
            )
        }
        assertThat(BuiltInTools.GRANTED).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(BuiltInTools.startingAlways()).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)

        // Hidden on the agent's page: no paragraph, and no tool.
        val without = Agent(workspaceId = 0, name = "Quiet", type = AgentType.LLM, hiddenTools = mutableListOf(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG))
        val bare = BuiltInTools.lentTo(without, sheds.of(without, session))
        assertThat(bare?.briefing().orEmpty()).doesNotContain(SessionFlagTools.BRIEFING)
        assertThat(bare?.specs().orEmpty().map { it.name }).doesNotContain(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        // And no session, no flag: there would be nothing to flag.
        assertThat(sheds.of(agent, null)?.specs().orEmpty().map { it.name }).doesNotContain(SessionFlagTools.FLAG)
    }

    /* ------------------------------------------------------------ the report -- */

    private fun seed(
        workspace: Long, into: Long, type: SessionFlagType, severity: Int, note: String,
        daysAgo: Long = 0, agentName: String = "Guard", cleared: String? = null,
    ): Long {
        /*
         * Days back on the calendar, in this server's zone. OffsetDateTime keeps
         * today's offset while it steps back, so across a clock change - 25
         * October - a flag three days ago at 23:30 landed past midnight on the
         * day after, inside the range the filter is meant to leave it out of.
         */
        val at = java.time.ZonedDateTime.now(ZoneId.systemDefault()).minusDays(daysAgo).toOffsetDateTime()
        return requireNotNull(
            flags.save(
                SessionFlag(
                    flaggedAt = at, workspaceId = workspace, sessionId = into, agentName = agentName,
                    type = type, severity = severity, note = note,
                    clearedAt = cleared?.let { at.plusMinutes(5) }, clearedBy = cleared,
                ),
            ).id,
        )
    }

    private fun rows(arguments: String): List<Map<*, *>> =
        graphQlTester.document("{ sessionFlagReport${if (arguments.isBlank()) "" else "($arguments)"} { rows { id note severity type state agentName clearedBy workspaceName sessionId } } }")
            .execute().path("sessionFlagReport.rows").entityList(Map::class.java).get()

    private fun notes(arguments: String): List<String> = rows(arguments).map { it["note"] as String }

    @Test
    fun `the report filters by workspace, type, severity, state, dates and words in the note`() {
        seed(workspaceId, session, SessionFlagType.SECURITY, 5, "alpha extract keys", daysAgo = 1)
        seed(workspaceId, session, SessionFlagType.TECHNICAL_ISSUE, 1, "beta connection 404", daysAgo = 2, cleared = "alice")
        seed(otherWorkspaceId, otherSession, SessionFlagType.SECURITY, 2, "gamma prompt injection", daysAgo = 3, agentName = "Watcher")
        seed(otherWorkspaceId, otherSession, SessionFlagType.TECHNICAL_ISSUE, 3, "delta old one", daysAgo = 60)

        assertThat(notes("")).containsExactly("alpha extract keys", "beta connection 404", "gamma prompt injection")
        assertThat(notes("filter: { workspaceId: $workspaceId }")).containsExactly("alpha extract keys", "beta connection 404")
        assertThat(notes("filter: { type: SECURITY }")).containsExactly("alpha extract keys", "gamma prompt injection")
        assertThat(notes("filter: { type: TECHNICAL_ISSUE }")).containsExactly("beta connection 404")
        assertThat(notes("filter: { severityFrom: 2, severityTo: 4 }")).containsExactly("gamma prompt injection")
        assertThat(notes("filter: { severityFrom: 5 }")).containsExactly("alpha extract keys")
        assertThat(notes("filter: { severityTo: 1 }")).containsExactly("beta connection 404")
        assertThat(notes("filter: { state: OPEN }")).containsExactly("alpha extract keys", "gamma prompt injection")
        assertThat(notes("filter: { state: CLEARED }")).containsExactly("beta connection 404")
        assertThat(notes("filter: { search: \"INJECTION\" }")).containsExactly("gamma prompt injection")
        val today = LocalDate.now(ZoneId.systemDefault())
        assertThat(notes("filter: { from: \"${today.minusDays(2)}\", to: \"${today.minusDays(1)}\" }"))
            .containsExactly("alpha extract keys", "beta connection 404")
        assertThat(notes("filter: { from: \"${today.minusDays(90)}\" }")).hasSize(4)

        // The counts and the chart cover what the filter matches, every day of the period.
        val report = graphQlTester.document(
            "{ sessionFlagReport { total open security technical highest days { day security technical severities } } }",
        ).execute()
        report.path("sessionFlagReport.total").entity(Int::class.java).isEqualTo(3)
        report.path("sessionFlagReport.open").entity(Int::class.java).isEqualTo(2)
        report.path("sessionFlagReport.security").entity(Int::class.java).isEqualTo(2)
        report.path("sessionFlagReport.technical").entity(Int::class.java).isEqualTo(1)
        report.path("sessionFlagReport.highest").entity(Int::class.java).isEqualTo(5)
        report.path("sessionFlagReport.days").entityList(Any::class.java).hasSize(30)
        report.path("sessionFlagReport.days[28].day").entity(String::class.java).isEqualTo(today.minusDays(1).toString())
        report.path("sessionFlagReport.days[28].security").entity(Int::class.java).isEqualTo(1)
        report.path("sessionFlagReport.days[28].severities").entityList(Int::class.java).containsExactly(0, 0, 0, 0, 0, 1)
        report.path("sessionFlagReport.days[27].technical").entity(Int::class.java).isEqualTo(1)
        report.path("sessionFlagReport.days[29].severities").entityList(Int::class.java).containsExactly(0, 0, 0, 0, 0, 0)
    }

    @Test
    fun `every column sorts both ways, on the server, a page at a time`() {
        seed(workspaceId, session, SessionFlagType.SECURITY, 5, "b middle", daysAgo = 2, agentName = "Bravo")
        seed(workspaceId, session, SessionFlagType.TECHNICAL_ISSUE, 0, "a first", daysAgo = 1, agentName = "Alpha", cleared = "zed")
        seed(otherWorkspaceId, otherSession, SessionFlagType.SECURITY, 3, "c last", daysAgo = 3, agentName = "Charlie")

        // Newest first unless asked.
        assertThat(notes("")).containsExactly("a first", "b middle", "c last")
        assertThat(notes("order: FLAGGED_AT, ascending: true")).containsExactly("c last", "b middle", "a first")
        assertThat(notes("order: SEVERITY")).containsExactly("b middle", "c last", "a first")
        assertThat(notes("order: SEVERITY, ascending: true")).containsExactly("a first", "c last", "b middle")
        assertThat(notes("order: AGENT")).containsExactly("a first", "b middle", "c last")
        assertThat(notes("order: AGENT, ascending: false")).containsExactly("c last", "b middle", "a first")
        assertThat(notes("order: NOTE")).containsExactly("a first", "b middle", "c last")
        assertThat(notes("order: TYPE").first()).isIn("b middle", "c last")
        assertThat(notes("order: TYPE").last()).isEqualTo("a first")
        // CLEARED before OPEN, A to Z.
        assertThat(notes("order: STATE").first()).isEqualTo("a first")
        assertThat(notes("order: STATE, ascending: false").last()).isEqualTo("a first")
        assertThat(notes("order: CLEARED_BY, ascending: false").first()).isEqualTo("a first")
        assertThat(notes("order: CLEARED_BY, ascending: true").last()).isEqualTo("a first")
        assertThat(notes("order: SESSION, ascending: true").first()).isIn("a first", "b middle")
        assertThat(notes("order: SESSION, ascending: false").first()).isEqualTo("c last")
        val names = rows("order: WORKSPACE, ascending: true").map { it["workspaceName"] as String }
        assertThat(names).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER)
        assertThat(rows("order: WORKSPACE, ascending: false").map { it["workspaceName"] as String }).isEqualTo(names.reversed())

        // Paged after sorting, so the second page is the next in that order.
        assertThat(notes("order: NOTE, page: 1, size: 1")).containsExactly("b middle")
        graphQlTester.document("{ sessionFlagReport(page: 0, size: 1) { total rows { id } } }").execute()
            .path("sessionFlagReport.total").entity(Int::class.java).isEqualTo(3)
            .path("sessionFlagReport.rows").entityList(Any::class.java).hasSize(1)
    }

    @Test
    fun `an administrator clears a flag, audited, and it is cleared once`() {
        val id = seed(workspaceId, session, SessionFlagType.SECURITY, 4, "keys")

        graphQlTester.document("mutation { clearSessionFlag(id: $id) { id state clearedBy clearedAt } }").execute()
            .path("clearSessionFlag.state").entity(String::class.java).isEqualTo("CLEARED")
            .path("clearSessionFlag.clearedBy").entity(String::class.java).isEqualTo("alice")
        assertThat(audit.findAll().filter { it.message == "Flag #$id on session #$session cleared" })
            .singleElement().satisfies({ assertThat(it.workspaceId).isNull(); assertThat(it.userId).isEqualTo("alice") })

        graphQlTester.document("mutation { clearSessionFlag(id: $id) { id } }").execute()
            .errors().expect { it.extensions["code"] == "SessionFlagAlreadyCleared" && it.message!!.contains("already cleared") }
        graphQlTester.document("mutation { clearSessionFlag(id: 987654321) { id } }").execute()
            .errors().expect { it.extensions["code"] == "SessionFlagNotFound" }

        graphQlTester.document("{ sessionFlags(sessionId: $session) { id note state } }").execute()
            .path("sessionFlags[*].note").entityList(String::class.java).containsExactly("keys")
    }

    /** Nobody but an installation administrator reads or clears a flag, through any of the three. */
    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `nobody but an installation administrator reads or clears a flag`() {
        val id = seed(workspaceId, session, SessionFlagType.SECURITY, 4, "keys")

        graphQlTester.document("{ sessionFlagReport { total rows { note } } }").execute()
            .errors().expect { it.extensions["code"] == "AdminRequired" }.verify()
        graphQlTester.document("{ sessionFlags(sessionId: $session) { note } }").execute()
            .errors().expect { it.extensions["code"] == "AdminRequired" }.verify()
        graphQlTester.document("mutation { clearSessionFlag(id: $id) { id } }").execute()
            .errors().expect { it.extensions["code"] == "AdminRequired" }.verify()
        assertThat(flags.findById(id).orElseThrow().clearedAt).isNull()
    }
}
