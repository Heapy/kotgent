# Mutexes in Kotgent

[All agents](../../README.md#agents)

A mutex serializes work across kotgent sessions: a build that shares output with other worktrees, a
deploy, a test run on a shared tmux socket. Any agent or shell session can take one by name. Only a
kotgent session can hold or wait: the call must run inside a kotgent pane or name a running session
with `--session`, and a session's end releases everything it holds.

## Commands

```shell
kotgent mutex run kotlin-build -- ./kotlin test -p macosArm64 -p jvm
kotgent mutex acquire deploy [--wait 90] [--ticket T] [--json] [--session S]
kotgent mutex release <token> [--json] [--session S]
kotgent mutex list [--json]
```

- `run` waits for the key, runs the command in the foreground with the terminal's stdio, forwards
  SIGINT, SIGTERM and SIGHUP to it, and releases after it exits, whatever the exit. It returns the
  command's own exit status, or 127 when the command cannot be started. Prefer it to `acquire`/`release`:
  nothing is left held when the command fails.
- `acquire` long-polls for up to `--wait` seconds (default 90, at most 540). It prints `acquired: <token>`,
  or `pending: <ticket>` with the position in line. Call `acquire <key> --ticket <ticket>` again to keep
  the place; a pending ticket lapses 30 s after its last answer.
- `release` needs the token `acquire` printed and must come from the holding session.
- Every acquisition gets its own token, so two subagents in one pane still exclude each other.

Exit codes are the same for every verb: `0` for any printed result, `pending` included; `1` when the
daemon cannot be reached or answers with an error; `2` for a usage error; `3` for a conflict, such as a
lapsed ticket or a token held by another session.

## Web UI

The command palette's **Open mutexes** shows `/mutexes`: each key, its holder, since when and for how
long, and its queue. A holding older than 15 minutes is highlighted. **Force release** takes the key away
after an in-app confirmation and hands it to the first waiter. The open session's terminal header shows
`🔒 key` for what it holds and `⏳ key · #N` for its place in a queue.

## Known limitations

- Holdings have no timeout. A holder that never releases keeps the key until its session ends or you force
  release it.
- `SIGKILL` of `kotgent mutex run` cannot release: the key stays held until the session ends or is force
  released.
- Releasing does not stop anything. A command left running after its session's holding was force released,
  or after the session ended, keeps running unguarded.
- Waiters are not persisted. A daemon restart drops every queue. A waiting `mutex run` queues again if the
  daemon answers its next poll and exits with 1 otherwise; `acquire --ticket` answers with a conflict, so
  start over without the ticket.
