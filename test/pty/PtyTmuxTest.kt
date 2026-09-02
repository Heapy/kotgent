package io.kotgent.pty

import io.kotgent.tmux.Tmux
import io.kotgent.tmux.tmuxCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class PtyTmuxTest {

    private fun skipped(reason: String = "tmux is not runnable") {
        println("SKIP  PtyTmuxTest — $reason")
    }

    // `-f /dev/null` is load-bearing: developer tmux settings must not alter the fixture server.
    @Test
    fun tmuxAttachRunsOnTheSpawnedPts() {
        val tmux = which("tmux") ?: return skipped()
        val session = "kt-ptytest"
        val target = "${q(tmux)} -f /dev/null -L $TEST_SOCKET"

        val _ = sh("$target kill-session -t $session")
        val created = sh("$target new-session -d -s $session -x 80 -y 24 /bin/cat")
        expect(created == 0) { "could not create the tmux fixture session (exit=$created)" }

        try {
            val destroyUnattached = capture("$target show-options -gv destroy-unattached")
            expect(destroyUnattached == "off") {
                "an isolated server must report destroy-unattached off, got <$destroyUnattached> — " +
                    "is -f /dev/null still there?"
            }

            val pty = Pty.open(
                command = tmuxCommand(tmux, TEST_SOCKET, listOf("attach", "-t", session)),
                env = mapOf("TERM" to "xterm-256color", "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin"),
            )
            try {
                pty.write("hello-fanout\n".encodeToByteArray())
                val out = readUntil(pty, "hello-fanout", timeoutMs = 10_000)
                expect("hello-fanout" in out) { "expected the attached pane to echo our line, got: <$out>" }
            } finally {
                val _ = pty.close()
            }

            val alive = sh("$target has-session -t $session")
            expect(alive == 0) { "the tmux session should outlive the attach (has-session exit=$alive)" }
        } finally {
            val _ = sh("$target kill-session -t $session")
        }
    }

    // posix_spawn does not give the child a controlling tty, so this catches a missing explicit SIGWINCH.
    @Test
    fun aResizeReachesARunningTmuxAttach() {
        val tmux = which("tmux") ?: return skipped()
        val target = "${q(tmux)} -f /dev/null -L $TEST_SOCKET"
        val session = "kt-ptytest-resize"

        val _ = sh("$target kill-session -t $session")
        val created = sh("$target new-session -d -s $session -x 80 -y 24 /bin/cat")
        expect(created == 0) { "could not create the tmux fixture session (exit=$created)" }

        try {
            val pty = Pty.open(
                command = tmuxCommand(tmux, TEST_SOCKET, listOf("attach", "-t", session)),
                env = mapOf("TERM" to "xterm-256color", "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin"),
                cols = 80,
                rows = 24,
            )
            try {
                val attached = waitUntil { capture("$target list-clients -t $session -F '#{client_width}'") == "80" }
                expect(attached) { "the attach client never came up at 80 columns" }

                pty.resize(cols = 143, rows = 53)

                val resized = waitUntil { capture("$target display -p -t $session '#{window_width}'") == "143" }
                expect(resized) {
                    "the running tmux client ignored the resize — window is still " +
                        capture("$target display -p -t $session '#{window_width}x#{window_height}'")
                }
            } finally {
                val _ = pty.close()
            }
        } finally {
            val _ = sh("$target kill-session -t $session")
        }
    }

    /** Two subscribers share one upstream attach, and tmux survives the detach. */
    @Test
    fun terminalBridgeFansOutARealTmuxAttach() {
        val tmux = Tmux(socket = TEST_SOCKET)
        if (!tmux.isAvailable()) return skipped()
        val id = "ptytest-bridge"

        runBlocking {
            val readerScope = CoroutineScope(coroutineContext + Job())
            try {
                val _ = tmux.killSession(id)
                val _ = tmux.newSession(id = id, cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)

                val bridge = terminalBridgeForSession(tmux, id, readerScope, realPtyFactory)

                val a = bridge.subscribe()
                val b = bridge.subscribe()
                withTimeout(5.seconds) { a.output.receive() }
                withTimeout(5.seconds) { b.output.receive() }
                expect(bridge.subscriberCount() == 2) { "expected 2 subscribers, got ${bridge.subscriberCount()}" }

                a.write("hello-fanout\n".encodeToByteArray())
                val fromA = receiveUntil(a, "hello-fanout")
                val fromB = receiveUntil(b, "hello-fanout")
                expect("hello-fanout" in fromA) { "subscriber A missed the echo, got: <$fromA>" }
                expect("hello-fanout" in fromB) { "subscriber B missed the echo, got: <$fromB>" }

                a.close()
                b.close()
                expect(tmux.listPanes().any { it.session == tmux.sessionName(id) }) {
                    "the tmux session should outlive the attach"
                }
            } finally {
                readerScope.cancel()
                val _ = tmux.killSession(id)
            }
        }
    }
}
