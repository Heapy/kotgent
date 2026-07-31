# Free terminal: a plain shell as kotgent's fourth agent kind

## Overview

`kotgent start shell [cwd]` — plus a "Shell" card in the Web session dialog and the palette's already
reserved `⌘K t` command — launches the user's **login shell** in a `tmux` session `kt-<id>`, managed
exactly like an agent session: it appears in the sidebar, attaches over the terminal WebSocket, takes
`/input`, survives a daemon restart, and can be killed, "Done"-d, restored and resumed. It has **no
provider hooks, no transcript and no import path** — it is a terminal, not a conversation.

It solves three things the user actually asked for:

- a real terminal on the work machine reachable from the phone through the existing tunnel/PWA
- long-running processes (dev server, `watch`, `tail`) started inside that shell and checked from anywhere
- a scratch shell next to an agent working in the same project, without disturbing the agent's TUI

A second, independent gap is closed on the way: **kotgent currently learns that a session died only at
daemon start** (`Reconciler.reconcile()` at `src/cli/Commands.kt:477`). Codex and junie deliver a
`SessionEnd` hook, but claude does not, and a shell has no hooks at all — so a `session-closed` **tmux**
hook is added, which reclassifies any session whose tmux session goes away, shell or agent.

**Phasing.** Tasks 1–4 + 11 deliver a working `kotgent start shell` and its UI. Tasks 5–10 deliver live
death detection. The two halves are independently shippable, in that order; a blocker in the second must
not hold the first.

## Context (from discovery)

Files/components involved:

- `src/adapter/AgentAdapter.kt`, `src/adapter/LaunchSpec.kt` — the provider seam the shell plugs into
- `src/daemon/SessionManager.kt` — `agentFactoryOf` (`:83`), `startReserved` (`:454`), `resume` (`:655`),
  `isPaneAlive` (`:712`), `persistDerivedState` (`:905`), `shellCommand` (`:948`), and the constructor's
  `vendorProbe` (`:312`) / `supportedAgentKinds` (`:325`)
- `src/daemon/Reconciler.kt` — pure `classify` (`:170`), `productionVendorStoreProbe` (`:48`),
  `byAgentVendorStoreProbe` (`:35`), and the probe call that is skipped when `providerSessionId == null`
  (`:127`)
- `src/tmux/Tmux.kt` — `sessionName` (`:83`), `newSession`'s inline argv + option chain (`:159-175`);
  `src/tmux/TmuxOptions.kt` — `tmuxOptionCommands` (`:188`), `tmuxCommand` (`:198`)
- `src/transport/HookRoutes.kt` — the `loopbackOnly` ingress pattern and its `runCatching` + `eprintln`
  failure containment (`:284`); `src/transport/Server.kt` — mount site (`:157`), constructor (`:93`) and
  the `production` factory `Commands.daemon` actually calls (`:271`)
- `src/cli/Commands.kt` — `agentBuilders` (`:374`), the **token-rotation persist callback** (`:314-319`),
  hook-file writers (`writeCodexHookScript` `:693`), the one-shot `Reconciler` (`:477`), the codex model
  gate (`:443`)
- `src/cli/Cli.kt` — usage text (`:109`); `README.md` — agent kinds enumerated at `:16`, `:54`, `:107`,
  `:159`, `:186`
- `resources/webui/lib/agents.js`, `resources/webui/lib/commands.js` (**already carries the reserved
  `general.free-terminal` command, `:139-145`**), `resources/webui/components/dialogs.js`,
  `resources/webui/style.css` (`.agent-icon-<kind>` rules, `:903-918`)

Related patterns found:

- **Provider seam:** "adding a provider means an adapter + a `VendorStoreProbe` + an `agentFactoryOf`
  entry" — the shell uses exactly this, adding no new launch codepath.
- **Hook script discipline** (`CodexHookConfig`/`JunieHookConfig` + `writeCodexHookScript`): a `0600`
  `sh` script plus a separate `0600` header file holding the token, read by `curl -H @<file>`; the secret
  never reaches an argv. The script needs no execute bit because it is invoked as `/bin/sh <script>`.
  **Every such credential is rewritten by the rotation callback at `Commands.kt:314-319`**, hook headers
  first and the token file last — that ordering is load-bearing and documented at `:306-313`.
- **Option chaining** (`Tmux.newSession`): anything that must exist before the pane does rides in the
  same invocation as `new-session`, because a standalone call cannot start a server.
- **Control-plane locking**: every session mutation runs under `SessionManager.withControlLock`, and
  derived-state writes go through `persistDerivedState` → `EventStore.updateSessionState` (never a
  full-row `upsertSession`, which would clobber a concurrent hook's `last_seq`/`provider_session_id` —
  see the KDoc at `SessionManager.kt:896-904`).

