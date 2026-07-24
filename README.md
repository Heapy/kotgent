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
  You do **not** need a separate install or Gradle; the wrapper provisions the toolchain (0.11.1) on
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

The suite currently reports **361 run / 361 passed / 0 skipped**.

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
  web [--print]                 open the Web UI in a browser (or print the login URL)
  token rotate                  re-mint the master token (old key stops authenticating)
  config get | set public-url <url>   read / set the public URL published behind the tunnel
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

### Access & auth — two keys, one shape

The daemon binds `127.0.0.1` only. Two distinct keys guard it:

- **The master token** — the *machine* key. Stored at **`~/.kotgent/token`** (mode `0600`, inside a
  `0700 ~/.kotgent`; generated from 32 bytes of `/dev/urandom`). It authenticates the CLI (as a `Bearer`
  header), the provider hooks (as their own header), and the issuing of browser tickets. `kotgent token
  rotate` re-mints it; the old key stops authenticating **new** requests immediately (already-open
  WebSockets survive until they reconnect, since auth is computed once at handshake).
- **A session cookie** — the *browser* key. A stateless `HttpOnly; SameSite=Strict; Path=/` cookie of the
  form `v1.<issuedAt>.<hmac>` where `hmac = HMAC-SHA256(master-token, "v1|" + issuedAt)`. There is **no
  session table** — the cookie verifies by recomputing the HMAC, so "sign out every device" is just
  `kotgent token rotate` (every HMAC dies at once). It is never in a URL.

`~/.kotgent` also holds the generated hook settings, the optional `config.json` (public URL), and the
SQLite database.

### Web UI — `kotgent web`

```shell
kotgent web            # mint a one-time ticket and open the Web UI in your browser
kotgent web --print    # print the login URL instead of opening it
```

No copy-pasting a token into a URL. `kotgent web` issues a **one-time ticket** (32 bytes, 10-minute TTL,
kept in memory) and opens `http://127.0.0.1:<port>/auth#ticket=…`. The ticket lives in the URL
**fragment**, which the browser never sends to the server: the `/auth` page reads it from `location.hash`,
`POST`s it to `/auth/exchange` (which burns the ticket and sets the cookie), then `location.replace("/")`
so the ticket never lands in history. From then on the cookie authenticates every request and WebSocket.

The UI shows the session list with live state badges and a "Needs attention" queue (fed by the events
WebSocket), and renders a session's terminal with `xterm.js` over the terminal WebSocket (byte rendering,
keyboard input, resize).

### Sign in from your phone

There is no TLS on the native build (`ktor-server-cio` for `macosArm64` has no `sslConnector` — that is a
JVM-only API), so the phone reaches the daemon through a **cloudflared named tunnel** in front of
Cloudflare Access, not by exposing the port. Point kotgent at the public host:

```shell
kotgent config set public-url https://kotgent.example.com
```

The Web UI's **phone** button (📱) then issues a ticket and renders a QR of
`https://kotgent.example.com/auth#ticket=…`; scan it on a phone that has passed Access and the same
one-time-ticket exchange sets a cookie there. Without a configured `public-url` the dialog prints the
`cloudflared` ingress snippet to add instead of a QR. Setting up the tunnel and the Access policy is a
one-time host-side step (ingress rule → `http://127.0.0.1:27508`, DNS route, a strict Access policy on
your own identity — the host fronts a terminal that can run anything on the Mac).

Authorization is one rule for both surfaces: the `Host` must be in the allowlist (loopback or the
configured public host), and an `Origin`, **required on any non-GET request and on every WebSocket
handshake and checked for a match whenever it is present**, keeps a cookie from being replayed cross-site
(`SameSite` alone would not — sibling `*.example.com` hosts are the same site). Hook ingress, ticket
issuance and token rotation are additionally **loopback-only**: only the browser surface is ever published
outward.

## The first vertical slice

kotgent's first milestone is one end-to-end path that proves the core value:

> **`kotgent start` a Claude session → close IDEA (Detach) → open the browser → continue the same
> session → see it flag "needs attention" when Claude asks for approval.**

Concretely:

1. `kotgent start claude` launches Claude inside `tmux` session `kt-<id>` and records it.
2. Attaching from the IDE terminal and then closing it (Detach) drops one WebSocket subscriber. The
   daemon holds the **single** upstream `tmux attach` client and fans it out, so the agent keeps running
   with no client attached.
3. Running `kotgent web` opens the browser (over a one-time ticket, no token in the URL) and clicking the
   session re-attaches to the very same live process — the browser is just another client of the same
   fan-out.
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
| `transport/` | Ktor CIO server: control REST, events WS, terminal WS, `Bearer`/cookie auth (`authorize`), the `/auth` ticket exchange, hook ingress, static Web UI. |
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
- **Two keys, browser-friendly auth.** The daemon still binds `127.0.0.1` only, but browsers authenticate
  with a stateless, no-secret-in-URL session cookie (`kotgent web` mints a one-time ticket), and a phone
  can sign in through a **cloudflared** tunnel + Cloudflare Access. The CLI and hooks keep using the master
  token; `kotgent token rotate` invalidates every cookie at once. No secret ever appears in a URL.
- The full `start → Detach → browser → continue → needs-attention` path, session reconciliation on daemon
  restart (`running` / `stopped` / `crashed` / `resumable` classification), provider-id capture, and
  launchd install.

**Backlog (not built yet):**

- The Codex **app-server** (JSON-RPC v2) as an alternative event source — structured items, two-way
  approvals, and no terminal. That is a different product surface (a chat UI, not a terminal fan-out),
  so it is deliberately separate from the adapter above.
- **A third provider: `cursor-cli`** — another TUI-in-`tmux` adapter behind the same shape (launch spec +
  hook config + normalizer, an ingress route, a `VendorStoreProbe`, and an `agentFactoryOf` entry), with
  nothing in `core/`, the store, or the fan-out changing. Open questions to resolve first: whether it
  exposes per-launch hooks (like Codex's `-c 'hooks={…}'`) or forces a user-scoped config, how it reports
  approvals, and whether/how a session id can be preallocated or must be scanned after the fact.
- **Show the provider version once a session is deployed** — capture the agent CLI's version
  (`claude --version` / `codex --version`) at launch and surface it in the sidebar/session view, so it is
  obvious which build is running (and, later, whether a session predates an upgrade).
- **Mobile-native UX** (a PWA manifest + home-screen icon, an Esc/Ctrl/Tab/arrow key row for the soft
  keyboard, approve buttons) and **Web Push**. Remote phone access itself — the cloudflared tunnel +
  Access, sign-in by QR, and the cookie — is now in the slice (see [Sign in from your
  phone](#sign-in-from-your-phone)).
- **Session archiving** — a way to hide a finished session from the sidebar without ending the agent or
  losing its history, so the list stays the working set rather than every session ever started.
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
