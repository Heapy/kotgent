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

- [x] extend `parseProject` with `delete <uuid>` and `restore <uuid>` (uuid required) and
      `list [--archived]`; update the `USAGE` block
- [x] add the two `ApiClient` calls against the real paths
- [x] render both as the JSON-only family does — one JSON value on stdout, one error object on stderr
- [x] write parser tests including the malformed/missing-uuid rejections
- [x] write command tests against stubs for output shape and exit codes
- ➕ [x] `listProjects` gained the `archived` parameter too — `project list --archived` is a wire
      selection the render layer cannot see, so `ApiClientTaskTest` is where `?archived=true` is proven
      and `TaskCommandsTest` stays about output shape
- [x] run tests — must pass before task 7

### Task 7: The two Web UI dialogs

**Files:**
- Modify: `resources/webui/lib/commands.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/lib/tasks.js`
- Modify: `resources/webui/style.css`
- ➕ Modify: `resources/webui/components/dialogs.js`

- [x] add two chordless board commands to the registry: "Delete project" (disabled with no project
      selected) and "Restore project"
- [x] add the two API calls to `lib/tasks.js` beside the existing project reads
- [x] render both dialogs from `app.js`'s existing `dialog` state via `Dialog`, with `lightDismiss={!busy}`
- [x] delete dialog: name, last-seen path, task count from the live list, and explicit text that the file
      stays, the tasks stay, and restore brings them back
- [x] restore dialog: reads `?archived=true` on open, one row per project, honest empty state
- [x] on success re-read `GET /projects` and move the selection to the first remaining project, or null
- ➕ [x] the two dialog COMPONENTS were declared in `components/dialogs.js`, beside the five other
      app-owned dialogs, and are rendered from `app.js`'s `dialog` state (`kind: "delete-project"` /
      `"restore-project"`). The plan's "in `app.js`, not in `Board.js`" rule is about which screen owns
      the state and the render — that is honoured; declaring the components where every sibling dialog
      already lives adds no served module and keeps `Board.js` out of it. `dialogs.js` now imports
      `lib/tasks.js`, which introduces no cycle.
- ➕ [x] a restore selects the project it brought back (the `projectCreated`/adopt precedent) rather than
      falling to "first remaining"; the fallback rule still applies when the restored row is absent
- [x] run `node --check` on every changed module
- [x] run tests — must pass before task 8

### Task 8: The browser tier

**Files:**
- Modify: `webuicheck/src/scenarios/Board.kt`
- Modify: `webuicheck/src/TaskCommands.kt`
- ➕ Modify: `webuitest/test/HarnessFixture.kt`
- Create: `webuitest/test/ProjectArchiveTest.kt`

- [x] seed an archived project in the board scenario, and add a stdin command to archive/restore one so a
      test can drive the transition
- ➕ [x] the seed went into a NEW board scenario (`board-projects`, declared in `Board.kt` beside the
      others and registered through `boardScenarios()`) rather than into `board` itself. `board` is the
      shared fixture of BoardTest, TaskCommandsTest and the style tests, and "the selection moves on" can
      only be observed where a project SURVIVES the delete — a lone project proves the null branch alone.
      Adding a second LIVE project to `board` would have moved what `task-add` files under
      (`listProjects().firstOrNull()`) and what the board selects by default. The new scenario seeds
      Alpha (3 tasks, selected), Beta (spare) and an archived Gamma; `fixtureProject` gained an
      `archived` flag and still writes `.kotgent.json` for an archived project, because the file the
      tombstone does not touch is the whole reason the row exists.
- ➕ [x] the stdin commands are `project-del <uuid>` / `project-restore <uuid>` (the `task-del` naming),
      dispatched from `handleTaskCommand`
- [x] write a browser test that the palette shows both rows on the board and that "Delete project" is
      disabled with no selection
- [x] write a browser test that deleting removes the project from the sidebar and moves the selection
- [x] write a browser test that restore returns it, driven through the restore dialog
- ➕ [x] the restore test also proves the dialog re-reads `?archived=true` on EVERY opening: after the
      restore it archives another project through the new stdin command and reopens, and the list is
      the daemon's new answer rather than the one it read the first time. That is what drives the
      command; a cached list is a real bug (delete, then open restore in the same session)
- ➕ [x] ~~the tests deliberately assert nothing about the restored project's CARDS~~ — the review phase
      found this was a defect rather than a limitation: `readTasksBaseline` iterated LIVE projects only,
      so the one snapshot a page builds its whole task list from omitted a deleted project's rows, which
      broke a deep link to such a card and left a restored project showing an empty backlog under a
      dialog that promises "The backlog comes back with it". It now reads both sides, Gamma is seeded
      with two cards, and the browser test asserts them on screen after the restore
