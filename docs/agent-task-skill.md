# The agent task contract

This is the interface an **Agent Skill** is written against — the one that lets an agent running inside a
kotgent session read the task it was started for, report progress on it, close it, and pick up the next
one without a human typing anything.

The skills themselves are **not in this repository**. They live in Heapy/Kortex and ship through the
separate `kotgent` plugin as `create-tasks`, `sync-tasks`, `review-tasks`, `take-task`, and `work-task`.
This file is the contract they implement: the commands, their output shapes, their exit codes, and the
four things an agent must not assume. Nothing here is code and nothing here is tested by `./kotlin test`;
when the CLI changes, this file changes with it.

Everything below is the `kotgent` CLI. There is no SDK, no library and no direct daemon access — the
commands are the API surface, and they exist because an agent already has a shell.

## The loop

```
kotgent task show                            # what am I working on?
kotgent task comment -m "found the cause…"   # progress, as often as useful
kotgent task review -m "summary, commits"    # hand it back for review — and stop there
kotgent task next                            # ONLY once this session holds no task; exit 3 = stop
```

Three commands and a **conditional** fourth. `review` is where the agent's work on a task ends; `next` is
not the next step of that task, it is the first step of the following one, and it may only be run by a
session that is free. Read the section below before wiring the loop — running `next` straight after
`review` is the one composition that quietly breaks, and kotgent will not stop you.

- **`kotgent task show`** — no ref. The agent does not know its task's id and must never be told one by
  the prompt; see "How a ref-less command knows its subject" below.
- **`kotgent task comment -m TEXT`** — appends one attributable row to the task's activity feed. `-m -`
  reads the text from stdin instead, which is how a long summary avoids shell quoting:
  `kotgent task comment -m - <<'EOF'`. An empty pipe is a usage error, not an empty comment.
- **`kotgent task review -m "…"`** — moves the task to `review` and records the message as the transition's
  explanation, in one operation. The `-m` is optional to the CLI and mandatory to this contract: a review
  with no summary is a card a human has to open a terminal to understand. Say what changed and name the
  commits. **It deliberately leaves the session linked** — one session, one task, end to end, because the
  human reviews *that* session's terminal and diff.
- **`kotgent task next`** — links the next eligible task in the project to this session and prints it,
  **overwriting whatever link the session already holds**. **Exit code `3` means "nothing eligible" —
  stop.** It is a distinct code precisely so a script does not confuse an empty backlog with a network
  failure.

`kotgent task review` is the agent's terminal state. **The agent does not run `kotgent task done`**:
closing a task is the human's call after reading the review, either from the board or by pressing "Done"
on the session (which closes the linked task and archives the session). `task done` exists in the CLI for
the operator; a skill that calls it is skipping the review it just asked for.

## `task next` only from a free session

Three rules, each correct on its own, and one order in which they are jointly wrong:

1. `task review` keeps the link — the task in `review` is still this session's task.
2. `task next` **overwrites** `sessions.task_ref`. A session works one task at a time and re-pointing it
   is an ordinary write: no warning, no error, no feed entry on the task that was dropped.
3. Session "Done" closes whatever the link points at **now**, then archives the session.

So `review A` immediately followed by `next B` leaves task A sitting in `review` with **no session at
all** — no terminal for the human to open, no diff, and nothing in A's activity feed saying it lost its
worker — and when that human then presses "Done" on the terminal in front of them, kotgent closes **B**,
the task the agent had only just started and probably has not finished, and archives the session carrying
it. Both tasks end up wrong from two operations that each did exactly what they promise.

**So: run `task next` only when this session is linked to nothing.** A session becomes free when the human
disposes of the reviewed task, and the two ways differ in what happens to the agent:

- **Closed from the board** — every holder is unlinked and the sessions stay **alive**. That is what hands
  a long-lived worker session back to `task next`, and it is the path the loop is built around.
- **"Done" on the session** — the task is closed and the session is archived. That agent's run is over;
  there is no next task for it.

**`kotgent task unlink` is not the way out of this.** It would free the session, but it would also take
the reviewed task's only session with it — leaving the human the same card with no terminal behind it,
just with an `unlinked` row in the feed to explain it. `unlink` is for abandoning work, not for finishing
it.

The check is a ref-less `kotgent task show`: a session holding no task fails with exit `1` and
`{"error":"this session is not linked to a task — …"}` on stderr, which is exactly the "free" signal. Poll
that between tasks rather than assuming; there is no notification channel, and (see below) kotgent
deliberately refuses to enforce anything about who holds what — including refusing to stop this. Keeping
the reviewed task linked until a human releases it is the skill's job, not the daemon's.

## kotgent does not enforce one worker per task

Read this before designing anything around it.

