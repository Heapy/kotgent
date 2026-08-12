# Deleting a project: a tombstone the resolver respects, not a row the filesystem resurrects

## Overview

A project cannot be removed today by any means the product offers. `GET /api/v1/projects` and
`POST /api/v1/projects` are the whole HTTP surface, `project list` and `project init` the whole CLI one,
and `Projects.sq` carries `upsertProject` / `selectProject` / `selectAllProjects` and no `DELETE` at all.
The only way out is editing `~/.kotgent/kotgent.db` by hand with the daemon stopped. The reported case is
mundane and has no answer: a project created in the wrong directory, now permanently in the selector.

The obvious fix — delete the row — does not work, and the reason is the design rather than an oversight.
`.kotgent.json` is what BINDS a directory to a project (and carries the identity that survives a move, a
clone and a worktree, which is why `/repo` and `/repo-wt/feature` are one backlog). The file outlives any
row: the first `kotgent start` in that directory calls `resolveAndRegisterProject` → `upsertProject` and
the project is back, with the same uuid and an empty backlog. A delete that undoes itself is worse than
no delete. Removing the file instead is not available either — the daemon writes into an operator's
repository only when a task is created, and in two of the four real scenarios there is nothing to remove
(the directory is already gone) or the file belongs to somebody else (after an id change it carries a
DIFFERENT uuid, and deleting it would take the live project with it).

So deletion becomes a **tombstone**: one `archived` column on `projects`, set by an explicit operator
action and respected by every resolution path. The file is never touched. Nothing cascades — tasks,
dependencies, activity, `sessions.project_id` and `sessions.task_ref` are left exactly as they are — so
restore returns everything and the operation needs no `--force` and no confirmation beyond an ordinary
dialog. The mark is cleared only by an operator asking for it: `POST /projects` (adopt) or an explicit
restore. This mirrors `SessionManager.resume`, which clears a session's `archived` for the same reason —
reviving something under a hidden row is how a row is lost.

## Context (from discovery)

Files and components involved:

- `sqldelight/io/kotgent/db/Projects.sq` — the table, `upsertProject`, `selectAllProjects`.
- `src/store/TaskStore.kt` + `src/store/SqliteTaskStore.kt` — the interface and its single writer;
  `init` runs `CREATE_TABLES_IF_NOT_EXISTS` (`SqliteTaskStore.kt:56`).
- `src/task/Task.kt:46` — `ProjectRecord(id, name, path, updatedAt)`.
- `src/store/SqliteEventStore.kt:425` — `hasColumn`, currently a `private fun SqlDriver.hasColumn`, i.e.
  not reachable from the task store.
- The five places that read a project file: `SessionManager.resolveAndRegisterProject`,
  `Reconciler`'s `project_id` backfill, `TaskWriteRoutes.resolveProjectForCreate`,
  `POST /projects`' adopt branch, and the CLI's own local `resolveProject(PosixProjectFs(), …)`.
- `fakes/src/store/FakeTaskStore.kt:318` — the shared double both the native test fragment and
  `webuicheck` depend on.
- `resources/webui/app.js` — owns `projectId` (line 164) and the shared `dialog` state with its `kind`
  discriminator (`help` / `phone` / `prefs` / `upload`); `resources/webui/lib/commands.js` is the one
  command registry.

Patterns to follow:

- Additive column migration: `.sq` for a fresh database, a guarded `ALTER` in the owning store's `init`.
  `sessions.archived` in `SqliteEventStore` is the precedent, and the guard is load-bearing — sqliter
  LOGS a failing statement with a stack trace before it throws, so `runCatching { ALTER }` prints a wall
  of red on every daemon start for a pure no-op.
- Route shape: `delete("/tasks/{ref}")` for removal, `post("/tasks/{ref}/<verb>")` for an action.
- One-shot request counters (`newTaskRequest` + `servedRequestRef`) exist ONLY for forms the board
  renders. They are not the pattern here and must not be copied — see Solution Overview.

## Development Approach

- **testing approach**: test-first for the behavioural invariants (a tombstoned project does not come
  back; `upsertProject` cannot clear the mark; `task add` refuses instead of writing a file), ordinary
  order for the mechanical parts (column, DTO field, dialogs). `docs/TESTING.md` asks for the regression
  to be seen failing for the intended reason, and those three are the regressions.
- complete each task fully before moving to the next.
- **every task includes its tests**, listed as separate checklist items.
- **all tests pass before the next task starts.** Task 1 deliberately carries the signature change AND
  every call site, so the tree never sits in a non-compiling state between tasks.
- `./kotlin build` before `./kotlin test` — `PtyTest` execs `ptycheck` and the whole browser tier execs
  `webuicheck`; no test task links a main binary.
