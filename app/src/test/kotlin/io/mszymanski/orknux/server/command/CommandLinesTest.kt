package io.mszymanski.orknux.server.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The command lines and scripts a run is made of, read without running them -
 * which is the only way elevation is tested at all, since nothing in a build
 * can answer a UAC prompt or a polkit dialog.
 */
class CommandLinesTest {

    private val windowsFiles = RunFiles("C:\\Users\\o'neil\\AppData\\Local\\Temp\\orknux-commands\\run1", '\\')
    private val linuxFiles = RunFiles("/tmp/orknux-commands/run1", '/')

    @Test
    fun `a block's language decides its shell, and an untagged one has none`() {
        assertThat(CommandShell.of("PowerShell")).isEqualTo(CommandShell.POWERSHELL)
        assertThat(CommandShell.of("ps1")).isEqualTo(CommandShell.POWERSHELL)
        assertThat(CommandShell.of("bat")).isEqualTo(CommandShell.CMD)
        assertThat(CommandShell.of("bash")).isEqualTo(CommandShell.POSIX)
        assertThat(CommandShell.of("console")).isEqualTo(CommandShell.POSIX)
        assertThat(CommandShell.of("")).isNull()
        assertThat(CommandShell.of("python")).isNull()
    }

    @Test
    fun `unelevated, the script is handed to its shell as a file`() {
        val ps = CommandLines.plain(CommandShell.POWERSHELL, HostSystem.WINDOWS, windowsFiles, "/bin/sh", null)
        assertThat(ps).containsExactly(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            windowsFiles.scriptFor(CommandShell.POWERSHELL).toString(),
        )
        assertThat(CommandLines.plain(CommandShell.CMD, HostSystem.WINDOWS, windowsFiles, "/bin/sh", null))
            .containsExactly("cmd.exe", "/d", "/c", windowsFiles.scriptFor(CommandShell.CMD).toString())
        assertThat(CommandLines.plain(CommandShell.POSIX, HostSystem.LINUX, linuxFiles, "/usr/bin/bash", null))
            .containsExactly("/usr/bin/bash", "/tmp/orknux-commands/run1/command.sh")
    }

    @Test
    fun `a PowerShell script answers its program's exit code and writes UTF-8`() {
        val text = CommandLines.scriptText(CommandShell.POWERSHELL, "git status")
        assertThat(text).startsWith("\uFEFF")
        assertThat(text).contains("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n\$OutputEncoding = [System.Text.Encoding]::UTF8\r\ngit status\r\n")
        assertThat(text).contains("if (\$LASTEXITCODE) { exit \$LASTEXITCODE }")
        assertThat(CommandLines.scriptText(CommandShell.CMD, "dir")).isEqualTo("@echo off\r\nchcp 65001 >nul\r\ndir\r\n")
    }

    @Test
    fun `elevated on Windows, UAC is asked through Start-Process -Verb RunAs and a declined prompt leaves a file`() {
        assertThat(CommandLines.elevated(HostSystem.WINDOWS, windowsFiles, "pkexec")).containsExactly(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", windowsFiles.elevate.toString(),
        )
        val elevate = CommandLines.elevateScript(windowsFiles)
        assertThat(elevate).contains("Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden -Wait -PassThru")
        // The path is inside a single-quoted literal, so its apostrophe is doubled.
        assertThat(elevate).contains("-File \"C:\\Users\\o''neil\\AppData\\Local\\Temp\\orknux-commands\\run1\\watch.ps1\"'")
        assertThat(elevate).contains("NativeErrorCode -eq 1223")
        assertThat(elevate).contains("Set-Content -LiteralPath 'C:\\Users\\o''neil\\AppData\\Local\\Temp\\orknux-commands\\run1\\declined'")
        assertThat(elevate).contains("exit \$p.ExitCode")
    }

    @Test
    fun `the elevated side says it started, stops on the stop file, and captures every stream into a file`() {
        val watch = CommandLines.watchScript(windowsFiles)
        assertThat(watch).contains("run1\\started'")
        assertThat(watch).contains("Test-Path -LiteralPath 'C:\\Users\\o''neil\\AppData\\Local\\Temp\\orknux-commands\\run1\\stop'")
        assertThat(watch).contains("& taskkill.exe /T /F /PID \$p.Id")

        val capture = CommandLines.captureScript(CommandShell.POWERSHELL, windowsFiles)
        assertThat(capture).contains("& 'C:\\Users\\o''neil\\AppData\\Local\\Temp\\orknux-commands\\run1\\command.ps1' *>&1 | Out-File -LiteralPath")
        assertThat(capture).contains("run1\\output.txt' -Append -Encoding utf8")
        assertThat(capture).contains("Set-Content -LiteralPath 'C:\\Users\\o''neil\\AppData\\Local\\Temp\\orknux-commands\\run1\\exit.txt' -Value \$code")
        assertThat(CommandLines.captureScript(CommandShell.CMD, windowsFiles)).contains("& cmd.exe /d /c 'C:\\Users")

        assertThat(CommandLines.elevationScripts(CommandShell.CMD, HostSystem.WINDOWS, windowsFiles, "/bin/sh", null).keys)
            .containsExactlyInAnyOrder(windowsFiles.elevate, windowsFiles.watch, windowsFiles.capture)
    }

