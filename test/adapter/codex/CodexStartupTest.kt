package io.kotgent.adapter.codex

import io.kotgent.adapter.LaunchMode
import io.kotgent.core.ProviderSessionId
import io.kotgent.daemon.SessionManager
import io.kotgent.tmux.ProcessRunner
import io.kotgent.tmux.Tmux
import io.kotgent.transport.readFileBytesOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CodexStartupTest {
    private class Fixture : AutoCloseable {
        val directory = ProcessRunner.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-codex-startup-XXXXXX"))
            .also { assertEquals(0, it.exitCode, it.stderr) }.stdout.trim()
        private val binary = "$directory/codex cli's launcher"
        private val hook = "$directory/hooks"

        init {
            write("version", "codex-cli 0.154.0\n")
            write("next-version", "codex-cli 0.155.0\n")
            write("exit-code", "0")
            write("payload", "{\"session_id\":\"fixture-session\"}\n")
            write("hooks", CodexHookConfig.hookScript(7777, "$directory/header"))
            executable("curl", $$"""
                #!/bin/sh
                root=$${ProcessRunner.shQuote(directory)}
                printf '%s\n' "$@" > "$root/curl-args"
                cat > "$root/curl-body"
                exit 7
            """.trimIndent())
            executable("codex cli's launcher", $$"""
                #!/bin/sh
                root=$${ProcessRunner.shQuote(directory)}
                PATH="$root:$PATH"
                export PATH
                if [ "$1" = --version ]; then
                  [ ! -f "$root/broken-version" ] || exit 2
                  cat "$root/version"
                  exit 0
                fi
                count=$(cat "$root/count" 2>/dev/null || echo 0)
                count=$((count + 1))
                echo "$count" > "$root/count"
                [ "$count" -le 2 ] || exit 97
                printf '%s\n' "$@" > "$root/args-$count"
                printf '%s\n' "$TMUX_PANE" > "$root/pane-$count"
                printf '%s\n' "${KOTGENT_CODEX_STARTUP_DIR-}" > "$root/scratch-$count"
                if [ -t 0 ] && [ -t 1 ] && [ -t 2 ]; then
                  echo tty > "$root/tty-$count"
                fi
                if [ -f "$root/interactive" ]; then
                  echo "READY-$count"
                  IFS= read -r answer || exit 98
                  printf '%s\n' "$answer" > "$root/input-$count"
                fi
                if [ "$count" = 1 ]; then
                  if [ -f "$root/started" ]; then
                    /bin/sh "$root/hooks" SessionStart < "$root/payload"
                  fi
                  cp "$root/next-version" "$root/version"
                  if [ -f "$root/break-next-version" ]; then
                    : > "$root/broken-version"
                  fi
                  if [ -f "$root/interrupted" ]; then
                    kill -INT "$PPID"
                  fi
                elif [ -f "$root/upgrade-again" ]; then
                  echo 'codex-cli 0.156.0' > "$root/version"
                fi
                exit "$(cat "$root/exit-code")"
            """.trimIndent())
        }

        fun spec(mode: LaunchMode = LaunchMode.New) = CodexAdapter(
            cwd = directory, hookScriptPath = hook, events = emptyFlow(), binaryName = binary,
        ).buildLaunchSpec(mode)

        fun run(mode: LaunchMode = LaunchMode.New) = ProcessRunner.run(
            listOf("/bin/sh", "-c", SessionManager.shellCommand(spec(mode).command)),
        )

        fun write(name: String, text: String = "") =
            writePrivateFile("$directory/$name", text.encodeToByteArray())

        fun text(name: String) = readFileBytesOrNull("$directory/$name")?.decodeToString().orEmpty()

        private fun executable(name: String, text: String) {
            write(name, text)
            assertEquals(0, ProcessRunner.run(listOf("/bin/chmod", "700", "$directory/$name")).exitCode)
        }

        fun assertScratchRemoved() {
            val scratch = text("scratch-1").trim()
            assertTrue(scratch.isNotEmpty(), "the launch has a private startup marker directory")
            assertEquals(1, ProcessRunner.run(listOf("/bin/test", "-e", scratch)).exitCode)
        }

        override fun close() {
            assertEquals(0, ProcessRunner.run(listOf("/bin/rm", "-rf", directory)).exitCode)
        }
    }

    @Test
    fun aStartupUpgradeRelaunchesOnceWithTheSameArgumentsForNewAndResume() {
        for (mode in listOf(LaunchMode.New, LaunchMode.Resume(ProviderSessionId("fixture-session")))) {
            Fixture().use { f ->
                f.write("upgrade-again")
                assertEquals(0, f.run(mode).exitCode)
                assertEquals("2\n", f.text("count"), "a successful startup update must continue launching Codex")
                assertEquals(f.text("args-1"), f.text("args-2"), "hooks and any resume id survive the update")
                f.assertScratchRemoved()
            }
        }
    }

    @Test
    fun ordinaryQuitAndFailedUpgradeKeepTheirExitStatusWithoutRestarting() {
        for (exitCode in listOf(0, 1, 130, 143)) {
            Fixture().use { f ->
                f.write("exit-code", "$exitCode")
                if (exitCode == 0) f.write("next-version", f.text("version"))
                assertEquals(exitCode, f.run().exitCode)
                assertEquals("1\n", f.text("count"))
                f.assertScratchRemoved()
            }
        }
    }

    @Test
    fun aStartedConversationIsNeverRelaunchedEvenIfCodexWasUpgradedElsewhere() {
        Fixture().use { f ->
            f.write("started")
            assertEquals(0, f.run().exitCode)
            assertEquals("1\n", f.text("count"))
            assertEquals(f.text("payload"), f.text("curl-body"), "the marker must not consume the hook payload")
            assertTrue("X-Kotgent-Hook-Event: SessionStart" in f.text("curl-args"))
            f.assertScratchRemoved()
        }
    }

    @Test
    fun unreadableVersionNeverTriggersARestart() {
        for (flag in listOf("broken-version", "break-next-version")) {
            Fixture().use { f ->
                f.write(flag)
                assertEquals(0, f.run().exitCode)
                assertEquals("1\n", f.text("count"))
            }
        }
    }

    @Test
    fun anInterruptPreventsRestartEvenIfTheChildExitsSuccessfullyAfterUpgrading() {
        Fixture().use { f ->
            f.write("interrupted")
            assertEquals(0, f.run().exitCode)
            assertEquals("1\n", f.text("count"))
            f.assertScratchRemoved()
        }
    }

    @Test
    fun startupUpgradeKeepsTheSameLiveTmuxPaneAndInteractiveStdio() = runBlocking {
        Fixture().use { f ->
            val tmux = Tmux(socket = "kotgent-codex-${f.directory.substringAfterLast('-')}")
            assertTrue(tmux.isAvailable(), "tmux is required for the startup upgrade regression")
            f.write("interactive")
            try {
                withTimeout(10.seconds) {
                    val pane = tmux.newSession("update", f.directory, SessionManager.shellCommand(f.spec().command), 80, 24)
                    while ("READY-1" !in tmux.capturePane("update")) delay(20)
                    tmux.sendKeys("update", "upgrade\n".encodeToByteArray())
                    while ("READY-2" !in tmux.capturePane("update")) delay(20)
                    val live = tmux.listPanes().single()
                    assertEquals(pane, live.paneId)
                    assertFalse(live.dead, "the update must not close Kotgent's pane")
                    for (invocation in 1..2) {
                        assertEquals("${pane.value}\n", f.text("pane-$invocation"))
                        assertEquals("tty\n", f.text("tty-$invocation"), "Codex must retain all three terminal descriptors")
                    }
                    tmux.sendKeys("update", "quit\n".encodeToByteArray())
                    while (f.text("input-2").isEmpty()) delay(20)
                    assertEquals("quit\n", f.text("input-2"))
                    while (tmux.listPanes().any { !it.dead }) delay(20)
                    assertEquals("2\n", f.text("count"), "normal exit after the restart must stay exited")
                }
            } finally {
                val _ = ProcessRunner.run(listOf(tmux.tmuxPath, "-L", tmux.socket, "kill-server"))
            }
        }
    }
}