- update this plan when scope changes.

## Testing Strategy

- **unit / component**: the resolution invariants over `FakeProjectFs` + `FakeTaskStore` — this is where
  the reported bug reproduces, and no browser is needed for it.
- **persistence**: `TaskStoreTest` against the real engine, including a database written before the
  column existed. `EventStoreTest`'s pre-`archived` reopen is the shape to copy.
- **transport**: real Ktor requests for idempotency, `404` boundaries, the `?archived=true` selection,
  and the refusal that must not create a file (asserted through `MemoryProjectFileWriter` staying empty).
- **browser (`webuitest`)**: the palette rows, the delete dialog's effect on the sidebar and the
  selection, and restore returning the project. Requires `archived` support in `FakeTaskStore` and a
  `webuicheck` scenario that seeds an archived project.
- **CLI**: parser and JSON contract against stubs.
- `EXPECTED_CHECKS` in `webuicheck` is NOT touched — nothing here depends on cinterop.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- keep this file in sync with the actual work

## Solution Overview

**The mark lives on the project row and nowhere else.** `projects.archived` is the only new state. No
second table, no tombstone registry, no cascade. Restore is `archived = 0` and everything is back,
which is what removes the need for `--force`, for a confirmation that counts tasks defensively, and for
any compensating write.

**Registration must answer, atomically, whether it registered.** `upsertProject` is called from five
places and is the exact operation that resurrects a project. It gains a return value — registered, or
refused because the project is archived — computed under the store's own mutex. A read-then-write at each
call site would leave a window between the read and the write for a concurrent restore, and would spread
the rule across five files instead of one. `archived` never appears in `ON CONFLICT DO UPDATE SET`, so an
upsert cannot clear the mark even by accident.

**Clearing is an explicit act.** `POST /projects` (adopt) and `POST /projects/{id}/restore` clear it;
nothing else does. `restore` exists separately from adopt because an orphan — the directory was deleted —
cannot be adopted at all: there is no path to canonicalize.

**The Web UI dialogs live in `app.js`, not in `Board.js`.** `app.js` already owns the `dialog` state and
the selected `projectId`, and both dialogs change exactly that state. The board's one-shot counters exist
because the board RENDERS the create-task form and the create-project form beside it (they share the
directory-completion field); our dialogs share nothing with the board, so they need no counter, no prop
and no reset on leaving the screen.

**Deliberate limitation:** no `project_update` frame is added to `/api/v1/events`. The project list is
already the one thing the task side fetches on every entry to `/tasks` and never polls, so the tab that
deleted a project re-reads it, and a second tab sees the change on its next visit to the board. Record
this in the route's KDoc rather than growing the frame hierarchy for it.

## Technical Details

**Schema.** `archived INTEGER NOT NULL DEFAULT 0` on `projects`. Fresh databases get it from
`Projects.sq` and from `SqliteTaskStore`'s `CREATE TABLE IF NOT EXISTS`; pre-existing ones get a guarded
`ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0` in `init`, run only when
`hasColumn("projects", "archived")` is false.

**`hasColumn` is currently private to `SqliteEventStore.kt`.** Move it to a shared, `public` home
(`src/store/Migrations.kt`) and have both stores use it. It is the one helper whose behaviour must be
identical in both places, and Kotlin Toolchain 0.11 has no friend-module relationship to the test
fragment, so `internal` would hide it from tests.

**Store surface.**

- `ProjectRecord` gains `archived: Boolean`.
- `TaskStore.upsertProject(id, name, path)` returns a result distinguishing "registered" from
  "archived, nothing written".
- `TaskStore.setProjectArchived(id, archived): Boolean` — `false` when no such row.
- `listProjects(archived: Boolean = false)` (or a second query) selects one side or the other; the board
  and the restore dialog each want exactly one.

**HTTP.**

| Method | Path | Behaviour |
|---|---|---|
| `DELETE` | `/api/v1/projects/{id}` | sets the mark, answers `ProjectDto` with `archived: true`; idempotent; `404` only for a uuid never seen |
| `POST` | `/api/v1/projects/{id}/restore` | clears the mark, same DTO; idempotent; `404` likewise |
| `GET` | `/api/v1/projects` | live projects only |
| `GET` | `/api/v1/projects?archived=true` | archived only, for the restore dialog |

`ProjectDto` gains `archived: Boolean`. `GET /tasks?project=` and `GET /tasks/{ref}` keep working for an
archived project — the tasks still exist and a deep link to a card must not break.

