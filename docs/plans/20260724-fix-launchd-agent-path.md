# Fix: launchd daemon can't find agent binaries → new session attach fails with 1006

## Overview
Under launchd the daemon runs with a **minimal, hardcoded PATH** and cannot find the user's
`claude`/`codex` binaries, so every new session's agent dies at spawn, the tmux server tears down, and
the browser attach fails with `Connection Closed: 1006`. This plan makes the launchd install **snapshot
the user's real PATH** into the plist and adds **fail-fast** so an unresolvable agent produces a clear,
actionable error instead of a phantom `running` session.

Benefits: new sessions launch and attach correctly under the released (Homebrew + launchd) install;
agents inherit the same environment the user has in a terminal; misconfiguration surfaces as a readable
message pointing at `kotgent install` rather than a silent `1006`.

## Context (from discovery)

### Root cause (confirmed on the machine)
- launchd starts the daemon with a minimal env; the plist PATH is hardcoded in
  `DAEMON_DEFAULT_PATH = "/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"` (`src/launchd/Plist.kt:15`).
- The user's agents live **outside** it: `claude` → `/Users/yoda/.local/bin/claude` (native installer,
  `~/.local/share/claude/versions/…`); `codex` → an nvm dir (`…/nvm/versions/node/vXX/bin`).
- Chain: `Commands.kt:298` does `val claudePath = claudeCli.locate() ?: CLAUDE_AGENT_KIND`. `locate()`
  runs `command -v claude` through `/bin/sh` under the **same** launchd PATH → `null` → falls back to the
  bare name `"claude"`. `SessionManager.start()` → `tmux.newSession(…, shellCommand(spec.command), …)`
  runs `claude …` via `/bin/sh` → `command not found` (127) → the pane's process exits → the tmux window
  closes → the (now empty) tmux server exits. On attach, `TerminalBridge`→`capture-pane` finds
  `no server running` → the WS handler throws `TmuxException` → the socket closes → the browser shows
  `1006`.
- Evidence: `tmux -L kotgent ls` → `no server running`; DB row `e34ac408` has `last_seq=0` (zero events),
  empty `provider_session_id`, `created_at == updated_at`; `daemon.out.log` repeats
  `tmux capture-pane for 'e34ac408' failed: error connecting to …/kotgent (No such file or directory)`.
- Why it worked before: earlier the daemon was started from an interactive shell (full PATH). The
  regression appeared with the 0.1.0 Homebrew + launchd install.

### Files/components involved
- `src/launchd/Plist.kt` — `DAEMON_DEFAULT_PATH`, `launchAgentPlist(...)` (pure plist generator).
- `src/launchd/Install.kt` — `LaunchdInstaller` (constructor seams: runner/launchAgentsDir/logDir/label/uid;
  `install()` at line 80 calls `launchAgentPlist` at line 83).
- `src/cli/Commands.kt` — daemon startup: `claudePath`/`codexPath` resolution (298-299) + `agentFactoryOf`
  wiring (303-323).
- `src/daemon/SessionManager.kt` — exception types (~106-129), `agentFactoryOf`/`create()` (69-71),
  `start()` (223-295); `create()` is called at 234, **before** `withControlLock` and any tmux side-effect.
- `src/transport/ControlRoutes.kt` — start handler (~80-93) and action/resume handler (~146-165); both
  already map `UnsupportedAgentException`/`TmuxException` → `BadRequest` (400).

### Key facts
- `kotgent install` is a **manual CLI command** (`Cli.kt:124` → `Commands.install()` →
  `LaunchdInstaller().install()`); the Homebrew formula does **not** call it. So `install` runs in the
  user's shell where `getenv("PATH")` is the full login PATH — the snapshot is legitimate there.
- The nvm dir must be on PATH not only to find `codex` but because `codex` is a node CLI with a
  `#!/usr/bin/env node` shebang (needs `node` on PATH at exec).
- `locate()` runs under the same PATH as tmux's child shell, so `locate() == null` ⟺ the launch would
  fail anyway — a reliable pre-check, safe to fail-fast on. **Asymmetry (known limitation):** this is a
  complete guarantee for `claude`, but only partial for `codex` — `locate()` proving `codex` resolves does
  not prove `node` (its `#!/usr/bin/env node` shebang) resolves, so a codex with `node` off the daemon PATH
  could pass the pre-check yet still die at exec. The A1 full-PATH snapshot closes this in the fixed state;
  don't over-trust the pre-check for node-based providers.

