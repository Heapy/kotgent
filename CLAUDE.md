# CLAUDE.md — working in the kotgent repo

Guidance for future work here. Read this before changing the build, adding native code, or touching the
event model. It captures the conventions and the hard-won toolchain lessons so they don't get re-derived.

## Build system: JetBrains Kotlin Toolchain (NOT Gradle)

This project builds with the **JetBrains Kotlin Toolchain** (formerly Amper), driven by the committed
`./kotlin` wrapper. There is **no Gradle** — no `build.gradle(.kts)`, no `settings.gradle`, no Gradle
plugins.

```shell
./kotlin build      # compile        (run this BEFORE `test` — PtyTest execs the ptycheck binary)
./kotlin do kexePath # print the root app's built debug .kexe path (also written to build/kexe-path)
./kotlin test       # run tests
./kotlin run -m kotgent   # run the binary  (⚠️ avoid in automation — this starts things; see below)
```

- **Manifests are declarative YAML.** The root module is `module.yaml`; the multi-module wiring is
  `project.yaml` (`modules:` + `plugins:`). Configure dependencies, `settings.kotlin`, `settings.ktor`,
  `settings.native.entryPoint`, etc. there — not in code or a Gradle DSL.
- **Amper source layout.** Sources go in `src/`, tests in `test/` — **not** `src/nativeMain/kotlin/…`.
  Package is `io.kotgent.*`; directories are organized by area (`src/core/…` = `package io.kotgent.core`)
  but the directory does not have to mirror the package.
- **cinterop `.def` files live under `cinterop/`** (e.g. `sysnative/cinterop/pty.def`) with **no YAML
  wiring** — the toolchain auto-discovers them and applies them to the module's native platform.
- **Portability rule for YAML:** never put a machine-specific absolute path in a `*.yaml`
  (`git grep '/Users/' -- '*.yaml'` must stay empty). Portable system flags are fine
  (e.g. `freeCompilerArgs: [-linker-option, -lsqlite3]`); an absolute `-library` path is not.

## Module structure

Five modules (see `project.yaml`):

- **root — `macos/app`, `macosArm64`** (`module.yaml`): the application. `src/` + `test/`, plus
  `sqldelight/*.sq` (schema) and `resources/webui/` (the SPA). Depends on `./sysnative`; enables the
  `build-info` and `sqldelight-gen` plugins.
- **`sysnative/` — `kmp/lib`, `macosArm64`**: owns **ALL** raw POSIX/cinterop bindings and their thin
  Kotlin wrappers (`cinterop/pty.def`; `Pty`, `NativeTty`, `NativeExe`). The app depends on it, so the
  auto-discovered cinterop klib links into the app's **main** binary as a normal module dependency (this
  replaced an old machine-specific `-library` hack). **Any new raw cinterop goes here**, behind an
  interface (see KT-78062 below).
- **`ptycheck/` — `macos/app`, `macosArm64`**: a **test fixture, not a product**. Its `main()` runs the
  real-PTY integration checks that a test binary cannot run at all (KT-78062 below): the `cat`
  round-trip, `resize`, the child exit code, a failing spawn, the spawned child inheriting only its tty
  (`POSIX_SPAWN_CLOEXEC_DEFAULT`), write/reader teardown ordering, a real `tmux attach` acquiring a
  controlling tty, and `TerminalBridge`'s fan-out over that attach. It depends on `./sysnative` **and**
  on the root app module (allowed, one-way — that is where `TerminalBridge`/`Tmux` live). The suite's
  `PtyTest` execs the binary and asserts it exits 0. Because there is now more than one runnable
  module, `./kotlin run` needs `-m kotgent` (it errors and lists the modules otherwise).
- **`plugins/sqldelight-gen/` — `jvm/amper-plugin`**: a build-time JVM plugin that runs SQLDelight codegen
  (SQLDelight ships only a Gradle plugin, so we drive its compiler programmatically via a vendored,
  Gradle-free `SqlDelightEnvironment` and contribute the output via `generated.sources`). It runs on the
  JVM at build time and is not linked into the native app, so it can depend on SQLDelight's heavy JVM
  compiler artifacts freely.
- **`plugins/build-info/` — `jvm/amper-plugin`**: generates `VERSION` and embedded build metadata from
  `version.txt` + Git HEAD. Ordinary source builds display `VERSION+<short-sha>` in the Web UI; the tagged
  release workflow sets `KOTGENT_RELEASE_BUILD=true`, so its packaged/Homebrew binary displays `VERSION`
  alone and keeps the CLI's `kotgent VERSION` contract. Its action deliberately disables execution
  avoidance: in a linked worktree, `.git` is a stable indirection file while the changing branch ref lives
  in the common Git directory outside the worktree. It also exposes the `kexePath` / `releaseKexePath`
  custom commands (see the invariant below).

## Core patterns & invariants

**Host-free core vs. edges.** The domain, reducer, event store (behind an interface), and adapter
normalization are **host-free** and fully unit-tested. The edges — cinterop/PTY, `tmux`, Ktor WS, tty-raw,
the codegen plugins — are kept thin and behind interfaces so the logic above them is testable. Preserve
this split: put pure logic in `core/`, and keep I/O at the boundary.

**Event-sourcing is the backbone.** State is a **derived projection**, never stored as the truth:

- Adapters normalize provider signals into a canonical `AgentEvent` (7 v1 types:
  `TurnStarted`, `TurnCompleted`, `ApprovalRequested`, `ApprovalResolved`, `ToolCall`, `Exited`,
  `SessionBound`).
- Those are appended to an **append-only `events` log** (`EventStore.append`).
- A **pure `reduce(projection, event)`** folds the log into a `Projection`; `replay(events)` reconstructs
  it from scratch. Restart-safety is exactly this replay.
- **`ControlSignal`** (`Interrupt` / `Stop` / `Resume` / `Detach`) is a **separate reducer input**
  (an overloaded `reduce(projection, signal)`), **not** an `AgentEvent` and **not persisted** — control
  signals do not advance the per-session `seq`. Don't fold operator actions into the event vocabulary.
- Reducer invariant: `pendingApprovals > 0 ⟺ state == needs_approval`. There is no "permission answered"
  hook from Claude, so **entering `running` (a `ToolCall` / `TurnStarted`) resets `pendingApprovals = 0`** —
  that is how an approval clears. `needs_answer` and `resumable` are **not** produced by the reducer;
  `needs_answer` is forward-modeled and `resumable` is a reconciler classification (and the state an
  import registers directly — see "Import is registration, not launch").

**Two providers, one shape.** `claude` and `codex` are both launched as a **TUI inside `tmux`** and both
report through **hooks → a local HTTP ingress → the normalizer**. Adding a provider means an adapter
(launch spec + hook config + normalizer), an ingress route, a `VendorStoreProbe` (registered in
`productionVendorStoreProbe`, `src/daemon/Reconciler.kt` — no longer inlined in `Commands`), a
`VendorSessionLocator` (registered in `productionSessionLocator`, `src/daemon/VendorSessionLocator.kt` —
without it import discovery silently answers null for the new kind, forcing `--cwd`), and an entry in
`agentFactoryOf` — nothing in `core/`, the store, or the fan-out changes. What differs between the two:

- **Hook delivery.** Claude takes a settings FILE (`claude --settings <path>`). Codex has no such flag, so
  hooks ride in the argv as `-c 'hooks={…}'` — verified to resolve as `source: sessionFlags`, i.e. scoped
  to that one launch. **Never** write kotgent's hooks into `$CODEX_HOME/hooks.json` or a `[hooks]` table in
  the user's `config.toml`: both resolve as `source: user` and would fire for every codex session the user
  runs. Codex also marks an unseen hook `untrusted`, hence the companion `-c bypass_hook_trust=true`.
