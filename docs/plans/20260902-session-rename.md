# Session rename: make `sessions.name` an operator-editable label

## Overview

A session's `name` is set once, at creation, from `--name` or — when that is absent — from the tmux
session string (`src/daemon/SessionManager.kt:230`, `:476`). Nothing can change it afterwards. An
operator who starts a session before knowing what it will become is stuck with `kotgent-<id>` for the
life of the row, and the only escape is to finish the session and start a new one, which discards its
terminal and its provider transcript. That is a bad trade for a typo.

This plan adds one write path: `PATCH /api/v1/sessions/{id}` with a `name` field, reachable from
`kotgent session rename <id> <name>` and from a Web UI command-palette dialog.

The change is small because the field already exists and is already display-only. Three facts, verified
during planning, are what make it small:

- **`name` is not an identifier.** Nothing resolves a session by it. The tmux session string comes from
  the session id (`SessionManager.kt:216`, `tmux.sessionName(shortId)`), not from `name`, so renaming
  cannot desynchronise the daemon from tmux.
- **The store already has the shape.** `setModel`, `setArchived`, `setTaskRef` and `setProjectId` are
  targeted single-column mutators that advance `rev` and call `emitFromRow`
  (`src/store/SqliteEventStore.kt:152-189`). `setName` is the same pattern with no new machinery.
- **The UI already tolerates an empty name.** `displayName` falls back to `tmuxSession` and then to the
  id (`resources/webui/lib/sessions.js:89-93`). Clearing the field is therefore "reset to the automatic
  label", not a broken row, and needs no separate reset affordance.

**The one part with no existing template:** the live WebSocket frame does not carry `name`.
`SessionUpdate` (`src/store/EventStore.kt:25`) and `SessionUpdateDto` (`src/transport/EventsWs.kt:240`)
omit it, and the browser's `patchIfNewer` (`lib/sessions.js:136`) therefore has no field to apply. A
rename that stops at the store would be invisible to every other open client until it reloaded. Adding
`name` to that frame is Task 4 and is not optional.

**Deliberately NOT in scope**, recorded so the next reader does not re-derive them:

- **Rename from the sidebar.** Palette-only for the first version (decided with the user). In-place
  editing in `Sidebar.js` needs its own focus, Escape and blur handling plus served-DOM tests, and buys
  speed for a rare action.
- **Renaming tags.** `tags` is the other operator-owned column with no write path. The `PATCH` body
  shape leaves room for it; this plan does not fill it.
- **A rename event in the log.** `name` is metadata, like `model`. It does not enter the closed
  `AgentEvent` vocabulary and does not touch the reducer.
- **Touching `updated_at`.** `setName` advances `rev` only, matching the "derived metadata mutators
  advance rev but leave updated_at to activity-owning writes" rule already stated in `Sessions.sq:79`.
  A rename is not session activity and must not reorder the done-list, which sorts by `updatedAt`
  (`lib/sessions.js:159`).

### Decisions taken with the user

| Question | Chosen | Why |
|---|---|---|
| CLI verb | `kotgent session rename <id> <name>` | Explicit namespace, symmetric with `task`/`project`. The flat `stop`/`resume`/`attach` verbs stay as they are; this plan does not migrate them. |
| Web UI entry point | Command palette only | One entry, one form. Matches `link-task`, `upload-files`, `delete-project`. |
| HTTP shape | `PATCH /sessions/{id}` | The project's existing shape for "edit a field on a resource" (`PATCH /tasks/{ref}`, `src/transport/TaskWriteRoutes.kt:65`). Leaves room for `tags`. |
| Empty name | Stores `""`, reads back as the automatic label | `displayName` already does this. No new semantics, no new UI state. |
| Length bound | 200 UTF-16 units, server-side, shared by start / import / rename | There is no server-side bound today; only the browser's `maxlength="80"` (`components/dialogs.js:390`). Rename must not be stricter than creation, so the bound is added to all three at once. |

## Context (from discovery)

- **Schema**: `sqldelight/io/kotgent/db/Sessions.sq` — `name TEXT NOT NULL` (line 3); `upsert` writes the
  full row (line 29); `setModel` (line 81) is the mutator template.
