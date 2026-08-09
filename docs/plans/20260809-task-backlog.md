# Local task backlog: ordered, dependency-aware, agent-driven

## Overview

kotgent tracks *sessions*, not *work*. This plan adds the work layer: an ordered, dependency-aware backlog
per project, stored locally, that a human grooms on a kanban board and an agent inside a session reads from
and writes to. A session's purpose stops being something the operator remembers and becomes something the
daemon records.

Requirements source: [issue #4](https://github.com/Heapy/kotgent/issues/4) — its body was rewritten on
2026-08-09. Read it first; this document is the execution plan for it. Where the two disagree, this plan is
newer: three review passes changed the claim protocol (see "No exclusivity" below), and the issue body's
"two conditional updates in one transaction" no longer describes it.

**What it solves.** Ten parallel sessions across three repositories currently carry no answer to "what is
this one for", "what is left", or "which of these is waiting for me". A backlog with a session link answers
all three, and gives an agent a way to pick up the next piece of work without a human typing it.

**How it integrates.** A new `src/task/` domain plus a new `SqliteTaskStore` beside `SqlitePushStore`; two
additive columns on `sessions`; `taskRoutes` inside the `route(API_PREFIX)` block Task 1 introduces; new
frame kinds in the existing `sealed EventsFrame`; a new `/tasks` screen behind the Web UI's first router.
The reducer, the `AgentEvent` vocabulary and the tmux/pty layer are untouched. `src/core/` changes in
exactly two ways: two nullable fields on `SessionMeta`, and the `TaskRef` / `ProjectId` value classes, which
live in `src/core/Ids.kt` beside `SessionId` so the dependency runs `task → core` and never back.

## Context (from discovery)

**Files/components involved**

| Area | Files |
|---|---|
| New domain | `src/task/` (whole directory) |
| New storage | `src/store/SqliteTaskStore.kt`, `sqldelight/io/kotgent/db/{Tasks,Backlog,Projects}.sq` |
| Storage edits | `src/store/{EventStore,SqliteEventStore}.kt`, `sqldelight/io/kotgent/db/Sessions.sq`, `src/core/{SessionMeta,Ids}.kt` |
| Transport | `src/transport/{TaskRoutes,EventsWs,Server,WebUiAssets,ControlRoutes}.kt` |
| Daemon | `src/daemon/{TaskService,SessionManager,Reconciler}.kt` |
| CLI | `src/cli/{Cli,Commands,ApiClient,AttachClient,TmuxSelf}.kt` |
| Web UI | `resources/webui/{app.js,index.html,style.css,sw.js}`, `lib/{router,tasks,sessions,commands,api}.js`, `components/{Board,TaskCard,TaskDetail,Sidebar,TerminalPane,dialogs}.js` |
| Schema artifact | `schema/project.v1.json` |
| Docs | `CLAUDE.md`, `README.md` |

**Related patterns found (copy these, do not invent new ones)**

- Whole-table creation for pre-existing DBs: `src/push/SqlitePushStore.kt:24-33` — `.sq` for a fresh DB plus
  `CREATE TABLE IF NOT EXISTS` in `init`, no `PRAGMA` guard needed. The `.sq` header comment at
  `sqldelight/io/kotgent/db/PushSubscriptions.sq:8-12` records why (`.sqm` files are dropped by the codegen
  plugin, `Schema.migrate()` is empty).
- Additive column for pre-existing DBs: `src/store/SqliteEventStore.kt:151-156` — `driver.hasColumn(...)`
  guard (helper at `:536`) then `ALTER TABLE`. Never `runCatching { ALTER }` — sqliter logs the failure
  before throwing.
- Conditional write as a selection guard: `sqldelight/io/kotgent/db/Sessions.sq:126`
  (`setModelForProvider`) — the check lives inside the SQL `WHERE`, so the read and the write are one
  statement.
- `MAX`-merge in an upsert so a stale snapshot cannot regress a field: the expression is
  `sqldelight/io/kotgent/db/Sessions.sq:72` (`read_cursor = MAX(...)`); `:41-46` is the comment explaining
  why.
- Monotonic `rev` counter: `src/store/SqliteEventStore.kt:83` (field), `:160` (seeded from `maxRev()`),
  `++revCounter` stamped by every mutator. It is an in-memory field of that one class.
- Update flow: `src/store/SqliteEventStore.kt:106-111` — `_sessionUpdates`, a 1024-entry `DROP_OLDEST`
  `MutableSharedFlow`; every publish is `_sessionUpdates.tryEmit(update)` at `:425`, under the store's
  mutex immediately after the matching mutation. **Do not copy `_reliableSessionUpdates` (`:124`)** — that
  one is deliberately unbuffered and backpressuring, for the push notifier.
- Snapshot/subscribe race closure: `src/transport/EventsWs.kt:136-143` — the baseline snapshot is taken
  inside **that same flow's** `.onSubscription { }`. A second flow needs its own `.onSubscription`.
- Atomic file publish: `src/transport/FileUploadRoutes.kt:185-273` — `mkstemp` → write → `fsync` → `link(2)`,
  unlink the temp on every failure path, an existing target loses the `link` race rather than being
  overwritten. Verified to link into the test binary (`test/transport/FileUploadTest.kt:49`).
- Pane → session resolution: `src/transport/HookRoutes.kt:272-284` (`PaneId` parse + `resolvePane`). The
  registry is seeded from the **store** at `src/daemon/SessionManager.kt:414-419`
  (`rebuildRegistryFromStore`, alive-state rows only) and then narrowed to the authoritative **live-pane**
  set by `src/daemon/Reconciler.kt:155` (`registry.replaceAll(livePanes)`). A stale pane therefore fails
  closed because of the Reconciler, not because of the seed.
- Global WS frames: `src/transport/EventsWs.kt:189-224` (sealed hierarchy + DTOs), `:238` (`sendEventsFrame`,
  the one send path), conflating per-socket sender at `:105-132`.
- Route mounting: `src/transport/Server.kt:177-191` (the `authenticated { … }` block; `staticWebUi` follows
  at `:193`); optional-subsystem wiring precedent (`pushStore`/`vapidPublicKey` nullable, routes mounted
  only when both exist) at `:189-191`. `AuthRouteSelector` evaluates to `Transparent`
  (`src/transport/Auth.kt:200-206`), so nesting a `route(...)` inside `authenticated { }` is sound.
- Static serving and the caching rule: `src/transport/Server.kt:353-390`. `staticWebUi` already substitutes
  `index.html` for a blank path at `:356`; `stripRevPrefix(rel)` runs first at `:362`, so any `_v/` check
  must test `rel` or `rev`, never `path`; the revision substitution lives in the `path == "index.html"`
  branch at `:374`.
- Browser API surface: `resources/webui/lib/api.js` — `wsUrl` (`:34`) and `apiRequest` (`:50`) are the two
  chokepoints every caller goes through; `AUTH_PATH` is `:16`. The client applies the newest-rev-wins rule
  at `resources/webui/lib/sessions.js:81` (`if (!(msg.rev > prev.rev)) return list;`).
- Service worker constants: `resources/webui/sw.js:30` (`SESSIONS_URL`), `:32` / `:33` (push
  subscribe/unsubscribe, live `fetch` targets in `postPushState`, `:87-107`), notification-click deep link
  `/?session=<id>` at `:268`.
- Document-relative links in the shell: `resources/webui/index.html:16-18` — `manifest.webmanifest`,
  `icons/logo.svg`, `icons/apple-touch-icon.png`. Scripts and styles already carry the absolute
  `/_v/__REV__/` prefix; these three do not.
- Daemon startup order: `src/cli/Commands.kt:488-491` — `manager.rebuildRegistryFromStore()` then
  `Reconciler(tmux, store, vendorProbe, registry).reconcile()`, both **before** `startDaemonServer(...)`.
- Served-module registry for JS: `test/transport/WebUiServingTest.kt:271-287`; command-registry assertions
  at `:381`; two API-vs-static canaries — `theStaticCatchAllDoesNotShadowTheTokenGatedApi` at `:3694`
  (asserts `GET /sessions` is `200`) and `versionApiIsAuthenticatedAndOutranksTheStaticCatchAll` at `:3704`;
  a literal `"/sessions"` is asserted inside the served `sw.js` at `:2221`.
- Frame discriminator test to extend: `test/transport/TransportTest.kt:310`.
- CLI stub-server harness: `test/cli/CliTest.kt:855` (`withStub`); `renderSessions` already covered at `:434`.
  `test/cli/` contains only `CliTest.kt` and `ConfigTest.kt` — there is no `ApiClientTest.kt` or
  `CommandsTest.kt`, and no `EventsWsTest.kt` (the socket is covered by `TransportTest.kt`).

**Dependencies identified**

- No new third-party dependencies. No new cinterop (KT-78062 — anything raw would have to live in
  `sysnative/`, and nothing here needs it: `mkstemp`/`fsync`/`link` are stock `platform.posix`).
- No `git` subprocess. Project resolution reads `.git` from the filesystem.
- `isCanonicalUuid` (`src/core/Ids.kt:96`) validates the project id at the boundary.
- `DirectoryCompletion.kt` already backs path pickers; the board's "new project" reuses it.
- `TMUX_SOCKET` (`src/cli/Cli.kt:20`) is the bare `-L` label `"kotgent"`; the socket **path** is
  `${TMUX_TMPDIR:-/tmp}/tmux-<uid>/kotgent`.

## Development Approach

- **testing approach**: Regular (code first, then tests) — matching how the repository's existing plans were
  executed.
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
- run `./kotlin build` **before** `./kotlin test` (`PtyTest` execs the `ptycheck` binary, and `test` never
  links a main binary)
- backward compatibility: a pre-existing `~/.kotgent/kotgent.db` must open, and an old still-open browser
  tab must keep working degraded — **with one deliberate exception, Task 1**, which breaks an already-open
  tab, an installed service worker, and an older CLI binary. See that task.

**Repository conventions that constrain every task** (from `CLAUDE.md`):

- Build is the JetBrains Kotlin Toolchain via `./kotlin`, **not** Gradle. Manifests are `module.yaml` /
  `project.yaml`. Sources in `src/`, tests in `test/` — no `src/nativeMain/kotlin/…`.
- `internal` is **not** visible to tests (no friend-module relationship). Anything a test touches is `public`.
- `kotlin.system.getTimeMillis()` is an error-level deprecation. Use `kotlin.time.Clock`, and inject
  `now: () -> Long` so tests stay deterministic.
- Bound every Flow/WS/PTY test with `withTimeout(...)`.
- SQLDelight's native driver confines a transaction to one thread and is not suspend-safe. Every
  `db.transaction { }` runs inside `mutex.withLock` with **no suspension inside it**
  (`SqliteEventStore.kt:303` is the shape). Do not `await` anything within a transaction block.
- **In automation, never run the daemon or a live agent** (`kotgent daemon`, `./kotlin run`, real
  `claude`/`codex`/`junie`, `launchctl`). Only `./kotlin build` / `./kotlin test`.
- Host-free core vs. thin edges: pure rules go behind interfaces with fakes; I/O stays at the boundary.

## Testing Strategy

- **unit tests**: required for every task (see Development Approach above)
- **e2e tests**: the project has no browser test harness by design. The equivalent obligations are:
  - every changed `.js` file passes `node --check <file>`
  - every newly served ES module is registered in `test/transport/WebUiServingTest.kt`
  - source/serving contracts that matter (a command exists in the registry, a handler is wired, a CSS rule
    is present) are asserted by serving the file and matching its text — the existing pattern in that test
  - browser behaviour stays in the manual checklist under Post-Completion
- **harness caveat that already bit this plan once**: `withServer` in `WebUiServingTest.kt` builds a
  `KotgentServer` without the task subsystem. Any test whose point is the *interaction* between API routes
  and static serving must mount the task routes explicitly, or it passes vacuously.
- **baseline**: 921 native tests passed / 0 skipped (`CLAUDE.md:1065`), plus the build-info plugin's 7 JVM
  tests and `ptycheck`'s 11 real-PTY checks. Keep skips at zero.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

**Two layers, not one.** `TaskTracker` covers only what a tracker can know — title, body, url, external
state. The workflow (`todo → in_progress → review → done`), ordering, dependencies, the session link and the
activity feed are kotgent's own concepts and live in a local layer keyed by `TaskRef`. This is the
load-bearing decision: GitHub has no "review" state and no "position 3 in my backlog", so a fat
`TaskTracker` with capability flags would force the UI to degrade to a backlog with neither ordering nor a
session link — which is the entire product. With the split, a future GitHub adapter implements a small
interface and its issues drop into the same ordered backlog for free.

**No exclusivity — this is the decision three review rounds converged on, and it is what keeps the design
small.** kotgent cannot enforce "only one worker per task": the operator opens a second terminal in the
same repository and the daemon never hears about it. An invariant that only holds against your own API is
not an invariant. So a task may be linked from **any number of sessions**, linking is an unconditional
write, and the board shows every linked session. What that deletes, compared to an exclusive claim: the
conditional `setTaskRefIfNull`, the busy-session `409`, the compensating write, the "daemon died between
two writes" residual, the reconciliation pass that recovered it (which could not tell its target from a
card a human had dragged into `in_progress`), and the `skip` set that guarded the retry loop. The one thing
that survives is a conditional `todo → in_progress` transition, and its **only** job is to stop
`kotgent task next` handing the same task to two agents in a row. That is a selection convention, not a
protected invariant, and the plan says so wherever it appears: an explicit `task claim <ref>` on a task
already in progress is allowed and simply adds a link.

