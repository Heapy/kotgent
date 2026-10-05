package io.kotgent.transport

import app.cash.sqldelight.driver.native.inMemoryDriver
import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchOptions
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
import io.kotgent.store.FakePlanStore
import io.kotgent.store.PlanDocument
import io.kotgent.plan.*
import io.kotgent.daemon.PlanReviewResponse
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.kotgent.store.FakeMutexStore
import io.kotgent.store.FakePreferencesStore
import io.kotgent.store.MutexAcquireResult
import io.kotgent.store.SqliteMutexStore
import io.kotgent.tmux.Tmux
import io.ktor.client.HttpClient
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
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeFully
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

class PlanRoutesTest {
    @Test
    fun everyRouteRequiresAuthentication() = withServer { f ->
        for (request in listOf(
            HttpMethod.Get to BASE, HttpMethod.Put to BASE,
            HttpMethod.Patch to "$BASE/blocks/s_x", HttpMethod.Put to "$BASE/blocks/s_x/viewed",
            HttpMethod.Delete to "$BASE/blocks/s_x/viewed", HttpMethod.Post to "$BASE/threads",
            HttpMethod.Post to "$BASE/threads/th_x/messages", HttpMethod.Post to "$BASE/threads/th_x/resolve",
            HttpMethod.Post to "$BASE/review/wait", HttpMethod.Post to "$BASE/review/submit",
            HttpMethod.Post to "$BASE/review/approve",
            HttpMethod.Patch to "$BASE/settings", HttpMethod.Post to "$BASE/claim", HttpMethod.Post to "$BASE/complete",
            HttpMethod.Post to "$BASE/tasks/t_x/start", HttpMethod.Post to "$BASE/tasks/t_x/finish", HttpMethod.Post to "$BASE/tasks/t_x/block",
            HttpMethod.Post to "$BASE/tasks/t_x/status", HttpMethod.Post to "$BASE/tasks/t_x/worker", HttpMethod.Post to "$BASE/tasks/t_x/feedback",
            HttpMethod.Post to "$BASE/tasks/t_x/wait", HttpMethod.Post to "$BASE/wait", HttpMethod.Post to "$BASE/steps/st_x/done",
            HttpMethod.Post to "$BASE/findings", HttpMethod.Post to "$BASE/findings/send", HttpMethod.Post to "$BASE/findings/f_x/verify",
            HttpMethod.Post to "$BASE/findings/f_x/amend", HttpMethod.Post to "$BASE/findings/f_x/note", HttpMethod.Post to "$BASE/findings/f_x/decide", HttpMethod.Post to "$BASE/findings/f_x/investigate",
        )) assertEquals(HttpStatusCode.Unauthorized, f.request(request.first, request.second, "{}", token = false).status)
    }

