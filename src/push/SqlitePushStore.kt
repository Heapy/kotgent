package io.kotgent.push

import app.cash.sqldelight.db.SqlDriver
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SQLDelight generation does not apply migrations, so existing databases create this additive table at
 * runtime. [CREATE_TABLE_IF_NOT_EXISTS] must stay synchronized with `PushSubscriptions.sq`. Operations
 * serialize access to the shared single connection.
 */
class SqlitePushStore(driver: SqlDriver) : PushStore {

    private val db: KotgentDatabase = KotgentDatabase(driver)
    private val queries get() = db.pushSubscriptionsQueries

    private val mutex = Mutex()

    init {
        driver.execute(null, CREATE_TABLE_IF_NOT_EXISTS, 0)
    }

    override suspend fun list(): List<PushSubscription> = mutex.withLock {
        queries.selectAll { endpoint, p256dh, auth, createdAt ->
            PushSubscription(endpoint = endpoint, p256dh = p256dh, auth = auth, createdAt = createdAt)
        }.executeAsList()
    }

    override suspend fun save(subscription: PushSubscription): Unit = mutex.withLock {
        queries.upsert(
            subscription.endpoint,
            subscription.p256dh,
            subscription.auth,
            subscription.createdAt,
        )
    }

    override suspend fun remove(endpoint: String): Unit = mutex.withLock {
        queries.deleteByEndpoint(endpoint)
    }

    companion object {
        const val CREATE_TABLE_IF_NOT_EXISTS: String =
            "CREATE TABLE IF NOT EXISTS push_subscriptions (" +
                "endpoint TEXT NOT NULL PRIMARY KEY, " +
                "p256dh TEXT NOT NULL, " +
                "auth TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
    }
}