Dependencies identified: none new. `platform.posix` (stock, links into the test binary — KT-78062 only
affects *custom* cinterop) covers `getpwuid`/`getuid`/`access`/`stat`.

## Development Approach

- **testing approach**: Regular (code first, then tests) — matching how this repo's existing plans ran.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
- **CRITICAL: all tests must pass before starting the next task** — `./kotlin build && ./kotlin test`
- **CRITICAL: `./kotlin build` must run BEFORE `./kotlin test`** — `PtyTest` execs the `ptycheck` binary
  and `./kotlin test` never links a main binary
- **CRITICAL: update this plan file when scope changes during implementation**
- maintain backward compatibility: no schema migration, no change to the event vocabulary, no change to
  the reducer

### Environment constraints for whoever executes this plan

- do **not** run the daemon, `./kotlin run`, a real `claude`/`codex`/`junie`, or `launchctl`
- tmux integration tests only on the throwaway `-L kotgent-test` socket, with a skip-guard and a
  `kill-server` teardown; never touch `-L kotgent`
- no test may write into the operator's real `~/.kotgent` (no test in this repo sets `HOME`) — anything
  that writes files must take an injectable directory
- changed ES modules must pass `node --check`; a newly *served* module must be registered in
  `test/transport/WebUiServingTest.kt`
- bound every Flow/WS/PTY test with `withTimeout(...)`
- baseline to keep green: **851 native tests passed / 0 skipped**, plus the build-info plugin's 7 JVM
  tests and `ptycheck`'s 11 real-PTY checks

## Testing Strategy

- **unit tests**: required in every task (pure functions get table-driven tests; adapters get spec tests;
  routes go in `test/transport/HookRoutesTest.kt`, which owns the ingress harness)
- **integration tests**: the tmux hook gets one real-tmux test on `-L kotgent-test`, following
  `TmuxTest`'s existing skip-guard/teardown shape, plus a `set-hook -gu session-closed` teardown so the
  shared socket is left clean for the other suites that use it
- **e2e tests**: the project has no browser e2e harness by design; browser behaviour is covered by the
  manual checklist under Post-Completion, and serving contracts by `WebUiServingTest`

## Progress Tracking

- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update the plan if implementation deviates from the original scope

## Solution Overview

**The shell is a fourth agent kind, not a new dimension.** `ShellAdapter` implements the existing
`AgentAdapter`; `"shell"` is registered in `agentBuilders`, and everything downstream — start, resume,
reconcile, kill, archive, terminal fan-out — works unchanged. Rejected alternatives (do not revisit):

- a `kind: agent | shell` column — a migration plus branching everywhere, and still a second launch path
- an `if (agentKind == "shell")` branch in `SessionManager.start` — a second launch codepath, which this
  repo deliberately avoids (import registers a row and reuses `resume()` for exactly this reason)
- an ephemeral terminal with no `sessions` row — nothing could ever close it, and the reconciler would
  never see it
- a `--cmd '<command>'` flag and `remain-on-exit` — a shell already covers it: run the command inside,
  see its output when it dies, press up-arrow
- a split inside an agent's tmux session — already available today via the tmux prefix

**Key design decisions:**

1. **A synthetic provider id (UUID v4), minted for `New` only.** The shell has no provider identity, but
   two existing paths key off one: `resume()` throws `ResumeBlockedException` when it is null
   (`SessionManager.kt:662`), and the reconciler does not even ask the probe when it is null, falling
   through to `crashed` (`Reconciler.kt:127`). Preallocating an id — exactly like claude does — makes both
   work with **zero** changes to either file. `LaunchSpec.preallocatedSessionId` is documented as `null`
   for `Resume` (`LaunchSpec.kt:49-55`), and `resume()` ignores it, so `Resume` keeps it null.
2. **`buildLaunchSpec(Resume)` renders the same argv as `New`.** There is nothing to restore, so "resume
   a shell" means "start a shell in the same cwd". This is what makes `resume()` provider-neutral for us.
3. **Death detection is a tmux `session-closed` hook that acts as a TRIGGER, not as truth — and the
   reclassification belongs to `SessionManager`, under its per-session control lock.** The handler calls
   `SessionManager.onTmuxSessionClosed(id)`, which takes `withControlLock`, re-asks `isPaneAlive` and runs
   the same pure `Reconciler.classify(...)`. The lock is not optional: `resume()` recreates the tmux
   session under the **same name** `kt-<id>`, so an unsynchronised reclassification could read "dead",
   interleave with a completing `resume()`, and then write `resumable` over a live session — after which
   the next Resume hits a duplicate tmux name, `compensateFailedLaunch` runs, and it **kills the live
   session** (`SessionManager.kt:521-530`), i.e. the long-running dev server this feature exists to
   protect. Putting the logic in `SessionManager` (which already holds `vendorProbe` `:312`, `isPaneAlive`
   `:712`, the registry and the lock) also means no new mechanism and no `Reconciler.reconcileOne`.
