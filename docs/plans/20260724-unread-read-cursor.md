# Make the "Unread events" badge a real unread counter (advance `read_cursor`)

> **Status: COMPLETED.** Landed on branch `worktree-unread-read-cursor` in five tasks (store → route → UI →
> acceptance → docs) plus a review-fix pass, each keeping `./kotlin build` + `./kotlin test` green. The
> branch adds **18 tests**; rebased onto `main` the suite is **456 run / 456 passed / 0 skipped** (the
> per-task totals recorded below are from before that rebase). Notable deviations from the plan as written:
> Task 4 added
> three tests it did not call for (so five browser-facing criteria have a mechanical proof); the
> `Reconciler`-race rationale for the max-merged `upsert` turned out to be unreachable today — reconcile()
> runs once at daemon start, before the server binds — so it is documented as defence in depth rather than a
> live race; and the plan's claim that the positional-`?` form avoids a `Long?` inference is wrong (both
> forms infer `Long?`), corrected in Technical Details below. The UI is still verified by hand plus the
> served-module assertions — no JS test harness exists in this repo, and none was invented for this.

## Overview
The sidebar's unread pill grows forever. `unread` is derived as `lastSeq - readCursor`
(`src/core/Projection.kt:87`), but **nothing ever advances `sessions.read_cursor`**: there is no SQL
statement that writes it (beyond the full-row `upsert`), no HTTP route, and no call from the UI. So the
cursor stays at `0` for a session's whole life and the badge shows the session's **total** event count,
monotonically increasing until the session (or the DB) is deleted.

This plan wires the missing half: the browser tells the daemon "I have seen through seq N" for the
session it is currently looking at, the daemon persists that into the existing `read_cursor` column, and
the recomputed `unread` rides the already-existing `SessionUpdate` signal back to every client.

Benefits: the pill means what it says (activity since you last looked), it clears on the phone and on the
desktop together (one server-side cursor), and it survives a daemon restart. No schema migration is
needed — the column and the core derivation already exist and are already tested.

## Context (from discovery)

### Root cause (confirmed by reading the code)
- `unread(lastSeq, readCursor) = (lastSeq - readCursor).coerceAtLeast(0)` — `src/core/Projection.kt:87`,
  the single definition; `Projection.unread`/`hasUnread` delegate to it and are covered by
  `test/core/ReducerTest.kt:229`.
- `lastSeq` advances on every `append` (`src/store/SqliteEventStore.kt`), i.e. on every `AgentEvent`.
- `read_cursor` (`sqldelight/io/kotgent/db/Sessions.sq:25`) is written **only** by the full-row `upsert`
  (line 60, `read_cursor = excluded.read_cursor`). `updateCache` is explicitly documented as leaving it
  untouched (line 66); `updateControlState`, `setArchived`, `setModel` do not mention it.
- The only value ever supplied is `SessionMeta.readCursor`, which defaults to `Seq(0)`
  (`src/core/SessionMeta.kt:51`) and is never constructed with anything else anywhere in `src/`.
- Consumers are already in place and need no change: `SessionDto` carries `readCursor` + `unread`
  (`src/transport/ControlRoutes.kt:227`), `SessionUpdateDto` carries `unread`
  (`src/transport/EventsWs.kt:131`), `app.js:142` copies it into state, `Sidebar.js:44` renders the pill.

### Files/components involved
- `sqldelight/io/kotgent/db/Sessions.sq` — schema + the targeted statements (`setArchived`/`setModel` are
  the pattern to copy).
- `src/store/EventStore.kt` / `src/store/SqliteEventStore.kt` — the interface and the single writer
  (`Mutex`, read-back of the row, `_sessionUpdates.tryEmit`).
- `src/transport/ControlRoutes.kt` — REST surface; `post("/sessions/{id}/input")` is the existing literal
  route that outranks the generic `post("/sessions/{id}/{action}")`.
- `resources/webui/app.js` — `activeId` (sidebar selection), `attachedId` (terminal open), the `/events`
  WS `onmessage` handler, the `*Ref` convention, `apiRequest`.
