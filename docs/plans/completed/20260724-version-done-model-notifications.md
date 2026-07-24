# CLI version, Done (kill+archive), model, and notifications

> **Status: COMPLETED.** All four features landed as green commits on branch
> `feat/version-done-model-notifications` (CLI version → Done → model → notifications), each keeping
> `./kotlin build` + `./kotlin test` green. Final suite: **400 run / 400 passed / 0 skipped**. The
> `.sqm` migration path was NOT used (the vendored plugin drops migration files) — the `archived` column
> is added by an idempotent init-`ALTER` instead (see Task 4). Notifications are frontend-only, verified by
> an ESM syntax check plus the green Kotlin build (no JS test harness exists).

## Overview

Four backlog features for kotgent, shipped as four independent green commits in a safety-first order
(most test-covered first, the frontend-only notifications last, outside the `./kotlin test` net):

1. **CLI version in the sidebar** — populate the already-modeled `SessionMeta.cliVersion` / `cliPath` at
   launch and show the version where the cwd is today (the path is already legible from grouping).
2. **Done = kill + archive** — a "Done" action that terminates the agent (`kill-session`) and then hides
   the session from the sidebar via a new `archived` flag; a "Show done" toggle reveals archived sessions,
   and "Restore" un-archives them.
3. **Model (best-effort)** — populate `SessionMeta.model` from the provider's own on-disk record (Claude
   transcript, Codex rollout) and surface it next to the version. Non-breaking: if the model can't be
   found, it stays null and the UI simply omits it.
4. **Notifications** — browser `Notification` on a session's transition *into* needs-attention, plus a
   prominent per-device on/off toggle.

Each feature keeps `./kotlin build` and `./kotlin test` green (baseline **361 run / 361 passed / 0
skipped**) before the next begins.

## Context (from discovery)

- **Project**: kotgent — a Kotlin/Native (macosArm64) daemon that runs `claude`/`codex` TUIs inside
  `tmux` and fans terminal output to IDE/browser. Built with the **JetBrains Kotlin Toolchain** (NOT
  Gradle): `./kotlin build` (before `test`), `./kotlin test`.
- **Feature 1 is ~80% wired already**: `SessionMeta.cliVersion`/`cliPath` fields exist
  (`src/core/SessionMeta.kt:29-31`), the `sessions` schema has `cli_version`/`cli_path`, and the store
  round-trips them (`SqliteEventStore.kt:121-122, 311-312`). `ClaudeCli.detectVersion()` /
  `CodexCli.detectVersion()` exist; the claude version is *already detected* at daemon start for the
  `--session-id` gate (`Commands.kt:293-294`) and then discarded. Nothing is set into `SessionMeta` and
  nothing is exposed in the DTO/UI.
- **Feature 2 needs a schema change**: a new `archived` column. The custom, Gradle-free `sqldelight-gen`
  plugin **drops `.sqm` migration files** (`deriveSchemaFromMigrations = false` / `verifyMigrations = false`
  and it filters `MigrationFile`s out under those flags; generated `Schema.version == 1`, `migrate()` is
  empty). So the migration is a **hand-rolled idempotent `ALTER` in `SqliteEventStore.init`**, not a `.sqm`
  (Task 4) — sidestepping the unproven plugin path entirely.
- **Feature 3 sources are confirmed on real files**: Claude transcript JSONL carries
  `"model":"claude-opus-4-8"`; Codex rollout JSONL carries `"model":"gpt-5.5"` in a `turn_context` record
  at ~line 6 (deeper than the current `HEAD_BYTES=8KB` head read); the Codex `session_meta` first line has
  only `model_provider`, not `model`.
- **Feature 4 is greenfield**: the webui has no notification code at all (no `Notification` API usage).
- **Patterns to reuse**: `ProviderIdCapture` (`src/daemon/ProviderIdCapture.kt`) as the shape for a
  best-effort model capture; the existing `POST /sessions/{id}/{action}` switch
  (`ControlRoutes.kt:125-169`) for new lifecycle actions; the `controlSession` client helper
  (`app.js:167-198`) which already patches a row from the action's response DTO; `lib/prefs.js` as the
  shape for a per-device localStorage toggle.