- [x] run `:webuitest:testJvm` — must pass before task 9

### Task 9: Verify acceptance criteria

Verified by reading the shipped code paths and by the tests that execute them. No daemon was started —
the acceptance evidence is the assembled Ktor server, the real SQLite engine, the real store and a real
Chromium, which is what `docs/TESTING.md` asks a claim of this kind to rest on.

- [x] all four scenarios work end to end: wrong folder, finished project, deleted directory (orphan, no
      filesystem access needed), duplicate after an id change (the live file's project is untouched)
  - **wrong folder** — `DELETE /projects/{id}` (`TaskWriteRoutes.kt`) sets the mark and `GET /projects`
    defaults to live-only (`TaskReadRoutes.kt`), so the row leaves every selector:
    `TaskReadRoutesTest.projectsListsEveryLiveProjectAndNeverADeletedOne`,
    `TaskWriteRoutesTest.deletingAProjectMarksTheRowAndRemovesNothingItOwns`, and through the whole stack
    `ProjectArchiveTest.deletingTheSelectedProjectTakesItOutOfTheSidebarAndMovesTheSelectionOn`. It stays
    gone because every registration path goes through the one refusing `upsertProject`
    (`TaskStoreTest.anArchivedProjectRefusesRegistrationAndKeepsTheRowItAlreadyHad`).
  - **finished project** — the committed `.kotgent.json` still resolves and still may not resurrect the
    row: `TaskProjectWiringTest.aStartInsideAnArchivedProjectIsNotStampedAndResurrectsNothing` seeds the
    file on `FakeProjectFs` and asserts the launch succeeds, `projectId` stays null and no row was
    written; `…theBackfillSkipsASessionWhoseProjectIsArchivedInsteadOfRetryingIt` is the restart half;
    `…restoringTheProjectMakesTheNextStartBindItAgain` proves the guard reads the mark every time rather
    than latching.
  - **orphan** — both routes take the uuid from the path and touch the store and nothing else;
    `deletingAProjectMarksTheRowAndRemovesNothingItOwns` asserts `env.fs.reads.isEmpty()`, i.e. the
    filesystem was not even READ. The CLI matches: `parseProjectId` requires an explicit uuid and never
    derives one from the cwd, and `project list --archived` is how an operator finds the uuid of a
    checkout that is gone (`ApiClientTaskTest.listProjectsAsksForTheDeletedSideOnlyWhenTold`).
  - **duplicate after an id change** — `setProjectArchived` is `WHERE id = ?` and `upsertProject`'s
    tombstone read is `selectProjectArchived(<the id being registered>)`, so a stale row can be neither
    read nor written by the live one's resolution:
    `TaskStoreTest.theTwoProjectSelectionsSplitTheListAndNeitherSideSeesTheOther` against the real
    engine, and the browser tier's `board-projects` (Alpha and Beta live, Gamma archived) shows a delete
    of one leaving the others exactly as they were.
- [x] a deleted project does not come back after `kotgent start` in its directory
  - `SessionManager.resolveAndRegisterProject` returns null on `refusedArchived`, and it is the ONE
    registration site for both `start` and `importSession`. Pinned by
    `TaskProjectWiringTest.aStartInsideAnArchivedProjectIsNotStampedAndResurrectsNothing`.
- [x] `kotgent project init` in that directory restores it, and the backlog is intact
  - `project init` → `POST /projects` → the adopt branch clears the mark before registering:
    `TaskWriteRoutesTest.postProjectsAdoptingADeletedProjectsDirectoryBringsItBack` asserts the live DTO,
    the refreshed name and path, that the board lists it again and that the file was never written.
    "Intact" is the delete's own contract — `deletingAProjectMarksTheRowAndRemovesNothingItOwns` (nothing
    cascades), `TaskReadRoutesTest.aDeletedProjectsBacklogAndItsCardsStillAnswer` (the reads keep
    answering) and `restoringClearsTheMarkAndReturnsTheWholeBacklogWithIt` (the whole backlog comes back
    with the row).
- [x] `./kotlin build` then `./kotlin test` — full suite green, 0 skipped
  - `./kotlin build` successful; `./kotlin test` green. Native `:project-archive:testMacosArm64Debug`
    **1367 passed / 0 skipped** (94 test cases) after the two review rounds, browser
    `:webuitest:testJvm` **123 passed / 0 skipped**
    (22 containers), `build-info` **7 passed / 0 skipped**. `PtyTest.realPtyChecksPass` and
    `WebUiCheckTest.harnessSelfCheckPasses` both green inside the native count, so `ptycheck`'s 11 checks
    and `webuicheck`'s 2 self-checks ran as well.
