# Isolate the kotgent tmux server from `~/.tmux.conf` and force our own options

> **Revision 2** — rewritten after a plan review plus a second round of probing. Four blocking defects
> in revision 1 were confirmed and fixed here: an unfalsifiable isolation test, an unimplementable test
> step, an incomplete `ptycheck` inventory, and a verification grep that could not see the sites it was
> meant to check. The `mouse on` rationale was also re-derived from measurements — the original one was
> wrong in both directions.

## Overview

The daemon's tmux server (`-L kotgent`) **reads the user's `~/.tmux.conf`**. The `-L` socket label
isolates the *socket*, not the *configuration*: tmux parses the system and user config whenever a
command starts a server, whatever the socket is called. Verified on the throwaway `kotgent-test`
socket — the operator's existing `~/.tmux.conf` (`mouse on`, `focus-events on`) was picked up by a
server on a socket it has nothing to do with.

Consequences, worst first:

1. **`set -g destroy-unattached on` in a user's config kills the agent on Detach.** kotgent holds *one*
   upstream `tmux attach` per session and closes it on the last subscriber — that last-detach is the
   "Detach", and the agent is supposed to live on inside tmux. With that option inherited, tmux destroys
   the session the moment kotgent's upstream goes away: a core invariant broken by a file kotgent never
   reads. **This one is closed by isolation alone** — tmux's own default is already `off` (measured), so
   `-f /dev/null` restores correct behaviour without setting anything.
2. **The agent's pane inherits arbitrary third-party behaviour** — plugins (`run-shell`), `bind-key`
   tables, `status-format`, hooks, `default-terminal`. The pane an agent renders into should be a
   function of kotgent's version, not of the operator's dotfiles. Also closed by isolation alone.
3. **Pane ergonomics are left to chance** — wheel scrollback, status bar, history depth and `ESC`
   latency are all decided today by whatever the operator happens to have configured. This is what the
   forced option set addresses.

The fix has the same shape as the existing `LANG` rule: **force, never inherit.** Every tmux invocation
gets `-f /dev/null` (measured: suppresses the user config completely — `mouse` fell back to the built-in
`off` despite `mouse on` in `~/.tmux.conf`), and kotgent's own options are set explicitly, in code.

**`focus-events` is deliberately NOT enabled.** Focus tracking is meaningless under kotgent's fan-out:
one upstream client serves N subscribers (browser, IDE, CLI), so "is the terminal focused" has no single
answer. kotgent already has strictly better signals — `TerminalBridge` knows its subscriber count
(lazy open/close), and agent state arrives over hooks. It doubles as the decoy in the isolation test
(Task 2), precisely *because* kotgent never sets it.

## Context (from discovery)

- **Files/components involved:**
  - `src/tmux/Tmux.kt` — `private fun tmux(vararg args)` (line 57) is the single argv assembly point for
    every control-plane call; `newSession` (line 86) is where a server is actually born;
    `ensureServer()` (line 76) is the production first-start, called from `Commands.kt:291`;
    `isAvailable()` (line 54) deliberately bypasses `tmux()`.
  - `src/pty/PtyHandle.kt` — `attachUpstreamCommand` (line 101), pure and already unit-tested.
  - `src/pty/RealPtyHandle.kt` — `terminalBridgeForSession` passes that argv into `Pty.open`.
  - `ptycheck/src/Main.kt` — six tmux sites, four of them **string-interpolated** (see Task 5 inventory).
  - `test/tmux/TmuxTest.kt` — integration tests on the throwaway `kotgent-test` socket.
  - `test/pty/AttachEnvTest.kt` — pure argv assertions (line 76).
  - Production instantiation is a single site: `Tmux(TMUX_SOCKET)` in `src/cli/Commands.kt:290`.
- **Related patterns found:**
  - "Force, don't inherit" is established: `utf8LocaleOrDefault` + the plist `LANG` + tmux's `-u` flag.
  - Pure-rule-plus-thin-edge is the house style: the rule is a pure function, unit-tested; the I/O
    (`ProcessRunner`/`Pty`) stays dumb.
  - `ProcessRunner` quotes every argument strictly (`shQuote`, `ProcessRunner.kt:199`), so a literal `;`
    argument survives `/bin/sh` and reaches tmux as a command separator.
  - No test in the repo calls `setenv`; six sites *read* `HOME`. Mutating the test process's environment
    would be a new, global-state pattern — avoided here (see Task 2).

