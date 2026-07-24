# PWA on mobile + server-sent push notifications

## Overview

Turn the kotgent Web UI into an installable PWA that is usable as a full terminal on a phone, and add
**server-sent Web Push** so a session entering `needs_attention` reaches the operator when the tab (or the
whole app) is closed.

Today `resources/webui/lib/notify.js` raises a `Notification` only while the page is alive and the `/events`
WebSocket is open — a phone with the screen locked never learns that an agent is waiting for an approval.
Web Push fixes exactly that: the daemon POSTs to the browser's push service (Apple / Google), the OS wakes
the service worker, and the service worker raises a real system notification.

Three things make this one plan rather than three:

1. **Push on iOS exists only inside an installed PWA** (iOS 16.4+). So the manifest + service worker are not
   polish, they are the precondition for the notification half.
2. **An installed iOS PWA has its own cookie jar** — the QR sign-in link opened in Safari does not carry the
   session cookie into the home-screen app, so the login flow needs a form the PWA itself can complete, and
   the SPA needs to *route* an unauthenticated launch to that form.
3. A notification that opens a UI unusable on a phone is pointless, so the mobile terminal layout ships with
   it.

Explicit non-goals: encrypted push payloads (RFC 8291) — see the risk fork below; push on any trigger other
than `needs_attention`; resolving the "resize is last-active" conflict between a phone and a desktop attach
(accepted as-is); offline support (without the daemon there is nothing to show).

## Context (from discovery)

- **Files/components involved:**
  - `resources/webui/` — Preact SPA, no build step: `index.html`, `app.js`, `style.css`,
    `lib/{api,notify,prefs,sessions,paths,qr}.js`, `components/{Sidebar,TerminalPane,dialogs}.js`,
    `vendor/` (xterm, preact, htm, qrcode). The per-device notifications toggle is `#notify-toggle` in
    **`components/Sidebar.js`** (not in `PreferencesDialog`); `lib/api.js` throws
    "Session expired — run \`kotgent web\` to sign in again." on any `401`.
  - `src/transport/` — `Server.kt` (`KotgentServer` constructor **and** the `production(...)` companion
    factory the daemon actually calls, `staticWebUi`, `serveStaticFile`, `contentTypeFor`),
    `AuthRoutes.kt` (`AUTH_PAGE_HTML`, `/auth`, `/auth/ticket`, `/auth/exchange`), `Tickets.kt`
    (`TicketStore`, `TICKET_TTL_MILLIS`), `Auth.kt` (`authenticated`, `loopbackOnly`, `writePrivateFile`,
    `createPrivateFileExclusive`), `EventsWs.kt` (the `onSubscription` snapshot idiom).
  - `src/store/` — `EventStore` (`sessionUpdates: SharedFlow<SessionUpdate>` with `replay = 0`,
    `listSessions()`), `SqliteEventStore` (`using(driver)`, idempotent DDL in `init`).
  - `src/cli/Commands.kt` — `daemon` (driver → store → `bgScope` → `rebuildRegistryFromStore()` →
    `Reconciler.reconcile()` → `KotgentServer.production(...)`), `web`.
  - `src/tmux/ProcessRunner.kt` — `object ProcessRunner.run(argv): ProcessResult`, which exposes
    **`stdoutBytes`/`stderrBytes` as `ByteArray`** (`stdout`/`stderr` are decoding conveniences); popen with
    a CLOEXEC sweep before the fork.
  - `src/crypto/` — `Sha256`, `Hmac`, `Hex` (pure Kotlin; no Base64 yet).
  - `sqldelight/io/kotgent/db/` — `Events.sq`, `Sessions.sq`.
  - `test/transport/WebUiServingTest.kt` — hard-codes the list of served `lib/`/`components/` modules
    (`daemonServesTheComponentAndLibModules`), so every new module must be added there.
- **Related patterns found:**
  - Idempotent schema migration lives in `SqliteEventStore.init` (`driver.hasColumn(...)` + `ALTER`), because
    the `sqldelight-gen` plugin drops `.sqm` files and `Schema.create()` only runs on a fresh DB.
  - Every edge sits behind a pure-Kotlin interface with a fake for tests (`PtyHandle`, `TmuxControl`,
    `LocalTty`) — KT-78062 means custom cinterop never links into the test binary. `ProcessRunner` is
    `popen`-based and therefore usable *from* tests.
  - Shared mutable state in the transport is either `Mutex`-guarded (`TicketStore`) or atomic
    (`TokenHolder`) — never a plain field.
  - There is **no JS test harness**; frontend work is verified by an ESM syntax check plus server-side
    serving tests and a manual checklist.