**A session works one task at a time.** `sessions.task_ref` stays single-valued, so the cardinality is
"many sessions → one task". Pointing a session at a different task overwrites the link; there is no error
case.

**A project is a committed file, not a path.** `.kotgent.json` holds a uuid and a name. A path key breaks on
worktrees — `/repo` and `/repo-wt/feature` are one body of work but two strings, hence two backlogs. A
committed uuid survives a move, a rename, and a clone.

**Project resolution is pure filesystem.** Walk up from the cwd to the first `.kotgent.json`; on a miss
inside a repository, also look at the main checkout root, found by *reading* `.git`. No `git` subprocess:
the daemon's PATH is a snapshot and `git` may not be on it, and a pure rule is testable against a fake
filesystem. The supported layouts and the recorded unsupported ones are in Technical Details — "worktree-
invariant for free" is true for the ordinary `git worktree add` layout and **only** that one.

**Every `sessions` write stays inside `SqliteEventStore`.** `sessions.rev` is stamped from an in-memory
counter owned by that class (`:83`) and `sessionUpdates` is emitted there (`:425`). A second store writing
the table would fork the counter — producing duplicate and regressing revs, which the client's
`if (!(msg.rev > prev.rev)) return list;` (`lib/sessions.js:81`) then silently drops — and would emit no
update at all, so the badge would never move. This is why the task store never touches `sessions`, and why
`TaskService` calls the two stores sequentially rather than nesting them.

**A session stays linked through `review`.** One session, one task, end to end — the human reviews *that*
session's terminal and diff, and "Done" on the session closes the task and archives the session. Closing
from the board instead unlinks every session and leaves them alive, which is what hands a long-lived worker
session back to `task next`.

**Two URL spaces, separated once and for all.** Ktor scores a literal path segment above the
`get("/{path...}")` tailcard that serves the SPA (`Server.kt:354`) — that is why `/sessions` returns JSON
today and never the shell. A UI route named after any API route would therefore be unreachable. Task 1
moves the cookie/Bearer-gated non-bootstrap surface under `/api/v1` and leaves the SPA the bare paths. Two
surfaces deliberately stay: `/hooks/*`, because each adapter bakes its ingress URL into a per-session shell
script on disk (`ClaudeHookConfig.kt:43` and siblings), so moving it would silently stop every
already-running session from reporting until relaunched; and the whole `/auth*` bootstrap surface, whose
page is addressed by the QR code and the PWA's `location.replace(AUTH_PATH)`, whose `/auth/exchange` is
fetched by an inline script inside the page the daemon itself serves (`AuthRoutes.kt:577`), and whose
`/auth/ticket` **both** the browser (`components/dialogs.js:886`, through `apiRequest`) and the CLI
(`ApiClient.kt`, `AUTH_TICKET_PATH` / `AUTH_ROTATE_PATH`) call — so the exemption is needed on both sides,
not just in JavaScript.

**Live updates extend the existing socket.** New kinds in `sealed EventsFrame`, same rules: encoded only
through `EventsFrame.serializer()`, applied newest-`rev`-wins, conflated per socket. No second WebSocket.

## Technical Details

### Domain

`TaskRef` and `ProjectId` go in **`src/core/Ids.kt`**, beside `SessionId` / `ProviderSessionId` and the
`isCanonicalUuid` helper they use (`:96`). Putting them in `src/task/` would make `src/core/SessionMeta.kt`
depend on `src/task/`, inverting the layering.

```kotlin
// src/core/Ids.kt
@JvmInline value class TaskRef(val value: String)   // "local:42"
@JvmInline value class ProjectId(val value: String) // canonical uuid

// src/task/Task.kt
enum class TaskState { todo, in_progress, review, done }

data class Task(val ref: TaskRef, val title: String, val body: String, val url: String?, val updatedAt: Long)
data class BacklogEntry(
    val ref: TaskRef, val project: ProjectId, val position: Double, val state: TaskState,
    val blocked: Boolean, val createdAt: Long, val updatedAt: Long, val rev: Long,
)
data class TaskActivityEntry(
    val id: Long, val ref: TaskRef, val ts: Long, val kind: ActivityKind,
    val author: String, val text: String?, val fromState: TaskState?, val toState: TaskState?,
)
enum class ActivityKind { created, comment, transition, linked, unlinked }

/** What the task-updates flow carries. A null [entry] means the ref was deleted. */
data class TaskUpdate(val ref: TaskRef, val entry: BacklogEntry?, val rev: Long)

interface TaskTracker {
    val id: String                       // "local"
    suspend fun list(project: ProjectId): List<Task>
    suspend fun get(ref: TaskRef): Task?
    suspend fun create(project: ProjectId, title: String, body: String): Task
    suspend fun update(ref: TaskRef, title: String?, body: String?): Task?
    suspend fun delete(ref: TaskRef): Boolean
}
```

`TaskRef` is `<tracker>:<key>`: exactly one `:`, tracker and key each non-blank, total ≤128 chars, charset
`[A-Za-z0-9_-]` for both halves with an alphanumeric first character. **`.` is deliberately excluded** —
`ProviderSessionId`'s rule only rejects a value that *equals* `..`, not `..` as a substring, and a ref ends
up in URLs and in argv. The mandatory `:` is also what keeps `POST /api/v1/tasks/claim` from being shadowed
by `POST /api/v1/tasks/{ref}/…`: `claim` can never parse as a ref. That is load-bearing — pin it with a test.

No capability flag on `TaskTracker`: the built-in tracker is the only implementation, and a flag with no
second implementation and no reader is speculative.

### Project file

`.kotgent.json` in the project root, committed:

```json
{
  "$schema": "https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json",
  "id": "0f2c7a4e-1c3d-4f7a-9b21-6f0a2d9c1e34",
  "name": "kotgent"
}
```

Untrusted input (it arrives with somebody's repository): the read is capped at 8 KiB (the repo bounds every
untrusted intake — 1024 bytes for `/auth/exchange`, 100 MiB per upload), `id` must pass `isCanonicalUuid`,
`name` is trimmed, capped at 100 characters and rejected if it contains control characters, and malformed
JSON logs a warning and reads as "no project" — never an exception out of the resolver.

Resolution order:

1. Walk up from the canonical cwd to the filesystem root; first `.kotgent.json` wins (nearest wins in a
   monorepo).
