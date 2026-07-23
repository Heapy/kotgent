/*
 * Talking to the daemon: the token, the REST calls and the WebSocket URLs.
 *
 * The token arrives in the URL fragment (`#token=…`), which browsers never send to a server — so it
 * stays out of request lines and logs. It is then replayed as an `Authorization: Bearer` header for
 * REST and as a `?token=` query parameter for the two WebSockets, because a browser cannot set headers
 * on a WebSocket handshake.
 */

/** Extract the bearer token from a location fragment like `#token=abc` (or `#foo=1&token=abc`). */
export function parseToken(hash) {
  const m = /(?:^#|[#&])token=([^&]*)/.exec(hash || "");
  if (!m || !m[1]) return null;
  try { return decodeURIComponent(m[1]); } catch (_) { return m[1]; }
}

/** Build a same-origin WebSocket URL for [path], carrying the token as `?token=`. */
export function wsUrl(path, token, base) {
  const loc = base || window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  const sep = path.indexOf("?") >= 0 ? "&" : "?";
  return proto + "//" + loc.host + path + sep + "token=" + encodeURIComponent(token);
}

/** The text control frame the terminal WS expects for a resize (matches TerminalWs's protocol). */
export function resizeFrame(cols, rows) {
  return JSON.stringify({ type: "resize", cols: cols, rows: rows });
}

export function errorMessage(error) {
  return error && error.message ? error.message : String(error);
}

/** Fetch one authenticated JSON API response and surface its server-provided error text. */
export async function apiRequest(token, path, options) {
  const opts = Object.assign({}, options || {});
  opts.headers = Object.assign({ "Authorization": "Bearer " + token }, opts.headers || {});
  if (opts.body) opts.headers["Content-Type"] = "application/json";

  const resp = await fetch(path, opts);
  const text = await resp.text();
  if (resp.status === 401) throw new Error("Unauthorized — check the #token in the URL.");
  if (!resp.ok) throw new Error(text || ("HTTP " + resp.status));
  if (!text) return null;
  try { return JSON.parse(text); } catch (_) { return text; }
}
