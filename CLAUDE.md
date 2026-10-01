# Kotgent development guide

Read [docs/INTENT.md](docs/INTENT.md) for product intent and [docs/TESTING.md](docs/TESTING.md) for the
testing strategy. A new implementation plan lives in Kotgent on its task. Before closing the task, move
durable decisions to authoritative `docs/` and unfinished work to the backlog. Existing `docs/plans/`
documents remain usable; do not archive completed plans.

Keep current user-facing agent behavior and accepted limitations in `docs/agents/`. The README provides
the common workflow and links to those guides; implementation invariants belong here.

## Tooling

- This is a Kotlin/Native project built with Kotlin Toolchain 0.12.2. Use the project-local `./kotlin`
  wrapper and the `/kortex:kotlin-toolchain` skill.
- Run `npm ci --prefix webui`, `npm run typecheck --prefix webui`, `npm run build --prefix webui`,
  `./kotlin build -p <host-target> -p jvm`, then `./kotlin test -p <host-target> -p jvm` in that order
  (`macosArm64` on macOS, `linuxX64` on Linux): the `webuitest` browser tier executes the `webuicheck`
  binary, and no test task builds it.
- Run `npm run typecheck --prefix webui` for changed `webui/src` modules and `webuitest/js/*.ts` tests, and
  `npm run build --prefix webui` for changed `webui/src` modules; browser behavior is tested in `webuitest`.
- Run browser-independent tests from the repository root with
  `node --test 'webuitest/js/**/*.test.ts'`; see [docs/TESTING.md](docs/TESTING.md) for the runner contract.
- Never overlap `./kotlin` invocations, including across worktrees: they share build output. Keep aggregate
  tests serial as well because integration tests share the `kotgent-test` tmux socket.
- Do not run `kotgent daemon`, `./kotlin run -m kotgent-macos` or `-m kotgent-linux`, `launchctl`, or real agent commands in
  automation. They start long-lived processes. `./kotlin test` terminates safely. The one exception is
  `codex app-server` in `CodexAdapterTest`: it runs with an empty temporary `CODEX_HOME`, starts no model
  turn, and exits when its input closes.

- The root and `webuicheck` are shared libraries; `apps/` holds per-OS executable launchers.
  Linux ARM64 is cross-compiled on x64 and tested by executing transferred test binaries on ARM64.
- Linux requires glibc 2.35+, libcurl and SQLite. The Linux build template selects Ubuntu 22.04
  multiarch glibc and GCC 11 libraries to match SQLite. Ktor bundles curl/OpenSSL static libraries.
  The spawn shim resolves glibc's close-from action dynamically because Kotlin's bundled headers
  predate it; never replace it with a racy descriptor sweep or fork-without-exec.
- Linux systemd units use `KillMode=process`: restarting/removing the daemon must preserve tmux sessions.
  The isolated `scripts/test-systemd.sh` helper is allowed in CI; it never starts the product daemon or
  a real provider. Never use the operator's `kotgent.service` for automated service checks.

## Architecture boundaries

- Keep the domain, reducers, adapter normalization, and store interfaces host-free. Put platform I/O at
  the edges and behind interfaces.
- Session state is a projection of the append-only event log. Operator control signals are not events
  and are not persisted in that log.
- `VendorStoreProbe` reports resume availability, including a shell's cwd. Use it in reconciliation,
  import and resume; let `Reconciler.classify` assign `lost`.
- `ProviderSessionId` is a bounded safe-character identifier, not necessarily a UUID. Enforce UUID shape
  only at provider boundaries where the provider guarantees one.
- Codex trusts Kotgent's hooks per handler through `hooks.state` hashes in the same `-c 'hooks={…}'`
  value. Never pass `--dangerously-bypass-hook-trust`: it also trusts every user, project and plugin hook.