- **Dependencies identified:** `io.ktor:ktor-client-darwin` is new (`ktor-client-cio` cannot do HTTPS on
  Kotlin/Native at all — Ktor's `openTLSSession` for non-JVM is
  `error("TLS sessions are not supported on Native platform.")`). **`/usr/bin/openssl`** (LibreSSL 3.3.6,
  present on every macOS) is used for the VAPID P-256 key and its ES256 signature, driven through the
  existing `ProcessRunner`. The absolute path is pinned deliberately: `which -a openssl` on a dev machine
  resolves to Homebrew's OpenSSL first, and the launchd daemon runs on the PATH snapshotted by
  `kotgent install`, so a bare `openssl` would behave differently in tests and in production.

## Development Approach

- **testing approach**: Regular (code first, then tests) — matching how this repo has been built.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - Kotlin changes → unit tests in `test/<area>/`, success **and** error paths
  - JS changes → there is no JS test harness: verify with `node --check` (ESM), add every new module to
    `WebUiServingTest`'s served-module list, and rely on the manual checklist in Post-Completion for
    behaviour. Do not fake a JS test into existence.
- **CRITICAL: all tests must pass before starting next task** — `./kotlin build` then `./kotlin test`
  (build first: `PtyTest` execs the `ptycheck` binary). Baseline: **428 run / 428 passed / 0 skipped**.
- **CRITICAL: update this plan file when scope changes during implementation**
- maintain backward compatibility: an existing `~/.kotgent/kotgent.db` must keep working (new table via
  `CREATE TABLE IF NOT EXISTS`), and a daemon with no push subscriptions must behave exactly as today.

## Testing Strategy

- **unit tests**: required for every Kotlin task (see above).
- **e2e tests**: the project has none (no Playwright/Cypress, no JS runner). The equivalent here is
  `test/transport/WebUiServingTest.kt` for anything the daemon serves, plus the manual checklist.
- **integration**: `PushSender` is tested against a fake transport, never against Apple/Google. Nothing in
  the suite may make an outbound network call.
- **the one third-party check**: `OpensslVapidSigner` is verified by having `openssl dgst -verify` validate
  the signature we produced — an outside verifier, not our own code agreeing with itself.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**Push without a payload.** RFC 8030 allows a push message with no body: the daemon POSTs an empty request
carrying only the VAPID `Authorization` header, the push service wakes the service worker, and the worker
fetches `/sessions` with its cookie to learn *which* session needs attention. This buys the whole feature
without implementing RFC 8291 encryption (ECDH P-256 + HKDF + AES-128-GCM), which in Kotlin/Native would
mean hand-writing 256-bit modular arithmetic — there is no `BigInteger` in the stdlib. Cost: the worker
needs network at wake time; if the fetch fails (expired Cloudflare Access session, rotated token) it shows a
generic "a session needs your attention".

**VAPID via the system openssl.** The P-256 keypair lives in `~/.kotgent/vapid.pem`, generated on the first
`GET /push/vapid-key` (the browser needs `applicationServerKey` *before* it can subscribe, so that request —
not the subscribe — is the generation trigger; it runs `ProcessRunner.run` inside a handler, as the tmux
paths already do). `/usr/bin/openssl ecparam -name prime256v1 -genkey -noout` writes the PEM to **stdout**,
which we persist with `createPrivateFileExclusive` (0600, first-writer-wins if two requests race) — openssl's
own `-out` would leave the key 0644, verified on this machine. The public point comes from
`ec -pubout -outform DER`: 91-byte SPKI minus its 26-byte header = the 65-byte uncompressed point. Signing
input goes in through a temp file (popen offers no writable stdin) and the DER signature comes back on
`stdoutBytes` — no output temp file, and neither value is secret. The signed JWT is cached **per
push-service origin** (Apple's `aud` differs from Google's) with a 12h `exp`, refreshed when under an hour
remains — so openssl runs about twice a day, not once per notification.

**Delivery over NSURLSession.** `HttpClient(Darwin)` with a mandatory `HttpTimeout`; the system trust store
validates the certificate chain. `ktor-client-cio` is not an option on native.

**Detection is a pure function.** `AttentionTracker` holds `SessionId → wasNeedingAttention` and answers "is
this a `false → true` transition?". It is seeded from `store.listSessions()` **inside `onSubscription`** —
the same idiom `EventsWs.streamGlobalUpdates` uses, because `sessionUpdates` has `replay = 0` and anything
emitted between subscribe and snapshot would otherwise be lost. Without the seed, the first update after a
restart about an already-waiting session would look like a fresh transition and ring the phone for nothing.
`PushNotifier` is the thin edge: it collects `sessionUpdates` on `bgScope`, started *after* the reconciler
has finished its startup writes.

