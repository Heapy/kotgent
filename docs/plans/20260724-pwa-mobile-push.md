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

- [x] add `PushSubscriptions.sq`: table DDL plus `selectAll`, `upsert` (by `endpoint`), `deleteByEndpoint`
- [x] add `src/push/PushStore.kt` — `data class PushSubscription(endpoint, p256dh, auth, createdAt)` and
      `interface PushStore { suspend fun list(); suspend fun save(sub); suspend fun remove(endpoint) }`
- [x] add `SqlitePushStore(driver)` over the same `SqlDriver` the event store uses, with
      `CREATE TABLE IF NOT EXISTS` in `init` for pre-existing databases; `EventStore` is untouched
- [x] write tests: save → list round-trip, re-saving the same endpoint updates instead of duplicating,
      remove is idempotent
- [x] write test: opening the store over a driver whose schema predates the table (create the DB from a
      schema without it, like `EventStoreTest` does for `archived`) still works
- [x] run `./kotlin build && ./kotlin test` — must pass before task 2
      ➕ actual baseline on this branch is **438**, not the 428 the plan quotes; with the 4 new tests the
      suite is **442 run / 442 passed / 0 skipped**

### Task 2: AttentionTracker (pure transition detection)

**Files:**
- Create: `src/push/AttentionTracker.kt`
- Create: `test/push/AttentionTrackerTest.kt`

- [x] add `AttentionTracker` holding `SessionId → Boolean`, with `seed(sessions: List<SessionMeta>)` and
      `isNewAttention(update: SessionUpdate): Boolean` (true only on `false → true`)
- [x] make it host-free: no store, no I/O, no clock
- [x] write tests: a single transition fires once; staying in attention does not re-fire; leaving and
      re-entering fires again
- [x] write test: seeding from an already-attention session makes the next identical update **not** fire
      (the daemon-restart case)
- [x] write test: an unknown session id whose first update is already `needsAttention` fires exactly once
- [x] run `./kotlin build && ./kotlin test` — must pass before task 3
      ➕ "waiting" is `state.needsAttention && !archived`, matching the service worker's `/sessions`
      filter, so an archived row never rings; 3 extra tests (archived, per-session independence, seed
      replaces prior state) bring the suite to **450 run / 450 passed / 0 skipped**

### Task 3: /push routes and server wiring

**Files:**
- Create: `src/transport/PushRoutes.kt`
- Modify: `src/transport/Server.kt`
- Create: `test/transport/PushRoutesTest.kt`

- [x] add `Route.pushRoutes(store: PushStore, vapidPublicKey: suspend () -> String, json: Json)` with
      `GET /push/vapid-key`, `POST /push/subscribe`, `POST /push/unsubscribe`
      ➕ a fourth param `now: () -> Long = ::pushEpochMillis` stamps `createdAt`, so the store write is
      deterministic in tests (the `authRoutes(…, now =)` idiom)
- [x] validate the subscribe body: absolute `https://` endpoint, non-empty `p256dh`/`auth`, else `400`
      ➕ the rule is the pure, public `validateSubscribeRequest(req): String?` (unit-testable without a
      server); it also refuses whitespace/control characters (a CRLF the sender would put on the wire
      unattended) and caps `endpoint` at 2048 / the keys at 512 characters
- [x] mount inside `authenticated { }` in the `KotgentServer` constructor behind new nullable params
      (`pushStore`, `vapidPublicKey`); when either is null the routes are not mounted, so existing harnesses
      are unaffected
- [x] give `KotgentServer.production(...)` the same two nullable params and forward them — that companion
      factory, not the constructor, is what `Commands.daemon` calls
- [x] write tests: subscribe with a cookie and with a Bearer both succeed; the row lands in the store;
      unsubscribe removes it; `GET /push/vapid-key` returns the injected key