2. Miss + inside a repository → check the main checkout root:
   - `.git` is a **directory** → this directory is the root.
   - `.git` is a **file** whose `gitdir:` target contains a `/worktrees/<name>` segment → strip that
     segment to get the common dir; the root is the common dir's parent. A **relative** `gitdir:` target is
     resolved against the directory holding the `.git` file, then canonicalized through `realpath`, before
     any segment is examined.
   - anything else → treat the current directory as the root.
3. Still nothing → no project.

**Recorded unsupported layouts**, each degrading to "the current directory is the root" rather than
misbehaving: `git init --separate-git-dir` (the common dir's parent is the metadata directory, not the
checkout), submodules (`gitdir:` points into `…/.git/modules/<name>`, which has no `worktrees` segment),
bare repositories, and `$GIT_DIR` / `$GIT_WORK_TREE`. Two checkouts of different branches disagree whenever
`.kotgent.json` is committed on one and not the other — the uuid is invariant across worktrees of a
repository, not across the history of a file. Each of these is a test case, not a surprise.

Creation (first `task add` in a location with no project): write to the main checkout root computed by the
same rule, via `mkstemp` sibling → write → `fsync` → `chmod` to `0666 & ~umask` → `link(2)`. Not `0600`:
the file is meant to be committed. An existing file always wins: a lost `link` race means re-read theirs and
return that descriptor. The daemon writes the file and never commits it.

**Every path that reads or creates a project file upserts the `projects` row** (id, name, path). Without
that, a project created by `kotgent task add` in a fresh repository has backlog rows but never appears in
`GET /api/v1/projects`, so the board's selector can never reach its backlog.

`projects.path` is explicitly **"the checkout the daemon saw most recently"**, not "the project's location":
worktrees deliberately share one uuid and overwrite one row. It is a convenience default only —
`start --task` prefers the caller's cwd when that resolves to the same project, falls back to
`projects.path`, and falls back again to the caller's cwd when the stored path no longer exists, saying
which it used in its JSON output.

### Storage

New `src/store/SqliteTaskStore.kt` — its own `Mutex`, its own `rev` counter seeded from `MAX(rev)`, its own
`taskUpdates` flow (`MutableSharedFlow<TaskUpdate>`, 1024-entry `DROP_OLDEST`, the `_sessionUpdates` shape
at `SqliteEventStore.kt:106-111`), `.sq` files for a fresh DB plus `CREATE TABLE IF NOT EXISTS` in `init`
for pre-existing ones. It never writes the `sessions` table.

| table | columns |
|---|---|
| `tasks` | `id TEXT PK`, `title TEXT NOT NULL`, `body TEXT NOT NULL DEFAULT ''`, `created_at`, `updated_at` |
| `backlog_entries` | `task_ref TEXT PK`, `project TEXT NOT NULL`, `position REAL NOT NULL`, `state TEXT NOT NULL`, `created_at`, `updated_at`, `rev INTEGER NOT NULL DEFAULT 0` |
| `backlog_deps` | `task_ref TEXT NOT NULL`, `depends_on TEXT NOT NULL`, PK(`task_ref`, `depends_on`) |
| `task_activity` | `id INTEGER PK AUTOINCREMENT`, `task_ref TEXT NOT NULL`, `ts INTEGER NOT NULL`, `kind TEXT NOT NULL`, `author TEXT NOT NULL`, `text TEXT`, `from_state TEXT`, `to_state TEXT` |
| `projects` | `id TEXT PK`, `name TEXT NOT NULL`, `path TEXT`, `updated_at INTEGER NOT NULL` |

Two stores now open `db.transaction { }` over the same `SqlDriver` under *different* mutexes
(`SqlitePushStore` shares the driver but issues only single statements). The native driver borrows a single
writer entry, so concurrent transactions serialize by blocking rather than corrupt — but a blocked
transaction holds a `Dispatchers.Default` thread while the other store's mutex is held. Keep every
transaction short and suspension-free, which the convention above already requires.

Two additive columns on `sessions`, each behind a `driver.hasColumn` guard in `SqliteEventStore.init`,
declared **last** in `Sessions.sq`'s `CREATE TABLE` so a fresh DB matches the migrated shape:
`task_ref TEXT` and `project_id TEXT`. In `upsert`'s `ON CONFLICT` both use
`COALESCE(excluded.x, sessions.x)` — the `MAX(read_cursor, …)` precedent at `Sessions.sq:72`: a caller
writing a `SessionMeta` snapshot read before a link must not silently clear it. Clearing is done only by the
targeted setter. Both setters touch only their column plus `updated_at` and `rev`, the
`setArchived`/`setModel` shape, and both emit a `SessionUpdate` — which therefore carries `taskRef`, or the
sidebar badge never moves.

`sessions.task_ref` is a **reference, not a foreign key**. A task deleted while a link write is in flight
leaves a dangling ref; the UI renders the bare ref rather than a title, and reconciliation clears it at the
next daemon start. Making that atomic would mean one statement spanning both tables — the thing this design
exists to avoid — for a window measured in microseconds and a consequence measured in one stale badge.

### Linking and selection

Linking a session to a task is **two independent writes, neither conditional on the other**:

```sql
-- task store: advance the state only if it has not started. Zero rows is NORMAL, not an error —
-- it means the task was already in_progress/review/done, and the link is still made.
UPDATE backlog_entries SET state='in_progress', updated_at=?, rev=? WHERE task_ref=? AND state='todo';
-- event store: unconditional. Overwrites whatever the session pointed at before.
UPDATE sessions SET task_ref=?, updated_at=?, rev=? WHERE id=?;
```

No compensation, no ordering requirement, no residual to reconcile. A crash between them leaves either a
task in `in_progress` with no session — indistinguishable from, and as legitimate as, a card a human dragged
into that column — or a session linked to a task still marked `todo`, which the next link or a board drag
fixes. Neither is an inconsistency worth code.

**`task next`** picks the first `todo` entry in the project with no unfinished dependency, ordered by
`position`, and links it. Two agents racing land on the same candidate; the conditional transition means one
of them changes the row and the other sees zero rows, re-queries (the row is no longer `todo`, so it is
naturally excluded) and takes the next candidate. The loop ends when the query returns nothing, which is the
**only** thing that reports "nothing eligible" (CLI exit `3`). No `skip` set is needed — nothing puts a
candidate back to `todo` mid-loop, because nothing compensates any more.

**`release`** unlinks the calling session and leaves the task's state alone. Whether the work is finished is
not something kotgent can infer from a session detaching, and several sessions may still be linked.

**Delete** unlinks every session holding the ref before removing the task, so the ordinary case leaves no
dangling badge; the racing case is covered by the reference rule above.

### Ordering

`position REAL`. Insert at the end = `max + 1.0`; between neighbours = their midpoint; at the top =
`positionBetween(0.0, min)`. When a gap falls below `1e-9`, renormalize the project's whole column to
`1.0, 2.0, 3.0, …` in one transaction and retry the move once. Every renormalized row stamps a new `rev`
and emits on `taskUpdates`, or a connected board silently holds stale positions. Neighbour resolution and
the single `UPDATE` both run inside the store mutex.

### Derived `blocked`, and why it must be emitted

`BacklogEntry.blocked` is derived (`state == todo` and some dependency is not `done`), computed in the read
path so the UI does not recompute per card. That makes it **stale by construction**: closing or deleting task
A changes the blocked-ness of everything that depends on A, without touching those rows. So every dependency
edit and every state transition re-reads the reverse dependents of the affected ref, stamps each a new `rev`
and emits each on `taskUpdates`. Without that the board shows a blocked marker on a task that is ready, until
a reload.

Dependencies are validated on insert: both refs must exist, must belong to the **same project**, must not be
equal, and must not close a cycle (a pure ancestor walk). A dangling or cross-project edge would otherwise be
accepted and then read as "already satisfied" by a joining candidate query — silently unblocking a task.

### Transport

All under the `/api/v1` prefix Task 1 introduces:

```
GET    /api/v1/whoami              { sessionId, projectId, taskRef } for the calling pane
GET    /api/v1/tasks?project=<u>   list backlog entries joined with tracker fields
POST   /api/v1/tasks               create — { project?, title, body }
GET    /api/v1/tasks/{ref}         entry + tracker fields + project path + deps + sessions + activity
PATCH  /api/v1/tasks/{ref}         title / body / state — state may carry an optional message
DELETE /api/v1/tasks/{ref}         unlink every session, then remove the task, its deps and its feed
POST   /api/v1/tasks/{ref}/move    { before | after | top | bottom }
POST   /api/v1/tasks/{ref}/deps    { add | remove, on }
POST   /api/v1/tasks/{ref}/comment { text }
POST   /api/v1/tasks/{ref}/link    link the calling session to this task
POST   /api/v1/tasks/{ref}/unlink  drop this session's link; the task's state is untouched
POST   /api/v1/tasks/next          { project? } → link the next eligible task to the calling session
GET    /api/v1/projects            known projects (board selector)
POST   /api/v1/projects            create/init a project at a path
POST   /api/v1/sessions            (existing route) gains an optional `taskRef`
```

`PATCH` carrying an optional message is what makes `kotgent task review -m "…"` one operation: the
transition and its activity row commit in one task-store transaction, so a failure cannot leave a review with
no explanation or a comment on an unreviewed task.

**Session identity.** It comes from the `X-Kotgent-Tmux-Pane` header — the same header hooks send — resolved
through the existing pane registry, or from an explicit `sessionId` in the request body. `link`, `unlink`,
`comment` and `next` **require** it: all four write `sessions.task_ref` or attribute an activity row, and
none of them means anything without a session. `POST /tasks` does **not**: it takes an optional `project`,
because the board has neither a pane nor a session and creating tasks is its headline job.

`GET /whoami` exists for one purpose: an agent knows only its pane, so a ref-less `task show` / `comment` /
`review` / `unlink` resolves its subject through it. **When `--session <id>` is given the CLI skips
`/whoami` entirely** — it already knows the id and sends it in the body; `/whoami` is pane resolution, not a
session lookup.

