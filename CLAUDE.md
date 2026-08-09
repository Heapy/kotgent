# AGENTS.md — working in the kotgent repo

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
- **`ProviderSessionId` is a SAFE-CHARSET id, not a UUID.** Claude and Codex mint UUIDs but Junie does not
  (`session-260730-015553-1j1h`), so the core invariant is what every provider's id must actually satisfy:
  non-blank, ≤128 chars, `[A-Za-z0-9._-]`, first character alphanumeric (which keeps `..` — a path
  component that escapes its parent — and `-…` — a value a CLI reads as a flag — out). Where UUID-ness is
  load-bearing the BOUNDARY checks it explicitly with `isCanonicalUuid`: the Claude/Codex hook normalizers
  (an untrusted callback body must not bind arbitrary text), `CodexRolloutScan.rolloutFileSessionId` (a
  36-char tail is an id only if it is a UUID), and `SessionManager.importSession`'s lowercase
  normalization, which applies **only** to a UUID-shaped id — lowercasing is UUID case-insensitivity, and
  doing it blindly would corrupt an id whose case is significant. Keep new such checks at the boundary.

**Three providers, one shape.** `claude`, `codex` and `junie` are all launched as a **TUI inside `tmux`**
and all report through **hooks → a local HTTP ingress → the normalizer**. Adding a provider means an adapter
(launch spec + hook config + normalizer), an ingress route, a `VendorStoreProbe` (registered in
`productionVendorStoreProbe`, `src/daemon/Reconciler.kt` — no longer inlined in `Commands`), a
`VendorSessionLocator` (registered in `productionSessionLocator`, `src/daemon/VendorSessionLocator.kt` —
without it import discovery silently answers null for the new kind, forcing `--cwd`), and an entry in
`agentFactoryOf` — nothing in `core/`, the store, or the fan-out changes. What differs between them:

- **Hook delivery.** Claude takes a settings FILE (`claude --settings <path>`). Codex has no such flag, so
  hooks ride in the argv as `-c 'hooks={…}'` — verified to resolve as `source: sessionFlags`, i.e. scoped
  to that one launch. **Never** write kotgent's hooks into `$CODEX_HOME/hooks.json` or a `[hooks]` table in
  the user's `config.toml`: both resolve as `source: user` and would fire for every codex session the user
  runs. Codex also marks an unseen hook `untrusted`, hence the companion `-c bypass_hook_trust=true`.
  Junie takes an extra config FILE (`junie --config-location <path>`), the one hook layer scoped to a
  single launch — and the only one Junie honors even for an untrusted project. **Never** write into
  `~/.junie/config.json` (the USER layer: it would fire for every junie session the user runs); a
  project-level `.junie/config.json` is worse still, since Junie deliberately IGNORES `hooks` from it.
- **Provider id.** Claude preallocates (`--session-id <uuid>`). Codex cannot, so the id is captured after
  the fact: the `SessionStart` hook if it fires, else `CodexRolloutScan` reading
  `~/.codex/sessions/<date>/rollout-<ts>-<id>.jsonl` (id in the file NAME, `cwd` in the first line). The
  hook wins over the scan — it is authoritative for *this* session, the scan infers from disk. Junie is the
  codex shape with one measured twist: its `--session-id` only names an EXISTING session to resume, its
  documented `SessionStart` payload carries no id, and its `sessions/index.jsonl` row appears only once the
  session has run a TASK — so `JunieSessionScan.discoverSessionId` enumerates session DIRECTORIES (which
  exist from the moment junie starts) thresholded on the directory's **birth** time, and uses the index
  only to EXCLUDE a candidate whose recorded `projectDir` is a different one. An index-only discovery would
  expire (`ProviderIdCapture` polls 20 × 250 ms) long before a human types their first prompt, leaving
  every junie session unresumable; an mtime threshold would offer a long-running session started hours ago,
  because it is still writing events. Junie ids are NOT UUIDs (`session-260730-015553-1j1h`).
- **Approvals.** Codex and Junie both fire a real `PermissionRequest`, so `needs_approval` is precise
  there; Claude maps any `Notification`. The *clearing* rule is the same for all three (see the reducer
  invariant above) — kotgent never answers an approval, the operator does, in the terminal. Junie makes
  that rule an EXIT-CODE contract: a `PermissionRequest` hook that exits `0` AUTO-APPROVES the action and
  one that exits `2` auto-DENIES it, so kotgent's hook script POSTs and then **exits 1** — the documented
  fall-through that keeps Junie's own dialog (cost: a small TUI warning per request). Never regress that
  exit to 0 or 2. Junie also PARSES a hook's stdout as a decision object (invalid JSON becomes
  `additionalContext` injected into the model's turn), so the script writes nothing to stdout at all; both
  halves are pinned by RUNNING the generated script in `JunieHookConfigTest`.
