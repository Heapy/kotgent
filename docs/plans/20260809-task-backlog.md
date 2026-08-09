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
- **baseline**: 927 native tests passed / 0 skipped after wave 1 (the plan was written against 921;
  Tasks 1 and 2 added six), plus the build-info plugin's 7 JVM
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
// canonical uuid, LOWER-CASED. The constructor is private and every value arrives through
// ProjectId.of(...) / ProjectId.parseOrNull(...): isCanonicalUuid is case-insensitive but SQLite
// compares TEXT binary, so two spellings of one uuid would key two projects with two backlogs.
// This is `SessionManager.importSession`'s lowercase-a-UUID-shaped-id rule, moved into the type.
@JvmInline value class ProjectId private constructor(val value: String)

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

**`SqliteTaskStore` is split into a core plus two collaborators** — `BacklogOrdering` and
`BacklogDependencies`, each taking the generated queries object and the store's mutex. The split is made
**for parallel execution**: it lets three agents implement the store at once without touching one file. It
is not a bad shape on its own (the class would otherwise be ~600 lines covering three unrelated concerns),
but the honest reason is the fleet, and the KDoc says so.

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

### Board CSS vocabulary (Tasks 24, 25 and 28 share exactly this list)

Three agents write the board's markup and its stylesheet at the same time and share no file: Task 24 owns
`Board.js` + `TaskCard.js`, Task 25 owns `TaskDetail.js`, Task 28 owns **all** of `style.css` and the
components ship no CSS of their own. Nothing in the code connects them, so the class names are frozen
here. **Use these spellings and no others**; a component that needs a class not on this list adds it to
this list first (and tells Task 28), rather than inventing one Task 28 will never style.

The prefix is `task-`/`board-` throughout, matching the existing `session-*` / `terminal-*` families.

| Class | Where | What it is |
|---|---|---|
| `board` | Board | the screen's root grid |
| `board-head` | Board | the row holding the project selector and the two actions |
| `board-project` | Board | the project `<select>` |
| `board-new-task` / `board-new-project` | Board | the two header buttons |
| `board-columns` | Board | the four-column flex/grid track (one column below the breakpoint) |
| `board-column` | Board | one column; `data-state="todo|in_progress|review|done"` carries which |
| `board-column-head` | Board | its title + count |
| `board-column-switch` | Board | the phone-only column switcher |
| `board-show-all-done` | Board | the "show all" toggle under the capped `done` column |
| `board-drop-target` | Board | added to the column a drag is currently over |
| `task-card` | TaskCard | one card; `is-dragging` while it is the pointer-captured one |
| `task-card-handle` | TaskCard | the drag handle — **the only element with `touch-action: none`** |
| `task-card-title` | TaskCard | the title line |
| `task-card-meta` | TaskCard | the row holding the badges below the title |
| `task-blocked` | TaskCard, TaskDetail | the blocked marker |
| `task-dep-count` | TaskCard, TaskDetail | the dependency count |
| `task-sessions` | TaskCard, TaskDetail | the linked-session list |
| `task-session-dot` | TaskCard, TaskDetail, Sidebar | one session's state dot; reuses `stateBadge`'s value in `data-state` |
| `task-card-menu` | TaskCard | the per-card menu (delete; plus the move actions on a phone) |
| `task-detail` | TaskDetail | the detail screen's root |
| `task-detail-head` | TaskDetail | title + state + the "Start session" action |
| `task-detail-body` | TaskDetail | the editable body |
| `task-deps` | TaskDetail | the dependency editor |
| `task-activity` | TaskDetail | the feed |
| `task-activity-row` | TaskDetail | one feed entry; `data-kind` carries the `ActivityKind` |
| `task-badge` | Sidebar, TerminalPane | the session→task badge (Task 26); links to `/tasks/{ref}` |
| `task-badge-unknown` | Sidebar, TerminalPane | the same badge when the ref names no known task |

Task 28's serving test asserts every one of these appears in `style.css`; Tasks 24/25/26's serving tests
assert their components emit the ones they own. That is the whole coupling — there is no build step that
could catch a typo, so the two halves are asserted from opposite ends.

## Parallel execution protocol

This plan is written to be executed by a fleet: one agent per task, many at once, each in its own git
worktree, followed by a review loop. Everything about its shape follows from one constraint.

**Ten worktrees produce ten branches, and merging them is painless only if no two touched the same file.**
So file ownership is exclusive, and it is what the waves are for — not the dependency order between
features, which is much looser than it looks once everything compiles against interfaces.

### The rules an executor must follow

1. **Touch only the files your task's `Owns:` block lists.** Not one more, not even a one-line import fix
   elsewhere. Another agent owns that file right now.
2. **Never change a signature declared in Task 2 (Contracts).** If your task cannot be implemented against
   the declared interface — a missing method, a wrong parameter, an unrepresentable return — **stop and
   report it**. Do not edit the contract, do not add an overload, do not work around it. A contract change
   invalidates other agents' work in flight and must be made once, centrally.
3. **Write tests in the new test file your task owns.** The shared suites (`WebUiServingTest.kt`,
   `CliTest.kt`, `TransportTest.kt`, `EventStoreTest.kt`) are chokepoints; Task 2 makes whatever edits they
   need, and no wave-2 task may touch them.
4. **Leave no `TODO()` behind.** Task 2 ships stub bodies on purpose; your task's file must contain none
   when you are done. The final verification greps for them.
5. **Done means the command passes**, not that the code looks right: `./kotlin build && ./kotlin test`,
   in that order (`PtyTest` execs the `ptycheck` binary, which `test` alone never links).
6. **Do not commit, branch or stage.** Leave the working tree dirty; the diff is the deliverable.
7. The repository's conventions are in the task preamble below, written out. Do not go looking for
   `AGENTS.md` — it is a one-line pointer that expands to nothing outside Claude Code.

### Conventions every executor needs (repeat these into each agent's prompt)

- Build is the **JetBrains Kotlin Toolchain** driven by `./kotlin` — no Gradle, no `build.gradle`. Manifests
  are `module.yaml` / `project.yaml`. Sources in `src/`, tests in `test/`; target `macosArm64`.
- `internal` is **not visible to tests** — anything a test touches is `public`.
- KT-78062: custom cinterop does not link into the test binary. All raw cinterop lives in the `sysnative`
  module behind interfaces. Stock `platform.posix` is fine everywhere; nothing in this plan needs new cinterop.
- The SQLDelight codegen plugin **drops `.sqm` files**, so `Schema.migrate()` is empty. New tables come from
  `CREATE TABLE IF NOT EXISTS` in the owning store's `init`; new columns from a hand-rolled `ALTER TABLE`
  behind a `PRAGMA table_info` check. Never `runCatching { ALTER … }`.
- The native SQLDelight driver confines a transaction to one thread and is not suspend-safe: every
  `db.transaction { }` runs inside `mutex.withLock` with **no suspension inside it**.
- `kotlin.system.getTimeMillis()` is an error-level deprecation. Use `kotlin.time.Clock`, injected as
  `now: () -> Long`.
- Bound every Flow/WS/PTY test with `withTimeout(...)`.
- **Never start the daemon or a live agent** (`kotgent daemon`, `./kotlin run`, real `claude`/`codex`/`junie`,
  `launchctl`). Only `./kotlin build` / `./kotlin test`.
- Changed `.js` files must pass `node --check <file>`.
- `ProjectId` has a **private constructor**: build one with `ProjectId.of(...)` (throws) or
  `ProjectId.parseOrNull(...)` (`null`), both of which lower-case the uuid. `TaskRef` keeps a public
  constructor plus `TaskRef.parseOrNull`.
- **Do not declare a package-level extension function in your own file if another task might need the
  same one.** `io.kotgent.transport` alone holds four concurrently-written files; an extension is
  package-level, so two of them declaring `fun BacklogEntry.toDto(...)` is a redeclaration error that only
  appears at merge, in nobody's worktree. The shared mappers are already in `TaskDtos.kt`; if you need one
  that is not there, stop and report it rather than adding it locally.
