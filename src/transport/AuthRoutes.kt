package io.kotgent.transport

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
 *   browser opens localUrl      GET  /auth#ticket=… (no auth, NO parameters) → the page below
 *   the page's script           POST /auth/exchange (the ticket IS the credential) → Set-Cookie, then "/"
 * ```
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
 * Where `kotgent token rotate` re-mints the master token. `Bearer` + loopback only. Rotation also drops all
 * outstanding sign-in tickets, so "revoke all browser credentials" covers pending ones as well as live cookies.
 */
const val AUTH_ROTATE_PATH: String = "/auth/rotate"

/**
 * The body of `POST /auth/ticket`. Both URLs already carry the ticket in their fragment, so the CLI (and
 * the Task-11 QR dialog) can hand them over verbatim without knowing the flow's shape.
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

/**
 * Mount the four login routes on [this] route.
 *
 * @param tokens the live master token; [TokenHolder.rotate] is what `/auth/rotate` calls, and
 *   [TokenHolder.current] is both the `Bearer` compared against and the HMAC key the cookie is signed with.
 * @param tickets the one-shot ticket store — issuing and redeeming are its main entry points; `/auth/rotate`
 *   also calls [TicketStore.invalidateAll] so a rotation revokes outstanding (unredeemed) tickets too.
 * @param publicUrl the configured public origin, or `null` for loopback-only. Decides the `publicUrl` in a
 *   ticket response, the `Origin` allowlist on the exchange, and whether the cookie is `Secure`.
 * @param now epoch millis, stamped into the issued cookie (injected so tests are deterministic).
 */
fun Route.authRoutes(
    tokens: TokenHolder,
    tickets: TicketStore,
    publicUrl: String? = null,
    json: Json = TRANSPORT_JSON,
    now: () -> Long = ::authEpochMillis,
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
                val ticket = tickets.issue()
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
                // its body — so a cookie reaching here (an XSS in the SPA firing a same-origin POST, whose
                // Host is loopback and whose Origin is allowed) would escalate a browser-scoped credential
                // into the machine key, collapsing the two-key model. Demand the Bearer explicitly, reusing
                // the exact constant-time compare [authorize] runs for a token; a cookie-only caller is 403.
                val presented = call.presentedToken()
                if (presented == null || !constantTimeEquals(presented, tokens.current())) {
                    call.respondText(refusalBody(HttpStatusCode.Forbidden), status = HttpStatusCode.Forbidden)
                    return@post
                }
                // Persist-then-publish lives in [TokenHolder.rotate]; a failing persist throws here and
                // becomes a 500 with the OLD token still in force, which is the safe end of that failure.
                val rotated = tokens.rotate()
                // Then drop every outstanding sign-in ticket, so rotation actually means "revoke ALL browser
                // credentials": cookies stop verifying the instant the key flips, but a ticket is a browser
                // credential that has NOT been redeemed yet and would otherwise still exchange into a fresh
                // cookie under the new token — the very shoulder-surfed link an operator rotates to kill.
                // Ordered AFTER the token flip on purpose: once the new token is live the old Bearer can no
                // longer mint a ticket, so this sweep clears exactly the pre-rotation set with nothing new
                // racing in behind it (clearing first would let a last old-Bearer issue slip a ticket through).
                tickets.invalidateAll()
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
            call.respondText(refusalBody(decision.status), status = decision.status)
            return@post
        }
        val presented = try {
            json.decodeFromString(ExchangeRequest.serializer(), call.receiveText()).ticket.trim()
        } catch (_: SerializationException) {
            call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        // One answer for "never existed", "already spent" and "expired": distinguishing them would tell a
        // prober which of its guesses was ever a real ticket.
        if (presented.isEmpty() || !tickets.redeem(presented)) {
            call.respondText("invalid or expired ticket", status = HttpStatusCode.BadRequest)
            return@post
        }
        call.setSessionCookie(
            issueSessionCookie(tokens.current(), now()),
            secure = requiresSecureCookie(facts.host, publicUrl),
        )
        call.respondText("ok")
    }
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

/** `<origin>/auth#ticket=<value>` — the shape both the CLI and the QR code hand to a browser. */
private fun ticketUrl(origin: String, ticket: String): String =
    "${origin.trimEnd('/')}$AUTH_PAGE_PATH#ticket=$ticket"

/** Wall clock in epoch millis — the production [authRoutes] `now` (`getTimeMillis` is a hard error). */
private fun authEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * The login page: read the ticket out of `location.hash`, spend it, land on the SPA.
 *
 * Self-contained by design — no stylesheet, no module import, no vendored dependency. It is the page that
 * has to render when the browser holds nothing and the static UI may not even be configured, so anything it
 * had to fetch first would be one more way for a login to fail.
 *
 * `location.replace("/")` rather than an assignment: replacing the history entry means the ticket URL is
 * never left in the phone's back stack (or its history sync), which is the last place the value could
 * linger after being spent.
 *
 * A failure says only that the link is not valid — never whether it expired, was already used, or was never
 * a ticket at all. The remedy is the same in every case and is printed right under it.
 */
const val AUTH_PAGE_HTML: String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kotgent — sign in</title>
<style>
  :root { color-scheme: light dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
         font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 30rem; padding: 2rem; text-align: center; }
  h1 { margin: 0 0 1rem; font-size: .85rem; letter-spacing: .18em; text-transform: uppercase; opacity: .6; }
  p { margin: .4rem 0; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9em;
         padding: .1em .4em; border-radius: 4px; background: rgba(127, 127, 127, .18); }
  .error { color: #c0392b; }
  .hint { opacity: .7; font-size: .9em; }
</style>
</head>
<body>
<main>
  <h1>Kotgent</h1>
  <p id="status">Signing in…</p>
  <p id="hint" class="hint" hidden>Issue a fresh link with <code>kotgent web</code>.</p>
</main>
<script>
(function () {
  var status = document.getElementById("status");
  var hint = document.getElementById("hint");
  function fail() {
    status.textContent = "This sign-in link is not valid.";
    status.className = "error";
    hint.hidden = false;
  }
  var params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  var ticket = params.get("ticket");
  if (!ticket) { fail(); return; }
  fetch("/auth/exchange", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ticket: ticket })
  }).then(function (response) {
    if (!response.ok) { fail(); return; }
    window.location.replace("/");
  }).catch(fail);
})();
</script>
</body>
</html>
"""