- Codex's accepted startup update installs the CLI and exits 0 before any session hook; every other way
  out of the prompt continues into the session. `SessionManager.onTmuxSessionClosed` repeats the launch
  under the same session when the closed row is still alive with a state the daemon wrote itself rather
  than a hook (no `SessionEnd`, no Stop, no turn) and the probed CLI version differs from the one recorded
  at launch. That row state is the only signal: no marker file and no shell wrapper around Codex, which
  must stay the pane's session leader. Every Codex launch (start, resume, relaunch) probes and records the
  version it runs through `setCliVersion`, which advances `rev` without changing activity order.
- The daemon owns one upstream `tmux attach` per session and fans it out to subscribers. Runtime identity
  comes from the live pane id, never an inherited environment variable.
- Keep raw POSIX/cinterop in `sysnative`. Toolchain 0.12 links custom cinterop into test binaries, so
  real-PTY checks are ordinary tests under `test/pty/`; platform-independent behavior still needs an
  interface and fake.
- Every spawned child must inherit stdio and no unrelated file descriptors. Preserve the CLOEXEC handling
  in both process-launch paths. PTY master writes stay nonblocking with bounded polls: Linux does not
  reliably wake a blocked writer when its slave closes. `prepareClose` stops writes before fd ownership ends.
- `SqliteEventStore` is the only writer of `sessions`; it owns the monotonic `sessions.rev` sequence and
  live session emissions. Task data stays in `SqliteTaskStore`.
- A session `name` is operator-owned metadata, never identity or event-log state. `upsert` preserves it;
  `setName` advances `rev` without changing activity order.
- `sessions.adhd` is operator-owned ADHD-mode membership, handled exactly like `name`: `upsert` preserves
  it, `setAdhd` advances `rev` without changing activity order, and `emitFromRow` reads it off the row.
  Start and import take it in the request and write it with the row's INSERT, not a follow-up PATCH; the
  Web UI asks for it while its screen is reduced, so a new session outlives its selection.
  Folder membership lives in `folder_settings`, keyed by absolute path because folders are derived from
  each session's `cwd` and have no identity. Clearing a mark updates its column and keeps the row, which
  will carry the folder's other settings.
  `setFolderAdhd` writes that table and bumps `ui_preferences.revision` in one transaction under the same
  mutex as `savePreferences`, so clients merge one revisioned payload.
- Folder marks share `ui_preferences.revision`, which is also `PreferencesDialog`'s remount key, so never
  put folder-mark controls inside that dialog: each click would reset it. A mark from another device
  resets an open dialog too; accepted, because one operator does not edit grouping while marking elsewhere.
- `sessions.parent_session_id`, `read_only` and `prompt_path` are launch facts written only by the INSERT
  that creates the row. `upsert` keeps the stored values, so no full-row writer, the Reconciler included,
  can change or clear them. They are not event-log state.
- A session's prompt lives in a private 0600 file `~/.kotgent/prompts/<sessionId>.md`, written after the
  adapter accepts the launch options and never rewritten. `resume` and `relaunchAfterUpdate` rebuild
  `LaunchOptions` from the row: read-only applies to every incarnation, the prompt only to a `New` launch.
  An adapter rejects an option it does not support, and start answers 400.
- Claude read-only is `--permission-mode plan`: advisory, not a sandbox. Only Codex `--sandbox read-only`
  is enforced, and it also blocks network.
- `MAX_SESSION_NAME_LENGTH` and `normalizeSessionName` bound and normalize start, import and rename
  alike. Trim only there. Creation folds blank to `null` (use the tmux name); rename clears to `""`.
- New session verbs go under the `session` namespace (`kotgent session rename`), symmetric with `task`
  and `project`. The flat `stop`/`resume`/`interrupt`/`attach` verbs deliberately stay unmigrated.
- SQLDelight `.sqm` migrations are not generated by the local plugin. Existing databases require the
  guarded runtime migrations used by the owning store.
- The task backlog is a local workflow layer over tracker data. Task/session links are intentionally
  non-exclusive, and task references are durable external identifiers.
- Session `done` closes the linked task only when no other holder is left unarchived. The predicate is
  `archived`, not liveness: `stopped` and `resumable` are ordinary states of a session to come back to, so
  a holder blocks the close until somebody marks it done. It archives the acting session before counting
  holders, so two concurrent closes cannot both defer and strand the task, and it re-reads the holders
  after the close so a link made during it is released too. A non-last holder keeps its link, so `undone`
  restores a blocking holder. `task done` from CLI or board stays unconditional and is the escape hatch
  for a task whose remaining holder is never coming back.
