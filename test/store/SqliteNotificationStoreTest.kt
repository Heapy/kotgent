package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.NOTIFICATION_RETENTION_MILLIS
import io.kotgent.core.NOTIFICATION_WINDOW_MILLIS
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageResetNotification
import io.kotgent.core.UsageSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.closedir
import platform.posix.getenv
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class SqliteNotificationStoreTest {
    private val epoch = 1_800_000_000_000L
    private val week = 604_800L

    private inner class Fixture {
        val driver = inMemoryDriver(preNotificationSchema)
        var clock = epoch
        val store = SqliteNotificationStore(driver) { clock }
    }

    private fun test(block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.driver.close()
            }
        }
    }

    private fun reset(id: Long = 1, at: Long = epoch) = UsageReset(
        provider = "claude",
        windowKey = "seven_day",
        expectedAt = epoch + week * 1_000,
        observedAt = at,
        usedBefore = 73.5,
        usedBeforeSeenAt = at - 60_000,
        early = true,
        resetsAtMoved = false,
        windowSeconds = week,
        id = id,
    )

    @Test
    fun insertKeepsResetIdentityAndOriginalFieldsOnDuplicate() = test { fixture ->
        val first = reset()
        assertTrue(fixture.store.insert(first))
        assertFalse(fixture.store.insert(first))
        fixture.clock += 10_000
        assertFalse(fixture.store.insert(first.copy(
            observedAt = fixture.clock,
            expectedAt = first.expectedAt!! + 10_000,
            usedBefore = 80.0,
        )))
        assertEquals(listOf(UsageResetNotification(
            id = "usage.reset:1",
            createdAt = first.observedAt,
            provider = first.provider,
            windowKey = first.windowKey,
            expectedAt = first.expectedAt,
            usedBefore = first.usedBefore,
            usedBeforeSeenAt = first.usedBeforeSeenAt,
            windowSeconds = first.windowSeconds,
        )), fixture.store.recent(0))
        assertEquals(1L, scalar(fixture.driver, "SELECT COUNT(*) FROM notifications"))
    }

    @Test
    fun onlyEarlyWeeklyResetsWithDurableIdsCanEnterInbox() = test { fixture ->
        assertFailsWith<IllegalArgumentException> { fixture.store.insert(reset(id = 0)) }
        assertFailsWith<IllegalArgumentException> { fixture.store.insert(reset(id = -1)) }
        val rejected = listOf(
            reset(id = 1).copy(early = false),
            reset(id = 2).copy(windowKey = "five_hour", windowSeconds = 18_000),
            reset(id = 3).copy(provider = "codex", windowKey = "secondary", windowSeconds = 18_000),
            reset(id = 4).copy(provider = "codex", windowKey = "secondary", windowSeconds = null),
            reset(id = 5).copy(provider = "unknown"),
        )
        for (item in rejected) assertFalse(fixture.store.insert(item))
        assertTrue(fixture.store.recent(0).isEmpty())

        assertTrue(fixture.store.insert(reset(id = 6).copy(
            provider = "codex", windowKey = "primary", resetsAtMoved = true,
        )))
        assertTrue(fixture.store.insert(reset(id = 7).copy(
            provider = "codex", windowKey = "secondary", resetsAtMoved = true,
        )))
        assertTrue(fixture.store.insert(reset(id = 8).copy(expectedAt = null, windowSeconds = null)))
        assertEquals(listOf("usage.reset:6", "usage.reset:7", "usage.reset:8"), fixture.store.recent(0).map { it.id })
        val nullable = fixture.store.recent(0).last()
        assertEquals(null, nullable.expectedAt)
        assertEquals(null, nullable.windowSeconds)
    }

    @Test
    fun recentIncludesBoundaryAndOrdersByResetTimeThenId() = test { fixture ->
        val cutoff = epoch - NOTIFICATION_WINDOW_MILLIS
        assertTrue(fixture.store.insert(reset(id = 1, at = cutoff - 1)))
        assertTrue(fixture.store.insert(reset(id = 2, at = cutoff)))
        assertTrue(fixture.store.insert(reset(id = 4, at = epoch)))
        assertTrue(fixture.store.insert(reset(id = 3, at = epoch)))
        assertEquals(
            listOf("usage.reset:3", "usage.reset:4", "usage.reset:2"),
            fixture.store.recent(cutoff).map { it.id },
        )
        fixture.clock += NOTIFICATION_WINDOW_MILLIS + 1
        assertTrue(fixture.store.recent(fixture.clock - NOTIFICATION_WINDOW_MILLIS).isEmpty())
        assertEquals(4L, scalar(fixture.driver, "SELECT COUNT(*) FROM notifications"), "the read window does not delete rows")
    }

    @Test
    fun pruneRemovesOnlyRowsOlderThanRetention() = test { fixture ->
        val cutoff = epoch - NOTIFICATION_RETENTION_MILLIS
        assertTrue(fixture.store.insert(reset(id = 1, at = cutoff - 1)))
        assertTrue(fixture.store.insert(reset(id = 2, at = cutoff)))
        assertTrue(fixture.store.insert(reset(id = 3, at = epoch)))
        fixture.store.prune()
        assertEquals(listOf("usage.reset:3", "usage.reset:2"), fixture.store.recent(0).map { it.id })
        fixture.clock += 1
        fixture.store.prune()
        assertEquals(listOf("usage.reset:3"), fixture.store.recent(0).map { it.id })
    }

    @Test
    fun additiveCreationPreservesOldDatabaseAndDuplicateIdentityAfterReopen() = runBlocking {
        withTimeout(20.seconds) {
            withTempDbDir { directory ->
                fun open() = openDatabase(directory)
                val original = open()
                try {
                    original.execute(null, "INSERT INTO legacy_marker VALUES ('preserved')", 0)
                    assertEquals(0L, scalar(original, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'notifications'"))
                    assertTrue(SqliteNotificationStore(original) { epoch }.insert(reset()))
                } finally {
                    original.close()
                }

                val reopened = open()
                try {
                    val store = SqliteNotificationStore(reopened) { epoch + 1_000 }
                    assertEquals(1L, scalar(reopened, "SELECT COUNT(*) FROM legacy_marker WHERE value = 'preserved'"))
                    assertFalse(store.insert(reset().copy(observedAt = epoch + 1_000)))
                    assertEquals("usage.reset:1", store.recent(0).single().id)
                    assertEquals(epoch, store.recent(0).single().createdAt)
                    assertEquals(1L, scalar(reopened, "SELECT COUNT(*) FROM notifications"))
                } finally {
                    reopened.close()
                }
            }
        }
    }

    @Test
    fun replayAfterInboxCommitBeforeResetAcknowledgementRemainsOneNotification() = runBlocking {
        withTimeout(20.seconds) {
            withTempDbDir { directory ->
                var clock = epoch
                var durableId = 0L
                val original = openDatabase(directory)
                try {
                    val usage = SqliteUsageStore(original) { clock }
                    val inbox = SqliteNotificationStore(original) { clock }
                    usage.observe(UsageObservation(
                        "claude", "seven_day", 73.5, epoch + week * 1_000, week,
                        source = UsageSource("session:incarnation", 1, clock),
                    ))
                    clock += 1_000
                    usage.observe(UsageObservation(
                        "claude", "seven_day", 3.0, epoch + week * 1_000, week,
                        source = UsageSource("session:incarnation", 2, clock),
                    ))
                    val pending = usage.pendingResetNotifications(0).single()
                    durableId = pending.id
                    assertTrue(durableId > 0)
                    assertTrue(inbox.insert(pending))
                    assertEquals(listOf(pending), usage.pendingResetNotifications(0))
                } finally {
                    original.close()
                }

                val reopened = openDatabase(directory)
                try {
                    val usage = SqliteUsageStore(reopened) { clock }
                    val inbox = SqliteNotificationStore(reopened) { clock }
                    val replayed = usage.pendingResetNotifications(0).single()
                    assertEquals(durableId, replayed.id)
                    assertFalse(inbox.insert(replayed))
                    usage.markResetNotificationProjected(replayed.id)
                    assertTrue(usage.pendingResetNotifications(0).isEmpty())
                    assertEquals("usage.reset:$durableId", inbox.recent(0).single().id)
                    assertEquals(1L, scalar(reopened, "SELECT COUNT(*) FROM notifications"))
                } finally {
                    reopened.close()
                }

                val acknowledged = openDatabase(directory)
                try {
                    assertTrue(SqliteUsageStore(acknowledged) { clock }.pendingResetNotifications(0).isEmpty())
                    assertEquals("usage.reset:$durableId", SqliteNotificationStore(acknowledged) { clock }.recent(0).single().id)
                } finally {
                    acknowledged.close()
                }
            }
        }
    }

    private fun openDatabase(directory: String) = NativeSqliteDriver(
        schema = preNotificationSchema,
        name = "notification-reopen.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = directory)) },
    )

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(assertNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value

    private val preNotificationSchema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE legacy_marker (value TEXT NOT NULL)", 0)
            return QueryResult.Unit
        }
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    private inline fun withTempDbDir(block: (String) -> Unit) {
        val directory = memScoped {
            val temporaryRoot = (getenv("TMPDIR")?.toKString() ?: "/tmp").trimEnd('/')
            val encoded = "$temporaryRoot/kotgent-notifications-test-XXXXXX".encodeToByteArray()
            val chars = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { index, byte -> chars[index] = byte }
            chars[encoded.size] = 0
            mkdtemp(chars)?.toKString() ?: error("could not create the notification-store test directory")
        }
        try {
            block(directory)
        } finally {
            val handle = opendir(directory)
            if (handle != null) {
                val names = buildList {
                    while (true) {
                        val entry = readdir(handle) ?: break
                        val name = entry.pointed.d_name.toKString()
                        if (name != "." && name != "..") add(name)
                    }
                }
                closedir(handle)
                for (name in names) unlink("$directory/$name")
            }
            rmdir(directory)
        }
    }
}