- The three new `EventStore` writes (`setTaskRef`, `setProjectId`, `sessionsHoldingTask`) default to
  **throwing**. A fake store on a path that links a task must override all three — a green test whose fake
  silently did nothing is exactly what that default exists to prevent.

### Checking the ownership claim

The exclusivity above is a property of this document, so it can be verified from this document. Before
launching a wave, extract each task's `Owns:` block and assert that no file appears in two tasks of the same
wave. Every `Owns:` block is one paragraph, terminated by a blank line, with each path in backticks — kept
that way deliberately so the check is a dozen lines of script rather than a careful read.

Five files are owned by two tasks each, all in *different* waves and therefore sequential:
`CLAUDE.md` and `README.md` (Tasks 1 and 32), `src/cli/ApiClient.kt` (Tasks 1 and 20),
`src/cli/Commands.kt` (Tasks 2 and 15), and `src/daemon/SessionManager.kt` (Tasks 12 and 29). Nothing
overlaps inside wave 2, inside wave 1.5, or between the two.

**Task 2 is excluded from that count on purpose.** It creates or modifies nearly every file waves 1.5 and
2 then implement — that is what a contracts wave *is* — so listing those pairs would drown the check. Run
the overlap assertion across the tasks of one wave, not across the whole document.

### Waves

| Wave | Tasks | Concurrency | Why |
|---|---|---|---|
| 0 | 1 | serial | The `/api/v1` move rewrites every client. Nothing else can be in flight. |
| 1 | 2 | serial | Contracts. Creates every shared file so wave 2 has none. |
| 1.5 | 3, 5, 6 | parallel (3) | The pure rules five wave-2 tasks CALL. A free function cannot be faked. |
| 2 | 4, 7–28 | parallel | 23 tasks, one file each. |
| 3 | 29–32 | serial | Integration assertions, the skill contract, acceptance, docs. |

**Why wave 1.5 exists, and why it is not a design change.** `Ordering.kt`, `Dependencies.kt` and
`ProjectFile.kt` expose **top-level functions**. That is the right shape for a pure rule — there is
nothing to inject and nothing to configure — but it means a caller cannot substitute a fake for them:
`positionForEnd`, `positionBetween`, `wouldCycle`, `resolveProject` and `mainCheckoutRoot` are reached by
name, so while their bodies are `TODO()` every caller hits a live `NotImplementedError` in its own
worktree, whatever it passes in. Five wave-2 tasks call them and could not have written a passing test:
Task 7's `create` positions the new entry, Task 8's whole job is position arithmetic, Task 9's cycle
refusal is `wouldCycle`, and Tasks 12 and 14 both resolve a project. The mistake was the schedule, not the
design, so the three files move one wave earlier instead of growing an interface nobody needs. **Task
numbers are stable identities and are deliberately NOT renumbered** — wave 1.5 is Tasks 3, 5 and 6, wave 2
picks up at Task 4, and the task blocks below are grouped under their wave rather than in numeric order.

**Concurrency is a guess, not a measurement.** Ten concurrent Kotlin/Native builds share one `~/.konan` and
one machine; the toolchain's behaviour under that contention has not been measured here. Start at 4–6
executors and raise it only if wall-clock actually improves. Do not treat "10" as a target.

**Where the residual risk is.** Every wave-2 task tests against fakes and stubs, because that is what makes
them independent. The first time the real store meets the real routes meets the real browser is wave 3. That
is the price of the parallelism, it is concentrated in one place on purpose, and wave 3's tests exist to pay
it — not to re-check what wave 2 already proved.

### The review loop

After wave 2 merges and wave 3 lands, review in rounds rather than once:

- **Fan out per area, not per file.** One reviewer per subsystem (storage, service, transport, CLI, Web UI,
  project resolution), each given the merged diff for its area and this plan's Technical Details as the
  specification. A reviewer that has to read everything finds less than six that each read a sixth.
- **Two models, independently.** Run Claude and codex over the same area without showing either the other's
  output. Their disagreement is the signal; their agreement is the least informative part. Three rounds on
  this plan produced defects every time, and in two of three rounds the new defects were introduced by the
  previous round's fixes — so **re-review what you just changed**, not only what you have not looked at yet.
- **Verify before acting.** A reviewer's finding is input to a decision, not a work order. Confirm it in the
  code first; roughly one in six did not survive that check while writing this plan.
- **Fixes are tasks too.** A fix that spans files owned by different areas goes to one agent, serially. Do
  not fan out fixes that touch the same file.
- **Stop when a round produces only findings you decide not to act on.** That is the honest terminating
  condition, not a fixed number of rounds.

## Implementation Steps

### Wave 0 — the API namespace

### Task 1: Move the client-facing API under `/api/v1`

**Owns:** `src/transport/Server.kt`, `src/cli/ApiClient.kt`, `src/cli/AttachClient.kt`,
`resources/webui/lib/api.js`, `resources/webui/sw.js`,
`test/transport/{TransportTest,WebUiServingTest,ShutdownSignalsTest}.kt`, `test/cli/CliTest.kt`,
`CLAUDE.md`, `README.md`

**Runs alone.** It rewrites every client of the daemon; nothing may be in flight beside it.

- [x] add a single `API_PREFIX = "/api/v1"` constant and wrap the **body of the existing
      `authenticated { … }` block** (`Server.kt:177-191`) in `route(API_PREFIX) { … }` — that block already
      contains exactly the cookie/Bearer-gated surface (`controlRoutes`, `fileUploadRoutes`,
      `directoryCompletionRoutes`, `preferencesRoutes`, `eventsWs`, `terminalWs`, `pushRoutes`), so this is
      one structural change rather than per-route edits; `AuthRouteSelector` evaluates to `Transparent`
      (`Auth.kt:200-206`), so the nesting is sound
- [x] leave `/hooks/*` and the whole `/auth*` surface outside the prefix, with a KDoc recording why: each
      adapter bakes `ingressUrl(port)` into a per-session shell script on disk, and `/auth` is addressed by
      the QR code, the PWA's `location.replace(AUTH_PATH)` and an inline `fetch("/auth/exchange")` inside
      the page the daemon serves (`AuthRoutes.kt:577`)
- [x] apply the prefix **inside `apiRequest` and `wsUrl`** (`lib/api.js:50`, `:34`) with an exemption for
      paths starting with `/auth` — `components/dialogs.js:886` mints the phone ticket through
      `apiRequest("/auth/ticket")`. Centralizing here also covers `app.js`, `components/TerminalPane.js:450`,
      `components/dialogs.js:317,640` and `lib/push.js:255,269,324` for free
- [x] apply the **same exemption on the Kotlin side**: `ApiClient` mixes moved paths (`"$baseUrl/sessions"`)
      with unmoved ones (`AUTH_TICKET_PATH`, `AUTH_ROTATE_PATH`); a blanket prefix helper breaks
      `kotgent web` and `kotgent token rotate`
- [x] update **all three** `sw.js` URL constants — `SESSIONS_URL` (`:30`), `PUSH_SUBSCRIBE_URL` (`:32`),
      `PUSH_UNSUBSCRIBE_URL` (`:33`)
- [x] update `AttachClient`, including the WebSocket URLs
- [x] retarget **both** API-vs-static canaries rather than deleting either:
      `theStaticCatchAllDoesNotShadowTheTokenGatedApi` (`WebUiServingTest.kt:3694`) and
      `versionApiIsAuthenticatedAndOutranksTheStaticCatchAll` (`:3704`) — each should assert the prefixed
      path works **and** the bare path falls through, because that literal-beats-tailcard property is what
      Task 17's SPA fallback depends on. Also fix the served-`sw.js` literal assertion at `:2221`
