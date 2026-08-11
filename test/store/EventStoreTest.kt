package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.replay
import io.kotgent.core.unread
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            assertEquals(events.drop(3), store.read(sid, Seq(4)).map { it.event })
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

            val a1 = store.append(a, AgentEvent.TurnStarted, EventSource.hook)
            val a2 = store.append(a, AgentEvent.ToolCall("x"), EventSource.hook)
            val b1 = store.append(b, AgentEvent.TurnStarted, EventSource.hook)
            val a3 = store.append(a, AgentEvent.TurnCompleted, EventSource.hook)
            val b2 = store.append(b, AgentEvent.ToolCall("y"), EventSource.hook)

            assertEquals(listOf(1L, 2L, 3L), listOf(a1, a2, a3).map { it.value })
            assertEquals(listOf(1L, 2L), listOf(b1, b2).map { it.value })
            assertEquals(listOf(1L, 2L, 3L), store.read(a, Seq(0)).map { it.seq.value })
            assertEquals(listOf(1L, 2L), store.read(b, Seq(0)).map { it.seq.value })
        }
    }

    @Test
    fun updateSessionStateDoesNotClobberLastSeqOrProviderId() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 7L })
            val sid = SessionId("ctl")
            store.upsertSession(meta(sid))
            val pid = ProviderSessionId("33333333-3333-3333-3333-333333333333")
            store.append(sid, AgentEvent.SessionBound(pid), EventSource.hook)
            store.append(sid, AgentEvent.ApprovalRequested("a1"), EventSource.hook)
            assertEquals(Seq(2), store.getSession(sid)!!.lastSeq)

            store.updateSessionState(sid, SessionState.ready, EventSource.user, PaneId("%7"), 9L)
            store.getSession(sid)!!.let { m ->
                assertEquals(SessionState.ready, m.state, "control state applied")
                assertEquals(EventSource.user, m.stateSource)
                assertEquals(PaneId("%7"), m.paneId)
                assertEquals(9L, m.updatedAt)
                assertEquals(Seq(2), m.lastSeq, "last_seq is NOT clobbered by the control write")
                assertEquals(pid, m.providerSessionId, "provider id is NOT dropped by the control write")
            }
        }
    }

    @Test
    fun aStrayAppendDoesNotResurrectAControlStoppedSession() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("stopres")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.ToolCall("x"), EventSource.hook)

            store.updateSessionState(sid, SessionState.stopped, EventSource.user, PaneId("%1"), 5L)
            assertEquals(SessionState.stopped, store.getSession(sid)!!.state)

            val pid = ProviderSessionId("44444444-4444-4444-4444-444444444444")
            store.append(sid, AgentEvent.SessionBound(pid), EventSource.hook)
            store.getSession(sid)!!.let { m ->
                assertEquals(SessionState.stopped, m.state, "a stray append must not resurrect a stopped session")
                assertEquals(Seq(2), m.lastSeq, "the event is still recorded (last_seq advances)")
                assertEquals(pid, m.providerSessionId, "a late SessionBound still records the provider id")
            }
            assertEquals(SessionState.running, store.projectionOf(sid).state, "the event-log projection stays pure")
        }
    }

    @Test
    fun appendIsAtomicWithTheSessionCacheUpdate() = runBlocking {
        withTimeout(20_000) {
            var clock = 1_000L
            val store = SqliteEventStore.inMemory(now = { clock })
            val sid = SessionId("atomic")
            store.upsertSession(meta(sid, createdAt = 500L))

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

            clock = 1_002L
            store.append(sid, AgentEvent.ToolCall("bash"), EventSource.hook)
            store.getSession(sid)!!.let { m ->
                assertEquals(SessionState.running, m.state)
                assertEquals(Seq(2), m.lastSeq)
            }
            assertEquals(0, store.projectionOf(sid).pendingApprovals)

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
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store1 = SqliteEventStore.using(driver, now = { 7L })
            val sid = SessionId("restart")
            store1.upsertSession(meta(sid))
            val events = listOf(
                AgentEvent.TurnStarted,
                AgentEvent.ApprovalRequested("x"),
                AgentEvent.ToolCall("edit"),
                AgentEvent.TurnCompleted,
                AgentEvent.SessionBound(ProviderSessionId("33333333-3333-3333-3333-333333333333")),
            )
            events.forEach { store1.append(sid, it, EventSource.hook) }

            val expected = replay(events)

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
            store.append(sid, AgentEvent.TurnStarted, EventSource.hook)

            val received = CompletableDeferred<List<StoredEvent>>()
            val job = launch {
                received.complete(store.subscribe(sid, Seq(2)).take(2).toList())
            }
            withTimeout(10_000) { while (store.activeSubscribers(sid) == 0) yield() }

            store.append(sid, AgentEvent.ToolCall("a"), EventSource.hook)
            store.append(sid, AgentEvent.TurnCompleted, EventSource.hook)

            val got = withTimeout(10_000) { received.await() }
            assertEquals(listOf(2L, 3L), got.map { it.seq.value })
            assertTrue(got[0].event is AgentEvent.ToolCall)
            assertTrue(got[1].event is AgentEvent.TurnCompleted)
            job.join()
            withTimeout(10_000) { while (store.activeSubscribers(sid) != 0) yield() }
        }
    }

    @Test
    fun subscribeAlsoReplaysAlreadyStoredEventsFromTheCursor() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 0L })
            val sid = SessionId("snapshot")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.TurnStarted, EventSource.hook)
            store.append(sid, AgentEvent.ToolCall("a"), EventSource.hook)
            store.append(sid, AgentEvent.TurnCompleted, EventSource.hook)

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
            store.append(sid, AgentEvent.TurnStarted, EventSource.hook)

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


    @Test
    fun reliableSessionUpdatesPreserveCommittedOrderAndBackpressureWriters() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("reliable-updates")

            store.upsertSession(meta(sid))

            val firstSeen = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val states = mutableListOf<SessionState>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                store.reliableSessionUpdates.take(2).collect { update ->
                    states += update.state
                    if (states.size == 1) {
                        firstSeen.complete(Unit)
                        releaseFirst.await()
                    }
                }
            }

            val firstWrite = async(start = CoroutineStart.UNDISPATCHED) {
                store.updateSessionState(
                    sid, SessionState.needs_approval, EventSource.system, paneId = null, updatedAt = 2L,
                )
            }
            firstSeen.await()
            firstWrite.await()

            val secondWrite = async(start = CoroutineStart.UNDISPATCHED) {
                store.updateSessionState(
                    sid, SessionState.running, EventSource.system, paneId = null, updatedAt = 3L,
                )
            }
            assertTrue(!secondWrite.isCompleted, "a lagging reliable subscriber backpressures the next writer")

            releaseFirst.complete(Unit)
            secondWrite.await()
            collector.join()
            assertEquals(
                listOf(SessionState.needs_approval, SessionState.running),
                states,
                "the reliable signal preserves committed mutation order",
            )
        }
    }


    @Test
    fun archivedRoundTripsThroughUpsertAndGet() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("arch01")
            store.upsertSession(meta(sid))
            assertEquals(false, store.getSession(sid)!!.archived, "a fresh session is not archived")

            store.upsertSession(meta(sid).copy(archived = true))
            assertTrue(store.getSession(sid)!!.archived, "archived = true round-trips")

            store.upsertSession(meta(sid).copy(archived = false))
            assertEquals(false, store.getSession(sid)!!.archived, "and back to false")
        }
    }

    @Test
    fun setArchivedFlipsTheFlagAndEmitsAnUpdate() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("arch02")
            store.upsertSession(meta(sid))

            val seen = CompletableDeferred<Boolean>()
            val collector = launch {
                store.sessionUpdates.take(1).toList().firstOrNull()?.let { seen.complete(it.archived) }
            }
            yield()
            store.setArchived(sid, true, 2L)
            assertTrue(store.getSession(sid)!!.archived, "setArchived persisted the flag")
            assertTrue(seen.await(), "the emitted SessionUpdate carries archived = true")
            collector.join()

            store.setArchived(sid, false, 3L)
            assertEquals(false, store.getSession(sid)!!.archived, "setArchived(false) clears it")
        }
    }

    @Test
    fun theInitMigrationAddsArchivedToAPreExistingTable() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(preArchivedSchema)
            val store = SqliteEventStore.using(driver, now = { 1L })
            val sid = SessionId("mig01")
            store.upsertSession(meta(sid))
            assertEquals(false, store.getSession(sid)!!.archived, "migrated column defaults to false")

            store.setArchived(sid, true, 2L)
            assertTrue(store.getSession(sid)!!.archived, "…and is writable after the migration")

            val reopened = SqliteEventStore.using(driver, now = { 3L })
            assertTrue(reopened.getSession(sid)!!.archived, "a second open over the migrated DB still reads")
        }
    }

    @Test
    fun theRevisionIsMigratedStampedOnEveryWriteAndSurvivesReopen() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(preArchivedSchema)
            val store = SqliteEventStore.using(driver, now = { 1L })
            val sid = SessionId("rev01")
            store.upsertSession(meta(sid))
            val first = store.getSession(sid)!!.rev
            assertTrue(first > 0, "the store stamps a positive revision on the first write")

            store.setArchived(sid, true, 2L)
            val second = store.getSession(sid)!!.rev
            assertTrue(second > first, "a targeted mutator advances the revision")

            store.append(sid, AgentEvent.TurnStarted, EventSource.hook)
            val third = store.getSession(sid)!!.rev
            assertTrue(third > second, "append's cache update advances the revision too")

            val reopened = SqliteEventStore.using(driver, now = { 3L })
            reopened.markRead(sid, Seq(0))
            val fourth = reopened.getSession(sid)!!.rev
            assertTrue(fourth > third, "the reopened counter continues past the persisted maximum")
        }
    }


    @Test
    fun setModelForProviderWritesOnlyWhileTheRowStillHoldsThatId() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("model01")
            val scanned = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            val hook = ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

            assertFalse(store.setModelForProvider(sid, scanned, "gpt-6", 2L), "no row: nothing written")

            store.upsertSession(meta(sid))
            assertFalse(store.setModelForProvider(sid, scanned, "gpt-6", 2L), "a NULL provider id never matches")
            assertNull(store.getSession(sid)!!.model)

            store.append(sid, AgentEvent.SessionBound(scanned), EventSource.system)
            assertTrue(store.setModelForProvider(sid, scanned, "gpt-6", 3L), "the held id matches: written")
            assertEquals("gpt-6", store.getSession(sid)!!.model)

            store.append(sid, AgentEvent.SessionBound(hook), EventSource.hook)
            store.setModel(sid, null, 4L)
            assertFalse(
                store.setModelForProvider(sid, scanned, "gpt-6", 5L),
                "a write conditioned on the displaced id is refused",
            )
            assertNull(store.getSession(sid)!!.model, "the rebind's clear survives the raced capture")

            assertTrue(store.setModelForProvider(sid, hook, "gpt-5.5", 6L))
            assertEquals("gpt-5.5", store.getSession(sid)!!.model)
        }
    }


    @Test
    fun markReadAdvancesTheCursorAndLeavesEverythingElseAlone() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 500L })
            val sid = SessionId("rc01")
            store.upsertSession(meta(sid, createdAt = 100L))
            val pid = ProviderSessionId("55555555-5555-5555-5555-555555555555")
            store.append(sid, AgentEvent.SessionBound(pid), EventSource.hook)
            store.append(sid, AgentEvent.ApprovalRequested("a1"), EventSource.hook)
            val before = store.getSession(sid)!!
            assertEquals(Seq(0), before.readCursor, "nothing is read until a client says so")
            assertEquals(2L, unread(before.lastSeq.value, before.readCursor.value), "the badge counts both events")

            store.markRead(sid, Seq(2))
            store.getSession(sid)!!.let { m ->
                assertEquals(Seq(2), m.readCursor, "the cursor advanced to the seq the client displayed")
                assertEquals(0L, unread(m.lastSeq.value, m.readCursor.value), "the badge is cleared")
                assertEquals(before.state, m.state, "markRead never touches state")
                assertEquals(before.lastSeq, m.lastSeq, "markRead never touches last_seq")
                assertEquals(before.providerSessionId, m.providerSessionId, "markRead never touches the provider id")
                assertEquals(before.updatedAt, m.updatedAt, "viewing is not activity: updated_at is NOT written")
            }
        }
    }

    @Test
    fun markReadIsMonotonicSoALateOrRetriedMarkCannotRegressTheBadge() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc02")
            store.upsertSession(meta(sid))
            repeat(3) { store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }

            store.markRead(sid, Seq(3))
            assertEquals(Seq(3), store.getSession(sid)!!.readCursor)
            store.markRead(sid, Seq(1))
            assertEquals(Seq(3), store.getSession(sid)!!.readCursor, "MAX() keeps the cursor at the high-water mark")
        }
    }

    @Test
    fun twoClientsMarkingReadConcurrentlyLeaveTheCursorAtTheHighestSeq() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc08")
            store.upsertSession(meta(sid))
            repeat(3) { store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }

            coroutineScope {
                launch { store.markRead(sid, Seq(3)) }
                launch { store.markRead(sid, Seq(1)) }
            }
            assertEquals(
                Seq(3),
                store.getSession(sid)!!.readCursor,
                "whichever order the writer lock granted, the cursor lands on the highest seq",
            )
        }
    }

    @Test
    fun markReadIsClampedToLastSeqSoABogusSeqCannotSilenceTheBadge() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc03")
            store.upsertSession(meta(sid))
            repeat(3) { store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }

            store.markRead(sid, Seq(999))
            assertEquals(Seq(3), store.getSession(sid)!!.readCursor, "MIN() clamps the cursor to the log")

            store.append(sid, AgentEvent.TurnCompleted, EventSource.hook)
            store.getSession(sid)!!.let { m ->
                assertEquals(1L, unread(m.lastSeq.value, m.readCursor.value), "the next event raises the badge again")
            }
        }
    }

    @Test
    fun markReadEmitsAnUpdateCarryingUnreadZeroAndTheRowsArchivedFlag() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc04")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.ToolCall("x"), EventSource.hook)
            store.setArchived(sid, true, 2L)

            val seen = CompletableDeferred<SessionUpdate>()
            val collector = launch { store.sessionUpdates.take(1).toList().firstOrNull()?.let { seen.complete(it) } }
            yield()
            store.markRead(sid, Seq(1))

            val update = seen.await()
            assertEquals(sid, update.sessionId)
            assertEquals(0L, update.unread, "the badge clears in every connected client")
            assertEquals(Seq(1), update.lastSeq)
            assertTrue(update.archived, "the signal carries the row's archived — it must not un-hide the session")
            collector.join()
        }
    }

    @Test
    fun anAppendAndAControlStateWriteOnAnArchivedSessionAlsoCarryArchived() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc09")
            store.upsertSession(meta(sid))
            store.append(sid, AgentEvent.ToolCall("x"), EventSource.hook)
            store.setArchived(sid, true, 2L)

            val seen = CompletableDeferred<List<SessionUpdate>>()
            val collector = launch { seen.complete(store.sessionUpdates.take(2).toList()) }
            yield()
            store.append(sid, AgentEvent.ApprovalRequested("perm-1"), EventSource.hook)
            store.updateSessionState(sid, SessionState.stopped, EventSource.system, null, 3L)

            val (appended, controlled) = seen.await()
            assertEquals(Seq(2), appended.lastSeq)
            assertEquals(2L, appended.unread, "the badge still counts the unread event")
            assertTrue(appended.archived, "an append on a done session must not un-hide it")
            assertEquals(SessionState.stopped, controlled.state)
            assertTrue(controlled.archived, "a control-state write on a done session must not un-hide it")
            collector.join()
        }
    }

    @Test
    fun aNoOpMarkReadStillEmitsSoALostPostIsResynchronized() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc05")
            store.upsertSession(meta(sid))
            repeat(2) { store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }
            store.markRead(sid, Seq(2))

            val seen = CompletableDeferred<SessionUpdate>()
            val collector = launch { store.sessionUpdates.take(1).toList().firstOrNull()?.let { seen.complete(it) } }
            yield()
            store.markRead(sid, Seq(1))

            assertEquals(0L, seen.await().unread, "a no-op markRead still broadcasts the current state")
            assertEquals(Seq(2), store.getSession(sid)!!.readCursor)
            collector.join()
        }
    }

    @Test
    fun aClearedBadgeSurvivesADaemonRestart() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store1 = SqliteEventStore.using(driver, now = { 1L })
            val sid = SessionId("rc07")
            store1.upsertSession(meta(sid))
            repeat(3) { store1.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }
            store1.markRead(sid, Seq(3))

            val store2 = SqliteEventStore.using(driver, now = { 1L })
            store2.getSession(sid)!!.let { m ->
                assertEquals(Seq(3), m.readCursor, "the cursor is persisted, not in-memory state")
                assertEquals(0L, unread(m.lastSeq.value, m.readCursor.value), "so the badge stays cleared")
            }
            store2.append(sid, AgentEvent.TurnCompleted, EventSource.hook)
            store2.getSession(sid)!!.let { m ->
                assertEquals(1L, unread(m.lastSeq.value, m.readCursor.value), "one new event, one unread")
            }
        }
    }

    @Test
    fun anAppendPastTheCursorBroadcastsTheRecomputedUnreadNotTheWholeLog() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc10")
            store.upsertSession(meta(sid))
            repeat(3) { store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }
            store.markRead(sid, Seq(3))

            val seen = CompletableDeferred<SessionUpdate>()
            val collector = launch { store.sessionUpdates.take(1).toList().firstOrNull()?.let { seen.complete(it) } }
            yield()
            store.append(sid, AgentEvent.TurnCompleted, EventSource.hook)

            val update = seen.await()
            assertEquals(Seq(4), update.lastSeq)
            assertEquals(1L, update.unread, "one event past the cursor, not the whole log")
            collector.join()
        }
    }

    @Test
    fun markReadOnAnUnknownSessionIsASilentNoOp() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val missing = SessionId("no-such-session")
            val updates = ArrayList<SessionUpdate>()
            val collector = launch { store.sessionUpdates.collect { updates.add(it) } }
            yield()
            store.markRead(missing, Seq(5))
            repeat(20) { yield() }

            assertEquals(null, store.getSession(missing), "and creates no row")
            assertTrue(updates.isEmpty(), "and broadcasts nothing for a session that does not exist: $updates")
            collector.cancel()
        }
    }

    @Test
    fun aStaleFullRowUpsertNeitherRegressesTheCursorNorBroadcastsAStaleUnread() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory(now = { 1L })
            val sid = SessionId("rc06")
            store.upsertSession(meta(sid))
            repeat(3) { store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }
            store.markRead(sid, Seq(3))

            val seen = CompletableDeferred<SessionUpdate>()
            val collector = launch { store.sessionUpdates.take(1).toList().firstOrNull()?.let { seen.complete(it) } }
            yield()
            store.upsertSession(meta(sid).copy(lastSeq = Seq(3), readCursor = Seq(0)))

            assertEquals(Seq(3), store.getSession(sid)!!.readCursor, "a stale full-row write never regresses it")
            assertEquals(0L, seen.await().unread, "the emit is built from the corrected row, not the stale meta")
            collector.join()
        }
    }

    private val preArchivedSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (session_id, seq))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, tags TEXT NOT NULL, " +
                    "agent TEXT NOT NULL, provider_session_id TEXT, model TEXT, cli_version TEXT, cli_path TEXT, " +
                    "cwd TEXT NOT NULL, repository TEXT, worktree TEXT, branch TEXT, tmux_session TEXT NOT NULL, " +
                    "pane_id TEXT, state TEXT NOT NULL, state_source TEXT, last_seq INTEGER NOT NULL, " +
                    "read_cursor INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                0,
            )
            driver.execute(null, "CREATE INDEX events_session_seq ON events(session_id, seq)", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }
}