## Development Approach

- **testing approach**: Regular (code first, then tests) — matches the codebase, whose tests assert
  behavior of concrete units with injected fakes.
- Complete each task fully (code + tests + green) before the next.
- **Every Kotlin task MUST add/adjust unit tests** (success + edge/error). All tests pass before moving on.
- **Frontend-only tasks have no unit tests** — the webui is not compiled and has no JS test harness (a
  browser e2e harness is itself backlog). Those tasks are verified by `./kotlin build`/`test` staying green
  (they must not break resource bundling) plus manual/browser checks noted in Post-Completion. This is
  stated honestly rather than faked into a passing test.
- Keep raw cinterop behind pure interfaces with `Fake…` (KT-78062: custom cinterop does not link into the
  test binary). None of these four features need new cinterop.
- Use `kotlin.time.Clock` / injected `now: () -> Long`; never `getTimeMillis()`.
- Generated / test-visible symbols must be `public` (no friend-module for `test` in Toolchain 0.11).

## Testing Strategy

- **unit tests**: required for every Kotlin task (pure scanners, store round-trips, `SessionManager`
  lifecycle, DTO mapping, adapter launch specs).
- **e2e tests**: the project has **no** UI e2e harness (backlog). Frontend behavior (sidebar rendering,
  Done/Restore buttons, notifications) is verified manually — captured in Post-Completion, not as
  automated tests.
- **migration coverage** (Task 4): the in-memory store always runs `create()` (never `migrate()`), so the
  init-`ALTER` is covered by a dedicated test that opens the store over a driver whose `sessions` table was
  created *without* `archived` and asserts the column is added and round-trips.
- **wiring coverage** (Tasks 10, 11): each model-capture seam has an end-to-end test (a claude hook POST
  persisting the model; `SessionManager.start` invoking the codex capture) so a green suite cannot hide an
  unwired feature — the failure mode the isolated unit tests would miss.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update this plan if implementation deviates from scope

## Solution Overview

- **Feature 1** threads the CLI version/path as pure metadata on `LaunchSpec` (symmetric with
  `preallocatedSessionId`): detection happens once at bootstrap (IO already done), the adapter echoes the
  injected values into the spec, and `SessionManager.start` copies them into `SessionMeta`. Display swaps
  the sidebar sub-line's cwd for the version, keeping cwd in the row `title`.
- **Feature 2** adds an orthogonal `archived` boolean (never touched by the reducer/control-state paths).
  "Done" is a daemon control op = `terminate(Kill)` then `setArchived(true)`, exposed as
  `POST /sessions/{id}/done`; "Restore" = `setArchived(false)` via `/undone`. The acting client patches
  its row from the action's response DTO; cross-device consistency comes from `archived` riding the
  existing `SessionUpdate` → `/events` resync.
- **Feature 3** keeps population best-effort and out of the pure normalizer: a pure `extractModel` scanner
  plus provider-specific capture, persisted once via a targeted `setModel`. **The two providers use
  different, separately-wired seams** (unit-testing the scanner in isolation would leave the daemon wiring
  unverified, so each seam gets an end-to-end test):
  - **Claude** — in the hook ingress: the shared `hookRoutes` gains an optional `onHookPayload` callback
    (fired after payload parse + pane resolve, regardless of normalize); `claudeHookRoutes` passes one
    (defaulted to a real `ClaudeModelCapture(store)`, so production is wired with **no `Server.kt` change**)
    that reads `transcript_path` from the payload, extracts the model, and `setModel`s it once while the
    session's model is still null. `codexHookRoutes` leaves `onHookPayload` at its no-op default (untouched).
  - **Codex** — post-launch, because the rollout does not exist at launch: `SessionManager` gains an
    injected background `captureModelInBackground: (SessionId, suspend () -> String?) -> Unit = { _, _ -> }`
    (default no-op keeps existing tests green), invoked in `start()`; `Commands.kt` wires it to a
    `bgScope.launch` that polls `rolloutScan.discoverModel(cwd, createdAt)` (codex only) and `setModel`s the
    first hit. This is separate from `discoverProviderId` (no re-scan/concern-mixing).