- [x] write tests: no credential → `401`; `POST` without an `Origin` → refused by the existing rule;
      malformed endpoint → `400`
      ➕ also: a foreign `Origin` → `403`, an unparseable body → `400`, unsubscribing an unknown endpoint is
      still `200` (the store's remove is idempotent), and a THROWING key provider → `503` on that one route
      with the other push routes still answering (the "openssl missing → push disabled, daemon healthy"
      path Task 10 relies on)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 4
      ➕ 12 new tests: **462 run / 462 passed / 0 skipped**

### Task 4: Base64Url encoder

**Files:**
- Create: `src/crypto/Base64Url.kt`
- Create: `test/crypto/Base64UrlTest.kt`

- [x] add `base64Url(bytes): String` only (RFC 4648 §5, `-`/`_`, no padding) — nothing in this feature
      decodes base64url on the Kotlin side, so no decoder is written until RFC 8291 needs one
- [x] keep it pure Kotlin next to `Hex.kt`; do not add a second encoder anywhere else
- [x] write tests: RFC 4648 vectors, all three padding-remainder lengths, empty input
- [x] write test: output never contains `+`, `/` or `=` for high-bit bytes
- [x] run `./kotlin build && ./kotlin test` — must pass before task 5
      ➕ 7 new tests: **469 run / 469 passed / 0 skipped**. Expected strings come from an independent
      encoder (`python3 base64.urlsafe_b64encode`), not from this code agreeing with itself; extra cases
      cover all 256 byte values, the 65-byte VAPID point (87 chars, the 2-byte-tail case the feature
      actually hits) and the encoded length for every input size 0..40

### Task 5: VAPID keypair (generate, persist 0600, expose)

**Files:**
- Create: `src/push/VapidKey.kt`
- Create: `test/push/VapidKeyTest.kt`

- [x] add `VapidKey` that reads `~/.kotgent/vapid.pem` or generates it once:
      `/usr/bin/openssl ecparam -name prime256v1 -genkey -noout` → PEM on `stdoutBytes` →
      `createPrivateFileExclusive(path, pem)` (0600, first-writer-wins). **Do not use openssl's `-out`** —
      verified on macOS it writes 0644 and the key would ship world-readable
      ➕ generation + the cached point are serialized by a `Mutex` (two concurrent
      `GET /push/vapid-key` calls would otherwise race the cache field); `defaultVapidKeyPath()` reuses
      `kotgentHome()` rather than resolving `$HOME` a second time
- [x] extract the public point: `/usr/bin/openssl ec -in <pem> -pubout -outform DER` → drop the 26-byte SPKI
      header → 65 bytes starting with `0x04` → `base64Url`
      ➕ the slicing rule is the pure, public `publicPointFromSpki(der)` (unit-testable without openssl);
      it asserts the 91-byte length and the `0x04` tag instead of assuming them
- [x] inject the openssl path, the key path and the `ProcessRunner` seam so tests run against a temp
      directory; pin `/usr/bin/openssl` by default (a bare name resolves to Homebrew's build locally and to
      nothing under a stale launchd PATH)
- [x] write tests: generating twice returns the same key (no silent re-mint — a new key kills every existing
      subscription); the public point is 65 bytes and starts with `0x04`; the file's mode is 0600
- [x] write test: a corrupt/empty pem surfaces a clear error instead of a half-broken key
      ➕ four more failure paths: a non-PEM file, a bogus openssl path (and no key file left behind), a
      non-zero openssl (its stderr is in the message), an exit-0-with-empty-stdout (never persisted as an
      empty `vapid.pem`), and a runner-level throw folded into `VapidKeyException`
- [x] run `./kotlin build && ./kotlin test` — must pass before task 6
      ➕ 13 new tests: **482 run / 482 passed / 0 skipped**

### Task 6: ECDSA DER → raw r||s

**Files:**
- Create: `src/push/EcdsaDer.kt`
- Create: `test/push/EcdsaDerTest.kt`

- [x] add `derToRawSignature(der: ByteArray): ByteArray` returning exactly 64 bytes (left-padded `r`, `s`)
      ➕ pure and public, with `P256_COORDINATE_LENGTH` / `P256_RAW_SIGNATURE_LENGTH` named next to it
- [x] reject malformed input explicitly (bad tag, length overrun, `r`/`s` longer than 33 bytes)
      ➕ every failure is one new `EcdsaDerException` (an `IllegalArgumentException`, distinct from
      `VapidKeyException` so task 8's signer can attribute it to openssl); also rejected: a declared
      SEQUENCE length that disagrees with the input, a zero-length INTEGER, a long-form DER length, a
      33-byte integer whose leading byte is NOT a sign byte, and trailing bytes after `s`. Non-minimal
      encodings are deliberately ACCEPTED — stripping them is unambiguous and openssl never emits them,
      so refusing would only add a way for production signing to fail
- [x] write tests with fixtures: the common 70-byte form, the 71/72-byte forms where `r` or `s` carries a
      leading `0x00`, and a short `r` that must be left-padded
      ➕ the fixtures are REAL `/usr/bin/openssl dgst -sha256 -sign` signatures (found by generating 1500
      and keeping one of each shape) with the expected `r`/`s` extracted by `openssl asn1parse` — an
      outside producer and an outside parser. Pinned as constants, never signed at test time: a signature
      is randomised, so a generate-at-runtime test would only exercise whichever shape chance handed it.
      All six shapes are covered (32/32, 33/32, 32/33, 33/33, 31/32, 32/31), plus a short `s`
- [x] write tests: malformed DER throws rather than returning garbage
- [x] run `./kotlin build && ./kotlin test` — must pass before task 7
      ➕ 14 new tests: **496 run / 496 passed / 0 skipped**

### Task 7: VAPID JWT builder and per-origin token cache (pure)

**Files:**
- Create: `src/push/VapidJwt.kt`
- Create: `test/push/VapidJwtTest.kt`

- [x] add pure `pushServiceOrigin(endpoint)` (scheme + host — the JWT `aud`) and
      `vapidSigningInput(aud, exp, sub)` (header + claims, base64url, dot-joined)
      ➕ `pushServiceOrigin` drops the path/query/fragment (a push endpoint's path IS the device
      identity), lower-cases, drops a DEFAULT port so `:443` and the bare host share one token, and
      refuses userinfo / whitespace / control characters / a non-`http(s)` scheme with a new
      `VapidJwtException`. `vapidSigningInput` takes `exp` in epoch **seconds** (JWT NumericDate) and
      refuses a claim value that JSON would have to escape, so a bypassed caller cannot ship a JWT whose
      claims silently do not parse at the push service
- [x] add `vapidSubject(publicUrl: String?)`: the public origin when configured, else
      `mailto:kotgent@localhost`
      ➕ only an **https** public URL becomes the subject; an `http://` one (always loopback here — no TLS
      on native) and an unparseable one both fall back rather than throw, so a bad `publicUrl` cannot
      disable push
- [x] add `VapidTokenCache(now, sign, ttl = 12h, refreshBefore = 1h)` keyed by origin, plus
      `vapidAuthorizationHeader(jwt, publicKey)`
      ➕ the constructor is `VapidTokenCache(subject, sign, now, ttlMillis, refreshBeforeMillis)` — the
      `sub` claim has to come from somewhere and belongs with the cache, not with each call. `sign` is a
      non-suspend `(String) -> ByteArray` so task 8's `VapidSigner::sign` binds directly; the map is
      `Mutex`-guarded (a burst of sends collapses into one openssl run) and `init` rejects
      `refreshBefore >= ttl`, which would re-sign on every call
- [x] write tests: claims are deterministic under an injected clock; Apple and Google endpoints produce
      different `aud`; the cache returns the same token twice and re-signs once inside the refresh window
- [x] write test: the header string matches the `vapid t=…, k=…` shape exactly
- [x] run `./kotlin build && ./kotlin test` — must pass before task 8
      ➕ 19 new tests: **515 run / 515 passed / 0 skipped**. The expected base64url segments are pinned
      from an independent encoder (`python3 base64.urlsafe_b64encode`), and the header segment is the
      well-known `eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9` every ES256 JWT carries. Extra paths covered: a
      failing signer propagates and caches nothing (so a missing openssl degrades to "no push", not to a
      poisoned cache), a malformed endpoint fails before anything is signed, and two devices on the same
      push service share one token

### Task 8: OpensslVapidSigner (the signing edge)

**Files:**
- Create: `src/push/VapidSigner.kt`
- Create: `test/push/OpensslVapidSignerTest.kt`

- [x] add `interface VapidSigner { fun sign(input: String): ByteArray }`
      ➕ non-suspend on purpose, so `signer::sign` binds straight into `VapidTokenCache`'s `sign` param
- [x] add `OpensslVapidSigner(keyPath, opensslPath = "/usr/bin/openssl", runner)`: signing input → temp file
      (popen has no writable stdin) → `openssl dgst -sha256 -sign <pem> <tmp>` → DER from `stdoutBytes` →
      `derToRawSignature`; the input temp file is removed in a `finally`
      ➕ the temp is `mkstemp` (atomic, 0600, collision-free under a concurrent fan-out) and the bytes go
      through the returned fd — never a second `fopen` by name — with a short-write loop, because a
      truncated input would be signed happily and verify against nothing. A blank input is refused before
      anything is spawned
- [x] surface a non-zero exit with openssl's stderr in the message (this is the "push disabled" diagnostic
      the daemon later relies on)
      ➕ every failure is one `VapidSignerException` (distinct from `VapidKeyException`: a key problem
      means "push cannot be enabled", a signing problem means "this send failed"); an exit-0-with-empty-
      stdout and an `EcdsaDerException` over openssl's own bytes are both re-attributed to openssl so the
      operator does not go looking in the caller
- [x] write test: the signature is 64 bytes and `openssl dgst -sha256 -verify <pubkey>` accepts it —
      an outside verifier, not our own code agreeing with itself
      ➕ the test re-encodes the raw `r||s` back to DER itself (the verifier only speaks DER) and also
      asserts that two signatures over the same input DIFFER — ECDSA is randomised, which is what proves
      nothing is cached at this layer — with both still verifying
- [x] write tests: a missing key file and a bogus openssl path both fail with a clear error, leaving no temp
      file behind
      ➕ "no temp file behind" is asserted on EVERY failure path (and on the success path) as a
      before/after diff of `$TMPDIR` entries matching the signer's prefix, so a stale file from an earlier
      crashed run cannot fail an unrelated test; the argv seen by a spy runner also proves the command
      shape and that openssl sees the signing input byte for byte
- [x] run `./kotlin build && ./kotlin test` — must pass before task 9
      ➕ 11 new tests: **526 run / 526 passed / 0 skipped**

### Task 9: Push sender over NSURLSession

**Files:**
- Modify: `module.yaml`
- Create: `src/push/PushSender.kt`
- Create: `src/push/DarwinPushTransport.kt`
- Create: `test/push/PushSenderTest.kt`

- [x] add `io.ktor:ktor-client-darwin` to `module.yaml` dependencies (portable, no absolute paths)
      ➕ it resolved and linked with no trouble (3.4.3, from the same BOM as the other Ktor artifacts), so
      the `curl`-through-`ProcessRunner` fallback in the risk fork was not needed
- [x] add `interface PushTransport { suspend fun post(url: String, headers: Map<String,String>): Int }` and
      `DarwinPushTransport` — `HttpClient(Darwin)` with `HttpTimeout` (never an untimed request)
      ➕ the status code is the whole return value (a push service's body carries nothing the daemon acts
      on) and an implementation must NOT turn a transport failure into a fake status; `setBody(ByteArray(0))`
      lets Ktor derive `Content-Length: 0`, which it refuses to let a caller set by hand
- [x] add `PushSender(store, publicKey, cache, transport)`: for each subscription build the VAPID header,
      `TTL: 1800`, `Topic: <short hash of session id>`, POST an empty body
      ➕ the third parameter is `vapidToken: suspend (String) -> String` (production passes
      `VapidTokenCache::tokenFor`) rather than the cache object — the seam idiom the rest of the push
      package already uses (`VapidTokenCache(sign = signer::sign)`, `authenticated(tokens::current)`), and
      it keeps the JWT format out of this class's tests instead of re-asserting `VapidJwtTest`. The key is
      resolved only when there is at least one subscription, so a daemon nobody enabled push on never
      shells out to openssl. `Topic` is `base64Url(sha256(id)).take(16)`: hashed because a session id is
      kotgent's own identifier and there is no reason for Apple to learn it, and because a `Topic` is
      syntactically restricted to the URL-safe base64 alphabet
- [x] handle results: `200/201` ok, `404/410` → `store.remove(endpoint)`, `429/5xx` → skip, no retry queue;
      a transport exception must not propagate into the caller's coroutine
      ➕ success is the whole `2xx` range (services differ on `200` vs `201`); every failure — an unreadable
      store, a failing key provider, a failing token, a throwing transport, a failing prune — is caught and
      reported through an injected `onError` (stderr in production), and `CancellationException` is the one
      exception re-thrown, since swallowing it would detach the send from the scope that owns it
- [x] write tests with a fake transport: success keeps the row; `410` deletes it; `429` keeps it; a throwing
      transport is swallowed and the other subscriptions are still attempted
- [x] write test: the outgoing header set is exactly what the RFC needs (`Authorization` shape, `TTL`, `Topic`)
      ➕ asserted as an exact key SET, so a stray header is a failure too; the expected `Topic` is pinned
      from an independent digest (`python3 hashlib.sha256` + `base64.urlsafe_b64encode`), never recomputed
      with `pushTopic`
- [x] run `./kotlin build && ./kotlin test` — must pass before task 10
      ➕ 12 new tests: **538 run / 538 passed / 0 skipped** (the branch baseline was 526, not the 428 this
      plan quotes)

### Task 10: PushNotifier and daemon wiring

**Files:**
- Create: `src/push/PushNotifier.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `src/transport/Server.kt`
- Create: `test/push/PushNotifierTest.kt`

- [x] add `PushNotifier(store, tracker, sender)` collecting `EventStore.sessionUpdates` and seeding the
      tracker from `listSessions()` **inside `.onSubscription { }`** — the `EventsWs.streamGlobalUpdates`
      idiom, required because `sessionUpdates` has `replay = 0`
      ➕ the third param is `send: suspend (SessionId) -> Unit`, not the `PushSender` type — the seam idiom
      the package already uses (`PushSender(vapidToken = cache::tokenFor)`), and the ONLY way to test "a
      throwing sender does not stop the collector": a real `PushSender` swallows everything by design. The
      signature is `PushNotifier(store, send, tracker = AttentionTracker(), onError = ::eprintln)`
- [x] construct `SqlitePushStore` over the same `driver` in `Commands.daemon` and pass it plus the VAPID
      public-key provider into `KotgentServer.production(...)`
      ➕ `Server.kt` needed NO change — Task 3 already added the two nullable params and the `production(...)`
      forwarding. The whole assembly is one private `Commands.startPush(driver, publicUrl, scope, events)`
      returning a `DaemonPush(store, publicKey, transport)` holder, so `daemon()` gains four lines rather
      than twenty; the `DarwinPushTransport` is closed in the teardown (after `bgScope.cancel()`, before
      `driver.close()`) instead of leaking an NSURLSession
- [x] start the notifier on `bgScope` **after** `rebuildRegistryFromStore()` + `Reconciler.reconcile()`, so
      startup reconciliation writes are not evaluated against a half-built world
- [x] make a missing/unusable openssl or an unwritable `vapid.pem` degrade to "push disabled" with one
      diagnostic line — never take the daemon down
      ➕ read as "a line, never a crash", NOT "one line per daemon lifetime": the key is minted lazily on the
      first `GET /push/vapid-key`, so an eager startup probe would make every daemon generate a `vapid.pem`
      for a feature most never enable. openssl failures therefore surface where they already did — a `503`
      on the key route and one `PushSender` line per attempted send. What is new here is that NOTHING can
      escape: `PushNotifier.run` guards the send, the seed and the whole collection separately (an exception
      out of a `launch` on K/N reaches the unhandled-exception hook and would kill the daemon), and
      `startPush` returns null on the one eager failure that exists (the subscription table)
- [x] write tests: a transition into attention triggers exactly one send with the right session id; an update
      that is not a transition sends nothing; a session already in attention at seed time sends nothing
      ➕ every "sends nothing" is an ORDERING assertion (emit the silent update, then a loud one, require the
      loud one to arrive first) — a bare "the channel is empty" would also pass if the collector had simply
      not run yet
- [x] write test: a sender that throws does not cancel the collector (later updates still work)
      ➕ plus an unreadable `listSessions()` (reported, collection continues with an empty baseline) and a
      scope cancellation (ends the job, reports nothing)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 11
      ➕ 8 new tests: **546 run / 546 passed / 0 skipped** (branch baseline 538). The `onSubscription` test
      is mutation-verified: moving the seed above `.collect` makes
      `theBaselineIsTakenAfterSubscribingSoNoUpdateIsLost` fail (it times out on the update that a
      subscribe-after-snapshot drops), so it genuinely pins the idiom rather than merely passing

### Task 11: Manifest, icons and static-serving headers

**Files:**
- Create: `resources/webui/manifest.webmanifest`
- Move: `logo.svg` (repo root, provided by the user) → `resources/webui/icons/logo.svg`
- Create: `resources/webui/icons/icon-192.png`, `resources/webui/icons/icon-512.png`,
  `resources/webui/icons/apple-touch-icon.png` (rendered from `logo.svg`)
- Modify: `resources/webui/index.html`
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] add the manifest: `name`/`short_name` "Kotgent", `start_url: "/"`, `scope: "/"`,
      `display: "standalone"`, background/theme colours matching `style.css`, 192 + 512 icons with
      `purpose: "any maskable"`
      ➕ the colours are the **dark** `style.css` values (`#14171c`) for both `background_color` and
      `theme_color`: a manifest carries one pair with no media query, and the artwork itself is a dark
      `#0d1218` square, so a light splash would frame a dark icon with a white flash on every launch. Icon
      `src`s are scope-absolute (`/icons/…`) so they resolve identically from `/` and from a deep link
- [x] move the user's `logo.svg` (512×512, dark rounded square) from the repo root to
      `resources/webui/icons/logo.svg` and render the PNGs from it — verified pipeline:
      `qlmanage -t -s 512 -o <dir> logo.svg` (produces `logo.svg.png`, 512×512 RGBA) then
      `sips -z 192 192 <in> --out icon-192.png`; `apple-touch-icon.png` is the 512 render at 180×180.
      Commit the rendered PNGs — there is no build step to generate them at deploy time
      ➕ `git mv` (the file was tracked at the root) and `chmod 644` — the user's copy was `0600`, which the
      daemon would still serve (same uid) but which is wrong for a committed static asset. `icon-512.png`
      is the `qlmanage` render byte-for-byte, not a re-encode
- [x] update `index.html`: `<link rel="manifest">`, `<link rel="apple-touch-icon">`,
      `apple-mobile-web-app-capable`, `apple-mobile-web-app-status-bar-style`, `viewport-fit=cover`
      ➕ status-bar style is `black-translucent` (the value that pairs with `viewport-fit=cover` and the
      task-16 `env(safe-area-inset-*)` padding); also added `apple-mobile-web-app-title`, a
      `<meta name="theme-color">` matching the manifest, and `rel="icon"` pointing at the SVG source so a
      desktop tab gets the real artwork instead of a favicon 404
- [x] teach `contentTypeFor` about `.webmanifest`; send `Cache-Control: no-cache` from `serveStaticFile` for
      `index.html` and `sw.js`
      ➕ the rule is a named private `isRevalidateAlways(rel)` matched on the whole request-relative path,
      not on an extension — only the root-scope `/sw.js` may claim it (a nested `vendor/index.html` must
      not), and it stays targeted: every other asset keeps the default with no `Cache-Control` at all
- [x] write tests in `WebUiServingTest`: the manifest is served as `application/manifest+json`, an icon as
      `image/png`, and `index.html` carries `Cache-Control: no-cache`
      ➕ the manifest is **parsed** (a manifest that is not valid JSON is silently ignored by every browser,
      which looks exactly like "the install prompt never appears") and every icon it declares is then
      fetched and verified to be a real PNG **of the declared size, read out of its own IHDR** — the icons
      are rendered by hand and committed, so "the 192 slot holds a 512 render" is a mistake nothing else
      would catch. The `no-cache` test asserts both halves: `/` and `/index.html` carry it, and
      `app.js`/`style.css`/the manifest/an icon carry no `Cache-Control` at all
- [x] run `./kotlin build && ./kotlin test` — must pass before task 12
      ➕ 4 new tests: **550 run / 550 passed / 0 skipped** (branch baseline 546)

### Task 12: Service worker, subscription flow, deep link

**Files:**
- Create: `resources/webui/sw.js`
- Create: `resources/webui/lib/push.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/lib/notify.js`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] add `sw.js` with the three handlers (`push` → fetch `/sessions` → one notification per session with
      `needsAttention && !archived`, `tag` = id, generic fallback text when the fetch fails;
      `notificationclick` → focus or `openWindow("/?session=<id>")`; pass-through `fetch`)
      ➕ the generic fallback also covers "the fetch SUCCEEDED but nothing is waiting any more" (a resolved
      approval), which is the same `userVisibleOnly` obligation; plus `skipWaiting` + `clients.claim` so a
      deploy does not leave yesterday's push handler in charge, and a classic (non-module) worker with no
      imports — pinned by a test that no line starts with `import `
