# Junie in Kotgent

[All agents](../../README.md#agents)

Kotgent runs the Junie terminal interface in `tmux` and reconnects viewers to the same live process.
Junie owns the conversation history in its session directories.

## Setup and launch

Install and sign in to the `junie` CLI, then run `kotgent install` from a login shell where it is on
`PATH`. Hook support was developed against Junie 26.8.3 (EAP). Builds that ignore those hooks can still
launch and attach, but Kotgent has less detailed session state.

```shell
kotgent start junie /path/to/project
kotgent resume <kotgent-session-id>
```

Kotgent discovers the provider id after launch from Junie's session directory; it does not preallocate
one. Resume uses `junie --resume --session-id <provider-session-id>`. The sidebar records the CLI version
and discovers the model from Junie's saved events on a best-effort basis.

## Import an existing conversation

Find the provider session id in Junie's `/history` or as a directory under `~/.junie/sessions`, for
example `session-260730-015553-1j1h`.

```shell
kotgent import junie <junie-session-id>
```

Import verifies the saved session, discovers its project directory, and resumes it under Kotgent. Add
`--cwd /path/to/project` to override directory discovery, or `--no-start` to register it without launching.
A session in which no prompt was submitted may have no recorded project directory and need `--cwd`.

Junie retains only recent session context. A session whose directory Junie has pruned cannot be
imported or recovered by Kotgent. See the [common import behavior](../../README.md#the-cli) before
importing a conversation still running in another terminal.

## Configuration, status, and approvals

Kotgent supplies hooks using `junie --config-location <kotgent-owned file>`. The user's
`~/.junie/config.json` is not modified.

With hooks available, Kotgent receives prompt submission, tool activity, turn completion, session
identity, and session end. Junie's explicit `PermissionRequest` drives **Needs approval**. Kotgent leaves
the decision to Junie's interactive dialog; it does not automatically approve or deny the request.

Without hook support, the terminal remains usable and process lifecycle can still be reconciled, but
fine-grained activity and approval notifications are unavailable. The terminal is the place to check
what Junie needs.

## Usage limits

Kotgent does not currently capture Junie quota readings or show a Junie usage bar. It cannot produce
Junie quota-reset notifications. Check Junie's own interface for usage or limit messages.
