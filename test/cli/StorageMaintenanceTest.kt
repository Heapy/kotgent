package io.kotgent.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StorageMaintenanceTest {
    @Test
    fun aStartupPruneFailureIsReportedAndTheNextScheduledPruneStillRuns() = runBlocking {
        withTimeout(10.seconds) {
            val ticks = Channel<Unit>()
            val recovered = Channel<Unit>()
            val errors = mutableListOf<String>()
            var calls = 0
            val job = startStorageMaintenance(
                scope = this,
                prune = { if (++calls == 1) error("database locked") else recovered.send(Unit) },
                awaitNext = { ticks.receive() },
                onError = { errors += it },
            )
            try {
                assertEquals(1, calls)
                assertTrue(errors.single().contains("database locked"))
                ticks.send(Unit)
                recovered.receive()
                assertEquals(2, calls)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun startupPrunesBeforeReturningAndPeriodicFailureDoesNotEndTheSchedule() = runBlocking {
        withTimeout(10.seconds) {
            val ticks = Channel<Unit>()
            val completions = Channel<Int>(Channel.UNLIMITED)
            val errors = Channel<String>(Channel.UNLIMITED)
            var calls = 0
            val job = startStorageMaintenance(
                scope = this,
                prune = {
                    calls++
                    if (calls == 2) error("temporary database failure")
                    completions.send(calls)
                },
                awaitNext = { ticks.receive() },
                onError = { errors.trySend(it).getOrThrow() },
            )
            try {
                assertEquals(1, calls, "startup retention completes before the caller exposes ingress")
                assertEquals(1, completions.receive())
                ticks.send(Unit)
                assertTrue(errors.receive().contains("temporary database failure"))
                ticks.send(Unit)
                assertEquals(3, completions.receive(), "the next scheduled prune still runs")
            } finally {
                job.cancelAndJoin()
                ticks.close()
                completions.close()
                errors.close()
            }
        }
    }

    @Test
    fun cancellationDuringPruningEndsTheJobWithoutReportingAFailure() = runBlocking {
        withTimeout(10.seconds) {
            val ticks = Channel<Unit>()
            var calls = 0
            val errors = mutableListOf<String>()
            val job = startStorageMaintenance(
                scope = this,
                prune = {
                    calls++
                    if (calls == 2) throw CancellationException("shutting down")
                },
                awaitNext = { ticks.receive() },
                onError = { errors += it },
            )
            try {
                ticks.send(Unit)
                job.join()
                assertTrue(job.isCancelled)
                assertTrue(errors.isEmpty())
                assertEquals(2, calls)
            } finally {
                job.cancelAndJoin()
                ticks.close()
            }
        }
    }
}