- A session with a parent is never a task holder: `closeLinkedTask` ignores children, and marking a child
  done archives it without closing the task.

## Mutexes

- Inside a kotgent session, call `./kotlin` only as `kotgent mutex run kotlin-build -- ./kotlin …`, so the
  serial `./kotlin` rule holds across sessions and worktrees. Outside a session mutex calls are rejected.
- `SqliteMutexStore` is the only writer of `mutexes` and `mutex_revision`; the revision table keeps `rev`
  monotonic across releases and restarts. Holdings persist. Waiters live in memory and die with the
  daemon, like every long-poll; startup reconciliation releases the holdings of sessions whose pane is gone.
- A holder is a session plus a per-acquisition token, so subagents sharing one pane exclude each other.
  Acquire and release require a live session resolved from the pane or a checked `--session`; only the
  holding session releases. Force release is the operator's call: a request with no session identity.
- A waiter is leased while its long-poll is open and for 30 s after a `pending` answer. A grant to an
  unleased ticket is skipped; a leased grant becomes a holding only when a `--ticket` call claims it.
- `SessionEndListeners.fire` runs when the pane is gone: from `onTmuxSessionClosed` whether or not the
  state changed, from `terminate`, and from `Reconciler.reconcile`. Callers may hold per-session control
  locks, so a listener only enqueues work and never suspends, blocks or throws into them.
- Long-poll hang-up detection installs Ktor's `@InternalAPI` `HttpRequestCloseHandlerKey` from a
  route-scoped plugin, for keep-alive calls only. Recheck it on Ktor upgrades: CIO otherwise keeps a
  hung-up poll running, and a grant claimed for a gone caller holds the key until its session ends.
- `webui/src/state/mutexes.ts` owns the listing. Frames carry the whole listing: a snapshot replaces it,
  an update applies only with a higher `rev`. Durations age a stamp against the frame's `serverNow`, then
  add local monotonic time, as usage freshness does. Force release goes through the in-app dialog and
  re-checks the confirmed holder, because the route releases whoever holds the key.

## Structured plans

- `SqlitePlanStore` is the sole writer of `plans`, `plan_blocks`, `plan_view_marks`, `plan_threads`,
  `plan_edits`, `plan_rounds` and `plan_execution`. One plan belongs to one task reference. Persist content and review state
  in one transaction before publishing a revision. Keep deleted block ids in `plan_blocks`: stale writes
  must report deletions and generated ids must never reuse them.
- Every visible change advances the plan revision; a block revision advances only on authored content.
  Viewed marks name a block revision, and an operator edit journals before/after text in the same write.
- Review rounds survive restarts and repeated waits. Submitting names the current open round; a stale
  browser cannot submit a newer round. A changed authored document after a verdict returns to draft.
  `afterRound` explicitly acknowledges a completed round when answers alone need another review; retrying
  that acknowledgement reuses its successor, even after further content edits.
- Open rounds supply durable `plan.review:<taskRef>` notification levels without altering session attention.
  Opening a round queues one best-effort payload-less wake; continuing never queues another.
- `plan_changed` carries revision hints, including deletion. Each events connection compares every key
  after a conflated change, so one plan cannot hide another. Open viewers reread on connection recovery.
- Task deletion and plan creation share the plan coordinator's lock. Check task existence while holding
  it, and delete the plan through `deleteTask` when removing the task.
- `state/plans.ts` owns browser plan documents. Merge only higher revisions, reread observed plans on
  event recovery, and never let a late missing response erase a newer mutation. Inline editors freeze
  their block revision and preserve drafts through conflicts; retry requires accepting the latest base.
- Plan shortcuts `v` and `n` belong to the focused plan panel, excluding forms. A task's Plan action in
  a session opens or reuses a Terminal · Plan tab; the board uses `/tasks/<ref>/plan`.

