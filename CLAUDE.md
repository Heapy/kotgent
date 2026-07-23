# CLAUDE.md — working in the kotgent repo

Guidance for future work here. Read this before changing the build, adding native code, or touching the
event model. It captures the conventions and the hard-won toolchain lessons so they don't get re-derived.

## Build system: JetBrains Kotlin Toolchain (NOT Gradle)

This project builds with the **JetBrains Kotlin Toolchain** (formerly Amper), driven by the committed
`./kotlin` wrapper. There is **no Gradle** — no `build.gradle(.kts)`, no `settings.gradle`, no Gradle
plugins.

```shell
./kotlin build      # compile        (run this BEFORE `test` — PtyTest execs the ptycheck binary)
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

Four modules (see `project.yaml`):

- **root — `macos/app`, `macosArm64`** (`module.yaml`): the application. `src/` + `test/`, plus
  `sqldelight/*.sq` (schema) and `resources/webui/` (the SPA). Depends on `./sysnative`; enables the
  `sqldelight-gen` plugin.
- **`sysnative/` — `kmp/lib`, `macosArm64`**: owns **ALL** raw POSIX/cinterop bindings and their thin
  Kotlin wrappers (`cinterop/pty.def`; `Pty`, `NativeTty`, `NativeExe`). The app depends on it, so the
  auto-discovered cinterop klib links into the app's **main** binary as a normal module dependency (this
  replaced an old machine-specific `-library` hack). **Any new raw cinterop goes here**, behind an
  interface (see KT-78062 below).
- **`ptycheck/` — `macos/app`, `macosArm64`**: a **test fixture, not a product**. Its `main()` runs the
  real-PTY integration checks that a test binary cannot run at all (KT-78062 below): the `cat`
  round-trip, `resize`, the child exit code, a failing spawn, the spawned child inheriting only its tty
  (`POSIX_SPAWN_CLOEXEC_DEFAULT`), a real `tmux attach` acquiring a controlling tty, and
  `TerminalBridge`'s fan-out over that attach. It depends on `./sysnative` **and**
  on the root app module (allowed, one-way — that is where `TerminalBridge`/`Tmux` live). The suite's
  `PtyTest` execs the binary and asserts it exits 0. Because there is now more than one runnable
  module, `./kotlin run` needs `-m kotgent` (it errors and lists the modules otherwise).
- **`plugins/sqldelight-gen/` — `jvm/amper-plugin`**: a build-time JVM plugin that runs SQLDelight codegen
  (SQLDelight ships only a Gradle plugin, so we drive its compiler programmatically via a vendored,
  Gradle-free `SqlDelightEnvironment` and contribute the output via `generated.sources`). It runs on the
  JVM at build time and is not linked into the native app, so it can depend on SQLDelight's heavy JVM
  compiler artifacts freely.

## Core patterns & invariants

**Host-free core vs. edges.** The domain, reducer, event store (behind an interface), and adapter
normalization are **host-free** and fully unit-tested. The edges — cinterop/PTY, `tmux`, Ktor WS, tty-raw,
the codegen plugin — are kept thin and behind interfaces so the logic above them is testable. Preserve
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
  `needs_answer` is forward-modeled and `resumable` is a reconciler classification.

**Single-upstream `tmux`-client fan-out.** The daemon holds **exactly one** upstream `tmux attach` client
per session and fans its output out to all subscribers (IDE, browser). `TerminalBridge` is **lazy**: the
upstream PTY opens on the *first* subscriber and closes on the *last* (that last-detach is the "Detach" —
the agent lives on in `tmux`). Input from any subscriber goes to the one upstream; resize is "last active".
Do not open a second `tmux attach` or route input via `tmux send-keys` — it breaks the single-upstream
invariant.

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

**Session identity is `pane_id`, not inherited env.** The logical key is the `tmux` session name
`kt-<shortid>`; the runtime correlation key is the pane id (`#{pane_id}`), recaptured from live panes on
daemon start. Hooks report `$TMUX_PANE`. **Never trust an inherited env var** (`KOTGENT_SESSION_ID` is a
debug label only) — env is poisoned across nested shells/agents.

**Storage = SQLDelight via the custom plugin + `native-driver`.** Schema is `sqldelight/*.sq`; the
`sqldelight-gen` plugin generates the typed API at build time; the runtime driver is
`app.cash.sqldelight:native-driver`. `SqliteEventStore` is single-writer (a `Mutex`), WAL, and appends the
event + updates the session cache in **one transaction**. A JSONL fallback was designed but not needed; the
`EventStore` interface isolates the choice regardless.

**PTY via `openpty` + `posix_spawn` (NOT `forkpty`).** `Pty` opens the master with `openpty` and spawns the
child with `posix_spawn(POSIX_SPAWN_SETSID)`, marshalling all C strings **before** the spawn. `forkpty`
(fork-without-exec) is unsafe for the Kotlin/Native runtime — Kotlin allocation / a GC safepoint in the
forked child can deadlock. A dedicated reader thread does the blocking `read()` into a `Channel`
(there is no `Dispatchers.IO` on native).

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
- Native links print harmless `'+zcm' is not a recognized feature` lines that the log formatter labels
  ERROR. They do not affect the build (`Build successful`); ignore them.

## Testing & running

- Every change keeps `./kotlin build` and `./kotlin test` green. Baseline: **204 run / 204 passed /
  0 skipped**.
- **Run `./kotlin build` before `./kotlin test`.** `PtyTest` execs the `ptycheck` binary, and
  `./kotlin test` never links a main binary (not even its own module's) — the test says so explicitly
  instead of silently passing when the binary is missing.
- Bound every Flow/WS/PTY test with `withTimeout(...)` (anti-hang) — the suite does this consistently.
- `tmux` integration tests use a throwaway `-L kotgent-test` socket with a skip-guard and kill it in
  teardown; they never touch the real `-L kotgent` socket. `ptycheck` follows the same rule.
- **In automation, do not run the daemon or anything that spawns a real agent.** Avoid `kotgent daemon`,
  `./kotlin run -m kotgent`, a real `claude`, and `launchctl` — they start long-lived / interactive
  processes. Prefer the terminating `./kotlin build` / `./kotlin test`. Running the `ptycheck` binary
  directly is fine — it terminates, and only touches the throwaway `-L kotgent-test` socket.

## Where things live

```
module.yaml / project.yaml     build manifests (root app + sysnative + ptycheck + plugin)
src/core/                      host-free domain: AgentEvent, SessionState, SessionMeta, Ids, Reducer, Projection
src/store/                     EventStore interface + SqliteEventStore (SQLDelight)
src/pty/                       TerminalBridge, Broadcaster, PtyHandle (iface), RealPtyHandle
src/sys/                       Cloexec (FD_CLOEXEC sweep run before every spawn)
src/tmux/                      Tmux, TmuxControl (iface), ProcessRunner (popen)
src/adapter/                   AgentAdapter, LaunchSpec; claude/ (Cli, HookConfig, HookNormalizer, Adapter)
src/daemon/                    SessionManager, Reconciler, ProviderIdCapture, ClaudeVendorStoreProbe
src/transport/                 Server, Auth, ControlRoutes, EventsWs, TerminalWs, HookRoutes
src/cli/                       Cli (parseArgs), ApiClient, AttachClient, Commands
src/launchd/                   Plist, Install
sysnative/cinterop/pty.def     ALL raw cinterop (PTY, tty-raw, executable-path C helpers)
sysnative/src/                 Pty, NativeTty, NativeExe (thin cinterop wrappers)
ptycheck/src/Main.kt           real-PTY checks run from a MAIN binary (KT-78062); driven by PtyTest
sqldelight/io/kotgent/db/      Events.sq, Sessions.sq (schema + typed queries)
plugins/sqldelight-gen/        the jvm/amper-plugin that runs SQLDelight codegen at build time
resources/webui/               static SPA (index.html, app.js, style.css, vendored xterm.js)
docs/plans/                    implementation plans
```
