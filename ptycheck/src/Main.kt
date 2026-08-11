package io.kotgent.ptycheck

import io.kotgent.cinterop.pty.kotgent_ptsname
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
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import platform.posix.F_DUPFD
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.SIGKILL
import platform.posix.close
import platform.posix.fcntl
import platform.posix.fread
import platform.posix.kill
import platform.posix.pclose
import platform.posix.pipe
import platform.posix.popen
import platform.posix.open as posixOpen

// Toolchain 0.11 does not link this cinterop into test binaries (KT-78062), so PtyTest executes this
// main binary. Its PASS/FAIL/SKIP lines, final SUMMARY, and bounded reads are the runner protocol.
fun main() {
    ptyRoundTripThroughCat()
    resizeSucceeds()
    exitCodeIsCaptured()
    spawnNonexistentCommandFails()
    spawnedChildInheritsOnlyTheTty()
    prepareCloseUnblocksAFullMasterWrite()
    closeStopsTheReaderBeforeReleasingTheMasterDescriptor()
    concurrentCloseRunsTeardownExactlyOnce()
    tmuxAttachRunsOnTheSpawnedPts()
    resizeReachesARunningTmuxAttach()
    terminalBridgeFansOutRealTmuxAttach()

    println("SUMMARY total=$total failed=$failed skipped=$skipped")
    if (failed > 0) exitProcess(1)
}


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
                for (chunk in pty.output) {  }
                pty.waitFor()
            }
        }
        expect(code == 7) { "child `sh -c 'exit 7'` should report exit code 7, got $code" }
    } finally {
        pty.close()
    }
}

private fun spawnNonexistentCommandFails() = check("spawning a nonexistent command throws") {
    val thrown = try {
        Pty.open(listOf("/nonexistent/kotgent-not-a-real-binary-xyz"))
        null
    } catch (e: PtyException) {
        e
    }
    expect(thrown != null) { "expected PtyException for a nonexistent binary, got a live Pty" }
}