- Execution mutations and authored puts share the plan coordinator lock. A put preserves runtime state,
  settings and findings, and cannot delete started tasks or change their dependencies. A live
  orchestrator owns start/merge/completion; only the assigned child worker finishes tasks and steps.
- Persist task transitions and execution events in one transaction. Waits do not consume events: each
  caller advances a cursor only after handling its returned batch. Retain events until task deletion.
  Session-end callbacks enqueue worker-loss reconciliation; startup also checks workers lost offline.
- Finding mutations carry expected finding revisions. An amendment retains previous details and clears
  verification and decision; notes and investigator linkage also advance the finding revision. Review
  mode is fixed for an iteration; settings changes update its next mode. Supervised decisions belong to
  the operator, autonomous decisions to the assigned worker with a note. All findings need independent
  verification; subagents sharing one pane may share session attribution.
- Investigator launches are operator-only, Claude-only, read-only children of the current live
  orchestrator, linked to its task in the worker worktree. Claude plan mode is advisory. Keep the launch
  and finding link under the investigator lock and protect that short sequence from client cancellation.
  The reserved `kotgent-plan-investigator` session tag lets startup reap a launch whose finding link was
  lost in a crash. Do not use that tag for ordinary sessions.
- The investigator monitor rereads durable state on plan/session changes and retries cleanup failures.
  Decisions, superseded reviews, missing plans and dead parents retire investigators with `markDone`;
  children never close the parent task. Retain the finding's investigator ID as durable cleanup evidence.
- Finding decision drafts freeze their expected revision and survive conflict refreshes. `state/layout.ts`
  owns transient per-session finding focus; opening an investigator reuses Terminal · Plan and focuses
  the requested finding after its document loads.

## Usage and notifications

- `SqliteUsageStore` owns the account projection keyed by provider/window, source baselines, sample
  history, and reset journal. Commit them together before publishing. Keep this data out of `sessions`.
- Capture existing provider output without polling: Claude status-line renders and Codex end-of-turn
  rollout records. Preserve native window keys, omit unavailable percentages, and normalize times to
  epoch milliseconds and durations to seconds at ingress.
- Source revision and capture time reject reordered input before assigning monotonic per-key
  `observedAt`, which is a merge revision. Separate `receivedAt` records actual daemon receipt time for
  heartbeat gating, display and reset timing; neither timestamp establishes provider fetch freshness.
  Matching Claude heartbeats may refresh receipt time without adding samples; unchanged cached values
  must never undo another source's projection or advance the account evidence watermark.
- Claude reset evidence requires both an account decrease and a decrease within a known source whose
  baseline belongs to the current reset generation. Codex requires a percent decrease and two known,
  different reset timestamps. Early means more than five minutes before the previous window end.
  Notify only for early weekly resets: Claude `seven_day`, or Codex duration 604800 seconds in either slot.
- Every reset is journaled. Eligible pending inbox projection is durable and idempotent by reset id.
  `UsageResetNotifier` starts independently of push, subscribes and projects before HTTP binds, and
  enables wakes only after binding. Its fast collector never waits for SQLite projection or network
  delivery. Projection makes at most three attempts per signal. Runtime exhaustion schedules another
  local inbox attempt within a minute, independently of provider activity; rows older than the one-hour
  notification window still expire. Startup exhaustion reports failure and exits normally with code 1.
  Live usage flows may drop old hints; pending work and snapshots remain in SQLite. Never suspend a
  writer on a slow collector. Join background work before closing SQLite.
- Inbox acknowledgement means the notification row is durable, not that a push was delivered. Wakes are
  best effort and may coalesce because each fetch reads the whole inbox. A failed bind or shutdown after
  acknowledgement can lose a queued wake without losing the inbox row.
- `/api/v1/notifications` merges the last hour of durable `usage.reset` items with all current,
  non-archived `session.attention` levels. There is no notification read state. Keep push payload-less,
  with namespaced topic keys `usage.reset:<id>` and `session.attention:<id>`.
