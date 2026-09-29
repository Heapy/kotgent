/*
 * Classic, root-scoped, network-only worker. Pushes are payloadless, so it fetches notifications; a
 * failed fetch still shows a generic banner because the subscription promises user-visible delivery.
 */

import { apiPath, NOTIFICATIONS_URL, SUBSCRIBE_URL, UNSUBSCRIBE_URL } from "./lib/api-paths.ts";
import { DEEP_LINK_PARAM, PUSH_PREFERENCE_MESSAGE } from "./lib/push-messages.ts";

declare const self: ServiceWorkerGlobalScope;

// WebWorker's NotificationOptions omits the Notifications API's renotify option.
declare global {
  interface NotificationOptions {
    renotify?: boolean;
  }
}

interface PushPreferenceMessage {
  type?: unknown;
  enabled?: unknown;
  endpoints?: unknown;
}

interface NotificationCandidate {
  type?: unknown;
  sessionId?: unknown;
  sessionName?: unknown;
  id?: unknown;
  provider?: unknown;
  usedBefore?: unknown;
  usedBeforeSeenAt?: unknown;
}

type InboxNotification = {
  type: "session.attention";
  sessionId: string;
  sessionName?: unknown;
} | {
  type: "usage.reset";
  id: string;
  provider: string;
  usedBefore: number;
  usedBeforeSeenAt: number;
};

interface NotificationData {
  type?: string;
  sessionId?: string;
}

const TITLE = "Kotgent — needs attention";
const NOTIFICATIONS_TIMEOUT_MS = 10_000;
const PUSH_PREFERENCE_CACHE = "kotgent-push-preference-v1";
const PUSH_PREFERENCE_URL = "/.kotgent-push-preference";
const GENERIC_TAG = "kotgent-attention";
const GENERIC_BODY = "Open Kotgent to see recent notifications.";
let pushLifecycle = Promise.resolve();

// Activate updates without waiting for every tab to close.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

// Intentionally omit respondWith: without the daemon, there is no useful offline shell.
self.addEventListener("fetch", () => { /* default network handling */ });

self.addEventListener("push", (event) => {
  event.waitUntil(showNotifications());
});

self.addEventListener("pushsubscriptionchange", (event) => {
  event.waitUntil(queuePushLifecycle(() => syncPushSubscription(event)));
});

