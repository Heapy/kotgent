/*
 * Server-sent Web Push, from the browser's side.
 *
 * The daemon can only reach a locked phone through the browser's push service, and it can only do that if
 * this page hands it a subscription first. That handshake is:
 *
 *   1. Notification.requestPermission — iOS refuses this outside a user gesture (see the ordering note)
 *   2. register `/sw.js`            — the worker that will be woken; its scope must be `/`
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
 * daemon with no `openssl` (`GET /push/vapid-key` answers 503) — all of these leave the active flag false,
 * and the caller falls back to the in-tab notifications `lib/notify.js` has always raised.
 */

import { apiRequest } from "./api.js";
import { ensurePermission, setPushActive } from "./notify.js";

/** The worker script — served from the root so its scope is the whole origin. */
export const SW_URL = "/sw.js";

/** The daemon's push routes (mirrors `PUSH_*_PATH` in src/transport/PushRoutes.kt). */
export const VAPID_KEY_URL = "/push/vapid-key";
export const SUBSCRIBE_URL = "/push/subscribe";
export const UNSUBSCRIBE_URL = "/push/unsubscribe";

/** Last endpoint handed to the daemon by this tab. OFF can revoke it without a browser lookup. */
const ENDPOINT_KEY = "kotgent.push.endpoint.v1";
let endpointMemory = null;

const DEFAULT_TRANSITION = Object.freeze({
  isCurrent: () => true,
  repairLatest: () => {},
  signal: undefined,
});

function transitionContext(context) {
  return context || DEFAULT_TRANSITION;
}

function rememberedEndpoints() {
  const endpoints = new Set();
  if (endpointMemory) endpoints.add(endpointMemory);
  try {
    // Another tab may have replaced this tab's remembered endpoint. Delete both: either POST may have
    // reached the daemon, and a stalled browser lookup must not leave the cross-tab endpoint subscribed.
    const stored = window.localStorage.getItem(ENDPOINT_KEY);
    if (stored) endpoints.add(stored);
  } catch (_) {
    // Private mode can deny storage while the in-memory fallback remains usable.
  }
  return endpoints;
}

function rememberEndpoint(endpoint) {
  endpointMemory = endpoint;
  try {
    window.localStorage.setItem(ENDPOINT_KEY, endpoint);
  } catch (_) { /* private mode / quota — browser lookup remains the fallback */ }
}

/**
 * Browser PushManager calls and daemon writes cannot be cancelled safely. If one settles after a newer
 * generation started, queue a fresh reconciliation AFTER that stale mutation so the latest choice wins.
 */
async function settleMutation(promise, context) {
  try {
    return await promise;
  } finally {
    if (!context.isCurrent()) context.repairLatest();
  }
}

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
async function activeRegistration(context) {
  await navigator.serviceWorker.register(SW_URL, { scope: "/" });
  if (!context.isCurrent()) return null;
  const registration = await navigator.serviceWorker.ready;
  return context.isCurrent() ? registration : null;
}

/**
 * Whether [subscription] is provably tied to a different application-server key. Missing/non-standard
 * option data is NOT proof: delete a working subscription only when the lengths differ or at least one
 * stored byte differs from the requested key.
 */
function applicationServerKeyDiffers(subscription, requestedKey) {
  const storedKey = subscription && subscription.options && subscription.options.applicationServerKey;
  if (!storedKey) return false;
  try {
    const stored = new Uint8Array(storedKey);
    if (stored.length !== requestedKey.length) return true;
    return stored.some((value, index) => value !== requestedKey[index]);
  } catch (_) {
    return false;
  }
}

/**
 * Subscribe with [key], replacing a subscription that was minted under a DIFFERENT application server key.
 * That happens whenever the daemon's `vapid.pem` is regenerated, and the browser reports it by throwing
 * from `subscribe()` rather than by returning the stale subscription — leaving the device permanently
 * unreachable until it is dropped and re-taken.
 */
async function subscribeWith(registration, key, context) {
  const options = { userVisibleOnly: true, applicationServerKey: decodeBase64Url(key) };
  try {
    const subscription = await settleMutation(registration.pushManager.subscribe(options), context);
    return context.isCurrent() ? subscription : null;
  } catch (e) {
    if (!context.isCurrent()) return null;
    const existing = await registration.pushManager.getSubscription();
    if (!context.isCurrent()) return null;
    if (!existing || !applicationServerKeyDiffers(existing, options.applicationServerKey)) throw e;
    await settleMutation(existing.unsubscribe(), context);
    if (!context.isCurrent()) return null;
    const replacement = await settleMutation(registration.pushManager.subscribe(options), context);
    return context.isCurrent() ? replacement : null;
  }
}

/** A successful VAPID response always carries a non-empty key; anything else is a broken server contract. */
function responseKey(response) {
  const key = response && response.key;
  if (typeof key !== "string" || key.length === 0) {
    throw new Error("kotgent: /push/vapid-key returned no key");
  }
  return key;
}

/**
 * Read the application-server key when push is available. A failed request is the daemon's documented
 * capability downgrade (notably its 503 when openssl/key persistence is unavailable); a malformed successful
 * response still throws from responseKey because that is a broken server contract.
 */
async function vapidKeyOrNull(context) {
  let response;
  try {
    response = await apiRequest(VAPID_KEY_URL, { signal: context.signal });
  } catch (_) {
    return null;
  }
  return context.isCurrent() ? responseKey(response) : null;
}

