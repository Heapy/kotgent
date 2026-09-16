# Codex in Kotgent

[All agents](../../README.md#agents)

Kotgent runs the Codex terminal interface in `tmux`. Codex keeps its conversation in rollout files;
Kotgent provides the shared terminal, session status, notifications, and available quota readings.

## Setup and launch

Install and sign in to the `codex` CLI, then run `kotgent install` from a login shell where it is on
`PATH`. If your installation uses an `env node` launcher, the captured `PATH` must also resolve Node.
After changing Node versions or moving the CLI, run `kotgent install` again from that shell.

The adapter was developed against Codex CLI 0.145; usage capture was also probed with 0.154.0. These are
verification baselines, not a minimum-version guarantee; see [provider verification](../TESTING.md#provider-adapters).

```shell
kotgent start codex /path/to/project
kotgent resume <kotgent-session-id>
```

Codex does not preallocate a session id through this integration. Kotgent captures it after launch from
`SessionStart` or the rollout file, so it may not be available immediately. Resume passes the saved id
to `codex resume` and requires the provider record to remain available.

## Import an existing conversation

Find the provider session id in the `codex resume` picker or in the trailing UUID of a rollout filename:
`~/.codex/sessions/<date>/rollout-<timestamp>-<session-id>.jsonl`.

```shell
kotgent import codex <codex-session-id>
```

Import verifies the rollout, discovers the project directory, and resumes the conversation under
Kotgent. Add `--cwd /path/to/project` to override directory discovery, or `--no-start` to register it
without launching. A conversation archived by Codex cannot be imported from its archived rollout.
This is separate from archiving a Kotgent row, which can be restored in the Web UI. See the
[common import behavior](../../README.md#the-cli) before importing a conversation still running elsewhere.

## Configuration, status, and approvals

Kotgent installs its hooks through launch-scoped `-c 'hooks={…}'` settings. It does not modify the
user-level configuration under `~/.codex`.

Hooks report prompt submission, tool activity, turn completion, session identity, and session end.
The explicit `PermissionRequest` event drives **Needs approval**. Kotgent observes the request; you
approve or deny it in Codex's terminal dialog. The sidebar records the CLI version and reads the model
from rollout data on a best-effort basis.

The current integration uses the terminal CLI and its hooks and files. It does not use Codex's app-server
protocol or offer a structured chat interface or separate approval buttons.

## Usage and known limitations

Kotgent reads quota metadata from the existing rollout after a turn completes. It performs a bounded
follow-up read to allow a delayed record to appear; there is no provider polling or continuous quota
refresh during a long turn. Scanning the same record again does not make the observation newer.

Available percentage windows are shared across Codex sessions. Labels follow the reported duration:
`primary` is not always a five-hour window and can be weekly. `secondary` may be absent. Credits without
a usage percentage do not become a quota bar. Kotgent assumes one Codex account on the Mac.

A detected reset requires both a percentage decrease and a changed, known reset timestamp. Only an
early reset of a weekly window produces a usage-reset notice, whether that window occupies the primary
or secondary slot. Scheduled and shorter-window resets stay quiet. See the
[Web UI guide](../../README.md#web-ui--kotgent-web) for freshness indicators and notification delivery.
