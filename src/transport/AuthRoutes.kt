package io.kotgent.transport

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The login surface (plan Task 8): how a browser that holds NO credential ends up holding a session
 * cookie, without the master token ever appearing in a URL.
 *
 * ```
 *   kotgent web                 POST /auth/ticket   (Bearer, loopback-only)  → {ticket, localUrl, publicUrl}
 *   normal browser opens /auth  GET  /auth (no credential) → operator types the still-unused code
 *   web --print link opens URL  GET  /auth#ticket=… (fragment stays client-side)
 *   the page's script           POST /auth/exchange (code/ticket IS the credential) → Set-Cookie, then "/"
 * ```
 *
 * The same page has a second entrance with no link at all: an installed PWA launches at `start_url` with
 * its own empty cookie jar, so the SPA routes its first `401` to `GET /auth`, where the operator TYPES the
 * code into a form that posts the very same `/auth/exchange` (see [AUTH_PAGE_HTML]).
 *
 * ## Why the ticket rides in the FRAGMENT
 * `GET /auth` carries no ticket at all: a fragment is never put on the wire. So the server cannot see the
 * ticket on the page load, which means nothing that merely FOLLOWS the link can spend it — a mail scanner,
 * a chat unfurler, a browser prefetcher, corporate antivirus. It also never lands in the tunnel's request
 * log, and it survives Cloudflare Access's SSO redirect chain for free, because carrying the fragment
 * across a redirect is the browser's job, not the server's.
 *
 * ## Why `/auth/exchange` is not inside [authenticated]
 * The ticket is the credential being spent, so there is nothing else to authenticate with — a browser
 * arriving here has no cookie yet by definition. What still applies is the BROWSER half of the rule
 * ([authorizeTicketExchange]): the `Host` allowlist, and an `Origin` that must be present and must match.
 * That is what stops a page on a sibling subdomain from POSTing a ticket it phished and quietly planting a
 * session cookie in the victim's browser.
 *
 * ## Why the page is a string constant
 * It is served BEFORE the operator has any credential and must work when `webUiDir` is `null` (both test
 * harnesses) or points at a directory that is not there. Making it a constant means the login page cannot
 * be broken by the static-serving configuration — the one page that has to work when nothing else does.
 */

/** The login page a ticket link points at — `…/auth#ticket=<value>`. Unauthenticated, takes no parameters. */
const val AUTH_PAGE_PATH: String = "/auth"

/** Where the CLI mints a one-shot login ticket. `Bearer` + loopback only. */
const val AUTH_TICKET_PATH: String = "/auth/ticket"

/** Where the login page spends a ticket for the session cookie. No `Bearer`; the ticket is the credential. */
const val AUTH_EXCHANGE_PATH: String = "/auth/exchange"

/**
 * Where `kotgent token rotate` re-mints the master token. `Bearer` + loopback only. Rotation is "revoke all
 * browser credentials": every session cookie is signed with the master token, so changing it stops every
 * cookie from verifying at once. An outstanding, unredeemed sign-in ticket is bound to the token it was minted
 * under, so any cookie it could still mint is signed with the OLD token and dead on arrival — pending
 * credentials are covered too, by construction rather than by clearing a ticket map.
 */
const val AUTH_ROTATE_PATH: String = "/auth/rotate"

/**
 * The body of `POST /auth/ticket`. Both URLs carry the ticket in their fragment for an intentional
 * credentialed-link hand-off (`kotgent web --print`). Normal `kotgent web` and the phone QR strip the
 * fragment and present the same credential as a code instead, leaving it unused until the form submits.
 *
 * [publicUrl] is `null` when no public origin is configured — that is the signal for the UI to explain how
 * to set one up instead of drawing a QR code that could only ever be scanned by this machine.
 */
@Serializable
data class TicketResponse(
    val ticket: String,
    val localUrl: String,
    val publicUrl: String? = null,
    val expiresAt: Long,
)

/** The body of `POST /auth/exchange`: the ticket read out of the page's fragment. */
@Serializable
data class ExchangeRequest(val ticket: String)

/** The body of `POST /auth/rotate` — the freshly minted master token, echoed back for the CLI to print. */
@Serializable
data class RotateResponse(val token: String)

