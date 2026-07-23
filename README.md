# kotgent

**kotgent** is a local-first, restart-safe control plane for coding-agent sessions.

Agent processes (Claude and Codex today) run inside `tmux`, independent of any user interface. The IDE terminal,
the browser, and — later — a phone are interchangeable clients over one daemon. `tmux` is the transport
and the process-survival mechanism, **not** the source of truth: state is derived by replaying an
append-only event log, so it survives a daemon restart.

```text
IDE terminal ───────┐
Desktop Web UI ─────┼──▶ kotgent daemon ──▶ tmux ──▶ claude | codex
(mobile PWA — later)┘          │
                               ├── SQLite (event log + session cache)
                               └── provider adapter (hooks → canonical events, approvals)
```

There are two distinct kinds of durability, and kotgent leans on both instead of trying to make `tmux`
immortal:

- **Close the IDE / reload the browser** — the agent keeps running in `tmux` (a client detached, the
  process did not die).
- **Reboot the machine** — the process is gone, but the conversation is preserved by Claude on disk and
  is restored with `resume`.

## Requirements

- **macOS on Apple Silicon (arm64).** The build targets `macosArm64` and links against macOS system
  libraries; there is no other supported target.
- **JetBrains Kotlin Toolchain** — invoked through the bundled `./kotlin` wrapper committed in the repo.
  You do **not** need a separate install or Gradle; the wrapper provisions the toolchain (0.11.0) on
  first run. A JDK is required for the toolchain and the build-time SQLDelight codegen plugin.
- **`tmux`** — sessions live on a dedicated server socket (`tmux -L kotgent`), isolated from your normal
  `tmux`. Developed against tmux 3.7b.
- **`claude`** — the Claude Code CLI, on your `PATH`. Session-id preallocation (`claude --session-id`)
  needs a recent version; kotgent version-gates it and falls back to a `SessionStart` hook on older CLIs.
  Developed against claude 2.1.x.
- **`codex`** — the Codex CLI, on your `PATH`, if you want codex sessions. kotgent installs its hooks
  per launch (`codex -c 'hooks={…}'`), so your `~/.codex` is never modified. Codex has no session-id
  preallocation, so the id is captured afterwards — from the `SessionStart` hook, or by reading the
  rollout file Codex writes under `~/.codex/sessions`. Developed against codex-cli 0.145.

## Build & test

```shell
./kotlin build      # compile the macosArm64 app (+ the sysnative cinterop, + SQLDelight codegen)
./kotlin test       # run the test suite
```

The suite currently reports **201 run / 201 passed / 0 skipped**.

Run `build` before `test`: one test (`PtyTest`) drives the real-PTY checks by executing the `ptycheck`
binary, and `./kotlin test` on its own never links a main binary. If the binary is missing the test says
so rather than passing quietly. See [Status & limitations](#status--limitations) for why those checks
live in a separate binary.

The produced binary lands under `build/` (the `macos/app` output); `kotgent` below refers to that binary.

## The CLI

```text
kotgent <command> [args]

  daemon [--port N]              run the control-plane server (default port 27508; the launchd entry point)
  install | uninstall           (un)install the launchd LaunchAgent (io.kotgent.daemon)
  start <agent> [cwd]           start a session (agent: 'claude' | 'codex'; cwd defaults to the current dir)
             [--name N] [--tag T]
  list | ls                     list sessions and their states
  stop <id>                     stop a session
  resume <id>                   resume a stopped/crashed/resumable session
  interrupt <id>                send Ctrl-C to un-stick a session
  attach <id>                   attach a raw terminal to a session
  --version | --help
```

- **`daemon`** binds `127.0.0.1:27508` by default (`27508` = `0x6b74` = ASCII "kt"). Override the
  daemon's listen port with `--port`. The `$KOTGENT_PORT` environment variable does **not** change the
  daemon's port — it tells the CLI *client* (`list`/`start`/`stop`/`attach`/…) which port to reach a
  running daemon on. This is the process launchd runs on login.
- **`install` / `uninstall`** write `~/Library/LaunchAgents/io.kotgent.daemon.plist`
  (`RunAtLoad` + `KeepAlive`, so the daemon comes up on login and is restarted if it dies) and
  `launchctl bootstrap` / `bootout` it.
- **`start`** creates a `tmux` session `kt-<id>`, launches the agent in it, and records the session.
- **`attach`** is **not** a direct `tmux attach`. It is a raw-terminal passthrough over the daemon's
  terminal WebSocket (tty put in raw mode via `termios`, stdin → WS, WS → stdout, `SIGWINCH` → resize,
  terminal restored on exit). Detaching an attach only drops a client; the agent stays alive.

### Local-only token auth

The daemon serves `127.0.0.1` only, gated by a single shared token stored at **`~/.kotgent/token`**
(mode `0600`, inside a `0700 ~/.kotgent`; generated from 32 bytes of `/dev/urandom`). The same token is
used by the CLI (as a `Bearer` header), by the WebSocket clients (as a `?token=` query parameter, since
browsers cannot set headers on a WS handshake), and by the Claude hooks (as their own header). `~/.kotgent`
also holds the generated hook settings and the SQLite database.

### Web UI

Open:

```text
http://127.0.0.1:<port>/#token=<token>
```

The token lives in the URL **fragment** (`#token=`), which the browser never sends to the server; the SPA
reads it from `location.hash` and appends it as `?token=` only when opening the events / terminal sockets.
The UI shows the session list with live state badges and a "Needs attention" queue (fed by the events
WebSocket), and renders a session's terminal with `xterm.js` over the terminal WebSocket (byte rendering,
keyboard input, resize).

