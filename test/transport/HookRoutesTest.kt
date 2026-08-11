package io.kotgent.transport

import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.adapter.codex.CodexHookConfig
import io.kotgent.adapter.junie.JunieHookConfig
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.store.EventStore
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StoredEvent
import io.kotgent.tmux.TmuxHookConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
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

class HookRoutesTest {

    private val token = "hook-secret-token-abc123"
    private val pane = PaneId("%1")
    private val session = SessionId("kt-abc123")
    private val seededPanes: Map<PaneId, SessionId> = mapOf(pane to session)

    private fun withIngress(
        store: EventStore,
        paneLookup: suspend (PaneId) -> SessionId? = { seededPanes[it] },
        tokenProvider: () -> String = { token },
        modelCapture: ClaudeModelCapture? = null,
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                // These tests exclude the launch/register race, so unknown panes must fail immediately.
                claudeHookRoutes(
                    tokenProvider, paneLookup, store, paneLookupGraceMillis = 0,
                    modelCapture = modelCapture ?: ClaudeModelCapture(store),
                )
            }
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

    private fun withTmuxIngress(
        tokenProvider: () -> String = { token },
        onSessionClosed: suspend (SessionId) -> Unit = {},
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing { tmuxHookRoutes(tokenProvider, onSessionClosed) }
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

    private suspend fun HttpClient.postTmuxHook(
        port: Int,
        token: String? = this@HookRoutesTest.token,
        sessionName: String? = "kt-abc123",
        host: String? = null,
    ): HttpResponse = post("http://127.0.0.1:$port${TmuxHookConfig.INGRESS_PATH}") {
        if (token != null) header(TmuxHookConfig.HOOK_TOKEN_HEADER, token)
        if (sessionName != null) header(TmuxHookConfig.SESSION_HEADER, sessionName)
        if (host != null) header(HttpHeaders.Host, host)
        setBody("")
    }


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
    fun aClaudeHookWithATranscriptPathCapturesTheModel() {
        val store = SqliteEventStore.inMemory(now = { 1L })
        val capture = ClaudeModelCapture(
            store,
            readTranscriptTail = { path -> if (path == "/t.jsonl") """{"message":{"model":"claude-opus-4-8"}}""" else null },
            now = { 2L },
        )
        withIngress(store, modelCapture = capture) { port, client ->
            store.upsertSession(
                SessionMeta(
                    id = session, name = "n", agent = "claude", cwd = "/w",
                    tmuxSession = "kt-abc123", state = SessionState.running, createdAt = 1L, updatedAt = 1L,
                ),
            )
            val response = client.postHook(port, ClaudeHookConfig.STOP, body = """{"transcript_path":"/t.jsonl"}""")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("claude-opus-4-8", store.getSession(session)!!.model, "the ingress wired the model capture")
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


    @Test
    fun anUnknownPaneIs404AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(port, ClaudeHookConfig.STOP, pane = "%999")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "an unresolved pane must append nothing")
        }
    }


