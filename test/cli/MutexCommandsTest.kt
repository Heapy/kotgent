package io.kotgent.cli

import io.kotgent.proc.ChildProcessException
import io.kotgent.transport.MutexAcquireResponse
import io.kotgent.transport.MutexHolderDto
import io.kotgent.transport.MutexDto
import io.kotgent.transport.MutexListingDto
import io.kotgent.transport.MutexReleaseResponse
import io.kotgent.transport.MutexWaiterDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.PosixException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutexCommandsTest {
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { block() } }

    private val acquired = MutexAcquireResponse("acquired", "kotlin-build", token = "tok1", acquiredAt = 5)

    private val pending = MutexAcquireResponse("pending", "kotlin-build", ticket = "tkt1", position = 2)

    private suspend fun acquire(
        command: MutexAcquire = MutexAcquire("kotlin-build", null, 90, false, null),
        result: suspend () -> MutexAcquireResponse,
    ) = runMutexAcquireCommand(command, result, { out += it }, { err += it })

    @Test
    fun acquireLeadsWithTheOutcomeOnItsFirstLine() = test {
        assertEquals(0, acquire { acquired })
        assertEquals("acquired: tok1", out.single().lines().first())
        assertTrue(out.single().contains("kotgent mutex release tok1"))
        out.clear()

        assertEquals(0, acquire { pending })
        val lines = out.single().lines()
        assertEquals("pending: tkt1", lines.first())
        assertTrue("position: 2" in lines)
        assertTrue("next: kotgent mutex acquire kotlin-build --ticket tkt1" in lines)
    }

    @Test
    fun jsonOutputIsTheDaemonsAnswer() = test {
        assertEquals(0, acquire(MutexAcquire("kotlin-build", null, 90, json = true, session = null)) { pending })
        assertEquals(
            """{"status":"pending","key":"kotlin-build","token":null,"acquiredAt":null,"ticket":"tkt1","position":2}""",
            out.single(),
        )
    }

    @Test
    fun aDroppedWaitIsPendingOnTheSameTicket() = test {
        val command = MutexAcquire("kotlin-build", "tkt1", 90, false, null)
        assertEquals(0, acquire(command) { throw IOException("Connection reset by peer") })
        assertEquals("pending: tkt1", out.single().lines().first())
        assertTrue(err.isEmpty())
        out.clear()

        assertEquals(0, acquire { throw PosixException.ConnectionResetException("reset") })
        assertEquals("pending: ", out.single().lines().first())
        assertTrue(out.single().lines().contains("next: kotgent mutex acquire kotlin-build"))
    }

    @Test
    fun anUnreachableDaemonIsAFailureNotAPendingWait() = test {
        val refused = IOException("Failed to connect", PosixException.ConnectionRefusedException("refused"))
        assertEquals(MUTEX_EXIT_DAEMON, acquire { throw refused })
        assertTrue(out.isEmpty())
        assertFalse(isInterruptedWait(refused))
        assertFalse(isInterruptedWait(IllegalStateException("not json")))
        assertTrue(isInterruptedWait(IOException("closed")))
    }

    @Test
    fun conflictsExitThreeAndOtherDaemonErrorsExitOne() = test {
        val gone = ApiException(410, "wait ticket 'tkt1' is no longer queued")
        assertEquals(MUTEX_EXIT_CONFLICT, acquire { throw gone })
        assertTrue(err.single().contains("no longer queued"))
        assertEquals(MUTEX_EXIT_CONFLICT, acquire { throw ApiException(409, "belongs to another session") })
        assertEquals(MUTEX_EXIT_DAEMON, acquire { throw ApiException(400, "no calling session") })
        assertTrue(out.isEmpty())
    }

    @Test
    fun releaseReportsWhetherAHoldingWasReleased() = test {
        val command = MutexRelease("tok1", false, null)
        suspend fun release(response: MutexReleaseResponse) =
            runMutexReleaseCommand(command, { response }, { out += it }, { err += it })
        assertEquals(0, release(MutexReleaseResponse(true, "kotlin-build")))
        assertEquals(0, release(MutexReleaseResponse(false)))
        assertEquals(listOf("released: kotlin-build", "not-held: tok1"), out)
    }

    @Test
    fun listShowsHoldersWithTheirAgeAndWaiters() = test {
        val listing = MutexListingDto(
            rev = 4,
            mutexes = listOf(
                MutexDto(
                    "kotlin-build",
                    MutexHolderDto("alice", acquiredAt = 1_000),
                    listOf(MutexWaiterDto("bob", 2_000, granted = false)),
                ),
            ),
            serverNow = 1_000 + 192_000,
        )
        assertEquals(0, runMutexListCommand(MutexList(false), { listing }, { out += it }, { err += it }))
        assertEquals(listOf("kotlin-build  held by alice for 3m12s  · 1 waiting"), out)
    }

    @Test
    fun runWaitsExecutesAndReleasesReturningTheCommandsStatus() = test {
        val tickets = mutableListOf<String?>()
        val answers = ArrayDeque(listOf(pending, acquired))
        val released = mutableListOf<String>()
        val ran = mutableListOf<List<String>>()
        val status = runMutexRunCommand(
            MutexRun("kotlin-build", listOf("make", "all"), null),
            acquire = { ticket -> tickets += ticket; answers.removeFirst() },
            release = { token -> released += token; MutexReleaseResponse(true, "kotlin-build") },
            execute = { argv -> ran += argv; 7 },
            stderr = { err += it },
        )
        assertEquals(7, status)
        assertEquals(listOf(null, "tkt1"), tickets)
        assertEquals(listOf(listOf("make", "all")), ran)
        assertEquals(listOf("tok1"), released)
        assertTrue(err.single().contains("waiting for mutex 'kotlin-build' (position 2)"))
    }

    @Test
    fun runRequeuesAfterALapsedTicketAndRetriesADroppedWait() = test {
        val tickets = mutableListOf<String?>()
        val answers = ArrayDeque<suspend () -> MutexAcquireResponse>(
            listOf(
                { pending },
                { throw IOException("reset") },
                { throw ApiException(410, "gone") },
                { acquired },
            ),
        )
        val status = runMutexRunCommand(
            MutexRun("kotlin-build", listOf("true"), null),
            acquire = { ticket -> tickets += ticket; answers.removeFirst()() },
            release = { MutexReleaseResponse(true, "kotlin-build") },
            execute = { 0 },
            stderr = { err += it },
            retryDelayMillis = 1,
        )
        assertEquals(0, status)
        assertEquals(listOf(null, "tkt1", "tkt1", null), tickets)
    }

    @Test
    fun aCommandThatCannotStartStillReleases() = test {
        val released = mutableListOf<String>()
        val status = runMutexRunCommand(
            MutexRun("kotlin-build", listOf("no-such-tool"), null),
            acquire = { acquired },
            release = { token -> released += token; MutexReleaseResponse(true, "kotlin-build") },
            execute = { throw ChildProcessException("cannot run 'no-such-tool': No such file or directory") },
            stderr = { err += it },
        )
        assertEquals(MUTEX_EXIT_NOT_RUN, status)
        assertEquals(listOf("tok1"), released)
    }

    @Test
    fun aFailedReleaseIsReportedButKeepsTheCommandsStatus() = test {
        val status = runMutexRunCommand(
            MutexRun("kotlin-build", listOf("true"), null),
            acquire = { acquired },
            release = { throw IOException("daemon went away") },
            execute = { 3 },
            stderr = { err += it },
        )
        assertEquals(3, status)
        assertTrue(err.single().contains("stays held"))
    }

    @Test
    fun theLongPollRequestOutlivesTheServerWait() = test {
        var seen: HttpRequestData? = null
        val engine = MockEngine { request ->
            seen = request
            respond(
                """{"status":"pending","key":"k","ticket":"tkt1","position":1}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        ApiClient(baseUrl = "http://daemon", token = "secret", client = client).use { api ->
            val _ = api.acquireMutex("k", "tkt1", 90, sessionId = null)
        }
        val request = assertNotNull(seen)
        assertEquals("/api/v1/mutexes/k/acquire", request.url.encodedPath)
        assertEquals("90", request.url.parameters["wait"])
        assertEquals("tkt1", request.url.parameters["ticket"])
        val timeout = assertNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
        assertEquals(105_000L, timeout.requestTimeoutMillis)
        assertTrue(assertNotNull(timeout.requestTimeoutMillis) > 90_000L)
        assertEquals(105_000L, longPollTimeoutMillis(90))
    }
}
