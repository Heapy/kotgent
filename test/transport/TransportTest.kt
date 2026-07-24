package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.PaneId
import io.kotgent.core.Projection
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionState
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.reduce
import io.kotgent.core.replay
import io.kotgent.daemon.AgentBinaryNotFoundException
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.pty.PtyFactory
import io.kotgent.pty.PtyHandle
import io.kotgent.pty.TerminalBridge
import io.kotgent.store.EventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.StaleCursorException
import io.kotgent.store.StoredEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end transport tests (plan Task 14) — a real embedded [KotgentServer] on an ephemeral port,
 * driven by a Ktor CIO client, wired to a host-free stack: a thread-safe in-memory [FakeEventStore], a
 * [SessionManager] over a [FakeTmux] + a canned agent, and a [WsFakePtyFactory] behind the terminal
 * bridges. Every body is bounded by [withTimeout] (anti-hang).
 *
 * ## Why fakes instead of SqliteEventStore / the Task-9 FakePtyHandle
 * The CIO server runs handlers on its OWN engine threads, distinct from the test thread. So (a) shared
 * state observed across that boundary must synchronize — the [WsFakePty] records input/resize/output on
 * [Channel]s (which give a cross-thread happens-before), unlike the Task-9 single-threaded FakePtyHandle;
 * and (b) the store is touched from both threads, so it is a coroutine-[Mutex]-guarded in-memory fake
 * rather than the native sqliter driver (whose thread-affinity HookRoutesTest already sidesteps the same
 * way). The fake honors the real [EventStore] contract, including the restart-safe stale-cursor error.
 */
class TransportTest {

    private val token = "transport-secret-token-xyz789"
    private val providerId = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val seed = "SEED-SCREEN\r\n".encodeToByteArray()

    /**
     * A published origin (as `~/.kotgent/config.json`'s `publicUrl`) for the through-the-tunnel cases, and
     * the bare host a request arriving through the tunnel carries. Derived, so renaming the fixture origin
     * cannot desynchronize the two and turn a gate test into an `Origin`-mismatch failure.
     */
    private val publicHost = "kotgent.example.com"
    private val publicUrl = "https://$publicHost"

    // ---- 0. SessionDto mapping: cli version/path are carried through ----

    @Test
    fun sessionDtoCarriesTheCliVersionAndPath() {
        val meta = SessionMeta(
            id = SessionId("dto01"), name = "n", agent = "claude", cwd = "/w",
            tmuxSession = "kt-dto01", state = SessionState.running, createdAt = 1L, updatedAt = 1L,
            model = "claude-opus-4-8", cliVersion = "2.1.218", cliPath = "/usr/local/bin/claude",
        )
        val dto = meta.toDto()
        assertEquals("claude-opus-4-8", dto.model)
        assertEquals("2.1.218", dto.cliVersion)
        assertEquals("/usr/local/bin/claude", dto.cliPath)

        val bare = meta.copy(model = null, cliVersion = null, cliPath = null).toDto()
        assertNull(bare.model, "a session with no discovered model maps to null")
        assertNull(bare.cliVersion, "a session with no detected version maps to null")
        assertNull(bare.cliPath)
    }

    @Test
    fun archivedIsCarriedOnBothTheDtoAndTheUpdateDto() {
        val meta = SessionMeta(
            id = SessionId("arc01"), name = "n", agent = "claude", cwd = "/w",
            tmuxSession = "kt-arc01", state = SessionState.stopped, createdAt = 1L, updatedAt = 1L,
            archived = true,
        )
        assertTrue(meta.toDto().archived, "SessionDto carries archived")
        assertTrue(meta.toUpdateDto().archived, "the resync SessionUpdateDto carries archived")
        assertTrue(
            SessionUpdate(SessionId("arc01"), SessionState.stopped, Seq(1), 0L, archived = true).toDto().archived,
            "the live SessionUpdateDto carries archived",
        )
    }

