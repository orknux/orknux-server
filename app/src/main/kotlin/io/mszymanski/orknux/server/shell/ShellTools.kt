package io.mszymanski.orknux.server.shell

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.OPENED_IN_SHELL
import io.mszymanski.orknux.server.device.DeviceKind
import io.mszymanski.orknux.server.device.DeviceTools
import io.mszymanski.orknux.server.device.DeviceTools.Companion.number
import io.mszymanski.orknux.server.device.DeviceTools.Companion.text
import io.mszymanski.orknux.server.device.DeviceToolsParts
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.tuning.Tunable
import org.springframework.stereotype.Service

/**
 * The person's own computer, as one tool: run an operating-system command
 * where Orknux Shell is open, and read what it printed.
 *
 * Offered by where the chat was opened rather than by a grant on the agent:
 * a chat opened in Orknux Shell lends the person's machine to whichever agent
 * they pick there, and no other chat - in the browser, a workflow, a task -
 * ever gets it. Opening the chat in the terminal is the person saying so.
 *
 * What runs is the Shell's to decide, not the server's: it runs a command at
 * once unless it is sudo-like, matches the person's "ask" rules, or the model
 * itself calls it dangerous - then the person approves it first, or declines
 * and the model is told so. The model is made to say which, every time.
 */
@Service
class ShellTools(parts: DeviceToolsParts) : DeviceTools(parts, DeviceKind.SHELL) {

    override val specs: List<ToolSpec> get() = SPECS
    override val briefing: String get() = BRIEFING
    // Neither a page read as text nor a picture: what a command prints is capped by the Shell.
    override val snapshotTool: String get() = ""
    override val screenshotTool: String get() = ""
    override val prefix: String get() = "terminal_"

    override fun offered(agent: Agent, session: Long?): Boolean =
        session != null && parts.chats.findFirstByLlmSessionId(session)?.openedIn == OPENED_IN_SHELL

    override fun waitSeconds(): Long = parts.tuning[Tunable.SHELL_COMMAND_SECONDS].toLong()

    companion object {
        const val RUN = "terminal_run"

        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                name = RUN,
                description = "Runs an operating-system command on the person's own computer, where they have Orknux Shell " +
                    "open, and answers its exit code and what it printed (the end of it, when long). It runs as the " +
                    "person, in their shell - bash on Linux and macOS, PowerShell on Windows - in their files. Commands " +
                    "that are sudo-like, that match the person's own rules, or that you mark dangerous wait for them to " +
                    "approve; a declined one did not run. Set dangerous honestly: true for anything that deletes, " +
                    "overwrites, installs, changes system settings, kills processes, pushes, sends, publishes or " +
                    "spends - anything hard to undo or reaching outside this machine.",
                parameters = listOf(
                    text("command", "The command line to run, as the person would type it.", required = true),
                    ToolParameterSpec(
                        "dangerous",
                        "Whether running it could do harm that is hard to undo, or reach outside this computer. True asks the person first.",
                        true,
                        mapOf("type" to "boolean"),
                    ),
                    text("reason", "One short sentence on why, shown to the person when they are asked to approve."),
                    text("cwd", "The directory to run it in. Leave it out for the one the Shell was started in."),
                    number("timeoutSeconds", "How long it may run before it is stopped. Leave it out for two minutes."),
                ),
                summary = "run a command on the person's computer",
            ),
        )

        val BRIEFING: String =
            "This chat is open in Orknux Shell, so you can run commands on the person's own computer with $RUN: look " +
                "around (ls, cat, git status, uname) before you change anything, and run one command at a time. Mark " +
                "dangerous true for anything that deletes, overwrites, installs, changes settings, kills, pushes, sends " +
                "or spends - the person approves those first - and never try to get round an approval or use sudo " +
                "without being asked. When it says no terminal is connected, the person has closed Orknux Shell. " +
                // The Shell types everything with a slash and sends a skill on as the workspace's marker.
                "In Orknux Shell the person types a skill's command with a slash at the start of a message - /plan, " +
                "not the marker - so when you tell them which commands they have, write them that way."
    }
}
