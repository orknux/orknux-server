package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.llm.SessionInbox
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
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Watchers as an agent meets them, and as the clock checks them. Issue #606.
 *
 * Setting one and every refusal `watcher_set` can give; listing and finishing;
 * the limits read from Admin Settings at the moment of asking; and the tick -
 * called here by hand, as the suite runs without db-scheduler - firing on a
 * match and waking the session through its inbox, timing out, and ending a
 * watcher whose tool was taken away. The watched tool is a workspace tool whose
 * source the test rewrites between ticks, which is how a result changes.
 */
@SpringBootTest
@Import(WatcherTest.Heard::class)
class WatcherTest(
    @Autowired val tools: WatcherTools,
    @Autowired val service: WatcherService,
    @Autowired val watchers: WatcherRepository,
    @Autowired val settings: WatcherSettings,
    @Autowired val stored: InstallationSettingRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val events: SessionEventRepository,
    @Autowired val inbox: SessionInbox,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val heard: CopyOnWriteArrayList<Long>,
) {

    @TestConfiguration
    class Heard {
        @Bean
        fun watcherHeard() = CopyOnWriteArrayList<Long>()

        @Bean
        fun watcherListener(watcherHeard: CopyOnWriteArrayList<Long>) = Listener(watcherHeard)
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
    private lateinit var tool: AgentTool

    @BeforeEach
    fun reset() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        session = recorder.open(workspaceId, "node", "watch")
        tool = agentTools.save(
            AgentTool(
                workspaceId = workspaceId,
                name = "buildStatus",
                description = "Says how the build is going.",
                source = returning("running"),
                typescript = returning("running"),
            ),
        )
        agent = agents.save(
            Agent(workspaceId = workspaceId, name = "Builder", type = AgentType.LLM, tools = mutableListOf("buildStatus")),
        )
        heard.clear()
    }

    @AfterEach
    fun clean() {
        stored.deleteAll(stored.findAll().filter { it.name.startsWith("watcher.") })
        watchers.deleteAll()
        events.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        agentTools.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun returning(status: String) =
        "export default async function buildStatus() { return { status: '$status', build: 41 }; }"

    private fun shed(of: Agent = agent): ToolShed = requireNotNull(tools.shed(of, session))

    private fun call(name: String, arguments: String, of: Agent = agent) =
        shed(of).run(ToolCall("1", name, arguments))

    private fun set(
        condition: String = """$[?(@.status == 'done')]""",
        type: String = "jsonpath",
        interval: Any = 15,
        timeout: Any = 600,
        toolName: String = "buildStatus",
        extra: String = "",
    ): String = call(
        WatcherTools.SET,
        """{"tool":"$toolName","arguments":{},"condition_type":"$type","condition":${json(condition)},""" +
            """"interval_seconds":$interval,"timeout_seconds":$timeout,"note":"tell the team"$extra}""",
    )

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun idIn(answer: String): Long = Regex("\"watcher\":(\\d+)").find(answer)!!.groupValues[1].toLong()

    /** Moves a watcher's next check into the past, so the next tick looks at it. */
    private fun due(id: Long) {
        val watcher = watchers.findById(id).orElseThrow()
        watcher.nextCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
    }

    private fun notes(): List<String> =
        lines.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }.mapNotNull { it.content }

    @Test
    fun `the three tools are built-ins every agent holds, an existing one included`() {
        // An agent saved with no lists at all, as one written before watchers existed is.
        listOf(WatcherTools.SET, WatcherTools.LIST, WatcherTools.FINISH).forEach {
            assertThat(BuiltInTools.GRANTED).contains(it)
            assertThat(BuiltInTools.granted(agent, it)).isTrue()
        }
        val lent = requireNotNull(BuiltInTools.lentTo(agent, shed()))
        assertThat(lent.specs().map { it.name })
            .containsExactly(WatcherTools.SET, WatcherTools.LIST, WatcherTools.FINISH)

        // Hidden on the agent's page, neither offered nor answered.
        agent.hiddenTools = mutableListOf(WatcherTools.SET)
        val hidden = requireNotNull(BuiltInTools.lentTo(agent, shed()))
        assertThat(hidden.specs().map { it.name }).doesNotContain(WatcherTools.SET)
        assertThat(hidden.handles(WatcherTools.SET)).isFalse()

        // No session, nothing to wake: not offered at all.
        assertThat(tools.shed(agent, null)).isNull()
    }

    @Test
    fun `a watcher is set, written into the session log, audited, and listed`() {
        val answer = set()
        assertThat(answer).contains("\"watcher\":").contains("\"firstCheckInSeconds\":15")
        val id = idIn(answer)

        val watcher = watchers.findById(id).orElseThrow()
        assertThat(watcher.status).isEqualTo(WatcherStatus.ACTIVE)
        assertThat(watcher.agentId).isEqualTo(agent.id)
        assertThat(watcher.sessionId).isEqualTo(session)
        assertThat(watcher.arguments).isEqualTo("{}")
        assertThat(watcher.conditionKind).isEqualTo(WatcherConditionKind.JSONPATH)
        assertThat(watcher.expiresAt).isAfter(watcher.createdAt.plusSeconds(599))
        assertThat(watcher.nextCheckAt).isAfter(watcher.createdAt.plusSeconds(14))

        assertThat(notes().single()).startsWith("Watcher #$id set by Builder: buildStatus every 15s")
        assertThat(audit.findAll().map { it.message }).contains("Watcher #$id on buildStatus set by agent Builder")

        val listed = call(WatcherTools.LIST, "{}")
        assertThat(listed).contains("\"watcher\":$id").contains("buildStatus").contains("\"thisConversation\":true")
    }

    @Test
    fun `watcher_set refuses what it cannot keep, saying why`() {
        assertThat(set(toolName = "deployProd")).contains("You have no tool called deployProd")
        // A tool the workspace has, not granted to this agent, is no tool of its.
        agentTools.save(AgentTool(workspaceId = workspaceId, name = "secret", source = "x", typescript = "x"))
        assertThat(set(toolName = "secret")).contains("You have no tool called secret")
        // Asking another agent is not something to poll.
        assertThat(set(toolName = "ask_agent")).contains("You have no tool called ask_agent")

        assertThat(set(condition = "$[?(@.status == ")).contains("not a JSONPath")
        assertThat(set(condition = "status")).contains("starts with \$")
        assertThat(set(condition = "(unclosed", type = "regex")).contains("not a regular expression")
        assertThat(set(type = "xpath")).contains("condition_type must be jsonpath or regex")

        assertThat(set(interval = 14)).contains("at least 15")
        assertThat(set(timeout = settings.maxSeconds() + 1)).contains("at most ${settings.maxSeconds()} seconds")
        assertThat(set(timeout = 0)).contains("greater than zero")
        assertThat(set(interval = 60, timeout = 30)).contains("longer than timeout_seconds")
        assertThat(set(interval = "\"soon\"")).contains("whole number of seconds")
        assertThat(call(WatcherTools.SET, """{"tool":"buildStatus","arguments":"[1]","condition_type":"regex",
            "condition":"done","interval_seconds":15,"timeout_seconds":60}""")).contains("must be a JSON object")

        assertThat(watchers.count()).isZero()
    }

    @Test
    fun `the limits are Admin Settings, read at the moment of asking`() {
        settings.setMinIntervalSeconds(30, "alice")
        settings.setMaxSeconds(120, "alice")
        settings.setMaxPerAgent(2, "alice")

        assertThat(shed().specs().first().description)
            .contains("at least 30").contains("at most 120").contains("at most 2 running")
        assertThat(set(interval = 15, timeout = 60)).contains("at least 30")
        assertThat(set(interval = 30, timeout = 121)).contains("at most 120 seconds")

        set(interval = 30, timeout = 120)
        set(interval = 30, timeout = 120)
        assertThat(set(interval = 30, timeout = 120))
            .contains("You already have 2 watchers running").contains(WatcherTools.FINISH)

        // Per agent: another agent has its own count.
        val other = agents.save(
            Agent(workspaceId = workspaceId, name = "Other", type = AgentType.LLM, tools = mutableListOf("buildStatus")),
        )
        assertThat(call(WatcherTools.SET, """{"tool":"buildStatus","condition_type":"regex","condition":"done",
            "interval_seconds":30,"timeout_seconds":60}""", of = other)).contains("\"watcher\":")

        // Zero switches them off, and a model is not offered what will not run.
        settings.setMaxPerAgent(0, "alice")
        assertThat(shed().specs()).isEmpty()
        assertThat(shed().handles(WatcherTools.SET)).isFalse()
    }

    @Test
    fun `watcher_list lists only the caller's own, running by default and ended ones when asked`() {
        val running = idIn(set())
        val ended = idIn(set(condition = "done", type = "regex"))
        call(WatcherTools.FINISH, """{"watcher":$ended}""")
        val other = agents.save(
            Agent(workspaceId = workspaceId, name = "Other", type = AgentType.LLM, tools = mutableListOf("buildStatus")),
        )
        val theirs = idIn(call(WatcherTools.SET, """{"tool":"buildStatus","condition_type":"regex","condition":"x",
            "interval_seconds":15,"timeout_seconds":60}""", of = other))

        val mine = call(WatcherTools.LIST, "{}")
        assertThat(mine).contains("\"watcher\":$running").doesNotContain("\"watcher\":$ended")
            .doesNotContain("\"watcher\":$theirs")
            .contains("\"state\":\"active\"").contains("\"timeoutSeconds\":600").contains("\"createdAt\":")
            .contains("\"arguments\":\"{}\"")

        val all = call(WatcherTools.LIST, """{"include_finished":true}""")
        assertThat(all).contains("\"watcher\":$running").contains("\"watcher\":$ended")
            .doesNotContain("\"watcher\":$theirs")
            .contains("\"state\":\"finished\"").contains("\"why\":\"Finished by Builder.\"")
        assertThat(call(WatcherTools.LIST, """{"include_finished":"true"}""")).contains("\"watcher\":$ended")
        assertThat(call(WatcherTools.LIST, """{"include_finished":false}""")).doesNotContain("\"watcher\":$ended")

        // And the other agent sees its own and nothing of this one's.
        val theirList = call(WatcherTools.LIST, """{"include_finished":true}""", of = other)
        assertThat(theirList).contains("\"watcher\":$theirs").doesNotContain("\"watcher\":$running")
    }

    @Test
    fun `a watcher is finished by the agent that set it, and by nobody else`() {
        val id = idIn(set())
        val other = agents.save(Agent(workspaceId = workspaceId, name = "Other", type = AgentType.LLM))
        assertThat(call(WatcherTools.FINISH, """{"watcher":$id}""", of = other)).contains("You have no watcher #$id")

        assertThat(call(WatcherTools.FINISH, """{"watcher":"$id"}""")).contains("\"finished\":$id")
        val ended = watchers.findById(id).orElseThrow()
        assertThat(ended.status).isEqualTo(WatcherStatus.FINISHED)
        assertThat(ended.finishedAt).isNotNull()
        assertThat(notes().last()).isEqualTo("Watcher #$id finished by Builder.")
        assertThat(call(WatcherTools.FINISH, """{"watcher":$id}""")).contains("already ended")
        assertThat(call(WatcherTools.LIST, "{}")).contains("\"watchers\":[]")

        // Finishing told the agent nothing: it did it itself.
        assertThat(events.findAll()).isEmpty()
        due(id)
        assertThat(service.tick()).isZero()
    }

    @Test
    fun `the tick calls the tool, waits while it does not match, and fires and wakes the session when it does`() {
        val id = idIn(set())

        // Not due yet: nothing is called.
        assertThat(service.tick()).isZero()

        due(id)
        assertThat(service.tick()).isEqualTo(1)
        val looked = watchers.findById(id).orElseThrow()
        assertThat(looked.status).isEqualTo(WatcherStatus.ACTIVE)
        assertThat(looked.checks).isEqualTo(1)
        assertThat(looked.lastResult).contains("running")
        assertThat(looked.nextCheckAt).isAfter(OffsetDateTime.now().plusSeconds(10))
        assertThat(heard).isEmpty()

        tool.source = returning("done")
        tool.typescript = returning("done")
        agentTools.save(tool)
        due(id)
        assertThat(service.tick()).isEqualTo(1)

        val fired = watchers.findById(id).orElseThrow()
        assertThat(fired.status).isEqualTo(WatcherStatus.FIRED)
        assertThat(fired.checks).isEqualTo(2)
        assertThat(fired.matched).contains("done")
        assertThat(fired.finishedAt).isNotNull()

        // Woken through the inbox, with what matched and the whole result in the message.
        assertThat(heard).containsExactly(session)
        val posted = events.findAll().single()
        assertThat(posted.kind).isEqualTo(SessionEventKind.WATCHER)
        assertThat(posted.body).startsWith("Watcher #$id fired.")
            .contains("on check 2").contains("Your note: tell the team.")
            .contains("What matched:").contains("\"build\":41").contains(WatcherTools.SET)
        assertThat(inbox.take(session).single()).isEqualTo(posted.body)

        assertThat(notes().last()).startsWith("Watcher #$id fired on check 2")

        // Once: it has ended.
        due(id)
        assertThat(service.tick()).isZero()
    }

    @Test
    fun `a regular expression is found anywhere in the result`() {
        tool.source = returning("Deployed to production")
        tool.typescript = tool.source
        agentTools.save(tool)
        val id = idIn(set(condition = """(?i)\bdeployed\b""", type = "regex"))
        due(id)
        service.tick()
        val fired = watchers.findById(id).orElseThrow()
        assertThat(fired.status).isEqualTo(WatcherStatus.FIRED)
        assertThat(fired.matched).isEqualTo("Deployed")
    }

    @Test
    fun `a watcher that never matches times out, and the agent is told`() {
        val id = idIn(set(interval = 15, timeout = 60))
        val watcher = watchers.findById(id).orElseThrow()
        // Its time is up: the last check is made, and it does not match.
        watchers.save(
            Watcher(
                id = watcher.id, workspaceId = watcher.workspaceId, sessionId = watcher.sessionId,
                agentId = watcher.agentId, agentName = watcher.agentName, tool = watcher.tool,
                arguments = watcher.arguments, conditionKind = watcher.conditionKind, condition = watcher.condition,
                intervalSeconds = watcher.intervalSeconds, timeoutSeconds = watcher.timeoutSeconds, note = watcher.note,
                createdAt = watcher.createdAt.minusSeconds(120), expiresAt = OffsetDateTime.now().minusSeconds(1),
                nextCheckAt = OffsetDateTime.now().minusSeconds(1),
            ),
        )

        assertThat(service.tick()).isEqualTo(1)
        val ended = watchers.findById(id).orElseThrow()
        assertThat(ended.status).isEqualTo(WatcherStatus.TIMED_OUT)
        assertThat(ended.checks).isEqualTo(1)
        assertThat(heard).containsExactly(session)
        assertThat(events.findAll().single().body).startsWith("Watcher #$id timed out")
            .contains("never matched").contains("running")
        assertThat(notes().last()).startsWith("Watcher #$id timed out after 60s and 1 checks")
    }

    @Test
    fun `a watcher whose tool was taken from its agent ends rather than calling it`() {
        val id = idIn(set())
        agent.tools = mutableListOf()
        agents.save(agent)
        due(id)

        service.tick()
        val ended = watchers.findById(id).orElseThrow()
        assertThat(ended.status).isEqualTo(WatcherStatus.FAILED)
        assertThat(ended.checks).isZero()
        assertThat(ended.outcome).isEqualTo("buildStatus is no longer one of Builder's tools.")
        assertThat(events.findAll().single().body).contains("has ended without matching")
    }

    @Test
    fun `a stop from the page tells the agent, in its session`() {
        val id = idIn(set())
        service.stop(id, "alice")
        val stopped = watchers.findById(id).orElseThrow()
        assertThat(stopped.status).isEqualTo(WatcherStatus.STOPPED)
        assertThat(stopped.finishedBy).isEqualTo("alice")
        assertThat(heard).containsExactly(session)
        assertThat(events.findAll().single().body).contains("was stopped by alice")
        assertThat(notes().last()).isEqualTo("Watcher #$id stopped by alice from the Watchers page.")
    }
}