- `resources/webui/lib/` — where the pure, out-of-browser-testable helpers live (`app.js:22-24`).
- `src/daemon/Reconciler.kt:119` — the full-row `upsertSession` a max-merged cursor guards against.
  (Re-checked during review: `reconcile()` is called exactly once, at daemon start, *before* the server
  binds, over metas it read moments earlier — so it cannot regress a cursor today. The merge stays as
  defence in depth against a future periodic/concurrent writer, and is documented as such.)

### Patterns to follow
- **Targeted write pattern** (`SqliteEventStore.setArchived` / `setModel`, lines 171-193): take the writer
  `Mutex`, run one narrow `UPDATE`, read the row back with `sessions.get(...)` — **not** `getSession(...)`,
  the `Mutex` is not reentrant — then `tryEmit` a `SessionUpdate` built from the read-back values, and
  `return@withLock` (no-op) when the row is missing.
- **Two-writer discipline** (`CLAUDE.md`, "Two orthogonal session fields set outside the reducer"): a
  field that is neither reducer- nor control-state gets its own statement that leaves
  `state`/`last_seq`/`provider_session_id` alone. `read_cursor` is exactly such a field.
- **Handlers read refs, not closures** (`app.js:18-20`, `56-66`): `sessionsRef`/`activeRef`/`prefsRef`
  exist so handlers keep a stable identity across updates.
- **No `.sqm`**: the `sqldelight-gen` plugin drops migration files. Not an issue here — the column
  already exists in `create()` and in every deployed DB.

### Dependencies identified
- Four `EventStore` implementations must gain the new method: `SqliteEventStore` plus the test fakes
  `FakeEventStore` (`test/transport/TransportTest.kt:570`), `RecordingEventStore`
  (`test/transport/HookRoutesTest.kt:415`), `NoopEventStore` (`test/transport/WebUiServingTest.kt:315`).
  The wrappers in `test/daemon/SessionManagerTest.kt` use `: EventStore by delegate` and need no edit.
- `WebUiServingTest.kt:133-141` asserts the served `lib/` + `components/` module list — a new
  `lib/throttle.js` belongs in it.

## Development Approach
- **testing approach**: Regular (code first, then tests) — matches the repo's existing style.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting the next task**
- **CRITICAL: update this plan file when scope changes during implementation**
- `./kotlin build` **before** `./kotlin test` (`PtyTest` execs the `ptycheck` binary)
- maintain backward compatibility: an old DB row keeps `read_cursor = 0` and simply starts counting from
  the first mark-read

## Testing Strategy
- **unit tests**: required for every task.
  - `test/store/EventStoreTest.kt` — the storage invariants against a real SQLite file.
  - `test/transport/TransportTest.kt` — the route over `FakeEventStore`.
  - `test/transport/WebUiServingTest.kt` — the new `lib/` module is served.
- **e2e tests**: the project has no JS/browser test harness, so the UI wiring is verified by hand (see
  Post-Completion). Do not invent a harness for this change; the throttle helper goes in `lib/` precisely
  so its pure part stays inspectable.
- **not touched**: `ptycheck` (no PTY involved), `src/core` (`unread()` already covered by
  `ReducerTest`), `resources/webui/components/Sidebar.js` (already renders `session.unread`).

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update the plan if implementation deviates from the original scope

## Solution Overview
**Server-owned cursor, UI-driven trigger, one-way `/events` preserved.**

1. The browser knows what it displayed. When a session is **selected in the sidebar** and the tab is
   **visible**, it POSTs `{"seq": <lastSeq it has>}` to `POST /sessions/{id}/read`, throttled.
2. The daemon persists it with a narrow, monotonic, clamped `UPDATE`, then emits the ordinary
   `SessionUpdate` so every connected client (second browser, phone) recomputes `unread` — no new
   notification channel.
3. The badge value keeps flowing through the existing DTOs; `Sidebar.js` is untouched.

### Key design decisions and rationale
- **Server-side cursor, not `localStorage`.** One cursor per session, shared by all clients; survives a
  restart and a change of device. The column already exists, so this costs nothing extra.
- **REST route, not a bidirectional `/events` frame.** Keeping the global stream one-way (server→client)
  preserves the model documented in `EventsWs.kt` and keeps the operation reachable by `curl` and by a
  future CLI. Event volume is tens per session, so the saved round-trips would not pay for the
  complexity.
- **Explicit `seq` in the body, not "mark everything read".** The server may have moved ahead during the
  round-trip; an empty body would silently mark events the client never displayed.
