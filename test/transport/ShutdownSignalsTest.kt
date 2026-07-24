package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.store.SqliteEventStore
import io.kotgent.sys.installShutdownSignals
import io.kotgent.sys.pendingShutdownSignal
import io.kotgent.sys.restoreDefaultShutdownSignals
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.SIGINT
import platform.posix.raise

/**
 * Guards the ORDER that makes Ctrl+C work: [installShutdownSignals] must run **after** the Ktor engine
 * started, so its SIGINT/SIGTERM handlers replace the ones `EmbeddedServer.start()` installs.
 *
 * Ktor's native shutdown hook (`ShutdownHookNative.kt`) registers `signal(SIGINT)`/`signal(SIGTERM)`
 * handlers whose only action is `EmbeddedServer.stop()` — no exit. With those in place a foreground
 * daemon answered Ctrl+C by quietly killing its own HTTP server and then living on forever in its park
 * loop: no listening socket, database still open, tty still held. This test reproduces the exact wiring
 * (start a real server, then install ours, then raise a real SIGINT) and asserts the two properties the
 * daemon depends on: the signal becomes an observable shutdown request, and the server is still serving
 * when it does — i.e. Ktor's handler did NOT run.
 */
class ShutdownSignalsTest {

    private val token = "shutdown-signals-test-token"

    @Test
    fun ourHandlerReplacesKtorsAndLeavesTheServerRunning() = runBlocking {
        withTimeout(40_000) {
            val store = SqliteEventStore.inMemory()
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                FakeTmux(),
                store,
                PaneRegistry(),
                AgentFactory { _, cwd ->
                    object : AgentAdapter {
                        override val events: Flow<AgentEvent> = emptyFlow()
                        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                            LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                    }
                },
                ProviderIdCapture(store, idScope),
                now = { 1L },
            )
            val server = KotgentServer(
                sessionManager = manager,
                store = store,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in this test") },
                webUiDir = null,
                port = 0,
            ).start()
            val client = HttpClient(CIO)
            try {
                val port = server.port()
                suspend fun sessionsStatus() = client
                    .get("http://127.0.0.1:$port/sessions") { header(HttpHeaders.Authorization, "Bearer $token") }
                    .status

                assertEquals(HttpStatusCode.OK, sessionsStatus(), "the server serves before the signal")

                // The daemon's order: start the server first, take the signals back second.
                installShutdownSignals()
                raise(SIGINT)

                assertEquals(SIGINT, pendingShutdownSignal(), "SIGINT must reach OUR handler")
                // Ktor's stop() is asynchronous, so give it time to close the listener if it ever ran.
                delay(SETTLE_MILLIS)
                assertEquals(
                    HttpStatusCode.OK,
                    sessionsStatus(),
                    "the engine must still be up — a closed listener here means Ktor's hook won the race",
                )
            } finally {
                restoreDefaultShutdownSignals()
                client.close()
                server.stop()
                idScope.cancel()
            }
        }
    }

    private companion object {
        /** Long enough for a Ktor `stop()` triggered by the signal to have closed the listener. */
        const val SETTLE_MILLIS: Long = 500
    }
}