- [x] add `lib/push.js`: `supported()`, `subscribe()` (register `/sw.js`, `Notification.requestPermission`,
      `GET /push/vapid-key`, `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})`,
      `POST /push/subscribe`), `unsubscribe()` — every call reachable from a click handler, since iOS
      refuses the permission prompt outside a user gesture
      ➕ the permission prompt is requested BEFORE any other await (awaiting the worker registration first
      loses the iOS gesture); `subscribeWith` drops and re-takes a subscription minted under a different
      application server key (a regenerated `vapid.pem` makes `subscribe()` throw and would otherwise leave
      the device permanently unreachable); `decodeBase64Url` converts the key rather than trusting the
      DOMString form; `refreshActive()` reconciles the mirror flag on load, since a stale `true` would
      silence BOTH paths
- [x] extend the existing `#notify-toggle` handler in **`components/Sidebar.js`** (this is where the toggle
      lives, not in `PreferencesDialog`): turning it on subscribes, off unsubscribes; when a push
      subscription is active, `notify.js` stops raising its own notification (no duplicate on an open tab)
      ➕ the mirror flag (`kotgent.push.v1`) lives in `notify.js`, not `push.js`: `notifyAttention` stays
      synchronous and the dependency runs one way (`push.js → notify.js`) instead of a cycle. The stored
      toggle is written before the handshake — push is an upgrade on top of the in-tab path, not a
      precondition — and a failed subscribe degrades to in-tab notifications with a `console.warn`