- [x] write tests: `GET /api/v1/sessions` works; `/hooks/claude` and `/auth` are untouched; the WS endpoints
      answer on their prefixed paths; the phone ticket still mints through the unprefixed path
- [x] record **three** compatibility breaks in the KDoc and in CLAUDE.md: an older `kotgent` binary cannot
      talk to a newer daemon; an already-open browser tab breaks hard rather than degrading (its `/events`
      upgrade falls to the static catch-all and 404s instead of 401ing, so the sign-out recovery never fires
      — a reload is the recovery); and **an installed service worker outlives its pages**, so with every tab
      closed it can wake on a push still holding the old paths until a navigation replaces it. The last one
      is silent; say so
- [x] update every documented path in `README.md` and `CLAUDE.md` (search both for `/sessions`, `/events`,
      `/push/`, `/preferences`, `/version`, `/directories/` and fix each hit — do not work from a fixed list)
- [x] `./kotlin build && ./kotlin test`

### Wave 1 — contracts

### Task 2: Declare everything

**Owns:** every file listed below. **Runs alone, and is reviewed before wave 2 starts.**

- Create: `src/task/{Task,TaskTracker,ProjectFs,TaskErrors}.kt`
- Create: `src/store/{TaskStore,SqliteTaskStore,BacklogOrdering,BacklogDependencies}.kt`
- Create: `src/daemon/TaskService.kt`
- Create: `src/transport/{TaskDtos,TaskRoutes,TaskReadRoutes,TaskWriteRoutes,TaskLinkRoutes}.kt`
- Create: `src/cli/{TaskCliCommands,TaskCommands,TmuxSelf}.kt`
- Create: `src/task/{ProjectFile,ProjectFileWriter,Ordering,Dependencies}.kt`
- Create: `sqldelight/io/kotgent/db/{Tasks,Backlog,Projects}.sq`
- Create: `resources/webui/lib/{router,tasks}.js`, `resources/webui/components/{Board,TaskCard,TaskDetail}.js`
- Modify: `src/core/{Ids,SessionMeta}.kt`, `src/store/{EventStore,SqliteEventStore}.kt`,
  `sqldelight/io/kotgent/db/Sessions.sq`, `src/transport/{EventsWs,Server,WebUiAssets,ControlRoutes}.kt`,
  `src/daemon/{SessionManager,Reconciler}.kt`, `src/cli/{Cli,Commands,ApiClient}.kt`,
  `resources/webui/{app.js,index.html}`, `test/transport/WebUiServingTest.kt`

This task is the whole plan's single point of failure: a wrong signature here blocks or corrupts work in
26 agents at once. It gets its own review pass before anything fans out.

⚠️ **It failed that review the first time**, and the repair is recorded here rather than rewritten away,
because the failure mode is the interesting part. Two independent reviews of the contracts commit found
that it did not do its one job. Three categories, all fixed before any fan-out:

  1. **A declaration nobody can fake.** `Ordering.kt` / `Dependencies.kt` / `ProjectFile.kt` are top-level
     functions, so five wave-2 tasks would have hit a live `NotImplementedError`. Fixed by the schedule
     (wave 1.5), not by the design.
  2. **Declarations that were simply missing**, each blocking a task that does not own the file they belong
     in: `ApiClient`'s fifteen task methods (Task 21 calls them, Task 20 owns the file), `taskRef` on
     `StartSessionRequest` / `SessionDto` / `SessionMeta.toDto()`, a `TaskService` parameter on
     `controlRoutes`, four `TaskDtos` mappers that four agents in one package would otherwise each declare
     (a redeclaration error at merge), and three `app.js` wirings — `tasks` on `Sidebar`/`TerminalPane`,
     `onStartSession` on `TaskDetail`, the three task entries in `buildCommands`' `actions`.
  3. **Wrong contracts**: `lastActivityId`'s KDoc understated its requirement, `ProjectId` did not
     normalize case, the three new `EventStore` writes defaulted to a *silent* no-op, and
     `TASK_PANE_HEADER` was a fourth uncoupled copy of a string.

The lesson for the next contracts wave: review it by asking, per wave-2 task, "can this one compile AND
pass a test in a worktree where every other task is unstarted?" — not by reading the declarations for
plausibility.

**Declarations (no behaviour).** Write the full signatures and KDoc from Technical Details:

- [x] `src/core/Ids.kt` — add `TaskRef` and `ProjectId` value classes with their `init` validation, beside
      `SessionId` (**not** in `src/task/`, or `SessionMeta` would make `core` depend on `task`).
      `ProjectId`'s constructor is **private** and `of` / `parseOrNull` lower-case the uuid: the columns
      that key a project compare `TEXT` binary, so two spellings would mean two backlogs. Read-back from
      SQL uses `parseOrNull` (a hand-edited column must not throw out of a read)
- [x] `src/task/Task.kt` — `TaskState`, `Task`, `BacklogEntry` (with derived `blocked`), `TaskActivityEntry`,
      `ActivityKind`, `TaskUpdate`
- [x] `src/task/TaskTracker.kt`, `src/task/ProjectFs.kt` (interface only — the posix implementation belongs
      to Task 3), `src/task/TaskErrors.kt` (the typed failures the routes map: unknown ref, bad dependency
      edge, bad project path)
- [x] `src/store/TaskStore.kt` — the complete interface including `taskUpdates: SharedFlow<TaskUpdate>`
- [x] `src/store/EventStore.kt` — add `setTaskRef`, `setProjectId`, `sessionsHoldingTask`; add `taskRef` and
      `projectId` to the domain `SessionUpdate`. All three are **defaulted so the seven pre-existing fake
      stores keep compiling**, and the default **throws `UnsupportedOperationException`** rather than
      no-op'ing: they are the first defaulted WRITES on this interface, and a fake that forgot to override
      a silent one would let `TaskService.link()` pass its test while persisting nothing
- [x] `src/core/SessionMeta.kt` — add the two nullable fields
- [x] `src/transport/TaskDtos.kt` — every wire DTO (`TaskDto`, `BacklogEntryDto`, `TaskDetailDto` carrying
      deps, linked sessions, activity and the project path, `ProjectDto`, `ActivityEntryDto`), each with `rev`
- [x] `src/transport/TaskDtos.kt` — and the four **mappers**, with real bodies:
      `BacklogEntry.toDto(task, dependsOn)`, `ProjectRecord.toDto()`, `TaskActivityEntry.toDto()`,
      `SessionMeta.toLinkedSessionDto()`. Tasks 13, 14, 15 and 16 all need them and all four files are in
      `package io.kotgent.transport`, where an extension function is package-level — two agents writing
      `fun BacklogEntry.toDto(...)` is a redeclaration error the moment their branches merge. The
      `SessionUpdate.toDto()` precedent in `EventsWs.kt`. Not `TODO()`: a mapping is nobody's design work,
      and a stub would fail four tasks' tests from a file none of them owns
- [x] `src/transport/ControlRoutes.kt` — `taskRef` on `StartSessionRequest`, `taskRef`/`projectId` on
      `SessionDto` and `SessionMeta.toDto()`, and a nullable `taskService` parameter on `controlRoutes`
      (passed from `Server.kt`). Task 15 owns this file but not `Server.kt`, so without the parameter it
      could not wire `POST /sessions` + `taskRef` to the linking path at all; and the DTO fields must exist
      before Task 26's badge, because the socket's snapshot and full-row frames carry `SessionDto`
- [x] `src/cli/ApiClient.kt` — declare `whoami()` and the fourteen task/project methods with `TODO()`
      bodies, plus the `paneId` constructor parameter, the `paneHeader()` helper and the (implemented)
      `taskRef` argument on `startSession`. Task 21 calls every one of them and may not touch this file
- [x] `src/transport/EventsWs.kt` — the four new `EventsFrame` subclasses and `taskRef` on `SessionUpdateDto`
      + `SessionUpdate.toDto()`