**A task may be linked from any number of sessions at once, and kotgent will not stop it.** There is no
lock, no lease, no exclusive claim. The reason is not that it was hard: an operator can open a second
terminal in the same repository and start an agent kotgent never hears about, so an exclusivity invariant
would only hold against kotgent's own API — and an invariant that holds against only one of the two doors
is not an invariant.

What is actually true:

- `kotgent task next` **will not hand the same task to two agents in a row.** It advances the task from
  `todo` to `in_progress` conditionally, and a racing second caller sees that write lose, re-queries and
  takes the next candidate instead. This is a *selection convention* — it stops the obvious duplicate — and
  it is not a guarantee about anything else.
- `kotgent task claim <ref>` on a task that is **already `in_progress` is allowed** and simply adds another
  link. It is not an error, it does not warn, and it will not be made one.
- The board **shows every linked session** on a task's card. Two dots on a card is a legitimate state, not
  a corruption to be reconciled away.
- Pointing a session at a different task overwrites that session's link. A session works one task at a
  time; a task does not work one session at a time. That includes `task next`, which is why the loop above
  runs it only from a free session — the daemon will not refuse a `next` from a session that is still
  holding a task in `review`, so nothing but the skill's own discipline prevents it.

**Therefore: an agent must not assume it is alone on its task.** Before making a large or destructive
change, look at the working tree and the recent activity feed rather than trusting that the task being
`in_progress` means "in progress by me". If the feed shows another session commenting, say so in a comment
of your own instead of racing it. Coordination between two agents on one task is a human's job, and the
feed is where they will look for it.

## How a ref-less command knows its subject

`task show`, `task comment`, `task review`, `task done` and `task unlink` all take an **optional** ref. With
no ref, the CLI resolves the subject through the daemon:

1. The CLI reads `$TMUX` and `$TMUX_PANE` and sends the pane id in the `X-Kotgent-Tmux-Pane` header — the
   same header the provider hooks send. It does this **only** when `$TMUX`'s socket path is kotgent's own
   (`${TMUX_TMPDIR:-/tmp}/tmux-<uid>/kotgent`), because pane ids are unique per tmux *server*: a `%2` from
   the operator's own tmux would otherwise resolve to an unrelated kotgent session. Outside a kotgent pane
   the header is simply absent — it fails closed. See `src/cli/TmuxSelf.kt`.
2. `GET /api/v1/whoami` resolves that pane to a session and answers `{ sessionId, projectId, taskRef }`.
3. The command uses that `taskRef` as its subject. A session linked to no task fails cleanly, telling the
   caller to name a ref or run `kotgent task claim <ref>` — it never guesses.

`/whoami` is **pane resolution, not a session lookup**. Two consequences a skill has to respect:

- **Run these commands from inside the session's own pane.** A subprocess of the agent inherits `$TMUX` and
  `$TMUX_PANE`, so the ordinary case just works; a command dispatched to some other machine, shell or
  container does not.
- **`--session <id>` is the escape hatch, and it skips `/whoami` entirely.** When it is given the CLI
  already knows the id, sends it in the request body, and never asks about a pane it may not have. Every
  `task` subcommand accepts it. This is the supported way to drive the backlog from outside a pane — a
  wrapper, a test, a human at a laptop.

`task list` and `task next` behave the same way for the *project* rather than the task: inside a pane they
need no argument (the project comes from the calling session's row), and `--project <uuid>` names it
explicitly. `--project` takes the uuid out of `.kotgent.json`, which `kotgent project list` prints.

## Output, exit codes and errors

**Every one of these commands prints JSON and only JSON.** There is no human-readable table to fall back
to and no prose mixed in — the family exists for a parser. The split between the two streams is the
contract:

- **stdout is the answer.** One JSON value, on success.
- **stderr is why there is none.** One JSON object: `{"error":"…","status":400}`, where `status` is the
  daemon's HTTP status when there was one and absent when the failure never reached it (no daemon, no
  token, an unresolvable subject). No stack traces, ever.

**The one exception, and it is exit `2`:** an argument the CLI cannot *parse* is rejected before any
command body runs, so it never reaches the JSON error renderer. `runCli` prints the complaint, a blank
line and the whole `USAGE` block as **plain text** on stderr. A parser must therefore treat exit `2` as
"stderr is prose, not JSON" — the one `2` that does answer in JSON is `project init` with a path it cannot
anchor, which fails inside the command. In practice a skill should never see either: exit `2` means the
skill built a command line wrong.

Exit codes:

| Code | Meaning |
|---|---|
| `0` | success |
| `1` | a daemon or API failure — unreachable daemon, missing token, an HTTP error, an unresolvable subject. stderr is JSON. |
| `2` | a usage error — bad arguments, a malformed ref, an unanchorable `project init` path. stderr is **prose** except for `project init`. |
| `3` | **`task next` only**: nothing eligible. Not a failure. |

