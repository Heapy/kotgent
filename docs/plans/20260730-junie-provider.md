# Junie as kotgent's third provider

## Status

Implemented on 2026-07-30, verified at **844 native tests passed / 0 skipped** (plus the build-info
plugin's 7 JVM tests and the 11 real-PTY checks driven by `PtyTest`). The manual checks at the bottom
need a live `junie` and are therefore post-implementation verification, not unfinished work.

## Overview

`kotgent start junie [cwd]` launches Junie's TUI in `tmux` with kotgent's hooks installed for that one
launch, tracks its state live, resumes it, reconciles it across daemon restarts, imports a session started
outside kotgent, and captures its model best-effort — full parity with `claude` and `codex`. The provider
seam absorbed it: nothing in `core/` (beyond the id invariant below), the store, the reducer or the
terminal fan-out changed.

Developed against `junie 26.8.3 (2548.3) eap`. The conventions and the reasons behind each rule live in
`CLAUDE.md` ("Three providers, one shape"); this document records what shipped and what still needs a
human at a terminal.

## What shipped

| Area | Files |
|------|-------|
| Adapter (outgoing) | `src/adapter/junie/{JunieCli,JunieHookConfig,JunieAdapter}.kt` |
| Ingress (incoming) | `src/adapter/junie/JunieHookNormalizer.kt`, `Route.junieHookRoutes` in `src/transport/HookRoutes.kt`, mounted in `src/transport/Server.kt` |
| On-disk identity | `src/daemon/JunieSessionScan.kt` (+ `readTail` / `jsonStringField` / `jsonLongField` in `VendorStoreFs.kt`), registered in `productionVendorStoreProbe` and `productionSessionLocator` |
| Model | `extractDominantModel` in `src/adapter/ModelScan.kt`, `captureJunieModelOnce` |
| Core | relaxed `ProviderSessionId` + `isCanonicalUuid` in `src/core/Ids.kt`, with explicit UUID guards at the claude/codex boundaries |
| Bootstrap / CLI | `writeJunieHookConfig`, `agentBuilders` entry, id discovery and model poll in `src/cli/Commands.kt`; usage text in `src/cli/Cli.kt` |
| Web UI | Junie card enabled in `resources/webui/lib/agents.js`; import hint + CLI help in `resources/webui/components/dialogs.js` |

## Findings that changed the design

Both were measured against a real `~/.junie`, and both invalidate the obvious implementation:

1. **Junie writes a session's `index.jsonl` row only once the session has run a task.** Two sessions were
   live in one junie process and only one had a row; the other had 150 KB of events, no `state.json` and no
   row, because no prompt had been submitted in it. Since `ProviderIdCapture` polls for just 20 × 250 ms, an
   index-only id discovery would expire before a human finishes typing, leaving essentially every junie
   session "id pending" — i.e. unresumable. So discovery enumerates session DIRECTORIES (present from the
   moment junie starts) and uses the index only to EXCLUDE a candidate whose recorded `projectDir` is a
   different one.
2. **A long-running session keeps touching its files, so mtime cannot threshold a launch.** The candidate
   filter uses the directory's `st_birthtimespec` instead; a session started hours ago is out of scope even
   though it wrote an event a second ago.

A third finding shaped the model capture: in a real session the primary model appeared 40 times against
6/1/1 for three helper models — and the FIRST model in the file was a helper. Hence a frequency-based
extractor rather than `extractModel`'s first match.

## Manual verification checklist

Automated tests cover everything that does not need a live agent (no test launches `junie`, writes into
`~/.junie`, or starts the daemon). These need a human:

- [ ] **Launch.** `kotgent start junie ~/some/project` → the session appears in `list` and in the Web UI,
      the terminal attaches, and the pane runs junie's TUI.
- [ ] **Trust prompt.** In a project junie has not seen, its trust question appears INSIDE the pane and is
      answerable there; the hooks fire regardless of the answer (they are an explicit
      `--config-location`, which Junie honors for an untrusted project).
- [ ] **Live state.** Submitting a prompt moves the row to `running`; the turn finishing moves it to
      `ready`. An LLM error (`StopFailure`) also lands `ready` rather than sticking at `running`.
- [ ] **Approval.** A sensitive action shows **Junie's own** permission dialog (never auto-approved, never
      auto-denied) while kotgent shows `needs_approval` and sends the push notification. Answering in the
      terminal clears it on the next tool call.
- [ ] **Id + model.** Within seconds of launch the row has a `provider_session_id`
      (`session-<date>-<time>-<suffix>`); after the first turn it also shows a model, and that model is the
      primary one — not a helper such as `gpt-5.4-nano`.
- [ ] **Resume.** Stop the session, then `kotgent resume <id>` (or Resume in the Web UI) → the same
      conversation comes back.
- [ ] **Restart safety.** Restart the daemon while the junie session is dead → it reconciles to
      `resumable`, not `crashed`.
- [ ] **Import.** Start `junie` by hand, submit a prompt, quit, then
      `kotgent import junie session-…` with NO `--cwd` → the project directory is discovered and the
      session is resumable.
- [ ] **Web UI.** The Junie card is selectable in the New-session dialog and starts a session.
- [ ] **Experiment (optional).** Check whether `{"decision":"ask"}` on stdout with exit 0 passes a
      `PermissionRequest` through *without* the TUI warning that exit 1 produces. Adopt it ONLY if
      empirically confirmed — and never regress the exit code to 0 or 2, which would answer the operator's
      permission prompt for them.

## Known limitations (recorded, not fixed)

- **Junie's hooks are an EAP feature.** On a stable build that ignores the `hooks` config the session still
  launches and attaches; its state simply stays coarse (`running` until reconciled). No hard version gate —
  `cliVersion` metadata is the support handle.
- **`PermissionRequest` fall-through costs a small TUI warning** per permission dialog (exit 1 is the
  documented way to keep Junie's own dialog).
- **In-TUI session switching is invisible.** `/new` or a `/history` selection changes the live junie session
  behind one kotgent row; resume then targets the originally bound id. This can only be healed if Junie
  ever puts `session_id` in its hook payloads (the normalizer and the ingress rebind seam are already wired
  for that day).
- **A junie session that never ran a task has no discoverable project directory** (no index row), so
  importing it requires `--cwd`. There is nothing to resume in such a session anyway.
- **A same-cwd neighbour launched in the same second could be bound.** Same class as codex, mitigated the
  same way: the birth-time threshold, the index's cwd filter, the hook rebind seam and the conditional
  model write.
