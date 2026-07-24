package io.kotgent.ptycheck

import io.kotgent.pty.Pty
import io.kotgent.pty.PtyException
import io.kotgent.pty.Subscriber
import io.kotgent.pty.realPtyFactory
import io.kotgent.pty.terminalBridgeForSession
import io.kotgent.tmux.Tmux
import io.kotgent.tmux.tmuxCommand
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess
import platform.posix.F_DUPFD
import platform.posix.close
import platform.posix.fcntl
import platform.posix.fread
import platform.posix.pclose
import platform.posix.pipe
import platform.posix.popen

/**
 * Real-PTY integration checks for [Pty], run from a MAIN binary.
 *
 * These are the tests that used to be `@Ignore`d in `test/pty/PtyTest.kt`: they exercise the
 * `sysnative` cinterop (`openpty` + `posix_spawn`) for real, and a custom cinterop klib is never
 * linked into a test binary on Kotlin Toolchain 0.11.x (KT-78062 — the toolchain registers the
 * cinterop task for the non-test fragment only, while the test link asks for test-fragment cinterop
 * artifacts). A main binary does link it, so the same assertions run here instead, and the suite's
 * `PtyTest` execs this binary and asserts it exits 0.
 *
 * Contract with that runner: every check prints one `PASS`/`FAIL`/`SKIP` line, the last line is
 * `SUMMARY total=<n> failed=<n> skipped=<n>`, and the exit code is 0 iff nothing failed. Every read
 * from a pty is bounded by a [withTimeout] so a broken round-trip fails fast instead of hanging.
 */
fun main() {
    ptyRoundTripThroughCat()
    resizeSucceeds()
    exitCodeIsCaptured()
    spawnNonexistentCommandFails()
    spawnedChildInheritsOnlyTheTty()
    tmuxAttachRunsOnTheSpawnedPts()
    resizeReachesARunningTmuxAttach()
    terminalBridgeFansOutRealTmuxAttach()

    println("SUMMARY total=$total failed=$failed skipped=$skipped")
    if (failed > 0) exitProcess(1)
}

// --- checks -------------------------------------------------------------------------------------

private fun ptyRoundTripThroughCat() = check("cat echoes a round-trip line") {
    val pty = Pty.open(listOf("/bin/cat"))
    try {
        pty.write("hello-kotgent\n".encodeToByteArray())
        val out = readUntil(pty, "hello-kotgent")
        expect("hello-kotgent" in out) { "expected the pty to echo our line, got: <$out>" }
    } finally {
        pty.close()
    }
}

private fun resizeSucceeds() = check("resize (TIOCSWINSZ) succeeds") {
    val pty = Pty.open(listOf("/bin/cat"), cols = 80, rows = 24)
    try {
        // Must not throw; ioctl(TIOCSWINSZ) returns 0.
        pty.resize(cols = 120, rows = 40)
    } finally {
        pty.close()
    }
}

private fun exitCodeIsCaptured() = check("child exit code is captured") {
    val pty = Pty.open(listOf("/bin/sh", "-c", "exit 7"))
    try {
        val code = runBlocking {
            withTimeout(5_000) {
                // The child exits on its own; that closes the slave, which EOFs the master and
                // closes the output channel. Draining it means the child is reapable.
                for (chunk in pty.output) { /* discard */ }
                pty.waitFor()
            }
        }
        expect(code == 7) { "child `sh -c 'exit 7'` should report exit code 7, got $code" }
    } finally {
        pty.close()
    }
}

private fun spawnNonexistentCommandFails() = check("spawning a nonexistent command throws") {
    // On Darwin posix_spawn resolves an absolute path synchronously and returns ENOENT, so open()
    // throws rather than silently yielding a dead child.
    val thrown = try {
        Pty.open(listOf("/nonexistent/kotgent-not-a-real-binary-xyz"))
        null
    } catch (e: PtyException) {
        e
    }
    expect(thrown != null) { "expected PtyException for a nonexistent binary, got a live Pty" }
}

