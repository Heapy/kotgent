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
    return try {
        DaemonServer(createServer(push).start(), push)
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            try {
                push?.close?.invoke()
            } catch (closeFailure: Throwable) {
                // The bind/create failure remains primary, but a failed cleanup can leave the push
                // transport open and must remain visible to diagnostics.
                e.addSuppressed(closeFailure)
            }
        }
        throw e
    }
}
