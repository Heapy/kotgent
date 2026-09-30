package io.kotgent.adapter.codex

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchOptions
import io.kotgent.adapter.LaunchSpec
import io.kotgent.adapter.initialPromptInstruction
import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/**
 * Codex cannot preallocate a session id; hooks or the rollout scan bind it after launch.
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

    override fun buildLaunchSpec(mode: LaunchMode, options: LaunchOptions): LaunchSpec {
        val resume = mode as? LaunchMode.Resume
        val command = buildList {
            add(binaryName)
            // Codex parses the resume subcommand before its session config overrides.
            if (resume != null) {
                add(RESUME_SUBCOMMAND)
                add(resume.providerSessionId.value)
            }
            add(CONFIG_FLAG)
            add(CodexHookConfig.hooksToml(hookScriptPath))
            if (options.readOnly) {
                add(SANDBOX_FLAG)
                add(READ_ONLY_SANDBOX)
            }
            val promptPath = options.promptPath
            if (resume == null && promptPath != null) {
                add(END_OF_OPTIONS)
                add(initialPromptInstruction(promptPath))
            }
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

        const val SANDBOX_FLAG: String = "--sandbox"

        const val READ_ONLY_SANDBOX: String = "read-only"

        const val END_OF_OPTIONS: String = "--"
    }
}
