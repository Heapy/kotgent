package io.kotgent.adapter

import io.kotgent.core.ProviderSessionId

sealed interface LaunchMode {
    data object New : LaunchMode

    data class Resume(val providerSessionId: ProviderSessionId) : LaunchMode
}

/**
 * [promptPath] seeds only a new conversation; a resume ignores it, so callers can pass a session's
 * options unchanged to every launch.
 */
data class LaunchOptions(
    val readOnly: Boolean = false,
    val promptPath: String? = null,
)

class UnsupportedLaunchOptionException(val agentKind: String, val option: String) :
    IllegalArgumentException("$agentKind sessions do not support the $option launch option")

fun LaunchOptions.requireDefault(agentKind: String) {
    if (readOnly) throw UnsupportedLaunchOptionException(agentKind, "read-only")
    if (promptPath != null) throw UnsupportedLaunchOptionException(agentKind, "initial prompt")
}

fun initialPromptInstruction(promptPath: String): String =
    "Read the file $promptPath and follow the instructions in it."

data class LaunchSpec(
    val command: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    val preallocatedSessionId: ProviderSessionId? = null,
    val cliVersion: String? = null,
    val cliPath: String? = null,
)
