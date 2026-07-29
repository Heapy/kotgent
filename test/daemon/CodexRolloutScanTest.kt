package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionState
import kotlinx.cinterop.ExperimentalForeignApi
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

    // --- model discovery ---

    @Test
    fun discoverModelReadsTheModelFromTurnContextPastTheHeadBoundary() {
        val codexDir = makeCodexDir()
        val id = uuid('d')
        // The model sits in a turn_context record AFTER a >8 KB session_meta line — past discoverSessionId's
        // 8 KB head, so this only works because discoverModel reads the larger MODEL_SCAN_BYTES window.
        placeRolloutWithModel(codexDir, "2026", "07", "23", id, cwd = "/work/model", model = "gpt-5.5")

        val scan = CodexRolloutScan(codexDir)
        assertEquals("gpt-5.5", scan.discoverModel("/work/model", notBeforeMillis = 0))
        assertNull(scan.discoverModel("/work/nowhere", notBeforeMillis = 0), "no matching cwd -> null")
    }

    @Test
    fun discoverModelIsNullWhenTheRolloutHasNoModel() {
        val codexDir = makeCodexDir()
        // A plain session_meta-only rollout (no turn_context, no model) — best-effort miss.
        placeRollout(codexDir, "2026", "07", "23", uuid('e'), cwd = "/work/nomodel")
        assertNull(CodexRolloutScan(codexDir).discoverModel("/work/nomodel", notBeforeMillis = 0))
    }

    @Test
    fun modelOfReadsTheIdKeyedRolloutIgnoringNeighboursInTheSameCwd() {
        // The resume-path capture: the provider id is known, so the model comes from THAT session's
        // rollout (matched by id in the file name) — a newer neighbour session in the same cwd, which
        // discoverModel's cwd+mtime heuristic could prefer, must never answer for it.
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
    fun modelForCaptureNeverFallsBackToTheHeuristicWhenTheProviderIdIsKnown() {
        // The resume-path hazard: this session's rollout has no turn_context YET (codex writes the model
        // only once the resumed session takes a turn), while a busier neighbour in the same cwd already
        // has one. Falling back to the cwd+mtime heuristic would persist the NEIGHBOUR's model; the
        // id-keyed lookup must answer null so the capture loop retries against the right rollout.
        val codexDir = makeCodexDir()
        val mine = uuid('a')
        val neighbour = uuid('b')
        placeRollout(codexDir, "2026", "07", "23", mine, cwd = "/work/shared")
        placeRolloutWithModel(codexDir, "2026", "07", "24", neighbour, cwd = "/work/shared", model = "gpt-6")

        val scan = CodexRolloutScan(codexDir)
        assertNull(
            scan.modelForCapture(mine, "/work/shared", notBeforeMillis = 0),
            "a temporary id-keyed miss stays null — it must not adopt the neighbour's model",
        )
        assertEquals(
            "gpt-6",
            scan.modelForCapture(null, "/work/shared", notBeforeMillis = 0),
            "an id-less fresh launch still discovers through the cwd+mtime heuristic",
        )
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
     * followed by a `turn_context` record carrying the [model] — the shape [CodexRolloutScan.discoverModel]
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
