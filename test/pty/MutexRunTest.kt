package io.kotgent.pty

import io.kotgent.cli.MutexRun
import io.kotgent.cli.runMutexRunCommand
import io.kotgent.proc.ChildProcessException
import io.kotgent.proc.ForwardingChild
import io.kotgent.transport.MutexAcquireResponse
import io.kotgent.transport.MutexReleaseResponse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.SIGINT
import platform.posix.SIG_IGN
import platform.posix.close
import platform.posix.pipe
import platform.posix.signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class MutexRunTest {

    @Test
    fun theChildsExitStatusIsReturnedAndItsNameIsLookedUpOnThePath() {
        assertEquals(7, ForwardingChild.run(listOf("/bin/sh", "-c", "exit 7")))
        assertEquals(0, ForwardingChild.run(listOf("true")))
        assertEquals(128 + 9, ForwardingChild.run(listOf("sh", "-c", "kill -KILL $$")))
    }

    @Test
    fun aMissingProgramIsReportedRatherThanRun() {
        val _ = assertFailsWith<ChildProcessException> { ForwardingChild.run(listOf("kotgent-no-such-program-xyz")) }
    }

    @Test
    fun theChildInheritsStdioButNoOtherDescriptor() = memScoped {
        val fds = allocArray<IntVar>(2)
        assertEquals(0, pipe(fds))
        try {
            val leaked = fds[1]
            assertEquals(0, ForwardingChild.run(listOf("/bin/sh", "-c", "test -e /dev/fd/1 && test -e /dev/fd/2")))
            assertEquals(1, ForwardingChild.run(listOf("/bin/sh", "-c", "test -e /dev/fd/$leaked")))
        } finally {
            val _ = close(fds[0])
            val _ = close(fds[1])
        }
    }

    @Test
    fun aSignalToTheRunnerReachesTheChildAndTheMutexIsStillReleased() = runBlocking {
        withTimeout(20.seconds) {
            val released = mutableListOf<String>()
            // The child signals its parent, this test process, which must forward the signal rather than die.
            val status = runMutexRunCommand(
                MutexRun("kotlin-build", listOf("/bin/sh", "-c", "kill -TERM \$PPID; exec sleep 10"), null),
                acquire = { MutexAcquireResponse("acquired", "kotlin-build", token = "tok1") },
                release = { token -> released += token; MutexReleaseResponse(true, "kotlin-build") },
                execute = ForwardingChild::run,
                stderr = {},
            )
            assertEquals(128 + 15, status)
            assertEquals(listOf("tok1"), released)
        }
    }

    @Test
    fun aSignalTheCallerIgnoresStaysIgnoredForTheChild() {
        val previous = signal(SIGINT, SIG_IGN)
        try {
            assertEquals(0, ForwardingChild.run(listOf("/bin/sh", "-c", "kill -INT \$PPID; kill -INT $$; exit 0")))
        } finally {
            val _ = signal(SIGINT, previous)
        }
    }
}