- [x] make `app.js` read `?session=` on load and select that session (this is what makes the notification
      tap useful)
      ➕ honoured once, after the first `/sessions` load (the id means nothing before the list exists), then
      `?session=` is stripped with `history.replaceState` so a reload cannot resurrect it. Added the other
      half too: the worker `postMessage`s `select-session` to an already-open window it focuses — a bare
      focus would leave whatever session was on screen, which is the common case on a phone
- [x] syntax-check the new/changed ES modules (`node --check`); add `/lib/push.js` to
      `WebUiServingTest`'s served-module list and a case that `/sw.js` is served as JavaScript with
      `Cache-Control: no-cache`
      ➕ `/lib/notify.js` was missing from that list too and is now in it; the push-flow test asserts the
      page's route strings against the Kotlin constants (`PUSH_VAPID_KEY_PATH` &c.), so renaming a route
      server-side and forgetting the page fails here rather than in a browser nobody is watching
- [x] run `./kotlin build && ./kotlin test` — must pass before task 13
      ➕ 2 new tests: **552 run / 552 passed / 0 skipped** (branch baseline 550)

### Task 13: Short login code (format + 5-minute TTL)

**Files:**
- Modify: `src/transport/Tickets.kt`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `test/transport/TicketsTest.kt`

- [x] replace the hex ticket value with 8 characters of Crockford base32 (alphabet without `I`,`L`,`O`,`U`)
      encoded from `randomBytes(5)` — 40 bits mapping onto exactly 8 symbols, no padding and no modulo bias
      ➕ the encoding is the pure, public `crockfordBase32(bytes)` (unit-testable without a store) next to
      the named `TICKET_CODE_ALPHABET` / `TICKET_CODE_BYTES` / `TICKET_CODE_LENGTH`; it `require`s exactly 5
      bytes rather than silently truncating or short-coding a wrong-sized input