- **Feature 4** is an isolated `lib/notify.js` + a per-device toggle + a few lines in the existing
  `session_update` handler to fire on the not-attention → attention edge.

## Technical Details

- **`LaunchSpec`** gains `cliVersion: String? = null`, `cliPath: String? = null` (echoed by adapters, both
  New and Resume).
- **`SessionMeta`** gains `archived: Boolean = false` (declared after the cache fields; construct with
  named args as today).
- **`sessions` schema** gains `archived INTEGER NOT NULL DEFAULT 0` in the `.sq` `CREATE TABLE` (so fresh
  DBs get it). **Migration is a hand-rolled idempotent `ALTER` in `SqliteEventStore.init`, NOT a `.sqm`:**
  the vendored `sqldelight-gen` plugin runs with `deriveSchemaFromMigrations = false` /
  `verifyMigrations = false` and filters `MigrationFile`s out under exactly those flags, so a `.sqm` would
  be silently dropped and `Schema.migrate()` would stay empty (confirmed by plan-review reading the
  plugin). Instead, `init` runs `ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0`
  wrapped in `runCatching` that ignores the "duplicate column" error — additive and idempotent, correct on
  both a fresh DB (column already created → ALTER is a caught no-op) and an existing v1 DB (column added).
  `Schema.version` stays 1; no `.sqm`.
- **`SessionDto`** gains `cliVersion`, `cliPath`, `archived`, `model`. **`SessionUpdate` /
  `SessionUpdateDto`** gain `archived` — on the core `SessionUpdate` (not only the snapshot DTO) so the
  live and resync paths agree: were `archived` carried only by `SessionMeta.toUpdateDto()` (resync), a live
  `session_update` for the same session would send `archived=false` and un-hide it in another client until
  the next 15s resync flipped it back — a visible flicker. Populated from the row/meta already in hand at
  each of the four construction sites. (Acting client still gets the change instantly via the action's
  response DTO; other devices within the 15s resync.)
- **`EventStore`** gains `setArchived(id, archived, updatedAt)` and `setModel(id, model, updatedAt)` —
  targeted updates that leave `state`/`last_seq`/`provider_session_id` untouched (like
  `updateControlState`).
- **`extractModel(text): String?`** returns the first `"model":"<v>"` value; the `":"` after `model`
  prevents matching `"model_provider"`.

## What Goes Where

- **Implementation Steps** (`[ ]`): all Kotlin + webui code and their unit tests.
- **Post-Completion** (no checkboxes): manual browser verification of the sidebar, Done/Restore, and
  notifications; end-to-end model population against a live provider (population is best-effort and only
  observable with a real transcript/rollout).

## Implementation Steps

### Task 1: Carry cliVersion/cliPath through LaunchSpec and the adapters

**Files:**
- Modify: `src/adapter/LaunchSpec.kt`
- Modify: `src/adapter/claude/ClaudeAdapter.kt`
- Modify: `src/adapter/codex/CodexAdapter.kt`
- Modify: `test/adapter/claude/ClaudeAdapterTest.kt`
- Modify: `test/adapter/codex/CodexAdapterTest.kt`

- [ ] add `cliVersion: String? = null` and `cliPath: String? = null` to `LaunchSpec` (KDoc: pure metadata,
      the IO happened at bootstrap; symmetric with `preallocatedSessionId`)
- [ ] add matching constructor params to `ClaudeAdapter` / `CodexAdapter` (default null) and echo them into
      every returned `LaunchSpec` (both `New` and `Resume` branches)
- [ ] write tests: `buildLaunchSpec(New)` and `buildLaunchSpec(Resume)` carry the injected `cliVersion` /
      `cliPath` for both adapters