- **Resumability.** Claude namespaces transcripts per project dir (probe needs the `cwd`); Codex names a
  rollout by id alone and Junie a session directory by id alone (both probes ignore the `cwd`). Archived
  codex rollouts do **not** count — archiving puts a session out of `codex resume`'s reach — and for junie
  the DISK is the authority, not the index: Junie prunes old sessions' context, so a session whose
  directory is gone classifies honestly as `crashed` even while its index row lingers.
- **The model.** Claude reports it in the hook payload's transcript; Codex writes ONE `model` into the
  rollout's `turn_context`, so `extractModel` takes the first match. Junie records a `modelUsage` list per
  turn that mixes the primary model with helper models — and the FIRST model in the file is a helper — so
  it needs `extractDominantModel` (most frequent, ties → first seen), measured on a real session at 40
  occurrences for the primary against 6/1/1 for three helpers.

**Shell is a fourth agent kind, not a new session dimension.** `ShellAdapter` implements the same
`AgentAdapter` seam and `"shell"` is registered beside the three provider builders. This deliberately
reuses the one `SessionManager.start` / `resume` path and all generic tmux, terminal, control, archive and
reconciliation machinery; do not add a shell column, a reducer branch or a second launch path. It runs
`currentLoginShell()` with `-l`; `src/sys/LoginShell.kt` chooses the first absolute executable from
`$SHELL`, the passwd entry and `/bin/zsh`, in that order. The Web command palette's stage-4 `⌘K t`
handoff is delivered through the ordinary New-session dialog with Shell preselected, and Import mode
omits it because there is no outside conversation to adopt.

A new shell mints a synthetic UUID provider id and appends the ordinary `SessionBound`; a resume neither
mints nor embeds another id and launches the same `[shell, "-l"]` argv. The id is mechanical, not a
vendor identity: the generic resume gate and reconciler both require a non-null provider id, so minting it
for `New` only keeps those paths provider-neutral. The quiet-state limitations are intentional. While its
pane is alive, a new shell remains `running` and a resumed shell remains `ready`; its empty event flow can
never enter `needs_approval` / `needs_answer`, so it never notifies. `SessionBound` is its only event (the
unread count is one until first open), `model` stays null, and liveness is its only later state input. A
close observed while the daemon is live uses the tmux hook below; a close while it is down is caught by
startup reconciliation. tmux exposes no exit status, so an unrequested clean exit and a killed process
are indistinguishable and both classify from pane liveness, stop intent and whether the cwd still exists.

**Import is registration, not launch.** `kotgent import` / the Web UI's Import mode →
`POST /sessions/import` → `SessionManager.importSession` brings a session started *outside* kotgent under
management with **zero tmux side-effects**: it writes a full `resumable` row (provider id set,
`paneId = null`) and appends `SessionBound` via `ProviderIdCapture.bind`; the actual launch is the
existing `resume()` path (`claude --resume` / `codex resume` / `junie --resume --session-id`), so there is
no second launch codepath and
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

**A repaint is one frame, and the hold keys on the cursor being TAKEN AWAY.** A TUI turns the cursor off
before repainting and back on at the end, so a hidden cursor is the stream's own statement that the screen
is not consistent yet. Forwarding each pty `read(2)` as its own WS frame therefore ships half-drawn
screens: measured on a live claude pane, one repaint arrives as 4–6 reads (median 1 KiB, ~4.5 KiB total)
within 0–10 ms, and 1183 of 1666 hide/show pairs landed in different reads. A pair separated by `dt` is
split by the browser's 16.7 ms vsync about `dt/16.7` of the time — ~3 cursor dropouts per second, each
exactly one frame long. `TerminalBridge.readerLoop` therefore holds a read that turned a VISIBLE cursor
off until `?25h` arrives, then broadcasts the whole repaint at once. **Hold on the transition, never on
"hidden"**: a full-screen app hides the cursor for its entire run (measured — htop emits ONE `?25l` at
startup and never a `?25h`), so the "hidden" form held every later read for the full bound, batching
output into 50 ms chunks and making htop visibly sluggish. It is also pointless: a cursor that is never
drawn cannot be seen to drop out. A visible cursor is forwarded immediately, which keeps the hold off the
latency path and leaves `Broadcaster`'s per-frame overflow accounting unchanged. Both bounds fail toward
SENDING — `HIDDEN_CURSOR_HOLD` (50 ms; an app may hide the cursor forever) and `MAX_COALESCED_FRAME`
(256 KiB; memory). `CursorVisibilityScanner` carries the last 5 bytes of each chunk into the next, because
a read boundary falls anywhere — the measured stream's smallest message was ONE byte.

**Fixing this in the browser does not work, and both attempts are recorded.** Banking incoming messages
and writing once per `requestAnimationFrame` quantises by the very frame that splits the pair; measured
before and after, the dropout rate did not move (3.0 → 2.8 Hz). Holding `?25l` in xterm's parser and
replaying it after a grace does suppress the dropout, but then the cursor stays VISIBLE at the
intermediate positions a repaint walks it through — reported as "the cursor twitches on other lines",
i.e. worse than the dropout. `cursorBlink` is a third dead end: no blink is involved at all (claude sends
`?12l`, asking for a steady cursor — the browser starts steady for the same reason, since with the DOM
renderer the cursor span is rebuilt on every repaint of its row and restarts its CSS blink from the "on"
phase). xterm's own `addon-attach` writes every message straight through with no buffering — that is the
canonical shape; keep the browser dumb and fix the stream on the daemon.

