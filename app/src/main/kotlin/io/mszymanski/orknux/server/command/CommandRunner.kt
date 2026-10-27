package io.mszymanski.orknux.server.command

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.AuditRedaction
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where a run is. Every state but [RUNNING] is final. */
enum class CommandRunState {
    RUNNING,

    /** It ended by itself; the exit code is its own. */
    EXITED,

    /** Somebody pressed Stop. */
    STOPPED,

    /** It outlasted the timeout and was stopped. */
    TIMED_OUT,

    /** The operating system's administrator prompt was declined or dismissed. */
    DECLINED,

    /** It could not be started, or elevation was not obtained; [CommandRun.message] says what was reported. */
    FAILED,
}

/**
 * One command a person ran from a chat answer, while it runs and for a while
 * after, so the page can read what it printed.
 *
 * Kept in memory and nowhere else: what a command printed can be anything at
 * all, and the audit entry is the record that it ran. A restart forgets the
 * output, which is the same as closing a terminal.
 */
class CommandRun(
    val id: String,
    val by: String,
    val command: String,
    val shell: CommandShell,
    val elevated: Boolean,
    val files: RunFiles,
    private val cap: Int,
) {
    val startedAt: Instant = Instant.now()

    @Volatile var state: CommandRunState = CommandRunState.RUNNING
    @Volatile var exitCode: Int? = null
    @Volatile var message: String? = null
    @Volatile var endedAt: Instant? = null
    @Volatile var truncated: Boolean = false
    @Volatile var process: Process? = null

    /** Set when somebody asked it to stop, so the end says stopped rather than an exit code. */
    @Volatile var stopAsked: Boolean = false
    @Volatile var timedOut: Boolean = false

    private val output = StringBuilder()

    fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(output) {
            val room = cap - output.length
            if (room <= 0) {
                truncated = true
                return
            }
            if (text.length > room) {
                output.append(text, 0, room)
                truncated = true
            } else {
                output.append(text)
            }
        }
    }

    /** What it printed from [from] on, and where the next read starts. */
    fun outputFrom(from: Int): Pair<String, Int> = synchronized(output) {
        val start = from.coerceIn(0, output.length)
        output.substring(start) to output.length
    }

    fun outputLength(): Int = synchronized(output) { output.length }
}

/**
 * Runs a command on the machine the server is on, for the chat's Run button.
 *
 * Who may and whether the installation allows it is the API's business; this
 * class only runs, reads, stops and forgets. The timeout and the output cap
 * are [Tunable]s, read as each run starts.
 */