- [x] add `normalizeTicketCode(raw)`: trim, strip spaces/dashes, upper-case, map `I`/`i`/`L`/`l` → `1` and
      `O`/`o` → `0` (both Crockford substitutions, not just `I`); `redeem` normalises before lookup
      ➕ strips ANY whitespace (a pasted code carries `\t`/`\n` as readily as a space), not only `' '`, and
      deliberately does NOT map `U` — it is excluded from the alphabet but has no Crockford substitution, so
      inventing one would let a typo redeem a different code
- [x] cut `TICKET_TTL_MILLIS` to 5 minutes and rewrite the KDoc that the format change invalidates: the
      "lives ten minutes" prose, and the `redeem` rationale that justifies a plain map lookup by "256 bits of
      entropy" — at 40 bits the compensating control is the global rate limit (task 14), single use and the
      5-minute TTL
      ➕ the `redeem` KDoc now says outright that the old argument DIED with the hex format, and rests the
      plain lookup on the narrower true claim (a hash bucket is not a prefix, so there is no per-character
      hill to climb) with the rate limit named as what actually protects 40 bits. `SECRET_BYTES`' KDoc in
      `Auth.kt` also claimed to cover "token, ticket" and no longer does
- [x] update the user-facing copy in `dialogs.js` (`phoneBody`: "expires in 10 minutes") to 5 minutes
- [x] write tests: issued codes are 8 chars from the alphabet only; lower-case, spaced, `L`-for-`1`,
      `I`-for-`1` and `O`-for-`0` input all redeem the same ticket; a code cannot be redeemed twice
      ➕ the digit-substitution test mints until it gets a code that actually CONTAINS both `1` and `0`, so
      the substitution is exercised rather than passing vacuously on a code that has neither