**A regression test for the hold must emit both reads before asserting either.** A hold with nothing to
merge only DELAYS, so a test that emits one read, asserts it, then emits the next passes against the
broken code. The merged frame is the observable: assert `rows one` and get `rows onerows two`.

**xterm reports input on TWO events.** `term.onData` carries keystrokes and SGR-encoded mouse reports;
`term.onBinary` carries mouse reports in the legacy X10 encoding, whose coordinates are raw bytes above
127 (`CoreMouseService` routes `DEFAULT` to `triggerBinaryEvent`). Subscribing to `onData` alone drops
those silently — the mouse just stops working — and the encoding degrades that way whenever tracking
arrived without `?1006h`, the same failure `TERMINAL_MODE_RESET`'s ordering rule guards. Narrow that
payload byte-wise (`charCodeAt(i) & 0xff`), never through `TextEncoder`, and never through sticky Ctrl:
it is a pointer report, not a keystroke.

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
TUIs are unaffected. **The browser terminal therefore opens with `scrollback: 0`**, and the two halves of
that are a width and a history. The width: FitAddon reserves a FIXED 14 px for the scroll bar of any
terminal whose `scrollback` is non-zero — no measurement, no check that a bar could ever appear — which
cost about two columns of grid permanently (xterm 5.5 reserved 15 px the same way, through `Viewport`'s
"assume an OSX overlay scroll bar" fallback, so this predates the 6.0 update); the option is the one
supported way to reclaim it, because the addon short-circuits on exactly it. The history is the reason
that is not a loss, and it is **measured, and different per subscriber**. The FIRST subscriber gets the
upstream stream itself, whose third sequence is tmux's `?1049h`: the client spends the whole attach on
the ALTERNATE screen, whose buffer xterm builds with `hasScrollback = false` (`BufferSet`), so its bar
could never appear at all. A JOINER is seeded from `capture-pane` and `terminalSeed` synthesizes no
app-owned modes, so it starts on the NORMAL screen — and there tmux's line feeds do fill the scrollback
(recorded off a real client: 247 CR-LFs under a full-screen `CSI 1;24 r` region, i.e. exactly the
`scrollTop === 0` case that pushes lines out of the viewport). So the bar appeared for joiners only, and
scrolled the WRONG history: a mirror starting at the capture, reachable only by dragging it, while the
wheel reaches the pane's complete one. Zero deletes the divergent copy and makes every subscriber alike. **A joiner does not get client modes for free**: `capture-pane -p -e` carries zero
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
Residual (1) reaches the PHONE now that a swipe is bridged into a wheel (see the mobile invariants below):
the most natural phone gesture puts the shared pane into copy-mode, after which the key bar's `^C` — like
every other byte routed through the terminal WS, which deliberately does not cancel — is consumed by
`send-keys -X cancel` instead of interrupting the agent, while the header's Interrupt button stays immune
because `Tmux.sendKeys` chains `copy-mode -q`. Recovery is discoverable (tmux paints its `[n/m]` overlay
per pane, the key bar's Esc cancels, and `copy-mode -e` auto-exits at the bottom), so this is degraded,
not broken — but it is the same unfixed residual, now on the primary input path of the smaller device.
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

**The tmux `session-closed` hook is a trigger, not truth.** Its private script posts only the closed tmux
session name. `SessionManager.onTmuxSessionClosed` then takes the same per-session control lock as
`resume`, re-reads pane liveness and the provider/cwd probe, and delegates to `Reconciler.classify` before
writing derived state. Never treat the payload as an exit event or move that read/write sequence outside
the lock: a close notification can race a resume. tmux supplies no usable child exit status, so shell
closures deliberately use the same liveness and resumability evidence as every other agent. Measured on
tmux 3.7b, the server continues answering `list-panes` while its `run-shell` hook command is running, so
the callback can safely re-derive that truth instead of trusting the notification.

The global hook must stay in the **same chained invocation** as `new-session`, after the forced server
options and before the session is created. A standalone `set-hook` cannot start a tmux server, and adding
it after creation leaves a close-before-install race. `TmuxHookConfig` writes a token-bearing `0600`
header separately from the non-secret executable script, then `Tmux.newSession` chains the generated
command through `tmuxCommand()` like every production tmux argv. Both initial daemon setup and the token
rotation callback must finish `writeTmuxHookScript(port, token)` before publishing the token used to
authenticate requests; otherwise a newly rotated daemon and its installed callback disagree.

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

**Asset invalidation is a content revision in the PATH, and the prefix is what makes it free.**
`WebUiAssets.kt`'s `webUiRevision` hashes the whole `resources/webui` tree — `sha256` per file, then
`sha256` over the sorted `"<relpath> <hex>"` listing, first 12 hex characters — and `serveStaticFile`
substitutes it for the `__REV__` placeholder in `index.html`. Hashing per file rather than over one
concatenation bounds memory to a single file AND puts the path into the result, so a rename counts too;
the sort makes the answer independent of `readdir` order. It is recomputed on **every** `index.html`
request (~910 KB, 36 files) rather than memoized, so an edit made while the daemon runs is visible on the
next reload with no cache to invalidate. **The prefix must stay a path segment, never a `?v=` query**: an
ES module specifier resolves against the URL of the IMPORTING MODULE, so substituting `/_v/<rev>/` once in
`index.html` reaches the entire import graph without touching a line of JavaScript — which is exactly what
the old hand-bumped token could not do (it versioned 3 files out of 34, and a `lib/api.js` edit shipped
under an unchanged URL). The one thing it does not reach is the `importmap`, whose targets resolve against
the DOCUMENT and therefore spell the prefix out; `sw.js`, `manifest.webmanifest` and `icons/` deliberately
stay on stable URLs, because the worker's root scope depends on its path and an installed PWA refers to
the other two by a fixed address. One caching rule follows: a valid revision in the path means the bytes
can never change under that URL → `max-age=31536000, immutable`; **everything else, prefixed or not, is
`no-cache`** — which also closed the real hole, since unprefixed assets used to be served with no caching
header at all, i.e. under the browser's own heuristic freshness. Two guards are load-bearing and must not
be relaxed. `isRevToken` gates `immutable` on `[0-9a-f]{12}`: a failed substitution would otherwise serve
`/_v/__REV__/app.js`, a URL that never changes, and pin that file in every cache forever — with the guard
it degrades to revalidation. And `neverImmutable` keeps `index.html` and `sw.js` revalidating however they
were addressed, because the shell is what hands out every other asset URL. An unrecognised revision is
deliberately **served, not 404'd** — the prefix is only stripped, never verified — since a client can hold
an old revision only from an old shell it cannot have, and refusing would break the one real race (a shell
fetched just before a daemon update asking for its assets just after it).

**The unicode addons are opt-in, and the `import()` IS the gate.** xterm ships Unicode 6 widths built in;
`@xterm/addon-unicode11` and `@xterm/addon-unicode-graphemes` are vendored beside it but appear **nowhere
in `index.html`** — `lib/unicode.js`, the one registry of modes/labels/module paths, imports one
dynamically when the device-local `Terminal unicode` preference selects it. A `<script>` tag would make
every operator download 65 KB of width tables to keep a default they never changed; the specifier stays
**relative**, so it resolves against `lib/unicode.js`'s own `/_v/<rev>/` URL and inherits the content
revision for free — which is exactly what an importmap entry (document-relative) could not do. Defaulting
to off is not timidity: a width the browser computes differently from the width `tmux` used to lay the
pane out shifts every following cell on that line, and the two tables disagree in both directions
depending on the character, so the choice is the operator's and its scope is one device — the same scope
as the terminal font size, since it changes only how THIS browser draws bytes every other viewer receives
unchanged. Three details are load-bearing. **`Unicode11Addon.activate()` only REGISTERS its provider** (the
graphemes addon is the one that sets `activeVersion` itself), so `installTerminalUnicode` sets
`term.unicode.activeVersion` explicitly — without that line the fetch happens and nothing whatsoever
changes. **Its `dispose()` is empty** and a provider can never be unregistered, only shadowed, so the
disposer restores the version captured at install time. And **loading is a separate call from
installing**: `import()` is async, two mode changes have two loads in flight, and they can resolve in
either order — a combined call would let the loser land last and leave a disposer holding a stale version
to restore, so `TerminalPane` re-checks `cancelled` between the halves and a superseded load mutates
nothing. Its effect keys on `[attachedId, terminalUnicode]`, because a new attachment is a new `Terminal`
carrying only the built-in provider. A provider governs how bytes are PARSED, so a mode change lands on
the pane's next repaint and never re-measures cells already in the buffer — under an agent TUI that is
continuous, which is why this is documented rather than fixed.

**The command palette is the home of rare Web UI actions.** `resources/webui/lib/commands.js` is the
single command and mnemonic registry: search mode renders its filtered descriptors and leader mode renders
the chord-bearing subset. Do not introduce a second list in `app.js` or a component. The sidebar brand row
keeps only the daily notification toggle (plus the structural mobile drawer close); Preferences stays
reachable from the base-path note, and an empty first run keeps its direct "Start a session" action.
Reserved future chords remain visible but disabled in this one registry until their stages are designed.

**A native `<dialog>` paints a backdrop that dismisses nothing, so `Dialog` owns both pointer gestures.**
`showModal()` gives Esc, the focus trap and the backdrop's ink — not a light dismiss — so before this every
modal was closable only from a keyboard or its own ×, and the command palette, the one dialog that draws no
`.dialog-head`, had no × at all: on a phone it could not be dismissed. The wrapper in
`resources/webui/components/dialogs.js` adds the two gestures and every screen inherits them; do not
re-implement either per dialog. **A press outside is checked against the panel's GEOMETRY, and both halves
must land there** — a press on the backdrop reports its target as the `<dialog>` itself, but so does a drag
that started on the panel and a click a native `<select>` popup lets through, so `pointerdown` and `click`
are paired and each is tested against `getBoundingClientRect()`. The arming record is **one slot holding the
most recent PRIMARY press's `pointerId`**, and the pointer must also RELEASE outside. A *set* of votes was
tried and is worse than a flag: a press that answers with no `click` at all (a secondary button reports
`contextmenu`/`auxclick`) stays armed, and the next drag out of the panel spends it — closing a dialog from
a gesture that began inside it, the exact false close the pairing exists to prevent. The accepted cost is
the opposite error, worth one tap: a second contact landing inside disarms a pending backdrop press.

**A swipe starts only from a touch pointer on `.dialog-grabber`** — the head is deliberately NOT a handle.
`dialog:modal`'s UA rule makes an overflowing `<dialog>` its own scroller with the head as its first child, so
the `touch-action: none` a swipe needs would turn "pan the sheet by its title" into a dead zone and put the
fields of a long form out of reach. The grabber scrolls nothing, so reserving it costs nothing. Its box is
scoped by **`@media (any-pointer: coarse)`, not by viewport width**: the gesture only exists for a touch
pointer, and the old `max-width: 720px` ink left the palette — the one dialog with no head, i.e. the one with
no other handle — unswipeable on every tablet, the exact device the reservation was written for. The same
query gives the palette's × a 44 px box: it sits ~16 px above an option row whose tap RUNS a command, so a
thumb that misses the desktop-sized × hits Interrupt. **That block must stay BELOW every dialog's own
`padding` shorthand in `style.css`** — a media query adds no specificity, so its `padding-top` compensation
for the handle's height wins on source order alone; written up beside the base `.dialog-grabber` rule it
computes to nothing while looking present, and the phone pays the handle's height twice.

**Every rule in the gesture fails toward KEEPING the dialog**, because what a dialog holds is unsaved and
local. The pointer is captured only after a downward slop (8 px) that also has to beat the horizontal travel
(a sweep across the sheet is not a dismissal however far it drifts); `pointerup` checks the pointer id
**before** clearing `dragRef` (clearing first let any second finger's release abandon a live swipe and strand
the panel under its transform); `pointercancel` has its **own** handler that springs back — a gesture the
platform took away is not a release, and evaluating distance there closed dialogs on an incoming call; and a
flick counts only while its speed sample is fresh (`SWIPE_FLICK_HANDOFF_MS`, 90 ms, the same handoff
`installSwipeScroll` measured), because a stationary contact emits no `pointermove` and the last sample would
otherwise stand for however long the finger then rested. The spring-back reads `prefers-reduced-motion` **in
JS**: it is an inline style, so it outranks the stylesheet and `#sidebar`'s media-query remedy cannot reach it.
A screen with work in flight opts out of both gestures with **`lightDismiss`** (`UploadFilesDialog` passes
`!busy`: unmounting aborts the request and the loop returns before it can name which files landed). The flag
is re-read where each dismissal is DECIDED, never only where a gesture starts: a screen can turn busy under a
swipe another pointer already owns, and a start-time check alone would still let that swipe close it. Esc, the
× and Cancel are never gated — those are the operator saying it on purpose.

Esc is **not** a uniform escape hatch to reason from: `cwdKeyDown` spends the first one on New session's open
cwd-completion list, so the keyboard has a layer these gestures do not, and a backdrop tap while that list is
open discards the whole draft in one step. Two residuals are recorded, not fixed: in an overflowing dialog the
grabber scrolls out of the port and the swipe goes with it (× and backdrop remain, and giving those four forms
`#help-form`'s internal scroller changes iOS focus/keyboard behaviour that cannot be verified from here), and
the palette's `type="search"` input still paints the UA's own clear × a few pixels inside kotgent's.

**Mobile file upload is a session-cwd write, never an arbitrary-path API.** The palette's `f` command opens
the native multi-file picker and `POST`s one raw file at a time to `/sessions/{id}/files?name=…`; the browser
shows the selected session's cwd but never submits a directory. The authenticated route re-reads that
session row and supplies its stored cwd to `FileUploader`, while the filename gate accepts one leaf only
(no `/`, dot entries, control/NUL characters, or overlong UTF-8 component). Production streams at most 100
MiB under a ten-minute deadline into a `mkstemp` sibling (`0600`), `fsync`s and closes it, then publishes via
`link(2)`: an existing file or symlink wins with `409`, never gets overwritten, and every failed, cancelled,
oversized, or timed-out request unlinks its partial temp. Keep the UI's multi-file loop sequential and the
daemon's limit per file; a partial batch must report each failed name without retrying successful files.

**Global Web UI shortcuts have one owner.** `app.js` installs exactly one capture-phase document
`keydown` listener for the palette openers and desktop sidebar toggle; extend that listener rather than
adding another global listener. Match physical keys with `event.code`, not layout-dependent `event.key`.
Leader mnemonics likewise read bare `event.code` without modifier checks because the opener's modifier may
be released before the second key arrives. The listener must continue to yield while another dialog owns
the keyboard. `⌘1` is reliable in the installed PWA but reserved for tab switching in ordinary browser
tabs, so `#sidebar-toggle` remains the guaranteed path.

**The Web UI is dark-only.** `style.css` carries one unconditional dark palette with the Kotlin-purple
accent; do not reintroduce `prefers-color-scheme` branches. The OLED-black phone variables belong inside
the existing `@media (max-width: 720px)` block so the FitAddon test's mobile slice remains stable, and the
translucent sidebar's composite blur stays desktop-only.

**The mobile terminal lifecycle has five coupled invariants.** Initial xterm geometry is computed from
`window.visualViewport` before fitting and opening the terminal WebSocket, so the upstream starts at the
visible keyboard-constrained size. The installed shell is marked before first render from the standalone,
fullscreen **or** iOS `navigator.standalone` signal: WebKit can report a standalone manifest as fullscreen,
while its `dvh` and hidden-keyboard `visualViewport.height` omit the safe areas. The shell therefore uses
physical `vh`, and a visual-viewport height loss no larger than the vertical safe areas leaves xterm's flex
host uncapped; only a further keyboard shrink removes the stale bottom inset and applies the host ceiling.
On a narrow screen the shell yields its bottom inset only while an attached key bar exists, so detached and
stopped hints still clear the Home indicator. A terminal tap focuses xterm's helper textarea synchronously
from that gesture, and the textarea stays at `16px` to prevent Safari auto-zoom from corrupting viewport geometry.
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

The fifth is the swipe-scroll bridge (`installSwipeScroll`, `TerminalPane.js`), and it couples a browser
gesture to tmux's forced `mouse on` and to one CSS rule — change any of the three and the other two must
be re-measured. xterm 5.5 ran its own touch scrolling ONLY while mouse tracking is off, and kotgent keeps
it on so a desktop wheel reaches pane history, so on a phone `touchmove` was a no-op: the seeded
`capture-pane` screen was all a mobile viewer could ever reach. xterm 6.0 removed the terminal element's
`touchstart`/`touchmove` handlers outright when the viewport moved onto VS Code's scrollable element, so
there is no native path left to lose — the bridge is now the ONLY way a finger scrolls, tracking or not.
The bridge replays a claimed one-finger
vertical swipe as line-based `WheelEvent`s on xterm's own element, so the reports go out under whatever
mouse protocol is live rather than as hand-spelled SGR bytes, and it yields the gesture back when
`mouseTrackingMode` is `"none"` — nobody asked for reports there, so fabricating them would either
double-scroll 5.5's local buffer or invent cursor keys. Five rules are measured, not
assumed. **POINTER events with `setPointerCapture`, never TouchEvents** — a touch gesture is delivered to
the node it began on, and the rows under the finger are exactly what a scroll repaints, so a swipe over
glyphs produced 1-2 reports for a whole gesture while the empty gutter beside the text stayed smooth
(measured on a real iPhone; the earlier `targetTouches` fix, which cured a thumb resting on the key bar
freezing the gesture, is subsumed — a captured pointer is identified by id, so other contacts are simply
not it). Capturing retargets every later move to the terminal element, which is what makes the stream
survive the repaint it causes. `pointerType !== "touch"` returns early: mouse and trackpad already have a
real wheel. **One report per ROW, and
deliberately NOT per five rows** — what a report is worth depends on who consumes it, and the browser
cannot tell them apart, because tmux keeps mouse reporting enabled on the client either way while the PANE
decides. A quiet pane enters copy-mode, where the binding is `send-keys -X -N 5 scroll-up` and a report
moves five lines (measured: 44 reports = 220 lines, about five screens, for one full-height drag). An
agent pane does not: every live claude pane reports `mouse_any_flag=1 alternate_on=1`, so tmux forwards
the wheel with `send-keys -M` and the TUI scrolls its own way, typically one line per report. Converting
at tmux's copy-mode rate was tried and reverted — it made the agent pane, i.e. the common case, scroll
five times too slowly. Row-for-row is the honest default until the daemon tells the browser which kind of
pane it is looking at. **The gesture BANKS and a frame loop EMITS** — `touchmove` adds travel to
`pendingPx` and updates a smoothed velocity, while a `requestAnimationFrame` loop converts the bank into
reports at a bounded rate and keeps running after the finger lifts, decaying that velocity. Both halves
answer a measured complaint about an agent pane, which repaints its whole alternate screen for every
report it receives: emitting a whole touchmove's worth at once arrived as visible lurches, and a phone
gesture stopped dead on `touchend` where a macOS trackpad gets momentum synthesised by the browser for
free (the same claude session scrolls smoothly from a desktop wheel — that is what proves the TUI keeps
up and the burst shape was the problem). The per-frame budget must stay ABOVE what a finger delivers
(~60px, about four rows, per frame on the measured device) or the picture lags the finger, which reads
worse than any burst; the overflow stays banked rather than dropped, so a reversal just subtracts from it.
A new `touchstart` kills a coasting throw before it even qualifies the gesture, a lift that followed a
pause does not coast at all, and the loop stops itself when the bank is spent — never leave it turning
frames, and cancel it in `dispose` along with the listeners.
**`touch-action: none` is unconditional**, living with the bridge rather than in the phone breakpoint:
measured on a real iPhone, `pinch-zoom` and `auto` both let the browser claim the gesture and the terminal
stops scrolling at ALL — which is exactly what landscape and iPad (wider than 720px) used to get while the
bridge was installed for them anyway. Losing pinch-zoom over the terminal is the accepted price;
`overscroll-behavior: none` already covers pull-to-refresh. One thing the code does NOT do is what its
shape suggests: a swipe does not summon the keyboard because `preventDefault()` on a claimed move
suppresses the whole compatibility mouse burst (so xterm's own `mousedown` focus never runs) — the
`shouldFocus()` gate is a second line, not the mechanism, and must not be used to "prove" the rule.

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
leaves `state`/`last_seq`/`provider_session_id` untouched. **Orthogonal does not mean untouched by
`resume`**: because nothing in the launch path writes `archived`, a Done → Resume used to revive the agent
under a row the sidebar still hid — a live pane whose state advanced invisibly, recoverable only by
Restore, which is reachable but not what anyone looks for after resuming. So `SessionManager.resume` clears
it (`clearDoneOnResume`), including on the already-alive launch no-op, which is what heals a row an earlier
resume left hidden. Two details are load-bearing: the un-archive is written AFTER the state update so its
`SessionUpdate` is the last one a client sees (fresh state and `archived=false` in one step, no flicker
back under the state signal) and the returned meta carries `archived = false`, because an HTTP client
merges that DTO into its list newest-rev-wins and a stale `true` would keep the revived row hidden until
its next change. A failed launch deliberately does NOT re-archive: compensation puts the dead state back,
and the row must stay visible to carry that failure. `model` is captured after launch — Claude from
the hook payload's `transcript_path` (a default-wired `ClaudeModelCapture` behind `hookRoutes`'
`onHookPayload` seam, so no `Server.kt` change), Codex by polling the rollout's `turn_context` (a
`SessionManager.captureModelInBackground` seam). A miss just leaves `model` null. Both `archived` and
`model` ride `SessionUpdate` — every emitted field is re-read from the committed row, so a patch is
authoritative for its whole payload, `model = null` included (a model the provider-id rebind correction
cleared clears in a connected UI on that very frame; there is no snapshot/live discriminator any more).
`read_cursor` is the only **client-driven** one: `app.js` POSTs
`/sessions/{id}/read` for the session it displays, from three **imperative** triggers (selection, every
`/events` frame for the active session, and `visibilitychange`), never a `useEffect` on
`[id, lastSeq, unread]`, whose primitives are unchanged after a failed POST so an effect would never
retry. A lost POST heals in `postRead` itself: a per-session retry loop, coalesced to the newest seq,
that stops on success or on `isDefiniteAnswer` (a 401 after rotation / a 404 for a vanished session can
never succeed, and the page lives for days) — this replaced the 15 s resync heartbeat. Monotonicity and
the clamp live in SQL
(`setReadCursor`), which writes **no `updated_at`** (viewing is not activity; `kotgent list` sorts by it) —
hence `markRead` takes no clock. Each rule has one home in the code: SQL semantics in `Sessions.sq`, the
storage contract on `EventStore`, the browser trigger in `app.js`. The CLI marks nothing read.

**Every session-row write stamps `rev` — the single-master replication cursor.** `sessions.rev` is a
global monotonic revision: each statement that touches a row writes `rev = ++counter` (the counter lives
in `SqliteEventStore` under the existing writer mutex, seeded from `MAX(rev)` at open, so it survives
restarts; a value consumed by a zero-row conditional write is never observable). Every observation of a
row — a `SessionDto` from HTTP, a WS frame, the domain `SessionUpdate` — carries the row's rev, and the
client applies an observation **only if its rev is newer** (`upsertIfNewer`/`patchIfNewer` in
`resources/webui/lib/sessions.js`, which also stamp the frame's rev onto the stored row — without that
the invariant self-destructs after the first patch). That one rule is what makes HTTP responses and WS
frames safely mergeable in any arrival order; it replaced the old "never merge per-row / reload the whole
list" discipline and its 15 s resync. `rev` is NOT a flow-resumption cursor (a reconnect re-baselines from
a snapshot, never replays) and is orthogonal to `updated_at` (`markRead` bumps rev but not the sort key).

