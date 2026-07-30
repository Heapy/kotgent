package io.kotgent.daemon

import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionState
import io.kotgent.store.SqliteEventStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Import WIRING test (the [io.kotgent.transport] AuthorizeWiringTest pattern): [SessionImportTest]
 * pins the `importSession` rules over fakes, but only THIS test proves the REAL production components
 * answer them together — [productionSessionLocator] and [productionVendorStoreProbe] are the exact
 * functions `Commands.daemon` wires into [SessionManager] (and the [Reconciler]), driven here over
 * throwaway vendor homes. This is the guard against "the tests ran fakes while production shipped a
 * stub" (the Task-15 `{ false }` probe bug recorded in the ClaudeVendorStoreProbe.kt header).
 *
 * Reach, recorded honestly: the guard covers the FACTORY FUNCTIONS only. The `Commands.daemon`
 * call-site that passes them into the production [SessionManager] is outside automation (the daemon
 * cannot be started in tests — repo rule), so a stub swapped in at that line would not fail here;
 * it would only be caught by the manual verification checklist.
 *
 * NEVER touches the real `~/.claude` / `~/.codex` / `~/.junie`: every tree is a `$TMPDIR` throwaway laid
 * out exactly like the vendors' (torn down in [cleanUp]), and every vendor home is passed explicitly so
 * no default can reach the operator's own.
 */
@OptIn(ExperimentalForeignApi::class)
class ImportWiringTest {