- [x] test counts in CLAUDE.md's baseline updated to the new numbers
  - Nothing to update: this branch's `CLAUDE.md` was condensed (commits `2eb7c23`, `7d1836b`) into a
    71-line policy guide that records NO test counts at all, and `docs/TESTING.md` records none either.
    The measured numbers are therefore written above instead of being re-introduced into a file whose
    current form deliberately states rules rather than an inventory. Verified by searching both files for
    any baseline figure; the only number left in `docs/TESTING.md` is the historical "1181 substring
    assertions", which is a statement about the tier that was deleted and not a baseline.
- ➕ [x] `task add`'s refusal reaches the operator unaltered: `withCwdProjectFallback` — the retry that
      re-sends a locally resolved uuid after a 400 — is wired only into `task list` and `task next`, so
      `runTaskAddCommand` surfaces the daemon's 400 with no second request.
- ⚠️ **Not covered by any test, stated here rather than asserted from inspection.** The
  duplicate-after-an-id-change scenario is proven in its two halves (per-id isolation in the store; a
  resolution that keys only on the file's own uuid) but never as one composite — no test puts a
  `.kotgent.json` carrying the NEW uuid in a directory whose STALE row is tombstoned and then starts a
  session there. Likewise, no test asserts the backlog through the ADOPT path specifically; adopt and
  restore clear the same column through the same store call, so it follows, but it is inference.
- ✅ **`POST /tasks/next` did not honour the tombstone; the review phase fixed it.** It now refuses an
  archived project with the same 404 an unknown one gets (`nextRefusesADeletedProjectBecauseItStartsThe`
  `CardItHandsOut`), because it SELECTS the operator's next piece of work out of a project they deleted
  and then starts it; reads, and writes to a card the caller names, stay open. The second review round
  settled what divides the two, because the first round's KDoc had written it as "next writes, link does
  not" — which is false: `TaskService.link` makes the SAME three writes `linkNext` does (`startIfTodo`,
  the session stamp, the `linked` activity row). The discriminator is the ref, not the writing.
  `POST /tasks/{ref}/link` therefore stays OPEN, both KDocs and `docs/agent-task-skill.md` now say so in
  those terms, and `linkStaysOpenForADeletedProjectsCardBecauseItNamesOneThatAlreadyExists` asserts the
  resulting `in_progress` and the feed row rather than only the 200. The review phase also closed the sibling hole the residual named —
  `resolveProjectForCreate`'s `session.projectId` short-circuit, and its `ensureProjectFile` fallback,
  which a session whose cwd no longer canonicalizes reaches with the checkout root's existing file.
- ✅ **The `/events` task baseline read the two sides of the tombstone as TWO observations; the second
  review round fixed it.** `listProjects() + listProjects(archived = true)` took the store's lock twice,
  so a delete landing between them put one project in BOTH lists — `rows` is a list, so the snapshot
  shipped duplicate cards, and `upsertTaskIfNewer`/`patchTaskIfNewer` only ever update the first match,
  leaving the stale copy on screen — while a restore landing between them put it in NEITHER, which is
  exactly the defect the two-sided read was written to close. `baselineDue` is one-shot per socket, so
  nothing healed either. `TaskStore.listAllProjects()` (SQL `selectAllProjects`, one statement under one
  lock) replaces the pair; `TaskStoreTest.listAllProjectsAnswersBothSidesOfTheTombstoneAsOneObservation`
  covers the store and `TaskEventsTest`'s fake now THROWS from `listProjects`, so a revert to the pair
  fails there rather than in review. **Residual, recorded not fixed:** the archived side is unbounded —
  every deleted project's backlog reaches every browser on every connect and nothing prunes it. Narrowing
  it would need the socket to know which deep link the page is on, so it is a design change, not a fix.
- ✅ **The stamped-session refusal named no file exit; the second review round fixed that too.** After a
  delete `resolveAndRegisterProject` returns null, so no NEW session in that directory is stamped — every
  session reaching that branch was stamped BECAUSE a `.kotgent.json` there named the project, and the
  tombstone never touches the file. Offering only `project restore` and `--project` therefore left the
  "created in the wrong folder" case with neither exit that fixes it, while a session started after the
  delete, in the same directory, was told about the file. `refuseDeletedProject` now takes a
  `DeletedProjectArrival` and names the file in both texts, worded per arrival: the stamp short-circuits
  the filesystem, so moving the file frees the DIRECTORY while that session keeps the project it carries
  and it takes a session started afterwards. `createFromASessionStampedWithADeletedProjectIsRefusedToo`
  asserted the omission and now asserts the exit.