- **Store**: `src/store/EventStore.kt:52-117` (interface), `src/store/SqliteEventStore.kt:106-200`
  (implementation, `emitFromRow`), `fakes/src/store/FakeEventStore.kt:101-160` (fake, must implement any
  new interface method).
- **Transport**: `src/transport/ControlRoutes.kt` — routes and `SessionDto` (`toDto` at line 344);
  `src/transport/EventsWs.kt:240-266` — `SessionUpdateDto` and its mapper;
  `src/transport/TaskWriteRoutes.kt:65-133` — the `PATCH` handler to copy.
- **CLI**: `src/cli/Cli.kt` — `CliCommand` sealed interface (line 70), top-level dispatch (line 149),
  `parseTask` (line 388) is the sub-verb parser template; `src/cli/Commands.kt`;
  `src/cli/ApiClient.kt:188` — `patchTask` is the request-builder template.
- **Web UI**: `resources/webui/app.js:827` (`startSession`, the `runMutation` flow template), `:1052`
  and `:1158-1177` (dialog openers), `:1302-1330` (dialog rendering);
  `resources/webui/lib/commands.js` (palette entries, `disabledWhilePending` at line 35);
  `resources/webui/components/dialogs.js` (dialog components);
  `resources/webui/lib/sessions.js:136` (`patchIfNewer`), `:89` (`displayName`);
  `resources/webui/state/dialog.js` (`openDialog`, `closeDialogFrom`);
  `resources/webui/lib/mutation.js` (the shared lock).
- **Reconciler is not involved.** `Reconciler.reconcile()` has exactly one call site
  (`src/cli/Commands.kt:391`) and runs to completion before `startDaemonServer` (`:396`) binds the port.
  No rename can be in flight while its full-row `upsertSession` runs. The `name = sessions.name` guard in
  Task 1 is therefore forward-looking hardening, not a fix for a live race — say so in the commit message
  so a reader does not go looking for the bug it "fixed".
- **No new JS module.** The dialog lives in the existing `components/dialogs.js`, so the module registry
  in `test/transport/WebUiServingTest.kt:189-196` does not change.
- **Import cannot reach the upsert conflict path.** `importSession` allocates a fresh session id
  (`SessionManager.kt:470`, `freshSessionId()`), so its `upsertSession` is always an INSERT. Changing the
  conflict clause in Task 1 therefore cannot turn `import --name` into a no-op. Verified during planning;
  do not re-derive.
- **`FakeEventStore` already merges on upsert.** It preserves `createdAt`, `readCursor`, `taskRef` and
  `projectId` (`fakes/src/store/FakeEventStore.kt:69-81`), so `name` joins that list or the fake will
  accept a clobber the real store refuses. Its `emitFromMeta` (`:59-67`) also builds `SessionUpdate` and
  must carry `name` in Task 4.
- **Two counts go stale.** The header of `resources/webui/lib/mutation.js` says "all six at once" and
  lists the flow-name vocabulary; `CLAUDE.md` says "the six mutating flows share". Rename is the seventh
  and both must be updated.
- **Palette chord**: `a b c d e f h i j l m n o p r s t u w` are taken (`lib/commands.js`). Rename takes
  `chord: null`, like `general.new-project`.

## Development Approach

- **Testing approach**: regular — code first, then tests, within each task.
- Complete each task fully before moving to the next.
- **Every task ships its own tests.** Tests are a deliverable, not a follow-up.
- **All tests pass before the next task starts.**
- Build before test: `./kotlin build` then `./kotlin test`. The test command does not build `ptycheck`
  and `webuicheck`, and the tests execute them.
- **Never run two `./kotlin` invocations at once**, backgrounded included, in this checkout or any
  worktree. They share one build directory.
- Run `node --check <file>` for every changed JavaScript file.
- Update this plan file when scope changes.

## Testing Strategy

- **Kotlin unit tests** for the store mutator, the route, the DTO round-trip and the CLI parser.
- **Node tier** (`webuitest/js/**/*.test.js`, run by `WebUiLogicTest`) for the merge rule: a patch frame
  carrying a new `name` updates the row; a frame from an older daemon that omits `name` leaves the
  previous name alone.
- **Browser tier** (`webuitest/test/`, against `webuicheck`) for the dialog: the served DOM carries
  `spellcheck="false"` on the new input, and the palette entry is disabled while another mutation holds
  the lock.
