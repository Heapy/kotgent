package io.kotgent.adapter.codex

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * Codex cannot preallocate a session id; hooks or the rollout scan bind it after launch. Hook trust is
 * bypassed only for kotgent's generated `0600` script and only through this launch's `-c` override.
 */
class CodexAdapter(
    private val cwd: String,
    private val hookScriptPath: String,
    override val events: Flow<AgentEvent>,
    private val binaryName: String = "codex",
    private val env: Map<String, String> = emptyMap(),
    private val cliVersion: String? = null,
    private val cliPath: String? = null,
) : AgentAdapter {

    override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec {
        val command = buildList {
            add(binaryName)
            // Codex parses the resume subcommand before its session config overrides.
            if (mode is LaunchMode.Resume) {
                add(RESUME_SUBCOMMAND)
                add(mode.providerSessionId.value)
            }
            add(CONFIG_FLAG)
            add(CodexHookConfig.hooksToml(hookScriptPath))
            add(CONFIG_FLAG)
            add(BYPASS_HOOK_TRUST)
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
        const val RESUME_SUBCOMMAND: String = "resume"

        const val CONFIG_FLAG: String = "-c"

        const val BYPASS_HOOK_TRUST: String = "bypass_hook_trust=true"
    }
}
