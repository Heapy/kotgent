# Web UI asset snapshots: make `/_v/<rev>/` address bytes, not a moving disk

## Overview

`139d54c` replaced the hand-bumped `?v=` token with a content revision carried in a path prefix
(`/_v/<rev>/app.js`) and serves anything under a well-formed prefix with
`Cache-Control: max-age=31536000, immutable`. The invalidation half works. The addressing half does not:
`stripRevPrefix` only STRIPS the prefix and never checks it, so `/_v/<rev>/app.js` is not an address of
particular bytes — it is `app.js` with a decorative segment, served from whatever is on disk at request
time.

That gap is what this plan closes. A shell handed out at revision A, a file edited while the browser is
still fetching subresources, and the browser pins revision-B bytes for a year under an address that
promises A. The KDoc's own defence — "the worst outcome is one cache entry the client never asks for
again" — is false precisely because the revision is a pure function of content: undo the edit and the
same revision recurs, the same URL is handed out again, and the poisoned entry is served without a
network request. Nothing the daemon does can dislodge it.

The fix is to make the promise true rather than to police it: when the daemon computes a revision it
already reads every byte in the tree, so it keeps that content as an in-memory SNAPSHOT keyed by the
revision. An asset request is answered FROM the snapshot its URL names. A revision the daemon does not
hold is answered `404` — honestly, and with `no-cache` so the negative answer cannot outlive the
condition that caused it — while the shell carries a small inline handler that turns that `404` into one
automatic reload.

Two consequences make this cheaper than it sounds. The snapshot costs no extra I/O, only memory: those
bytes are read today and thrown away. And serving strictly from a snapshot key retires a whole class of
gate bugs — a path that is not a key in the snapshot simply cannot be served `immutable`, which closes
the `%00` truncation and the case-insensitive `SW.js` bypass without a single string-normalisation rule.

Findings addressed: F1 (unverified revision), F3 (circular test oracle), F4 (`%00`), F5 (case), F6
(headerless error responses), F7 (directory 200), F8 (empty-walk fallback mints a valid token), F9
(hashed set exceeds served set), F11 (`/auth` cache policy), F12 (walk safety), F13/F15 (test quality),
F14 (contradictory CLAUDE.md).

Deliberately NOT addressed: **F2** (full-tree SHA-256 on every `GET /`). The KDoc already records the
cost as an accepted trade-off, the measured release figure (2.8 ms) is better than the documented
estimate ("tens of milliseconds"), and the remote amplification angle does not exist — the public host
sits behind Cloudflare Access. What remains is a local-only vector that requires code already running on
the machine. The cost stays; CLAUDE.md gains the Access fact so the next reader does not re-derive it.

## Context (from discovery)

- **Files involved**: `src/transport/WebUiAssets.kt` (revision, prefix, gates), `src/transport/Server.kt`
  (`serveStaticFile`, `staticWebUi`, `contentTypeFor`, `resolveWebUiDir`), `src/transport/Auth.kt`
  (`readFileBytesOrNull`), `src/transport/AuthRoutes.kt` (`/auth`), `src/daemon/VendorStoreFs.kt`
  (`listDir`, `isDirectory`), `resources/webui/index.html`, `test/transport/WebUiServingTest.kt`,
  `CLAUDE.md`.
- **Patterns to follow**: `TokenHolder` (`src/transport/TokenHolder.kt`) is the house pattern for
  request-path state — `kotlin.concurrent.atomics.AtomicReference`, never a `Mutex`, because readers run
  on every request including the non-suspend WS handshake. `Broadcaster` uses the same primitive.
- **Test harness**: `WebUiServingTest.withServer { ctx -> ... }` is the existing entry point (grep for it —
  that file was cut from ~4500 lines to ~1050 when the grep tier was replaced, so any line number
  predating that is wrong);
  `FileUploadTest.withTempDir`/`makeTempDir` (line ~120-140) is the reference fixture helper with the
  documented `mkdtemp` fallback and verified cleanup; `DirectoryCompletionTest.kt:108` has the symbolic
  `MODE_0700`.
