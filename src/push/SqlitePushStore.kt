package io.kotgent.push

import app.cash.sqldelight.db.SqlDriver
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The SQLDelight-backed [PushStore], over the SAME [SqlDriver] the event store uses (the daemon opens
 * one `~/.kotgent/kotgent.db`). `EventStore` is untouched by this: the two stores share a connection,
 * not a schema concern.
 *
 * **Migration for pre-existing databases.** The `sqldelight-gen` plugin drops `.sqm` files and leaves
 * the generated `Schema.migrate()` empty, so `push_subscriptions` from `PushSubscriptions.sq` is only
 * created on a FRESH database. An existing `kotgent.db` therefore gets it here, in [init], via
 * `CREATE TABLE IF NOT EXISTS`. Unlike the additive `ALTER TABLE … ADD COLUMN` in
 * `SqliteEventStore.init` — which needs a `PRAGMA table_info` guard because a duplicate-column ALTER
 * makes sqliter log a SQLITE_ERROR stack trace on every start — `IF NOT EXISTS` can neither fail nor
 * log, so it runs unguarded. The statement must stay in sync with the `.sq` DDL.
 *
 * All operations are serialized by [mutex], matching the single-writer discipline of the event store
 * (the driver is one connection shared by concurrently-running route handlers).
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
        /** Mirror of the `PushSubscriptions.sq` DDL, for databases created before the table existed. */
        const val CREATE_TABLE_IF_NOT_EXISTS: String =
            "CREATE TABLE IF NOT EXISTS push_subscriptions (" +
                "endpoint TEXT NOT NULL PRIMARY KEY, " +
                "p256dh TEXT NOT NULL, " +
                "auth TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
    }
}