- Retention runs at startup and daily: usage history/source baselines for 90 days, inbox rows for one
  day. A failed prune is reported without preventing startup or cancelling future maintenance. Keep the
  current usage projection. Age cutoffs on inbox reads apply even before pruning runs.
- Authentication owns the private Claude header file and its rotation. Per-launch settings generation
  only reads and chains the user-scope status command; it must not rewrite the token header. Preserve
  the operator command's input, output and exit status independently of background capture. Changes to
  that command apply on the next launch; project/local status commands are not chained.
- Claude render ordering and throttling use monotonic time with a boot identity; capture time stays in
  epoch milliseconds. Capture state is pruned after 90 days and abandoned staging files after one day,
  under one permanent directory lock. Do not unlink legacy lock inodes a running old script may hold.

## Transport and Web UI

- Client APIs live under `/api/v1`.
- Authorization decisions belong in the shared authorization function. Never put the master token in a
  URL; browser access uses one-time tickets and the stateless session cookie.
- Drain `incoming` in every WebSocket handler, including handlers that only send frames.
- Service-worker sources may import shared modules; the built `/sw.js` remains a classic, root-scoped,
  network-only IIFE with no offline shell. Root shell and worker responses must revalidate.
- Content-hashed files under `assets/` are cached as immutable: changed bytes have a different URL.
  Precompressed `.br`/`.gz` siblings are chosen by `Accept-Encoding`; compressed and identity responses
  carry `Vary: Accept-Encoding`. If no representation including identity is acceptable, return 406 with
  `Vary` and no `immutable`; direct sibling URLs are 404. Watch builds do not precompress. Source maps
  stay uncompressed and revalidate because their names follow the chunk, not their own bytes; all other
  static files revalidate too.
- On `vite:preloadError`, refetch the shell and reload only when its entry module differs from the running
  one. A `sessionStorage` slot holding the last `running → served` reload refuses the same pair again;
  previous builds' assets are deliberately not kept. An old shell requesting its entry after the directory
  swap is not covered, because the event fires only for lazy imports.
- `webui/src/lib/router.ts` is the only owner of browser history. `app.tsx` owns global shortcuts
  and screen selection; avoid parallel sources of truth in components.
- Keep terminal reattachment decisions in `webui/src/lib/reattach.ts`; `app.tsx` supplies current
  environment and performs declared effects. Preserve the distinction between hidden and cancelled, do
  not spend a grant before a candidate exists, and keep probe guards ordered. A hidden page holds a
  resolved probe; among pending mutations only the control actions `affectsAttachment` names do. A
  candidate whose row is not alive is retired: stop cancels before the POST, and the pane dies before the
  answer, so the close lands after the cancel.
- `TerminalPane` owns one `#terminal-host` element, one xterm and one upstream attach for the attached
  session; xterm keeps `scrollback: 0` because history lives in tmux. A workspace `TerminalSlot` claims the
  host by moving that element; with no slot mounted it is parked hidden and keeps its socket and buffer.
  A parked host never fits and never sends a resize frame; showing it fits and reports exactly once, and a
  fit does not report again through xterm's `onResize`. The workspace never opens, closes or reattaches a
  terminal socket: those decisions stay with `lib/reattach.ts` and `attachedId`.
- Session workspaces (tabs of columns) are device-local. `webui/src/state/layout.ts` is their sole owner
  under one `localStorage` key; `webui/src/lib/workspace.ts` holds the rules, and every operation returns
  its input unchanged when it does nothing. A type is unique within a tab, and choosing a held type swaps
  the two columns. Layouts are pruned only after a full `sessions_snapshot` and capped at 200 by
  `touchedAt`. `AVAILABLE_COLUMN_TYPES` gates what renders: a stored type this build cannot show (`files`,
  `diff` until implemented) stays stored and hidden, and width hiding never rewrites the layout.
- The session header is one 48px row. Workspace tabs share it on desktop; phones use a tab picker.
  Configure columns owns all column type/add/close controls, leaving panels without header rows.
  The phone panel switcher appears only when some columns are hidden. Session details hold cwd,
  agent/model, task and mutex links; attention and mutex indicators remain visible in the header.