/**
 * `POSIX_SPAWN_CLOEXEC_DEFAULT`: the child must get the pts on 0/1/2 and NOTHING else. This is what
 * stops a `tmux attach` (and the agent living on inside tmux) from inheriting the daemon's listening
 * socket and holding the port after the daemon dies — see `io.kotgent.sys.markOpenFdsCloexec`. Only a
 * real `posix_spawn` can show it, hence a check here rather than in the suite; the `popen` side of the
 * same guarantee is covered by `CloexecTest`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun spawnedChildInheritsOnlyTheTty() = check("spawned child inherits only the tty") {
    memScoped {
        val fds = allocArray<IntVar>(2)
        expect(pipe(fds) == 0) { "pipe() failed" }
        // F_DUPFD picks the lowest free slot at or above 30 (never closing anything, unlike dup2) and
        // clears FD_CLOEXEC — so this descriptor is inheritable unless the spawn flag closes it.
        val high = fcntl(fds[0], F_DUPFD, 30)
        expect(high >= 30) { "F_DUPFD failed (got $high)" }
        val pty = try {
            Pty.open(listOf("/bin/sh", "-c", "ls /dev/fd"))
        } catch (e: PtyException) {
            close(high); close(fds[0]); close(fds[1])
            throw e
        }
        try {
            val out = readUntil(pty, "0")
            val reported = out.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
            expect("1" in reported) { "child should report its stdout; got <$out>" }
            expect("$high" !in reported) { "fd $high leaked into the pty child; got <$out>" }
        } finally {
            pty.close()
            close(high)
            close(fds[0])
            close(fds[1])
        }
    }
}

/**
 * The tty-wiring path, which only a real `tmux attach` exercises: [Pty.open] has the child *open the
 * pts by path* as its stdio rather than inheriting a dup of our slave fd. Without that, `tmux attach`
 * fails with "open terminal failed: not a terminal" and exits immediately.
 *
 * (It does NOT make the pts the child's controlling terminal — the kernel skips `open(2)`'s implicit
 * `TIOCSCTTY` for a posix_spawn file action — which is why [resizeReachesARunningTmuxAttach] exists.)
 *
 * Uses the throwaway `-L kotgent-test` socket (never the real `kotgent` one), like the tmux tests
 * in the suite, and kills its session in teardown.
 *
 * **This check is what starts the ptycheck run's tmux server**, so its isolation is the one that
 * decides the whole file's. `-L` labels the SOCKET, not the CONFIG: without `-f /dev/null` this
 * `new-session` would parse the developer's `~/.tmux.conf`, and one with `set -g destroy-unattached
 * on` would have tmux tear the session down the instant the attach below closes — failing the
 * "should outlive the attach" assertion on a machine-specific setting the fixture never mentions.
 * `main()` runs this check first and `-f` only applies to the invocation that STARTS a server, so
 * the `-f` on every later call here is inert while this server lives; they carry it so no call site
 * has to know which one came first.
 */
private fun tmuxAttachRunsOnTheSpawnedPts() = check("tmux attach runs on the spawned pts") {
    val tmux = which("tmux") ?: skip("tmux is not on PATH")
    val socket = TEST_SOCKET
    val session = "kt-ptycheck"
    val target = "${q(tmux)} -f /dev/null -L $socket"

    sh("$target kill-session -t $session")
    val created = sh("$target new-session -d -s $session -x 80 -y 24 /bin/cat")
    expect(created == 0) { "could not create the tmux fixture session (exit=$created)" }

    try {
        val pty = Pty.open(
            command = tmuxCommand(tmux, socket, listOf("attach", "-t", session)),
            // tmux needs a usable TERM and a PATH; Pty.open defaults to an EMPTY environment.
            env = mapOf("TERM" to "xterm-256color", "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin"),
        )
        try {
            pty.write("hello-fanout\n".encodeToByteArray())
            val out = readUntil(pty, "hello-fanout", timeoutMs = 10_000)
            expect("hello-fanout" in out) { "expected the attached pane to echo our line, got: <$out>" }
        } finally {
            pty.close()
        }

        // Detaching (closing the attach) must leave the tmux session — and its `cat` — alive.
        val alive = sh("$target has-session -t $session")
        expect(alive == 0) { "the tmux session should outlive the attach (has-session exit=$alive)" }
    } finally {
        sh("$target kill-session -t $session")
    }
}