    @Test
    fun anUnmappedHookIs200AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.postHook(port, "PreToolUse")
            assertEquals(HttpStatusCode.OK, response.status, "a wired-but-unmapped hook is accepted")
            assertTrue(store.appended.tryReceive().isFailure, "an ignored hook stores nothing")
        }
    }


    @Test
    fun rotatingTheTokenFlipsTheIngressToTheNewValueWithoutARestart() {
        val store = RecordingEventStore()
        val holder = TokenHolder(token)
        withIngress(store, tokenProvider = holder::current) { port, client ->
            assertEquals(
                HttpStatusCode.OK,
                client.postHook(port, ClaudeHookConfig.STOP, token = holder.current()).status,
                "the current token is accepted before the rotation",
            )
            store.appended.receive()

            val rotated = holder.rotate(token)!!

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postHook(port, ClaudeHookConfig.STOP, token = token).status,
                "the pre-rotation token no longer authenticates",
            )
            assertTrue(store.appended.tryReceive().isFailure, "a rejected hook appends nothing")

            assertEquals(
                HttpStatusCode.OK,
                client.postHook(port, ClaudeHookConfig.STOP, token = rotated).status,
                "the rotated token authenticates on the same running server",
            )
            assertEquals(session, store.appended.receive().sessionId)
        }
    }


    @Test
    fun aHookArrivingUnderAForeignHostIs403AndAppendsNothing() {
        val store = RecordingEventStore()
        withIngress(store) { port, client ->
            val response = client.post(url(port, ClaudeHookConfig.STOP)) {
                header(ClaudeHookConfig.HOOK_TOKEN_HEADER, token)
                header(ClaudeHookConfig.TMUX_PANE_HEADER, pane.value)
                header(HttpHeaders.Host, "kotgent.example.com")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "a non-local hook must append nothing")
        }
    }

    @Test
    fun theCodexIngressIsLocalOnlyToo() {
        val store = RecordingEventStore()
        withCodexIngress(store) { port, client ->
            val response = client.post("http://127.0.0.1:$port${CodexHookConfig.INGRESS_PATH}?event=${CodexHookConfig.STOP}") {
                header(CodexHookConfig.HOOK_TOKEN_HEADER, token)
                header(CodexHookConfig.TMUX_PANE_HEADER, pane.value)
                header(HttpHeaders.Host, "kotgent.example.com")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(store.appended.tryReceive().isFailure)
        }
    }


    @Test
    fun tmuxCloseIngressRejectsAWrongOrMissingToken() {
        val closed = Channel<SessionId>(Channel.UNLIMITED)
        withTmuxIngress(onSessionClosed = { closed.send(it) }) { port, client ->
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postTmuxHook(port, token = "wrong-token").status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.postTmuxHook(port, token = null).status,
            )
            assertTrue(closed.tryReceive().isFailure, "unauthenticated close triggers invoke nothing")
        }
    }

    @Test
    fun tmuxCloseIngressRejectsAForeignHostBeforeReadingTheToken() {
        withTmuxIngress(
            tokenProvider = { error("the foreign-Host gate must run before token validation") },
        ) { port, client ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.postTmuxHook(
                    port,
                    token = "irrelevant",
                    host = "kotgent.example.com",
                ).status,
            )
        }
    }

    @Test
    fun tmuxCloseIngressStripsTheManagedPrefixAndInvokesTheTrigger() {
        val closed = Channel<SessionId>(Channel.UNLIMITED)
        withTmuxIngress(onSessionClosed = { closed.send(it) }) { port, client ->
            assertEquals(HttpStatusCode.OK, client.postTmuxHook(port, sessionName = "kt-1a2b3c4d").status)
            assertEquals(SessionId("1a2b3c4d"), closed.receive())
        }
    }

    @Test
    fun tmuxCloseIngressIgnoresMissingForeignAndBlankSessionNames() {
        val closed = Channel<SessionId>(Channel.UNLIMITED)
        withTmuxIngress(onSessionClosed = { closed.send(it) }) { port, client ->
            for (name in listOf<String?>(null, "other-session", "kt-", "kt-   ")) {
                assertEquals(
                    HttpStatusCode.OK,
                    client.postTmuxHook(port, sessionName = name).status,
                    "an unusable session header is a fire-and-forget no-op: <$name>",
                )
            }
            assertTrue(closed.tryReceive().isFailure, "unusable names never reach the trigger")
        }
    }

    @Test
    fun tmuxCloseIngressStillAnswersOkWhenTheTriggerThrows() {
        withTmuxIngress(onSessionClosed = { error("simulated liveness-probe failure") }) { port, client ->
            assertEquals(
                HttpStatusCode.OK,
                client.postTmuxHook(port, sessionName = "kt-not-ours").status,
                "an unknown session or probe failure must not surface in tmux's terminal",
            )
        }
    }


    private fun withCodexIngress(
        store: EventStore,
        paneLookup: suspend (PaneId) -> SessionId? = { seededPanes[it] },
        onProviderIdRebound: suspend (SessionId) -> Unit = {},
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                codexHookRoutes(
                    { token }, paneLookup, store, paneLookupGraceMillis = 0,
                    onProviderIdRebound = onProviderIdRebound,
                )
            }
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

    private suspend fun HttpClient.postCodexHook(
        port: Int,
        event: String,
        token: String? = this@HookRoutesTest.token,
        pane: String? = this@HookRoutesTest.pane.value,
        body: String = "{}",
    ): HttpResponse = post("http://127.0.0.1:$port${CodexHookConfig.INGRESS_PATH}?event=$event") {
        if (token != null) header(CodexHookConfig.HOOK_TOKEN_HEADER, token)
        if (pane != null) header(CodexHookConfig.TMUX_PANE_HEADER, pane)
        setBody(body)
    }

    @Test
    fun codexPermissionRequestAppendsAnApproval() {
        val store = RecordingEventStore()
        withCodexIngress(store) { port, client ->
            val response = client.postCodexHook(
                port,
                CodexHookConfig.PERMISSION_REQUEST,
                body = """{"tool_name":"shell","turn_id":"t1"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val appended = store.appended.receive()
            assertEquals(session, appended.sessionId, "the pane resolved to its session")
            assertEquals(AgentEvent.ApprovalRequested("shell"), appended.event)
            assertEquals(EventSource.hook, appended.source)
        }
    }

    @Test
    fun codexSessionStartBindsCodexOwnId() {
        val store = RecordingEventStore()
        val id = "019f8ea0-2548-7871-9835-947ff7623ccf"
        withCodexIngress(store) { port, client ->
            val response = client.postCodexHook(
                port,
                CodexHookConfig.SESSION_START,
                body = """{"session_id":"$id","cwd":"/work"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AgentEvent.SessionBound(ProviderSessionId(id)), store.appended.receive().event)
        }
    }


    private fun boundMeta(providerId: ProviderSessionId?, agent: String = "codex"): SessionMeta = SessionMeta(
        id = session, name = "kt-abc123", agent = agent,
        providerSessionId = providerId, cwd = "/work", tmuxSession = "kt-abc123", paneId = pane,
        state = SessionState.running, stateSource = EventSource.system,
        createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun codexSessionStartThatDisplacesADifferentBoundIdFiresTheRebindSeam() {
        val store = RecordingEventStore()
        store.sessionMeta = boundMeta(ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
        val rebounds = Channel<SessionId>(Channel.UNLIMITED)
        withCodexIngress(store, onProviderIdRebound = { rebounds.send(it) }) { port, client ->
            val response = client.postCodexHook(
                port,
                CodexHookConfig.SESSION_START,
                body = """{"session_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","cwd":"/work"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AgentEvent.SessionBound(ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
                store.appended.receive().event,
                "the hook id was appended regardless — the hook wins over the scan",
            )
            assertEquals(session, rebounds.receive(), "displacing a DIFFERENT bound id fired the seam")
        }
    }

    @Test
    fun aThrowingRebindCorrectionNeverFailsTheHook() {
        val store = RecordingEventStore()
        store.sessionMeta = boundMeta(ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
        withCodexIngress(
            store,
            onProviderIdRebound = { throw IllegalStateException("correction broke") },
        ) { port, client ->
            val response = client.postCodexHook(
                port,
                CodexHookConfig.SESSION_START,
                body = """{"session_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","cwd":"/work"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status, "a failed correction is logged, never a failed hook")
            assertEquals(
                AgentEvent.SessionBound(ProviderSessionId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
                store.appended.receive().event,
                "the displacing append itself still committed",
            )
        }
    }

    @Test
    fun codexSessionStartFiresNoRebindOnAFirstBindOrARepeatOfTheSameId() {
        val store = RecordingEventStore()
        val rebounds = Channel<SessionId>(Channel.UNLIMITED)
        withCodexIngress(store, onProviderIdRebound = { rebounds.send(it) }) { port, client ->
            val id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            client.postCodexHook(port, CodexHookConfig.SESSION_START, body = """{"session_id":"$id"}""")
            store.appended.receive()
            assertTrue(rebounds.tryReceive().isFailure, "a first bind is not a displacement")

            store.sessionMeta = boundMeta(ProviderSessionId(id))
            client.postCodexHook(port, CodexHookConfig.SESSION_START, body = """{"session_id":"$id"}""")
            store.appended.receive()
            assertTrue(rebounds.tryReceive().isFailure, "re-binding the same id displaces nothing")
        }
    }

    @Test
    fun codexIngressRejectsAWrongTokenBeforeLookingAtAnythingElse() {
        val store = RecordingEventStore()
        withCodexIngress(store) { port, client ->
            val response = client.postCodexHook(port, CodexHookConfig.STOP, token = "wrong")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "an unauthenticated callback stores nothing")
        }
    }

    @Test
    fun codexIngress404sAnUnknownPane() {
        val store = RecordingEventStore()
        withCodexIngress(store, paneLookup = { null }) { port, client ->
            assertEquals(HttpStatusCode.NotFound, client.postCodexHook(port, CodexHookConfig.STOP).status)
            assertTrue(store.appended.tryReceive().isFailure)
        }
    }

    @Test
    fun eachIngressSpeaksOnlyItsOwnProvidersVocabulary() {
        val claudeStore = RecordingEventStore()
        withIngress(claudeStore) { port, client ->
            assertEquals(HttpStatusCode.OK, client.postHook(port, CodexHookConfig.PERMISSION_REQUEST).status)
            assertTrue(claudeStore.appended.tryReceive().isFailure, "a codex-only hook is inert on /hooks/claude")
            assertEquals(HttpStatusCode.OK, client.postHook(port, JunieHookConfig.STOP_FAILURE).status)
            assertTrue(claudeStore.appended.tryReceive().isFailure, "a junie-only hook is inert on /hooks/claude")
        }

        val codexStore = RecordingEventStore()
        withCodexIngress(codexStore) { port, client ->
            assertEquals(HttpStatusCode.OK, client.postCodexHook(port, ClaudeHookConfig.NOTIFICATION).status)
            assertTrue(codexStore.appended.tryReceive().isFailure, "a claude-only hook is inert on /hooks/codex")
            assertEquals(HttpStatusCode.OK, client.postCodexHook(port, JunieHookConfig.STOP_FAILURE).status)
            assertTrue(codexStore.appended.tryReceive().isFailure, "a junie-only hook is inert on /hooks/codex")
        }

        val junieStore = RecordingEventStore()
        withJunieIngress(junieStore) { port, client ->
            assertEquals(HttpStatusCode.OK, client.postJunieHook(port, ClaudeHookConfig.NOTIFICATION).status)
            assertTrue(junieStore.appended.tryReceive().isFailure, "a claude-only hook is inert on /hooks/junie")
            assertEquals(HttpStatusCode.OK, client.postJunieHook(port, CodexHookConfig.POST_TOOL_USE).status)
            assertTrue(junieStore.appended.tryReceive().isFailure, "junie has no PostToolUse: inert")
        }
    }


    private fun withJunieIngress(
        store: EventStore,
        paneLookup: suspend (PaneId) -> SessionId? = { seededPanes[it] },
        onProviderIdRebound: suspend (SessionId) -> Unit = {},
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                junieHookRoutes(
                    { token }, paneLookup, store, paneLookupGraceMillis = 0,
                    onProviderIdRebound = onProviderIdRebound,
                )
            }
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

    private suspend fun HttpClient.postJunieHook(
        port: Int,
        event: String,
        token: String? = this@HookRoutesTest.token,
        pane: String? = this@HookRoutesTest.pane.value,
        body: String = "{}",
    ): HttpResponse = post("http://127.0.0.1:$port${JunieHookConfig.INGRESS_PATH}?event=$event") {
        if (token != null) header(JunieHookConfig.HOOK_TOKEN_HEADER, token)
        if (pane != null) header(JunieHookConfig.TMUX_PANE_HEADER, pane)
        setBody(body)
    }

    @Test
    fun juniePreToolUseAppendsAToolCallCarryingTheToolName() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.PRE_TOOL_USE,
                body = """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"ls"}}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val appended = store.appended.receive()
            assertEquals(session, appended.sessionId, "the pane resolved to its session")
            assertEquals(AgentEvent.ToolCall("Bash"), appended.event)
            assertEquals(EventSource.hook, appended.source)
        }
    }

    @Test
    fun juniePermissionRequestAppendsAnApproval() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.PERMISSION_REQUEST,
                body = """{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{}}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AgentEvent.ApprovalRequested("Bash"), store.appended.receive().event)
        }
    }

    @Test
    fun junieStopFailureCompletesTheTurnSoASessionCannotStickAtRunning() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.STOP_FAILURE,
                body = """{"hook_event_name":"StopFailure","error":"rate_limit","error_details":"429"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AgentEvent.TurnCompleted, store.appended.receive().event)
        }
    }

    @Test
    fun junieSessionEndExitsTheSession() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.SESSION_END,
                body = """{"hook_event_name":"SessionEnd","reason":"prompt_input_exit"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(AgentEvent.Exited(0), store.appended.receive().event)
        }
    }

    @Test
    fun junieSessionStartCarriesNoIdSoNothingIsBound() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.SESSION_START,
                body = """{"hook_event_name":"SessionStart","source":"startup"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "no session_id -> nothing to bind")
        }
    }

    @Test
    fun junieSessionStartBindsANonUuidIdWhenOneIsPresent() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.SESSION_START,
                body = """{"hook_event_name":"SessionStart","session_id":"session-260730-015553-1j1h"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AgentEvent.SessionBound(ProviderSessionId("session-260730-015553-1j1h")),
                store.appended.receive().event,
            )
        }
    }

    @Test
    fun junieSessionStartThatDisplacesADifferentBoundIdFiresTheRebindSeam() {
        val store = RecordingEventStore()
        store.sessionMeta = boundMeta(ProviderSessionId("session-260730-010101-aaaa"), agent = "junie")
        val rebounds = Channel<SessionId>(Channel.UNLIMITED)
        withJunieIngress(store, onProviderIdRebound = { rebounds.send(it) }) { port, client ->
            val response = client.postJunieHook(
                port,
                JunieHookConfig.SESSION_START,
                body = """{"session_id":"session-260730-015553-1j1h"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                AgentEvent.SessionBound(ProviderSessionId("session-260730-015553-1j1h")),
                store.appended.receive().event,
                "the hook id was appended regardless — the hook wins over the scan",
            )
            assertEquals(session, rebounds.receive(), "displacing a DIFFERENT bound id fired the seam")
        }
    }

    @Test
    fun junieIngressRejectsAWrongTokenBeforeLookingAtAnythingElse() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val response = client.postJunieHook(port, JunieHookConfig.STOP, token = "wrong")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "an unauthenticated callback stores nothing")
        }
    }

    @Test
    fun junieIngress404sAnUnknownPane() {
        val store = RecordingEventStore()
        withJunieIngress(store, paneLookup = { null }) { port, client ->
            assertEquals(HttpStatusCode.NotFound, client.postJunieHook(port, JunieHookConfig.STOP).status)
            assertTrue(store.appended.tryReceive().isFailure)
        }
    }

    @Test
    fun theJunieIngressIsLocalOnlyToo() {
        val store = RecordingEventStore()
        withJunieIngress(store) { port, client ->
            val url = "http://127.0.0.1:$port${JunieHookConfig.INGRESS_PATH}?event=${JunieHookConfig.STOP}"
            val response = client.post(url) {
                header(JunieHookConfig.HOOK_TOKEN_HEADER, token)
                header(JunieHookConfig.TMUX_PANE_HEADER, pane.value)
                header(HttpHeaders.Host, "kotgent.example.com")
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(store.appended.tryReceive().isFailure, "a non-local hook must append nothing")
        }
    }

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
        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long) = Unit
        override suspend fun setModel(sessionId: SessionId, model: String?, updatedAt: Long) = Unit
        override suspend fun setModelForProvider(
            sessionId: SessionId,
            providerSessionId: ProviderSessionId,
            model: String,
            updatedAt: Long,
        ): Boolean = false
        override suspend fun markRead(sessionId: SessionId, seq: Seq) = Unit

        var sessionMeta: SessionMeta? = null
        override suspend fun getSession(sessionId: SessionId): SessionMeta? = sessionMeta
        override suspend fun listSessions(): List<SessionMeta> = emptyList()
        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = emptyList()
        override suspend fun projectionOf(sessionId: SessionId): Projection = Projection.EMPTY
        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = emptyFlow()
        override val sessionUpdates: SharedFlow<SessionUpdate> = MutableSharedFlow()
    }
}