- **Both invariants live in SQL, not Kotlin, and in a single positional statement.**
  `MAX(read_cursor, MIN(?, last_seq))` is monotonic (two clients racing, or a late retry, can never
  regress the badge) and clamped (a bogus or malicious `seq` cannot push the cursor past the log and
  silence the badge forever). Expressing both inside `SET` keeps one bind parameter and the file's
  positional-`?` style. **Correction (review):** the positional form does NOT avoid a nullable bind —
  SQLDelight infers a `MIN()` operand as nullable either way, so the generated signature is
  `setReadCursor(value: Long?, id: String)`. A null would make the `SET` evaluate to `NULL` and violate
  `NOT NULL`, so every caller must stay on a non-null `Long`; `markRead`'s non-null `Seq` guarantees that,
  and the `.sq` comment now records it.
- **`updated_at` is deliberately NOT written.** `renderSessions` sorts `kotgent list` by `updatedAt`
  (`src/cli/Commands.kt:503`); merely *looking* at a session in the browser must not float it above
  sessions that actually did work. This also makes `markRead`'s signature (no `updatedAt` parameter,
  unlike `setArchived`/`setModel`) principled rather than an inconsistency.
- **`upsert` becomes non-regressing, on both the row and the wire.**
  `read_cursor = MAX(sessions.read_cursor, excluded.read_cursor)` protects the stored value from a full-row
  write carrying a stale cursor. That alone would not be enough: `upsertSession` used to emit `unread`
  computed from the **passed-in meta**, so the DB would be right and the broadcast wrong; the emit is
  rebuilt from a read-back row, like every other targeted mutator.
  **Correction (review):** no caller can produce a stale cursor *today* — `SessionManager.start` inserts a
  fresh row and `Reconciler.reconcile()` runs once at daemon start, before the server binds, over metas it
  just read (so the MAX always selects the stored value). Both halves stay as defence in depth against a
  future periodic/concurrent writer and are documented as such, not as a live race.
- **The emitted `SessionUpdate` must carry the row's real `archived` and `state`.**
  `SessionUpdate.archived` defaults to `false` (`src/store/EventStore.kt:51`) and its own KDoc warns that
  a live update claiming `archived=false` un-hides the row in every client until the next resync
  (`app.js:143` assigns it unconditionally, `Sidebar.js` filters on it). An archived session can still be
  the active one — `controlSession("done")` leaves `activeId` pointing at it — so this path is reachable.
- **`markRead` emits unconditionally**, even when the `WHERE`/`MAX` made the write a no-op: the emit is
  what re-synchronizes a client whose earlier POST was lost.
- **`activeId`, not `attachedId`.** Selecting the row is "I am looking at this session"; requiring an
  open terminal would be stricter than intended (attach is a separate action in `app.js`).
- **The trigger is imperative (inside the `/events` `onmessage` handler), not a `useEffect` on
  `[id, lastSeq, unread]`** — corrected during plan review. An effect keyed on those primitives cannot
  retry a failed POST: when the POST fails the values do not change, the 15 s resync re-sends *equal*
  numbers, and preact's `Object.is` dep comparison skips the effect. Running the check inside the message
  handler makes the existing resync a real heartbeat — every 15 s frame re-evaluates "active + visible +
  `unread > 0`" regardless of whether anything changed — so a lost POST heals within one resync instead of
  waiting for the next agent event (which may never come, precisely when the session is
  `needs_approval`).
- **No optimistic local zeroing.** With the imperative trigger the server stays the single source of
  truth, and the throttle's leading edge already clears the badge within a local round-trip.
- **CLI is out of scope.** `kotgent attach` does not mark anything read; `AttachClient` / `TerminalWs`
  are untouched.

## Technical Details

### SQL (`sqldelight/io/kotgent/db/Sessions.sq`)
```sql
-- Advance ONLY the read cursor. Monotonic (MAX: a stale, out-of-order or retried mark-read can never
-- regress the badge) and clamped to last_seq (MIN: a bogus seq from a client cannot push the cursor past
-- the log and silence the badge forever). Deliberately does NOT touch updated_at — viewing a session is
-- not activity, and `kotgent list` sorts by it. Never touches state/last_seq/provider id.
setReadCursor:
UPDATE sessions
SET read_cursor = MAX(read_cursor, MIN(?, last_seq))
WHERE id = ?;
```
Both `MAX`/`MIN` here are SQLite's 2-argument *scalar* functions, and both read pre-update values.

