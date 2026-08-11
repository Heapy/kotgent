package io.kotgent.adapter.junie

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * Junie's `--session-id` resumes only existing sessions, so new ids are captured after launch. Tmux
 * sets the authoritative cwd; passing Junie's separate `--project` could make the two disagree.
 */
class JunieAdapter(
    private val cwd: String,
    private val hookConfigPath: String,
    override val events: Flow<AgentEvent>,
    private val binaryName: String = "junie",
    private val env: Map<String, String> = emptyMap(),
    private val cliVersion: String? = null,
    private val cliPath: String? = null,
) : AgentAdapter {

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec {
        val command = buildList {
            add(binaryName)
            if (mode is LaunchMode.Resume) {
                add(RESUME_FLAG)
                add(SESSION_ID_FLAG)
                add(mode.providerSessionId.value)
            }
            add(CONFIG_LOCATION_FLAG)
            add(hookConfigPath)
        }
        return LaunchSpec(
            command = command,
            env = env,
            cwd = cwd,
            preallocatedSessionId = null,
            cliVersion = cliVersion,
            cliPath = cliPath,
        )
    }

    companion object {
        const val RESUME_FLAG: String = "--resume"

        const val SESSION_ID_FLAG: String = "--session-id"

        const val CONFIG_LOCATION_FLAG: String = "--config-location"
    }
}
