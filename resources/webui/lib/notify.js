/*
 * Browser notifications, opt-in per device.
 *
 * The on/off preference lives in its OWN localStorage key (not the view prefs object) so app.js never has
 * to thread it around: notifyAttention() checks isEnabled() itself and no-ops unless the toggle is on AND
 * the browser granted permission. A device is thus silent by default until the user turns it on and grants
 * permission.
 */

const KEY = "kotgent.notifications.v1";

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
 * and permission is granted. Keyed by session id so repeated alerts for one session coalesce.
 */
export function notifyAttention(session) {
  if (!isEnabled() || !supported() || Notification.permission !== "granted") return;
  const name = (session && (session.name || session.tmuxSession || session.id)) || "A session";
  try {
    // eslint-disable-next-line no-new
    new Notification("Kotgent — needs attention", {
      body: name + " needs your attention.",
      tag: "kotgent-attn-" + (session && session.id),
    });
  } catch (_) { /* some browsers throw if constructed off a user gesture — ignore */ }
}
