package io.kotgent.store

import io.kotgent.core.UsageReset
import io.kotgent.core.UsageResetNotification

interface NotificationStore {
    /** True only for the first insertion of an eligible durable reset. */
    suspend fun insert(reset: UsageReset): Boolean
    suspend fun recent(since: Long): List<UsageResetNotification>
    suspend fun prune()
}