- `useVisiblePane` caps the entire terminal-pane flex stack to `visualViewport`, including workspace
  panels and the key bar. Capping only the nested xterm host leaves the footer below the keyboard.
  Ignore transient zero geometry, account for viewport offset, and clear the cap on keyboard dismissal.
  The app reserves safe areas once, delegating the bottom inset to the phone key bar when present.
  Extra terminal keys must preserve the xterm textarea's touch focus.
- `TaskDetail` takes `onClose`: the board passes its way back to `/tasks`, a workspace column passes null
  and renders embedded. The board's floating-overlay CSS is scoped to `#app:has(.board)`, so it never
  applies to the column.
- Use `webui/src/lib/refresh.ts` for unversioned sources such as projects. Reads are serial and a
  response overtaken by a later request is discarded. Each read owns its readiness token. A port that
  throws must still answer its waiters; a failing `read` or `succeed` is a failed read and never stops
  the pump.
- Event-stream recovery goes through `webui/src/lib/resync.ts`: one coordinator per source batches
  requests, serializes resynchronization and owns retries. Beside it, `resume.ts` emits requests; `events.ts`
  applies frames through state writers and completes after every snapshot. Retired socket callbacks
  cannot publish. Wall-clock discontinuities request server time; they never supply usage time or freshness.
- Session, task, project, usage, selection, dialog, status, and preference state lives in signals under
  `webui/src/state/`, one owner per concern; callers must use that module's writers.
- State modules import `@preact/signals-core` directly and must share one reactive graph with
  `@preact/signals`. The `overrides` pin in `webui/package.json` and
  `webuitest/js/module-graph.test.ts` guard that single-copy invariant.
- Reading `.value` in a render body is what subscribes a component to a signal. `app.tsx`'s bare
  `import "@preact/signals"` installs the required Preact hooks and is load-bearing.
- Only an application-level singleton may hold a bare `computed()`. Anything a component can mount more
  than once must derive with `useComputed`/`useSignal` from `@preact/signals`.
- Library versions use exact pins in `webui/package.json`, with resolutions in `webui/package-lock.json`.
- Preserve revision-based newest-wins merging for HTTP responses and WebSocket frames. Arrival timing is
  not an ordering guarantee.
- Usage has dedicated `usage_snapshot`/`usage_update` frames and no REST usage read. Subscribe before
  the authoritative snapshot, reread all windows on coalesced hints, and send only newer revisions.
  A dropped hint for one window must recover on another window's surviving hint. Merge strictly by
  `observedAt`. The sidebar ages `receivedAt` against the frame's `serverNow`, then
  uses local monotonic elapsed time. Phone wall-clock skew must not alter freshness. Its expiry timer
  does not poll a provider.
- `SessionUpdateDto` carries `name` and `adhd`, whose absent-means-keep contract is the opposite of
  `model`'s authoritative clear. Keep the one rationale on those DTO fields rather than duplicating it at
  merge sites or repeating it per field.
- `PatchSessionRequest` carries nullable `name` and `adhd` and answers 400 only for a body carrying
  neither, rather than requiring a field: the shape stays open for the next operator-owned column
  (`tags`). `TRANSPORT_JSON` encodes defaults, so the CLI's rename body sends `"adhd":null`; an explicit
  null must read as absent, never as a clear.
- `runMutation` owns the global mutation lock and holds it through a flow's follow-up read. It publishes
  the holder name but no currency token: late announcements use `announcementHolds`, conditional
  auto-selection uses the selection generation, and component lifetime uses `aliveRef`.
- `webui/src/lib/readiness.ts` answers `idle | loading | ready | failed` with `retry()`, and is
  sticky at `ready`; a failed revalidation keeps usable rows, while an initial failure stays visible.
- One typeahead-listbox primitive answers the command palette, both directory-path pickers and the
  session/task link picker. Keep rules framework-free in `lib/typeahead.ts`, bind them in
  `components/Typeahead.tsx`, compare rows by key, and share path-picker behavior through
  `components/PathSuggestions.tsx`.