### Facts established by probing

All on throwaway sockets, never `-L kotgent`; servers killed after each probe. tmux **3.7b**.

| # | Probe | Result |
|---|---|---|
| 1 | Does a non-default socket read `~/.tmux.conf`? | **Yes** — `mouse on`, `focus-events on` leaked in |
| 2 | Does `-f /dev/null` suppress it? | **Yes** — `mouse` back to the built-in `off` |
| 3 | Does a standalone `set-option` start a server? | **No** — `error connecting to …`, **exit 1**, nothing applied |
| 4 | Does `set-option … ';' new-session …` in one call work? | **Yes** — all six applied, `-P -F '#{pane_id}'` still printed `%0`, exit 0 |
| 5 | Are `-s escape-time` / `-g default-terminal` accepted in that form? | **Yes** |
| 6 | Built-in defaults under `-f /dev/null` | `destroy-unattached off`, `default-terminal tmux-256color`, `history-limit 2000`, `status on`, `mouse off`, `escape-time 10` |
| 7 | `status off` geometry | client 100x40 → window **100x40** (was 100x39 with the status bar) |

Probe 3 is load-bearing: **the chain is not an optimisation, it is the only way.** `set-option` alone
will not bring a server up, and `default-terminal` is read when a pane is created, so setting it after
`new-session` would be too late for the agent already running in that pane.

Probe 6 is why 2 of the 6 options (`destroy-unattached`, `default-terminal`) are **no-ops on today's
tmux**. They stay as deliberate pins against a future default change; they are not what fixes the
invariant.

### What `mouse on` actually does (measured; revision 1 got this wrong)

Driven through a real PTY attach with an SGR wheel-up event (`\e[<64;10;10M`):

| Pane app | `mouse off` | `mouse on` |
|---|---|---|
| alt-screen TUI (`less`) | nothing | **nothing** — no copy-mode, app does not move |
| normal-screen (shell output) | nothing | enters `copy-mode`, `scroll_position=0` — history scroll works |
| app that requested SGR mouse reporting | — | the event is delivered **to the app** (`^[[<64;10;10M`) |

Two follow-ups that settle the risk:

- **copy-mode exits by itself.** Wheel-down back to the bottom returns `pane_in_mode` to `0`, and normal
  input reaches the application immediately afterwards (measured: `HELLO` was echoed by `cat`). No
  operator rescue and no kotgent-side copy-mode policy is needed.
- **Agent TUIs are not all alt-screen.** Claude Code keeps its transcript in the terminal's scrollback
  and prints its own hint recommending `set -g mouse on` under tmux — so the wheel-scroll benefit lands
  squarely on the agent pane. (Revision 1 generalised from `less` and concluded the opposite.)

Residual, accepted: copy-mode is pane state, so it is shared across subscribers — one subscriber's wheel
puts *the* pane into copy-mode. Given the automatic exit, this is the same behaviour every tmux user
already lives with.

## Development Approach

- **testing approach**: **TDD (tests first)** — the new surfaces are pure functions, so tests can be
  written before the code compiles.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting next task** — no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run `./kotlin build` **then** `./kotlin test` after each change (`PtyTest` execs the `ptycheck`
  binary, and `./kotlin test` never links a main binary)
- maintain backward compatibility

## Testing Strategy

- **unit tests**: required for every task. The two pure surfaces — the option list and **`tmuxCommand()`,
  the argv builder** — get exact-value assertions, including a *negative* assertion that `focus-events`
  is absent (the test is the record of that decision).
- **integration tests**: `test/tmux/TmuxTest.kt` drives a real tmux on the throwaway `-L kotgent-test`
  socket (never `-L kotgent`), with the existing skip-guard and `kill-server` teardown.
- **the isolation test must be able to fail — and the decoy must be an option kotgent does not force.**
  Revision 1 used `history-limit`, which is *in* the forced set: once the chain lands, `new-session`
  pins it to 10000 with or without `-f`, so deleting the isolation would have left a green suite
  (demonstrated during review). The decoy is therefore **`focus-events`** (built-in `off`, never set by
  kotgent):
  - raw tmux under `HOME=<tmp>` **without** `-f` → `focus-events on` (the decoy loads; the probe is live)
  - raw tmux under `HOME=<tmp>` **with** `-f /dev/null` → `focus-events off` (isolation works)
