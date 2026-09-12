package io.kotgent.store

import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageWindowState
import kotlinx.coroutines.flow.SharedFlow

/** Account usage projections and their durable, source-ordered observation journal. */
interface UsageStore {
    suspend fun observe(observation: UsageObservation)

    suspend fun list(): List<UsageWindowState>

    suspend fun prune()

    val updates: SharedFlow<UsageWindowState>

    val resets: SharedFlow<UsageReset>

    /** Pending early weekly resets; the inbox uses each reset id as its idempotency key. */
    suspend fun pendingResetNotifications(since: Long): List<UsageReset>

    suspend fun markResetNotificationProjected(id: Long)
}