@Service
class CommandRunner(
    private val tuning: Tuning,
    private val audit: WorkspaceAuditRecorder,
) {
    private val log = LoggerFactory.getLogger(CommandRunner::class.java)
    private val system = HostSystem.current()
    private val runs = ConcurrentHashMap<String, CommandRun>()

    /** The java.io.tmpdir of this server, so two servers on one machine keep apart. */
    private val root: Path = Path.of(System.getProperty("java.io.tmpdir"), "orknux-commands")

    /** The shells this machine can run a block with. */
    fun shells(): List<CommandShell> = when (system) {
        HostSystem.WINDOWS -> listOf(CommandShell.POWERSHELL, CommandShell.CMD)
        else -> listOfNotNull(CommandShell.POSIX, CommandShell.POWERSHELL.takeIf { pwsh() != null })
    }

    /** Whether this machine has a way to ask its operating system for elevation. */
    fun canElevate(): Boolean = when (system) {
        HostSystem.WINDOWS -> true
        HostSystem.LINUX -> pkexec() != null
        HostSystem.OTHER -> false
    }

    fun find(id: String): CommandRun? {
        forgetOld()
        return runs[id]
    }

    /**
     * Starts [command] and returns at once; the run is read with [find] as it
     * goes. [by] is who asked, for the audit entry the end writes on a thread
     * that has nobody signed in on it.
     */
    fun start(command: String, language: String, shell: CommandShell, elevated: Boolean, by: String): CommandRun {
        forgetOld()
        val id = UUID.randomUUID().toString()
        val dir = root.resolve(id)
        Files.createDirectories(dir)
        ownerOnly(dir)
        val files = RunFiles(dir.toString())
        // Held to the longest text a StringBuilder can hold: past it appending would throw, not keep more.
        val cap = tuning[Tunable.COMMAND_OUTPUT_CHARACTERS].coerceAtMost(io.mszymanski.orknux.server.tuning.TunableArithmetic.LONGEST_TEXT)
        val run = CommandRun(id, by, command, shell, elevated, files, cap)
        runs[id] = run

        val posixShell = posixShellFor(language)
        Files.writeString(Path.of(files.scriptFor(shell)), CommandLines.scriptText(shell, command, posixShell), StandardCharsets.UTF_8)
        val line = if (elevated) {
            CommandLines.elevationScripts(shell, system, files, posixShell, pwsh()).forEach { (path, text) ->
                Files.writeString(Path.of(path), text, StandardCharsets.UTF_8)
            }
            CommandLines.elevated(system, files, pkexec() ?: "pkexec")
        } else {
            CommandLines.plain(shell, system, files, posixShell, pwsh())
        }

        val process = try {
            ProcessBuilder(line)
                .directory(java.io.File(System.getProperty("user.home")))
                .redirectErrorStream(true)
                .start()
        } catch (cause: Exception) {
            run.message = cause.message ?: cause.javaClass.simpleName
            finish(run, CommandRunState.FAILED, null)
            return run
        }
        run.process = process
        // Nothing types into it: a command that asks a question reads the end of its input, not a wait for ever.
        runCatching { process.outputStream.close() }

        val timeout = Duration.ofSeconds(tuning[Tunable.COMMAND_TIMEOUT_SECONDS].toLong())
        Thread.ofVirtual().name("command-$id-out").start { pump(process.inputStream, run) }
        val tail = if (elevated && system == HostSystem.WINDOWS) {
            Thread.ofVirtual().name("command-$id-tail").start { tailFile(Path.of(files.output), run, process) }
        } else {
            null
        }
        Thread.ofVirtual().name("command-$id").start { await(run, process, timeout, tail) }
        return run
    }

    /** Stops a run: the process tree for an unelevated one, the stop file for an elevated one. */
    fun stop(run: CommandRun) {
        if (run.state != CommandRunState.RUNNING) return
        run.stopAsked = true
        halt(run)
    }

    private fun halt(run: CommandRun) {
        if (run.elevated) runCatching { Files.writeString(Path.of(run.files.stop), "stop") }
        val process = run.process ?: return
        process.descendants().forEach { runCatching { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    private fun await(run: CommandRun, process: Process, timeout: Duration, tail: Thread?) {
        val ended = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!ended) {
            run.timedOut = true
            halt(run)
            process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)
        }
        tail?.join(STOP_GRACE_SECONDS * 1000)
        val code = if (process.isAlive) null else process.exitValue()
        // The reader has the last of what was printed by the time the process is gone, give or take a read.
        Thread.sleep(READ_SETTLE_MILLIS)
        when {
            run.timedOut -> finish(run, CommandRunState.TIMED_OUT, code)
            run.stopAsked -> finish(run, CommandRunState.STOPPED, code)
            run.elevated -> finishElevated(run, code)
            else -> finish(run, CommandRunState.EXITED, code)
        }
    }

    /**
     * What an elevated run's end means. The elevated side writes the command's
     * own code when it ran; without that, the prompt's answer is what decides,
     * as the operating system reported it.
     */
    private fun finishElevated(run: CommandRun, code: Int?) {
        val files = run.files
        val own = runCatching { Files.readString(Path.of(files.exit)).trim().removePrefix("﻿").trim().toInt() }.getOrNull()
        when {
            system == HostSystem.WINDOWS && Files.exists(Path.of(files.declined)) -> {
                run.message = "The administrator prompt was declined."
                finish(run, CommandRunState.DECLINED, code)
            }
            system == HostSystem.WINDOWS -> finish(run, CommandRunState.EXITED, own ?: code)
            Files.exists(Path.of(files.started)) -> finish(run, CommandRunState.EXITED, code)
            code == CommandLines.PKEXEC_DISMISSED -> {
                run.message = "The administrator prompt was declined."
                finish(run, CommandRunState.DECLINED, code)
            }
            else -> {
                run.message = "pkexec did not run it: not authorized, or authentication failed (exit $code)."
                finish(run, CommandRunState.FAILED, code)
            }
        }
    }

    private fun finish(run: CommandRun, state: CommandRunState, code: Int?) {
        run.exitCode = code
        run.endedAt = Instant.now()
        run.state = state
        val said = when (state) {
            CommandRunState.EXITED -> "exited with $code"
            CommandRunState.STOPPED -> "was stopped"
            CommandRunState.TIMED_OUT -> "timed out"
            CommandRunState.DECLINED -> "was not run: the administrator prompt was declined"
            CommandRunState.FAILED -> "could not be run"
            CommandRunState.RUNNING -> "is running"
        }
        runCatching {
            audit.recordAutomated(null, WorkspaceAuditCategory.SHELL, auditLine("Command from the chat $said", run), run.by)
        }.onFailure { log.warn("Could not audit the end of command run {}", run.id, it) }
        // The scripts go; an elevated run's directory stays until it is forgotten, because its stop file may yet be read.
        if (!run.elevated) deleteQuietly(Path.of(run.files.dir))
    }

    private fun pump(stream: InputStream, run: CommandRun) {
        runCatching {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(READ_CHUNK)
                var first = true
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    var text = String(buffer, 0, read)
                    if (first) {
                        text = text.removePrefix("﻿")
                        first = false
                    }
                    run.append(text)
                }
            }
        }
    }

    /** Follows the file an elevated Windows run writes, until the process waiting on it has ended. */
    private fun tailFile(file: Path, run: CommandRun, process: Process) {
        var read = 0L
        var pending = ByteArray(0)
        fun drain() {
            if (!Files.exists(file)) return
            val bytes = runCatching {
                java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
                    if (raf.length() <= read) return@use ByteArray(0)
                    raf.seek(read)
                    val chunk = ByteArray((raf.length() - read).toInt())
                    raf.readFully(chunk)
                    chunk
                }
            }.getOrDefault(ByteArray(0))
            if (bytes.isEmpty()) return
            read += bytes.size
            var all = pending + bytes
            if (read == bytes.size.toLong() && all.size >= 3 &&
                all[0] == 0xEF.toByte() && all[1] == 0xBB.toByte() && all[2] == 0xBF.toByte()
            ) {
                all = all.copyOfRange(3, all.size)
            }
            // A character split across two reads waits for its other half.
            val whole = completeUtf8(all)
            run.append(String(all, 0, whole, StandardCharsets.UTF_8))
            pending = all.copyOfRange(whole, all.size)
        }
        while (process.isAlive) {
            drain()
            Thread.sleep(TAIL_MILLIS)
        }
        drain()
    }

    private fun forgetOld() {
        val keep = Duration.ofSeconds(tuning[Tunable.COMMAND_TIMEOUT_SECONDS].toLong())
        val now = Instant.now()
        runs.values.filter { it.endedAt?.plus(keep)?.isBefore(now) == true }.forEach { run ->
            runs.remove(run.id)
            deleteQuietly(Path.of(run.files.dir))
        }
    }

    /** The shell a POSIX block names, where this machine has it; sh otherwise. */
    private fun posixShellFor(language: String): String = when (language.trim().lowercase()) {
        "bash" -> CommandLines.onPath("bash")
        "zsh" -> CommandLines.onPath("zsh")
        else -> null
    } ?: "/bin/sh"

    private fun pwsh(): String? = CommandLines.onPath("pwsh")

    private fun pkexec(): String? = CommandLines.onPath("pkexec")

    private fun ownerOnly(dir: Path) {
        runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
    }

    private fun deleteQuietly(dir: Path) {
        runCatching {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    companion object {
        private const val READ_CHUNK = 4096
        private const val TAIL_MILLIS = 200L
        private const val READ_SETTLE_MILLIS = 50L
        private const val STOP_GRACE_SECONDS = 5L

        /** Room in a 500-character audit row for the words around the command. */
        private const val AUDIT_COMMAND_CHARACTERS = 380

        /** The command as the audit log may hold it: credentials out first, then cut to fit. */
        fun auditLine(prefix: String, run: CommandRun): String {
            val redacted = AuditRedaction.redact(run.command.trim()).replace(Regex("\\s*\\R\\s*"), " ; ")
            val shown = if (redacted.length > AUDIT_COMMAND_CHARACTERS) redacted.take(AUDIT_COMMAND_CHARACTERS) + "..." else redacted
            val how = if (run.elevated) " as administrator" else ""
            return "$prefix$how: $shown"
        }

        /** How many leading bytes of [bytes] are whole UTF-8 characters. */
        fun completeUtf8(bytes: ByteArray): Int {
            var end = bytes.size
            var back = 0
            while (back < 3 && end - back - 1 >= 0) {
                val b = bytes[end - back - 1].toInt() and 0xFF
                if (b and 0xC0 != 0x80) {
                    val need = when {
                        b and 0x80 == 0 -> 1
                        b and 0xE0 == 0xC0 -> 2
                        b and 0xF0 == 0xE0 -> 3
                        b and 0xF8 == 0xF0 -> 4
                        else -> 1
                    }
                    return if (back + 1 >= need) end else end - back - 1
                }
                back++
            }
            return end
        }
    }
}