    private val files = mutableListOf<String>()
    private val dirs = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        for (f in files) unlink(f)
        for (d in dirs.asReversed()) rmdir(d)
    }

    private fun uuid(c: Char): ProviderSessionId =
        ProviderSessionId("$c$c$c$c$c$c$c$c-$c$c$c$c-4$c$c$c-8$c$c$c-$c$c$c$c$c$c$c$c$c$c$c$c")

    /** Import must never build an adapter, so the wiring runs with a factory that fails the test. */
    private val untouchableFactory = AgentFactory { kind, _ ->
        throw AssertionError("importSession must never build an adapter (asked for '$kind')")
    }

    private fun CoroutineScope.manager(
        store: SqliteEventStore,
        tmux: FakeTmux,
        probe: VendorStoreProbe,
        locator: VendorSessionLocator,
    ) = SessionManager(
        tmux, store, PaneRegistry(), untouchableFactory,
        ProviderIdCapture(store, this),
        probe, locator,
        // The same kinds the daemon's builders map registers (its keys are what production passes).
        setOf(CLAUDE_AGENT_KIND, CODEX_AGENT_KIND, JUNIE_AGENT_KIND),
        newSessionId = { SessionId("wire0001") },
        now = { 42L },
    )

    @Test
    fun aClaudeSessionIsDiscoveredByTheRealLocatorAndSurvivesTheRealProbeAndReconcile() = runBlocking {
        withTimeout(20_000) {
            val base = makeBase()
            val claudeDir = makeDir("$base/.claude")
            val projectCwd = makeDir("$base/project") // the cwd must EXIST — import checks access(F_OK)
            val id = uuid('a')
            // The transcript sits where Claude would put it for THIS cwd, recording the same cwd — so
            // the locator's discovery re-encodes back to the transcript and the probe agrees.
            placeClaudeTranscript(claudeDir, encodeClaudeProjectDir(projectCwd), id, recordedCwd = projectCwd)
            val probe = productionProbe(base, claudeDir = claudeDir)
            val locator = productionLocator(base, claudeDir = claudeDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            val mgr = manager(store, tmux, probe, locator)

            val meta = mgr.importSession(CLAUDE_AGENT_KIND, id) // NO explicit cwd — discovery answers

            assertEquals(projectCwd, meta.cwd, "the real locator discovered the recorded cwd")
            assertEquals(SessionState.resumable, meta.state)
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux side effects")

            // The SAME probe instance the import validated against is what a daemon restart re-asks:
            // the imported session must hold `resumable`, not degrade to `crashed`.
            Reconciler(tmux, store, probe, PaneRegistry(), now = { 43L }).reconcile()
            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("wire0001"))!!.state,
                "reconcile over the real probe keeps the imported session resumable",
            )
        }
    }

    @Test
    fun aCodexSessionIsDiscoveredFromItsRolloutByTheRealScan() = runBlocking {
        withTimeout(20_000) {
            val base = makeBase()
            val codexDir = makeDir("$base/.codex")
            val projectCwd = makeDir("$base/repo")
            val id = uuid('b')
            placeCodexRollout(codexDir, id, recordedCwd = projectCwd)
            val probe = productionProbe(base, codexDir = codexDir)
            val locator = productionLocator(base, codexDir = codexDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            val mgr = manager(store, tmux, probe, locator)

            val meta = mgr.importSession(CODEX_AGENT_KIND, id) // NO explicit cwd — discovery answers

            assertEquals(projectCwd, meta.cwd, "the real rollout scan read the session_meta cwd")
            assertEquals(SessionState.resumable, meta.state)
            assertEquals(id, meta.providerSessionId)

            // The "survives daemon restart" guarantee, end-to-end for codex too: the SAME probe a
            // restart re-asks keeps the imported session resumable (the codex probe ignores cwd, but
            // that must be proven by the real component, not assumed).
            Reconciler(tmux, store, probe, PaneRegistry(), now = { 43L }).reconcile()
            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("wire0001"))!!.state,
                "reconcile over the real probe keeps the imported codex session resumable",
            )
        }
    }

    @Test
    fun aSymlinkedRecordedCwdIsCanonicalizedIntoTheTranscriptKeyAndTheImportSucceeds() = runBlocking {
        withTimeout(20_000) {
            // The real-world /tmp-vs-/private/tmp shape, driven through the REAL /tmp symlink: Claude
            // wrote the transcript under the project dir encoded from /private/tmp (its process getcwd —
            // the realpath form), while the recorded/typed spelling says "/tmp". The daemon canonicalizes
            // with realpath(3) BEFORE probing, so both spellings converge on the transcript's key and the
            // import lands with the canonical cwd in the row — with the canonicalization deleted, the
            // probe re-encodes "/tmp" to a project dir with no transcript and this import fails loudly.
            // (A cwd that still misses AFTER canonicalization keeps that loud TranscriptNotFoundException
            // — SessionImportTest pins the ladder.)
            val base = makeBase()
            val claudeDir = makeDir("$base/.claude")
            val id = uuid('c')
            placeClaudeTranscript(claudeDir, encodeClaudeProjectDir("/private/tmp"), id, recordedCwd = "/tmp")
            val probe = productionProbe(base, claudeDir = claudeDir)
            val locator = productionLocator(base, claudeDir = claudeDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store, FakeTmux(), probe, locator)

            val meta = mgr.importSession(CLAUDE_AGENT_KIND, id) // discovery answers "/tmp"

            assertEquals("/private/tmp", meta.cwd, "the row stores the canonical spelling, not the symlinked one")
            assertEquals(SessionState.resumable, meta.state)
            // The Reconciler's restart re-probe asks the same canonical triple, so the row survives it.
            Reconciler(FakeTmux(), store, probe, PaneRegistry(), now = { 43L }).reconcile()
            assertEquals(SessionState.resumable, store.getSession(SessionId("wire0001"))!!.state)
        }
    }

    @Test
    fun anArchivedCodexRolloutIsNotDiscoverable() = runBlocking {
        withTimeout(20_000) {
            // Archiving moves the rollout to the flat archived_sessions/ — out of `codex resume`'s
            // reach, so the real scan must not offer it and the import fails asking for --cwd/naming
            // the archive.
            val base = makeBase()
            val codexDir = makeDir("$base/.codex")
            val id = uuid('d')
            val archived = makeDir("$codexDir/archived_sessions")
            writeFile(
                "$archived/rollout-2026-07-29T10-00-00-${id.value}.jsonl",
                sessionMetaLine(id, cwd = "$base"),
            )
            val probe = productionProbe(base, codexDir = codexDir)
            val locator = productionLocator(base, codexDir = codexDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store, FakeTmux(), probe, locator)

            val ex = assertFailsWith<ImportCwdException> { mgr.importSession(CODEX_AGENT_KIND, id) }
            assertTrue(
                ex.message!!.contains("archived"),
                "the natural no---cwd path names the archived-codex cause: ${ex.message}",
            )
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun aJunieSessionIsDiscoveredFromItsIndexByTheRealScan() = runBlocking {
        withTimeout(20_000) {
            val base = makeBase()
            val junieDir = makeDir("$base/.junie")
            val projectCwd = makeDir("$base/proj")
            val id = ProviderSessionId("session-260730-015553-1j1h")
            placeJunieSession(junieDir, id, recordedCwd = projectCwd)
            val probe = productionProbe(base, junieDir = junieDir)
            val locator = productionLocator(base, junieDir = junieDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            val mgr = manager(store, tmux, probe, locator)

            val meta = mgr.importSession(JUNIE_AGENT_KIND, id) // NO explicit cwd — discovery answers

            assertEquals(projectCwd, meta.cwd, "the real index scan read the recorded projectDir")
            assertEquals(SessionState.resumable, meta.state)
            assertEquals(id, meta.providerSessionId, "a junie id is NOT a UUID and must survive verbatim")
            assertTrue(tmux.newSessionCommands.isEmpty(), "no tmux side effects")

            // The "survives daemon restart" guarantee for junie: the SAME probe a restart re-asks keeps
            // the imported session resumable.
            Reconciler(tmux, store, probe, PaneRegistry(), now = { 43L }).reconcile()
            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("wire0001"))!!.state,
                "reconcile over the real probe keeps the imported junie session resumable",
            )
        }
    }

    @Test
    fun aJunieSessionWhoseDirectoryWasPrunedIsNotDiscoverable() = runBlocking {
        withTimeout(20_000) {
            // Junie keeps the context of only its most recent sessions, so an index row can outlive the
            // session directory it names. The DISK is the authority: a pruned session must not be
            // importable, because `--resume` could not revive it.
            val base = makeBase()
            val junieDir = makeDir("$base/.junie")
            val id = ProviderSessionId("session-260624-190541-1d97")
            makeDir("$junieDir/sessions")
            writeFile("$junieDir/sessions/index.jsonl", junieIndexLine(id, projectDir = base))
            val probe = productionProbe(base, junieDir = junieDir)
            val locator = productionLocator(base, junieDir = junieDir)
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store, FakeTmux(), probe, locator)

            assertFailsWith<ImportCwdException> { mgr.importSession(JUNIE_AGENT_KIND, id) }
            assertTrue(store.listSessions().isEmpty(), "no row was created")

            // With an explicit --cwd the discovery is bypassed, but the PROBE still refuses: there is
            // nothing on disk to resume.
            val probed = assertFailsWith<TranscriptNotFoundException> {
                mgr.importSession(JUNIE_AGENT_KIND, id, cwd = base)
            }
            assertTrue(
                probed.message!!.contains(id.value),
                "the refusal names the session it could not find: ${probed.message}",
            )
        }
    }

    // --- harness (throwaway $TMPDIR vendor homes; NEVER the real ones) --------------------------------

    /**
     * The real probe dispatch with EVERY vendor home pointed at a throwaway under [base] — a default
     * would reach the operator's own `~/.claude` / `~/.codex` / `~/.junie`.
     */
    private fun productionProbe(
        base: String,
        claudeDir: String = "$base/.claude",
        codexDir: String = "$base/.codex",
        junieDir: String = "$base/.junie",
    ): VendorStoreProbe = productionVendorStoreProbe(claudeDir, codexDir, junieDir)

    /** The real locator dispatch, same all-throwaway-homes discipline as [productionProbe]. */
    private fun productionLocator(
        base: String,
        claudeDir: String = "$base/.claude",
        codexDir: String = "$base/.codex",
        junieDir: String = "$base/.junie",
    ): VendorSessionLocator = productionSessionLocator(claudeDir, codexDir, junieDir)

    private val mode0700: Int get() = S_IRUSR or S_IWUSR or S_IXUSR

    private fun makeBase(): String {
        val tmp = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
        val made = makeDir("$tmp/kotgent-import-wiring-${getpid()}-${counter++}")
        // The fixtures must be laid out from the CANONICAL spelling ($TMPDIR sits behind the
        // /var → /private/var symlink): real providers record their process getcwd — the realpath form —
        // and importSession canonicalizes the same way before probing, so a fixture built from the
        // symlinked spelling would place transcripts under a key production never asks.
        return canonicalPath(made) ?: made
    }

    private fun makeDir(path: String): String {
        mkdir(path, mode0700.convert())
        if (!dirs.contains(path)) dirs += path
        return path
    }

    private fun placeClaudeTranscript(
        claudeDir: String,
        project: String,
        id: ProviderSessionId,
        recordedCwd: String,
    ) {
        makeDir("$claudeDir/projects")
        val projectDir = makeDir("$claudeDir/projects/$project")
        writeFile(
            "$projectDir/${id.value}.jsonl",
            """{"type":"summary","summary":"a chat","leafUuid":"${id.value}"}""" + "\n" +
                """{"parentUuid":null,"isSidechain":false,"cwd":"$recordedCwd","sessionId":"${id.value}","type":"user"}""" + "\n",
        )
    }

    private fun placeCodexRollout(codexDir: String, id: ProviderSessionId, recordedCwd: String) {
        makeDir("$codexDir/sessions")
        makeDir("$codexDir/sessions/2026")
        makeDir("$codexDir/sessions/2026/07")
        val day = makeDir("$codexDir/sessions/2026/07/29")
        writeFile("$day/rollout-2026-07-29T10-00-00-${id.value}.jsonl", sessionMetaLine(id, recordedCwd))
    }

    /**
     * Lay a junie session where junie would: the session directory holding its `events.jsonl` (which is
     * what the probe reads) plus the `index.jsonl` row carrying its `projectDir` (what the locator reads).
     */
    private fun placeJunieSession(junieDir: String, id: ProviderSessionId, recordedCwd: String) {
        val sessions = makeDir("$junieDir/sessions")
        val session = makeDir("$sessions/${id.value}")
        writeFile("$session/events.jsonl", """{"kind":"SessionA2uxEvent","timestampMs":1785000000000}""" + "\n")
        writeFile("$sessions/index.jsonl", junieIndexLine(id, recordedCwd))
    }

    // The real index record shape (verified against junie 26.8.3's ~/.junie/sessions/index.jsonl).
    private fun junieIndexLine(id: ProviderSessionId, projectDir: String): String =
        """{"sessionId":"${id.value}","createdAt":1785000000000,"updatedAt":1785000009999,""" +
            """"projectDir":"$projectDir","taskName":"Do the thing"}""" + "\n"

    // Same payload shape as CodexRolloutScanTest's fixtures (the id key is not read — it comes from
    // the file name — but the two harnesses must model the one real record identically).
    private fun sessionMetaLine(id: ProviderSessionId, cwd: String): String =
        """{"timestamp":"2026-07-29T10:00:00.000Z","type":"session_meta",""" +
            """"payload":{"session_id":"${id.value}","timestamp":"2026-07-29T10:00:00.000Z","cwd":"$cwd"}}""" + "\n"

    private fun writeFile(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), fp) }
        } finally {
            fclose(fp)
        }
        files += path
    }

    private companion object {
        var counter = 0
    }
}
