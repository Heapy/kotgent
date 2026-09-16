# Claude Code in Kotgent

[All agents](../../README.md#agents)

Kotgent runs the Claude Code terminal interface in `tmux`. Claude owns the conversation history;
Kotgent provides the shared terminal, session status, notifications, and available quota readings.

## Setup and launch

Install and sign in to the `claude` CLI, then run `kotgent install` from a login shell where it is on
`PATH`. The integration was developed against Claude Code 2.1.x.

```shell
kotgent start claude /path/to/project
kotgent resume <kotgent-session-id>
```

For versions that support `claude --session-id`, Kotgent allocates the conversation id before launch.
Older versions report the id through the `SessionStart` hook. Resume passes the saved conversation id
to `claude --resume`; it depends on Claude's transcript still being available.

## Import an existing conversation

Find the provider session id in the `claude --resume` picker or in the transcript filename:
`~/.claude/projects/<encoded-project-dir>/<session-id>.jsonl`.

```shell
kotgent import claude <claude-session-id>
```

Import verifies the transcript, discovers the project directory, and resumes the conversation under
Kotgent. Add `--cwd /path/to/project` to override directory discovery, or `--no-start` to register it
without launching. See the [common import behavior](../../README.md#the-cli) for duplicate sessions and
conversations still running in another terminal.

## Configuration and status line

Kotgent supplies its hooks and status-line capture through a generated `--settings` file for each
launch. It does not rewrite Claude's user or project settings.

An existing command-type status line from `~/.claude/settings.json` is chained: it receives its original
input and keeps its output and exit status. Kotgent captures quota data alongside it. Changes to that
user command take effect when the next session launches or resumes. Project/local status-line commands
are not chained; the launch settings take precedence in the ordinary unmanaged case. Managed Claude
policy can override launch settings.

## Session status and notifications

Hooks report prompt submission, tool activity, turn completion, and session identity. Kotgent treats
Claude's `Notification` hook as an approval request, so the **Needs attention** state is inferred from a
general notification rather than a permission-specific event. Read and answer the actual prompt in
the terminal.

The sidebar records the CLI version and discovers the model from transcript data on a best-effort basis.
Closing a viewer leaves Claude running; Stop ends its process, and Resume reopens the saved conversation.

## Usage and known limitations

The sidebar displays the available `five_hour` and `seven_day` percentage windows from Claude's
status-line payload. Missing windows stay hidden. Capture runs when Claude renders its status line;
Kotgent does not poll Claude's usage endpoint.

Claude can render cached quota without making a new provider request. A matching heartbeat may refresh
the displayed observation time, but that time describes capture activity, not when Claude fetched the
quota. The captured payload contains neither the account identity nor the quota-fetch timestamp, so
Kotgent assumes one Claude account on the Mac.

Reset detection requires both a decrease in the shared account meter and a decrease against an
established source-session baseline for the current reset generation. A lower first reading from a new
session or an unchanged cached render cannot establish a reset. This can miss resets seen only by a new
source or a source catching up after an earlier reset. An early reset first observed after the expected
window end is treated as scheduled. Without provider activity, there is no new evidence.

Only early resets of the weekly window produce usage-reset notices; scheduled resets and five-hour
resets stay quiet. See the [Web UI guide](../../README.md#web-ui--kotgent-web) for freshness indicators and
notification delivery.

**Fable is not shown.** Its separate limit may appear in Claude's `/usage` view and usage page, but the
status-line payload captured by Kotgent does not include it. This is a known, accepted limitation:
the repository author has decided not to implement Fable support.
