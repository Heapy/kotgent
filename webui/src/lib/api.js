export const AUTH_PATH = "/auth";

const API_PREFIX = "/api/v1";
const API_REQUEST_TIMEOUT_MS = 60_000;
export const AUTH_TICKET_PATH = "/auth/ticket";

function apiPath(path) {
  return API_PREFIX + path;
}

export function isUnauthenticated(error) {
  return !!(error && error.unauthenticated);
}

// `/auth` is a server-rendered page, not a router screen, so leaving is a location change and `replace`
// keeps the back button off the signed-out app. Behind a port so the Node tier can observe the call.
let signOut = () => window.location.replace(AUTH_PATH);

export function setSignOutHandler(handler) {
  signOut = handler;
}

// A 4xx is authoritative. A client timeout cannot confirm the daemon's outcome, while missing status
// and 5xx can recover without changing the request.
export function isDefiniteAnswer(error) {
  return !!error && !error.timedOut && error.status >= 400 && error.status < 500;
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

function requestTimeoutError() {
  const error = new Error(
    "The request timed out after 60 seconds. The operation may have completed, so its outcome is " +
      "unconfirmed. Reload the page to check.",
  );
  error.name = "TimeoutError";
  error.timedOut = true;
  return error;
}

export async function apiRequest(path, options) {
  const opts = Object.assign({ credentials: "same-origin" }, options || {});
  opts.headers = Object.assign({}, opts.headers || {});
  // Let the browser choose multipart/binary headers for non-string bodies.
  const hasContentType = Object.keys(opts.headers)
    .some((name) => name.toLowerCase() === "content-type");
  if (typeof opts.body === "string" && !hasContentType) {
    opts.headers["Content-Type"] = "application/json";
  }

  const timeoutSignal = opts.timeout === false ? null : AbortSignal.timeout(API_REQUEST_TIMEOUT_MS);
  delete opts.timeout;
  const requestSignal = timeoutSignal && opts.signal
    ? AbortSignal.any([opts.signal, timeoutSignal])
    : (timeoutSignal || opts.signal);
  if (requestSignal) opts.signal = requestSignal;

  let resp;
  let text;
  try {
    resp = await fetch(apiPath(path), opts);
    text = await resp.text();
  } catch (error) {
    if (timeoutSignal && requestSignal.aborted && requestSignal.reason === timeoutSignal.reason) {
      throw requestTimeoutError();
    }
    throw error;
  }
  // Every read answers 401 the same way, including the reattach probe, whose caller would otherwise
  // report an expired cookie as a dead session.
  if (resp.status === 401) {
    signOut();
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