## The first vertical slice

kotgent's first milestone is one end-to-end path that proves the core value:

> **`kotgent start` a Claude session → close IDEA (Detach) → open the browser → continue the same
> session → see it flag "needs attention" when Claude asks for approval.**

Concretely:

1. `kotgent start claude` launches Claude inside `tmux` session `kt-<id>` and records it.
2. Attaching from the IDE terminal and then closing it (Detach) drops one WebSocket subscriber. The
   daemon holds the **single** upstream `tmux attach` client and fans it out, so the agent keeps running
   with no client attached.
3. Opening `http://127.0.0.1:<port>/#token=<token>` in the browser and clicking the session re-attaches
   to the very same live process — the browser is just another client of the same fan-out.
4. When Claude hits a permission prompt, its `Notification` hook posts to the daemon, which normalizes it
   into an `ApprovalRequested` event; the reducer moves the session to `needs_approval`, and the events
   WebSocket lights the session up in the browser's "Needs attention" queue.

## Architecture at a glance

State is **event-sourced**. Adapters normalize provider signals into a canonical `AgentEvent`; a pure
reducer folds the append-only log into a `Projection` (the derived state). Restart-safety is just
`replay`. The code is split into a host-free core and thin edges:

| Layer | What it does |
|-------|--------------|
| `core/` | Host-free domain: `AgentEvent`, `SessionState`, `SessionMeta`, `Reducer`, `Projection`. No I/O. |
| `store/` | `EventStore` interface + SQLDelight-backed `SqliteEventStore` (single-writer, WAL, append+cache in one transaction). |
| `pty/` | `TerminalBridge` + `Broadcaster` — the lazy single-upstream `tmux attach` fan-out. |
| `tmux/` | Thin wrapper over `tmux -L kotgent` via a `popen`-based `ProcessRunner`. |
| `adapter/` | `AgentAdapter` contract + the Claude and Codex adapters (launch/resume spec, hook config, event normalization). |
| `daemon/` | Session manager, start-up reconciliation, provider-id capture, stop modes. |
| `transport/` | Ktor CIO server: control REST, events WS, terminal WS, token auth, hook ingress, static Web UI. |
| `cli/` | Subcommands + the raw `attach` passthrough. |
| `launchd/` | `plist` generation + install/uninstall. |
| `sysnative/` (module) | Owns **all** raw POSIX/cinterop bindings (PTY via `openpty`+`posix_spawn`, tty raw, executable-path). |
| `plugins/sqldelight-gen/` (build plugin) | Runs SQLDelight codegen from `sqldelight/*.sq` at build time. |

For deeper conventions and the toolchain gotchas, see [CLAUDE.md](CLAUDE.md).

## Status & limitations

This is the **first vertical slice** — deliberately narrow but genuinely end-to-end. Be honest about what
is and isn't here:

**In the slice (v1):**

- **Two providers: Claude and Codex.** Both run as a TUI in `tmux` and report through hooks. Codex adds
  a real `PermissionRequest` signal, so `needs_approval` is precise there rather than inferred from a
  generic notification.
- **Local-only.** `127.0.0.1` + a single token. No remote access.
- The full `start → Detach → browser → continue → needs-attention` path, session reconciliation on daemon
  restart (`running` / `stopped` / `crashed` / `resumable` classification), provider-id capture, and
  launchd install.

**Backlog (not built yet):**

- The Codex **app-server** (JSON-RPC v2) as an alternative event source — structured items, two-way
  approvals, and no terminal. That is a different product surface (a chat UI, not a terminal fan-out),
  so it is deliberately separate from the adapter above.
- **Mobile PWA**, a **cloudflared** tunnel + Access, and **Web Push** for remote / phone access.
- A **diff viewer**, external-session import, and snapshots.
- **Usage-limit tracking** — how much of each provider's quota is left and when it resets (Claude: the
  5-hour window and the weekly cap; Codex: the weekly cap).
- A **prominent notification toggle** — one large, obvious control to turn notifications on and off per
  device (phone / laptop).
- A browser e2e harness (Playwright).

**Why the real-PTY checks live in their own binary.** A Kotlin Toolchain issue
([KT-78062](https://youtrack.jetbrains.com/issue/KT-78062)) means **our own** raw-cinterop path cannot be
called from a test binary at all: the toolchain registers the cinterop-klib task for the non-test fragment
only, while the test link asks for a test-fragment cinterop artifact, so nothing matches and partial
linkage turns every call into a stub that throws `IrLinkageError`. It is still the case on toolchain
0.11.0, 0.11.1 and 0.12.0-dev, and nothing in the YAML can work around it (a relative `-library` path
cannot resolve — the compiler runs with `workingDir = kotlinNativeHome` — and `module.yaml` has no
variable interpolation).

Main binaries *do* link the cinterop, so the affected assertions live in the **`ptycheck`** module, whose
`main()` runs them for real: the `cat` round-trip, `resize`, the child exit code, a failing spawn, a real
`tmux attach` acquiring a controlling terminal, and `TerminalBridge`'s fan-out over that attach. The suite
runs them through `PtyTest`, which executes that binary and asserts it exits 0 — so these are real,
non-skipped tests. Everything around the cinterop is still tested directly via interface fakes
(`FakePtyHandle`, `FakeTty`).

Third-party klibs that happen to contain cinterop (Ktor, the SQLite `native-driver`) and the stock
`platform.posix` bindings are **not** affected — they link into test binaries normally — so the transport,
store, and `tmux` layers are fully tested in CI.