- **the link from that fact to production code is a unit test, not an integration test.** `Tmux` builds
  its argv inside a private method and `ProcessRunner` inherits the process environment, so there is no
  way to run `Tmux.newSession` under a temp `$HOME` (revision 1 asked for exactly that; it is not
  implementable, and the `setenv` workaround has no precedent in this repo). Instead the pure
  `tmuxCommand(tmuxPath, socket, args)` is asserted to always contain `-f /dev/null`, and `Tmux` is
  refactored to use it.
- **e2e tests**: the project has no browser e2e harness (no Playwright/Cypress); the web UI is unchanged
  by this work beyond wheel scroll starting to function. Manual checks in Post-Completion.

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview

Two mechanisms, both in code, no file on disk:

1. **Isolation** — `-f /dev/null` is injected into *every* tmux argv kotgent builds, control-plane and
   attach alike. tmux global flags must precede the subcommand, so it goes in next to `-L`.
2. **Forcing** — a pure list of options is expanded into `set-option` commands and **chained ahead of
   `new-session` in a single tmux invocation**, so they are in effect before the pane (and therefore the
   agent process) exists. A failed chain degrades to a bare `new-session` rather than failing the
   session (see Technical Details).

Rejected alternatives:

- **A generated `~/.kotgent/tmux.conf` + `-f <path>`** — would also apply at server start, but adds a file
  that can go stale, an I/O path, and an implicit invitation to hand-edit a file kotgent overwrites.
  The option list stays more testable as Kotlin.
- **`set-option` only, no `-f`** — forces our values but leaves the user's plugins, key tables, hooks and
  `run-shell` lines loaded into the agent's pane. Solves the smaller half of the problem.

Design decisions:

- **Options are global (`-g`) / server (`-s`), not per-session.** kotgent owns the whole server on that
  socket; per-session options would have to be re-applied per session for no gain.
- **Re-applying the chain on every `newSession` is intended.** It is idempotent, and a server that came
  up some other way still converges to kotgent's options.
- **`default-terminal` is pinned to `tmux-256color`**, which is already tmux 3.7b's default. This is
  *not* the same knob as `ATTACH_TERM` (`xterm-256color`): `ATTACH_TERM` is the `TERM` of the attach
  *client* (the terminal kotgent presents to tmux), `default-terminal` is the `TERM` seen *inside* the
  pane by the agent. Different ends of the pipe, correctly different values. Note `tmux-256color`
  terminfo is present on this macOS but absent on macOS ≤ Monterey — irrelevant while it equals tmux's
  own default, and relevant exactly in the scenario the pin exists for.

## Technical Details

### The option set

| Option | Scope | Value | Built-in | Why |
|---|---|---|---|---|
| `destroy-unattached` | `-g` | `off` | `off` | Pin: Detach must never kill the agent |
| `default-terminal` | `-g` | `tmux-256color` | `tmux-256color` | Pin against a future default change |
| `mouse` | `-g` | `on` | `off` | Wheel scroll of pane history; apps that request mouse reporting still get their own events |
| `status` | `-g` | `off` | `on` | The status bar costs a row and renders noise into a pane nobody drives with tmux keys |
| `history-limit` | `-g` | `10000` | `2000` | 2000 is thin for an agent transcript |
| `escape-time` | `-s` | `0` | `10` | The built-in delay makes `ESC` laggy in a TUI |

`focus-events` is intentionally absent (see Overview) and is the isolation test's decoy.

### Resulting invocation shape

```
# every control-plane call
tmux -f /dev/null -L kotgent <sub> …

# session creation — ONE call, options first, still prints the pane id
tmux -f /dev/null -L kotgent \
  set-option -g destroy-unattached off ';' \
  set-option -g default-terminal tmux-256color ';' \
  set-option -g mouse on ';' \
  set-option -g status off ';' \
  set-option -g history-limit 10000 ';' \
  set-option -s escape-time 0 ';' \
  new-session -d -s kt-<id> -c <cwd> -x <cols> -y <rows> -e KOTGENT_SESSION_ID=<id> -P -F '#{pane_id}' <cmd>

# attach upstream
tmux -f /dev/null -u -L kotgent attach -t kt-<id>
```

### Failure mode, and the degradation that answers it

Every command in a chain must succeed or the whole invocation fails — **an option name or scope that a
different tmux build rejects would take `new-session` down with it.** Per CLAUDE.md's fail-fast path
that surfaces as a `TmuxException` and *no session can be created at all*: a cosmetic option would brick
the product on that host.

