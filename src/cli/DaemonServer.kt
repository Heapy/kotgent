package io.kotgent.cli

import io.kotgent.push.PushStore
import io.kotgent.transport.KotgentServer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The optional push pieces retained for one daemon run: the route dependencies plus their transport
 * cleanup. Keeping this host-free lets the production startup ordering be exercised without starting the
 * blocking daemon command or constructing its native SQLite/NSURLSession edges.
 */
class DaemonPush(
    val store: PushStore,
    val publicKey: suspend () -> String,
    val close: suspend () -> Unit,
)

/** A bound production server and the optional push runtime assembled immediately before it. */
class DaemonServer(
    val server: KotgentServer,
    val push: DaemonPush?,
)

/**
 * Run a suspending startup step after its resource has already been acquired, compensating if that step
 * fails before an owner can be returned to the caller.
 *
 * Compensation is [NonCancellable] because shutdown cancellation is one of the failures this exists to
 * handle. A cleanup failure is suppressed onto the startup failure so the original cause remains primary
 * without hiding a leaked-resource diagnostic.
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
 * Assemble optional push first, await whatever readiness barrier that assembler owns, then build and bind
 * the server that receives its route dependencies.
 *
 * This is the non-blocking slice of [Commands.daemon] used by integration tests. The ordering is
 * load-bearing: [assemblePush] starts and seeds the notifier, and only after it returns can
 * [createServer] expose hook ingress capable of producing attention transitions.
 */
suspend fun startDaemonServer(
    assemblePush: suspend () -> DaemonPush?,
    createServer: (DaemonPush?) -> KotgentServer,
): DaemonServer {
    val push = assemblePush()
    return withStartupCompensation(
        compensate = { push?.close?.invoke() },
    ) {
        DaemonServer(createServer(push).start(), push)
    }
}
