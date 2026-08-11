// On iOS, request notification permission before the first await or the user gesture is lost.

import { apiRequest } from "./api.js";
import { ensurePermission, isEnabled as notifyEnabled, setPushActive } from "./notify.js";

export const SW_URL = "/sw.js";

export const VAPID_KEY_URL = "/push/vapid-key";
export const SUBSCRIBE_URL = "/push/subscribe";
export const UNSUBSCRIBE_URL = "/push/unsubscribe";

const ENDPOINT_KEY = "kotgent.push.endpoint.v1";
const PUSH_PREFERENCE_ACK_TIMEOUT_MS = 2_000;
// The classic worker duplicates this value because it cannot import modules.
export const PUSH_PREFERENCE_MESSAGE = "push-notification-preference";
export const PUSH_REPAIR_SIGNAL_KEY = "kotgent.push.repair.v1";
let endpointMemory = null;
let activeRegistrationMemory = null;
let repairSignalSequence = 0;
const repairSignalSource = Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);

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
    // Either the in-memory or cross-tab endpoint may have reached the daemon.
    const stored = window.localStorage.getItem(ENDPOINT_KEY);
    if (stored) endpoints.add(stored);
  } catch (_) {}
  return endpoints;
}

function rememberEndpoint(endpoint) {
  endpointMemory = endpoint;
  try {
    window.localStorage.setItem(ENDPOINT_KEY, endpoint);
  } catch (_) {}
}

// Publish current intent, not a captured transition; delayed lookups must not replay obsolete state.
export async function syncWorkerPushPreference(registration = null, waitForApply = false) {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return false;
  if (registration) activeRegistrationMemory = registration;
  let worker = navigator.serviceWorker.controller ||
    (activeRegistrationMemory && activeRegistrationMemory.active);
  if (!worker) {
    try {
      const found = await navigator.serviceWorker.getRegistration();
      if (!found) return false;
      activeRegistrationMemory = found;
      worker = found.active;
    } catch (_) {
      return false;
    }
  }
  if (!worker) return false;
  const enabled = notifyEnabled();
  const message = {
    type: PUSH_PREFERENCE_MESSAGE,
    enabled: enabled,
    endpoints: enabled ? [] : Array.from(rememberedEndpoints()),
  };
  if (!waitForApply) {
    try {
      worker.postMessage(message);
      return true;
    } catch (_) {
      return false;
    }
  }
  return new Promise((resolve) => {
    const channel = new MessageChannel();
    let settled = false;
    const finish = (applied) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      channel.port1.close();
      resolve(applied);
    };
    const timeout = setTimeout(() => finish(false), PUSH_PREFERENCE_ACK_TIMEOUT_MS);
    channel.port1.onmessage = (event) => finish(event.data === true);
    try {
      worker.postMessage(message, [channel.port2]);
    } catch (_) {
      finish(false);
    }
  });
}

function signalPushRepair() {
  repairSignalSequence += 1;
  try {
    window.localStorage.setItem(
      PUSH_REPAIR_SIGNAL_KEY,
      repairSignalSource + ":" + repairSignalSequence,
    );
  } catch (_) {}
}

// Irreversible stale mutations trigger repair after they settle, including in other tabs.
async function settleMutation(promise, context) {
  try {
    return await promise;
  } finally {
    if (!context.isCurrent()) {
      signalPushRepair();
      context.repairLatest();
    }
  }
}

export function supported() {
  return typeof window !== "undefined" &&
    typeof navigator !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window;
}

// Some browsers reject the spec-permitted string form of applicationServerKey.
export function decodeBase64Url(value) {
  const standard = String(value).replace(/-/g, "+").replace(/_/g, "/");
  const padded = standard + "=".repeat((4 - (standard.length % 4)) % 4);
  const raw = window.atob(padded);
  const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

async function activeRegistration(context) {
  await navigator.serviceWorker.register(SW_URL, { scope: "/" });
  if (!context.isCurrent()) return null;
  const registration = await navigator.serviceWorker.ready;
  if (!context.isCurrent()) return null;
  activeRegistrationMemory = registration;
  return registration;
}

// Never delete a working subscription unless its stored key proves it is stale.
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

// VAPID regeneration requires replacing the browser subscription tied to the old key.
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

function responseKey(response) {
  const key = response && response.key;
  if (typeof key !== "string" || key.length === 0) {
    throw new Error("kotgent: /push/vapid-key returned no key");
  }
  return key;
}

// Failure to obtain a key is a capability downgrade; malformed success remains an error.
async function vapidKeyOrNull(context) {
  let response;
  try {
    response = await apiRequest(VAPID_KEY_URL, { signal: context.signal });
  } catch (_) {
    return null;
  }
  return context.isCurrent() ? responseKey(response) : null;
}

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

// The caller starts permission synchronously in the click handler, before serialized network work.
export async function subscribe(permissionRequest = null, transition = DEFAULT_TRANSITION) {
  const context = transitionContext(transition);
  if (!supported() || !context.isCurrent()) return false;
  setPushActive(false);
  const permission = permissionRequest || ensurePermission();
  if (!(await permission) || !context.isCurrent()) return false;

  const registration = await activeRegistration(context);
  if (!registration || !context.isCurrent()) return false;
  if (!(await syncWorkerPushPreference(registration, true)) || !context.isCurrent()) return false;
  const key = await vapidKeyOrNull(context);
  if (!key) return false;
  const subscription = await subscribeWith(registration, key, context);
  if (!subscription || !context.isCurrent()) return false;
  if (!(await registerSubscription(subscription, context))) return false;
  if (!context.isCurrent()) return false;
  setPushActive(true);
  return true;
}

// Start daemon cleanup before any browser await; browser subscription calls are not cancellable.
export async function unsubscribe(transition = DEFAULT_TRANSITION) {
  const context = transitionContext(transition);
  if (!context.isCurrent()) return false;
  setPushActive(false);
  syncWorkerPushPreference();

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

  rememberedEndpoints().forEach(startDaemonDrop);
  let browserDropped = false;
  try {
    if (!supported()) return false;
    const registration = await navigator.serviceWorker.getRegistration();
    if (!context.isCurrent()) {
      context.repairLatest();
      return false;
    }
    syncWorkerPushPreference(registration);
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
    // Retain the endpoint so repair can repeat a delete after a stale subscribe POST lands.
    await Promise.allSettled(Array.from(daemonDrops.values()));
    if (!context.isCurrent()) context.repairLatest();
  }
}

// Browser and daemon subscription state must both be restored before disabling in-tab notifications.
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
    if (registration && !(await syncWorkerPushPreference(registration, true))) return false;
    if (!context.isCurrent()) return false;
    const existing = registration ? await registration.pushManager.getSubscription() : null;
    if (!context.isCurrent()) return false;
    if (!registration || !existing) {
      // Repair may reuse granted permission but must not prompt without a gesture.
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
