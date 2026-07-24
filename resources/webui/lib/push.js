/*
 * Server-sent Web Push, from the browser's side.
 *
 * The daemon can only reach a locked phone through the browser's push service, and it can only do that if
 * this page hands it a subscription first. That handshake is:
 *
 *   1. register `/sw.js`            — the worker that will be woken; its scope must be `/`
 *   2. Notification.requestPermission — iOS refuses this outside a user gesture (see the ordering note)
 *   3. GET /push/vapid-key          — the daemon's P-256 public point; the browser will not subscribe
 *                                     without an applicationServerKey, so the KEY call comes first
 *   4. pushManager.subscribe        — userVisibleOnly: true, i.e. every push MUST end in a banner
 *   5. POST /push/subscribe         — the endpoint + keys, stored by the daemon against this device
 *
 * ## Ordering matters more than it looks
 * `ensurePermission()` runs BEFORE any other await. On iOS the permission prompt is only allowed while the
 * page is still inside the user-gesture task, and awaiting a service-worker registration first is enough to
 * lose that. Every function here is therefore written to be called directly from a click handler.
 *
 * ## Failure is a downgrade, never an error the user has to read
 * A browser without push (desktop Safari outside an installed PWA, a private window), a denied prompt, a
 * daemon with no `openssl` (`GET /push/vapid-key` answers 503) — all of these leave `subscribe()` returning
 * false, and the caller falls back to the in-tab notifications `lib/notify.js` has always raised.
 */

import { apiRequest } from "./api.js";
import { ensurePermission, isPushActive, setPushActive } from "./notify.js";

/** The worker script — served from the root so its scope is the whole origin. */
export const SW_URL = "/sw.js";

/** The daemon's push routes (mirrors `PUSH_*_PATH` in src/transport/PushRoutes.kt). */
export const VAPID_KEY_URL = "/push/vapid-key";
export const SUBSCRIBE_URL = "/push/subscribe";
export const UNSUBSCRIBE_URL = "/push/unsubscribe";

/**
 * Whether this browser can be a push target at all. All three are required: the worker to be woken, the
 * push machinery, and the notification API the subscription promises to use.
 */
export function supported() {
  return typeof window !== "undefined" &&
    typeof navigator !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window;
}

/** Whether a push subscription is believed to be active (see `notify.js`, which owns the flag). */
export function isActive() {
  return isPushActive();
}

/**
 * Decode a base64url string (RFC 4648 §5, unpadded — what `base64Url` in src/crypto emits) into the byte
 * array `applicationServerKey` wants. A DOMString is legal per spec but not accepted everywhere, so the
 * conversion is done here rather than trusted to the browser.
 */
export function decodeBase64Url(value) {
  const standard = String(value).replace(/-/g, "+").replace(/_/g, "/");
  const padded = standard + "=".repeat((4 - (standard.length % 4)) % 4);
  const raw = window.atob(padded);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

/** Register the worker (idempotent) and wait until one is actually active — subscribe() needs that. */
async function activeRegistration() {
  await navigator.serviceWorker.register(SW_URL, { scope: "/" });
  return navigator.serviceWorker.ready;
}

/**
 * Subscribe with [key], replacing a subscription that was minted under a DIFFERENT application server key.
 * That happens whenever the daemon's `vapid.pem` is regenerated, and the browser reports it by throwing
 * from `subscribe()` rather than by returning the stale subscription — leaving the device permanently
 * unreachable until it is dropped and re-taken.
 */
async function subscribeWith(registration, key) {
  const options = { userVisibleOnly: true, applicationServerKey: decodeBase64Url(key) };
  try {
    return await registration.pushManager.subscribe(options);
  } catch (e) {
    const existing = await registration.pushManager.getSubscription();
    if (!existing) throw e;
    await existing.unsubscribe();
    return registration.pushManager.subscribe(options);
  }
}

/**
 * Make this device a push target. Returns whether it now is — false (not a throw) for every "this browser
 * / this daemon cannot do push" case, so a caller can simply fall back to in-tab notifications. A genuine
 * fault (the daemon refusing the subscription) does throw, so the UI can log it.
 *
 * Must be called from a user gesture: the permission prompt is requested before anything is awaited.
 */
export async function subscribe() {
  if (!supported()) return false;
  if (!(await ensurePermission())) return false;

  const registration = await activeRegistration();
  const response = await apiRequest(VAPID_KEY_URL);
  const key = response && response.key;
  if (!key) return false;   // the daemon has no VAPID key (no openssl) — push stays off, the tab still works

  const subscription = await subscribeWith(registration, key);
  const json = subscription.toJSON();
  const keys = json.keys || {};
  await apiRequest(SUBSCRIBE_URL, {
    method: "POST",
    body: JSON.stringify({ endpoint: json.endpoint, p256dh: keys.p256dh || "", auth: keys.auth || "" }),
  });
  setPushActive(true);
  return true;
}

/**
 * Stop being a push target: the daemon is told first (while the endpoint is still known) and the browser
 * subscription is dropped after. A daemon that cannot be reached is not fatal — it prunes an endpoint that
 * no longer exists on the first `404`/`410` from the push service.
 */
export async function unsubscribe() {
  setPushActive(false);
  if (!supported()) return false;
  const registration = await navigator.serviceWorker.getRegistration();
  const subscription = registration ? await registration.pushManager.getSubscription() : null;
  if (!subscription) return false;
  try {
    await apiRequest(UNSUBSCRIBE_URL, {
      method: "POST",
      body: JSON.stringify({ endpoint: subscription.endpoint }),
    });
  } catch (_) { /* the daemon prunes it on the next 410 */ }
  await subscription.unsubscribe();
  return true;
}

/**
 * Reconcile the mirror flag with the browser's real subscription, and return it. Called once on load: a
 * subscription can disappear without this page being told (the browser drops it, the user clears site
 * data), and a stale `true` would silence BOTH paths — the worker no longer fires and `notifyAttention`
 * stands down for a subscription that is gone. Every uncertain answer therefore resolves to false.
 */
export async function refreshActive() {
  if (!supported()) {
    setPushActive(false);
    return false;
  }
  try {
    const registration = await navigator.serviceWorker.getRegistration();
    const subscription = registration ? await registration.pushManager.getSubscription() : null;
    setPushActive(!!subscription);
    return !!subscription;
  } catch (_) {
    setPushActive(false);
    return false;
  }
}
