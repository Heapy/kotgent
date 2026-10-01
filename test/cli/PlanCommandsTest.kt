package io.kotgent.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
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
    @Test
    fun executionWaitKeepsItsCursorAndAnInterruptedReplyCannotAcknowledgeEvents() = runBlocking {
        val out = mutableListOf<String>()
        val command = PlanWait("local:1", "t_1", 540, 9, true, "worker")
        assertEquals(0, runPlanCommand(command, { throw IOException("Connection reset by peer") }, { out += it }, {}))
        assertEquals(listOf("{\"event\":\"pending\"}"), out)
        assertEquals(3, runPlanCommand(command, { throw ApiException(410, "deleted") }, {}, {}))
        val engine = MockEngine { request ->
            assertEquals("/api/v1/tasks/local:1/plan/tasks/t_1/wait", request.url.encodedPath)
            assertEquals("9", request.url.parameters["after"])
            assertEquals("worker", request.url.parameters["sessionId"])
            assertEquals(555_000L, request.getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis)
            respond("{}")
        }
        ApiClient(baseUrl = "http://fixture", token = "token", client = HttpClient(engine) { install(HttpTimeout) }).use {
            assertEquals("{}", it.planRequest(command))
        }
    }

    @Test
    fun settingsUsePatchAndFindingWritesRetainTheExactExpectedRevision() = runBlocking {
        val commands = listOf(
            PlanMutation("local:1", "/settings", "{\"mode\":\"supervised\"}", patch = true),
            PlanMutation("local:1", "/findings/f_1/verify", "{\"rev\":4,\"verifier\":{}}", session = "reviewer"),
        )
        var index = 0
        val engine = MockEngine { request ->
            val command = commands[index++]
            assertEquals(if (command.patch) HttpMethod.Patch else HttpMethod.Post, request.method)
            assertEquals(command.body, (request.body as TextContent).text)
            assertEquals(command.session, request.url.parameters["sessionId"])
            respond("{}")
        }
        ApiClient(baseUrl = "http://fixture", token = "token", client = HttpClient(engine)).use { api ->
            for (command in commands) assertEquals("{}", api.planRequest(command))
        }
        assertEquals(2, index)
    }

}
