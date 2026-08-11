export const AUTH_PATH = "/auth";

const API_PREFIX = "/api/v1";

// Keep `/auth*` unprefixed: unauthenticated clients need it, and ApiClient.daemonPath mirrors this rule.
function apiPath(path) {
  return path.indexOf(AUTH_PATH) === 0 ? path : API_PREFIX + path;
}

export function isUnauthenticated(error) {
  return !!(error && error.unauthenticated);
}

// A 4xx is authoritative; missing status and 5xx can recover without changing the request.
export function isDefiniteAnswer(error) {
  return !!error && error.status >= 400 && error.status < 500;
}

export function wsUrl(path, base) {
  const loc = base || window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  return proto + "//" + loc.host + apiPath(path);
}

export function resizeFrame(cols, rows) {
  return JSON.stringify({ type: "resize", cols: cols, rows: rows });
}

export function errorMessage(error) {
  return error && error.message ? error.message : String(error);
}

export async function apiRequest(path, options) {
  const opts = Object.assign({ credentials: "same-origin" }, options || {});
  opts.headers = Object.assign({}, opts.headers || {});
  // Let the browser choose multipart/binary headers for non-string bodies.
  const hasContentType = Object.keys(opts.headers)
    .some((name) => name.toLocaleLowerCase() === "content-type");
  if (typeof opts.body === "string" && !hasContentType) {
    opts.headers["Content-Type"] = "application/json";
  }

  const resp = await fetch(apiPath(path), opts);
  const text = await resp.text();
  if (resp.status === 401) {
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
