package io.kotgent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val providerId = ProviderSessionId(uuid)

    private val running: Projection = reduce(Projection.EMPTY, AgentEvent.TurnStarted)

    private val blocked: Projection = reduce(running, AgentEvent.ApprovalRequested("appr-1"))


    @Test
    fun emptyProjectionIsRunningAliveWithNoApprovals() {
        val p = Projection.EMPTY
        assertEquals(SessionState.running, p.state, "a just-created session is live/running")
        assertEquals(0, p.pendingApprovals)
        assertEquals(Seq(0), p.lastSeq, "no events applied yet")
        assertNull(p.providerSessionId, "provider id unknown until SessionBound")
        assertFalse(p.stopRequested, "no clean-stop intent armed")
    }


    @Test
    fun startEventProducesRunning() {
        val p = reduce(Projection.EMPTY, AgentEvent.TurnStarted)
        assertEquals(SessionState.running, p.state)
        assertEquals(Seq(1), p.lastSeq, "the first event advances lastSeq to 1")
    }

    @Test
    fun toolCallIsAlsoARunningProducer() {
        val ready = reduce(running, AgentEvent.TurnCompleted)
        assertEquals(SessionState.ready, ready.state)
        val p = reduce(ready, AgentEvent.ToolCall("Bash"))
        assertEquals(SessionState.running, p.state)
    }

    @Test
    fun approvalRequestedProducesNeedsApprovalAndIncrements() {
        val p1 = reduce(running, AgentEvent.ApprovalRequested("appr-1"))
        assertEquals(SessionState.needs_approval, p1.state)
        assertEquals(1, p1.pendingApprovals)
        assertTrue(p1.needsAttention, "needs_approval surfaces as needs-attention")

        val p2 = reduce(p1, AgentEvent.ApprovalRequested("appr-2"))
        assertEquals(SessionState.needs_approval, p2.state)
        assertEquals(2, p2.pendingApprovals)
    }

    @Test
    fun turnCompletedProducesReady() {
        val p = reduce(running, AgentEvent.TurnCompleted)
        assertEquals(SessionState.ready, p.state)
        assertFalse(p.needsAttention)
    }

    @Test
    fun exitedZeroProducesStoppedAndNonZeroProducesCrashed() {
        assertEquals(SessionState.stopped, reduce(running, AgentEvent.Exited(0)).state)
        assertEquals(SessionState.crashed, reduce(running, AgentEvent.Exited(1)).state)
        assertEquals(SessionState.crashed, reduce(running, AgentEvent.Exited(137)).state)
        assertTrue(reduce(running, AgentEvent.Exited(0)).state.isDead)
        assertTrue(reduce(running, AgentEvent.Exited(1)).state.isDead)
    }

    @Test
    fun sessionBoundRecordsProviderIdWithoutChangingLifecycleState() {
        val p = reduce(running, AgentEvent.SessionBound(providerId))
        assertEquals(SessionState.running, p.state, "SessionBound does not change lifecycle state")
        assertEquals(providerId, p.providerSessionId)
        assertEquals(running.lastSeq.next(), p.lastSeq, "SessionBound still advances the event seq")

        val boundWhileBlocked = reduce(blocked, AgentEvent.SessionBound(providerId))
        assertEquals(SessionState.needs_approval, boundWhileBlocked.state)
        assertEquals(1, boundWhileBlocked.pendingApprovals)
        assertEquals(providerId, boundWhileBlocked.providerSessionId)
    }


    @Test
    fun enteringRunningViaToolCallClearsPendingApprovals() {
        assertEquals(SessionState.needs_approval, blocked.state)
        assertEquals(1, blocked.pendingApprovals)

        val resumed = reduce(blocked, AgentEvent.ToolCall("Bash"))
        assertEquals(SessionState.running, resumed.state, "re-entering running clears needs_approval")
        assertEquals(0, resumed.pendingApprovals, "entering running RESETS pendingApprovals to 0")
    }

    @Test
    fun enteringRunningViaTurnStartedClearsPendingApprovals() {
        val doubleBlocked = reduce(blocked, AgentEvent.ApprovalRequested("appr-2"))
        assertEquals(2, doubleBlocked.pendingApprovals)

        val resumed = reduce(doubleBlocked, AgentEvent.TurnStarted)
        assertEquals(SessionState.running, resumed.state)
        assertEquals(0, resumed.pendingApprovals, "all pending approvals cleared on running-entry")
    }

    @Test
    fun approvalResolvedDecrementsAndReturnsToRunningWhenLastCleared() {
        val doubleBlocked = reduce(blocked, AgentEvent.ApprovalRequested("appr-2"))
        assertEquals(2, doubleBlocked.pendingApprovals)

        val oneLeft = reduce(doubleBlocked, AgentEvent.ApprovalResolved("appr-1", approved = true))
        assertEquals(SessionState.needs_approval, oneLeft.state, "still blocked while an approval remains")
        assertEquals(1, oneLeft.pendingApprovals)

        val cleared = reduce(oneLeft, AgentEvent.ApprovalResolved("appr-2", approved = false))
        assertEquals(SessionState.running, cleared.state, "last approval resolved -> back to running")
        assertEquals(0, cleared.pendingApprovals)

        val underflow = reduce(cleared, AgentEvent.ApprovalResolved("stray", approved = true))
        assertEquals(0, underflow.pendingApprovals, "pendingApprovals floors at 0")
    }


    @Test
    fun interruptResetsAStuckRunning() {
        assertEquals(SessionState.running, running.state)
        val interrupted = reduce(running, ControlSignal.Interrupt)
        assertEquals(SessionState.ready, interrupted.state, "interrupt un-sticks running -> ready")
        assertEquals(0, interrupted.pendingApprovals)
    }

    @Test
    fun interruptCancelsABlockedApprovalToo() {
        val interrupted = reduce(blocked, ControlSignal.Interrupt)
        assertEquals(SessionState.ready, interrupted.state)
        assertEquals(0, interrupted.pendingApprovals, "interrupt clears pending approvals")
    }

    @Test
    fun interruptOnADeadSessionIsANoOp() {
        val dead = reduce(running, AgentEvent.Exited(0))
        assertEquals(dead, reduce(dead, ControlSignal.Interrupt), "cannot interrupt a dead process")
    }

    @Test
    fun detachIsANoOpAcrossAllStates() {
        for (p in listOf(Projection.EMPTY, running, blocked,
                reduce(running, AgentEvent.TurnCompleted), reduce(running, AgentEvent.Exited(2)))) {
            assertEquals(p, reduce(p, ControlSignal.Detach), "detach must not change state for $p")
        }
    }

    @Test
    fun controlSignalsDoNotAdvanceLastSeq() {
        assertEquals(running.lastSeq, reduce(running, ControlSignal.Interrupt).lastSeq)
        assertEquals(running.lastSeq, reduce(running, ControlSignal.Stop).lastSeq)
        assertEquals(running.lastSeq, reduce(running, ControlSignal.Detach).lastSeq)
        val dead = reduce(running, AgentEvent.Exited(0))
        assertEquals(dead.lastSeq, reduce(dead, ControlSignal.Resume).lastSeq)
    }

    @Test
    fun stopIntentReclassifiesANonZeroExitAsStopped() {
        assertEquals(SessionState.crashed, reduce(running, AgentEvent.Exited(143)).state)

        val stopping = reduce(running, ControlSignal.Stop)
        assertTrue(stopping.stopRequested, "Stop arms the clean-termination intent")
        assertEquals(SessionState.running, stopping.state, "Stop only records intent; state waits for Exited")
        val stopped = reduce(stopping, AgentEvent.Exited(143))
        assertEquals(SessionState.stopped, stopped.state, "intended termination -> stopped, not crashed")
        assertFalse(stopped.stopRequested, "the intent is consumed by Exited")
    }

    @Test
    fun resumeRevivesADeadSessionToReady() {
        for (exit in listOf(0, 1)) {
            val dead = reduce(running, AgentEvent.Exited(exit))
            assertTrue(dead.state.isDead)
            val revived = reduce(dead, ControlSignal.Resume)
            assertEquals(SessionState.ready, revived.state, "resume revives a dead session -> ready")
            assertEquals(0, revived.pendingApprovals)
        }
        assertEquals(running, reduce(running, ControlSignal.Resume))
    }


    @Test
    fun unreadIsDerivedFromLastSeqAgainstAReadCursor() {
        val p = replay(listOf(AgentEvent.TurnStarted, AgentEvent.ToolCall("Read"), AgentEvent.TurnCompleted))
        assertEquals(Seq(3), p.lastSeq)
        assertEquals(3L, p.unread(Seq(0)), "nothing read -> all 3 unread")
        assertEquals(1L, p.unread(Seq(2)), "read through seq 2 -> 1 unread")
        assertEquals(0L, p.unread(Seq(3)), "caught up")
        assertEquals(0L, p.unread(Seq(5)), "cursor ahead never goes negative")
        assertTrue(p.hasUnread(Seq(2)))
        assertFalse(p.hasUnread(Seq(3)))
    }


    private val representative: List<AgentEvent> = listOf(
        AgentEvent.SessionBound(providerId),
        AgentEvent.TurnStarted,
        AgentEvent.ToolCall("Read"),
        AgentEvent.ApprovalRequested("a1"),
        AgentEvent.ApprovalRequested("a2"),
        AgentEvent.ToolCall("Bash"),
        AgentEvent.TurnCompleted,
        AgentEvent.TurnStarted,
        AgentEvent.ApprovalResolved("stray", true),
        AgentEvent.Exited(0),
    )

    @Test
    fun replayReconstructsTheConcreteTrajectoryAndFinalProjection() {
        val expected = listOf(
            Triple(SessionState.running, 0, 1L),
            Triple(SessionState.running, 0, 2L),
            Triple(SessionState.running, 0, 3L),
            Triple(SessionState.needs_approval, 1, 4L),
            Triple(SessionState.needs_approval, 2, 5L),
            Triple(SessionState.running, 0, 6L),
            Triple(SessionState.ready, 0, 7L),
            Triple(SessionState.running, 0, 8L),
            Triple(SessionState.running, 0, 9L),
            Triple(SessionState.stopped, 0, 10L),
        )
        var acc = Projection.EMPTY
        representative.forEachIndexed { i, event ->
            acc = reduce(acc, event)
            val (state, pending, seq) = expected[i]
            assertEquals(state, acc.state, "state after event $i (${event::class.simpleName})")
            assertEquals(pending, acc.pendingApprovals, "pendingApprovals after event $i")
            assertEquals(Seq(seq), acc.lastSeq, "lastSeq after event $i")
        }
        assertEquals(providerId, acc.providerSessionId, "SessionBound's provider id is carried to the end")
        assertFalse(acc.stopRequested)

        val batch = replay(representative)
        assertEquals(SessionState.stopped, batch.state)
        assertEquals(0, batch.pendingApprovals)
        assertEquals(Seq(10), batch.lastSeq)
        assertEquals(providerId, batch.providerSessionId)
        assertEquals(batch, replay(representative), "replay is deterministic")
    }

    @Test
    fun pendingApprovalsInvariantHoldsAfterEveryEvent() {
        var acc = Projection.EMPTY
        assertInvariant(acc)
        for (e in representative) {
            acc = reduce(acc, e)
            assertInvariant(acc)
        }
        assertInvariant(reduce(blocked, ControlSignal.Interrupt))
        assertInvariant(reduce(blocked, ControlSignal.Detach))
        assertInvariant(reduce(blocked, ControlSignal.Stop))
    }

    private fun assertInvariant(p: Projection) {
        assertTrue(p.pendingApprovals >= 0, "pendingApprovals must be non-negative: $p")
        assertEquals(
            p.pendingApprovals > 0,
            p.state == SessionState.needs_approval,
            "invariant pendingApprovals>0 <=> needs_approval violated by $p",
        )
        assertNotNull(p.state)
        assertTrue(p.state != SessionState.needs_answer, "needs_answer is forward-modeled, never reduced in v1")
    }
}