/**
 * A resize applied while `tmux attach` is **already running** must reach it.
 *
 * This is the check that would have caught "a freshly attached terminal renders at the pty's birth size
 * until you detach and re-attach": `ioctl(TIOCSWINSZ)` raises `SIGWINCH` only on the tty's FOREGROUND
 * PROCESS GROUP, and a child spawned by [Pty.open] has no controlling terminal at all (the kernel runs
 * the posix_spawn file-action open without `open(2)`'s implicit `TIOCSCTTY`), so the pts has no such
 * group and the signal reaches nobody. [Pty.resize] therefore sends `SIGWINCH` itself; without that,
 * only a size set before the client's startup `TIOCGWINSZ` ever took effect.
 *
 * Needs a real pty AND a real tmux client, so it cannot live in the suite. Throwaway `-L kotgent-test`
 * socket, session killed in teardown. `-f /dev/null` rides along for the same reason as everywhere
 * else; here it is normally inert, because [tmuxAttachRunsOnTheSpawnedPts] runs first and has already
 * started the server (see its KDoc).
 */
private fun resizeReachesARunningTmuxAttach() = check("a resize reaches a running tmux attach") {
    val tmux = which("tmux") ?: skip("tmux is not on PATH")
    val target = "${q(tmux)} -f /dev/null -L $TEST_SOCKET"
    val session = "kt-ptycheck-resize"

    sh("$target kill-session -t $session")
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
            // Wait until the client is up *and* tmux has read its 80-column size — only then is what
            // follows a genuine "while running" resize instead of one the client picks up at startup.
            val attached = waitUntil { capture("$target list-clients -t $session -F '#{client_width}'") == "80" }
            expect(attached) { "the attach client never came up at 80 columns" }

            pty.resize(cols = 143, rows = 53)

            val resized = waitUntil { capture("$target display -p -t $session '#{window_width}'") == "143" }
            expect(resized) {
                "the running tmux client ignored the resize — window is still " +
                    capture("$target display -p -t $session '#{window_width}x#{window_height}'")
            }
        } finally {
            pty.close()
        }
    } finally {
        sh("$target kill-session -t $session")
    }
}

/**
 * The one real end-to-end fan-out: a live `tmux attach` opened through the real cinterop [Pty],
 * driving real bytes to two subscribers of one [io.kotgent.pty.TerminalBridge]. Everything else
 * about the bridge (lazy open, last-detach close, resize policy) is covered in `TerminalBridgeTest`
 * through a pure-Kotlin fake; this is the leg that needs the actual cinterop, so it lives here.
 *
 * Asserts the single-upstream invariant end to end: both subscribers see the same echoed bytes, and
 * the tmux session outlives the last detach (the agent lives on in tmux).
 */
private fun terminalBridgeFansOutRealTmuxAttach() = check("TerminalBridge fans out a real tmux attach") {
    val tmux = Tmux(socket = TEST_SOCKET)
    if (!tmux.isAvailable()) skip("tmux is not installed")
    val id = "ptycheck-bridge"

    runBlocking {
        val readerScope = CoroutineScope(coroutineContext + Job())
        try {
            tmux.killSession(id)
            tmux.newSession(id = id, cwd = "/tmp", cmd = "cat", cols = 80, rows = 24)

            val bridge = terminalBridgeForSession(tmux, id, readerScope, realPtyFactory)

            // The first subscriber opens the real upstream `tmux -L kotgent-test attach -t kt-…`;
            // the second joins that same upstream. Each gets a capture-pane seed first.
            val a = bridge.subscribe()
            val b = bridge.subscribe()
            withTimeout(5_000) { a.output.receive() }
            withTimeout(5_000) { b.output.receive() }
            expect(bridge.subscriberCount() == 2) { "expected 2 subscribers, got ${bridge.subscriberCount()}" }

            // Input from one subscriber reaches `cat` in the pane; its echo fans out to BOTH.
            a.write("hello-fanout\n".encodeToByteArray())
            val fromA = receiveUntil(a, "hello-fanout")
            val fromB = receiveUntil(b, "hello-fanout")
            expect("hello-fanout" in fromA) { "subscriber A missed the echo, got: <$fromA>" }
            expect("hello-fanout" in fromB) { "subscriber B missed the echo, got: <$fromB>" }

            // Last subscriber leaving closes the attach; the tmux session (and `cat`) survives.
            a.close()
            b.close()
            expect(tmux.listPanes().any { it.session == tmux.sessionName(id) }) {
                "the tmux session should outlive the attach"
            }
        } finally {
            readerScope.cancel()
            tmux.killSession(id)
        }
    }
}