- ✅ **Both remaining tombstone checks were read-then-write at the CALL SITE; an external review round
  found them and they are now closed inside the store.** `POST /tasks` read the project row in
  `resolveProjectForCreate` and filed the card in a separate `TaskStore.create` call, and `POST
  /tasks/next` read it in the route and selected in a separate `nextCandidate` call — in both, the route
  holds no lock between the two, so a `DELETE /projects/{id}` landing in that gap filed a card into, or
  handed work out of, a project the board had already stopped listing. The fix is the one this feature
  had already chosen once: `upsertProject` reads `selectProjectArchived` inside its OWN transaction, and
  `ProjectRegistration`'s KDoc says in as many words that a read-then-write at each call site "would
  leave a window for a concurrent restore and spread one rule across five files". So `create` now reads
  the tombstone as the first statement of the insert's transaction and throws `ArchivedProjectException`,
  and `Backlog.sq`'s `nextCandidate` excludes an archived project in the same statement that picks the
  card. **This is deliberately NOT the `sessions.task_ref` call**, which recorded its race instead of
  closing it: that one needed a statement spanning two stores with independent mutexes, which is the
  split's whole point, whereas both of these are one store, one mutex, one database — `TaskService`'s
  no-nested-locks invariant is untouched, because `TaskService` is not on either path.
  - The route checks STAY, and the two now answer different questions. Resolution's check decides HOW to
    refuse — it is the only place that still knows whether the uuid was typed, carried on the session's
    stamp, or read out of a `.kotgent.json`, and that picks the status and the three exits. The store's
    check decides WHETHER, and it is the authority. So `resolveProjectForCreate` returns a `CreateTarget`
    carrying the arrival, and the route re-uses `refuseDeletedProject` when the store refuses — a card
    that raced the delete gets the same sentence it would have got a moment earlier, not a generic one.
    `DeletedProjectArrival` gained `explicitUuid` for the arrival that has no file to move and therefore
    leaves through the `404` door.
  - `/tasks/next` needed no route change at all, and its degraded answer is recorded in its KDoc: a
    delete that overtakes the route's check leaves `linkNext` nothing to select, so the answer is the
    ordinary `{"task":null}` — `task next`'s documented exit `3` — rather than the `404` an instant
    earlier would have given. Nothing is handed out either way, which is the contract. Re-reading the row
    after the selection would be the same race with more steps.
  - Tests interleave rather than sequence, because calling the two in sequence proves nothing — in
    sequence the route's own check already answers. `TaskWriteRoutesTest` and `TaskLinkRoutesTest` each
    gained a store-seam hook that archives the project between the route's read and the store's write
    (`aDeleteLandingBetweenResolutionAndTheInsertFilesNothing`,
    `aDeleteRacingACreateThatNamedTheProjectOutrightIs404LikeAnUnknownOne`,
    `aDeleteLandingBetweenNextsCheckAndItsSelectionStillHandsOutNothing`), and `TaskStoreTest` pins the
    guarantees against the real engine, including that the refused create rolls back whole and does not
    consume its local key. Both private route doubles and the shared `fakes` `FakeTaskStore` carry the
    check under their own lock, so a fake cannot prove a race production has closed.
  - **The residual this first recorded is now CLOSED, and the reasoning that recorded it was wrong.** It
    read: the store guards the SELECTION, not the writes that follow, so a delete landing between
    `nextCandidate` and `startIfTodo` still starts and links that one card — "the same end state
    `POST /tasks/{ref}/link` accepts on purpose". That reasons from the wrong half of the rule. The line
    is not about which writes happen (`link` and `linkNext` make the identical three, settled earlier);
    it is about WHO CHOSE THE CARD. `link` acts on a card its caller NAMED, which is deference; `next`
    has the daemon CHOOSE one out of the project, which is the selection a deleted project stops
    offering. A delete landing mid-`linkNext` therefore produced exactly the outcome the refusal exists
    to prevent. Closed with the mechanism used twice already on this branch: the tombstone rides in the
    statement that WRITES. `Backlog.sq` now carries two starts — `startIfTodo` (deference, no clause) and
    `startIfTodoInLiveProject` (selection) — behind one `TaskStore.startIfTodo(ref, requireLiveProject)`,
    which `linkNext` passes true and `link` false.
    - One method with a parameter, not two, following `listProjects(archived)`: the two differ by one
      WHERE clause, and a second interface method would mean eight more bodies across the fake and the
      seven private test doubles for no added clarity. The named argument states the choice at both
      production call sites, and the interface KDoc is where the WHO-CHOSE rule is written down.
    - No new control flow: a refused start matches zero rows, which `linkNext`'s loop already treats as
      "somebody else took it, re-query", and the re-query then finds the backlog withdrawn and answers
      null. So the degraded answer is unchanged — the ordinary `{"task":null}`, `task next`'s documented
      exit `3` — whichever of the two gaps the delete lands in.
    - `POST /tasks/{ref}/link` is deliberately UNCHANGED and pinned by
      `linkStaysOpenForADeletedProjectsCardBecauseItNamesOneThatAlreadyExists` plus the new store-level
      `aNamedRefStartsInADeletedProjectBecauseDeferenceIsNotSelection`: an agent already working when the
      operator deleted the project must still re-link and close what it holds.
    - Proven at both honest levels, each interleaving rather than sequencing:
      `TaskStoreTest.aSelectedCardIsStillRefusedByTheStartItselfWhenTheDeleteOvertookTheSelection`
      against the real engine (plus `aStartInAProjectWithNoRowAtAllIsNotRefusedEitherWay` — absence is
      still not a tombstone), `TaskServiceTest.aDeleteLandingBetweenTheSelectionAndItsStartHandsOut`
      `NothingAndNeedsNoNewControlFlow` on the loop's journal, and
      `TaskLinkRoutesTest.aDeleteLandingBetweenNextsSelectionAndItsStartStillHandsOutNothing` through the
      route. Sensitivity checked by dropping the new clause: exactly those three fail, nothing else.
  - **Not covered:** `aBacklogWhoseProjectHasNoRowAtAllIsStillOffered` pins that absence is not a
    tombstone, but nothing asserts the same for `create` — an insert into an unregistered project is
    exercised only incidentally, by every other test in `TaskStoreTest` that creates without upserting.