**Refusal text** for `task add` inside a session whose cwd resolves to an archived project must name the
project and the way out (`kotgent project restore <uuid>`, or `--project`). It must NOT fall through to
`ensureProjectFile`: the file is on disk, so the writer would hand back the very same uuid and the task
would land in a deleted project.

## What Goes Where

- **Implementation Steps**: everything in this repository — schema, stores, daemon, routes, CLI, Web UI,
  fixtures and tests.
- **Post-Completion**: the real-device pass for the two new dialogs and the check of the out-of-repo
  Agent Skill contract.

## Implementation Steps

### Task 1: The `archived` column, the migration, and a store contract that answers

**Files:**
- Modify: `sqldelight/io/kotgent/db/Projects.sq`
- Create: `src/store/Migrations.kt`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `src/store/SqliteTaskStore.kt`
- Modify: `src/store/TaskStore.kt`
- Modify: `src/task/Task.kt`
- Modify: `fakes/src/store/FakeTaskStore.kt`
- Modify: `src/daemon/SessionManager.kt`, `src/daemon/Reconciler.kt`,
  `src/transport/TaskWriteRoutes.kt`, `src/transport/TaskReadRoutes.kt`
- Modify: `test/store/TaskStoreTest.kt`

- [x] add `archived INTEGER NOT NULL DEFAULT 0` to `Projects.sq`; keep it out of `upsertProject`'s
      `ON CONFLICT DO UPDATE SET`, and document in the file header why
- [x] add `setProjectArchived` and an archived-scoped selection to `Projects.sq`
- [x] move `hasColumn` from `SqliteEventStore.kt` into a public `src/store/Migrations.kt`; both stores use it
- [x] add the guarded `ALTER TABLE` to `SqliteTaskStore.init` and the column to its
      `CREATE TABLE IF NOT EXISTS projects`
- [x] add `archived` to `ProjectRecord`; change `TaskStore.upsertProject` to return registered/refused and
      add `setProjectArchived`; mirror both in `FakeTaskStore`
- [x] update all five call sites mechanically — they ignore the new result for now, behaviour unchanged,
      tree compiles
- [x] write a file-backed test that a database created before the column opens, migrates and works
- [x] write tests that `upsertProject` on an archived project writes nothing and reports refusal, that
      `setProjectArchived` round-trips, and that the two selections split the list
- [x] run tests — must pass before task 2