`task next` with nothing eligible still prints parseable JSON — `{"task":null}` — so a caller may read the
answer instead of the code. Nothing else ever exits `3`.

## What each command prints

The shapes are the transport DTOs in **`src/transport/TaskDtos.kt`**; read them there rather than from a
copy here that will drift. The daemon encodes with `encodeDefaults = true`, so every declared field is
present in the JSON, nulls included — a parser may rely on the key existing.

| Command | stdout |
|---|---|
| `task add <title> [--body B] [--project P]` | `BacklogEntryDto` |
| `task list [--project P]` | array of `BacklogEntryDto`, in rank order |
| `task show [<ref>]` | `TaskDetailDto` |
| `task next [--project P]` | `BacklogEntryDto`, or `{"task":null}` with exit `3` |
| `task claim <ref>` | `{"ref":"local:42","linked":true}` (plus `"sessionId"` when `--session` was given) |
| `task comment [<ref>] -m TEXT` | `ActivityEntryDto` |
| `task review [<ref>] [-m TEXT]` | `BacklogEntryDto` |
| `task done [<ref>] [-m TEXT]` | `BacklogEntryDto` |
| `task unlink [<ref>]` | `{"ref":"local:42","unlinked":true}` (plus `"sessionId"` when `--session` was given) |
| `task move <ref> --top\|--bottom\|--before R\|--after R` | `BacklogEntryDto` |
| `task dep add\|rm <ref> --on R` | `BacklogEntryDto` — the **edited** entry, so `blocked` is readable |
| `task delete <ref>` | `{"ref":"local:42","deleted":true}` |
| `project list [--archived]` | array of `ProjectDto`; live projects, or the deleted ones with `--archived` |
| `project init [<path>] [--name N]` | `ProjectDto`; explicit create/adopt, which also clears an existing delete mark |
| `project delete <uuid>` | `ProjectDto`, now `archived: true` |
| `project restore <uuid>` | `ProjectDto`, now `archived: false` |
| `start <agent> [cwd] --task <ref>` | `{"taskRef","cwd","cwdSource","session":SessionDto}` |

Four behaviours the table cannot show. **`task unlink` with an explicit ref the session does not hold is a
`409`**, not a silent no-op: the daemon refuses to clear a link nobody asked about, and names the ref that
is actually held. The same `409` answers the RACE of that case — if a `task claim` or a `task next` from
somewhere else re-points the session between the daemon reading its link and clearing it, the clear is
conditional and writes nothing, and that is reported rather than acknowledged: an `{"unlinked":true}` there
would tell you that you released a task while your session is in fact working on another one. Re-read with
a ref-less `task show` and unlink what it holds now. Unlinking a session that holds nothing is `ok` (the
caller asked for "not linked to this", which is true). And **`task delete` on a ref that names no task
exits `1`**, not `0` — a script that deletes what it just created and is told "nothing there" has hit a
real problem, so it is an error rather than `{"deleted":false}` on the success stream.

**A deleted project is not a source of work.** `task add` refuses there rather than quietly filing the
task into it or starting a fresh project beside it, and `task next` refuses too — it picks the next card
out of the project for you, then moves it to `in_progress` and links your session to it. A ref-less
`task add` whose session resolves to such a project exits `1` with
`{"error":"project '<name>' (<uuid>) was deleted — …","status":400}`, and the message names the ways out:
`kotgent project restore <uuid>`, `--project <other uuid>`, and deleting or moving the `.kotgent.json`
that names the project — the delete never touches that file, so the file is what keeps a directory
resolving to a project that is gone. If your session was stamped with the project before it was deleted,
that last exit reads a little differently: moving the file frees the *directory*, but this session keeps
the project it already carries, so it takes a session started afterwards. A uuid the caller spelled out
itself gets a bare `404` instead, the same answer an unknown uuid gets: that is
`task add --project <deleted uuid>` and
`task next --project <deleted uuid>`. A ref-less `task next` answers in one of two shapes: `404` when
your session is stamped with the deleted project, and the ordinary `session '<id>' resolves to no
project` `400` when it is not — a session started in a deleted project's directory is never stamped at
all. Every one of these exits `1`; exit `3` still means only "nothing eligible" in a live project.

**Do not retry any of them.** Nothing about the environment changes on its own — the `.kotgent.json` that
names the project is still on disk and is exactly what the daemon refuses to act on — and every way out is
the human's call, so say in a comment that the project is deleted rather than restoring it, re-homing the
task or picking another project unasked. **`project init` is a restore in this situation, not a workaround:**
when the directory's retained `.kotgent.json` names the deleted project, init explicitly adopts it again and
clears the delete mark. An agent must not run it unless the human asked to restore or re-adopt that project.