**Project resolution for `POST /tasks`, in order:** explicit `project` in the body → the calling session's
`project_id` → `resolveProject(session cwd)` → create the file at `mainCheckoutRoot(session cwd)` via
`ProjectFileWriter` → `400` naming `--project`, and **only** when there is no resolvable session at all
(the board path with nothing selected). Written out because the two halves of it — "400 when there is no
project" and "create the file when there is no project" — otherwise read as contradictory.

**`POST /projects` writes a file at a browser-supplied absolute path**, which departs from the rule that
`CLAUDE.md` states for uploads ("a session-cwd write, never an arbitrary-path API"). The departure is
deliberate and bounded: the path must be absolute and an existing directory, the only file written is
`.kotgent.json`, publication is `link(2)` so an existing file always wins, and the mode is `0666 & ~umask`.
It is no wider than the New-session dialog, which already directs the daemon to `cd` anywhere. Recorded here
so the upload rule is not read as having quietly eroded.

**`POST /sessions` gains an optional `taskRef`**, which is what `start --task` and the board's "Start
session" need: the session row and the link are written by the same request, and a failure to create the
session leaves no link behind. The link itself cannot fail (it is unconditional), so there is no partial
state to roll back — that is a direct dividend of dropping exclusivity.

New `EventsFrame` subclasses in `src/transport/EventsWs.kt`: `tasks_snapshot`, `task_row`, `task_update`,
`task_removed`. Same conflating sender, same "only a delivered row marks the ref as carried" rule, same
`EventsFrame.serializer()`-only send path.

**The tasks baseline must not suspend between subscribe and collect.** The sessions baseline sends its
snapshot inside `.onSubscription { }` (`EventsWs.kt:136-143`), which closes the subscribe/snapshot race but
leaves a second one: while that send is suspended the collector has not begun draining, and the source flow
drops the oldest past 1024 buffered updates — which a single renormalization of a large project can produce
by itself. So for tasks, `.onSubscription { }` **reads** the snapshot and hands it to the same sequential
per-socket sender as the first queued item; the collector starts draining immediately and never waits on a
socket write. The activity feed does not ride the socket at all.

### Routing

History API: `/`, `/tasks`, `/tasks/{ref}`, `/s/{id}`. These bare paths are free because Task 1 moved the
API. `isSpaRoute` matches the *original* `rel` (not the rev-stripped `path`) against an **exact segment
grammar**: `tasks`, `tasks/<one segment>`, `s/<one segment>`. Not a prefix match — `s/id/extra` and
`tasks/id/missing.js` must stay `404`, or the promise that a mistyped asset path 404s is false. When it
matches and the file is absent, serve `index.html` **through the existing `path == "index.html"` branch**
(`Server.kt:374`) so `__REV__` is substituted; skipping that branch would ship a shell whose every asset URL
is `/_v/__REV__/…`.

Deep routes break the shell's three **document-relative** links (`index.html:16-18`): at `/tasks/local:42`,
`manifest.webmanifest` resolves to `/tasks/manifest.webmanifest`. They must become root-absolute
(`/manifest.webmanifest`, `/icons/logo.svg`, `/icons/apple-touch-icon.png`) — which preserves CLAUDE.md's
"the manifest and icons stay on stable URLs" rule and keeps the iOS install path, and therefore push, working
from a deep link.

## What Goes Where

- **Implementation Steps** (`[ ]` checkboxes): everything achievable in this repository — Kotlin, SQL, JS,
  tests, docs.
- **Post-Completion** (no checkboxes): the Agent Skill in Heapy/Kortex, and browser/phone verification that
  needs a human at a terminal.

## Implementation Steps

### Stage 0 — the API namespace

*First, alone, and before any new code exists: this is the only breaking change in the plan, it touches
every client, and Stage 1 does not go near transport. Landing it at the head of the branch isolates the
break in one commit instead of spreading it across ten tasks of new code.*

### Task 1: Move the client-facing API under `/api/v1`

**Files:**
- Modify: `src/transport/Server.kt`
- Modify: `src/cli/{ApiClient,AttachClient}.kt`
- Modify: `resources/webui/lib/api.js`, `resources/webui/sw.js`
- Modify: `test/transport/{TransportTest,WebUiServingTest,ShutdownSignalsTest}.kt`
- Modify: `test/cli/CliTest.kt`
- Modify: `CLAUDE.md`, `README.md`

- [ ] add a single `API_PREFIX = "/api/v1"` constant and wrap the **body of the existing
      `authenticated { … }` block** (`Server.kt:177-191`) in `route(API_PREFIX) { … }` — that block already
      contains exactly the cookie/Bearer-gated surface (`controlRoutes`, `fileUploadRoutes`,
      `directoryCompletionRoutes`, `preferencesRoutes`, `eventsWs`, `terminalWs`, `pushRoutes`), so this is
      one structural change rather than per-route edits; `AuthRouteSelector` evaluates to `Transparent`
      (`Auth.kt:200-206`), so the nesting is sound
- [ ] leave `/hooks/*` and the whole `/auth*` surface outside the prefix, with a KDoc recording why: each
      adapter bakes `ingressUrl(port)` into a per-session shell script on disk, and `/auth` is addressed by
      the QR code, the PWA's `location.replace(AUTH_PATH)` and an inline `fetch("/auth/exchange")` inside
      the page the daemon serves (`AuthRoutes.kt:577`)
- [ ] apply the prefix **inside `apiRequest` and `wsUrl`** (`lib/api.js:50`, `:34`) with an exemption for
      paths starting with `/auth` — `components/dialogs.js:886` mints the phone ticket through
      `apiRequest("/auth/ticket")`. Centralizing here also covers `app.js`, `components/TerminalPane.js:450`,
      `components/dialogs.js:317,640` and `lib/push.js:255,269,324` for free
- [ ] apply the **same exemption on the Kotlin side**: `ApiClient` mixes moved paths (`"$baseUrl/sessions"`)
      with unmoved ones (`AUTH_TICKET_PATH`, `AUTH_ROTATE_PATH`); a blanket prefix helper breaks
      `kotgent web` and `kotgent token rotate`
- [ ] update **all three** `sw.js` URL constants — `SESSIONS_URL` (`:30`), `PUSH_SUBSCRIBE_URL` (`:32`) and
      `PUSH_UNSUBSCRIBE_URL` (`:33`)
- [ ] update `AttachClient`, including the WebSocket URLs
- [ ] retarget **both** API-vs-static canaries rather than deleting either: `theStaticCatchAllDoesNotShadowTheTokenGatedApi`
      (`WebUiServingTest.kt:3694`, asserts `GET /sessions` is `200`) and
      `versionApiIsAuthenticatedAndOutranksTheStaticCatchAll` (`:3704`) — each should now assert the prefixed
      path works **and** that the bare path falls through, because that literal-beats-tailcard property is
      exactly what Task 18's SPA fallback depends on. Also fix the served-`sw.js` literal assertion at `:2221`
- [ ] write tests: `GET /api/v1/sessions` works; `/hooks/claude` and `/auth` are untouched; the WS endpoints
      answer on their prefixed paths; the phone ticket still mints through the unprefixed path
- [ ] record **three** compatibility breaks in the KDoc and in CLAUDE.md: an older `kotgent` binary cannot
      talk to a newer daemon (acceptable — one binary, shipped together); an already-open browser tab breaks
      hard rather than degrading (its `/events` upgrade falls to the static catch-all and 404s instead of
      401ing, so the sign-out recovery never fires — a reload is the recovery); and **an installed service
      worker outlives its pages**, so with every tab closed it can wake on a push or `pushsubscriptionchange`
      still holding the old paths, until a navigation fetches the replacement. The last one is silent; say so
- [ ] update every documented path in `README.md` and `CLAUDE.md` (search both for `/sessions`, `/events`,
      `/push/`, `/preferences`, `/version`, `/directories/` and fix each hit — do not work from a fixed list)
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 2

### Stage 1 — domain and storage

### Task 2: Task domain types

**Files:**
- Modify: `src/core/Ids.kt`
- Create: `src/task/Task.kt`
- Create: `src/task/TaskTracker.kt`
- Create: `test/task/TaskDomainTest.kt`

- [ ] add `TaskRef` and `ProjectId` to `src/core/Ids.kt` beside `SessionId` (not to `src/task/`, or
      `SessionMeta` would make `core` depend on `task`), enforcing the charset in `init`: exactly one `:`,
      both halves non-blank, ≤128 total, `[A-Za-z0-9_-]`, alphanumeric first character, **no `.`**;
      `ProjectId` via `isCanonicalUuid`
- [ ] create `src/task/Task.kt` with `TaskState`, `Task`, `BacklogEntry` (including derived `blocked`),
      `TaskActivityEntry`, `ActivityKind`, `TaskUpdate` — all `public`, host-free, no I/O imports
- [ ] create `src/task/TaskTracker.kt` with the interface (list/get/create/update/delete)
- [ ] write tests for `TaskRef` acceptance (`local:42`, `local:a-b_c`) and rejection (blank, no colon, two
      colons, a dot, `..`, leading `-`, 129 chars, non-ASCII, empty half), plus one asserting that the
      literal `claim`, `next` and `whoami` are **not** valid refs — that is what keeps the route table
      unambiguous
- [ ] write tests for `ProjectId` acceptance/rejection against `isCanonicalUuid`
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 3

### Task 3: Project file resolution (pure rules)

**Files:**
- Create: `src/task/ProjectFile.kt`
- Create: `test/task/ProjectFileTest.kt`

- [ ] create `src/task/ProjectFile.kt` with a `ProjectFs` interface (`isDirectory`, `isFile`,
      `readTextBounded(path, maxBytes)`, `parentOf`, `canonicalize`) plus a real posix-backed implementation
      kept to a few lines
