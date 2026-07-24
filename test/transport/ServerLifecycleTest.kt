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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-socket lifecycle regressions for [KotgentServer].
 *
 * These use a live CIO client because a server-side close of an accepted connection is what leaves the
 * old local endpoint in TCP teardown state on macOS. The daemon must still be able to bind the same
 * well-known port immediately after a graceful stop.
 */
class ServerLifecycleTest {

    @Test
    fun stoppedServerCanImmediatelyRebindItsPortAfterServingAClient() = runBlocking {
        withTimeout(40_000) {
            val fixture = Fixture()
            val firstClient = HttpClient(CIO)
            val secondClient = HttpClient(CIO)
            var first: KotgentServer? = null
            var second: KotgentServer? = null
            try {
                first = fixture.server(port = 0).start()
                val port = first.port()
                assertEquals(HttpStatusCode.NotFound, firstClient.get("http://127.0.0.1:$port/").status)

                // Keep firstClient alive: stopping the server makes the server endpoint initiate the
                // connection close, reproducing the TIME_WAIT-shaped immediate-restart failure.
                first.stop()
                first = null

                second = fixture.server(port).start()
                assertEquals(HttpStatusCode.NotFound, secondClient.get("http://127.0.0.1:$port/").status)
            } finally {
                second?.stop()
                first?.stop()
                secondClient.close()
                firstClient.close()
                fixture.close()
            }
        }
    }

    @Test
    fun occupiedPortIsReportedAsServerBindException() = runBlocking {
        withTimeout(40_000) {
            val fixture = Fixture()
            var owner: KotgentServer? = null
            var contender: KotgentServer? = null
            try {
                owner = fixture.server(port = 0).start()
                val port = owner.port()
                contender = fixture.server(port)

                val failure = assertFailsWith<ServerBindException> {
                    contender.start()
                }
                assertTrue(failure.message.orEmpty().contains("EADDRINUSE"), failure.message)
            } finally {
                runCatching { contender?.stop() }
                owner?.stop()
                fixture.close()
            }
            Unit
        }
    }

    private class Fixture {
        private val store = SqliteEventStore.inMemory()
        private val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val manager = SessionManager(
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

        fun server(port: Int): KotgentServer = KotgentServer(
            sessionManager = manager,
            store = store,
            tokens = TokenHolder("server-lifecycle-test-token"),
            terminalBridgeFactory = { _, _ -> error("terminal bridge is not used in this test") },
            webUiDir = null,
            port = port,
        )

        fun close() {
            idScope.cancel()
        }
    }
}
