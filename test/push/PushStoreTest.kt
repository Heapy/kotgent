package io.kotgent.push

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class PushStoreTest {

    private fun sub(
        endpoint: String,
        p256dh: String = "key-$endpoint",
        auth: String = "auth-$endpoint",
        createdAt: Long = 1_000L,
    ) = PushSubscription(endpoint = endpoint, p256dh = p256dh, auth = auth, createdAt = createdAt)

    private fun freshStore(): SqlitePushStore = SqlitePushStore(inMemoryDriver(KotgentDatabase.Schema))

    @Test
    fun saveThenListRoundTrips() = runBlocking {
        withTimeout(20_000) {
            val store = freshStore()
            assertEquals(emptyList(), store.list(), "a fresh store has no subscriptions")

            val a = sub("https://web.push.apple.com/aaa", createdAt = 10L)
            val b = sub("https://fcm.googleapis.com/fcm/send/bbb", createdAt = 20L)
            store.save(a)
            store.save(b)

            assertEquals(listOf(a, b), store.list(), "every field round-trips, oldest first")
        }
    }

    @Test
    fun reSavingTheSameEndpointUpdatesInsteadOfDuplicating() = runBlocking {
        withTimeout(20_000) {
            val store = freshStore()
            val endpoint = "https://web.push.apple.com/same"
            store.save(sub(endpoint, p256dh = "old-p256dh", auth = "old-auth", createdAt = 10L))
            store.save(sub(endpoint, p256dh = "new-p256dh", auth = "new-auth", createdAt = 99L))

            val rows = store.list()
            assertEquals(1, rows.size, "endpoint is the identity — a re-subscribe replaces the row")
            assertEquals(
                PushSubscription(endpoint, "new-p256dh", "new-auth", 99L),
                rows.single(),
                "rotated keys (and the new created_at) win",
            )
        }
    }

    @Test
    fun removeIsIdempotentAndOnlyTouchesTheNamedEndpoint() = runBlocking {
        withTimeout(20_000) {
            val store = freshStore()
            val kept = sub("https://fcm.googleapis.com/fcm/send/kept", createdAt = 10L)
            val doomed = sub("https://web.push.apple.com/doomed", createdAt = 20L)
            store.save(kept)
            store.save(doomed)

            store.remove(doomed.endpoint)
            assertEquals(listOf(kept), store.list(), "only the named endpoint is dropped")

            store.remove(doomed.endpoint)
            store.remove("https://web.push.apple.com/never-stored")
            assertEquals(listOf(kept), store.list(), "removing an absent endpoint changes nothing")
        }
    }

    @Test
    fun theInitCreateAddsTheTableToAPreExistingDatabase() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(prePushSchema)
            val store = SqlitePushStore(driver)
            val one = sub("https://web.push.apple.com/mig", createdAt = 5L)
            store.save(one)
            assertEquals(listOf(one), store.list(), "the created table is usable")

            val reopened = SqlitePushStore(driver)
            assertEquals(listOf(one), reopened.list(), "a second open over the migrated DB still reads")

            val sessionCount = driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM sessions",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0)!!)
                },
                parameters = 0,
            ).value
            assertEquals(0L, sessionCount, "creating the push table left the rest of the schema alone")
        }
    }

    private val prePushSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (session_id, seq))",
                0,
            )
            driver.execute(
                null,
                "CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, tags TEXT NOT NULL, " +
                    "agent TEXT NOT NULL, provider_session_id TEXT, model TEXT, cli_version TEXT, cli_path TEXT, " +
                    "cwd TEXT NOT NULL, repository TEXT, worktree TEXT, branch TEXT, tmux_session TEXT NOT NULL, " +
                    "pane_id TEXT, state TEXT NOT NULL, state_source TEXT, last_seq INTEGER NOT NULL, " +
                    "read_cursor INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                    "archived INTEGER NOT NULL DEFAULT 0)",
                0,
            )
            driver.execute(null, "CREATE INDEX events_session_seq ON events(session_id, seq)", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }
}