**The global `/events` protocol: one snapshot, then per-row frames, conflated per socket.** All global
frames form one `sealed class EventsFrame` (`EventsWs.kt`) discriminated by `TRANSPORT_JSON`'s
`classDiscriminator = "type"`; **every send must encode through `EventsFrame.serializer()`** — kotlinx
emits the discriminator only via the sealed base, so a concrete `X.serializer()` produces a type-less
frame every client silently drops (`sendEventsFrame` is the one send path; pinned by
`everyGlobalFrameKindCarriesTheTypeDiscriminator`). On connect the socket sends ONE `sessions_snapshot`
of full `SessionDto` rows (the client builds its entire list from it — there is no `GET /sessions` on
page load); a session the socket has not carried yet goes out as a full-row `session_row`; every later
change as a light `session_update` patch; `preferences_update` rides the same hierarchy. Per socket, a
collector banks the newest update per session under a Mutex and a single sequential sender ships them
**outside** the Mutex — a slow client conflates instead of stalling the collector into the store's
`DROP_OLDEST` window (there is no periodic resync to heal a drop after the fact, so prevention is the
contract). The sender's order is load-bearing: for an uncarried id it fetches the row first, and **only a
delivered row marks the id as carried** — a null row produces no frame and stays uncarried (the row
arrives whole on its next emission), because a carried-but-never-delivered id would ship every later
change as a patch the client ignores, leaving the session invisible until reconnect. The client applies
frames via the if-newer helpers; the snapshot applicator additionally diffs per row against the previous
list (notify-edge for a session that entered needs-attention while the socket was down, `markReadIfViewing`
for the active row) and latches announcements ("N session(s)." only on the first snapshot,
"Daemon connection lost" once per outage). Old still-open tabs keep working degraded: they drop the
unknown frame kinds and fall back to their own HTTP reload on the first unknown-id patch —
`GET /sessions` itself stays (CLI, service worker, targeted fetches).

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

