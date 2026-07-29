package io.kotgent.daemon

import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [CodexRolloutScan] — provider-id discovery and the Codex resumability probe.
 *
 * NEVER reads the real `~/.codex`: every filesystem test injects a throwaway `$TMPDIR` tree laid out
 * exactly like Codex's (`sessions/<YYYY>/<MM>/<DD>/rollout-<ts>-<id>.jsonl`, first line a `session_meta`),
 * so the scan runs for real against a fake home. The pure parts (file name → id, head → cwd) are tested
 * directly, including on the truncated input the reader actually produces.
 */
@OptIn(ExperimentalForeignApi::class)
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

    // ---- pure: the id lives in the file NAME ----

    @Test
    fun idIsReadFromTheEndOfTheRolloutFileName() {
        // Real name from ~/.codex (2026-07): the timestamp contains dashes too, so only "the last 36
        // characters" identifies the id unambiguously.
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

    // ---- pure: the cwd lives in the first line ----

    @Test
    fun cwdIsReadOutOfTheSessionMetaLine() {
        val line = """{"timestamp":"2026-07-23T08:02:55.942Z","type":"session_meta","payload":""" +
            """{"session_id":"019f8ea0-2548-7871-9835-947ff7623ccf","cwd":"/Users/yoda/dev/pet/kotgent",""" +
            """"originator":"codex_exec"}}"""
        assertEquals("/Users/yoda/dev/pet/kotgent", rolloutCwd(line))
    }

    @Test
    fun cwdSurvivesATruncatedHead() {
        // What the reader actually hands over: a `session_meta` line embeds the full base instructions
        // and is cut off mid-record. `cwd` sits well before the cut, so it must still be readable.
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

    // ---- the probe: a live rollout makes a dead session resumable ----

    @Test
    fun aPresentRolloutIsResumable() = runBlocking {
        withTimeout(10_000) {
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
        withTimeout(10_000) {
            val codexDir = makeCodexDir()
            val id = uuid('b')
            placeRollout(codexDir, "2026", "07", "23", id, cwd = "/work/one")

            val probe = codexVendorStoreProbe(codexDir)
            // Unlike Claude (whose transcripts are namespaced per project dir), a codex rollout is found
            // by id regardless of the cwd the session was launched in.
            assertTrue(probe.hasTranscript("codex", "/somewhere/else", id))
        }
    }

    @Test
    fun aMissingRolloutIsNotResumable() = runBlocking {
        withTimeout(10_000) {
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
        withTimeout(10_000) {
            val probe = codexVendorStoreProbe("/nonexistent/kotgent-test-codex-home")
            assertFalse(probe.hasTranscript("codex", "/work", uuid('e')), "an unreadable home answers false")
        }
    }

    // ---- discovery: which rollout belongs to the session we just launched ----

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
        // The guard that keeps a hand-started codex in the same directory from being bound to OUR
        // session: files written before the launch began are out of scope, however recent.
        val codexDir = makeCodexDir()
        placeRollout(codexDir, "2026", "07", "23", uuid('a'), cwd = "/work/mine")

        val scan = CodexRolloutScan(codexDir)
        val wayInTheFuture = 4_000_000_000_000 // year ~2096, comfortably after any file we just wrote
        assertNull(
            scan.discoverSessionId("/work/mine", notBeforeMillis = wayInTheFuture),
            "a rollout older than the launch cutoff is not ours",
        )
    }

    @Test
    fun discoveryWalksEveryDateDirectory() {
        val codexDir = makeCodexDir()
        val id = uuid('f')
        // A session started before midnight lands in yesterday's directory — the walk must not assume
        // "today".
        placeRollout(codexDir, "2025", "12", "31", id, cwd = "/work/late")

        assertEquals(id, CodexRolloutScan(codexDir).discoverSessionId("/work/late", notBeforeMillis = 0))
    }

    @Test
    fun discoveryOnAnEmptyOrAbsentTreeIsNull() {
        assertNull(CodexRolloutScan(makeCodexDir()).discoverSessionId("/work", notBeforeMillis = 0))
        assertNull(CodexRolloutScan("/nonexistent/kotgent-test-codex").discoverSessionId("/work", 0))
    }

    // --- cwdOf: the recorded cwd of a live rollout (import discovery) ---

    @Test
    fun cwdOfReadsTheRecordedCwdOutOfTheSessionMeta() = runBlocking {
        withTimeout(20_000) {
            val codexDir = makeCodexDir()
            val id = uuid('a')
            placeRollout(codexDir, "2026", "07", "23", id, cwd = "/work/mine")
            placeRollout(codexDir, "2026", "07", "23", uuid('b'), cwd = "/work/other")

            assertEquals("/work/mine", CodexRolloutScan(codexDir).cwdOf(id))
            // The production VendorSessionLocator is the same lookup behind the uniform (agent, id) shape.
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
        // Archiving puts a session out of `codex resume`'s reach, so an import discovered from an
        // archived rollout would offer a revival that fails — it must not answer.
        val codexDir = makeCodexDir()
        val id = uuid('e')
        placeArchivedRollout(codexDir, id, cwd = "/work/archived")

        assertNull(CodexRolloutScan(codexDir).cwdOf(id))
    }

    // --- model capture (id-keyed ONLY — no id-less result is ever written) ---

    @Test
    fun modelOfReadsTheIdKeyedRolloutIgnoringNeighboursInTheSameCwd() {
        // The one model lookup: the provider id keys the file NAME, so a newer neighbour session in
        // the same cwd never answers for this one. The fixture's session_meta line is padded past the
        // 8 KB HEAD_BYTES window, so this also pins that modelOf reads the larger MODEL_SCAN_BYTES
        // head the turn_context record sits behind.
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
        // Codex writes turn_context (and the model in it) only once the session takes its first turn.
        // Until then the id-keyed lookup must answer an honest null so the capture loop retries — a
        // busier same-cwd neighbour that already has a model must never answer for this session.
        val codexDir = makeCodexDir()
        val mine = uuid('a')
        placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared")
        placeRolloutWithModel(codexDir, "2026", "07", "24", uuid('b'), cwd = "/work/shared", model = "gpt-6")

        assertNull(CodexRolloutScan(codexDir).modelOf(mine))
    }

    @Test
    fun captureCodexModelOnceReReadsTheProviderIdTheBackgroundBindLandedMidPoll() = runBlocking {
        withTimeout(20_000) {
            // The fresh-launch poll: the capture loop starts with a launch-time meta whose provider id
            // is NULL (codex has no --session-id), and the background id capture lands mid-poll. Every
            // attempt must re-read the ROW's current id — a loop pinned to the stale null could never
            // capture a fresh launch's model at all.
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            placeRolloutWithModel(codexDir, "2026", "07", "23", mine, cwd = "/work/shared", model = "gpt-5.5")
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val store = SqliteEventStore.inMemory(now = { 42L })
            val launchMeta = SessionMeta(
                id = SessionId("cap00001"),
                name = "kt-cap00001", tags = emptyList(), agent = CODEX_AGENT_KIND,
                providerSessionId = null, // what the loop closed over at launch time
                cwd = "/work/shared", tmuxSession = "kt-cap00001", paneId = null,
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            // The bind has landed by the time this attempt runs: the ROW knows the id.
            store.upsertSession(launchMeta.copy(providerSessionId = mine))

            val persisted = captureCodexModelOnce(store, CodexRolloutScan(codexDir), launchMeta, now = { 43L })

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
        withTimeout(20_000) {
            // While the id is unknown an attempt persists NOTHING (no cwd+mtime heuristic exists any
            // more): an id-less hit could only be a same-cwd guess, and a FIRST SessionStart bind
            // (null -> id) landing at any later moment triggers no model correction — only a bind that
            // DISPLACES a different persisted id fires the ingress rebind seam — so a guess once
            // written could stick forever.
            // And once the id IS bound, an id-keyed miss (this session's rollout has no turn_context
            // yet) stays an honest null so the loop keeps polling.
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            // This session's own rollout has no turn_context; the same-cwd neighbour has one.
            placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared")
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val store = SqliteEventStore.inMemory(now = { 42L })
            val launchMeta = SessionMeta(
                id = SessionId("cap00003"),
                name = "kt-cap00003", tags = emptyList(), agent = CODEX_AGENT_KIND,
                providerSessionId = null, // what the loop closed over at launch time
                cwd = "/work/shared", tmuxSession = "kt-cap00003", paneId = null,
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta)

            val scan = CodexRolloutScan(codexDir)
            assertFalse(captureCodexModelOnce(store, scan, launchMeta, now = { 43L }))
            assertNull(
                store.getSession(SessionId("cap00003"))!!.model,
                "an id-less attempt persists nothing — any hit could be the neighbour's",
            )

            // The bind lands mid-poll. The next attempt runs id-keyed, misses (no turn_context yet),
            // and the model honestly stays null — no fallback exists to guess from.
            store.upsertSession(launchMeta.copy(providerSessionId = mine))
            assertFalse(captureCodexModelOnce(store, scan, launchMeta, now = { 44L }))
            assertNull(
                store.getSession(SessionId("cap00003"))!!.model,
                "a bound id makes the id-keyed lookup the only source; a miss stays null",
            )
        }
    }

    @Test
    fun captureCodexModelOnceNeverPersistsAGuessEvenWhenTheIdNeverBinds() = runBlocking {
        withTimeout(20_000) {
            // The id never binds (hook lost, rollout discovery failed). Every attempt — including the
            // poll's last — persists nothing: a delayed codex SessionStart can still bind the id AFTER
            // the poll has exited, and a FIRST bind (null -> id) triggers no model correction (the
            // ingress rebind seam fires only when a DIFFERENT persisted id is displaced), so any guess
            // written here (the same-cwd neighbour's gpt-6) would stick forever. Such a session is
            // already degraded — resume itself requires the id — and its honest null model beats a guess.
            val codexDir = makeCodexDir()
            placeRolloutWithModel(codexDir, "2026", "07", "24", uuid('b'), cwd = "/work/solo", model = "gpt-6")
            val store = SqliteEventStore.inMemory(now = { 42L })
            val launchMeta = SessionMeta(
                id = SessionId("cap00004"),
                name = "kt-cap00004", tags = emptyList(), agent = CODEX_AGENT_KIND,
                providerSessionId = null,
                cwd = "/work/solo", tmuxSession = "kt-cap00004", paneId = null,
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta)

            val scan = CodexRolloutScan(codexDir)
            repeat(3) { attempt ->
                assertFalse(captureCodexModelOnce(store, scan, launchMeta, now = { 43L + attempt }))
            }
            assertNull(
                store.getSession(SessionId("cap00004"))!!.model,
                "no attempt ever writes an id-less result — the model stays an honest null",
            )
        }
    }

    @Test
    fun aHookRebindAfterAScanBoundNeighbourCorrectsThePersistedModel() = runBlocking {
        withTimeout(20_000) {
            // The full mislabeling chain, driven through the exact production seams (no daemon):
            //   1. the cwd+mtime discovery fallback binds the same-cwd NEIGHBOUR's id (under mtime ties
            //      discoverSessionId can pick either same-cwd rollout — that IS the hazard, so the
            //      neighbour is bound deterministically through the same ProviderIdCapture.bind the
            //      discovery path calls);
            //   2. the id-keyed capture trusts the row's id, persists the NEIGHBOUR's model, and stops;
            //   3. the authoritative hook SessionStart appends SessionBound with the true id — the
            //      reducer overwrites the id ("the hook wins over the scan") but corrects no model;
            //   4. the ingress' rebind seam (SessionManager.onProviderIdRebound) clears the suspect
            //      model and re-runs the capture, which now keys off the true id.
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
                providerSessionId = null, // fresh codex launch: no id yet
                cwd = "/work/shared", tmuxSession = "kt-rbnd0001", paneId = null,
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            store.upsertSession(launchMeta)
            val idCapture = ProviderIdCapture(store, this)
            val recapture = CompletableDeferred<SessionMeta>()
            val mgr = SessionManager(
                FakeTmux(), store, PaneRegistry(),
                AgentFactory { _, _ -> throw AssertionError("the chain never launches an adapter") },
                idCapture,
                VendorStoreProbe { _, _, _ -> false }, VendorSessionLocator { _, _ -> null },
                setOf("claude", "codex"),
                captureModelInBackground = { m -> recapture.complete(m) },
                now = { 43L },
            )

            // 1 + 2: scan-bound neighbour id -> the capture persists the neighbour's model and stops.
            assertTrue(idCapture.bind(sid, neighbour), "the discovery fallback's bind path")
            assertTrue(captureCodexModelOnce(store, scan, launchMeta, now = { 43L }))
            assertEquals("gpt-6", store.getSession(sid)!!.model, "the neighbour's model was persisted")

            // 3: the hook displaces the id; the append alone corrects nothing.
            store.append(sid, AgentEvent.SessionBound(mine), EventSource.hook)
            assertEquals(mine, store.getSession(sid)!!.providerSessionId, "the hook wins over the scan")
            assertEquals("gpt-6", store.getSession(sid)!!.model, "…but the suspect model survives the append")

            // 4: the rebind seam clears it and the re-run capture stamps the TRUE session's model.
            mgr.onProviderIdRebound(sid)
            assertNull(store.getSession(sid)!!.model, "the suspect model is cleared before any recapture")
            val again = recapture.await()
            assertTrue(captureCodexModelOnce(store, scan, again, now = { 44L }), "the re-run answers id-keyed")
            assertEquals("gpt-5.5", store.getSession(sid)!!.model, "the true model replaces the neighbour's")
        }
    }

    @Test
    fun captureCodexModelOnceCannotRacePastTheRebindClearWithTheDisplacedIdsModel() = runBlocking {
        withTimeout(20_000) {
            // The residual the rebind seam alone left open: an attempt that read the scan-bound
            // NEIGHBOUR id, and then had the authoritative hook displace that id (and the seam clear
            // the model) WHILE it was still scanning the rollout tree, used to write the neighbour's
            // model unconditionally — racing past the clear and restoring the mislabel with its poll
            // already stopped. The write is now atomically conditional on the row still holding the id
            // the lookup was keyed by (EventStore.setModelForProvider), so the raced attempt writes
            // zero rows and answers false — the poll retries keyed off the now-authoritative id.
            val codexDir = makeCodexDir()
            val mine = uuid('a')
            val neighbour = uuid('b')
            placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared") // no turn_context yet
            placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")
            val real = SqliteEventStore.inMemory(now = { 42L })
            val sid = SessionId("race0001")
            val launchMeta = SessionMeta(
                id = sid, name = "kt-race0001", agent = CODEX_AGENT_KIND,
                providerSessionId = null, // fresh codex launch: no id yet
                cwd = "/work/shared", tmuxSession = "kt-race0001", paneId = null,
                state = SessionState.running, stateSource = EventSource.system,
                createdAt = 0L, updatedAt = 0L,
            )
            real.upsertSession(launchMeta)
            real.append(sid, AgentEvent.SessionBound(neighbour), EventSource.system) // the scan-bound id

            // Interleave the displacement into the capture's read-scan-write window: the id the attempt
            // read goes stale the moment it starts scanning.
            val store = object : EventStore by real {
                private var raced = false
                override suspend fun getSession(sessionId: SessionId): SessionMeta? {
                    val row = real.getSession(sessionId)
                    if (!raced) {
                        raced = true
                        real.append(sid, AgentEvent.SessionBound(mine), EventSource.hook) // the hook displaces
                        real.setModel(sid, null, 43L) // the rebind seam's clear
                    }
                    return row
                }
            }

            assertFalse(
                captureCodexModelOnce(store, CodexRolloutScan(codexDir), launchMeta, now = { 44L }),
                "a capture holding the displaced id writes zero rows",
            )
            assertNull(
                real.getSession(sid)!!.model,
                "the neighbour's model cannot race past the rebind's clear",
            )
        }
    }

    // --- harness (throwaway $TMPDIR fake ~/.codex; NEVER the real one) --------------------------------

    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    /** A fresh throwaway `<tmp>/…/.codex` base directory (created + tracked for teardown). */
    private fun makeCodexDir(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val base = "$tmp/kotgent-rollout-test-${getpid()}-${counter++}"
        mkdir(base, mode0700.convert()).also { dirs += base }
        val codex = "$base/.codex"
        mkdir(codex, mode0700.convert()).also { dirs += codex }
        return codex
    }

    /**
     * Lay a rollout at exactly the path Codex would use, with a first line shaped like a real
     * `session_meta` (id in the file name, `cwd` in the payload).
     */
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

    /**
     * Lay a rollout in the FLAT `archived_sessions/` directory — where `codex archive` moves a session,
     * out of `codex resume`'s (and therefore the scan's) reach. Same file naming as a live rollout.
     */
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

    /**
     * Like [placeRollout] but with a realistically LARGE `session_meta` line (padded past the 8 KB head)
     * followed by a `turn_context` record carrying the [model] — the shape [CodexRolloutScan.modelOf]
     * must read past to find. The session_meta carries `model_provider` but not `model`, guarding the
     * false-match.
     */
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

        val padding = "x".repeat(20_000) // pushes the turn_context past the 8 KB session-id head window
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
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
    }

    private companion object {
        var counter = 0
    }
}