Since the built-in defaults are already safe for the Detach invariant (probe 6), degrading loses only
ergonomics. So `newSession` retries **once** with a bare `new-session` if the chained form fails, and
then applies the options best-effort (ignoring failures) on the now-running server. `default-terminal`
is lost for that pane on the degraded path — an acceptable trade against not starting at all.

To keep this testable without a second tmux build, `Tmux` takes the option list as a **constructor
parameter defaulting to `TMUX_SERVER_OPTIONS`**; a test constructs `Tmux(socket = "kotgent-test",
serverOptions = <a deliberately bogus option>)` and asserts a session is still created. This adds no
parameter to the `TmuxControl` interface.

### Known limitation (document, do not fix)

If a server is already running on `-L kotgent` because something *else* started it without `-f`, that
server has already loaded the user's config; `-f` on later calls is ignored (it only applies to the
command that starts a server). The `set-option` chain still converges the option *values* on the next
`newSession`, but plugins/bindings loaded at that server's start remain. Not worth guarding against —
kotgent is the only thing that should touch that socket.

## What Goes Where

- **Implementation Steps** (`[ ]`): code, tests, docs inside this repo.
- **Post-Completion** (no checkboxes): manual verification that needs a running daemon and a browser.

## Implementation Steps

### Task 1: Pure option list and argv builder

**Files:**
- Create: `src/tmux/TmuxOptions.kt`
- Create: `test/tmux/TmuxOptionsTest.kt`

- [x] write `test/tmux/TmuxOptionsTest.kt` first: `TMUX_CONFIG_ISOLATION == listOf("-f", "/dev/null")`
- [x] write test: `TMUX_SERVER_OPTIONS` holds exactly the six rows of the option table, with `escape-time`
      carrying the `-s` scope and the rest `-g`
- [x] write test: `TMUX_SERVER_OPTIONS` does **not** mention `focus-events` — records the fan-out decision
      *and* keeps the Task 2 decoy valid (a future author adding it would fail here first)
- [x] write test: `tmuxOptionCommands()` emits `set-option <scope> <name> <value> ';'` per option and ends
      with a trailing `';'`, so it can be prefixed to any subcommand
- [x] write test: `tmuxCommand(tmuxPath, socket, args)` returns
      `[tmuxPath, -f, /dev/null, -L, socket, *args]` — the isolation flags precede `-L`, and both precede
      the subcommand
- [x] create `src/tmux/TmuxOptions.kt` with `TMUX_CONFIG_ISOLATION`, a `TmuxOption(scope, name, value)`
      data class, `TMUX_SERVER_OPTIONS`, `tmuxOptionCommands()` and `tmuxCommand()`; all `public`
      (no friend-module relationship between a module and its tests)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 2
      (447 run / 447 passed / 0 skipped — the pre-change count was 438, not the 428 CLAUDE.md still
      records; Task 7 updates that baseline)

### Task 2: Route every control-plane call through the isolated builder

**Files:**
- Modify: `src/tmux/Tmux.kt`
- Modify: `test/tmux/TmuxTest.kt`

- [x] write the decoy integration test in `TmuxTest`: temp dir under `getenv("TMPDIR") ?: "/tmp"`, write
      `set -g focus-events on` into `<tmp>/.tmux.conf`
- [x] write the **negative half first** — raw argv via
      `/usr/bin/env HOME=<tmp> <tmuxPath> -L kotgent-test new-session …` (no `-f`) leaves
      `show -g focus-events` at `on`, proving the decoy is loadable and the test can fail
      (observed `on` by hand on the throwaway socket before writing the assertion)
- [x] write the **positive half** — the same raw argv **with** `-f /dev/null` leaves it at `off`
- [x] verify both halves kill the server between them and delete the temp dir on every path
      (the `killServer()` between halves is load-bearing: `-f` only applies to the invocation that
      STARTS a server; the temp tree is removed in a `finally`)
- [x] modify `Tmux.tmux()` to delegate argv assembly to `tmuxCommand()`; the unit test from Task 1 is what
      proves production carries `-f`
- [x] KDoc: record that `ensureServer()` (the production first-start) is covered transitively, that the
      option chain is deliberately *not* applied there (a session-less server does not persist), and that
      `isAvailable()` needs no `-f` because `tmux -V` starts no server and reads no config