- **Served-DOM assertion is mandatory** for the new text input. `spellcheck=${false}` — lowercase name,
  interpolated boolean — is the only spelling that works; the other three leave spellcheck on and only
  the served DOM separates them.

## Progress Tracking

- Mark completed items `[x]` immediately.
- New tasks get a `➕` prefix; blockers get `⚠️`.

## Solution Overview

```
Web UI palette ──┐
                 ├──▶ PATCH /api/v1/sessions/{id}  {"name": "..."}
kotgent session ─┘              │
        rename                  ▼
                    ControlRoutes: validate, bound, 404/400
                                 │
                                 ▼
                    store.setName(id, name)
                       UPDATE sessions SET name = ?, rev = ?
                       (updated_at untouched)
                                 │
                                 ▼
                        emitFromRow(id)
                                 │
                    SessionUpdate(name = ...) ──▶ WS session_update
                                 │
                                 ▼
                    patchIfNewer applies name if present
```

## Technical Details

**Bound.** One constant, `MAX_SESSION_NAME_LENGTH = 200`, applied by start, import and rename, and
counted in **UTF-16 units** — plain `String.length`. Code points would be friendlier to emoji, but HTML
`maxlength` counts UTF-16 units and Kotlin/Native's common stdlib has no `codePointCount`, so a
code-point server bound would need a hand-rolled surrogate scan *and* would still be silently overridden
by the stricter browser cap. One unit of measure on both sides is worth more than the extra headroom.
`resources/webui/lib/unicode.js` is about terminal Unicode modes and offers no helper here.

**Validation.** Reject a name containing a control character (`Char.isISOControl`) — a newline in a
sidebar row and a `\r` in a CLI table both misrender. An empty name is valid and means "use the
automatic label".

**`patchIfNewer` guard.** `name: msg.name != null ? msg.name : prev.name`. A daemon older than this
change omits the field, and `undefined` must not wipe a name the snapshot supplied. `""` is not `null`,
so a deliberate reset still applies. This mirrors the existing `updatedAt: msg.updatedAt || prev.updatedAt`
guard and its comment two lines below.

**Upsert.** `name = sessions.name` in the `ON CONFLICT` block, joining `created_at`. Both call sites that
supply a name (`SessionManager.kt:246`, `:491`) are fresh inserts, so nothing legitimately changes a name
through the full-row path.

## What Goes Where

- **Implementation Steps**: everything in this repository — schema, store, fake, transport, CLI, Web UI,
  tests, docs.
- **Post-Completion**: real-device mobile checks that Chromium cannot prove.

## Implementation Steps

### Task 1: Add the `setName` store mutator

**Files:**
- Modify: `sqldelight/io/kotgent/db/Sessions.sq`
- Modify: `src/store/EventStore.kt`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `fakes/src/store/FakeEventStore.kt`
- Modify: `test/store/` (the existing event-store test file — grep for `setModel` to find it)

- [ ] add `setName:` to `Sessions.sq` — `UPDATE sessions SET name = ?, rev = ? WHERE id = ?`, placed
      beside `setModel:` and under the existing "derived metadata mutators advance rev but leave
      updated_at" comment
- [ ] change the `upsert` conflict clause from `name = excluded.name` to `name = sessions.name`, beside
      `created_at`
- [ ] add `suspend fun setName(sessionId: SessionId, name: String)` to `EventStore`
- [ ] implement it in `SqliteEventStore` under `mutex.withLock`, advancing `revCounter` and calling
      `emitFromRow` — copy `setModel` exactly
- [ ] implement it in `FakeEventStore`
- [ ] add `name = prior.name` to the `FakeEventStore.upsertSession` merge block, beside `createdAt`, so
      the fake and the real store answer the same question
- [ ] write a test: `setName` changes the name, advances `rev`, and leaves `updatedAt` unchanged
- [ ] write a test: an `upsertSession` of a snapshot taken before a rename does not restore the old name
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 2

### Task 2: Add the shared name bound and validator

**Files:**
- Modify: `src/core/SessionMeta.kt` (or a sibling in `src/core/` — keep it host-free)
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `test/core/` (new or existing test file for the validator)