/** The bounded outcome of reading an unauthenticated `/auth/exchange` request body. */
sealed interface AuthExchangeBodyRead {
    data class Received(val text: String) : AuthExchangeBodyRead
    data object Incomplete : AuthExchangeBodyRead
    data object TooLarge : AuthExchangeBodyRead
    data object TimedOut : AuthExchangeBodyRead
}

/**
 * Read one exchange body without allowing an unauthenticated peer to allocate or hold resources without
 * bound.
 *
 * Reading [maxBytes] plus one distinguishes an exactly-full valid body from an oversized chunked body
 * without ever buffering the rest. When [expectedBytes] comes from `Content-Length`, EOF before that many
 * bytes is [AuthExchangeBodyRead.Incomplete], never a complete body. [withTimeoutOrNull] converts only this
 * function's own timeout to [AuthExchangeBodyRead.TimedOut]; cancellation of the owning request still
 * propagates.
 */
suspend fun readAuthExchangeBody(
    channel: ByteReadChannel,
    expectedBytes: Long? = null,
    maxBytes: Int = AUTH_EXCHANGE_MAX_BODY_BYTES,
    timeoutMillis: Long = AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS,
): AuthExchangeBodyRead {
    require(expectedBytes == null || expectedBytes >= 0) {
        "the expected auth exchange body length must be non-negative, got $expectedBytes"
    }
    require(maxBytes > 0) { "the auth exchange body limit must be positive, got $maxBytes" }
    require(maxBytes < Int.MAX_VALUE) { "the auth exchange body limit is too large to probe for overflow" }
    require(timeoutMillis > 0) { "the auth exchange body timeout must be positive, got $timeoutMillis ms" }

    return withTimeoutOrNull(timeoutMillis) {
        val bytes = ByteArray(maxBytes + 1)
        var size = 0
        while (size < bytes.size) {
            val read = channel.readAvailable(bytes, size, bytes.size - size)
            if (read < 0) break
            size += read
        }
        when {
            size > maxBytes -> AuthExchangeBodyRead.TooLarge
            expectedBytes != null && size.toLong() < expectedBytes -> AuthExchangeBodyRead.Incomplete
            else -> AuthExchangeBodyRead.Received(bytes.decodeToString(endIndex = size))
        }
    } ?: AuthExchangeBodyRead.TimedOut
}

/**
 * Mount the four login routes on [this] route.
 *
 * @param tokens the live master token; [TokenHolder.rotate] is what `/auth/rotate` calls, and
 *   [TokenHolder.current] is both the `Bearer` compared against and the token a freshly issued ticket is bound
 *   to (the exchange signs the cookie with the ticket's bound token, so validity flows from the master token).
 * @param tickets the one-shot ticket store — [TicketStore.issue] captures the current token onto the ticket
 *   and [TicketStore.redeem] hands it back, so a rotation between mint and redeem yields a dead cookie without
 *   any cross-lock ticket bookkeeping on `/auth/rotate`.
 * @param publicUrl the configured public origin, or `null` for loopback-only. Decides the `publicUrl` in a
 *   ticket response, the `Origin` allowlist on the exchange, and whether the cookie is `Secure`.
 * @param now epoch millis, stamped into the issued cookie (injected so tests are deterministic).
 * @param exchangeLimit the guessing budget on `/auth/exchange`. A PARAMETER with a default, not something
 *   the handler makes: the default is evaluated once, here, when the routes are mounted, so the whole
 *   daemon shares one counter. Constructing it per call would reset it on every request — a limiter that
 *   silently does nothing, which is the one failure mode a unit test of [ExchangeRateLimit] cannot see.
 *   It is what pays for the login code carrying 40 bits instead of 256 (see [TicketStore]).
 * @param afterExchangeAdmitted a lifecycle seam invoked after reserving capacity and inside the
 *   non-cancellable-cleanup `try/finally`; production leaves it empty, while tests use it to abort an
 *   admitted handler deterministically and prove the reservation is released.
 * @param exchangeBodyTimeoutMillis maximum admitted body-intake time. Production uses the fixed public
 *   limit; tests may shorten it to prove that a raw stalled socket is actually closed.
 */
