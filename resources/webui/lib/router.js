/*
 * The SPA's first router — History API over three screens.
 *
 * The bare paths are free because the whole cookie/Bearer-gated API moved under `/api/v1`: Ktor scores a
 * literal segment above the `/{path...}` tailcard that serves this app, so before that move a UI route
 * named after an API route would have been permanently unreachable.
 *
 *   /            the session view (today's whole app)
 *   /tasks       the kanban board
 *   /tasks/{ref} one task's detail
 *   /s/{id}      one session
 *
 * The daemon answers all four with the shell (`isSpaRoute` in `src/transport/WebUiAssets.kt`), matched as
 * an EXACT segment grammar — `/s/id/extra` and `/tasks/id/missing.js` still 404, so a mistyped asset path
 * still fails loudly instead of loading a page that then does nothing.
 *
 * `?session=<id>` is preserved: it is the deep link a notification tap opens (`sw.js`'s `openWindow`),
 * and the service worker is a classic script that cannot import this module, so the two must stay in
 * step by hand.
 *
 * Three rules shape everything below.
 *
 * **A path outside the grammar parses as the session view, never as an error screen.** The daemon already
 * refuses to serve the shell for one (that is what `isSpaRoute`'s exactness buys), so the only way to
 * reach one here is an in-app `navigate` with a wrong path — a bug, whose least harmful rendering is the
 * app's home screen rather than a blank page the operator cannot navigate out of.
 *
 * **Parsing is total.** `decodeURIComponent` throws on a malformed escape (`/tasks/%zz`), and a router
 * that throws while computing the initial `useState` takes the whole app down before it renders. Every
 * decode falls back to the raw segment.
 *
 * **A ref is carried through `encodeURIComponent`.** A `TaskRef` is `<tracker>:<key>` and the mandatory
 * `:` encodes to `%3A`, so `/tasks/local%3A42` and a hand-typed `/tasks/local:42` are the same route —
 * both must parse, which is why the segment is decoded rather than compared raw.
 */

/** The query parameter a notification tap deep-links with (`/?session=<id>`); mirrors `sw.js`. */
export const DEEP_LINK_PARAM = "session";

/** `/tasks` — the board. */
export const SCREEN_TASKS = "tasks";

/** `/tasks/{ref}` — one task. */
export const SCREEN_TASK = "task";

/** `/` and `/s/{id}` — the session view. */
export const SCREEN_SESSIONS = "sessions";

/** The non-empty path segments of [pathname], so a trailing or doubled slash cannot change the arm. */
function segmentsOf(pathname) {
  return String(pathname || "").split("/").filter((segment) => segment.length > 0);
}

/** [value] decoded, or [value] itself when it is not a well-formed escape sequence. */
function decodeSegment(value) {
  try {
    return decodeURIComponent(value);
  } catch (_) {
    return value;
  }
}

/** The `?session=` id in [search], or null — the deep link `sw.js` builds for a notification tap. */
function deepLinkId(search) {
  try {
    const id = new URLSearchParams(search || "").get(DEEP_LINK_PARAM);
    return id || null;
  } catch (_) {
    return null;
  }
}

/**
 * Parse a location into `{ screen, id }`. [id] is the task ref for `task`, the session id for `sessions`
 * when the path or the `?session=` deep link named one, and null otherwise.
 *
 * The path wins over the deep link: `/s/{id}?session=other` is a request for `{id}`, and the query is a
 * leftover from the tap that opened the window.
 */
export function parseRoute(pathname, search) {
  const segments = segmentsOf(pathname);
  if (segments.length === 1 && segments[0] === SCREEN_TASKS) {
    return { screen: SCREEN_TASKS, id: null };
  }
  if (segments.length === 2 && segments[0] === SCREEN_TASKS) {
    return { screen: SCREEN_TASK, id: decodeSegment(segments[1]) };
  }
  if (segments.length === 2 && segments[0] === "s") {
    return { screen: SCREEN_SESSIONS, id: decodeSegment(segments[1]) };
  }
  return { screen: SCREEN_SESSIONS, id: deepLinkId(search) };
}

/** The path for a route object — the inverse of [parseRoute]. */
export function routePath(route) {
  const screen = route ? route.screen : null;
  const id = route && route.id ? String(route.id) : null;
  if (screen === SCREEN_TASKS) return "/tasks";
  // A `task` route with no ref is the board: `/tasks/` names no task, and a dead link is worse than the
  // list the operator was heading for anyway.
  if (screen === SCREEN_TASK) return id ? taskPath(id) : "/tasks";
  if (screen === SCREEN_SESSIONS && id) return sessionPath(id);
  return "/";
}

/** `/tasks/{ref}` — where a task badge links. */
export function taskPath(ref) {
  return "/tasks/" + encodeURIComponent(ref);
}

/** `/s/{id}` — where a session row links. */
export function sessionPath(id) {
  return "/s/" + encodeURIComponent(id);
}

/**
 * Every live [subscribeToRoute] handler.
 *
 * The `popstate` listener is installed once, on the first subscription, and deliberately stays installed
 * after the last unsubscribe: the app subscribes once for the life of the page, and add/remove churn
 * around a re-mounted effect buys nothing while risking a window with no listener at all.
 */
const routeHandlers = new Set();
let popstateInstalled = false;

/** Read the live location and hand the parsed route to every subscriber. */
function emitRoute() {
  const route = parseRoute(window.location.pathname, window.location.search);
  // A copy, because a handler may unsubscribe itself (or another) while this loop runs; and each is
  // called inside its own try, so one throwing subscriber cannot strand the ones after it.
  for (const handler of Array.from(routeHandlers)) {
    try {
      handler(route);
    } catch (_) { /* a subscriber's failure is its own problem, not the router's */ }
  }
}

/** Navigate without a reload (`history.pushState`), notifying every [subscribeToRoute] handler. */
export function navigate(path) {
  const target = typeof path === "string" && path.length > 0 ? path : "/";
  try {
    // Both halves, because a navigation off `/?session=x` to `/` really does change the location even
    // though the pathname does not — dropping the query is the point.
    if (target === window.location.pathname + window.location.search) return;
    window.history.pushState(null, "", target);
  } catch (_) {
    // No usable History API: a full load still reaches the screen, which beats a link that does nothing.
    try {
      window.location.assign(target);
    } catch (_ignored) { /* nothing left to try */ }
    return;
  }
  emitRoute();
}

/** Subscribe to route changes (`popstate` plus [navigate]); returns an unsubscribe function. */
export function subscribeToRoute(handler) {
  routeHandlers.add(handler);
  if (!popstateInstalled) {
    popstateInstalled = true;
    window.addEventListener("popstate", emitRoute);
  }
  return () => { routeHandlers.delete(handler); };
}
