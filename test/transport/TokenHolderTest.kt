package io.kotgent.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TokenHolder] — the master token as a live provider (plan Task 5).
 *
 * Two layers are covered. The unit half pins the holder's own contract: what [TokenHolder.rotate] returns,
 * what it persists, and in which ORDER (persist before publish, so a failed write leaves the old token in
 * force). The server half proves the point of the whole change — a rotation is visible to a server that is
 * ALREADY running, which is exactly what a captured `val token: String` could not do.
 */
class TokenHolderTest {

    private val initial = "initial-master-token-0123456789abcdef"

    // --- the holder itself --------------------------------------------------------------------------

    @Test
    fun currentReturnsTheInitialTokenUntilItIsRotated() {
        val holder = TokenHolder(initial)
        assertEquals(initial, holder.current())
        assertEquals(initial, holder.current(), "reading does not consume or change anything")
    }

    @Test
    fun rotateMintsANewTokenPublishesItAndDropsTheOldOne() {
        val holder = TokenHolder(initial)

        val rotated = holder.rotate(expected = initial)!!

        assertNotEquals(initial, rotated, "a rotation must actually change the secret")
        assertEquals(rotated, holder.current(), "the returned value is the one now in force")
        assertNotEquals(initial, holder.current(), "the pre-rotation value is gone")
        // 32 bytes of entropy, hex-encoded — the same shape readOrCreateToken mints (both go through
        // generateToken), so nothing downstream can tell a rotated token from a freshly created one.
        assertEquals(SECRET_BYTES * 2, rotated.length, "a rotated token carries the full 32 bytes, hex")
        assertTrue(rotated.all { it in "0123456789abcdef" }, "hex-encoded")
    }

    @Test
    fun rotateIsCalledWithTheNewValueAndNeverRepeatsIt() {
        val persisted = mutableListOf<String>()
        val holder = TokenHolder(initial, persisted::add)

        val first = holder.rotate(expected = initial)!!
        val second = holder.rotate(expected = first)!!

        assertEquals(listOf(first, second), persisted, "persist sees exactly the values that went live")
        assertNotEquals(first, second, "two rotations mint two different secrets")
        assertEquals(second, holder.current())
    }

    @Test
    fun persistRunsBeforeTheNewValueIsPublished() {
        // The callback observes ITS OWN holder mid-rotation: at that moment the OLD token must still be
        // the live one, because the value on disk has to be durable before anything authenticates it.
        val seenDuringPersist = mutableListOf<String>()
        var holder: TokenHolder? = null
        holder = TokenHolder(initial) { seenDuringPersist += holder!!.current() }

        holder.rotate(expected = initial)

        assertEquals(listOf(initial), seenDuringPersist, "persist runs before the publish")
    }

    @Test
    fun aFailingPersistAbortsTheRotationAndLeavesTheOldTokenInForce() {
        val holder = TokenHolder(initial) { error("disk is full") }

        assertFailsWith<IllegalStateException> { holder.rotate(expected = initial) }

        // Publishing a token that was never written would leave the CLI and the hooks — which read the
        // secret from disk — permanently 401ing, and a restart would roll it back anyway.
        assertEquals(initial, holder.current(), "a rotation that could not be persisted did not happen")
    }

    @Test
    fun rotateWithAStaleOrWrongExpectedReturnsNullAndChangesNothing() {
        // The compare-and-swap heart of "rotate = revoke all": rotation proceeds ONLY if the caller presented
        // the token that is still live. This is what makes two concurrent rotates resolve to one winner — the
        // loser presents a token that is no longer current and gets null — instead of both minting and both
        // learning a live value (a holder of the old token racing the operator).
        val persisted = mutableListOf<String>()
        val holder = TokenHolder(initial, persisted::add)

        // A value that is not the live token cannot rotate: the CAS refuses, nothing is persisted, and the
        // token is untouched.
        assertNull(holder.rotate(expected = "not-the-live-token"), "a wrong expected does not rotate")
        assertEquals(initial, holder.current(), "and leaves the token in force")
        assertTrue(persisted.isEmpty(), "a refused rotation persists nothing")

        // The live token rotates once; presenting the NOW-STALE original again loses the CAS (the concurrent
        // loser's path): null, and no second mint.
        val rotated = holder.rotate(expected = initial)
        assertNotNull(rotated, "the live token rotates")
        assertNull(holder.rotate(expected = initial), "the stale token cannot rotate again — someone rotated first")
        assertEquals(rotated, holder.current(), "so the live token is still the one the winning rotation minted")
        assertEquals(listOf(rotated), persisted, "exactly one mint reached disk — the loser never persisted")
    }

    // --- a running server picks the rotation up ------------------------------------------------------

    @Test
    fun afterARotationTheOldBearerIs401AndTheNewOneIs200OnALiveServer() {
        val holder = TokenHolder(initial)
        withPingServer(holder) { port, client ->
            assertEquals(HttpStatusCode.OK, client.ping(port, initial).status, "the initial token works")

            val rotated = holder.rotate(expected = initial)!!

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.ping(port, initial).status,
                "the pre-rotation Bearer is refused by the very next request — no restart involved",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.ping(port, rotated).status,
                "the rotated Bearer is accepted by the same running server",
            )
        }
    }

    @Test
    fun anUnauthenticatedRequestIsStill401AfterARotation() {
        val holder = TokenHolder(initial)
        withPingServer(holder) { port, client ->
            holder.rotate(expected = initial)
            assertEquals(HttpStatusCode.Unauthorized, client.ping(port, token = null).status)
        }
    }

    // --- harness --------------------------------------------------------------------------------------

    /**
     * A minimal server whose only route sits behind [authenticated] reading through [holder]. Deliberately
     * not the full [KotgentServer]: the thing under test is that the gate resolves the token per request,
     * and a bare route makes a failure point at the gate rather than at any of the daemon's fakes.
     */
    private fun withPingServer(
        holder: TokenHolder,
        block: suspend (port: Int, client: HttpClient) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                authenticated(holder::current) {
                    get("/ping") { call.respondText("pong") }
                }
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

    private suspend fun HttpClient.ping(port: Int, token: String?): HttpResponse =
        get("http://127.0.0.1:$port/ping") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }
}