4. **The shell's probe answers "does the cwd still exist".** A dead shell whose directory survives is
   `resumable`; if the directory is gone it is honestly `crashed`.

### Measured facts this design rests on (tmux 3.7b, throwaway `-L kotgent-test`)

- `set-hook -g session-closed …` **chains with `new-session` in one invocation** — verified
- `#{hook_session_name}` yields the closed session's name (`kt-<id>`) — verified
- the hook fires both on `kill-session` and on the pane command exiting on its own — verified
- the hook **still runs when the LAST session closes**, before the server dies — verified
- **the tmux server keeps answering while a `session-closed` `run-shell` executes** — verified by running
  `tmux list-panes -a` on the same socket *from inside* the hook: it returned `rc=0` in the same second
  and already omitted the closing session. So the handler may safely query tmux, and `paneAlive=false` is
  observable immediately. (This is why no async hand-off is needed.)
- **no exit status is available**: `session-closed` carries none, and `pane-exited` reports an empty
  `hook_session_name` and an empty `pane_dead_status` (with `remain-on-exit off`). Classification
  therefore proceeds without an exit code, like codex/junie's `UNKNOWN_EXIT`
- the exact working hook string is
  `run-shell "/bin/sh '<script>' '#{hook_session_name}'"` — verified end-to-end, with the script at
  `0600` and no execute bit

## Technical Details

### `resolveLoginShell`

```
resolveLoginShell(shellEnv: String?, pwShell: String?, isExecutable: (String) -> Boolean): String
  = first candidate of [shellEnv, pwShell] that is non-blank, absolute AND executable, else "/bin/zsh"
```

Pure and host-free (`src/sys/LoginShell.kt`, next to `Locale.kt`'s `utf8LocaleOrDefault`, which follows
the same "a missing or unusable value becomes a sane default" shape). A thin `currentLoginShell()` reads
`getenv("SHELL")`, `getpwuid(getuid())->pw_shell` and passes `{ access(it, X_OK) == 0 }`.

The executability check is not decoration: `Task 4` deliberately skips `requireAbsoluteBinary`, so this
predicate is the only thing standing between a stale `$SHELL` (a removed Homebrew fish/zsh) and a pane
that dies at exec **after** a `running` row was persisted — the phantom-session failure
`AgentBinaryNotFoundException` exists to prevent (`SessionManager.kt:134-149`). A relative candidate is
skipped for the same reason `requireAbsoluteBinary` demands absoluteness: `tmux new-session -c <cwd>` cds
before exec.

### `ShellAdapter`

```
LaunchSpec(
  command = listOf(shell, "-l"),
  cwd     = cwd,
  preallocatedSessionId = ProviderSessionId(newUuidV4()),   // New only; null for Resume
  cliPath = shell,
  cliVersion = null,
)
```

`-l` makes it a login shell so `/etc/zprofile` + `~/.zshrc` load and the user gets their usual
environment; the pane otherwise inherits only the tmux server's PATH, which under launchd is the plist
snapshot. `LANG` is already forced through the plist, so UTF-8 holds. `env` stays empty: the daemon never
reads `LaunchSpec.env` (`startReserved` `:477` and `resume` `:681` pass only `spec.command`), and tmux
sets `KOTGENT_SESSION_ID` itself at `Tmux.kt:166`.

`events` is `emptyFlow()`. `generateSessionId` is an injectable constructor parameter, as in
`ClaudeAdapter`, so tests are deterministic.

### `SessionManager.onTmuxSessionClosed`

```
suspend fun onTmuxSessionClosed(sessionId: SessionId)
```

Under `withControlLock`: read the row (return if unknown), compute `paneAlive = isPaneAlive(meta.tmuxSession)`,
`stopIntent = meta.state == stopped`, `transcriptExists` from `vendorProbe` (only when
`providerSessionId != null`, exactly as the reconciler does), then the **same pure**
`Reconciler.classify(...)`, and write through `persistDerivedState(meta, newState, EventSource.liveness)`
— never `upsertSession`, which would clobber `last_seq`/`provider_session_id`. Unregister the pane when
the session is not alive. A no-op when nothing changed.

`EventSource.liveness` is used rather than `user`: the daemon observed this, the operator did not request
it. `Reconciler.classify` stays the single classification rule for both the startup pass and this one.

### `TmuxHookConfig` (`src/tmux/`)