- [ ] implement `parseProjectFile(text): ProjectDescriptor?` — validates `id` with `isCanonicalUuid`, trims
      `name`, caps it at 100 chars, rejects control characters, returns null (never throws) on malformed JSON
- [ ] cap the file read at 8 KiB; an oversized file reads as "no project" with a logged warning
- [ ] implement `mainCheckoutRoot(dir, fs)` — `.git` directory → this dir; `.git` file → resolve a relative
      `gitdir:` against the `.git` file's directory, canonicalize, and only then look for a
      `/worktrees/<name>` segment to strip, taking the common dir's parent; anything else → this dir
- [ ] implement `resolveProject(cwd, fs)` — upward walk to the first `.kotgent.json`, then the main checkout
      root fallback, then null
- [ ] write tests over a fake `ProjectFs`: file in cwd; file in an ancestor; nearest-wins in a monorepo;
      ordinary linked worktree finding the main checkout's file; **relative `gitdir:`**; symlinked common
      dir; and the recorded unsupported layouts each degrading to cwd — `--separate-git-dir`, a submodule
      `gitdir: …/modules/<name>`, a bare repository, no git at all
- [ ] write tests for the parse rules: malformed JSON; non-uuid `id`; control characters in `name`;
      200-char `name`; a 1 MiB file
- [ ] write ONE test against the **real** posix `ProjectFs` in `TMPDIR` (a real `.git` directory and a real
      `.git` worktree file), so the thin I/O implementation is not the plan's only untested line
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 4

### Task 4: Project file creation

**Files:**
- Create: `src/task/ProjectFileWriter.kt`
- Create: `schema/project.v1.json`
- Create: `test/task/ProjectFileWriterTest.kt`

- [ ] create `schema/project.v1.json` — a JSON Schema for `{$schema, id, name}`, `id` as a uuid pattern,
      `name` with `maxLength: 100`, `additionalProperties: false`
- [ ] create `src/task/ProjectFileWriter.kt` — `mkstemp` sibling in the target directory, write the JSON,
      `fsync`, `chmod` the temp to `0666 & ~umask` (it is meant to be committed, not a secret), close,
      publish with `link(2)`, `unlink` the temp on every path including success; mirror
      `FileUploadRoutes.kt:185-273`
- [ ] make an existing target win: a `link` failing with `EEXIST` re-reads the file and returns the existing
      `ProjectDescriptor` rather than erroring
- [ ] reject a target that is not an existing directory, and a relative path, with a typed error the route
      maps to `400`
- [ ] write tests: creates the file with the expected shape and mode; a second call returns the first call's
      id; a pre-created target keeps its content; a non-existent and a non-directory target are refused; the
      temp is gone afterwards in every branch
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 5

### Task 5: Task store schema, CRUD and the update flow

**Files:**
- Create: `sqldelight/io/kotgent/db/Tasks.sq`
- Create: `sqldelight/io/kotgent/db/Backlog.sq`
- Create: `sqldelight/io/kotgent/db/Projects.sq`
- Create: `src/store/TaskStore.kt`
- Create: `src/store/SqliteTaskStore.kt`
- Create: `test/store/TaskStoreTest.kt`

- [ ] write the three `.sq` files with the tables from Technical Details, each carrying the header comment
      that explains the `.sqm`-is-dropped rule (copy the wording style of `PushSubscriptions.sq:8-12`)
- [ ] create `src/store/TaskStore.kt` — the interface the daemon and transport use (list/get/create/update/
      delete, activity append/read, project upsert/list, `taskUpdates: SharedFlow<TaskUpdate>`)
- [ ] create `src/store/SqliteTaskStore.kt` — own `Mutex`, own `revCounter` seeded from `MAX(rev)`, own
      `taskUpdates` created **with the store** (1024-entry `DROP_OLDEST`, the `_sessionUpdates` shape at
      `SqliteEventStore.kt:106-111` — **not** the unbuffered `_reliableSessionUpdates` at `:124`),
      `CREATE TABLE IF NOT EXISTS` for all five tables in `init`, `now: () -> Long` injected; it must never
      write the `sessions` table
- [ ] implement the built-in `TaskTracker` over `tasks`, with `delete` cascading to `backlog_entries`,
      `backlog_deps` (both directions) and `task_activity` in one transaction, emitting a `TaskUpdate` whose
      `entry` is null — the `sessions` unlink is **not** here, it belongs to `TaskService` (Task 9)
- [ ] write tests: create → get round-trip; update touches `updated_at`, bumps `rev` and emits; delete
      removes the entry, both dependency directions and the feed, and emits a null-entry update; activity
      append is ordered and append-only; project upsert refreshes name and path; re-open resumes
      `revCounter` above the persisted max
- [ ] write a test that opens the store over a database missing the new tables and confirms `init` recreates
      them without logging an error
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 6

### Task 6: Ordering — fractional index and move

**Files:**
- Create: `src/task/Ordering.kt`
- Modify: `sqldelight/io/kotgent/db/Backlog.sq`
- Modify: `src/store/SqliteTaskStore.kt`
- Create: `test/task/OrderingTest.kt`
- Modify: `test/store/TaskStoreTest.kt`

- [ ] create `src/task/Ordering.kt` with the pure rules: `positionForEnd(max)`, `positionBetween(a, b)`,
      `positionForTop(min)`, `needsRenormalization(a, b)` at `1e-9`
- [ ] add `move` to the store: resolve neighbours for `top`/`bottom`/`before`/`after` and write one row, all
      inside the store mutex; when the gap collapses, renormalize the project's whole column in one
      transaction and retry the move once
- [ ] stamp a fresh `rev` on **every** renormalized row and emit each on `taskUpdates`
- [ ] add the queries `Backlog.sq` needs (`maxPosition`, `minPosition`, `neighboursAround`, `renormalize`)
- [ ] write tests for the pure helpers: midpoint ordering holds; top insert halves toward zero; repeated
      midpoints trip the threshold
- [ ] write store tests: move to top/bottom/before/after produces the expected order; 60 consecutive midpoint
      inserts between the same pair still yield a strictly ordered list; a renormalization bumps every row's
      `rev` and emits for each
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 7

### Task 7: Dependencies, `blocked`, and the `next` candidate

**Files:**
- Create: `src/task/Dependencies.kt`
- Modify: `sqldelight/io/kotgent/db/Backlog.sq`
- Modify: `src/store/SqliteTaskStore.kt`
- Create: `test/task/DependenciesTest.kt`
- Modify: `test/store/TaskStoreTest.kt`

- [ ] implement `wouldCycle(edges, from, to)` as a pure ancestor walk
- [ ] add `addDependency` / `removeDependency`, validating that both refs exist, belong to the same project,
      are not equal, and do not close a cycle — each failure a typed exception the route maps to `400`
- [ ] compute `blocked` in the entry read path (`state == todo` and some dependency not `done`)
- [ ] after every dependency edit and every state transition, re-read the affected ref's **reverse
      dependents**, stamp each a new `rev` and emit each on `taskUpdates` — otherwise a board keeps showing
      a blocked marker on a task that is ready
- [ ] add `nextCandidate(project)`: `state='todo'`, no `depends_on` entry that is not `done`, ordered by
      `position`, limit 1 — no exclusion set is needed (see Technical Details)
- [ ] write tests for cycle detection: direct, transitive (A→B→C→A), self, and a legal diamond
- [ ] write tests for referential validation: a dangling `depends_on` and a cross-project edge are both
      refused
- [ ] write store tests: a blocked task is skipped by `nextCandidate` and returned once its dependency is
      `done`; closing a dependency emits an update for every reverse dependent; an empty backlog returns null
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 8

### Task 8: Session columns `task_ref` and `project_id`

**Files:**
- Modify: `sqldelight/io/kotgent/db/Sessions.sq`
- Modify: `src/store/SqliteEventStore.kt`
- Modify: `src/store/EventStore.kt`
- Modify: `src/core/SessionMeta.kt`
- Modify: `src/transport/EventsWs.kt`
- Modify: `test/store/EventStoreTest.kt`
- Modify: `test/transport/TransportTest.kt`

- [ ] add `task_ref TEXT` and `project_id TEXT` as the **last** two columns of `Sessions.sq`'s `CREATE TABLE`,
      with a comment explaining the additive-`ALTER` ordering rule (the existing `archived` comment is the
      template)
- [ ] include both in `upsert` as `COALESCE(excluded.x, sessions.x)`, citing the `MAX(read_cursor, …)`
      precedent at `Sessions.sq:72`; leave them out of `updateCache` and `updateControlState`
- [ ] add `setTaskRef` (nullable, so it can clear) and `setProjectId`, each touching only its column plus
      `updated_at` and `rev`, each emitting a `SessionUpdate`
- [ ] add `sessionsHoldingTask(ref)` — the query delete and the `done` transition both need. There is
      deliberately **no** bulk clear statement: unlinking is a loop of `setTaskRef(id, null)` so every row
      gets its own `rev` and its own `SessionUpdate`, or the sidebar keeps a badge pointing at a deleted task
- [ ] add the two `driver.hasColumn`-guarded `ALTER TABLE` statements to `SqliteEventStore.init`
- [ ] add `taskRef` / `projectId` to `SessionMeta`, to the domain `SessionUpdate` (`EventStore.kt`), to its
      emission (`SqliteEventStore.kt:425`), and to `SessionUpdateDto` + `SessionUpdate.toDto()`
      (`EventsWs.kt:212-235`) — the badge is live only if all four carry it
- [ ] write a test that opens the store over a pre-`task_ref` schema and then re-opens over the migrated one
- [ ] write tests: `setTaskRef` leaves `state` / `last_seq` / `provider_session_id` untouched, bumps `rev`
      and emits a `SessionUpdate` carrying the new ref; a full-row `upsert` carrying a null `task_ref` does
      **not** clear an existing link; the WS patch carries `taskRef`
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 9

### Task 9: `TaskService` — linking, transitions, delete