**Sign-in inside the PWA.** The ticket becomes an 8-character Crockford-base32 code (alphabet without
`I`/`L`/`O`/`U`, case-insensitive with `I`/`L` → `1`, `O` → `0` normalisation), TTL cut to 5 minutes, still
single-use, plus a **global, `Mutex`-guarded** failed-exchange rate limit (per-IP is useless: traffic through
cloudflared arrives from loopback). One format, not two — keeping the long ticket for the QR while a short
code redeems the same record would leave the record only as strong as its weakest key. The rate limit is
also what now carries the argument that a plain map lookup in `redeem` is safe, which the `Tickets.kt` KDoc
currently pins on 256 bits of entropy; that KDoc must be rewritten with the format.

**Mobile is layout + input, not a second UI.** Same Preact tree: the sidebar becomes a drawer under a width
media query, `100dvh` and `env(safe-area-inset-*)` replace `100vh`, `visualViewport` drives the terminal
resize when the software keyboard appears, and a special-keys bar sends raw bytes (`\x1b`, `\t`, `\x1b[Z`,
`\x1b[A`, `\x03`) down the existing raw terminal WebSocket — no server-side protocol change.

## Technical Details

**New table** (`sqldelight/io/kotgent/db/PushSubscriptions.sq`):

| column       | type                | note                                                     |
|--------------|---------------------|----------------------------------------------------------|
| `endpoint`   | `TEXT PRIMARY KEY`  | the push service URL; identity of a device subscription   |
| `p256dh`     | `TEXT NOT NULL`     | unused today; kept so RFC 8291 needs no re-subscribe      |
| `auth`       | `TEXT NOT NULL`     | same                                                      |
| `created_at` | `INTEGER NOT NULL`  | epoch millis                                              |

No device-label column: nothing in this plan writes or reads one, and there is no subscription-list UI.
Existing databases get the table from `CREATE TABLE IF NOT EXISTS` in `SqlitePushStore.init` — **not** an
`ALTER`, so no `PRAGMA table_info` guard is needed (`IF NOT EXISTS` cannot fail and cannot log a red wall).

**HTTP request the daemon sends:** `POST <endpoint>`, empty body, headers
`Authorization: vapid t=<jwt>, k=<base64url 65-byte public point>`, `TTL: 1800`,
`Topic: <short hash of sessionId>` (lets the push service collapse queued messages per session).
Responses: `200`/`201` success; `404`/`410` → delete the subscription row; `429`/`5xx` → skip, there is no
retry queue (the event goes stale faster than a retry would help).

