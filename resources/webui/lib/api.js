/*
 * Talking to the daemon: the REST calls and the WebSocket URLs.
 *
 * No token appears anywhere here. The browser authenticates with the ambient `kotgent_session` cookie the
 * login flow set (`HttpOnly`, so this script cannot even read it) — the daemon reads it off every request
 * and WebSocket handshake. So REST calls carry no `Authorization` header, only `credentials: "same-origin"`
 * to make the cookie ride along, and the WebSocket URLs are plain same-origin URLs.
 */

/**
 * The login page (`AUTH_PAGE_PATH` in `src/transport/AuthRoutes.kt`) — where a browser holding no valid
 * cookie has to go. With no `#ticket=` fragment it renders the typed-code form, which is the ONLY way into
 * an installed home-screen app: it launches at `start_url` with its own empty cookie jar and cannot be
 * handed a link fragment.
 */
export const AUTH_PATH = "/auth";

/** True for the error [apiRequest] throws on a `401` — the caller decides whether to route to [AUTH_PATH]. */
export function isUnauthenticated(error) {
  return !!(error && error.unauthenticated);
}

/**
 * True when the daemon answered THIS request and the answer will not change on a retry: the session is
 * gone, or this client may no longer read it. Nothing else qualifies: an offline or aborted request
 * carries no status at all, and a `5xx` from a tunnel in front of a restarting daemon is precisely the
 * case worth retrying. So a caller that retries on recovery retries only what recovery can actually fix.
 */
export function isDefiniteAnswer(error) {
  return !!error && error.status >= 400 && error.status < 500;
}

/** Build a same-origin WebSocket URL for [path]. The session cookie authenticates the handshake. */
export function wsUrl(path, base) {
  const loc = base || window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  return proto + "//" + loc.host + path;
}

/** The text control frame the terminal WS expects for a resize (matches TerminalWs's protocol). */
export function resizeFrame(cols, rows) {
  return JSON.stringify({ type: "resize", cols: cols, rows: rows });
}

export function errorMessage(error) {
  return error && error.message ? error.message : String(error);
}

/** Fetch one authenticated JSON API response and surface its server-provided error text. */
export async function apiRequest(path, options) {
  const opts = Object.assign({ credentials: "same-origin" }, options || {});
  opts.headers = Object.assign({}, opts.headers || {});
  if (opts.body) opts.headers["Content-Type"] = "application/json";

  const resp = await fetch(path, opts);
  const text = await resp.text();
  if (resp.status === 401) {
    // Not "run `kotgent web`": on a phone — the case this message exists for — there is no shell to run it
    // in. What every client CAN do is open the sign-in page and type a code, so that is what it says, and
    // the flag lets a caller (app.js, on its first load) navigate there instead of only reporting it.
    const expired = new Error("Signed out — open " + AUTH_PATH + " and enter a sign-in code.");
    expired.unauthenticated = true;
    expired.status = resp.status;
    throw expired;
  }
  if (!resp.ok) {
    const failed = new Error(text || ("HTTP " + resp.status));
    failed.status = resp.status;
    throw failed;
  }
  if (!text) return null;
  try { return JSON.parse(text); } catch (_) { return text; }
}