- [ ] write tests: defaults are null when not supplied (back-compat)
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 2: Populate SessionMeta from the spec and detect the version once at bootstrap

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `test/adapter/FakeAdapter.kt`
- Modify: `test/daemon/SessionManagerTest.kt`

- [ ] in `SessionManager.start()` copy `spec.cliVersion` / `spec.cliPath` into the constructed
      `SessionMeta` (leave `resume()` untouched — version is persisted from the first start)
- [ ] in `Commands.kt`, detect the version once: `val claudeVersion = claudeCli.detectVersion()` and drive
      the gate via `ClaudeCli.supportsSessionId(claudeVersion)` (no second binary call); likewise
      `CodexCli().detectVersion()`. Note the types: `detectVersion()` returns `ClaudeVersion?`/`CodexVersion?`
      (data classes), so pass `cliVersion = claudeVersion?.toString()` (both define `toString()` → e.g.
      `"2.1.218"`); `cliPath` is the separate `locate()` result (already a `String?`). Feed both into each
      factory closure.
- [ ] give `FakeAdapter` optional `cliVersion`/`cliPath` fields wired into its canned `LaunchSpec`
- [ ] write test: `start()` persists `cliVersion`/`cliPath` from the adapter's spec into the stored
      `SessionMeta`
- [ ] write test: a spec without version yields a null `cliVersion` (no crash)
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 3: Expose the version in the DTO and the sidebar

**Files:**
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `test/transport/TransportTest.kt` (the embedded-server suite that already decodes `SessionDto`; there is no `ControlRoutesTest.kt`)

- [ ] add `cliVersion: String?` and `cliPath: String?` to `SessionDto` and map them in `toDto()`
- [ ] in `Sidebar.js` `SessionRow`, change the sub-line from `${agent} · ${cwd}` to
      `${agent} · ${cliVersion || cwd}`, and add `title=${cwd}` on the row `<li>` so the path survives when
      grouping is off
- [ ] write test (in `TransportTest.kt`): a started session's `GET /sessions/{id}` DTO carries
      `cliVersion`/`cliPath` (and both are null-safe when absent)
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task
- [ ] **commit**: `feat: show the agent CLI version on each session`

### Task 4: Add the `archived` column via an idempotent init-migration

Chosen approach (see Technical Details): the vendored plugin drops `.sqm` files, so do NOT use one. Add the
column to the `.sq` `CREATE TABLE` (fresh DBs) and a hand-rolled idempotent `ALTER` in `init` (existing
DBs). `Schema.version` stays 1.

**Files:**
- Modify: `sqldelight/io/kotgent/db/Sessions.sq`
- Modify: `src/core/SessionMeta.kt`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `test/store/EventStoreTest.kt`

- [ ] add `archived INTEGER NOT NULL DEFAULT 0` to the `sessions` `CREATE TABLE`, to the `upsert` column
      list / `VALUES` / `ON CONFLICT DO UPDATE`, and add a `setArchived` query (targeted
      `UPDATE sessions SET archived = ?, updated_at = ? WHERE id = ?`)
- [ ] in `SqliteEventStore.init` (after the WAL pragma), run the additive migration via
      `driver.execute(null, "ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0", 0)`
      wrapped in `runCatching { }` that swallows the "duplicate column name" failure — idempotent on both a
      fresh DB (column already created by `create()` → caught no-op) and an existing v1 DB (column added).
      `ALTER … ADD COLUMN` returns no rows, so use `execute`, not `executeQuery`. Comment WHY the `.sqm`
      path is not used.
- [ ] add `archived: Boolean = false` to `SessionMeta`; read/write it in `SqliteEventStore.toMeta()` and the
      `upsert(...)` call (SQLDelight maps the INTEGER as Long → compare `!= 0L`; write `if (archived) 1 else 0`)
