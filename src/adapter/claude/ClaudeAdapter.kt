package io.kotgent.adapter.claude

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.newUuidV4
import kotlinx.coroutines.flow.Flow

/**
 * Claude launch adapter. CLIs without `--session-id` support omit preallocation and bind the id from
 * the later `SessionStart` hook.
 */
class ClaudeAdapter(
    private val cwd: String,
    private val settingsPath: String,
    override val events: Flow<AgentEvent>,
    private val sessionIdSupported: Boolean = true,
    private val binaryName: String = "claude",
    private val env: Map<String, String> = emptyMap(),
    private val generateSessionId: () -> ProviderSessionId = { ProviderSessionId(newUuidV4()) },
    private val cliVersion: String? = null,
    private val cliPath: String? = null,
) : AgentAdapter {

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
        is LaunchMode.New ->
            if (sessionIdSupported) {
                val id = generateSessionId()
                LaunchSpec(
                    command = listOf(binaryName, "--session-id", id.value, "--settings", settingsPath),
                    env = env,
                    cwd = cwd,
                    preallocatedSessionId = id,
                    cliVersion = cliVersion,
                    cliPath = cliPath,
                )
            } else {
                LaunchSpec(
                    command = listOf(binaryName, "--settings", settingsPath),
                    env = env,
                    cwd = cwd,
                    preallocatedSessionId = null,
                    cliVersion = cliVersion,
                    cliPath = cliPath,
                )
            }

        is LaunchMode.Resume ->
            LaunchSpec(
                command = listOf(binaryName, "--resume", mode.providerSessionId.value, "--settings", settingsPath),
                env = env,
                cwd = cwd,
                preallocatedSessionId = null,
                cliVersion = cliVersion,
                cliPath = cliPath,
            )
    }
}