What stays open for a deleted project is everything that addresses ONE existing card: `task show`,
`task list`, `task comment`, `task review`, `task done`, `task claim` and `task unlink` all keep working,
so a session already holding a card can finish it. **The line is drawn at selecting work, not at writing
to it** — `task claim <ref>` on a `todo` card in a deleted project starts it, moving it to `in_progress`
and adding a `linked` row to its feed exactly as it would in a live project, and `task review` /
`task comment` move and write the same way. What a deleted project no longer does is hand you a card you
did not name (`task next`) or accept a new one (`task add`). `task show` will not warn you, though —
`TaskDetailDto` carries the project's name and path but no deleted flag, so `project list` (which lists
the live projects only) is the authority on whether a project still exists. And **`project delete` and
`project restore` are both idempotent** — a repeat answers the same row instead of failing, and the only
`404` is a uuid the daemon has never seen.

The three shapes a skill actually reads:

- **`BacklogEntryDto`** — one board row. `ref`, `project`, `title`, `body`, `url`, `position`, `state`
  (`todo` / `in_progress` / `review` / `done`), `blocked`, `dependsOn`, `rev` and timestamps. `blocked` is
  derived server-side (`state == todo` and some dependency is not `done`); an agent should treat a blocked
  task as not workable — `task next` already refuses to hand one out. `url` belongs to the tracker seam
  and the built-in tracker never fills it: the key is always present and always `null` today.
  **`task dep` answers this shape too, and that is why it is worth reading**: `blocked` is the one thing a
  dependency edit changes, it cannot be worked out from the request, and the CLI has no events socket to
  learn it from later.
- **`TaskDetailDto`** — what `task show` prints: the entry, the project's name and last-seen path, both
  directions of its dependencies, **every session linked to it** (`sessions`, which is where the
  no-exclusivity rule becomes visible), and the whole activity feed.
- **`ActivityEntryDto`** — one feed row. `kind` is `created` / `comment` / `transition` / `linked` /
  `unlinked`; `author` is the acting session's id, or the literal `board` for a change a human made from
  the Web UI with no session behind it; `fromState`/`toState` are set only on a transition.

A **task ref** is `<tracker>:<key>` — one colon, both halves non-blank, `[A-Za-z0-9_-]`, first character
alphanumeric, 128 characters total. The built-in tracker is `local`, so refs read `local:42`. A ref never
contains `.` or `/`, which is what makes it safe in a URL and in argv. Do not construct one: refs come from
the daemon.

## `.kotgent.json`, and the commit you must not sweep it into

A project is a **committed file**, not a path: `.kotgent.json` in the project root holds a uuid and a name,
so a backlog survives a `git worktree`, a move, a rename and a clone, where a path key would fork one body
of work into several.

**The daemon writes that file and never commits it.** It appears the first time a task is created in a
location that has no project, and it is written to be committed — mode `0666 & ~umask`, not `0600`, and an
existing file always wins the race rather than being overwritten.

So an agent doing ordinary work may find a new untracked `.kotgent.json` in its repository that it did not
create. The rule:

- **Mention it.** Say in a comment or the review summary that kotgent created it, so the human is not
  surprised by an untracked file.
- **Do not sweep it into an unrelated commit.** Committing it is the right end state, but it belongs in its
  own commit with its own message, not folded silently into the change under review. Staging with a
  whole-tree `git add -A` is exactly how it ends up in the wrong one.
- **Do not delete it.** Deleting it unlinks the backlog from that checkout.

**A project can be deleted while its file stays**, so the presence of `.kotgent.json` is not proof that
there is a project to file into. `kotgent project delete <uuid>` takes a project out of every selector and
makes `task add` refuse there, and deliberately does not touch the file: kotgent creates `.kotgent.json`
and never removes it. Nothing cascades either — the tasks, their dependencies, their feeds and the
sessions linked to them are all left where they are — which is why `kotgent project restore <uuid>` brings
the whole backlog back. `kotgent project list` is the authority on what is live; `--archived` lists the
deleted ones, and is how an operator finds the uuid of a checkout that no longer exists.

## Things the contract deliberately does not offer

- **No streaming and no notification channel.** The commands are request/response. An agent that wants to
  know whether its task changed re-reads it; the live board is the browser's business, not the agent's.
- **No way to answer an approval prompt.** kotgent never answers an approval — the operator does, in the
  terminal. Nothing in the task family changes that.
- **No `task done` from the agent** (see "The loop").
- **No assumption of solitude** (see the section that says so).
- **No git.** kotgent runs no `git` subprocess anywhere in this path; branching, committing and pushing are
  the agent's own work, governed by the repository's conventions and not by this contract.