- [ ] write test: `upsertSession` → `getSession` round-trips `archived` (both true and false)
- [ ] write test (real migration path): open a `SqliteEventStore.using(driver)` over a driver whose
      `sessions` table was created WITHOUT `archived` (raw `driver.execute` of the pre-`archived`
      `CREATE TABLE`), then assert the store's `init` added the column and an upserted row reads back
      `archived == false` — this exercises the init `ALTER`, which the in-memory `create()` path never does
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 5: EventStore.setArchived and archived on SessionUpdate

**Files:**
- Modify: `src/store/EventStore.kt`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `test/store/EventStoreTest.kt`

- [ ] add `suspend fun setArchived(sessionId, archived, updatedAt)` to the `EventStore` interface (KDoc:
      orthogonal to state; never touched by `append`/`updateControlState`)
- [ ] implement it in `SqliteEventStore` (under `mutex`, call `sessions.setArchived`, emit a `SessionUpdate`)
- [ ] add `archived: Boolean` to `SessionUpdate` (on the core signal, not only the snapshot DTO — see
      Technical Details: avoids the live-vs-resync flicker); populate it at every construction site
      (`append`, `upsertSession`, `updateSessionState`, `setArchived`) from the row/meta already in hand
- [ ] write test: `setArchived` persists and emits a `SessionUpdate` whose `archived` matches
- [ ] write test: an ordinary `append` / `updateSessionState` leaves `archived` unchanged and reports it
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 6: SessionManager.markDone / undone

⚠️ **Do NOT wrap `markDone` in `withControlLock`.** `terminate(id)` already acquires the session's control
lock internally (`SessionManager.kt:449`), and that `Mutex` is **non-reentrant** — an outer
`withControlLock(id)` would double-acquire and **deadlock (hang the test run)**. Compose it exactly like
`stop()` (`SessionManager.kt:346-352`), which calls `terminate(id)` with no outer lock. `setArchived` is
orthogonal (never touches control state), so a control op racing between the kill and the archive is benign.

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `test/daemon/SessionManagerTest.kt`

- [ ] add `suspend fun markDone(sessionId)` = `terminate(sessionId)` (which locks internally) then
      `store.setArchived(sessionId, true, now())` — NO outer `withControlLock`
- [ ] add `suspend fun undone(sessionId)` = `store.setArchived(sessionId, false, now())` (no tmux side effect)
- [ ] write test: `markDone` kills the tmux session (fake records `killSession`) AND sets `archived = true`
      (the test must complete, not hang — direct proof the deadlock is avoided)
- [ ] write test: `undone` clears `archived` without touching tmux
- [ ] write test: `markDone` on an unknown id is a no-op / consistent with existing control-op behavior
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 7: Transport — done/undone actions, DTO + WS carry archived

**Files:**
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `src/transport/EventsWs.kt`
- Modify: `test/transport/TransportTest.kt` (holds both the `SessionDto` and the `SessionUpdateDto`/WS coverage; there is no `ControlRoutesTest.kt`/`EventsWsTest.kt`)

- [ ] add `"done" -> sessionManager.markDone(id)` and `"undone" -> sessionManager.undone(id)` to the
      `POST /sessions/{id}/{action}` switch (the handler already returns the updated `SessionDto`)
- [ ] add `archived: Boolean` to `SessionDto` and map it in `toDto()`
- [ ] add `archived: Boolean` to `SessionUpdateDto`; populate it in `SessionUpdate.toDto()` and
      `SessionMeta.toUpdateDto()`
- [ ] write test: `POST …/done` returns a DTO with `archived == true`; `…/undone` returns `archived == false`
- [ ] write test: `SessionUpdateDto` (live + snapshot) carries `archived`
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 8: Frontend — hide archived, Done/Restore, Show-done toggle