**Files:**
- Create: `src/daemon/TaskService.kt`
- Modify: `sqldelight/io/kotgent/db/Backlog.sq`
- Create: `test/daemon/TaskServiceTest.kt`

- [ ] create `src/daemon/TaskService.kt` — the one place that owns link/unlink/transition/delete, holding
      both stores; document at the top that it never nests their locks: each store is called, returns, and
      only then is the other called
- [ ] implement `link(ref, sessionId)` as the two independent writes from Technical Details — the
      conditional `todo → in_progress` advance (zero rows is normal) and the unconditional
      `setTaskRef`. No compensation, no busy-session error, no ordering requirement
- [ ] implement `linkNext(project, sessionId)`: loop over `nextCandidate(project)`, attempt the conditional
      advance, and on zero rows re-query (the row is no longer `todo`, so it is naturally excluded);
      terminate when the query returns null, which is the only "nothing eligible" answer
- [ ] implement `unlink(sessionId)` — clears the session's link and **leaves the task's state alone**
- [ ] implement `transition(ref, to, author, message?)` — entry state, activity row (with the message when
      given) and reverse-dependent re-emission in one task-store transaction; on `done`, unlink every
      session holding it afterwards, one `setTaskRef(id, null)` per row
- [ ] implement `delete(ref)` — unlink every holder first (event store, per row), then delete through the
      tracker (task store); document that a link racing the delete leaves a dangling ref cleared at the next
      reconciliation, and that making it atomic would need a statement spanning both tables
- [ ] append an activity row for every link, unlink and comment, with the session id as `author`
- [ ] write tests: two sessions can link the same task and both appear in `sessionsHoldingTask`; a link to a
      task already `in_progress` succeeds and leaves the state alone; `linkNext` under contention gives two
      sessions two different tasks; `linkNext` on an empty backlog reports nothing eligible; `unlink` does
      not change the state; `transition(done)` unlinks every holder; `delete` unlinks every holder
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 10

### Task 10: Project resolution and dangling-ref cleanup in the daemon

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/daemon/Reconciler.kt`
- Modify: `test/daemon/SessionManagerTest.kt`
- Modify: `test/daemon/ReconcilerTest.kt`

- [ ] add nullable `taskStore` / `projectFs` constructor parameters **with null defaults** to
      `SessionManager` and `Reconciler`, so every existing harness (`test/daemon/ReconcilerTest.kt:98`,
      `ImportWiringTest.kt`, `SessionImportTest.kt`, `test/transport/ShutdownSignalsTest.kt:60`) keeps
      compiling untouched; Task 11 supplies the real ones
- [ ] resolve the project at `start` and at `importSession` from the already-canonicalized cwd, persist
      `project_id`, and upsert the `projects` row (a miss leaves both alone — no file is created here)
- [ ] backfill `project_id` for rows that lack one during startup reconciliation, best-effort and silent on
      failure
- [ ] in the same pass, clear a `sessions.task_ref` that names a task no longer in `backlog_entries` — the
      dangling-reference cleanup. **Nothing else is reconciled about tasks**: an `in_progress` entry with no
      linked session is legitimate (a human dragged the card), so there is nothing to "recover"
- [ ] write tests with a fake `ProjectFs`: a session in a project gets its id and creates the `projects` row;
      a session outside one gets null; two sessions in different worktrees of the same repository get the
      **same** id; a dangling `task_ref` is cleared while a valid one is left alone; an `in_progress` entry
      with no session survives reconciliation untouched
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 11

### Stage 2 — transport

### Task 11: Construct and wire the task subsystem

**Files:**
- Modify: `src/cli/Commands.kt`
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/ServerLifecycleTest.kt`

- [ ] construct `SqliteTaskStore` over the existing driver in `Commands.daemon` **before** `SessionManager`
      (the driver is created around `Commands.kt:330`) and build `TaskService` from it plus the event store
- [ ] pass the task store and a real `ProjectFs` into `SessionManager(...)` and into
      `Reconciler(tmux, store, vendorProbe, registry)` at `Commands.kt:491` — without this, everything
      Task 10 added is dead code in the shipped daemon
- [ ] thread `taskStore` / `taskService` through `KotgentServer`'s constructor and factory as **nullable with
      null defaults**, following the `pushStore` / `vapidPublicKey` precedent at `Server.kt:189-191`
- [ ] pass the task store into `eventsWs` so the baseline can include tasks (Task 16 fills that in)
- [ ] write tests: a server built without the task subsystem serves everything it does today, and one built
      with it still does — the constructor and factory change is inert on its own. **Do not assert a task
      route here**; `TaskRoutes.kt` does not exist until Task 12
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 12

### Task 12: Read routes, `/whoami` and DTOs

**Files:**
- Create: `src/transport/TaskRoutes.kt`
- Modify: `src/transport/Server.kt`
- Create: `test/transport/TaskRoutesTest.kt`

- [ ] create `src/transport/TaskRoutes.kt` with `TaskDto`, `BacklogEntryDto` (carrying `blocked`),
      `TaskDetailDto` (dependencies, **linked sessions**, activity, and the project's **path**, which
      `start --task` needs), `ProjectDto`, `ActivityEntryDto`, each carrying `rev`
- [ ] implement `GET /api/v1/whoami` — resolve the calling pane through the existing registry and answer
      `{ sessionId, projectId, taskRef }`; unresolvable → `400` naming `--session`
- [ ] implement `GET /api/v1/tasks?project=`, `GET /api/v1/tasks/{ref}`, `GET /api/v1/projects`
- [ ] mount `taskRoutes(...)` inside the **`route(API_PREFIX)` block introduced by Task 1**, alongside
      `controlRoutes` — mounting it in the bare `authenticated { }` scope re-creates the collision Task 1
      exists to prevent
- [ ] write tests: list is ordered by position and carries `blocked`; detail carries deps, linked sessions,
      activity and the project path; an unknown ref is `404`; a malformed ref is `400`; `/whoami` answers
      from the pane header and `400`s without one; all routes require authentication
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 13

### Task 13: Create, edit, delete and project creation

**Files:**
- Modify: `src/transport/TaskRoutes.kt`
- Modify: `test/transport/TaskRoutesTest.kt`

- [ ] add `POST /api/v1/tasks` implementing the project-resolution order from Technical Details **in that
      order**: explicit `project` → the calling session's `project_id` → `resolveProject(session cwd)` →
      create the file via `ProjectFileWriter` → `400` naming `--project` only when no session resolves at all
- [ ] add `PATCH /api/v1/tasks/{ref}` (title / body / state, with an optional message on a state change, so
      `task review -m` is one operation) and `DELETE /api/v1/tasks/{ref}` through `TaskService.delete`
- [ ] add `POST /api/v1/projects`, refusing a relative path or a non-existent directory with `400`; upsert
      the `projects` row here and on the implicit-creation path
- [ ] map the typed failures: unknown ref → `404`, malformed body or bad path → `400`
- [ ] write tests: create with an explicit `project` and **no pane header** succeeds (the board's path);
      create from a pane whose session has a project uses it; create from a pane in a projectless directory
      creates the file **and** the `projects` row rather than `400`ing; create with no session and no
      `project` is a `400`; a state change with a message writes exactly one activity row; a delete unlinks
      every session holding the ref
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 14

### Task 14: Move, dependency and comment routes

**Files:**
- Modify: `src/transport/TaskRoutes.kt`
- Modify: `test/transport/TaskRoutesTest.kt`

- [ ] add `POST /api/v1/tasks/{ref}/move`, `…/deps`, `…/comment`
- [ ] require session identity on `comment` (pane header, or `sessionId` in the body); map a cycle, a
      self-edge, a dangling ref and a cross-project edge each to `400` naming the offending edge
- [ ] write tests for each route's success path, the four dependency `400`s, the header-vs-body identity
      precedence on `comment`, and that a dependency change emits updates for the reverse dependents
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 15

### Task 15: Link, unlink and next

**Files:**
- Modify: `src/transport/TaskRoutes.kt`
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `test/transport/TaskRoutesTest.kt`

- [ ] add `POST /api/v1/tasks/{ref}/link`, `…/unlink`, and `POST /api/v1/tasks/next` with an optional
      `project` defaulting to the resolved session's `project_id`. All three **require** session identity —
      each writes `sessions.task_ref` or attributes an activity row
- [ ] add an optional `taskRef` to `POST /api/v1/sessions` (`ControlRoutes.kt`), linking the new session in
      the same request, so `start --task` and the board's "Start session" are one call; a failed session
      creation leaves no link, and the link itself cannot fail
- [ ] answer "nothing eligible" from `next` distinguishably from every error, so the CLI can map it to exit
      `3` without guessing
- [ ] write tests: two sessions linking the same task both succeed and both appear in the detail's session
      list; a link from an unknown pane is refused rather than silently attributed; `next` under contention
      hands two sessions two different tasks; `next` with nothing eligible is not an error status;
      `POST /sessions` with a `taskRef` returns a session already carrying it
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 16

### Task 16: Task frames on the global events socket

**Files:**
- Modify: `src/transport/EventsWs.kt`
- Modify: `test/transport/TransportTest.kt`

- [ ] add `TasksSnapshotDto`, `TaskRowDto`, `TaskUpdateDto`, `TaskRemovedDto` to the `sealed EventsFrame`
      hierarchy with `@SerialName` values `tasks_snapshot` / `task_row` / `task_update` / `task_removed`
- [ ] collect `taskStore.taskUpdates` in its own `launch` with its own `.onSubscription { }`, which **reads**
      the snapshot and queues it to the existing sequential per-socket sender as the first item — it must not
      `send` while holding up the collector, or a burst larger than the 1024-entry buffer (one renormalization
      of a large project) drops the oldest before collection begins
- [ ] extend the conflating sender to bank task updates by `TaskRef` under the same lock, keeping the "only a
      delivered row marks the ref as carried" rule; a `TaskUpdate` with a null entry becomes `task_removed`
      and clears the carried mark
- [ ] skip the whole task branch cleanly when the server was built without a task store
- [ ] extend `test/transport/TransportTest.kt:310` so every new frame kind carries the `type` discriminator
- [ ] write tests in `TransportTest.kt`: a link reaches a connected socket as a patch; a task created after
      connect arrives as a full row first; a delete arrives as `task_removed`; a renormalization reaches the
      socket; a burst emitted during the baseline is not lost
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 17

### Task 17: Absolute shell links

**Files:**
- Modify: `resources/webui/index.html`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] make the three document-relative links in `index.html:16-18` root-absolute:
      `/manifest.webmanifest`, `/icons/logo.svg`, `/icons/apple-touch-icon.png`. Under the deep routes Task 18
      introduces they would otherwise resolve to `/tasks/manifest.webmanifest` and 404 — taking the iOS
      "Add to Home Screen" path, and therefore push, down with them