self.addEventListener("message", (event) => {
  const message: PushPreferenceMessage | null = event.data;
  if (!message || message.type !== PUSH_PREFERENCE_MESSAGE || typeof message.enabled !== "boolean") return;
  const reply = event.ports && event.ports[0];
  const enabled = message.enabled;
  const endpoints = Array.isArray(message.endpoints)
    ? message.endpoints.filter((endpoint: unknown): endpoint is string => typeof endpoint === "string" && endpoint.length > 0)
    : [];
  const applied = queuePushLifecycle(() => applyPushPreference(enabled, endpoints));
  const answer = (value: boolean) => {
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
  const data: NotificationData = event.notification.data || {};
  event.waitUntil(openNotification(data));
});

async function postPushState(url: string, body: unknown) {
  const response = await fetch(url, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error("push subscription synchronization failed: HTTP " + response.status);
}

async function registerPushSubscription(subscription: PushSubscription) {
  const json = subscription.toJSON();
  const keys = json.keys || {};
  await postPushState(apiPath(SUBSCRIBE_URL), {
    endpoint: json.endpoint,
    p256dh: keys["p256dh"] || "",
    auth: keys["auth"] || "",
  });
}

async function unregisterPushSubscription(endpoint: string) {
  await postPushState(apiPath(UNSUBSCRIBE_URL), { endpoint: endpoint });
}

function queuePushLifecycle(operation: () => Promise<void>) {
  const queued = pushLifecycle.catch(() => {}).then(operation);
  pushLifecycle = queued.catch(() => {});
  return queued;
}

// Cache is only a one-record preference store; it is never used for fetch responses.
async function storePushPreference(enabled: boolean) {
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
async function applyPushPreference(enabled: boolean, rememberedEndpoints: readonly string[]) {
  await storePushPreference(enabled);
  if (enabled) return;
  const daemonDrops = new Map<string, Promise<boolean>>();
  const startDaemonDrop = (endpoint: string) => {
    if (!endpoint || daemonDrops.has(endpoint)) return;
    daemonDrops.set(
      endpoint,
      unregisterPushSubscription(endpoint).then(() => true).catch(() => false),
    );
  };
  rememberedEndpoints.forEach(startDaemonDrop);
  let subscription: PushSubscription | null = null;
  try {
    subscription = await self.registration.pushManager.getSubscription();
  } catch (_) {}
  if (subscription) startDaemonDrop(subscription.endpoint);
  await Promise.allSettled([
    ...Array.from(daemonDrops.values()),
    subscription ? subscription.unsubscribe() : Promise.resolve(false),
  ]);
}

async function discardPushSubscription(subscription: PushSubscription) {
  await Promise.allSettled([
    unregisterPushSubscription(subscription.endpoint),
    subscription.unsubscribe(),
  ]);
}

// Store a rotated endpoint before removing the old one; recreate a missing replacement only if still wanted.
async function syncPushSubscription(event: PushSubscriptionChangeEvent) {
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

async function currentNotifications(): Promise<InboxNotification[]> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), NOTIFICATIONS_TIMEOUT_MS);
  try {
    const resp = await fetch(apiPath(NOTIFICATIONS_URL), {
      credentials: "include",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!resp.ok) return [];
    const list: unknown = await resp.json();
    if (!Array.isArray(list)) return [];
    return list.filter((item: NotificationCandidate | null): item is InboxNotification => {
      if (!item) return false;
      if (item.type === "session.attention") return typeof item.sessionId === "string" && item.sessionId.length > 0;
      return item.type === "usage.reset" && typeof item.id === "string" && item.id.length > 0
        && typeof item.provider === "string" && item.provider.length > 0
        && typeof item.usedBefore === "number" && Number.isFinite(item.usedBefore) && item.usedBefore >= 0 && item.usedBefore <= 100
        && typeof item.usedBeforeSeenAt === "number" && Number.isFinite(item.usedBeforeSeenAt)
        && Number.isFinite(new Date(item.usedBeforeSeenAt).getTime());
    });
  } catch (_) {
    return [];
  } finally {
    clearTimeout(timeout);
  }
}

async function showNotifications() {
  const notifications = await currentNotifications();
  if (notifications.length === 0) {
    await self.registration.showNotification("Kotgent", {
      body: GENERIC_BODY,
      tag: GENERIC_TAG,
      renotify: false,
    });
    return;
  }
  await Promise.all(notifications.map((item) => {
    if (item.type === "session.attention") {
      return self.registration.showNotification(TITLE, {
        body: (item.sessionName || item.sessionId) + " needs your attention.",
        tag: item.sessionId,
        renotify: false,
        data: { type: item.type, sessionId: item.sessionId },
      });
    }
    return self.registration.showNotification("Kotgent — early usage reset", {
      body: item.provider + " weekly limit reset early. " + item.usedBefore + "% used; last seen "
        + new Date(item.usedBeforeSeenAt).toLocaleString() + ".",
      tag: item.id,
      renotify: false,
      data: { type: item.type },
    });
  }));
}

// Focused clients must also switch sessions; focus alone leaves the old session selected.
async function openNotification(data: NotificationData) {
  const overview = data.type === "usage.reset";
  const sessionId = overview ? null : data.sessionId;
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  const client = clients[0];
  if (client) {
    if (sessionId) {
      try { client.postMessage({ type: "select-session", sessionId: sessionId }); } catch (_) {}
    } else if (overview) {
      // A reset opens the overview even when this client is displaying a session or task.
      try {
        const root = await client.navigate("/");
        if (root) return root.focus();
      } catch (_) {}
      return self.clients.openWindow("/");
    }
    if ("focus" in client) return client.focus();
    return undefined;
  }
  return self.clients.openWindow(sessionId ? "/?" + DEEP_LINK_PARAM + "=" + encodeURIComponent(sessionId) : "/");
}
