package io.mszymanski.orknux.server.schedule

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Schedules as an agent meets them, and as the clock runs them.
 *
 * Setting one by interval and by cron, and every refusal `schedule_set` can
 * give; listing and stopping; and the tick - called here by hand, as the suite
 * runs without db-scheduler - posting to the session's inbox, working out the
 * next run, ending at its number of runs or its end time, and running once,
 * not in a burst, after the server was down.
 */
@SpringBootTest
@Import(ScheduleTest.Heard::class)
class ScheduleTest(
    @Autowired val tools: ScheduleTools,
    @Autowired val service: ScheduleService,
    @Autowired val schedules: AgentScheduleRepository,
    @Autowired val settings: ScheduleSettings,
    @Autowired val installation: InstallationSettings,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val events: SessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val heard: CopyOnWriteArrayList<Long>,
) {

    @TestConfiguration
    class Heard {
        @Bean
        fun scheduleHeard() = CopyOnWriteArrayList<Long>()

        @Bean
        fun scheduleListener(scheduleHeard: CopyOnWriteArrayList<Long>) = Listener(scheduleHeard)
    }

    class Listener(private val heard: CopyOnWriteArrayList<Long>) {
        @org.springframework.context.event.EventListener
        fun due(event: SessionEventDue) {
            heard += event.sessionId
        }
    }

    private var workspaceId: Long = 0
    private var session: Long = 0
    private lateinit var agent: Agent

    @BeforeEach
    fun reset() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "reports")).id)
        session = recorder.open(workspaceId, "chat", "reports")
        agent = agents.save(Agent(workspaceId = workspaceId, name = "Reporter", type = AgentType.LLM))
        heard.clear()
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("schedule.") })
        schedules.deleteAll()
        events.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun shed(of: Agent = agent, at: Long = session): ToolShed = requireNotNull(tools.shed(of, at))

    private fun call(name: String, arguments: String, of: Agent = agent) = shed(of).run(ToolCall("1", name, arguments))

    private fun idIn(answer: String): Long = Regex("\"id\":(\\d+)").find(answer)!!.groupValues[1].toLong()

    private fun inbox(): List<String> =
        events.findAll().filter { it.sessionId == session && it.kind == SessionEventKind.SCHEDULE }.map { it.body }

    /** A schedule saved straight to the table, so a test chooses when it is due. */
    private fun saved(
        every: Int? = 300,
        cron: String? = null,
        zone: String? = null,
        nextRunAt: OffsetDateTime,
        times: Int? = null,
        until: OffsetDateTime? = null,
    ): AgentSchedule = schedules.save(
        AgentSchedule(
            workspaceId = workspaceId, sessionId = session, agentId = agent.id, agentName = agent.name,
            note = "post the queue report", everySeconds = every, cron = cron, timezone = zone,
            maxRuns = times, untilAt = until, createdAt = nextRunAt.minusSeconds(300), nextRunAt = nextRunAt,
        ),
    )

    @Test
    fun `the three tools are built-ins every agent holds, and are offered only where schedules are allowed`() {
        ScheduleTools.NAMES.forEach {
            assertThat(BuiltInTools.GRANTED).contains(it)
            assertThat(BuiltInTools.granted(agent, it)).isTrue()
        }
        assertThat(requireNotNull(BuiltInTools.lentTo(agent, shed())).specs().map { it.name })
            .containsExactly(ScheduleTools.SET, ScheduleTools.LIST, ScheduleTools.CANCEL)
        assertThat(tools.shed(agent, null)).isNull()

        // The description sends the model to the other two kinds of waiting, by their declared names.
        val described = shed().specs().single { it.name == ScheduleTools.SET }.description
        assertThat(described)
            .contains(io.mszymanski.orknux.server.chat.TimerTools.SET)
            .contains(io.mszymanski.orknux.server.watcher.WatcherTools.SET)
            .contains("300")

        settings.setMaxPerAgent(0, "alice")
        assertThat(shed().specs()).isEmpty()
        assertThat(shed().handles(ScheduleTools.SET)).isFalse()
    }

    @Test
    fun `a schedule is set by interval, written into the session, audited and listed`() {
        val answer = call(ScheduleTools.SET, """{"note":"post the queue report","every_seconds":600,"times":3}""")
        assertThat(answer).contains("\"set\":true").contains("\"nextRunAt\"")
        assertThat(Regex("\"nextRunInSeconds\":(\\d+)").find(answer)!!.groupValues[1].toLong()).isBetween(598L, 600L)
        val id = idIn(answer)

        val schedule = schedules.findById(id).orElseThrow()
        assertThat(schedule.status).isEqualTo(ScheduleStatus.ACTIVE)
        assertThat(schedule.everySeconds).isEqualTo(600)
        assertThat(schedule.cron).isNull()
        assertThat(schedule.maxRuns).isEqualTo(3)
        assertThat(schedule.agentId).isEqualTo(agent.id)
        assertThat(audit.findAll().map { it.message }).contains("Schedule #$id set by agent Reporter")

        val listed = call(ScheduleTools.LIST, "{}")
        assertThat(listed).contains("\"id\":$id").contains("post the queue report").contains("\"thisConversation\":true")
    }

    @Test
    fun `a schedule is set by cron, read in its timezone or the installation's`() {
        val warsaw = idIn(
            call(ScheduleTools.SET, """{"note":"standup","cron":"0 9 * * 1-5","timezone":"Europe/Warsaw"}"""),
        )
        val schedule = schedules.findById(warsaw).orElseThrow()
        assertThat(schedule.cron).isEqualTo("0 9 * * 1-5")
        assertThat(schedule.timezone).isEqualTo("Europe/Warsaw")
        val next = requireNotNull(schedule.nextRunAt).atZoneSameInstant(ZoneId.of("Europe/Warsaw"))
        assertThat(next.hour).isEqualTo(9)
        assertThat(next.minute).isZero()
        assertThat(next.dayOfWeek.value).isBetween(1, 5)

        val local = idIn(call(ScheduleTools.SET, """{"note":"weekly","cron":"30 8 * * 1"}"""))
        assertThat(schedules.findById(local).orElseThrow().timezone).isEqualTo(installation.workingZone().id)
    }

    @Test
    fun `what schedule_set refuses, it refuses in words the model can act on`() {
        fun refused(arguments: String) = call(ScheduleTools.SET, arguments).also { assertThat(it).contains("\"error\"") }

        assertThat(refused("""{"note":"x","every_seconds":60}""")).contains("at least 300")
        // Every minute is below the floor however it is written.
        assertThat(refused("""{"note":"x","cron":"* * * * *"}""")).contains("every 60s").contains("300")
        assertThat(refused("""{"note":"x","cron":"0,1 9 * * *"}""")).contains("every 60s")
        assertThat(refused("""{"note":"x","cron":"not a cron"}""")).contains("five-field")
        assertThat(refused("""{"note":"x","cron":"0 0 9 * * *"}""")).contains("five-field")
        assertThat(refused("""{"note":"x","cron":"0 9 31 2 *"}""")).contains("never comes up")
        assertThat(refused("""{"note":"x","cron":"0 9 * * *","timezone":"Mars/Olympus"}""")).contains("not a timezone")
        assertThat(refused("""{"note":"x"}""")).contains("exactly one of")
        assertThat(refused("""{"note":"x","every_seconds":600,"cron":"0 9 * * *"}""")).contains("exactly one of")
        assertThat(refused("""{"every_seconds":600}""")).contains(ScheduleTools.NOTE)
        assertThat(refused("""{"note":"x","every_seconds":600,"times":0}""")).contains(ScheduleTools.TIMES)
        assertThat(refused("""{"note":"x","every_seconds":600,"until":"tomorrow"}""")).contains("ISO 8601")
        val soon = OffsetDateTime.now().plusSeconds(60)
        assertThat(refused("""{"note":"x","every_seconds":600,"until":"$soon"}"""))
            .contains("before the first run").contains(io.mszymanski.orknux.server.chat.TimerTools.SET)
        assertThat(schedules.count()).isZero()

        // The floor and the count are read from Admin Settings at the moment of asking.
        settings.setMinIntervalSeconds(60, "alice")
        settings.setMaxPerAgent(1, "alice")
        idIn(call(ScheduleTools.SET, """{"note":"x","cron":"* * * * *"}"""))
        assertThat(refused("""{"note":"y","every_seconds":600}""")).contains("already have 1").contains(ScheduleTools.CANCEL)
    }

    @Test
    fun `an agent stops its own schedule and nobody else's`() {
        val id = idIn(call(ScheduleTools.SET, """{"note":"x","every_seconds":600}"""))
        val other = agents.save(Agent(workspaceId = workspaceId, name = "Other", type = AgentType.LLM))
        assertThat(call(ScheduleTools.CANCEL, """{"id":$id}""", of = other)).contains("no schedule #$id")

        assertThat(call(ScheduleTools.CANCEL, """{"id":$id}""")).contains("\"stopped\":true")
        val stopped = schedules.findById(id).orElseThrow()
        assertThat(stopped.status).isEqualTo(ScheduleStatus.STOPPED)
        assertThat(stopped.nextRunAt).isNull()
        assertThat(stopped.outcome).isEqualTo("Stopped by Reporter.")
        assertThat(call(ScheduleTools.CANCEL, """{"id":$id}""")).contains("already ended")
        assertThat(call(ScheduleTools.LIST, "{}")).doesNotContain("\"id\":$id")
    }

    @Test
    fun `a due schedule posts its note to the inbox, wakes the session, and works out its next run`() {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val due = now.minusSeconds(5)
        val id = requireNotNull(saved(nextRunAt = due).id)
        val later = requireNotNull(saved(nextRunAt = now.plusSeconds(60)).id)

        assertThat(service.tick(now)).isEqualTo(1)

        val posted = inbox().single()
        assertThat(posted).contains("schedule #$id").contains("run 1").contains("post the queue report")
            .contains(ScheduleTools.CANCEL).doesNotContain("late")
        assertThat(heard).contains(session)
        val ran = schedules.findById(id).orElseThrow()
        assertThat(ran.runs).isEqualTo(1)
        assertThat(ran.lastRunAt).isAtSameInstantAs(now)
        // On the cadence it was set on, not shifted by when the tick happened to look.
        assertThat(ran.nextRunAt).isAtSameInstantAs(due.plusSeconds(300))
        assertThat(schedules.findById(later).orElseThrow().runs).isZero()

        // Nothing more is due at the same moment.
        assertThat(service.tick(now)).isZero()
    }

    @Test
    fun `a cron schedule's next run is the cron's next time in its zone`() {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val id = requireNotNull(saved(every = null, cron = "0 9 * * *", zone = "Europe/Warsaw", nextRunAt = now.minusSeconds(1)).id)
        service.tick(now)
        val next = requireNotNull(schedules.findById(id).orElseThrow().nextRunAt).atZoneSameInstant(ZoneId.of("Europe/Warsaw"))
        assertThat(next.hour).isEqualTo(9)
        assertThat(next.minute).isZero()
        assertThat(next.toOffsetDateTime()).isAfter(now)
    }

    @Test
    fun `a schedule ends after its number of runs`() {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val id = requireNotNull(saved(nextRunAt = now.minusSeconds(1), times = 2).id)

        service.tick(now)
        assertThat(schedules.findById(id).orElseThrow().status).isEqualTo(ScheduleStatus.ACTIVE)
        assertThat(inbox().single()).contains("run 1 of 2")

        service.tick(now.plusSeconds(300))
        val ended = schedules.findById(id).orElseThrow()
        assertThat(ended.status).isEqualTo(ScheduleStatus.ENDED)
        assertThat(ended.runs).isEqualTo(2)
        assertThat(ended.nextRunAt).isNull()
        assertThat(ended.finishedAt).isNotNull()
        assertThat(ended.outcome).contains("2 runs")
        assertThat(inbox().last()).contains("run 2 of 2").contains("last run")

        assertThat(service.tick(now.plusDays(1))).isZero()
    }

    @Test
    fun `a schedule ends where its next run would fall after its end time`() {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val id = requireNotNull(saved(nextRunAt = now.minusSeconds(1), until = now.plusSeconds(100)).id)
        service.tick(now)
        val ended = schedules.findById(id).orElseThrow()
        assertThat(ended.status).isEqualTo(ScheduleStatus.ENDED)
        assertThat(ended.outcome).contains("end time")
        assertThat(inbox().single()).contains("last run")
    }

    /** A day down with a schedule of every five minutes is one late run, not two hundred and eighty-eight. */
    @Test
    fun `runs missed while the server was down are not replayed in a burst`() {
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val missedSince = now.minusDays(1)
        val id = requireNotNull(saved(nextRunAt = missedSince).id)

        assertThat(service.tick(now)).isEqualTo(1)
        assertThat(service.tick(now)).isZero()

        assertThat(inbox()).hasSize(1)
        assertThat(inbox().single()).contains("late").contains("not made up")
        val after = schedules.findById(id).orElseThrow()
        assertThat(after.runs).isEqualTo(1)
        val next = requireNotNull(after.nextRunAt)
        assertThat(next).isAfter(now)
        assertThat(next).isBeforeOrEqualTo(now.plusSeconds(300))
        // Still on the cadence it was set on.
        assertThat(java.time.Duration.between(missedSince, next).seconds % 300).isZero()
    }

    @Test
    fun `deleting the session deletes its schedules`() {
        val id = idIn(call(ScheduleTools.SET, """{"note":"x","every_seconds":600}"""))
        events.deleteAll()
        jdbc.update("DELETE FROM llm_session_event WHERE session_id = ?", session)
        jdbc.update("DELETE FROM llm_session WHERE id = ?", session)
        assertThat(schedules.findById(id)).isEmpty()
    }

    /**
     * V389 and its SQLite copy, V67: an agent written before schedules existed
     * is given the Always mark for each schedule tool a new agent has - once,
     * however often it runs - and one that hid a tool on purpose is not.
     */
    @Test
    fun `the migration marks the schedule tools Always for agents that predate them`() {
        val capped = agents.save(Agent(workspaceId = workspaceId, name = "Capped", type = AgentType.LLM).apply { maxTools = 5 })
        val hiding = agents.save(
            Agent(workspaceId = workspaceId, name = "Hiding", type = AgentType.LLM)
                .apply { hiddenTools = mutableListOf(ScheduleTools.SET) },
        )
        val engine = if (jdbc.dataSource!!.connection.use { it.metaData.databaseProductName }.contains("SQLite", true)) "sqlite" else "postgresql"
        val migration = if (engine == "sqlite") "V67__agent_schedules.sql" else "V389__agent_schedules.sql"
        val text = requireNotNull(javaClass.getResource("/db/migration/$engine/$migration")).readText()
        // Only the marks: the table and the inbox's kinds are there already.
        val marks = text.substring(text.indexOf("INSERT INTO agent_required_tool"))
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }

        repeat(2) { marks.forEach(jdbc::execute) }

        listOf(agent, capped).forEach { one ->
            assertThat(agents.findById(one.id!!).orElseThrow().requiredTools).describedAs(one.name)
                .containsOnlyOnce(ScheduleTools.SET, ScheduleTools.LIST, ScheduleTools.CANCEL)
        }
        assertThat(agents.findById(hiding.id!!).orElseThrow().requiredTools)
            .doesNotContain(ScheduleTools.SET).contains(ScheduleTools.LIST)
    }
}