**Files:**
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/style.css`

- [ ] `app.js`: generalize `controlSession(action, id = activeRef.current)` to accept an explicit session id
      (default keeps today's active-session behavior). ⚠️ Needed because `controlSession` currently only
      acts on the *active* session — an archived row is generally not active, so `controlSession("undone")`
      without an id would restore the wrong session or none
- [ ] `Sidebar.js`: exclude `archived` sessions from the attention list, the flat/grouped session lists,
      the group counts, and the attention count; add a footer toggle "Show done (N)" that reveals an
      archived section; on archived rows show a "Restore" action → `controlSession("undone", session.id)`
      (explicit id)
- [ ] `TerminalPane.js`: add a "Done" button to the header controls (danger style) that calls a new
      `onDone` prop; wire `onDone` in `app.js` to `controlSession("done")` (active session — the one shown)
      guarded by `window.confirm("Mark done? This stops the agent and hides the session.")`
- [ ] `app.js`: in the `session_update` handler, also patch `archived` from the message (so a Done from
      another device hides the row within the resync); the acting client already patches from the action
      response DTO
- [ ] `style.css`: minimal styling for the toggle / archived section / Done button
- [ ] verification: `./kotlin build && ./kotlin test` stays green (resource bundling intact); manual browser
      check listed in Post-Completion (no JS unit harness exists)
- [ ] **commit**: `feat: Done — stop an agent and archive its session off the sidebar`

### Task 9: Pure extractModel scanner

**Files:**
- Create: `src/adapter/ModelScan.kt`
- Create: `test/adapter/ModelScanTest.kt`

- [ ] implement `fun extractModel(text: String): String?` returning the first `"model":"<v>"` value,
      host-free and side-effect-free
- [ ] write test: extracts `claude-opus-4-8` from a Claude-transcript sample line
- [ ] write test: extracts `gpt-5.5` from a Codex `turn_context` sample line
- [ ] write test: a body with only `"model_provider":"openai"` (no `model`) yields null (no false match)
- [ ] write test: absent field / empty input yields null
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 10: Claude model capture via the hook ingress + EventStore.setModel

Wiring detail (the crux — an isolated `ClaudeModelCapture` unit test would pass even if the daemon never
calls it): add an optional `onHookPayload: suspend (SessionId, JsonElement) -> Unit = { _, _ -> }` param to
the shared `hookRoutes`, invoked once the payload is parsed and the pane→session is resolved, **regardless
of the normalize result** — Claude hooks that normalize to `null` (e.g. `SessionStart`) still carry
`transcript_path`, so gating on `store.append` would miss them. `claudeHookRoutes` passes an `onHookPayload`
that delegates to a `ClaudeModelCapture` (defaulted to a real instance, so production is wired with **no
`Server.kt` change**). `codexHookRoutes` leaves the default no-op. Because `Server.kt:96` calls
`claudeHookRoutes(...)` with defaults, the capture is live in production automatically; the end-to-end test
below is what proves the wire.

**Files:**
- Create: `src/transport/ClaudeModelCapture.kt` (in `transport`, next to the route it serves; file IO via
  stock `platform.posix` `fopen`/`fseek`/`fread` — links into the test binary, cf. `CodexRolloutScan`)
- Modify: `src/transport/HookRoutes.kt`
- Modify: `src/store/EventStore.kt`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `test/transport/HookRoutesTest.kt`
- Modify: `test/store/EventStoreTest.kt`

- [ ] add `suspend fun setModel(sessionId, model, updatedAt)` to `EventStore` + `SqliteEventStore` (targeted
      update + `sessionUpdates` emit; leaves state/seq/provider id untouched)
- [ ] add `ClaudeModelCapture(store, readTranscriptTail: (String) -> String? = ::defaultReadTail)`: given a
      `SessionId` and the hook payload, pull `transcript_path`, read a bounded tail, `extractModel`, and
      `setModel` **once** while the session's model is still null (guard on `store.getSession(id)?.model == null`)
- [ ] add the `onHookPayload` param to `hookRoutes` (invoked after payload parse + pane resolve, regardless
      of normalize) and give `claudeHookRoutes` an optional `modelCapture: ClaudeModelCapture =
      ClaudeModelCapture(store)` param (default references the `store` param — production stays default-wired;
      the e2e test injects one with a fake reader), passing
      `onHookPayload = { sid, payload -> modelCapture.maybeCapture(sid, payload) }`; awaited inline (bounded
      read — no `CoroutineScope` needed); best-effort, never fail the hook on a miss
- [ ] write unit test (`ClaudeModelCapture`): a fake `readTranscriptTail` yields the model → `setModel` once;
      idempotent (a second call with model already set does not re-write); missing `transcript_path` / no
      model → model stays null
- [ ] write end-to-end test (`HookRoutesTest`): POST a claude hook whose payload has a `transcript_path` a
      fake reader resolves, then assert the session's stored `model` was persisted — this fails if the route
      wiring is absent
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 11: Codex model discovery via the rollout scan

Seam detail (the codex model can only be read *after* launch — the rollout does not exist yet at
`start()` — and the only per-session post-launch hook in the daemon is `SessionManager.start`). Give
`SessionManager` an injected `captureModelInBackground: (SessionId, suspend () -> String?) -> Unit =
{ _, _ -> }` (default no-op keeps every existing `SessionManager` test green), invoked once in `start()`
right after pane registration. `Commands.kt` wires it to `{ id, discover -> bgScope.launch { discover()
?.let { store.setModel(id, it, now()) } } }`, and passes `discover = { rolloutScan.discoverModel(meta.cwd,
meta.createdAt) }` for codex only (null for claude — its model comes from Task 10's hook path). Keep this
separate from `discoverProviderId` (no re-scan, no concern-mixing). ⚠️ The `CodexRolloutScan` unit test
alone would pass even if `SessionManager` never calls it, so the `SessionManager` test below must assert the
invocation.

**Files:**
- Modify: `src/daemon/CodexRolloutScan.kt`
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `test/daemon/CodexRolloutScanTest.kt`
- Modify: `test/daemon/SessionManagerTest.kt`

- [ ] add `discoverModel(cwd, notBeforeMillis): String?` to `CodexRolloutScan` — locate the session's
      rollout (as `discoverSessionId` does) and read enough of the file to reach the `turn_context` model
      (deeper than `HEAD_BYTES`; introduce a larger bounded `MODEL_SCAN_BYTES` and reuse `extractModel`)
- [ ] add the injected `captureModelInBackground` param to `SessionManager` and invoke it in `start()`;
      wire it in `Commands.kt` to a `bgScope.launch` that calls `discoverModel` (codex only) and `setModel`s
      the first non-null hit
- [ ] write test (`CodexRolloutScanTest`): `discoverModel` finds `gpt-5.5` in a fixture rollout whose
      `turn_context` sits PAST the head-read boundary; a rollout with no model line yields null
- [ ] write test (`SessionManagerTest`): `start()` invokes `captureModelInBackground` for the new session
      (inject a fake that records/runs it synchronously and assert `store.setModel` landed) — proves the wire
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 12: Expose the model in the DTO and the sidebar

**Files:**
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `test/transport/TransportTest.kt`

- [ ] add `model: String?` to `SessionDto` and map it in `toDto()`
- [ ] `Sidebar.js`: render `model` alongside the version in the sub-line
      (e.g. `${agent} · ${[model, cliVersion].filter(Boolean).join(" · ") || cwd}`), null-safe
- [ ] write test (in `TransportTest.kt`): a session's DTO carries `model` (present and null)
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task
- [ ] **commit**: `feat: capture and display the model each session is using`

### Task 13: notify.js and the per-device notifications toggle

Keep the toggle state in a **separate localStorage key owned by `notify.js`** (its own `isEnabled()` /
`setEnabled()`), NOT folded into the existing `{basePath, groupingLevel}` prefs object — that object is
`app.js` state threaded to `Sidebar` as a prop and coerced by `sanitizePrefs`, so extending it would drag
`app.js` + `prefs.js` into a frontend-only concern. `notifyAttention` checks `isEnabled()` internally, so
`app.js` (Task 14) never has to read or thread the pref.

**Files:**
- Create: `resources/webui/lib/notify.js`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/style.css`