```
INGRESS_PATH      = "/hooks/tmux"
HOOK_TOKEN_HEADER = "X-Kotgent-Hook-Token"      // same header name as the provider ingresses
SESSION_HEADER    = "X-Kotgent-Tmux-Session"    // the closed session name, e.g. kt-1a2b3c4d
ingressUrl(port)  = "http://127.0.0.1:$port/hooks/tmux"
headerFileContent(token) = "X-Kotgent-Hook-Token: $token\n"
hookScript(port, headerFilePath): String        // #!/bin/sh, exec /usr/bin/curl … --max-time …
hookCommand(scriptPath): String                 // run-shell "/bin/sh '<script>' '#{q:hook_session_name}'"
```

`curl` gets `--connect-timeout 2 --max-time 5`. CLAUDE.md's rule is explicit — "never issue an untimed
request at the daemon" — because a listening socket inherited by an orphan accepts and then stays silent
forever. Measurement above shows a blocked hook does **not** wedge the tmux server, so the risk here is a
pile of immortal `curl` processes rather than a hang; the timeout is still mandatory.

`/usr/bin/curl` is spelled absolutely. The reason is *not* a different PATH — tmux special-cases `PATH`,
so a pane and `run-shell` alike inherit the server's, which is the daemon snapshot the provider scripts
also run under. It is the same principle as `/usr/bin/openssl` in the VAPID signer: an edge that must
work under launchd does not depend on a snapshot at all.

`#{q:hook_session_name}` (rather than the bare format) shell-quotes the substituted name, so a tmux
session whose name contains a quote cannot break out into `/bin/sh`. kotgent's own names are `kt-<hex>`,
but the `-L kotgent` socket is one users are invited to attach to directly, so the hook is global over
sessions kotgent did not create.

### Hook installation

`Tmux` takes a new constructor parameter `hookScriptPath: String? = null` (a test seam, exactly like
`serverOptions`, and deliberately **not** on `TmuxControl` — no daemon-facing caller chooses tmux hooks).
`newSession` currently builds its argv inline (`Tmux.kt:159-175`), which nothing can unit-test, so the
argv construction is extracted into a pure `newSessionArgv(...)` in `TmuxOptions.kt` beside
`tmuxOptionCommands`. When `hookScriptPath` is non-null it prepends `set-hook -g session-closed
<hookCommand> ;` ahead of the forced-option chain, i.e. into the invocation that starts the server; a
standalone `set-hook` cannot start one. `-g` re-set on every `new-session` is idempotent.

### Ingress

`Route.tmuxHookRoutes(token: () -> String, onSessionClosed: suspend (SessionId) -> Unit)` inside
`loopbackOnly`, deliberately **not** reusing the private `hookRoutes` helper: that one is keyed by
`pane_id` and produces an `AgentEvent`, while here the pane is already gone and no event is produced. The
`SessionId` is the session name minus the `kt-` prefix (`Tmux.sessionName(id) = "kt-$id"`, `Tmux.kt:83`);
a missing header, a name without that prefix, a bare `kt-` (blank ids are rejected by `Ids.kt:25-28`) and
an unknown session all answer `200` and do nothing. The callback is wrapped in `runCatching` +
`eprintln`, following `HookRoutes.kt:284`: a hook is fire-and-forget, and a `TmuxException` from the
liveness probe must not turn into a `500` that tmux reports to a user's terminal.

### Import stays closed

`supportedAgentKinds` is `agentBuilders.keys` on purpose, so the factory and the import gate cannot
disagree (`Commands.kt:370-373`). Importing a shell is meaningless (there is no outside session to adopt,
and the user would have to invent a provider id), so the bootstrap passes
`importableAgentKinds(agentBuilders.keys)` — a small pure function subtracting `SHELL_AGENT_KIND`, so the
rule has one testable home. `POST /sessions/import` then answers `400` for `shell`.

## Known limitations (accepted, do NOT "fix")

1. A shell's state never moves after launch: a started shell reports `running`, and a **resumed** one
   reports `ready` (`ControlSignal.Resume` maps a dead projection to `ready`, `Reducer.kt:133-136`) —
   neither ever changes again, because no shell emits `TurnCompleted`. Both are outside
   `NEEDS_ATTENTION` (`SessionState.kt:47`), so neither rings a notification.
2. Exactly one event (`SessionBound`) is ever appended for a shell, so the unread badge shows `1` until
   the session is first opened, then stays silent forever.
3. `model` is always `null`; push notifications never fire.
4. The tmux hook only reaches a **live** daemon; a shell that dies while the daemon is down is still
   reclassified by the ordinary start-up reconciliation.
5. No exit code is available, so a shell that exits cleanly and one whose process was killed are
   indistinguishable — both classify by pane liveness plus stop intent.

## What Goes Where

- **Implementation Steps** (`[ ]`): code, tests, documentation inside this repo
- **Post-Completion** (no checkboxes): manual checks that need a live daemon, a browser or a phone

## Implementation Steps

### Task 1: Resolve the user's login shell

**Files:**
- Create: `src/sys/LoginShell.kt`
- Create: `test/sys/LoginShellTest.kt`

