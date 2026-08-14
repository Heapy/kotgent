package io.kotgent.adapter

import io.kotgent.core.AgentEvent
import kotlinx.coroutines.flow.Flow

/** Provider-specific launch planning and provider-neutral event delivery. */
interface AgentAdapter {
    fun buildLaunchSpec(mode: LaunchMode): LaunchSpec

    val events: Flow<AgentEvent>
}
