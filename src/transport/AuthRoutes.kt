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


const val AUTH_PAGE_PATH: String = "/auth"

const val AUTH_TICKET_PATH: String = "/auth/ticket"

const val AUTH_EXCHANGE_PATH: String = "/auth/exchange"

const val AUTH_ROTATE_PATH: String = "/auth/rotate"

@Serializable
data class TicketResponse(
    val ticket: String,
    val localUrl: String,
    val publicUrl: String? = null,
    val expiresAt: Long,
)

@Serializable
data class ExchangeRequest(val ticket: String)

@Serializable
data class RotateResponse(val token: String)

sealed interface AuthExchangeBodyRead {
    data class Received(val text: String) : AuthExchangeBodyRead
    data object Incomplete : AuthExchangeBodyRead
    data object TooLarge : AuthExchangeBodyRead
    data object TimedOut : AuthExchangeBodyRead
}

suspend fun readAuthExchangeBody(
    channel: ByteReadChannel,
    expectedBytes: Long? = null,
    maxBytes: Int = AUTH_EXCHANGE_MAX_BODY_BYTES,
    timeoutMillis: Long = AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS,
): AuthExchangeBodyRead {
    // This unauthenticated read buffers at most maxBytes+1 and cannot hold a handler indefinitely.
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
    authenticated(tokens::current, publicUrl) {
        loopbackOnly {
            post(AUTH_TICKET_PATH) {
                // Re-check one token snapshot and bind the ticket to that exact value: rotation between
                // the outer gate and issuance must not launder an old credential onto the new token.
                val token = tokens.current()
                val presented = call.presentedToken()
                val proven = if (presented != null) {
                    constantTimeEquals(presented, token)
                } else {
                    verifySessionCookie(token, call.sessionCookie())
                }
                if (!proven) {
                    call.respondText(refusalBody(HttpStatusCode.Unauthorized), status = HttpStatusCode.Unauthorized)
                    return@post
                }
                val ticket = tickets.issue(token)
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
                // Rotation returns the machine key, so a browser cookie must never authorize it.
                val presented = call.presentedToken()
                if (presented == null) {
                    call.respondText(refusalBody(HttpStatusCode.Forbidden), status = HttpStatusCode.Forbidden)
                    return@post
                }
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
        val attempt = exchangeLimit.begin()
        // Reserve before reading an attacker-controlled body; the limiter also bounds stalled peers.
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
                call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                return@post
            }
            val boundToken = if (presented.isEmpty()) null else tickets.redeem(presented)
            failedExchange = boundToken == null
            if (boundToken == null) {
                call.respondText("invalid or expired ticket", status = HttpStatusCode.BadRequest)
                return@post
            }
            call.setSessionCookie(
                // Tickets retain their mint-time token, so rotation makes a late exchange's cookie invalid.
                issueSessionCookie(boundToken, now()),
                secure = requiresSecureCookie(facts.host, publicUrl),
            )
            call.respondText("ok")
        } finally {
            // Request cancellation must not leak an in-flight limiter reservation.
            withContext(NonCancellable) { attempt.finish(failedExchange) }
        }
    }
}

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

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun ApplicationCall.closePinnedCioConnectionAfterFlush(
    reason: String = "closing unconsumed /auth/exchange request body",
) {
    // Pinned to Ktor CIO 3.4.x: the grandparent owns raw-body parsing and closes the accepted socket.
    val callJob = coroutineContext[Job] ?: return
    val requestHandlerJob = callJob.parent ?: return
    val connectionPipelineJob = requestHandlerJob.parent ?: return
    // Its response writer is a sibling, so allow the early 4xx bytes a bounded flush window.
    delay(AUTH_EXCHANGE_RESPONSE_FLUSH_GRACE_MILLIS)
    connectionPipelineJob.cancel(CancellationException(reason))
}

fun authorizeTicketExchange(facts: RequestFacts, publicUrl: String?): AuthDecision {
    // The ticket is the credential; Host+Origin are the browser-side CSRF boundary for this POST.
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

private fun ticketUrl(origin: String, ticket: String): String =
    "${origin.trimEnd('/')}$AUTH_PAGE_PATH#ticket=$ticket"

private fun authEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

const val AUTH_EXCHANGE_MAX_BODY_BYTES: Int = 1_024

const val AUTH_EXCHANGE_BODY_TIMEOUT_MILLIS: Long = 5_000L

private const val AUTH_EXCHANGE_RESPONSE_FLUSH_GRACE_MILLIS: Long = 100L

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