- [x] add pure `resolveLoginShell(shellEnv: String?, pwShell: String?, isExecutable: (String) -> Boolean): String`
      — first non-blank, **absolute**, **executable** candidate of `shellEnv`, `pwShell`, else `/bin/zsh`
- [x] KDoc why each condition exists: launchd supplies no `$SHELL`; a relative program would resolve
      against the session cwd (`new-session -c`); a stale `$SHELL` would otherwise produce the phantom
      `running` row that `AgentBinaryNotFoundException` exists to prevent
- [x] add thin `currentLoginShell(): String` reading `getenv("SHELL")` and `getpwuid(getuid())->pw_shell`
      via stock `platform.posix`, passing `{ access(it, X_OK) == 0 }`
- [x] write table-driven tests: both present, only env, only passwd, neither, blank, relative path,
      absolute-but-not-executable in either slot (falls through to the next candidate, then the default)
- [x] write a test that `currentLoginShell()` returns an absolute, executable path on this host
- [x] run `./kotlin build && ./kotlin test` — must pass before task 2

### Task 2: Add ShellAdapter and the `shell` agent kind

**Files:**
- Create: `src/adapter/shell/ShellAdapter.kt`
- Modify: `src/daemon/SessionManager.kt`
- Create: `test/adapter/shell/ShellAdapterTest.kt`

- [x] add `const val SHELL_AGENT_KIND = "shell"` beside the three existing kind constants
- [x] create `ShellAdapter(cwd, shell, generateSessionId = { ProviderSessionId(newUuidV4()) })`
      implementing `AgentAdapter`, with `events = emptyFlow()` and no `env`
- [x] implement `buildLaunchSpec`: `[shell, "-l"]` with `cliPath = shell`, `cliVersion = null`; mint
      `preallocatedSessionId` for `New` and leave it **null** for `Resume` (the documented contract at
      `LaunchSpec.kt:49-55`; `resume()` ignores it either way)
- [x] KDoc the two reasons the synthetic id exists (`ResumeBlockedException` at `SessionManager.kt:662`,
      and the null-id probe skip at `Reconciler.kt:127`) and that it identifies nothing at any vendor
- [x] decide where `newUuidV4` lives: either import it from `adapter/claude/ClaudeAdapter.kt:106` with a
      comment, or promote it to a neutral home — pick one and say why in the KDoc
- [x] write tests: `New` renders `[shell, "-l"]` with the injected id; `Resume(existingId)` renders the
      same argv, carries a null `preallocatedSessionId` and does not embed the id anywhere; `events`
      completes empty
- [x] run `./kotlin build && ./kotlin test` — must pass before task 3

### Task 3: Add the shell vendor-store probe

**Files:**
- Create: `src/daemon/ShellVendorStoreProbe.kt`
- Modify: `src/daemon/Reconciler.kt`
- Create: `test/daemon/ShellVendorStoreProbeTest.kt`

- [x] add `shellVendorStoreProbe(): VendorStoreProbe` answering "does `cwd` exist and is it a directory",
      reusing the existing `isDirectory` helper (`SessionManager.kt:244-249`) — promote it to a shared
      home rather than copying the `stat`/`S_IFDIR` logic a second time
- [x] KDoc that a shell's resumability is only "is there somewhere to come back to", and that the
      provider id is deliberately ignored
- [x] register `SHELL_AGENT_KIND to shellVendorStoreProbe()` in `productionVendorStoreProbe`
- [x] write tests: existing directory → true; missing path → false; a regular file → false
- [x] write a test that `productionVendorStoreProbe` dispatches `shell` to it (a shell row with a live
      cwd probes true even though no vendor directory exists)
- [x] run `./kotlin build && ./kotlin test` — must pass before task 4

### Task 4: Register the kind in the bootstrap and close the import gate

