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
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
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
                VendorStoreProbe { _, _, _ -> false },
                VendorSessionLocator { _, _ -> null },
                setOf("claude", "codex"),
                now = { 1L },
            )
            val server = KotgentServer(
                sessionManager = manager,
                store = store,
                preferencesStore = store,
                tokens = TokenHolder(token),
                terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in this test") },
                webUiDir = null,
                port = 0,
            ).start()
            val client = HttpClient(CIO)
            try {
                val port = server.port()
                suspend fun sessionsStatus() = client
                    .get("http://127.0.0.1:$port$API_PREFIX/sessions") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    .status

                assertEquals(HttpStatusCode.OK, sessionsStatus(), "the server serves before the signal")

                installShutdownSignals()
                raise(SIGINT)

                assertEquals(SIGINT, pendingShutdownSignal(), "SIGINT must reach OUR handler")
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
        const val SETTLE_MILLIS: Long = 500
    }
}