- [x] `src/cli/TaskCliCommands.kt` — the new `CliCommand` variants (a separate file in the same package, so
      Task 19 can own `Cli.kt` alone)

**Schema (complete, so nobody edits SQL later).**

- [x] the three new `.sq` files with every table **and every query** the plan needs — CRUD, activity,
      projects, `maxPosition`, `minPosition`, `neighboursAround`, `nextCandidate`, the
      dependency reads and writes, reverse-dependent lookup. **There is deliberately no `renormalize`
      statement**: every rewritten row must stamp its OWN `rev` and emit its own `TaskUpdate`, which a bulk
      `UPDATE` cannot do, so renormalizing is a loop over `selectPositionsOrdered` + `setPosition` inside one
      transaction. `Backlog.sq` says so at both queries
- [x] `Sessions.sq` — the two columns declared **last**, `COALESCE(excluded.x, sessions.x)` in `upsert`
      (the `MAX(read_cursor, …)` precedent at `:72`), `setTaskRef`, `setProjectId`, `sessionsHoldingTask`
- [x] `SqliteEventStore.init` — the two `driver.hasColumn`-guarded `ALTER TABLE` statements

**Skeletons (compile, throw if called).**

- [x] `SqliteTaskStore` with its `Mutex`, `revCounter`, `taskUpdates` flow, `CREATE TABLE IF NOT EXISTS`
      block, and construction of `BacklogOrdering` / `BacklogDependencies`; every method body `TODO()`.
      The collaborator split exists so three agents can implement the store in parallel — say so in the KDoc,
      because it is otherwise an odd shape for a 600-line class
- [x] `BacklogOrdering`, `BacklogDependencies`, `TaskService`, `TaskCommands`, `TmuxSelf`, `ProjectFile`,
      `ProjectFileWriter`, `Ordering`, `Dependencies` — declarations with `TODO()` bodies
- [x] `TaskRoutes.kt` — the aggregator calling `taskReadRoutes()`, `taskWriteRoutes()`, `taskLinkRoutes()`;
      the three route files with empty extension functions
- [x] `WebUiAssets.kt` — `isSpaRoute(rel): Boolean` returning `false`, so Task 17 owns only this file

**Wiring (so no wave-2 task touches an integration file).**

- [x] `Server.kt` — nullable `taskStore` / `taskService` on the constructor and factory (the
      `pushStore`/`vapidPublicKey` precedent at `:189-191`), `taskRoutes(...)` mounted inside the
      `route(API_PREFIX)` block, the task store passed to `eventsWs`, and the `isSpaRoute` call site added to
      `serveStaticFile` (absent file + match → serve through the existing `path == "index.html"` branch at
      `:374`, keeping the `..` guard first)
- [x] `Commands.kt` — construct `SqliteTaskStore` **before** `SessionManager`, build `TaskService`, pass both
      plus a real `ProjectFs` into `SessionManager(...)` and `Reconciler(...)` (`:491`), and dispatch the new
      `CliCommand` variants to `TaskCommands`. `--task` does **not** go through `Commands.start`: `runCli`
      routes a `start` carrying one to `TaskCommands.startWithTask`, because Task 21 owns the "which cwd"
      rule and Task 20 owns `ApiClient.startSession`'s new argument — neither exists in wave 1
- [x] the task column in `renderSessions` is still **not** here — it is Task 15's, which now owns
      `src/cli/Commands.kt` for exactly that. What blocked it (`SessionDto` had no `taskRef`) is gone: the
      field is declared here now, so Task 15 only has to render it
- [x] `SessionManager.kt` / `Reconciler.kt` — nullable `taskStore` / `projectFs` constructor parameters with
      **null defaults**, so `test/daemon/ReconcilerTest.kt`, `ImportWiringTest.kt`, `SessionImportTest.kt` and
      `test/transport/ShutdownSignalsTest.kt` keep compiling untouched
- [x] `ControlRoutes.kt` **is** owned here after all — for its DECLARATIONS only (the three DTO fields and
      the `taskService` parameter, listed above). The behaviour behind them is Task 15's and this file is
      in its `Owns:` block. The original plan left the whole file to Task 15 and said "if that forces a
      signature change, it is a contract bug: fix it here, before wave 2" — it did, and this is that fix
- [x] `app.js` — mount the router, dispatch the four new frame kinds to `lib/tasks.js`, and route between the
      session view and the board
- [x] `app.js` — and the three wirings the new components cannot add themselves, because no wave-2 task
      owns this file: **`tasks` on `Sidebar` and `TerminalPane`** (without it Task 26's badge can only
      render its unknown-task arm, since a session row carries a ref and not a title);
      **`onStartSession(cwd, taskRef)` on `TaskDetail`** plus `initialTaskRef` on `NewSessionDialog`, which
      is what lets Task 25 reuse the ordinary New-session dialog instead of adding the second launch path
      the plan forbids; and **`openBoard` / `newTask` / `openSessionTask` in `buildCommands`' `actions`**
      for Task 27
- [x] `index.html` — make the three document-relative links root-absolute (`/manifest.webmanifest`,
      `/icons/logo.svg`, `/icons/apple-touch-icon.png`); a deep-linked `/tasks/{ref}` would otherwise serve a
      shell whose manifest 404s, taking the iOS install path and push with it
- [x] the new JS modules as **stubs exporting their documented signatures**, so `WebUiServingTest.kt` can
      register them now and no wave-2 task has to touch that shared file
- [x] `WebUiServingTest.kt` — register the new modules in the list at `:272`. There are **five**, not six:
      `lib/router.js`, `lib/tasks.js`, `components/Board.js`, `components/TaskCard.js`,
      `components/TaskDetail.js` (Task 25 also edits `components/dialogs.js`, which is already registered).
      The index.html install-surface assertion moved to the root-absolute spellings in the same pass

- [x] `./kotlin build && ./kotlin test` — green with stubs. No test may assert stubbed behaviour

### Wave 1.5 — the pure rules wave 2 calls by name

*Three tasks, all independent, all in `src/task/`. They ship BEFORE wave 2 because they are top-level
functions: a wave-2 task cannot fake one, so a `TODO()` body in any of them is a live
`NotImplementedError` in five other agents' worktrees. See "Why wave 1.5 exists" above. Same rules as
wave 2 otherwise — own your files, test against fakes, finish on `./kotlin build && ./kotlin test`.*

### Task 3: Project resolution rules
**Owns:** `src/task/ProjectFile.kt`, `test/task/ProjectFileTest.kt`

**Called by Tasks 12 and 14** (`resolveProject`, `mainCheckoutRoot`) — hence wave 1.5.

