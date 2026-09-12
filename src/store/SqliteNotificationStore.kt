package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import io.kotgent.core.NOTIFICATION_RETENTION_MILLIS
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageResetNotification
import io.kotgent.core.isNotificationEligible
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class SqliteNotificationStore(
    driver: SqlDriver,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : NotificationStore {
    private val db = KotgentDatabase(driver)
    private val queries get() = db.notificationsQueries
    private val mutex = Mutex()

    init {
        driver.execute(null, CREATE_TABLE_IF_NOT_EXISTS, 0)
    }

    override suspend fun insert(reset: UsageReset): Boolean = mutex.withLock {
        require(reset.id > 0) { "Notification requires a durable usage reset id" }
        if (!reset.isNotificationEligible) {
            return@withLock false
        }
        queries.insertNotification(
            id = "usage.reset:${reset.id}",
            created_at = reset.observedAt,
            provider = reset.provider,
            window_key = reset.windowKey,
            expected_at = reset.expectedAt,
            used_before = reset.usedBefore,
            used_before_seen_at = reset.usedBeforeSeenAt,
            window_seconds = reset.windowSeconds,
        ).value > 0L
    }

    override suspend fun recent(since: Long): List<UsageResetNotification> = mutex.withLock {
        queries.selectRecent(since) {
            id, createdAt, provider, windowKey, expectedAt, usedBefore, usedBeforeSeenAt, windowSeconds ->
            UsageResetNotification(
                id, createdAt, provider, windowKey, expectedAt, usedBefore, usedBeforeSeenAt, windowSeconds,
            )
        }.executeAsList()
    }

    override suspend fun prune(): Unit = mutex.withLock {
        val _ = queries.prune(now() - NOTIFICATION_RETENTION_MILLIS)
    }

    companion object {
        const val CREATE_TABLE_IF_NOT_EXISTS: String =
            "CREATE TABLE IF NOT EXISTS notifications (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "created_at INTEGER NOT NULL, " +
                "provider TEXT NOT NULL, " +
                "window_key TEXT NOT NULL, " +
                "expected_at INTEGER, " +
                "used_before REAL NOT NULL, " +
                "used_before_seen_at INTEGER NOT NULL, " +
                "window_seconds INTEGER)"
    }
}
