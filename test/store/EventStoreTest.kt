package io.kotgent.store

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.replay
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * EventStore contract tests (Task 7), driven against [SqliteEventStore] over in-memory SQLite
 * (`:memory:`, like the Task 4 spike). Together they also re-prove the storage pipeline the retired
 * SqlDelightSpikeTest covered: the `.sq` -> plugin codegen -> compiled-into-macosArm64 -> native-driver
 * round-trip (every test exercises the generated Events/Sessions API end to end).
 *
 * Every DB/flow interaction is bounded by [withTimeout] (anti-flaky, mirrors the other suites).
 */
class EventStoreTest {

    private fun meta(id: SessionId, createdAt: Long = 100L): SessionMeta = SessionMeta(
        id = id,
        name = "test-${id.value}",
        agent = "claude",
        cwd = "/tmp/${id.value}",
        tmuxSession = "kt-${id.value}",
        state = SessionState.running,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun appendThenReadRoundTrips() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 42L })
            val sid = SessionId("round-trip")
            store.upsertSession(meta(sid))
            // One of every v1 AgentEvent subtype — proves the payload JSON round-trips each shape.
            val events = listOf(
                AgentEvent.TurnStarted,
                AgentEvent.ApprovalRequested("ap-1"),
                AgentEvent.ApprovalResolved("ap-1", approved = true),
                AgentEvent.ToolCall("grep"),
                AgentEvent.TurnCompleted,
                AgentEvent.SessionBound(ProviderSessionId("22222222-2222-2222-2222-222222222222")),
                AgentEvent.Exited(0),
            )
            events.forEach { store.append(sid, it, EventSource.hook) }

            val read = store.read(sid, Seq(1))
            assertEquals(events, read.map { it.event }, "every event round-trips through the payload column")
            assertEquals((1..events.size).map { it.toLong() }, read.map { it.seq.value }, "contiguous seqs from 1")
            read.forEach {
                assertEquals(42L, it.ts)
                assertEquals(EventSource.hook, it.source)
                assertEquals(sid, it.sessionId)
            }
            // reading from a later cursor returns only the tail
            assertEquals(events.drop(3), store.read(sid, Seq(4)).map { it.event })
            // an unknown session reads empty (not an error)
            assertEquals(emptyList(), store.read(SessionId("unknown"), Seq(0)).map { it.event })
        }
    }

    @Test
    fun seqIsMonotonicPerSessionAndIndependentAcrossSessions() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val a = SessionId("sess-a")
            val b = SessionId("sess-b")
            store.upsertSession(meta(a))
            store.upsertSession(meta(b))

            // interleave appends across the two sessions
            val a1 = store.append(a, AgentEvent.TurnStarted, EventSource.hook)
            val a2 = store.append(a, AgentEvent.ToolCall("x"), EventSource.hook)
            val b1 = store.append(b, AgentEvent.TurnStarted, EventSource.hook)
            val a3 = store.append(a, AgentEvent.TurnCompleted, EventSource.hook)
            val b2 = store.append(b, AgentEvent.ToolCall("y"), EventSource.hook)

            // each session's seq is strictly monotonic and starts at 1, independent of the other
            assertEquals(listOf(1L, 2L, 3L), listOf(a1, a2, a3).map { it.value })
            assertEquals(listOf(1L, 2L), listOf(b1, b2).map { it.value })
            assertEquals(listOf(1L, 2L, 3L), store.read(a, Seq(0)).map { it.seq.value })
            assertEquals(listOf(1L, 2L), store.read(b, Seq(0)).map { it.seq.value })
        }
    }

    @Test
    fun appendIsAtomicWithTheSessionCacheUpdate() = runBlocking {
        withTimeout(20_000) {
            var clock = 1_000L
            val store = SqliteEventStore.inMemory(now = { clock })
            val sid = SessionId("atomic")
            store.upsertSession(meta(sid, createdAt = 500L))

            // ApprovalRequested -> needs_approval, seq 1: the committed sessions row must already
            // reflect the reduced projection (same transaction).
            clock = 1_001L
            store.append(sid, AgentEvent.ApprovalRequested("a1"), EventSource.hook)
            store.getSession(sid)!!.let { m ->
                assertEquals(SessionState.needs_approval, m.state)
                assertEquals(Seq(1), m.lastSeq)
                assertEquals(EventSource.hook, m.stateSource)
                assertEquals(1_001L, m.updatedAt)
                assertEquals(500L, m.createdAt, "created_at is preserved across the cache update")
                assertEquals(store.projectionOf(sid).state, m.state, "cache state == reduced projection")
                assertEquals(store.projectionOf(sid).lastSeq, m.lastSeq, "cache last_seq == reduced projection")
            }

            // ToolCall re-enters running -> clears the pending approval (no "permission answered").
            clock = 1_002L
            store.append(sid, AgentEvent.ToolCall("bash"), EventSource.hook)
            store.getSession(sid)!!.let { m ->
                assertEquals(SessionState.running, m.state)
                assertEquals(Seq(2), m.lastSeq)
            }
            assertEquals(0, store.projectionOf(sid).pendingApprovals)

            // SessionBound captures the provider id into the cache without changing lifecycle.
            val pid = ProviderSessionId("11111111-1111-1111-1111-111111111111")
            clock = 1_003L
            store.append(sid, AgentEvent.SessionBound(pid), EventSource.system)
            store.getSession(sid)!!.let { m ->
                assertEquals(pid, m.providerSessionId)
                assertEquals(SessionState.running, m.state)
                assertEquals(Seq(3), m.lastSeq)
                assertEquals(EventSource.system, m.stateSource)
            }
        }
    }

    @Test
    fun replayFromTheStoreReconstructsTheProjectionAcrossARestart() = runBlocking {
        withTimeout(20_000) {
            // Share one in-memory DB across two store instances to simulate a daemon restart.
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store1 = SqliteEventStore.using(driver, now = { 7L })
            val sid = SessionId("restart")
            store1.upsertSession(meta(sid))
            val events = listOf(
                AgentEvent.TurnStarted,
                AgentEvent.ApprovalRequested("x"),
                AgentEvent.ToolCall("edit"), // clears approval -> running
                AgentEvent.TurnCompleted, // -> ready
                AgentEvent.SessionBound(ProviderSessionId("33333333-3333-3333-3333-333333333333")),
            )
            events.forEach { store1.append(sid, it, EventSource.hook) }

            val expected = replay(events)

            // A brand-new store over the SAME database has cold in-memory caches, so projectionOf
            // must rebuild by replaying the persisted log — and match reducing the events directly.
            val store2 = SqliteEventStore.using(driver, now = { 7L })
            assertEquals(expected, store2.projectionOf(sid), "store replay == core replay")
            store2.getSession(sid)!!.let { m ->
                assertEquals(expected.state, m.state, "persisted cache survives the restart")
                assertEquals(expected.lastSeq, m.lastSeq)
                assertEquals(expected.providerSessionId, m.providerSessionId)
            }
        }
    }

    @Test
    fun subscribeLiveStreamsNewlyAppendedEvents() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val sid = SessionId("subscribe")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.TurnStarted, EventSource.hook) // seq 1 (already stored)

            // Subscribe from the tail (seq 2 == lastSeq+1): the snapshot is empty, so both events
            // below must arrive over the LIVE relay.
            val received = CompletableDeferred<List<StoredEvent>>()
            val job = launch {
                received.complete(store.subscribe(sid, Seq(2)).take(2).toList())
            }
            // Wait until the subscriber has registered so the appends are delivered live, not via snapshot.
            withTimeout(10_000) { while (store.activeSubscribers(sid) == 0) yield() }

            store.append(sid, AgentEvent.ToolCall("a"), EventSource.hook) // seq 2 (live)
            store.append(sid, AgentEvent.TurnCompleted, EventSource.hook) // seq 3 (live)

            val got = withTimeout(10_000) { received.await() }
            assertEquals(listOf(2L, 3L), got.map { it.seq.value })
            assertTrue(got[0].event is AgentEvent.ToolCall)
            assertTrue(got[1].event is AgentEvent.TurnCompleted)
            job.join()
            // the relay is deregistered once the subscription completes
            withTimeout(10_000) { while (store.activeSubscribers(sid) != 0) yield() }
        }
    }

    @Test
    fun subscribeAlsoReplaysAlreadyStoredEventsFromTheCursor() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val sid = SessionId("snapshot")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.TurnStarted, EventSource.hook) // 1
            store.append(sid, AgentEvent.ToolCall("a"), EventSource.hook) // 2
            store.append(sid, AgentEvent.TurnCompleted, EventSource.hook) // 3

            // Subscribing from an earlier cursor first drains the stored snapshot, in order.
            val got = withTimeout(10_000) { store.subscribe(sid, Seq(2)).take(2).toList() }
            assertEquals(listOf(2L, 3L), got.map { it.seq.value })
        }
    }

    @Test
    fun subscribeWithAStaleCursorFailsHard() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val sid = SessionId("stale")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.TurnStarted, EventSource.hook) // lastSeq = 1

            // fromSeq = 3 is beyond lastSeq+1 (=2): a stale/gapped cursor must fail the flow.
            val ex = assertFailsWith<StaleCursorException> {
                withTimeout(10_000) { store.subscribe(sid, Seq(3)).toList() }
            }
            assertEquals(sid, ex.sessionId)
            assertEquals(Seq(3), ex.requested)
            assertEquals(Seq(1), ex.lastSeq)
        }
    }

    @Test
    fun concurrentAppendsAreSerializedIntoAContiguousLog() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val sid = SessionId("concurrent")
            store.upsertSession(meta(sid))
            val n = 50
            // Many callers append at once; the single-writer mutex must serialize them so the log
            // is a contiguous 1..n with no gaps or duplicate seqs.
            coroutineScope {
                repeat(n) { i -> launch { store.append(sid, AgentEvent.ToolCall("t$i"), EventSource.hook) } }
            }
            val seqs = store.read(sid, Seq(0)).map { it.seq.value }
            assertEquals((1..n).map { it.toLong() }, seqs)
            assertEquals(Seq(n.toLong()), store.projectionOf(sid).lastSeq)
            assertEquals(n.toLong(), store.getSession(sid)!!.lastSeq.value)
        }
    }

    @Test
    fun concurrentReadersObserveOnlyCommittedContiguousState() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val sid = SessionId("read-write")
            store.upsertSession(meta(sid))
            coroutineScope {
                val writer = launch {
                    repeat(30) { store.append(sid, AgentEvent.ToolCall("w$it"), EventSource.hook) }
                }
                val reader = launch {
                    repeat(30) {
                        // Any snapshot a concurrent reader sees is a contiguous prefix 1..k — never a
                        // gap or a partially-applied append.
                        val evs = store.read(sid, Seq(0))
                        assertEquals((1..evs.size).map { it.toLong() }, evs.map { it.seq.value })
                        yield()
                    }
                }
                writer.join()
                reader.join()
            }
            assertEquals(30, store.read(sid, Seq(0)).size)
        }
    }
}
