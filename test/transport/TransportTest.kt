package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.adapter.claude.ClaudeHookConfig
import io.kotgent.cli.DUPLICATE_IMPORT_ID_IN_BODY
import io.kotgent.cli.DaemonPush
import io.kotgent.cli.startDaemonServer
import io.kotgent.cli.withStartupCompensation
import io.kotgent.core.AgentEvent
import io.kotgent.core.EventSource
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.Seq
import io.kotgent.core.SessionState
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.daemon.AgentBinaryNotFoundException
import io.kotgent.daemon.AgentFactory
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.daemon.canonicalPath
import io.kotgent.push.PushStore
import io.kotgent.push.PushSubscription
import io.kotgent.push.PushNotifier
import io.kotgent.pty.PtyFactory
import io.kotgent.pty.PtyHandle
import io.kotgent.pty.TerminalBridge
import io.kotgent.store.EventStore
import io.kotgent.store.FakeEventStore
import io.kotgent.store.SqliteEventStore
import io.kotgent.store.SessionUpdate
import io.kotgent.store.UiPreferences
import io.kotgent.tmux.Tmux
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {

    private val token = "transport-secret-token-xyz789"
    private val providerId = ProviderSessionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val seed = "SEED-SCREEN\r\n".encodeToByteArray()

    private val publicHost = "kotgent.example.com"
    private val publicUrl = "https://$publicHost"

    @Test
    fun productionPushAssemblerIsReadyBeforeTheRealServerFactoryBinds() {
        val sent = Channel<SessionId>(Channel.UNLIMITED)
        var assembled = false
        withServer(
            productionFactory = true,
            pushAssembler = { events, scope ->
                val subscriptions = object : PushStore {
                    override suspend fun list(): List<PushSubscription> = emptyList()
                    override suspend fun save(subscription: PushSubscription) {}
                    override suspend fun remove(endpoint: String) {}
                }
                PushNotifier(events, send = { sent.send(it) }).start(scope)
                assembled = true
                DaemonPush(subscriptions, { "production-forwarded-vapid-key" }, close = {})
            },
        ) { ctx ->
            assertTrue(assembled, "the push assembler completed its notifier readiness barrier before bind")
            val response = ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX$PUSH_VAPID_KEY_PATH") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "the real production factory mounts the dependencies returned by the assembler",
            )
            assertEquals(
                "production-forwarded-vapid-key",
                TRANSPORT_JSON.decodeFromString(VapidKeyResponse.serializer(), response.bodyAsText()).key,
            )

            val created = ctx.startSession()
            ctx.store.append(
                SessionId(created.id),
                AgentEvent.ApprovalRequested("production-push"),
                EventSource.hook,
            )
            assertEquals(
                SessionId(created.id),
                sent.receive(),
                "the notifier assembled before bind observes the first post-bind attention transition",
            )
        }
    }

    @Test
    fun failedServerStartupCompletesPushCleanupAndPreservesItsFailure() = runBlocking {
        withTimeout(20_000) {
            val primary = IllegalStateException("server creation failed")
            val closeFailure = IllegalArgumentException("push close failed")
            val cleanupCompleted = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            val startup = launch {
                try {
                    startDaemonServer(
                        assemblePush = {
                            currentCoroutineContext()[Job]!!.cancel()
                            DaemonPush(
                                store = object : PushStore {
                                    override suspend fun list(): List<PushSubscription> = emptyList()
                                    override suspend fun save(subscription: PushSubscription) {}
                                    override suspend fun remove(endpoint: String) {}
                                },
                                publicKey = { "unused" },
                                close = {
                                    yield()
                                    cleanupCompleted.complete(Unit)
                                    throw closeFailure
                                },
                            )
                        },
                        createServer = { throw primary },
                    )
                } catch (e: Throwable) {
                    observed.complete(e)
                }
            }

            val failure = observed.await()
            startup.join()
            assertTrue(cleanupCompleted.isCompleted, "suspending cleanup completes in a cancelled startup coroutine")
            assertTrue(failure === primary, "the server startup failure remains primary")
            assertTrue(
                failure.suppressedExceptions.any { it === closeFailure },
                "the push cleanup failure is attached to the primary error: ${failure.suppressedExceptions}",
            )
        }
    }

    @Test
    fun cancelledPushAssemblyCompensatesBeforeADaemonPushExists() = runBlocking {
        withTimeout(20_000) {
            val primary = CancellationException("notifier startup cancelled")
            val closeFailure = IllegalStateException("transport close failed")
            val cleanupCompleted = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable>()
            var closeCalls = 0

            val startup = launch {
                try {
                    withStartupCompensation(
                        compensate = {
                            yield()
                            closeCalls++
                            cleanupCompleted.complete(Unit)
                            throw closeFailure
                        },
                    ) {
                        currentCoroutineContext()[Job]!!.cancel(primary)
                        throw primary
                    }
                } catch (failure: Throwable) {
                    observed.complete(failure)
                }
            }

            val failure = observed.await()
            startup.join()
            assertTrue(cleanupCompleted.isCompleted, "suspending transport cleanup survives startup cancellation")
            assertEquals(1, closeCalls, "the pre-owner resource is compensated exactly once")
            assertTrue(failure === primary, "the notifier startup cancellation remains primary")
            assertTrue(
                failure.suppressedExceptions.any { it === closeFailure },
                "a failed transport close remains visible on the primary cancellation",
            )
        }
    }


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
    fun archivedIsCarriedOnBothTheFullRowAndThePatch() {
        val meta = SessionMeta(
            id = SessionId("arc01"), name = "n", agent = "claude", cwd = "/w",
            tmuxSession = "kt-arc01", state = SessionState.stopped, createdAt = 1L, updatedAt = 1L,
            archived = true,
        )
        assertTrue(meta.toDto().archived, "SessionDto carries archived")
        assertTrue(
            SessionUpdate(
                SessionId("arc01"), SessionState.stopped, Seq(1), 0L, updatedAt = 1L, archived = true,
            ).toDto().archived,
            "the patch SessionUpdateDto carries archived",
        )
    }

    @Test
    fun thePatchCarriesTheUpdatedAtStampTheDoneListOrdersBy() {
        assertEquals(
            1_700_000_000_042L,
            SessionUpdate(
                SessionId("upd01"), SessionState.stopped, Seq(1), 0L,
                updatedAt = 1_700_000_000_042L, archived = true,
            ).toDto().updatedAt,
            "a client that only ever sees patches still learns when the row last changed",
        )
    }

    @Test
    fun thePatchCarriesTheModelAndRevAndItsNullModelIsAuthoritative() {
        val live = SessionUpdate(
            SessionId("mdl01"), SessionState.running, Seq(1), 0L, updatedAt = 1L,
            model = "gpt-6", rev = 7,
        ).toDto()
        assertEquals("gpt-6", live.model, "the patch carries the row's model")
        assertEquals(7L, live.rev, "the patch carries the row's rev")
        assertNull(
            SessionUpdate(
                SessionId("mdl01"), SessionState.running, Seq(1), 0L, updatedAt = 1L, rev = 8,
            ).toDto().model,
            "a cleared model rides the patch as an authoritative null",
        )
    }

    @Test
    fun everyGlobalFrameKindCarriesTheTypeDiscriminator() {
        fun typeOf(frame: EventsFrame): String? {
            val encoded = TRANSPORT_JSON.encodeToString(EventsFrame.serializer(), frame)
            return TRANSPORT_JSON.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content
        }
        val meta = SessionMeta(
            id = SessionId("fr01"), name = "n", agent = "claude", cwd = "/w",
            tmuxSession = "kt-fr01", state = SessionState.running, createdAt = 1L, updatedAt = 1L,
        )
        assertEquals("sessions_snapshot", typeOf(SessionsSnapshotDto(listOf(meta.toDto()))))
        assertEquals("session_row", typeOf(SessionRowDto(meta.toDto())))
        assertEquals(
            "session_update",
            typeOf(SessionUpdate(SessionId("fr01"), SessionState.running, Seq(1), 0L, updatedAt = 1L).toDto()),
        )
        assertEquals("preferences_update", typeOf(UiPreferences("/p", 1, 1).toUpdateDto()))
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


    @Test
    fun eventsWsPushesAStateChangeWhenASessionStartsNeedingAttention() = withServer { ctx ->
        val created = ctx.startSession()
        val sid = SessionId(created.id)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val snapshot = receiveSnapshot()
            assertTrue(snapshot.sessions.any { it.id == created.id }, "the snapshot covers the started session")

            ctx.store.append(sid, AgentEvent.ApprovalRequested("perm-1"), EventSource.hook)

            val update = awaitUpdate { it.sessionId == created.id && it.state == "needs_approval" }
            assertTrue(update.needsAttention, "needs_approval is a needs-attention state")
            assertTrue(update.lastSeq >= 1, "lastSeq advanced")
        }
    }

    @Test
    fun connectDeliversOneSnapshotOfFullRowsAndNothingBeforeIt() = withServer { ctx ->
        val a = ctx.startSession(cwd = "/tmp/a")
        val b = ctx.startSession(cwd = "/tmp/b")

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val (type, text) = receiveFirstSessionFrameJson()
            assertEquals("sessions_snapshot", type, "the baseline is ONE snapshot frame")
            val snapshot = TRANSPORT_JSON.decodeFromString(SessionsSnapshotDto.serializer(), text)
            assertEquals(
                setOf(a.id, b.id),
                snapshot.sessions.map { it.id }.toSet(),
                "the snapshot carries every session",
            )
            val row = snapshot.sessions.single { it.id == a.id }
            assertEquals("/tmp/a", row.cwd, "snapshot rows are full SessionDto rows, not thin updates")
            assertTrue(row.rev > 0, "a persisted row carries a positive rev")
        }
    }

    @Test
    fun aSessionStartedAfterConnectArrivesAsAFullRowAndThenPatches() = withServer { ctx ->
        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            assertTrue(receiveSnapshot().sessions.isEmpty(), "the baseline is empty before any session")

            val created = ctx.startSession(cwd = "/tmp/late")
            val row = receiveRow()
            assertEquals(created.id, row.session.id, "a session new to this socket arrives as a full row")
            assertEquals("/tmp/late", row.session.cwd, "…carrying full metadata the client can render")

            ctx.store.append(SessionId(created.id), AgentEvent.ApprovalRequested("p1"), EventSource.hook)
            val patch = awaitUpdate { it.sessionId == created.id && it.state == "needs_approval" }
            assertTrue(patch.rev > row.session.rev, "the patch's rev is newer than the row it follows")
        }
    }

    @Test
    fun setModelReachesAConnectedClientImmediatelyIncludingItsClear() = withServer { ctx ->
        val created = ctx.startSession()
        val sid = SessionId(created.id)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveSnapshot()

            ctx.store.setModel(sid, "gpt-6")
            val captured = awaitUpdate { it.sessionId == created.id && it.model == "gpt-6" }

            ctx.store.setModel(sid, null)
            val cleared = awaitUpdate { it.sessionId == created.id && it.rev > captured.rev }
            assertNull(cleared.model, "the clear rides the patch as an authoritative null")
        }
    }

    @Test
    fun aRowlessUpdateIsNotForwardedAndTheRowStillArrivesWholeLater() = withServer { ctx ->
        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveSnapshot()

            val sid = SessionId("ghost01")
            ctx.store.append(sid, AgentEvent.TurnStarted, EventSource.hook)
            ctx.store.upsertSession(
                SessionMeta(
                    id = sid, name = "ghost", agent = "claude", cwd = "/tmp/ghost",
                    tmuxSession = "kt-ghost01", state = SessionState.running, createdAt = 5L, updatedAt = 5L,
                ),
            )
            val (type, text) = receiveFirstSessionFrameJson()
            assertEquals(
                "session_row", type,
                "no frame for the row-less append, and the session still arrives WHOLE after its upsert",
            )
            val row = TRANSPORT_JSON.decodeFromString(SessionRowDto.serializer(), text)
            assertEquals(sid.value, row.session.id)
        }
    }

    @Test
    fun aBurstConflatesPerSocketButTheFinalStateAlwaysArrives() = withServer { ctx ->
        val created = ctx.startSession()
        val sid = SessionId(created.id)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveSnapshot()

            repeat(40) { ctx.store.append(sid, AgentEvent.ToolCall("t$it"), EventSource.hook) }
            val final = awaitUpdate { it.sessionId == created.id && it.lastSeq == 41L }
            assertEquals("running", final.state, "the final conflated state matches the last append")
        }
    }


    @Test
    fun terminalWsDeliversSeedStreamsBytesForwardsInputAndHandlesResize() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()

            assertContentEquals(seed, receiveBinary(), "the capture-pane seed is delivered before live deltas")

            upstream.emit("hello-terminal".encodeToByteArray())
            assertContentEquals("hello-terminal".encodeToByteArray(), receiveBinary(), "live bytes stream through")

            send(Frame.Binary(fin = true, data = "typed-input".encodeToByteArray()))
            assertContentEquals("typed-input".encodeToByteArray(), upstream.writes.receive(), "input reaches the upstream")

            send(Frame.Text("""{"type":"resize","cols":123,"rows":45}"""))
            assertEquals(123 to 45, upstream.resizes.receive(), "the resize frame reaches the upstream")
        }
    }

    @Test
    fun terminalWsOpensTheUpstreamAtTheGeometryTheClientDeclaresInItsQuery() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/terminal?cols=143&rows=53",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()
            assertEquals(143 to 53, upstream.resizes.receive(), "the query geometry sizes the upstream at open")
            assertContentEquals(seed, receiveBinary(), "the seed still arrives before any live delta")
        }
    }


    @Test
    fun postInputReachesTheSharedTerminalUpstream() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()
            receiveBinary()

            val resp = ctx.postBody("/sessions/${created.id}/input", "rest-typed")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertContentEquals("rest-typed".encodeToByteArray(), upstream.writes.receive(), "POST /input reaches the upstream")
        }
    }


    @Test
    fun postFilesUsesTheSessionsStoredCwdAndStreamsTheBody() {
        val uploader = RecordingFileUploader()
        withServer(fileUploader = uploader) { ctx ->
            val created = ctx.startSession(cwd = "/work/current-project")
            val payload = "photo bytes from the phone".encodeToByteArray()

            val response = ctx.upload(created.id, "whiteboard.jpg", payload)

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                FileUploadResponse("whiteboard.jpg", payload.size.toLong(), "/work/current-project"),
                TRANSPORT_JSON.decodeFromString(FileUploadResponse.serializer(), response.bodyAsText()),
            )
            val recorded = uploader.uploads.receive()
            assertEquals("/work/current-project", recorded.directory)
            assertEquals("whiteboard.jpg", recorded.fileName)
            assertEquals(payload.size.toLong(), recorded.expectedBytes)
            assertContentEquals(payload, recorded.body)
        }
    }

    @Test
    fun postFilesRejectsTraversalAndUnknownSessionsBeforeTouchingTheUploader() {
        val uploader = RecordingFileUploader()
        withServer(fileUploader = uploader) { ctx ->
            val created = ctx.startSession()

            assertEquals(
                HttpStatusCode.BadRequest,
                ctx.upload(created.id, "..%2Fescape.txt", "nope".encodeToByteArray(), encodedName = true).status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                ctx.upload(created.id, "", "nope".encodeToByteArray()).status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                ctx.upload("no-such-session", "file.txt", "nope".encodeToByteArray()).status,
            )
            assertTrue(uploader.uploads.tryReceive().isFailure, "invalid targets never reach the filesystem edge")
        }
    }

    @Test
    fun postFilesReportsAnExistingDestinationAsAConflict() {
        val uploader = RecordingFileUploader(FileUploadResult.AlreadyExists)
        withServer(fileUploader = uploader) { ctx ->
            val created = ctx.startSession(cwd = "/work/current-project")

            val response = ctx.upload(created.id, "notes.txt", "new".encodeToByteArray())

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("already exists"))
            uploader.uploads.receive()
        }
    }


    @Test
    fun postReadAdvancesTheCursorAndClearsUnread() = withServer { ctx ->
        val created = ctx.startSession()
        assertEquals(1L, created.unread, "a fresh session starts with its whole log unread")

        val resp = ctx.postBody("/sessions/${created.id}/read", """{"seq":1}""")
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
        assertEquals(
            HttpStatusCode.BadRequest,
            ctx.postBody("/sessions/${created.id}/read", "{}").status,
            "a body missing the required seq is a 400, not an implicit 0",
        )
    }

    @Test
    fun postReadIsReachableThroughTheTunnelNotJustFromLoopback() = withServer(publicUrl = publicUrl) { ctx ->
        val created = ctx.startSession()
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/read") {
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
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val snapshot = receiveSnapshot()
            val row = snapshot.sessions.single { it.id == created.id }
            assertEquals(1L, row.unread, "the second client starts out showing the badge")

            ctx.postBody("/sessions/${created.id}/read", """{"seq":1}""")

            val update = awaitUpdate { it.sessionId == created.id && it.unread == 0L }
            assertEquals(1L, update.lastSeq, "the cleared badge is reported against the same log position")
            assertTrue(!update.archived, "a live session stays visible")
        }
    }

    @Test
    fun markingAnArchivedSessionReadDoesNotUnHideItInOtherClients() = withServer { ctx ->
        val created = ctx.startSession()
        ctx.store.setArchived(SessionId(created.id), true, 2L)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveSnapshot()
            ctx.postBody("/sessions/${created.id}/read", """{"seq":1}""")

            val update = awaitUpdate { it.sessionId == created.id && it.unread == 0L }
            assertTrue(update.archived, "the mark-read signal carries archived=true, so the row stays hidden")
        }
    }

    @Test
    fun anEventOrAControlStateWriteOnAnArchivedSessionAlsoKeepsItHidden() = withServer { ctx ->
        val created = ctx.startSession()
        val sid = SessionId(created.id)
        ctx.store.setArchived(sid, true, 2L)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            receiveSnapshot()

            ctx.store.append(sid, AgentEvent.ApprovalRequested("perm-1"), EventSource.hook)
            val appended = awaitUpdate { it.sessionId == created.id && it.lastSeq == 2L }
            assertTrue(appended.archived, "an append on a done session must not un-hide it")
            assertEquals(2L, appended.unread, "and the badge still counts the unread event")

            ctx.store.updateSessionState(sid, SessionState.stopped, EventSource.system, null, 3L)
            val controlled = awaitUpdate { it.sessionId == created.id && it.state == "stopped" }
            assertTrue(controlled.archived, "a control-state write on a done session must not un-hide it")
        }
    }

    @Test
    fun postInputLeavesCopyModeAndRefusesWhenItCannot() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val upstream = ctx.ptyFactory.opened.receive()
            receiveBinary()

            val ok = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/input") {
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

            ctx.tmux.copyModeStuck = true
            val refused = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/input") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("swallowed")
            }
            assertEquals(HttpStatusCode.Conflict, refused.status, "an undeliverable write must not answer ok")
            val refusalBody = refused.bodyAsText()
            assertTrue("copy-mode" in refusalBody, "and it must say why: $refusalBody")
            assertTrue(
                "scroll the pane back to the bottom" in refusalBody,
                "and it must carry the copy-mode remedy promised by both input paths: $refusalBody",
            )
            assertTrue(
                upstream.writes.tryReceive().isFailure,
                "a failed copy-mode preflight must short-circuit before any bytes reach the upstream",
            )
        }
    }

    @Test
    fun postInputRefusesWhenNoTerminalIsAttachedToTheSession() = withServer { ctx ->
        val created = ctx.startSession()

        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/input") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("nobody-is-watching")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status, "input with no upstream to reach must not answer ok")
        val body = resp.bodyAsText()
        assertTrue("could not be confirmed" in body, "the result is uncertainty, not proof of zero delivery: $body")
        assertTrue("prefix" in body && "duplicate" in body, "the 409 must warn against a blind whole-body retry: $body")
        assertTrue(
            ctx.ptyFactory.opened.tryReceive().isFailure,
            "and a write never opens an upstream — only a terminal subscriber does",
        )
    }

    @Test
    fun postInputWithAnEmptyBodyNeverTouchesTheSharedPane() = withServer { ctx ->
        val created = ctx.startSession()
        ctx.tmux.copyModeStuck = true

        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/input") {
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
        assertEquals(
            "running",
            ctx.getSession(created.id).state,
            "and any failed/absent send must leave the projection unchanged",
        )

        ctx.tmux.sendKeysFailure = null
        val ok = ctx.post("/sessions/${created.id}/interrupt")
        assertEquals(HttpStatusCode.OK, ok.status, "and a delivered interrupt still succeeds")
        assertEquals("ready", ctx.getSession(created.id).state, "which is when the projection may record it")
    }


    @Test
    fun missingOrWrongTokenIsRejectedOnRestAndOnWsHandshake() = withServer { ctx ->
        assertEquals(
            HttpStatusCode.Unauthorized,
            ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions").status,
            "a control call with no token is 401",
        )
        val wrong = ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions") {
            header(HttpHeaders.Authorization, "Bearer not-the-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status, "a control call with a wrong token is 401")

        var wsRejected = false
        try {
            ctx.client.webSocket(
                "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
                request = { header(HttpHeaders.Authorization, "Bearer not-the-token") },
            ) {
            }
        } catch (_: Throwable) {
            wsRejected = true
        }
        assertTrue(wsRejected, "a WS handshake with a bad Bearer must be rejected")

        var queryTokenRejected = false
        try {
            ctx.client.webSocket("ws://127.0.0.1:${ctx.port}$API_PREFIX/events?token=$token") {
            }
        } catch (_: Throwable) {
            queryTokenRejected = true
        }
        assertTrue(queryTokenRejected, "a WS handshake carrying the token as ?token= must be rejected")
    }


    @Test
    fun aSessionCookieAuthenticatesTheControlPlaneJustLikeABearer() = withServer { ctx ->
        val cookie = issueSessionCookie(token, issuedAt = 1_700_000_000_000)
        val resp = ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "a cookie minted from the master token is accepted")

        val forged = ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=v1.1700000000000.deadbeef")
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status, "a forged one is not")
    }


    @Test
    fun aStalePerSessionCursorErrorsTheEventsWs() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events?session=${created.id}&from=999",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason, "a stale cursor closes with VIOLATED_POLICY")
        }
    }


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
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"agent":"aider","cwd":"/tmp"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun resumingASessionWhoseAgentIsUnsupportedIs400NotA500() = withServer { ctx ->
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
        factory = agentFactoryOf(mapOf("claude" to { _: String -> throw AgentBinaryNotFoundException("claude") })),
    ) { ctx ->
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions") {
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


    @Test
    fun importRegistersAResumableSessionWithNoLaunchAndReturns201() = withServer(
        probe = VendorStoreProbe { _, _, _ -> true },
    ) { ctx ->
        val resp = ctx.postBody(
            "/sessions/import",
            """{"agent":"claude","providerSessionId":"${providerId.value}","cwd":"/tmp","name":"imported","tags":["t1"]}""",
        )
        assertEquals(HttpStatusCode.Created, resp.status, "a successful import answers 201: ${resp.bodyAsText()}")
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertEquals("resumable", dto.state, "an import lands resumable — nothing was launched")
        assertEquals(providerId.value, dto.providerSessionId, "the row carries the imported provider id")
        assertEquals("claude", dto.agent)
        assertEquals(canonicalPath("/tmp"), dto.cwd, "the row stores the canonical (realpath) cwd spelling")
        assertEquals("imported", dto.name)
        assertEquals(listOf("t1"), dto.tags)
        assertNull(dto.paneId, "no pane — import launches nothing")
        assertTrue(ctx.tmux.newSessionCommands.isEmpty(), "and no tmux session was created")

        assertTrue(ctx.getSessions().any { it.id == dto.id }, "the imported session appears in GET /sessions")
        val bound = ctx.store.read(SessionId(dto.id), Seq(0))
        assertEquals(
            listOf<AgentEvent>(AgentEvent.SessionBound(providerId)),
            bound.map { it.event },
            "the import appended exactly the SessionBound (replay stays consistent with the row)",
        )
    }

    @Test
    fun importFailuresAreDistinguishable400sNotServerErrors() = withServer { ctx ->

        val unparseable = ctx.postBody("/sessions/import", "not-json-at-all")
        assertEquals(HttpStatusCode.BadRequest, unparseable.status, "an undecodable body is a 400")

        val unknown = ctx.postBody(
            "/sessions/import",
            """{"agent":"aider","providerSessionId":"${providerId.value}","cwd":"/tmp"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, unknown.status, "an unknown agent kind is a client error")
        val unknownBody = unknown.bodyAsText()
        assertTrue("unknown agent kind" in unknownBody, "the body names the failure: $unknownBody")
        assertTrue("claude" in unknownBody && "codex" in unknownBody, "…and the supported kinds: $unknownBody")

        val malformed = ctx.postBody(
            "/sessions/import",
            """{"agent":"claude","providerSessionId":"not a uuid","cwd":"/tmp"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, malformed.status, "a malformed provider id is a 400, not a 500")
        assertTrue(
            "ProviderSessionId" in malformed.bodyAsText(),
            "the body says what a valid id looks like: ${malformed.bodyAsText()}",
        )

        val noCwd = ctx.postBody(
            "/sessions/import",
            """{"agent":"claude","providerSessionId":"${providerId.value}"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, noCwd.status, "discovery finding no cwd is a 400")
        val noCwdBody = noCwd.bodyAsText()
        assertTrue("no on-disk record" in noCwdBody, "the body names the discovery miss: $noCwdBody")
        assertTrue("--cwd" in noCwdBody, "…and the workaround: $noCwdBody")
        assertTrue(
            "archived" in noCwdBody,
            "…and the archived-codex cause — the one --cwd can never fix: $noCwdBody",
        )

        val gone = "/nonexistent/kotgent-import-route-test"
        val deleted = ctx.postBody(
            "/sessions/import",
            """{"agent":"claude","providerSessionId":"${providerId.value}","cwd":"$gone"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, deleted.status, "a deleted project directory is a 400")
        val deletedBody = deleted.bodyAsText()
        assertTrue("does not exist" in deletedBody && gone in deletedBody, "the body names the missing dir: $deletedBody")

        val noTranscript = ctx.postBody(
            "/sessions/import",
            """{"agent":"claude","providerSessionId":"${providerId.value}","cwd":"/tmp"}""",
        )
        assertEquals(HttpStatusCode.BadRequest, noTranscript.status, "a transcript the probe cannot see is a 400")
        val noTranscriptBody = noTranscript.bodyAsText()
        assertTrue("no live claude transcript" in noTranscriptBody, "the body names the probe miss: $noTranscriptBody")
        assertTrue("--cwd" in noTranscriptBody, "…the workaround: $noTranscriptBody")
        assertTrue("archived" in noTranscriptBody, "…and archived codex sessions as a cause: $noTranscriptBody")

        assertTrue(ctx.getSessions().isEmpty(), "no failed import left a row behind")
    }

    @Test
    fun importOfADuplicateProviderIdIs409NamingTheExistingSessionAndItsArchivedState() = withServer(
        probe = VendorStoreProbe { _, _, _ -> true },
    ) { ctx ->
        val body = """{"agent":"claude","providerSessionId":"${providerId.value}","cwd":"/tmp"}"""
        val first = ctx.postBody("/sessions/import", body)
        assertEquals(HttpStatusCode.Created, first.status)
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), first.bodyAsText())

        val dup = ctx.postBody("/sessions/import", body)
        assertEquals(HttpStatusCode.Conflict, dup.status, "a second import of the same provider id is a 409")
        val dupBody = dup.bodyAsText()
        assertTrue(dto.id in dupBody, "the 409 names the existing kotgent session: $dupBody")
        assertEquals(
            dto.id,
            DUPLICATE_IMPORT_ID_IN_BODY.find(dupBody)?.groupValues?.get(1),
            "the 409 body matches the phrase contract the CLI parses: $dupBody",
        )
        assertTrue("archived" !in dupBody, "a live duplicate carries no archived marker: $dupBody")

        ctx.store.setArchived(SessionId(dto.id), true, 2L)
        val dupArchived = ctx.postBody("/sessions/import", body)
        assertEquals(HttpStatusCode.Conflict, dupArchived.status)
        val archivedBody = dupArchived.bodyAsText()
        assertTrue(dto.id in archivedBody, "the archived 409 still names the existing session: $archivedBody")
        assertTrue(
            "archived" in archivedBody && "Restore" in archivedBody,
            "…and flags it archived with the Restore hint: $archivedBody",
        )
        assertEquals(1, ctx.getSessions().size, "still exactly one row for the provider id")
    }

    @Test
    fun importAuthenticatedByCookieRequiresAnOriginOnThePost() = withServer(
        probe = VendorStoreProbe { _, _, _ -> true },
    ) { ctx ->
        val cookie = issueSessionCookie(token, issuedAt = 1_700_000_000_000)
        val body = """{"agent":"claude","providerSessionId":"${providerId.value}","cwd":"/tmp"}"""

        val noOrigin = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/import") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
            setBody(body)
        }
        assertEquals(
            HttpStatusCode.Forbidden,
            noOrigin.status,
            "a cookie-authenticated POST without an Origin is refused — the ambient-credential rule",
        )
        assertTrue(ctx.getSessions().isEmpty(), "and the refused import registered nothing")

        val withOrigin = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/import") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE_NAME=$cookie")
            header(HttpHeaders.Origin, "http://127.0.0.1")
            setBody(body)
        }
        assertEquals(
            HttpStatusCode.Created,
            withOrigin.status,
            "the same POST with a loopback Origin is served: ${withOrigin.bodyAsText()}",
        )
    }

    @Test
    fun importIsReachableThroughTheTunnelNotJustFromLoopback() = withServer(
        publicUrl = publicUrl,
        probe = VendorStoreProbe { _, _, _ -> true },
    ) { ctx ->
        val resp = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/sessions/import") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Host, publicHost)
            header(HttpHeaders.Origin, publicUrl)
            setBody("""{"agent":"claude","providerSessionId":"${providerId.value}","cwd":"/tmp"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status, "the published host reaches /sessions/import: ${resp.bodyAsText()}")
    }


    @Test
    fun fakeEventStoreMirrorsTheRealStoresCacheStateAuthority() = runBlocking {
        withTimeout(15_000) {
            suspend fun cacheAuthorityAnswers(store: EventStore): List<Any?> {
                val dead = SessionId("contrct1")
                store.upsertSession(contractMeta(dead, SessionState.resumable))
                store.append(dead, AgentEvent.SessionBound(providerId), EventSource.system)
                val afterDeadAppend = store.getSession(dead)!!

                val alive = SessionId("contrct2")
                store.upsertSession(contractMeta(alive, SessionState.running))
                store.append(alive, AgentEvent.TurnStarted, EventSource.hook)
                store.append(alive, AgentEvent.TurnCompleted, EventSource.hook)
                val afterAliveAppends = store.getSession(alive)!!

                return listOf(
                    afterDeadAppend.state, afterDeadAppend.providerSessionId, afterDeadAppend.lastSeq,
                    afterAliveAppends.state, afterAliveAppends.lastSeq,
                )
            }
            assertEquals(
                cacheAuthorityAnswers(SqliteEventStore.inMemory(now = { 1L })),
                cacheAuthorityAnswers(FakeEventStore(now = { 1L })),
                "the harness fake must answer append/upsert exactly like SqliteEventStore",
            )
        }
    }

    private fun contractMeta(id: SessionId, state: SessionState) = SessionMeta(
        id = id, name = id.value, agent = "claude", cwd = "/w",
        tmuxSession = "kt-${id.value}", state = state, createdAt = 1L, updatedAt = 1L,
    )


    @Test
    fun preferencesGetDefaultsAndPutReturnsCanonicalPersistedValue() = withServer { ctx ->
        assertEquals(
            PreferencesDto(basePath = "", groupingLevel = 1, revision = 0),
            ctx.getPreferences(),
            "a fresh daemon exposes the seeded defaults",
        )

        val response = ctx.putPreferences(
            """{"basePath":"  //Users///me/dev///  ","groupingLevel":4}""",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val saved = TRANSPORT_JSON.decodeFromString(PreferencesDto.serializer(), response.bodyAsText())
        assertEquals(
            PreferencesDto(basePath = "/Users/me/dev", groupingLevel = 4, revision = 1),
            saved,
            "PUT canonicalizes with the same rule as the browser and consumes one revision",
        )
        assertEquals(saved, ctx.getPreferences(), "GET reads back the persisted value")
    }

    @Test
    fun invalidPreferenceBodiesPathsAndLevelsAre400WithoutMutation() = withServer { ctx ->
        val before = ctx.getPreferences()
        val invalidBodies = listOf(
            "not-json",
            "{}",
            """{"basePath":"relative/path","groupingLevel":1}""",
            """{"basePath":"/work","groupingLevel":-1}""",
            """{"basePath":"/work","groupingLevel":5}""",
            """{"basePath":"/work","groupingLevel":"2"}""",
        )

        for (body in invalidBodies) {
            val response = ctx.putPreferences(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, "invalid body must be rejected: $body")
            assertEquals(before, ctx.getPreferences(), "a rejected request cannot mutate or consume a revision")
        }
    }

    @Test
    fun preferencesAreReachableThroughThePublishedTunnel() =
        withServer(publicUrl = publicUrl) { ctx ->
            val response = ctx.client.put("http://127.0.0.1:${ctx.port}$API_PREFIX/preferences") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Host, publicHost)
                header(HttpHeaders.Origin, publicUrl)
                setBody("""{"basePath":"/shared/work","groupingLevel":2}""")
            }

            assertEquals(HttpStatusCode.OK, response.status, "the published host reaches PUT /preferences")
            assertEquals(
                PreferencesDto("/shared/work", 2, 1),
                TRANSPORT_JSON.decodeFromString(PreferencesDto.serializer(), response.bodyAsText()),
            )
        }

    @Test
    fun globalEventsWsSendsTheCurrentPreferencesThenLiveSavesToAnotherClient() = withServer { ctx ->
        val firstSave = ctx.putPreferences("""{"basePath":"/before-connect","groupingLevel":1}""")
        assertEquals(HttpStatusCode.OK, firstSave.status)

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            assertEquals(
                PreferencesUpdateDto(
                    basePath = "/before-connect",
                    groupingLevel = 1,
                    revision = 1,
                ),
                receivePreferencesUpdate(),
                "a global subscriber immediately receives the persisted snapshot",
            )

            val secondSave = ctx.putPreferences("""{"basePath":"/live","groupingLevel":3}""")
            assertEquals(HttpStatusCode.OK, secondSave.status)
            assertEquals(
                PreferencesUpdateDto(basePath = "/live", groupingLevel = 3, revision = 2),
                receivePreferencesUpdate(),
                "an accepted save is delivered live to the already-connected client",
            )
        }
    }


    @Test
    fun directoryCompletionReturnsServerPathsAndRejectsARelativeInputWithoutBasePath() {
        val requests = Channel<Pair<String?, String>>(capacity = 1)
        val completer = DirectoryCompleter { basePath, input ->
            requests.trySend(basePath to input)
            listOf("/Users/me/dev/kotbot", "/Users/me/dev/kotgent")
        }

        withServer(directoryCompleter = completer) { ctx ->
            val response = ctx.postBody(
                "/directories/complete",
                """{"basePath":"/Users/me/dev","input":"kot"}""",
            )
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                CompleteDirectoryResponse(listOf("/Users/me/dev/kotbot", "/Users/me/dev/kotgent")),
                TRANSPORT_JSON.decodeFromString(
                    CompleteDirectoryResponse.serializer(),
                    response.bodyAsText(),
                ),
            )
            assertEquals("/Users/me/dev" to "kot", requests.receive())

            val invalid = ctx.postBody("/directories/complete", """{"input":"relative"}""")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(requests.tryReceive().isFailure, "an invalid request never reaches the filesystem completer")
        }
    }

    @Test
    fun directoryCompletionIsReachableThroughThePublicHost() {
        val completer = DirectoryCompleter { _, _ -> listOf("/work/kotgent") }
        withServer(publicUrl = publicUrl, directoryCompleter = completer) { ctx ->
            val response = ctx.client.post("http://127.0.0.1:${ctx.port}$API_PREFIX/directories/complete") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Host, publicHost)
                header(HttpHeaders.Origin, publicUrl)
                setBody("""{"basePath":"/work","input":"kot"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                CompleteDirectoryResponse(listOf("/work/kotgent")),
                TRANSPORT_JSON.decodeFromString(
                    CompleteDirectoryResponse.serializer(),
                    response.bodyAsText(),
                ),
            )
        }
    }


    @Test
    fun theGatedSurfaceAnswersUnderTheApiPrefixAndNoLongerOnTheBarePaths() = withServer { ctx ->
        for (path in listOf("/sessions", "/version", "/preferences")) {
            val prefixed = ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, prefixed.status, "$API_PREFIX$path is the API")

            val bare = ctx.client.get("http://127.0.0.1:${ctx.port}$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(
                HttpStatusCode.NotFound,
                bare.status,
                "$path is no longer the API — the bare paths belong to the SPA now",
            )
        }
    }

    @Test
    fun theHookIngressUsesTheApiPrefixAndKeepsItsLegacyAlias() = withServer { ctx ->
        for (path in listOf(ClaudeHookConfig.INGRESS_PATH, ClaudeHookConfig.LEGACY_INGRESS_PATH)) {
            val response = ctx.client.post("http://127.0.0.1:${ctx.port}$path?event=Stop")
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "$path reaches the hook handler and applies its token gate",
            )
        }
    }

    @Test
    fun theAuthPageStaysAtRootAndTheTicketApiKeepsItsLegacyAlias() = withServer { ctx ->
        assertEquals(
            HttpStatusCode.OK,
            ctx.client.get("http://127.0.0.1:${ctx.port}$AUTH_PAGE_PATH").status,
            "the login page is still at /auth",
        )
        assertEquals(
            HttpStatusCode.NotFound,
            ctx.client.get("http://127.0.0.1:${ctx.port}$API_PREFIX$AUTH_PAGE_PATH").status,
            "and did not move under the prefix",
        )

        for (path in listOf(AUTH_TICKET_PATH, LEGACY_AUTH_TICKET_PATH)) {
            val ticket = ctx.client.post("http://127.0.0.1:${ctx.port}$path") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, ticket.status, "$path mints a phone ticket")
            assertTrue(
                TRANSPORT_JSON.decodeFromString(TicketResponse.serializer(), ticket.bodyAsText()).ticket.isNotBlank(),
                "$path answers a real ticket",
            )
        }
    }

    @Test
    fun bothWebSocketsUpgradeOnTheirPrefixedPathAndNotOnTheBareOne() = withServer { ctx ->
        val created = ctx.startSession()

        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/events",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            assertTrue(receiveSnapshot().sessions.any { it.id == created.id }, "the prefixed /events socket speaks")
        }
        ctx.client.webSocket(
            "ws://127.0.0.1:${ctx.port}$API_PREFIX/sessions/${created.id}/terminal",
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            ctx.ptyFactory.opened.receive()
            assertContentEquals(seed, receiveBinary(), "the prefixed terminal socket delivers its seed")
        }

        for (bare in listOf("/events", "/sessions/${created.id}/terminal")) {
            val failure = runCatching {
                ctx.client.webSocket(
                    "ws://127.0.0.1:${ctx.port}$bare",
                    request = { header(HttpHeaders.Authorization, "Bearer $token") },
                ) { }
            }
            assertTrue(failure.isFailure, "the bare $bare no longer upgrades")
        }
    }


    private inner class Ctx(
        val port: Int,
        val client: HttpClient,
        val store: FakeEventStore,
        val ptyFactory: WsFakePtyFactory,
        val tmux: FakeTmux,
    ) {
        suspend fun startSession(agent: String = "claude", cwd: String = "/tmp/work"): SessionDto {
            val resp = client.post("http://127.0.0.1:$port$API_PREFIX/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("""{"agent":"$agent","cwd":"$cwd"}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status, "POST /sessions returns 201")
            return TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        }

        suspend fun getSessions(): List<SessionDto> {
            val resp = client.get("http://127.0.0.1:$port$API_PREFIX/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            return TRANSPORT_JSON.decodeFromString(ListSerializer(SessionDto.serializer()), resp.bodyAsText())
        }

        suspend fun getSession(id: String): SessionDto {
            val resp = client.get("http://127.0.0.1:$port$API_PREFIX/sessions/$id") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            return TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        }

        suspend fun getPreferences(): PreferencesDto {
            val resp = client.get("http://127.0.0.1:$port$API_PREFIX/preferences") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            return TRANSPORT_JSON.decodeFromString(PreferencesDto.serializer(), resp.bodyAsText())
        }

        suspend fun putPreferences(body: String) = client.put("http://127.0.0.1:$port$API_PREFIX/preferences") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }

        suspend fun post(path: String) = client.post("http://127.0.0.1:$port$API_PREFIX$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        suspend fun postBody(path: String, body: String) = client.post("http://127.0.0.1:$port$API_PREFIX$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }

        suspend fun upload(
            id: String,
            name: String,
            body: ByteArray,
            encodedName: Boolean = false,
        ) = client.post(
            "http://127.0.0.1:$port$API_PREFIX/sessions/$id/files?name=" +
                if (encodedName) name else encodeQueryComponent(name),
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }
    }

    private fun withServer(
        factory: AgentFactory = agentFactoryOf(
            mapOf("claude" to { cwd: String -> CannedAgentFactory(listOf("cat"), providerId).create("claude", cwd) }),
        ),
        publicUrl: String? = null,
        directoryCompleter: DirectoryCompleter = DirectoryCompleter { _, _ -> emptyList() },
        fileUploader: FileUploader = FileUploader { directory, fileName, body, expectedBytes ->
            saveUploadedFile(directory, fileName, body, expectedBytes)
        },
        probe: VendorStoreProbe = VendorStoreProbe { _, _, _ -> false },
        locator: VendorSessionLocator = VendorSessionLocator { _, _ -> null },
        productionFactory: Boolean = false,
        pushAssembler: (suspend (EventStore, CoroutineScope) -> DaemonPush?)? = null,
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
                probe,
                locator,
                setOf("claude", "codex"),
                now = { 1L },
            )
            val ptyFactory = WsFakePtyFactory()
            val bridgeFactory: (String, CoroutineScope) -> TerminalBridge = { id, scope ->
                TerminalBridge(listOf("fake-attach", id), { seed }, ptyFactory, scope)
            }
            var push: DaemonPush? = null
            val server = if (productionFactory) {
                startDaemonServer(
                    assemblePush = { pushAssembler?.invoke(store, idScope) },
                    createServer = { assembled ->
                        KotgentServer.production(
                            manager,
                            store,
                            store,
                            TokenHolder(token),
                            Tmux(socket = "kotgent-production-factory-test", tmuxPath = "/usr/bin/false"),
                            ptyFactory = ptyFactory,
                            fileUploader = fileUploader,
                            webUiDir = null,
                            publicUrl = publicUrl,
                            pushStore = assembled?.store,
                            vapidPublicKey = assembled?.publicKey,
                            port = 0,
                        )
                    },
                ).also { push = it.push }.server
            } else {
                KotgentServer(
                    sessionManager = manager,
                    store = store,
                    preferencesStore = store,
                    tokens = TokenHolder(token),
                    terminalBridgeFactory = bridgeFactory,
                    directoryCompleter = directoryCompleter,
                    fileUploader = fileUploader,
                    webUiDir = null,
                    publicUrl = publicUrl,
                    port = 0,
                ).start()
            }
            val client = HttpClient(CIO) { install(WebSockets) }
            try {
                block(Ctx(server.port(), client, store, ptyFactory, tmux))
            } finally {
                client.close()
                server.stop()
                idScope.cancel()
                push?.close?.invoke()
            }
        }
    }

    private data class RecordedUpload(
        val directory: String,
        val fileName: String,
        val body: ByteArray,
        val expectedBytes: Long?,
    )

    private class RecordingFileUploader(
        private val result: FileUploadResult? = null,
    ) : FileUploader {
        val uploads = Channel<RecordedUpload>(capacity = Channel.UNLIMITED)

        override suspend fun upload(
            directory: String,
            fileName: String,
            body: ByteReadChannel,
            expectedBytes: Long?,
        ): FileUploadResult {
            val chunks = ArrayList<ByteArray>()
            var total = 0
            val buffer = ByteArray(1_024)
            while (true) {
                val count = body.readAvailable(buffer)
                if (count < 0) break
                if (count == 0) continue
                chunks += buffer.copyOf(count)
                total += count
            }
            val received = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(received, destinationOffset = offset)
                offset += chunk.size
            }
            uploads.send(RecordedUpload(directory, fileName, received, expectedBytes))
            return result ?: FileUploadResult.Stored(received.size.toLong())
        }
    }

    private fun encodeQueryComponent(value: String): String = value.encodeToByteArray().joinToString("") { byte ->
        val unsigned = byte.toInt() and 0xff
        val char = unsigned.toChar()
        if (
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == '~'
        ) {
            char.toString()
        } else {
            "%" + unsigned.toString(16).uppercase().padStart(2, '0')
        }
    }


    private suspend fun DefaultClientWebSocketSession.receiveBinary(): ByteArray {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Binary) return frame.readBytes()
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveUpdate(): SessionUpdateDto {
        return TRANSPORT_JSON.decodeFromString(
            SessionUpdateDto.serializer(),
            receiveTextPayload("session_update"),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveSnapshot(): SessionsSnapshotDto {
        return TRANSPORT_JSON.decodeFromString(
            SessionsSnapshotDto.serializer(),
            receiveTextPayload("sessions_snapshot"),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveRow(): SessionRowDto {
        return TRANSPORT_JSON.decodeFromString(
            SessionRowDto.serializer(),
            receiveTextPayload("session_row"),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveFirstSessionFrameJson(): Pair<String, String> {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val type = runCatching {
                TRANSPORT_JSON.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
            }.getOrNull() ?: continue
            if (type != "preferences_update") return type to text
        }
    }

    private suspend fun DefaultClientWebSocketSession.receivePreferencesUpdate(): PreferencesUpdateDto {
        return TRANSPORT_JSON.decodeFromString(
            PreferencesUpdateDto.serializer(),
            receiveTextPayload("preferences_update"),
        )
    }

    private suspend fun DefaultClientWebSocketSession.receiveTextPayload(type: String): String {
        while (true) {
            val frame = incoming.receive()
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val actualType = runCatching {
                TRANSPORT_JSON.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
            }.getOrNull()
            if (actualType == type) return text
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitUpdate(predicate: (SessionUpdateDto) -> Boolean): SessionUpdateDto {
        while (true) {
            val update = receiveUpdate()
            if (predicate(update)) return update
        }
    }

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

    // Channels provide happens-before across the CIO engine and test threads.
    private class WsFakePty(val command: List<String>) : PtyHandle {
        private val out = Channel<ByteArray>(Channel.UNLIMITED)
        override val output: ReceiveChannel<ByteArray> get() = out

        val writes = Channel<ByteArray>(Channel.UNLIMITED)

        val resizes = Channel<Pair<Int, Int>>(Channel.UNLIMITED)

        fun emit(bytes: ByteArray) { out.trySend(bytes) }
        override fun write(bytes: ByteArray) { writes.trySend(bytes) }
        override fun resize(cols: Int, rows: Int) { resizes.trySend(cols to rows) }
        override fun prepareClose() = Unit
        override fun close() { out.close() }
    }

    private class WsFakePtyFactory : PtyFactory {
        val opened = Channel<WsFakePty>(Channel.UNLIMITED)
        override fun invoke(command: List<String>, env: Map<String, String>): PtyHandle =
            WsFakePty(command).also { opened.trySend(it) }
    }
}
