package io.kotgent.core

/*
 * The event-sourcing reducer (Task 6): the pure, host-free `log -> state` projection that is the
 * spine of kotgent's restart-safe control plane. State is a deterministic function of the log, so
 * `replay` (fold from EMPTY) reconstructs any session's projection after a daemon restart.
 *
 * Two reducer inputs:
 *  - AgentEvent    — the 7 persisted, provider-neutral event types (append-only `events` log).
 *  - ControlSignal — user/operator actions the daemon issues (StopMode) that are NOT agent events.
 *
 * v1 state machine (from the plan):
 *  - TurnStarted / ToolCall            -> running   (running-PRODUCERS; reset pendingApprovals = 0)
 *  - ApprovalRequested                 -> needs_approval, pendingApprovals += 1
 *  - ApprovalResolved (forward-modeled)-> decrement; back to running when the last one clears
 *  - TurnCompleted                     -> ready
 *  - Exited(0) | intended              -> stopped ;   Exited(!=0) without intent -> crashed
 *  - SessionBound                      -> records providerSessionId (no lifecycle change)
 *
 * Waiting logic is APPROVAL-ONLY in v1: `needs_answer` is forward-modeled and produced by no v1
 * event, and `resumable` is a reconciler (daemon) classification — the reducer yields neither.
 *
 * The critical rule (Claude has no "permission answered" event): entering `running` RESETS
 * pendingApprovals to 0, so a `Notification -> PostToolUse` chain (ApprovalRequested -> ToolCall)
 * clears `needs_approval` back to `running`.
 */

/**
 * A user/operator-initiated transition that is NOT an [AgentEvent] — it originates in the
 * daemon / StopMode (Task 13), not the agent, so it is a SEPARATE reducer input rather than one of
 * the 7 persisted event types (that vocabulary stays unchanged). Control signals do NOT advance
 * [Projection.lastSeq]; if the daemon chooses to persist/replay them it does so on its own stream.
 *
 * [decision] Modeled as a small sealed hierarchy the reducer also accepts (mirroring the operator
 * actions named in the plan) rather than by extending [AgentEvent], keeping `reduce` pure and total
 * while leaving the persisted log vocabulary intact. See the Task 6 progress log.
 */
sealed interface ControlSignal {
    /**
     * Un-stick a session: Claude emits no hook on Esc/Ctrl-C, so an [Interrupt] resets a stuck
     * `running` (or cancels a pending approval) back to idle `ready` and clears pendingApprovals.
     * A no-op on a dead session (nothing to interrupt).
     */
    data object Interrupt : ControlSignal

    /**
     * Arm a clean/expected-termination intent so a subsequent non-zero [AgentEvent.Exited] is
     * classified as `stopped` rather than `crashed` (exit code alone cannot tell an operator stop
     * from a crash). Records intent only; the lifecycle change waits for `Exited`.
     */
    data object Stop : ControlSignal

    /** Revive a dead session (its transcript survives) back to idle `ready`. No-op if alive. */
    data object Resume : ControlSignal

    /**
     * Client disconnect (a WS subscriber left / the lazy terminal bridge tore down) — the session
     * lives on, so this is the identity. In practice detach is handled at the transport layer and
     * never reaches the reducer; it is modeled here as an explicit no-op to keep the control
     * surface total and self-documenting.
     */
    data object Detach : ControlSignal
}

/**
 * Apply one persisted [event] to [projection], returning the next projection. Pure and total over
 * the 7 v1 [AgentEvent] subtypes; advances [Projection.lastSeq] by exactly 1 (events are fed in
 * contiguous per-session seq order starting at 1, matching the store).
 */
fun reduce(projection: Projection, event: AgentEvent): Projection {
    val seq = projection.lastSeq.next()
    return when (event) {
        // Running-PRODUCERS. Entering running clears pending approvals (no "permission answered"
        // event) and disarms any stale stop-intent (the agent demonstrably kept working).
        is AgentEvent.TurnStarted, is AgentEvent.ToolCall ->
            projection.copy(
                state = SessionState.running,
                pendingApprovals = 0,
                stopRequested = false,
                lastSeq = seq,
            )

        is AgentEvent.ApprovalRequested ->
            projection.copy(
                state = SessionState.needs_approval,
                pendingApprovals = projection.pendingApprovals + 1,
                lastSeq = seq,
            )

        // Forward-modeled seam (no v1 Claude hook): resolve one approval; when the last clears,
        // the agent resumes -> running. Floors at 0.
        is AgentEvent.ApprovalResolved -> {
            val remaining = (projection.pendingApprovals - 1).coerceAtLeast(0)
            val state =
                if (remaining == 0 && projection.state == SessionState.needs_approval) SessionState.running
                else projection.state
            projection.copy(state = state, pendingApprovals = remaining, lastSeq = seq)
        }

        is AgentEvent.TurnCompleted ->
            projection.copy(state = SessionState.ready, pendingApprovals = 0, lastSeq = seq)

        is AgentEvent.Exited -> {
            val dead =
                if (event.code == 0 || projection.stopRequested) SessionState.stopped
                else SessionState.crashed
            projection.copy(
                state = dead,
                pendingApprovals = 0,
                stopRequested = false,
                lastSeq = seq,
            )
        }

        // Provenance/identity only — does not touch the lifecycle state or approval count.
        is AgentEvent.SessionBound ->
            projection.copy(providerSessionId = event.providerSessionId, lastSeq = seq)
    }
}

/**
 * Apply one user/operator [signal] to [projection]. Pure and total; never advances
 * [Projection.lastSeq] (control signals are not part of the persisted [AgentEvent] log).
 */
fun reduce(projection: Projection, signal: ControlSignal): Projection = when (signal) {
    ControlSignal.Interrupt ->
        if (projection.state.isAlive) projection.copy(state = SessionState.ready, pendingApprovals = 0)
        else projection // cannot interrupt a dead process

    ControlSignal.Stop ->
        projection.copy(stopRequested = true) // record intent; Exited performs the transition

    ControlSignal.Resume ->
        if (projection.state.isDead)
            projection.copy(state = SessionState.ready, pendingApprovals = 0, stopRequested = false)
        else projection // already alive

    ControlSignal.Detach ->
        projection // client disconnect: the session lives, state is unchanged
}

/**
 * Rebuild a projection by folding [events] from [Projection.EMPTY] — the restart-safety primitive
 * (`state == replay(log)`). Deterministic and associative: replaying any prefix and then continuing
 * yields the same projection as replaying the whole.
 */
fun replay(events: List<AgentEvent>): Projection =
    events.fold(Projection.EMPTY) { projection, event -> reduce(projection, event) }
