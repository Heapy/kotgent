# Product intent

Kotgent lets one person supervise long-running coding work without staying at the laptop. The same live
session should remain usable from an IDE terminal, a browser, or a phone, and losing any client—or
restarting the daemon—must not discard the work.

## User outcomes

- Start a new agent or shell, or adopt a provider conversation that began elsewhere, and continue it from
  any client without losing its terminal or history.
- Leave the machine and receive a notification only when a session needs human attention.
- See what every session is doing, organize sessions by project, and recover stopped conversations.
- Keep an ordered, dependency-aware backlog per project so ideas become explicit work rather than context
  remembered by the operator or trapped in one session.
- Use the full terminal and backlog comfortably from an installable phone or tablet PWA.

## Product principles

- **Local first.** The daemon, credentials, session metadata, and backlog live on the user's Mac. Remote
  access is an explicit, secure tunnel to that machine, not a hosted kotgent account or a second source of truth.
- **Clients are interchangeable and disposable.** Agent and shell processes live in `tmux`; closing the
  IDE, browser, or PWA only detaches a viewer. The daemon coordinates them but does not make their lifetime
  depend on its own process.
- **Providers own conversations.** Claude, Codex, and Junie remain responsible for their transcripts and
  resume semantics. Kotgent records enough identity and state to reconnect; it does not replace provider
  storage or pretend provider differences do not exist.
- **Attention is actionable.** State and notifications should answer whether the operator must intervene,
  not merely report that bytes or events arrived. Approvals remain human decisions made in the terminal.
- **Work is explicit but collaborative.** Project identity survives moves, clones, and worktrees. Task links
  coordinate sessions without exclusive locks: more than one session may work the same task, and an agent
  hands completed work to review rather than silently accepting it on the human's behalf.
- **Destructive-looking actions preserve recovery.** Finishing a session hides it without deleting its
  history. Deleting a project is a reversible tombstone that keeps its file, tasks, activity, dependencies,
  and linked sessions intact.
- **Clients agree.** Phone, desktop, and terminal clients should converge on the same state; reconnects,
  delayed responses, and arrival timing must not leave them presenting conflicting versions of the work.
- **Mobile is a real client.** The PWA should feel native and support the same terminal and work-management
  loop. Platform constraints are handled honestly instead of replacing the UI with a reduced mobile mode.
- **Degrade honestly.** Missing provider hooks, model metadata, push support, or a resumable transcript may
  reduce fidelity, but must not fabricate state or endanger a running session.

## Deliberate boundaries

- Kotgent is a terminal-oriented control plane, not a replacement chat UI, autonomous approval service, or
  cloud scheduler.
- The service worker is network-only: without the local daemon there is no useful offline application state.
- Shell sessions provide a remotely reachable login shell, not a durable conversation or provider import.
