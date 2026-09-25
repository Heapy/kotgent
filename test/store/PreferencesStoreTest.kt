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
import kotlin.time.Duration.Companion.seconds

class PreferencesStoreTest {

    @Test
    fun aFreshStoreStartsWithTheSeededDefaults() = runBlocking {
        withTimeout(20.seconds) {
            assertEquals(
                UiPreferences(basePath = "", groupingLevel = 1, revision = 0),
                SqliteEventStore.inMemory().preferences.value,
            )
        }
    }

    @Test
    fun saveThenReadRoundTripsEveryField() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory()

            val saved = store.savePreferences("/Users/me/dev", 3)

            assertEquals(UiPreferences("/Users/me/dev", 3, 1), saved)
            assertEquals(saved, store.preferences.value)
        }
    }

    @Test
    fun everyAcceptedSaveIncrementsTheRevisionEvenWhenValuesMatch() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory()

            assertEquals(1L, store.savePreferences("/work", 2).revision)
            assertEquals(2L, store.savePreferences("/work", 2).revision)
            assertEquals(3L, store.savePreferences("", 1).revision)
        }
    }

    @Test
    fun preferencesSurviveAStoreRestart() = runBlocking {
        withTimeout(20.seconds) {
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
        withTimeout(20.seconds) {
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
        withTimeout(20.seconds) {
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

    @Test
    fun markingAFolderAddsThePathAndBumpsTheSharedRevision() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory()

            val marked = store.setFolderAdhd("/Users/me/dev/api", true)

            assertEquals(listOf("/Users/me/dev/api"), marked.adhdPaths)
            assertEquals(1L, marked.revision, "a folder mark moves the revision clients merge on")
            assertEquals(marked, store.preferences.value)
        }
    }

    @Test
    fun unmarkingAFolderDropsThePathAndBumpsTheRevision() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory()
            val _ = store.setFolderAdhd("/a", true)
            val _ = store.setFolderAdhd("/b", true)

            val cleared = store.setFolderAdhd("/a", false)

            assertEquals(listOf("/b"), cleared.adhdPaths, "only the named path is dropped")
            assertEquals(3L, cleared.revision)
        }
    }

    @Test
    fun markingTheSamePathTwiceLeavesOneEntry() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory()
            val _ = store.setFolderAdhd("/a", true)

            val again = store.setFolderAdhd("/a", true)

            assertEquals(listOf("/a"), again.adhdPaths)
            assertEquals(2L, again.revision, "a repeated mark still advertises itself to other clients")
        }
    }

    @Test
    fun unmarkingAPathThatWasNeverMarkedIsHarmless() = runBlocking {
        withTimeout(20.seconds) {
            val store = SqliteEventStore.inMemory()

            val cleared = store.setFolderAdhd("/never", false)

            assertEquals(emptyList(), cleared.adhdPaths)
            assertEquals(1L, cleared.revision)
        }
    }

    @Test
    fun groupingAndFolderMarksDoNotDisturbEachOther() = runBlocking {
        withTimeout(20.seconds) {
            val stores = listOf<PreferencesStore>(SqliteEventStore.inMemory(), FakePreferencesStore())
            for (store in stores) {
                val who = store::class.simpleName
                val _ = store.setFolderAdhd("/Users/me/dev", true)

                val saved = store.savePreferences("/Users/me", 2)
                assertEquals(listOf("/Users/me/dev"), saved.adhdPaths, "$who: saving grouping keeps the marks")

                val marked = store.setFolderAdhd("/Users/me/other", true)
                assertEquals("/Users/me", marked.basePath, "$who: marking keeps basePath")
                assertEquals(2, marked.groupingLevel, "$who: marking keeps groupingLevel")
            }
        }
    }

    @Test
    fun folderMarksSurviveAStoreRestart() = runBlocking {
        withTimeout(20.seconds) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val first = SqliteEventStore.using(driver)
            val marked = first.setFolderAdhd("/persisted", true)

            val reopened = SqliteEventStore.using(driver)

            assertEquals(marked, reopened.preferences.value)
        }
    }

    @Test
    fun initCreatesTheFolderSettingsTableOnALegacyDatabase() = runBlocking {
        withTimeout(20.seconds) {
            val driver = inMemoryDriver(prePreferencesSchema)
            val first = SqliteEventStore.using(driver)
            assertEquals(emptyList(), first.preferences.value.adhdPaths, "a legacy DB reports no marks")

            val marked = first.setFolderAdhd("/legacy", true)
            assertEquals(listOf("/legacy"), marked.adhdPaths, "…and is writable after the migration")

            val reopened = SqliteEventStore.using(driver)
            assertEquals(listOf("/legacy"), reopened.preferences.value.adhdPaths)
        }
    }

    @Test
    fun unmarkingAFolderClearsOnlyItsFlagAndKeepsTheRow() = runBlocking {
        withTimeout(20.seconds) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val store = SqliteEventStore.using(driver)
            val _ = store.setFolderAdhd("/a", true)

            val cleared = store.setFolderAdhd("/a", false)

            assertEquals(emptyList(), cleared.adhdPaths)
            assertEquals(1L, folderSettingsRows(driver), "the row outlives the flag, so other folder settings would too")

            val marked = store.setFolderAdhd("/a", true)

            assertEquals(listOf("/a"), marked.adhdPaths, "marking again reuses the kept row")
            assertEquals(1L, folderSettingsRows(driver))
        }
    }

    @Test
    fun loweringTheLevelDropsOnlyTheMarksDeeperThanTheNewLevel() = runBlocking {
        withTimeout(20.seconds) {
            for (store in bothStores()) {
                val who = store::class.simpleName
                val _ = store.savePreferences("/", 2)
                for (path in listOf("/", "/a", "/a/b")) {
                    val _ = store.setFolderAdhd(path, true)
                }

                val saved = store.savePreferences("/", 1)

                assertEquals(listOf("/", "/a"), saved.adhdPaths, "$who: level 1 draws no /a/b folder")
            }
        }
    }

    @Test
    fun raisingTheLevelKeepsEveryMark() = runBlocking {
        withTimeout(20.seconds) {
            for (store in bothStores()) {
                val _ = store.savePreferences("/", 1)
                val _ = store.setFolderAdhd("/a", true)

                val saved = store.savePreferences("/", 3)

                assertEquals(listOf("/a"), saved.adhdPaths, "${store::class.simpleName}")
            }
        }
    }

    @Test
    fun levelZeroKeepsOnlyAMarkOnTheBaseItself() = runBlocking {
        withTimeout(20.seconds) {
            for (store in bothStores()) {
                val _ = store.savePreferences("/a", 1)
                val _ = store.setFolderAdhd("/a", true)
                val _ = store.setFolderAdhd("/a/b", true)

                val saved = store.savePreferences("/a", 0)

                assertEquals(listOf("/a"), saved.adhdPaths, "${store::class.simpleName}: level 0 folds into the base")
            }
        }
    }

    @Test
    fun turningGroupingOffDropsEveryFolderMark() = runBlocking {
        withTimeout(20.seconds) {
            for (store in bothStores()) {
                val _ = store.savePreferences("/", 2)
                val _ = store.setFolderAdhd("/a", true)
                val _ = store.setFolderAdhd("/a/b", true)

                val saved = store.savePreferences("", 1)

                assertEquals(emptyList(), saved.adhdPaths, "${store::class.simpleName}: a flat list draws no folders")
            }
        }
    }

    @Test
    fun movingTheBaseDropsMarksThatLoseTheirFolderAndKeepsOnesAlreadyOutside() = runBlocking {
        withTimeout(20.seconds) {
            for (store in bothStores()) {
                val _ = store.savePreferences("/a", 2)
                val _ = store.setFolderAdhd("/a/b", true)
                val _ = store.setFolderAdhd("/d", true)

                val saved = store.savePreferences("/c", 2)

                assertEquals(
                    listOf("/d"),
                    saved.adhdPaths,
                    "${store::class.simpleName}: /a/b fell outside the base, /d was outside all along",
                )
            }
        }
    }

    @Test
    fun survivalComparesWholePathSegments() {
        assertEquals(false, adhdPathSurvivesGrouping("/a/bc", previousBase = "/", base = "/a/b", level = 4))
        assertEquals(true, adhdPathSurvivesGrouping("/a/b/c", previousBase = "/", base = "/a/b", level = 1))
        assertEquals(false, adhdPathSurvivesGrouping("/a/b/c/d", previousBase = "/", base = "/a/b", level = 1))
        assertEquals(true, adhdPathSurvivesGrouping("/d", previousBase = "/a", base = "/", level = 1))
    }

    private fun bothStores(): List<PreferencesStore> = listOf(SqliteEventStore.inMemory(), FakePreferencesStore())

    private fun folderSettingsRows(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM folder_settings",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(checkNotNull(cursor.getLong(0)))
            },
            parameters = 0,
        ).value

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
