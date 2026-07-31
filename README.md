# kotgent

[![CI](https://github.com/Heapy/kotgent/actions/workflows/ci.yml/badge.svg)](https://github.com/Heapy/kotgent/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**kotgent** is a local-first, restart-safe control plane for coding-agent sessions.

Agent processes (Claude, Codex, Junie and plain login shells today) run inside `tmux`, independent of any user
interface. The IDE terminal, desktop Web UI, and installable mobile PWA are interchangeable clients over one daemon. On a
phone, server-sent Web Push can wake the PWA's service worker when a session needs attention, even after
the app is closed. `tmux` is the transport and the process-survival mechanism, **not** the source of
truth: state is derived by replaying an append-only event log, so it survives a daemon restart.

```text
IDE terminal ──────┐
Desktop Web UI ────┼──▶ kotgent daemon ──▶ tmux ──▶ claude | codex | junie | shell
Mobile PWA ────────┘          │        │
                              │        └──▶ browser push service ──▶ service worker
                              ├── SQLite (event log, session cache, push subscriptions)
                              └── provider adapter (hooks → canonical events, approvals)
```

There are two distinct kinds of durability, and kotgent leans on both instead of trying to make `tmux`
immortal:

- **Close the IDE / reload the browser** — the agent keeps running in `tmux` (a client detached, the
  process did not die).
- **Reboot the machine** — the process is gone, but the conversation is preserved on disk by the provider
  itself (Claude's per-project transcripts, Codex's rollout files, Junie's session directories) and is
  restored with `resume`.

## Contents

- [Quick start](#quick-start)
- [Requirements](#requirements)
- [Build & test](#build--test)
- [The CLI](#the-cli) — [access & auth](#access--auth--two-keys-one-shape), [Web UI](#web-ui--kotgent-web),
  [sign in from your phone](#sign-in-from-your-phone)
- [Troubleshooting](#troubleshooting)
- [The first vertical slice](#the-first-vertical-slice)
- [Architecture at a glance](#architecture-at-a-glance)
- [Status & limitations](#status--limitations)
- [Contributing](#contributing)
- [License](#license)

## Quick start

Install from the Homebrew tap (macOS on Apple Silicon; the formula pulls in `tmux`):

```shell
brew install Heapy/tap/kotgent
```

The formula installs kotgent itself, **not** the agent CLIs: `claude`, `codex` and/or `junie` have to be on
your `PATH` already (see [Requirements](#requirements)); the `shell` kind uses your existing login shell.
Then run the rest **from a normal login shell** —
`kotgent install` snapshots that shell's environment (`PATH`, so the daemon can find the agent binaries,
and `LANG`, so the TUI renders as UTF-8) into the launchd plist, and launchd would otherwise start the
daemon with a minimal env and no locale at all:

```shell
kotgent install            # install + boot the daemon as a launchd agent (RunAtLoad + KeepAlive)
kotgent start claude       # launch a Claude session inside tmux, in the current directory
kotgent start shell        # launch a plain login shell in the same managed terminal surface
kotgent web                # open the sign-in form and print its one-time code
```

That's the whole loop. From there, `kotgent list` shows every session and its state, `kotgent attach <id>`
drops the terminal straight into your shell, and closing either client just detaches — the agent keeps
running in `tmux`.

Upgrades are `brew upgrade kotgent`, followed by **`kotgent install` again**: the plist records the
binary's real (version-qualified) Cellar path, which a new release invalidates.

To build from source instead, see [Build & test](#build--test).

## Requirements

- **macOS on Apple Silicon (arm64).** The build targets `macosArm64` and links against macOS system
  libraries; there is no other supported target.
- **JetBrains Kotlin Toolchain** — invoked through the bundled `./kotlin` wrapper committed in the repo.
  You do **not** need a separate install or Gradle; the wrapper provisions the toolchain (0.11.1) on
  first run. A JDK is required for the toolchain and the build-time SQLDelight codegen plugin.
- **`tmux`** — sessions live on a dedicated server socket (`tmux -L kotgent`), isolated from your normal
  `tmux` **and from your `~/.tmux.conf`**: kotgent passes `-f /dev/null` on every invocation, so none of
  your config is loaded into an agent's pane — not your prefix key, bindings, plugins, `status-format` or
  `default-terminal`. This is deliberate. A `~/.tmux.conf` is written for a terminal *you* drive, and one
  line of it (`set -g destroy-unattached on`) would kill the agent every time the last viewer detaches.
  In its place kotgent forces its own small set: `destroy-unattached off`, `default-terminal
  tmux-256color`, `mouse on`, `status off`, `history-limit 10000`, `escape-time 10`. `mouse on` is what
  makes the wheel scroll an agent's transcript — that scrollback lives in the tmux pane, so it is the only
  way a browser tab that joined an existing session can see anything above the current screen. kotgent arms
  mouse reporting for every viewer as it joins, but the single upstream uses the last-resizing viewer's
  geometry; only that viewer has a fully live wheel, and a larger tab's lower/right area may not scroll.
  Two other things to know: selecting text in the web terminal needs Option-drag on macOS (Shift-drag
  elsewhere), because a mouse-reporting terminal otherwise sends the drag to the app; and a wheel scroll
  puts the pane into tmux copy-mode, which every viewer shares — kotgent leaves copy-mode before
  programmatic Interrupt/REST input. Interrupt returns only after tmux verifies delivery; REST input
  reports when full PTY write completion was not observed. A PTY error may have written a prefix, so
  inspect the terminal before resending to avoid duplicated input. `focus-events` stays off: with one tmux client
  fanned out to many viewers, "is the terminal focused" has no single answer. Developed against tmux 3.7b.
- **`claude`** — the Claude Code CLI, on your `PATH`. Session-id preallocation (`claude --session-id`)
  needs a recent version; kotgent version-gates it and falls back to a `SessionStart` hook on older CLIs.
  Developed against claude 2.1.x.
- **`codex`** — the Codex CLI, on your `PATH`, if you want codex sessions. kotgent installs its hooks
  per launch (`codex -c 'hooks={…}'`), so your `~/.codex` is never modified. Codex has no session-id
  preallocation, so the id is captured afterwards — from the `SessionStart` hook, or by reading the
  rollout file Codex writes under `~/.codex/sessions`. Developed against codex-cli 0.145.
- **`junie`** — the Junie CLI, on your `PATH`, if you want junie sessions. kotgent installs its hooks per
  launch (`junie --config-location <kotgent-owned file>`), so your `~/.junie/config.json` is never
  modified. Junie has no session-id preallocation either, so the id is captured afterwards, by reading the
  session directory Junie writes under `~/.junie/sessions`. Live state tracking needs Junie's **hooks**,
  which are an EAP feature: on a stable build that ignores them the session still launches and attaches,
  it just shows a coarser state. Developed against junie 26.8.3 (EAP).
- **`shell`** — your current login shell (`$SHELL`, then the passwd entry, with `/bin/zsh` as the safe
  fallback). It launches with `-l`, needs no additional CLI, emits no provider hooks and has no import path.
- **`/usr/bin/openssl` and NSURLSession (Web Push only).** kotgent lazily uses macOS's system
  `/usr/bin/openssl` to generate and sign its VAPID P-256 credential; outbound HTTPS delivery uses
  the Darwin HTTP client backed by NSURLSession and the system trust store. Both are macOS runtime
  facilities, not packages to install. If VAPID setup fails, the daemon and in-tab notifications keep
  working; only server-sent push is unavailable.

## Build & test

```shell
./kotlin build      # compile the macosArm64 app (+ the sysnative cinterop, + SQLDelight codegen)
./kotlin do kexePath # print the debug app's absolute .kexe path (releaseKexePath for the release one)
./kotlin test       # run the test suite
```

The suite currently reports **765 native tests passed / 0 skipped**, plus 7 JVM tests for the build-info
plugin and the 11 real-PTY checks `ptycheck` runs (see below).

Run `build` before `test`: one test (`PtyTest`) drives the real-PTY checks by executing the `ptycheck`
binary, and `./kotlin test` on its own never links a main binary. If the binary is missing the test says
so rather than passing quietly. See [Status & limitations](#status--limitations) for why those checks
live in a separate binary.

The produced binary lands under `build/` (the `macos/app` output). Its directory and filename include
the checkout/worktree name, so use `./kotlin do kexePath` after `build` instead of hard-coding either
(`releaseKexePath` for a `-v release` build); `kotgent` below refers to that binary. The command reads
what `build` left behind rather than triggering it — the toolchain has no way for a plugin task to
depend on the native link — so run it after a successful `build`, or it fails saying so.

It prints the path and also writes it to `build/kexe-path`, which is what a script should read: a task
action's stdout reaches you through the build log, so it is prefixed, interleaved with the log's own
lines, and silenced by `--log-level` before the surrounding noise is. The file follows `--build-dir`
along with everything else, and a failed lookup deletes it rather than leaving a stale answer behind.

```shell
./kotlin build
./kotlin do kexePath
kexe=$(cat build/kexe-path)
```

## The CLI

```text
kotgent <command> [args]

  daemon [--port N]              run the control-plane server (default port 27508; the launchd entry point)
  install | uninstall           (un)install the launchd LaunchAgent (io.kotgent.daemon)
  start <agent> [cwd]           start a session (agent: 'claude' | 'codex' | 'junie' | 'shell'; cwd defaults to the current dir)
             [--name N] [--tag T]
  import <agent> <session-id>   register a session started outside kotgent, then resume it
             [--cwd D] [--name N] [--tag T] [--no-start]
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
  `launchctl bootstrap` / `bootout` it. `install` also **snapshots your shell's `PATH` and `LANG`** into
  the plist: launchd starts the daemon with a minimal env and *no* locale, so the snapshot is what lets the
  daemon and the agents it spawns find `claude`/`codex`/`junie` and render a UTF-8 TUI. Re-run it from a full shell
  whenever either goes stale. An agent that can't be resolved on the daemon's `PATH` fails fast with a
  clear error pointing at `kotgent install`, not a silent attach failure.
- **`start`** creates a `tmux` session `kt-<id>`, launches the requested agent or login shell in it, and
  records the session.
- **`import`** brings a conversation you started *outside* kotgent — `claude`, `codex` or `junie` run in a
  plain terminal — under kotgent, with its history intact. The import itself only registers the session (no
  `tmux` side effects): kotgent verifies the provider's own on-disk record and writes a `resumable` entry,
  then immediately resumes it (`claude --resume <id>` / `codex resume <id>` /
  `junie --resume --session-id <id>`); `--no-start` skips that and
  leaves it registered for later. The project directory is discovered from the provider's record; pass
  `--cwd` if that discovery fails or picks the wrong directory. `shell` is deliberately not importable:
  there is no outside provider session or transcript to adopt. Finding a provider session id:
  - **claude** — shown in the `claude --resume` session picker; it is also the transcript's file name,
    `~/.claude/projects/<encoded-project-dir>/<session-id>.jsonl`.
  - **codex** — shown in the `codex resume` session picker; it is also the trailing UUID of the rollout
    file name, `~/.codex/sessions/<date>/rollout-<timestamp>-<session-id>.jsonl`. An *archived* codex
    session cannot be imported — archiving puts it out of `codex resume`'s reach.
  - **junie** — shown in `/history`; it is also the directory name under `~/.junie/sessions`, e.g.
    `session-260730-015553-1j1h`. Junie keeps only its most recent sessions' context, so a session whose
    directory it has pruned cannot be imported. A junie session in which you never submitted a prompt has
    no recorded project directory, so pass `--cwd` (there is nothing to resume in it anyway).

  Importing an id kotgent already tracks fails with the existing session's id and the right next move
  (`kotgent resume <id>`, or Restore in the Web UI if that session is archived). The Web UI's new-session
  dialog has a matching **Import** mode, including the register-only checkbox. One caveat: kotgent cannot
  detect that the conversation is still *live* in the original terminal — resuming it there and under
  kotgent at once runs two CLI copies of the same conversation.
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
  `kotgent token rotate` (every HMAC dies at once).

`~/.kotgent` also holds the generated hook settings, the optional `config.json` (public URL), and
`kotgent.db` (including device push subscriptions). Web Push lazily creates the VAPID private key at
`~/.kotgent/vapid.pem` with mode `0600`; deleting or replacing it invalidates existing browser
subscriptions, so notifications must be re-enabled on each device afterwards.

### Web UI — `kotgent web`

```shell
kotgent web            # open the sign-in form and print a code to type into it
kotgent web --print    # print a credentialed login URL for scripting or copying
```

No master token is copied into a URL. `kotgent web` issues one **single-use, 8-character Crockford
Base32 code** (40 bits, held in memory for five minutes), opens the bare
`http://127.0.0.1:<port>/auth` form, and prints the still-valid code. Type it into that browser or an
already-installed PWA. The exchange is protected by a daemon-wide rolling budget of ten failed attempts
per minute in addition to the short lifetime and single-use rule.

`kotgent web --print` is the non-interactive form: stdout contains exactly
`http://127.0.0.1:<port>/auth#ticket=…`, while the equivalent grouped code and human hint go to stderr,
so piping the URL remains safe. The fragment and typed code are two representations of the **same**
credential; spending either invalidates the other. A browser opening the fragment reads it locally,
`POST`s it to `/auth/exchange`, and then uses `location.replace("/")`, so neither the server's initial
`GET /auth` nor browser history receives the live fragment.

The UI shows the session list with live state badges and a "Needs attention" queue (fed by the events
WebSocket), and renders a session's terminal with `xterm.js` over the terminal WebSocket (byte rendering,
keyboard input, resize). Its installable PWA layout adds a mobile sidebar drawer, safe-area handling,
terminal sizing from `visualViewport`, and a phone-only row for Esc, Tab, Shift-Tab, arrows, Ctrl, and
Ctrl-C. Terminal taps focus the software keyboard without Safari zooming the helper textarea, and a
terminal socket lost — to the app being backgrounded, or to the daemon restarting under it — is reattached
without a reload: on returning to the app, and on the events socket reconnecting, which is the only signal
that a restarted daemon is back. Each attempt checks daemon liveness under a deadline first; a daemon that
is merely unreachable leaves the attempt available for the next one, while a daemon that answers that this
session is gone ends it rather than retrying forever.

The sidebar footer identifies the running daemon: local source builds show the release version plus their
embedded short Git hash (for example `0.5.0+81c37fe`), while published Homebrew builds show the release
version alone (`0.5.0`).

A session row also carries an **unread pill** — how many events have arrived since you last looked at that
session. Looking at it clears it: the browser posts the cursor it has displayed, so the count is
**server-side** (it clears on the phone and the desktop together, and a second browser sees it clear with no
reload) and **persistent** (restarting the daemon does not resurrect a cleared badge). Reading a session does
not count as activity, so `kotgent list`'s ordering is unaffected.

The per-device notifications toggle registers `/sw.js` and the browser's Web Push subscription. A
`false → true` attention transition sends a payload-less push; the service worker fetches `/sessions`
under a ten-second deadline, shows one notification per waiting, non-archived session, and opens or
focuses that session when tapped. If the fetch fails or stalls it still shows a generic attention
notification. If Web Push is unsupported, denied, or unavailable on the daemon, the live tab falls back
to ordinary in-tab notifications.

![The kotgent Web UI: the sidebar's "Needs attention" queue and session list on the left, a live Claude
session's terminal on the right, with Interrupt / Detach / Stop / Done controls.](docs/images/web-ui.png)

### Sign in from your phone

There is no TLS on the native build (`ktor-server-cio` for `macosArm64` has no `sslConnector` — that is a
JVM-only API), so the phone reaches the daemon through a **cloudflared named tunnel** in front of
Cloudflare Access, not by exposing the port. Point kotgent at the public host:

```shell
kotgent config set public-url https://kotgent.example.com
```

The Web UI's **phone** button (📱) then issues a code and renders a credential-free QR for
`https://kotgent.example.com/auth`. Scan it on a phone that has passed Access, choose **Add to Home
Screen**, launch Kotgent, and type the displayed code into that form. The `/auth` landing page carries the
PWA install metadata but the QR intentionally does not carry or spend the credential: Safari and an
installed iOS PWA have separate cookie jars, so signing Safari in would not sign in the home-screen app.
An unsigned PWA that launches at `/` also routes its first `/sessions` `401` to the same form; a later
expired credential leaves the live terminal visible and reports the error instead.

Without a configured `public-url` the dialog prints the `cloudflared` ingress snippet to add instead of a
QR. Setting up the tunnel and the Access policy is a one-time host-side step (ingress rule →
`http://127.0.0.1:27508`, DNS route, a strict Access policy on your own identity — the host fronts a
terminal that can run anything on the Mac).

Authorization is one rule for both surfaces: the `Host` must be in the allowlist (loopback or the
configured public host), and an `Origin`, **required on any non-GET request and on every WebSocket
handshake and checked for a match whenever it is present**, keeps a cookie from being replayed cross-site
(`SameSite` alone would not — sibling `*.example.com` hosts are the same site). Hook ingress, ticket
issuance and token rotation are additionally **loopback-only**: only the browser surface is ever published
outward.

## Troubleshooting

Most real-world breakage traces back to the daemon's launchd environment, which is minimal by design — so
the first question is almost always "does the plist still match my shell?".

- **`start` fails with `agent '…' not found on the daemon's PATH`.** The daemon's `PATH` is a
  snapshot taken at `kotgent install`, not your live shell's. If `claude`/`codex` moved (a version manager,
  a new Homebrew prefix, a fresh `nvm` install for codex's `env node` shebang), re-run `kotgent install`
  from a full login shell. kotgent fails fast here on purpose: the error names the fix instead of leaving a
  phantom `running` row.
- **The TUI renders as a wall of underscores.** The tmux client decided it may not emit UTF-8, which
  happens when the daemon runs without a UTF-8 `LANG` — again a stale plist. Re-run `kotgent install` from
  a shell where `locale` reports a UTF-8 setting.
- **After `brew upgrade kotgent` the daemon does not come back.** The plist records the binary's
  version-qualified Cellar path, which the upgrade invalidates. Re-run `kotgent install`.
- **The port is bound but nothing answers** (a rebind fails with `EADDRINUSE`, or a client connects and
  then hangs). Current builds close every spawn path against descriptor inheritance, so this should only
  come from a long-lived `tmux` server started by an *older* kotgent, which is still holding the listening
  socket the daemon that spawned it left behind. `tmux -L kotgent kill-server` releases it — note that this
  also stops every agent running under that server.
- **My tmux settings do nothing inside a kotgent pane.** Expected: kotgent runs every tmux command with
  `-f /dev/null`, so `~/.tmux.conf` is never loaded on its socket (see [Requirements](#requirements) for
  what it forces instead). Your own `tmux` on the default socket is untouched. There is no user-facing
  override — the option set lives in `src/tmux/TmuxOptions.kt`. Note the flag only affects the command
  that *starts* a server: if something else already started one on `-L kotgent`, that server has your
  config until it is restarted (`tmux -L kotgent kill-server`, which also stops every agent on it).
- **I can't select text in the browser terminal / the wheel scrolls tmux instead of my terminal.** Both
  are `mouse on`, which kotgent forces so the wheel reaches the pane's own history (10 000 lines, and the
  only scrollback a newly attached viewer has). To select text while an agent's TUI is running, hold
  Option and drag on macOS, or Shift and drag elsewhere. The wheel puts the pane into tmux copy-mode —
  shared by every viewer of that session — which scrolls back down to the bottom to exit, and kotgent
  cancels it anyway before sending keys, so Interrupt is never swallowed by it.
- **Notifications stay in the open tab instead of reaching the phone.** On iOS, Web Push requires iOS
  16.4 or later and an installed home-screen app; enable it from that app's sidebar so the permission
  prompt runs from the tap itself. A missing/unusable `/usr/bin/openssl`, denied browser permission, or an
  unreachable push service disables only server-sent push, and kotgent falls back to live-tab
  notifications.
- **Push stopped after `vapid.pem` was deleted, replaced, or regenerated.** A browser subscription is
  bound to the VAPID public key it was created with. Toggle notifications off and on in each installed
  browser/PWA to register a fresh subscription with the daemon. The key at
  `~/.kotgent/vapid.pem` should remain mode `0600`.
- **Inspecting the daemon itself.** It is a normal LaunchAgent: `launchctl print gui/$UID/io.kotgent.daemon`
  shows its state, and the plist at `~/Library/LaunchAgents/io.kotgent.daemon.plist` shows the exact `PATH`
  and `LANG` that were snapshotted.

### Uninstall

```shell
kotgent uninstall                  # bootout + remove the LaunchAgent plist
tmux -L kotgent kill-server        # stop every agent still living in tmux
rm -rf ~/.kotgent                  # token, config/hooks, SQLite data/subscriptions, VAPID private key
brew uninstall kotgent             # if installed from the tap
```

`kotgent uninstall` only removes the launchd entry — the agents in `tmux` and the state under `~/.kotgent`
outlive it by design, so drop them explicitly if you mean to.

## The first vertical slice

kotgent's first milestone is one end-to-end path that proves the core value:

> **`kotgent start` a Claude session → close IDEA (Detach) → open the browser → continue the same
> session → see it flag "needs attention" when Claude asks for approval.**

Concretely:

1. `kotgent start claude` launches Claude inside `tmux` session `kt-<id>` and records it.
2. Attaching from the IDE terminal and then closing it (Detach) drops one WebSocket subscriber. The
   daemon holds the **single** upstream `tmux attach` client and fans it out, so the agent keeps running
   with no client attached.
3. Running `kotgent web` opens the credential-free sign-in form and prints a one-time code; after signing
   in, clicking the session re-attaches to the very same live process — the browser is just another client
   of the same fan-out.
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
| `tmux/` | Thin wrapper over `tmux -f /dev/null -L kotgent` via a `popen`-based `ProcessRunner`: one argv builder that isolates the server from `~/.tmux.conf`, plus the small option set kotgent forces in its place. |
| `adapter/` | `AgentAdapter` contract + the Claude, Codex and Junie adapters (launch/resume spec, hook config, event normalization). |
| `daemon/` | Session manager, start-up reconciliation, provider-id capture, stop modes. |
| `push/` | Attention-edge tracking, SQLite subscription store, VAPID key/JWT signing, Darwin/NSURLSession delivery, and notifier lifecycle. |
| `transport/` | Ktor CIO server: control REST, events WS, terminal WS, `Bearer`/cookie auth (`authorize`), `/auth` exchange, push/auth/control/hook routes, static PWA. |
| `cli/` | Subcommands + the raw `attach` passthrough. |
| `launchd/` | `plist` generation + install/uninstall. |
| `sysnative/` (module) | Owns **all** raw POSIX/cinterop bindings (PTY via `openpty`+`posix_spawn`, tty raw, executable-path). |
| `plugins/sqldelight-gen/` (build plugin) | Runs SQLDelight codegen from `sqldelight/*.sq` at build time. |

The authenticated push HTTP surface is `GET /push/vapid-key`, `POST /push/subscribe`, and
`POST /push/unsubscribe`. The GET returns the VAPID application-server key; the POSTs persist or remove
the browser endpoint and its keys. Both POST routes inherit the transport's required, same-origin
`Origin` check. They are not loopback-only, because a PWA must register through the configured public
host.

For deeper conventions and the toolchain gotchas, see [CLAUDE.md](CLAUDE.md).

## Status & limitations

This is the **first vertical slice** — deliberately narrow but genuinely end-to-end. Be honest about what
is and isn't here:

**In the slice (v1):**

- **Four launch kinds: Claude, Codex, Junie and Shell.** The three providers run as a TUI in `tmux` and
  report through hooks; Shell runs the user's login shell through the same lifecycle and terminal fan-out.
  Codex and Junie both fire a real `PermissionRequest`, so `needs_approval` is precise there rather than inferred
  from a generic notification — kotgent only observes it, the operator answers in the terminal. Junie's
  hooks are an EAP feature: without them a junie session still launches and attaches, its state is simply
  coarser.
- **Two keys, browser-friendly auth.** The daemon still binds `127.0.0.1` only, but browsers authenticate
  with a stateless, no-secret-in-URL session cookie (`kotgent web` mints a one-time ticket), and a phone
  can sign in through a **cloudflared** tunnel + Cloudflare Access. The CLI and hooks keep using the master
  token; `kotgent token rotate` invalidates every cookie at once.
- The full `start → Detach → browser → continue → needs-attention` path, session reconciliation on daemon
  restart (`running` / `stopped` / `crashed` / `resumable` classification), provider-id capture, and
  launchd install.
- **Session metadata & lifecycle polish.** Each session shows its agent CLI version and, best-effort, the
  model it is running; **Done** stops an agent and archives it off the sidebar (restorable, history kept);
  and an opt-in, per-device **notification toggle** registers server-sent Web Push for attention edges,
  with live-tab notification fallback.
- **Installable mobile PWA.** The manifest, root service worker, home-screen icons, responsive drawer,
  visual-viewport terminal sizing, software-keyboard focus handling, special-key toolbar, foreground
  terminal reattachment, and notification deep links are all shipped. The service worker is network-only:
  there is deliberately no offline shell when the local daemon cannot serve useful state.
- **Import of externally started sessions.** `kotgent import` (and the Web UI's Import mode) registers a
  conversation begun in a plain terminal and continues it under kotgent — fan-out, push, and mobile access
  included, with the provider's own on-disk record as the history (see [The CLI](#the-cli)).

**Backlog (not built yet):**

- The Codex **app-server** (JSON-RPC v2) as an alternative event source — structured items, two-way
  approvals, and no terminal. That is a different product surface (a chat UI, not a terminal fan-out),
  so it is deliberately separate from the adapter above.
- **A fourth provider: `cursor-cli`** — another TUI-in-`tmux` adapter behind the same shape (launch spec +
  hook config + normalizer, an ingress route, a `VendorStoreProbe`, and an `agentFactoryOf` entry), with
  nothing in `core/`, the store, or the fan-out changing. Open questions to resolve first: whether it
  exposes per-launch hooks (like Codex's `-c 'hooks={…}'`) or forces a user-scoped config, how it reports
  approvals, and whether/how a session id can be preallocated or must be scanned after the fact.
- Structured mobile actions such as native approve/deny buttons outside the agent's terminal. Approvals
  remain interactive TUI operations today.
- A **diff viewer** and snapshots.
- **Usage-limit tracking** — how much of each provider's quota is left and when it resets (Claude: the
  5-hour window and the weekly cap; Codex: the weekly cap).
- A browser e2e harness (Playwright).

**Why the real-PTY checks live in their own binary.** A Kotlin Toolchain issue
([KT-78062](https://youtrack.jetbrains.com/issue/KT-78062)) means **our own** raw-cinterop path cannot be
called from a test binary at all — partial linkage turns every such call into a stub that throws
`IrLinkageError`, and nothing in the YAML works around it. Main binaries *do* link the cinterop, so the
affected assertions live in the **`ptycheck`** module, whose `main()` runs all 8 for real:

1. a `cat` round-trip through the pty,
2. `resize` (`TIOCSWINSZ`) succeeds,
3. the child's exit code is captured,
4. spawning a nonexistent command throws,
5. the spawned child inherits **only** its tty (the `POSIX_SPAWN_CLOEXEC_DEFAULT` guarantee — an
   inherited listening socket would keep the port bound after the daemon dies),
6. `tmux attach` runs on the spawned pts,
7. a resize **reaches a running `tmux attach`** (the child gets no controlling terminal, so `Pty.resize`
   must deliver `SIGWINCH` itself — see [CLAUDE.md](CLAUDE.md)),
8. `TerminalBridge` fans out over that real attach.

The suite runs them through `PtyTest`, which executes that binary and asserts it exits 0 — so these are
real, non-skipped tests. Everything around the cinterop is still tested directly via interface fakes
(`FakePtyHandle`, `FakeTty`). Third-party klibs that happen to contain cinterop (Ktor, the SQLite
`native-driver`) and the stock `platform.posix` bindings are **not** affected — they link into test
binaries normally — so the transport, store, and `tmux` layers are fully tested in CI. The full root-cause
write-up is in [CLAUDE.md](CLAUDE.md).

## Contributing

Issues and pull requests are welcome. A few things worth knowing before you open one:

- **The build is the JetBrains Kotlin Toolchain, not Gradle.** Use the committed `./kotlin` wrapper; there
  is no `build.gradle`. Dependencies and module wiring live in `module.yaml` / `project.yaml`.
- **Keep `./kotlin build` and `./kotlin test` green**, and run `build` before `test` (see
  [Build & test](#build--test)). New tests are expected to come with the change; the suite has no skips and
  should stay that way.
- **Syntax-check changed Web UI modules with `node --check`.** This is a no-build Preact app with no
  JavaScript test harness; add newly served modules to `WebUiServingTest` and manually verify browser
  behavior.
- **Read [CLAUDE.md](CLAUDE.md) first** if you are touching the build, native code, or the event model. It
  documents the invariants (host-free core, single-upstream `tmux` fan-out, the event-sourcing rules) and
  the toolchain gotchas that are expensive to rediscover.
- **The target is `macosArm64` only.** CI runs on Apple-silicon macOS runners with `tmux` installed.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in this work
shall be licensed as above, without any additional terms or conditions.
