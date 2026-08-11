package io.kotgent.adapter

import io.kotgent.core.AgentEvent
import io.kotgent.core.ProviderSessionId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FakeAdapter(
    val cwd: String = "/tmp/kotgent-fake",
    val env: Map<String, String> = mapOf("KOTGENT_FAKE" to "1"),
    val newSessionId: ProviderSessionId = ProviderSessionId("00000000-0000-4000-8000-000000000000"),
    val cliVersion: String? = null,
    val cliPath: String? = null,
) : AgentAdapter {

    private val channel = Channel<AgentEvent>(Channel.UNLIMITED)

    override val events: Flow<AgentEvent> = channel.receiveAsFlow()

    val launchModes: MutableList<LaunchMode> = mutableListOf()

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec {
        launchModes.add(mode)
        return when (mode) {
            is LaunchMode.New -> LaunchSpec(
                command = listOf("claude", "--session-id", newSessionId.value),
                env = env,
                cwd = cwd,
                preallocatedSessionId = newSessionId,
                cliVersion = cliVersion,
                cliPath = cliPath,
            )
            is LaunchMode.Resume -> LaunchSpec(
                command = listOf("claude", "--resume", mode.providerSessionId.value),
                env = env,
                cwd = cwd,
                preallocatedSessionId = null,
                cliVersion = cliVersion,
                cliPath = cliPath,
            )
        }
    }

    suspend fun emit(event: AgentEvent) {
        channel.send(event)
    }

    suspend fun emitAll(events: Iterable<AgentEvent>) {
        for (event in events) channel.send(event)
    }

    fun close() {
        channel.close()
    }
}