- [ ] add `MAX_SESSION_NAME_LENGTH = 200` and a pure `sessionNameProblem(name: String): String?`
      returning null when valid, in `src/core/` so it stays host-free
- [ ] reject names over the bound (counted in code points) and names containing an ISO control character
- [ ] accept the empty string
- [ ] apply the validator in the existing `POST /sessions` and `POST /sessions/import` handlers, before
      they reach `SessionManager`
- [ ] write tests for the validator: valid, empty, too long, control character, astral-plane name at the
      boundary
- [ ] write a route test: `POST /sessions` with an over-long name answers 400 and creates nothing
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 3

### Task 3: Add `PATCH /api/v1/sessions/{id}`

**Files:**
- Modify: `src/transport/ControlRoutes.kt`
- Create: `test/transport/SessionRenameRoutesTest.kt`

- [ ] add `PatchSessionRequest(val name: String? = null)` beside the other request DTOs
- [ ] add the `patch("/sessions/{id}")` handler, copying the structure of `patch("/tasks/{ref}")`:
      malformed id → 400, unknown session → 404, body carrying no field → 400 with a sentence naming what
      a patch may carry, invalid name → 400
- [ ] call `store.setName` and answer the refreshed `SessionDto`, matching the action route's tail
      (`ControlRoutes.kt:276-281`)
- [ ] register the route inside the same authorized block as the other session routes
- [ ] write tests: successful rename returns the new name and a higher `rev`; empty name is accepted;
      unknown id is 404; malformed id is 400; empty body is 400; over-long name is 400
- [ ] write a test asserting `updatedAt` is unchanged across a rename
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 4

### Task 4: Carry `name` on the live WebSocket update

**Files:**
- Modify: `src/store/EventStore.kt` (`SessionUpdate`)
- Modify: `src/store/SqliteEventStore.kt` (`emitFromRow`)
- Modify: `src/transport/EventsWs.kt` (`SessionUpdateDto`, `SessionUpdate.toDto`)
- Modify: `fakes/src/store/FakeEventStore.kt` (`emitFromMeta`)
- Modify: `test/transport/TransportTest.kt` or the nearest WS frame test

- [ ] add `val name: String? = null` to `SessionUpdate`, after `model`
- [ ] populate it in `emitFromRow` from the row
- [ ] populate it in `FakeEventStore.emitFromMeta`, which builds the same value
- [ ] add `val name: String? = null` to `SessionUpdateDto`, after `model`, and map it in `toDto`
- [ ] write a test: a rename emits a `session_update` frame whose `name` is the new value and whose `rev`
      is higher than the previous frame's
- [ ] write a test: the field is absent-tolerant — decoding a frame without `name` yields null, so an
      older client and an older daemon both stay readable
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 5

### Task 5: Apply the renamed row in the browser merge

**Files:**
- Modify: `resources/webui/lib/sessions.js`
- Modify: `webuitest/js/sessions.test.js`

- [ ] add `name: msg.name != null ? msg.name : prev.name` to the `patchIfNewer` object, with a one-line
      comment saying an older daemon omits the field and `undefined` must not wipe the snapshot's name
- [ ] run `node --check resources/webui/lib/sessions.js`
- [ ] write a node-tier test: a patch carrying a new `name` updates the row
- [ ] write a node-tier test: a patch omitting `name` leaves the previous name
- [ ] write a node-tier test: a patch carrying `""` clears the name, and `displayName` then answers the
      tmux session string
- [ ] write a node-tier test: a patch with a `rev` no higher than the row's is ignored, name included
- [ ] run `node --test 'webuitest/js/**/*.test.js'` from the repository root, then
      `./kotlin build && ./kotlin test` — must pass before Task 6

### Task 6: Add the rename dialog and its palette command

**Files:**
- Modify: `resources/webui/components/dialogs.js`
- Modify: `resources/webui/lib/commands.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/style.css` (only if the dialog needs a rule the existing ones do not give)
- Modify: `webuitest/test/` (the browser-tier suite covering dialogs)

- [ ] add a `RenameSessionDialog` to `components/dialogs.js`: one text input prefilled with the session's
      current `name`, a Save button and a Cancel button
- [ ] spell the input's attribute `spellcheck=${false}` — lowercase name, interpolated boolean
- [ ] set `maxlength` to the same 200 the server enforces, and say in the empty-field placeholder that
      clearing it restores the automatic name