**VAPID JWT:** header `{"typ":"JWT","alg":"ES256"}`, claims `{"aud":<endpoint origin>,"exp":<now+12h>,
"sub":<subject>}`, signature = raw `r||s` (64 bytes) decoded from openssl's DER (70–72 bytes; the DER→raw
conversion must handle a leading zero byte and a short `r` or `s`). The subject is the configured
`publicUrl` when there is one (an `https:` URL is unambiguously acceptable to Apple's validator) and
`mailto:kotgent@localhost` only as the loopback-only fallback.

**Service worker** (`resources/webui/sw.js`, served at `/sw.js` so its scope is `/`):
- `push` → `fetch("/sessions", {credentials:"include"})` → sessions with `needsAttention && !archived` → one
  `showNotification` per session, `tag: <id>`, `renotify: false`. Always shows something: `userVisibleOnly`
  forbids a silent push, and staying silent makes Safari display its own "updated in the background" filler.
- `notificationclick` → focus an existing client, else `openWindow("/?session=<id>")`; `app.js` reads
  `?session=` on load and selects that session.
- `fetch` → straight to network, no cache (satisfies Chrome's installability check).

**Static serving:** `contentTypeFor` learns `.webmanifest` → `application/manifest+json`;
`serveStaticFile` sends `Cache-Control: no-cache` for `index.html` and `sw.js` (a cached service worker would
pin an old UI for a day).

## What Goes Where

- **Implementation Steps** (`[ ]`): everything in this repo — Kotlin, SQL, JS, CSS, tests, docs.
- **Post-Completion** (no checkboxes): the device-level checks that cannot run in CI — real delivery to a
  locked iPhone, home-screen install, Android and desktop delivery.

## Implementation Steps

### Task 1: Push subscription table and store

**Files:**
- Create: `sqldelight/io/kotgent/db/PushSubscriptions.sq`
- Create: `src/push/PushStore.kt`
- Create: `src/push/SqlitePushStore.kt`
- Create: `test/push/PushStoreTest.kt`

- [ ] add `PushSubscriptions.sq`: table DDL plus `selectAll`, `upsert` (by `endpoint`), `deleteByEndpoint`
- [ ] add `src/push/PushStore.kt` — `data class PushSubscription(endpoint, p256dh, auth, createdAt)` and
      `interface PushStore { suspend fun list(); suspend fun save(sub); suspend fun remove(endpoint) }`
- [ ] add `SqlitePushStore(driver)` over the same `SqlDriver` the event store uses, with
      `CREATE TABLE IF NOT EXISTS` in `init` for pre-existing databases; `EventStore` is untouched
- [ ] write tests: save → list round-trip, re-saving the same endpoint updates instead of duplicating,
      remove is idempotent
- [ ] write test: opening the store over a driver whose schema predates the table (create the DB from a
      schema without it, like `EventStoreTest` does for `archived`) still works
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 2

### Task 2: AttentionTracker (pure transition detection)

**Files:**
- Create: `src/push/AttentionTracker.kt`
- Create: `test/push/AttentionTrackerTest.kt`

- [ ] add `AttentionTracker` holding `SessionId → Boolean`, with `seed(sessions: List<SessionMeta>)` and
      `isNewAttention(update: SessionUpdate): Boolean` (true only on `false → true`)
- [ ] make it host-free: no store, no I/O, no clock
- [ ] write tests: a single transition fires once; staying in attention does not re-fire; leaving and
      re-entering fires again
- [ ] write test: seeding from an already-attention session makes the next identical update **not** fire
      (the daemon-restart case)
- [ ] write test: an unknown session id whose first update is already `needsAttention` fires exactly once
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 3

### Task 3: /push routes and server wiring

**Files:**
- Create: `src/transport/PushRoutes.kt`
- Modify: `src/transport/Server.kt`
- Create: `test/transport/PushRoutesTest.kt`

- [ ] add `Route.pushRoutes(store: PushStore, vapidPublicKey: suspend () -> String, json: Json)` with
      `GET /push/vapid-key`, `POST /push/subscribe`, `POST /push/unsubscribe`
- [ ] validate the subscribe body: absolute `https://` endpoint, non-empty `p256dh`/`auth`, else `400`
- [ ] mount inside `authenticated { }` in the `KotgentServer` constructor behind new nullable params
      (`pushStore`, `vapidPublicKey`); when either is null the routes are not mounted, so existing harnesses
      are unaffected
- [ ] give `KotgentServer.production(...)` the same two nullable params and forward them — that companion
      factory, not the constructor, is what `Commands.daemon` calls
- [ ] write tests: subscribe with a cookie and with a Bearer both succeed; the row lands in the store;
      unsubscribe removes it; `GET /push/vapid-key` returns the injected key
- [ ] write tests: no credential → `401`; `POST` without an `Origin` → refused by the existing rule;
      malformed endpoint → `400`
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 4

### Task 4: Base64Url encoder

**Files:**
- Create: `src/crypto/Base64Url.kt`
- Create: `test/crypto/Base64UrlTest.kt`

- [ ] add `base64Url(bytes): String` only (RFC 4648 §5, `-`/`_`, no padding) — nothing in this feature
      decodes base64url on the Kotlin side, so no decoder is written until RFC 8291 needs one
- [ ] keep it pure Kotlin next to `Hex.kt`; do not add a second encoder anywhere else
- [ ] write tests: RFC 4648 vectors, all three padding-remainder lengths, empty input
- [ ] write test: output never contains `+`, `/` or `=` for high-bit bytes
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 5

### Task 5: VAPID keypair (generate, persist 0600, expose)

**Files:**
- Create: `src/push/VapidKey.kt`
- Create: `test/push/VapidKeyTest.kt`

- [ ] add `VapidKey` that reads `~/.kotgent/vapid.pem` or generates it once:
      `/usr/bin/openssl ecparam -name prime256v1 -genkey -noout` → PEM on `stdoutBytes` →
      `createPrivateFileExclusive(path, pem)` (0600, first-writer-wins). **Do not use openssl's `-out`** —
      verified on macOS it writes 0644 and the key would ship world-readable
- [ ] extract the public point: `/usr/bin/openssl ec -in <pem> -pubout -outform DER` → drop the 26-byte SPKI
      header → 65 bytes starting with `0x04` → `base64Url`
- [ ] inject the openssl path, the key path and the `ProcessRunner` seam so tests run against a temp
      directory; pin `/usr/bin/openssl` by default (a bare name resolves to Homebrew's build locally and to
      nothing under a stale launchd PATH)
- [ ] write tests: generating twice returns the same key (no silent re-mint — a new key kills every existing
      subscription); the public point is 65 bytes and starts with `0x04`; the file's mode is 0600
- [ ] write test: a corrupt/empty pem surfaces a clear error instead of a half-broken key
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 6

### Task 6: ECDSA DER → raw r||s

**Files:**
- Create: `src/push/EcdsaDer.kt`
- Create: `test/push/EcdsaDerTest.kt`

- [ ] add `derToRawSignature(der: ByteArray): ByteArray` returning exactly 64 bytes (left-padded `r`, `s`)
- [ ] reject malformed input explicitly (bad tag, length overrun, `r`/`s` longer than 33 bytes)
- [ ] write tests with fixtures: the common 70-byte form, the 71/72-byte forms where `r` or `s` carries a
      leading `0x00`, and a short `r` that must be left-padded
- [ ] write tests: malformed DER throws rather than returning garbage
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 7

### Task 7: VAPID JWT builder and per-origin token cache (pure)

**Files:**
- Create: `src/push/VapidJwt.kt`
- Create: `test/push/VapidJwtTest.kt`

- [ ] add pure `pushServiceOrigin(endpoint)` (scheme + host — the JWT `aud`) and
      `vapidSigningInput(aud, exp, sub)` (header + claims, base64url, dot-joined)
- [ ] add `vapidSubject(publicUrl: String?)`: the public origin when configured, else
      `mailto:kotgent@localhost`
- [ ] add `VapidTokenCache(now, sign, ttl = 12h, refreshBefore = 1h)` keyed by origin, plus
      `vapidAuthorizationHeader(jwt, publicKey)`
- [ ] write tests: claims are deterministic under an injected clock; Apple and Google endpoints produce
      different `aud`; the cache returns the same token twice and re-signs once inside the refresh window
- [ ] write test: the header string matches the `vapid t=…, k=…` shape exactly
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 8

### Task 8: OpensslVapidSigner (the signing edge)

**Files:**
- Create: `src/push/VapidSigner.kt`
- Create: `test/push/OpensslVapidSignerTest.kt`

- [ ] add `interface VapidSigner { fun sign(input: String): ByteArray }`
- [ ] add `OpensslVapidSigner(keyPath, opensslPath = "/usr/bin/openssl", runner)`: signing input → temp file
      (popen has no writable stdin) → `openssl dgst -sha256 -sign <pem> <tmp>` → DER from `stdoutBytes` →
      `derToRawSignature`; the input temp file is removed in a `finally`
- [ ] surface a non-zero exit with openssl's stderr in the message (this is the "push disabled" diagnostic
      the daemon later relies on)
- [ ] write test: the signature is 64 bytes and `openssl dgst -sha256 -verify <pubkey>` accepts it —
      an outside verifier, not our own code agreeing with itself
- [ ] write tests: a missing key file and a bogus openssl path both fail with a clear error, leaving no temp
      file behind
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 9

### Task 9: Push sender over NSURLSession

**Files:**
- Modify: `module.yaml`
- Create: `src/push/PushSender.kt`
- Create: `src/push/DarwinPushTransport.kt`
- Create: `test/push/PushSenderTest.kt`

- [ ] add `io.ktor:ktor-client-darwin` to `module.yaml` dependencies (portable, no absolute paths)
- [ ] add `interface PushTransport { suspend fun post(url: String, headers: Map<String,String>): Int }` and
      `DarwinPushTransport` — `HttpClient(Darwin)` with `HttpTimeout` (never an untimed request)
- [ ] add `PushSender(store, publicKey, cache, transport)`: for each subscription build the VAPID header,
      `TTL: 1800`, `Topic: <short hash of session id>`, POST an empty body
- [ ] handle results: `200/201` ok, `404/410` → `store.remove(endpoint)`, `429/5xx` → skip, no retry queue;
      a transport exception must not propagate into the caller's coroutine
- [ ] write tests with a fake transport: success keeps the row; `410` deletes it; `429` keeps it; a throwing
      transport is swallowed and the other subscriptions are still attempted
- [ ] write test: the outgoing header set is exactly what the RFC needs (`Authorization` shape, `TTL`, `Topic`)
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 10

### Task 10: PushNotifier and daemon wiring

**Files:**
- Create: `src/push/PushNotifier.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `src/transport/Server.kt`
- Create: `test/push/PushNotifierTest.kt`

- [ ] add `PushNotifier(store, tracker, sender)` collecting `EventStore.sessionUpdates` and seeding the
      tracker from `listSessions()` **inside `.onSubscription { }`** — the `EventsWs.streamGlobalUpdates`
      idiom, required because `sessionUpdates` has `replay = 0`
- [ ] construct `SqlitePushStore` over the same `driver` in `Commands.daemon` and pass it plus the VAPID
      public-key provider into `KotgentServer.production(...)`
- [ ] start the notifier on `bgScope` **after** `rebuildRegistryFromStore()` + `Reconciler.reconcile()`, so
      startup reconciliation writes are not evaluated against a half-built world
- [ ] make a missing/unusable openssl or an unwritable `vapid.pem` degrade to "push disabled" with one
      diagnostic line — never take the daemon down
- [ ] write tests: a transition into attention triggers exactly one send with the right session id; an update
      that is not a transition sends nothing; a session already in attention at seed time sends nothing
- [ ] write test: a sender that throws does not cancel the collector (later updates still work)
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 11

### Task 11: Manifest, icons and static-serving headers

**Files:**
- Create: `resources/webui/manifest.webmanifest`
- Create: `resources/webui/icons/icon-192.png`, `resources/webui/icons/icon-512.png`,
  `resources/webui/icons/apple-touch-icon.png` (placeholders — to be replaced by the user's artwork)
- Modify: `resources/webui/index.html`
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add the manifest: `name`/`short_name` "Kotgent", `start_url: "/"`, `scope: "/"`,
      `display: "standalone"`, background/theme colours matching `style.css`, 192 + 512 icons with
      `purpose: "any maskable"`
- [ ] produce the placeholder PNGs from a committed one-colour SVG monogram via
      `qlmanage -t -s 512 -o resources/webui/icons <svg>` then `sips -z 192 192` to downscale; if QuickLook
      refuses the SVG, decode a committed base64 PNG blob with `base64 -d` instead. File names are the
      contract — the artwork is replaceable without code changes
- [ ] update `index.html`: `<link rel="manifest">`, `<link rel="apple-touch-icon">`,
      `apple-mobile-web-app-capable`, `apple-mobile-web-app-status-bar-style`, `viewport-fit=cover`
- [ ] teach `contentTypeFor` about `.webmanifest`; send `Cache-Control: no-cache` from `serveStaticFile` for
      `index.html` and `sw.js`
- [ ] write tests in `WebUiServingTest`: the manifest is served as `application/manifest+json`, an icon as
      `image/png`, and `index.html` carries `Cache-Control: no-cache`
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 12

### Task 12: Service worker, subscription flow, deep link

**Files:**
- Create: `resources/webui/sw.js`
- Create: `resources/webui/lib/push.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/lib/notify.js`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add `sw.js` with the three handlers (`push` → fetch `/sessions` → one notification per session with
      `needsAttention && !archived`, `tag` = id, generic fallback text when the fetch fails;
      `notificationclick` → focus or `openWindow("/?session=<id>")`; pass-through `fetch`)
- [ ] add `lib/push.js`: `supported()`, `subscribe()` (register `/sw.js`, `Notification.requestPermission`,
      `GET /push/vapid-key`, `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})`,
      `POST /push/subscribe`), `unsubscribe()` — every call reachable from a click handler, since iOS
      refuses the permission prompt outside a user gesture
- [ ] extend the existing `#notify-toggle` handler in **`components/Sidebar.js`** (this is where the toggle
      lives, not in `PreferencesDialog`): turning it on subscribes, off unsubscribes; when a push
      subscription is active, `notify.js` stops raising its own notification (no duplicate on an open tab)
