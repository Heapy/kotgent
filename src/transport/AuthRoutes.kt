package io.kotgent.transport

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 */
fun Route.authRoutes(
    tokens: TokenHolder,
    tickets: TicketStore,
    publicUrl: String? = null,
    json: Json = TRANSPORT_JSON,
    now: () -> Long = ::authEpochMillis,
    exchangeLimit: ExchangeRateLimit = ExchangeRateLimit(),
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
            call.respondText(refusalBody(decision.status), status = decision.status)
            return@post
        }
        // The guessing budget, checked BEFORE the body is even read: over the limit, no candidate code is
        // looked at at all, so a saturated limiter cannot be used as an oracle either. The refusal is
        // deliberately a 429 and not the 400 a wrong code gets — "you are being throttled" is not a secret,
        // and the Task-15 code form has to be able to tell the operator to wait rather than to retype.
        val attempt = exchangeLimit.begin()
        if (attempt == null) {
            call.respondText("too many failed sign-in attempts", status = HttpStatusCode.TooManyRequests)
            return@post
        }
        var failedExchange = false
        try {
            val presented = try {
                json.decodeFromString(ExchangeRequest.serializer(), call.receiveText()).ticket.trim()
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
            // Cancellation (a client disappearing mid-body or mid-response) must not leak a reservation and
            // throttle sign-in forever. Finishing is tiny, but Mutex.lock is cancellable, so give this cleanup
            // the same non-cancellable guarantee a resource release would have.
            withContext(NonCancellable) { attempt.finish(failedExchange) }
        }
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
 * The login page, in its two shapes: spend the ticket out of `location.hash`, or — when there is no
 * fragment at all — let the operator TYPE the code instead. Either way it ends on the SPA.
 *
 * Self-contained by design — no stylesheet, no module import, no vendored dependency. It is the page that
 * has to render when the browser holds nothing and the static UI may not even be configured, so anything it
 * had to fetch first would be one more way for a login to fail.
 *
 * ## Why a typed code, and not only a link
 * An installed iOS home-screen app has its OWN cookie jar. Scanning the QR signs SAFARI in; the installed
 * app then launches at `start_url` holding nothing, and there is no way to hand it a fragment — it opens the
 * URL the manifest names, not a link. So the one path into an installed PWA is a code the operator reads off
 * one screen and types into another, which is exactly what [TICKET_CODE_ALPHABET] made typable. The SPA
 * routes a `401` on its first load here, so that launch lands on this form instead of a wall of errors.
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