- [x] write tests: expiry at exactly 5 minutes; an unknown code returns null; update the "same strength as
      the master token" assertion/comment in `TicketsTest`
      ➕ also pinned: the alphabet has 32 unique symbols and none of `I`/`L`/`O`/`U`; the encoding matches an
      independent encoder (`python3`, same alphabet applied by hand) on seven vectors including both ends and
      an MSB-first check; it is injective over all 256 values of a byte; and `AuthRoutesTest` asserts the code
      shape it used to assert as 64 hex chars, plus a new end-to-end "typed the way a human types it"
      exchange through the real route (the route's own `trim`/empty guard sits between the wire and `redeem`)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 14
      ➕ 11 new tests: **563 run / 563 passed / 0 skipped** (branch baseline 552)

### Task 14: Global rate limit on ticket exchange

**Files:**
- Create: `src/transport/ExchangeRateLimit.kt`
- Modify: `src/transport/AuthRoutes.kt`
- Create: `test/transport/ExchangeRateLimitTest.kt`
- ➕ Modify: `test/transport/AuthRoutesTest.kt` (the route-level wiring tests — a unit test of the limiter
  class cannot see a per-call instance, which is the whole failure mode this task exists to avoid)

- [x] add `ExchangeRateLimit(now: () -> Long, max = 10, windowMillis = 60_000)` counting **failed** exchanges
      globally, guarded by a `Mutex` like `TicketStore` (route handlers run concurrently; a plain field would
      be a data race). Per-IP is not an option: every tunnelled request arrives from loopback
      ➕ the API is `allow()` (a question — consumes nothing) + `recordFailure()` (the only withdrawal) +
      `failuresInWindow()` for tests/diagnostics, over an `ArrayDeque<Long>` of failure instants pruned from
      the head. The window is ROLLING, not a bucket that resets wholesale (a fixed bucket would let `2 * max`
      guesses land across its boundary), and half-open like `TicketStore`'s expiry — a failure sampled exactly
      `windowMillis` later has already aged out. `init` refuses a non-positive `max`/`window` (a zero budget
      would refuse every sign-in; a zero window would make the limiter a no-op). Named constants
      `EXCHANGE_FAILURE_LIMIT` / `EXCHANGE_WINDOW_MILLIS` sit next to the class
- [x] thread one instance through `authRoutes(...)` as a parameter with a default, so it is constructed once
      per daemon and injectable (with its clock) in tests — a per-call instance would be a silent no-op
      ➕ appended after `now`, so every existing call site (`Server.kt`, both test harnesses) is source-
      compatible and the daemon picks the limiter up with no `Server.kt` change at all
- [x] apply it in `POST /auth/exchange`: over the limit → `429` before the code is looked at; a successful
      exchange consumes no budget
      ➕ the check sits AFTER the `Host`/`Origin` decision and BEFORE the body is read, so a saturated limiter
      is not an oracle either; only a failed *redemption* charges the budget — an unparseable body never names
      a candidate code, so counting it would just be a cheaper way to deny sign-in
- [x] record the accepted trade-off in the KDoc: an attacker who can reach `/auth/exchange` can deny sign-in
      in rolling 60-second windows; Cloudflare Access fronts the public surface, and the alternative
      (unbounded guessing against 40 bits) is worse
      ➕ the KDoc also spells out why per-IP is not merely unimplemented but impossible here (cloudflared
      connects from loopback, so every phone on earth shares one address — the same reason `loopbackOnly` is a
      `Host` check), and that the denial self-heals one window after the attacker stops
- [x] write tests: the 11th failure in a window is refused; the window slides; successes never trip it; a
      valid code still redeems while the limiter is warm but under the cap
      ➕ 12 unit tests plus 4 through the REAL route in `AuthRoutesTest` — the wiring is where the silent
      no-op would live, so the "one limiter per daemon" claim is pinned by failures accumulating ACROSS
      requests, driven through the route's OWN default (the harness only injects a limiter when a test needs
      to move its clock). Also pinned end to end: a throttled request plants no cookie AND does not burn the
      valid ticket it carried (the refusal precedes the lookup), so the denial costs no credential
- [x] run `./kotlin build && ./kotlin test` — must pass before task 15
      ➕ 16 new tests: **579 run / 579 passed / 0 skipped** (branch baseline 563, not the 428 this plan quotes)

### Task 15: Code entry on /auth, unauthenticated routing, and showing the code

