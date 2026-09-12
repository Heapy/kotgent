package io.kotgent.transport

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.NOTIFICATION_WINDOW_MILLIS
import io.kotgent.core.Notification
import io.kotgent.core.Seq
import io.kotgent.core.SessionAttentionNotification
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.core.UsageReset
import io.kotgent.core.UsageResetNotification
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.db.KotgentDatabase
import io.kotgent.store.FakeEventStore
import io.kotgent.store.FakePreferencesStore
import io.kotgent.store.SqliteNotificationStore
import io.kotgent.tmux.Tmux
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class NotificationRoutesTest {
    @Test
    fun theProductionServerMergesBothKindsByTimeAndPreservesTheirSerializedDiscriminators() = withServer { f ->
        val firstReset = f.reset(11, f.now - 4_000)
        val approval = f.session("approval", updatedAt = f.now - 3_000, name = "Review the change")
        val secondReset = f.reset(12, f.now - 2_000, provider = "codex", key = "primary")
        val answer = f.session("answer", updatedAt = f.now - 1_000, state = SessionState.needs_answer)

        val response = f.request()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        val body = response.bodyAsText()
        val notifications = TRANSPORT_JSON.decodeFromString(ListSerializer(Notification.serializer()), body)

        assertEquals(
            listOf("session.attention:answer", "usage.reset:12", "session.attention:approval", "usage.reset:11"),
            notifications.map { it.id },
        )
        assertEquals(listOf(answer.updatedAt, secondReset.observedAt, approval.updatedAt, firstReset.observedAt),
            notifications.map { it.createdAt })
        val attention = assertIs<SessionAttentionNotification>(notifications[2])
        assertEquals(approval.id.value, attention.sessionId)
        assertEquals(approval.name, attention.sessionName)
        val reset = assertIs<UsageResetNotification>(notifications[1])
        assertEquals("codex", reset.provider)
        assertEquals("primary", reset.windowKey)
        assertEquals(WEEK_SECONDS, reset.windowSeconds)
        assertEquals(secondReset.expectedAt, reset.expectedAt)
        assertEquals(secondReset.usedBefore, reset.usedBefore)
        assertEquals(secondReset.usedBeforeSeenAt, reset.usedBeforeSeenAt)

        val encoded = TRANSPORT_JSON.parseToJsonElement(body)
        assertEquals(
            listOf("session.attention", "usage.reset", "session.attention", "usage.reset"),
            encoded.jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        assertEquals(encoded, TRANSPORT_JSON.parseToJsonElement(
            TRANSPORT_JSON.encodeToString(ListSerializer(Notification.serializer()), notifications),
        ))
    }

    @Test
    fun readingNeverConsumesNotificationsAndAttentionResolvesWhenTheSessionStopsWaitingOrIsArchived() = withServer { f ->
        val approval = f.session("approval")
        val answer = f.session("answer", state = SessionState.needs_answer)
        val _ = f.reset(21, f.now - 500)
        val initial = f.notifications()
        assertEquals(initial, f.notifications(), "another device can read the same inbox")

        f.events.markRead(approval.id, approval.lastSeq)
        assertEquals(initial, f.notifications(), "reading session events does not resolve its waiting state")

        f.events.updateSessionState(approval.id, SessionState.running, EventSource.hook, null, f.now)
        assertEquals(setOf("session.attention:answer", "usage.reset:21"), f.notifications().map { it.id }.toSet())

        f.events.setArchived(answer.id, true, f.now)
        assertEquals(listOf("usage.reset:21"), f.notifications().map { it.id })
        assertEquals(listOf("usage.reset:21"), f.notifications().map { it.id }, "resolved attention leaves the durable reset intact")
    }

    @Test
    fun onlyDurableResetsExpireFromTheReadWindowWhileOldActiveAttentionRemains() = withServer { f ->
        val old = f.now - NOTIFICATION_WINDOW_MILLIS - 60_000
        val waiting = f.session("old-waiting", updatedAt = old)
        val _ = f.session("archived", archived = true)
        val _ = f.session("ready", state = SessionState.ready)
        val _ = f.session("crashed", state = SessionState.crashed)
        val _ = f.reset(31, old)
        val _ = f.reset(32, f.now - 1_000)

        val notifications = f.notifications()

        assertEquals(listOf("usage.reset:32", "session.attention:old-waiting"), notifications.map { it.id })
        assertEquals(waiting.updatedAt, notifications.last().createdAt)
        assertTrue(f.inbox.recent(0).any { it.id == "usage.reset:31" }, "the GET filters the old row without deleting its journal")
    }

    @Test
    fun attentionIsStillServedWithoutAnInboxAndClearedNamesKeepTheirDisplayFallbacks() = withServer(inboxEnabled = false) { f ->
        val session = f.session("waiting", name = "")
        val unnamed = f.session("unnamed", updatedAt = f.now - 2_000, name = "", tmuxSession = "")
        val _ = f.reset(41, f.now - 500)

        assertEquals(
            listOf<Notification>(
                SessionAttentionNotification("session.attention:waiting", session.updatedAt, session.id.value, session.tmuxSession),
                SessionAttentionNotification("session.attention:unnamed", unnamed.updatedAt, unnamed.id.value, unnamed.id.value),
            ),
            f.notifications(),
            "the route is mounted even when the optional inbox is unavailable",
        )
    }

    @Test
    fun theProductionNotificationsEndpointRequiresAuthenticationAndAcceptsTheBrowserCookie() = withServer { f ->
        val _ = f.session("waiting")
        val _ = f.reset(51, f.now - 500)

        assertEquals(HttpStatusCode.Unauthorized, f.request(bearer = null).status)
        assertEquals(HttpStatusCode.Unauthorized, f.request(bearer = "wrong-token").status)
        val cookie = issueSessionCookie(TOKEN, f.now)
        val response = f.request(bearer = null, cookie = cookie)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(f.notifications(), TRANSPORT_JSON.decodeFromString(
            ListSerializer(Notification.serializer()), response.bodyAsText(),
        ))
    }

    private class Fixture(
        val now: Long,
        val events: FakeEventStore,
        val inbox: SqliteNotificationStore,
        private val client: HttpClient,
        private val port: Int,
    ) {
        suspend fun request(bearer: String? = TOKEN, cookie: String? = null): HttpResponse =
            client.get("http://127.0.0.1:$port/api/v1/notifications") {
                if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                if (cookie != null) header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
            }

        suspend fun notifications(): List<Notification> {
            val response = request()
            assertEquals(HttpStatusCode.OK, response.status)
            return TRANSPORT_JSON.decodeFromString(ListSerializer(Notification.serializer()), response.bodyAsText())
        }

        suspend fun session(
            id: String,
            updatedAt: Long = now - 1_000,
            state: SessionState = SessionState.needs_approval,
            archived: Boolean = false,
            name: String = id,
            tmuxSession: String = "fixture-$id",
        ): SessionMeta = SessionMeta(
            id = SessionId(id), name = name, agent = "claude", cwd = "/fixture", tmuxSession = tmuxSession,
            state = state, lastSeq = Seq(3), readCursor = Seq(0),
            createdAt = updatedAt - 60_000, updatedAt = updatedAt, archived = archived,
        ).also { events.upsertSession(it) }

        suspend fun reset(id: Long, at: Long, provider: String = "claude", key: String = "seven_day"): UsageReset =
            UsageReset(
                provider = provider, windowKey = key, expectedAt = at + 60_000, observedAt = at,
                usedBefore = 67.5, usedBeforeSeenAt = at - 1_000, early = true,
                resetsAtMoved = provider == "codex", windowSeconds = WEEK_SECONDS, id = id,
            ).also { assertTrue(inbox.insert(it)) }
    }

    private fun withServer(inboxEnabled: Boolean = true, block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(30.seconds) {
            val now = Clock.System.now().toEpochMilliseconds()
            val driver = inMemoryDriver(KotgentDatabase.Schema)
            val events = FakeEventStore(now = { now })
            val inbox = SqliteNotificationStore(driver) { now }
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = HttpClient(CIO)
            var server: KotgentServer? = null
            try {
                val manager = SessionManager(
                    tmux = FakeTmux(), store = events, registry = PaneRegistry(),
                    agentFactory = { _, cwd ->
                        object : AgentAdapter {
                            override val events: Flow<AgentEvent> = emptyFlow()
                            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec =
                                LaunchSpec(listOf("cat"), emptyMap(), cwd, null)
                        }
                    },
                    idCapture = ProviderIdCapture(events, idScope), vendorProbe = { _, _, _ -> false },
                    sessionLocator = { _, _ -> null }, supportedAgentKinds = setOf("claude", "codex"),
                )
                val started = withContext(Dispatchers.Default) {
                    KotgentServer.production(
                        sessionManager = manager, eventStore = events, preferencesStore = FakePreferencesStore(),
                        tokens = TokenHolder(TOKEN),
                        tmux = Tmux(socket = "kotgent-notifications-test", tmuxPath = "/usr/bin/false"),
                        webUiDir = null, port = 0, notificationStore = inbox.takeIf { inboxEnabled },
                    ).start()
                }
                server = started
                block(Fixture(now, events, inbox, client, started.port()))
            } finally {
                client.close()
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.Default) { server?.stop() }
                    } finally {
                        idScope.coroutineContext[Job]?.cancelAndJoin()
                        driver.close()
                    }
                }
            }
        }
    }

    private companion object {
        const val TOKEN = "notification-route-test-token"
        const val WEEK_SECONDS = 604_800L
    }
}
