# Login shell in Kotgent

[All agents](../../README.md#agents)

A shell session gives you a shared terminal without installing an agent CLI. It uses the same `tmux`
hosting, browser/PWA access, and session controls as agent sessions.

## Start and resume

```shell
kotgent start shell /path/to/project
kotgent resume <kotgent-session-id>
```

Kotgent selects the login shell from `$SHELL`, then the user's passwd entry, with `/bin/zsh` as the
fallback. It launches the shell with `-l` in the selected working directory.

Closing a terminal or browser detaches that viewer and leaves the shell running. Stop ends the process.
**Resume starts a new login shell in the same working directory.** It does not restore jobs, environment
changes, or an interactive conversation from the previous process.

## Status and limitations

Shell sessions have no provider hooks, model, quota meter, or agent attention notifications. Kotgent
tracks their process lifecycle and terminal connection.

There is no shell import: an existing shell has no provider conversation record for Kotgent to adopt.
Starting an agent manually inside a shell session does not turn that row into an agent integration;
use the corresponding `kotgent start` command when you want its hooks, status, and supported usage meter.
