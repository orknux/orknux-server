package io.mszymanski.orknux.server.command

import java.io.File

/**
 * What a fenced block in a chat answer is run with, read off the language the
 * model wrote on it.
 *
 * Only blocks that name a shell. An untagged block is as often what a command
 * printed, a config file or a path as it is a command, and a Run button on it
 * invites running something that was never meant to be run.
 */
enum class CommandShell(val languages: Set<String>) {
    POWERSHELL(setOf("powershell", "ps1", "ps", "pwsh")),
    CMD(setOf("cmd", "bat", "batch", "dos")),
    POSIX(setOf("sh", "bash", "zsh", "shell", "console")),
    ;

    companion object {
        fun of(language: String): CommandShell? {
            val said = language.trim().lowercase()
            return entries.firstOrNull { said in it.languages }
        }
    }
}

/** The operating system the server is on, as far as running a command cares. */
enum class HostSystem {
    WINDOWS,
    LINUX,
    OTHER,
    ;

    companion object {
        fun current(): HostSystem {
            val name = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                name.startsWith("windows") -> WINDOWS
                name.contains("linux") -> LINUX
                else -> OTHER
            }
        }
    }
}

/**
 * The files one run is made of, all in a directory of its own.
 *
 * The command is always written to a file and the file is what a shell is
 * handed, never the command on a command line: a model's command carries quotes,
 * percent signs and dollar signs, and every layer of quoting between Java,
 * PowerShell, `Start-Process` and cmd would be one more place to get it wrong.
 */
class RunFiles(val dir: String, private val separator: Char = File.separatorChar) {
    private fun at(name: String): String = dir.trimEnd(separator) + separator + name

    fun scriptFor(shell: CommandShell): String = at(
        when (shell) {
            CommandShell.POWERSHELL -> "command.ps1"
            CommandShell.CMD -> "command.cmd"
            CommandShell.POSIX -> "command.sh"
        },
    )

    /** Windows elevation: what the hidden elevated window writes, since it shares no console with the server. */
    val output: String get() = at("output.txt")

    /** The command's own exit code, written by the elevated side when it ends. */
    val exit: String get() = at("exit.txt")

    /** Written by the server to stop an elevated run, which it cannot kill itself. */
    val stop: String get() = at("stop")

    /** Written by the elevated side as it starts, so its exit code is known to be the command's. */
    val started: String get() = at("started")

    /** Written on Windows when the person said no to the administrator prompt. */
    val declined: String get() = at("declined")

    val elevate: String get() = at("elevate.ps1")
    val watch: String get() = at("watch.ps1")
    val capture: String get() = at("capture.ps1")
    val watchSh: String get() = at("watch.sh")
}

/**
 * The programs a run starts, and the scripts it writes first. Pure: every line
 * here is a function of what was asked and where, so a test can read the
 * command line built for UAC or pkexec on a machine that has neither.
 *
 * Elevation is asked of the operating system, never of Orknux - nothing here
 * ever holds a password. On Windows that is UAC, through `Start-Process -Verb
 * RunAs`; the elevated process gets a console of its own, so its output comes
 * back through a file. On Linux it is pkexec, whose polkit dialog asks the
 * person at the desktop, and whose output is the command's own.
 */
object CommandLines {

    /** The exit code Windows gives a cancelled UAC prompt: ERROR_CANCELLED. */
    const val UAC_CANCELLED = 1223

    /** pkexec's own codes: the dialog was dismissed, or authorisation was not obtained. */
    const val PKEXEC_DISMISSED = 126
    const val PKEXEC_NOT_AUTHORIZED = 127

    /** The script a run's shell is handed, with what makes its output UTF-8 and its exit code the command's. */
    fun scriptText(shell: CommandShell, command: String, posixShell: String = "/bin/sh"): String = when (shell) {
        // A BOM, or Windows PowerShell 5.1 reads the file in the ANSI code page.
        CommandShell.POWERSHELL -> "﻿" + listOf(
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "\$OutputEncoding = [System.Text.Encoding]::UTF8",
            command,
            // -File answers 0 for a script that ran a failing program; the program's code is the answer.
            "if (\$LASTEXITCODE) { exit \$LASTEXITCODE }",
        ).joinToString("\r\n") + "\r\n"
        CommandShell.CMD -> listOf("@echo off", "chcp 65001 >nul", command).joinToString("\r\n") + "\r\n"
        CommandShell.POSIX -> "#!$posixShell\n$command\n"
    }