- Every change keeps `./kotlin build` and `./kotlin test` green. Baseline: **921 native tests passed /
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
  `./kotlin run -m kotgent`, a real `claude` / `codex` / `junie`, and `launchctl` — they start long-lived /
  interactive processes. Prefer the terminating `./kotlin build` / `./kotlin test`. Running the `ptycheck`
  binary directly is fine — it terminates, and only touches the throwaway `-L kotgent-test` socket.
- Inspecting a provider's CLI is fine and often necessary (`codex --help`, `codex app-server
  generate-json-schema --out <dir>`, `hooks/list` over an `app-server --stdio` pipe) — those terminate and
  touch no model. Do NOT start a turn (`codex exec`, `turn/start`), and do not write into `~/.codex` or
  `~/.claude` / `~/.junie`: a probe must be readable-only against the user's real home.

## Where things live

```
module.yaml / project.yaml     build manifests (root app + sysnative + ptycheck + build plugins)
version.txt                    single source of the application release version
src/core/                      host-free domain: AgentEvent, SessionState, SessionMeta, Ids, Reducer, Projection
src/crypto/                    Sha256, Hmac, Hex, Base64Url — canonical pure-Kotlin encoders/digests
                               (KT-78062: no CommonCrypto in the test binary)
src/store/                     EventStore interface + SqliteEventStore (SQLDelight)
src/pty/                       TerminalBridge (repaint hold), Broadcaster, CursorVisibility (DECTCEM
                               tracking), PtyHandle (iface), RealPtyHandle
