package io.kotgent.core

/**
 * Operator input is deliberately separate from the persisted [AgentEvent] vocabulary and does not
 * advance [Projection.lastSeq].
 */
sealed interface ControlSignal {
    /** Claude emits no hook for Esc/Ctrl-C, so interrupt explicitly returns a live session to ready. */
    data object Interrupt : ControlSignal

    /** Arms intent so a subsequent non-zero exit is classified as stopped rather than crashed. */
    data object Stop : ControlSignal

    data object Resume : ControlSignal

    data object Detach : ControlSignal
}

fun reduce(projection: Projection, event: AgentEvent): Projection {
    val seq = projection.lastSeq.next()
    return when (event) {
        // Providers without an approval-resolution hook prove resolution by re-entering running.
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

        is AgentEvent.SessionBound ->
            projection.copy(providerSessionId = event.providerSessionId, lastSeq = seq)
    }
}

fun reduce(projection: Projection, signal: ControlSignal): Projection = when (signal) {
    ControlSignal.Interrupt ->
        if (projection.state.isAlive) projection.copy(state = SessionState.ready, pendingApprovals = 0)
        else projection

    ControlSignal.Stop ->
        projection.copy(stopRequested = true)

    ControlSignal.Resume ->
        if (projection.state.isDead)
            projection.copy(state = SessionState.ready, pendingApprovals = 0, stopRequested = false)
        else projection

    ControlSignal.Detach ->
        projection
}

fun replay(events: List<AgentEvent>): Projection =
    events.fold(Projection.EMPTY) { projection, event -> reduce(projection, event) }