**Files:**
- Modify: `src/transport/AuthRoutes.kt`
- Modify: `resources/webui/lib/api.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `src/cli/Commands.kt`
- Modify: `test/transport/AuthRoutesTest.kt`
- ➕ Modify: `resources/webui/style.css` (the `.phone-code` type scale — "large type" is a style, and the
  page's other copy classes all live here)
- ➕ Modify: `test/cli/CliTest.kt` (the CLI-output test)

- [x] extend `AUTH_PAGE_HTML`: with no `#ticket=` fragment, render a code input + submit that POSTs to the
      same `/auth/exchange`, then `location.replace("/")`; state the 5-minute life and show a clear error for
      `400`/`429`
      ➕ the length and the TTL in the copy are INTERPOLATED from `TICKET_CODE_LENGTH` / `TICKET_TTL_MILLIS`
      (both `const`, so the page is still a compile-time constant), so the one page that must work when
      nothing else does cannot drift from the format it is describing. A FAILED link falls through to the
      same form instead of dead-ending — a stale QR then leaves the operator one typed code away. `400`,
      `410`-style refusals and "never existed" all read as one message (the remedy is identical); only `429`
      is told apart, because retyping a good code cannot help there and waiting can, plus a network-failure
      message for a `fetch` that never got a status at all
- [x] route an unauthenticated launch to that form: on a `401` from the initial `/sessions` load,
      `location.replace("/auth")`, and reword `api.js`'s "run `kotgent web`" message — an installed PWA opens
      at `start_url` with an empty cookie jar and cannot run a CLI command, so without this the form built
      above is unreachable
      ➕ `api.js` flags the error (`error.unauthenticated`, read through the exported `isUnauthenticated`)
      and exports `AUTH_PATH` rather than navigating itself — the module is used by dialogs and control
      calls too, and a redirect buried in the transport would fire from any of them. Only the FIRST
      `/sessions` load routes (a `firstLoadRef` claimed up front so a concurrent load is never "first"
      either): a later `401` (a rotated token) means a live page with an attached terminal on screen, and
      navigating out from under it would throw that away silently
- [x] show the code in `PhoneDialog` in large type next to the QR (the ticket response already carries it)
      ➕ shown split in the middle (`A1B2 C3D4`) — the daemon strips whitespace before the lookup, so the
      grouping is display-only and the grouped form redeems unchanged — with one line saying WHY an
      installed app needs it (its own cookie jar). The QR copy stopped calling the ticket a "link": it is
      one credential in two forms now
- [x] print the code in `kotgent web` alongside the URL, with a one-line hint that this is what an installed
      home-screen app asks for
      ➕ the block is the pure, public `renderSignInCode(ticket)` (+ `groupLoginCode`) so it is unit-testable
      without a daemon, the `renderSessions` idiom. Under `--print` it goes to **stderr** — stdout stays
      exactly the URL, so `kotgent web --print | pbcopy` keeps working while the operator still sees the code
- [x] write tests: `GET /auth` HTML contains the code form; a code minted by the ticket route exchanges and
      sets the cookie; a wrong code returns `400` and sets none
      ➕ the form test pins the input, the POST target (against the `AUTH_EXCHANGE_PATH` constant) and both
      interpolated copy values, so a format change that forgets the page fails here. The exchange test drives
      the code through the route in its DISPLAY shape (grouped and lower-cased) and then uses the resulting
      cookie on the gated control plane — the whole PWA path, with no fragment anywhere in it. The wrong-code
      test asserts the absence of `Set-Cookie`, not just the status
- [x] syntax-check the changed modules; write a test that the CLI output contains the code
      ➕ `node --check` on `app.js`, `lib/api.js`, `components/dialogs.js`. The CLI test cross-checks the
      printed grouping against the REAL `normalizeTicketCode`, so a display change that made the code
      unredeemable would fail rather than merely look different
- [x] run `./kotlin build && ./kotlin test` — must pass before task 16
      ➕ 5 new tests: **584 run / 584 passed / 0 skipped** (branch baseline 579)

### Task 16: Mobile layout — drawer sidebar, compact header, viewport units

**Files:**
- Modify: `resources/webui/style.css`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/app.js`
- ➕ Modify: `test/transport/WebUiServingTest.kt` (the mobile layer is invisible to every other test — a
  drawer whose toggle was never rendered looks exactly like today's desktop UI in this binary)

- [x] add a width media query that turns the sidebar into an overlay drawer with a hamburger toggle;
      selecting a session closes it
      ➕ the breakpoint is the EXISTING `max-width: 720px` (the help dialog already uses it) — one
      breakpoint, not two. The open/closed state lives in `app.js` because the hamburger sits in the
      terminal header, on the other side of the tree; closing is wired in `showSession`, so every entry
      point (a tap in the list, a freshly started session, a notification deep link) closes it rather
      than only the click handler. Dismissal is also a real `<button class="drawer-scrim">` (keyboard- and
      VoiceOver-reachable, unlike a `<div onClick>`) plus a `✕` inside the drawer — the scrim covers the
      hamburger that opened it. Closed, the drawer is `visibility: hidden` as well as translated away, or
      a keyboard would walk into off-screen controls
- [x] move interrupt/stop/resume/done into a compact icon row in the terminal header on narrow screens
      ➕ done with `data-icon` + `content: attr(data-icon)` under the media query, so ONE row of markup
      serves both layouts with no JS branch and no second component; each button keeps its text (the
      label collapses to `font-size: 0`) and gains an `aria-label`, so the accessible name is identical
      above and below the breakpoint. Icons: 🔗 attach, ⏸ interrupt, ▶ resume, ⏏ detach, ⏹ stop, ✓ done
- [x] switch the app shell to `100dvh`, add `env(safe-area-inset-*)` padding and
      `overscroll-behavior: none` (pull-to-refresh must not reload the page mid-session)
      ➕ `height: 100vh` is kept as the preceding declaration (the fallback for a browser without `dvh`);
      the safe-area padding sits on `#app` OUTSIDE the media query, because a notched phone in landscape
      is wider than the breakpoint but still has an inset — `env()` is 0 everywhere else. The drawer is
      `position: fixed`, so it re-applies the insets itself
- [x] keep the desktop layout byte-for-byte identical above the breakpoint
      ➕ the three new controls are `display: none` above the breakpoint (a `display:none` flex item
      consumes no `gap`), `data-icon`/`aria-label` are non-visual, and `100dvh == 100vh` / `env(...) == 0`
      on a desktop viewport. The only desktop-visible deletions are the OLD mobile rules this replaces