- [ ] `notify.js`: `isEnabled()`/`setEnabled(b)` over a dedicated localStorage key; `ensurePermission()`
      (requests `Notification.permission` on demand); `notifyAttention(session)` (no-ops when
      disabled/unsupported/denied)
- [ ] add a prominent on/off toggle in the sidebar header bound to `isEnabled()`/`setEnabled()`; enabling it
      triggers `ensurePermission()`
- [ ] verification: `./kotlin build && ./kotlin test` stays green (resource bundling intact); manual browser
      check in Post-Completion (no JS unit harness exists)
- [ ] run `./kotlin build && ./kotlin test` — must pass before next task

### Task 14: Fire notifications on the not-attention → attention edge

**Files:**
- Modify: `resources/webui/app.js`

- [ ] in the `session_update` handler, compare the previous row's attention state to the incoming one; on a
      not-needs-attention → needs-attention transition, call `notify.notifyAttention(session)` (which
      internally no-ops unless the toggle is on and permission granted — `app.js` reads no pref)
- [ ] ensure no notification fires on the initial snapshot/backfill or the 15s resync (only on a genuine
      live edge — the compared `prev` row must already exist and have been not-attention)
- [ ] verification: `./kotlin build && ./kotlin test` stays green; manual browser check in Post-Completion
- [ ] **commit**: `feat: browser notifications with a per-device toggle`