src/sys/                       Cloexec (FD_CLOEXEC sweep run before every spawn), Locale (UTF-8 LANG rule),
                               LoginShell.kt (absolute executable login-shell resolution), Signals
                               (SIGINT/SIGTERM taken back from Ktor's shutdown hook)
src/tmux/                      Tmux, TmuxControl (iface), ProcessRunner (popen),
                               TmuxOptions (-f /dev/null isolation, forced server options, tmuxCommand argv builder),
                               TmuxHookConfig.kt (private session-closed callback artifacts)
src/adapter/                   AgentAdapter, LaunchSpec, ModelScan; claude/ + codex/ + junie/
                               (Cli, HookConfig, HookNormalizer, Adapter); shell/ShellAdapter.kt
src/daemon/                    SessionManager, Reconciler, ProviderIdCapture, VendorSessionLocator,
                               VendorStoreFs (listDir/readHead/readTail/JSON field scans),
                               Claude/Codex/Junie vendor-store probes + scans, ShellVendorStoreProbe.kt
src/push/                      AttentionTracker, subscription store, VAPID key/JWT/signer, Darwin sender, PushNotifier
src/transport/                 Server, Auth/Authorization, session cookies, tickets/rate limit,
                               WebUiAssets (content revision + the one caching rule),
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
                               mobile terminal controls/lifecycle, vendored ESM; lib/unicode.js is the
                               one registry for the opt-in xterm unicode addons; /auth is a string
                               constant in AuthRoutes.kt
docs/plans/                    implementation plans
```
