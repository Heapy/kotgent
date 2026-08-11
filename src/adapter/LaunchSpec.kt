package io.kotgent.adapter

import io.kotgent.core.ProviderSessionId

sealed interface LaunchMode {
    data object New : LaunchMode

    data class Resume(val providerSessionId: ProviderSessionId) : LaunchMode
}

data class LaunchSpec(
    val command: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    val preallocatedSessionId: ProviderSessionId? = null,
    val cliVersion: String? = null,
    val cliPath: String? = null,
)