- [ ] make `app.js` read `?session=` on load and select that session (this is what makes the notification
      tap useful)
- [ ] syntax-check the new/changed ES modules (`node --check`); add `/lib/push.js` to
      `WebUiServingTest`'s served-module list and a case that `/sw.js` is served as JavaScript with
      `Cache-Control: no-cache`
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 13

### Task 13: Short login code (format + 5-minute TTL)

**Files:**
- Modify: `src/transport/Tickets.kt`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `test/transport/TicketsTest.kt`

- [ ] replace the hex ticket value with 8 characters of Crockford base32 (alphabet without `I`,`L`,`O`,`U`)
      encoded from `randomBytes(5)` — 40 bits mapping onto exactly 8 symbols, no padding and no modulo bias
- [ ] add `normalizeTicketCode(raw)`: trim, strip spaces/dashes, upper-case, map `I`/`i`/`L`/`l` → `1` and
      `O`/`o` → `0` (both Crockford substitutions, not just `I`); `redeem` normalises before lookup
- [ ] cut `TICKET_TTL_MILLIS` to 5 minutes and rewrite the KDoc that the format change invalidates: the
      "lives ten minutes" prose, and the `redeem` rationale that justifies a plain map lookup by "256 bits of
      entropy" — at 40 bits the compensating control is the global rate limit (task 14), single use and the
      5-minute TTL
