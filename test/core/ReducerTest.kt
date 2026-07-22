package io.kotgent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reducer TDD (Task 6): the pure `log -> state` projection. Covers every v1 transition, the
 * critical "entering running clears pendingApprovals" rule (Claude has no permission-answered
 * event), the [ControlSignal] user-initiated transitions (Interrupt / Stop-intent / Resume /
 * Detach), and a determinism/associativity property for [replay] (restart-safety = replay).
 *
 * Design under test (see Reducer.kt / Projection.kt KDoc):
 * - running-producing AgentEvents are [AgentEvent.TurnStarted] and [AgentEvent.ToolCall]; both
 *   set `running` AND reset `pendingApprovals = 0`.
 * - the projection invariant `pendingApprovals > 0  <=>  state == needs_approval` holds after
 *   every transition (asserted as a meta-property below).
 * - control signals are a SEPARATE reducer input that does NOT advance `lastSeq` (they are not
 *   part of the 7 persisted AgentEvent types).
 */
class ReducerTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"
    private val providerId = ProviderSessionId(uuid)

    /** A running session reached the normal way (start event). Used as the "stuck running" base. */
    private val running: Projection = reduce(Projection.EMPTY, AgentEvent.TurnStarted)

    /** A blocked session with one pending approval. */
    private val blocked: Projection = reduce(running, AgentEvent.ApprovalRequested("appr-1"))

    // ---- empty / initial projection ----

    @Test
    fun emptyProjectionIsRunningAliveWithNoApprovals() {
        val p = Projection.EMPTY
        assertEquals(SessionState.running, p.state, "a just-created session is live/running")
        assertEquals(0, p.pendingApprovals)
        assertEquals(Seq(0), p.lastSeq, "no events applied yet")
        assertNull(p.providerSessionId, "provider id unknown until SessionBound")
        assertFalse(p.stopRequested, "no clean-stop intent armed")
    }

    // ---- v1 AgentEvent transitions ----

    @Test
    fun startEventProducesRunning() {
        val p = reduce(Projection.EMPTY, AgentEvent.TurnStarted)
        assertEquals(SessionState.running, p.state)
        assertEquals(Seq(1), p.lastSeq, "the first event advances lastSeq to 1")
    }

    @Test
    fun toolCallIsAlsoARunningProducer() {
        // From ready, a PostToolUse (ToolCall) re-enters running.
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

        // A second concurrent approval accumulates.
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
        // dead states are dead
        assertTrue(reduce(running, AgentEvent.Exited(0)).state.isDead)
        assertTrue(reduce(running, AgentEvent.Exited(1)).state.isDead)
    }

    @Test
    fun sessionBoundRecordsProviderIdWithoutChangingLifecycleState() {
        val p = reduce(running, AgentEvent.SessionBound(providerId))
        assertEquals(SessionState.running, p.state, "SessionBound does not change lifecycle state")
        assertEquals(providerId, p.providerSessionId)
        assertEquals(running.lastSeq.next(), p.lastSeq, "SessionBound still advances the event seq")

        // it also does not disturb a blocked session's approval count/state
        val boundWhileBlocked = reduce(blocked, AgentEvent.SessionBound(providerId))
        assertEquals(SessionState.needs_approval, boundWhileBlocked.state)
        assertEquals(1, boundWhileBlocked.pendingApprovals)
        assertEquals(providerId, boundWhileBlocked.providerSessionId)
    }

    // ---- the critical pendingApprovals rule (no "permission answered" event) ----

    @Test
    fun enteringRunningViaToolCallClearsPendingApprovals() {
        // Claude chain: Notification (ApprovalRequested) -> PostToolUse (ToolCall) -> running.
        assertEquals(SessionState.needs_approval, blocked.state)
        assertEquals(1, blocked.pendingApprovals)

        val resumed = reduce(blocked, AgentEvent.ToolCall("Bash"))
        assertEquals(SessionState.running, resumed.state, "re-entering running clears needs_approval")
        assertEquals(0, resumed.pendingApprovals, "entering running RESETS pendingApprovals to 0")
    }

    @Test
    fun enteringRunningViaTurnStartedClearsPendingApprovals() {
        // Same rule via UserPromptSubmit (TurnStarted), even with multiple pending approvals.
        val doubleBlocked = reduce(blocked, AgentEvent.ApprovalRequested("appr-2"))
        assertEquals(2, doubleBlocked.pendingApprovals)

        val resumed = reduce(doubleBlocked, AgentEvent.TurnStarted)
        assertEquals(SessionState.running, resumed.state)
        assertEquals(0, resumed.pendingApprovals, "all pending approvals cleared on running-entry")
    }

    /**
     * Forward-modeled seam: the v1 Claude adapter never emits [AgentEvent.ApprovalResolved], but the
     * reducer must stay total AND model the richer-adapter semantics — decrement, and return to
     * running once the last approval clears.
     */
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

        // never underflows below zero
        val underflow = reduce(cleared, AgentEvent.ApprovalResolved("stray", approved = true))
        assertEquals(0, underflow.pendingApprovals, "pendingApprovals floors at 0")
    }

    // ---- ControlSignal: user-initiated transitions (not AgentEvents) ----

    @Test
    fun interruptResetsAStuckRunning() {
        // Claude sends no hook on Esc/Ctrl-C; Interrupt un-sticks a hung running session.
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
        // Detach is a client disconnect: the session lives, state is unchanged (identity).
        for (p in listOf(Projection.EMPTY, running, blocked,
                reduce(running, AgentEvent.TurnCompleted), reduce(running, AgentEvent.Exited(2)))) {
            assertEquals(p, reduce(p, ControlSignal.Detach), "detach must not change state for $p")
        }
    }

    @Test
    fun controlSignalsDoNotAdvanceLastSeq() {
        // Control signals are not part of the persisted AgentEvent log, so they leave lastSeq alone.
        assertEquals(running.lastSeq, reduce(running, ControlSignal.Interrupt).lastSeq)
        assertEquals(running.lastSeq, reduce(running, ControlSignal.Stop).lastSeq)
        assertEquals(running.lastSeq, reduce(running, ControlSignal.Detach).lastSeq)
        val dead = reduce(running, AgentEvent.Exited(0))
        assertEquals(dead.lastSeq, reduce(dead, ControlSignal.Resume).lastSeq)
    }

    @Test
    fun stopIntentReclassifiesANonZeroExitAsStopped() {
        // Without intent, a non-zero exit is a crash (the simple heuristic).
        assertEquals(SessionState.crashed, reduce(running, AgentEvent.Exited(143)).state)

        // With a clean-stop intent armed by the daemon, the SAME exit is an intended stop.
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
        // resume on an already-alive session is a no-op
        assertEquals(running, reduce(running, ControlSignal.Resume))
    }

    // ---- unread derivation ----

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

    // ---- replay / determinism (restart-safety = replay) ----

    /** A representative sequence exercising all 7 AgentEvent subtypes and the interesting paths. */
    private val representative: List<AgentEvent> = listOf(
        AgentEvent.SessionBound(providerId),          // record provider id (no state change)
        AgentEvent.TurnStarted,                        // running
        AgentEvent.ToolCall("Read"),                   // running
        AgentEvent.ApprovalRequested("a1"),            // needs_approval, pending 1
        AgentEvent.ApprovalRequested("a2"),            // needs_approval, pending 2
        AgentEvent.ToolCall("Bash"),                   // running, pending 0 (critical clear)
        AgentEvent.TurnCompleted,                      // ready
        AgentEvent.TurnStarted,                        // running again
        AgentEvent.ApprovalResolved("stray", true),    // forward-modeled floor at 0, stays running
        AgentEvent.Exited(0),                          // stopped
    )

    @Test
    fun replayEqualsIncrementalFoldAndIsDeterministic() {
        // Manual incremental fold threading the projection by hand.
        var acc = Projection.EMPTY
        for (e in representative) acc = reduce(acc, e)

        val batch = replay(representative)
        assertEquals(acc, batch, "replay must equal the incremental fold")
        assertEquals(batch, replay(representative), "replay must be deterministic (no hidden state/clock)")

        // The representative sequence ends stopped, provider id bound, seq == event count.
        assertEquals(SessionState.stopped, batch.state)
        assertEquals(providerId, batch.providerSessionId)
        assertEquals(Seq(representative.size.toLong()), batch.lastSeq, "each event advances lastSeq by exactly 1")
        assertEquals(0, batch.pendingApprovals)
    }

    @Test
    fun replayIsAssociativeAcrossEverySplitPoint() {
        // Restart-safety property: replay(prefix) then continue == replay(whole), for ANY split.
        val whole = replay(representative)
        for (k in 0..representative.size) {
            val prefix = replay(representative.take(k))
            val continued = representative.drop(k).fold(prefix) { p, e -> reduce(p, e) }
            assertEquals(whole, continued, "replay must be associative at split k=$k")
        }
    }

    @Test
    fun reduceDoesNotMutateItsInput() {
        val before = running.copy()
        reduce(running, AgentEvent.ApprovalRequested("x"))
        reduce(running, ControlSignal.Interrupt)
        assertEquals(before, running, "reduce must be pure — the input projection is never mutated")
    }

    /**
     * Meta-property: after EVERY prefix of the representative sequence, the projection invariant
     * `pendingApprovals > 0  <=>  state == needs_approval` holds, and pendingApprovals is never
     * negative. This ties the whole state machine together.
     */
    @Test
    fun pendingApprovalsInvariantHoldsAfterEveryEvent() {
        var acc = Projection.EMPTY
        assertInvariant(acc)
        for (e in representative) {
            acc = reduce(acc, e)
            assertInvariant(acc)
        }
        // also holds after each control signal applied to a blocked session
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
        // The v1 reducer never produces the forward-modeled needs_answer.
        assertNotNull(p.state)
        assertTrue(p.state != SessionState.needs_answer, "needs_answer is forward-modeled, never reduced in v1")
    }
}