/** Hand an existing browser subscription to the daemon, which is what makes this device reachable. */
async function registerSubscription(subscription, context) {
  if (!context.isCurrent()) return false;
  const json = subscription.toJSON();
  const keys = json.keys || {};
  rememberEndpoint(json.endpoint);
  await settleMutation(
    apiRequest(SUBSCRIBE_URL, {
      method: "POST",
      body: JSON.stringify({ endpoint: json.endpoint, p256dh: keys.p256dh || "", auth: keys.auth || "" }),
    }),
    context,
  );
  return context.isCurrent();
}

/**
 * Make this device a push target. Returns whether it now is — false (not a throw) for every "this browser
 * / this daemon cannot do push" case, so a caller can simply fall back to in-tab notifications. A genuine
 * fault (the daemon refusing the subscription) does throw, so the UI can log it.
 *
 * Must be called from a user gesture: the permission prompt is requested before anything is awaited.
 * [permissionRequest] lets a serialized UI transition start that prompt synchronously in its click handler,
 * then wait for an older on/off transition before it performs the subscription I/O. [context] carries the
 * latest-choice predicate and repair hook; a queue deadline never turns a still-current choice into stale.
 */
export async function subscribe(permissionRequest = null, transition = DEFAULT_TRANSITION) {
  const context = transitionContext(transition);
  if (!supported() || !context.isCurrent()) return false;
  setPushActive(false);
  const permission = permissionRequest || ensurePermission();
  if (!(await permission) || !context.isCurrent()) return false;

  const registration = await activeRegistration(context);
  if (!registration || !context.isCurrent()) return false;
  const key = await vapidKeyOrNull(context);
  if (!key) return false;
  const subscription = await subscribeWith(registration, key, context);
  if (!subscription || !context.isCurrent()) return false;
  if (!(await registerSubscription(subscription, context))) return false;
  if (!context.isCurrent()) return false;
  setPushActive(true);
  return true;
}

/**
 * Stop being a push target. Daemon cleanup starts from remembered endpoints BEFORE any browser await:
 * service-worker lookup and PushSubscription.unsubscribe() are not cancellable, and neither may keep sending
 * notifications after the toggle is off. Browser and daemon cleanup then settle independently. A browser
 * rejection never skips the daemon request, and a stale completion schedules a latest-state repair.
 */
export async function unsubscribe(transition = DEFAULT_TRANSITION) {
  const context = transitionContext(transition);
  if (!context.isCurrent()) return false;
  setPushActive(false);

  const daemonDrops = new Map();
  const startDaemonDrop = (endpoint) => {
    if (!endpoint || daemonDrops.has(endpoint)) return;
    const drop = settleMutation(
      apiRequest(UNSUBSCRIBE_URL, {
        method: "POST",
        body: JSON.stringify({ endpoint: endpoint }),
      }),
      context,
    ).then(() => true).catch(() => false);
    daemonDrops.set(endpoint, drop);
  };

  // This request is deliberately launched, not merely prepared, before getRegistration/getSubscription.
  rememberedEndpoints().forEach(startDaemonDrop);
  let browserDropped = false;
  try {
    if (!supported()) return false;
    const registration = await navigator.serviceWorker.getRegistration();
    if (!context.isCurrent()) {
      context.repairLatest();
      return false;
    }
    const subscription = registration ? await registration.pushManager.getSubscription() : null;
    if (!context.isCurrent()) {
      context.repairLatest();
      return false;
    }
    if (!subscription) return false;

    const endpoint = subscription.endpoint;
    rememberEndpoint(endpoint);
    startDaemonDrop(endpoint);
    try {
      browserDropped = !!(await settleMutation(subscription.unsubscribe(), context));
    } catch (_) {
      browserDropped = false;
    }
    return browserDropped && context.isCurrent();
  } finally {
    // Keep the last endpoint cached after success: a stale subscribe POST may land after this delete, and
    // the repair must still know which idempotent daemon delete to repeat even when the browser already dropped it.
    await Promise.allSettled(Array.from(daemonDrops.values()));
    if (!context.isCurrent()) context.repairLatest();
  }
}

/**
 * Reconcile the mirror flag with BOTH halves of the subscription, and return it. A browser-side subscription
 * alone is not enough: the daemon may have lost its row after a failed POST, database replacement, or key
 * regeneration. Re-subscribe with the current VAPID key and POST the endpoint again before standing down the
 * in-tab path. Every uncertain answer resolves to false.
 */
export async function refreshActive(transition = DEFAULT_TRANSITION) {
  const context = transitionContext(transition);
  if (!context.isCurrent()) return false;
  setPushActive(false);
  if (!supported()) {
    return false;
  }
  try {
    const registration = await navigator.serviceWorker.getRegistration();
    if (!context.isCurrent()) return false;
    const existing = registration ? await registration.pushManager.getSubscription() : null;
    if (!registration || !context.isCurrent()) return false;
    if (!existing) {
      // Recreate a subscription removed by a stale OFF, but never open a permission prompt from reload/repair.
      return Notification.permission === "granted"
        ? subscribe(Promise.resolve(true), context)
        : false;
    }
    rememberEndpoint(existing.endpoint);

    const key = await vapidKeyOrNull(context);
    if (!key) return false;
    const subscription = await subscribeWith(registration, key, context);
    if (!subscription || !context.isCurrent()) return false;
    if (!(await registerSubscription(subscription, context))) return false;
    if (!context.isCurrent()) return false;
    setPushActive(true);
    return true;
  } catch (_) {
    if (context.isCurrent()) setPushActive(false);
    return false;
  }
}
