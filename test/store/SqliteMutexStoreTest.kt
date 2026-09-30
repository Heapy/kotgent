package io.kotgent.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.SessionId
import io.kotgent.db.KotgentDatabase
import io.kotgent.mutex.Holding
import io.kotgent.mutex.MutexKey
import io.kotgent.mutex.MutexToken
import io.kotgent.mutex.WaitTicket
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class SqliteMutexStoreTest {
    private val build = MutexKey("kotlin-build")
    private val alice = SessionId("alice")
    private val bob = SessionId("bob")
    private var clock = 1_800_000_000_000L
    private var minted = 0

    private fun CoroutineScope.open(driver: SqlDriver, job: Job) = SqliteMutexStore(
        driver,
        CoroutineScope(coroutineContext + job),
        now = { clock },
        newToken = { MutexToken("tok${++minted}") },
        newTicket = { WaitTicket("tkt${++minted}") },
    )

    private fun test(block: suspend CoroutineScope.(workers: Job) -> Unit) = runBlocking {
        withTimeout(20.seconds) {
            val workers = Job()
            try {
                block(workers)
            } finally {
                workers.cancel()
            }
        }
    }

    private suspend fun MutexStore.holds(session: SessionId): Holding =
        assertIs<MutexAcquireResult.Acquired>(acquire(build, session, null, Duration.ZERO)).holding

    private suspend fun MutexStore.waits(session: SessionId, ticket: WaitTicket? = null): MutexAcquireResult.Pending =
        assertIs<MutexAcquireResult.Pending>(acquire(build, session, ticket, Duration.ZERO))

    @Test
    fun aFreshDatabaseAcquiresReleasesAndGrantsInOrder() = test { workers ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = open(driver, workers)
            val held = store.holds(alice)
            val pending = store.waits(bob)
            assertEquals(1, pending.position)
            assertIs<MutexAcquireResult.Pending>(store.acquire(build, bob, pending.ticket, Duration.ZERO))

            assertEquals(MutexReleaseResult.Released(held), store.release(held.token, alice))
            assertEquals(MutexReleaseResult.NotHeld, store.release(held.token, alice))
            val claimed = assertIs<MutexAcquireResult.Acquired>(
                store.acquire(build, bob, pending.ticket, Duration.ZERO),
            )
            assertEquals(bob, claimed.holding.sessionId)
            assertEquals(listOf(claimed.holding), store.listing.value.entries.map { it.holding })
        } finally {
            driver.close()
        }
    }

    @Test
    fun anOpenLongPollIsWokenByTheGrant() = test { workers ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = open(driver, workers)
            val held = store.holds(alice)
            val waiting = async { store.acquire(build, bob, null, 10.seconds) }
            store.listing.first { it.entries.single().waiters.isNotEmpty() }
            val _ = store.release(held.token, alice)
            assertEquals(bob, assertIs<MutexAcquireResult.Acquired>(waiting.await()).holding.sessionId)
        } finally {
            driver.close()
        }
    }

    @Test
    fun aCancelledLongPollStartsTheLeaseAndAnUnclaimedTicketIsDropped() = test { workers ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = open(driver, workers)
            val held = store.holds(alice)
            val waiting = async { store.acquire(build, bob, null, 10.seconds) }
            store.listing.first { it.entries.single().waiters.isNotEmpty() }
            val ticket = store.listing.value.entries.single().waiters.single().ticket
            waiting.cancelAndJoin()

            clock += 31_000
            store.expire()
            assertTrue(store.listing.value.entries.single().waiters.isEmpty())
            val _ = store.release(held.token, alice)
            assertEquals(MutexAcquireResult.UnknownTicket, store.acquire(build, bob, ticket, Duration.ZERO))
            assertTrue(store.listing.value.entries.isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun onlyTheHoldingSessionReleasesAndTicketsStayWithTheirSession() = test { workers ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = open(driver, workers)
            val held = store.holds(alice)
            assertEquals(MutexReleaseResult.HeldByAnotherSession(held), store.release(held.token, bob))
            val pending = store.waits(bob)
            assertEquals(MutexAcquireResult.ForeignTicket, store.acquire(build, alice, pending.ticket, Duration.ZERO))
            assertEquals(
                MutexAcquireResult.ForeignTicket,
                store.acquire(MutexKey("other"), bob, pending.ticket, Duration.ZERO),
            )
            assertEquals(held, store.forceRelease(build))
            assertNull(store.forceRelease(build))
        } finally {
            driver.close()
        }
    }

    @Test
    fun sessionEndReleasesHoldingsAndDropsWaitersWithoutBlocking() = test { workers ->
        val driver = inMemoryDriver(KotgentDatabase.Schema)
        try {
            val store = open(driver, workers)
            val _ = store.holds(alice)
            val _ = store.waits(alice)
            store.sessionEnded(alice)
            val listing = store.listing.first { it.entries.isEmpty() }
            assertTrue(listing.rev > 0)
        } finally {
            driver.close()
        }
    }

    @Test
    fun holdingsAndRevisionSurviveReopenOfAnExistingDatabase() = test { workers ->
        withTempDbDir { directory ->
            val original = openDatabase(directory)
            val [held, revBeforeRestart] = try {
                original.execute(null, "INSERT INTO legacy_marker VALUES ('preserved')", 0)
                assertEquals(0L, scalar(original, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'mutexes'"))
                val store = open(original, workers)
                val held = store.holds(alice)
                val other = assertIs<MutexAcquireResult.Acquired>(
                    store.acquire(MutexKey("other"), alice, null, Duration.ZERO),
                ).holding
                val _ = store.release(other.token, alice)
                held to store.listing.value.rev
            } finally {
                original.close()
            }

            val reopened = openDatabase(directory)
            try {
                val store = open(reopened, workers)
                assertEquals(1L, scalar(reopened, "SELECT COUNT(*) FROM legacy_marker WHERE value = 'preserved'"))
                assertEquals(listOf(held), store.listing.value.entries.map { it.holding })
                assertEquals(revBeforeRestart, store.listing.value.rev)
                val _ = store.waits(bob)
                assertTrue(store.listing.value.rev > revBeforeRestart)
                assertEquals(MutexReleaseResult.Released(held), store.release(held.token, alice))
            } finally {
                reopened.close()
            }
        }
    }

    private fun openDatabase(directory: String) = NativeSqliteDriver(
        schema = preMutexSchema,
        name = "mutex-reopen.db",
        onConfiguration = { it.copy(extendedConfig = it.extendedConfig.copy(basePath = directory)) },
    )

    private fun scalar(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: error("no value"))
        },
        parameters = 0,
    ).value

    private val preMutexSchema = object : SqlSchema<QueryResult.Value<Unit>> {
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
            val encoded = "$temporaryRoot/kotgent-mutex-test-XXXXXX".encodeToByteArray()
            val chars = allocArray<ByteVar>(encoded.size + 1)
            encoded.forEachIndexed { index, byte -> chars[index] = byte }
            chars[encoded.size] = 0
            mkdtemp(chars)?.toKString() ?: error("could not create the mutex-store test directory")
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
