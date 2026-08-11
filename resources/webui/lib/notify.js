// In-tab notifications stand down while Web Push is active to avoid duplicate banners.

const KEY = "kotgent.notifications.v1";
const PUSH_KEY = "kotgent.push.v1";

export function supported() {
  return typeof window !== "undefined" && "Notification" in window;
}

export function isEnabled() {
  try {
    return window.localStorage.getItem(KEY) === "1";
  } catch (_) {
    return false;
  }
}

export function setEnabled(on) {
  try {
    window.localStorage.setItem(KEY, on ? "1" : "0");
  } catch (_) { /* best effort */ }
}

export function isPushActive() {
  try {
    return window.localStorage.getItem(PUSH_KEY) === "1";
  } catch (_) {
    return false;
  }
}

export function setPushActive(on) {
  try {
    window.localStorage.setItem(PUSH_KEY, on ? "1" : "0");
  } catch (_) { /* best effort */ }
}

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

export function notifyAttention(session) {
  if (!isEnabled() || !supported() || Notification.permission !== "granted") return;
  if (isPushActive()) return;
  const name = (session && (session.name || session.tmuxSession || session.id)) || "A session";
  try {
    // eslint-disable-next-line no-new
    new Notification("Kotgent — needs attention", {
      body: name + " needs your attention.",
      tag: "kotgent-attn-" + (session && session.id),
    });
  } catch (_) { /* best effort */ }
}