- **Provider id.** Claude preallocates (`--session-id <uuid>`). Codex cannot, so the id is captured after
  the fact: the `SessionStart` hook if it fires, else `CodexRolloutScan` reading
  `~/.codex/sessions/<date>/rollout-<ts>-<id>.jsonl` (id in the file NAME, `cwd` in the first line). The
  hook wins over the scan — it is authoritative for *this* session, the scan infers from disk.
- **Approvals.** Codex fires a real `PermissionRequest`, so `needs_approval` is precise there; Claude maps
  any `Notification`. The *clearing* rule is the same for both (see the reducer invariant above) — kotgent
  never answers an approval, the operator does, in the terminal.
- **Resumability.** Claude namespaces transcripts per project dir (probe needs the `cwd`); Codex names a
  rollout by id alone (probe ignores the `cwd`). Archived codex rollouts do **not** count — archiving puts
  a session out of `codex resume`'s reach.

**Import is registration, not launch.** `kotgent import` / the Web UI's Import mode →
`POST /sessions/import` → `SessionManager.importSession` brings a session started *outside* kotgent under
management with **zero tmux side-effects**: it writes a full `resumable` row (provider id set,
`paneId = null`) and appends `SessionBound` via `ProviderIdCapture.bind`; the actual launch is the
existing `resume()` path (`claude --resume` / `codex resume`), so there is no second launch codepath and
a failed start leaves the row honestly `resumable`. The cwd (explicit or discovered) is **canonicalized
through the filesystem** first (`realpath(3)` — `canonicalPath` in `SessionManager.kt`): providers record
their process `getcwd` — the symlink-free spelling — so `/repo/./`, an uncollapsed `--cwd ../proj` and a
symlinked prefix (`/tmp` for `/private/tmp`) must all converge on the ONE string that keys the claude
transcript probe and lands in the row, or a valid import is falsely rejected (claude) / a noncanonical
cwd is persisted (codex). The daemon owns this because only the filesystem can answer it — the CLI's
`resolveCwdAgainst` deliberately collapses only `.`/duplicate/trailing slashes and passes `..` through
UNRESOLVED (a lexical `..` collapse crosses symlinks wrongly: `/tmp/../Users` really names
`/private/Users`; `start` likewise leaves `..` for tmux's `new-session -c` to resolve in the kernel).
Validation happens with the same `(agent, canonical cwd, id)` triple the `Reconciler` re-probes on every
daemon start (`VendorStoreProbe`, plus `VendorSessionLocator` discovering the `cwd` from the provider's
own records when none is given) — an import that succeeds therefore stays `resumable` across restarts
instead of silently degrading to `crashed`; a cwd that fails the probe even after canonicalization (a
genuinely different directory) fails the import loudly, naming `--cwd` as the workaround. The agent *binary* is deliberately not checked at import time —
`resume()` already fails fast with the `kotgent install` hint. Known limitations, recorded not fixed: a
session still live in another terminal is undetectable (resume runs a second CLI copy of the same
conversation — operator's responsibility), and an imported session's `cliVersion`/`cliPath` stay null
forever (filling them would mean running the binary at import, contradicting the rule above); `model`
appears after the first resume (claude via hooks, codex via `resume()`'s model capture, which reads ONLY
the session's own id-keyed rollout — `captureCodexModelOnce` in `CodexRolloutScan.kt` re-reads the row's
provider id on EVERY attempt, so a fresh launch whose background id capture lands mid-poll starts
answering. While the id is unknown an attempt persists NOTHING, and there is deliberately NO cwd+mtime
heuristic fallback any more: a codex `SessionStart` hook can bind the id at ANY later moment — even
after the poll has exhausted its attempts — and a FIRST bind (null → id) triggers no model correction,
so an id-less guess (possibly a busier same-cwd neighbour rollout's model), persisted at any point,
could stick forever. Every guarded variant of the heuristic — provisional writes, final-attempt-only,
pre-write re-reads — lost that same race and was removed. A session whose id never binds keeps an
honest null model; it is already degraded, since resume itself requires the id. The row's id can
itself be a wrong PROVISIONAL one — `discoverSessionId`'s cwd+mtime fallback can bind a same-cwd
NEIGHBOUR's id, making the capture persist the neighbour's model and stop — and the hook's later
authoritative `SessionBound` overwrites only the id (the reducer records it unconditionally: the hook
wins over the scan). That displacement therefore carries a correction: the codex hook ingress reads
the row's id just before a `SessionBound` append, and when the append DISPLACED a different persisted
id it fires `SessionManager.onProviderIdRebound`, which clears the suspect model (`setModel(null)`)
and re-runs the id-keyed capture under the now-authoritative id. Two guarantees keep that correction
honest: the displacing append and the callback run as ONE non-cancellable unit in the ingress (a
dropped hook connection or a thrown callback is logged, never lost — a same-id retry could not
re-detect the displacement), and every id-keyed capture WRITE is atomically conditional on the row
still holding the id the lookup was keyed by (`EventStore.setModelForProvider`, the check inside the
SQL `WHERE`), so an in-flight capture that raced the rebind writes zero rows and keeps polling
instead of restoring the neighbour's model past the clear). The four import
failures are deliberately **standalone, hierarchy-free** exceptions (`UnknownAgentKindException`,
`ImportCwdException`, `TranscriptNotFoundException` → 400; `DuplicateImportException` → 409) so the
route's catches are order-free — the flat counterpart of the load-bearing `TmuxCopyModeException`
subtype pattern above. One cross-file contract rides the 409: its body's `kotgent session '<id>'`
phrase is parsed back out by the CLI (`runImportCommand` via `DUPLICATE_IMPORT_ID_IN_BODY` in
`src/cli/Commands.kt`), pinned on the server side by TransportTest and on the CLI side by CliTest
stubs built from the real exception message — reword `DuplicateImportException` and a test fails
instead of the `kotgent resume <id>` hint silently degrading.

**Single-upstream `tmux`-client fan-out.** The daemon holds **exactly one** upstream `tmux attach` client
per session and fans its output out to all subscribers (IDE, browser). `TerminalBridge` is **lazy**: the
upstream PTY opens on the *first* subscriber and closes on the *last* (that last-detach is the "Detach" —
the agent lives on in `tmux`). Input from any subscriber goes to the one upstream; resize is "last active".
Do not open a second `tmux attach` or route input via `tmux send-keys` — it breaks the single-upstream
invariant.

**A subscriber's geometry must be known at OPEN, not only after the first resize frame.** A `tmux` client
reads its size from `TIOCGWINSZ` exactly once, at startup, so a size that lands later is a *reflow* of the
agent's TUI (and, before the `SIGWINCH` fix below, was silently lost). So the terminal WS carries
`?cols=&rows=` (browser: after `fit()`; `kotgent attach`: the tty's size) → `TerminalBridge.subscribe(cols,
rows)` → `Broadcaster.attach(size)`, which records it as the new "last active" size **before** opening the
upstream, where the existing "re-apply `lastSize` on open" path applies it. Non-positive/absent values are
ignored (open at the pty default, correct via the first resize frame) — never trust them into the ioctl.

**Spawned children inherit stdio and NOTHING else.** Every process the daemon starts outlives it —
`tmux` daemonizes, and the agent lives on inside `tmux` — so an inherited descriptor is an inherited
descriptor *forever*. The listening socket is created inside Ktor CIO (macOS `socket(2)` has no
`SOCK_CLOEXEC`), so it is inheritable by default, and a `tmux` server that inherited it keeps the port
bound after the daemon dies: rebinds fail with `EADDRINUSE`, and clients hang on connections the kernel
accepts but nobody serves. Both spawn paths are closed and must stay closed:
`ProcessRunner.run` (`popen`) sweeps the descriptor table with `markOpenFdsCloexec` (`src/sys/Cloexec.kt`,
stock `fcntl` so it links into test binaries) right before the fork; `Pty.open` (`posix_spawn`) passes
macOS's `POSIX_SPAWN_CLOEXEC_DEFAULT`, which closes everything not named in its file actions, atomically.
**Any new spawn path must do the same.** Covered by `CloexecTest` (popen) and a `ptycheck` check
(posix_spawn). Corollary for clients: never issue an untimed request at the daemon — a socket held by an
orphan accepts and then stays silent, so `ApiClient` sets `HttpTimeout` and `AttachClient` bounds the WS
*handshake* with `withTimeout` (a finite `requestTimeoutMillis` would kill a healthy long-lived attach).

**The daemon's PATH is snapshotted at `kotgent install`.** launchd starts the daemon with a minimal env,
so `kotgent install` captures the caller's login `getenv("PATH")` and merges it with the
`DAEMON_DEFAULT_PATH` fallback (`mergedDaemonPath` in `src/launchd/Plist.kt` — dedup, captured entries
first) into the plist's `EnvironmentVariables.PATH`. Agents inherit that PATH — that is how a launchd-run
daemon finds `claude`/`codex` (and, for codex's `env node` shebang, `node`) outside the system bins;
re-run `kotgent install` from a full shell whenever the PATH goes stale. (Forcing PATH per-pane via tmux
`new-session -e PATH=…` was tried and does **not** work: tmux **special-cases `PATH`** — the pane always
inherits the **tmux server's** own PATH and the `-e PATH=` is dropped, while sibling `-e` vars such as
`KOTGENT_SESSION_ID` *do* land. This is **not** a login-shell/`path_helper` effect: verified on a
direct-argv pane where no shell runs at all — `/etc/zprofile` never fires, yet `-e PATH=` is still
discarded while a sibling `-e FOO=` survives. This is upstream-documented behaviour — PATH is inherited
from the first-created session, i.e. the server's env (tmux/tmux#476). The server's PATH is the daemon's
PATH — under launchd, the plist snapshot above — which is why the snapshot, not `-e`, is the fix.) An agent binary that
does **not** resolve on the daemon's PATH **fails fast**: the factory's `create()` throws
`AgentBinaryNotFoundException` before any tmux side-effect (no phantom `running` row), which `ControlRoutes`
maps to a **400 carrying a `kotgent install` hint** — not a silent attach `1006`.

**A UTF-8 `LANG` is never inherited — it is forced.** launchd supplies no locale at all (on macOS the
terminal *emulator* sets `LANG`; no shell runs for a LaunchAgent, and `/etc/zprofile`'s `LANG=C.UTF-8`
default only fires for a login shell that never happens here). A tmux **client** decides whether it may
emit UTF-8 from its own locale, and a client that reads as non-UTF-8 makes tmux rewrite **every**
non-ASCII cell as `_` (`tty_check_codeset`) — an agent's box-drawing TUI arrives as a wall of
underscores, which is exactly what a launchd-started daemon used to produce. So the locale is forced in
three places, never merely passed through: `utf8LocaleOrDefault` (`src/sys/Locale.kt`, the one pure rule
— a missing *or* non-UTF-8 value becomes `en_US.UTF-8`), the plist's `EnvironmentVariables.LANG`
(snapshotting the installer's `LANG` through that rule, so the daemon, the tmux server and the agent all
run UTF-8), and the attach upstream, which sets `LANG` in `terminalAttachEnv` **and** passes tmux's
global `-u` (`attachUpstreamCommand`) — the flag does not depend on the requested locale existing on the
host. Do not "optimize" either half away.

**The tmux config is not inherited either — every argv carries `-f /dev/null`.** `-L kotgent` isolates the
*socket*, not the *configuration*: tmux parses its system config and the user's (`~/.tmux.conf`, also
`$XDG_CONFIG_HOME/tmux/tmux.conf`; the system one sits next to the binary, e.g.
`/opt/homebrew/etc/tmux.conf` for the Homebrew tmux `defaultTmuxPath()` prefers) whenever a command
**starts a server**, whatever the socket is labelled (measured on a throwaway socket — an operator's
`mouse on` / `focus-events on` leaked straight into a server their config has nothing to do with). The
consequence that matters is `destroy-unattached on`: kotgent's last-detach closes the one upstream, tmux
then destroys the session, and the agent is killed by a file kotgent never reads. `-f /dev/null` suppresses
the user config completely, which alone restores the Detach invariant. **`-f` only affects the invocation
that STARTS a server** — on every later call it is inert, so a `-L kotgent` server that something *else*
brought up has already loaded the user's config and no later flag undoes it (the option chain still
re-converges the option *values* on the next `newSession`, but bindings/hooks/plugins stay). Two places in
*production* build a tmux argv — `tmuxCommand()` (`src/tmux/TmuxOptions.kt`), the funnel for the whole
control plane, and `attachUpstreamCommand` (`src/pty/PtyHandle.kt`) for the attach upstream — and both
prepend `TMUX_CONFIG_ISOLATION`. **Any new tmux argv site must go through `tmuxCommand()`**; a hand-rolled
`listOf(tmux, "-L", socket, …)` silently re-opens the hole. Outside production two fixtures build their own:
`ptycheck/src/Main.kt` hand-rolls `"${q(tmux)} -f /dev/null -L $socket"` as a shell string through its
local fixture helpers, and `TmuxTest.rawTmux` builds a deliberately *un*-isolated argv — that one **is**
the isolation probe. (`ptycheck` depends on the root module, so its use of local helpers is not because
`ProcessRunner` is unavailable.) Exempt, because they start no server and parse no config: `tmux -V`,
`command -v tmux`, and `kill-server` teardowns. On top of isolation kotgent forces six options
(`TMUX_SERVER_OPTIONS`):
`destroy-unattached off`, `default-terminal tmux-256color`, `mouse on`, `status off`,
`history-limit 10000`, `escape-time 10` (`-s`). Three of them already equal tmux 3.7b's built-in defaults
— isolation is what fixes Detach; they are pins against a future upstream default change. `TmuxOption.scope` is documentation
plus the read-back flag, **not** a correctness gate: tmux resolves an option's scope from its *name* and
ignores a mismatched flag (`set-option -g escape-time 55` exits 0 and sets the server option). Note
`default-terminal` is the `TERM` the agent sees *inside* the pane; `ATTACH_TERM` (`xterm-256color`,
`src/pty/PtyHandle.kt`) is the `TERM` of the attach *client* kotgent presents to tmux — different ends of
the pipe, correctly different values. The options are chained into the **same invocation** as `new-session`
(`set-option … ';' new-session …`), never applied afterwards: a standalone `set-option` cannot start a
server (exit 1, `error connecting to …`) and `default-terminal` is read when the pane is *created*, so the
chain is the only way, not an optimisation. A rejected chain aborts before `new-session` and creates no
session, and `newSession` then **fails loudly** with tmux's stderr (which names the option) — fail-fast,
like `AgentBinaryNotFoundException`. A bare-retry + best-effort fallback was tried and removed: it fired on
*every* failure (duplicate session, bad cwd, dead socket), doubled the spawn count, misattributed the real
error, and silently lost the pane-creation-time options anyway. `Tmux.serverOptions` is a constructor
parameter (deliberately **not** on `TmuxControl`) purely as a test seam: it is the only way to drive a
*non-default* `default-terminal` through `newSession` and prove from the pane's `$TERM` that the chain
landed before the pane existed — with the production values that assertion would pass with the whole chain
deleted. **`mouse on` is the one forced row that flips a real default, and it is coupled to
`Tmux.sendKeys`.** It buys the wheel scrolling a pane's *own* history in both viewers (web terminal and
`kotgent attach`) — that history lives in the tmux pane, not in xterm.js, and a subscriber joining an
existing bridge is seeded from `capture-pane`, so without it nothing older than the current screen is
reachable; an app that requested SGR mouse reporting still gets its own events (measured), so alt-screen
TUIs are unaffected. **A joiner does not get client modes for free**: `capture-pane -p -e` carries zero
private-mode sequences (measured) and the upstream's enables went out as live deltas when it *opened*.
So every non-empty per-subscriber seed prepends `TERMINAL_BRACKETED_PASTE_ENABLE` (tmux enables `2004`
for every client; without it xterm.js sends multiline paste as executable ordinary input) and, when
`forcesMouseOn` is true, `TERMINAL_MOUSE_ENABLE`. Do not "simplify" the seed back to a bare `capturePane`
— tmux only re-emits the mode set on a repaint that a *geometry change* triggers, and macOS raises
`SIGWINCH` solely on an actual size change, so a second tab at the same size would silently lose both
paste safety and the wheel. App-owned modes are deliberately not synthesized. It costs this: copy-mode
is *shared pane state*, so any subscriber's wheel puts **the**
pane into it, and while `pane_in_mode=1` every keystroke — `send-keys` and bytes written into an attached
client's pty alike, including `SessionManager.interrupt`'s `0x03` — is routed to the copy-mode key table
and dropped while tmux still exits 0, which would make the projection record an interrupt that never
happened. That is why **`Tmux.sendKeys` chains `copy-mode -q` + the send + a `#{pane_in_mode}` read-back
into ONE tmux invocation** and accepts only an answered `0`: an answered `1` throws
`TmuxCopyModeException`, while an empty/unparseable answer or a missing server/session/pane throws a
plain `TmuxException`. A soft absence makes `leaveCopyMode`'s clearance question moot (`true`), but it
can never satisfy `sendKeys`' delivery contract; therefore `SessionManager.interrupt` persists `ready`
only after verified delivery. Once that synchronous send returns, Ctrl-C is irreversible: the projection
read plus derived-state write run under `NonCancellable`, because abandoning either would leave stale
state that invites an unsafe second Ctrl-C (which quits some agent TUIs). The send itself is outside
`NonCancellable`, but that does **not** make it cancellable: `Tmux.sendKeys` blocks
synchronously inside `ProcessRunner` with no cancellation point, so a hung send holds the per-session
control lock. Separate invocations
leave a window a wheel event can land in, `copy-mode -q` (unlike `send-keys -X cancel`) is a silent no-op
on a pane in no mode so it can be chained at all, and there is deliberately no retry — a duplicated `0x03`
quits some agent TUIs. Do not weaken that chain while `mouse on` is set;
`TmuxTest.sendKeysReachesTheProcessEvenFromCopyMode` and `…FailsLoudlyWhenTheCopyModeCancelIsDefeated` are
its two halves, and copy-mode auto-exiting when the wheel reaches the bottom covers only the operator who
scrolls back down. A swallowed `send-keys` throws `TmuxCopyModeException` — a **subtype** of
`TmuxException` so the action route can answer it **409 + hint** (transient, retryable) instead of the
plain `TmuxException`'s generic operation-failure 400; catch it *before* the `TmuxException` branch.
The same hazard reaches `POST /sessions/{id}/input`, which cannot chain (its bytes go into the shared
upstream pty), so it calls `Tmux.leaveCopyMode` first and answers **409** when that provably fails instead
of `ok` for discarded input; the interactive terminal WS deliberately does neither. Two rules keep that
endpoint honest: `leaveCopyMode` answers `true` **only** for an answered `#{pane_in_mode}` of `0` or a
soft absence — a wrong `tmuxPath`, a half-dead server or unparseable output is `false`, because an
unanswered cancel is not proof the pane will deliver — and the sink `&&`s that with
`TerminalBridge.write`, which returns whether the **full pty write completed without throwing**. The
bridge is lazy, so with no subscriber there is no upstream and definitely no write; a pty write error is
different because the real loop may have written a prefix before a later syscall failed. The shared
Boolean therefore means “full pty write completion observed,” not “the agent consumed the body,” and the
`409` warns callers to inspect before resending because a whole-body retry can duplicate commands or
paste content. `Broadcaster` uses a dedicated upstream-I/O gate (separate from output fan-out): a write
owns its handle until it returns, and teardown unpublishes the handle, calls `PtyHandle.prepareClose`
(bounded child termination, which unblocks a full-queue write **without** freeing the master fd), then
drains the gate before the final close. Never let the handle escape that gate — an fd freed during a
stale write can be reused by another session. An EMPTY body short-circuits to `ok` before
either runs: cancelling
copy-mode is a shared-pane side effect that would yank every viewer out of their scrollback for a
guaranteed no-op (`Tmux.sendKeys` guards the same way).
**Two residuals are recorded, not fixed** (`TMUX_SERVER_OPTIONS`' "Known residuals", plus `terminalSeed`
and `terminalWs`): (1) because copy-mode is shared *pane* state and the WS deliberately does not cancel,
a wheel scroll in browser tab A silently swallows everything typed in tab B or `kotgent attach` — silent
loss on the primary input path; (2) tmux resolves a mouse event against the ONE upstream client's window,
so under "last active" resize only the subscriber that resized last has a fully live wheel and a larger
tab's is dead over the lower/right of its viewport. Both need per-subscriber state in `Broadcaster`
(subscriber-agnostic about input today) — and (2) also a resize-policy rethink — so neither is a local fix.
Two smaller obligations ride along: `kotgent attach` writes `TERMINAL_MODE_RESET` in the same `finally` as
`tty.restore()` — all three mouse trackers (`1003`/`1002`/`1000`) **before** the SGR encoding `1006`, or a
surviving tracker degrades to the legacy X10 encoding, plus `2004`/`2031`/`1049`/`25` and application
keypad reset `ESC >` for the `ESC =` xterm-256color enables — and the browser terminal sets
`macOptionClickForcesSelection: true` (xterm.js disables
selection under mouse reporting — Option-drag on macOS, Shift-drag elsewhere). **`focus-events` is deliberately NOT set.** Focus has no single answer when
one upstream client serves N subscribers, and kotgent has better signals (the lazy `TerminalBridge`'s
subscriber count, agent state over hooks); it doubles as the decoy in the isolation integration test
*because* kotgent never sets it, and a unit test pins its absence so forcing it fails there first rather
than quietly making the isolation test unfalsifiable.

**Session identity is `pane_id`, not inherited env.** The logical key is the `tmux` session name
`kt-<shortid>`; the runtime correlation key is the pane id (`#{pane_id}`), recaptured from live panes on
daemon start. Hooks report `$TMUX_PANE`. **Never trust an inherited env var** (`KOTGENT_SESSION_ID` is a
debug label only) — env is poisoned across nested shells/agents.

**Two keys, one authorization rule.** Access is guarded by **two** distinct secrets with different roles,
and `authorize(...)` (`src/transport/Authorization.kt`) is the single pure function that decides every
request:

- **Master token** (`~/.kotgent/token`, `0600`) = the *machine* key: CLI `Bearer`, provider hooks, and
  ticket issuance. Inside the daemon it is **not** a captured `val` but an atomic provider (`TokenHolder`
  over `kotlin.concurrent.atomics.AtomicReference` — readers run on every request including the non-suspend
  WS handshake, so a `Mutex` won't do). `rotate()` re-mints it **persist-then-publish** (a failed persist
  leaves the old token live) and there is no grace period — the old key stops authenticating *new* requests
  at once; already-open WS survive because auth is computed once, in `Plugins`.
- **Session cookie** = the *browser* key: `kotgent_session=v1.<issuedAt>.<hmac>` where
  `hmac = HMAC-SHA256(master-token, "v1|" + issuedAt)`, `HttpOnly; SameSite=Strict; Path=/`, `Max-Age` 10y
  (Safari drops a session cookie on restart), `Secure` only when the request arrived on the public host
  (never on `http://127.0.0.1`, which the browser would silently discard). It is **stateless — there is no
  session table and no schema migration**: the cookie verifies by recomputing the HMAC, so "revoke every
  device" is exactly `token rotate` (every HMAC dies together). The cookie is read/written via Ktor
  core-API (`RequestCookies`/`ResponseCookies` are in the native klib — do not hand-roll a `Cookie:`
  parser).

The **Origin rule**: an `Origin` is **required on any non-GET request and on every WebSocket handshake**
(detected by the `Sec-WebSocket-Key` header, not the path), and **checked for a match whenever it is
present**. It is *not* required on same-origin GET — browsers don't send one there, and demanding it would
kill the whole UI. Safe anyway: cross-site `fetch` is always CORS-mode (carries `Origin`), every state
change is a POST, and the WS handshake — the one browser channel that bypasses CORS — always carries
`Origin`. `SameSite` alone is insufficient because sibling `*.example.com` hosts are the same *site*.
`Bearer` never needs an `Origin` (it is not a browser). **The published surface is browser-only**: hook
ingress, ticket issuance and token rotation are additionally **loopback-only** (`Route.loopbackOnly {}`,
gated on `Host` via `isLoopbackHost`, which ignores the port — harnesses bind `port = 0`).

**No master token in a URL; the one-time ticket is a short, rate-limited code.** The old
`?token=`/`#token=` forms are gone. `TicketStore` issues one 8-character Crockford-base32 code (40 bits,
alphabet without `I`/`L`/`O`/`U`), held in memory for 5 minutes and redeemable exactly once; input is
case-insensitive, ignores spaces/dashes and normalizes `I`/`L` → `1`, `O` → `0`. A typed code and the
credentialed link returned by `kotgent web --print` spend the **same** value — do not add a longer parallel
ticket, because the record is only as strong as its shortest credential. In a link it rides only in the
URL **fragment**: `GET /auth` never sees it (so no prefetcher/scanner/Cloudflare log can burn or leak it),
and the page `POST`s it to `/auth/exchange`, which burns it and sets the cookie. Normal `kotgent web`
opens the bare local `/auth` form and prints the unspent code. The phone dialog's QR is also deliberately
credential-free: it points at the public `/auth` page, whose install metadata lets Safari add the app to
the home screen without spending the code the installed PWA will need.

Forty bits is acceptable only with the compensating `ExchangeRateLimit`: one daemon-wide,
`Mutex`-guarded rolling budget of 10 failed redemptions per minute. It is global because cloudflared
connects from loopback, so a per-IP key cannot identify the remote browser. An admitted attempt reserves
capacity before the unauthenticated body is read, then bounds that intake to 1024 bytes and five seconds;
it is settled from a non-cancellable `finally`. A miss becomes a timestamped failure, while success,
malformed/oversized/timed-out input, and cancellation release the reservation without charging it.
Without the in-flight reservation, slow uploads or concurrent guesses could consume unbounded resources
or all pass the check before any failure was charged. Requests rejected by Host/Origin never reach
admission; every early response that leaves a body unread schedules the pinned CIO connection itself to
close after a 100ms response-flush grace, because cancelling only Ktor's exposed request channel does not
wake its raw socket parser. Failure ages come from monotonic elapsed time, never wall timestamps, so an
NTP/sleep-wake rollback between sparse requests cannot extend the one-minute recovery. Keep this control
with the short format.

**An installed iOS PWA has a separate cookie jar from Safari.** The phone QR therefore opens the
credential-free public `/auth` page: Safari can add it to the home screen without being signed in, and
cannot accidentally spend the one code the installed PWA needs. The manifest's `start_url` is `/`, so the
installed app launches there with an empty cookie jar; its first `/sessions` `401` uses
`location.replace("/auth")` to reach the form and exchange the typed 8-character code. Only the first-load
`401` redirects — a later rotation must leave the live UI and its terminal visible instead of navigating
out from under it.

**Web Push is payload-less and edge-triggered.** After startup reconciliation, `PushNotifier.start` seeds
`AttentionTracker` from the settled session list, installs the replay-free
`reliableSessionUpdates` subscription, and returns only when both are ready; `Commands.daemon` awaits that
barrier before binding the server, so no accepted hook can land in the seed-to-subscribe handoff. The
ordinary `sessionUpdates` flow remains a 1024-entry `DROP_OLDEST` UI signal whose periodic snapshot can
self-heal. Notification edge tracking cannot drop an intermediate leave/re-entry, so the store publishes
the same committed order to an unbuffered companion that backpressures only until the notifier's
constant-time collector receives each update. The tracker records only a `false → true` waiting transition
(`state.needsAttention && !archived`), so restart does not ring again and archived sessions are not
targets. Potentially slow delivery runs in a separate worker with one conflated pending wake: payload-less
push always makes the worker fetch the complete `/sessions` list, so retaining every stale session id adds
no information and would make memory unbounded. `PushSender` POSTs an empty RFC 8030 message with VAPID,
TTL and a per-session `Topic`; this deliberately avoids RFC 8291 payload encryption (`p256dh`/`auth` are
stored now for that future path). The service worker wakes, fetches `/sessions` with the session cookie
under a ten-second abort deadline and shows one notification per waiting session (or a generic notification
when the fetch fails or stalls). Permanent `404`/`410` endpoints are pruned; other delivery failures are
logged and never make the daemon unhealthy.

**Push permission ordering is a user-gesture invariant on iOS.** The notifications toggle must call
`Notification.requestPermission()` before its first `await`; only after permission resolves may it await
root service-worker registration, `GET /push/vapid-key`, `pushManager.subscribe`, and
`POST /push/subscribe`, in that order. Awaiting worker readiness or any other async operation before the
permission request leaves the click's user-activation task and prevents iOS from showing the prompt.
Transitions carry a monotonically increasing generation and a ten-second deadline, so a stalled
reconciliation cannot block a later off click forever. Turning off starts an idempotent daemon delete from
the remembered endpoint before it awaits browser subscription lookup, then drops the browser subscription;
late browser or daemon mutations queue a coalesced repair of the newest choice, and `storage` events apply
that same generation invalidation across open tabs because this is a per-device setting. A VAPID replacement
may destroy an existing subscription only when its stored application-server-key bytes prove that the key
differs.

**The PWA is network-only, and its root files always revalidate.** `/sw.js` stays at the origin root so its
scope covers the whole app and its push permission; its `fetch` handler deliberately does not call
`respondWith`, because an offline shell cannot show useful daemon state. Both `index.html` and `sw.js`
are served with `Cache-Control: no-cache` ("revalidate", not "never store") so an old shell or push handler
cannot remain pinned after an upgrade. Keep the root scope, network-only fetch contract, and the targeted
cache rule together.

**The mobile terminal lifecycle has four coupled invariants.** Initial xterm geometry is computed from
`window.visualViewport` before fitting and opening the terminal WebSocket, so the upstream starts at the
visible keyboard-constrained size. A terminal tap focuses xterm's helper textarea synchronously from that
gesture, and the textarea stays at `16px` to prevent Safari auto-zoom from corrupting viewport geometry.
The phone key bar sends binary terminal bytes and preserves xterm focus (special keys never become resize
text frames). Finally, a terminal socket lost to suspension **or to a daemon restart** is reattached from
one remembered candidate, and every rule around it governs *what may spend that candidate*. Four sites
grant an attempt: a fresh attachment (selecting a live session, an explicit attach, a resume), a foreground
transition, and the events socket's **re**-open — the only signal the daemon came back. A grant must be
written after that site's `cancelReattach()` and inside its alive branch, or it is revoked the instant it
is made. The attempt fetches fresh daemon liveness under a deadline, then re-checks the active id,
visibility, request ownership and pending control action before opening. What it does on the way *out* is
the load-bearing half: **explicit intent and a definitive answer destroy the candidate; transient
conditions keep it.** A different active session, a `cancelReattach()`, and a `4xx` — the daemon
answering about *this* session, gone or unreadable by this client — are final, and re-asking them on
every later grant would loop forever. An in-flight control action and an unreachable daemon are not
final: they keep the candidate for the next grant, which is what makes a restart recover on the
reconnect instead of waiting out a visibility change or the 10 s deadline. `isDefiniteAnswer`
(`lib/api.js`, over a `status` the `apiRequest` error now carries) is the one place that distinction
is written. Hiding or explicit intent aborts the owned liveness request so an older same-id completion
cannot consume a newer attempt, and the events effect reaches the reattach callback through a **ref**,
never a dependency: rebuilding that socket resets its effect-scoped `opened` latch, after which no open
ever reads as a recovery again.

**VAPID uses `/usr/bin/openssl`, but openssl never owns the private-key file.** `VapidKey` generates the
P-256 PEM and `OpensslVapidSigner` signs ES256 through the existing CLOEXEC-safe `ProcessRunner`; the
absolute system path avoids Homebrew/launchd PATH drift. Generation omits openssl's `-out` (which creates
a private key under the process umask and was observed as `0644`): PEM bytes return on stdout and
`createPrivateFileExclusive` persists `~/.kotgent/vapid.pem` as `0600`, first-writer-wins. Key creation and
public-point extraction are lazy on the first `GET /push/vapid-key`; failures make push unavailable without
stopping the daemon, and JWTs are cached per push-service origin.

**SHA-256 and HMAC are pure Kotlin** (`src/crypto/`), *not* CommonCrypto via cinterop — because of KT-78062
(custom cinterop does not link into the test binary), the same reason the PTY path is behind an interface.
`randomBytes`/`hex` live once in `Auth.kt`/`Hex.kt`; don't add a second entropy source or hex encoder.
`base64Url` in `Base64Url.kt` is the sole wrapper around Kotlin's standard unpadded RFC 4648 §5 encoder
(browser VAPID keys, JWT parts, push topics). Do not add another implementation; decoding stays
browser-side until an encrypted-payload path actually needs it in Kotlin.

**No CIO TLS on native — server OR client.** `ktor-server-cio` for `macosArm64` has no `sslConnector` (a
JVM-only API — verified in the klib), so the daemon cannot terminate TLS itself. Remote/phone access goes
through a **cloudflared named tunnel + Cloudflare Access** (the public host is `config.json`'s `publicUrl`,
passed into `KotgentServer` by the constructor — transport never reads config files itself). The daemon
trusts the inbound `Host` for the allowlist; if cloudflared ever rewrites it, switch to `X-Forwarded-Host`.
`Secure` is derived from "Host matched the public host", **never** from `X-Forwarded-Proto` (a local client
forges that and would set a `Secure` cookie on loopback). The client side has the same native constraint:
`ktor-client-cio` reaches a hard "TLS sessions are not supported" path, while every Web Push endpoint is
HTTPS. `DarwinPushTransport` therefore uses `HttpClient(Darwin)`/NSURLSession, a mandatory timeout and the
macOS system trust store; do not switch the push edge to CIO.

**Ktor's native `start()` HIJACKS SIGINT/SIGTERM — take them back, after it.** On Kotlin/Native
`EmbeddedServer.start()` installs its shutdown hook as literal `signal(SIGINT, …)` / `signal(SIGTERM, …)`
(`ShutdownHookNative.kt` in `ktor-server-core`; visible in our own linked binary as two bridge calls
passing `2` and `15`), and its handler **only calls `EmbeddedServer.stop()` — it never exits**. So the
kernel's default "terminate" disposition is gone the moment the server starts, and a Ctrl+C on a
foreground `kotgent daemon` used to stop the HTTP engine and then leave the process parked forever in
`awaitCancellation()`: no `LISTEN` descriptor, only CLOSED accepted sockets, SQLite and the tty still
held, serving nothing — while a later daemon happily took the freed port. (Observed in the wild; `kill
-HUP` was the only thing that still killed it, because SIGHUP is not one of the two Ktor grabs.)
`Commands.daemon` therefore calls `installShutdownSignals()` (`src/sys/Signals.kt`) **after**
`KotgentServer.start()` — `signal(2)` keeps the last handler, so the order IS the fix — and parks in a
100ms poll of the flag instead of `awaitCancellation()`, then tears down in order (server → `bgScope` →
driver). The handler only stores an int (nothing else is async-signal-safe — same idiom as
`AttachClient`'s SIGWINCH flag) and restores `SIG_DFL` for the signal it took, so a second Ctrl+C hard-kills
a wedged teardown. `SignalsTest` raises the real signals; `transport/ShutdownSignalsTest` guards the
ordering by asserting the server still serves after a SIGINT. **Any future `start()` of a Ktor server in a
long-lived process owes the same re-installation.**

**Storage = SQLDelight via the custom plugin + `native-driver`.** Schema is `sqldelight/*.sq`; the
`sqldelight-gen` plugin generates the typed API at build time; the runtime driver is
`app.cash.sqldelight:native-driver`. `SqliteEventStore` is single-writer (a `Mutex`), WAL, and appends the
event + updates the session cache in **one transaction**. A JSONL fallback was designed but not needed; the
`EventStore` interface isolates the choice regardless.

**Schema migrations: the `sqldelight-gen` plugin DROPS `.sqm` files.** It runs with
`deriveSchemaFromMigrations = false` / `verifyMigrations = false`, and filters `MigrationFile`s out under
exactly those flags, so a `.sqm` never contributes and the generated `Schema.migrate()` stays an empty
body (`Schema.version` is 1). **Do not add a `.sqm` and expect it to run.** Additive columns are migrated
by a hand-rolled `ALTER` in `SqliteEventStore.init`, **guarded by a `PRAGMA table_info` existence check**
(`driver.hasColumn("sessions", "archived")`): on a fresh DB the column is already in `create()` from the
`.sq`, so nothing runs; on a pre-existing DB the `ALTER` adds it. **Do not "simplify" the guard back into
a `runCatching { ALTER … }`** — sqliter *logs* a failing statement (`error while compiling: … duplicate
column name`, plus a full stack trace) before the exception is ever thrown, so the swallow-it version
printed that wall of red on **every** daemon start for a pure no-op. With the guard, a failing `ALTER`
propagates, which is right: the column really was missing and every session write would fail anyway. The
`archived` column was added this way; an `EventStoreTest` opens the store over a pre-`archived` schema and
then re-opens over the migrated one to prove both paths.

**A wholly new table uses `CREATE TABLE IF NOT EXISTS`, not the column-migration idiom.** A fresh database
gets the table from its `.sq`, while the owning store's `init` repeats matching DDL for old databases.
`SqlitePushStore` does this for `push_subscriptions`. `IF NOT EXISTS` is already idempotent and does not
log an error for an already-present table, so it needs **no** `PRAGMA table_info` guard; keep the runtime
DDL in sync with the `.sq`.

**Three orthogonal session fields set outside the reducer:** `archived` (the "Done" flag — kill then hide;
`setArchived`), `model` (best-effort provider model; `setModel`) and `read_cursor` (the unread badge;
`markRead`). None is a reducer/control-state concern, so each has its own targeted `EventStore` write that
leaves `state`/`last_seq`/`provider_session_id` untouched. `model` is captured after launch — Claude from
the hook payload's `transcript_path` (a default-wired `ClaudeModelCapture` behind `hookRoutes`'
`onHookPayload` seam, so no `Server.kt` change), Codex by polling the rollout's `turn_context` (a
`SessionManager.captureModelInBackground` seam). A miss just leaves `model` null. Only `archived` rides
`SessionUpdate` (live+resync must agree, or the row flickers); `model` rides only the snapshot/resync form
of the `/events` DTO, which marks itself (`snapshot: true`, the wire discriminator — `encodeDefaults` makes
a live frame's untracked `model` serialize as the same `null`) and which app.js merges VERBATIM — null
included, so a model the provider-id rebind correction cleared clears in a connected UI too. `read_cursor`
is the only **client-driven** one: `app.js` POSTs
`/sessions/{id}/read` for the session it displays, from three **imperative** triggers (selection, every
`/events` frame for the active session — the 15 s resync doubles as the heartbeat that heals a lost POST —
and `visibilitychange`), never a `useEffect` on `[id, lastSeq, unread]`, whose primitives are unchanged
after a failed POST so an effect would never retry. Monotonicity and the clamp live in SQL
(`setReadCursor`), which writes **no `updated_at`** (viewing is not activity; `kotgent list` sorts by it) —
hence `markRead` takes no clock. Each rule has one home in the code: SQL semantics in `Sessions.sq`, the
storage contract on `EventStore`, the browser trigger in `app.js`. The CLI marks nothing read.

**PTY via `openpty` + `posix_spawn` (NOT `forkpty`).** `Pty` opens the master with `openpty` and spawns the
child with `posix_spawn(POSIX_SPAWN_SETSID)`, marshalling all C strings **before** the spawn. `forkpty`
(fork-without-exec) is unsafe for the Kotlin/Native runtime — Kotlin allocation / a GC safepoint in the
forked child can deadlock. A dedicated reader thread does the blocking `read()` into a `Channel`
(there is no `Dispatchers.IO` on native).

**`Pty.close` joins the reader before releasing the master fd, exactly once.** Coroutine cancellation
cannot interrupt a native thread blocked in a C syscall, so the reader polls the master plus a private
wake pipe. Close terminates/reaps the child, signals that pipe, cancels and joins the reader, and only then
closes the master; closing the master first frees its descriptor number while the stale reader can still
run and consume bytes from a new session that reuses it. An atomic claim gives the whole teardown one
owner, and concurrent or later callers await the same completed exit code instead of repeating any raw
close. `prepareClose` remains the separate first phase used to unblock a full master write without
releasing the fd. The `ptycheck` ordering check holds a slave open and snapshots the independent reader
job at the actual master-release helper; moving only that helper above the unchanged wake/cancel/join
sequence was verified to produce `SUMMARY total=11 failed=1 skipped=0`. Do not weaken the check back to a
descriptor-validity query immediately before its own close — that observation is tautological.
The SIGTERM grace is bounded by `TimeSource.Monotonic`, not by adding the requested `usleep` intervals:
macOS may resume a 5 ms sleep much later under load, and the old accounting stretched the nominal two
seconds to 7–10+ seconds on hosted runners. Set `KOTGENT_PTY_CLOSE_TRACE=1` before opening a pty to emit
flush-on-write close stages, syscall results and elapsed time to stderr; `PtyTest` enables it for the
out-of-process helper and only surfaces the captured trace when a check fails.

**A `posix_spawn`ed child gets NO controlling terminal, so `Pty.resize` must send `SIGWINCH` itself.**
`ioctl(TIOCSWINSZ)` raises `SIGWINCH` on the tty's **foreground process group** — and this pty has none:
the child opens the pts through a posix_spawn **file action**, and the kernel runs that open *without*
`open(2)`'s implicit `TIOCSCTTY`, so the pts ends up with no session and no pgrp (`ps` shows `TT ??` for
the child; `ps -t <pts>` lists nobody). Nothing needs a ctty — `tmux attach` runs fine on fd 0/1/2 (that
is what the pts-by-path file action is really for; a dup2 of an inherited slave fd fails with "open
terminal failed: not a terminal") — but it means a `TIOCSWINSZ` alone reaches **no one**: a resize applied
while `tmux attach` is already running was silently dropped, and only a size set before the client's
startup `TIOCGWINSZ` ever took effect. That was the "a new session's terminal renders 80x24 until you
detach and re-attach" bug: the browser's size arrived milliseconds too late, was remembered as `lastSize`,
and only got applied on the *next* attach. `Pty.resize` therefore does the ioctl **and** `kill(-pid,
SIGWINCH)` (the child's pgid == its pid under `POSIX_SPAWN_SETSID`, mirroring the kernel's foreground-pgrp
delivery; skipped once reaped, since a pid can be recycled). Guarded by the `ptycheck` check "a resize
reaches a running tmux attach" — verified to FAIL (`window is still 80x23`) with the `kill` removed. Any
new pty path owes the same signal.

## Load-bearing toolchain gotchas

These are real and cost time to rediscover. Respect them.

- **KT-78062 — custom cinterop klibs do NOT link into TEST binaries.** An auto-discovered cinterop klib
  reaches the **main** binary only; calling it from a test binary throws `IrLinkageError` at the call
  site (partial linkage stubs the symbol). **Root cause** (read out of the toolchain's own bytecode):
  `TaskBuilderNativeKt` registers the cinterop-klib task once, for the **non-test** fragment
  (`isTest=false`), while `NativeLinkTask` selects `CinteropKlibsArtifact` with **its own** `isTest` —
  so for a test link nothing matches. Nothing in YAML can fix it: a relative `-library` path cannot work
  (the compiler subprocess runs with `workingDir = kotlinNativeHome`), `module.yaml` has no `${…}`
  interpolation ("References are not yet supported in this file"), and neither `exported: true` nor a
  duplicate entry in `test-dependencies` changes the selection. **Verified still broken on toolchain
  0.11.0, 0.11.1 and 0.12.0-dev**, and reproducible in a one-module toy project.
  **Two-part mitigation, and the pattern to follow:**
  1. Keep raw cinterop behind a pure-Kotlin interface (`PtyHandle`, `LocalTty`, `TmuxControl`) with a
     `Fake…` for tests, so the *logic* runs for real in the test binary, and wrap the actual cinterop in
     a single thin class (`RealPtyHandle`, `NativeTty`).
  2. For assertions that genuinely need the real cinterop, put them in the **`ptycheck` main binary**
     (main binaries link it fine) and drive them from the suite — `PtyTest` execs it. That is how the
     former 5 `@Ignore`d tests run today; **the suite has no skips left.**
  **This does NOT affect** third-party klibs that carry cinterop (Ktor, `native-driver`/`sqliter`) or the
  stock `platform.posix` bindings — those link into test binaries fine. Never reintroduce a `-library`
  path hack to work around it, and never fake a skip into a pass.
- **`posix_spawn` is absent from `platform.posix`.** It lives in `<spawn.h>`, which is not in the
  `platform.posix` macOS header set (that's why `Pty` needs its own `pty.def`). So `ProcessRunner` (which
  must spawn from the *test* binary, and therefore cannot use custom cinterop) uses **`popen`/`pclose`** —
  `popen` forks inside libc (no K/N code runs in the child) and is stock `platform.posix`.
- **WAL `PRAGMA` via `executeQuery`, not `execute`.** `PRAGMA journal_mode=WAL` returns a row, so
  sqliter's `execute()` throws ("query/rawQuery only"). Use `executeQuery`.
- **Generated / test-visible symbols must be `public`.** Kotlin Toolchain 0.11 has no friend-module
  relationship between a module and its `test`, so `internal` is not visible to tests, and generated
  sources must be `public` (SQLDelight already generates public API, so it's moot there — but keep it in
  mind for anything the tests need to see).
- **`kotlin.system.getTimeMillis()` is ERROR-level deprecated** on Kotlin 2.4.10 (a hard compile error).
  Use `kotlin.time.Clock` (e.g. `Clock.System.now().toEpochMilliseconds()`), and prefer injecting a
  `now: () -> Long` so tests stay deterministic.
- **A plugin task cannot see a native link, and its stdout is not a channel.** The `kexePath` /
  `releaseKexePath` commands live inside three measured limits of the 0.11.1 plugin API, and each one
  shapes the code. (1) **No reference names a native executable or the build root.** `ProjectDataForPlugin`
  exposes `rootDir` and nothing else; `module.jar`/`classes` are `CompilationArtifact.Kind` = `Jar|Classes`
  only, and `module.runtimeClasspath` on a `macos/app` module aborts the CLI with
  `IllegalStateException: Dependencies for JVM are not calculated`. So both paths are derived from
  `${taskOutputDir}` — the link task's directory is its sibling under `tasks/`, the build root is that
  directory's parent. That is what makes `--build-dir` work; the original `${project.rootDir}/build`
  silently reported an artifact the run never produced. The parameter must **not** be called
  `taskOutputDir` (a name matching its own reference is rejected as a reference loop) and must carry
  `@Input(inferTaskDependency = false)`, or every toolchain invocation warns that a build-directory input
  is "not produced by any other task". (2) **There is no task-dependency syntax, and `@Input` does not
  infer one from a built-in task** — verified: adding the annotation left `show tasks` at
  `printKexePath@build-info -> build-info:runtimeClasspathJvm`. The commands therefore *report* the last
  build; they cannot trigger or order after one, and the docs must not claim otherwise. (3) **An action's
  `println` and `System.err` both go through the build log** (a raw `FileDescriptor` write is the only way
  around it), so the log is for humans and the machine-readable answer is the `build/kexe-path` record —
  deleted before the lookup so a failed run leaves no stale answer for a script that skipped the exit
  code. `UserReadableError` is CLI-internal, so a failure is rendered `ERROR: <class>: <message>`;
  `MissingExecutableException` suppresses its stack trace to keep the actionable sentence readable.
- Native links print harmless `'+zcm' is not a recognized feature` lines that the log formatter labels
  ERROR. They do not affect the build (`Build successful`); ignore them.

## Testing & running

- Every change keeps `./kotlin build` and `./kotlin test` green. Baseline: **765 native tests passed /
  0 skipped**, plus the build-info plugin's 7 JVM tests (and `ptycheck`'s 11 real-PTY checks, driven by
  `PtyTest` — keep its `EXPECTED_CHECKS` in sync when adding one).
- **Run `./kotlin build` before `./kotlin test`.** `PtyTest` execs the `ptycheck` binary, and
  `./kotlin test` never links a main binary (not even its own module's) — the test says so explicitly
  instead of silently passing when the binary is missing.
- Changed JavaScript ES modules must pass `node --check <file>`. There is deliberately no JavaScript test
  harness: source/serving contracts live in `test/transport/WebUiServingTest.kt`, every newly served
  module must be registered there, and browser behavior remains part of the manual verification checklist.
- Bound every Flow/WS/PTY test with `withTimeout(...)` (anti-hang) — the suite does this consistently.
- `tmux` integration tests use a throwaway `-L kotgent-test` socket with a skip-guard and kill it in
  teardown; they never touch the real `-L kotgent` socket. `ptycheck` follows the same rule.
- **In automation, do not run the daemon or anything that spawns a real agent.** Avoid `kotgent daemon`,
  `./kotlin run -m kotgent`, a real `claude` / `codex`, and `launchctl` — they start long-lived /
  interactive processes. Prefer the terminating `./kotlin build` / `./kotlin test`. Running the `ptycheck`
  binary directly is fine — it terminates, and only touches the throwaway `-L kotgent-test` socket.
- Inspecting a provider's CLI is fine and often necessary (`codex --help`, `codex app-server
  generate-json-schema --out <dir>`, `hooks/list` over an `app-server --stdio` pipe) — those terminate and
  touch no model. Do NOT start a turn (`codex exec`, `turn/start`), and do not write into `~/.codex` or
  `~/.claude`: a probe must be readable-only against the user's real home.

## Where things live

```
module.yaml / project.yaml     build manifests (root app + sysnative + ptycheck + build plugins)
version.txt                    single source of the application release version
src/core/                      host-free domain: AgentEvent, SessionState, SessionMeta, Ids, Reducer, Projection
src/crypto/                    Sha256, Hmac, Hex, Base64Url — canonical pure-Kotlin encoders/digests
                               (KT-78062: no CommonCrypto in the test binary)
src/store/                     EventStore interface + SqliteEventStore (SQLDelight)
src/pty/                       TerminalBridge, Broadcaster, PtyHandle (iface), RealPtyHandle
src/sys/                       Cloexec (FD_CLOEXEC sweep run before every spawn), Locale (UTF-8 LANG rule),
                               Signals (SIGINT/SIGTERM taken back from Ktor's shutdown hook)
src/tmux/                      Tmux, TmuxControl (iface), ProcessRunner (popen),
                               TmuxOptions (-f /dev/null isolation, forced server options, tmuxCommand argv builder)
src/adapter/                   AgentAdapter, LaunchSpec; claude/ + codex/ (Cli, HookConfig, HookNormalizer, Adapter)
src/daemon/                    SessionManager, Reconciler, ProviderIdCapture, VendorSessionLocator,
                               Claude/Codex vendor-store probes
src/push/                      AttentionTracker, subscription store, VAPID key/JWT/signer, Darwin sender, PushNotifier
src/transport/                 Server, Auth/Authorization, session cookies, tickets/rate limit,
                               auth/push/control/event/terminal/hook routes
src/cli/                       Cli (parseArgs), ApiClient, AttachClient, Commands, Config (~/.kotgent/config.json)
src/launchd/                   Plist, Install
sysnative/cinterop/pty.def     ALL raw cinterop (PTY, tty-raw, executable-path C helpers)
sysnative/src/                 Pty, NativeTty, NativeExe (thin cinterop wrappers)
ptycheck/src/Main.kt           real-PTY checks run from a MAIN binary (KT-78062); driven by PtyTest
sqldelight/io/kotgent/db/      Events.sq, Sessions.sq, PushSubscriptions.sq (schema + typed queries)
plugins/sqldelight-gen/        the jvm/amper-plugin that runs SQLDelight codegen at build time
plugins/build-info/            generates VERSION + an embedded Git revision at build time
resources/webui/               no-build Preact PWA, network-only root service worker, manifest/icons,
                               mobile terminal controls/lifecycle, vendored ESM; /auth is a string
                               constant in AuthRoutes.kt
docs/plans/                    implementation plans
```