### Task 10: [Final] Documentation

- [x] check whether `docs/agent-task-skill.md` needs the refusal case — it is the one artefact no test in
      this repository can catch
  - It did, in three places, and the test was "could an agent inside a pane hit this and have to parse it".
    (1) The command table gained `project delete <uuid>` / `project restore <uuid>` and `--archived` on
    `project list`; each prints one `ProjectDto`, which now carries `archived`. (2) A new paragraph beside
    the existing "behaviours the table cannot show" (now four, not two) states the refusal: a ref-less
    `task add` in a deleted project exits `1` with `{"error":"… was deleted — …","status":400}` naming
    both ways out, an explicit `--project` naming a deleted uuid is a `404` like an unknown one, and the
    refusal must NOT be retried — nothing in the environment changes on its own, and both ways out are the
    human's call, so the agent comments instead of restoring or re-homing the task unasked. (3) The
    `.kotgent.json` section says the file survives a delete, so its presence is not proof there is a
    project to file into, and `project list` is the authority.
  - Two judgement calls, both toward saying less. The `session.projectId` short-circuit IS documented, in
    one sentence, because it is agent-OBSERVABLE — two sessions in the same directory answer differently —
    and a skill author would otherwise file it as a bug; the design reason it is unguarded is not. The
    `POST /tasks/next` window recorded in Task 9 is NOT documented: nothing about `task next` changed for
    the agent, and a contract document that describes an internal race ages badly. (That window has since
    been closed — see Task 9 — which leaves this judgement correct for a second reason: there is now
    nothing inconsistent to describe, and the agent-visible answer is the same `{"task":null}` either way.)
    The refusal paragraph is therefore scoped to `task add` and makes no blanket claim that the whole
    family refuses.
  - Verified by reading only (Markdown-only change): the message text against
    `TaskWriteRoutes.resolveProjectForCreate`, the DTO against `TaskDtos.kt`, the exit codes and the
    `--archived` wire selection against `TaskCommands.kt` / `Cli.kt`'s `USAGE`, and
    "kotgent creates `.kotgent.json` and never removes it" against `ProjectFileWriter`, whose only
    `unlink` targets the mkstemp temp file. `CLAUDE.md` was deliberately not touched.
- [x] move this plan to `docs/plans/completed/` — NOT done here by design: the harness performs the move
      after the review and finalize phases, which read this file at its current path.

## Post-Completion

**Manual verification:**
- both dialogs on a phone and a tablet: the swipe handle, the compensated padding, backdrop dismissal, and
  that neither can be dismissed mid-request.
- an installed PWA reaching the board and deleting a project from the palette button rather than `⌘K`.

**External systems:**
- the out-of-repo Agent Skill (Heapy/Kortex) written against `docs/agent-task-skill.md` — if the refusal
  message becomes part of the contract, that skill changes with it.
