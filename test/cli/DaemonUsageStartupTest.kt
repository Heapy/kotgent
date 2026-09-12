package io.kotgent.cli

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.Notification
import io.kotgent.core.UsageObservation
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageResetNotification
import io.kotgent.core.UsageSource
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.db.KotgentDatabase
import io.kotgent.push.FakePushStore
import io.kotgent.push.UsageResetNotifier
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePreferencesStore
import io.kotgent.store.NotificationStore
import io.kotgent.store.SqliteNotificationStore
import io.kotgent.store.SqliteUsageStore
import io.kotgent.store.UsageStore
import io.kotgent.tmux.Tmux
import io.kotgent.transport.KotgentServer
import io.kotgent.transport.ServerBindException
import io.kotgent.transport.TRANSPORT_JSON
import io.kotgent.transport.TokenHolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class DaemonUsageStartupTest {
    @Test
    fun recoveredResetIsReadableOverHttpBeforeItsWakeRuns() = test { f ->
        val reset = f.seedReset()
        val created = CompletableDeferred<KotgentServer>()
        val projected = CompletableDeferred<Unit>()
        val usage = object : UsageStore by f.usage {
            override suspend fun markResetNotificationProjected(id: Long) {
                f.usage.markResetNotificationProjected(id)
                projected.complete(Unit)
            }
        }
        val delivered = Channel<Pair<String, Result<List<Notification>>>>(Channel.UNLIMITED)
        val runtime = startDaemonServer(
            assemblePush = { f.push() },
            startUsage = {
                UsageResetNotifier(usage, f.inbox, wake = { key ->
                    val notices = runCatching { f.notifications(created.await().port()) }
                    delivered.send(key to notices)
                }, now = { f.now }).start(f.scope)
            },
            createServer = {
                assertTrue(projected.isCompleted, "projection completes before the server factory runs")
                assertTrue(delivered.tryReceive().isFailure, "a recovered wake cannot precede binding")
                f.server().also { created.complete(it) }
            },
        )
        try {
            val delivery = delivered.receive()
            val notices = delivery.second.getOrThrow()
            assertEquals("usage.reset:${reset.id}", delivery.first)
            val notice = assertIs<UsageResetNotification>(notices.single())
            assertEquals(reset.usedBefore, notice.usedBefore)
            assertEquals(reset.usedBeforeSeenAt, notice.usedBeforeSeenAt)
            assertEquals(reset.expectedAt, notice.expectedAt)
        } finally {
            delivered.close()
            runtime.push?.close?.invoke()
        }
    }

    @Test
    fun startupWithoutPushStillProjectsAndServesTheRecoveredInbox() = test { f ->
        val reset = f.seedReset()
        val runtime = startDaemonServer(
            assemblePush = { null },
            startUsage = { push ->
                assertEquals(null, push)
                UsageResetNotifier(f.usage, f.inbox, now = { f.now }).start(f.scope)
            },
            createServer = { f.server() },
        )
        assertEquals(null, runtime.push)
        assertTrue(f.usage.pendingResetNotifications(0).isEmpty())
        assertEquals(listOf("usage.reset:${reset.id}"), f.notifications(runtime.server.port()).map { it.id })
    }

    @Test
    fun failedBindJoinsUsageCleanupBeforeClosingPushAndNeverActivatesItsWake() = test { f ->
        val blocker = withContext(Dispatchers.Default) { f.server().start() }
        val occupiedPort = blocker.port()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val pushClosed = CompletableDeferred<Unit>()
        val deliveryGate = CompletableDeferred<Unit>()
        val startup = async {
            runCatching {
                startDaemonServer(
                    assemblePush = { f.push { pushClosed.complete(Unit) } },
                    startUsage = {
                        // Observe activation itself, independent of whether a wake coroutine gets scheduled.
                        val job = Job(f.scope.coroutineContext[Job])
                        CoroutineScope(job).launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cleanupEntered.complete(Unit)
                                    releaseCleanup.await()
                                }
                            }
                        }
                        UsageResetNotifier.Running(job, deliveryGate)
                    },
                    createServer = { f.server(occupiedPort) },
                )
            }
        }
        try {
            cleanupEntered.await()
            assertFalse(pushClosed.isCompleted, "push stays open while the usage watcher is still cleaning up")
            assertFalse(startup.isCompleted)
            assertFalse(deliveryGate.isCompleted, "failed binding never opens the delivery gate")
            releaseCleanup.complete(Unit)
            assertIs<ServerBindException>(startup.await().exceptionOrNull())
            assertTrue(pushClosed.isCompleted)
            assertFalse(deliveryGate.isCompleted)
        } finally {
            releaseCleanup.complete(Unit)
            startup.cancelAndJoin()
        }
    }

    @Test
    fun exhaustedStartupProjectionClosesPushWithoutCreatingAServer() = test { f ->
        val _ = f.seedReset()
        val failure = IllegalStateException("inbox unavailable")
        val pushClosed = CompletableDeferred<Unit>()
        val retries = mutableListOf<Long>()
        var created = false
        val inbox = object : NotificationStore by f.inbox {
            override suspend fun insert(reset: UsageReset): Boolean = throw failure
        }
        val result = runCatching {
            startDaemonServer(
                assemblePush = { f.push { pushClosed.complete(Unit) } },
                startUsage = {
                    UsageResetNotifier(f.usage, inbox, now = { f.now }, awaitRetry = { retries += it }).start(f.scope)
                },
                createServer = { created = true; f.server() },
            )
        }
        assertTrue(result.exceptionOrNull() === failure)
        assertFalse(created)
        assertTrue(pushClosed.isCompleted)
        assertEquals(listOf(250L, 1_000L), retries)
        assertEquals(1, f.usage.pendingResetNotifications(0).size)
        assertTrue(f.inbox.recent(0).isEmpty())
        assertTrue(f.scope.coroutineContext[Job]!!.children.none(), "startup failure joins the watcher before returning")
    }

    @Test
    fun cancellationDuringInitialProjectionJoinsTheWatcherBeforePushCleanup() = test { f ->
        val _ = f.seedReset()
        val insertEntered = CompletableDeferred<Unit>()
        val insertExited = CompletableDeferred<Unit>()
        val pushClosed = CompletableDeferred<Unit>()
        var created = false
        val inbox = object : NotificationStore by f.inbox {
            override suspend fun insert(reset: UsageReset): Boolean {
                insertEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    insertExited.complete(Unit)
                }
            }
        }
        val startup = launch {
            val _ = startDaemonServer(
                assemblePush = { f.push {
                    assertTrue(insertExited.isCompleted)
                    pushClosed.complete(Unit)
                } },
                startUsage = { UsageResetNotifier(f.usage, inbox, now = { f.now }).start(f.scope) },
                createServer = { created = true; f.server() },
            )
        }
        try {
            insertEntered.await()
            startup.cancelAndJoin()
            assertFalse(created)
            assertTrue(insertExited.isCompleted)
            assertTrue(pushClosed.isCompleted)
            assertEquals(1, f.usage.pendingResetNotifications(0).size)
            assertTrue(f.scope.coroutineContext[Job]!!.children.none())
        } finally {
            startup.cancelAndJoin()
        }
    }

    private class Fixture {
        val now = Clock.System.now().toEpochMilliseconds()
        private val driver = inMemoryDriver(KotgentDatabase.Schema)
        val usage = SqliteUsageStore(driver) { now }
        val inbox = SqliteNotificationStore(driver) { now }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val events = FakeEventStore()
        private val client = HttpClient(CIO)
        private val servers = mutableListOf<KotgentServer>()
        private val manager = SessionManager(
            tmux = FakeTmux(), store = events, registry = PaneRegistry(),
            agentFactory = { _, cwd -> object : AgentAdapter {
                override val events: Flow<AgentEvent> = emptyFlow()
                override fun buildLaunchSpec(mode: LaunchMode) = LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
            } },
            idCapture = ProviderIdCapture(events, scope), vendorProbe = { _, _, _ -> false },
            sessionLocator = { _, _ -> null }, supportedAgentKinds = setOf("claude", "codex"),
        )

        suspend fun seedReset(): UsageReset {
            usage.observe(UsageObservation("claude", "seven_day", 73.5, now + 604_800_000, 604_800,
                source = UsageSource("startup:source", 1, now)))
            usage.observe(UsageObservation("claude", "seven_day", 3.0, now + 604_800_000, 604_800,
                source = UsageSource("startup:source", 2, now + 1)))
            return usage.pendingResetNotifications(0).single()
        }

        fun push(close: suspend () -> Unit = {}) = DaemonPush(FakePushStore(), { "unused" }, close)

        fun server(port: Int = 0): KotgentServer = KotgentServer.production(
            sessionManager = manager, eventStore = events, preferencesStore = FakePreferencesStore(),
            tokens = TokenHolder(TOKEN), tmux = Tmux(socket = "kotgent-usage-startup-test", tmuxPath = "/usr/bin/false"),
            usageStore = usage, notificationStore = inbox, webUiDir = null, port = port,
        ).also { servers += it }

        suspend fun notifications(port: Int): List<Notification> {
            val response = client.get("http://127.0.0.1:$port/api/v1/notifications") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN")
            }
            check(response.status == HttpStatusCode.OK) { "notification endpoint returned ${response.status}" }
            return TRANSPORT_JSON.decodeFromString(ListSerializer(Notification.serializer()), response.bodyAsText())
        }

        suspend fun close() = withContext(NonCancellable) {
            try {
                withContext(Dispatchers.Default) { servers.asReversed().forEach { it.stop() } }
            } finally {
                try {
                    scope.coroutineContext[Job]?.cancelAndJoin()
                } finally {
                    client.close()
                    driver.close()
                }
            }
        }
    }

    private fun test(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        withTimeout(30.seconds) {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }

    private companion object {
        const val TOKEN = "usage-startup-test-token"
    }
}