**Files:**
- Modify: `src/cli/Commands.kt`
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/cli/Cli.kt`
- Modify: `README.md`
- Modify: `test/daemon/SessionManagerTest.kt`
- Modify: `test/daemon/SessionImportTest.kt`
- Modify: `test/cli/CliTest.kt`

- [x] add the `SHELL_AGENT_KIND` entry to `agentBuilders`, building `ShellAdapter(cwd, currentLoginShell())`
      — no `locate()`/`requireAbsoluteBinary`, because Task 1's predicate already enforces the same
      fail-fast property; note that in the entry's comment
- [x] add pure `importableAgentKinds(kinds: Set<String>): Set<String> = kinds - SHELL_AGENT_KIND` and pass
      it as `SessionManager`'s `supportedAgentKinds`, with a comment stating that the factory still accepts
      every key and only the **import** gate subtracts the shell (there is no outside shell session to adopt)
- [x] update the usage line in `Cli.kt:109` and the five agent-kind enumerations in `README.md`
      (`:16`, `:54`, `:107`, `:159`, `:186`)
- [x] write a unit test for `importableAgentKinds` (subtracts shell, leaves the rest, idempotent on a set
      without it)
- [x] write a `SessionManagerTest` case: `start("shell", cwd)` creates a `running` row whose
      `providerSessionId` is set and whose tmux command is the shell argv
- [x] write a `SessionManagerTest` case covering design decision 2 end to end: start a shell → pane
      killed → `resume()` → same argv, a fresh pane, state `ready`
- [x] write a `SessionImportTest` case: `importSession("shell", …)` throws `UnknownAgentKindException`
      while `start("shell", …)` still succeeds against the same manager
- [x] update `CliTest` for the new usage text and add a `start shell` parse case
- [x] run `./kotlin build && ./kotlin test` — must pass before task 5

### Task 5: Reclassify a closed session under the control lock

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `test/daemon/SessionManagerTest.kt`

- [x] add `suspend fun onTmuxSessionClosed(sessionId: SessionId)`: under `withControlLock`, read the row
      (return on unknown), recompute `paneAlive` via `isPaneAlive`, `stopIntent`, and `transcriptExists`
      via `vendorProbe` (only when the provider id is non-null), classify with the **existing pure**
      `Reconciler.classify`, and write via `persistDerivedState(..., EventSource.liveness)` only on change
- [x] unregister the pane from the registry when the session is not alive
- [x] KDoc why this lives here and not in `Reconciler`: it needs the per-session control lock, and the
      unsynchronised version can interleave with a completing `resume()` and write `resumable` over a
      live session, after which the next Resume collides on the tmux name and `compensateFailedLaunch`
      kills the live session (`SessionManager.kt:521-530`)
- [x] KDoc why the write is `persistDerivedState`, not `upsertSession` (`SessionManager.kt:896-904`)
- [x] write a test: a shell row whose pane is gone and whose cwd exists becomes `resumable`
- [x] write a test: the same row with a deleted cwd becomes `crashed`
- [x] write a test: a row already `stopped` stays `stopped` (idempotent under a repeated trigger)
- [x] write a test: a row whose pane is alive is left untouched
- [x] write a test proving the lock matters: a `resume()` and an `onTmuxSessionClosed` for the same id run
      concurrently and the row never ends up dead while a pane is live
- [x] write a test that the write does not regress `last_seq` / `provider_session_id`
- [x] write a test: an unknown id is a silent no-op
- [x] run `./kotlin build && ./kotlin test` — must pass before task 6

### Task 6: Generate the tmux hook script and its header file

**Files:**
- Create: `src/tmux/TmuxHookConfig.kt`
- Create: `test/tmux/TmuxHookConfigTest.kt`

- [x] add `INGRESS_PATH`, `HOOK_TOKEN_HEADER`, `SESSION_HEADER`, `ingressUrl(port)`,
      `headerFileContent(token)` mirroring `CodexHookConfig`
- [x] add `hookScript(port, headerFilePath)`: `#!/bin/sh` + `exec /usr/bin/curl -sS -o /dev/null -X POST`
      with `--connect-timeout 2 --max-time 5`, `-H @<headerFile>`, `-H "X-Kotgent-Tmux-Session: $1"` and
      an empty body
- [x] KDoc the timeout as mandatory (CLAUDE.md: never issue an untimed request at the daemon — an
      orphan-held socket accepts and stays silent) and the absolute `/usr/bin/curl` as the
      `/usr/bin/openssl` principle — **not** as a PATH difference, since tmux special-cases `PATH` and
      `run-shell` inherits the same server snapshot the provider scripts run under
- [x] add `hookCommand(scriptPath)` producing `run-shell "/bin/sh '<path>' '#{q:hook_session_name}'"`,
      POSIX-single-quoting the script path; KDoc that `#{q:…}` guards against a session name containing a
      quote, and that this exact form was verified against tmux 3.7b
- [x] write tests: the token never appears in the script, only the header file's path; the URL carries
      the requested port; the timeout flags are present; a script path containing a quote is escaped
- [x] write a test that runs `/bin/sh -n` over the generated script (syntax check without executing it),
      following `JunieHookConfigTest`'s "actually exercise the generated script" precedent
- [x] run `./kotlin build && ./kotlin test` — must pass before task 7

### Task 7: Install the hook in the same invocation as `new-session`

**Files:**
- Modify: `src/tmux/TmuxOptions.kt`
- Modify: `src/tmux/Tmux.kt`
- Modify: `test/tmux/TmuxOptionsTest.kt`
- Modify: `test/tmux/TmuxTest.kt`

- [x] extract the argv `Tmux.newSession` builds inline (`Tmux.kt:159-175`) into a pure
      `newSessionArgv(serverOptions, hookScriptPath, id, cwd, cmd, cols, rows)` in `TmuxOptions.kt`,
      beside `tmuxOptionCommands` — it is currently untestable, which is why this extraction is part of
      the task rather than a nicety