@OptIn(ExperimentalForeignApi::class)
/** Verifies POSIX_SPAWN_CLOEXEC_DEFAULT so daemon sockets cannot leak into attached agents. */
private fun spawnedChildInheritsOnlyTheTty() = check("spawned child inherits only the tty") {
    memScoped {
        val fds = allocArray<IntVar>(2)
        expect(pipe(fds) == 0) { "pipe() failed" }
        val high = fcntl(fds[0], F_DUPFD, 30) // Creates an inheritable positive-control descriptor.
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

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
// `sleep` never reads stdin, so the large raw-mode write blocks until prepareClose closes the slave.
private fun prepareCloseUnblocksAFullMasterWrite() = check("prepareClose unblocks a full master write") {
    val pty = Pty.open(
        listOf("/bin/sh", "-c", "/bin/stty -icanon -echo; echo READY; exec /bin/sleep 30"),
    )
    val payload = ByteArray(FULL_PTY_WRITE_BYTES) { 'x'.code.toByte() }
    val writerContext = newSingleThreadContext("ptycheck-full-write")
    val prepareContext = newSingleThreadContext("ptycheck-prepare-close")
    val writerScope = CoroutineScope(writerContext + Job())
    val prepareScope = CoroutineScope(prepareContext + Job())
    val writeEntered = CompletableDeferred<Unit>()
    var writing: Deferred<Result<Unit>>? = null
    var preparing: Deferred<Unit>? = null
    var childMayBeAlive = true

    try {
        readUntil(pty, "READY")
        val writeTask = writerScope.async {
            writeEntered.complete(Unit)
            runCatching { pty.write(payload) }
        }
        writing = writeTask
        val completedThroughPrepare = runBlocking {
            withTimeout(5_000) { writeEntered.await() }
            delay(200)
            expect(!writeTask.isCompleted) {
                "the positive control did not fill the tty input queue; the large write returned early"
            }

            val prepare = prepareScope.async { pty.prepareClose() }
            preparing = prepare
            val completed = withTimeoutOrNull(5_000) {
                prepare.await()
                writeTask.await()
                true
            } ?: false

            if (!completed) {
                // Unblock a broken implementation so the fixture can report failure instead of hanging.
                kill(-pty.pid, SIGKILL)
                withTimeout(5_000) {
                    prepare.await()
                    writeTask.await()
                }
            }
            childMayBeAlive = false
            completed
        }
        expect(completedThroughPrepare) {
            "prepareClose did not make the blocked master write return within 5 seconds"
        }
    } finally {
        if (childMayBeAlive) kill(-pty.pid, SIGKILL)
        runBlocking {
            withTimeout(5_000) {
                writing?.join()
                preparing?.join()
            }
        }
        pty.close()
        writerScope.cancel()
        prepareScope.cancel()
        writerContext.close()
        prepareContext.close()
    }
}

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
// Holding a duplicate slave open prevents EOF and isolates the reader-wakeup ordering under test.
private fun closeStopsTheReaderBeforeReleasingTheMasterDescriptor() =
    check("close stops the reader before releasing the master descriptor") {
        val pty = Pty.open(listOf("/bin/cat"))
        val closeContext = newSingleThreadContext("ptycheck-reader-close")
        val closeScope = CoroutineScope(closeContext + Job())
        var heldSlaveFd = -1
        var closing: Deferred<Int>? = null

        try {
            heldSlaveFd = openSlave(pty.masterFd)
            pty.prepareClose()
            val expectedExitCode = pty.waitFor()
            expect(expectedExitCode >= 0) { "prepareClose should record the child's exit code" }
            runBlocking { delay(200) }
            expect(!pty.output.isClosedForReceive) {
                "the held slave did not keep the reader blocked for the teardown check"
            }

            val closeTask = closeScope.async { pty.close() }
            closing = closeTask
            var closeExitCode: Int? = null
            val stoppedBeforeRelease = runBlocking {
                val completed = withTimeoutOrNull(2_000) {
                    closeExitCode = closeTask.await()
                    true
                } ?: false
                if (!completed) {
                    close(heldSlaveFd)
                    heldSlaveFd = -1
                    closeExitCode = withTimeout(5_000) { closeTask.await() }
                }
                completed
            }
            expect(stoppedBeforeRelease) {
                "close could not stop and join its reader while the master descriptor remained owned"
            }
            expect(closeExitCode == expectedExitCode) {
                "close returned $closeExitCode instead of the recorded child exit code $expectedExitCode"
            }
            expect(pty.readerCompletedBeforeMasterFdRelease) {
                "the master descriptor was released before close completed and joined its reader"
            }
        } finally {
            if (heldSlaveFd >= 0) close(heldSlaveFd)
            runBlocking { withTimeout(5_000) { closing?.join() } }
            pty.close()
            closeScope.cancel()
            closeContext.close()
        }
    }

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
// Ignored SIGTERM keeps two close callers overlapped through the bounded reap path.
private fun concurrentCloseRunsTeardownExactlyOnce() = check("concurrent close runs teardown exactly once") {
    val pty = Pty.open(
        listOf("/bin/sh", "-c", "trap '' TERM; printf 'READY\\n'; exec /bin/sleep 60"),
    )
    val firstContext = newSingleThreadContext("ptycheck-close-first")
    val secondContext = newSingleThreadContext("ptycheck-close-second")
    val firstScope = CoroutineScope(firstContext + Job())
    val secondScope = CoroutineScope(secondContext + Job())
    val start = CompletableDeferred<Unit>()
    val firstReady = CompletableDeferred<Unit>()
    val secondReady = CompletableDeferred<Unit>()
    var firstClose: Deferred<Int>? = null
    var secondClose: Deferred<Int>? = null
    var childMayBeAlive = true
    var closeCompleted = false

    try {
        readUntil(pty, "READY", timeoutMs = 4_000)
        val firstTask = firstScope.async {
            firstReady.complete(Unit)
            start.await()
            pty.close()
        }
        val secondTask = secondScope.async {
            secondReady.complete(Unit)
            start.await()
            pty.close()
        }
        firstClose = firstTask
        secondClose = secondTask

        val results = runBlocking {
            val bothReady = withTimeoutOrNull(5_000) {
                firstReady.await()
                secondReady.await()
                true
            } ?: false
            expect(bothReady) { "both dedicated close workers did not reach the start gate" }
            start.complete(Unit)
            delay(200)
            expect(!firstTask.isCompleted && !secondTask.isCompleted) {
                "the ignored-SIGTERM positive control did not keep both close callers overlapped"
            }
            withTimeoutOrNull(CONCURRENT_CLOSE_TIMEOUT_MS) { firstTask.await() to secondTask.await() }
        }
        val completedResults = results
            ?: throw AssertionError(
                "the two close callers did not finish the one bounded teardown within " +
                    "${CONCURRENT_CLOSE_TIMEOUT_MS / 1_000} seconds; before cleanup: " +
                    "first(active=${firstTask.isActive}, completed=${firstTask.isCompleted}, " +
                    "cancelled=${firstTask.isCancelled}), " +
                    "second(active=${secondTask.isActive}, completed=${secondTask.isCompleted}, " +
                    "cancelled=${secondTask.isCancelled})",
            )
        childMayBeAlive = false
        val expectedExitCode = 128 + SIGKILL
        expect(completedResults.first == expectedExitCode && completedResults.second == expectedExitCode) {
            "both close callers should return the one child exit code $expectedExitCode, got $completedResults"
        }
        expect(pty.close() == expectedExitCode) {
            "a later sequential close should return the same child exit code $expectedExitCode"
        }
        closeCompleted = true
    } finally {
        try {
            if (childMayBeAlive) kill(-pty.pid, SIGKILL)
            val workersFinished = runBlocking {
                withTimeoutOrNull(5_000) {
                    firstClose?.join()
                    secondClose?.join()
                    true
                } ?: false
            }
            if (!closeCompleted && (workersFinished || firstClose == null && secondClose == null)) {
                runCatching { pty.close() }
            }
        } finally {
            firstScope.cancel()
            secondScope.cancel()
            firstContext.close()
            secondContext.close()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun openSlave(masterFd: Int): Int = memScoped {
    val path = allocArray<ByteVar>(PTY_PATH_CAP)
    expect(kotgent_ptsname(masterFd, path, PTY_PATH_CAP.convert()) == 0) {
        "could not resolve the slave path for master fd $masterFd"
    }
    val fd = posixOpen(path.toKString(), O_RDWR or O_NOCTTY)
    expect(fd >= 0) { "could not open the slave path for master fd $masterFd" }
    fd
}

// `-f /dev/null` is load-bearing: developer tmux settings must not alter the fixture server.
private fun tmuxAttachRunsOnTheSpawnedPts() = check("tmux attach runs on the spawned pts") {
    val tmux = which("tmux") ?: skip("tmux is not on PATH")
    val socket = TEST_SOCKET
    val session = "kt-ptycheck"
    val target = "${q(tmux)} -f /dev/null -L $socket"

    sh("$target kill-session -t $session")
    val created = sh("$target new-session -d -s $session -x 80 -y 24 /bin/cat")
    expect(created == 0) { "could not create the tmux fixture session (exit=$created)" }

    try {
        val destroyUnattached = capture("$target show-options -gv destroy-unattached")
        expect(destroyUnattached == "off") {
            "an isolated server must report destroy-unattached off, got <$destroyUnattached> — is -f /dev/null still there?"
        }

        val pty = Pty.open(
            command = tmuxCommand(tmux, socket, listOf("attach", "-t", session)),
            env = mapOf("TERM" to "xterm-256color", "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin"),
        )
        try {
            pty.write("hello-fanout\n".encodeToByteArray())
            val out = readUntil(pty, "hello-fanout", timeoutMs = 10_000)
            expect("hello-fanout" in out) { "expected the attached pane to echo our line, got: <$out>" }
        } finally {
            pty.close()
        }

        val alive = sh("$target has-session -t $session")
        expect(alive == 0) { "the tmux session should outlive the attach (has-session exit=$alive)" }
    } finally {
        sh("$target kill-session -t $session")
    }
}

// posix_spawn does not give the child a controlling tty, so this catches a missing explicit SIGWINCH.
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

/** The cinterop-backed proof that two subscribers share one upstream attach and tmux survives detach. */
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

            val a = bridge.subscribe()
            val b = bridge.subscribe()
            withTimeout(5_000) { a.output.receive() }
            withTimeout(5_000) { b.output.receive() }
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
            tmux.killSession(id)
        }
    }
}


private var total = 0
private var failed = 0
private var skipped = 0

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

private const val TEST_SOCKET = "kotgent-test" // Never use the developer's real kotgent tmux socket.

// Large enough to fill Darwin's raw tty input queue when the slave never reads.
private const val FULL_PTY_WRITE_BYTES = 16 * 1_048_576

private const val PTY_PATH_CAP = 1024

private const val CONCURRENT_CLOSE_TIMEOUT_MS = 6_000L

private fun readUntil(pty: Pty, needle: String, timeoutMs: Long = 5_000): String = runBlocking {
    val sb = StringBuilder()
    withTimeout(timeoutMs) {
        while (needle !in sb) sb.append(pty.output.receive().decodeToString())
    }
    sb.toString()
}

private suspend fun receiveUntil(sub: Subscriber, needle: String, timeoutMs: Long = 10_000): String {
    val sb = StringBuilder()
    withTimeout(timeoutMs) {
        while (needle !in sb) sb.append(sub.output.receive().decodeToString())
    }
    return sb.toString()
}


/** POSIX single-quote escaping for the fixture's `/bin/sh -c` snippets. */
private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

@OptIn(ExperimentalForeignApi::class)
private fun sh(command: String): Int {
    val fp = popen("$command >/dev/null 2>&1", "r") ?: return -1
    return decodeExitCode(pclose(fp))
}

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

private fun which(program: String): String? =
    capture("command -v ${q(program)}").ifEmpty { null }

// tmux state settles asynchronously after the client/server IPC round trip.
private fun waitUntil(attempts: Int = 50, delayMs: Long = 100, condition: () -> Boolean): Boolean =
    runBlocking {
        repeat(attempts) {
            if (condition()) return@runBlocking true
            delay(delayMs)
        }
        condition()
    }

private fun decodeExitCode(status: Int): Int = when {
    status == -1 -> -1
    status and 0x7f == 0 -> (status shr 8) and 0xff
    else -> 128 + (status and 0x7f)
}