- [ ] confirm this preserves the stable-URL rule: the manifest, `sw.js` and `icons/` deliberately stay off
      the `/_v/<rev>/` prefix
- [ ] write a serving test asserting all three hrefs are root-absolute in the served shell
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 18

### Task 18: History-API routing fallback

**Files:**
- Modify: `src/transport/Server.kt`
- Modify: `src/transport/WebUiAssets.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add `isSpaRoute(rel)` to `WebUiAssets.kt` — an **exact segment grammar**, not a prefix match:
      `tasks`, `tasks/<one segment>`, `s/<one segment>`. Test it against the original `rel`, since
      `stripRevPrefix` runs first at `Server.kt:362`. Add a KDoc paragraph beside `neverImmutable` /
      `isRevToken` saying why exact matching: a prefix match would serve a `200` shell for
      `/s/id/extra` and `/tasks/id/missing.js`, contradicting the promise that a mistyped path 404s
- [ ] in `serveStaticFile`, when the file is absent and `isSpaRoute` holds, serve `index.html` **through the
      existing `path == "index.html"` branch** (`Server.kt:374`) so `__REV__` is substituted and the response
      keeps `no-cache`; keep the `..` traversal guard first
- [ ] write tests **with the task routes mounted**, so an API/UI collision would actually fail: `/tasks` and
      `/tasks/local:42` serve the shell with a substituted revision and `no-cache`; `/api/v1/tasks` returns
      JSON; `/s/id/extra`, `/tasks/id/missing.js`, `/lib/nope.js` and `/nope` all `404`; `/_v/<rev>/app.js`
      still serves `immutable`; `/sw.js` and `/manifest.webmanifest` are unaffected; traversal is still `403`
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 19

### Task 19: Couple session "Done" to the task

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `test/daemon/SessionManagerTest.kt`

- [ ] on the session "Done" action, transition its linked task to `done` (which unlinks every holder) before
      archiving the session, calling the two stores sequentially, never nested
- [ ] state the honest guarantee in the KDoc: `NonCancellable` prevents **coroutine cancellation** between
      the two writes; it is not a transaction and does not survive process death or a throw from the second
      write. The residual — a task marked `done` while its session is still unarchived — is visible, benign,
      and fixed by pressing Done again; do not describe it as atomic
- [ ] leave `resume`'s existing un-archive alone, and confirm it does not resurrect a `done` task
- [ ] write tests: Done on a linked session closes the task, unlinks it and archives the session; Done on an
      unlinked session behaves exactly as today; closing the task from the board leaves the session alive
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 20

### Stage 3 — CLI

### Task 20: `TmuxSelf` — resolving the calling pane safely

**Files:**
- Create: `src/cli/TmuxSelf.kt`
- Create: `test/cli/TmuxSelfTest.kt`

- [ ] create `src/cli/TmuxSelf.kt` — read `$TMUX_PANE` and `$TMUX`, parse the socket path out of `$TMUX`'s
      `<socket>,<pid>,<index>` form, and return the pane id **only** when that path is kotgent's
      (`${TMUX_TMPDIR:-/tmp}/tmux-<uid>/kotgent`; `TMUX_SOCKET` at `Cli.kt:20` is only the `-L` label)
- [ ] document why: pane ids are unique per tmux *server*, so `%2` from the operator's own tmux would
      otherwise resolve to an unrelated live kotgent pane
- [ ] inject the environment lookup so the rule is testable without a live tmux
- [ ] write tests: kotgent socket accepted; a foreign socket rejected; `$TMUX_TMPDIR` honoured; `$TMUX`
      absent; `$TMUX_PANE` malformed; both absent
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 21

### Task 21: Parse the `task` and `project` command families

**Files:**
- Modify: `src/cli/Cli.kt`
- Modify: `test/cli/CliTest.kt`

- [ ] add `CliCommand` variants for `TaskList`, `TaskShow`, `TaskAdd`, `TaskNext`, `TaskLink`, `TaskUnlink`,
      `TaskReview`, `TaskComment`, `TaskDone`, `TaskMove`, `TaskDep`, `TaskRemove`, `ProjectInit`
- [ ] add the `task` / `project` dispatch to `parseArgs` beside the existing `token` / `config` sub-parsers
- [ ] add `--task <ref>` to `parseStart`, `--session <id>` to every task subcommand, and `--project <path>`
      to `add`, `list` and `next` so they work from a plain shell
- [ ] add `-m/--message` with a `-` convention that reads the body from stdin, for `add`, `comment`, `review`
- [ ] make the ref optional on `show`, `comment`, `review`, `unlink` and `done` — absent means "my session's
      task", resolved at execution time
- [ ] write tests for every subcommand's happy parse, missing-argument errors, the optional-ref forms, and
      the `--task` / `--session` / `--project` flags; an unknown subcommand produces `Invalid` with a helpful
      message
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 22

### Task 22: ApiClient task methods

**Files:**
- Modify: `src/cli/ApiClient.kt`
- Modify: `test/cli/CliTest.kt`

- [ ] add request methods for every route from Tasks 12-15, including `whoami()` and the `taskRef` argument
      on `startSession`, sending the `X-Kotgent-Tmux-Pane` header from the pane `TmuxSelf` resolved and
      `sessionId` in the body when `--session` was given
- [ ] surface the HTTP status on failures so `Commands` can map each to its own exit code (errors already
      carry a status — the `isDefiniteAnswer` precedent)
- [ ] keep the existing `HttpTimeout` discipline: never issue an untimed request at the daemon
- [ ] write tests against the existing `withStub` harness (`test/cli/CliTest.kt:855`) for each method,
      including that the pane header is sent only when resolved and that `--session` sends a body id instead
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 23

### Task 23: Execute the task commands with JSON output and exit codes

**Files:**
- Modify: `src/cli/Commands.kt`
- Modify: `test/cli/CliTest.kt`

- [ ] implement every `task` / `project` command, printing **JSON only** for the `task` family
- [ ] resolve a ref-less subcommand's subject through `/whoami` — **but skip that call entirely when
      `--session <id>` was given**, since the id is already known and `/whoami` only resolves panes. Fail
      with a clear JSON error when the caller is neither in a kotgent pane nor passing `--session`
- [ ] map outcomes to exit codes: nothing eligible from `task next` → `3`; every other failure keeps today's
      convention
- [ ] write tests that each command prints parseable JSON, that `task next` exits `3` on an empty backlog,
      that `--session` works outside any pane without calling `/whoami`, and that a ref-less command outside
      a pane fails cleanly
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 24

### Task 24: `start --task` and the task reference in `kotgent list`

**Files:**
- Modify: `src/cli/Commands.kt`
- Modify: `test/cli/CliTest.kt`

- [ ] make `kotgent start --task <ref>` send the ref on the single `POST /api/v1/sessions` call from Task 15,
      choosing the cwd as: the caller's cwd when it resolves to the task's project, else the project's stored
      path, else the caller's cwd — and say which it used in the JSON output
- [ ] fail loudly, without leaving a session behind, when the ref does not exist
- [ ] add the task reference column to `renderSessions`, keeping that output human-readable (existing
      coverage at `test/cli/CliTest.kt:434`)
- [ ] write tests: `start --task` creates a session already linked; a stale `projects.path` falls back to the
      caller's cwd; an unknown ref creates nothing; `renderSessions` shows the reference and stays aligned
      when it is absent
- [ ] run `./kotlin build && ./kotlin test` - must pass before task 25

### Stage 4 — Web UI

### Task 25: Router

**Files:**
- Create: `resources/webui/lib/router.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] create `lib/router.js` — parse `location.pathname` into `{screen, id}` for `/`, `/tasks`,
      `/tasks/{ref}`, `/s/{id}`; `navigate(path)` over `history.pushState`; a `popstate` subscription
- [ ] wire the router into `App` so the session view and the board are two screens, preserving the existing
      query-parameter deep link — `sw.js:268` opens `/?session=<id>` on a notification click, so that
      contract has a live consumer
- [ ] register `/lib/router.js` in `WebUiServingTest.kt:272`'s module list
- [ ] write a serving test asserting the module exports `parseRoute` and `navigate`, and that the
      `?session=` deep link still resolves
- [ ] run `node --check resources/webui/lib/router.js` and `./kotlin build && ./kotlin test` - must pass
      before task 26

### Task 26: Task state module

**Files:**
- Create: `resources/webui/lib/tasks.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] create `lib/tasks.js` with the API calls and the newest-`rev`-wins merge helpers, mirroring
      `lib/sessions.js`'s `upsertIfNewer` / `patchIfNewer` — including stamping the frame's rev onto the
      stored row, without which the invariant self-destructs after the first patch
- [ ] apply the four new frame kinds in `app.js`'s events-socket handler, ignoring unknown kinds
- [ ] register `/lib/tasks.js` in the served-module list
- [ ] write a serving test asserting the merge helpers are exported and the removal path drops the row
- [ ] run `node --check` on both changed files and `./kotlin build && ./kotlin test` - must pass before task 27

### Task 27: Kanban board

**Files:**
- Create: `resources/webui/components/Board.js`
- Create: `resources/webui/components/TaskCard.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] create `Board.js` — four columns, a project selector in the header (exactly one project at a time; no
      "all projects" mode), a "new project" action backed by `DirectoryCompletion`, and a "new task" action
      on the `todo` column that posts the **selected project id** (the browser has no session, so the id
      must be explicit); `done` capped at the last N with a "show all" toggle
