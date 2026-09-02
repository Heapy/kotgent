package io.kotgent.transport

import io.kotgent.adapter.AgentAdapter
import io.kotgent.adapter.LaunchMode
import io.kotgent.adapter.LaunchSpec
import io.kotgent.core.AgentEvent
import io.kotgent.core.MAX_SESSION_NAME_LENGTH
import io.kotgent.core.ProviderSessionId
import io.kotgent.core.SessionId
import io.kotgent.core.SessionMeta
import io.kotgent.core.SessionState
import io.kotgent.daemon.FakeTmux
import io.kotgent.daemon.PaneRegistry
import io.kotgent.daemon.ProviderIdCapture
import io.kotgent.daemon.SessionManager
import io.kotgent.daemon.VendorSessionLocator
import io.kotgent.daemon.VendorStoreProbe
import io.kotgent.daemon.agentFactoryOf
import io.kotgent.store.FakeEventStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

class SessionRenameRoutesTest {

    private val token = "session-rename-routes-master-token-0123"
    private val seeded = SessionId("s-rename")
    private val seededAt = 1_700_000_000_000L

    @Test
    fun renamingAnswersTheNewNameAndAHigherRevision() = withRenameServer { env ->
        val before = assertNotNull(env.store.getSession(seeded))

        val resp = env.rename(seeded.value, """{"name":"ship the parser"}""")

        assertEquals(HttpStatusCode.OK, resp.status, "answered ${resp.bodyAsText()}")
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertEquals("ship the parser", dto.name)
        assertTrue(dto.rev > before.rev, "a rename advances the revision: ${dto.rev} vs ${before.rev}")
        assertEquals("ship the parser", assertNotNull(env.store.getSession(seeded)).name)
    }

    @Test
    fun renamingLeavesUpdatedAtAlone() = withRenameServer { env ->
        val resp = env.rename(seeded.value, """{"name":"later"}""")

        assertEquals(HttpStatusCode.OK, resp.status, "answered ${resp.bodyAsText()}")
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertEquals(
            seededAt,
            dto.updatedAt,
            "a rename is not session activity and must not reorder the done-list",
        )
    }

    @Test
    fun anEmptyNameIsAcceptedAndMeansTheAutomaticLabel() = withRenameServer { env ->
        val resp = env.rename(seeded.value, """{"name":""}""")

        assertEquals(HttpStatusCode.OK, resp.status, "answered ${resp.bodyAsText()}")
        val dto = TRANSPORT_JSON.decodeFromString(SessionDto.serializer(), resp.bodyAsText())
        assertEquals("", dto.name)
    }

    // `kotgent list` renders whatever GET /sessions answers, so the renamed row has to reach that list and
    // not only the PATCH response the renaming client already holds.
    @Test
    fun theSessionListShowsTheRenamedNameAndTheClearedOneFallsBackToTmux() = withRenameServer { env ->
        assertEquals(HttpStatusCode.OK, env.rename(seeded.value, """{"name":"ship the parser"}""").status)

        val listed = assertNotNull(env.sessions().firstOrNull { it.id == seeded.value })
        assertEquals("ship the parser", listed.name)
        assertEquals(seededAt, listed.updatedAt, "the list orders by updatedAt, which a rename does not touch")

        assertEquals(HttpStatusCode.OK, env.rename(seeded.value, """{"name":""}""").status)

        val cleared = assertNotNull(env.sessions().firstOrNull { it.id == seeded.value })
        assertEquals("", cleared.name)
        assertEquals(
            "kotgent-seeded",
            cleared.tmuxSession,
            "a cleared name leaves the automatic label every client falls back to",
        )
    }