`upsert` gains one changed line:
```sql
  read_cursor         = MAX(sessions.read_cursor, excluded.read_cursor),
```

### Store API
```kotlin
suspend fun markRead(sessionId: SessionId, seq: Seq)
```
No `updatedAt` parameter — the statement does not write it (see the rationale above), so no clock is
needed and `controlRoutes` requires no new wiring.

Implementation shape (identical to `setArchived`, `SqliteEventStore.kt:171-180`): `mutex.withLock` →
`sessions.setReadCursor(...)` → `sessions.get(...)` read-back (`getSession` would deadlock, the `Mutex` is
not reentrant) → `tryEmit(SessionUpdate(id, SessionState.valueOf(row.state), Seq(row.last_seq),
unread(row.last_seq, row.read_cursor), row.archived != 0L))`. The in-memory `projections` map (pure
event-log replay) is untouched.

`upsertSession` changes in the same spirit: after the upsert, read the row back and emit from it instead
of from the passed-in `meta`.

### Route
```
POST /sessions/{id}/read     body: {"seq": <long>}     → "ok"
```
- unknown session → `404`; unparseable body → `400`; negative `seq` → clamped to 0.
- A "malformed id" branch is **not** worth a test: `SessionId` only rejects blank values
  (`src/core/Ids.kt:26-28`), and an empty path segment does not match the route at all — which is why no
  such test exists anywhere in `TransportTest.kt`.
- Mounted inside `authenticated`; as a POST it requires a matching `Origin` under the standard rule. It
  is **not** `loopbackOnly` — the phone must be able to call it through the tunnel.
- Response is `"ok"` (like `/input`); `apiRequest` already returns raw text for a non-JSON body
  (`lib/api.js:37`), so no client-side change is needed. The new value reaches clients via the
  `SessionUpdate` emitted by `markRead`.

### UI flow (`resources/webui/app.js`)
```
session selected ────────────┐
/events frame (live or 15 s resync) for the active session ─┤
tab becomes visible ─────────┘
                             │
                             ├─ guard: id present && unread > 0
                             │         && document.visibilityState === "visible"
                             └─→ markReadThrottled(id, lastSeq)   ── leading edge, ~1000 ms window
                                          │
                                          └─→ POST /sessions/{id}/read → SessionUpdate → unread = 0
```
Leading edge matters: the first call goes out immediately (the badge clears within a local round-trip),
while a burst of events inside the window collapses into a single trailing call — one throttle per session
id, so a mark pending for one session is not discarded when another supersedes it.

**Correction (review):** the guard does NOT exclude archived sessions, as first written. `Sidebar.js`
renders "done" rows with `onSelect` wired AND draws the unread pill on them, so an archived session that
was archived with a non-zero badge would show a pill no click could ever clear. Marking it read is safe —
the emitted signal carries `archived = true`, which is exactly what
`TransportTest.markingAnArchivedSessionReadDoesNotUnHideItInOtherClients` pins.

## What Goes Where
- **Implementation Steps**: SQL, store, route, UI, tests, documentation — all inside this repo.
- **Post-Completion**: manual multi-device verification in a real browser (no JS test harness exists).

## Implementation Steps

### Task 1: Persist the read cursor in the store

**Files:**
- Modify: `sqldelight/io/kotgent/db/Sessions.sq`
- Modify: `src/store/EventStore.kt`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `test/transport/TransportTest.kt` (FakeEventStore)
- Modify: `test/transport/HookRoutesTest.kt` (RecordingEventStore)
- Modify: `test/transport/WebUiServingTest.kt` (NoopEventStore)
- Modify: `test/store/EventStoreTest.kt`

- [x] add the `setReadCursor` statement to `Sessions.sq` next to `setArchived`/`setModel`, positional
      params, with the comment explaining MAX (monotonic), MIN (clamp) and why `updated_at` is left alone
- [x] change `upsert`'s `read_cursor` line to `MAX(sessions.read_cursor, excluded.read_cursor)` and note
      in the surrounding comment that a stale full-row write never regresses the cursor