    @Test
    fun callerAttributionBodyBoundsAndDocumentConflictsAreEnforced() = withServer { f ->
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Put, "$BASE?baseRev=0", DOCUMENT).status)
        assertEquals(HttpStatusCode.BadRequest, f.request(HttpMethod.Put, "$BASE?baseRev=0", "{}", session = true).status)
        assertTrue(f.refuseOversized().contains(" 413 "), "reject a declared oversized body before reading it")
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/findings/f_x/investigate", "{}", session = true).status)
        assertEquals(HttpStatusCode.BadRequest, f.request(HttpMethod.Post, "$BASE/findings/f_x/investigate", "{}").status)
        val initial = f.put()
        val section = initial.plan.sections.single()
        assertEquals(HttpStatusCode.Conflict, f.request(HttpMethod.Put, "$BASE?baseRev=0", DOCUMENT, session = true).status)
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Put, "$BASE/blocks/${section.id}/viewed", """{"rev":1}""", session = true).status)
        val marked = f.document(f.request(HttpMethod.Put, "$BASE/blocks/${section.id}/viewed", """{"rev":1}"""))
        assertEquals(1, marked.review.viewMarks.size)
        val edited = f.document(f.request(HttpMethod.Patch, "$BASE/blocks/${section.id}", """{"rev":1,"body":"Operator edit"}"""))
        assertEquals(PlanActor.Operator, edited.review.edits.single().author)
        assertEquals(HttpStatusCode.Conflict, f.request(HttpMethod.Patch, "$BASE/blocks/${section.id}", """{"rev":1,"body":"Stale"}""").status)
        val asked = f.document(f.request(HttpMethod.Post, "$BASE/threads", """{"blockId":"${section.id}","body":"Why?"}"""))
        val thread = asked.review.threads.single()
        val reply = f.document(f.request(HttpMethod.Post, "$BASE/threads/${thread.id}/messages", """{"body":"Because"}""", session = true))
        assertEquals(PlanActor.Session("alice"), reply.review.threads.single().messages.last().author)
        assertEquals(ThreadStatus.answered, reply.review.threads.single().status)
    }

    @Test
    fun reviewWaitContinuesAndItsNotificationClearsOnSubmit() = withServer { f ->
        val _ = f.put()
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/review/wait?wait=0").status)
        val pending = f.request(HttpMethod.Post, "$BASE/review/wait?wait=0", session = true)
        assertEquals(HttpStatusCode.OK, pending.status)
        assertEquals("pending", TRANSPORT_JSON.decodeFromString(PlanReviewResponse.serializer(), pending.bodyAsText()).verdict)
        val _ = f.request(HttpMethod.Post, "$BASE/review/wait?wait=0", session = true)
        assertEquals(listOf("plan.review:local:1"), f.wakes)
        assertTrue(f.request(HttpMethod.Get, "/notifications").bodyAsText().contains("plan.review:local:1"))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/review/approve", """{"round":1}""", session = true).status)
        coroutineScope {
            val wait = async { f.request(HttpMethod.Post, "$BASE/review/wait?wait=10", session = true) }
            val _ = f.document(f.request(HttpMethod.Post, "$BASE/review/approve", """{"round":1}"""))
            assertEquals("approved", TRANSPORT_JSON.decodeFromString(PlanReviewResponse.serializer(), wait.await().bodyAsText()).verdict)
        }
        assertFalse(f.request(HttpMethod.Get, "/notifications").bodyAsText().contains("plan.review:local:1"))
        assertEquals(HttpStatusCode.Conflict, f.request(HttpMethod.Post, "$BASE/review/submit", """{"round":1}""").status)
    }

    @Test
    fun executionTransitionsFindingsAndFeedbackEnforceTheirCallerRolesOverHttp() = withServer { f ->
        val initial = f.put()
        val task = initial.plan.tasks.single()
        val taskPath = "$BASE/tasks/${task.id}"
        val _ = f.request(HttpMethod.Post, "$BASE/review/wait?wait=0", session = true)
        val _ = f.document(f.request(HttpMethod.Post, "$BASE/review/approve", """{"round":1}"""))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/claim").status)
        val _ = f.document(f.request(HttpMethod.Post, "$BASE/claim", session = true))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/claim", pane = BOB_PANE).status)
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$taskPath/start", pane = BOB_PANE).status)
        val _ = f.document(f.request(HttpMethod.Post, "$taskPath/start", session = true))
        val worker = """{"sessionId":"bob","branch":"work/one","worktree":"/fixture"}"""
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$taskPath/worker", worker, pane = BOB_PANE).status)
        val _ = f.document(f.request(HttpMethod.Post, "$taskPath/worker", worker, session = true))
        val stepPath = "$BASE/steps/${task.steps.single().id}/done"
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, stepPath, session = true).status)
        val _ = f.document(f.request(HttpMethod.Post, stepPath, pane = BOB_PANE))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$taskPath/finish", session = true).status)
        val _ = f.document(f.request(HttpMethod.Post, "$taskPath/finish", pane = BOB_PANE))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/findings", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, f.request(HttpMethod.Post, "$BASE/findings", "{}", session = true).status)
        val added = f.document(f.request(HttpMethod.Post, "$BASE/findings", """{"taskId":"${task.id}","condition":"Empty rows","impact":"Crash","danger":"high","likelihood":"medium","options":[{"fix":"Guard","outcome":"No crash","cost":"low","fit":"high"}],"recommended":0}""", session = true))
        val finding = added.execution.findings.single()
        val findingPath = "$BASE/findings/${finding.id}"
        val verified = f.document(f.request(HttpMethod.Post, "$findingPath/verify", """{"rev":${finding.rev},"verifier":{"danger":"high","likelihood":"medium","options":[{"cost":"low","fit":"high"}],"verdict":"confirmed","reason":"Reproduced"}}""", session = true)).execution.findings.single()
        val noted = f.document(f.request(HttpMethod.Post, "$findingPath/note", """{"body":"The guard is sufficient."}""", pane = BOB_PANE)).execution.findings.single()
        val staleDecision = """{"rev":${verified.rev},"decision":{"kind":"fix_now","optionIndex":0}}"""
        assertEquals(HttpStatusCode.Conflict, f.request(HttpMethod.Post, "$findingPath/decide", staleDecision).status)
        val decision = """{"rev":${noted.rev},"decision":{"kind":"fix_now","optionIndex":0}}"""
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$findingPath/decide", decision, pane = BOB_PANE).status)
        assertEquals(HttpStatusCode.Conflict, f.request(HttpMethod.Post, "$BASE/findings/send", """{"taskId":"${task.id}"}""").status)
        val _ = f.document(f.request(HttpMethod.Post, "$findingPath/decide", decision))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/findings/send", """{"taskId":"${task.id}"}""", session = true).status)
        val _ = f.document(f.request(HttpMethod.Post, "$BASE/findings/send", """{"taskId":"${task.id}"}"""))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$taskPath/wait?wait=0", session = true).status)
        val feedback = f.request(HttpMethod.Post, "$taskPath/wait?wait=0", pane = BOB_PANE)
        assertEquals(HttpStatusCode.OK, feedback.status)
        assertTrue(feedback.bodyAsText().contains("\"event\":\"feedback\""))
        val _ = f.document(f.request(HttpMethod.Post, "$taskPath/finish", pane = BOB_PANE))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$taskPath/status", """{"status":"merging"}""", pane = BOB_PANE).status)
        val _ = f.document(f.request(HttpMethod.Post, "$taskPath/status", """{"status":"merging"}""", session = true))
        val _ = f.document(f.request(HttpMethod.Post, "$taskPath/status", """{"status":"done"}""", session = true))
        assertEquals(HttpStatusCode.Forbidden, f.request(HttpMethod.Post, "$BASE/complete", pane = BOB_PANE).status)
        assertEquals(PlanStatus.done, f.document(f.request(HttpMethod.Post, "$BASE/complete", session = true)).plan.status)
    }

    private class Fixture(val plans: FakePlanStore, val client: HttpClient, private val port: Int, val wakes: MutableList<String>) {
        fun url(path: String) = "http://127.0.0.1:$port/api/v1$path"
        suspend fun request(method: HttpMethod, path: String = BASE, body: String? = null, session: Boolean = false, token: Boolean = true, pane: String? = null): HttpResponse =
            client.request(url(path)) {
                this.method = method
                if (token) header(HttpHeaders.Authorization, "Bearer $TOKEN")
                if (pane != null || session) header(TASK_PANE_HEADER, pane ?: ALICE_PANE)
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
        suspend fun document(response: HttpResponse): PlanDocument {
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return TRANSPORT_JSON.decodeFromString(PlanDocument.serializer(), response.bodyAsText())
        }
        suspend fun put(): PlanDocument = document(request(HttpMethod.Put, "$BASE?baseRev=0", DOCUMENT, session = true))

        // Send only headers: an early refusal must not race a client still streaming half a megabyte.
        suspend fun refuseOversized(): String = withTimeout(5.seconds) {
            val selector = SelectorManager(Dispatchers.Default)
            val socket = aSocket(selector).tcp().connect("127.0.0.1", port)
            try {
                val input = socket.openReadChannel()
                socket.openWriteChannel(autoFlush = true).writeFully(buildString {
                    append("PUT /api/v1$BASE?baseRev=0 HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\nAuthorization: Bearer $TOKEN\r\n")
                    append("$TASK_PANE_HEADER: $ALICE_PANE\r\nContent-Type: application/json\r\n")
                    append("Content-Length: ${MAX_PLAN_DOCUMENT_BYTES + 1}\r\n\r\n")
                }.encodeToByteArray())
                input.readLine().orEmpty()
            } finally {
                socket.close()
                selector.close()
            }
        }
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
                        state = state, createdAt = 1L, updatedAt = 1L, parentSessionId = if (id == "bob") SessionId("alice") else null,
                    ),
                )
            }
            val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val plans = FakePlanStore(now = { clock[0] })
            val wakes = mutableListOf<String>()
            val client = routeTestClient()
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
                            override fun buildLaunchSpec(mode: LaunchMode, options: LaunchOptions): LaunchSpec =
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
                        webUiDir = null, port = 0, planStore = plans, onPlanReview = { topic -> val _ = wakes.add(topic) }, usageClock = { clock[0] },
                    ).start()
                }
                server = started
                block(Fixture(plans, client, started.port(), wakes))
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
        const val BASE = "/tasks/local:1/plan"
        const val DOCUMENT = """{"taskRef":"local:1","title":"Plans","sections":[{"kind":"overview","body":"Initial"}],"tasks":[{"ordinal":1,"title":"Implement","steps":[{"text":"Check"}]}]}"""
        const val TOKEN = "mutex-route-test-token"
        const val ALICE_PANE = "%1"
        const val BOB_PANE = "%2"
        const val LEASE_MILLIS = 30_000L
    }
}
