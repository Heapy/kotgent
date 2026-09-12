package io.kotgent.webuicheck

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.UsageObservation
import io.kotgent.daemon.daemonEpochMillis
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.SqliteUsageStore
import io.kotgent.store.UsageStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/** The real usage projection stays in memory; commands control its receipt clock, not stored rows. */
class UsageFixture {
    private val driver = inMemoryDriver(KotgentDatabase.Schema)
    private val observationMutex = Mutex()

    @Volatile
    private var observationTime: Long = daemonEpochMillis()

    val store: UsageStore = SqliteUsageStore(driver) { observationTime }

    fun currentTimeMillis(): Long = observationTime

    suspend fun observe(observation: UsageObservation, observedAt: Long? = null) {
        observationMutex.withLock {
            observationTime = observedAt ?: daemonEpochMillis()
            store.observe(observation)
        }
    }

    fun close() {
        driver.close()
    }
}