### Task 2: Resolution respects the tombstone

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/daemon/Reconciler.kt`
- Modify: `test/daemon/TaskProjectWiringTest.kt` (both sides live here; `SessionManagerTest` /
  `ReconcilerTest` own no project wiring, so neither was touched)

- [x] write the failing test first: with `.kotgent.json` present on `FakeProjectFs` and its project
      archived in `FakeTaskStore`, a started session gets `project_id = null`
- [x] write the paired failing test: `Reconciler`'s backfill leaves such a session's `project_id` null
- [x] make `resolveAndRegisterProject` return null when registration reports refusal
- [x] make the reconciler backfill skip it quietly, the same way it skips an unresolvable cwd
- [x] add a test that an unarchived project still binds normally (the guard must not swallow the ordinary case)
- [x] run tests — must pass before task 3

### Task 3: `task add` refuses instead of creating a file

**Files:**
- Modify: `src/transport/TaskWriteRoutes.kt`
- Modify: `test/transport/TaskWriteRoutesTest.kt`

- [x] write the failing test: a session whose cwd resolves to an archived project posting `/tasks` with no
      explicit project gets a refusal, and `MemoryProjectFileWriter` recorded nothing
- [x] make `resolveProjectForCreate` refuse before the `ensureProjectFile` fallback, with a message naming
      the project and `kotgent project restore <uuid>` / `--project`
- [x] keep the explicit-`project` branch answering `404` for an archived uuid, as it does for an unknown one
- [x] add a test that a live project in the same position still creates the task
- [x] run tests — must pass before task 4

### Task 4: Adopt clears the tombstone

**Files:**
- Modify: `src/transport/TaskWriteRoutes.kt`
- Modify: `test/transport/TaskWriteRoutesTest.kt`

- [x] make `POST /projects`' adopt branch clear the mark and answer the project as live
- [x] leave the create branch untouched — it only runs when nothing owns the path
- [x] write a test that adopting the directory of an archived project restores it and returns
      `archived: false`
- [x] write a test that adopting a live project is unchanged
- [x] run tests — must pass before task 5

### Task 5: `DELETE` and `restore` routes

**Files:**
- Modify: `src/transport/TaskWriteRoutes.kt`
- Modify: `src/transport/TaskReadRoutes.kt`
- Modify: `src/transport/TaskDtos.kt`
- Modify: `src/transport/TaskRoutes.kt`
- Modify: `test/transport/TaskWriteRoutesTest.kt`, `test/transport/TaskReadRoutesTest.kt`

- [x] add `archived` to `ProjectDto` and its mapper
- [x] add `DELETE /projects/{id}` and `POST /projects/{id}/restore`, both idempotent, `404` only for an
      unseen uuid
- [x] add `?archived=true` to `GET /projects`; default stays live-only
- [x] record the no-`project_update`-frame limitation in the route KDoc, with the reason
- [x] write tests for both routes: happy path, repeat call, unknown uuid, and that the DTO carries `archived`
- [x] write a test that `GET /tasks?project=` and `GET /tasks/{ref}` still answer for an archived project
- ➕ [x] pin the store contract the routes' idempotency rests on: `setProjectArchived` answers `true` for a
      repeat of the same value, so `false` may mean only "no such row" — added to `TaskStoreTest`'s
      round-trip against the real engine, because a fake that answered otherwise would prove a fictional
      system while production 404'd on the second click
- [x] run tests — must pass before task 6

### Task 6: CLI `project delete` / `project restore` / `project list --archived`

**Files:**
- Modify: `src/cli/Cli.kt`
- Modify: `src/cli/ApiClient.kt`
- Modify: `src/cli/TaskCommands.kt`
- Modify: `src/cli/TaskCliCommands.kt`
- Modify: `test/cli/CliTaskParseTest.kt`, `test/cli/TaskCommandsTest.kt`, `test/cli/ApiClientTaskTest.kt`

- [ ] extend `parseProject` with `delete <uuid>` and `restore <uuid>` (uuid required) and
      `list [--archived]`; update the `USAGE` block
- [ ] add the two `ApiClient` calls against the real paths
- [ ] render both as the JSON-only family does — one JSON value on stdout, one error object on stderr
- [ ] write parser tests including the malformed/missing-uuid rejections
- [ ] write command tests against stubs for output shape and exit codes
- [ ] run tests — must pass before task 7

### Task 7: The two Web UI dialogs

**Files:**
- Modify: `resources/webui/lib/commands.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/lib/tasks.js`
- Modify: `resources/webui/style.css`

- [ ] add two chordless board commands to the registry: "Delete project" (disabled with no project
      selected) and "Restore project"
- [ ] add the two API calls to `lib/tasks.js` beside the existing project reads
- [ ] render both dialogs from `app.js`'s existing `dialog` state via `Dialog`, with `lightDismiss={!busy}`
- [ ] delete dialog: name, last-seen path, task count from the live list, and explicit text that the file
      stays, the tasks stay, and restore brings them back
- [ ] restore dialog: reads `?archived=true` on open, one row per project, honest empty state
- [ ] on success re-read `GET /projects` and move the selection to the first remaining project, or null
- [ ] run `node --check` on every changed module
- [ ] run tests — must pass before task 8

### Task 8: The browser tier

**Files:**
- Modify: `webuicheck/src/scenarios/Board.kt`
- Modify: `webuicheck/src/TaskCommands.kt`
- Create: `webuitest/test/ProjectArchiveTest.kt`

- [ ] seed an archived project in the board scenario, and add a stdin command to archive/restore one so a
      test can drive the transition
- [ ] write a browser test that the palette shows both rows on the board and that "Delete project" is
      disabled with no selection
- [ ] write a browser test that deleting removes the project from the sidebar and moves the selection
- [ ] write a browser test that restore returns it, driven through the restore dialog
- [ ] run `:webuitest:testJvm` — must pass before task 9

### Task 9: Verify acceptance criteria

- [ ] all four scenarios work end to end: wrong folder, finished project, deleted directory (orphan, no
      filesystem access needed), duplicate after an id change (the live file's project is untouched)
- [ ] a deleted project does not come back after `kotgent start` in its directory
- [ ] `kotgent project init` in that directory restores it, and the backlog is intact
- [ ] `./kotlin build` then `./kotlin test` — full suite green, 0 skipped
- [ ] test counts in CLAUDE.md's baseline updated to the new numbers

### Task 10: [Final] Documentation

- [ ] check whether `docs/agent-task-skill.md` needs the refusal case — it is the one artefact no test in
      this repository can catch
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

**Manual verification:**
- both dialogs on a phone and a tablet: the swipe handle, the compensated padding, backdrop dismissal, and
  that neither can be dismissed mid-request.
- an installed PWA reaching the board and deleting a project from the palette button rather than `⌘K`.

**External systems:**
- the out-of-repo Agent Skill (Heapy/Kortex) written against `docs/agent-task-skill.md` — if the refusal
  message becomes part of the contract, that skill changes with it.