- [x] run `./kotlin build && ./kotlin test` — must pass before task 3
      (448 run / 448 passed / 0 skipped; `tmux -L kotgent-test kill-server` reports "no server running")

### Task 3: Chain the options atomically with `new-session`, with degradation

**Files:**
- Modify: `src/tmux/Tmux.kt`
- Modify: `test/tmux/TmuxTest.kt`

- [x] write test: after `newSession`, `show -g` reports every value from `TMUX_SERVER_OPTIONS`
      (`show -s` for `escape-time`), driven off the list rather than hardcoded
      (`newSessionForcesEveryServerOption`, read back via `show-options <scope>v <name>` — the option's
      own scope flag works verbatim for both `-g` and `-s`, verified on the throwaway socket)
- [x] write test: `newSession` still returns a non-blank pane id (the chain must not disturb `-P -F` stdout)
- [x] write test: a **second** session on the same server succeeds and leaves the options intact
- [x] write test: the options are in effect **before the pane exists** — launch
      `sh -c 'echo T=$TERM; cat'` and assert `capturePane` shows `T=tmux-256color` (this is the evidence
      for the whole "chain, don't set afterwards" design)
- [x] write test: with a deliberately bogus `serverOptions`, `newSession` still creates the session
      (degradation path) — the bogus option is paired with a valid `history-limit 12345` so the test also
      proves the best-effort second half lands on the now-running server
- [x] add the `serverOptions` constructor parameter (defaulting to `TMUX_SERVER_OPTIONS`) — not on the
      `TmuxControl` interface
- [x] modify `newSession` to prepend `tmuxOptionCommands()` in one invocation, and on failure retry a bare
      `new-session` then apply the options best-effort; KDoc must state that a standalone `set-option`
      cannot start a server (exit 1) and that `default-terminal` is read at pane creation
      (re-measured: a rejected chain aborts **before** `new-session` runs and creates no session, so the
      bare retry cannot collide with a half-created one — recorded in the KDoc)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 4
      (453 run / 453 passed / 0 skipped; `tmux -L kotgent-test kill-server` reports "no server running")

### Task 4: Isolate the attach upstream

**Files:**
- Modify: `src/pty/PtyHandle.kt`
- Modify: `test/pty/AttachEnvTest.kt`

- [x] update `theAttachCommandForcesUtf8Output` to expect
      `[tmuxPath, -f, /dev/null, -u, -L, <socket>, attach, -t, <session>]`
- [x] write test: `-f` and its value precede the `attach` subcommand (same bug class as the existing `-u`
      ordering assertion)
      (`theAttachCommandIsolatesTheUserConfig` — also asserts the argv carries `TMUX_CONFIG_ISOLATION`
      verbatim rather than a re-spelled literal, so a future edit to the shared constant cannot drift)
- [x] modify `attachUpstreamCommand` to include `TMUX_CONFIG_ISOLATION`; KDoc notes the flag is inert when
      the server is already up and is there for consistency and for the case where it is not
- [x] leave `test/pty/TerminalBridgeTest.kt:56,98` alone — that argv is the fake factory's own default
      command, not a real tmux invocation (confirmed during review)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 5
      (454 run / 454 passed / 0 skipped; `tmux -L kotgent-test kill-server` reports "no server running")

### Task 5: Give the ptycheck fixtures the same isolation

**Files:**
- Modify: `ptycheck/src/Main.kt`

Verified inventory (revision 1 had this wrong — four sites are string-interpolated, not a `$target` var):

| Line | Site | Action |
|---|---|---|
| 163 | `sh("${q(tmux)} -L $socket kill-session …")` | add `-f /dev/null` |
| **164** | `sh("${q(tmux)} -L $socket new-session …")` | **add — this is the first server start of the whole ptycheck run** |
| 182 | `sh("${q(tmux)} -L $socket has-session …")` | add |
| 185 | `sh("${q(tmux)} -L $socket kill-session …")` | add |
| 204 | `val target = "${q(tmux)} -L $TEST_SOCKET"` | add |
| 169, 213 | `Pty.open(command = listOf(tmux, "-L", …))` | add |
| 249 | `Tmux(socket = TEST_SOCKET)` | **leave — covered transitively by Task 2** |

- [x] hoist a `val target = "${q(tmux)} -f /dev/null -L $socket"` in `tmuxAttachRunsOnTheSpawnedPts` and use
      it for lines 163/164/182/185