- [x] syntax-check the changed modules (`node --check`); run `./kotlin build && ./kotlin test` — must pass
      before task 17
      ➕ 1 new test (`theWebUiShipsTheMobileDrawerAndViewportRules`): **585 run / 585 passed / 0 skipped**
      (branch baseline 584). It pins both ends of the CSS↔markup contract — `100dvh`, the safe-area
      insets, `overscroll-behavior: none`, `#sidebar.open`, `content: attr(data-icon)`, and that every
      lifecycle button really declares a `data-icon` AND an `aria-label`. The htm templates were also
      parsed against the vendored `preact`+`htm` outside the browser, so the new attributes are proven to
      reach the vnode rather than only to compile

### Task 17: Keyboard-aware terminal sizing and focus

**Files:**
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/lib/prefs.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `resources/webui/style.css`
- ➕ Modify: `resources/webui/app.js` (threads the live preference into `TerminalPane`; loading storage from
  the pane would hide the dependency and would not react to a save)
- ➕ Modify: `test/transport/WebUiServingTest.kt` (pins the browser-only viewport/listener/CSS contract
  that the native test binary cannot execute)

- [x] subscribe to `visualViewport` `resize`/`scroll`: shrink the terminal container to the visible area,
      call `fit()`, and send the resulting size down the terminal WebSocket
      ➕ the max-height is calculated from `offsetTop + height - host.top`, bounded by the unconstrained
      flex height, and applied before the first `fit()` / WebSocket URL so tmux opens at the right geometry.
      Intermediate keyboard-animation events are debounced before `fit()` + resize reporting
- [x] focus xterm's hidden textarea only from a tap handler (iOS never opens the keyboard otherwise)
      ➕ focus is only in the host's completed `click` handler (not async `ws.onopen`, and not pointer-down
      where a swipe would open the keyboard). The helper textarea computes to 16px to prevent Safari's
      input auto-zoom from corrupting `visualViewport`
- [x] add a terminal font-size preference (three steps) to `prefs.js` (`sanitizePrefs` must coerce it) and
      to `PreferencesDialog`, applied on mount and on change with a `fit()`
      ➕ Small/Medium/Large are `11/13/16px`; `13px` preserves the old default. Numeric strings are
      accepted, while missing/tampered values fall back to 13. A separate live-update effect changes
      xterm's option and reports the re-fit grid without reconnecting the terminal WebSocket
- [x] detach every listener on unmount (no leak when switching sessions)
      ➕ both visualViewport listeners and the host click listener are removed, xterm subscriptions are
      disposed, WebSocket callbacks are cleared, the observer is disconnected, the pending debounce is
      cancelled, and the viewport class/property and live refs are reset
- [x] syntax-check the changed modules; run `./kotlin build && ./kotlin test` — must pass before task 18
      ➕ `node --check` passed for `app.js`, `TerminalPane.js`, `dialogs.js`, and `prefs.js`; the preference
      coercion was also exercised directly under Node. 1 new serving-contract test: **586 run / 586 passed /
      0 skipped** (branch baseline 585)

### Task 18: Special-keys bar

**Files:**
- Create: `resources/webui/components/KeyBar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] expose a send seam from `TerminalPane`: the WebSocket is a local inside the `attachedId` effect, so
      store a `sendBytes` ref inside that effect and clear it on teardown — `KeyBar` writes through the ref
      ➕ the sender emits binary frames only while its socket is OPEN, and teardown identity-checks the
      function before clearing it so an old terminal cannot erase a replacement socket's seam
- [x] add `KeyBar` with Esc, Tab, Shift+Tab, ↑ ↓ ← →, a sticky Ctrl modifier and Ctrl-C, each sending raw
      bytes (`\x1b`, `\t`, `\x1b[Z`, `\x1b[A`/`[B`/`[C`/`[D`, `\x03`)
      ➕ every button constructs a `Uint8Array`, preserving the terminal protocol's binary-input/text-resize
      distinction; the dedicated Ctrl-C key also disarms an already-sticky Ctrl
- [x] make the sticky Ctrl apply to the next printable key, then release itself (with a visible pressed state)
      ➕ a live ref feeds xterm's `onData` path (reliable for the phone keyboard) while Preact state drives
      `aria-pressed` styling. Standard ASCII Ctrl mappings and xterm's digit aliases are emitted; control
      sequences and multi-character paste leave the modifier armed, while unsupported printable input is
      passed through unchanged and consumes it
- [x] show the bar only on narrow screens and only while a terminal is attached; never steal focus from the
      terminal (`preventDefault` on pointer-down)
      ➕ the toolbar is conditionally rendered for the active attachment, hidden by default and flexed only
      under the existing 720px breakpoint. Its height is reserved in the visual-viewport calculation so
      the bar stays above the software keyboard
- [x] add `/components/KeyBar.js` to `WebUiServingTest`'s served-module list; syntax-check the changed
      modules; run `./kotlin build && ./kotlin test` — must pass before task 19
      ➕ `node --check` passed for `KeyBar.js` and `TerminalPane.js`; 1 new serving/source-contract test:
      **587 run / 587 passed / 0 skipped** (branch baseline 586)

### Task 19: Reattach after backgrounding

**Files:**
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/app.js`

- [x] on `visibilitychange` → visible, if the terminal socket is closed and the session is still alive,
      reconnect it (the `/events` socket already self-heals via its `onclose` retry)
- [x] guard against double reconnects (a pending reconnect must not be scheduled twice) and against
      reconnecting a session that died while backgrounded
- [x] make the failure path explicit: if the reattach fails, show the existing "detached" hint rather than a
      blank pane
- [x] syntax-check the changed modules; run `./kotlin build && ./kotlin test` — must pass before task 20
      ➕ `node --check` passed for both changed modules; **588 run / 588 passed / 0 skipped**

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
- [ ] note in `resources/webui/icons/` that the PNGs are rendered from `logo.svg` (and how)
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
