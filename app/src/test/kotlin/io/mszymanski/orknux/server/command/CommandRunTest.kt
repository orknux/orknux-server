package io.mszymanski.orknux.server.command

import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * The Run button on a chat answer's shell blocks, through the API the chat
 * calls: a command run on this machine for real, its output and exit code read
 * back, the timeout taken from its Tunable, the switch and the role both
 * refusing, and every run audited with its credentials out.
 *
 * Unelevated only - nothing in a build can answer a UAC prompt or a polkit
 * dialog; `CommandLinesTest` reads the command lines built for those instead.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class CommandRunTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val tunables: Tunables,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    /** The shell every machine a build runs on has: cmd on Windows, sh elsewhere. */
    private val windows = HostSystem.current() == HostSystem.WINDOWS
    private val language = if (windows) "cmd" else "sh"

    @BeforeEach
    fun on() {
        audit.deleteAll()
        // Stored rather than pressed: the person in some tests may not press it.
        settings.save(
            io.mszymanski.orknux.server.attachment.InstallationSetting(
                name = SettingNames.CHAT_RUN_COMMANDS,
                value = "true",
                lastModifiedBy = "alice",
            ),
        )
    }

    @AfterEach
    fun back() {
        settings.deleteById(SettingNames.CHAT_RUN_COMMANDS)
        if (settings.existsById(Tunable.COMMAND_TIMEOUT_SECONDS.key)) tunables.set(Tunable.COMMAND_TIMEOUT_SECONDS, null, "test")
    }

    private fun switch(enabled: Boolean) {
        graphQlTester.document("mutation { setCommandRuns(enabled: $enabled) { enabled configured } }").execute()
            .path("setCommandRuns.enabled").entity(Boolean::class.java).isEqualTo(enabled)
            // Off in the file: a server is where it is off until somebody turns it on.
            .path("setCommandRuns.configured").entity(Boolean::class.java).isEqualTo(false)
    }

    private fun run(command: String): String =
        graphQlTester.document("mutation(\$c: String!, \$l: String!) { runCommand(command: \$c, language: \$l) { id state } }")
            .variable("c", command).variable("l", language)
            .execute()
            .path("runCommand.id").entity(String::class.java).get()

    /** Reads the run until it ends, the way the page does, and answers the whole of what it printed. */
    private fun ended(id: String, within: Long = 30_000): Map<String, Any?> {
        val until = System.currentTimeMillis() + within
        val output = StringBuilder()
        var next = 0
        while (true) {
            val read = graphQlTester.document("query { commandRun(id: \"$id\", from: $next) { state output next exitCode message truncated } }")
                .execute().path("commandRun").entity(Map::class.java).get()
            output.append(read["output"] as String)
            next = read["next"] as Int
            if (read["state"] != "RUNNING") return read.entries.associate { it.key.toString() to it.value } + ("all" to output.toString())
            check(System.currentTimeMillis() < until) { "Run $id still running after ${within}ms" }
            Thread.sleep(100)
        }
    }

    @Test
    fun `a block runs here and what it printed comes back with its exit code`() {
        graphQlTester.document("query { commandRunsOffered { languages } }").execute()
            .path("commandRunsOffered.languages").entityList(String::class.java).contains(language)

        val done = ended(run("echo hello-orknux"))
        assertThat(done["state"]).isEqualTo("EXITED")
        assertThat(done["exitCode"]).isEqualTo(0)
        assertThat(done["all"] as String).contains("hello-orknux")
    }

    @Test
    fun `a command's own exit code is the one reported`() {
        val done = ended(run(if (windows) "exit /b 3" else "exit 3"))
        assertThat(done["state"]).isEqualTo("EXITED")
        assertThat(done["exitCode"]).isEqualTo(3)
    }

    /** Wherever PowerShell is offered - always on Windows, where pwsh is on the PATH elsewhere. */
    @Test
    fun `a PowerShell block runs as a script and answers its program's exit code`() {
        val offered = graphQlTester.document("query { commandRunsOffered { languages } }").execute()
            .path("commandRunsOffered.languages").entityList(String::class.java).get()
        org.junit.jupiter.api.Assumptions.assumeTrue("powershell" in offered, "PowerShell is not on this machine")

        // A program's exit code, not the script's own `exit`: -File would answer 0 for it without the epilogue.
        val failing = if (windows) "cmd /c exit 4" else "sh -c 'exit 4'"
        val id = graphQlTester.document("mutation(\$c: String!) { runCommand(command: \$c, language: \"powershell\") { id } }")
            .variable("c", "Write-Output \"ps-\$(1+1)\"\n$failing")
            .execute().path("runCommand.id").entity(String::class.java).get()
        val done = ended(id)
        assertThat(done["state"]).isEqualTo("EXITED")
        assertThat(done["all"] as String).contains("ps-2")
        assertThat(done["exitCode"]).isEqualTo(4)
    }

    @Test
    fun `a run past the timeout from its Tunable is stopped and says so`() {
        tunables.set(Tunable.COMMAND_TIMEOUT_SECONDS, 1, "test")
        val started = System.currentTimeMillis()
        val done = ended(run(if (windows) "ping -n 30 127.0.0.1 >nul" else "sleep 30"), within = 20_000)
        assertThat(done["state"]).isEqualTo("TIMED_OUT")
        assertThat(System.currentTimeMillis() - started).isLessThan(20_000)
    }

    @Test
    fun `stop ends a run`() {
        val id = run(if (windows) "ping -n 30 127.0.0.1 >nul" else "sleep 30")
        graphQlTester.document("mutation { stopCommandRun(id: \"$id\") { id } }").execute()
            .path("stopCommandRun.id").entity(String::class.java).isEqualTo(id)
        assertThat(ended(id, within = 15_000)["state"]).isEqualTo("STOPPED")
    }

    @Test
    fun `with the switch off nothing is offered and a run is refused`() {
        switch(false)
        graphQlTester.document("query { commandRunsOffered { languages elevation } }").execute()
            .path("commandRunsOffered.languages").entityList(String::class.java).hasSize(0)
            .path("commandRunsOffered.elevation").entity(Boolean::class.java).isEqualTo(false)
        graphQlTester.document("mutation { runCommand(command: \"echo no\", language: \"$language\") { id } }").execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.extensions["code"]).isEqualTo("CommandRunsOff") })
            }
    }

    @Test
    fun `a block in a language this machine cannot run is refused by name`() {
        val other = if (windows) "bash" else "cmd"
        graphQlTester.document("mutation { runCommand(command: \"echo no\", language: \"$other\") { id } }").execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.extensions["code"]).isEqualTo("CommandLanguageUnrunnable")
                })
            }
    }

    @Test
    fun `every run is audited as it starts and as it ends, with its credentials out`() {
        ended(run("echo --password=hunter2"))
        val rows = audit.findAll().filter { it.category == WorkspaceAuditCategory.SHELL }
        val messages = rows.map { it.message }
        assertThat(messages).anyMatch { it.startsWith("Command run from the chat: echo --password=") }
        assertThat(messages).anyMatch { it.startsWith("Command from the chat exited with 0: echo --password=") }
        assertThat(messages).noneMatch { it.contains("hunter2") }
        // The end is written on the run's own thread, with nobody signed in on it, and still names who ran it.
        assertThat(rows.map { it.userId }.toSet()).containsOnly("alice")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["BACKEND"])
    fun `somebody who is not an administrator is offered nothing and refused`() {
        graphQlTester.document("query { commandRunsOffered { languages } }").execute()
            .path("commandRunsOffered.languages").entityList(String::class.java).hasSize(0)
        graphQlTester.document("mutation { runCommand(command: \"echo no\", language: \"$language\") { id } }").execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({ assertThat(it.extensions["code"]).isEqualTo("AdminRequired") })
            }
        graphQlTester.document("mutation { setCommandRuns(enabled: false) { enabled } }").execute()
            .errors().satisfy { assertThat(it).isNotEmpty() }
    }
}
