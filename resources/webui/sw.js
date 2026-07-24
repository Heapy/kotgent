/*
 * The kotgent service worker: the half of the notification path that runs when nothing else does.
 *
 * Served from `/sw.js` so its scope is the whole origin — a worker registered from `/lib/` could never
 * control `/`, and on iOS an installed PWA's push permission is bound to that scope.
 *
 * ## Why a push carries no payload
 * The daemon POSTs an EMPTY message to the push service (RFC 8030 allows it), so this worker is told
 * "something happened" and nothing else. It then fetches `/sessions` with the page's cookie to learn WHICH
 * sessions are waiting. That is what buys the whole feature without RFC 8291 payload encryption
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

const TITLE = "Kotgent — needs attention";
const SESSIONS_URL = "/sessions";
const GENERIC_TAG = "kotgent-attention";
const GENERIC_BODY = "A session needs your attention.";

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

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  event.waitUntil(openSession(data.sessionId));
});

/** The sessions the daemon says are waiting on the operator, or an empty list if it could not be asked. */
async function waitingSessions() {
  try {
    const resp = await fetch(SESSIONS_URL, { credentials: "include" });
    if (!resp.ok) return [];
    const list = await resp.json();
    if (!Array.isArray(list)) return [];
    // The same filter the daemon's own AttentionTracker uses: an archived ("done") session never rings.
    return list.filter((s) => s && s.needsAttention && !s.archived);
  } catch (_) {
    return [];   // offline / signed out — the caller still shows the generic banner
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
