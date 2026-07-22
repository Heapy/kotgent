package io.kotgent.adapter

import io.kotgent.core.AgentEvent
import io.kotgent.core.ProviderSessionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A pure-Kotlin [AgentAdapter] for exercising the adapter contract (Task 10) with no provider, no
 * process, and no cinterop — so it runs for real in the test binary. It lets a test *drive* the
 * event stream (stand in for a live provider) and returns a canned, Claude-shaped [LaunchSpec] so
 * the New-vs-Resume launch shapes can be asserted before the real `ClaudeAdapter` exists (Task 11).
 *
 * ## Driving [events]
 * The stream is backed by an UNLIMITED [Channel]: [emit] / [emitAll] push events onto it (never
 * blocking) and [close] ends the stream (as a real provider does after `Exited`). Exposed as a
 * [Flow] via [receiveAsFlow], so a single collector receives every emitted event once, in order,
 * then the flow completes on [close]. This mirrors the contract's "the stream completes when the
 * session ends"; tests still bound their collection with `withTimeout` per the contract.
 */
class FakeAdapter(
    /** The `cwd` the canned [LaunchSpec] reports. */
    val cwd: String = "/tmp/kotgent-fake",
    /** The `env` the canned [LaunchSpec] reports. */
    val env: Map<String, String> = mapOf("KOTGENT_FAKE" to "1"),
    /** The provider id a [LaunchMode.New] spec preallocates (must be a valid UUID). */
    val newSessionId: ProviderSessionId = ProviderSessionId("00000000-0000-4000-8000-000000000000"),
) : AgentAdapter {

    private val channel = Channel<AgentEvent>(Channel.UNLIMITED)

    override val events: Flow<AgentEvent> = channel.receiveAsFlow()

    /** Every [LaunchMode] this adapter was asked to build a spec for, in call order. */
    val launchModes: MutableList<LaunchMode> = mutableListOf()

    /**
     * Canned, Claude-shaped launch spec: [LaunchMode.New] preallocates [newSessionId] and passes it
     * via `--session-id`; [LaunchMode.Resume] re-addresses the existing conversation via `--resume`
     * and preallocates nothing. Records [mode] in [launchModes].
     */
    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec {
        launchModes.add(mode)
        return when (mode) {
            is LaunchMode.New -> LaunchSpec(
                command = listOf("claude", "--session-id", newSessionId.value),
                env = env,
                cwd = cwd,
                preallocatedSessionId = newSessionId,
            )
            is LaunchMode.Resume -> LaunchSpec(
                command = listOf("claude", "--resume", mode.providerSessionId.value),
                env = env,
                cwd = cwd,
                preallocatedSessionId = null,
            )
        }
    }

    /** Push one already-normalized [event] onto the stream (never blocks — the channel is UNLIMITED). */
    suspend fun emit(event: AgentEvent) {
        channel.send(event)
    }

    /** Push [events] onto the stream in order. */
    suspend fun emitAll(events: Iterable<AgentEvent>) {
        for (event in events) channel.send(event)
    }

    /** End the stream — the provider's session is over, so the [events] flow completes. */
    fun close() {
        channel.close()
    }
}
