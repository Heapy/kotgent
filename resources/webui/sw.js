/*
 * Classic, root-scoped, network-only worker. Pushes are payloadless, so it fetches session state; a
 * failed fetch still shows a generic banner because the subscription promises user-visible delivery.
 */

/* eslint-env serviceworker */

// A classic worker cannot import the shared API-prefix helper.
const TITLE = "Kotgent — needs attention";
const SESSIONS_URL = "/api/v1/sessions";
const SESSIONS_TIMEOUT_MS = 10_000;
const PUSH_SUBSCRIBE_URL = "/api/v1/push/subscribe";
const PUSH_UNSUBSCRIBE_URL = "/api/v1/push/unsubscribe";
const PUSH_PREFERENCE_MESSAGE = "push-notification-preference";
const PUSH_PREFERENCE_CACHE = "kotgent-push-preference-v1";
const PUSH_PREFERENCE_URL = "/.kotgent-push-preference";
const GENERIC_TAG = "kotgent-attention";
const GENERIC_BODY = "A session needs your attention.";
let pushLifecycle = Promise.resolve();

// Activate updates without waiting for every tab to close.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

// Intentionally omit respondWith: without the daemon, there is no useful offline shell.
self.addEventListener("fetch", () => { /* default network handling */ });

self.addEventListener("push", (event) => {
  event.waitUntil(showAttention());
});

self.addEventListener("pushsubscriptionchange", (event) => {
  event.waitUntil(queuePushLifecycle(() => syncPushSubscription(event)));
});

self.addEventListener("message", (event) => {
  const message = event.data;
  if (!message || message.type !== PUSH_PREFERENCE_MESSAGE || typeof message.enabled !== "boolean") return;
  const reply = event.ports && event.ports[0];
  const endpoints = Array.isArray(message.endpoints)
    ? message.endpoints.filter((endpoint) => typeof endpoint === "string" && endpoint.length > 0)
    : [];
  const applied = queuePushLifecycle(() => applyPushPreference(message.enabled, endpoints));
  const answer = (value) => {
    try {
      if (reply) reply.postMessage(value);
    } catch (_) {}
  };
  event.waitUntil(applied.then(
    () => answer(true),
    () => answer(false),
  ));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  event.waitUntil(openSession(data.sessionId));
});

async function postPushState(url, body) {
  const response = await fetch(url, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error("push subscription synchronization failed: HTTP " + response.status);
}

async function registerPushSubscription(subscription) {
  const json = subscription.toJSON();
  const keys = json.keys || {};
  await postPushState(PUSH_SUBSCRIBE_URL, {
    endpoint: json.endpoint,
    p256dh: keys.p256dh || "",
    auth: keys.auth || "",
  });
}

async function unregisterPushSubscription(endpoint) {
  await postPushState(PUSH_UNSUBSCRIBE_URL, { endpoint: endpoint });
}

function queuePushLifecycle(operation) {
  const queued = pushLifecycle.catch(() => {}).then(operation);
  pushLifecycle = queued.catch(() => {});
  return queued;
}

// Cache is only a one-record preference store; it is never used for fetch responses.
async function storePushPreference(enabled) {
  const cache = await self.caches.open(PUSH_PREFERENCE_CACHE);
  await cache.put(PUSH_PREFERENCE_URL, new Response(enabled ? "1" : "0"));
}

async function pushIsStillWanted() {
  if (Notification.permission !== "granted") return false;
  try {
    const response = await self.caches.match(
      PUSH_PREFERENCE_URL,
      { cacheName: PUSH_PREFERENCE_CACHE },
    );
    return !!response && (await response.text()) === "1" && Notification.permission === "granted";
  } catch (_) {
    return false;
  }
}

// Persist OFF before deleting both remembered daemon endpoints and the current browser subscription.
async function applyPushPreference(enabled, rememberedEndpoints) {
  await storePushPreference(enabled);
  if (enabled) return;
  const daemonDrops = new Map();
  const startDaemonDrop = (endpoint) => {
    if (!endpoint || daemonDrops.has(endpoint)) return;
    daemonDrops.set(
      endpoint,
      unregisterPushSubscription(endpoint).then(() => true).catch(() => false),
    );
  };
  rememberedEndpoints.forEach(startDaemonDrop);
  let subscription = null;
  try {
    subscription = await self.registration.pushManager.getSubscription();
  } catch (_) {}
  if (subscription) startDaemonDrop(subscription.endpoint);
  await Promise.allSettled([
    ...Array.from(daemonDrops.values()),
    subscription ? subscription.unsubscribe() : Promise.resolve(false),
  ]);
}

async function discardPushSubscription(subscription) {
  await Promise.allSettled([
    unregisterPushSubscription(subscription.endpoint),
    subscription.unsubscribe(),
  ]);
}

// Store a rotated endpoint before removing the old one; recreate a missing replacement only if still wanted.
async function syncPushSubscription(event) {
  const oldSubscription = event.oldSubscription || null;
  let replacement = event.newSubscription || null;
  if (!replacement && oldSubscription && await pushIsStillWanted()) {
    try {
      replacement = await self.registration.pushManager.subscribe(oldSubscription.options);
    } catch (_) {
      replacement = null;
    }
  }
  if (replacement && !(await pushIsStillWanted())) {
    await discardPushSubscription(replacement);
    replacement = null;
  }
  if (replacement) {
    await registerPushSubscription(replacement);
    // Compensate if OFF crossed the non-cancellable registration POST.
    if (!(await pushIsStillWanted())) {
      await discardPushSubscription(replacement);
      replacement = null;
    }
  }
  if (oldSubscription && (!replacement || oldSubscription.endpoint !== replacement.endpoint)) {
    await unregisterPushSubscription(oldSubscription.endpoint);
  }
}

async function waitingSessions() {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), SESSIONS_TIMEOUT_MS);
  try {
    const resp = await fetch(SESSIONS_URL, {
      credentials: "include",
      signal: controller.signal,
    });
    if (!resp.ok) return [];
    const list = await resp.json();
    if (!Array.isArray(list)) return [];
    return list.filter((s) => s && s.needsAttention && !s.archived);
  } catch (_) {
    return [];
  } finally {
    clearTimeout(timeout);
  }
}

function sessionName(s) {
  return (s && (s.name || s.tmuxSession || s.id)) || "A session";
}

async function showAttention() {
  const waiting = await waitingSessions();
  if (waiting.length === 0) {
    await self.registration.showNotification(TITLE, {
      body: GENERIC_BODY,
      tag: GENERIC_TAG,
      renotify: false,
    });
    return;
  }
  await Promise.all(waiting.map((s) => self.registration.showNotification(TITLE, {
    body: sessionName(s) + " needs your attention.",
    tag: s.id,
    renotify: false,
    data: { sessionId: s.id },
  })));
}

// Focused clients must also switch sessions; focus alone leaves the old session selected.
async function openSession(sessionId) {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  if (clients.length > 0) {
    const client = clients[0];
    if (sessionId) {
      try { client.postMessage({ type: "select-session", sessionId: sessionId }); } catch (_) {}
    }
    if ("focus" in client) return client.focus();
    return undefined;
  }
  return self.clients.openWindow(sessionId ? "/?session=" + encodeURIComponent(sessionId) : "/");
}