- [ ] implement `parseProjectFile` (8 KiB cap, `isCanonicalUuid`, name trimmed/capped at 100/no control
      characters, malformed JSON → null, never a throw), `mainCheckoutRoot` (relative `gitdir:` resolved
      against the `.git` file's directory then canonicalized **before** looking for `/worktrees/<name>`), and
      `resolveProject`; plus `PosixProjectFs` implementing the contract's interface
- [ ] tests over a fake `ProjectFs`: file in cwd; in an ancestor; nearest-wins in a monorepo; ordinary linked
      worktree; relative `gitdir:`; symlinked common dir; and each recorded unsupported layout degrading to
      cwd — `--separate-git-dir`, a submodule `gitdir: …/modules/<name>`, a bare repository, no git at all
- [ ] parse tests: malformed JSON, non-uuid `id`, control characters, 200-char name, a 1 MiB file
- [ ] `ProjectId` has a **private constructor**: build one with `ProjectId.of(...)` / `ProjectId.parseOrNull(...)`,
      which lower-case it. A test that an upper-case `id` in the file parses to the same `ProjectId` as its
      lower-case spelling belongs here
- [ ] one test against the **real** posix implementation in `TMPDIR`, with a real `.git` directory and a real
      `.git` worktree file

### Task 5: Ordering rules
**Owns:** `src/task/Ordering.kt`, `test/task/OrderingTest.kt`

**Called by Tasks 7 and 8** (`positionForEnd`, `positionBetween`, `positionForTop`,
`needsRenormalization`) — hence wave 1.5.

- [ ] `positionForEnd`, `positionBetween`, `positionForTop`, `needsRenormalization` at `1e-9`
- [ ] tests: midpoint ordering holds; a top insert halves toward zero; repeated midpoints trip the threshold

### Task 6: Dependency rules
**Owns:** `src/task/Dependencies.kt`, `test/task/DependenciesTest.kt`

**Called by Task 9** (`wouldCycle` is its cycle refusal) — hence wave 1.5.

- [ ] `wouldCycle(edges, from, to)` as a pure ancestor walk
- [ ] tests: direct, transitive (A→B→C→A), self, and a legal diamond

### Wave 2 — implementations, one file each

*Twenty-three tasks (4 and 7–28), all independent. Each owns its files exclusively, tests against fakes,
and finishes on `./kotlin build && ./kotlin test`. None may edit a file another task owns or a signature
from Task 2. Numbering is not contiguous because Tasks 3, 5 and 6 moved to wave 1.5 and keep their
numbers.*

### Task 4: Project file creation
**Owns:** `src/task/ProjectFileWriter.kt`, `schema/project.v1.json`, `test/task/ProjectFileWriterTest.kt`

- [ ] `mkstemp` sibling → write → `fsync` → `chmod` to `0666 & ~umask` → `link(2)`, unlinking the temp on
      every path including success (mirror `FileUploadRoutes.kt:185-273`); `EEXIST` re-reads and returns the
      existing descriptor; a relative or non-directory target is a typed error
- [ ] `schema/project.v1.json`: `{$schema, id, name}`, uuid pattern, `maxLength: 100`,
      `additionalProperties: false`
- [ ] tests: shape and mode; a second call returns the first call's id; a pre-created target keeps its
      content; relative and non-directory targets refused; no temp survives any branch

### Task 7: Task store core
**Owns:** `src/store/SqliteTaskStore.kt`, `test/store/TaskStoreTest.kt`

- [ ] fill the tracker CRUD, activity append/read, project upsert/list, and `taskUpdates` emission; `delete`
      cascades to `backlog_entries`, `backlog_deps` (both directions) and `task_activity` in one transaction
      and emits a null-entry `TaskUpdate`. The `sessions` unlink is **not** here — it is Task 11's
- [ ] **the nine delegating members are yours too.** `SqliteTaskStore.kt` carries nine `TODO()`s whose
      messages name Tasks 8 and 9 (`entry`, `listBacklog`, `nextCandidate`, `move`, `dependenciesOf`,
      `dependentsOf`, `dependencyEdges`, `addDependency`, `removeDependency`). Those tasks own
      `BacklogOrdering.kt` / `BacklogDependencies.kt` and may not touch this file, so nobody but you can
      fill them. Each is a one-line delegation to the collaborator this class already constructs
      (`ordering.move(...)`, `mutex.withLock { dependencies.…Locked(...) }` for the read path) — the
      behaviour is theirs, the wiring is yours, and the labels in the stub messages were a bookkeeping
      error, not a second owner. **Leave no `TODO()` in this file.**
- [ ] `lastActivityId` (`Tasks.sq`) must be read inside the **same `db.transaction { }`** as the
      `insertActivity` whose id it reports — not merely under the mutex. See that query's comment: outside
      a transaction the native driver answers it from a `query_only` reader connection and returns `0`, and
      an in-memory store cannot reproduce it because there the reader pool *is* the transaction pool
- [ ] tests: create → get; update bumps `rev` and emits; delete removes everything and emits; activity is
      ordered and append-only **with non-zero ids**; project upsert refreshes name and path; re-open resumes
      `revCounter`; opening over a database missing the tables recreates them without logging an error

### Task 8: Backlog ordering
**Owns:** `src/store/BacklogOrdering.kt`, `test/store/BacklogOrderingTest.kt`

- [ ] `move` for top/bottom/before/after inside the store mutex; on a collapsed gap, renormalize the
      project's column in one transaction and retry once; **every** renormalized row stamps a new `rev` and
      emits
- [ ] the arithmetic is `src/task/Ordering.kt`'s, implemented in wave 1.5 — call it, do not re-derive it.
      `SqliteTaskStore.move` is a one-line delegation to your class and is Task 7's line, not yours
- [ ] tests: each move produces the expected order; 60 consecutive midpoint inserts between one pair still
      yield a strictly ordered list; a renormalization bumps and emits for every row

### Task 9: Backlog dependencies
**Owns:** `src/store/BacklogDependencies.kt`, `test/store/BacklogDependenciesTest.kt`

- [ ] add/remove validating that both refs exist, are in the same project, differ, and do not close a cycle;
      compute `blocked` in the read path; after every edit re-stamp and re-emit the **reverse dependents**;
      `nextCandidate(project)`
- [ ] the cycle refusal is `wouldCycle` from `src/task/Dependencies.kt`, implemented in wave 1.5 — call it.
      The eight delegating members in `SqliteTaskStore.kt` are Task 7's lines, not yours
- [ ] tests: the four refusals; a blocked task is skipped by `nextCandidate` and returned once its dependency
      is `done`; closing a dependency emits an update for every reverse dependent; an empty backlog is null

### Task 10: Session link columns
**Owns:** `src/store/SqliteEventStore.kt`, `test/store/EventStoreTaskLinkTest.kt`

- [ ] implement `setTaskRef` (nullable, so it can clear), `setProjectId`, `sessionsHoldingTask`, and carry
      `taskRef`/`projectId` through `toMeta`, `upsertSession` and the emitted `SessionUpdate`
- [ ] tests: `setTaskRef` leaves `state`/`last_seq`/`provider_session_id` alone, bumps `rev` and emits with
      the new ref; a full-row `upsert` carrying a null `task_ref` does **not** clear an existing link; open
      over a pre-`task_ref` schema and re-open over the migrated one

### Task 11: TaskService
**Owns:** `src/daemon/TaskService.kt`, `test/daemon/TaskServiceTest.kt`

- [ ] `link` as the two independent writes (conditional `todo → in_progress` where zero rows is normal, then
      unconditional `setTaskRef`); `linkNext` looping over `nextCandidate` and terminating on null;
      `unlink` leaving the task's state alone; `transition` (state + activity + reverse-dependent
      re-emission in one task-store transaction, unlinking every holder on `done`); `delete` unlinking every
      holder first, then deleting through the tracker. Never nest the two stores' locks
- [ ] tests against fake stores: two sessions link the same task and both appear in `sessionsHoldingTask`; a
      link to a task already `in_progress` succeeds and leaves the state alone; `linkNext` under contention
      gives two sessions two different tasks; `linkNext` on an empty backlog reports nothing eligible;
      `unlink` does not change state; `transition(done)` and `delete` each unlink every holder

### Task 12: Project resolution in the daemon
**Owns:** `src/daemon/SessionManager.kt`, `src/daemon/Reconciler.kt`, `test/daemon/TaskProjectWiringTest.kt`

- [ ] resolve the project at `start` and `importSession` from the canonicalized cwd, persist `project_id`,
      upsert the `projects` row; backfill `project_id` during startup reconciliation; clear a
      `sessions.task_ref` naming a task no longer in `backlog_entries`
- [ ] **nothing else about tasks is reconciled**: an `in_progress` entry with no linked session is
      legitimate (a human dragged the card), so there is nothing to recover
- [ ] `resolveProject` / `mainCheckoutRoot` come from `src/task/ProjectFile.kt` (wave 1.5) and are real by
      the time you start; you own neither that file nor the `ProjectFs` interface
