package io.kotgent.daemon

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
                SessionState.crashed,
                Reconciler.classify(
                    paneAlive = false,
                    currentState = SessionState.running,
                    stopIntent = false,
                    transcriptExists = false,
                ),
                "nothing to resume -> crashed, not a resume that would fail",
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
            for (id in ids) {
                placeRollout(codexDir, "2026", "09", "11", id, "/work/shared")
                writeFile(files.last(), quotaRecord(20))
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
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            val _ = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
    }

    private companion object {
        var counter = 0
    }
}
