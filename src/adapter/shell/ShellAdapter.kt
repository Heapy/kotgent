package io.kotgent.adapter.shell

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.newUuidV4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A fresh shell receives a synthetic provider id so provider-neutral resume and reconciliation gates
 * remain usable. The id has no identity outside kotgent, and resume does not mint a replacement.
 */
class ShellAdapter(
    private val cwd: String,
    private val shell: String,
    private val generateSessionId: () -> ProviderSessionId = { ProviderSessionId(newUuidV4()) },
) : AgentAdapter {
    override val events: Flow<AgentEvent> = emptyFlow()

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = LaunchSpec(
        command = listOf(shell, "-l"),
        env = emptyMap(),
        cwd = cwd,
        preallocatedSessionId = when (mode) {
            LaunchMode.New -> generateSessionId()
            is LaunchMode.Resume -> null
        },
        cliVersion = null,
        cliPath = shell,
    )
}
