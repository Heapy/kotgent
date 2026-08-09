/*
 * The kotgent service worker: the half of the notification path that runs when nothing else does.
 *
 * Served from `/sw.js` so its scope is the whole origin — a worker registered from `/lib/` could never
 * control `/`, and on iOS an installed PWA's push permission is bound to that scope.
 *
 * ## Why a push carries no payload
 * The daemon POSTs an EMPTY message to the push service (RFC 8030 allows it), so this worker is told
 * "something happened" and nothing else. It then fetches `/api/v1/sessions` with the page's cookie to learn
 * WHICH sessions are waiting. That is what buys the whole feature without RFC 8291 payload encryption
 * (ECDH P-256 + HKDF + AES-128-GCM, which Kotlin/Native has no BigInteger to implement).
 *
 * The cost is that this handler needs the network at wake time. When the fetch fails — an expired
 * Cloudflare Access session, a rotated master token, no connectivity — it must STILL show something:
 * the subscription is `userVisibleOnly: true`, so a push that ends without a notification is a promise
 * broken to the browser (Safari substitutes its own "…updated in the background" filler, and Chrome
 * eventually revokes the subscription). Silence is never an option here; the generic fallback text is.
 *
 * Repeat pushes for one session collapse onto a single banner via `tag` = the session id, with
 * `renotify: false` so an already-visible banner is replaced quietly rather than re-buzzing the phone.
 *
 * This file is a CLASSIC worker script — no `import`, no bare specifiers, nothing from `lib/`. It is
 * registered without `{ type: "module" }` because module workers are still the narrower path on Safari,
 * and the deep-link shape below (`/?session=<id>`) is the one thing it shares with `app.js`.
 */

/* eslint-env serviceworker */

// The three daemon paths this worker talks to. They spell `/api/v1` out rather than importing it: a
// classic worker has no module graph, and this file is the ONE client that can outlive every page that
// could have told it the prefix moved (see the third compatibility break on `API_PREFIX`).
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

// Take over as soon as a new worker is installed: the shell is served `Cache-Control: no-cache`, so a
// deploy must not leave yesterday's push handler in charge until every tab is closed.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

/**
 * Straight to the network for everything. Registering the handler at all is what makes Chrome consider
 * the app installable; not calling `respondWith` leaves the browser's own fetch untouched, which is what
 * we want — there is no offline story here (without the daemon there is nothing to show).
 */
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
    } catch (_) { /* the page may have closed after posting */ }
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

/** Serialize preference writes and browser-initiated rotations under the worker's waitUntil lifetime. */
function queuePushLifecycle(operation) {
  const queued = pushLifecycle.catch(() => {}).then(operation);
  pushLifecycle = queued.catch(() => {});
  return queued;
}

/**
 * Persist the page's origin-wide preference where a worker can read it after every client disappears.
 * This cache is only a one-record state store; the fetch handler remains deliberately network-only.
 */
async function storePushPreference(enabled) {
  const cache = await self.caches.open(PUSH_PREFERENCE_CACHE);
  await cache.put(PUSH_PREFERENCE_URL, new Response(enabled ? "1" : "0"));
}

/** Missing/corrupt state and storage failures fail closed; permission is re-read after the cache await. */
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

/**
 * Apply one serialized page choice. OFF persists first, starts remembered daemon deletes, then also removes
 * whatever browser subscription exists now; therefore it compensates a whole rotation that won the queue
 * before the OFF message even when the sending page has already closed.
 */
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
  } catch (_) { /* remembered daemon cleanup still completes */ }
  if (subscription) startDaemonDrop(subscription.endpoint);
  await Promise.allSettled([
    ...Array.from(daemonDrops.values()),
    subscription ? subscription.unsubscribe() : Promise.resolve(false),
  ]);
}

/** Undo a replacement that crossed an explicit OFF. Start daemon cleanup before dropping the browser side. */
async function discardPushSubscription(subscription) {
  await Promise.allSettled([
    unregisterPushSubscription(subscription.endpoint),
    subscription.unsubscribe(),
  ]);
}

/**
 * Propagate a browser-initiated endpoint/key rotation while no page is open. New details are stored before
 * the obsolete endpoint is removed, and a same-endpoint key rotation is only upserted. Some browsers report
 * an expired subscription without a replacement, so make one best-effort attempt with the old options; a
 * lost permission rejects that attempt and leaves only the obsolete daemon row to remove.
 */
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
    // OFF may cross the non-cancellable POST after its own delete. Re-read intent and compensate after it.
    if (!(await pushIsStillWanted())) {
      await discardPushSubscription(replacement);
      replacement = null;
    }
  }
  if (oldSubscription && (!replacement || oldSubscription.endpoint !== replacement.endpoint)) {
    await unregisterPushSubscription(oldSubscription.endpoint);
  }
}

/** The sessions the daemon says are waiting on the operator, or an empty list if it could not be asked. */
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
    // The same filter the daemon's own AttentionTracker uses: an archived ("done") session never rings.
    return list.filter((s) => s && s.needsAttention && !s.archived);
  } catch (_) {
    return [];   // offline / signed out — the caller still shows the generic banner
  } finally {
    clearTimeout(timeout);
  }
}

function sessionName(s) {
  return (s && (s.name || s.tmuxSession || s.id)) || "A session";
}

/** Raise one banner per waiting session, or the generic one when we could not find out which. */
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

/**
 * Bring the operator to [sessionId]. An already-open window is focused and told to switch (a focus alone
 * would leave whatever session was on screen, which makes the tap useless in the common case); otherwise
 * the app is opened deep-linked, and `app.js` reads `?session=` on load.
 */
async function openSession(sessionId) {
  const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
  if (clients.length > 0) {
    const client = clients[0];
    if (sessionId) {
      try { client.postMessage({ type: "select-session", sessionId: sessionId }); } catch (_) { /* gone */ }
    }
    if ("focus" in client) return client.focus();
    return undefined;
  }
  return self.clients.openWindow(sessionId ? "/?session=" + encodeURIComponent(sessionId) : "/");
}