// --- harness ------------------------------------------------------------------------------------

private var total = 0
private var failed = 0
private var skipped = 0

/** Signals "not applicable in this environment" (e.g. no tmux) — reported, never counted as failed. */
private class SkipCheck(message: String) : RuntimeException(message)

private class CheckFailed(message: String) : RuntimeException(message)

private fun skip(reason: String): Nothing = throw SkipCheck(reason)

private inline fun expect(condition: Boolean, message: () -> String) {
    if (!condition) throw CheckFailed(message())
}

private fun check(name: String, body: () -> Unit) {
    total++
    try {
        body()
        println("PASS  $name")
    } catch (e: SkipCheck) {
        skipped++
        println("SKIP  $name — ${e.message}")
    } catch (e: Throwable) {
        failed++
        println("FAIL  $name — ${e::class.simpleName}: ${e.message}")
    }
}

/**
 * The throwaway tmux socket the suite uses; never the real `kotgent` one. Sessions created on it are
 * killed in teardown.
 */
private const val TEST_SOCKET = "kotgent-test"

/** Receive chunks from [pty] until [needle] appears in the accumulated output, or time out. */
private fun readUntil(pty: Pty, needle: String, timeoutMs: Long = 5_000): String = runBlocking {
    val sb = StringBuilder()
    withTimeout(timeoutMs) {
        while (needle !in sb) sb.append(pty.output.receive().decodeToString())
    }
    sb.toString()
}

/** Same, for a bridge [Subscriber]. */
private suspend fun receiveUntil(sub: Subscriber, needle: String, timeoutMs: Long = 10_000): String {
    val sb = StringBuilder()
    withTimeout(timeoutMs) {
        while (needle !in sb) sb.append(sub.output.receive().decodeToString())
    }
    return sb.toString()
}

// --- tiny shell helpers -------------------------------------------------------------------------
//
// This module cannot use the root module's ProcessRunner (an app module is not a dependency of
// another app module), and it only needs a fixture setup/teardown, so it talks to /bin/sh directly
// through stock `platform.posix` popen/pclose — the same "fork+exec happens inside libc" argument
// ProcessRunner makes.

/** POSIX single-quote quoting: every byte inside becomes literal to the shell. */
private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** Run [command] via `/bin/sh -c`, discard its output, and return its exit code. */
@OptIn(ExperimentalForeignApi::class)
private fun sh(command: String): Int {
    val fp = popen("$command >/dev/null 2>&1", "r") ?: return -1
    return decodeExitCode(pclose(fp))
}

/** Run [command] via `/bin/sh -c` and return its trimmed stdout (empty when it produced none). */
@OptIn(ExperimentalForeignApi::class)
private fun capture(command: String): String {
    val fp = popen("$command 2>/dev/null", "r") ?: return ""
    val out = StringBuilder()
    memScoped {
        val bufSize = 4096
        val buf = allocArray<ByteVar>(bufSize)
        while (true) {
            val n = fread(buf, 1UL, bufSize.toULong(), fp).toInt()
            if (n <= 0) break
            out.append(buf.readBytes(n).decodeToString())
        }
    }
    pclose(fp)
    return out.toString().trim()
}

/** Absolute path of [program] as resolved by the shell, or null when it is not installed. */
private fun which(program: String): String? =
    capture("command -v ${q(program)}").ifEmpty { null }

/**
 * Poll [condition] until it holds, or give up. For tmux state that a signal/IPC round trip settles
 * asynchronously (a client resize reaches the server, which then resizes the window), so the check
 * neither races nor hangs.
 */
private fun waitUntil(attempts: Int = 50, delayMs: Long = 100, condition: () -> Boolean): Boolean =
    runBlocking {
        repeat(attempts) {
            if (condition()) return@runBlocking true
            delay(delayMs)
        }
        condition()
    }

/** wait(2)-format status (as returned by `pclose`) -> conventional exit code. */
private fun decodeExitCode(status: Int): Int = when {
    status == -1 -> -1
    status and 0x7f == 0 -> (status shr 8) and 0xff
    else -> 128 + (status and 0x7f)
}