- [ ] create `TaskCard.js` — title, blocked marker, **every** linked session with its state dot, dependency
      count, and a menu carrying delete
- [ ] surface rejected mutations (a refused move, a dependency cycle, a bad project path) through the
      existing announcement channel rather than failing silently
- [ ] add the board styles inside the existing dark palette, with phone variables staying inside the current
      `@media (max-width: 720px)` block
- [ ] register both modules in the served-module list
- [ ] write serving tests: both modules are served as JavaScript; four column headings; the `done` cap; the
      new-task action sends a project id; a card renders more than one linked session; the error
      announcement path exists
- [ ] run `node --check` on both files and `./kotlin build && ./kotlin test` - must pass before task 28

### Task 28: Desktop drag and drop

**Files:**
- Modify: `resources/webui/components/Board.js`
- Modify: `resources/webui/components/TaskCard.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] implement pointer-event dragging: capture after an 8 px slop, `setPointerCapture`, `touch-action: none`
      on the **card handle only** — the discipline `Dialog`'s swipe established in `dialogs.js`
- [ ] a drop **within** a column is one `POST …/move`; a drop **into another column** is one
      `PATCH …/{ref}` carrying the new state, and a drop that changes both is the `PATCH` followed by the
      `move` — say so explicitly, because `/move` does not accept a state and `PATCH` does not accept a
      position
- [ ] apply results newest-rev-wins so a racing frame cannot roll a gesture back
- [ ] write serving tests asserting the pointer handlers, the scoped `touch-action` rule, and that a
      cross-column drop issues the state request
- [ ] run `node --check` on the changed files and `./kotlin build && ./kotlin test` - must pass before task 29

### Task 29: Phone column switcher and move menu

**Files:**
- Modify: `resources/webui/components/Board.js`
- Modify: `resources/webui/components/TaskCard.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] render one column at a time with a switcher below the phone breakpoint, with no dragging installed there
- [ ] add move actions (top/bottom/up/down/other column) to the card menu, hitting the same endpoints as the
      desktop drag
- [ ] write serving tests asserting the switcher and the move menu exist and that dragging is scoped away
      from the mobile branch
- [ ] run `node --check` on the changed files and `./kotlin build && ./kotlin test` - must pass before task 30

### Task 30: Task detail view

**Files:**
- Create: `resources/webui/components/TaskDetail.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] create `TaskDetail.js` — editable title and body, dependency editor, the list of linked sessions,
      activity feed (fetched with `GET /api/v1/tasks/{ref}`, not from the socket), delete
- [ ] add "Start session", which opens the **ordinary** New-session dialog pre-filled with the project cwd
      and the task, submitting the single `POST /api/v1/sessions` with `taskRef` — do not add a second
      launch path
- [ ] register the module in the served-module list
- [ ] write serving tests: the module is served; it renders the feed and the session list; the start action
      reuses the existing dialog and sends `taskRef`
- [ ] run `node --check resources/webui/components/TaskDetail.js` and `./kotlin build && ./kotlin test` -
      must pass before task 31

### Task 31: Task badges on sessions

**Files:**
- Modify: `resources/webui/lib/sessions.js`
- Modify: `resources/webui/components/Sidebar.js`
- Modify: `resources/webui/components/TerminalPane.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add the badge text builder to `lib/sessions.js` so the wording lives in one place, rendering a ref
      whose task is unknown as the bare ref rather than blank (a delete can leave one briefly)
- [ ] render the badge in `SessionRow` and in the terminal header, linking to `/tasks/{ref}`
- [ ] write serving tests asserting the builder is exported, both components use it, and an unknown ref
      still renders
- [ ] run `node --check` on the three changed files and `./kotlin build && ./kotlin test` - must pass before
      task 32

### Task 32: Palette commands

**Files:**
- Modify: `resources/webui/lib/commands.js`
- Modify: `resources/webui/app.js`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add the task commands to `buildCommands` in `lib/commands.js` (open board, new task, open this
      session's task), with chords from the currently free letters — **this file is the only registry**
- [ ] wire the handlers through the existing single capture-phase `keydown` listener in `app.js`
- [ ] extend `theCommandRegistryIsTheServedSourceOfSearchAndLeaderCommands` (`WebUiServingTest.kt:381`) with
      the new ids
- [ ] write a test asserting no chord collides with an existing one
- [ ] run `node --check` on both files and `./kotlin build && ./kotlin test` - must pass before task 33

### Stage 5 — the agent's contract

### Task 33: Agent Skill contract

**Files:**
- Create: `docs/agent-task-skill.md`

- [ ] write the contract the Heapy/Kortex skill implements: read your task with `kotgent task show` (no ref
      needed), comment progress, finish with `kotgent task review -m "summary, commits"`, take the next one
      with `kotgent task next`, stop on exit code `3`
- [ ] state plainly that kotgent does **not** enforce one worker per task: `task next` will not hand the same
      task to two agents in a row, but an explicit link on a task already in progress is allowed, and the
      board shows every linked session. An agent must not assume it is alone
- [ ] document that the daemon writes `.kotgent.json` but never commits it, so the agent should mention it
      rather than sweeping it into an unrelated commit
- [ ] document the JSON shapes the skill parses and the `/whoami` mechanism behind ref-less commands,
      pointing at the DTOs rather than restating them
- [ ] no code and no tests — this file is the interface description the out-of-repo skill is written against

### Task 34: Verify acceptance criteria

- [ ] a backlog with no `.kotgent.json` anywhere behaves as today, and no file is written until a task is created
- [ ] sessions in `/repo` and in an ordinary linked worktree of it resolve to the same project and backlog;
      the recorded unsupported layouts degrade to the current directory instead of misbehaving
- [ ] an agent in a pane reads its own task, comments and moves it to `review` without being told its session id
- [ ] the board creates a task with no session anywhere in the picture
- [ ] two sessions can be linked to one task and both are visible on its card; `task next` under contention
      hands them two different tasks
- [ ] reordering, state changes, links and deletions reach a second open browser tab without a reload, and a
      closed dependency clears the blocked marker on its dependents
- [ ] a blocked task is never returned by `task next`; a cycle, a self-edge, a dangling ref and a
      cross-project edge are all refused at insert
- [ ] closing a task from the board unlinks its sessions and leaves them alive; "Done" on a session closes
      the task and archives the session; deleting a task unlinks its sessions
- [ ] `/tasks` serves the shell and `/api/v1/tasks` serves JSON; `/s/id/extra` is a `404`; the phone ticket
      dialog still mints through the unprefixed `/auth/ticket`; a deep-linked `/tasks/{ref}` serves a shell
      whose manifest and icon links resolve
- [ ] run the full suite: `./kotlin build && ./kotlin test` — 0 skips, count at or above the 921 baseline plus
      the new tests
- [ ] run `node --check` over every changed `.js` file
- [ ] confirm every new ES module appears in `test/transport/WebUiServingTest.kt`
- [ ] confirm `git grep '/Users/' -- '*.yaml'` is still empty

### Task 35: [Final] Update documentation

- [ ] add a CLAUDE.md section for the task layer: the two-layer split and why; `.kotgent.json` as the project
      key, its supported and unsupported git layouts, and `projects.path` being "last seen"; **why there is
      no exclusivity** and what that bought (no compensation, no busy-session error, no orphan recovery);
      the conditional `todo → in_progress` being a selection convention, not an invariant; every `sessions`
      write staying in `SqliteEventStore` and why; `sessions.task_ref` being a reference, not a foreign key;
      the session-stays-linked-through-review rule; the `/api/v1` prefix rule — what moved, what deliberately
      did not, and the Ktor literal-beats-tailcard priority that forces the split; the exact-segment SPA
      grammar; the `task` CLI family being JSON-only
- [ ] note in CLAUDE.md's "Where things live" the new `src/task/`, `src/store/SqliteTaskStore.kt`,
      `src/cli/TmuxSelf.kt`, `schema/`, and the new Web UI modules
- [ ] update the test baseline number in CLAUDE.md's "Testing & running"
- [ ] update the issue #4 body so it no longer describes the superseded exclusive-claim protocol
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**External system updates**

- The Agent Skill itself lives in the Heapy/Kortex repository and ships through the heapy plugin. It is
  written against `docs/agent-task-skill.md` (Task 33) and is not part of this repository's build or tests.
- `schema/project.v1.json` is referenced by `$schema` at
  `https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json`, so the URL only resolves once
  the file is on `main`. Editors will show an unresolved schema until then — expected, not a defect.

**Manual verification** (needs a human at a terminal, per the automation rule)

- After Task 1: reload any open tab (it will be broken until reloaded, by design); confirm the phone QR
  dialog still mints a ticket; confirm push notifications still resolve their session list; and check an
  installed PWA whose worker predates the change — it may need one navigation before its worker updates.
- Desktop: drag a card within a column and across columns; confirm a second browser tab follows without a
  reload.
- Phone: the single-column switcher and the card move menu; confirm no gesture conflicts with the terminal's
  swipe-scroll bridge or the dialog swipe. Confirm a deep-linked `/tasks/{ref}` can still be added to the
  home screen.
- Real agent: start a session with `--task`, have the agent comment and move the task to `review` from inside
  its pane, confirm the sidebar badge updates live.
- Worktree: create a `git worktree` of this repository, start a session in it, and confirm it lands in the
  same project as a session in the main checkout. Then repeat with `git init --separate-git-dir` and confirm
  it degrades to the current directory rather than picking the metadata directory.
- Run `kotgent task next` from the operator's own tmux and confirm it refuses on the socket check rather than
  attributing the link to an unrelated kotgent pane.
