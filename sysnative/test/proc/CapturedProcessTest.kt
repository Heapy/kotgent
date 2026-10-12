package io.kotgent.proc

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class CapturedProcessTest {
    // The independent alarm bounds the helper even if capture's timeout/cleanup regresses.
    private fun perl(script: String, vararg args: String) =
        listOf("/usr/bin/perl", "-e", "alarm 6; $script", "--") + args

    @Test
    fun literalArgvCompleteEnvironmentAndNonzeroExit() = runBlocking<Unit> {
        val literals = listOf("space and 'quotes'", "\$(touch /no-such-file)", "*;|<>\n", "", "кириллица")
        val result = CapturedProcess.run(
            perl("print join(qq(\\0), @ARGV); print STDERR join(q(,), sort keys %ENV); exit 17", *literals.toTypedArray()),
            environment = mapOf("CAPTURE_ONLY" to "literal=value"),
        )
        assertEquals(17, result.exitCode)
        assertEquals(literals.joinToString("\u0000"), result.stdout.decodeToString())
        assertEquals("CAPTURE_ONLY", result.stderr.decodeToString())
    }

    @Test
    fun drainsBothPipesBeyondPipeCapacity() = runBlocking<Unit> {
        val result = CapturedProcess.run(perl(
            "for (1..128) { print STDOUT 'o' x 4096; print STDERR 'e' x 4096; }",
        ), stderrLimitBytes = 1024 * 1024)
        assertEquals(0, result.exitCode)
        assertContentEquals(ByteArray(524288) { 'o'.code.toByte() }, result.stdout)
        assertContentEquals(ByteArray(524288) { 'e'.code.toByte() }, result.stderr)
    }

    @Test
    fun independentByteCapsRejectFloodsAndAllowExactBoundary() = runBlocking<Unit> {
        for (stream in CapturedStream.entries) {
            val target = if (stream == CapturedStream.STDOUT) "STDOUT" else "STDERR"
            val failure = assertFailsWith<CapturedProcessLimitException> {
                CapturedProcess.run(perl("while (1) { print $target 'x' x 8192; }"),
                    stdoutLimitBytes = 16384, stderrLimitBytes = 16384)
            }
            assertEquals(stream, failure.stream)
            assertEquals(16384, failure.limitBytes)
        }
        val exact = CapturedProcess.run(perl("print 'x' x 8192"), stdoutLimitBytes = 8192, stderrLimitBytes = 0)
        assertEquals(8192, exact.stdout.size)
        assertFailsWith<CapturedProcessLimitException> {
            CapturedProcess.run(perl("print 'x'"), stdoutLimitBytes = 0)
        }
        assertEquals(0, CapturedProcess.run(perl("exit 0"), stdoutLimitBytes = 0, stderrLimitBytes = 0).exitCode)
    }

    @Test
    fun deadlineStillAppliesAfterBothStreamsClose() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()
        assertFailsWith<CapturedProcessTimeoutException> {
            CapturedProcess.run(perl("close STDOUT; close STDERR; sleep 5"), timeout = 150.milliseconds)
        }
        assertTrue(start.elapsedNow() < 3.seconds)
    }

    @Test
    fun timeoutKillsDescendantsEvenAfterLeaderExits() = runBlocking<Unit> {
        withPidFile { path ->
            val script = $$"""
                my $kid = fork(); die $! unless defined $kid;
                if (!$kid) { alarm 6; sleep 5; exit 0; }
                open my $f, '>', $ARGV[0] or die $!; print $f "$$ $kid"; close $f;
                exit 0;
            """
            assertFailsWith<CapturedProcessTimeoutException> {
                CapturedProcess.run(perl(script, path), timeout = 500.milliseconds)
            }
            assertReapedAndStopped(readPids(path))
        }
    }

    @Test
    fun cancellationKillsGroupAndReapsBeforeReturning() = runBlocking<Unit> {
        withPidFile { path ->
            val job = async {
                CapturedProcess.run(perl(groupHelper, path), timeout = 5.seconds)
            }
            val pids = awaitPids(path)
            val start = TimeSource.Monotonic.markNow()
            job.cancelAndJoin()
            assertTrue(start.elapsedNow() < 3.seconds)
            assertTrue(job.isCancelled)
            assertReapedAndStopped(pids)
        }
    }

    @Test
    fun capFailureKillsGroupAndReapsBeforeReturning() = runBlocking<Unit> {
        withPidFile { path ->
            assertFailsWith<CapturedProcessLimitException> {
                CapturedProcess.run(perl(groupHelper.replace("sleep 5;", "while (1) { print 'x' x 8192; }"), path),
                    stdoutLimitBytes = 1024)
            }
            assertReapedAndStopped(readPids(path))
        }
    }

    @Test
    fun normalExitStillTerminatesDescendantsThatClosedTheirPipes() = runBlocking<Unit> {
        withPidFile { path ->
            val script = groupHelper.replace(
                "if (!\$kid) { alarm 6;", "if (!\$kid) { close STDOUT; close STDERR; alarm 6;",
            ).replace("sleep 5;", "exit 23;")
            val result = CapturedProcess.run(perl(script, path))
            assertEquals(23, result.exitCode)
            assertReapedAndStopped(readPids(path))
        }
    }

    @Test
    fun signalExitAndExecFormatFailureAreReported() = runBlocking<Unit> {
        val result = CapturedProcess.run(perl("kill 9, $$"))
        assertEquals(128 + SIGKILL, result.exitCode)
        withPidFile { path ->
            assertEquals(0, chmod(path, 448.convert()))
            val failure = assertFailsWith<CapturedProcessException> { CapturedProcess.run(listOf(path)) }
            assertEquals(ENOEXEC, failure.errorCode)
        }
    }

    @Test
    fun missingExecutableAndRepeatedFailuresDoNotLeakDescriptors() = runBlocking<Unit> {
        val before = descriptorCount()
        repeat(10) {
            val failure = assertFailsWith<CapturedProcessException> {
                CapturedProcess.run(listOf("/no-such-kotgent-capture-executable"))
            }
            assertEquals(ENOENT, failure.errorCode)
            assertFailsWith<CapturedProcessLimitException> {
                CapturedProcess.run(perl("print 'xx'"), stdoutLimitBytes = 1)
            }
        }
        assertEquals(0, CapturedProcess.run(listOf("/usr/bin/true")).exitCode)
        assertEquals(before, descriptorCount())
    }

    @Test
    fun stdinIsEofAndUnrelatedNonCloexecDescriptorIsNotInherited() = runBlocking<Unit> {
        val original = open("/dev/null", O_RDONLY)
        assertTrue(original >= 0)
        val fd = fcntl(original, F_DUPFD, 100)
        close(original)
        assertTrue(fd >= 100)
        try {
            assertEquals(0, fcntl(fd, F_GETFD) and FD_CLOEXEC)
            val result = CapturedProcess.run(perl(
                $$"""use Fcntl; my $fd = shift; open my $f, '<&=', $fd and die 'inherited';
                    my $n = sysread(STDIN, my $b, 1); die 'stdin' unless defined($n) && $n == 0;
                    print 'isolated';""", fd.toString(),
            ))
            assertEquals(0, result.exitCode, result.stderr.decodeToString())
            assertEquals("isolated", result.stdout.decodeToString())
        } finally { close(fd) }
    }

    @Test
    fun rejectsNulAndMalformedEnvironmentBeforeSpawn() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { CapturedProcess.run(listOf("/bin/echo", "x\u0000y")) }
        assertFailsWith<IllegalArgumentException> { CapturedProcess.run(listOf("/bin/echo"), mapOf("A=B" to "x")) }
        assertFailsWith<IllegalArgumentException> { CapturedProcess.run(listOf("/bin/echo"), mapOf("A" to "x\u0000y")) }
    }

    @Test
    fun repeatedParallelCapturesKeepOutputsSeparateAndDoNotLeakDescriptors() = runBlocking<Unit> {
        // Warm runtime-owned workers before comparing descriptor ownership.
        val _ = CapturedProcess.run(listOf("/usr/bin/true"))
        val before = descriptorCount()
        repeat(3) {
            val results = (0..15).map { index ->
                async {
                    val result = CapturedProcess.run(perl("print $index; print STDERR 'e$index'"))
                    assertEquals(0, result.exitCode)
                    assertEquals(index.toString(), result.stdout.decodeToString())
                    assertEquals("e$index", result.stderr.decodeToString())
                }
            }
            val _ = results.awaitAll()
        }
        assertEquals(before, descriptorCount())
    }

    @Test
    fun saturatedCaptureQueueTimesOutAndCancelsWithoutSpawning() = runBlocking<Unit> {
        withPidFiles(5) { paths ->
            val jobs = paths.take(4).map { path ->
                async { CapturedProcess.run(perl(groupHelper, path), timeout = 5.seconds) }
            }
            try {
                val running = paths.take(4).map { awaitPids(it) }
                val marker = paths.last()
                val argv = perl("open my \$f, '>', \$ARGV[0] or die \$!; print \$f 'spawned'; close \$f", marker)
                withTimeout(2.seconds) {
                    assertFailsWith<CapturedProcessTimeoutException> {
                        CapturedProcess.run(argv, timeout = 100.milliseconds)
                    }
                }
                val queued = async(start = CoroutineStart.UNDISPATCHED) { CapturedProcess.run(argv) }
                withTimeout(2.seconds) { queued.cancelAndJoin() }
                assertTrue(queued.isCancelled)
                assertEquals(0L, fileSize(marker), "a timed out or cancelled queued command must never spawn")
                jobs.forEach { it.cancelAndJoin() }
                running.forEach { assertReapedAndStopped(it) }
                assertEquals(0L, fileSize(marker), "released slots must not launch cancelled queue entries")
                // Released permits must remain usable after both queue exits.
                assertEquals(0, CapturedProcess.run(listOf("/usr/bin/true")).exitCode)
            } finally { jobs.forEach { it.cancelAndJoin() } }
        }
    }

    private suspend fun withPidFiles(count: Int, paths: List<String> = emptyList(), block: suspend (List<String>) -> Unit) {
        if (count == 0) block(paths)
        else withPidFile { path -> withPidFiles(count - 1, paths + path, block) }
    }

    private fun fileSize(path: String): Long = memScoped {
        val info = alloc<stat>()
        check(stat(path, info.ptr) == 0)
        info.st_size
    }

    private val groupHelper = $$"""
        my $kid = fork(); die $! unless defined $kid;
        if (!$kid) { alarm 6; select undef, undef, undef, 5; exit 0; }
        open my $f, '>', $ARGV[0] or die $!; print $f "$$ $kid"; close $f;
        sleep 5;
    """

    private suspend fun withPidFile(block: suspend (String) -> Unit) {
        val path = memScoped {
            val template = "/tmp/kotgent-capture-XXXXXX".cstr.ptr
            val fd = mkstemp(template)
            check(fd >= 0)
            close(fd)
            template.toKString()
        }
        var completed = false
        try { block(path); completed = true } finally {
            // A failing assertion must not strand a helper. Only this fixture's recorded pids are used.
            if (!completed) for (pid in readPids(path)) kill(pid, SIGKILL)
            unlink(path)
        }
    }

    private fun readPids(path: String): List<Int> = memScoped {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return@memScoped emptyList()
        try {
            val buf = allocArray<ByteVar>(128)
            val n = read(fd, buf, 128.convert())
            if (n <= 0) emptyList() else buf.readBytes(n.toInt()).decodeToString().split(' ').mapNotNull { it.toIntOrNull() }
        } finally { close(fd) }
    }

    private suspend fun awaitPids(path: String): List<Int> = withTimeout(3.seconds) {
        while (true) {
            val pids = readPids(path)
            if (pids.size == 2) return@withTimeout pids
            delay(10)
        }
        error("unreachable")
    }

    private suspend fun assertReapedAndStopped(pids: List<Int>) {
        assertEquals(2, pids.size)
        memScoped {
            val status = alloc<IntVar>()
            assertEquals(-1, waitpid(pids[0], status.ptr, WNOHANG))
            assertEquals(ECHILD, errno, "capture must reap its direct child")
        }
        withTimeout(3.seconds) {
            while (true) {
                val result = CapturedProcess.run(listOf("/bin/ps", "-o", "stat=", "-p", pids[1].toString()))
                val state = result.stdout.decodeToString().trim()
                // An orphan zombie awaits the platform's reaper; it cannot execute or retain pipes.
                if (state.isEmpty() || state.startsWith("Z")) break
                delay(10)
            }
        }
    }

    private fun descriptorCount(): Int = (0..1023).count { fcntl(it, F_GETFD) >= 0 }
}
