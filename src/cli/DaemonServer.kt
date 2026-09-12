package io.kotgent.cli

import io.kotgent.push.PushStore
import io.kotgent.push.UsageResetNotifier
import io.kotgent.transport.KotgentServer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Push resources owned by one daemon run. */
class DaemonPush(
    val store: PushStore,
    val publicKey: suspend () -> String,
    val close: suspend () -> Unit,
)

class DaemonServer(
    val server: KotgentServer,
    val push: DaemonPush?,
)

/**
 * Compensates an acquired resource when startup fails. Cleanup is [NonCancellable], and its failure is
 * suppressed so the startup failure remains primary.
 */
suspend fun <T> withStartupCompensation(
    compensate: suspend () -> Unit,
    start: suspend () -> T,
): T = try {
    start()
} catch (failure: Throwable) {
    withContext(NonCancellable) {
        try {
            compensate()
        } catch (compensationFailure: Throwable) {
            if (compensationFailure !== failure) failure.addSuppressed(compensationFailure)
        }
    }
    throw failure
}

/**
 * Watchers subscribe and project pending inbox work before ingress opens; recovered usage wakes wait
 * until the endpoint that supplies their banners is listening.
 */
suspend fun startDaemonServer(
    assemblePush: suspend () -> DaemonPush?,
    startUsage: (suspend (DaemonPush?) -> UsageResetNotifier.Running)? = null,
    createServer: (DaemonPush?) -> KotgentServer,
): DaemonServer {
    val push = assemblePush()
    return withStartupCompensation(
        compensate = { push?.close?.invoke() },
    ) {
        val usage = startUsage?.invoke(push)
        withStartupCompensation(compensate = { usage?.close() }) {
            val server = createServer(push).start()
            usage?.activateDelivery()
            DaemonServer(server, push)
        }
    }
}
