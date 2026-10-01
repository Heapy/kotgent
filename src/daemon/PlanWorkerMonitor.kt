package io.kotgent.daemon

import io.kotgent.core.SessionId
import io.kotgent.store.EventStore
import io.kotgent.store.PlanStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Session-end callbacks only enqueue. Startup reconciliation also catches workers lost while offline. */
class PlanWorkerMonitor(
    private val plans: PlanStore,
    private val sessions: EventStore,
    private val onError: (Throwable) -> Unit = {},
) {
    private val ended = Channel<SessionId>(Channel.UNLIMITED)
    private var worker: Job? = null

    fun sessionEnded(sessionId: SessionId) { val _ = ended.trySend(sessionId) }

    suspend fun reconcile() {
        val workers = plans.all().flatMap { doc -> doc.plan.tasks.mapNotNull { it.worker?.sessionId } }.distinct()
        for (id in workers) if (sessions.getSession(SessionId(id))?.state?.isAlive != true) plans.workerEnded(id)
    }

    suspend fun start(scope: CoroutineScope) {
        check(worker == null) { "plan worker monitor already started" }
        reconcile()
        worker = scope.launch {
            for (id in ended) {
                while (isActive) {
                    try { plans.workerEnded(id.value); break }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { onError(error); delay(1.seconds) }
                }
            }
        }
    }
}
