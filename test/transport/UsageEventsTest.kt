package io.kotgent.transport

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageWindowState
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePreferencesStore
import io.kotgent.store.SqliteUsageStore
import io.kotgent.store.UsageStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class UsageEventsTest {
    private val epoch = 1_800_000_000_000L

    @Test
    fun usageFramesRoundTripThroughTheSealedSerializerIncludingUnknownOptionalValues() {
        val window = UsageWindowDto("codex", "primary", 12.5, null, null, epoch, epoch - 1, epoch - 2)
        val frames = listOf<EventsFrame>(UsageSnapshotDto(listOf(window), epoch + 123), UsageUpdateDto(window, epoch + 456))
        val types = listOf("usage_snapshot", "usage_update")
        for (index in frames.indices) {
            val encoded = TRANSPORT_JSON.encodeToString(EventsFrame.serializer(), frames[index])
            assertEquals(types[index], TRANSPORT_JSON.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals(frames[index], TRANSPORT_JSON.decodeFromString(EventsFrame.serializer(), encoded))
        }
    }

    @Test
    fun aUsageWindowDtoKeepsReceiptAndChangeTimesSeparateFromItsOrderingRevision() {
        val state = UsageWindowState(
            observation(12.5).copy(observedAt = epoch + 60_000),
            changedAt = epoch,
            receivedAt = epoch - 1,
        )
        assertEquals(
            UsageWindowDto("claude", "seven_day", 12.5, epoch + 3_600_000, 604_800, epoch + 60_000, epoch, epoch - 1),
            state.toDto(),
        )
    }

    @Test
    fun connectionSendsOneSnapshotContainingEveryProviderWindow() = withStore { store ->
        store.observe(observation(40.0))
        store.observe(observation(60.0, provider = "codex", key = "primary"))
        val expected = store.list().map { it.toDto() }
        withSocket(store) {
            val snapshot = assertIs<UsageSnapshotDto>(nextUsageFrame())
            assertEquals(expected, snapshot.windows)
            assertEquals(epoch, snapshot.serverNow)
        }
    }

    @Test
    fun anEmptySnapshotIsFollowedByWholeWindowUpdates() = withStore { store ->
        withSocket(store) {
            assertTrue(assertIs<UsageSnapshotDto>(nextUsageFrame()).windows.isEmpty())
            store.observe(observation(20.0))
            val update = assertIs<UsageUpdateDto>(nextUsageFrame())
            assertEquals(epoch, update.serverNow)
            val first = update.window
            assertEquals(store.list().single().toDto(), first)

            store.observe(observation(21.0))
            val next = assertIs<UsageUpdateDto>(nextUsageFrame()).window
            assertEquals(21.0, next.usedPercent)
            assertEquals(first.provider, next.provider)
            assertEquals(first.windowKey, next.windowKey)
            assertTrue(next.observedAt > first.observedAt)
        }
    }

    @Test
    fun aGatedOldSnapshotDoesNotBlockMoreThanTheStoreBufferAndCannotLoseTheNewestWindows() = withStore { store ->
        store.observe(observation(80.0))
        store.observe(observation(90.0, provider = "codex", key = "primary"))
        val oldSnapshot = store.list().map { it.toDto() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : UsageStore by store {
            override suspend fun list(): List<UsageWindowState> {
                val snapshot = store.list()
                val _ = entered.complete(Unit)
                release.await()
                return snapshot
            }
        }
        withSocket(gated) {
            entered.await()
            val publisher = async {
                repeat(130) { index ->
                    store.observe(observation((index % 101).toDouble()))
                    store.observe(observation((index % 101).toDouble(), provider = "codex", key = "primary"))
                }
            }
            val expected: Map<Pair<String, String>, UsageWindowDto>
            try {
                // Publishing must finish while the snapshot is blocked, not only after this gate opens.
                withTimeout(5.seconds) { publisher.await() }
                expected = store.list().associate { (it.current.provider to it.current.windowKey) to it.toDto() }
            } finally {
                // Even a regressed non-cancellable publisher can finish and let the test tear down.
                val _ = release.complete(Unit)
            }
            assertEquals(oldSnapshot, assertIs<UsageSnapshotDto>(nextUsageFrame()).windows)
            val received = oldSnapshot.associate { (it.provider to it.windowKey) to it }.toMutableMap()
            while (received != expected) {
                val window = assertIs<UsageUpdateDto>(nextUsageFrame()).window
                val key = window.provider to window.windowKey
                assertTrue(window.observedAt > (received[key]?.observedAt ?: -1L), "usage frames never go backwards")
                received[key] = window
            }
            assertEquals(setOf("claude", "codex"), received.values.map { it.provider }.toSet())
            assertTrue(received.values.all { it.usedPercent == 28.0 })
        }
    }

    @Test
    fun aSurvivingHintRefreshesAWindowWhoseLastUpdateWasDroppedDuringAnotherWindowsBurst() = withStore { store ->
        store.observe(observation(10.0))
        store.observe(observation(0.0, provider = "codex", key = "primary"))
        val forwarded = MutableSharedFlow<UsageWindowState>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val port = object : UsageStore by store { override val updates = forwarded }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withSocket(port) {
            val initial = assertIs<UsageSnapshotDto>(nextUsageFrame())
            val received = initial.windows.associateBy { it.provider }.toMutableMap()
            var first = true
            val relay = launch(start = CoroutineStart.UNDISPATCHED) {
                store.updates.collect { state ->
                    if (first) {
                        first = false
                        entered.complete(Unit)
                        release.await()
                    }
                    val _ = forwarded.tryEmit(state)
                }
            }
            try {
                store.observe(observation(1.0, provider = "codex", key = "primary"))
                entered.await()
                store.observe(observation(20.0))
                repeat(80) { index -> store.observe(observation(2.0 + index, provider = "codex", key = "primary")) }
                release.complete(Unit)
                while (received["claude"]?.usedPercent != 20.0 || received["codex"]?.usedPercent != 81.0) {
                    val update = assertIs<UsageUpdateDto>(nextUsageFrame()).window
                    received[update.provider] = update
                }
            } finally {
                release.complete(Unit)
                relay.cancelAndJoin()
            }
        }
    }

    private fun observation(percent: Double, provider: String = "claude", key: String = "seven_day") =
        UsageObservation(provider, key, percent, epoch + 3_600_000, 604_800)

    private fun withStore(block: suspend (SqliteUsageStore) -> Unit) = runBlocking {
        withTimeout(30.seconds) {
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            try {
                block(SqliteUsageStore(driver) { epoch })
            } finally {
                driver.close()
            }
        }
    }

    private suspend fun withSocket(usage: UsageStore, block: suspend DefaultClientWebSocketSession.() -> Unit) {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            install(ServerWebSockets)
            routing { eventsWs(FakeEventStore(), FakePreferencesStore(), usageStore = usage, usageClock = { epoch }) }
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

    private suspend fun DefaultClientWebSocketSession.nextUsageFrame(): EventsFrame {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val parsed = TRANSPORT_JSON.decodeFromString(EventsFrame.serializer(), frame.readText())
            if (parsed is UsageSnapshotDto || parsed is UsageUpdateDto) return parsed
        }
    }
}