    @Test
    fun `elevated on Linux, pkexec runs a watcher as root that runs the command and stops it on the stop file`() {
        assertThat(CommandLines.elevated(HostSystem.LINUX, linuxFiles, "/usr/bin/pkexec"))
            .containsExactly("/usr/bin/pkexec", "/bin/sh", "/tmp/orknux-commands/run1/watch.sh")

        val scripts = CommandLines.elevationScripts(CommandShell.POSIX, HostSystem.LINUX, linuxFiles, "/bin/bash", null)
        val watch = scripts.getValue(linuxFiles.watchSh)
        assertThat(watch).startsWith("#!/bin/sh\n: > '/tmp/orknux-commands/run1/started'\n")
        assertThat(watch).contains("'/bin/bash' '/tmp/orknux-commands/run1/command.sh' &")
        assertThat(watch).contains("if [ -e '/tmp/orknux-commands/run1/stop' ]; then")
        assertThat(watch).endsWith("wait \"\$child\"\nexit \$?\n")
    }

    /**
     * The elevation scripts parse, in the shell that will read them - which is
     * as far as a build can go without somebody at a UAC prompt or a polkit
     * dialog. PowerShell's parser where this machine has PowerShell, `sh -n`
     * where it has sh.
     */
    @Test
    fun `the elevation scripts parse in the shell that reads them`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val windows = HostSystem.current() == HostSystem.WINDOWS
        val files = RunFiles(dir.toString())
        val system = if (windows) HostSystem.WINDOWS else HostSystem.LINUX
        val scripts = CommandLines.elevationScripts(CommandShell.POWERSHELL, system, files, "/bin/sh", null)
        scripts.forEach { (path, text) -> java.nio.file.Files.writeString(java.nio.file.Path.of(path), text) }
        scripts.keys.forEach { path ->
            val check = if (windows) {
                listOf(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseFile('${path.replace("'", "''")}', [ref]\$null, [ref]\$e); " +
                        "if (\$e.Count) { \$e | ForEach-Object { \$_.Message }; exit 1 }",
                )
            } else {
                listOf("/bin/sh", "-n", path)
            }
            val process = ProcessBuilder(check).redirectErrorStream(true).start()
            val said = process.inputStream.bufferedReader().readText()
            assertThat(process.waitFor()).describedAs("$path: $said").isEqualTo(0)
        }
    }

    @Test
    fun `quoting doubles what each shell needs doubled`() {
        assertThat(CommandLines.ps("it's")).isEqualTo("'it''s'")
        assertThat(CommandLines.sh("it's")).isEqualTo("'it'\\''s'")
    }

    @Test
    fun `a character split across two reads of the output file waits for its other half`() {
        val bytes = "zażółć".toByteArray(Charsets.UTF_8)
        assertThat(CommandRunner.completeUtf8(bytes)).isEqualTo(bytes.size)
        assertThat(CommandRunner.completeUtf8(bytes.copyOf(bytes.size - 1))).isEqualTo(bytes.size - 2)
        assertThat(CommandRunner.completeUtf8("abc".toByteArray())).isEqualTo(3)
    }

    @Test
    fun `the audit line has the credentials out, one line, and fits the row`() {
        val run = CommandRun(
            "id", "alice", "curl -u alice:s3cr3t https://example.invalid\n" + "x".repeat(1000),
            CommandShell.POSIX, true, linuxFiles, 100,
        )
        val line = CommandRunner.auditLine("Command run from the chat", run)
        assertThat(line).startsWith("Command run from the chat as administrator: curl -u ")
        assertThat(line).doesNotContain("s3cr3t").doesNotContain("\n")
        assertThat(line.length).isLessThanOrEqualTo(500)
    }

    @Test
    fun `output past the cap is dropped and said to be`() {
        val run = CommandRun("id", "alice", "x", CommandShell.POSIX, false, linuxFiles, 5)
        run.append("abc")
        run.append("defgh")
        assertThat(run.outputFrom(0)).isEqualTo("abcde" to 5)
        assertThat(run.truncated).isTrue()
        assertThat(run.outputFrom(3)).isEqualTo("de" to 5)
    }
}
