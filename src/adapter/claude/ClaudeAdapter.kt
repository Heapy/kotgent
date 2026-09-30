package io.kotgent.adapter.claude

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchOptions
import io.kotgent.adapter.LaunchSpec
import io.kotgent.adapter.initialPromptInstruction
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

    override fun buildLaunchSpec(mode: LaunchMode, options: LaunchOptions): LaunchSpec {
        val preallocated: ProviderSessionId?
        val modeArgs: List<String>
        val promptArgs: List<String>
        when (mode) {
            is LaunchMode.New -> {
                preallocated = if (sessionIdSupported) generateSessionId() else null
                modeArgs = preallocated?.let { listOf(SESSION_ID_FLAG, it.value) }.orEmpty()
                promptArgs = options.promptPath?.let { listOf(END_OF_OPTIONS, initialPromptInstruction(it)) }.orEmpty()
            }

            is LaunchMode.Resume -> {
                preallocated = null
                modeArgs = listOf(RESUME_FLAG, mode.providerSessionId.value)
                promptArgs = emptyList()
            }
        }
        val permissionArgs = if (options.readOnly) listOf(PERMISSION_MODE_FLAG, PLAN_PERMISSION_MODE) else emptyList()
        return LaunchSpec(
            command = listOf(binaryName) + modeArgs + listOf(SETTINGS_FLAG, settingsPath) + permissionArgs + promptArgs,
            env = env,
            cwd = cwd,
            preallocatedSessionId = preallocated,
            cliVersion = cliVersion,
            cliPath = cliPath,
        )
    }

    companion object {
        const val SESSION_ID_FLAG: String = "--session-id"

        const val RESUME_FLAG: String = "--resume"

        const val SETTINGS_FLAG: String = "--settings"

        const val PERMISSION_MODE_FLAG: String = "--permission-mode"

        const val PLAN_PERMISSION_MODE: String = "plan"

        const val END_OF_OPTIONS: String = "--"
    }
}