- [ ] update the user-facing copy in `dialogs.js` (`phoneBody`: "expires in 10 minutes") to 5 minutes
- [ ] write tests: issued codes are 8 chars from the alphabet only; lower-case, spaced, `L`-for-`1`,
      `I`-for-`1` and `O`-for-`0` input all redeem the same ticket; a code cannot be redeemed twice
- [ ] write tests: expiry at exactly 5 minutes; an unknown code returns null; update the "same strength as
      the master token" assertion/comment in `TicketsTest`
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 14

### Task 14: Global rate limit on ticket exchange

**Files:**
- Create: `src/transport/ExchangeRateLimit.kt`
- Modify: `src/transport/AuthRoutes.kt`
- Create: `test/transport/ExchangeRateLimitTest.kt`

- [ ] add `ExchangeRateLimit(now: () -> Long, max = 10, windowMillis = 60_000)` counting **failed** exchanges
      globally, guarded by a `Mutex` like `TicketStore` (route handlers run concurrently; a plain field would
      be a data race). Per-IP is not an option: every tunnelled request arrives from loopback
- [ ] thread one instance through `authRoutes(...)` as a parameter with a default, so it is constructed once
      per daemon and injectable (with its clock) in tests — a per-call instance would be a silent no-op
- [ ] apply it in `POST /auth/exchange`: over the limit → `429` before the code is looked at; a successful
      exchange consumes no budget
