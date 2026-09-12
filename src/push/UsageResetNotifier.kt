package io.kotgent.push

import io.kotgent.cli.eprintln
import io.kotgent.core.NOTIFICATION_WINDOW_MILLIS
import io.kotgent.core.isWeeklyUsageWindow
import io.kotgent.store.NotificationStore
import io.kotgent.store.UsageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class UsageResetNotifier(
    private val usageStore: UsageStore,
    private val inbox: NotificationStore,
    private val wake: (suspend (String) -> Unit)? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val awaitRetry: suspend (Long) -> Unit = { delay(it) },
    private val onError: (String) -> Unit = ::eprintln,
) {
    /** Returns after subscription and durable projection, with network delivery held until activation. */
    suspend fun start(scope: CoroutineScope): Running {
        val ready = CompletableDeferred<Unit>()
        val deliveryGate = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                run(ready, deliveryGate)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // Catch inside launch: an unhandled child failure can terminate a Native daemon.
                if (!ready.completeExceptionally(failure)) {
                    onError("usage notifications: the watcher stopped: ${failure.describe()}")
                }
            }
        }
        job.invokeOnCompletion { failure ->
            ready.completeExceptionally(
                failure ?: IllegalStateException("the usage notification watcher ended before readiness"),
            )
        }
        try {
            ready.await()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw failure
        }
        return Running(job, deliveryGate)
    }

    class Running internal constructor(
        val job: Job,
        private val deliveryGate: CompletableDeferred<Unit>,
    ) {
        fun activateDelivery() {
            deliveryGate.complete(Unit)
        }

        suspend fun close() {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    private suspend fun run(
        ready: CompletableDeferred<Unit>,
        deliveryGate: CompletableDeferred<Unit>,
    ): Unit = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val signals = Channel<Unit>(Channel.CONFLATED)
        // Every payload-less wake fetches the complete inbox, so one follow-up wake covers a burst.
        val deliveries = Channel<String>(Channel.CONFLATED)
        launch(start = CoroutineStart.UNDISPATCHED) {
            usageStore.resets.onSubscription { subscribed.complete(Unit) }.collect {
                val _ = signals.trySend(Unit)
            }
        }
        if (wake != null) {
            launch {
                deliveryGate.await()
                for (topic in deliveries) deliver(topic)
            }
        }
        try {
            subscribed.await()
            projectWithRetries(deliveries)?.let { throw it }
            ready.complete(Unit)
            for (signal in signals) {
                projectWithRetries(deliveries)?.let { failure ->
                    onError("usage notifications: inbox projection failed after 3 attempts; pending resets retained: ${failure.describe()}")
                }
            }
        } finally {
            signals.close()
            deliveries.close()
        }
    }

    private suspend fun projectWithRetries(deliveries: Channel<String>): Exception? {
        for (attempt in 0..2) {
            try {
                val pending = usageStore.pendingResetNotifications(now() - NOTIFICATION_WINDOW_MILLIS)
                for (reset in pending) {
                    if (!reset.early || !isWeeklyUsageWindow(reset.provider, reset.windowKey, reset.windowSeconds)) continue
                    val _ = inbox.insert(reset)
                    usageStore.markResetNotificationProjected(reset.id)
                    if (wake != null) {
                        val _ = deliveries.trySend("usage.reset:${reset.id}")
                    }
                }
                return null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (attempt == 2) return failure
            }
            awaitRetry(if (attempt == 0) 250L else 1_000L)
        }
        return null
    }

    private suspend fun deliver(topic: String) {
        try {
            wake?.invoke(topic)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            onError("usage notifications: cannot send wake $topic: ${failure.describe()}")
        }
    }
}