    @Test
    fun doneArchivesTheSessionAndUndoneRestoresIt() = withServer { ctx ->
        val created = ctx.startSession()

        val done = ctx.post("/sessions/${created.id}/done")
        assertEquals(HttpStatusCode.OK, done.status, "done returns 200")
        val doneDto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), done.bodyAsText())
        assertEquals("stopped", doneDto.state, "done killed the agent")
        assertTrue(doneDto.archived, "done archived the session")

        val undone = ctx.post("/sessions/${created.id}/undone")
        assertEquals(HttpStatusCode.OK, undone.status)
        val undoneDto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), undone.bodyAsText())
        assertEquals(false, undoneDto.archived, "undone restored the session")
    }

    // ---- 1. POST /sessions → the session appears in GET /sessions ----

    @Test
    fun postSessionsCreatesASessionThatAppearsInTheList() = withServer { ctx ->
        val created = ctx.startSession(cwd = "/tmp/project")
        assertEquals("claude", created.agent)
        assertEquals("/tmp/project", created.cwd)
        assertTrue(created.tmuxSession.startsWith("kt-"), "a tmux session name was assigned")
        assertEquals(providerId.value, created.providerSessionId, "preallocated provider id is bound up front")

        val list = ctx.getSessions()
        assertTrue(list.any { it.id == created.id }, "the started session appears in GET /sessions")

        val one = ctx.getSession(created.id)
        assertEquals(created.id, one.id)
        assertEquals("running", one.state)
    }

    // ---- 2. events-WS receives a state-change notification ----

    @Test
    fun eventsWsPushesAStateChangeWhenASessionStartsNeedingAttention() = withServer { ctx ->
        val created = ctx.startSession()
        val sid = SessionId(created.id)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            // Draining the baseline snapshot proves we are subscribed — so the append below is not raced.
            val snapshot = receiveUpdate()
            assertEquals(created.id, snapshot.sessionId, "the snapshot covers the started session")

            // A hook-style approval append flips the session to needs_approval; the WS must deliver it live.
            ctx.store.append(sid, AgentEvent.ApprovalRequested("perm-1"), EventSource.hook)

            val update = awaitUpdate { it.sessionId == created.id && it.state == "needs_approval" }
            assertTrue(update.needsAttention, "needs_approval is a needs-attention state")
            assertTrue(update.lastSeq >= 1, "lastSeq advanced")
        }
    }

    // ---- 3. terminal-WS: seed first, then streamed bytes, forwarded input, and a resize frame ----

    @Test
    fun terminalWsDeliversSeedStreamsBytesForwardsInputAndHandlesResize() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            // The first subscriber lazily opened the single upstream (recorded by the fake factory).
            val upstream = ctx.ptyFactory.opened.receive()

            // (a) capture-pane seed arrives first, as a binary frame.
            assertContentEquals(seed, receiveBinary(), "the capture-pane seed is delivered before live deltas")

            // (b) a live delta from the upstream streams through to the client.
            upstream.emit("hello-terminal".encodeToByteArray())
            assertContentEquals("hello-terminal".encodeToByteArray(), receiveBinary(), "live bytes stream through")

            // (c) client input (binary frame) is forwarded to the shared upstream.
            send(Frame.Binary(fin = true, data = "typed-input".encodeToByteArray()))
            assertContentEquals("typed-input".encodeToByteArray(), upstream.writes.receive(), "input reaches the upstream")

            // (d) a resize control frame reaches the upstream as a TIOCSWINSZ resize.
            send(Frame.Text("""{"type":"resize","cols":123,"rows":45}"""))
            assertEquals(123 to 45, upstream.resizes.receive(), "the resize frame reaches the upstream")
        }
    }

    @Test
    fun terminalWsOpensTheUpstreamAtTheGeometryTheClientDeclaresInItsQuery() = withServer { ctx ->
        val created = ctx.startSession()

        // The browser/CLI knows its size before it can send a frame, and `tmux attach` only reads its
        // geometry once, at startup — so the size must reach the upstream at OPEN. Regression guard for
        // "a freshly attached terminal renders 80x24 until you detach and re-attach".
        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/sessions/${created.id}/terminal?cols=143&rows=53",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()
            assertEquals(143 to 53, upstream.resizes.receive(), "the query geometry sizes the upstream at open")
            assertContentEquals(seed, receiveBinary(), "the seed still arrives before any live delta")
        }
    }

    // ---- 3b. POST /sessions/{id}/input reaches the shared upstream via the Broadcaster ----

    @Test
    fun postInputReachesTheSharedTerminalUpstream() = withServer { ctx ->
        val created = ctx.startSession()

        // Attach a terminal so the lazy upstream is open, then POST /input to the SAME session.
        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()
            receiveBinary() // consume the seed

            val resp = ctx.postBody("/sessions/${created.id}/input", "rest-typed")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertContentEquals("rest-typed".encodeToByteArray(), upstream.writes.receive(), "POST /input reaches the upstream")
        }
    }

    // ---- 3c. POST /sessions/{id}/read advances the unread cursor ----

    @Test
    fun postReadAdvancesTheCursorAndClearsUnread() = withServer { ctx ->
        val created = ctx.startSession() // lastSeq == 1 (the preallocated SessionBound)
        assertEquals(1L, created.unread, "a fresh session starts with its whole log unread")

        val resp = ctx.postBody("/sessions/${created.id}/read", """{"seq":1}""")
        // This also pins the route ORDERING: `read` must be handled by the literal route, not swallowed by
        // `post("/sessions/{id}/{action}")` below it — which would answer 400 "unknown action" instead of
        // this 200 "ok" (the same guarantee `/input` relies on).
        assertEquals(HttpStatusCode.OK, resp.status, "marking read returns 200")
        assertEquals("ok", resp.bodyAsText())

        val after = ctx.getSession(created.id)
        assertEquals(1L, after.readCursor, "the cursor moved to the seq the client displayed")
        assertEquals(0L, after.unread, "so the badge is clear")
    }

    @Test
    fun postReadOnAnUnknownSessionIs404AndAnUnparseableBodyIs400() = withServer { ctx ->
        assertEquals(
            HttpStatusCode.NotFound,
            ctx.postBody("/sessions/no-such-id/read", """{"seq":1}""").status,
            "an unknown session is a 404, not a silent no-op",
        )
        val created = ctx.startSession()
        assertEquals(
            HttpStatusCode.BadRequest,
            ctx.postBody("/sessions/${created.id}/read", "not-json-at-all").status,
            "an undecodable body is a 400",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            ctx.postBody("/sessions/${created.id}/read", "").status,
            "an empty body is a 400 too",
        )
        // `seq` is deliberately NOT defaulted in MarkReadRequest: a body that omits it must fail rather
        // than silently mark seq 0 read (which the SQL would then treat as a no-op, hiding the bug).
        assertEquals(
            HttpStatusCode.BadRequest,
            ctx.postBody("/sessions/${created.id}/read", "{}").status,
            "a body missing the required seq is a 400, not an implicit 0",
        )
    }

    @Test
    fun postReadIsReachableThroughTheTunnelNotJustFromLoopback() = withServer(publicUrl = publicUrl) { ctx ->
        // The phone case: /read is mounted inside `authenticated`, NOT `loopbackOnly`. Moving it under the
        // local-only gate would 403 every request arriving under the published host — with the rest of the
        // suite still green, since every other test drives it over 127.0.0.1.
        val created = ctx.startSession()
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions/${created.id}/read") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Host, publicHost)
            header(HttpHeaders.Origin, publicUrl)
            setBody("""{"seq":1}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "the published host reaches /read: ${resp.bodyAsText()}")
        assertEquals(0L, ctx.getSession(created.id).unread, "and the cursor really moved")
    }

    @Test
    fun postReadClampsANegativeSeqInsteadOfRejectingOrStoringIt() = withServer { ctx ->
        val created = ctx.startSession()
        val resp = ctx.postBody("/sessions/${created.id}/read", """{"seq":-5}""")
        assertEquals(HttpStatusCode.OK, resp.status, "a negative seq is clamped, not rejected")

        val after = ctx.getSession(created.id)
        assertEquals(0L, after.readCursor, "and the negative value never reaches the stored cursor")
        assertEquals(created.unread, after.unread, "so the badge is untouched")
    }

    @Test
    fun postReadClearsTheBadgeInEveryOtherConnectedClientWithoutAReload() = withServer { ctx ->
        // The second-device case: one browser marks the session read, and every OTHER client learns the new
        // `unread` from the ordinary /events session_update — no reload, no new channel.
        val created = ctx.startSession() // lastSeq == 1 (the preallocated SessionBound)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            // Draining the baseline snapshot proves this "second client" is subscribed before the POST.
            val snapshot = receiveUpdate()
            assertEquals(created.id, snapshot.sessionId)
            assertEquals(1L, snapshot.unread, "the second client starts out showing the badge")

            ctx.postBody("/sessions/${created.id}/read", """{"seq":1}""")

            val update = awaitUpdate { it.sessionId == created.id && it.unread == 0L }
            assertEquals(1L, update.lastSeq, "the cleared badge is reported against the same log position")
            assertTrue(!update.archived, "a live session stays visible")
        }
    }

    @Test
    fun markingAnArchivedSessionReadDoesNotUnHideItInOtherClients() = withServer { ctx ->
        // An archived ("done") session can still be the selected one, so the mark-read POST is reachable for
        // it. SessionUpdateDto.archived defaults to false, and the client assigns it unconditionally — a
        // signal that dropped the flag would make the row reappear in every sidebar until the next resync.
        val created = ctx.startSession()
        ctx.store.setArchived(SessionId(created.id), true, 2L)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveUpdate() // the baseline snapshot
            ctx.postBody("/sessions/${created.id}/read", """{"seq":1}""")

            val update = awaitUpdate { it.sessionId == created.id && it.unread == 0L }
            assertTrue(update.archived, "the mark-read signal carries archived=true, so the row stays hidden")
        }
    }

    @Test
    fun anEventOrAControlStateWriteOnAnArchivedSessionAlsoKeepsItHidden() = withServer { ctx ->
        // Mark-read is not the only emitter: a late hook append and a control-state write both broadcast for
        // a done session too. What this test pins is the TRANSPORT half of that — SessionUpdate.toDto and
        // the /events WS carry `archived` through for those two emitters as well, not just for mark-read.
        // The store half (that SqliteEventStore's `append` and `emitFromRow` actually put the stored row's
        // flag on the signal) cannot be observed here — withServer runs on FakeEventStore — so it is pinned
        // against the real store by EventStoreTest.anAppendAndAControlStateWriteOnAnArchivedSessionAlsoCarryArchived.
        val created = ctx.startSession() // lastSeq == 1 (the preallocated SessionBound)
        val sid = SessionId(created.id)
        ctx.store.setArchived(sid, true, 2L)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveUpdate() // the baseline snapshot

            ctx.store.append(sid, AgentEvent.ApprovalRequested("perm-1"), EventSource.hook)
            val appended = awaitUpdate { it.sessionId == created.id && it.lastSeq == 2L }
            assertTrue(appended.archived, "an append on a done session must not un-hide it")
            assertEquals(2L, appended.unread, "and the badge still counts the unread event")

            ctx.store.updateSessionState(sid, SessionState.stopped, EventSource.system, null, 3L)
            val controlled = awaitUpdate { it.sessionId == created.id && it.state == "stopped" }
            assertTrue(controlled.archived, "a control-state write on a done session must not un-hide it")
        }
    }

    /**
     * `mouse on` is forced, so a single wheel scroll by ANY viewer parks the SHARED pane in tmux
     * copy-mode, where every keystroke — including bytes written into the attached client's pty — is
     * routed to the copy-mode key table and dropped while tmux reports success. A REST caller has no
     * terminal to look at, so this endpoint must leave copy-mode first and, when it provably cannot,
     * **refuse** rather than answer `ok` for input that was thrown away.
     *
     * The interactive terminal WebSocket deliberately does NOT cancel: a human who scrolled back and
     * then typed expects tmux's own behaviour. This is the programmatic path only.
     */
    @Test
    fun postInputLeavesCopyModeAndRefusesWhenItCannot() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()
            receiveBinary() // consume the seed

            val ok = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions/${created.id}/input") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("delivered")
            }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertContentEquals("delivered".encodeToByteArray(), upstream.writes.receive())
            assertEquals(
                listOf(created.id),
                ctx.tmux.copyModeCancels,
                "the REST input path leaves copy-mode before writing into the shared upstream",
            )

            // Now the cancel does not take (a viewer's wheel keeps dragging the pane back).
            ctx.tmux.copyModeStuck = true
            val refused = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions/${created.id}/input") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("swallowed")
            }
            assertEquals(HttpStatusCode.Conflict, refused.status, "an undeliverable write must not answer ok")
            assertTrue("copy-mode" in refused.bodyAsText(), "and it must say why: ${refused.bodyAsText()}")
        }
    }

    /**
     * The OTHER way this endpoint drops input, and the more common one: the terminal bridge is lazy, so
     * with no subscriber attached there is no `tmux attach` upstream and the bytes go nowhere. The sink
     * used to hardcode `ok` after the write, so a `POST /input` at a session nobody is watching was
     * answered `200` and silently discarded.
     */
    @Test
    fun postInputRefusesWhenNoTerminalIsAttachedToTheSession() = withServer { ctx ->
        val created = ctx.startSession()

        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions/${created.id}/input") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("nobody-is-watching")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status, "input with no upstream to reach must not answer ok")
        assertTrue("not delivered" in resp.bodyAsText(), "and it must say so: ${resp.bodyAsText()}")
        assertTrue(
            ctx.ptyFactory.opened.tryReceive().isFailure,
            "and a write never opens an upstream — only a terminal subscriber does",
        )
    }

    /**
     * An empty body has nothing to deliver, so it must not reach the sink at all: the copy-mode cancel
     * is a SHARED-pane side effect that yanks every viewer of that pane out of their scrollback, and
     * running it for a write that is a guaranteed no-op is pure collateral damage. (`Tmux.sendKeys`
     * guards the same way; this is the REST seam's half of that rule.)
     */
    @Test
    fun postInputWithAnEmptyBodyNeverTouchesTheSharedPane() = withServer { ctx ->
        val created = ctx.startSession()
        // Both failure modes armed: no terminal attached AND a pane that cannot be cleared. An empty
        // body must still answer ok, because neither one can lose anything that was never sent.
        ctx.tmux.copyModeStuck = true

        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions/${created.id}/input") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "an empty write has nothing to fail to deliver")
        assertEquals(
            emptyList(),
            ctx.tmux.copyModeCancels,
            "and it never ran the shared-pane copy-mode cancel",
        )
    }

    /**
     * One tmux condition, one wire contract. A swallowed `send-keys` reaching `interrupt` used to be a
     * plain `TmuxException` → **400**, which tells a programmatic client the request was malformed and
     * must not be retried — while the identical condition on `/input` answered **409** with an operator
     * hint. The copy-mode case now has its own exception type and gets the 409 + hint on both routes;
     * every other tmux failure stays a 400.
     */
    @Test
    fun interruptAnswers409ForCopyModeAnd400ForAnyOtherTmuxFailure() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.tmux.sendKeysCopyModeStuck = true
        val conflict = ctx.post("/sessions/${created.id}/interrupt")
        assertEquals(HttpStatusCode.Conflict, conflict.status, "a swallowed Ctrl-C is retryable, not malformed")
        assertTrue("copy-mode" in conflict.bodyAsText(), "and it must carry the operator hint: ${conflict.bodyAsText()}")
        assertEquals(
            "running",
            ctx.getSession(created.id).state,
            "and the projection must NOT record an interrupt tmux never delivered",
        )

        ctx.tmux.sendKeysCopyModeStuck = false
        ctx.tmux.sendKeysFailure = "tmux send-keys for '${created.id}' failed: bad target"
        val badRequest = ctx.post("/sessions/${created.id}/interrupt")
        assertEquals(HttpStatusCode.BadRequest, badRequest.status, "an ordinary tmux failure stays a 400")

        ctx.tmux.sendKeysFailure = null
        val ok = ctx.post("/sessions/${created.id}/interrupt")
        assertEquals(HttpStatusCode.OK, ok.status, "and a delivered interrupt still succeeds")
        assertEquals("ready", ctx.getSession(created.id).state, "which is when the projection may record it")
    }

    // ---- 4. missing / wrong token → 401 on a control call AND on a WS handshake ----

    @Test
    fun missingOrWrongTokenIsRejectedOnRestAndOnWsHandshake() = withServer { ctx ->
        // REST: no token at all.
        assertEquals(
            HttpStatusCode.Unauthorized,
            ctx.client.get("http://127.0.0.1:${ctx.port}/sessions").status,
            "a control call with no token is 401",
        )
        // REST: wrong token.
        val wrong = ctx.client.get("http://127.0.0.1:${ctx.port}/sessions") {
            header(HttpHeaders.Authorization, "Bearer not-the-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status, "a control call with a wrong token is 401")

        // WS: a bad Bearer must be rejected at the handshake (401, no upgrade) → the client connect throws.
        var wsRejected = false
        try {
            ctx.client.webSocket(
                "ws://127.0.0.1:${ctx.port}/events",
                request = { header(HttpHeaders.Authorization, "Bearer not-the-token") },
            ) {
                // Unreachable if the handshake is correctly rejected.
            }
        } catch (_: Throwable) {
            wsRejected = true
        }
        assertTrue(wsRejected, "a WS handshake with a bad Bearer must be rejected")

        // WS: the legacy `?token=` query form no longer authenticates anything — even the CORRECT token in
        // the query must be rejected, so the secret can never live in a URL again (Task 9).
        var queryTokenRejected = false
        try {
            ctx.client.webSocket("ws://127.0.0.1:${ctx.port}/events?token=$token") {
                // Unreachable if the query token is correctly ignored and the handshake refused.
            }
        } catch (_: Throwable) {
            queryTokenRejected = true
        }
        assertTrue(queryTokenRejected, "a WS handshake carrying the token as ?token= must be rejected")
    }

    // ---- 4b. the browser's key: a session cookie authenticates the same control plane ----

    @Test
    fun aSessionCookieAuthenticatesTheControlPlaneJustLikeABearer() = withServer { ctx ->
        // The real server, not a bare route: this is what proves KotgentServer actually mounts the gate
        // that knows about cookies. No Origin is sent, exactly as a browser does on a same-origin GET.
        val cookie = issueSessionCookie(token, issuedAt = 1_700_000_000_000)
        val resp = ctx.client.get("http://127.0.0.1:${ctx.port}/sessions") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "a cookie minted from the master token is accepted")

        val forged = ctx.client.get("http://127.0.0.1:${ctx.port}/sessions") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=v1.1700000000000.deadbeef")
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status, "a forged one is not")
    }

    // ---- 5. a stale per-session cursor → error (restart-safe cursor is a hard error) ----

    @Test
    fun aStalePerSessionCursorErrorsTheEventsWs() = withServer { ctx ->
        val created = ctx.startSession() // lastSeq == 1 (the preallocated SessionBound)

        // Subscribe per-session with a cursor far beyond lastSeq+1 → StaleCursorException → the server
        // closes the socket with VIOLATED_POLICY (the client must resync, not silently skip).
        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}/events?session=${created.id}&from=999",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason, "a stale cursor closes with VIOLATED_POLICY")
        }
    }

    // ---- 6. control ops: stop transitions; unknown session 404; unknown action 400; resume-blocked 409 ----

    @Test
    fun controlStopTransitionsTheSessionToStopped() = withServer { ctx ->
        val created = ctx.startSession()
        val resp = ctx.post("/sessions/${created.id}/stop")
        assertEquals(HttpStatusCode.OK, resp.status, "stop returns 200")
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertEquals("stopped", dto.state, "the session is now stopped")
        assertTrue(ctx.tmux.killed.contains(created.id), "tmux kill-session was issued for the logical id")
    }

    @Test
    fun aControlOpOnAnUnknownSessionIs404() = withServer { ctx ->
        assertEquals(HttpStatusCode.NotFound, ctx.post("/sessions/no-such-id/stop").status)
    }

    @Test
    fun anUnknownControlActionIs400() = withServer { ctx ->
        val created = ctx.startSession()
        assertEquals(HttpStatusCode.BadRequest, ctx.post("/sessions/${created.id}/frobnicate").status)
    }

    @Test
    fun resumeWhileTheProviderIdIsPendingIs409() = withServer { ctx ->
        // A dead session whose provider id was never captured → resume is blocked (409 ResumeBlocked).
        ctx.store.upsertSession(
            SessionMeta(
                id = SessionId("pend01"), name = "pend01", agent = "claude", cwd = "/tmp",
                tmuxSession = "kt-pend01", state = SessionState.crashed, createdAt = 1L, updatedAt = 1L,
            ),
        )
        assertEquals(HttpStatusCode.Conflict, ctx.post("/sessions/pend01/resume").status)
    }

    @Test
    fun startingAnUnsupportedAgentIs400() = withServer { ctx ->
        // The factory only builds the kinds it was registered with, so an unknown one is a client error.
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"agent":"aider","cwd":"/tmp"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun resumingASessionWhoseAgentIsUnsupportedIs400NotA500() = withServer { ctx ->
        // A legacy row persisted with an agent this daemon cannot build. `resume` rebuilds the adapter
        // from the STORED agent kind, so it throws the very exception the start route maps to 400 — the
        // action route must map it the same way instead of surfacing a 500.
        ctx.store.upsertSession(
            SessionMeta(
                id = SessionId("lgcy01"), name = "lgcy01", agent = "aider", providerSessionId = providerId,
                cwd = "/tmp", tmuxSession = "kt-lgcy01", state = SessionState.resumable,
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        val resp = ctx.post("/sessions/lgcy01/resume")
        assertEquals(HttpStatusCode.BadRequest, resp.status, "an unsupported stored agent is a client error")
        assertTrue(ctx.tmux.newSessionCommands.isEmpty(), "and nothing was launched for it")
    }

    @Test
    fun startingAnAgentWhoseBinaryIsMissingIs400WithInstallHint() = withServer(
        // The kind IS supported, but its binary did not resolve on the daemon's PATH (launchd's minimal
        // env), so the factory builder throws AgentBinaryNotFoundException — a client-fixable
        // misconfiguration, not a 500, carrying the actionable `kotgent install` hint.
        factory = agentFactoryOf(mapOf("claude" to { _: String -> throw AgentBinaryNotFoundException("claude") })),
    ) { ctx ->
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"agent":"claude","cwd":"/tmp"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, "a missing agent binary is a client error, not a 500")
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent install"), "the 400 body carries the install hint: $body")
        assertTrue(body.contains("claude"), "the 400 body names the agent kind: $body")
        assertTrue(ctx.tmux.newSessionCommands.isEmpty(), "and nothing was launched for it")
    }

    @Test
    fun resumingASessionWhoseAgentBinaryIsMissingIs400WithInstallHint() = withServer(
        // `resume` rebuilds the adapter from the STORED kind; if that binary is no longer on the daemon's
        // PATH the builder throws AgentBinaryNotFoundException. The action route must map it to the same
        // 400 + install hint as the start route, not surface a 500.
        factory = agentFactoryOf(mapOf("claude" to { _: String -> throw AgentBinaryNotFoundException("claude") })),
    ) { ctx ->
        ctx.store.upsertSession(
            SessionMeta(
                id = SessionId("miss01"), name = "miss01", agent = "claude", providerSessionId = providerId,
                cwd = "/tmp", tmuxSession = "kt-miss01", state = SessionState.resumable,
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        val resp = ctx.post("/sessions/miss01/resume")
        assertEquals(HttpStatusCode.BadRequest, resp.status, "a missing agent binary is a client error, not a 500")
        val body = resp.bodyAsText()
        assertTrue(body.contains("kotgent install"), "the 400 body carries the install hint: $body")
        assertTrue(body.contains("claude"), "the 400 body names the agent kind: $body")
        assertTrue(ctx.tmux.newSessionCommands.isEmpty(), "and nothing was launched for it")
    }

    // --- harness -------------------------------------------------------------------------------------

    /** The wired-up server + client + fakes handed to each test body. */
    private inner class Ctx(
        val port: Int,
        val client: HttpClient,
        val store: FakeEventStore,
        val ptyFactory: WsFakePtyFactory,
        val tmux: FakeTmux,
    ) {
        suspend fun startSession(agent: String = "claude", cwd: String = "/tmp/work"): SessionDto {
            val resp = client.post("http://127.0.0.1:$port/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("""{"agent":"$agent","cwd":"$cwd"}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status, "POST /sessions returns 201")
            return TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        }

        suspend fun getSessions(): List<SessionDto> {
            val resp = client.get("http://127.0.0.1:$port/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            return TRANSPORT_JSON.decodeFromString(ListSerializer(SessionDto.serializer()), resp.bodyAsText())
        }

        suspend fun getSession(id: String): SessionDto {
            val resp = client.get("http://127.0.0.1:$port/sessions/$id") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            return TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        }

        /** An authenticated `POST` with no body — for the control ops (stop/resume/interrupt/…). */
        suspend fun post(path: String) = client.post("http://127.0.0.1:$port$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        /** An authenticated `POST` carrying a body — the no-body [post] helper cannot drive `/read`. */
        suspend fun postBody(path: String, body: String) = client.post("http://127.0.0.1:$port$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }
    }

    private fun withServer(
        // Wired as the daemon does (Commands.daemon), narrowed to one kind: an agent kind the factory
        // does not know — on start OR on resume of a stored row — surfaces as the UnsupportedAgentException
        // both routes must map to 400. Overridable so a test can inject a builder that throws (e.g.
        // AgentBinaryNotFoundException for the missing-binary paths).
        factory: AgentFactory = agentFactoryOf(
            mapOf("claude" to { cwd: String -> CannedAgentFactory(listOf("cat"), providerId).create("claude", cwd) }),
        ),
        // The published origin the daemon is reachable at through the cloudflared tunnel; `null` (the
        // default) is the loopback-only daemon every other test drives.
        publicUrl: String? = null,
        block: suspend (Ctx) -> Unit,
    ) = runBlocking {
        withTimeout(40_000) {
            val store = FakeEventStore(now = { 1L })
            val tmux = FakeTmux()
            val registry = PaneRegistry()
            val idScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                tmux, store, registry,
                factory,
                ProviderIdCapture(store, idScope),
                now = { 1L },
            )
            val ptyFactory = WsFakePtyFactory()
            val bridgeFactory: (String, CoroutineScope) -> TerminalBridge = { id, scope ->
                TerminalBridge(listOf("fake-attach", id), { seed }, ptyFactory, scope)
            }
            val server = KotgentServer(
                manager, store, TokenHolder(token), bridgeFactory,
                webUiDir = null, publicUrl = publicUrl, port = 0,
            ).start()
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                block(Ctx(server.port(), client, store, ptyFactory, tmux))
            } finally {
                client.close()
                server.stop()
                idScope.cancel()
            }
        }
    }

    // --- WS receive helpers (skip control frames) ----------------------------------------------------

    private suspend fun DefaultClientWebSocketSession.receiveBinary(): ByteArray {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) return frame.readBytes()
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveUpdate(): SessionUpdateDto {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text) return TRANSPORT_JSON.decodeFromString(SessionUpdateDto.serializer(), frame.readText())
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitUpdate(predicate: (SessionUpdateDto) -> Boolean): SessionUpdateDto {
        while (true) {
            val update = receiveUpdate()
            if (predicate(update)) return update
        }
    }

    /** An [AgentFactory] yielding a canned launch spec (a harmless `cat` + a preallocated provider id). */
    private class CannedAgentFactory(
        private val command: List<String>,
        private val preallocated: ProviderSessionId?,
    ) : AgentFactory {
        override fun create(agentKind: String, cwd: String): AgentAdapter = object : AgentAdapter {
            override val events: Flow<AgentEvent> = emptyFlow()
            override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
                is LaunchMode.New -> LaunchSpec(command, emptyMap(), cwd, preallocated)
                is LaunchMode.Resume ->
                    LaunchSpec(command + listOf("--resume", mode.providerSessionId.value), emptyMap(), cwd, null)
            }
        }
    }

    /**
     * A thread-safe fake [PtyHandle]: output/input/resize all cross the CIO-server↔test thread boundary
     * on [Channel]s (happens-before), so the terminal test can observe them race-free (unlike the Task-9
     * FakePtyHandle, which assumes a single dispatcher). [emit] plays a byte chunk as if the pty produced it.
     */
    private class WsFakePty(val command: List<String>) : PtyHandle {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: ReceiveChannel<ByteArray> get() = out

        /** Input the fan-out routed to this upstream. */
        val writes = Channel<ByteArray>(Channel.UNLIMITED)

        /** Resizes applied to this upstream (cols to rows). */
        val resizes = Channel<Pair<Int, Int>>(Channel.UNLIMITED)

        fun emit(bytes: ByteArray) { out.trySend(bytes) }
        override fun write(bytes: ByteArray) { writes.trySend(bytes) }
        override fun resize(cols: Int, rows: Int) { resizes.trySend(cols to rows) }
        override fun close() { out.close() }
    }

    /** A [PtyFactory] minting [WsFakePty]s and publishing each on [opened] so the test can grab the upstream. */
    private class WsFakePtyFactory : PtyFactory {
        val opened = Channel<WsFakePty>(Channel.UNLIMITED)
        override fun invoke(command: List<String>, env: Map<String, String>): PtyHandle =
            WsFakePty(command).also { opened.trySend(it) }
    }

    /**
     * A host-free, thread-safe in-memory [EventStore] honoring the Task-7 contract: append-only per-session
     * log with a monotonic seq, a session cache advanced transactionally with each append, a cursored
     * [subscribe] whose stale cursor is a hard [StaleCursorException], and the Task-14 [sessionUpdates]
     * signal. Guarded by one coroutine [Mutex]; every observable is a [Channel] / [SharedFlow], so it is
     * safe to touch from the CIO engine threads and the test thread at once.
     */
    private class FakeEventStore(private val now: () -> Long = { 1L }) : EventStore {
        private val mutex = Mutex()
        private val metas = LinkedHashMap<SessionId, SessionMeta>()
        private val logs = HashMap<SessionId, MutableList<StoredEvent>>()
        private val projections = HashMap<SessionId, Projection>()
        private val subs = HashMap<SessionId, MutableList<SendChannel<StoredEvent>>>()
        private val updates = MutableSharedFlow<SessionUpdate>(
            replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val sessionUpdates: SharedFlow<SessionUpdate> get() = updates

        /**
         * The fake's [io.kotgent.store.SqliteEventStore] `emitFromRow`: rebuild the signal from the STORED
         * meta rather than from each mutator's arguments, so all five targeted writers stay in step with the
         * real store by construction instead of by comment — including `archived`, which a client assigns
         * unconditionally and which therefore un-hides a "done" row if any emitter drops it. [append] is the
         * one exception, mirroring the real store (see its own note below).
         */
        private fun emitFromMeta(sessionId: SessionId) {
            val m = metas[sessionId] ?: return
            updates.tryEmit(
                SessionUpdate(sessionId, m.state, m.lastSeq, unread(m.lastSeq.value, m.readCursor.value), m.archived),
            )
        }

        override suspend fun upsertSession(meta: SessionMeta): Unit = mutex.withLock {
            val prior = metas[meta.id]
            // Honors the contract: full-row EXCEPT createdAt (preserved) and readCursor (max-merged, so a
            // caller holding a stale cursor cannot regress the badge — Sessions.sq's `upsert`).
            val merged = if (prior != null) {
                meta.copy(
                    createdAt = prior.createdAt,
                    readCursor = Seq(maxOf(prior.readCursor.value, meta.readCursor.value)),
                )
            } else {
                meta
            }
            metas[meta.id] = merged
            emitFromMeta(meta.id)
        }

        override suspend fun updateSessionState(
            sessionId: SessionId,
            state: SessionState,
            stateSource: EventSource,
            paneId: PaneId?,
            updatedAt: Long,
        ): Unit = mutex.withLock {
            val m = metas[sessionId] ?: return@withLock
            // Honors the contract: update only state/state_source/pane_id/updated_at, NEVER last_seq or
            // provider_session_id (so a concurrent append is not clobbered).
            metas[sessionId] = m.copy(state = state, stateSource = stateSource, paneId = paneId, updatedAt = updatedAt)
            emitFromMeta(sessionId)
        }

        override suspend fun setArchived(sessionId: SessionId, archived: Boolean, updatedAt: Long): Unit = mutex.withLock {
            val m = metas[sessionId] ?: return@withLock
            metas[sessionId] = m.copy(archived = archived, updatedAt = updatedAt)
            emitFromMeta(sessionId)
        }

        override suspend fun setModel(sessionId: SessionId, model: String, updatedAt: Long): Unit = mutex.withLock {
            val m = metas[sessionId] ?: return@withLock
            metas[sessionId] = m.copy(model = model, updatedAt = updatedAt)
            emitFromMeta(sessionId)
        }

        override suspend fun markRead(sessionId: SessionId, seq: Seq): Unit = mutex.withLock {
            val m = metas[sessionId] ?: return@withLock
            // Mirrors the SQL: monotonic (max) and clamped to lastSeq (min); updated_at is NOT written.
            metas[sessionId] = m.copy(readCursor = Seq(maxOf(m.readCursor.value, minOf(seq.value, m.lastSeq.value))))
            emitFromMeta(sessionId)
        }

        override suspend fun getSession(sessionId: SessionId): SessionMeta? = mutex.withLock { metas[sessionId] }

        override suspend fun listSessions(): List<SessionMeta> = mutex.withLock { metas.values.toList() }

        override suspend fun append(sessionId: SessionId, event: AgentEvent, source: EventSource): Seq = mutex.withLock {
            val log = logs.getOrPut(sessionId) { mutableListOf() }
            val prior = projections.getOrPut(sessionId) { replay(log.map { it.event }) }
            val next = reduce(prior, event)
            projections[sessionId] = next
            val ts = now()
            val stored = StoredEvent(sessionId, next.lastSeq, ts, source, event)
            log.add(stored)
            metas[sessionId]?.let { m ->
                metas[sessionId] = m.copy(
                    state = next.state,
                    stateSource = source,
                    lastSeq = next.lastSeq,
                    providerSessionId = next.providerSessionId ?: m.providerSessionId,
                    updatedAt = ts,
                )
            }
            // Hand-built rather than [emitFromMeta], for the same two reasons the real store's `append` is
            // exempt: the signal carries the freshly reduced state/lastSeq, and it must still go out when no
            // meta row exists (the event was stored regardless). read_cursor and archived are untouched by an
            // append but still ride it — an event on a done session must not un-hide its row.
            val cached = metas[sessionId]
            updates.tryEmit(
                SessionUpdate(
                    sessionId, next.state, next.lastSeq,
                    unread(next.lastSeq.value, cached?.readCursor?.value ?: 0L), cached?.archived ?: false,
                ),
            )
            subs[sessionId]?.forEach { it.trySend(stored) }
            next.lastSeq
        }

        override suspend fun read(sessionId: SessionId, fromSeq: Seq): List<StoredEvent> = mutex.withLock {
            (logs[sessionId] ?: emptyList()).filter { it.seq.value >= fromSeq.value }
        }

        override suspend fun projectionOf(sessionId: SessionId): Projection = mutex.withLock {
            projections.getOrPut(sessionId) { replay((logs[sessionId] ?: emptyList()).map { it.event }) }
        }

        override fun subscribe(sessionId: SessionId, fromSeq: Seq): Flow<StoredEvent> = channelFlow {
            val relay = Channel<StoredEvent>(Channel.UNLIMITED)
            val snapshot = mutex.withLock {
                val last = (projections[sessionId] ?: replay((logs[sessionId] ?: emptyList()).map { it.event })).lastSeq
                if (fromSeq.value > last.value + 1) throw StaleCursorException(sessionId, fromSeq, last)
                val snap = (logs[sessionId] ?: emptyList()).filter { it.seq.value >= fromSeq.value }
                subs.getOrPut(sessionId) { mutableListOf() }.add(relay)
                snap
            }
            try {
                for (e in snapshot) send(e)
                for (e in relay) send(e)
            } finally {
                withContext(NonCancellable) { mutex.withLock { subs[sessionId]?.remove(relay) } }
                relay.close()
            }
        }

        private fun unread(last: Long, readCursor: Long): Long = (last - readCursor).coerceAtLeast(0)
    }
}
