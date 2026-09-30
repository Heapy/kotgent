package io.kotgent.store

import app.cash.sqldelight.db.SqlDriver
import io.kotgent.core.SessionId
import io.kotgent.db.KotgentDatabase
import io.kotgent.mutex.Holding
import io.kotgent.mutex.MutexKey
import io.kotgent.mutex.MutexQueue
import io.kotgent.mutex.MutexToken
import io.kotgent.mutex.WaitTicket
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/** The only writer of `mutexes` and `mutex_revision`. */
class SqliteMutexStore(
    driver: SqlDriver,
    scope: CoroutineScope,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    leaseMillis: Long = MutexQueue.DEFAULT_LEASE_MILLIS,
    newToken: () -> MutexToken = { MutexToken(randomOpaqueId()) },
    newTicket: () -> WaitTicket = { WaitTicket(randomOpaqueId()) },
    onError: (Throwable) -> Unit = {},
) : MutexStore by MutexCoordinator(
    SqliteMutexRows(driver), scope, now, leaseMillis, newToken, newTicket, onError,
) {

    companion object {
        val CREATE_TABLES_IF_NOT_EXISTS: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS mutexes (" +
                "key TEXT NOT NULL PRIMARY KEY, " +
                "token TEXT NOT NULL UNIQUE, " +
                "session_id TEXT NOT NULL, " +
                "acquired_at INTEGER NOT NULL, " +
                "rev INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS mutex_revision (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "rev INTEGER NOT NULL)",
        )
    }
}

private class SqliteMutexRows(driver: SqlDriver) : MutexRows {
    private val db = KotgentDatabase(driver)
    private val mutexes get() = db.mutexesQueries

    init {
        for (statement in SqliteMutexStore.CREATE_TABLES_IF_NOT_EXISTS) driver.execute(null, statement, 0)
    }

    // The native driver sends transaction-less reads to a different connection; read inside one.
    override fun load(): PersistedMutexes = db.transactionWithResult {
        val holdings = mutexes.selectHoldings { key, token, sessionId, acquiredAt ->
            Holding(MutexKey(key), MutexToken(token), SessionId(sessionId), acquiredAt)
        }.executeAsList()
        PersistedMutexes(holdings, mutexes.revision().executeAsOne())
    }

    override fun commit(released: List<Holding>, acquired: List<Holding>, rev: Long) {
        db.transaction {
            for (holding in released) {
                val _ = mutexes.deleteHolding(holding.token.value)
            }
            for (holding in acquired) {
                val _ = mutexes.insertHolding(
                    holding.key.value,
                    holding.token.value,
                    holding.sessionId.value,
                    holding.acquiredAt,
                    rev,
                )
            }
            val _ = mutexes.raiseRevision(rev)
        }
    }
}