- [ ] record the accepted trade-off in the KDoc: an attacker who can reach `/auth/exchange` can deny sign-in
      in rolling 60-second windows; Cloudflare Access fronts the public surface, and the alternative
      (unbounded guessing against 40 bits) is worse
- [ ] write tests: the 11th failure in a window is refused; the window slides; successes never trip it; a
      valid code still redeems while the limiter is warm but under the cap
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 15

### Task 15: Code entry on /auth, unauthenticated routing, and showing the code

**Files:**
- Modify: `src/transport/AuthRoutes.kt`
- Modify: `resources/webui/lib/api.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `src/cli/Commands.kt`
- Modify: `test/transport/AuthRoutesTest.kt`

- [ ] extend `AUTH_PAGE_HTML`: with no `#ticket=` fragment, render a code input + submit that POSTs to the
      same `/auth/exchange`, then `location.replace("/")`; state the 5-minute life and show a clear error for
      `400`/`429`
- [ ] route an unauthenticated launch to that form: on a `401` from the initial `/sessions` load,
      `location.replace("/auth")`, and reword `api.js`'s "run `kotgent web`" message — an installed PWA opens
      at `start_url` with an empty cookie jar and cannot run a CLI command, so without this the form built
      above is unreachable
- [ ] show the code in `PhoneDialog` in large type next to the QR (the ticket response already carries it)
- [ ] print the code in `kotgent web` alongside the URL, with a one-line hint that this is what an installed
      home-screen app asks for
- [ ] write tests: `GET /auth` HTML contains the code form; a code minted by the ticket route exchanges and
      sets the cookie; a wrong code returns `400` and sets none
- [ ] syntax-check the changed modules; write a test that the CLI output contains the code
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 16

### Task 16: Mobile layout — drawer sidebar, compact header, viewport units

**Files:**
- Modify: `resources/webui/style.css`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/app.js`

- [ ] add a width media query that turns the sidebar into an overlay drawer with a hamburger toggle;
      selecting a session closes it
- [ ] move interrupt/stop/resume/done into a compact icon row in the terminal header on narrow screens
- [ ] switch the app shell to `100dvh`, add `env(safe-area-inset-*)` padding and
      `overscroll-behavior: none` (pull-to-refresh must not reload the page mid-session)
- [ ] keep the desktop layout byte-for-byte identical above the breakpoint
- [ ] syntax-check the changed modules (`node --check`); run `./kotlin build && ./kotlin test` — must pass
      before task 17

### Task 17: Keyboard-aware terminal sizing and focus

**Files:**
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/lib/prefs.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `resources/webui/style.css`

- [ ] subscribe to `visualViewport` `resize`/`scroll`: shrink the terminal container to the visible area,
      call `fit()`, and send the resulting size down the terminal WebSocket
- [ ] focus xterm's hidden textarea only from a tap handler (iOS never opens the keyboard otherwise)
- [ ] add a terminal font-size preference (three steps) to `prefs.js` (`sanitizePrefs` must coerce it) and
      to `PreferencesDialog`, applied on mount and on change with a `fit()`
- [ ] detach every listener on unmount (no leak when switching sessions)
- [ ] syntax-check the changed modules; run `./kotlin build && ./kotlin test` — must pass before task 18

### Task 18: Special-keys bar

**Files:**
- Create: `resources/webui/components/KeyBar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] expose a send seam from `TerminalPane`: the WebSocket is a local inside the `attachedId` effect, so
      store a `sendBytes` ref inside that effect and clear it on teardown — `KeyBar` writes through the ref