- [x] declare `suspend fun markRead(sessionId: SessionId, seq: Seq)` in `EventStore` with KDoc in the
      style of `setArchived`: what it leaves untouched, that it emits **unconditionally** (the emit is the
      resync path for a client whose POST was lost), that it is a no-op on a missing row, why it takes no
      `updatedAt`
- [x] implement it in `SqliteEventStore` as a copy of the `setArchived` shape — `mutex.withLock`, the new
      query, `sessions.get(...)` read-back (**not** `getSession`, the `Mutex` is not reentrant), then
      `tryEmit` a `SessionUpdate` carrying the row's `state`, `last_seq`, recomputed `unread` **and
      `archived`**; leave the in-memory `projections` map alone
- [x] change `upsertSession` to emit from a read-back row instead of the passed-in `meta`, so a stale
      full-row write cannot broadcast a too-high `unread` after the `MAX()` corrected the row
- [x] implement `markRead` in `FakeEventStore` for real (mutate its meta + emit, mirroring the SQL's
      MAX/MIN semantics), and add minimal stubs to `RecordingEventStore` and `NoopEventStore` matching
      their neighbours' style
- [x] write `EventStoreTest`: `markRead` advances the cursor, drives `unread` to 0, and leaves
      `state` / `lastSeq` / `providerSessionId` / `updatedAt` untouched
- [x] write `EventStoreTest`: monotonicity — `markRead(3)` then `markRead(1)` leaves the cursor at 3
- [x] write `EventStoreTest`: clamping — `markRead(999)` at `lastSeq = 3` stores 3, and the next `append`
      yields `unread == 1` (not 0)
- [x] write `EventStoreTest`: the emitted `SessionUpdate` carries `unread == 0`, and — on an archived
      session — `archived == true` (it must not un-hide the row in other clients)
- [x] write `EventStoreTest`: a no-op `markRead` (seq below the cursor) still emits, so a client that
      lost its POST is re-synchronized
- [x] write `EventStoreTest`: `markRead` on an unknown session is a silent no-op (does not throw)
- [x] write `EventStoreTest`: regression for the `Reconciler` race — `markRead(3)` followed by
      `upsertSession(meta.copy(readCursor = Seq(0)))` leaves the cursor at 3 **and** emits `unread`
      computed from the corrected row, not from the stale meta
- [x] run `./kotlin build && ./kotlin test` — must pass before task 2 (435 run / 435 passed / 0 skipped)

### Task 2: Expose `POST /sessions/{id}/read`

**Files:**
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `test/transport/TransportTest.kt`

- [x] add `@Serializable data class MarkReadRequest(val seq: Long)` next to the other request DTOs
- [x] add `post("/sessions/{id}/read")` immediately after the `/input` route, mirroring its structure:
      unknown session → `404`, body decode failure → `400`, then
      `store.markRead(id, Seq(req.seq.coerceAtLeast(0)))` and `respondText("ok")`
- [x] extend the endpoint list in the `controlRoutes` KDoc with the new route and one line on what it does
- [x] write `TransportTest`: a valid POST returns 200 and moves the cursor in the fake store — the body
      form needs `ctx.client.post { setBody(...) }` (the `ctx.post(path)` helper sends no body), as
      `startingAnUnsupportedAgentIs400` already does — added a `ctx.postBody(path, body)` harness helper
      next to `ctx.post`, since four tests need it
- [x] write `TransportTest`: unknown session → 404; unparseable body → 400
- [x] write `TransportTest`: a negative `seq` is clamped to 0 rather than rejected or propagated
- [x] write `TransportTest`: `read` is handled by the literal route, not swallowed by the generic
      `{action}` route (assert the response is not the `unknown action` text)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 3 (439 run / 439 passed / 0 skipped)

### Task 3: Mark read from the Web UI

