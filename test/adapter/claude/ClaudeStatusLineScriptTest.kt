package io.kotgent.adapter.claude

import io.kotgent.tmux.ProcessResult
import io.kotgent.tmux.ProcessRunner
import io.kotgent.transport.readFileBytesOrNull
import io.kotgent.transport.writePrivateFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClaudeStatusLineScriptTest {
    private val micros = 1_800_000_000_000_100L
    private val firstPayload = """{"session_id":"session-a","rate_limits":{"five_hour":{"used_percentage":20,"resets_at":1800003600},"seven_day":{"used_percentage":70,"resets_at":1800604800}}}"""
    private val changedPayload = firstPayload.replace("\"used_percentage\":70", "\"used_percentage\":5")

    private class Fixture {
        val directory: String = ProcessRunner.run(listOf("/usr/bin/mktemp", "-d", "/tmp/kotgent-status-script-XXXXXX"))
            .also { assertEquals(0, it.exitCode, it.stderr) }.stdout.trim()
        val header = "$directory/a header's file"
        val stateDirectory = "$directory/state"
        val curl = "$directory/curl-stub"
        private var invocation = 0

        init {
            writePrivateFile(header, "X-Kotgent-Hook-Token: fixture-secret\n".encodeToByteArray())
            writePrivateFile(curl, $$"""
                #!/bin/sh
                root=$${ProcessRunner.shQuote(directory)}
                printf '%s\n' "$@" > "$root/curl-args"
                body=$(cat)
                : > "$root/curl-entered"
                if [ -f "$root/stall" ]; then
                  attempts=0
                  while [ ! -f "$root/release" ] && [ "$attempts" -lt 500 ]; do
                    /bin/sleep 0.01
                    attempts=$((attempts + 1))
                  done
                fi
                printf '%s\n' "$body" >> "$root/requests"
                : > "$root/curl-finished"
            """.trimIndent().encodeToByteArray())
            assertEquals(0, ProcessRunner.run(listOf("/bin/chmod", "700", curl)).exitCode)
        }

        fun run(
            payload: String,
            atMicros: Long,
            operator: String? = null,
            pane: String = "%42",
            tmux: String = "/tmp/fixture-socket,123,0",
            state: String = stateDirectory,
            curlCommand: String = curl,
            atTicks: Long = atMicros,
            boot: String = "fixture-boot",
            perlOptions: String = "",
        ): ProcessResult {
            val index = invocation++
            val input = "$directory/input-$index"
            writePrivateFile(input, payload.encodeToByteArray())
            val command = ClaudeStatusLineScript.command(
                port = 7419,
                headerFilePath = header,
                operatorStatusLineCommand = operator,
                stateDirectory = state,
                curlPath = curlCommand,
                renderMicros = atMicros,
                renderTicks = atTicks,
                bootId = boot,
            )
            return ProcessRunner.run(listOf(
                "/usr/bin/perl", "-e", """
                    use POSIX qw(setsid getpid);
                    setsid() >= 0 or die "setsid failed";
                    my ${'$'}path = shift;
                    open my ${'$'}record, '>', ${'$'}path or die "group record failed";
                    print {${'$'}record} getpid();
                    close ${'$'}record;
                    exec @ARGV;
                    die "exec failed";
                """.trimIndent(), "$directory/process-group-$index",
                "/usr/bin/env", "TMUX_PANE=$pane", "TMUX=$tmux", "PERL5LIB=$directory", "PERL5OPT=$perlOptions", "/bin/sh", "-c",
                "$command < ${ProcessRunner.shQuote(input)}",
            ))
        }

        fun touch(name: String) = writePrivateFile("$directory/$name", ByteArray(0))
        fun exists(name: String) = readFileBytesOrNull("$directory/$name") != null
        fun text(name: String) = readFileBytesOrNull("$directory/$name")?.decodeToString().orEmpty()
        fun requests(): List<JsonObject> = text("requests").lineSequence().filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() }.toList()

        suspend fun awaitRequests(count: Int): List<JsonObject> = withTimeout(5.seconds) {
            while (true) {
                val requests = requests()
                if (requests.size >= count) return@withTimeout requests
                delay(10.milliseconds)
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }

        suspend fun awaitFile(name: String) = withTimeout(5.seconds) {
            while (!exists(name)) delay(10.milliseconds)
        }

        fun removeStateFiles() {
            val result = ProcessRunner.run(listOf("/bin/sh", "-c", "rm -f ${ProcessRunner.shQuote(stateDirectory)}/*.json"))
            assertEquals(0, result.exitCode, result.stderr)
        }

        fun state(): JsonObject {
            val result = ProcessRunner.run(listOf("/bin/sh", "-c", "cat ${ProcessRunner.shQuote(stateDirectory)}/*.json"))
            assertEquals(0, result.exitCode, result.stderr)
            return Json.parseToJsonElement(result.stdout).jsonObject
        }

        suspend fun awaitRender(renderTicks: Long) = withTimeout(5.seconds) {
            while (true) {
                val result = ProcessRunner.run(listOf("/bin/sh", "-c", "cat ${ProcessRunner.shQuote(stateDirectory)}/*.json 2>/dev/null"))
                val state = runCatching { Json.parseToJsonElement(result.stdout).jsonObject }.getOrNull()
                if (state?.get("render_ticks")?.jsonPrimitive?.long == renderTicks) return@withTimeout
                delay(10.milliseconds)
            }
        }

        fun ageFile(name: String, seconds: Long) {
            val result = ProcessRunner.run(listOf(
                "/usr/bin/perl", "-e", "my ${'$'}age = shift; utime(time() - ${'$'}age, time() - ${'$'}age, @ARGV) or die qq(age file failed\\n)",
                seconds.toString(), "$directory/$name",
            ))
            assertEquals(0, result.exitCode, result.stderr)
        }

        fun close() {
            touch("release")
            val groups = (0 until invocation).mapNotNull { index ->
                text("process-group-$index").trim().toLongOrNull()?.takeIf { it > 1 }?.toString()
            }
            val stopped = ProcessRunner.run(listOf("/usr/bin/perl", "-e", """
                use Time::HiRes qw(time sleep);
                my @groups = map { -${'$'}_ } @ARGV;
                kill 'TERM', @groups if @groups;
                my ${'$'}deadline = time() + 0.5;
                while (@groups && time() < ${'$'}deadline) {
                    @groups = grep { kill 0, ${'$'}_ } @groups;
                    sleep 0.01 if @groups;
                }
                kill 'KILL', @groups if @groups;
            """.trimIndent()) + groups)
            assertEquals(0, stopped.exitCode, stopped.stderr)
            assertEquals(0, ProcessRunner.run(listOf("/bin/rm", "-rf", directory)).exitCode)
        }
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }

    private fun source(request: JsonObject) = request.getValue("source").jsonObject
    private fun revision(request: JsonObject) = source(request).getValue("revision").jsonPrimitive.long
    private fun capturedAt(request: JsonObject) = source(request).getValue("capturedAt").jsonPrimitive.long
    private fun sourceId(request: JsonObject) = source(request).getValue("id").jsonPrimitive.content

    @Test
    fun operatorReceivesExactInputAndKeepsStdoutAndExitCode() = test { f ->
        val raw = "$firstPayload\n\n"

        val result = f.run(raw, micros, operator = "/bin/cat; printf 'operator-tail\\n'; exit 7")

        assertEquals(7, result.exitCode)
        assertEquals(raw + "operator-tail\n", result.stdout)
        assertEquals("", result.stderr)
        val request = f.awaitRequests(1).single()
        assertEquals(Json.parseToJsonElement(firstPayload), request["payload"])
        assertEquals(1L, revision(request))
        assertEquals(micros / 1_000, capturedAt(request))
        assertTrue(sourceId(request).matches(Regex("session-a:[0-9a-f]{32}")))
        val arguments = f.text("curl-args").lines()
        assertTrue("http://127.0.0.1:7419/api/v1/hooks/claude/usage" in arguments)
        assertTrue("@${f.header}" in arguments)
        assertFalse(arguments.any { "fixture-secret" in it })
        assertEquals("5", arguments[arguments.indexOf("--max-time") + 1])
    }

    @Test
    fun captureTempfileAndWriteFailuresCannotBreakTheOperatorsInputOutputOrExit() = test { f ->
        val raw = "$firstPayload\n\n"
        val failures = listOf(
            "die qq(injected tempfile failure\\n)",
            "my (${ '$' }handle, ${ '$' }path) = ${ '$' }original->(@_); close ${ '$' }handle; open ${ '$' }handle, '<', ${ '$' }path or die; return (${ '$' }handle, ${ '$' }path)",
        )
        for (failure in failures) {
            writePrivateFile("${f.directory}/FixtureTempFailure.pm", """
                package FixtureTempFailure;
                use File::Temp ();
                no warnings qw(redefine);
                my ${'$'}original = \&File::Temp::tempfile;
                *File::Temp::tempfile = sub { $failure };
                1;
            """.trimIndent().encodeToByteArray())
            val result = f.run(
                raw, micros,
                operator = "/bin/cat; exec /usr/bin/perl -e '1 while wait() > 0; exit 7'",
                perlOptions = "-MFixtureTempFailure",
            )
            assertEquals(7, result.exitCode)
            assertEquals(raw, result.stdout)
            assertEquals("", result.stderr)
        }
        assertTrue(f.requests().isEmpty())
    }

    @Test
    fun operatorInputLargerThanThePipeBufferIsPreservedByteForByte() = test { f ->
        val raw = "α\u0000β\n".repeat(50_000)
        val result = f.run(raw, micros, operator = "/bin/cat; exit 9")
        assertEquals(9, result.exitCode)
        assertEquals(raw, result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun aStalledCaptureCannotHoldTheOperatorsOutputPipeOpen() = test { f ->
        f.touch("stall")

        val result = f.run(firstPayload, micros, operator = "printf 'ready\\n'")

        assertEquals(0, result.exitCode)
        assertEquals("ready\n", result.stdout)
        f.awaitFile("curl-entered")
        assertFalse(f.exists("curl-finished"), "the operator and its stdout pipe finished while curl was blocked")
        f.touch("release")
        assertEquals(1, f.awaitRequests(1).size)
        f.awaitFile("curl-finished")
    }

    @Test
    fun unchangedQuotaIsThrottledAndHeartbeatKeepsItsOriginalSourceRevision() = test { f ->
        assertEquals(0, f.run(firstPayload, micros).exitCode)
        val initial = f.awaitRequests(1).single()
        val unchanged = """{"cost":{"total_duration_ms":99999},"rate_limits":{"seven_day":{"resets_at":1800604800,"used_percentage":70},"five_hour":{"resets_at":1800003600,"used_percentage":20}},"session_id":"session-a"}"""
        assertEquals(0, f.run(unchanged, micros + 59_999_000).exitCode)
        f.awaitRender(micros + 59_999_000)
        assertEquals(1, f.requests().size, "unrelated status data and JSON key order do not change quota")

        assertEquals(0, f.run(unchanged, micros + 60_000_000).exitCode)
        val heartbeat = f.awaitRequests(2).last()

        assertEquals(source(initial), source(heartbeat))
        assertEquals(Json.parseToJsonElement(unchanged), heartbeat["payload"])
        assertEquals(micros / 1_000, capturedAt(heartbeat))
    }

    @Test
    fun changedQuotaImmediatelyAdvancesRevisionAndStoresItsFirstCaptureTime() = test { f ->
        val _ = f.run(firstPayload, micros)
        val first = f.awaitRequests(1).single()
        val _ = f.run(changedPayload, micros + 10_000)
        val changed = f.awaitRequests(2).last()

        assertEquals(sourceId(first), sourceId(changed))
        assertEquals(2L, revision(changed))
        assertEquals((micros + 10_000) / 1_000, capturedAt(changed))
        val _ = f.run(changedPayload, micros + 60_010_000)
        val heartbeat = f.awaitRequests(3).last()
        assertEquals(source(changed), source(heartbeat))
    }

    @Test
    fun wallClockRollbackKeepsMonotonicHeartbeatsAndChangedCaptureEvidenceMoving() = test { f ->
        val _ = f.run(firstPayload, micros, atTicks = 1_000_000)
        val first = f.awaitRequests(1).single()
        val rolledBack = micros - 3_600_000_000
        val _ = f.run(firstPayload, rolledBack, atTicks = 60_999_000)
        f.awaitRender(60_999_000)
        assertEquals(1, f.requests().size)
        val _ = f.run(firstPayload, rolledBack + 1_000, atTicks = 61_000_000)
        val heartbeat = f.awaitRequests(2).last()
        assertEquals(source(first), source(heartbeat))
        val _ = f.run(changedPayload, rolledBack + 2_000, atTicks = 61_001_000)
        val changed = f.awaitRequests(3).last()
        assertEquals(sourceId(first), sourceId(changed))
        assertEquals(2L, revision(changed))
        assertEquals((rolledBack + 2_000) / 1_000, capturedAt(changed), "new evidence retains its actual epoch time")
        val _ = f.run(firstPayload, micros + 1_000, atTicks = 61_000_500, operator = "exec /usr/bin/perl -e '1 while wait() > 0'")
        assertEquals(3, f.requests().size, "an older worker cannot win merely because its wall clock was ahead")
    }

    @Test
    fun rebootStartsANewIncarnationEvenWhenTheTmuxIdentityIsReused() = test { f ->
        val _ = f.run(firstPayload, micros, atTicks = 9_000_000, boot = "boot-one")
        val first = f.awaitRequests(1).single()
        val _ = f.run(changedPayload, micros + 1_000, atTicks = 1_000, boot = "boot-two")
        val restarted = f.awaitRequests(2).last()
        assertNotEquals(sourceId(first), sourceId(restarted))
        assertEquals(1L, revision(restarted))
    }

    @Test
    fun oldWallClockStateStartsANewBaselineInsteadOfBlockingMonotonicRenders() = test { f ->
        val _ = f.run(firstPayload, micros, atTicks = 1_000)
        val first = f.awaitRequests(1).single()
        val migrate = ProcessRunner.run(listOf("/usr/bin/perl", "-MJSON::PP", "-e", """
            my (${ '$' }path) = glob(shift . '/*.json');
            open my ${ '$' }input, '<', ${ '$' }path or die;
            my ${ '$' }raw = do { local ${ '$' }/; <${ '$' }input> };
            close ${ '$' }input;
            my ${ '$' }state = decode_json(${ '$' }raw);
            delete ${ '$' }state->{render_ticks};
            ${ '$' }state->{render_micros} = 1800000000000100;
            open my ${ '$' }output, '>', ${ '$' }path or die;
            print {${ '$' }output} encode_json(${ '$' }state);
            close ${ '$' }output;
        """.trimIndent(), f.stateDirectory))
        assertEquals(0, migrate.exitCode, migrate.stderr)
        val _ = f.run(changedPayload, micros - 1_000, atTicks = 2_000)
        val renewed = f.awaitRequests(2).last()
        assertNotEquals(sourceId(first), sourceId(renewed))
        assertEquals(1L, revision(renewed))
    }

    @Test
    fun captureMaintenancePrunesInactiveStateAndAbandonedStagingButKeepsRecentFiles() = test { f ->
        val _ = f.run(firstPayload, micros)
        val _ = f.awaitRequests(1)
        val expired = "a".repeat(64) + ".json"
        val recent = "b".repeat(64) + ".json"
        for (name in listOf(expired, recent, ".state-abandoned", ".body-abandoned", ".state-recent")) {
            writePrivateFile("${f.stateDirectory}/$name", "fixture".encodeToByteArray())
        }
        f.ageFile("state/$expired", 91 * 86_400L)
        f.ageFile("state/.state-abandoned", 86_401)
        f.ageFile("state/.body-abandoned", 86_401)
        f.ageFile("state/.cleanup", 86_401)
        f.ageFile("state/.capture.lock", 91 * 86_400L)
        val _ = f.run(changedPayload, micros + 1_000)
        val _ = f.awaitRequests(2)
        assertFalse(f.exists("state/$expired"))
        assertFalse(f.exists("state/.state-abandoned"))
        assertFalse(f.exists("state/.body-abandoned"))
        assertTrue(f.exists("state/$recent"))
        assertTrue(f.exists("state/.state-recent"))
        assertTrue(f.exists("state/.capture.lock"), "the single shared lock inode remains stable across cleanup")
        val locks = ProcessRunner.run(listOf("/bin/sh", "-c", "ls -a ${ProcessRunner.shQuote(f.stateDirectory)}")).stdout.lines().filter { it.endsWith(".lock") }
        assertEquals(listOf(".capture.lock"), locks)
    }

    @Test
    fun aKilledStateWriterReleasesTheSharedLockAndItsAbandonedFileIsPruned() = test { f ->
        writePrivateFile("${f.directory}/FixtureKilledWriter.pm", $$"""
            package FixtureKilledWriter;
            use File::Temp ();
            no warnings qw(redefine);
            my $original = \&File::Temp::tempfile;
            *File::Temp::tempfile = sub {
                my ($handle, $path) = $original->(@_);
                open my $record, '>', '$${f.directory}/abandoned-path' or die;
                print {$record} $path;
                close $record;
                kill 'KILL', $$;
                die 'writer survived';
            };
            1;
        """.trimIndent().encodeToByteArray())
        val result = f.run(firstPayload, micros, operator = "/bin/cat", perlOptions = "-MFixtureKilledWriter")
        assertEquals(0, result.exitCode)
        assertEquals(firstPayload, result.stdout)
        f.awaitFile("abandoned-path")
        val abandoned = "state/" + f.text("abandoned-path").substringAfterLast('/')
        assertTrue(f.exists(abandoned))
        f.ageFile(abandoned, 86_401)
        f.ageFile("state/.cleanup", 86_401)
        val _ = f.run(changedPayload, micros + 1_000)
        assertEquals(1L, revision(f.awaitRequests(1).single()))
        assertFalse(f.exists(abandoned))
    }

    @Test
    fun olderWorkersWithinTheSameMillisecondCannotReverseQuotaEvidence() = test { f ->
        val later = micros + 800
        val _ = f.run(changedPayload, later)
        val current = f.awaitRequests(1).single()
        val _ = f.run(firstPayload, micros, operator = "exec /usr/bin/perl -e '1 while wait() > 0'")
        assertEquals(1, f.requests().size, "the older worker completed without publishing a reverse change")
        val _ = f.run(changedPayload, later + 60_000_000)
        val heartbeat = f.awaitRequests(2).last()

        assertEquals(1L, revision(heartbeat))
        assertEquals(source(current), source(heartbeat))
        assertEquals(Json.parseToJsonElement(changedPayload), heartbeat["payload"])
        assertEquals(later / 1_000, capturedAt(heartbeat))
    }

    @Test
    fun deletingCaptureStateStartsANewIncarnationAndANewBaseline() = test { f ->
        val _ = f.run(firstPayload, micros)
        val initial = f.awaitRequests(1).single()
        f.removeStateFiles()

        val _ = f.run(changedPayload, micros + 1_000)
        val restarted = f.awaitRequests(2).last()

        assertNotEquals(sourceId(initial), sourceId(restarted))
        assertEquals(1L, revision(restarted))
        assertEquals((micros + 1_000) / 1_000, capturedAt(restarted))
    }

    @Test
    fun aResumedSessionInAnotherPaneGetsIndependentCaptureState() = test { f ->
        val _ = f.run(firstPayload, micros, pane = "%42")
        val first = f.awaitRequests(1).single()
        val _ = f.run(changedPayload, micros + 1_000, pane = "%43")
        val otherPane = f.awaitRequests(2).last()

        assertNotEquals(sourceId(first), sourceId(otherPane))
        assertEquals(1L, revision(otherPane))
        assertTrue(sourceId(otherPane).startsWith("session-a:"))
    }

    @Test
    fun aRestartedTmuxServerCannotReuseAPreviousPanesSourceBaseline() = test { f ->
        val _ = f.run(firstPayload, micros, tmux = "/tmp/fixture-socket,123,0")
        val first = f.awaitRequests(1).single()
        val _ = f.run(changedPayload, micros + 1_000, tmux = "/tmp/fixture-socket,456,0")
        val restarted = f.awaitRequests(2).last()

        assertNotEquals(sourceId(first), sourceId(restarted))
        assertEquals(1L, revision(restarted))
    }

    @Test
    fun anAbsentOperatorProducesNoStatusTextWhileStillCapturing() = test { f ->
        val result = f.run(firstPayload, micros)

        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(1, f.awaitRequests(1).size)
    }

    @Test
    fun malformedOrMissingQuotaAndFailedCaptureStorageLeaveTheOperatorFunctional() = test { f ->
        val malformed = "{invalid JSON}\n\n"
        val absent = """{"session_id":"session-a","model":{"id":"example"}}""" + "\n"
        for (payload in listOf(malformed, absent)) {
            val result = f.run(payload, micros, operator = "exec /usr/bin/perl -e 'local ${'$'}/; print <STDIN>; 1 while wait() > 0'")
            assertEquals(0, result.exitCode)
            assertEquals(payload, result.stdout)
            assertEquals("", result.stderr)
        }
        val blocked = f.run(firstPayload, micros, operator = "printf 'still works'; exec /usr/bin/perl -e '1 while wait() > 0'", state = "/dev/null/state")
        assertEquals(0, blocked.exitCode)
        assertEquals("still works", blocked.stdout)
        assertEquals("", blocked.stderr)
        assertTrue(f.requests().isEmpty())
    }

    @Test
    fun aFailedCurlDoesNotChangeTheOperatorsOutputOrExitCode() = test { f ->
        val result = f.run(firstPayload, micros, operator = "exec /usr/bin/perl -e 'print qq(operator); 1 while wait() > 0; exit 3'", curlCommand = "/usr/bin/false")

        assertEquals(3, result.exitCode)
        assertEquals("operator", result.stdout)
        assertEquals("", result.stderr)
        f.awaitRender(micros)
        assertEquals(1L, f.state().getValue("revision").jsonPrimitive.long)
        assertTrue(f.requests().isEmpty())
    }
}
