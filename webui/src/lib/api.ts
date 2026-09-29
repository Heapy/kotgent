import { apiPath } from "./api-paths.ts";

export interface ApiRequestOptions extends Omit<RequestInit, "headers" | "signal"> {
  headers?: Record<string, string>;
  signal?: AbortSignal | null | undefined;
  timeout?: false;
}

export interface ApiError extends Error {
  status?: number;
  timedOut?: boolean;
  unauthenticated?: boolean;
}

function errorField(error: unknown, key: string): unknown {
  return error && Reflect.get(Object(error), key);
}

export const AUTH_PATH = "/auth";

const API_REQUEST_TIMEOUT_MS = 60_000;
export const AUTH_TICKET_PATH = "/auth/ticket";

export function isUnauthenticated(error: unknown) {
  return !!(errorField(error, "unauthenticated"));
}

// `/auth` is a server-rendered page, not a router screen, so leaving is a location change and `replace`
// keeps the back button off the signed-out app. Behind a port so the Node tier can observe the call.
let signOut = () => window.location.replace(AUTH_PATH);

export function setSignOutHandler(handler: () => void) {
  signOut = handler;
}

// A 4xx is authoritative. A client timeout cannot confirm the daemon's outcome, while missing status
// and 5xx can recover without changing the request.
export function isDefiniteAnswer(error: unknown) {
  return !!error && !errorField(error, "timedOut") && Number(errorField(error, "status")) >= 400 && Number(errorField(error, "status")) < 500;
}

export function wsUrl(path: string, base?: Pick<Location, "protocol" | "host"> | null) {
  const loc = base || window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  return proto + "//" + loc.host + apiPath(path);
}

export function resizeFrame(cols: number, rows: number) {
  return JSON.stringify({ type: "resize", cols: cols, rows: rows });
}

export function errorMessage(error: unknown) {
  return errorField(error, "message") ? errorField(error, "message") : String(error);
}

function requestTimeoutError() {
  const error: ApiError = new Error(
    "The request timed out after 60 seconds. The operation may have completed, so its outcome is " +
      "unconfirmed. Reload the page to check.",
  );
  error.name = "TimeoutError";
  error.timedOut = true;
  return error;
}

export async function apiRequest(path: string, options?: ApiRequestOptions): Promise<unknown> {
  const opts: ApiRequestOptions = Object.assign({ credentials: "same-origin" } satisfies ApiRequestOptions, options || {});
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

  let resp: Response;
  let text: string;
  try {
    // Web IDL treats an undefined signal as absent; transition contexts pass that value through.
    resp = await fetch(apiPath(path), opts as RequestInit);
    text = await resp.text();
  } catch (error) {
    if (timeoutSignal && requestSignal!.aborted && requestSignal!.reason === timeoutSignal.reason) {
      throw requestTimeoutError();
    }
    throw error;
  }
  // Every read answers 401 the same way, including the reattach probe, whose caller would otherwise
  // report an expired cookie as a dead session.
  if (resp.status === 401) {
    signOut();
    const expired: ApiError = new Error("Signed out — open " + AUTH_PATH + " and enter a sign-in code.");
    expired.unauthenticated = true;
    expired.status = resp.status;
    throw expired;
  }
  if (!resp.ok) {
    const failed: ApiError = new Error(text || ("HTTP " + resp.status));
    failed.status = resp.status;
    throw failed;
  }
  if (!text) return null;
  try { return JSON.parse(text); } catch (_) { return text; }
}