- [x] add `hookScriptPath: String? = null` to the `Tmux` constructor, KDoc'd as a test seam like
      `serverOptions` and deliberately absent from `TmuxControl`
- [x] when non-null, `newSessionArgv` prepends `set-hook -g session-closed <hookCommand> ;` ahead of the
      forced-option chain; KDoc that a standalone `set-hook` cannot start a server, so it must ride here
- [x] write pure tests: the `set-hook` triple precedes the option chain and `new-session`; with
      `hookScriptPath = null` the argv is byte-identical to today's
- [x] write an integration test on `-L kotgent-test` (existing skip-guard + `kill-server` teardown): a
      `Tmux` configured with a script that appends its `$1` to a temp file, two sessions created, one
      killed → the file names that session; then close the **last** session and assert it is recorded too
      (the server-death race)
- [x] add `set-hook -gu session-closed` to that test's teardown so the shared `-L kotgent-test` socket is
      left clean for the other suites that use it
- [x] run `./kotlin build && ./kotlin test` — must pass before task 8

### Task 8: Add the `/hooks/tmux` ingress

**Files:**
- Modify: `src/transport/HookRoutes.kt`
- Modify: `src/transport/Server.kt`
- Modify: `test/transport/HookRoutesTest.kt`

- [ ] add `Route.tmuxHookRoutes(token: () -> String, onSessionClosed: suspend (SessionId) -> Unit)` inside
      `loopbackOnly`, validating `HOOK_TOKEN_HEADER` with the existing constant-time comparison
- [ ] parse the session name from `SESSION_HEADER`, strip the `kt-` prefix to a `SessionId`, invoke the
      callback inside `runCatching { … }.onFailure { eprintln(…) }` (the precedent at `HookRoutes.kt:284`),
      and answer `200` for a missing header, a foreign name, a bare `kt-` and an unknown session
- [ ] KDoc why this does not reuse the private `hookRoutes` helper (no pane, no payload, no `AgentEvent`),
      and that the callback is a **trigger** whose truth is re-derived under the control lock
- [ ] add `onTmuxSessionClosed: suspend (SessionId) -> Unit = {}` to the `KotgentServer` constructor,
      **forward it through the `production` factory** (`Server.kt:271`, which is what `Commands.daemon`
      calls), and mount the route beside the three provider ingresses
- [ ] write tests in `HookRoutesTest.kt`: wrong/absent token → `401`; non-loopback `Host` → `403` without
      reaching the token check; valid call → `200` and the callback receives the parsed id;
      unknown/malformed/bare-`kt-` name → `200` with no callback; a **throwing** callback still → `200`
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 9

### Task 9: Wire the hook end to end in the daemon bootstrap

**Files:**
- Modify: `src/cli/Commands.kt`
- Create: `test/daemon/TmuxHookWiringTest.kt`

- [ ] add `writeTmuxHookScript(port, token, home = kotgentHome())` mirroring `writeCodexHookScript` but
      with an **injectable home** and public visibility, so a test can drive it against a throwaway
      directory instead of the operator's real `~/.kotgent`; it writes a `0600` header file and a `0600`
      script and returns the script path
- [ ] **add `writeTmuxHookScript(port, rotated)` to the token-rotation persist callback**
      (`Commands.kt:314-319`), before `writePrivateFile(defaultTokenPath(), …)` per the documented
      ordering; without this, `kotgent token rotate` leaves the hook holding a stale token and death
      detection dies silently (the ingress is fire-and-forget by design, so nothing would surface)
- [ ] construct `Tmux` with that `hookScriptPath`, and pass `sessionManager::onTmuxSessionClosed` into
      `KotgentServer.production` as `onTmuxSessionClosed`
- [ ] write a wiring test over the pure/injectable parts: the generated script targets the same port,
      path and header name the ingress validates, and both files are `0600`; record honestly — as
      `ImportWiringTest.kt:40-44` does — which part of the `Commands.daemon` call site remains outside
      automation
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 10

### Task 10: Offer Shell in the Web UI and light up the reserved palette command

**Files:**
- Modify: `resources/webui/lib/agents.js`
- Modify: `resources/webui/lib/commands.js`
- Modify: `resources/webui/components/dialogs.js`
- Modify: `resources/webui/app.js`
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] add `{ value: "shell", name: "Shell", available: true, importable: false, viewBox, icon }` to
      `AGENT_CHOICES`, and extend the file's header comment: this is the one card that is **not** a vendor
      mark, because a shell has no vendor
- [ ] add a `.agent-icon-shell` rule to `style.css` beside the existing per-kind rules (`:903-918`)
- [ ] filter the picker by mode in `dialogs.js:238-259` — it renders **one** `AGENT_CHOICES.map(...)` for
      both start and import today, so without a filter the Shell card appears in Import mode and produces
      the `400` the UI should have prevented
