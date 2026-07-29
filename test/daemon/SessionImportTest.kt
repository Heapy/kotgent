package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SessionManager.importSession] TDD — registering a provider session started OUTSIDE kotgent as a
 * `resumable` row + `SessionBound`, with zero tmux side effects. Host-free: [FakeTmux] + in-memory
 * [SqliteEventStore] + fake probe/locator (the real probe/locator wiring is covered by
 * ImportWiringTest over throwaway vendor homes). Separate from [SessionManagerTest] on purpose —
 * that file already carries 30+ launch/control cases.
 *
 * `/tmp` serves as the "existing" project directory for the cwd existence gate (`access(F_OK)` runs
 * for real — stock `platform.posix` links into the test binary fine).
 */
class SessionImportTest {

    private val providerId = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    /**
     * An [AgentFactory] that FAILS the test if import ever touches it: import must never build an
     * adapter — the agent-binary fail-fast belongs to `resume()`, not to a side-effect-free
     * registration.
     */
    private val untouchableFactory = AgentFactory { kind, _ ->
        throw AssertionError("importSession must never build an adapter (asked for '$kind')")
    }

    /** A real factory for the tests that go on to `resume()` (which does build the launch spec). */
    private val catFactory = AgentFactory { _, cwd ->
        object : AgentAdapter {
            override val events: Flow<AgentEvent> = emptyFlow()
            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
                is LaunchMode.New -> LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                is LaunchMode.Resume ->
                    LaunchSpec(listOf("cat", "--resume", mode.providerSessionId.value), emptyMap(), cwd, null)
            }
        }
    }

    private fun CoroutineScope.manager(
        store: EventStore,
        tmux: FakeTmux = FakeTmux(),
        probe: VendorStoreProbe = VendorStoreProbe { _, _, _ -> true },
        locator: VendorSessionLocator = VendorSessionLocator { _, _ -> null },
        kinds: Set<String> = setOf("claude", "codex"),
        factory: AgentFactory = untouchableFactory,
        capture: (SessionMeta) -> Unit = {},
        newSessionId: () -> SessionId = { SessionId("imp00001") },
    ) = SessionManager(
        tmux, store, PaneRegistry(), factory,
        ProviderIdCapture(store, this),
        probe, locator, kinds,
        captureModelInBackground = capture,
        newSessionId = newSessionId,
        now = { 42L },
    )

    // ---- happy path: a full resumable row + SessionBound, with zero tmux side effects ----

    @Test
    fun importRegistersAFullResumableRowAndBindsTheProviderIdWithNoTmuxSideEffects() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            val probed = mutableListOf<Triple<String, String, ProviderSessionId>>()
            val mgr = manager(
                store, tmux,
                probe = VendorStoreProbe { a, c, id -> probed += Triple(a, c, id); true },
            )

            val meta = mgr.importSession("claude", providerId, cwd = "/tmp")

            assertEquals(SessionId("imp00001"), meta.id)
            val row = store.getSession(SessionId("imp00001"))!!
            assertEquals(SessionState.resumable, row.state, "an import lands resumable — never running")
            assertEquals(providerId, row.providerSessionId)
            assertNull(row.paneId, "no pane — nothing was launched")
            assertEquals("kt-imp00001", row.tmuxSession, "tmuxSession from the pure sessionName formatter")
            assertEquals("kt-imp00001", row.name, "name defaults to the tmux session name")
            assertEquals("claude", row.agent)
            assertEquals("/tmp", row.cwd)
            assertNull(row.cliVersion, "no binary was run, so no version")
            assertNull(row.cliPath, "no binary was run, so no path")
            assertNull(row.model, "model arrives only after the first resume")
            assertEquals(42L, row.createdAt)
            assertEquals(42L, row.updatedAt)

            // SessionBound is in the event log (replay stays consistent with the row) …
            val events = store.read(SessionId("imp00001"), Seq(0))
            assertEquals(1, events.size, "exactly one event: the import's SessionBound")
            assertEquals(AgentEvent.SessionBound(providerId), events[0].event)
            assertEquals(EventSource.system, events[0].source)
            // … and the bind append did NOT resurrect the dead cache state.
            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("imp00001"))!!.state,
                "the row stays resumable after the SessionBound append",
            )

            // The probe was asked exactly the (agent, cwd, id) triple that went into the row.
            assertEquals(listOf(Triple("claude", "/tmp", providerId)), probed)

            // ZERO tmux side effects — sessionName is a pure formatter, everything else untouched.
            assertTrue(tmux.newSessionCommands.isEmpty(), "import must not create a tmux session")
            assertTrue(tmux.killed.isEmpty(), "import must not kill anything")
            assertTrue(tmux.sentKeys.isEmpty(), "import must not send keys")
            assertTrue(tmux.copyModeCancels.isEmpty(), "import must not touch pane modes")
        }
    }

    @Test
    fun anImportedSessionStaysResumableThroughReconcile() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux() // no live panes — the imported session has none
            // ONE probe answers BOTH the import and the reconcile, and only for the exact triple the
            // import stores — the discovery↔probe consistency guard: a mismatching cwd would flunk the
            // import instead of silently degrading resumable → crashed on the next daemon start.
            val probe = VendorStoreProbe { a, c, id -> a == "claude" && c == "/tmp" && id == providerId }
            val mgr = manager(store, tmux, probe = probe)
            mgr.importSession("claude", providerId, cwd = "/tmp")

            Reconciler(tmux, store, probe, PaneRegistry(), now = { 43L }).reconcile()

            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("imp00001"))!!.state,
                "reconcile re-probes the same (agent, cwd, id) and keeps the imported session resumable",
            )
        }
    }

    @Test
    fun importOfASupportedKindSucceedsEvenWhenTheAgentBinaryIsMissing() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            // The daemon-under-launchd shape: the kind is supported but its binary does not resolve, so
            // the factory would throw. Import never asks it — the fail-fast stays in resume(), where the
            // `kotgent install` hint belongs.
            val factory = AgentFactory { kind, _ -> throw AgentBinaryNotFoundException(kind) }
            val mgr = manager(store, factory = factory)

            val meta = mgr.importSession(
                "claude", providerId,
                cwd = "/tmp", name = "imported-chat", tags = listOf("a", "b"),
            )

            assertEquals(SessionState.resumable, meta.state, "import passes with the binary missing")
            assertEquals("imported-chat", meta.name, "an explicit name wins over the tmux default")
            assertEquals(listOf("a", "b"), meta.tags)
        }
    }

    // ---- duplicates: 409-shaped conflict, including archived rows and concurrent imports ----

    @Test
    fun aDuplicateProviderIdConflictsAndNamesTheExistingSessionIncludingArchived() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store)
            mgr.importSession("claude", providerId, cwd = "/tmp")

            val again = assertFailsWith<DuplicateImportException> {
                mgr.importSession("claude", providerId, cwd = "/tmp")
            }
            assertEquals(SessionId("imp00001"), again.existingId, "the conflict names the existing session")
            assertFalse(again.archived)

            // Archived rows count too: the right move there is Restore, not a second import.
            store.setArchived(SessionId("imp00001"), true, 43L)
            val archived = assertFailsWith<DuplicateImportException> {
                mgr.importSession("claude", providerId, cwd = "/tmp")
            }
            assertEquals(SessionId("imp00001"), archived.existingId)
            assertTrue(archived.archived, "the conflict flags the duplicate as archived")
            assertTrue(archived.message!!.contains("Restore"), "…and points at Restore: ${archived.message}")

            assertEquals(1, store.listSessions().size, "still exactly one row")
        }
    }

    @Test
    fun twoConcurrentImportsOfTheSameIdYieldExactlyOneRow() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            // Park the FIRST import inside its probe, then start a second import of the same provider
            // id. The daemon-wide import mutex must serialize them: the loser sees the winner's
            // committed row (the duplicate conflict), never a second row.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            val probe = VendorStoreProbe { _, _, _ ->
                if (first) {
                    first = false
                    entered.complete(Unit)
                    release.await()
                }
                true
            }
            val ids = ArrayDeque(listOf("imp00001", "imp00002"))
            val mgr = manager(store, probe = probe, newSessionId = { SessionId(ids.removeFirst()) })

            val a = async { runCatching { mgr.importSession("claude", providerId, cwd = "/tmp") } }
            entered.await() // the first import is mid-flight, holding the import mutex
            val b = async { runCatching { mgr.importSession("claude", providerId, cwd = "/tmp") } }
            repeat(20) { yield() } // give the second import every chance to interleave
            release.complete(Unit)
            val results = listOf(a.await(), b.await())

            assertEquals(1, results.count { it.isSuccess }, "exactly one import wins")
            val loser = results.single { it.isFailure }.exceptionOrNull()
            assertTrue(loser is DuplicateImportException, "the loser gets the duplicate conflict: $loser")
            assertEquals(
                1,
                store.listSessions().count { it.providerSessionId == providerId },
                "exactly one row holds the provider id",
            )
        }
    }

    @Test
    fun aConcurrentImportCannotTakeTheIdAStartHasDrawnButNotYetUpserted() = runBlocking {
        withTimeout(20_000) {
            val real = SqliteEventStore.inMemory(now = { 42L })
            // Park the START's upsert: its session id is allocated but not yet visible to the store —
            // exactly the check-to-upsert window in which only the id reservation can protect it. The
            // import's own (later) upsert must pass through unparked.
            val startUpsertReached = CompletableDeferred<Unit>()
            val releaseStartUpsert = CompletableDeferred<Unit>()
            var parkedOnce = false
            val store = object : EventStore by real {
                override suspend fun upsertSession(meta: SessionMeta) {
                    if (!parkedOnce) {
                        parkedOnce = true
                        startUpsertReached.complete(Unit)
                        releaseStartUpsert.await()
                    }
                    real.upsertSession(meta)
                }
            }
            // The generator INSISTS on the colliding id: the import draws "dup00001" too, and only the
            // reservation — not the store, which does not know the id while the upsert is parked — can
            // make it re-draw.
            val ids = ArrayDeque(listOf("dup00001", "dup00001", "dup00002"))
            val startProvider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            // Preallocated id so start() binds inline (no background capture job to wait out).
            val startFactory = AgentFactory { _, cwd ->
                object : AgentAdapter {
                    override val events: Flow<AgentEvent> = emptyFlow()
                    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                        LaunchSpec(listOf("cat"), emptyMap(), cwd, startProvider)
                }
            }
            val tmux = FakeTmux()
            val mgr = manager(store, tmux, factory = startFactory, newSessionId = { SessionId(ids.removeFirst()) })

            val start = async { mgr.start("claude", "/tmp") }
            startUpsertReached.await() // start holds "dup00001" — drawn and reserved, NOT stored yet

            val imported = mgr.importSession("codex", providerId, cwd = "/tmp")
            assertEquals(
                SessionId("dup00002"),
                imported.id,
                "the import re-drew instead of stealing the id the start had already allocated",
            )

            releaseStartUpsert.complete(Unit)
            assertEquals(SessionId("dup00001"), start.await().id)

            // Two sessions, two untangled rows — the import overwrote neither the row nor the log.
            val startRow = real.getSession(SessionId("dup00001"))!!
            assertEquals(SessionState.running, startRow.state)
            assertEquals(startProvider, startRow.providerSessionId)
            val importRow = real.getSession(SessionId("dup00002"))!!
            assertEquals(SessionState.resumable, importRow.state)
            assertEquals(providerId, importRow.providerSessionId)
            assertTrue(ids.isEmpty(), "the colliding candidate was drawn and rejected, then re-drawn")
        }
    }

    // ---- the failure ladder: kind, cwd discovery, cwd existence, probe ----

    @Test
    fun anUnknownAgentKindIsRejectedBeforeAnythingElse() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            var probeAsked = false
            val mgr = manager(store, tmux, probe = VendorStoreProbe { _, _, _ -> probeAsked = true; true })

            val ex = assertFailsWith<UnknownAgentKindException> {
                mgr.importSession("aider", providerId, cwd = "/tmp")
            }

            assertEquals("aider", ex.agentKind)
            assertEquals(setOf("claude", "codex"), ex.supported)
            assertTrue(ex.message!!.contains("claude, codex"), "the error names the supported kinds")
            assertFalse(probeAsked, "an unknown kind is rejected before any probe")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
            assertTrue(tmux.newSessionCommands.isEmpty() && tmux.killed.isEmpty(), "no tmux side effects")
        }
    }

    @Test
    fun aTranscriptTheProbeCannotSeeFailsNamingTheCwdFlagAndArchivedCodex() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store, probe = VendorStoreProbe { _, _, _ -> false })

            val ex = assertFailsWith<TranscriptNotFoundException> {
                mgr.importSession("codex", providerId, cwd = "/tmp")
            }

            assertEquals("codex", ex.agentKind)
            assertEquals("/tmp", ex.cwd)
            assertTrue(ex.message!!.contains("--cwd"), "the error names the --cwd workaround: ${ex.message}")
            assertTrue(ex.message!!.contains("archived"), "…and archived codex sessions as a cause: ${ex.message}")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun discoveryFindingNoCwdFailsWithTheCwdHint() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store, locator = VendorSessionLocator { _, _ -> null })

            val ex = assertFailsWith<ImportCwdException> {
                mgr.importSession("claude", providerId) // no explicit cwd, discovery finds nothing
            }

            assertTrue(ex.message!!.contains("--cwd"), "the error points at --cwd: ${ex.message}")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun anExplicitCwdWinsOverDiscovery() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            var locatorAsked = false
            val probedCwds = mutableListOf<String>()
            val mgr = manager(
                store,
                probe = VendorStoreProbe { _, c, _ -> probedCwds += c; true },
                locator = VendorSessionLocator { _, _ -> locatorAsked = true; "/somewhere/else" },
            )

            val meta = mgr.importSession("claude", providerId, cwd = "/tmp")

            assertFalse(locatorAsked, "discovery is not consulted when the caller supplies a cwd")
            assertEquals(listOf("/tmp"), probedCwds, "the probe sees the explicit cwd")
            assertEquals("/tmp", meta.cwd, "the explicit cwd is what the row stores")
        }
    }

    @Test
    fun aDeletedProjectDirectoryFailsBeforeTheProbe() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            var probeAsked = false
            val mgr = manager(store, probe = VendorStoreProbe { _, _, _ -> probeAsked = true; true })
            val gone = "/nonexistent/kotgent-import-test-dir"

            val ex = assertFailsWith<ImportCwdException> {
                mgr.importSession("claude", providerId, cwd = gone)
            }

            assertTrue(ex.message!!.contains(gone), "the error names the missing directory: ${ex.message}")
            assertFalse(probeAsked, "a missing project dir fails before the transcript probe")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun aRelativeCwdIsRejectedAtTheDaemon() = runBlocking {
        withTimeout(20_000) {
            // The CLI resolves relative paths client-side, but the Web UI / any API client can send one
            // raw. The daemon lives under launchd with cwd `/`, so access(F_OK) on "tmp" would answer
            // for "/tmp" — and for codex the probe ignores cwd entirely, so the raw relative string
            // would be stored and later mis-resolved by resume's `tmux new-session -c`.
            val store = SqliteEventStore.inMemory(now = { 42L })
            var probeAsked = false
            val mgr = manager(store, probe = VendorStoreProbe { _, _, _ -> probeAsked = true; true })

            val ex = assertFailsWith<ImportCwdException> {
                mgr.importSession("codex", providerId, cwd = "tmp")
            }

            assertTrue(ex.message!!.contains("absolute"), "the error demands an absolute path: ${ex.message}")
            assertFalse(probeAsked, "a relative cwd fails before the transcript probe")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun aCwdThatIsAPlainFileIsRejected() = runBlocking {
        withTimeout(20_000) {
            // access(F_OK) alone accepted a file; tmux `new-session -c` would only trip over it at
            // resume time, long after the row was stored.
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store)

            val ex = assertFailsWith<ImportCwdException> {
                mgr.importSession("claude", providerId, cwd = "/etc/hosts") // exists, but is a file
            }

            assertTrue(ex.message!!.contains("not a directory"), "the error names the shape: ${ex.message}")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun anUppercaseIdVariantIsNormalizedAndConflictsWithItsLowercaseTwin() = runBlocking {
        withTimeout(20_000) {
            // macOS's default FS is case-insensitive, so an uppercase re-casing of the same UUID finds
            // the same on-disk transcript — it must land as ONE session (stored lowercase, matching
            // what hooks later report), never as two rows for one conversation.
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store)
            val upper = ProviderSessionId(providerId.value.uppercase())

            val meta = mgr.importSession("claude", upper, cwd = "/tmp")
            assertEquals(providerId, meta.providerSessionId, "the stored id is normalized to lowercase")

            val dup = assertFailsWith<DuplicateImportException> {
                mgr.importSession("claude", providerId, cwd = "/tmp")
            }
            assertEquals(SessionId("imp00001"), dup.existingId, "the lowercase twin is the same session")
            assertEquals(1, store.listSessions().size, "one conversation, one row")
        }
    }

    @Test
    fun aDiscoveredCwdThatFailsTheProbeFailsLoudlyNotSilently() = runBlocking {
        withTimeout(20_000) {
            // The claude mismatch shape: discovery found the transcript and read its recorded cwd, but
            // that cwd re-encodes into a different project dir (/tmp vs /private/tmp), so the probe —
            // the same question the Reconciler will re-ask — cannot see it. This must be a loud error
            // naming --cwd, never a session that silently degrades resumable → crashed after restart.
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(
                store,
                probe = VendorStoreProbe { _, _, _ -> false },
                locator = VendorSessionLocator { _, _ -> "/tmp" }, // exists, but flunks the probe
            )

            val ex = assertFailsWith<TranscriptNotFoundException> {
                mgr.importSession("claude", providerId) // no explicit cwd — discovery answers
            }

            assertEquals("/tmp", ex.cwd, "the error names the cwd discovery settled on")
            assertTrue(ex.message!!.contains("--cwd"), "…with the --cwd workaround: ${ex.message}")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    // ---- the accepted bind residual (see importSession's KDoc), pinned ----

    @Test
    fun aBindFailureAfterTheRowCommitLeavesAFunctionalResumableRow() = runBlocking {
        withTimeout(20_000) {
            // idCapture.bind fails AFTER upsertSession committed. The exception surfaces (the route
            // would answer 500, and the client's retry then gets the duplicate conflict), but the row
            // survives carrying the provider id — resume() reads the row, so the session stays
            // functional; only the event log misses its SessionBound (the recorded replay divergence).
            val real = SqliteEventStore.inMemory(now = { 42L })
            val failing = object : EventStore by real {
                override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq =
                    throw IllegalStateException("simulated bind-append failure")
            }
            val mgr = manager(failing)

            assertFailsWith<IllegalStateException> { mgr.importSession("claude", providerId, cwd = "/tmp") }

            val row = real.getSession(SessionId("imp00001"))!!
            assertEquals(SessionState.resumable, row.state, "the committed row survives the bind failure")
            assertEquals(providerId, row.providerSessionId, "…carrying the provider id resume() needs")
            assertTrue(
                real.read(SessionId("imp00001"), Seq(0)).isEmpty(),
                "the log has no SessionBound — the KDoc's accepted replay divergence",
            )
            // A retry of the same import sees the surviving row: the duplicate conflict, not a twin.
            assertFailsWith<DuplicateImportException> { mgr.importSession("claude", providerId, cwd = "/tmp") }
            assertEquals(1, real.listSessions().size, "still exactly one row")
        }
    }

    // ---- resume() picks up the model of an imported (codex) session ----

    @Test
    fun resumeInvokesTheBackgroundModelCaptureForTheRevivedSession() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            val captured = CompletableDeferred<SessionMeta>()
            val mgr = manager(
                store, tmux,
                factory = catFactory,
                capture = { meta -> captured.complete(meta) },
            )
            mgr.importSession("codex", providerId, cwd = "/tmp")
            assertFalse(captured.isCompleted, "import itself must not run model capture — nothing launched yet")

            mgr.resume(SessionId("imp00001"))

            val meta = captured.await()
            assertEquals(SessionId("imp00001"), meta.id, "resume wires model capture for the revived session")
            assertEquals("codex", meta.agent)
            assertEquals("/tmp", meta.cwd, "…with the cwd the rollout scan needs")
            assertEquals(1, tmux.newSessionCommands.size, "the resume actually launched")
        }
    }
}
