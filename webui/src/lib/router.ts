/*
 * The client and WebUiAssets.kt share an exact route grammar. Parsing stays total so a malformed
 * percent escape cannot blank the app.
 */

import { DEEP_LINK_PARAM } from "./push-messages.ts";

export { DEEP_LINK_PARAM };

export const SCREEN_TASKS = "tasks";

export const SCREEN_TASK = "task";

export const SCREEN_SESSIONS = "sessions";

export const SCREEN_MUTEXES = "mutexes";

export const MUTEXES_PATH = "/mutexes";

export interface Route {
  screen: typeof SCREEN_TASKS | typeof SCREEN_TASK | typeof SCREEN_SESSIONS | typeof SCREEN_MUTEXES;
  id: string | null;
}

function segmentsOf(pathname: unknown) {
  return String(pathname || "").split("/").filter((segment) => segment.length > 0);
}

function decodeSegment(value: string) {
  try {
    return decodeURIComponent(value);
  } catch (_) {
    return value;
  }
}

function deepLinkId(search?: string | null) {
  try {
    const id = new URLSearchParams(search || "").get(DEEP_LINK_PARAM);
    return id || null;
  } catch (_) {
    return null;
  }
}

// A path id wins over a stale notification query parameter.
export function parseRoute(pathname: unknown, search?: string | null): Route {
  const segments = segmentsOf(pathname);
  if (segments.length === 1 && segments[0] === SCREEN_TASKS) {
    return { screen: SCREEN_TASKS, id: null };
  }
  if (segments.length === 1 && segments[0] === SCREEN_MUTEXES) {
    return { screen: SCREEN_MUTEXES, id: null };
  }
  if (segments.length === 2 && segments[0] === SCREEN_TASKS) {
    return { screen: SCREEN_TASK, id: decodeSegment(segments[1]!) };
  }
  if (segments.length === 2 && segments[0] === "s") {
    return { screen: SCREEN_SESSIONS, id: decodeSegment(segments[1]!) };
  }
  return { screen: SCREEN_SESSIONS, id: deepLinkId(search) };
}

export function routePath(route: Route | null | undefined) {
  const screen = route ? route.screen : null;
  const id = route && route.id ? String(route.id) : null;
  if (screen === SCREEN_TASKS) return "/tasks";
  if (screen === SCREEN_MUTEXES) return MUTEXES_PATH;
  if (screen === SCREEN_TASK) return id ? taskPath(id) : "/tasks";
  if (screen === SCREEN_SESSIONS && id) return sessionPath(id);
  return "/";
}

export function taskPath(ref: string) {
  return "/tasks/" + encodeURIComponent(ref);
}

export function sessionPath(id: string) {
  return "/s/" + encodeURIComponent(id);
}

const routeHandlers = new Set<(route: Route) => void>();
let popstateInstalled = false;

export function clearDeepLink() {
  try {
    const url = new URL(window.location.href);
    if (!url.searchParams.has(DEEP_LINK_PARAM)) return;
    url.searchParams.delete(DEEP_LINK_PARAM);
    window.history.replaceState(null, "", url.pathname + url.search + url.hash);
  } catch (_) { /* Leaving the parameter is harmless when the History API is unavailable. */ }
}

function emitRoute() {
  const route = parseRoute(window.location.pathname, window.location.search);
  // Handlers may unsubscribe while this loop runs.
  for (const handler of Array.from(routeHandlers)) {
    try {
      handler(route);
    } catch (_) { /* isolate subscribers */ }
  }
}

export function navigate(path: unknown) {
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

export function subscribeToRoute(handler: (route: Route) => void) {
  routeHandlers.add(handler);
  if (!popstateInstalled) {
    popstateInstalled = true;
    window.addEventListener("popstate", emitRoute);
  }
  return () => { routeHandlers.delete(handler); };
}