- [ ] wire `general.free-terminal` in `commands.js:139-145` — it already exists as a reserved, disabled
      command with the `⌘K t` chord, designed as stage 4 of the palette plan — to a real action that
      opens the New session dialog with Shell preselected; drop its `disabled: "not implemented yet"`
- [ ] update the CLI help text rendered in `dialogs.js` (`start <agent>` currently reads
      `claude | codex | junie`) and the import hint that enumerates per-provider id shapes
- [ ] run `node --check` on every changed module
- [ ] update the four pinned assertions in `WebUiServingTest.kt` that this change necessarily breaks:
      the reserved-chord check at `:337` narrows to `general.notifications` alone; the three
      `assertEquals(4, …)` counts at `:733`/`:739`/`:741` become five; the vendor-mark length assertion at
      `:743` applies to the vendor cards only, with a message saying the shell card is the deliberate
      exception — keep the invariant falsifiable for real logos rather than deleting it
- [ ] add assertions: `agents.js` exposes the `shell` choice marked `importable: false`, `style.css`
      declares `.agent-icon-shell` exactly once (matching the loop at `:815-821`), and the palette command
      is no longer disabled
- [ ] run `./kotlin build && ./kotlin test` — must pass before task 11

### Task 11: Verify acceptance criteria

- [ ] verify every Overview requirement is implemented: `kotgent start shell [cwd]`, the Web UI card, the
      `⌘K t` palette command, sidebar presence, attach/input, kill/Done/Restore, resume, survival across a
      daemon restart
- [ ] verify the accepted limitations hold **as written** — in particular that a started shell reports
      `running` and a resumed one `ready`, and that neither reaches `NEEDS_ATTENTION`
- [ ] verify no schema migration, no reducer change and no second launch codepath were introduced
      (`git diff` over `sqldelight/`, `src/core/Reducer.kt`, `SessionManager.start`)
- [ ] verify token rotation keeps death detection working (the rotation callback rewrites the tmux hook
      header) — at minimum by inspection of `Commands.kt:314-319`
- [ ] run the full suite: `./kotlin build && ./kotlin test` — expect **≥851 native tests, 0 skipped**
- [ ] confirm `git grep '/Users/' -- '*.yaml'` is still empty and that no new tmux argv bypasses
      `tmuxCommand()`

### Task 12: [Final] Update documentation

- [ ] add a `CLAUDE.md` section for the shell kind: why it is a fourth provider rather than a new
      dimension, why the provider id is synthetic and `New`-only, and the accepted limitations
- [ ] add a `CLAUDE.md` note that the `session-closed` tmux hook is a trigger whose truth is re-derived by
      `SessionManager.onTmuxSessionClosed` under the control lock, that its script must stay in the
      `new-session` invocation and in the rotation callback, that tmux exposes no exit status, and that
      the tmux server was measured to keep answering during a hook
- [ ] update the "Where things live" map with `src/adapter/shell/`, `src/sys/LoginShell.kt`,
      `src/tmux/TmuxHookConfig.kt`, `src/daemon/ShellVendorStoreProbe.kt`
- [ ] note in `docs/plans/completed/20260731-command-palette.md` (or in `CLAUDE.md`) that the palette's
      stage-4 `free-terminal` hand-off is now delivered
- [ ] update the test baseline number in `CLAUDE.md`
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only*

**Manual verification** (needs a live daemon and a browser; not runnable in automation):

- `kotgent start shell ~/some/project` → the session appears in `kotgent list` and in the Web UI, the
  terminal attaches, and the pane shows a login shell in that directory with the user's usual prompt
- `⌘K t` in the Web UI opens the New session dialog with Shell preselected
- type `exit` in the web terminal → within a moment the row flips to `resumable` **without** a daemon
  restart (this is the tmux hook working end to end); press Resume → a fresh shell in the same cwd
- start a dev server inside the shell, close the browser tab, reopen it → the process is still running and
  its output is intact in the scrollback
- kill the daemon while a shell is live, restart it → the shell is still `running` and re-attaches
- run `kotgent token rotate`, then exit a shell → the row still flips (the rotated hook header works)
- `Done` a shell → it is killed and archived; `Restore` brings it back as a dead row that can be resumed
- from the phone PWA: start a shell, type into it, background the app, return → the terminal reattaches
- confirm an agent session now also flips to `resumable`/`stopped` as soon as its TUI exits, without a
  daemon restart (the claude blind spot this closes)

**External system updates**: none. No schema migration, no plist change, no new dependency; an existing
`~/.kotgent` gains two files (`tmux-hook.sh`, `tmux-hook-header`) on the next daemon start or token
rotation.