- [ ] tests with a fake `ProjectFs`: a session in a project gets its id and creates the row; outside one it
      is null; two worktrees of one repository agree; a dangling `task_ref` is cleared and a valid one is
      not; an `in_progress` entry with no session survives untouched

### Task 13: Read routes and `/whoami`
**Owns:** `src/transport/TaskReadRoutes.kt`, `test/transport/TaskReadRoutesTest.kt`

- [ ] `GET /whoami` (pane → session through the registry; unresolvable → `400` naming `--session`),
      `GET /tasks?project=`, `GET /tasks/{ref}`, `GET /projects`
- [ ] the DTO mappers are already in `TaskDtos.kt` (`BacklogEntry.toDto`, `ProjectRecord.toDto`,
      `TaskActivityEntry.toDto`, `SessionMeta.toLinkedSessionDto`). Use them; declaring your own in this
      file collides with Tasks 14/15/16 at merge, because an extension function is package-level
- [ ] tests: the list is ordered by position and carries `blocked`; detail carries deps, linked sessions,
      activity and the project path; unknown ref `404`, malformed ref `400`; all require authentication

### Task 14: Write routes
**Owns:** `src/transport/TaskWriteRoutes.kt`, `test/transport/TaskWriteRoutesTest.kt`

- [ ] `POST /tasks` with the project-resolution order **in that order** (explicit `project` → the session's
      `project_id` → `resolveProject(session cwd)` → create the file → `400` naming `--project` only when no
      session resolves at all); `PATCH /tasks/{ref}` (title/body/state, with an optional message on a state
      change so `task review -m` is one operation); `DELETE`; `POST /tasks/{ref}/{move,deps,comment}`;
      `POST /projects`
- [ ] `resolveProject` / `mainCheckoutRoot` (`src/task/ProjectFile.kt`) are real by the time you start —
      wave 1.5 — and you reach the filesystem and the writer through `routing.service.projectFs` /
      `.projectFiles`, which `TaskService` carries for exactly this reason
- [ ] the DTO mappers are already in `TaskDtos.kt` (`BacklogEntry.toDto`, `ProjectRecord.toDto`,
      `TaskActivityEntry.toDto`, `SessionMeta.toLinkedSessionDto`). Use them; declaring your own in this
      file collides with Tasks 13/15/16 at merge