- [ ] add `KeyBar` with Esc, Tab, Shift+Tab, ↑ ↓ ← →, a sticky Ctrl modifier and Ctrl-C, each sending raw
      bytes (`\x1b`, `\t`, `\x1b[Z`, `\x1b[A`/`[B`/`[C`/`[D`, `\x03`)
- [ ] make the sticky Ctrl apply to the next printable key, then release itself (with a visible pressed state)
- [ ] show the bar only on narrow screens and only while a terminal is attached; never steal focus from the
      terminal (`preventDefault` on pointer-down)
- [ ] add `/components/KeyBar.js` to `WebUiServingTest`'s served-module list; syntax-check the changed
      modules; run `./kotlin build && ./kotlin test` — must pass before task 19

### Task 19: Reattach after backgrounding

**Files:**
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/app.js`

- [ ] on `visibilitychange` → visible, if the terminal socket is closed and the session is still alive,
      reconnect it (the `/events` socket already self-heals via its `onclose` retry)
- [ ] guard against double reconnects (a pending reconnect must not be scheduled twice) and against
      reconnecting a session that died while backgrounded
- [ ] make the failure path explicit: if the reattach fails, show the existing "detached" hint rather than a
      blank pane
- [ ] syntax-check the changed modules; run `./kotlin build && ./kotlin test` — must pass before task 20

### Task 20: Verify acceptance criteria

- [ ] verify all requirements from Overview are implemented (installable PWA, push on `needs_attention`,
      code sign-in reachable *from inside* the installed PWA, usable mobile terminal)
- [ ] verify edge cases: no subscriptions (silence, no errors), dead subscription pruned on `410`, daemon
      restart does not re-notify, openssl missing → push disabled but daemon healthy
- [ ] run the full suite: `./kotlin build && ./kotlin test` — expect ≥ 428 plus the new tests, 0 skipped
- [ ] confirm no machine-specific path entered any YAML: `git grep '/Users/' -- '*.yaml'` stays empty
- [ ] confirm `vapid.pem` is 0600 on a real run and that no new spawn path bypasses the CLOEXEC discipline
      (openssl goes through `ProcessRunner`)

### Task 21: [Final] Update documentation

- [ ] update `CLAUDE.md`: the push architecture (payload-less + VAPID via `/usr/bin/openssl`, key persisted
      by us rather than by openssl's `-out`), the "no TLS on native applies to the CLIENT too — hence
      `ktor-client-darwin`" note, the new-table migration idiom (`CREATE TABLE IF NOT EXISTS`, no PRAGMA
      guard), the short-code ticket format with its rate-limit compensation, and the iOS-PWA
      separate-cookie-jar fact with the `401 → /auth` routing it forces
- [ ] update the test-count baseline in `CLAUDE.md` to the new figure
- [ ] note in `resources/webui/icons/` that the artwork is a placeholder
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification:**
- Desktop Chrome first (cheapest signal): subscribe, force a session into `needs_attention`, confirm a
  system notification with the session name and that clicking it opens that session.
- iPhone: open the public URL in Safari via QR → Add to Home Screen → launch the installed app → confirm it
  lands on the code form (not a wall of 401s) → sign in with the 8-character code → enable notifications from
  the sidebar toggle → lock the screen → trigger an approval → confirm the banner arrives and the tap lands
  on the right session.
- Android Chrome: install, subscribe, verify delivery with the app closed.
- Mobile terminal: keyboard opens on tap, the special-keys bar drives a real agent TUI (Esc, Tab, Ctrl-C),
  the view survives backgrounding and returning.
- Confirm the accepted trade-off is tolerable in practice: attaching from the phone reflows the TUI for a
  desktop attach too ("resize is last active").

**Risk fork (decide only if manual verification fails):**
- If Safari/APNs does not deliver payload-less pushes, add RFC 8291 encryption: ECDH P-256 + HKDF-SHA256
  (the existing `Hmac` covers the KDF) + AES-128-GCM, ~1000–1500 lines including 256-bit modular arithmetic
  (Kotlin/Native has no `BigInteger`). Everything else — table, tracker, routes, worker, UI — stays as built;
  only the request body and the worker's `push` handler change. `p256dh`/`auth` are already stored, so no
  device has to re-subscribe.
- If Apple rejects the JWT (`400 BadJwtToken`), the `sub` claim is the first suspect — prefer the `https:`
  public URL over the `mailto:` fallback.
- If `ktor-client-darwin` fails to resolve or link for `macosArm64`, fall back to `curl` through
  `ProcessRunner` (same system trust store); only `DarwinPushTransport` is replaced.

**External system updates:**
- None. Cloudflare Access is unaffected (push is outbound to Apple/Google; the tunnel only carries inbound
  traffic), but note that an expired Access session degrades the notification text to the generic fallback.