### Related patterns to mirror
- `src/tmux/Tmux.kt:defaultTmuxPath()` — precedent for resolving a tool to an absolute path so the tmux
  attach (posix_spawn, no PATH search) works under launchd's minimal env.
- `SessionManagerTest.kt:846 agentFactoryRejectsUnsupportedAgentsBeforeAnyTmuxSideEffect` — the exact test
  shape to mirror for the not-found case (direct `create()` throw + `start()` leaves no tmux side-effect).
- `InstallTest.kt:141` — constructs `LaunchdInstaller` with injected seams under a temp dir.

## Development Approach
- **testing approach: TDD (tests first)** — within each task, write the failing test(s) first, then the
  code until green.
- complete each task fully before moving to the next; small, focused changes.
- **every task includes new/updated tests** (success + error/edge cases) as separate checklist items.
- **all tests pass before starting the next task.**
- **Run `./kotlin build` BEFORE `./kotlin test`** (from CLAUDE.md: `PtyTest` execs the `ptycheck` binary
  and `./kotlin test` never links a main binary itself).
- **In automation do NOT** run the daemon, a real agent, or `launchctl` (CLAUDE.md). All new tests use
  fakes / injected seams / temp dirs.
- maintain backward compatibility (default args keep current behavior for callers that don't pass PATH).

## Testing Strategy
- **unit tests**: required for every task (pure logic + behavior via fakes).
- **e2e tests**: none — this is a native CLI/daemon with no UI e2e harness. The end-to-end verification
  (create a session under launchd, attach in the browser) is a manual Post-Completion step (must not run
  in automation).
- Baseline is **361 run / 361 passed / 0 skipped**; it moves up with the new tests. Keep 0 skips.

## Progress Tracking
- mark completed items `[x]` immediately when done.
- add newly discovered tasks with ➕ prefix; blockers with ⚠️ prefix.
- keep this plan in sync with actual work; update if scope changes.

## Solution Overview
**Approach A (install-time PATH snapshot) + fail-fast**, chosen over runtime self-healing (approach B:
running a login shell each daemon start) — B adds a subprocess and rc-file fragility (the classic VS Code
shell-env pain) for self-healing we don't need when fail-fast makes staleness obvious.

- **Part 1 — snapshot (variant A1: full PATH).** `kotgent install` captures the caller's full
  `getenv("PATH")`, merges it with `DAEMON_DEFAULT_PATH` (dedup, captured entries first), and writes the
  result into the plist's `EnvironmentVariables.PATH`. A1 (full PATH, not just tool dirs) is chosen so
  kotgent-launched agents see the same environment as a terminal (find `~/go/bin`, `~/.cargo/bin`, etc.),
  and the nvm dir on PATH also satisfies codex's `env node` shebang. `DAEMON_DEFAULT_PATH` stays as the
  fallback when the captured PATH is null/empty.
- **Part 2 — fail-fast.** Daemon startup keeps the resolved agent path **nullable** (drop the bare-name
  fallback). A null path means "not resolvable on the daemon's PATH"; the factory builder throws a new
  `AgentBinaryNotFoundException` from `create()` — before any tmux side-effect — carrying a hint to run
  `kotgent install`. `ControlRoutes` maps it to 400.

## Technical Details
- `mergedDaemonPath(captured: String?): String` (pure, in `Plist.kt`):
  - split `captured` on `:`, drop empty segments; append `DAEMON_DEFAULT_PATH` segments; dedup preserving
    first-seen order (captured entries win position); rejoin with `:`.
  - `captured` null or (after cleaning) empty → return `DAEMON_DEFAULT_PATH` unchanged.
- `LaunchdInstaller` gains a seam `pathProvider: () -> String? = ::currentPath`, where `currentPath()` is a
  new top-level `@OptIn(ExperimentalForeignApi::class) fun currentPath(): String? =
  getenv("PATH")?.toKString()?.ifEmpty { null }` — mirroring the existing top-level `currentUid()` /
  `defaultLogDir()` opt-in helpers so the `getenv` opt-in stays off the class surface (an inline lambda
  default calling `getenv` would need a class/constructor-level opt-in it doesn't have). `install()` passes
  `path = mergedDaemonPath(pathProvider())` to `launchAgentPlist(...)`.
- `AgentBinaryNotFoundException(val agentKind: String) : IllegalStateException(<hint>)` (in
  `SessionManager.kt`, beside the others; base picked like the siblings —
  `UnsupportedAgentException : IllegalArgumentException`, `ResumeBlockedException : IllegalStateException` —
  and deliberately **not** a subtype of `UnsupportedAgentException`/`TmuxException`, so the existing
  `ControlRoutes` catches don't swallow it before Task 4's new catch). Message: ``agent '<kind>' not found
  on the daemon's PATH — run `kotgent install` from a shell where `<kind>` resolves, then create the
  session again``.
- `Commands.kt`: `val claudePath: String? = claudeCli.locate()`, `val codexPath: String? = CodexCli().locate()`;
  each `agentFactoryOf` builder throws `AgentBinaryNotFoundException(kind)` when its path is `null`,
  otherwise builds the adapter with `binaryName = <resolvedPath>`.
- `ControlRoutes.kt`: add `catch (e: AgentBinaryNotFoundException)` to the start handler and the
  action/resume handler → `BadRequest`, mirroring each handler's existing body prefix
  (`"cannot start session: ${e.message}"` in start; `"action '$action' failed: ${e.message}"` in action).

## What Goes Where
- **Implementation Steps** (checkboxes): all code + tests in this repo.
- **Post-Completion** (no checkboxes): the real `kotgent install` + browser attach verification, and
  cleanup of the manual stopgap plist edit — external/manual, must not run in automation.

## Implementation Steps

### Task 1: Pure PATH-merge function `mergedDaemonPath`

**Files:**
- Modify: `src/launchd/Plist.kt`
- Modify: `test/launchd/PlistTest.kt`

- [x] write test: `null` captured → returns `DAEMON_DEFAULT_PATH` exactly
- [x] write test: captured with only empty/blank segments → returns `DAEMON_DEFAULT_PATH`
- [x] write test: captured with new dirs (`/Users/x/.local/bin`, an nvm dir) → all present, captured
      entries first, `DAEMON_DEFAULT_PATH` entries appended, order preserved
- [x] write test: dedup — a captured PATH that already contains the default entries yields no duplicates
- [x] write test: empty segments in captured (`a::b`, leading/trailing `:`) are dropped
- [x] implement `mergedDaemonPath(captured: String?): String` in `Plist.kt` (pure, no I/O)
- [x] fix the `DAEMON_DEFAULT_PATH` KDoc — it is now the **fallback minimum** of system bins, not "where
      tmux/claude live"
- [x] run `./kotlin build && ./kotlin test` — must pass before Task 2

### Task 2: `LaunchdInstaller` snapshots PATH into the plist

**Files:**
- Modify: `src/launchd/Install.kt`
- Modify: `test/launchd/InstallTest.kt`

- [x] write test: with an injected `pathProvider` returning a PATH containing a custom dir, the written
      plist's `EnvironmentVariables.PATH` equals `mergedDaemonPath(<that PATH>)` (assert the custom dir is
      present and the defaults are retained)
- [x] write test: with `pathProvider` returning `null`, the plist PATH equals `DAEMON_DEFAULT_PATH`
      (backward-compatible fallback)
- [x] add a top-level `@OptIn(ExperimentalForeignApi::class) fun currentPath(): String? =
      getenv("PATH")?.toKString()?.ifEmpty { null }` (mirrors `currentUid`/`defaultLogDir`) and the
      constructor seam `pathProvider: () -> String? = ::currentPath` on `LaunchdInstaller`
- [x] change `install()` to pass `path = mergedDaemonPath(pathProvider())` into `launchAgentPlist(...)`
- [x] run `./kotlin build && ./kotlin test` — must pass before Task 3

### Task 3: `AgentBinaryNotFoundException` + fail-fast in the agent factory

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `test/daemon/SessionManagerTest.kt`

- [x] write test (mirror `agentFactoryRejectsUnsupportedAgentsBeforeAnyTmuxSideEffect`): a factory whose
      `claude` builder throws `AgentBinaryNotFoundException` → `factory.create("claude", cwd)` throws it
      directly
- [x] write test: `SessionManager.start("claude", …)` with that factory throws
      `AgentBinaryNotFoundException` and leaves **no** tmux side-effect (`tmux.newSessionCommands` empty)
      and **no** persisted row (no phantom `running`)
- [x] write test: `SessionManager.resume(...)` with a throwing builder likewise propagates
      `AgentBinaryNotFoundException` before any tmux side-effect (matches the resume-level rigor of the
      existing `UnsupportedAgentException` tests — `SessionManager.kt:382` also calls `create()`)
- [x] write test: the exception message contains the kind and the `kotgent install` hint
- [x] add `class AgentBinaryNotFoundException(val agentKind: String) : …` beside the other exceptions in
      `SessionManager.kt`, with the hint message
- [x] in `Commands.kt`: make `claudePath`/`codexPath` nullable (`= claudeCli.locate()` /
      `= CodexCli().locate()`, drop the `?: KIND` fallback); each `agentFactoryOf` builder throws
      `AgentBinaryNotFoundException(kind)` when its path is null, else builds with `binaryName = <path>`
- [x] rewrite the now-stale comment at `Commands.kt:295-297` (it still describes the removed bare-name
      fallback) to describe the fail-fast behavior
- [x] run `./kotlin build && ./kotlin test` — must pass before Task 4

### Task 4: `ControlRoutes` maps `AgentBinaryNotFoundException` → 400

**Files:**
- Modify: `src/transport/ControlRoutes.kt`
- Modify: `test/transport/TransportTest.kt`

- [ ] write test: a start request whose `SessionManager` throws `AgentBinaryNotFoundException` → HTTP 400
      with the hint text in the body
- [ ] write test: the same via the action/resume handler → HTTP 400 with the hint text
- [ ] add `catch (e: AgentBinaryNotFoundException)` to the start handler and the action/resume handler,
      `respondText(e.message ?: "…", status = HttpStatusCode.BadRequest)` (consistent with
      `UnsupportedAgentException`)
- [ ] run `./kotlin build && ./kotlin test` — must pass before Task 5

### Task 5: Verify acceptance criteria
- [ ] `mergedDaemonPath` correctly merges/dedups and falls back (Task 1 green)
- [ ] a fresh install writes a plist whose PATH includes the caller's PATH (Task 2 green)
- [ ] an unresolvable agent fails fast with a clear 400 + `kotgent install` hint and **no** phantom
      `running` row / orphan pane (Tasks 3-4 green)
- [ ] run the full suite: `./kotlin build && ./kotlin test` — 0 failures, **0 skips**, count ≥ prior 361
- [ ] no machine-specific absolute path leaked into any `*.yaml` (`git grep '/Users/' -- '*.yaml'` empty)

### Task 6: Update documentation
- [ ] update `CLAUDE.md` if warranted (note: the daemon's launchd PATH is snapshotted from the user's
      shell at `kotgent install`; agents inherit it — under the transport/launchd sections)
- [ ] update `README.md` only if it documents install/PATH behavior
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Manual / external — no checkboxes, informational only. Do not run these in automation.*

**Manual verification**
- Run `kotgent install` from an interactive shell, then confirm the written plist's
  `EnvironmentVariables.PATH` contains `~/.local/bin` and the nvm dir.
- Reload the daemon, create a new `claude` session in the browser, and confirm attach works (no `1006`).
  Repeat for a `codex` session (exercises the `env node` shebang path).
- Temporarily rename/hide `claude` and confirm a create attempt returns the 400 hint (fail-fast) rather
  than a silent `1006`.

**Stopgap cleanup**
- The current `~/Library/LaunchAgents/io.kotgent.daemon.plist` was hand-edited as a stopgap; the first
  real `kotgent install` after this ships overwrites it with the correctly-computed PATH.

**Out of scope (tracked separately)**
- The `EADDRINUSE` crash-loop in `daemon.err.log` (a listening socket inherited by a tmux server) is a
  distinct, already-known issue — not addressed here.
- The stale `running` row `e34ac408` needs no migration; the reconciler reclassifies it on daemon restart.
