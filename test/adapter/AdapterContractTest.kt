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

class AdapterContractTest {

    private val providerId = ProviderSessionId("11111111-2222-3333-4444-555555555555")

    private val representative: List<AgentEvent> = listOf(
        AgentEvent.SessionBound(providerId),
        AgentEvent.TurnStarted,
        AgentEvent.ToolCall("Read"),
        AgentEvent.ApprovalRequested("a1"),
        AgentEvent.ApprovalRequested("a2"),
        AgentEvent.ApprovalResolved("a1", true),
        AgentEvent.ApprovalResolved("a2", false),
        AgentEvent.ApprovalRequested("a3"),
        AgentEvent.ToolCall("Bash"),
        AgentEvent.TurnCompleted,
        AgentEvent.TurnStarted,
        AgentEvent.Exited(0),
    )

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

    private val allV1EventNames: Set<String> = setOf(
        "TurnStarted", "TurnCompleted", "ApprovalRequested",
        "ApprovalResolved", "ToolCall", "Exited", "SessionBound",
    )


    @Test
    fun foldingTheAdapterStreamThroughTheReducerVisitsTheExpectedStateTrajectory() = runBlocking {
        withTimeout(5_000) {
            val adapter = FakeAdapter()

            val trajectory = mutableListOf<SessionState>()
            var projection = Projection.EMPTY
            val collector = launch {
                adapter.events.collect { event ->
                    projection = reduce(projection, event)
                    trajectory.add(projection.state)
                }
            }

            adapter.emitAll(representative)
            adapter.close()
            collector.join()

            assertEquals(
                expectedTrajectory,
                trajectory,
                "reducing the adapter's event stream must visit the expected states in order",
            )

            assertEquals(SessionState.stopped, projection.state)
            assertEquals(0, projection.pendingApprovals)
            assertEquals(providerId, projection.providerSessionId, "SessionBound bound the provider id")
            assertEquals(Seq(representative.size.toLong()), projection.lastSeq)
            assertFalse(projection.stopRequested)

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
            adapter.close()

            val collected = adapter.events.toList()
            assertTrue(collected.isEmpty(), "no events were emitted")
            assertEquals(
                Projection.EMPTY,
                replay(collected),
                "an empty event log reduces to the seed projection (a live, running session)",
            )
        }
    }


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