fun Route.authRoutes(
    tokens: TokenHolder,
    tickets: TicketStore,
    publicUrl: String? = null,
    json: Json = TRANSPORT_JSON,
    now: () -> Long = ::authEpochMillis,
    exchangeLimit: ExchangeRateLimit = ExchangeRateLimit(),
    afterExchangeAdmitted: suspend () -> Unit = {},
    exchangeBodyTimeoutMillis: Long = AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS,
) {
    // Ticket issuance and rotation: an authenticated credential AND a loopback Host. Nesting the two gates
    // is the whole statement — the tunnel publishes the browser surface, never the surface that mints
    // credentials. The two routes differ in WHICH credential they accept: ticket issuance takes either the
    // master Bearer OR the browser's session cookie (the PhoneDialog mints a sign-in link from a logged-in
    // browser, and a ticket only yields another browser cookie — no escalation). Rotation is Bearer-ONLY —
    // it returns the NEW master token in its body, so a cookie must never reach it (guard in the handler).
    authenticated(tokens::current, publicUrl) {
        loopbackOnly {
            post(AUTH_TICKET_PATH) {
                // Take ONE token snapshot and use it for BOTH a credential re-check and the bind, so the ticket
                // is bound to the token the caller ACTUALLY PROVED — atomically on a single value. The outer
                // [authenticated] gate already validated a credential, but against tokens.current() as of the
                // GATE; reading tokens.current() again here would reopen a window in which a rotation between
                // the gate and this read lets a soon-to-be-revoked credential bind its ticket to the NEW token
                // and launder itself across the rotation. With one snapshot: a rotation BEFORE it makes the old
                // credential fail this re-check → 401 (correct — it is already revoked); a rotation AFTER it
                // binds to the old token, whose exchanged cookie is dead under the new token by construction
                // (see [TicketStore]). Either way no laundering, with no dependency on the threading model.
                //
                // This duplicates a little of the gate's Bearer-or-cookie logic on purpose — that is the price
                // of making gate-and-bind atomic on one snapshot; a present Bearer must match (no fall-through
                // to the cookie), so a stray non-live Bearer cannot mint a ticket.
                val token = tokens.current()
                val presented = call.presentedToken()
                val proven = if (presented != null) {
                    constantTimeEquals(presented, token)              // Bearer path (CLI, attach)
                } else {
                    verifySessionCookie(token, call.sessionCookie())  // cookie path (the PhoneDialog)
                }
                if (!proven) {
                    call.respondText(refusalBody(HttpStatusCode.Unauthorized), status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val ticket = tickets.issue(token)
                // The authority this request actually arrived on, so an ephemeral port (tests) or a
                // non-default one (`--port`) is reflected without the transport having to know it.
                // [loopbackOnly] already refused a request with no/foreign Host, so this is loopback.
                val authority = call.request.headers[HttpHeaders.Host].orEmpty()
                val body = TicketResponse(
                    ticket = ticket.value,
                    localUrl = ticketUrl("http://$authority", ticket.value),
                    publicUrl = publicUrl?.let { ticketUrl(it, ticket.value) },
                    expiresAt = ticket.expiresAt,
                )
                call.respondText(
                    json.encodeToString(TicketResponse.serializer(), body),
                    ContentType.Application.Json,
                )
            }

            post(AUTH_ROTATE_PATH) {
                // Bearer-ONLY, unlike ticket issuance above. [authenticated] admits EITHER the master
                // Bearer OR the browser's session cookie, but rotation echoes the NEW master token back in
                // its body — so a cookie-only caller reaching here (an XSS in the SPA firing a same-origin
                // POST, whose Host is loopback and whose Origin is allowed) would escalate a browser-scoped
                // credential into the machine key, collapsing the two-key model. [presentedToken] is
                // Authorization-only (post-Task-9), so a cookie carries no Bearer and lands here as null → 403.
                val presented = call.presentedToken()
                if (presented == null) {
                    call.respondText(refusalBody(HttpStatusCode.Forbidden), status = HttpStatusCode.Forbidden)
                    return@post
                }
                // COMPARE-AND-SWAP under TokenHolder's write lock, replacing a separate bearer pre-check +
                // rotate: those were NOT atomic, so two concurrent rotates presenting the same old token both
                // passed the check and both minted, leaving the live token the last writer's while BOTH callers
                // learned a value — a holder of the old token could race the operator and end up knowing the
                // final live token. rotate(expected) proceeds only if `presented` is STILL the live token, so
                // exactly one of two concurrent rotates wins; the loser gets `null` here → 409 (its token is
                // stale) instead of silently succeeding into a split-brain. The CAS subsumes the old
                // constant-time bearer check (rotate returns null unless `presented` matched the live token).
                //
                // Persist-then-publish lives in [TokenHolder.rotate]; a failing persist throws here and becomes
                // a 500 with the OLD token still in force, which is the safe end of that failure.
                //
                // Rotation revokes ALL browser credentials by construction, with no ticket bookkeeping here:
                // every session cookie is signed with the master token, and it just changed, so every cookie
                // ever issued stops verifying at once. An outstanding, unredeemed sign-in ticket is bound to
                // the token it was MINTED under (see [TicketStore]); it may still be redeemed for its TTL, but
                // the cookie the exchange then mints is signed with that OLD token and is dead the instant it
                // is set. There is deliberately no cross-lock "clear the tickets" step — it could only leak one
                // way or the other between the token flip (TokenHolder's lock) and a map clear (TicketStore's
                // Mutex), and it is not load-bearing once validity flows from the ticket's bound token.
                val rotated = tokens.rotate(expected = presented)
                if (rotated == null) {
                    call.respondText("token changed", status = HttpStatusCode.Conflict)
                    return@post
                }
                call.respondText(
                    json.encodeToString(RotateResponse.serializer(), RotateResponse(rotated)),
                    ContentType.Application.Json,
                )
            }
        }
    }

    // The page itself: no credential, no query parameters, nothing to burn. The ticket is in the fragment,
    // which this handler cannot see and therefore cannot spend.
    get(AUTH_PAGE_PATH) {
        call.respondText(AUTH_PAGE_HTML, ContentType.Text.Html)
    }

    post(AUTH_EXCHANGE_PATH) {
        val facts = call.requestFacts()
        val decision = authorizeTicketExchange(facts, publicUrl)
        if (decision is AuthDecision.Deny) {
            call.respondToUnconsumedExchangeAndClose(refusalBody(decision.status), decision.status)
            return@post
        }
        // Reserve BEFORE reading the unauthenticated body. The bounded, timed read below means a slow peer
        // can hold one reservation only briefly, while admission limits how many such peers can consume a
        // handler/connection at once. Over the limit no body or candidate code is inspected, so a valid
        // ticket survives until the rolling window opens again.
        val attempt = exchangeLimit.begin()
        if (attempt == null) {
            call.respondToUnconsumedExchangeAndClose(
                "too many failed sign-in attempts",
                HttpStatusCode.TooManyRequests,
            )
            return@post
        }
        var failedExchange = false
        try {
            afterExchangeAdmitted()
            val requestBody = call.receiveChannel()
            val contentLength = call.request.contentLength()
            val body = when {
                (contentLength ?: 0L) > AUTH_EXCHANGE_MAX_BODY_BYTES ->
                    AuthExchangeBodyRead.TooLarge

                else -> readAuthExchangeBody(
                    requestBody,
                    expectedBytes = contentLength,
                    timeoutMillis = exchangeBodyTimeoutMillis,
                )
            }
            val text = when (body) {
                is AuthExchangeBodyRead.Received -> body.text
                AuthExchangeBodyRead.Incomplete -> {
                    call.respondToUnconsumedExchangeAndClose(
                        "incomplete request body",
                        HttpStatusCode.BadRequest,
                        requestBody,
                    )
                    return@post
                }
                AuthExchangeBodyRead.TooLarge -> {
                    call.respondToUnconsumedExchangeAndClose(
                        "request body too large",
                        HttpStatusCode.PayloadTooLarge,
                        requestBody,
                    )
                    return@post
                }
                AuthExchangeBodyRead.TimedOut -> {
                    call.respondToUnconsumedExchangeAndClose(
                        "request body timed out",
                        HttpStatusCode.RequestTimeout,
                        requestBody,
                    )
                    return@post
                }
            }
            val presented = try {
                json.decodeFromString(ExchangeRequest.serializer(), text).ticket.trim()
            } catch (_: SerializationException) {
                // Not charged to the budget: a body that does not parse never names a code, so it is not a
                // guess — only a real redemption attempt spends from the window.
                call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                return@post
            }
            // Redeem returns the token the ticket was BOUND to at mint time (null for "never existed", "already
            // spent" or "expired" — one answer for all three, so a prober cannot learn which guess was ever real).
            // Sign the cookie with THAT token, not `tokens.current()`: a rotation between mint and redeem thus
            // yields a cookie signed with the old token, which fails verification against the now-current one and
            // is dead the instant it is set — rotation revokes it with zero cross-lock timing dependency.
            val boundToken = if (presented.isEmpty()) null else tickets.redeem(presented)
            failedExchange = boundToken == null
            if (boundToken == null) {
                // The one thing that spends from the window: an attempt that named a code and got it wrong.
                // A SUCCESS charges nothing, so signing a stack of devices in never walks into the limit.
                call.respondText("invalid or expired ticket", status = HttpStatusCode.BadRequest)
                return@post
            }
            call.setSessionCookie(
                issueSessionCookie(boundToken, now()),
                secure = requiresSecureCookie(facts.host, publicUrl),
            )
            call.respondText("ok")
        } finally {
            // Cancellation (a client disappearing during body intake, redemption or response writing) must
            // not leak a reservation and throttle sign-in forever. Finishing is tiny, but Mutex.lock is
            // cancellable, so give this cleanup the same non-cancellable guarantee a resource release would.
            withContext(NonCancellable) { attempt.finish(failedExchange) }
        }
    }
}

/**
 * Answer an exchange request whose body was deliberately not drained, then tear down the underlying CIO
 * connection. Merely setting `Connection: close` or cancelling [requestBody] is insufficient on Ktor CIO
 * 3.4.x: its connection-pipeline coroutine can still be suspended reading the raw socket into that channel,
 * retaining one file descriptor after the route and limiter reservation have finished.
 */
private suspend fun ApplicationCall.respondToUnconsumedExchangeAndClose(
    text: String,
    status: HttpStatusCode,
    requestBody: ByteReadChannel? = null,
) {
    response.header(HttpHeaders.Connection, "close")
    try {
        respondText(text, status = status)
    } finally {
        requestBody?.cancel(null)
        withContext(NonCancellable) { closePinnedCioConnectionAfterFlush() }
    }
}

/**
 * Close the client socket by cancelling CIO's per-connection pipeline after a tiny response-flush grace.
 *
 * This is intentionally isolated and pinned to the project's Ktor CIO 3.4.x engine layout. CIO creates the
 * call in a `withContext` child of the request-handler job, itself a child of the connection pipeline; that
 * pipeline owns `parseHttpBody(connection.input, ...)`, and its completion closes the accepted socket.
 * Ktor exposes neither the socket nor a connection-close hook to an [ApplicationCall], so walking these two
 * documented-by-source parents is the only way for an application route to interrupt a peer stalled in the
 * raw parser. The raw-socket regression test guards both this hierarchy and against cancelling the server
 * root when Ktor is upgraded.
 *
 * The pipeline's response writer is a sibling of the request handler. Cancelling their parent immediately
 * can race away the already-produced 408/413/429 bytes, so this handler holds its limiter reservation (when
 * it has one) for one short, fixed response-flush grace before cancellation. A compliant peer gets its
 * response; a peer that never finishes the body retains a socket only for that bounded grace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun ApplicationCall.closePinnedCioConnectionAfterFlush(
    reason: String = "closing unconsumed /auth/exchange request body",
) {
    val callJob = coroutineContext[Job] ?: return
    val requestHandlerJob = callJob.parent ?: return
    val connectionPipelineJob = requestHandlerJob.parent ?: return
    delay(AUTH_EXCHANGE_RESPONSE_FLUSH_GRACE_MILLIS)
    connectionPipelineJob.cancel(CancellationException(reason))
}

/**
 * The browser half of the rule, applied to `POST /auth/exchange` — the one state-changing route with no
 * credential of its own to check ([authorize] cannot express that: it always ends in "no valid credential").
 *
 * `Host` must be one we serve, and `Origin` must be PRESENT and allowed. Requiring it here is safe in a way
 * it would not be on a read: this is a POST, and a browser sends `Origin` on every POST — same-origin
 * included. Without the check, a page on a same-SITE sibling (`qa.heapyhop.com` next to
 * `kotgent.heapyhop.com`, which `SameSite=Strict` does not separate) could spend a ticket it obtained and
 * have the resulting session cookie planted in the victim's browser.
 */
fun authorizeTicketExchange(facts: RequestFacts, publicUrl: String?): AuthDecision {
    val host = facts.host?.trim().orEmpty()
    if (!isAllowedHost(host, publicUrl)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "host '$host' is not in the allowlist")
    }
    val origin = facts.origin?.trim()?.ifEmpty { null }
        ?: return AuthDecision.Deny(HttpStatusCode.Forbidden, "a ticket exchange must carry an Origin header")
    if (!isAllowedOrigin(origin, publicUrl)) {
        return AuthDecision.Deny(HttpStatusCode.Forbidden, "origin '$origin' is not in the allowlist")
    }
    return AuthDecision.Allow
}

/** `<origin>/auth#ticket=<value>` — the credentialed link only `kotgent web --print` hands off intact. */
private fun ticketUrl(origin: String, ticket: String): String =
    "${origin.trimEnd('/')}$AUTH_PAGE_PATH#ticket=$ticket"

/** Wall clock in epoch millis — the production [authRoutes] `now` (`getTimeMillis` is a hard error). */
private fun authEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Maximum unauthenticated exchange-body bytes retained in memory (a normal body is about 21 bytes). */
const val AUTH_EXCHANGE_MAX_BODY_BYTES: Int = 1_024

/** Maximum time an admitted peer may spend delivering that tiny body. */
const val AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS: Long = 5_000L

/** Bounded grace for CIO's sibling writer to flush an early response before the raw socket is forced shut. */
private const val AUTH_EXCHANGE_RESPONSE_FLUSH_GRACE_MILLIS: Long = 100L

/**
 * The login page, in its two shapes: spend the ticket out of `location.hash`, or — when there is no
 * fragment at all — let the operator TYPE the code instead. Either way it ends on the SPA.
 *
 * Self-contained by design — no REQUIRED stylesheet, module import, or vendored dependency. It is the page
 * that has to render when the browser holds nothing and the static UI may not even be configured, so login
 * cannot depend on anything it has to fetch first. The optional manifest/icon links make the credential-free
 * QR landing page installable; their failure does not affect the form.
 *
 * ## Why a typed code, and not only a link
 * An installed iOS home-screen app has its OWN cookie jar. The Phone dialog's QR therefore opens this page
 * WITHOUT the ticket fragment: Safari can install it without spending the credential the installed app
 * still needs. The app then launches at `start_url` holding nothing, and there is no way to hand it a
 * fragment — it opens the URL the manifest names, not a link. So the path into an installed PWA is a code
 * the operator reads off one screen and types into another, which is exactly what
 * [TICKET_CODE_ALPHABET] made typable. The SPA routes a `401` on its first load here, so that launch lands
 * on this form instead of a wall of errors.
 *
 * `location.replace("/")` rather than an assignment: replacing the history entry means the ticket URL is
 * never left in the phone's back stack (or its history sync), which is the last place the value could
 * linger after being spent.
 *
 * A refused code says only that it is not valid — never whether it expired, was already used, or was never
 * a code at all; the remedy is the same in all three. The ONE distinction the page does draw is the `429`
 * from [ExchangeRateLimit]: "you are being throttled" is not a secret, and an operator who is one window
 * away from getting in must be told to WAIT rather than to retype a code that is perfectly good.
 *
 * A failed link falls through to the same form rather than dead-ending, so a stale QR still leaves the
 * operator one typed code away from being signed in.
 */
const val AUTH_PAGE_HTML: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" href="/icons/logo.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/icons/apple-touch-icon.png">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Kotgent">
<meta name="theme-color" content="#14171c">
<title>Kotgent — sign in</title>
<style>
  /* Dark in the app's own shade rather than adaptive: an installed PWA launches straight here on its
     first run (its own cookie jar is empty), so a light — or merely UA-grey — first screen is a flash of
     a different application. The colours are the app's `--bg` / `--text` / `--attn` spelled literally:
     this page is served from Kotlin and shares no stylesheet with the SPA. It deliberately carries no
     breakpoint — a phone gets the desktop shade for the seconds a sign-in lasts, rather than this page
     growing a second palette to maintain. */
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         background: #14171c; color: #e6e9ef;
         font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 30rem; padding: 2rem; text-align: center; }
  h1 { margin: 0 0 1rem; font-size: .85rem; letter-spacing: .18em; text-transform: uppercase; opacity: .6; }
  p { margin: .4rem 0; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9em;
         padding: .1em .4em; border-radius: 4px; background: rgba(127, 127, 127, .18); }
  .error { color: #ff6b6b; }
  .hint { opacity: .7; font-size: .9em; }
  form { margin: 1.4rem 0 .6rem; }
  input { font: 1.6rem/1.2 ui-monospace, SFMono-Regular, Menlo, monospace; letter-spacing: .18em;
          text-align: center; text-transform: uppercase; width: 100%; box-sizing: border-box;
          padding: .6rem .4rem; border: 1px solid rgba(127, 127, 127, .5); border-radius: 8px;
          background: transparent; color: inherit; }
  button { font: inherit; margin-top: .8rem; width: 100%; padding: .7rem 1rem; border: 0;
           border-radius: 8px; background: #2f6feb; color: #fff; cursor: pointer; }
  button[disabled] { opacity: .6; cursor: default; }
</style>
</head>
<body>
<main>
  <h1>Kotgent</h1>
  <p id="status">Signing in…</p>
  <form id="code-form" hidden>
    <label for="code" class="hint">Sign-in code</label>
    <input id="code" name="code" type="text" required autocomplete="one-time-code"
           autocapitalize="characters" autocorrect="off" spellcheck="false" inputmode="latin"
           enterkeyhint="go" aria-describedby="code-help">
    <button id="code-submit" type="submit">Sign in</button>
    <p id="code-help" class="hint">$TICKET_CODE_LENGTH characters, one-time, good for
      ${TICKET_TTL_MILLIS / 60_000} minutes.</p>
  </form>
  <p id="hint" class="hint" hidden>Get a code with <code>kotgent web</code>.</p>
</main>
<script>
(function () {
  var status = document.getElementById("status");
  var hint = document.getElementById("hint");
  var form = document.getElementById("code-form");
  var input = document.getElementById("code");
  var submit = document.getElementById("code-submit");

  function say(text, isError) {
    status.textContent = text;
    status.className = isError ? "error" : "";
  }

  function reveal() {
    form.hidden = false;
    hint.hidden = false;
    try { input.focus(); } catch (e) { /* a browser that refuses focus is not a failure */ }
  }

  // One message for every way a code can be wrong (expired, spent, never existed) — the remedy is the
  // same and the difference is not the operator's business. The throttle IS told apart: retyping a good
  // code cannot help there, waiting can.
  function refusal(code) {
    if (code === 429) return "Too many attempts. Wait a minute, then try again.";
    if (code === 0) return "Could not reach kotgent. Check the connection and try again.";
    return "That code is not valid. It may have expired or already been used.";
  }

  function exchange(value) {
    return fetch("/auth/exchange", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ticket: value })
    }).then(function (response) {
      if (response.ok) { window.location.replace("/"); return true; }
      say(refusal(response.status), true);
      return false;
    }).catch(function () {
      say(refusal(0), true);
      return false;
    });
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    var typed = input.value.trim();
    if (!typed) { input.focus(); return; }
    submit.disabled = true;
    say("Signing in…", false);
    exchange(typed).then(function (ok) {
      if (ok) return;               // navigating away; leave the button disabled
      submit.disabled = false;
      input.select();
    });
  });

  var params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  var ticket = params.get("ticket");
  if (!ticket) {
    // No link — an installed home-screen app opening at its start_url with an empty cookie jar, or a
    // browser sent here by the SPA's 401 routing. Typing the code is the whole way in.
    say("Enter your sign-in code.", false);
    reveal();
    return;
  }
  exchange(ticket).then(function (ok) { if (!ok) reveal(); });
})();
</script>
</body>
</html>
"""
