package io.mszymanski.orknux.server.device

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.browser.BrowserTools
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ChatSession
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.desktop.DesktopTools
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.SessionScratch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The person's browser and Windows desktop, driven from an agent's tool call
 * through the door their Orknux client comes in by.
 *
 * Over the real port, with a real token, because what is being proved is the
 * round trip: the agent's call is queued for its person, a long poll carrying
 * that person's token takes it, and the answer posted back is what the agent
 * reads. A fake extension stands in for Chrome - what the extension does with a
 * command is the extension's to test; this is the server's half.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceToolsTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val tools: BrowserTools,
    @Autowired val desktop: DesktopTools,
    @Autowired val shell: io.mszymanski.orknux.server.shell.ShellTools,
    @Autowired val links: DeviceLinks,
    @Autowired val claudeProvider: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.claude.ClaudeCodeChats>,
    @Autowired val scratch: SessionScratch,
    @Autowired val tuning: Tuning,
    @Autowired val briefing: AgentBriefing,
    @Autowired val mapper: ObjectMapper,
    @Autowired val mcpSessions: io.mszymanski.orknux.server.mcp.McpClientSessionRepository,
    @Autowired val mcpRecorder: io.mszymanski.orknux.server.mcp.McpSessions,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var workspaceId = 0L
    private lateinit var token: String
    private lateinit var user: String
    private val made = mutableListOf<Long>()
    private val running = AtomicBoolean(false)
    private val extensions = mutableListOf<Thread>()
    @Volatile private var lastAnswer: String? = null
    private val seen = CopyOnWriteArrayList<Map<String, Any?>>()

    @BeforeEach
    fun setUp() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "browser-" + System.nanoTime())).id)
        user = "browsing-" + System.nanoTime()
        val person = users.save(AppUser(username = user, displayName = "Browsing", type = UserType.INTERNAL))
        token = internal.mint(person, "browser-" + System.nanoTime()).second
    }

    @AfterEach
    fun tearDown() {
        running.set(false)
        extensions.forEach { it.join(10_000) }
        made.forEach { chats.deleteById(it) }
    }

    private fun agent(vararg tools: String) =
        Agent(workspaceId = workspaceId, name = "Browser", type = AgentType.LLM, tools = tools.toMutableList())

    /** A conversation that is [owner]'s chat. */
    private fun chatOf(owner: String, openedIn: String? = null): Long {
        val session = recorder.open(workspaceId, "chat", UUID.randomUUID().toString())
        val chat = chats.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = UUID.randomUUID().toString(),
                title = "Browsing",
                userId = owner,
                llmSessionId = session,
                openedIn = openedIn,
            ),
        )
        made += requireNotNull(chat.id)
        return session
    }

    /**
     * Polls as the extension does and answers each command with [answer];
     * returns once the server counts the browser as connected.
     */
    private fun connect(kind: DeviceKind = DeviceKind.BROWSER, name: String = "Test", instance: String? = null, answer: (Map<String, Any?>) -> Map<String, Any?>) {
        running.set(true)
        extensions += Thread {
            while (running.get()) {
                val asked = client.get().uri("/api/${kind.path}/next?wait=1&client=$name&version=1.0.0" + (instance?.let { "&instance=$it" } ?: ""))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve().toEntity(String::class.java)
                lastAnswer = "${asked.statusCode} ${asked.body?.take(200)}"
                if (asked.statusCode != HttpStatus.OK) continue
                @Suppress("UNCHECKED_CAST")
                val command = mapper.readValue(asked.body, Map::class.java) as Map<String, Any?>
                seen += command + ("kind" to kind) + ("instance" to instance)
                client.post().uri("/api/${kind.path}/answers/${command["id"]}")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(answer(command)))
                    .retrieve().toBodilessEntity()
            }
        }.apply { isDaemon = true; start() }
        val until = System.currentTimeMillis() + 5_000
        while (links.presence(user, kind) == null) {
            check(System.currentTimeMillis() < until) { "The client never connected; the door last answered $lastAnswer" }
            Thread.sleep(20)
        }
    }

    private fun run(session: Long?, name: String, arguments: String = "{}"): String {
        val family: DeviceTools = when {
            name.startsWith("desktop_") -> desktop
            name.startsWith("terminal_") -> shell
            else -> tools
        }
        val shed = requireNotNull(family.shed(agent(*family.names.toTypedArray()), session))
        return shed.run(ToolCall("call-1", name, arguments))
    }

    @Test
    fun `an agent's call reaches the person's browser and its answer comes back`() {
        connect { mapOf("result" to mapOf("text" to "[e1] button \"Sign in\"", "tab" to 7)) }

        val answered = mapper.readTree(run(chatOf(user), BrowserTools.SNAPSHOT, """{"find":"sign"}"""))

        assertThat(answered.path("text").stringValue()).isEqualTo("[e1] button \"Sign in\"")
        val command = seen.single()
        assertThat(command["action"]).isEqualTo("snapshot")
        @Suppress("UNCHECKED_CAST")
        val arguments = command["arguments"] as Map<String, Any?>
        assertThat(arguments["find"]).isEqualTo("sign")
        // The installation's ceiling travels with the command, so the extension cuts the page where the server says.
        assertThat(arguments["maxCharacters"]).isEqualTo(tuning[Tunable.DEVICE_SNAPSHOT_CHARACTERS])
    }

    @Test
    fun `a refusal from the browser is the model's to read`() {
        connect { mapOf("error" to "No element e9 on this page; take a fresh snapshot.") }

        val answered = mapper.readTree(run(chatOf(user), BrowserTools.CLICK, """{"ref":"e9"}"""))

        assertThat(answered.path("error").stringValue()).contains("No element e9")
    }

    @Test
    fun `a screenshot is kept under a key rather than handed over as bytes`() {
        connect { mapOf("result" to mapOf("data" to "iVBORw0KGgo=", "mimeType" to "image/png", "title" to "Home")) }
        val session = chatOf(user)

        val answered = mapper.readTree(run(session, BrowserTools.SCREENSHOT))

        val key = answered.path("contentKey").stringValue()
        assertThat(key).startsWith("picture.")
        assertThat(answered.toString()).doesNotContain("iVBORw0KGgo")
        assertThat(scratch.get(session, key)).contains("iVBORw0KGgo=")
        assertThat(scratch.kindOf(session, key)?.contentType).isEqualTo("image/png")
    }

    @Test
    fun `with no browser connected the tool says what to do`() {
        val answered = mapper.readTree(run(chatOf(user), BrowserTools.OPEN, """{"url":"https://example.invalid"}"""))

        assertThat(answered.path("error").stringValue()).contains("side panel")
    }

    @Test
    fun `another person's browser is not this conversation's`() {
        connect { mapOf("result" to mapOf("ok" to true)) }

        val answered = mapper.readTree(run(chatOf("somebody-else"), BrowserTools.TABS))

        assertThat(answered.path("error").stringValue()).contains("No browser is connected")
        assertThat(seen).isEmpty()
    }

    @Test
    fun `a conversation that is nobody's reaches no browser`() {
        connect { mapOf("result" to mapOf("ok" to true)) }
        val step = recorder.open(workspaceId, "step", UUID.randomUUID().toString())

        val answered = mapper.readTree(run(step, BrowserTools.TABS))

        assertThat(answered.path("error").stringValue()).contains("does not belong to a person")
        assertThat(seen).isEmpty()
    }

    @Test
    fun `the tools are off until an agent is given them by name`() {
        assertThat(tools.shed(agent(), 1L)).isNull()
        BrowserTools.NAMES.forEach {
            assertThat(BuiltInTools.reaches(it)).describedAs(it).isTrue()
            assertThat(BuiltInTools.granted(agent(), it)).describedAs(it).isFalse()
        }
        assertThat(tools.shed(agent(BrowserTools.OPEN), 1L)?.specs()?.map { it.name })
            .containsExactlyElementsOf(BrowserTools.NAMES)
    }

    @Test
    fun `an agent without the browser is told to suggest the extension, and one with it is not`() {
        assertThat(briefing.grants(agent())).contains(AgentBriefing.SUGGEST_BROWSER)
        assertThat(briefing.grants(agent(*BrowserTools.NAMES.toTypedArray()))).doesNotContain(AgentBriefing.SUGGEST_BROWSER)
    }

    @Test
    fun `the door needs somebody signed in, and an answer nobody waits for is gone`() {
        val anonymous = client.get().uri("/api/browser/next?wait=0").retrieve().toBodilessEntity()
        assertThat(anonymous.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val hello = client.get().uri("/api/browser/hello")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toEntity(String::class.java)
        assertThat(mapper.readTree(hello.body).path("user").stringValue()).isEqualTo(user)

        val late = client.post().uri("/api/browser/answers/nobody-waits")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"result":{}}""")
            .retrieve().toBodilessEntity()
        assertThat(late.statusCode).isEqualTo(HttpStatus.GONE)
    }

    @Test
    fun `a desktop call reaches the person's computer, never their browser`() {
        connect(DeviceKind.BROWSER) { mapOf("result" to mapOf("from" to "browser")) }
        connect(DeviceKind.DESKTOP) { mapOf("result" to mapOf("windows" to listOf(mapOf("window" to 42, "title" to "Notepad")))) }

        val answered = mapper.readTree(run(chatOf(user), DesktopTools.WINDOWS))

        assertThat(answered.path("windows").path(0).path("title").stringValue()).isEqualTo("Notepad")
        assertThat(seen.single()["action"]).isEqualTo("windows")
        assertThat(seen.single()["kind"]).isEqualTo(DeviceKind.DESKTOP)
    }

    @Test
    fun `the window list says which system the computer runs, and the tools are told it could be either`() {
        connect(DeviceKind.DESKTOP, name = "Linux") { command ->
            mapOf("result" to if (command["action"] == "windows") mapOf("windows" to emptyList<Any>()) else mapOf("window" to 7))
        }

        val listed = mapper.readTree(run(chatOf(user), DesktopTools.WINDOWS))
        assertThat(listed.path("system").stringValue()).isEqualTo("Linux")
        val opened = mapper.readTree(run(chatOf(user), DesktopTools.OPEN, """{"target":"gedit"}"""))
        assertThat(opened.path("computer").stringValue()).describedAs("every answer says which computer it came from").isEqualTo("Linux")

        val said = DesktopTools.BRIEFING + DesktopTools.SPECS.joinToString(" ") { it.description }
        assertThat(said).contains("Orknux for Linux").contains("AT-SPI").contains("gedit")
        assertThat(said).doesNotContain("Windows computer").doesNotContain("Windows desktop")
    }

    @Test
    fun `an argument a tool does not take is refused in words, before the device is asked`() {
        connect(DeviceKind.DESKTOP) { mapOf("result" to mapOf("alreadyRunning" to true)) }

        // What an agent sent: new nested inside arguments, which is a string of command-line arguments.
        val nested = mapper.readTree(run(chatOf(user), DesktopTools.OPEN, """{"target":"musigium","arguments":{"new":true}}"""))
        assertThat(nested.path("error").stringValue()).contains("\"arguments\" should be a string").contains("It takes: target, arguments, new")

        val unknown = mapper.readTree(run(chatOf(user), DesktopTools.OPEN, """{"target":"musigium","fresh":true}"""))
        assertThat(unknown.path("error").stringValue()).contains("does not take \"fresh\"")

        val missing = mapper.readTree(run(chatOf(user), DesktopTools.OPEN, """{"new":true}"""))
        assertThat(missing.path("error").stringValue()).contains("\"target\" is required")

        assertThat(seen).describedAs("nothing half understood reaches the computer").isEmpty()

        val right = mapper.readTree(run(chatOf(user), DesktopTools.OPEN, """{"target":"musigium","new":true}"""))
        assertThat(right.path("alreadyRunning").booleanValue()).isTrue()
        assertThat(seen.single()["arguments"].toString()).contains("new")
    }

    @Test
    fun `a chat opened in Orknux Shell lends the agent the person's computer, and no other chat does`() {
        val plain = agent()
        assertThat(shell.shed(plain, chatOf(user))).describedAs("a chat opened in the browser").isNull()
        assertThat(shell.shed(plain, null)).describedAs("a conversation that is nobody's chat").isNull()
        val shed = requireNotNull(shell.shed(plain, chatOf(user, io.mszymanski.orknux.server.chat.OPENED_IN_SHELL)))
        assertThat(shed.specs().map { it.name }).containsExactly(io.mszymanski.orknux.server.shell.ShellTools.RUN)
        assertThat(shed.briefing()).contains("dangerous")
    }

    @Test
    fun `a command reaches the person's terminal, never their browser or desktop, and says whether it is dangerous`() {
        connect(DeviceKind.BROWSER) { mapOf("result" to mapOf("from" to "browser")) }
        connect(DeviceKind.SHELL) { command ->
            @Suppress("UNCHECKED_CAST")
            val args = command["arguments"] as Map<String, Any?>
            mapOf("result" to mapOf("exitCode" to 0, "stdout" to "ran ${args["command"]}", "stderr" to "", "approval" to "auto"))
        }
        val chat = chatOf(user, io.mszymanski.orknux.server.chat.OPENED_IN_SHELL)

        val answered = mapper.readTree(run(chat, io.mszymanski.orknux.server.shell.ShellTools.RUN, """{"command":"uname -a","dangerous":false}"""))

        assertThat(answered.path("stdout").stringValue()).isEqualTo("ran uname -a")
        assertThat(seen.single()["kind"]).isEqualTo(DeviceKind.SHELL)
        assertThat(seen.single()["action"]).isEqualTo("run")

        // Not saying whether it is dangerous is refused before the terminal is asked.
        val unsaid = mapper.readTree(run(chat, io.mszymanski.orknux.server.shell.ShellTools.RUN, """{"command":"rm -rf build"}"""))
        assertThat(unsaid.path("error").stringValue()).contains("\"dangerous\" is required")
        assertThat(seen).hasSize(1)
    }

    @Test
    fun `two of a person's Shells are two computers, and a chat's commands go to the one writing in it`() {
        connect(DeviceKind.SHELL, name = "Shell", instance = "laptop") { mapOf("result" to mapOf("exitCode" to 0, "stdout" to "laptop")) }
        connect(DeviceKind.SHELL, name = "Shell", instance = "desktop") { mapOf("result" to mapOf("exitCode" to 0, "stdout" to "desktop")) }
        val onLaptop = chatOf(user, io.mszymanski.orknux.server.chat.OPENED_IN_SHELL)
        val onDesktop = chatOf(user, io.mszymanski.orknux.server.chat.OPENED_IN_SHELL)
        val chatIdOf = { session: Long -> requireNotNull(chats.findFirstByLlmSessionId(session)).id.toString() }
        links.claim(user, DeviceKind.SHELL, chatIdOf(onLaptop), "laptop")
        links.claim(user, DeviceKind.SHELL, chatIdOf(onDesktop), "desktop")

        repeat(6) {
            val laptop = mapper.readTree(run(onLaptop, io.mszymanski.orknux.server.shell.ShellTools.RUN, """{"command":"hostname","dangerous":false}"""))
            assertThat(laptop.path("stdout").stringValue()).describedAs("every time, not whichever asked first").isEqualTo("laptop")
            val desktop = mapper.readTree(run(onDesktop, io.mszymanski.orknux.server.shell.ShellTools.RUN, """{"command":"hostname","dangerous":false}"""))
            assertThat(desktop.path("stdout").stringValue()).isEqualTo("desktop")
        }
        assertThat(seen.map { it["instance"] }).containsExactly(*Array(6) { listOf("laptop", "desktop") }.flatMap { it }.toTypedArray())
    }

    @Test
    fun `a chat no Shell claimed goes to an older Shell that names no instance, never to a newer one`() {
        connect(DeviceKind.SHELL, name = "Shell") { mapOf("result" to mapOf("exitCode" to 0, "stdout" to "older")) }
        connect(DeviceKind.SHELL, name = "Shell", instance = "newer") { mapOf("result" to mapOf("exitCode" to 0, "stdout" to "newer")) }
        val unclaimed = chatOf(user, io.mszymanski.orknux.server.chat.OPENED_IN_SHELL)
        repeat(4) {
            val answered = mapper.readTree(run(unclaimed, io.mszymanski.orknux.server.shell.ShellTools.RUN, """{"command":"hostname","dangerous":false}"""))
            assertThat(answered.path("stdout").stringValue()).isEqualTo("older")
        }
    }

    private fun twoComputers() {
        connect(DeviceKind.DESKTOP, name = "Windows") { command ->
            mapOf("result" to if (command["action"] == "windows") mapOf("windows" to listOf(mapOf("window" to 1, "title" to "Notepad"))) else mapOf("from" to "windows"))
        }
        Thread.sleep(50)
        connect(DeviceKind.DESKTOP, name = "Linux") { command ->
            mapOf("result" to if (command["action"] == "windows") mapOf("windows" to listOf(mapOf("window" to 1, "title" to "gedit"))) else mapOf("from" to "linux"))
        }
        val until = System.currentTimeMillis() + 5_000
        while (links.computers(user, DeviceKind.DESKTOP).size < 2) {
            check(System.currentTimeMillis() < until) { "the second computer never connected" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `with two computers connected the window list is each one's, under its name`() {
        twoComputers()
        val listed = mapper.readTree(run(chatOf(user), DesktopTools.WINDOWS))
        val computers = listed.path("computers").values().associate { it.path("computer").stringValue() to it.path("windows").path(0).path("title").stringValue() }
        assertThat(computers).isEqualTo(mapOf("Linux" to "gedit", "Windows" to "Notepad"))
        assertThat(listed.path("computers").values().map { it.path("system").stringValue() }).containsExactly("Linux", "Windows")
        assertThat(listed.path("note").stringValue()).contains("name the computer")
    }

    @Test
    fun `a call names its computer, and the conversation keeps to it until it names another`() {
        twoComputers()
        val chat = chatOf(user)
        val first = mapper.readTree(run(chat, DesktopTools.SNAPSHOT, """{"computer":"Windows"}"""))
        assertThat(first.path("from").stringValue()).isEqualTo("windows")
        assertThat(first.path("computer").stringValue()).isEqualTo("Windows")
        repeat(4) {
            val again = mapper.readTree(run(chat, DesktopTools.SNAPSHOT))
            assertThat(again.path("from").stringValue()).describedAs("the one it used last, not the newest").isEqualTo("windows")
        }
        val bySystem = mapper.readTree(run(chat, DesktopTools.SNAPSHOT, """{"computer":"linux"}"""))
        assertThat(bySystem.path("from").stringValue()).isEqualTo("linux")
        val nowhere = mapper.readTree(run(chat, DesktopTools.SNAPSHOT, """{"computer":"Mac"}"""))
        assertThat(nowhere.path("error").stringValue()).contains("No computer").contains("\"Linux\"").contains("\"Windows\"")
        assertThat(seen.none { (it["arguments"] as? Map<*, *>)?.containsKey("computer") == true })
            .describedAs("the computer is the server's to route by, not passed to the client").isTrue()
    }

    @Test
    fun `a chat whose Shell has gone is refused, not run on another of the person's computers`() {
        connect(DeviceKind.SHELL, name = "Shell", instance = "desktop") { mapOf("result" to mapOf("exitCode" to 0, "stdout" to "desktop")) }
        val chat = chatOf(user, io.mszymanski.orknux.server.chat.OPENED_IN_SHELL)
        links.claim(user, DeviceKind.SHELL, requireNotNull(chats.findFirstByLlmSessionId(chat)).id.toString(), "laptop-that-closed")

        val answered = mapper.readTree(run(chat, io.mszymanski.orknux.server.shell.ShellTools.RUN, """{"command":"rm -rf build","dangerous":false}"""))

        assertThat(answered.path("error").stringValue()).contains("is not connected now, so nothing was run")
        assertThat(seen).describedAs("the other computer was not asked").isEmpty()
    }

    @Test
    fun `with no computer connected the desktop tool says what to start`() {
        connect(DeviceKind.BROWSER) { mapOf("result" to mapOf("ok" to true)) }

        val answered = mapper.readTree(run(chatOf(user), DesktopTools.SNAPSHOT))

        assertThat(answered.path("error").stringValue()).contains("No computer is connected").contains("Orknux for Windows").contains("Orknux for Linux")
        assertThat(seen).isEmpty()
    }

    @Test
    fun `a desktop screenshot is kept under a key, with what the client said about it`() {
        connect(DeviceKind.DESKTOP) { mapOf("result" to mapOf("data" to "iVBORw0KGgo=", "mimeType" to "image/png", "title" to "Notepad", "window" to 42)) }
        val session = chatOf(user)

        val answered = mapper.readTree(run(session, DesktopTools.SCREENSHOT, """{"screen":true}"""))

        val key = answered.path("contentKey").stringValue()
        assertThat(key).startsWith("picture.desktop")
        assertThat(answered.path("title").stringValue()).isEqualTo("Notepad")
        assertThat(answered.toString()).doesNotContain("iVBORw0KGgo")
        assertThat(scratch.get(session, key)).contains("iVBORw0KGgo=")
    }

    @Test
    fun `the desktop tools are off until an agent is given them by name`() {
        assertThat(desktop.shed(agent(BrowserTools.OPEN), 1L)).isNull()
        DesktopTools.NAMES.forEach {
            assertThat(BuiltInTools.reaches(it)).describedAs(it).isTrue()
            assertThat(BuiltInTools.granted(agent(), it)).describedAs(it).isFalse()
        }
        assertThat(desktop.shed(agent(DesktopTools.CLICK), 1L)?.specs()?.map { it.name }).containsExactlyElementsOf(DesktopTools.NAMES)
    }

    @Test
    fun `the desktop door is its own, and an unknown kind is not a door`() {
        val hello = client.get().uri("/api/desktop/hello")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toEntity(String::class.java)
        assertThat(mapper.readTree(hello.body).path("user").stringValue()).isEqualTo(user)

        val toaster = client.get().uri("/api/toaster/next?wait=0")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token").retrieve().toBodilessEntity()
        assertThat(toaster.statusCode.is2xxSuccessful).isFalse()
    }

    @Test
    fun `the person sees which of their clients are connected, and where to get the rest`() {
        connect(DeviceKind.DESKTOP) { mapOf("result" to mapOf("ok" to true)) }

        val mine = myDevices()

        assertThat(mine.desktop.connected).isTrue()
        assertThat(mine.desktop.client).isEqualTo("Test")
        assertThat(mine.browser.connected).isFalse()
        assertThat(mine.browser.installUrl).startsWith("https://")
    }

    @Test
    fun `two desktops connected at once are two, each by its own system`() {
        connect(DeviceKind.DESKTOP, name = "Windows", instance = "Windows on PC") { mapOf("result" to mapOf("ok" to true)) }
        connect(DeviceKind.DESKTOP, name = "Linux", instance = "Linux on studio") { mapOf("result" to mapOf("ok" to true)) }
        val deadline = System.currentTimeMillis() + 5000
        while (myDevices().desktops.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)

        assertThat(myDevices().desktops.map { it.client to it.name })
            .containsExactlyInAnyOrder("Windows" to "Windows on PC", "Linux" to "Linux on studio")
    }

    private fun myDevices() = DeviceStatusAPI(links, claudeProvider, mcpSessions, mcpRecorder).let { api ->
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null, emptyList())
        try { api.myDevices() } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
    }

    @Test
    fun `Orknux MCP is lit while an MCP client of the person's is connected, and not once it has ended`() {
        assertThat(myDevices().mcp).isFalse()

        val session = mcpSessions.save(
            io.mszymanski.orknux.server.mcp.McpClientSession(workspaceId = workspaceId, sessionKey = "k-" + System.nanoTime(), username = user, ipAddress = "127.0.0.1"),
        )
        assertThat(myDevices().mcp).isTrue()

        session.endedAt = java.time.OffsetDateTime.now()
        mcpSessions.save(session)
        assertThat(myDevices().mcp).isFalse()
    }

    @Test
    fun `the Chrome extension's answers are let in by its own origin, and another site's are not`() {
        // A real extension's POST carries chrome-extension://<id>; the CORS rule refused it as a foreign site.
        val fromExtension = client.post().uri("/api/browser/answers/nobody-waits")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(HttpHeaders.ORIGIN, "chrome-extension://afephcfknfmhnbknhggaahcmkicoknio")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"result":{}}""")
            .retrieve().toBodilessEntity()
        assertThat(fromExtension.statusCode).isEqualTo(HttpStatus.GONE)

        val fromElsewhere = client.post().uri("/api/browser/answers/nobody-waits")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(HttpHeaders.ORIGIN, "https://somewhere.invalid")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"result":{}}""")
            .retrieve().toBodilessEntity()
        assertThat(fromElsewhere.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
