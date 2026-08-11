package io.kotgent.daemon

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.adapter.shell.ShellAdapter
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

class SessionImportTest {

    private val providerId = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    private val canonicalTmp = canonicalPath("/tmp")!!

    private val untouchableFactory = AgentFactory { kind, _ ->
        throw AssertionError("importSession must never build an adapter (asked for '$kind')")
    }

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


    @Test
    fun shellCanStartButCannotBeImportedAgainstTheSameManager() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val tmux = FakeTmux()
            val shellId = ProviderSessionId("12345678-1234-4234-8234-1234567890ab")
            val builders = mapOf<String, (String) -> AgentAdapter>(
                SHELL_AGENT_KIND to { cwd ->
                    ShellAdapter(cwd, "/bin/zsh", generateSessionId = { shellId })
                },
            )
            val mgr = SessionManager(
                tmux, store, PaneRegistry(), agentFactoryOf(builders),
                ProviderIdCapture(store, this),
                shellVendorStoreProbe(), VendorSessionLocator { _, _ -> null },
                importableAgentKinds(builders.keys),
                newSessionId = { SessionId("shellimp") },
                now = { 42L },
            )

            val started = mgr.start(SHELL_AGENT_KIND, "/tmp")
            val error = assertFailsWith<UnknownAgentKindException> {
                mgr.importSession(SHELL_AGENT_KIND, providerId, cwd = "/tmp")
            }

            assertEquals(SessionState.running, started.state, "the launch factory still accepts shell")
            assertEquals(SHELL_AGENT_KIND, error.agentKind)
            assertEquals(emptySet(), error.supported)
            assertEquals(1, tmux.newSessionCommands.size, "the rejected import has no additional tmux side effect")
        }
    }

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
            assertEquals(canonicalTmp, row.cwd, "the row stores the canonical (realpath) spelling")
            assertNull(row.cliVersion, "no binary was run, so no version")
            assertNull(row.cliPath, "no binary was run, so no path")
            assertNull(row.model, "model arrives only after the first resume")
            assertEquals(42L, row.createdAt)
            assertEquals(42L, row.updatedAt)

            val events = store.read(SessionId("imp00001"), Seq(0))
            assertEquals(1, events.size, "exactly one event: the import's SessionBound")
            assertEquals(AgentEvent.SessionBound(providerId), events[0].event)
            assertEquals(EventSource.system, events[0].source)
            assertEquals(
                SessionState.resumable,
                store.getSession(SessionId("imp00001"))!!.state,
                "the row stays resumable after the SessionBound append",
            )

            assertEquals(listOf(Triple("claude", canonicalTmp, providerId)), probed)

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
            val tmux = FakeTmux()
            val probe = VendorStoreProbe { a, c, id -> a == "claude" && c == canonicalTmp && id == providerId }
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
            entered.await()
            val b = async { runCatching { mgr.importSession("claude", providerId, cwd = "/tmp") } }
            repeat(20) { yield() }
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
            val ids = ArrayDeque(listOf("dup00001", "dup00001", "dup00002"))
            val startProvider = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
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
            startUpsertReached.await()

            val imported = mgr.importSession("codex", providerId, cwd = "/tmp")
            assertEquals(
                SessionId("dup00002"),
                imported.id,
                "the import re-drew instead of stealing the id the start had already allocated",
            )

            releaseStartUpsert.complete(Unit)
            assertEquals(SessionId("dup00001"), start.await().id)

            val startRow = real.getSession(SessionId("dup00001"))!!
            assertEquals(SessionState.running, startRow.state)
            assertEquals(startProvider, startRow.providerSessionId)
            val importRow = real.getSession(SessionId("dup00002"))!!
            assertEquals(SessionState.resumable, importRow.state)
            assertEquals(providerId, importRow.providerSessionId)
            assertTrue(ids.isEmpty(), "the colliding candidate was drawn and rejected, then re-drawn")
        }
    }


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
            assertEquals(canonicalTmp, ex.cwd, "the error names the canonical cwd the probe was keyed on")
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
                mgr.importSession("claude", providerId)
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
            assertEquals(listOf(canonicalTmp), probedCwds, "the probe sees the explicit cwd, canonicalized")
            assertEquals(canonicalTmp, meta.cwd, "the explicit cwd (canonical form) is what the row stores")
        }
    }

    @Test
    fun anExplicitCwdIsCanonicalizedThroughTheFilesystemBeforeTheProbeAndTheRow() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val probedCwds = mutableListOf<String>()
            val mgr = manager(store, probe = VendorStoreProbe { _, c, _ -> probedCwds += c; true })

            val meta = mgr.importSession("claude", providerId, cwd = "/tmp/./")

            assertEquals("/private/tmp", canonicalTmp, "/tmp is a symlink — realpath must cross it")
            assertEquals(canonicalTmp, meta.cwd, "the row stores the canonical form of a messy spelling")
            assertEquals(listOf(canonicalTmp), probedCwds, "the probe was keyed on the canonical form")
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
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(store)

            val ex = assertFailsWith<ImportCwdException> {
                mgr.importSession("claude", providerId, cwd = "/etc/hosts")
            }

            assertTrue(ex.message!!.contains("not a directory"), "the error names the shape: ${ex.message}")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }

    @Test
    fun anUppercaseIdVariantIsNormalizedAndConflictsWithItsLowercaseTwin() = runBlocking {
        withTimeout(20_000) {
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
            val store = SqliteEventStore.inMemory(now = { 42L })
            val mgr = manager(
                store,
                probe = VendorStoreProbe { _, _, _ -> false },
                locator = VendorSessionLocator { _, _ -> "/tmp" },
            )

            val ex = assertFailsWith<TranscriptNotFoundException> {
                mgr.importSession("claude", providerId)
            }

            assertEquals(canonicalTmp, ex.cwd, "the error names the canonical form of the discovered cwd")
            assertTrue(ex.message!!.contains("--cwd"), "…with the --cwd workaround: ${ex.message}")
            assertTrue(store.listSessions().isEmpty(), "no row was created")
        }
    }


    @Test
    fun aBindFailureAfterTheRowCommitLeavesAFunctionalResumableRow() = runBlocking {
        withTimeout(20_000) {
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
            assertFailsWith<DuplicateImportException> { mgr.importSession("claude", providerId, cwd = "/tmp") }
            assertEquals(1, real.listSessions().size, "still exactly one row")
        }
    }


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
            assertEquals(canonicalTmp, meta.cwd, "…with the (canonical) cwd the rollout scan needs")
            assertEquals(1, tmux.newSessionCommands.size, "the resume actually launched")
        }
    }
}
