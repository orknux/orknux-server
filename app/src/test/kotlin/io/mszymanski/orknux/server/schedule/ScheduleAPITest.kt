package io.mszymanski.orknux.server.schedule

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
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
import java.time.OffsetDateTime

/**
 * The Schedules page, a session's Schedules panel and Admin -> Settings ->
 * Schedules, through the API they use.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ScheduleAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val schedules: AgentScheduleRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val events: SessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0

    @BeforeEach
    fun reset() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "reports")).id)
        session = recorder.open(workspaceId, "chat", "morning report")
        jdbc.update("UPDATE llm_session SET title = ? WHERE id = ?", "Morning report", session)
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("schedule.") })
        schedules.deleteAll()
        events.deleteAll()
        lines.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun schedule(
        note: String,
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        finishedAt: OffsetDateTime? = null,
        cron: String? = null,
    ): Long = requireNotNull(
        schedules.save(
            AgentSchedule(
                workspaceId = workspaceId, sessionId = session, agentId = 7, agentName = "Reporter", note = note,
                everySeconds = if (cron == null) 3600 else null, cron = cron, timezone = cron?.let { "Europe/Warsaw" },
                status = status, runs = if (finishedAt != null) 3 else 0, maxRuns = 3, createdAt = createdAt,
                nextRunAt = if (status == ScheduleStatus.ACTIVE) createdAt.plusHours(1) else null,
                finishedAt = finishedAt, outcome = finishedAt?.let { "Ended after 3 runs, as many as it was set to make." },
            ),
        ).id,
    )

    private val fields = """id sessionId sessionTitle agentName note everySeconds cron timezone status runs times
        until createdAt nextRunAt lastRunAt finishedAt outcome"""

    @Test
    fun `the page lists active schedules by default, newest first, and ended ones when asked`() {
        val old = schedule("old", createdAt = OffsetDateTime.now().minusHours(2))
        val new = schedule("new", cron = "0 9 * * 1-5")
        val done = schedule(
            "done", ScheduleStatus.ENDED,
            createdAt = OffsetDateTime.now().minusHours(3), finishedAt = OffsetDateTime.now().minusMinutes(5),
        )

        val active = graphQlTester.document("{ schedules(workspaceId: $workspaceId) { totalElements content { $fields } } }")
            .execute()
        active.path("schedules.content[*].id").entityList(Long::class.java).containsExactly(new, old)
        active.path("schedules.totalElements").entity(Int::class.java).isEqualTo(2)
        active.path("schedules.content[0].sessionTitle").entity(String::class.java).isEqualTo("Morning report")
        active.path("schedules.content[0].cron").entity(String::class.java).isEqualTo("0 9 * * 1-5")
        active.path("schedules.content[0].timezone").entity(String::class.java).isEqualTo("Europe/Warsaw")
        active.path("schedules.content[0].everySeconds").valueIsNull()
        active.path("schedules.content[1].everySeconds").entity(Int::class.java).isEqualTo(3600)
        active.path("schedules.content[1].times").entity(Int::class.java).isEqualTo(3)
        active.path("schedules.content[0].nextRunAt").hasValue()
        active.path("schedules.content[0].outcome").valueIsNull()

        val ended = graphQlTester.document(
            "{ schedules(workspaceId: $workspaceId, ended: true) { content { $fields } } }",
        ).execute()
        ended.path("schedules.content[*].id").entityList(Long::class.java).containsExactly(done)
        ended.path("schedules.content[0].status").entity(String::class.java).isEqualTo("ENDED")
        ended.path("schedules.content[0].runs").entity(Int::class.java).isEqualTo(3)
        ended.path("schedules.content[0].nextRunAt").valueIsNull()
        ended.path("schedules.content[0].finishedAt").hasValue()
    }

    @Test
    fun `a session lists the schedules it set, active and ended`() {
        val ended = schedule(
            "done", ScheduleStatus.STOPPED,
            createdAt = OffsetDateTime.now().minusHours(3), finishedAt = OffsetDateTime.now().minusMinutes(5),
        )
        val active = schedule("running")
        val mine = session
        session = recorder.open(workspaceId, "chat", "elsewhere")
        schedule("another session's")

        val listed = graphQlTester.document("{ sessionSchedules(sessionId: $mine) { $fields } }").execute()
        listed.path("sessionSchedules[*].id").entityList(Long::class.java).containsExactly(active, ended)
        listed.path("sessionSchedules[1].status").entity(String::class.java).isEqualTo("STOPPED")
        graphQlTester.document("{ sessionSchedules(sessionId: 999999) { id } }").execute()
            .errors().expect { it.message?.contains("999999") == true }
    }

    @Test
    fun `stop ends an active schedule, tells its agent, and is audited`() {
        val id = schedule("post the queue report")
        graphQlTester.document("mutation { stopSchedule(id: $id) { id status nextRunAt finishedAt outcome } }").execute()
            .path("stopSchedule.status").entity(String::class.java).isEqualTo("STOPPED")
            .path("stopSchedule.nextRunAt").valueIsNull()
            .path("stopSchedule.outcome").entity(String::class.java).isEqualTo("Stopped by alice.")

        val told = events.findAll().single()
        assertThat(told.kind).isEqualTo(SessionEventKind.SCHEDULE)
        assertThat(told.body).contains("stopped by alice").contains("post the queue report")
        val written = audit.findAll().single { it.message.startsWith("Schedule") }
        assertThat(written.message).isEqualTo("Schedule #$id of Reporter stopped")
        assertThat(written.category).isEqualTo(WorkspaceAuditCategory.AGENT)
        assertThat(written.workspaceId).isEqualTo(workspaceId)

        graphQlTester.document("mutation { stopSchedule(id: $id) { id } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ScheduleNotActive") }
        graphQlTester.document("mutation { stopSchedule(id: 999999) { id } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ScheduleNotFound") }
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `somebody who cannot see the workspace can neither list nor stop its schedules, nor read the limits`() {
        val id = schedule("x")
        graphQlTester.document("{ schedules(workspaceId: $workspaceId) { totalElements } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("{ sessionSchedules(sessionId: $session) { id } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("mutation { stopSchedule(id: $id) { id } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(schedules.findById(id).orElseThrow().status).isEqualTo(ScheduleStatus.ACTIVE)
        graphQlTester.document("{ scheduleSettings { maxPerAgent } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("mutation { setScheduleMaxPerAgent(count: 3) { maxPerAgent } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `the two limits default to five minutes and ten, and are set and audited`() {
        val read = "{ scheduleSettings { minIntervalSeconds minIntervalSecondsConfigured maxPerAgent maxPerAgentConfigured } }"
        graphQlTester.document(read).execute()
            .path("scheduleSettings.minIntervalSeconds").entity(Int::class.java).isEqualTo(300)
            .path("scheduleSettings.minIntervalSecondsConfigured").entity(Int::class.java).isEqualTo(300)
            .path("scheduleSettings.maxPerAgent").entity(Int::class.java).isEqualTo(10)
            .path("scheduleSettings.maxPerAgentConfigured").entity(Int::class.java).isEqualTo(10)

        graphQlTester.document("mutation { setScheduleMinIntervalSeconds(seconds: 900) { minIntervalSeconds } }").execute()
            .path("setScheduleMinIntervalSeconds.minIntervalSeconds").entity(Int::class.java).isEqualTo(900)
        graphQlTester.document("mutation { setScheduleMaxPerAgent(count: 0) { maxPerAgent maxPerAgentConfigured } }").execute()
            .path("setScheduleMaxPerAgent.maxPerAgent").entity(Int::class.java).isEqualTo(0)
            .path("setScheduleMaxPerAgent.maxPerAgentConfigured").entity(Int::class.java).isEqualTo(10)
        assertThat(audit.findAll().map { it.message }).containsExactlyInAnyOrder(
            "Shortest schedule interval set to 900 seconds",
            "Schedules per agent set to 0",
        )

        graphQlTester.document("mutation { setScheduleMinIntervalSeconds(seconds: 5) { minIntervalSeconds } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ScheduleMinIntervalOutOfRange") }
        graphQlTester.document("mutation { setScheduleMaxPerAgent(count: -1) { maxPerAgent } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ScheduleMaxPerAgentOutOfRange") }
        graphQlTester.document(read).execute()
            .path("scheduleSettings.minIntervalSeconds").entity(Int::class.java).isEqualTo(900)
    }

    @Test
    fun `deleting the workspace deletes its schedules`() {
        val id = schedule("x")
        events.deleteAll()
        lines.deleteAll()
        workspaces.deleteById(workspaceId)
        assertThat(schedules.findById(id)).isEmpty()
    }
}
