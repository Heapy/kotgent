package io.kotgent.daemon

import io.kotgent.host.DecodedFileRead
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.SqliteUsageStore
import io.kotgent.host.readDecodedTail
import io.kotgent.host.readDecodedPrefix
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.UsageSource
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
// Filesystem fixtures use unique TMPDIR trees and never read the developer's ~/.codex.
class CodexRolloutScanTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    private fun uuid(c: Char): ProviderSessionId =
        ProviderSessionId("$c$c$c$c$c$c$c$c-$c$c$c$c-7$c$c$c-8$c$c$c-$c$c$c$c$c$c$c$c$c$c$c$c")


    @Test
    fun siblingsPreferPlainRegardlessOfDirectoryOrderAndDoNotFallbackOnEmptyPlain() {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/plain")
        val plain = files.last()
        writeBytes("$plain.zst", rawFrame("""{"cwd":"/work/compressed","model":"gpt-compressed"}"""))
        files += "$plain.zst"
        val scan = CodexRolloutScan(codexDir)
        assertEquals("/work/plain", scan.cwdOf(id))
        writeFile(plain, "{}")
        assertNull(scan.cwdOf(id))
        assertNull(scan.modelOf(id))
        unlink(plain)
        assertEquals("/work/compressed", scan.cwdOf(id))
        assertEquals("gpt-compressed", scan.modelOf(id))
        // Recreating plain after the compressed sibling reverses their creation/enumeration order.
        writeFile(plain, """{"cwd":"/work/materialized","model":"gpt-plain"}""")
        assertEquals("/work/materialized", scan.cwdOf(id))
        assertEquals("gpt-plain", scan.modelOf(id))
    }

    @Test
    fun compressedModelBeyondCwdPrefixAndCorruptionIsolation() {
        val codexDir = makeCodexDir()
        val bad = uuid('a')
        val good = uuid('b')
        placeRollout(codexDir, "2026", "10", "22", bad, "/work/bad")
        val badPlain = files.last()
        writeFile("$badPlain.zst", "not-zstd")
        files += "$badPlain.zst"
        unlink(badPlain)
        placeRollout(codexDir, "2026", "10", "22", good, "/work/good")
        val goodPlain = files.last()
        val content = """{"cwd":"/work/good","instructions":"""" + "x".repeat(20_000) +
            "\"}\n" + """{"type":"turn_context","payload":{"model":"gpt-test"}}""" + "\n"
        writeBytes("$goodPlain.zst", rawFrame(content))
        files += "$goodPlain.zst"
        unlink(goodPlain)
        val scan = CodexRolloutScan(codexDir)
        assertTrue(scan.hasRollout(bad), "availability does not validate content")
        assertNull(scan.cwdOf(bad))
        assertEquals("gpt-test", scan.modelOf(good))
        assertEquals(good, scan.discoverSessionId("/work/good", 0))
        writeFile(goodPlain, content)
        assertEquals("gpt-test", scan.modelOf(good))
        assertEquals("/work/good", scan.cwdOf(good))
    }

    @Test
    fun disappearanceBetweenResolutionAndOpenReresolvesBothDirectionsOnce() {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/plain")
        val plain = files.last()
        val compressed = "$plain.zst"
        files += compressed
        writeBytes(compressed, rawFrame("""{"cwd":"/work/compressed"}"""))
        val location = requireNotNull(RolloutLocation.fromPath(plain))
        var calls = 0
        val toCompressed = CodexRolloutReader(prefixRead = { path, zipped, bytes ->
            calls++
            if (calls == 1) unlink(plain)
            readDecodedPrefix(path, zipped, bytes)
        })
        assertEquals("/work/compressed", rolloutCwd(assertIsWindow(toCompressed.prefix(location, 1024))))
        assertEquals(2, calls)
        calls = 0
        val toPlain = CodexRolloutReader(prefixRead = { path, zipped, bytes ->
            calls++
            if (calls == 1) {
                unlink(compressed)
                writeFile(plain, """{"cwd":"/work/materialized"}""")
            }
            readDecodedPrefix(path, zipped, bytes)
        })
        assertEquals("/work/materialized", rolloutCwd(assertIsWindow(toPlain.prefix(location, 1024))))
        assertEquals(2, calls)
        calls = 0
        val unstable = CodexRolloutReader(prefixRead = { _, _, _ -> calls++; DecodedFileRead.Missing })
        assertEquals(DecodedFileRead.Missing, unstable.prefix(location, 1024))
        assertEquals(2, calls, "a continuously disappearing selection gets only one retry")
        calls = 0
        val corrupt = CodexRolloutReader(prefixRead = { _, _, _ -> calls++; DecodedFileRead.Unreadable })
        assertEquals(DecodedFileRead.Unreadable, corrupt.prefix(location, 1024))
        assertEquals(1, calls, "errors are not storage transitions")
    }

    private fun assertIsWindow(read: DecodedFileRead): String =
        kotlin.test.assertIs<DecodedFileRead.Window>(read).bytes.decodeToString()

    private fun rawFrame(text: String): ByteArray {
        val body = text.encodeToByteArray()
        require(body.size <= 128 * 1024)
        val size = body.size
        val block = (size shl 3) or 1
        return byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0xa0.toByte()) +
            ByteArray(4) { (size ushr (it * 8)).toByte() } +
            ByteArray(3) { (block ushr (it * 8)).toByte() } + body
    }

    @Test
    fun compressedOnlyRolloutSupportsIdentityAvailabilityDiscoveryAndCwd() {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/compressed")
        val plain = files.last()
        val compressed = "$plain.zst"
        // Synthetic Zstandard frame with one raw block; no provider transcript or compressor needed.
        val content = """{"type":"session_meta","payload":{"cwd":"/work/compressed"}}""" + "\n"
        val body = content.encodeToByteArray()
        val frame = byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0x20, body.size.toByte()) +
            byteArrayOf(((body.size shl 3) or 1).toByte(), (body.size shr 5).toByte(), 0) + body
        writeBytes(compressed, frame)
        files += compressed
        unlink(plain)
        val scan = CodexRolloutScan(codexDir)
        assertEquals(id, rolloutFileSessionId(compressed.substringAfterLast('/')))
        assertTrue(scan.hasRollout(id))
        assertEquals("/work/compressed", scan.cwdOf(id))
        assertEquals(id, scan.discoverSessionId("/work/compressed", 0))
        assertNull(scan.discoverSessionId("/work/compressed", Long.MAX_VALUE))
    }

    @Test
    fun idIsReadFromTheEndOfTheRolloutFileName() {
        assertEquals(
            ProviderSessionId("019f8ea0-2548-7871-9835-947ff7623ccf"),
            rolloutFileSessionId("rollout-2026-07-23T13-58-07-019f8ea0-2548-7871-9835-947ff7623ccf.jsonl"),
        )
    }

    @Test
    fun aNonRolloutFileNameYieldsNothing() {
        assertNull(rolloutFileSessionId("history.jsonl"), "not a rollout")
        assertNull(rolloutFileSessionId("rollout-2026-07-23T13-58-07-not-a-uuid.jsonl"), "id is not a UUID")
        assertNull(rolloutFileSessionId("rollout-019f8ea0-2548-7871-9835-947ff7623ccf.txt"), "wrong extension")
        assertNull(rolloutFileSessionId("rollout-.jsonl"), "no id at all")
        assertNull(
            rolloutFileSessionId("rollout019f8ea0-2548-7871-9835-947ff7623ccf.jsonl"),
            "the id must be dash-separated from the timestamp",
        )
    }


    @Test
    fun cwdIsReadOutOfTheSessionMetaLine() {
        val line = """{"timestamp":"2026-07-23T08:02:55.942Z","type":"session_meta","payload":""" +
                """{"session_id":"019f8ea0-2548-7871-9835-947ff7623ccf","cwd":"/Users/yoda/dev/pet/kotgent",""" +
                """"originator":"codex_exec"}}"""
        assertEquals("/Users/yoda/dev/pet/kotgent", rolloutCwd(line))
    }

    @Test
    fun cwdSurvivesATruncatedHead() {
        val head = """{"type":"session_meta","payload":{"cwd":"/work/repo","base_instructions":{"text":"You are Cod"""
        assertEquals("/work/repo", rolloutCwd(head))
    }

    @Test
    fun cwdHandlesEscapesAndMissingFields() {
        assertEquals("""/work/we"ird""", rolloutCwd("""{"cwd":"/work/we\"ird"}"""), "escaped quote")
        assertEquals("""/work/back\slash""", rolloutCwd("""{"cwd":"/work/back\\slash"}"""), "escaped backslash")
        assertEquals("/work/é", rolloutCwd("""{"cwd":"/work/é"}"""), "unicode escape")
        assertNull(rolloutCwd("""{"session_id":"x"}"""), "no cwd field")
        assertNull(rolloutCwd("""{"cwd":"/work/unterminate"""), "the value was cut off: no usable answer")
    }


    @Test
    fun aPresentRolloutIsResumable() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            val id = uuid('a')
            placeRollout(codexDir, "2026", "07", "23", id, cwd = "/work/repo")

            val probe = codexVendorStoreProbe(codexDir)
            assertTrue(probe.hasTranscript("codex", "/work/repo", id), "the rollout on disk is found")
            assertEquals(
                SessionState.resumable,
                Reconciler.classify(
                    paneAlive = false,
                    currentState = SessionState.running,
                    stopIntent = false,
                    transcriptExists = probe.hasTranscript("codex", "/work/repo", id),
                ),
                "a dead pane whose rollout survives classifies as resumable",
            )
        }
    }

    @Test
    fun theProbeIgnoresCwdBecauseCodexNamesRolloutsByIdAlone() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            val id = uuid('b')
            placeRollout(codexDir, "2026", "07", "23", id, cwd = "/work/one")

            val probe = codexVendorStoreProbe(codexDir)
            assertTrue(probe.hasTranscript("codex", "/somewhere/else", id))
        }
    }

    @Test
    fun aMissingRolloutIsNotResumable() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            placeRollout(codexDir, "2026", "07", "23", uuid('c'), cwd = "/work/repo")

            val probe = codexVendorStoreProbe(codexDir)
            assertFalse(probe.hasTranscript("codex", "/work/repo", uuid('d')), "a different id is not found")
            assertEquals(
                SessionState.lost,
                Reconciler.classify(
                    paneAlive = false,
                    currentState = SessionState.running,
                    stopIntent = false,
                    transcriptExists = false,
                ),
                "nothing to resume -> lost, not a resume that would fail",
            )
        }
    }

    @Test
    fun anAbsentCodexHomeDegradesToNotResumable() = runBlocking {
        withTimeout(10.seconds) {
            val probe = codexVendorStoreProbe("/nonexistent/kotgent-test-codex-home")
            assertFalse(probe.hasTranscript("codex", "/work", uuid('e')), "an unreadable home answers false")
        }
    }


    @Test
    fun discoveryFindsTheRolloutWrittenForThisCwd() {
        val codexDir = makeCodexDir()
        val mine = uuid('a')
        placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/mine")
        placeRollout(codexDir, "2026", "07", "23", uuid('b'), cwd = "/work/other")

        val scan = CodexRolloutScan(codexDir)
        assertEquals(mine, scan.discoverSessionId("/work/mine", notBeforeMillis = 0))
        assertNull(scan.discoverSessionId("/work/nothing-here", notBeforeMillis = 0), "no match -> null")
    }

    @Test
    fun discoveryIgnoresRolloutsOlderThanTheLaunch() {
        val codexDir = makeCodexDir()
        placeRollout(codexDir, "2026", "07", "23", uuid('a'), cwd = "/work/mine")

        val scan = CodexRolloutScan(codexDir)
        val wayInTheFuture = 4_000_000_000_000
        assertNull(
            scan.discoverSessionId("/work/mine", notBeforeMillis = wayInTheFuture),
            "a rollout older than the launch cutoff is not ours",
        )
    }

    @Test
    fun discoveryWalksEveryDateDirectory() {
        val codexDir = makeCodexDir()
        val id = uuid('f')
        placeRollout(codexDir, "2025", "12", "31", id, cwd = "/work/late")

        assertEquals(id, CodexRolloutScan(codexDir).discoverSessionId("/work/late", notBeforeMillis = 0))
    }

    @Test
    fun discoveryOnAnEmptyOrAbsentTreeIsNull() {
        assertNull(CodexRolloutScan(makeCodexDir()).discoverSessionId("/work", notBeforeMillis = 0))
        assertNull(CodexRolloutScan("/nonexistent/kotgent-test-codex").discoverSessionId("/work", 0))
    }


    @Test
    fun cwdOfReadsTheRecordedCwdOutOfTheSessionMeta() = runBlocking {
        withTimeout(20.seconds) {
            val codexDir = makeCodexDir()
            val id = uuid('a')
            placeRollout(codexDir, "2026", "07", "23", id, cwd = "/work/mine")
            placeRollout(codexDir, "2026", "07", "23", uuid('b'), cwd = "/work/other")

            assertEquals("/work/mine", CodexRolloutScan(codexDir).cwdOf(id))
            assertEquals("/work/mine", codexSessionLocator(codexDir).cwdOf("codex", id))
        }
    }

    @Test
    fun cwdOfAnUnknownIdIsNull() {
        val codexDir = makeCodexDir()
        placeRollout(codexDir, "2026", "07", "23", uuid('c'), cwd = "/work/mine")

        assertNull(CodexRolloutScan(codexDir).cwdOf(uuid('d')))
        assertNull(CodexRolloutScan("/nonexistent/kotgent-test-codex").cwdOf(uuid('d')), "absent home -> null")
    }

    @Test
    fun cwdOfIgnoresArchivedRollouts() {
        val codexDir = makeCodexDir()
        val id = uuid('e')
        placeArchivedRollout(codexDir, id, cwd = "/work/archived")

        assertNull(CodexRolloutScan(codexDir).cwdOf(id))
    }


    @Test
    fun modelOfReadsTheIdKeyedRolloutIgnoringNeighboursInTheSameCwd() {
        val codexDir = makeCodexDir()
        val mine = uuid('a')
        val neighbour = uuid('b')
        placeRolloutWithModel(codexDir, "2026", "07", "23", mine, cwd = "/work/shared", model = "gpt-5.5")
        placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")

        val scan = CodexRolloutScan(codexDir)
        assertEquals("gpt-5.5", scan.modelOf(mine), "the id-keyed rollout answers, not the newest for the cwd")
        assertEquals("gpt-6", scan.modelOf(neighbour))
        assertNull(scan.modelOf(uuid('c')), "an unknown id is null")
    }

    @Test
    fun modelOfIsNullWhenTheRolloutHasNoTurnContextYet() {
        val codexDir = makeCodexDir()
        val mine = uuid('a')
        placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared")
        placeRolloutWithModel(codexDir, "2026", "07", "24", uuid('b'), cwd = "/work/shared", model = "gpt-6")

        assertNull(CodexRolloutScan(codexDir).modelOf(mine))
    }

    @Test
    fun quotaReadsTheRolloutTailBeyondTheModelHeadWithStableRecordProvenance() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            placeRolloutWithModel(codexDir, "2026", "09", "11", mine, cwd = "/work/mine", model = "gpt-5.5")
            val file = files.last()
            val head = requireNotNull(readHead(file, CodexRolloutScan.MODEL_SCAN_BYTES))
            val padding = "é🙂".repeat(CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES / 2)
            val quota = """{"timestamp":"2026-09-11T18:05:58.392Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":58,"window_minutes":10080,"resets_at":1789435330}}}}"""
            val beforeQuota = "$head{\"type\":\"padding\",\"payload\":\"$padding\"}\n"
            val contents = "$beforeQuota$quota\n{\"unfinished\":"
            writeFile(file, contents)

            val scan = CodexRolloutScan(codexDir)
            val usage = scan.rateLimitsOf(mine).single()
            assertEquals(58.0, usage.usedPercent)
            assertEquals(604_800L, usage.windowSeconds)
            assertEquals(1_789_435_330_000L, usage.resetsAt)
            assertEquals(UsageSource(mine.value, beforeQuota.encodeToByteArray().size.toLong(), 1_789_149_958_392L), usage.source)
            assertEquals(usage, scan.rateLimitsOf(mine).single(), "reading the same record never freshens it")
            val shifted = contents + "\n{\"type\":\"message\",\"text\":\"" + "é🙂".repeat(500) + "\"}\n"
            writeFile(file, shifted)
            assertEquals(usage, scan.rateLimitsOf(mine).single(), "moving the bounded tail does not change the record's offset")
            val secondQuota = quota.replace("\"used_percent\":58", "\"used_percent\":59")
            writeFile(file, "$shifted$secondQuota\n")
            val second = scan.rateLimitsOf(mine).single()
            assertEquals(59.0, second.usedPercent)
            assertEquals(UsageSource(mine.value, shifted.encodeToByteArray().size.toLong(), 1_789_149_958_392L), second.source)
            assertTrue(second.source!!.revision > usage.source!!.revision, "distinct records sharing a timestamp still advance")
            assertEquals("gpt-5.5", scan.modelOf(mine), "model extraction still reads the head")
            assertTrue(scan.rateLimitsOf(uuid('b')).isEmpty(), "the source is keyed by provider id")
        }
    }

    @Test
    fun quotaWithoutARecordTimestampCannotBeAdmittedByTheProductionScanner() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('c')
            placeRollout(codexDir, "2026", "09", "11", mine, cwd = "/work/mine")
            val quota = """{"type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":20}}}}"""
            val previous = """{"timestamp":"2026-09-11T18:05:58.392Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":19}}}}"""
            writeFile(files.last(), "$previous\n$quota\n")

            assertTrue(CodexRolloutScan(codexDir).rateLimitsOf(mine).isEmpty())
            assertTrue(CodexRolloutScan("/nonexistent/kotgent-test-codex").rateLimitsOf(mine).isEmpty())
        }
    }

    @Test
    fun compressedUsageKeepsDecodedOffsetsAcrossUtf8AndPartialRecordBoundaries() = runBlocking {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/usage")
        val plain = files.last()
        files += "$plain.zst"
        val quota = quotaRecord(20)
        // Put the bounded tail's first byte inside the three-byte character in a long JSON record.
        val suffix = "x".repeat(CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES - quota.encodeToByteArray().size - 5)
        val prefix = "{\"text\":\"" + "x".repeat(100) + "€" + suffix + "\"}\n"
        val content = prefix + quota
        val bytes = content.encodeToByteArray()
        val start = bytes.size - CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES
        assertEquals(0x82, bytes[start].toInt() and 0xff, "fixture starts inside UTF-8")
        writeFile(plain, content)
        writeBytes("$plain.zst", rawFrames(bytes))
        val scan = CodexRolloutScan(codexDir)
        val expected = scan.rateLimitsOf(id).single()
        assertEquals(prefix.encodeToByteArray().size.toLong(), expected.source?.revision)
        assertEquals(id.value, expected.source?.id)
        assertEquals(1_789_149_958_392L, expected.source?.capturedAt)
        unlink(plain)
        assertEquals(listOf(expected), scan.rateLimitsOf(id))
        val tail = kotlin.test.assertIs<DecodedFileRead.Window>(
            CodexRolloutReader().tail(requireNotNull(RolloutLocation.fromPath(plain)), CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES),
        )
        assertEquals(CodexRolloutScan.TOKEN_COUNT_TAIL_BYTES, tail.bytes.size)
        assertEquals(start.toLong(), tail.logicalOffset)
    }

    @Test
    fun cachedCanonicalUsageLocationTracksBothTransitionsAndPlainAuthority() = runBlocking {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/usage")
        val plain = files.last()
        files += "$plain.zst"
        writeFile(plain, quotaRecord(20))
        var visits = 0
        val scan = CodexRolloutScan(codexDir, visitEntries = { dir, visit ->
            visits++
            visitDirectoryEntries(dir, visit)
        })
        val original = scan.rateLimitsOf(id)
        val coldVisits = visits
        writeBytes("$plain.zst", rawFrame(quotaRecord(20)))
        unlink(plain)
        assertEquals(original, scan.rateLimitsOf(id))
        writeFile(plain, quotaRecord(20) + quotaRecord(30))
        assertEquals(30.0, scan.rateLimitsOf(id).single().usedPercent)
        for (content in listOf("", "{}\n", "malformed\n", quotaRecord(40).replace("2026-09-11T18:05:58.392Z", "invalid"))) {
            writeFile(plain, content)
            assertTrue(scan.rateLimitsOf(id).isEmpty(), "present plain never falls back to compressed quota")
        }
        assertEquals(coldVisits, visits, "cache hits resolve siblings without walking inventory")
        unlink(plain)
        assertEquals(original, scan.rateLimitsOf(id))
        unlink("$plain.zst")
        assertTrue(scan.rateLimitsOf(id).isEmpty())
        val missingVisits = visits
        placeRollout(codexDir, "2026", "10", "23", id, "/work/usage")
        val moved = files.last()
        writeBytes("$moved.zst", rawFrame(quotaRecord(50)))
        files += "$moved.zst"
        unlink(moved)
        assertEquals(50.0, scan.rateLimitsOf(id).single().usedPercent)
        assertTrue(visits > missingVisits, "missing results are not cached")
    }

    @Test
    fun coldUsageLookupHonorsPlainEvenWhenCompressedIsEnumeratedFirst() = runBlocking {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/usage")
        val plain = files.last()
        val directory = plain.substringBeforeLast('/')
        val name = plain.substringAfterLast('/')
        files += "$plain.zst"
        writeBytes("$plain.zst", rawFrame(quotaRecord(20)))
        for (compressedFirst in listOf(true, false)) {
            for (content in listOf(quotaRecord(30), "", "broken")) {
                writeFile(plain, content)
                var matched = 0
                val scan = CodexRolloutScan(codexDir, visitEntries = { dir, visit ->
                    if (dir != directory) visitDirectoryEntries(dir, visit)
                    else {
                        val names = if (compressedFirst) listOf("$name.zst", name) else listOf(name, "$name.zst")
                        var keepGoing = true
                        for (candidate in names) {
                            matched++
                            keepGoing = visit(candidate)
                            if (!keepGoing) break
                        }
                        keepGoing
                    }
                })
                assertEquals(if (content == quotaRecord(30)) listOf(30.0) else emptyList(), scan.rateLimitsOf(id).map { it.usedPercent })
                assertEquals(1, matched, "first canonical match stops even with unusable plain content")
            }
        }
        unlink(plain)
        assertEquals(0, mkdir(plain, mode0700.convert()))
        try {
            assertTrue(CodexRolloutScan(codexDir).rateLimitsOf(id).isEmpty(), "nonregular plain is authoritative too")
        } finally { rmdir(plain) }
    }

    @Test
    fun compressedReplayCannotRefreshSqliteEvidenceAndMaterializedAppendCan() = runBlocking {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/usage")
        val plain = files.last()
        val content = "{\"text\":\"é€\"}\n" + quotaRecord(20).replace("\"window_minutes\":10080", "\"window_minutes\":10080,\"resets_at\":1790000000")
        writeFile(plain, content)
        val scan = CodexRolloutScan(codexDir)
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        var now = 1_789_150_000_000L
        val store = SqliteUsageStore(driver) { now }
        fun count(table: String): Long = driver.executeQuery(null, "SELECT COUNT(*) FROM $table", { cursor ->
            cursor.next()
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        }, 0).value
        try {
            store.observe(scan.rateLimitsOf(id).single())
            val before = store.list().single()
            now += 60_000
            writeBytes("$plain.zst", rawFrame(content))
            files += "$plain.zst"
            unlink(plain)
            repeat(2) { store.observe(scan.rateLimitsOf(id).single()) }
            assertEquals(before, store.list().single(), "compression changes neither revision, receipt nor freshness")
            assertEquals(1L, count("usage_samples"))
            assertEquals(0L, count("usage_resets"))
            writeFile(plain, content + quotaRecord(30).replace("18:05:58.392", "18:06:58.392")
                .replace("\"window_minutes\":10080", "\"window_minutes\":10080,\"resets_at\":1790604800"))
            store.observe(scan.rateLimitsOf(id).single())
            val after = store.list().single()
            assertEquals(30.0, after.current.usedPercent)
            assertEquals(content.encodeToByteArray().size.toLong(), after.current.source?.revision)
            assertEquals(now, after.receivedAt)
            assertTrue(after.current.observedAt > before.current.observedAt)
            assertEquals(2L, count("usage_samples"))
            assertEquals(0L, count("usage_resets"))
            unlink(plain)
            now += 60_000
            store.observe(scan.rateLimitsOf(id).single())
            assertEquals(after, store.list().single(), "older compressed evidence cannot undo the later plain record")
            assertEquals(2L, count("usage_samples"))
            assertEquals(0L, count("usage_resets"), "stale lower quota with another reset timestamp is not a reset")
        } finally {
            driver.close()
        }
    }

    @Test
    fun compressedTailRequiresCompleteEofAndKeepsExistingRecordRejectionRules() = runBlocking {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/usage")
        val plain = files.last()
        val compressed = "$plain.zst"
        files += compressed
        unlink(plain)
        val scan = CodexRolloutScan(codexDir)
        val quota = quotaRecord(20)
        val complete = rawFrame(quota)
        for (broken in listOf(complete.copyOf(complete.size - 1), complete + byteArrayOf(1), complete + rawFrame("suffix").dropLast(1).toByteArray())) {
            writeBytes(compressed, broken)
            assertTrue(scan.rateLimitsOf(id).isEmpty(), "valid-looking quota before broken EOF must not publish")
        }
        writeBytes(compressed, rawFrame(quota.replace("2026-09-11T18:05:58.392Z", "invalid")))
        assertTrue(scan.rateLimitsOf(id).isEmpty())
        writeBytes(compressed, rawFrame(quota.replace("\"timestamp\":\"2026-09-11T18:05:58.392Z\",", "")))
        assertTrue(scan.rateLimitsOf(id).isEmpty())
        writeBytes(compressed, rawFrame(quota + "{broken\n"))
        assertEquals(20.0, scan.rateLimitsOf(id).single().usedPercent, "malformed later JSON retains existing parser behavior")
    }

    @Test
    fun tailReresolvesDisappearingRepresentationsOnceButDoesNotRetryErrors() = runBlocking {
        val codexDir = makeCodexDir()
        val id = uuid('a')
        placeRollout(codexDir, "2026", "10", "22", id, "/work/usage")
        val plain = files.last()
        val compressed = "$plain.zst"
        files += compressed
        writeBytes(compressed, rawFrame(quotaRecord(20)))
        val location = requireNotNull(RolloutLocation.fromPath(plain))
        var calls = 0
        val toCompressed = CodexRolloutReader(tailRead = { path, zipped, bytes ->
            if (++calls == 1) unlink(plain)
            readDecodedTail(path, zipped, bytes)
        })
        assertEquals(quotaRecord(20), assertIsWindow(toCompressed.tail(location, 1024)))
        assertEquals(2, calls)
        calls = 0
        val toPlain = CodexRolloutReader(tailRead = { path, zipped, bytes ->
            if (++calls == 1) {
                unlink(compressed)
                writeFile(plain, quotaRecord(30))
            }
            readDecodedTail(path, zipped, bytes)
        })
        assertEquals(quotaRecord(30), assertIsWindow(toPlain.tail(location, 1024)))
        assertEquals(2, calls)
        for (failure in listOf(DecodedFileRead.Missing, DecodedFileRead.Unreadable, DecodedFileRead.ResourceLimit)) {
            calls = 0
            val reader = CodexRolloutReader(tailRead = { _, _, _ -> calls++; failure })
            assertEquals(failure, reader.tail(location, 1024))
            assertEquals(if (failure == DecodedFileRead.Missing) 2 else 1, calls)
        }
    }

    private fun rawFrames(bytes: ByteArray): ByteArray {
        val frames = bytes.asList().chunked(128 * 1024).map { chunk ->
            val body = chunk.toByteArray()
            val block = (body.size shl 3) or 1
            byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0xa0.toByte()) +
                ByteArray(4) { (body.size ushr (it * 8)).toByte() } +
                ByteArray(3) { (block ushr (it * 8)).toByte() } + body
        }
        val out = ByteArray(frames.sumOf { it.size })
        var offset = 0
        for (frame in frames) { frame.copyInto(out, offset); offset += frame.size }
        return out
    }

    @Test
    fun repeatedQuotaReadsReuseTheLocatedFileButStillReadItsLatestTail() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            placeRollout(codexDir, "2026", "09", "11", mine, "/work/mine")
            val path = files.last()
            writeFile(path, quotaRecord(20))
            var directoryReads = 0
            val scan = CodexRolloutScan(codexDir, visitEntries = { directory, visit ->
                directoryReads++
                visitDirectoryEntries(directory, visit)
            })

            assertEquals(20.0, scan.rateLimitsOf(mine).single().usedPercent)
            val coldReads = directoryReads
            assertTrue(coldReads > 0)
            writeFile(path, quotaRecord(30))
            assertEquals(30.0, scan.rateLimitsOf(mine).single().usedPercent)
            assertEquals(coldReads, directoryReads, "a retry or later turn does not walk the dated inventory again")

            unlink(path)
            assertTrue(scan.rateLimitsOf(mine).isEmpty(), "a removed cached file cannot retain a stale quota")
            val missingReads = directoryReads
            placeRollout(codexDir, "2026", "09", "12", mine, "/work/mine")
            writeFile(files.last(), quotaRecord(40))
            assertEquals(40.0, scan.rateLimitsOf(mine).single().usedPercent)
            assertTrue(directoryReads > missingReads, "absence is never cached and a moved rollout is rediscovered")
        }
    }

    @Test
    fun usageLookupStopsAtTheMatchedFileWithoutVisitingTheRestOfItsDirectory() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            repeat(64) { index ->
                placeRollout(codexDir, "2026", "09", "11", indexedUuid(index), "/work/shared")
                writeFile(files.last(), quotaRecord(20))
            }
            val directory = files.last().substringBeforeLast('/')
            val orderedNames = mutableListOf<String>()
            val _ = visitDirectoryEntries(directory) { orderedNames += it; true }
            val mine = requireNotNull(rolloutFileSessionId(orderedNames.first()))
            val visitedNames = mutableListOf<String>()
            val scan = CodexRolloutScan(codexDir, visitEntries = { dir, visit ->
                visitDirectoryEntries(dir) { name ->
                    if (dir == directory) visitedNames += name
                    visit(name)
                }
            })

            assertEquals(20.0, scan.rateLimitsOf(mine).single().usedPercent)
            assertEquals(listOf(orderedNames.first()), visitedNames, "the actual directory's first match ends enumeration")
        }
    }

    @Test
    fun usagePathCacheEvictsOldEntriesAndKeepsRecentlyReadPaths() = runBlocking {
        withTimeout(15.seconds) {
            val codexDir = makeCodexDir()
            val ids = (0..128).map(::indexedUuid)
            for (index in ids.indices) {
                placeRollout(codexDir, "2026", "09", "11", ids[index], "/work/shared")
                val plain = files.last()
                writeFile(plain, quotaRecord(20))
                if (index % 2 == 0) {
                    writeBytes("$plain.zst", rawFrame(quotaRecord(20)))
                    files += "$plain.zst"
                    unlink(plain)
                }
            }
            var directoryReads = 0
            val scan = CodexRolloutScan(codexDir, visitEntries = { directory, visit ->
                directoryReads++
                visitDirectoryEntries(directory, visit)
            })
            for (id in ids.take(128)) assertEquals(20.0, scan.rateLimitsOf(id).single().usedPercent)
            val _ = scan.rateLimitsOf(ids.first())
            val _ = scan.rateLimitsOf(ids.last())
            val fullReads = directoryReads
            val _ = scan.rateLimitsOf(ids.first())
            assertEquals(fullReads, directoryReads, "a recent path remains cached after reaching the bound")
            val _ = scan.rateLimitsOf(ids[1])
            assertTrue(directoryReads > fullReads, "the bounded cache rediscovers an evicted old path")
        }
    }

    @Test
    fun cancelledUsageLookupStopsInsideTheRealDirectoryWalk() = runBlocking {
        withTimeout(10.seconds) {
            val codexDir = makeCodexDir()
            placeRollout(codexDir, "2026", "09", "11", uuid('a'), "/work/other")
            val directory = files.last().substringBeforeLast('/')
            repeat(256) { index ->
                val neighbour = "$directory/neighbour-$index.jsonl"
                writeFile(neighbour, "{}")
                files += neighbour
            }
            var visited = 0
            val entered = CompletableDeferred<Unit>()
            val scan = CodexRolloutScan(codexDir, visitEntries = { dir, visit ->
                visitDirectoryEntries(dir) { name ->
                    visited++
                    if (visited == 20) entered.complete(Unit)
                    visit(name)
                }
            })
            val lookup = async(start = CoroutineStart.UNDISPATCHED) { scan.rateLimitsOf(uuid('b')) }

            entered.await()
            assertFalse(lookup.isCompleted, "a large native directory walk must yield to its caller before finishing")
            assertTrue(visited in 1..256)
            lookup.cancelAndJoin()
            assertFailsWith<CancellationException> { lookup.await() }
            assertTrue(visited < 257, "cancellation stops enumeration, not merely publication after the whole scan")
            var reopened = 0
            assertTrue(visitDirectoryEntries(directory) { reopened++; true })
            assertEquals(257, reopened)
        }
    }

    private fun quotaRecord(percent: Int): String =
        """{"timestamp":"2026-09-11T18:05:58.392Z","type":"event_msg","payload":{"type":"token_count","rate_limits":{"primary":{"used_percent":$percent,"window_minutes":10080}}}}""" + "\n"

    private fun indexedUuid(index: Int): ProviderSessionId =
        ProviderSessionId("00000000-0000-4000-8000-${index.toString(16).padStart(12, '0')}")

    @Test
    fun captureCodexModelOnceReReadsTheProviderIdTheBackgroundBindLandedMidPoll() = runBlocking {
        withTimeout(20.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            placeRolloutWithModel(codexDir, "2026", "07", "23", mine, cwd = "/work/shared", model = "gpt-5.5")
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val store = SqliteEventStore.inMemory(now = { 42L })
            val launchMeta = SessionMeta(
                id = SessionId("cap00001"),
                name = "kt-cap00001", tags = emptyList(), agent = CODEX_AGENT_KIND,
                cwd = "/work/shared", tmuxSession = "kt-cap00001",
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta.copy(providerSessionId = mine))

            val persisted = captureCodexModelOnce(store, CodexRolloutScan(codexDir), launchMeta)

            assertTrue(persisted, "the id-keyed lookup answered")
            assertEquals(
                "gpt-5.5",
                store.getSession(SessionId("cap00001"))!!.model,
                "the row's CURRENT id keys the lookup — never the launch-time null",
            )
        }
    }

    @Test
    fun captureCodexModelOncePersistsNothingWhileTheIdIsUnknown() = runBlocking {
        withTimeout(20.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared")
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val store = SqliteEventStore.inMemory(now = { 42L })
            val launchMeta = SessionMeta(
                id = SessionId("cap00003"),
                name = "kt-cap00003", tags = emptyList(), agent = CODEX_AGENT_KIND,
                cwd = "/work/shared", tmuxSession = "kt-cap00003",
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta)

            val scan = CodexRolloutScan(codexDir)
            assertFalse(captureCodexModelOnce(store, scan, launchMeta))
            assertNull(
                store.getSession(SessionId("cap00003"))!!.model,
                "an id-less attempt persists nothing — any hit could be the neighbour's",
            )

            store.upsertSession(launchMeta.copy(providerSessionId = mine))
            assertFalse(captureCodexModelOnce(store, scan, launchMeta))
            assertNull(
                store.getSession(SessionId("cap00003"))!!.model,
                "a bound id makes the id-keyed lookup the only source; a miss stays null",
            )
        }
    }

    @Test
    fun captureCodexModelOnceNeverPersistsAGuessEvenWhenTheIdNeverBinds() = runBlocking {
        withTimeout(20.seconds) {
            val codexDir = makeCodexDir()
            placeRolloutWithModel(codexDir, "2026", "07", "24", uuid('b'), cwd = "/work/solo", model = "gpt-6")
            val store = SqliteEventStore.inMemory(now = { 42L })
            val launchMeta = SessionMeta(
                id = SessionId("cap00004"),
                name = "kt-cap00004", tags = emptyList(), agent = CODEX_AGENT_KIND,
                cwd = "/work/solo", tmuxSession = "kt-cap00004",
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta)

            val scan = CodexRolloutScan(codexDir)
            repeat(3) { assertFalse(captureCodexModelOnce(store, scan, launchMeta)) }
            assertNull(
                store.getSession(SessionId("cap00004"))!!.model,
                "no attempt ever writes an id-less result — the model stays an honest null",
            )
        }
    }

    @Test
    fun aHookRebindAfterAScanBoundNeighbourCorrectsThePersistedModel() = runBlocking {
        withTimeout(20.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            placeRolloutWithModel(codexDir, "2026", "07", "23", mine, cwd = "/work/shared", model = "gpt-5.5")
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val scan = CodexRolloutScan(codexDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val sid = SessionId("rbnd0001")
            val launchMeta = SessionMeta(
                id = sid, name = "kt-rbnd0001", agent = CODEX_AGENT_KIND,
                cwd = "/work/shared", tmuxSession = "kt-rbnd0001",
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta)
            val idCapture = ProviderIdCapture(store, this)
            val recapture = CompletableDeferred<SessionMeta>()
            val mgr = SessionManager(
                tmux = FakeTmux(),
                store = store,
                registry = PaneRegistry(),
                agentFactory = { _, _ -> throw AssertionError("the chain never launches an adapter") },
                idCapture = idCapture,
                vendorProbe = { _, _, _ -> false },
                sessionLocator = { _, _ -> null },
                supportedAgentKinds = setOf("claude", "codex"),
                captureModelInBackground = { m -> recapture.complete(m) },
                now = { 43L },
            )

            assertTrue(idCapture.bind(sid, neighbour), "the discovery fallback's bind path")
            assertTrue(captureCodexModelOnce(store, scan, launchMeta))
            assertEquals("gpt-6", store.getSession(sid)!!.model, "the neighbour's model was persisted")

            val _ = store.append(sid, AgentEvent.SessionBound(mine), EventSource.hook)
            assertEquals(mine, store.getSession(sid)!!.providerSessionId, "the hook wins over the scan")
            assertEquals("gpt-6", store.getSession(sid)!!.model, "…but the suspect model survives the append")

            mgr.onProviderIdRebound(sid)
            assertNull(store.getSession(sid)!!.model, "the suspect model is cleared before any recapture")
            val again = recapture.await()
            assertTrue(captureCodexModelOnce(store, scan, again), "the re-run answers id-keyed")
            assertEquals("gpt-5.5", store.getSession(sid)!!.model, "the true model replaces the neighbour's")
        }
    }

    @Test
    fun captureCodexModelOnceCannotRacePastTheRebindClearWithTheDisplacedIdsModel() = runBlocking {
        withTimeout(20.seconds) {
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared")
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val real = SqliteEventStore.inMemory(now = { 42L })
            val sid = SessionId("race0001")
            val launchMeta = SessionMeta(
                id = sid, name = "kt-race0001", agent = CODEX_AGENT_KIND,
                cwd = "/work/shared", tmuxSession = "kt-race0001",
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            real.upsertSession(launchMeta)
            val _ = real.append(sid, AgentEvent.SessionBound(neighbour), EventSource.system)

            val store = object : EventStore by real {
                private var raced = false
                override suspend fun getSession(sessionId: SessionId): SessionMeta? {
                    val row = real.getSession(sessionId)
                    if (!raced) {
                        raced = true
                        val _ = real.append(sid, AgentEvent.SessionBound(mine), EventSource.hook)
                        real.setModel(sessionId = sid, null)
                    }
                    return row
                }
            }

            assertFalse(
                captureCodexModelOnce(store, CodexRolloutScan(codexDir), launchMeta),
                "a capture holding the displaced id writes zero rows",
            )
            assertNull(
                real.getSession(sid)!!.model,
                "the neighbour's model cannot race past the rebind's clear",
            )
        }
    }


    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private fun makeCodexDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-rollout-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        val codex = "$base/.codex"
        mkdir(codex, mode0700.convert()).also { dirs += codex }
        return codex
    }

    private fun placeRollout(
        codexDir: String,
        year: String,
        month: String,
        day: String,
        id: ProviderSessionId,
        cwd: String,
    ) {
        var path = "$codexDir/sessions"
        for (segment in listOf(year, month, day)) {
            mkdir(path, mode0700.convert()).also { if (!dirs.contains(path)) dirs += path }
            path = "$path/$segment"
        }
        mkdir(path, mode0700.convert()).also { if (!dirs.contains(path)) dirs += path }

        val file = "$path/rollout-$year-$month-${day}T10-00-00-${id.value}.jsonl"
        writeFile(
            file,
            """{"timestamp":"$year-$month-${day}T10:00:00.000Z","type":"session_meta","payload":""" +
                    """{"session_id":"${id.value}","cwd":"$cwd","cli_version":"0.145.0"}}""" + "\n",
        )
        files += file
    }

    private fun placeArchivedRollout(codexDir: String, id: ProviderSessionId, cwd: String) {
        val archived = "$codexDir/archived_sessions"
        mkdir(archived, mode0700.convert()).also { if (!dirs.contains(archived)) dirs += archived }
        val file = "$archived/rollout-2026-07-23T10-00-00-${id.value}.jsonl"
        writeFile(
            file,
            """{"timestamp":"2026-07-23T10:00:00.000Z","type":"session_meta","payload":""" +
                    """{"session_id":"${id.value}","cwd":"$cwd","cli_version":"0.145.0"}}""" + "\n",
        )
        files += file
    }

    private fun placeRolloutWithModel(
        codexDir: String,
        year: String,
        month: String,
        day: String,
        id: ProviderSessionId,
        cwd: String,
        model: String,
    ) {
        var path = "$codexDir/sessions"
        for (segment in listOf(year, month, day)) {
            mkdir(path, mode0700.convert()).also { if (!dirs.contains(path)) dirs += path }
            path = "$path/$segment"
        }
        mkdir(path, mode0700.convert()).also { if (!dirs.contains(path)) dirs += path }

        val padding = "x".repeat(20_000)
        val file = "$path/rollout-$year-$month-${day}T10-00-00-${id.value}.jsonl"
        val meta = """{"timestamp":"$year-$month-${day}T10:00:00.000Z","type":"session_meta","payload":""" +
                """{"session_id":"${id.value}","cwd":"$cwd","model_provider":"openai","base_instructions":"$padding"}}"""
        val turn = """{"timestamp":"$year-$month-${day}T10:00:05.000Z","type":"turn_context","payload":""" +
                """{"cwd":"$cwd","model":"$model"}}"""
        writeFile(file, meta + "\n" + turn + "\n")
        files += file
    }

    private fun writeFile(path: String, text: String) {
        writeBytes(path, text.encodeToByteArray())
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            if (bytes.isNotEmpty()) bytes.usePinned {
                assertEquals(bytes.size.toULong(), fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp))
            }
        } finally {
            fclose(fp)
        }
    }

    private companion object {
        var counter = 0
    }
}