- [x] add `-f /dev/null` to the `target` at 204 and to both `Pty.open` argv lists (169, 213)
      (the two argv lists now go through `tmuxCommand(tmux, socket, listOf("attach", "-t", session))`
      rather than re-spelling the flags — same argv, and the fixture cannot drift from the shared
      constant; ptycheck already depends on the root module)
- [x] comment the motive: `main()` runs `tmuxAttachRunsOnTheSpawnedPts` first, so line 164 is what starts
      the server; a developer with `destroy-unattached on` fails the "session outlives the attach"
      assertion at line 183, and a later `-f` is inert while that server lives
      (recorded in that check's KDoc, with a back-reference from `resizeReachesARunningTmuxAttach`)
- [x] run the binary directly (terminates, only touches `-L kotgent-test`) and confirm every check passes
      (`build/tasks/_ptycheck_linkMacosArm64Debug/ptycheck.kexe` — `SUMMARY total=8 failed=0 skipped=0`,
      exit 0)
- [x] run `./kotlin build && ./kotlin test` — `PtyTest` must still exec ptycheck and see exit 0
      (454 run / 454 passed / 0 skipped; `tmux -L kotgent-test kill-server` reports "no server running")

### Task 6: Verify acceptance criteria

- [x] verify all requirements from Overview: isolation on every call, six options forced, `focus-events`
      absent, degradation path present
      (all four hold. Isolation: the complete inventory of sites that actually exec tmux is
      `Tmux.tmux()` → `tmuxCommand()` (`Tmux.kt:83` — the single funnel for `ensureServer`,
      `newSession`, `listPanes`, `capturePane`, `killSession`, `sendKeys`) and `attachUpstreamCommand`
      (`PtyHandle.kt:112`); both carry `TMUX_CONFIG_ISOLATION`. Six options: `TMUX_SERVER_OPTIONS` has
      exactly the table's six rows, pinned by `serverOptionsHoldExactlyTheDocumentedTable` and read
      back off a live server by `newSessionForcesEveryServerOption`. `focus-events`: absent, pinned by
      `serverOptionsNeverForceFocusEvents`. Degradation: `newSession` chained → bare retry →
      `applyServerOptionsBestEffort`, exercised by `aRejectedOptionChainDegradesToABareNewSession`)
- [x] verify edge cases: second session idempotent, decoy proven loadable *and* proven suppressed, pane id
      still returned, `TERM` correct inside the pane, bogus-option session still created
      (all five ran for real against tmux **3.7b** — the skip-guard did NOT short-circuit them, confirmed
      from per-test durations in the full log: `aSecondSessionSucceedsAndLeavesTheOptionsIntact` 123 ms,
      `theUserConfigLeaksWithoutIsolationAndIsSuppressedByIt` 94 ms,
      `theOptionChainLeavesThePaneIdAloneOnStdout` 45 ms, `theForcedOptionsApplyBeforeThePaneExists`
      52 ms, `aRejectedOptionChainDegradesToABareNewSession` 119 ms — 13/13 `TmuxTest` OK)
- [x] `git grep -nE '\-L (\$|")' -- src test ptycheck` — the revision-1 grep (`'"-L"'`) could not see
      interpolated `-L $socket` sites and would have passed while four were unfixed
      (3 hits, all clean: `ptycheck/src/Main.kt:172` and `:217` both read
      `"${q(tmux)} -f /dev/null -L $socket"`, i.e. already isolated; `src/cli/Commands.kt:401` is a
      `println` hint string, not an invocation)
- [x] record why the remaining hits are exempt: `Tmux.isAvailable()` (`tmux -V`, no server, no config) and
      the `kill-server` teardowns in `TmuxTest.kt:37`, `SessionManagerTest.kt:1126,1161`
      (⚠️ the corrected grep is **necessary but not sufficient** for this step: it sees only
      shell-interpolated argv, so the list-literal sites it is meant to exempt do not appear in it
      either. Enumerated instead with
      `git grep -nE '"-L"|-L \$|-L "|tmuxPath|ProcessRunner\.run' -- src test ptycheck`. Exempt sites,
      each re-checked rather than assumed:
      • `Tmux.kt:71` `isAvailable()` — `tmux -V` prints the version and exits; starts no server, parses
        no config, so there is nothing to isolate;
      • ➕ `Tmux.kt:259` `defaultTmuxPath()` — `command -v tmux` is a shell path lookup, not a tmux
        invocation (not on the plan's list);
      • ➕ `Commands.kt:401` — `println("… (tmux -L $TMUX_SOCKET)")`, log text that executes nothing
        (not on the plan's list; it is the third grep hit);
      • `TmuxTest.kt:54` (KDoc at `:36`, not `:37`) and `SessionManagerTest.kt:1126,1161` — `kill-server`
        teardown only; `kill-server` never *starts* a server, so it never parses a config;
      • `TmuxTest.kt:355` `rawTmux()` — isolation is deliberately conditional on the `isolate` flag;
        this argv **is** the probe, and pinning `-f` on would destroy the negative half;
      • `TerminalBridgeTest.kt:56,98` — the fake factory's own default command string, never executed;
      • `ptycheck/src/Main.kt:262` `Tmux(socket = TEST_SOCKET)` — covered transitively by Task 2)
- [x] run full test suite: `./kotlin build && ./kotlin test` (baseline 428 run / 428 passed / 0 skipped —
      expect the count to rise, expect **0 skipped**)
      (**454 run / 454 passed / 0 skipped**, 40 test cases, 22.2 s. The 428 in CLAUDE.md was already
      stale before this work: `git grep -c '@Test' 7dd2f9b -- test` totals **438** at the pre-change
      commit and **454** at HEAD, matching the +16 this plan added — so Task 7 must write 454, and the
      438 recorded in Task 1 is confirmed. `ptycheck` run directly from
      `build/tasks/_ptycheck_linkMacosArm64Debug/ptycheck.kexe`: `SUMMARY total=8 failed=0 skipped=0`,
      exit 0)
- [x] confirm no tmux server leaked: `tmux -L kotgent-test kill-server` reports "no server running"
      (reports `no server running on /private/tmp/tmux-501/kotgent-test` after both the suite and the
      direct `ptycheck` run; the real `-L kotgent` socket was never touched)
- [x] note: no suite assertion depends on pane *height* (`TmuxTest.kt:87` asserts `height > 0`, `:89`
      asserts width 80, `Main.kt:226` asserts `window_width`), so `status off` breaks no test — its real
      effect is on the running system (one extra row per pane, one more line in the `capture-pane` seed)
      (confirmed, with the line numbers as they now stand after Tasks 2-5: `TmuxTest.kt:104` asserts
      `width > 0 && height > 0`, `:106` asserts width 80, `Main.kt:239` asserts `window_width == 143`
      — `window_height` appears at `Main.kt:242` only inside a failure *message*. `ReconcilerTest`'s
      heights are hand-built `TmuxPane` fixtures, not real tmux. So nothing in the suite pins an exact
      pane height and `status off` moves no assertion)

### Task 7: [Final] Update documentation

- [ ] add a CLAUDE.md paragraph next to the tmux fan-out / `LANG` invariants: the kotgent tmux server
      reads `~/.tmux.conf` unless `-f /dev/null` is passed; `set-option` cannot start a server so the
      chain rides with `new-session`; the chain degrades rather than failing session creation;
      `focus-events` is deliberately off and doubles as the isolation test's decoy
- [ ] note in CLAUDE.md that any new tmux argv site must go through `tmuxCommand()`
- [ ] update the "Where things live" map with `src/tmux/TmuxOptions.kt`
- [ ] update the test-count baseline in CLAUDE.md to the new number
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification** (needs a running daemon — do not do this from automation):

- Start a session, open it in the browser, confirm the wheel scrolls the agent's transcript, and confirm
  scrolling back down returns input to the agent (the measured auto-exit from copy-mode).
- Confirm the status bar is gone from the web terminal and the agent has the full row count.
- With `set -g destroy-unattached on` in `~/.tmux.conf`, start a session, attach, then close every
  subscriber: the session must survive in `tmux -L kotgent ls`. This is the regression that motivated the
  plan and it cannot be covered by the suite without touching the real socket.
- With `status off` there is nowhere for tmux to render its own messages/errors to the attached client —
  watch for a case where a tmux error would previously have been visible in the status line.

**External system updates:**

- None. No schema change, no config file, no plist change, no `kotgent install` re-run needed.
- Existing long-lived sessions on a running `-L kotgent` server keep whatever options that server started
  with until it is restarted; a daemon restart alone does not re-create the tmux server.
