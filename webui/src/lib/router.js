/*
 * The client and WebUiAssets.kt share an exact route grammar. The classic service worker separately
 * mirrors DEEP_LINK_PARAM. Parsing stays total so a malformed percent escape cannot blank the app.
 */

export const DEEP_LINK_PARAM = "session";

export const SCREEN_TASKS = "tasks";

export const SCREEN_TASK = "task";

export const SCREEN_SESSIONS = "sessions";

function segmentsOf(pathname) {
  return String(pathname || "").split("/").filter((segment) => segment.length > 0);
}

function decodeSegment(value) {
  try {
    return decodeURIComponent(value);
  } catch (_) {
    return value;
  }
}

function deepLinkId(search) {
  try {
    const id = new URLSearchParams(search || "").get(DEEP_LINK_PARAM);
    return id || null;
  } catch (_) {
    return null;
  }
}

// A path id wins over a stale notification query parameter.
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

export function routePath(route) {
  const screen = route ? route.screen : null;
  const id = route && route.id ? String(route.id) : null;
  if (screen === SCREEN_TASKS) return "/tasks";
  if (screen === SCREEN_TASK) return id ? taskPath(id) : "/tasks";
  if (screen === SCREEN_SESSIONS && id) return sessionPath(id);
  return "/";
}

export function taskPath(ref) {
  return "/tasks/" + encodeURIComponent(ref);
}

export function sessionPath(id) {
  return "/s/" + encodeURIComponent(id);
}

const routeHandlers = new Set();
let popstateInstalled = false;

function emitRoute() {
  const route = parseRoute(window.location.pathname, window.location.search);
  // Handlers may unsubscribe while this loop runs.
  for (const handler of Array.from(routeHandlers)) {
    try {
      handler(route);
    } catch (_) { /* isolate subscribers */ }
  }
}

export function navigate(path) {
  const target = typeof path === "string" && path.length > 0 ? path : "/";
  try {
    if (target === window.location.pathname + window.location.search) return;
    window.history.pushState(null, "", target);
  } catch (_) {
    try {
      window.location.assign(target);
    } catch (_ignored) {}
    return;
  }
  emitRoute();
}

export function subscribeToRoute(handler) {
  routeHandlers.add(handler);
  if (!popstateInstalled) {
    popstateInstalled = true;
    window.addEventListener("popstate", emitRoute);
  }
  return () => { routeHandlers.delete(handler); };
}
