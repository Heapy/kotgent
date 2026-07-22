package io.kotgent.adapter

import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * The adapter seam (Task 10): the contract that turns a specific agent provider (Claude now; Codex
 * later) into the two things the rest of kotgent depends on, and nothing more.
 *
 * An adapter has exactly two responsibilities:
 *  1. [buildLaunchSpec] — decide *how to launch* the agent for a given [LaunchMode] (New vs Resume),
 *     as a pure [LaunchSpec] value the daemon (Task 13) feeds to tmux.
 *  2. [events] — expose the provider's activity as a stream of already-*normalized*, provider-neutral
 *     [AgentEvent]s. The adapter alone knows how to translate its provider's raw signals (Claude
 *     hook callbacks, later Codex rollout-JSONL / app-server) into the canonical 7-type vocabulary;
 *     everything downstream (the [io.kotgent.core.reduce] reducer, the `EventStore`, the transport)
 *     consumes only [AgentEvent], so it never learns which provider produced it. This is what makes
 *     the reducer/state model provider-independent: `state == replay(adapter.events)`.
 *
 * Deliberately minimal. Capability interfaces the plan names as BACKLOG — `SupportsApprovalResolution`
 * (a real "permission answered" signal, so the reducer would not have to clear approvals on the next
 * running-entry) and `SupportsStructuredTranscript` (a richer transcript watch) — are NOT declared
 * here in the v1 slice. When a provider gains such a capability it will be a separate opt-in
 * interface an adapter additionally implements, leaving this core contract untouched.
 */
interface AgentAdapter {

    /**
     * Build the launch descriptor for [mode] — a fresh conversation ([LaunchMode.New], for which the
     * adapter preallocates the provider session id) or a continuation ([LaunchMode.Resume], which
     * carries the existing id). Pure: it computes an argv/env/cwd, it does not spawn anything.
     */
    fun buildLaunchSpec(mode: LaunchMode): LaunchSpec

    /**
     * The normalized event stream — every provider signal already mapped to a canonical [AgentEvent].
     * Cold or hot is an implementation detail; consumers fold it through the reducer and/or append it
     * to the store. The stream completes when the provider's session ends (typically after an
     * [AgentEvent.Exited]); consumers must bound their collection so a stuck provider cannot hang them.
     */
    val events: Flow<AgentEvent>
}
