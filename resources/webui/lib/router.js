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
 * STUB: Task 22 of the task-backlog plan implements this module. Every export below is present with its
 * final signature so `app.js` compiles against it and `WebUiServingTest` can register it now;
 * `parseRoute` deliberately answers "the session view" for everything, which is exactly today's
 * behaviour, so wiring the router changes nothing until Task 22 lands.
 */

/** The query parameter a notification tap deep-links with (`/?session=<id>`); mirrors `sw.js`. */
export const DEEP_LINK_PARAM = "session";

/** `/tasks` — the board. */
export const SCREEN_TASKS = "tasks";

/** `/tasks/{ref}` — one task. */
export const SCREEN_TASK = "task";

/** `/` and `/s/{id}` — the session view. */
export const SCREEN_SESSIONS = "sessions";

/**
 * Parse a location into `{ screen, id }`. [id] is the task ref for `task`, the session id for `sessions`
 * when the path or the `?session=` deep link named one, and null otherwise.
 */
export function parseRoute(pathname, search) {
  // Task 22.
  return { screen: SCREEN_SESSIONS, id: null };
}

/** The path for a route object — the inverse of [parseRoute]. */
export function routePath(route) {
  // Task 22.
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

/** Navigate without a reload (`history.pushState`), notifying every [subscribeToRoute] handler. */
export function navigate(path) {
  // Task 22.
}

/** Subscribe to route changes (`popstate` plus [navigate]); returns an unsubscribe function. */
export function subscribeToRoute(handler) {
  // Task 22.
  return () => {};
}