- [ ] tests: create with an explicit project and **no pane header** (the board's path); create from a pane
      whose session has a project; create from a pane in a projectless directory creates the file and the
      `projects` row rather than `400`ing; create with neither is `400`; a state change with a message writes
      exactly one activity row; the four dependency `400`s; a delete unlinks every holder

### Task 15: Link routes
**Owns:** `src/transport/TaskLinkRoutes.kt`, `src/transport/ControlRoutes.kt`, `src/cli/Commands.kt`,
`test/transport/TaskLinkRoutesTest.kt`

- [ ] `POST /tasks/{ref}/link`, `…/unlink`, `POST /tasks/next` (optional `project`, defaulting to the
      session's) — all three **require** session identity
- [ ] honour `StartSessionRequest.taskRef` in `ControlRoutes.kt`'s `POST /sessions`: parse it with
      `TaskRef.parseOrNull` (`400` on a malformed ref), start the session, then link through the
      `taskService` the route now receives — so `start --task` is one call with nothing to roll back. When
      `taskService` is `null` (a daemon with no task layer) a request carrying a `taskRef` is `400`: refuse
      the link rather than starting the session and dropping it silently
- [ ] answer "nothing eligible" from `next` distinguishably from every error, so the CLI can map exit `3`
- [ ] tests: two sessions link one task and both appear in the detail; a link from an unknown pane is
      refused rather than silently attributed; `next` under contention hands out two different tasks;
      `next` with nothing eligible is not an error status; `POST /sessions` with a `taskRef` returns a
      session already carrying it; `POST /sessions` with a `taskRef` against a task-less server is `400`
- ✅ `taskRef` / `projectId` on `SessionDto` and `SessionMeta.toDto()` are **already there** — the
      contracts repair added them, because the events socket's snapshot and full-row frames carry
      `SessionDto` and Task 26's badge would otherwise appear only after a session's next patch. Nothing
      to do here beyond not removing them
- ➕ add the task column to `renderSessions` in `src/cli/Commands.kt`, now listed in your `Owns:` block
      (Task 2 is the only other owner and it is a wave behind). Keep the existing header/format assertions
      in `CliTest.kt` passing — they match substrings, so a new column does not need a test edit, which is
      just as well: `CliTest.kt` is a shared suite no wave-2 task may touch

### Task 16: Task frames on the events socket
**Owns:** `src/transport/EventsWs.kt`, `test/transport/TaskEventsTest.kt`

- [ ] collect `taskStore.taskUpdates` in its own `launch` with its **own** `.onSubscription { }`, which
      *reads* the snapshot and queues it to the existing sequential sender as the first item — it must not
      `send` from there, or a burst larger than the 1024-entry buffer (one renormalization of a large
      project) is dropped before collection begins
- [ ] extend the conflating sender to bank by `TaskRef`, keeping "only a delivered row marks the ref as
      carried"; a null-entry `TaskUpdate` becomes `task_removed` and clears the mark; skip the whole branch
      when the server has no task store
- [ ] the DTO mappers are already in `TaskDtos.kt`; use them rather than declaring another
      `BacklogEntry.toDto` in this file, which would collide with Tasks 13/14/15 at merge
- [ ] tests: a link arrives as a patch; a task created after connect arrives as a full row first; a delete
      arrives as `task_removed`; a renormalization reaches the socket; a burst during the baseline is not lost

### Task 17: SPA route grammar
**Owns:** `src/transport/WebUiAssets.kt`, `test/transport/SpaRoutingTest.kt`

- [ ] implement `isSpaRoute(rel)` as an **exact segment grammar** — `tasks`, `tasks/<one>`, `s/<one>` —
      matched against the original `rel`, since `stripRevPrefix` runs first at `Server.kt:362`. Not a prefix
      match: that would serve a `200` shell for `/s/id/extra` and for a mistyped asset path. No arm for the
      empty path, which `staticWebUi` already turns into `index.html` at `:356`
- [ ] tests (mounting the task routes, so an API/UI collision would actually fail): `/tasks` and
      `/tasks/local:42` serve the shell with a substituted revision and `no-cache`;
      **`/api/v1/tasks` does NOT serve the shell** — assert that, not that it serves JSON: in your worktree
      Task 13's route bodies are still empty, so the prefixed path 404s, and 404-not-200 is exactly the
      property that proves the SPA grammar did not swallow the API namespace. The positive
      "`/api/v1/tasks` returns JSON while `/tasks` returns the shell" assertion belongs to Task 30, which
      has real route bodies
- [ ] `/s/id/extra`, `/tasks/id/missing.js`, `/lib/nope.js`, `/nope` all `404`; `/_v/<rev>/app.js` is
      still `immutable`; `/sw.js` and `/manifest.webmanifest` unaffected; traversal still `403`

### Task 18: `TmuxSelf`
**Owns:** `src/cli/TmuxSelf.kt`, `test/cli/TmuxSelfTest.kt`

- [ ] return the pane id **only** when `$TMUX`'s socket path is kotgent's
      (`${TMUX_TMPDIR:-/tmp}/tmux-<uid>/kotgent`; `TMUX_SOCKET` at `Cli.kt:20` is only the `-L` label),
      because pane ids are unique per tmux *server* and `%2` from the operator's own tmux would otherwise
      resolve to an unrelated kotgent pane; inject the environment lookup
- [ ] tests: kotgent socket accepted; foreign socket rejected; `$TMUX_TMPDIR` honoured; `$TMUX` absent;
      `$TMUX_PANE` malformed; both absent

### Task 19: CLI parsing
**Owns:** `src/cli/Cli.kt`, `test/cli/CliTaskParseTest.kt`

- [ ] dispatch `task` / `project` beside the existing `token` / `config` sub-parsers into the contract's
      `CliCommand` variants; `--task` on `start`; `--session` on every task subcommand; `--project` on
      `add`/`list`/`next`; `-m/--message` with a `-` stdin convention; an optional ref on `show`, `comment`,
      `review`, `unlink`, `done`
- [ ] tests: every subcommand's happy parse, missing-argument errors, the optional-ref forms, the three
      flags, and an unknown subcommand producing `Invalid` with a helpful message

### Task 20: ApiClient
**Owns:** `src/cli/ApiClient.kt`, `test/cli/ApiClientTaskTest.kt`

- [ ] fill the bodies of the fifteen task/project methods **already declared** on `ApiClient` — every route
      from Tasks 13–15 plus `whoami()`. They are declared (with `TODO()` bodies and a `paneId` constructor
      parameter) because Task 21 calls all of them and may not touch this file. The `taskRef` argument on
      `startSession` and the private `paneHeader()` helper are already implemented; **do not change a
      signature** — if one is wrong, stop and report it
- [ ] send `TASK_PANE_HEADER` via `paneHeader()` when a pane was resolved and `sessionId` in the body when
      `--session` was given; surface the HTTP status on failures; keep the `HttpTimeout` discipline
- [ ] tests against a stub server (the `withStub` shape at `test/cli/CliTest.kt:855`, reimplemented in your
      own file — do not edit `CliTest.kt`): each method, the pane header only when resolved, `--session`
      sending a body id instead

### Task 21: CLI execution
**Owns:** `src/cli/TaskCommands.kt`, `test/cli/TaskCommandsTest.kt`

- [ ] implement every `task` / `project` command, printing **JSON only**; resolve a ref-less subcommand
      through `/whoami` but **skip that call entirely when `--session` was given**; `task next` with nothing
      eligible exits `3`; choose `start --task`'s cwd as caller cwd when it resolves to the task's project,
      else the stored path, else the caller's cwd, and say which in the JSON
- ⚠️ **`ApiClient`'s task methods are `TODO()` in your worktree** — Task 20 is filling them at the same
      time and you may not touch that file. So a test that routes through a live `ApiClient` throws
      `NotImplementedError` and cannot pass. Test through the repository's existing seam pattern instead:
      `runWebCommand` (`Commands.kt:805`) and `runImportCommand` (`:852`) are pure functions taking
      suspending lambdas plus `stdout`/`stderr` sinks, and `CliTest:559-690` drives every branch of the
      import command with fakes and no daemon at all. Add the same shape **inside your own file** — the
      thin `TaskCommands.x(...)` entry points build the client and delegate to a `runXCommand(...)` your
      tests call directly. That also solves capturing stdout, which Kotlin/Native gives you no way to do
- [ ] tests: each command prints parseable JSON; `task next` exits `3` on an empty backlog; `--session` works
      outside any pane without calling `/whoami`; a ref-less command outside a pane fails cleanly; a stale
      `projects.path` falls back to the caller's cwd

### Task 22: Router module
**Owns:** `resources/webui/lib/router.js`, `test/transport/WebUiRouterTest.kt`

- [ ] parse `location.pathname` into `{screen, id}` for `/`, `/tasks`, `/tasks/{ref}`, `/s/{id}`;
      `navigate(path)` over `history.pushState`; a `popstate` subscription; preserve the existing
      `?session=` deep link, whose live consumer is `sw.js:268`
- [ ] `node --check`; a serving test asserting the exports and the deep link

### Task 23: Task state module
**Owns:** `resources/webui/lib/tasks.js`, `test/transport/WebUiTaskStateTest.kt`

- [ ] the API calls and newest-`rev`-wins merge helpers mirroring `lib/sessions.js` — including stamping the
      frame's rev onto the stored row, without which the invariant self-destructs after the first patch; the
      removal path drops the row
- [ ] `node --check`; a serving test asserting the exports and the removal path

### Task 24: Kanban board
**Owns:** `resources/webui/components/Board.js`, `resources/webui/components/TaskCard.js`,
`test/transport/WebUiBoardTest.kt`

- [ ] four columns; a project selector (exactly one project at a time — no "all projects" mode); a "new
      project" action backed by `DirectoryCompletion`; a "new task" action posting the **selected project
      id** (the browser has no session); `done` capped at the last N with a "show all" toggle
- [ ] the card: title, blocked marker, **every** linked session with its state dot, dependency count, a menu
      carrying delete, and the phone branch — one column plus a switcher below the breakpoint, with move
      actions in the menu instead of dragging
- [ ] desktop dragging: pointer events, capture after an 8 px slop, `setPointerCapture`,
      `touch-action: none` on the **card handle only**. A drop **within** a column is one `POST …/move`; a
      drop **into another column** is one `PATCH …/{ref}` carrying the state; a drop that does both is the
      `PATCH` then the `move` — `/move` takes no state and `PATCH` takes no position
- [ ] surface rejected mutations through the existing announcement channel rather than failing silently
- [ ] use **only** the class names in "Board CSS vocabulary" above — Task 28 writes `style.css` at the same
      time as you and has no other way to learn what you emitted
- [ ] the props `app.js` passes are fixed and documented in `Board.js`'s stub header; note
      **`newTaskRequest`**, a monotonically increasing counter (`0` = never asked) that the palette's
      "new task" command bumps. Open the create form when it CHANGES, not when it is truthy
- [ ] `node --check`; serving tests for the columns, the `done` cap, the project id on create, more than one
      linked session on a card, the pointer handlers, the scoped `touch-action`, the mobile switcher

### Task 25: Task detail view
**Owns:** `resources/webui/components/TaskDetail.js`, `resources/webui/components/dialogs.js`,
`test/transport/WebUiTaskDetailTest.kt`

- [ ] editable title and body, dependency editor, the linked-session list, the activity feed (fetched with
      the task, not from the socket), delete
- [ ] "Start session" opens the **ordinary** New-session dialog pre-filled with the project cwd and the task,
      submitting the single `POST /api/v1/sessions` with `taskRef` — do not add a second launch path. The
      hook is already wired: `app.js` passes `TaskDetail` an **`onStartSession(cwd, taskRef)`** callback
      that opens that dialog, and passes `NewSessionDialog` a new **`initialTaskRef`** prop. You own
      `dialogs.js`, so making the dialog carry that prop into its submitted body is the other half of the
      same task; `app.js` POSTs the body verbatim and needs no change
- [ ] use **only** the class names in "Board CSS vocabulary" above (`task-detail*`, `task-deps`,
      `task-activity*`, plus the shared `task-blocked` / `task-sessions` / `task-session-dot`)
- [ ] `node --check`; serving tests for the feed, the session list, and the dialog reuse

### Task 26: Task badges on sessions
**Owns:** `resources/webui/lib/sessions.js`, `resources/webui/components/Sidebar.js`,
`resources/webui/components/TerminalPane.js`, `test/transport/WebUiTaskBadgeTest.kt`

- [ ] one badge-text builder in `lib/sessions.js`, rendering a ref whose task is unknown as the bare ref
      (a delete can leave one briefly); render it in `SessionRow` and the terminal header, linking to
      `/tasks/{ref}`
- [ ] both components already receive a **`tasks`** prop from `app.js` (the same flat `BacklogEntryDto[]`
      the board gets) — that is what turns a `session.taskRef` into a title. Without it the builder could
      only ever take its unknown-task branch, which is the post-delete fallback, not the normal case
- [ ] the classes are `task-badge` / `task-badge-unknown` / `task-session-dot` from "Board CSS vocabulary";
      Task 28 styles them
- [ ] `node --check`; serving tests that the builder is exported, both components use it, and an unknown ref
      still renders

### Task 27: Palette commands
**Owns:** `resources/webui/lib/commands.js`, `test/transport/WebUiTaskCommandsTest.kt`

- [ ] add the task commands to `buildCommands` (open board, new task, open this session's task) with chords
      from the currently free letters — **this file is the only registry**
- [ ] the three callbacks are already in `app.js`'s one `actions` object: **`actions.openBoard`**,
      **`actions.newTask`**, **`actions.openSessionTask`**. Call those; do not reach for `history` or the
      session list from here, which would make this file a second piece of app state as well as the registry.
      `openSessionTask` is a no-op when the active session carries no `taskRef` — disable the command on
      exactly that condition, the `disabledWhenNotAlive` shape
- [ ] `node --check`; tests that the new ids are present and no chord collides with an existing one

### Task 28: Board styles
**Owns:** `resources/webui/style.css`, `test/transport/WebUiBoardStyleTest.kt`

- [ ] all board, card and detail styles, inside the existing unconditional dark palette; phone variables stay
      inside the current `@media (max-width: 720px)` block. **This is the only task that may touch
      `style.css`** — the components above ship no CSS of their own
- [ ] style **every** class in "Board CSS vocabulary" above and nothing invented beside it. Tasks 24, 25 and
      26 are writing the markup while you write the rules and you cannot read their files, so that list is
      the entire contract between you
- [ ] serving tests asserting each class from that list is present in the served `style.css` — the only
      end of the coupling you can check; the components assert the other end

### Wave 3 — integration, contract, verification

### Task 29: Session "Done" closes the task
**Owns:** `src/daemon/SessionManager.kt`, `test/daemon/SessionDoneTaskTest.kt`

- [ ] on "Done", transition the linked task to `done` (which unlinks every holder) before archiving the
      session, calling the two stores sequentially, never nested
- [ ] state the honest guarantee in the KDoc: `NonCancellable` prevents **coroutine cancellation** between
      the two writes; it is not a transaction and does not survive process death or a throw from the second
      write. The residual — a task `done` while its session is still unarchived — is visible, benign, and
      fixed by pressing Done again. Do not call it atomic
- [ ] tests: Done on a linked session closes the task, unlinks it and archives the session; Done on an
      unlinked session behaves exactly as today; closing from the board leaves the session alive

### Task 30: End-to-end integration
**Owns:** `test/transport/TaskIntegrationTest.kt`

- [ ] the assertions wave 2 could not make, because everything there ran against fakes: a real
      `SqliteTaskStore` behind real routes behind a real server — create a task, link a session, watch the
      frame arrive on a real `/events` socket, delete it and watch the session unlink
- [ ] a `POST /sessions` carrying `taskRef` produces a linked session through the real `SessionManager`
- [ ] the two URL spaces, asserted against real route bodies (moved here from Task 17, whose worktree has
      only empty ones): `GET /api/v1/tasks` returns JSON **and** `GET /tasks` returns the shell, from the
      same server. Task 17 can only prove the negative half — that its SPA grammar does not swallow the
      prefixed path — because in its wave nothing answers it
- [ ] `grep -rn 'TODO(' src/` returns nothing from this plan's files — note the OPEN paren: Task 2's
      skeletons carry a message (`TODO("Task 7: tracker create")`), so the literal `TODO()` matches none
      of them and the check as originally written would pass over every unfilled stub

### Task 31: Agent Skill contract
**Owns:** `docs/agent-task-skill.md`

- [ ] the contract the Heapy/Kortex skill implements: read your task with `kotgent task show` (no ref
      needed), comment progress, finish with `kotgent task review -m "summary, commits"`, take the next one
      with `kotgent task next`, stop on exit code `3`
- [ ] state plainly that kotgent does **not** enforce one worker per task: `task next` will not hand the same
      task to two agents in a row, but an explicit link on a task already in progress is allowed and the
      board shows every linked session. An agent must not assume it is alone
- [ ] the daemon writes `.kotgent.json` but never commits it — the agent should mention it rather than
      sweeping it into an unrelated commit
- [ ] the JSON shapes the skill parses and the `/whoami` mechanism behind ref-less commands, pointing at the
      DTOs rather than restating them
- [ ] no code, no tests — this is the interface description the out-of-repo skill is written against

### Task 32: [Final] Acceptance and documentation
**Owns:** `CLAUDE.md`, `README.md`, this plan file


- [ ] a backlog with no `.kotgent.json` behaves as today; no file is written until a task is created
- [ ] sessions in `/repo` and an ordinary linked worktree resolve to the same project; the recorded
      unsupported git layouts degrade to the current directory
- [ ] an agent in a pane reads its own task, comments and moves it to `review` without being told its id
- [ ] the board creates a task with no session anywhere in the picture
- [ ] two sessions link one task and both appear on its card; `task next` under contention hands out two
      different tasks
- [ ] reordering, state changes, links and deletions reach a second tab without a reload; closing a
      dependency clears the blocked marker on its dependents
- [ ] a blocked task is never returned by `task next`; cycle, self-edge, dangling ref and cross-project edge
      are all refused
- [ ] closing a task from the board unlinks its sessions and leaves them alive; "Done" closes and archives;
      deleting unlinks
- [ ] `/tasks` serves the shell, `/api/v1/tasks` serves JSON, `/s/id/extra` is `404`, and a deep-linked
      `/tasks/{ref}` serves a shell whose manifest and icon links resolve
- [ ] `./kotlin build && ./kotlin test` — 0 skips, at or above the 927 post-wave-1 baseline plus the
      new tests;
      `node --check` over every changed `.js`; every new module registered in `WebUiServingTest.kt`;
      `git grep '/Users/' -- '*.yaml'` still empty
- [ ] CLAUDE.md: the two-layer split; `.kotgent.json` as the project key with its supported and unsupported
      git layouts and `projects.path` being "last seen"; **why there is no exclusivity** and what that bought;
      the conditional `todo → in_progress` being a selection convention, not an invariant; every `sessions`
      write staying in `SqliteEventStore` and why; `task_ref` being a reference, not a foreign key; the
      `/api/v1` rule and what deliberately did not move; the exact-segment SPA grammar; the `task` CLI family
      being JSON-only
- [ ] CLAUDE.md "Where things live": `src/task/`, `src/store/{SqliteTaskStore,BacklogOrdering,BacklogDependencies}.kt`,
      `src/cli/{TmuxSelf,TaskCommands}.kt`, `schema/`, the new Web UI modules; update the test baseline
- [ ] update the issue #4 body if any decision moved during implementation
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**External system updates**

- The Agent Skill itself lives in the Heapy/Kortex repository and ships through the heapy plugin. It is
  written against `docs/agent-task-skill.md` (Task 31) and is not part of this repository's build or tests.
- `schema/project.v1.json` is referenced by `$schema` at
  `https://raw.githubusercontent.com/Heapy/kotgent/main/schema/project.v1.json`, so the URL only resolves once
  the file is on `main`. Editors will show an unresolved schema until then — expected, not a defect.

**Manual verification** (needs a human at a terminal, per the automation rule)

- After Task 1: reload any open tab (it will be broken until reloaded, by design); confirm the phone QR
  dialog still mints a ticket; confirm push still resolves its session list; and check an installed PWA whose
  worker predates the change — it may need one navigation before its worker updates.
- Desktop: drag a card within a column and across columns; confirm a second tab follows without a reload.
- Phone: the single-column switcher and the card move menu; confirm no gesture conflicts with the terminal's
  swipe-scroll bridge or the dialog swipe. Confirm a deep-linked `/tasks/{ref}` can still be added to the
  home screen.
- Real agent: start a session with `--task`, have the agent comment and move the task to `review` from inside
  its pane, confirm the sidebar badge updates live.
- Worktree: create a `git worktree` of this repository, start a session in it, and confirm it lands in the
  same project as one in the main checkout. Then repeat with `git init --separate-git-dir` and confirm it
  degrades to the current directory rather than picking the metadata directory.
- Run `kotgent task next` from the operator's own tmux and confirm it refuses on the socket check rather than
  attributing the link to an unrelated kotgent pane.