**Files:**
- Create: `resources/webui/lib/throttle.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] create `lib/throttle.js` exporting a leading-edge throttle factory (~1000 ms window) that fires the
      first call immediately, keeps the latest arguments pending, and fires once more at the end of the
      window — pure and import-able per the `lib/` convention (`app.js:22-24`); its behaviour was checked
      by importing it into `node` (leading call at 0 ms, one trailing call with the newest args at the
      window edge, immediate again after the window)
- [x] add `/lib/throttle.js` to the served-module list asserted in
      `WebUiServingTest.daemonServesTheComponentAndLibModules` (`WebUiServingTest.kt:133-141`) — the review
      pass added an assertion on its exported NAME there, plus `markReadIfViewing` / `/read` /
      `visibilitychange` greps in `daemonServesTheAppEntryModule`, so the wiring cannot be deleted green
- [x] in `app.js`, add a `markReadIfViewing()` helper that applies the guard (`unread > 0` and
      `document.visibilityState === "visible"`) and calls the throttled POST to
      `/sessions/{id}/read` with the session's `lastSeq`; swallow POST errors (the next resync retries).
      Reworked in review to take `(id, unread, lastSeq)` rather than an optional row: two of the three
      callers had no row to pass and were building a literal just to rename `sessionId` → `id`. Originally
      it took an OPTIONAL session argument for the two callers whose refs have not caught up yet (a
      fresh selection, and a `session_update` frame that is newer than `sessionsRef`)
- [x] call it from the three triggers: on selecting a session, at the end of the `/events` `onmessage`
      handler when the frame concerns the active session (this covers both live updates and the 15 s
      resync — the heartbeat that heals a lost POST), and from a `visibilitychange` listener registered
      once with `[]` deps — listed as `[markReadIfViewing]` (a stable `useCallback([])`, so still
      registered once) to match the file's dep-listing convention
- [x] add a short comment recording why the trigger is imperative rather than a `useEffect` on
      `[id, lastSeq, unread]` (equal primitives do not re-run an effect, so a failed POST would never
      retry) and why there is no optimistic local zeroing
- [x] match the local JS style: `(x && x.y)` rather than optional chaining, `Object.assign` for state
      patches (optional chaining appears nowhere in `resources/webui/`)
- [x] verify no changes are needed in `Sidebar.js` (it already renders `session.unread` and filters
      `archived` itself — untouched)
- [x] run `./kotlin build && ./kotlin test` — the Kotlin suite must stay green (439 run / 439 passed /
      0 skipped; the new `lib/` module is covered by `WebUiServingTest`, there is no JS harness)

### Task 4: Verify acceptance criteria

Automation cannot open a browser or start the daemon (CLAUDE.md forbids both), so each browser-facing
criterion below was verified as far as it can be without a running daemon — by a test that proves the
underlying mechanism and/or by reading the committed wiring — and the residual browser check is named.

- [x] the badge clears within ~1 s of selecting a session and stays at 0 while it is selected and visible
      — **verified statically + mechanically**: `showSession` calls `markReadIfViewing(session)` with the
      freshly selected row (`app.js:116`), the throttle's leading edge fires immediately (re-checked by
      importing `lib/throttle.js` into `node`: calls at 0 ms / window edge with the newest args / immediate
      again after the window), the POST clears the cursor
      (`TransportTest.postReadAdvancesTheCursorAndClearsUnread`), and every `/events` frame for the active
      session re-runs the guard so it stays at 0 (`app.js:186`). **Manual check remaining:** the actual
      on-screen latency in a browser.
- [x] with the tab hidden or another session selected, new events raise the badge again
      — **verified statically + by test**: the guard returns on
      `document.visibilityState !== "visible"` (`app.js:102`) and the `/events` trigger fires only when
      `msg.sessionId === activeRef.current` (`app.js:186`), so no POST goes out in either case; an `append`
      past the cursor then raises `unread` again
      (`EventStoreTest.markReadIsClampedToLastSeqSoABogusSeqCannotSilenceTheBadge`,
      `EventStoreTest.aClearedBadgeSurvivesADaemonRestart`). **Manual check remaining:** the browser's own
      `visibilitychange` delivery.
- [x] a second client (phone/second browser) sees the badge clear without a reload (via `SessionUpdate`)
      — **verified by a new test**: `TransportTest.postReadClearsTheBadgeInEveryOtherConnectedClientWithoutAReload`
      subscribes a second client to `/events`, POSTs `/read` from another connection and asserts the
      pushed `session_update` carries `unread == 0` at the same `lastSeq`. **Manual check remaining:** the
      phone case over the Cloudflare tunnel (the `Origin` rule for the new POST on the public host).
- [x] marking an archived ("done") session read does not make it reappear in any client's sidebar
      — **verified by tests**: `EventStoreTest.markReadEmitsAnUpdateCarryingUnreadZeroAndTheRowsArchivedFlag`
      (store level) and the new `TransportTest.markingAnArchivedSessionReadDoesNotUnHideItInOtherClients`
      (over the wire: the pushed DTO keeps `archived == true` rather than falling back to its `false`
      default). `Sidebar.js` filters on that field and is untouched.
- [x] restarting the daemon does not resurrect a cleared badge (cursor is persisted)
      — **verified by a new test**: `EventStoreTest.aClearedBadgeSurvivesADaemonRestart` reopens a second
      `SqliteEventStore` over the same database (the restart simulation the suite already uses) and finds
      `readCursor == 3` / `unread == 0`, with a later append counting forward from there.
- [x] `kotgent list` ordering is unchanged by viewing a session in the browser (no `updated_at` write)
      — **verified statically + by test**: `Commands.renderSessions` sorts by `updatedAt`
      (`src/cli/Commands.kt:504`), `setReadCursor` writes only `read_cursor`, and
      `EventStoreTest.markReadAdvancesTheCursorAndLeavesEverythingElseAlone` asserts `updatedAt` is
      unchanged.
- [x] `kotgent attach` still works unchanged and does not alter any cursor
      — **verified statically + by the suite**: `grep` finds no `markRead` / `readCursor` reference in
      `src/cli/`, `src/pty/`, `src/transport/TerminalWs.kt` or `src/daemon/`, and the untouched
      `CliTest` / terminal-WS tests stay green.
- [x] run the full suite: `./kotlin build && ./kotlin test`
- [x] confirm zero skipped tests and record the new total — **442 run / 442 passed / 0 skipped**

### Task 5: [Final] Update documentation
- [x] add a short paragraph to `CLAUDE.md` (near the "Two orthogonal session fields set outside the
      reducer" note) describing `read_cursor` as a third such field: UI-driven, monotonic + clamped in
      SQL, no `updated_at` write, non-regressing `upsert` on both row and signal, CLI not involved —
      first added as a separate bold-led paragraph after that note; the review pass merged it INTO the
      note instead, whose lead now reads "**Three orthogonal session fields set outside the reducer**"
- [x] fix the two KDocs the change invalidates: `EventStore.upsertSession` ("fully update" — `read_cursor`
      is now max-merged) and `EventStore.sessionUpdates` ("one per append and per upsertSession" —
      `markRead` is a third emitter; the list now names all the targeted mutators that emit)
- [x] update the test-count baseline in `CLAUDE.md`'s "Testing & running" section — **456 run / 456
      passed / 0 skipped** after the rebase onto `main` (442 when this task first ran)
- [x] README: judged unnecessary here (the only near-miss is `README.md:153` "live state badges", which
      describes the session-*state* badges), then added by the review pass — the pill is user-visible and
      its server-side, persistent, activity-neutral semantics are not guessable
- [x] move this plan to `docs/plans/completed/` — handled by the harness after the review phases; NOT
      moved here (moving it now would break every later review/finalize step that reads this file)

## Post-Completion
*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification** (no JS test harness in this repo):
- Run the daemon the normal way outside automation, open the Web UI, and walk Task 4's first four
  criteria by hand — including the two-device case over the Cloudflare tunnel, which also exercises the
  `Origin` rule for the new POST on the public host.
- Expect the pill on the *actively viewed* session to blink 0 → n → 0 as events arrive: there is no
  optimistic local zeroing (deliberately — the server is the single source of truth), so each append raises
  the badge and the throttled POST clears it up to a window later. That is the normal steady state, not a
  fault. What WOULD indicate a failing POST is a badge that keeps climbing on a session you are looking at
  with the tab visible — then check the daemon log and the browser network tab.
- Cost note (accepted, not measured): for the actively-viewed session each event now costs an extra POST,
  one narrow DB write and a `SessionUpdate` broadcast to all clients, throttled to at most ~1/s.

**Follow-ups deliberately out of scope:**
- `kotgent attach` marking a session read — needs a decision about what "read" means for a terminal
  client, and would move the trigger into `TerminalWs`.
- Per-device cursors — would need a new table and a stable client id; rejected during design because a
  single shared cursor matches how the sessions are actually watched.
