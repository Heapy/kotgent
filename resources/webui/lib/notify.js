/*
 * Browser notifications, opt-in per device.
 *
 * The on/off preference lives in its OWN localStorage key (not the view prefs object) so app.js never has
 * to thread it around: notifyAttention() checks isEnabled() itself and no-ops unless the toggle is on AND
 * the browser granted permission. A device is thus silent by default until the user turns it on and grants
 * permission.
 *
 * Two notification paths exist and exactly one of them may fire. This module owns the IN-TAB one (a live
 * page watching /events); `lib/push.js` owns the server-sent one (the service worker, which fires whether
 * or not a tab is alive). When a push subscription is active the service worker already raises the banner,
 * so notifyAttention() stands down — otherwise an open tab shows two notifications for one event. The
 * mirror flag lives HERE, not in push.js, so notifyAttention stays synchronous and the dependency runs one
 * way only (push.js → notify.js); push.js keeps it in step with the browser's real subscription.
 */

const KEY = "kotgent.notifications.v1";
const PUSH_KEY = "kotgent.push.v1";

/** Whether the Notification API exists in this browser (absent on some mobile / insecure contexts). */
export function supported() {
  return typeof window !== "undefined" && "Notification" in window;
}

/** Whether the per-device notifications toggle is on. */
export function isEnabled() {
  try {
    return window.localStorage.getItem(KEY) === "1";
  } catch (_) {
    return false; // storage disabled / private mode — treat as off
  }
}

/** Persist the per-device toggle state. */
export function setEnabled(on) {
  try {
    window.localStorage.setItem(KEY, on ? "1" : "0");
  } catch (_) { /* private mode / quota — the choice just won't persist */ }
}

/**
 * Whether this device currently has a server-sent push subscription — i.e. whether the service worker is
 * going to raise the banner by itself. Written by `lib/push.js` on every subscribe/unsubscribe and
 * reconciled against the browser's real subscription on load; a stale `true` would make this device silent
 * on both paths, so push.js errs towards clearing it.
 */
export function isPushActive() {
  try {
    return window.localStorage.getItem(PUSH_KEY) === "1";
  } catch (_) {
    return false; // storage disabled — assume no push and keep the in-tab notification
  }
}

/** Record whether a push subscription is active (called by `lib/push.js`, not by the UI). */
export function setPushActive(on) {
  try {
    window.localStorage.setItem(PUSH_KEY, on ? "1" : "0");
  } catch (_) { /* private mode / quota — the in-tab path just stays live */ }
}

/**
 * Ensure notification permission, prompting once if it is still default. Returns whether it is granted.
 * Never throws — a browser without the API, or a denied prompt, resolves to false.
 */
export async function ensurePermission() {
  if (!supported()) return false;
  if (Notification.permission === "granted") return true;
  if (Notification.permission === "denied") return false;
  try {
    return (await Notification.requestPermission()) === "granted";
  } catch (_) {
    return false;
  }
}

/**
 * Show a "needs attention" notification for [session] — a no-op unless the toggle is on, the API exists,
 * permission is granted, and no push subscription is active (the service worker would raise its own banner
 * for the same event). Keyed by session id so repeated alerts for one session coalesce.
 */
export function notifyAttention(session) {
  if (!isEnabled() || !supported() || Notification.permission !== "granted") return;
  if (isPushActive()) return;   // the service worker is showing this one — no duplicate on an open tab
  const name = (session && (session.name || session.tmuxSession || session.id)) || "A session";
  try {
    // eslint-disable-next-line no-new
    new Notification("Kotgent — needs attention", {
      body: name + " needs your attention.",
      tag: "kotgent-attn-" + (session && session.id),
    });
  } catch (_) { /* some browsers throw if constructed off a user gesture — ignore */ }
}
