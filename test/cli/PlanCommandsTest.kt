package io.kotgent.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanCommandsTest {
    @Test
    fun connectionLossPrintsPendingButHttpErrorsKeepTheirExitCodes() = runBlocking {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val command = PlanReviewCommand("local:1", 90, false, null)
        suspend fun run(failure: Exception) = runPlanCommand(command, { throw failure }, { out += it }, { err += it })
        assertEquals(0, run(IOException("Connection reset by peer")))
        assertEquals(listOf("verdict: pending"), out)
        assertTrue(err.isEmpty())
        assertEquals(3, run(ApiException(409, "changed")))
        assertEquals(1, run(ApiException(403, "operator action")))
        assertEquals(1, runPlanCommand(PlanShow("local:1", null), { throw IOException("closed") }, { out += it }, { err += it }))
    }

    @Test
    fun reviewRequestOutlivesTheServerWaitAndKeepsExplicitIdentity() = runBlocking {
        var called = false
        val engine = MockEngine { request ->
            called = true
            assertEquals("/api/v1/tasks/local:1/plan/review/wait", request.url.encodedPath)
            assertEquals("540", request.url.parameters["wait"])
            assertEquals("alice", request.url.parameters["sessionId"])
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            assertEquals(555_000L, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        ApiClient(baseUrl = "http://fixture", token = "token", client = client).use {
            assertEquals("{}", it.planRequest(PlanReviewCommand("local:1", 540, true, "alice")))
        }
        assertTrue(called)
    }
}
