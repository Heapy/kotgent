# Claude Code Channels: Architecture and Local Implementation Notes

Research date: 2026-07-24

## Summary

Claude Code Channels lets an MCP server push an external event into an already-running Claude Code
session. A channel can be one-way, such as a CI webhook, or two-way, such as a Telegram or Discord
bridge. The event is handled in the existing local session, so Claude keeps that session's conversation
context, working directory, files, and tools.

Channels is currently a research preview. The supported official channel plugins are Telegram, Discord,
and iMessage, with `fakechat` available as a local demo. Custom channels can be developed using the same
MCP extension.

The implementation has two distinct parts:

1. Claude Code contains the channel-aware MCP client, event queue integration, policy checks, and the
   hidden `--channels` command-line option.
2. A channel plugin contains the platform-specific bridge, access control, and outbound MCP tools.

Official documentation:

- [Push events into a running session with channels](https://code.claude.com/docs/en/channels)
- [Channels reference](https://code.claude.com/docs/en/channels-reference)
- [Official channel plugin sources](https://github.com/anthropics/claude-plugins-official/tree/main/external_plugins)

## What is present locally

The project's `.claude/` directory does not contain the Channels implementation. It currently contains
Claude-managed worktrees.

The user-level Claude directory does contain the installed Telegram channel:

```text
~/.claude/
├── channels/
│   └── telegram/
│       ├── .env
│       ├── access.json
│       └── approved/
└── plugins/cache/claude-plugins-official/telegram/0.0.6/
    ├── .claude-plugin/plugin.json
    ├── .mcp.json
    ├── ACCESS.md
    ├── README.md
    ├── server.ts
    └── skills/
```

The inspected installation has:

- Claude Code 2.1.218;
- the user-scoped `telegram@claude-plugins-official` plugin enabled;
- Telegram plugin version 0.0.6;
- Bun 1.3.14;
- a configured bot token stored in `.env`;
- `dmPolicy` set to `allowlist`;
- one allowed user, no enabled groups, and no pending pairings;
- file mode `0600` on both `.env` and `access.json`.

No bot PID file was present during inspection, so the Telegram poller was not active at that moment.
No credentials or user IDs are included in this document.

The platform bridge is a self-contained TypeScript MCP server in:

```text
~/.claude/plugins/cache/claude-plugins-official/telegram/0.0.6/server.ts
```

It is approximately 1,000 lines and uses:

- Bun as its runtime;
- `@modelcontextprotocol/sdk` for MCP;
- `grammy` for the Telegram Bot API;
- stdio for communication with Claude Code.

The Claude Code side is compiled into the installed Mach-O executable rather than stored as source
under `.claude`. Inspection of Claude Code 2.1.218 confirms that it contains:

- the hidden `--channels <servers...>` option;
- registration for `notifications/claude/channel`;
- registration and reconnection handling for channel servers;
- conversion of channel notifications into queued prompts;
- channel-origin metadata;
- slash-command suppression for channel messages;
- remote tool-permission relay.

These are implementation observations, not a stable public API. The documented protocol may change
while Channels remains in research preview.

## Architecture

At a high level:

```text
External system
    │
    │ Telegram/Discord polling, iMessage database, or HTTP webhook
    ▼
Local channel plugin
    │
    │ MCP notification over stdio
    ▼
Running Claude Code session
    │
    │ Existing context, files, tools, and permissions
    ▼
Claude calls an outbound MCP tool
    │
    │ reply / react / edit / attachment operation
    ▼
External system
```

A channel is therefore similar to a normal MCP server, but with an additional push direction. A normal
MCP server waits for Claude to invoke a tool or request a resource. A channel server can independently
send an event to Claude Code.

## Session startup and registration

Installing a plugin makes its skills and MCP configuration available, but it does not allow that server
to inject events into every session. Channel delivery is explicitly enabled per session:

```shell
claude --channels plugin:telegram@claude-plugins-official
```

The Telegram plugin's `.mcp.json` tells Claude Code to start the local server with Bun:

```json
{
  "mcpServers": {
    "telegram": {
      "command": "bun",
      "args": [
        "run",
        "--cwd",
        "${CLAUDE_PLUGIN_ROOT}",
        "--shell=bun",
        "--silent",
        "start"
      ]
    }
  }
}
```

Claude Code spawns the process and communicates with it over stdin/stdout. For Telegram and Discord,
the plugin polls the platform API, so there is no inbound URL or public local port to expose.

The server declares a channel-specific experimental capability:

```ts
const mcp = new Server(
  { name: "telegram", version: "1.0.0" },
  {
    capabilities: {
      tools: {},
      experimental: {
        "claude/channel": {},
        "claude/channel/permission": {}
      }
    }
  }
)
```

`claude/channel` registers the server as an inbound event source. The optional
`claude/channel/permission` capability opts it into permission relay.

Having the server in `.mcp.json` is not enough to enable inbound delivery. It must also be selected by
`--channels`, pass the effective plugin allowlist, and be permitted by organization policy.

## Inbound message flow

For Telegram, the plugin uses Bot API long polling. When it receives a message, it:

1. identifies the sender and chat;
2. runs the access gate;
3. drops, pairs, or accepts the message;
4. optionally downloads an attached photo;
5. starts a typing indicator and optional acknowledgement reaction;
6. sends an MCP notification to Claude Code.

The relevant notification has this shape:

```ts
await mcp.notification({
  method: "notifications/claude/channel",
  params: {
    content: text,
    meta: {
      chat_id,
      message_id,
      user,
      user_id,
      ts,
      image_path
    }
  }
})
```

`content` becomes the event body. Each `meta` entry becomes an attribute on the channel event presented
to the model:

```xml
<channel
  source="plugin:telegram:telegram"
  chat_id="123"
  message_id="456"
  user="example"
  ts="2026-07-24T12:00:00.000Z"
>
  Investigate why the build failed.
</channel>
```

The terminal renders a compact inbound line instead of the raw XML-like block. Internally, Claude Code
adds the event to the active session's prompt queue with channel-origin metadata. Channel input skips
slash-command handling, so a remote message such as `/compact` is treated as message text rather than a
local Claude Code command.

Because the event enters the existing session, Claude sees the same:

- conversation history;
- current working directory;
- checked-out files and uncommitted changes;
- MCP servers and built-in tools;
- permission mode;
- project instructions.

This is the main distinction from integrations that create a fresh cloud session.

## Outbound replies

Text printed by Claude in the local terminal is not automatically copied to Telegram. The channel
plugin adds ordinary MCP tools and system instructions telling Claude to use them.

The inspected Telegram plugin exposes:

| Tool | Purpose |
| --- | --- |
| `reply` | Send text and optional local files to an allowed chat |
| `react` | Add a Telegram-supported emoji reaction |
| `edit_message` | Edit a message previously sent by the bot |
| `download_attachment` | Download an attachment referenced by an inbound event |

The `reply` tool takes the `chat_id` from the inbound channel event:

```text
reply(chat_id, text, reply_to?, files?)
```

Long messages are split at Telegram's 4,096-character limit. Images can be sent as photos with an inline
preview; other files are sent as documents. Outbound operations are gated: the plugin refuses to send
to a chat that is not in the DM allowlist or configured groups.

Telegram's Bot API provides no general history or search API. The plugin sees new messages as they
arrive. Photos are downloaded eagerly, while other live attachments are represented by a file ID that
Claude can download if needed. Discord's plugin can expose limited recent-message retrieval because the
Discord bot API has different capabilities.

## Pairing and access control

A public bot without an access gate would be a direct prompt-injection endpoint into a local coding
agent. The official Telegram and Discord plugins therefore use sender-level access control.

Telegram stores its mutable state in:

```text
~/.claude/channels/telegram/access.json
```

The important fields are:

```json
{
  "dmPolicy": "allowlist",
  "allowFrom": ["<numeric-user-id>"],
  "groups": {},
  "pending": {}
}
```

DM policies:

| Policy | Behaviour |
| --- | --- |
| `pairing` | Unknown senders receive a temporary pairing code; their message is dropped |
| `allowlist` | Unknown senders are silently dropped |
| `disabled` | All inbound messages are dropped |

The expected setup flow is:

1. start in `pairing` mode;
2. send the bot a DM;
3. approve the returned code from the local terminal;
4. switch to `allowlist`.

Access changes are managed by the `/telegram:access` skill. The plugin's instructions explicitly forbid
approving a pairing or changing `access.json` because a channel message requested it. Such changes must
originate from the local terminal, because the remote request itself may be prompt injection.

For groups, access should be checked against the sender identity, not only the group or room ID. An
allowlisted group does not imply that every group member is trusted. The official plugin supports
per-group opt-in, sender restrictions, and mention-only delivery.

## Remote permission relay

A two-way channel can also relay tool approval dialogs.

When Claude wants to use a protected tool such as `Bash`, `Write`, or `Edit`, Claude Code can notify the
channel server:

```text
notifications/claude/channel/permission_request
```

The request includes:

- `request_id`: a short identifier;
- `tool_name`;
- `description`;
- `input_preview`.

The Telegram plugin forwards this to allowlisted DMs with See more, Allow, and Deny buttons. A remote
verdict is returned as:

```text
notifications/claude/channel/permission
```

with the original request ID and either `allow` or `deny`.

The terminal approval dialog remains active in parallel. Whichever valid response arrives first is
applied, and the other pending prompt is closed. A remote approval applies only to that request; it does
not change future permission policy.

Project trust and consent to a newly discovered MCP server are not relayed. Those still require local
terminal interaction.

## Lifecycle and availability

Events are delivered only while the Claude Code session and its channel subprocess are running. Closing
the session or restarting Claude Code without `--channels` stops delivery.

An always-on setup therefore needs one of:

- a persistent terminal or `tmux` session;
- a Claude Code background process;
- another supervised long-running process.

Channels requires Anthropic authentication through claude.ai or a Console API key. The preview is not
available through Amazon Bedrock, Google Cloud's Agent Platform, or Microsoft Foundry.

For Pro and Max users without an organization, Channels can be selected per session. For Team,
Enterprise, and managed Console organizations, administrators can control it with:

- `channelsEnabled`;
- `allowedChannelPlugins`.

During the preview, the `--channels` and `--dangerously-load-development-channels` options may be hidden
from `claude --help` even though they work.

## Official channel differences

| Channel | Inbound mechanism | Notable properties |
| --- | --- | --- |
| Telegram | Bot API long polling | Simple DM pairing; no general history/search |
| Discord | Gateway connection | DMs, guild channels, limited history and attachment retrieval |
| iMessage | Local Messages database | macOS only; requires Full Disk Access and AppleScript permission |
| Fakechat | Local browser UI | No external credentials; intended for testing and demonstrations |

iMessage does not require a bot token. It reads the local Messages database and sends responses through
AppleScript. Messages sent to oneself bypass pairing; other handles must be explicitly allowed.

## Building a custom channel

A custom channel is an MCP server that:

1. declares `experimental["claude/channel"]`;
2. connects to Claude Code using a supported MCP transport, normally stdio;
3. authenticates and gates its external sender;
4. emits `notifications/claude/channel`;
5. optionally exposes outbound tools;
6. optionally implements permission relay.

During the research preview, a bare server can be tested with:

```shell
claude --dangerously-load-development-channels server:webhook
```

An unapproved plugin can be tested with:

```shell
claude --dangerously-load-development-channels plugin:my-channel@my-marketplace
```

The development flag bypasses the channel-plugin allowlist only. It does not bypass an organization's
master `channelsEnabled` policy and should not be used with untrusted code.

For webhook-style integrations, the channel process can listen on a local HTTP port and translate each
authenticated POST into an MCP notification. If the external service cannot reach the local machine
directly, a separate authenticated relay or tunnel is required.

## Comparison with related Claude Code features

| Feature | Trigger | Where Claude runs | Session behaviour |
| --- | --- | --- | --- |
| Channels | External chat message, alert, or webhook | Existing local CLI session | Injects an event into the open session |
| Standard MCP | Claude invokes a tool or resource | Current session | On-demand pull initiated by Claude |
| Remote Control | User drives Claude from Claude web/mobile | Existing local session | Remote UI for steering the session |
| Claude in Slack | `@Claude` mention | Anthropic cloud | Typically starts a web/cloud task |
| Claude Code on the web | User starts a task | Fresh cloud sandbox | Clones a repository into an isolated environment |

Channels is most useful when a non-Claude system needs to wake an already-contextualized local agent.
Remote Control is a better fit when the user wants to operate the session directly from another device.

## Security considerations

Channels should be treated as a privileged remote interface to a local coding agent.

### Inbound prompt injection

Every accepted external message becomes model input. Authenticate the sender before emitting a channel
notification, and gate on sender identity rather than only room identity.

Keep the permanent policy in `allowlist` mode. Pairing is a temporary identity-discovery mechanism, not
a long-term policy.

### Local tool authority

The channel message is processed with the current session's local tools and permission mode. Avoid
running an externally reachable channel with `--dangerously-skip-permissions`. Otherwise, a compromised
messaging account could become broad unattended access to the machine.

Permission relay should be enabled only when the channel can authenticate the remote approver.

### File exfiltration

The Telegram `reply` tool accepts absolute file paths. The inspected plugin refuses to send its own
channel state, such as `.env` and `access.json`, while allowing files from its attachment inbox. This is
useful defence in depth, but it does not prevent Claude from sending other readable local files.

Normal filesystem permissions and Claude Code tool approvals remain important.

### Credentials

Bot tokens are stored as plaintext secrets in the channel state directory:

```text
~/.claude/channels/telegram/.env
```

The official plugin enforces owner-only mode where the platform supports it. A shell environment
variable takes precedence over the `.env` value. Tokens should never be committed, logged, or included
in diagnostic output.

### Plugin trust

In preview, production channels must come from the Anthropic-maintained allowlist or an organization's
explicit `allowedChannelPlugins` list. The development flag should only be used for local code that has
been reviewed.

## Telegram setup reference

The minimal official setup is:

```text
/plugin install telegram@claude-plugins-official
/reload-plugins
/telegram:configure <BOT_TOKEN>
```

Restart the session:

```shell
claude --channels plugin:telegram@claude-plugins-official
```

Then:

```text
# Send any DM to the bot and copy the returned code
/telegram:access pair <code>
/telegram:access policy allowlist
```

Useful diagnostics:

```text
/telegram:configure
/telegram:access
/mcp
```

If the bot does not respond, check that:

- Bun is installed;
- the token is configured;
- the session was started with `--channels`;
- organization policy allows Channels;
- no other process is polling Telegram with the same bot token;
- the Telegram MCP server is connected in `/mcp`.