    @Test
    fun renamingAnUnknownSessionIs404() = withRenameServer { env ->
        val resp = env.rename("s-absent", """{"name":"whatever"}""")

        assertEquals(HttpStatusCode.NotFound, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(resp.bodyAsText().contains("s-absent"), "the refusal names the session it could not find")
    }

    @Test
    fun aMalformedSessionIdIs400() = withRenameServer { env ->
        val resp = env.rename("%20", """{"name":"whatever"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(resp.bodyAsText().contains("malformed"), "the refusal says what was wrong")
    }

    @Test
    fun aBodyCarryingNoFieldIs400AndNamesWhatAPatchMayCarry() = withRenameServer { env ->
        val resp = env.rename(seeded.value, "{}")

        assertEquals(HttpStatusCode.BadRequest, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(resp.bodyAsText().contains("name"), "the refusal names the field: ${resp.bodyAsText()}")
        assertEquals("seeded", assertNotNull(env.store.getSession(seeded)).name, "nothing changed")
    }

    @Test
    fun anOverLongNameIs400AndChangesNothing() = withRenameServer { env ->
        val tooLong = "n".repeat(MAX_SESSION_NAME_LENGTH + 1)

        val resp = env.rename(seeded.value, """{"name":"$tooLong"}""")

        assertEquals(HttpStatusCode.BadRequest, resp.status, "answered ${resp.bodyAsText()}")
        assertTrue(
            resp.bodyAsText().contains(MAX_SESSION_NAME_LENGTH.toString()),
            "the refusal names the bound: ${resp.bodyAsText()}",
        )
        assertEquals(
            "seeded",
            assertNotNull(env.store.getSession(seeded)).name,
            "rename shares the bound with start, so neither path can write a name the other would refuse",
        )
    }

    private inner class Env(
        val port: Int,
        val client: HttpClient,
        val store: FakeEventStore,
    ) {
        suspend fun sessions(): List<SessionDto> {
            val resp = client.get("http://127.0.0.1:$port$API_PREFIX/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, resp.status, "answered ${resp.bodyAsText()}")
            return TRANSPORT_JSON.decodeFromString(
                ListSerializer(SessionDto.serializer()),
                resp.bodyAsText(),
            )
        }

        suspend fun rename(id: String, body: String): HttpResponse =
            client.patch("http://127.0.0.1:$port$API_PREFIX/sessions/$id") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
    }

    private fun withRenameServer(block: suspend (Env) -> Unit) = runBlocking {
        withTimeout(60.seconds) {
            val store = FakeEventStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager = SessionManager(
                FakeTmux(),
                store,
                PaneRegistry(),
                agentFactoryOf(mapOf("claude" to { cwd: String -> CannedAdapter(cwd) })),
                ProviderIdCapture(store, scope),
                VendorStoreProbe { _, _, _ -> false },
                VendorSessionLocator { _, _ -> null },
                setOf("claude"),
                now = { seededAt },
            )
            store.upsertSession(
                SessionMeta(
                    id = seeded,
                    name = "seeded",
                    agent = "claude",
                    cwd = "/tmp/work",
                    tmuxSession = "kotgent-seeded",
                    state = SessionState.running,
                    createdAt = seededAt,
                    updatedAt = seededAt,
                ),
            )
            val tokens = TokenHolder(token)
            val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
                routing {
                    val _ = authenticated(tokens::current) {
                        route(API_PREFIX) {
                            controlRoutes(manager, store, { _, _ -> true }, "test-version", null, TRANSPORT_JSON, null)
                        }
                    }
                }
            }
            server.start(wait = false)
            val client = HttpClient(CIO)
            try {
                block(Env(server.engine.resolvedConnectors().first().port, client, store))
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
                scope.cancel()
            }
        }
    }

    private class CannedAdapter(private val cwd: String) : AgentAdapter {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun buildLaunchSpec(mode: LaunchMode): LaunchSpec = when (mode) {
            is LaunchMode.New -> LaunchSpec(
                listOf("claude"),
                emptyMap(),
                cwd,
                ProviderSessionId("00000000-0000-4000-8000-000000000000"),
            )
            is LaunchMode.Resume -> LaunchSpec(listOf("claude", "--resume"), emptyMap(), cwd, null)
        }
    }
}