### Task 15: Verify acceptance criteria

- [ ] version shows on every session (sidebar sub-line); cwd remains available via the row title
- [ ] Done stops the agent and archives it; archived sessions are hidden by default; Show-done reveals them;
      Restore un-archives; archived state is consistent across a second browser within the resync window
- [ ] model appears when the provider record exposes it; its absence never breaks build/tests/UI
- [ ] a session entering needs-attention raises a notification only when the toggle is on and permission
      granted; toggling off suppresses it
- [ ] run full suite: `./kotlin build && ./kotlin test` — **361+ run, 0 skipped**, all green
- [ ] confirm no machine-specific absolute path landed in any `*.yaml`
      (`git grep '/Users/' -- '*.yaml'` stays empty)

### Task 16: Update documentation

- [ ] `README.md`: move the implemented items out of the backlog — "Show the provider version once a session
      is deployed", "Session archiving" (now "Done"), and the "prominent notification toggle"; keep
      `cursor-cli`
- [ ] `CLAUDE.md`: note any new load-bearing facts discovered (the `.sqm`/`Schema.version` migration
      mechanism if it worked; the `archived` orthogonal-flag convention; the model-capture seam)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — informational only.*

**Manual verification (browser — no JS e2e harness exists):**
- Sidebar shows `agent · model · version`; the row `title` still reveals the cwd when grouping is off.
- Done on a live session: confirm dialog → agent's tmux session dies → the row disappears from the default
  view; "Show done (N)" reveals it; Restore brings it back (dead/resumable, then Resume works).
- Open a second browser/device: a Done performed in one is reflected in the other within ~15s (the
  `/events` resync interval).
- Notifications: toggle on → grant permission → drive a session into needs-attention (e.g. a Claude
  `Notification` hook) and confirm exactly one notification; toggle off → none.

**End-to-end model population (needs a live provider, cannot run in automation):**
- Model population is best-effort and only observable against a real transcript/rollout. After a real
  `claude`/`codex` session produces output, confirm `SessionMeta.model` is filled and shown. If a
  provider's on-disk format shifts, `extractModel` returns null and the UI simply omits the model — adjust
  the scanner against a fresh sample if needed.

**Migration note:**
- The `archived` column is added to the developer's existing `~/.kotgent/kotgent.db` by the idempotent
  init-`ALTER` (Task 4) on first launch of the new daemon — no `.sqm`, no `Schema.version` bump (the
  vendored plugin drops migration files). The Task 4 migration test proves the `ALTER` path; still worth a
  one-time manual launch against the real DB to confirm the column appears and existing sessions load.
