/*
 * Talking to the daemon: the REST calls and the WebSocket URLs.
 *
 * No token appears anywhere here. The browser authenticates with the ambient `kotgent_session` cookie the
 * login flow set (`HttpOnly`, so this script cannot even read it) — the daemon reads it off every request
 * and WebSocket handshake. So REST calls carry no `Authorization` header, only `credentials: "same-origin"`
 * to make the cookie ride along, and the WebSocket URLs are plain same-origin URLs.
 */

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
  if (resp.status === 401) throw new Error("Session expired — run `kotgent web` to sign in again.");
  if (!resp.ok) throw new Error(text || ("HTTP " + resp.status));
  if (!text) return null;
  try { return JSON.parse(text); } catch (_) { return text; }
}
