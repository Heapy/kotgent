package io.kotgent.adapter

import io.kotgent.core.AgentEvent
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionState
import io.kotgent.core.reduce
import io.kotgent.core.replay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [AgentAdapter] contract test (Task 10): verifies the whole "adapter -> events -> reducer" seam
 * end-to-end. A [FakeAdapter] stands in for a live provider; the test drives it to emit a
 * representative stream exercising ALL 7 v1 [AgentEvent] subtypes, collects [AgentAdapter.events]
 * (bounded by `withTimeout`), and folds the collected events through the [reduce] / [replay] reducer,
 * asserting the resulting [Projection] / [SessionState] trajectory. It also asserts the New-vs-Resume
 * [AgentAdapter.buildLaunchSpec] shapes.
 *
 * This is pure Kotlin (no cinterop), so it runs for real in the test binary — unlike the PTY paths,
 * there is no KT-78062 caveat here.
 */
class AdapterContractTest {

    private val providerId = ProviderSessionId("11111111-2222-3333-4444-555555555555")

    /**
     * A representative stream covering every v1 [AgentEvent] subtype and the interesting reducer
     * paths: SessionBound (identity, no state change), the running-producers TurnStarted/ToolCall,
     * approval accumulation, the forward-modeled ApprovalResolved decrement back to running, the
     * critical "entering running clears pending approvals" rule (ToolCall while blocked), and Exited.
     */
    private val representative: List<AgentEvent> = listOf(
        AgentEvent.SessionBound(providerId),         // running   — records provider id, no state change
        AgentEvent.TurnStarted,                      // running
        AgentEvent.ToolCall("Read"),                 // running
        AgentEvent.ApprovalRequested("a1"),          // needs_approval (pending 1)
        AgentEvent.ApprovalRequested("a2"),          // needs_approval (pending 2)
        AgentEvent.ApprovalResolved("a1", true),     // needs_approval (pending 1) — decrement, still blocked
        AgentEvent.ApprovalResolved("a2", false),    // running        (pending 0) — last resolved -> running
        AgentEvent.ApprovalRequested("a3"),          // needs_approval (pending 1)
        AgentEvent.ToolCall("Bash"),                 // running        (pending 0) — CRITICAL clear-on-running
        AgentEvent.TurnCompleted,                    // ready
        AgentEvent.TurnStarted,                      // running
        AgentEvent.Exited(0),                        // stopped
    )

    /** The state the reducer must reach after each event of [representative], in order. */
    private val expectedTrajectory: List<SessionState> = listOf(
        SessionState.running,
        SessionState.running,
        SessionState.running,
        SessionState.needs_approval,
        SessionState.needs_approval,
        SessionState.needs_approval,
        SessionState.running,
        SessionState.needs_approval,
        SessionState.running,
        SessionState.ready,
        SessionState.running,
        SessionState.stopped,
    )

    /** Simple names of all 7 v1 [AgentEvent] subtypes (K/N has no `sealedSubclasses`, so hardcoded). */
    private val allV1EventNames: Set<String> = setOf(
        "TurnStarted", "TurnCompleted", "ApprovalRequested",
        "ApprovalResolved", "ToolCall", "Exited", "SessionBound",
    )

    // ---- the core contract: adapter -> events -> reducer ----

    @Test
    fun foldingTheAdapterStreamThroughTheReducerVisitsTheExpectedStateTrajectory() = runBlocking {
        withTimeout(5_000) {
            val adapter = FakeAdapter()

            // A collector reduces the adapter's stream incrementally, exactly as the daemon would.
            val trajectory = mutableListOf<SessionState>()
            var projection = Projection.EMPTY
            val collector = launch {
                adapter.events.collect { event ->
                    projection = reduce(projection, event)
                    trajectory.add(projection.state)
                }
            }

            // Drive the "provider": emit the whole representative sequence, then end the session.
            adapter.emitAll(representative)
            adapter.close()
            collector.join()

            assertEquals(
                expectedTrajectory,
                trajectory,
                "reducing the adapter's event stream must visit the expected states in order",
            )

            // Final projection: stopped, provider id bound, no pending approvals, seq == #events.
            assertEquals(SessionState.stopped, projection.state)
            assertEquals(0, projection.pendingApprovals)
            assertEquals(providerId, projection.providerSessionId, "SessionBound bound the provider id")
            assertEquals(Seq(representative.size.toLong()), projection.lastSeq)
            assertFalse(projection.stopRequested)

            // Streaming reduce over the live Flow must equal a batch replay of the same events —
            // this IS the adapter->events->reducer contract (state == replay(adapter.events)).
            assertEquals(
                replay(representative),
                projection,
                "incremental reduce over the stream must equal replay() of the collected events",
            )
        }
    }

    @Test
    fun theContractRunCoversAllV1EventTypesAndTheFlowDeliversThemLosslesslyInOrder() = runBlocking {
        withTimeout(5_000) {
            val adapter = FakeAdapter()
            adapter.emitAll(representative)
            adapter.close()

            // Collection bounded by the enclosing withTimeout; completes when the adapter closes.
            val collected: List<AgentEvent> = adapter.events.toList()

            assertEquals(representative, collected, "the adapter Flow delivers every event once, in order")

            val seen = collected.map { it::class.simpleName }.toSet()
            assertEquals(
                allV1EventNames,
                seen,
                "the representative contract run must exercise all 7 v1 AgentEvent subtypes",
            )
        }
    }

    @Test
    fun anEmptyAdapterStreamReducesToTheInitialRunningProjection() = runBlocking {
        withTimeout(5_000) {
            val adapter = FakeAdapter()
            adapter.close() // the session ends before producing any event

            val collected = adapter.events.toList()
            assertTrue(collected.isEmpty(), "no events were emitted")
            assertEquals(
                Projection.EMPTY,
                replay(collected),
                "an empty event log reduces to the seed projection (a live, running session)",
            )
        }
    }

    // ---- buildLaunchSpec: New vs Resume shapes ----

    @Test
    fun buildLaunchSpecNewVsResumeProduceTheExpectedShapes() {
        val resumeId = ProviderSessionId("99999999-8888-7777-6666-555555555555")
        val adapter = FakeAdapter()

        val new = adapter.buildLaunchSpec(LaunchMode.New)
        assertEquals(
            adapter.newSessionId,
            new.preallocatedSessionId,
            "a New launch preallocates a provider session id and surfaces it on the spec",
        )
        assertEquals(
            listOf("claude", "--session-id", adapter.newSessionId.value),
            new.command,
            "New passes the preallocated id via --session-id",
        )
        assertEquals(adapter.cwd, new.cwd)
        assertEquals(adapter.env, new.env)

        val resume = adapter.buildLaunchSpec(LaunchMode.Resume(resumeId))
        assertNull(resume.preallocatedSessionId, "Resume does not preallocate a new id")
        assertEquals(
            listOf("claude", "--resume", resumeId.value),
            resume.command,
            "Resume re-addresses the existing conversation via --resume <id>",
        )
        assertEquals(adapter.cwd, resume.cwd)

        assertEquals(
            listOf(LaunchMode.New, LaunchMode.Resume(resumeId)),
            adapter.launchModes,
            "the adapter records, in order, the launch modes it built specs for",
        )
    }
}