    /** What runs the script as the server's own user. */
    fun plain(shell: CommandShell, system: HostSystem, files: RunFiles, posixShell: String, pwsh: String?): List<String> {
        val script = files.scriptFor(shell)
        return when (shell) {
            CommandShell.POWERSHELL ->
                if (system == HostSystem.WINDOWS) {
                    listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script)
                } else {
                    listOf(pwsh ?: "pwsh", "-NoProfile", "-NonInteractive", "-File", script)
                }
            CommandShell.CMD -> listOf("cmd.exe", "/d", "/c", script)
            CommandShell.POSIX -> listOf(posixShell, script)
        }
    }

    /**
     * What runs the script as an administrator.
     *
     * Windows: a PowerShell that asks UAC for an elevated, hidden PowerShell
     * running [watchScript], which runs [captureScript] and kills it when the
     * stop file appears. Linux: pkexec, with [watchShScript] between it and the
     * command for the same reason - the server cannot signal a process that runs
     * as root, so stopping one is a file the root side looks for.
     */
    fun elevated(system: HostSystem, files: RunFiles, pkexec: String): List<String> =
        when (system) {
            HostSystem.WINDOWS ->
                listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", files.elevate)
            else -> listOf(pkexec, "/bin/sh", files.watchSh)
        }

    /** The scripts an elevated run writes beside the command, by file. */
    fun elevationScripts(shell: CommandShell, system: HostSystem, files: RunFiles, posixShell: String, pwsh: String?): Map<String, String> =
        when (system) {
            HostSystem.WINDOWS -> mapOf(
                files.elevate to elevateScript(files),
                files.watch to watchScript(files),
                files.capture to captureScript(shell, files),
            )
            else -> mapOf(files.watchSh to watchShScript(plain(shell, system, files, posixShell, pwsh), files))
        }

    /**
     * Unelevated: asks UAC, waits, and answers the elevated side's exit code.
     * A declined prompt is a Win32Exception with code 1223 somewhere in the
     * chain, and it leaves a file saying so rather than a guess at a message.
     */
    fun elevateScript(files: RunFiles): String = "﻿" + listOf(
        "\$ErrorActionPreference = 'Stop'",
        "try {",
        "  \$p = Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden -Wait -PassThru " +
            "-ArgumentList ${ps("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"${files.watch}\"")}",
        "  exit \$p.ExitCode",
        "} catch {",
        "  \$e = \$_.Exception",
        "  while (\$null -ne \$e) {",
        "    if (\$e.NativeErrorCode -eq $UAC_CANCELLED) { Set-Content -LiteralPath ${ps(files.declined)} -Value 'declined'; exit $UAC_CANCELLED }",
        "    \$e = \$e.InnerException",
        "  }",
        "  Write-Output \$_.Exception.Message",
        "  exit 1",
        "}",
    ).joinToString("\r\n") + "\r\n"

    /** Elevated and hidden: runs the capture and kills it, with everything it started, when told to stop. */
    fun watchScript(files: RunFiles): String = "﻿" + listOf(
        "Set-Content -LiteralPath ${ps(files.started)} -Value 'started'",
        "\$p = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -PassThru " +
            "-ArgumentList ${ps("-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"${files.capture}\"")}",
        "\$null = \$p.Handle",
        "while (-not \$p.HasExited) {",
        "  if (Test-Path -LiteralPath ${ps(files.stop)}) { & taskkill.exe /T /F /PID \$p.Id | Out-Null; exit 1 }",
        "  Start-Sleep -Milliseconds 200",
        "}",
        "exit \$p.ExitCode",
    ).joinToString("\r\n") + "\r\n"

    /** Elevated: runs the command with every stream into the output file, and writes its exit code. */
    fun captureScript(shell: CommandShell, files: RunFiles): String {
        val script = ps(files.scriptFor(shell))
        val run = when (shell) {
            CommandShell.CMD -> "& cmd.exe /d /c $script"
            else -> "& $script"
        }
        return "﻿" + listOf(
            "\$code = 0",
            "try {",
            "  $run *>&1 | Out-File -LiteralPath ${ps(files.output)} -Append -Encoding utf8 -Width 4096",
            "  if (\$LASTEXITCODE) { \$code = \$LASTEXITCODE }",
            "} catch {",
            "  \$_ | Out-File -LiteralPath ${ps(files.output)} -Append -Encoding utf8 -Width 4096",
            "  \$code = 1",
            "}",
            "Set-Content -LiteralPath ${ps(files.exit)} -Value \$code",
            "exit \$code",
        ).joinToString("\r\n") + "\r\n"
    }

    /**
     * As root under pkexec: says it started, runs the command, and stops it
     * when the stop file appears. Its exit code is the command's, which is told
     * apart from pkexec's own 126 and 127 by the started file.
     */
    fun watchShScript(command: List<String>, files: RunFiles): String = listOf(
        "#!/bin/sh",
        ": > ${sh(files.started)}",
        "${command.joinToString(" ") { sh(it) }} &",
        "child=\$!",
        "while kill -0 \"\$child\" 2>/dev/null; do",
        "  if [ -e ${sh(files.stop)} ]; then pkill -TERM -P \"\$child\" 2>/dev/null; kill -TERM \"\$child\" 2>/dev/null; exit 143; fi",
        "  sleep 0.2",
        "done",
        "wait \"\$child\"",
        "exit \$?",
    ).joinToString("\n") + "\n"

    /** A PowerShell single-quoted literal. */
    fun ps(text: String): String = "'" + text.replace("'", "''") + "'"

    /** A POSIX shell single-quoted literal. */
    fun sh(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /** A program on the PATH, by name, or null. */
    fun onPath(name: String): String? {
        val path = System.getenv("PATH").orEmpty()
        return path.split(File.pathSeparator).filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }
}