- **Dependencies**: none new. Pure-Kotlin `sha256` (`src/crypto/`), stock POSIX via `platform.posix`.
- **Verified during planning** (do not re-derive): all imports in `resources/webui` are STATIC — there is
  no `import(` anywhere — so prefixed assets are requested only during the seconds of a page load, never
  later from a long-open tab. `resolveWebUiDir` selects a directory by `access(F_OK)` alone. The checkout
  is on a case-insensitive APFS volume. `contentTypeFor` falls through to `application/octet-stream`.

## Development Approach

- **testing approach**: **TDD** — the failing test comes first in every task, and it must be shown to
  fail for the stated reason before the fix lands. Three of the findings exist precisely because a test
  asserted the implementation rather than the invariant, so a test that was never red proves nothing.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - write unit tests for new functions/methods
  - write unit tests for modified functions/methods
  - add new test cases for new code paths
  - update existing test cases if behavior changes
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run `./kotlin build` before `./kotlin test` — `./kotlin test` never links a main binary and the suite
  execs two of them: `PtyTest` runs `ptycheck`, and `WebUiCheckTest` plus every browser test run
  `webuicheck`
- maintain backward compatibility of the served URL shape (`/_v/<rev>/<path>` stays)

## Testing Strategy

- **unit tests**: required for every task (see Development Approach above)
- **e2e tests**: ⚠️ this plan was written before the browser tier existed and its test strategy is
  superseded — do NOT execute the paragraph this replaces, which routed UI claims into grep assertions.
  The Web UI now has three tiers (see CLAUDE.md's testing section and `docs/TESTING.md`).
  `test/transport/WebUiServingTest.kt` is the right home for **this** plan's subject and almost all of it:
  addresses, media types, caching headers, content revisions, path safety, precedence over the API, and
  the registry every newly served module must be entered in. Anything a running page could answer — and
  for this plan that is little more than "an old shell's subresources still load" — belongs in
  `webuitest/` as executed behaviour. Changed JavaScript modules must pass `node --check <file>`.
- **the oracle rule for this plan**: a test may not derive its expectation from the response it is
  asserting on. Where a served value must equal a computed one, compute it independently
  (`webUiRevision(locateWebUiDir())`) and compare.
- **baseline**: ⚠️ the recorded baseline (896 native / 0 skipped, 7 build-info JVM tests, 11 `ptycheck`
  checks) is stale — the browser tier landed after this plan was written, and the native count both grew
  and then fell when the grep tier was replaced. Re-measure `./kotlin test` before task 1 and use that as
  the baseline; the tiers to account for are native, browser (`webuitest`), build-info JVM, `ptycheck` and
  `webuicheck --self-check`. Every task states the new count it expects.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**The rule after this change:** `immutable` is granted if and only if the daemon can prove the bytes it is
sending are the bytes that revision names. It can prove that for exactly one reason — it is holding them.

```
GET /                       -> walk tree, read every file, compute rev,
                               STORE snapshot{rev -> path->bytes}, substitute __REV__

GET /_v/<rev>/<path>        -> snapshot for <rev> held?
                                 yes, and <path> is a key -> bytes FROM the snapshot, immutable
                                 yes, but <path> is not    -> 404, no-cache
                                 no                        -> 404, no-cache

GET /<path>  (no prefix)    -> bytes from disk, no-cache        (unchanged)
```

Four generations are retained (~2.6 MB), which covers editing files while several tabs load at different
revisions. Eviction is oldest-first.

**Why `404` and not "serve from disk anyway"** (this reverses the KDoc's current reasoning, so the KDoc
must be rewritten, not just the code): serving current bytes under a stale revision hands the browser a
MIXED module graph — some modules from before the edit, some after — and an ES module graph with a changed
signature then fails somewhere in the runtime, silently and far from the cause. A `404` fails loudly at
the point of the problem and is recoverable by definition: `index.html` is `no-cache`, so one reload
fetches a fresh shell with fresh URLs. Nothing can get stuck, because no state survives the reload.

Two conditions make that safe, and neither is optional:

1. **The `404` itself must carry `no-cache`.** Today both error branches `return` above the header write.
   A heuristically cached `404` on a content-addressed URL is the one failure that WOULD survive a reload,
   because the revision recurs. This is F6, promoted from a footnote to a precondition.
2. **The shell must self-heal.** An inline handler in `index.html` (capture-phase `error` on resource
   loads) performs exactly one `location.reload()`, guarded by a `sessionStorage` key so a genuinely
   broken deployment cannot loop.

**What this retires.** `isRevToken` and `neverImmutable` stop being the gate on `immutable` — snapshot
membership is. A `%00`-truncated path, a case-variant `SW.js`, a directory name, and a hand-typed
`/_v/000000000000/app.js` all fail the same way now: not a key in a held snapshot. The KDoc's claim that
`isRevToken` prevents a broken substitution from pinning an asset becomes true for a different and
stronger reason, and its claim that the empty-walk fallback is safe (F8) is removed along with the
fallback: a walk that finds nothing yields NO revision and therefore no `immutable` at all.

## Technical Details

**`WebUiSnapshot`** — `data class WebUiSnapshot(val revision: String, val files: Map<String, ByteArray>)`.
Keys are tree-relative paths exactly as the walk produced them (`lib/api.js`), no leading slash.

**`WebUiSnapshots`** — the holder. `kotlin.concurrent.atomics.AtomicReference<List<WebUiSnapshot>>`,
following `TokenHolder`:
- `publish(snapshot)` — prepend; if the revision is already held, move it to the front rather than
  duplicating; truncate to `MAX_SNAPSHOTS = 4`. Compare-and-set retry loop, no `Mutex`.
- `find(revision): WebUiSnapshot?` — linear scan of at most four entries.
- Instance is created in `KotgentServer` and passed to `staticWebUi(dir, snapshots)`. `null` `webUiDir`
  still mounts nothing, so tests that disable the SPA are unaffected.

**`webUiRevision` becomes `webUiSnapshot(dir): WebUiSnapshot?`** — same walk, same per-file `sha256`, same
sort, same 12-hex prefix, but it retains the bytes it already read and returns them with the revision.
`null` when the walk finds no files (replacing the `index.html`-only fallback). A thin
`webUiRevision(dir): String?` wrapper stays for tests that only want the token.

**Walk safety (F12/F9)**: `collectFileDigests` gains an `lstat`-based regular-file test — a symlink is
neither followed nor hashed, and a FIFO can no longer block `fopen` on the request path. Entries whose
name starts with `.` are skipped, so a Finder-written `.DS_Store` neither changes every asset URL nor
enters the snapshot. `MAX_WALK_DEPTH` stays as the backstop it is.

**Memory**: 4 × ~653 KB ≈ 2.6 MB steady state. The snapshot holds the same `ByteArray` instances the walk
allocated; nothing is copied on serve.

**`/auth` (F11)**: one `call.response.headers.append(HttpHeaders.CacheControl, "no-cache")`. It is the
installed PWA's second HTML entry point and carries the exchange protocol inline.

**Directory requests (F7)**: `serveStaticFile` rejects a path that `isDirectory` reports, before reading —
`readFileBytesOrNull` returns a non-null EMPTY array for a directory on macOS (`ftell` reports 320,
`fread` fails with `EISDIR`), so the existing `bytes == null` branch never fires.

## What Goes Where

- **Implementation Steps** (`[ ]` checkboxes): code, tests and documentation inside this repository.
- **Post-Completion** (no checkboxes): manual browser verification that no native test can perform.

## Implementation Steps

### Task 1: Pin the addressing invariant with failing tests

TDD anchor for the whole plan. These tests must FAIL against current `main` for the stated reasons; record
the failure messages in the PR/commit body.

**Files:**
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add `theServedRevisionEqualsTheComputedOne`: assert `webUiRevision(locateWebUiDir())` equals the
      token parsed out of `GET /` — the independent oracle F3 lacks (fails today only if the wiring is
      broken; it is the regression guard, keep it green)
- [ ] add `aRevisionTheDaemonDoesNotHoldIsNotImmutable`: `GET /_v/000000000000/app.js` must not carry
      `immutable` (fails today: shape-only `isRevToken` grants it)
- [ ] add `aStaleRevisionDoesNotServeCurrentBytesAsImmutable`: fetch `/`, capture rev A, mutate the served
      tree, request `/_v/<A>/<file>`, assert the response is not `immutable`-with-new-bytes (fails today)
- [ ] add `everyServedModuleIsFreeOfTheOldQueryToken` over the full module list from
      `daemonServesTheComponentAndLibModules`, not just the shell and `app.js` (F13)
- [ ] run tests - the three new assertions must be RED for the documented reason before Task 2

### Task 2: `WebUiSnapshots` holder

**Files:**
- Create: `src/transport/WebUiSnapshots.kt`
- Create: `test/transport/WebUiSnapshotsTest.kt`

- [ ] write `WebUiSnapshotsTest`: publish/find round trip; a fifth publish evicts the oldest; republishing
      a held revision does not duplicate it and does not evict; `find` of an unknown revision is `null`
- [ ] write a concurrency test: parallel `publish` from several coroutines leaves exactly
      `MAX_SNAPSHOTS` entries and never loses the newest
- [ ] create `src/transport/WebUiSnapshots.kt` with `WebUiSnapshot` and the `AtomicReference`-based holder,
      documenting why it is not a `Mutex` (request path, per `TokenHolder`)
- [ ] run tests - must pass before task 3

### Task 3: Revision walk returns bytes, drops the fallback

**Files:**
- Modify: `src/transport/WebUiAssets.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write a test that an empty directory yields `null` (no revision), NOT a valid-looking token — the
      F8 defect, red first
- [ ] write a test that the returned snapshot's key set equals the files on disk and that each value is
      byte-identical to the file
- [ ] replace `webUiRevision(dir): String` with `webUiSnapshot(dir): WebUiSnapshot?` retaining the bytes
      the walk already reads; keep a `webUiRevision(dir): String?` wrapper
- [ ] delete the `index.html`-only fallback and rewrite the KDoc paragraph that justified it
- [ ] run tests - must pass before task 4

### Task 4: Walk safety — regular files only, no dotfiles

**Files:**
- Modify: `src/daemon/VendorStoreFs.kt`
- Modify: `src/transport/WebUiAssets.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write a test that a symlink inside the tree is neither hashed nor present in the snapshot (F12)
- [ ] write a test that a `.DS_Store` in the tree does not change the revision (F9)
- [ ] add an `lstat`-based `isRegularFile` to `VendorStoreFs.kt` beside `isDirectory`
- [ ] filter the walk to regular, non-dot entries; document that this is what keeps a FIFO off the
      request path
- [ ] run tests - must pass before task 5

### Task 5: Serve assets from the snapshot

The core change. After it, Task 1's red assertions go green.

**Files:**
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write tests: a held revision serves snapshot bytes with `immutable`; an unheld revision is `404`; a
      path absent from a held snapshot is `404`; an unprefixed path still serves from disk with `no-cache`
- [ ] write tests for the retired gates: `/_v/<rev>/app.js%00.html` (F4) and `/_v/<rev>/SW.js` (F5) are
      `404`, not `immutable`
- [ ] publish a snapshot when serving `index.html` and substitute its revision
- [ ] serve a prefixed request from `WebUiSnapshots.find(rev)`, `404` on any miss; keep `neverImmutable`
      only for an exact hand-typed `/_v/<rev>/index.html` or `/_v/<rev>/sw.js`
- [ ] rewrite the `stripRevPrefix` KDoc: the revision IS checked now, and the reasoning that justified not
      checking it is wrong (the recurrence argument)
- [ ] run tests - must pass before task 6

### Task 6: Error responses carry `no-cache`

Precondition for Task 5, not a nicety: a cached `404` on a recurring content-addressed URL is the one
failure that survives a reload.

**Files:**
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write tests that the `403` (bad path) and every `404` carry `Cache-Control: no-cache`
- [ ] move the header write above both early returns in `serveStaticFile`
- [ ] run tests - must pass before task 7

### Task 7: A directory URL is `404`, not an empty `200`

**Files:**
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write a test that `GET /vendor` and `GET /_v/<rev>/vendor` answer `404` (today: `200`, empty body)
- [ ] reject a directory path in `serveStaticFile` before reading, with a comment naming the macOS
      `fopen`-on-a-directory behaviour that makes the `bytes == null` branch unreachable
- [ ] run tests - must pass before task 8

### Task 8: Shell self-heals a stale-revision `404`

**Files:**
- Modify: `resources/webui/index.html`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write a serving test asserting the inline handler is present in the served shell and that it is NOT
      behind the `/_v/` prefix (it must run even when every prefixed asset `404`s)
- [ ] add a capture-phase `error` listener before the module loads: on a failed script/style load perform
      one `location.reload()`, guarded by a `sessionStorage` key so a broken deployment cannot loop
- [ ] verify the shell still parses: `node --check` is not applicable to inline HTML, so extract the
      handler's logic mentally and keep it to statements the ES5 parser accepts (it runs before modules)
- [ ] run tests - must pass before task 9

### Task 9: `/auth` gets an explicit cache policy

**Files:**
- Modify: `src/transport/AuthRoutes.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] write a test that `GET /auth` carries `Cache-Control: no-cache`
- [ ] append the header in the route, with a one-line comment on why the auth shell must revalidate
      (inline exchange protocol + compile-time ticket TTL copy)
- [ ] run tests - must pass before task 10

### Task 10: Test-helper hygiene

**Files:**
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] replace the local `makeTempDir` with the `FileUploadTest` variant including its documented `mkdtemp`
      fallback, or extract one shared helper used by both (F15)
- [ ] make `writeFile` check `fwrite`'s return and fail the test on a short write
- [ ] replace `MODE_0700 = 0b111_000_000` with `S_IRUSR or S_IWUSR or S_IXUSR`
- [ ] replace the hand-listed `finally` cleanup with a child-iterating `withTempDir` that asserts `rmdir`
      returned 0
- [ ] run tests - must pass before task 11

### Task 11: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] rewrite the asset-invalidation section: the revision now names a held snapshot, `immutable` requires
      snapshot membership, a miss is `404` + `no-cache` + one shell-driven reload, and four generations
      are retained
- [ ] resolve the contradiction the commit left (F14): the older PWA paragraph still says to keep "the
      targeted cache rule" while the newer one says everything else is `no-cache` — state one rule
- [ ] record the Cloudflare Access boundary: Access guards the PUBLIC host, loopback
      (`http://127.0.0.1:27508`) is NOT behind it — which is why the unauthenticated static route's cost is
      acceptable (F2, deliberately unfixed) and why the local vector requires code already running
- [ ] record that all Web UI imports are static, so prefixed assets are requested only during a page load
      — the fact that makes a `404` recoverable
- [ ] run tests - must pass before task 12

### Task 12: Verify acceptance criteria

- [ ] verify every finding listed in the Overview is either fixed or explicitly recorded as not fixed
- [ ] verify Task 1's three red assertions are green for the right reason (not by weakening them)
- [ ] `./kotlin build && ./kotlin test` — expect 896 + the new tests, 0 skipped
- [ ] verify no `?v=` remains in any served file and no test derives its expectation from the response
- [ ] verify memory: four snapshots of the real tree, ~2.6 MB, no copy on serve

### Task 13: [Final] Update documentation

- [ ] update `CLAUDE.md` if implementation revealed patterns beyond Task 11's scope
- [ ] update the "Where things live" list if new files warrant it
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification** (no native test can do these):
- load the Web UI, edit `resources/webui/style.css` while the page is loading, confirm the tab either
  completes from the snapshot or reloads itself once — never renders a mixed graph
- restart the daemon with a tab mid-load and confirm the shell's handler recovers in one reload
- confirm on the installed iOS PWA that an upgrade is picked up on the next launch and that the reload
  guard does not loop
- DevTools: confirm prefixed assets are `immutable` and are served from cache on a second navigation,
  while `index.html`, `sw.js`, `/auth` and every error response revalidate

**Known limitations to accept, not fix:**
- snapshots die with the process, so a daemon restart during a page load costs one reload
- F2 stays: the full-tree hash runs on every `GET /`; the public host is behind Cloudflare Access and the
  local vector requires code already running on the machine
- there are still no validators (`ETag`/`Last-Modified`), so a `no-cache` asset revalidates as a full
  200 — relevant only for the three unprefixed shell references (~13 KB)