- Fold case for matching with `toLowerCase()`, never `toLocaleLowerCase()`. A tr/az browser folds an
  uppercase `I` differently. Keep `webuitest/js/turkish-fold.ts` coverage for every matching rule.
- In TSX use `spellcheck={false}`, `autoCorrect="off"`, and `autocapitalize="off"` without casts; served-DOM
  tests require `spellcheck="false"`, `autocorrect="off"`, and `autocapitalize="off"`.
  `spellCheck={false}` fails Preact's property test and sets nothing;
  `spellcheck="false"` reaches the boolean IDL setter, which coerces any non-empty string to on. No DOM
  property carries `autoCorrect`, so Preact sets a plain attribute in every engine, while lowercase
  `autocorrect="off"` hits Safari's boolean IDL and turns autocorrect on.
- ADHD-mode membership is decided when the list renders: a session is listed when its own flag is set or
  a folder head drawn above it is marked. Never fan out a folder mark to the sessions under it. The heads
  come from `headChain` in `webui/src/lib/paths.ts`, which shares one head rule with `groupSessions`,
  and the rules live in `webui/src/lib/adhd.ts`. With grouping off, folder marks cover nothing. A
  mark with no drawn head is inert and is never deleted by a grouping change; it acts again once its head
  is drawn. Marks are compared through `normalizePath`; a second path matcher would drift from the
  daemon's `normalizePreferencePath`. Whether a screen is reduced is device-local. A reduced screen hides
  the attention section and its count altogether, pinned sessions included; the selected session always
  keeps a row — `activeId` is the selection, not `attachedId` — and `#adhd-toggle` never hides on the
  sessions screen.
  `#empty-adhd` is gated on nothing being pinned, never on an empty list: the selected row always
  survives, so an empty list is not the state that needs explaining. It also waits for `prefsReadiness`,
  because until the daemon answers `adhdPaths` is a placeholder. A session listed through a folder mark
  shows a `covered` pin naming that folder; a click gives it a mark of its own. An empty pin takes no
  pointer events until its row is hovered or focused, or a first tap on a hybrid device would mark.
  In Chromium on a touchscreen laptop or Chromebook a tap sets `:hover` before its click, so the first tap
  can still mark there; that is accepted.
  The Done section is never reduced: `doneGroups` and `flatDoneSessions` key on `doneSignature` and
  deliberately exclude `doneSessions`, and `#show-done-toggle` hides when its list is empty.
- A child session nests under its parent only while that parent is drawn in the same list; the rules live
  in `webui/src/lib/tree.ts`. Folder grouping and folder marks see a child only through its topmost known
  ancestor's `cwd` (`treeCwd`), never its own worktree. In ADHD mode a session is listed on its own flag,
  through a listed live parent, or because it is selected. A listed child of a hidden parent is drawn at
  the top level naming that parent; one whose parent is archived or gone says "orchestrator finished".
  The attention section takes only top-level rows, so a nested child's attention shows as its collapsed
  parent's aggregated badge. Done keeps the tree among archived rows. Which trees are expanded is
  device-local, collapsed by default, and owned by `webui/src/state/tree.ts`.
- `SessionRow` selects only on keys aimed at the row itself. Enter on an inner button or link bubbles to
  it, and cancelling that event would select the row instead of activating the control.
- The routine first-snapshot session count is not announced while the board is on screen: it shares one
  aria-live region with the board's own results and lands whenever the socket connects. Read the existing
  module-scope `sessionViewOnScreen` rather than adding another mirror of the same fact.
- The Web UI is dark-only. Mobile terminal, dialog, pointer, safe-area, and push-permission behavior has
  real-device constraints that Chromium cannot fully prove; keep those checks in `docs/TESTING.md`.
- A board drag must not reflow. Every preview movement is a `transform`, the dragged card keeps its slot
  as an in-flow placeholder, and the lifted copy is fixed. Use transform-free layout geometry and paint
  order; commit the previewed target on release.
- End board drags through the shared idempotent abort path, including pointer loss, removal, unmount, and
  sidebar layout changes.
