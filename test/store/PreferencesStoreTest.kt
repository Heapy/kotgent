package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.db.KotgentDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferencesStoreTest {

    @Test
    fun aFreshStoreStartsWithTheSeededDefaults() = runBlocking {
        withTimeout(20_000) {
            assertEquals(
                UiPreferences(basePath = "", groupingLevel = 1, revision = 0),
                SqliteEventStore.inMemory().preferences.value,
            )
        }
    }

    @Test
    fun saveThenReadRoundTripsEveryField() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory()

            val saved = store.savePreferences("/Users/me/dev", 3)

            assertEquals(UiPreferences("/Users/me/dev", 3, 1), saved)
            assertEquals(saved, store.preferences.value)
        }
    }

    @Test
    fun everyAcceptedSaveIncrementsTheRevisionEvenWhenValuesMatch() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory()

            assertEquals(1L, store.savePreferences("/work", 2).revision)
            assertEquals(2L, store.savePreferences("/work", 2).revision)
            assertEquals(3L, store.savePreferences("", 1).revision)
        }
    }

    @Test
    fun preferencesSurviveAStoreRestart() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val first = SqliteEventStore.using(driver)
            val saved = first.savePreferences("/persisted", 4)

            val reopened = SqliteEventStore.using(driver)

            assertEquals(saved, reopened.preferences.value)
            assertEquals(2L, reopened.savePreferences("/after-restart", 0).revision)
        }
    }

    @Test
    fun theStateFlowPublishesAnAcceptedSave() = runBlocking {
        withTimeout(20_000) {
            val store = SqliteEventStore.inMemory()
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                store.preferences.drop(1).first()
            }

            val saved = store.savePreferences("/live", 2)

            assertEquals(saved, next.await())
        }
    }

    @Test
    fun initCreatesSeedsAndReopensTheTableOnALegacyDatabase() = runBlocking {
        withTimeout(20_000) {
            val driver = inMemoryDriver(prePreferencesSchema)
            val first = SqliteEventStore.using(driver)
            assertEquals(
                UiPreferences("", 1, 0),
                first.preferences.value,
                "opening a legacy DB creates and seeds the singleton",
            )
            val saved = first.savePreferences("/legacy", 3)

            val reopened = SqliteEventStore.using(driver)
            assertEquals(saved, reopened.preferences.value)
            assertEquals(2L, reopened.savePreferences("/legacy-again", 1).revision)
        }
    }

    private val prePreferencesSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE events (session_id TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                    "type TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, " +
                    "PRIMARY KEY (session_id, seq))",
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
