package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route tests for the Claude hook ingress ([claudeHookRoutes], plan Task 12). Each stands up an
 * embedded Ktor CIO server with the route wired to a fake pane lookup + a recording in-memory
 * [EventStore], drives it with a Ktor CIO client, and asserts behaviour via the store and the HTTP
 * status. Everything is wrapped in a bounded [withTimeout] so a broken round-trip fails fast.
 *
 * These are NOT @Ignore'd: Task 3 proved the Ktor CIO server + client run for real in the macosArm64
 * test binary, so the `401` / `404` / append paths are exercised end-to-end now, without waiting on the
 * Task 14 transport harness.
 */
class HookRoutesTest {

    private val token = "hook-secret-token-abc123"
    private val pane = PaneId("%1")
    private val session = SessionId("kt-abc123")
    private val seededPanes: Map<PaneId, SessionId> = mapOf(pane to session)

    /**
     * Boots the ingress on an ephemeral port with [store] + [paneLookup], hands the caller its bound
     * port and a CIO client, and guarantees teardown of both — all under a single [withTimeout].
     */
    private fun withIngress(
        store: EventStore,
        paneLookup: suspend (PaneId) -> SessionId? = { seededPanes[it] },
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            // grace = 0: these tests do not exercise the launch/register race, so an unknown pane must
            // 404 immediately rather than waiting out the production grace window.
            routing { claudeHookRoutes(token, paneLookup, store, paneLookupGraceMillis = 0) }
        }
        try {
            withTimeout(20_000) {
                server.start(wait = false)
                val port = server.engine.resolvedConnectors().first().port
                val client = HttpClient(CIO)
                try {
                    block(port, client)
                } finally {
                    client.close()
                }
            }
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        }
    }

    private fun url(port: Int, event: String): String =
        "http://127.0.0.1:$port${ClaudeHookConfig.INGRESS_PATH}?event=$event"

    private suspend fun HttpClient.postHook(
        port: Int,
        event: String,
        token: String? = this@HookRoutesTest.token,
        pane: String? = this@HookRoutesTest.pane.value,
        body: String = "{}",
    ): HttpResponse = post(url(port, event)) {
        if (token != null) header(ClaudeHookConfig.HOOK_TOKEN_HEADER, token)
        if (pane != null) header(ClaudeHookConfig.TMUX_PANE_HEADER, pane)
        setBody(body)
    }

    // ---- happy path: a valid POST appends the normalized event ----

    @Test
    fun aValidPostAppendsTheNormalizedEventToTheStore() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(port, ClaudeHookConfig.STOP)
            assertEquals(HttpStatusCode.OK, response.status)

            val appended = store.appended.receive()
            assertEquals(session, appended.sessionId, "appended under the pane-resolved session")
            assertEquals(AgentEvent.TurnCompleted, appended.event, "Stop → TurnCompleted")
            assertEquals(EventSource.hook, appended.source, "source is the hook ingress")
        }
    }

    @Test
    fun postToolUseAppendsAToolCallCarryingTheToolName() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(
                port,
                ClaudeHookConfig.POST_TOOL_USE,
                body = """{"tool_name":"Bash"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)

            val appended = store.appended.receive()
            assertEquals(AgentEvent.ToolCall("Bash"), appended.event)
            assertEquals(EventSource.hook, appended.source)
        }
    }

    @Test
    fun sessionStartAppendsSessionBoundWithTheProviderId() {
        val store = RecordingEventStore()
        val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        withIngress(store) { port, client ->
            val response = client.postHook(
                port,
                ClaudeHookConfig.SESSION_START,
                body = """{"session_id":"$uuid"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)

            val appended = store.appended.receive()
            assertEquals(AgentEvent.SessionBound(ProviderSessionId(uuid)), appended.event)
        }
    }

    // ---- auth: invalid / missing token → 401, nothing appended ----

    @Test
    fun anInvalidTokenIs401AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(port, ClaudeHookConfig.STOP, token = "wrong-token")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "a rejected token must append nothing")
        }
    }

    @Test
    fun aMissingTokenIs401AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(port, ClaudeHookConfig.STOP, token = null)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "a missing token must append nothing")
        }
    }

    // ---- unknown pane → 404, nothing appended ----

    @Test
    fun anUnknownPaneIs404AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            // %999 is well-formed but not in the seeded pane→session map.
            val response = client.postHook(port, ClaudeHookConfig.STOP, pane = "%999")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "an unresolved pane must append nothing")
        }
    }

    // ---- an ignored hook is accepted (200) but stores nothing ----

    @Test
    fun anUnmappedHookIs200AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(port, "PreToolUse")
            assertEquals(HttpStatusCode.OK, response.status, "a wired-but-unmapped hook is accepted")
            assertTrue(store.appended.tryReceive().isFailure, "an ignored hook stores nothing")
        }
    }

    /**
     * A minimal in-memory [EventStore] for route isolation: it only records what the route [append]s,
     * onto a thread-safe [Channel] the test drains (Channels give a happens-before across the CIO
     * server thread and the test thread). All other members are unused by the route and stubbed.
     */
    private class RecordingEventStore : EventStore {
        data class Appended(val sessionId: SessionId, val event: AgentEvent, val source: EventSource)

        val appended = Channel<Appended>(Channel.UNLIMITED)
        private var seq = 0L

        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq {
            seq += 1
            appended.send(Appended(sessionId, event, source))
            return Seq(seq)
        }

        override suspend fun upsertSession(meta: SessionMeta) = Unit
        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: io.kotgent.core.SessionState,
            stateSource: EventSource,
            paneId: io.kotgent.core.PaneId?,
            updatedAt: Long,
        ) = Unit
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = null
        override suspend fun listSessions(): List<SessionMeta> = emptyList()
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()
    }
}
