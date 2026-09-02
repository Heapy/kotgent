package io.kotgent.pty

import io.kotgent.cinterop.pty.kotgent_ptsname
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
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
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import platform.posix.F_DUPFD
import platform.posix.O_NOCTTY
import platform.posix.O_RDWR
import platform.posix.SIGKILL
import platform.posix.close
import platform.posix.fcntl
import platform.posix.kill
import platform.posix.pipe
import platform.posix.open as posixOpen

class PtyTest {

    @Test
    fun catEchoesARoundTripLine() {
        val pty = Pty.open(listOf("/bin/cat"))
        try {
            pty.write("hello-kotgent\n".encodeToByteArray())
            val out = readUntil(pty, "hello-kotgent")
            expect("hello-kotgent" in out) { "expected the pty to echo our line, got: <$out>" }
        } finally {
            val _ = pty.close()
        }
    }

    @Test
    fun resizeSucceeds() {
        val pty = Pty.open(listOf("/bin/cat"), cols = 80, rows = 24)
        try {
            pty.resize(cols = 120, rows = 40)
        } finally {
            val _ = pty.close()
        }
    }

    @Test
    fun childExitCodeIsCaptured() {
        val pty = Pty.open(listOf("/bin/sh", "-c", "exit 7"))
        try {
            val code = runBlocking {
                withTimeout(5.seconds) {
                    for (chunk in pty.output) {  }
                    pty.waitFor()
                }
            }
            expect(code == 7) { "child `sh -c 'exit 7'` should report exit code 7, got $code" }
        } finally {
            val _ = pty.close()
        }
    }

    @Test
    fun spawningANonexistentCommandThrows() {
        val thrown = try {
            val _ = Pty.open(listOf("/nonexistent/kotgent-not-a-real-binary-xyz"))
            null
        } catch (e: PtyException) {
            e
        }
        expect(thrown != null) { "expected PtyException for a nonexistent binary, got a live Pty" }
    }

    /** Verifies POSIX_SPAWN_CLOEXEC_DEFAULT so daemon sockets cannot leak into attached agents. */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun spawnedChildInheritsOnlyTheTty() {
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
                val _ = pty.close()
                close(high)
                close(fds[0])
                close(fds[1])
            }
        }
    }

    // `sleep` never reads stdin, so the large raw-mode write blocks until prepareClose closes the slave.
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun prepareCloseUnblocksAFullMasterWrite() = withCloseTrace {
        val pty = Pty.open(
            listOf("/bin/sh", "-c", "/bin/stty -icanon -echo; echo READY; exec /bin/sleep 30"),
        )
        val payload = ByteArray(FULL_PTY_WRITE_BYTES) { 'x'.code.toByte() }
        val writerContext = newSingleThreadContext("ptytest-full-write")
        val prepareContext = newSingleThreadContext("ptytest-prepare-close")
        val writerScope = CoroutineScope(writerContext + Job())
        val prepareScope = CoroutineScope(prepareContext + Job())
        val writeEntered = CompletableDeferred<Unit>()
        var writing: Deferred<Result<Unit>>? = null
        var preparing: Deferred<Unit>? = null
        var childMayBeAlive = true

        try {
            val _ = readUntil(pty, "READY")
            val writeTask = writerScope.async {
                writeEntered.complete(Unit)
                runCatching { pty.write(payload) }
            }
            writing = writeTask
            val completedThroughPrepare = runBlocking {
                withTimeout(5.seconds) { writeEntered.await() }
                delay(200.milliseconds)
                expect(!writeTask.isCompleted) {
                    "the positive control did not fill the tty input queue; the large write returned early"
                }

                val prepare = prepareScope.async { pty.prepareClose() }
                preparing = prepare
                val completed = withTimeoutOrNull(5.seconds) {
                    prepare.await()
                    writeTask.await()
                    true
                } ?: false

                if (!completed) {
                    // Unblock a broken implementation so the test can report failure instead of hanging.
                    kill(-pty.pid, SIGKILL)
                    withTimeout(5.seconds) {
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
                withTimeout(5.seconds) {
                    writing?.join()
                    preparing?.join()
                }
            }
            val _ = pty.close()
            writerScope.cancel()
            prepareScope.cancel()
            writerContext.close()
            prepareContext.close()
        }
    }

    // Holding a duplicate slave open prevents EOF and isolates the reader-wakeup ordering under test.
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun closeStopsTheReaderBeforeReleasingTheMasterDescriptor() = withCloseTrace {
        val pty = Pty.open(listOf("/bin/cat"))
        val closeContext = newSingleThreadContext("ptytest-reader-close")
        val closeScope = CoroutineScope(closeContext + Job())
        var heldSlaveFd = -1
        var closing: Deferred<Int>? = null

        try {
            heldSlaveFd = openSlave(pty.masterFd)
            pty.prepareClose()
            val expectedExitCode = pty.waitFor()
            expect(expectedExitCode >= 0) { "prepareClose should record the child's exit code" }
            runBlocking { delay(200.milliseconds) }
            expect(!pty.output.isClosedForReceive) {
                "the held slave did not keep the reader blocked for the teardown check"
            }

            val closeTask = closeScope.async { pty.close() }
            closing = closeTask
            var closeExitCode: Int? = null
            val stoppedBeforeRelease = runBlocking {
                val completed = withTimeoutOrNull(2.seconds) {
                    closeExitCode = closeTask.await()
                    true
                } ?: false
                if (!completed) {
                    close(heldSlaveFd)
                    heldSlaveFd = -1
                    closeExitCode = withTimeout(5.seconds) { closeTask.await() }
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
            runBlocking { withTimeout(5.seconds) { closing?.join() } }
            val _ = pty.close()
            closeScope.cancel()
            closeContext.close()
        }
    }

    // Ignored SIGTERM keeps two close callers overlapped through the bounded reap path.
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentCloseRunsTeardownExactlyOnce() = withCloseTrace {
        val pty = Pty.open(
            listOf("/bin/sh", "-c", "trap '' TERM; printf 'READY\\n'; exec /bin/sleep 60"),
        )
        val firstContext = newSingleThreadContext("ptytest-close-first")
        val secondContext = newSingleThreadContext("ptytest-close-second")
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
            val _ = readUntil(pty, "READY", timeoutMs = 4_000)
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
                val bothReady = withTimeoutOrNull(5.seconds) {
                    firstReady.await()
                    secondReady.await()
                    true
                } ?: false
                expect(bothReady) { "both dedicated close workers did not reach the start gate" }
                start.complete(Unit)
                delay(200.milliseconds)
                expect(!firstTask.isCompleted && !secondTask.isCompleted) {
                    "the ignored-SIGTERM positive control did not keep both close callers overlapped"
                }
                withTimeoutOrNull(CONCURRENT_CLOSE_TIMEOUT_MS.milliseconds) { firstTask.await() to secondTask.await() }
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
                    withTimeoutOrNull(5.seconds) {
                        firstClose?.join()
                        secondClose?.join()
                        true
                    } ?: false
                }
                if (!closeCompleted && (workersFinished || firstClose == null && secondClose == null)) {
                    val _ = runCatching { pty.close() }
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

    private companion object {
        // Large enough to fill Darwin's raw tty input queue when the slave never reads.
        const val FULL_PTY_WRITE_BYTES = 16 * 1_048_576

        const val PTY_PATH_CAP = 1024

        const val CONCURRENT_CLOSE_TIMEOUT_MS = 6_000L
    }
}
