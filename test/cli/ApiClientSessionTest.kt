package io.kotgent.cli

import io.kotgent.transport.API_PREFIX
import io.kotgent.transport.PatchSessionRequest
import io.kotgent.transport.SessionDto
import io.kotgent.transport.TRANSPORT_JSON
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.patch
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ApiClientSessionTest {

    @Test
    fun renameSendsAPatchToTheSessionAndReadsTheRenamedRowBack() = withStub { stub, api ->
        assertEquals("the rewrite", api.renameSession(SESSION, "the rewrite").name)
        val seen = stub.requests.receive()
        assertEquals("PATCH", seen.method)
        assertEquals("$API_PREFIX/sessions/$SESSION", seen.path)
        assertEquals("Bearer secret", seen.auth)
        val body = TRANSPORT_JSON.decodeFromString(PatchSessionRequest.serializer(), seen.body)
        assertEquals(PatchSessionRequest(name = "the rewrite"), body)
    }

    @Test
    fun anEmptyNameIsSentAsAnEmptyStringRatherThanOmitted() = withStub { stub, api ->
        val _ = api.renameSession(SESSION, "")
        val body = TRANSPORT_JSON.decodeFromString(PatchSessionRequest.serializer(), stub.requests.receive().body)
        assertEquals(
            "",
            body.name,
            "an absent name means 'nothing to change' to the route; the reset must stay distinguishable",
        )
    }

    @Test
    fun aMalformedIdStillReachesTheDaemonForItsCanonicalRefusal() = withStub { stub, api ->
        stub.status = HttpStatusCode.BadRequest
        val _ = assertFailsWith<ApiException> { api.renameSession("not an id", "x") }
        assertEquals("$API_PREFIX/sessions/not%20an%20id", stub.requests.receive().path)
    }

    @Test
    fun anUnknownSessionSurfacesItsStatus() = withStub { stub, api ->
        stub.status = HttpStatusCode.NotFound
        val failure = assertFailsWith<ApiException> { api.renameSession(SESSION, "x") }
        assertEquals(404, failure.status, "the CLI turns this into 'no such session'")
    }

    @Test
    fun aRefusedNameThrowsRatherThanReportingASuccessfulRename() = withStub { stub, api ->
        stub.status = HttpStatusCode.BadRequest
        val failure = assertFailsWith<ApiException> { api.renameSession(SESSION, "x".repeat(500)) }
        assertEquals(400, failure.status)
    }

    @Test
    fun renamingWithoutATokenNeverReachesTheNetwork() = withStub(token = null) { stub, api ->
        assertFailsWith<MissingTokenException> { api.renameSession(SESSION, "x") }
        assertNull(stub.requests.tryReceive().getOrNull())
    }

    private data class Recorded(
        val method: String,
        val path: String,
        val auth: String?,
        val body: String,
    )

    private class Stub {
        val requests = Channel<Recorded>(Channel.UNLIMITED)

        var status: HttpStatusCode = HttpStatusCode.OK

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                patch("$API_PREFIX/sessions/{id}") {
                    val body = call.receiveText()
                    requests.trySend(
                        Recorded(
                            method = "PATCH",
                            path = call.request.path(),
                            auth = call.request.headers[HttpHeaders.Authorization],
                            body = body,
                        ),
                    )
                    if (status != HttpStatusCode.OK) {
                        call.respondText("refused", ContentType.Text.Plain, status)
                        return@patch
                    }
                    val name = TRANSPORT_JSON.decodeFromString(PatchSessionRequest.serializer(), body).name
                    call.respondText(
                        TRANSPORT_JSON.encodeToString(SessionDto.serializer(), SESSION_DTO.copy(name = name ?: "")),
                        ContentType.Application.Json,
                    )
                }
            }
        }
    }

    private fun withStub(
        token: String? = "secret",
        block: suspend (Stub, ApiClient) -> Unit,
    ) = runBlocking {
        withTimeout(30.seconds) {
            val stub = Stub()
            stub.server.start(wait = false)
            val port = stub.server.engine.resolvedConnectors().first().port
            val api = ApiClient(baseUrl = "http://127.0.0.1:$port", token = token)
            try {
                block(stub, api)
            } finally {
                api.close()
                stub.server.stop(100, 500)
            }
        }
    }

    private companion object {
        const val SESSION: String = "abc12345"

        val SESSION_DTO = SessionDto(
            id = SESSION,
            name = "kotgent-$SESSION",
            tags = emptyList(),
            agent = "claude",
            providerSessionId = null,
            state = "running",
            needsAttention = false,
            alive = true,
            cwd = "/tmp/work",
            tmuxSession = "kt-$SESSION",
            paneId = "%1",
            lastSeq = 1,
            readCursor = 0,
            unread = 0,
            createdAt = 1,
            updatedAt = 2,
            rev = 3,
        )
    }
}
