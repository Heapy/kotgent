package io.kotgent.transport

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.PaneId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.db.KotgentDatabase
import io.kotgent.mutex.MutexKey
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakeMutexStore
import io.kotgent.store.FakePreferencesStore
import io.kotgent.store.MutexAcquireResult
import io.kotgent.store.SqliteMutexStore
import io.kotgent.tmux.Tmux
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

class MutexRoutesTest {

    @Test
    fun everyMutexRouteRequiresTheMasterToken() = withServer { f ->
        assertEquals(HttpStatusCode.Unauthorized, f.client.get(f.url("/mutexes")).status)
        assertEquals(HttpStatusCode.Unauthorized, f.client.post(f.url("/mutexes/kotlin-build/acquire?wait=0")).status)
        assertEquals(HttpStatusCode.Unauthorized, f.client.post(f.url("/mutexes/release")).status)
        assertEquals(HttpStatusCode.Unauthorized, f.client.post(f.url("/mutexes/kotlin-build/force-release")).status)
    }

    @Test
    fun anOversizedBodyIsRefusedBeforeItIsRead() = withServer { f ->
        val padding = "x".repeat(MUTEX_BODY_MAX_BYTES)
        val release = f.post("/mutexes/release", pane = ALICE_PANE, body = """{"token":"t","pad":"$padding"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, release.status)
        val acquire = f.post("/mutexes/kotlin-build/acquire?wait=0", pane = ALICE_PANE, body = """{"pad":"$padding"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, acquire.status)
    }

    @Test
    fun onlyALiveKotgentSessionMayAcquire() = withServer { f ->
        val outside = f.post("/mutexes/kotlin-build/acquire?wait=0")
        assertEquals(HttpStatusCode.BadRequest, outside.status)
        assertTrue(outside.bodyAsText().contains("no calling session"))
        assertEquals(HttpStatusCode.BadRequest, f.post("/mutexes/kotlin-build/acquire?wait=0", pane = "%77").status)
        assertEquals(HttpStatusCode.BadRequest, f.post("/mutexes/kotlin-build/acquire?wait=0", pane = "bogus").status)
        val stopped = f.post("/mutexes/kotlin-build/acquire?wait=0", body = """{"sessionId":"stopped"}""")
        assertEquals(HttpStatusCode.BadRequest, stopped.status)
        assertTrue(stopped.bodyAsText().contains("not running"))
        assertTrue(f.mutexes.listing.value.entries.isEmpty())

        val explicit = f.acquire(body = """{"sessionId":"bob"}""")
        assertEquals("acquired", explicit.status)
        assertEquals(SessionId("bob"), f.mutexes.listing.value.entries.single().holding?.sessionId)
    }

    @Test
    fun malformedKeysTicketsAndWaitsAreRejected() = withServer { f ->
        assertEquals(HttpStatusCode.BadRequest, f.post("/mutexes/-bad/acquire?wait=0", pane = ALICE_PANE).status)
        assertEquals(HttpStatusCode.BadRequest, f.post("/mutexes/k/acquire?wait=541", pane = ALICE_PANE).status)
        assertEquals(HttpStatusCode.BadRequest, f.post("/mutexes/k/acquire?wait=-1", pane = ALICE_PANE).status)
        val badTicket = f.post("/mutexes/k/acquire?wait=0&ticket=a/b", pane = ALICE_PANE)
        assertEquals(HttpStatusCode.BadRequest, badTicket.status)
        assertEquals(HttpStatusCode.BadRequest, f.post("/mutexes/release", pane = ALICE_PANE, body = "{}").status)
    }

    @Test
    fun aPendingWaiterContinuesWithItsTicketAndAClaimAfterTheLeaseIsRefused() = withServer { f ->
        val held = f.acquire(pane = ALICE_PANE)
        assertEquals("acquired", held.status)
        val token = assertNotNull(held.token)

        val pending = f.acquire(pane = BOB_PANE)
        assertEquals("pending", pending.status)
        assertEquals(1, pending.position)
        val ticket = assertNotNull(pending.ticket)
        assertEquals("pending", f.acquire(pane = BOB_PANE, ticket = ticket).status)

        val foreign = f.post("/mutexes/kotlin-build/acquire?wait=0&ticket=$ticket", pane = ALICE_PANE)
        assertEquals(HttpStatusCode.Conflict, foreign.status)
        val stolen = f.post("/mutexes/release", pane = BOB_PANE, body = """{"token":"$token"}""")
        assertEquals(HttpStatusCode.Conflict, stolen.status)

        val released = f.post("/mutexes/release", pane = ALICE_PANE, body = """{"token":"$token"}""")
        assertEquals(HttpStatusCode.OK, released.status)
        assertEquals(MutexReleaseResponse(true, "kotlin-build"), f.decode(MutexReleaseResponse.serializer(), released))
        val again = f.post("/mutexes/release", pane = ALICE_PANE, body = """{"token":"$token"}""")
        assertEquals(MutexReleaseResponse(false), f.decode(MutexReleaseResponse.serializer(), again))

        val claimed = f.acquire(pane = BOB_PANE, ticket = ticket)
        assertEquals("acquired", claimed.status)

        val late = f.acquire(pane = ALICE_PANE)
        val lateTicket = assertNotNull(late.ticket)
        f.clock += LEASE_MILLIS + 1
        f.mutexes.expire()
        val refused = f.post("/mutexes/kotlin-build/acquire?wait=0&ticket=$lateTicket", pane = ALICE_PANE)
        assertEquals(HttpStatusCode.Gone, refused.status)
    }

    @Test
    fun anOpenLongPollIsAnsweredByTheGrant() = withServer { f ->
        val token = assertNotNull(f.acquire(pane = ALICE_PANE).token)
        val waiting = coroutineScope {
            val poll = async { f.acquire(pane = BOB_PANE, wait = 20) }
            val _ = f.mutexes.listing.first { listing -> listing.entries.any { it.waiters.isNotEmpty() } }
            val _ = f.post("/mutexes/release", pane = ALICE_PANE, body = """{"token":"$token"}""")
            poll.await()
        }
        assertEquals("acquired", waiting.status)
    }

    @Test
    fun aDisconnectedLongPollStartsTheLeaseCountdown() = withServer { f ->
        val _ = f.acquire(pane = ALICE_PANE)
        val impatient = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 300 } }
        try {
            val _ = assertFailsWith<Throwable> {
                impatient.post(f.url("/mutexes/kotlin-build/acquire?wait=30")) {
                    header(HttpHeaders.Authorization, "Bearer $TOKEN")
                    header(TASK_PANE_HEADER, BOB_PANE)
                }
            }
        } finally {
            impatient.close()
        }
        // Only a closed poll lets its lease lapse; an open one would outlive every clock advance.
        withTimeout(10.seconds) {
            while (f.mutexes.listing.value.entries.single().waiters.isNotEmpty()) {
                f.clock += LEASE_MILLIS + 1
                f.mutexes.expire()
                delay(20.milliseconds)
            }
        }
    }

    @Test
    fun forceReleaseIsTheOperatorsAndPassesTheMutexOn() = withServer { f ->
        val _ = f.acquire(pane = ALICE_PANE)
        val pending = f.acquire(pane = BOB_PANE)
        assertEquals(HttpStatusCode.Forbidden, f.post("/mutexes/kotlin-build/force-release", pane = BOB_PANE).status)
        val forced = f.post("/mutexes/kotlin-build/force-release")
        assertEquals(HttpStatusCode.OK, forced.status)
        assertEquals(HttpStatusCode.NotFound, f.post("/mutexes/other/force-release").status)
        assertEquals("acquired", f.acquire(pane = BOB_PANE, ticket = pending.ticket).status)
    }

    @Test
    fun theListingNamesHoldersAndWaitersButNeverTheirSecrets() = withServer { f ->
        val held = f.acquire(pane = ALICE_PANE)
        val pending = f.acquire(pane = BOB_PANE)
        val response = f.client.get(f.url("/mutexes")) { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertFalse(text.contains(assertNotNull(held.token)))
        assertFalse(text.contains(assertNotNull(pending.ticket)))
        val listing = TRANSPORT_JSON.decodeFromString(MutexListingDto.serializer(), text)
        val mutex = listing.mutexes.single()
        assertEquals("kotlin-build", mutex.key)
        assertEquals(MutexHolderDto("alice", f.clock), mutex.holder)
        assertEquals(listOf(MutexWaiterDto("bob", f.clock, granted = false)), mutex.waiters)
        assertEquals(f.clock, listing.serverNow)
    }

    @Test
    fun theEventsSocketSendsASnapshotAfterSubscribingAndThenUpdates() = runBlocking {
        withTimeout(30.seconds) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val workers = Job()
            try {
                val store = SqliteMutexStore(driver, CoroutineScope(Dispatchers.Default + workers), now = { 7L })
                val key = MutexKey("kotlin-build")
                val first = store.acquire(key, SessionId("alice"), null, Duration.ZERO)
                val _ = assertIs<MutexAcquireResult.Acquired>(first)
                withSocket(store) {
                    val snapshot = assertIs<MutexesSnapshotDto>(nextMutexFrame())
                    assertEquals("alice", snapshot.mutexes.single().holder?.sessionId)
                    val _ = store.acquire(key, SessionId("bob"), null, Duration.ZERO)
                    val update = assertIs<MutexUpdateDto>(nextMutexFrame())
                    assertTrue(update.rev > snapshot.rev)
                    assertEquals(listOf("bob"), update.mutexes.single().waiters.map { it.sessionId })
                }
            } finally {
                workers.cancel()
                driver.close()
            }
        }
    }

    private suspend fun withSocket(store: SqliteMutexStore, block: suspend DefaultClientWebSocketSession.() -> Unit) {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing { eventsWs(FakeEventStore(), FakePreferencesStore(), mutexStore = store, usageClock = { 7L }) }
        }
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        try {
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port
            client.webSocket("ws://127.0.0.1:$port/events", block = block)
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    private suspend fun DefaultClientWebSocketSession.nextMutexFrame(): EventsFrame {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val parsed = TRANSPORT_JSON.decodeFromString(EventsFrame.serializer(), frame.readText())
            if (parsed is MutexesSnapshotDto || parsed is MutexUpdateDto) return parsed
        }
    }

    private class Fixture(
        val mutexes: FakeMutexStore,
        val client: HttpClient,
        private val port: Int,
        private val clockCell: LongArray,
    ) {
        var clock: Long
            get() = clockCell[0]
            set(value) {
                clockCell[0] = value
            }

        fun url(path: String) = "http://127.0.0.1:$port/api/v1$path"

        suspend fun post(path: String, pane: String? = null, body: String? = null): HttpResponse =
            client.post(url(path)) {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
                if (pane != null) header(TASK_PANE_HEADER, pane)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

        suspend fun acquire(
            pane: String? = null,
            ticket: String? = null,
            wait: Int = 0,
            body: String? = null,
        ): MutexAcquireResponse {
            val query = "?wait=$wait" + if (ticket == null) "" else "&ticket=$ticket"
            val response = post("/mutexes/kotlin-build/acquire$query", pane, body)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return decode(MutexAcquireResponse.serializer(), response)
        }

        suspend fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, response: HttpResponse): T =
            TRANSPORT_JSON.decodeFromString(serializer, response.bodyAsText())
    }

    private fun withServer(block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(30.seconds) {
            val clock = longArrayOf(1_800_000_000_000L)
            val events = FakeEventStore(now = { clock[0] })
            val fixtureSessions = listOf(
                "alice" to SessionState.running,
                "bob" to SessionState.running,
                "stopped" to SessionState.stopped,
            )
            for (fixture in fixtureSessions) {
                val [id, state] = fixture
                events.upsertSession(
                    SessionMeta(
                        id = SessionId(id), name = id, agent = "claude", cwd = "/fixture", tmuxSession = "kt-$id",
                        state = state, createdAt = 1L, updatedAt = 1L,
                    ),
                )
            }
            val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val mutexes = FakeMutexStore(background, now = { clock[0] })
            val client = HttpClient(CIO)
            var server: KotgentServer? = null
            try {
                val registry = PaneRegistry()
                registry.register(PaneId(ALICE_PANE), SessionId("alice"))
                registry.register(PaneId(BOB_PANE), SessionId("bob"))
                val manager = SessionManager(
                    tmux = FakeTmux(), store = events, registry = registry,
                    agentFactory = { _, cwd ->
                        object : AgentAdapter {
                            override val events: Flow<AgentEvent> = emptyFlow()
                            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                                LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                        }
                    },
                    idCapture = ProviderIdCapture(events, background), vendorProbe = { _, _, _ -> false },
                    sessionLocator = { _, _ -> null }, supportedAgentKinds = setOf("claude"),
                )
                val started = withContext(Dispatchers.Default) {
                    KotgentServer.production(
                        sessionManager = manager, eventStore = events, preferencesStore = FakePreferencesStore(),
                        tokens = TokenHolder(TOKEN),
                        tmux = Tmux(socket = "kotgent-mutex-routes-test", tmuxPath = "/usr/bin/false"),
                        webUiDir = null, port = 0, mutexStore = mutexes, usageClock = { clock[0] },
                    ).start()
                }
                server = started
                block(Fixture(mutexes, client, started.port(), clock))
            } finally {
                client.close()
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.Default) { server?.stop() }
                    } finally {
                        background.coroutineContext[Job]?.cancelAndJoin()
                    }
                }
            }
        }
    }

    private companion object {
        const val TOKEN = "mutex-route-test-token"
        const val ALICE_PANE = "%1"
        const val BOB_PANE = "%2"
        const val LEASE_MILLIS = 30_000L
    }
}
