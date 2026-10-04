package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.SkillTool
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
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
import org.springframework.jdbc.core.JdbcTemplate
import java.time.OffsetDateTime

/**
 * The Watchers page and Admin -> Settings -> Watchers, through the API they
 * use. Issue #606. And the Watchers skill, which every agent - new, or written
 * before it existed - is offered.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WatcherAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val watchers: WatcherRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val events: SessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val agents: AgentRepository,
    @Autowired val skills: SkillTool,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0

    @BeforeEach
    fun reset() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        session = recorder.open(workspaceId, "chat", "release")
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("watcher.") })
        watchers.deleteAll()
        events.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun watcher(
        tool: String,
        status: WatcherStatus = WatcherStatus.ACTIVE,
        createdAt: OffsetDateTime = OffsetDateTime.now(),
        finishedAt: OffsetDateTime? = null,
        workspace: Long = workspaceId,
    ): Long = requireNotNull(
        watchers.save(
            Watcher(
                workspaceId = workspace, sessionId = session, agentId = 7, agentName = "Builder", tool = tool,
                arguments = """{"id":"42"}""", conditionKind = WatcherConditionKind.JSONPATH,
                condition = "$[?(@.status == 'done')]", intervalSeconds = 60, timeoutSeconds = 3600,
                note = "tell the team", status = status, createdAt = createdAt,
                expiresAt = createdAt.plusHours(1), nextCheckAt = createdAt.plusMinutes(1),
                finishedAt = finishedAt, outcome = finishedAt?.let { "Fired on check 3." },
            ),
        ).id,
    )

    private val fields = """id sessionId sessionTitle agentName tool arguments conditionKind condition
        intervalSeconds timeoutSeconds note status checks outcome createdAt expiresAt finishedAt"""

    @Test
    fun `the page lists running watchers by default, newest first, and ended ones when asked`() {
        val old = watcher("buildStatus", createdAt = OffsetDateTime.now().minusHours(2))
        val new = watcher("deployStatus")
        val done = watcher(
            "ticketStatus", WatcherStatus.FIRED,
            createdAt = OffsetDateTime.now().minusHours(3), finishedAt = OffsetDateTime.now().minusMinutes(5),
        )

        val active = graphQlTester.document("{ watchers(workspaceId: $workspaceId) { totalElements content { $fields } } }")
            .execute()
        active.path("watchers.content[*].id").entityList(Long::class.java).containsExactly(new, old)
        active.path("watchers.totalElements").entity(Int::class.java).isEqualTo(2)
        active.path("watchers.content[0].sessionId").entity(Long::class.java).isEqualTo(session)
        active.path("watchers.content[0].arguments").entity(String::class.java).isEqualTo("""{"id":"42"}""")
        active.path("watchers.content[0].conditionKind").entity(String::class.java).isEqualTo("JSONPATH")
        active.path("watchers.content[0].intervalSeconds").entity(Int::class.java).isEqualTo(60)
        active.path("watchers.content[0].finishedAt").valueIsNull()

        val finished = graphQlTester.document(
            "{ watchers(workspaceId: $workspaceId, finished: true) { content { $fields } } }",
        ).execute()
        finished.path("watchers.content[*].id").entityList(Long::class.java).containsExactly(done)
        finished.path("watchers.content[0].status").entity(String::class.java).isEqualTo("FIRED")
        finished.path("watchers.content[0].outcome").entity(String::class.java).isEqualTo("Fired on check 3.")
        finished.path("watchers.content[0].finishedAt").hasValue()
    }

    @Test
    fun `stop ends a running watcher, tells its agent, and is audited`() {
        val id = watcher("buildStatus")
        graphQlTester.document("mutation { stopWatcher(id: $id) { id status finishedAt } }").execute()
            .path("stopWatcher.status").entity(String::class.java).isEqualTo("STOPPED")

        assertThat(watchers.findById(id).orElseThrow().finishedBy).isEqualTo("alice")
        assertThat(events.findAll().single().body).contains("was stopped by alice")
        val written = audit.findAll().single { it.message.startsWith("Watcher") }
        assertThat(written.message).isEqualTo("Watcher #$id on buildStatus stopped")
        assertThat(written.category).isEqualTo(WorkspaceAuditCategory.AGENT)
        assertThat(written.workspaceId).isEqualTo(workspaceId)

        graphQlTester.document("mutation { stopWatcher(id: $id) { id } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("WatcherNotActive") }
        graphQlTester.document("mutation { stopWatcher(id: 999999) { id } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("WatcherNotFound") }
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `somebody who cannot see the workspace can neither list nor stop its watchers`() {
        val id = watcher("buildStatus")
        graphQlTester.document("{ watchers(workspaceId: $workspaceId) { totalElements } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        graphQlTester.document("mutation { stopWatcher(id: $id) { id } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(watchers.findById(id).orElseThrow().status).isEqualTo(WatcherStatus.ACTIVE)
        graphQlTester.document("{ watcherSettings { maxSeconds } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
    }

    @Test
    fun `the three limits default to a week, fifteen seconds and ten, and are set and audited`() {
        val read = "{ watcherSettings { maxSeconds maxSecondsConfigured minIntervalSeconds maxPerAgent } }"
        graphQlTester.document(read).execute()
            .path("watcherSettings.maxSeconds").entity(Int::class.java).isEqualTo(604800)
            .path("watcherSettings.maxSecondsConfigured").entity(Int::class.java).isEqualTo(604800)
            .path("watcherSettings.minIntervalSeconds").entity(Int::class.java).isEqualTo(15)
            .path("watcherSettings.maxPerAgent").entity(Int::class.java).isEqualTo(10)

        graphQlTester.document("mutation { setWatcherMaxSeconds(seconds: 3600) { maxSeconds } }").execute()
            .path("setWatcherMaxSeconds.maxSeconds").entity(Int::class.java).isEqualTo(3600)
        graphQlTester.document("mutation { setWatcherMinIntervalSeconds(seconds: 60) { minIntervalSeconds } }").execute()
            .path("setWatcherMinIntervalSeconds.minIntervalSeconds").entity(Int::class.java).isEqualTo(60)
        graphQlTester.document("mutation { setWatcherMaxPerAgent(count: 0) { maxPerAgent } }").execute()
            .path("setWatcherMaxPerAgent.maxPerAgent").entity(Int::class.java).isEqualTo(0)
        assertThat(audit.findAll().map { it.message }).containsExactlyInAnyOrder(
            "Longest a watcher may run set to 3600 seconds",
            "Shortest watcher interval set to 60 seconds",
            "Watchers per agent set to 0",
        )

        graphQlTester.document("mutation { setWatcherMaxSeconds(seconds: 59) { maxSeconds } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("WatcherMaxSecondsOutOfRange") }
        graphQlTester.document("mutation { setWatcherMinIntervalSeconds(seconds: 0) { minIntervalSeconds } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("WatcherMinIntervalOutOfRange") }
        graphQlTester.document("mutation { setWatcherMaxPerAgent(count: -1) { maxPerAgent } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("WatcherMaxPerAgentOutOfRange") }
        graphQlTester.document(read).execute().path("watcherSettings.maxSeconds").entity(Int::class.java).isEqualTo(3600)
    }

    @Test
    fun `the Watchers skill is offered to a new agent and to one written before it existed`() {
        val created = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Fresh", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        val fresh = agents.findById(created).orElseThrow()
        // Saved straight to the table with nothing granted, as an agent from an older release is.
        val older = agents.save(Agent(workspaceId = workspaceId, name = "Older", type = AgentType.LLM))

        listOf(fresh, older).forEach { agent ->
            assertThat(skills.list(agent).map { it.name }).describedAs(agent.name).contains("Watchers")
            val page = requireNotNull(skills.load(agent, "watchers"))
            assertThat(page.content).contains("`watcher_set`")
            assertThat(skills.search(agent, "watcher jsonpath").map { it.name }).contains("Watchers")
        }
    }

    /**
     * Always, not only offered: the user asked for it, because an agent that
     * has to decide to read the skill first reaches for finish_answer with a
     * wake-up instead. A new agent starts marked, and V338 marks every agent
     * that predates it - but not one that hid the skill on purpose.
     */
    @Test
    fun `the Watchers skill is Always for a new agent and for one written before it existed`() {
        val created = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Fresh", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        assertThat(agents.findById(created).orElseThrow().requiredSkills).contains("watchers")

        val older = agents.save(Agent(workspaceId = workspaceId, name = "Older", type = AgentType.LLM))
        val hiding = agents.save(
            Agent(workspaceId = workspaceId, name = "Hiding", type = AgentType.LLM).apply { hiddenSkills = mutableListOf("watchers") },
        )
        assertThat(agents.findById(older.id!!).orElseThrow().requiredSkills).doesNotContain("watchers")

        val engine = if (jdbc.dataSource!!.connection.use { it.metaData.databaseProductName }.contains("SQLite", true)) "sqlite" else "postgresql"
        val migration = if (engine == "sqlite") "V16__watchers_skill_always.sql" else "V338__watchers_skill_always.sql"
        val sql = requireNotNull(javaClass.getResource("/db/migration/$engine/$migration")).readText()
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString(" ")
        jdbc.execute(sql)

        assertThat(agents.findById(older.id!!).orElseThrow().requiredSkills).containsOnlyOnce("watchers")
        assertThat(agents.findById(hiding.id!!).orElseThrow().requiredSkills).doesNotContain("watchers")
        // Run again, it adds nothing twice.
        jdbc.execute(sql)
        assertThat(agents.findById(older.id!!).orElseThrow().requiredSkills).containsOnlyOnce("watchers")
    }
}