- [ ] add a `session.rename` entry to `lib/commands.js` with `group: "session"`, `chord: null`, and
      `disabled: disabledWhilePending(pendingAction) || disabledWhenNoSession(activeSession)`
- [ ] add `openRename` in `app.js` writing `openDialog({ kind: "rename", session: selected })`, and
      render the dialog beside the others at `app.js:1302-1330`
- [ ] add the `renameSession` flow in `app.js` wrapped in `runMutation("rename", …)`, copying
      `startSession` (`app.js:827`): capture `dialogSignal.value` before the request, `closeDialogFrom`
      the captured instance, and route a late failure to `say(..., true)` when the dialog has since
      changed
- [ ] append `"rename"` to the flow-name vocabulary comment in `lib/mutation.js` and change its header's
      "all six at once" to seven
- [ ] run `node --check` on every changed JavaScript file
- [ ] write a browser test: the served rename input carries `spellcheck="false"`
- [ ] write a browser test: submitting the dialog renames the row in the sidebar
- [ ] write a browser test: the palette's rename entry is disabled while another mutation holds the lock
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 7

### Task 7: Add `kotgent session rename`

**Files:**
- Modify: `src/cli/Cli.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `src/cli/ApiClient.kt`
- Modify: `test/cli/CliTest.kt`
- Create or modify: `test/cli/ApiClientSessionTest.kt`

- [ ] add `renameSession(id, name): SessionDto` to `ApiClient`, copying `patchTask`
      (`ApiClient.kt:188`) — bearer, pane header, JSON content type, `ensureSuccess`
- [ ] add `CliCommand.SessionRename(val id: String, val name: String)` to the sealed interface
- [ ] add `"session" -> parseSession(rest)` to the top-level dispatch, with `parseSession` handling only
      `"rename"` today and answering `CliCommand.Invalid` with a usage line for anything else
- [ ] add the `Commands` function printing the new name on success and mapping a 404 to a clear message
- [ ] add `session rename` to the CLI help text
- [ ] write parser tests: valid form; missing id; missing name; unknown sub-verb; a name that begins with
      `-` is taken as the name, not a flag
- [ ] write an ApiClient test against a stub server: the request is a `PATCH` to `/sessions/{id}` with the
      expected body, and a non-2xx answer throws `ApiException`
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 8

### Task 8: Verify acceptance criteria

- [ ] rename from the CLI, confirm an open browser updates without a reload (this is what Task 4 buys)
- [ ] rename from the Web UI, confirm `kotgent list` shows the new name
- [ ] clear the name, confirm both clients fall back to the tmux session string
- [ ] confirm a rename does not move the session in the done-list ordering
- [ ] confirm the palette's session commands are disabled for the duration of a rename, as they are for
      the other six flows
- [ ] run the full suite: `./kotlin build && ./kotlin test`
- [ ] run `node --test 'webuitest/js/**/*.test.js'` from the repository root

### Task 9: [Final] Fold durable intent into its authoritative homes

CLAUDE.md requires durable intent to move to its permanent home and the plan to be **deleted**, not
archived. Do not create `docs/plans/completed/`.

- [ ] add the rename outcome to `docs/INTENT.md` under "User outcomes"
- [ ] add to `CLAUDE.md`, under Transport and Web UI: `SessionUpdateDto` carries `name`, and
      `patchIfNewer` treats an absent `name` as "keep", not "clear"
- [ ] add to `CLAUDE.md`, under Architecture boundaries: `upsert` no longer overwrites `name` on
      conflict; a rename goes through the targeted mutator, and `updated_at` stays owned by activity
- [ ] change "the six mutating flows" to seven in the `runMutation` paragraph of `CLAUDE.md`
- [ ] add the real-device rename checks to `docs/TESTING.md`
- [ ] delete `docs/plans/20260902-session-rename.md`

## Post-Completion

**Manual verification** (real-device constraints Chromium cannot prove, per `docs/TESTING.md`):

- On a phone, the rename dialog's input must not be covered by the on-screen keyboard, and the safe-area
  inset must still hold at the bottom of the dialog.
- The mobile keyboard must not offer autocorrect or capitalisation on the name field.

**External system updates**: none. No consumer outside this repository reads `sessions.name`.
