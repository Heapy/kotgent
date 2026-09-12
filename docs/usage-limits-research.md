# Harness usage-limit findings

Verified on 2026-09-11 against installed Claude Code 2.1.268 and Codex CLI 0.154.0.
This document separates observed behavior, published contracts, and remaining manual checks. The
operator authorized bounded real-agent probes for this research; no kotgent daemon was started.

## Result that changes the implementation

Claude status-line invocations do **not** imply a new provider observation. Its payload contains
process-local quota readings without their fetch time. A later render from an idle session can therefore
be older than an earlier render from another session. Assigning a monotonic receipt timestamp cannot
make these readings causally ordered.

The operator selected **require same-session evidence**: a Claude reset needs a decrease relative to an
earlier reading from the same source session. A lower first reading from another session does not prove
a reset. Repeated cached readings must not replace another source's newer aggregate value or manufacture
new reset evidence. Source/session provenance and a durable source revision protect ingress; the displayed meter remains
an account-level projection. A heartbeat may refresh receipt freshness only while the aggregate values
still match; it may not revert values or provide reset evidence. After a reset, the detecting source's
new baseline belongs to the new reset generation. Other sources retain their prior generation until a
distinct catch-up sample; cached heartbeats never promote it. This prevents a source carrying the old
window from replaying the reset. Unknown sources or a resumed session with a new capture incarnation
need a baseline before they can prove a decrease.
This deliberately misses resets witnessed only by a new source. Render receipt freshness and provider
sample freshness must be described separately; a clock in the status line does not refresh the quota.

## 1. Claude status-line precedence

The official [settings precedence documentation](https://code.claude.com/docs/en/settings#settings-precedence)
states that a key supplied with `--settings <file-or-json>` overrides the same key in local, project,
and user settings. Managed policy remains higher priority. Consequently kotgent can provide its chained
`statusLine` through `--settings` in the ordinary unmanaged case.

[Status-line configuration](https://code.claude.com/docs/en/statusline#manually-configure-a-status-line)
explicitly permits project settings. Both `.claude/settings.json` and `.claude/settings.local.json`
participate in normal settings precedence. Chaining only the command in `~/.claude/settings.json` does
not preserve a different command selected from one of these project layers; project chaining remains
outside the agreed scope.

A restricted read confirmed the operator's user settings already contain a command-type status line.
A successful live startup check at **2026-09-11 18:12:57.581 UTC** loaded `--setting-sources user`
and supplied a different command through `--settings`. The temporary command received the status
payload and its unique `KOTGENT_CLI_STATUS_MARKER` appeared in the terminal. This directly verifies that
`--settings` wins over the operator's user command on installed Claude Code 2.1.268. No prompt was sent,
no model response was requested, and plugins and built-in tools were disabled. The process group was
terminated and reaped after the marker appeared. The operator's global settings were not modified.

Three earlier attempts using new temporary project directories could not complete the workspace trust
dialog through the probe PTY; they produced no status payload or model response and their children were
reaped. Project/local precedence is therefore verified from the published contract, rather than a
successful conflicting-marker experiment. The operator's command rendering through the final chained
script remains a separate acceptance check.

## 2. Claude account identity and quota shape

The published [status-line payload](https://code.claude.com/docs/en/statusline#available-data) and the
installed payload builder agree on these fields (illustrative numbers):

```json
{
  "session_id": "source-session-id",
  "rate_limits": {
    "five_hour": {"used_percentage": 23.5, "resets_at": 1738425600},
    "seven_day": {"used_percentage": 41.2, "resets_at": 1738857600}
  }
}
```

Claude uses `used_percentage`, not Codex's `used_percent`. `resets_at` is Unix epoch seconds; ingress
converts it to milliseconds. Each window is optional, and the entire object may be absent. A current
Claude gateway may additionally provide `spend_limit`; that is outside this feature's scope.

No account discriminator or rate-limit fetch timestamp appears in the inspected status payload.
`session_id` identifies the source, not the billed account. Internal account epochs are not exported.
Keep the agreed `(provider, window_key)` account projection and record the accepted multi-account risk:
separate Claude configurations may share the same key, and same-session evidence does not establish
that the operator never switches the account behind a session.

The successful live capture below also confirmed both native windows and absence of an account ID
or quota-fetch timestamp. Credentials and authentication tokens were not inspected.

## 3. Codex end-of-turn flush timing

A bounded live probe used one harmless no-tools prompt, a temporary working directory, ignored user
configuration, a read-only model sandbox, and a vetted invocation-specific `Stop` hook. The hook read
only the rollout's quota metadata. No existing conversations or credentials were emitted. The probe
process group was cleaned up, including child processes.

| Observation | UTC time on 2026-09-11 |
|---|---|
| Probe started | 18:05:53.998 |
| Latest `token_count` timestamp | 18:05:58.392 |
| `Stop` hook entered | 18:05:58.481 |
| CLI exited | 18:05:59.872 |

At hook entry the 46,951-byte rollout already contained the same last `token_count` as after process
exit. The record timestamp preceded hook entry by 89 ms. This proves visibility at hook arrival in
this invocation; it does not establish a worst-case flush bound or guarantee that every filesystem
schedule behaves identically. A bounded short retry remains defensive engineering, not a measured
provider timing guarantee. No polling loop is justified by this observation.

Capture reads the tail immediately after the committed Stop event, then once more after 250 ms, within
a five-second coroutine timeout. The second read is needed even when the first contains a quota record:
that record may belong to the previous turn. Absolute rollout byte position identifies record order;
the record timestamp is retained separately. Two records can share a millisecond timestamp, and scanning
an unchanged tail must not refresh its displayed observation time.

## 4. Codex quota and credits shape

The live probe's quota record used this shape (sanitized metadata; no conversation or identifiers):

```json
{
  "type": "event_msg",
  "payload": {
    "type": "token_count",
    "rate_limits": {
      "limit_id": "codex",
      "limit_name": null,
      "primary": {
        "used_percent": 58.0,
        "window_minutes": 10080,
        "resets_at": 1789435330
      },
      "secondary": null,
      "credits": {"has_credits": false, "unlimited": false, "balance": "0"},
      "individual_limit": null,
      "spend_control_reached": null,
      "plan_type": "pro",
      "rate_limit_reached_type": null
    }
  }
}
```

`credits` has no `used_percent` in the real payload and must not become a fabricated zero-percent
window. Omit it unless a future actual payload supplies a valid percentage. `secondary` can be null.
Crucially, this invocation's `primary.window_minutes` was **10080**, a week: primary is not inherently
five hours. Preserve provider keys, derive labels from available duration, and do not require both
windows to be present for a valid observation.

The official [Codex App Server documentation](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt)
confirms reset timestamps are Unix seconds and quota duration is expressed in minutes. Its app-server
wire contract uses camelCase (`usedPercent`, `windowDurationMins`, `resetsAt`); rollout JSONL uses the
snake_case fields observed above. Convert `resets_at` to milliseconds and `window_minutes` to seconds
at ingress. Do not parse app-server examples as though they were rollout records.

The same documentation describes multiple metered limit buckets. `limit_id` identifies a meter, not an
account. Treat support for additional buckets as a future extension rather than claiming every quota
record necessarily belongs to the default Codex bucket.

## 5. Claude cached renders and refresh behavior

Read-only inspection of the installed executable established the following path:

- Payload builder `BVo` reads `TF()`, which reads `kk.rawUtilization` from a process-local `CFn` instance.
- `d2o`/`xFn` filter invalid or expired windows. They do not reject a value merely because it was fetched
  an hour ago while its reset time is still in the future.
- `CFn` keeps `lastAppliedObservationAtMs` and an account epoch internally. `BVo` emits only utilization
  and reset time for each quota window; it does not forward this observation timestamp.
- The cache is updated from provider response headers, including quota-probe response/error paths.
  Running the status-line command does not itself request quota from the provider.
- Status controller `g9t` can execute the same payload builder after permission, Vim, model, effort, or
  thinking changes, a configured `refreshInterval`, or a window/cache expiration timer. The quota cache
  need not have changed first. Its inspected inputs do not include terminal geometry, so a simple resize
  is not itself evidence that the command executed again.

The published [status-line update triggers](https://code.claude.com/docs/en/statusline#how-status-lines-work)
corroborate that non-response events and optional timers invoke the command. API-duration or context
usage changes can help distinguish renders, but neither is a provider fetch timestamp or an ordering
proof between processes. Consequently an unchanged rendered payload cannot be promoted to new reset
evidence simply because its receipt time advanced.

The exact one-hour, two-session idle experiment from the original plan has not been run. Static
inspection is enough to reject its unsafe assumption of fresh data on every invocation. A successful bounded live capture tested ordinary render triggers in the already-trusted repository,
without loading user/project settings or tools; its outcome is recorded below. The chosen same-session
rule is a conservative scope decision, not a claim to have discovered a missing provider timestamp.

### Live Claude capture

The final probe used the already-trusted repository directory, invocation-specific temporary settings,
an empty `--setting-sources` list, no built-in tools, no MCP servers, a minimal system prompt, and one
request to reply `OK`. It displayed the CLI status marker. Because lower settings layers were disabled,
this proves CLI delivery and payload shape, not precedence over the operator's command. The controller
and its child process group were terminated and reaped after capture.

| Capture | UTC time on 2026-09-11 | Observation |
|---|---|---|
| Initial status invocation | 18:10:37.141 | No `rate_limits`; API duration zero |
| First response invocation | 18:10:38.863 | `five_hour` and `seven_day`, epoch-second resets |
| Permission-mode invocation | 18:10:45.942 | Identical quota and API-usage data, no new response |

There were two captured invocations before resizing and still two three seconds after resizing. A
permission-mode key change then produced the third invocation, repeating the earlier quota exactly.
Its `total_api_duration_ms` and `context_window.current_usage` were also unchanged. No account ID or
quota-fetch timestamp appeared in the captured root keys or either quota window. This directly proves
that a non-API event can emit cached quota and corroborates the static resize analysis. It does not
prove a one-hour cross-session schedule; that longer check remains unperformed. A runtime change to
`refreshInterval` in the invocation's settings file produced no further captured invocation during the
short probe, so timer behavior is established from documentation/code, not claimed as a live result.

### Installed-artifact anchors

Claude executable: `~/.local/share/claude/versions/2.1.268`, embedded build timestamp
`2026-09-10T17:07:29Z`, embedded commit `8d19e585f3f1e02a7e31642695321906d38609a3`.
SHA-256: `06a96d5423f83770f120859f1c58e60d7252cc4c122aa13043b7e7cd716bc76a`.
These byte offsets refer to this exact executable; minified symbol names are not stable APIs:

| Offset | Evidence |
|---|---|
| 167103224 | `CFn` cache, internal observation timestamp, and stale-response guard |
| 167107958 | Response-header ingestion updates cached window readings |
| 167114458 | `TF`, `d2o`, and reset-expiry filtering |
| 167362576 | `BVo` status payload construction |
| 167365353 | `g9t` status-line triggers and execution |
| 169981356 | Shared `Ia` hook/status fields, including session identity |

Codex version was read from installed `@openai/codex/package.json` and confirmed by the probe.
Installed artifacts were read as data; no credentials were opened and no provider endpoint was polled.

## Remaining boundaries

- The store also requires an account-level decrease: a source's decrease cannot reset an account meter
  that is already lower. A known source that skipped a reset generation must establish a current
  baseline with its next distinct sample; that catch-up may miss a genuinely different reset. New
  sources with stale caches remain ambiguous because no provider fetch timestamp exists.
- A reset first observed after the promised end is classified as on time, even if it actually happened
  earlier. No session activity means no new evidence. The displayed receipt time, including matching
  heartbeats, cannot establish the freshness of a Claude provider fetch.
- The generated status command captures the operator's user-scope command at session launch. Edits
  take effect at the next launch; project/local status commands are not chained. Capture state is scoped
  to the boot, Claude session, tmux server and pane, with a fresh incarnation if its counters restart.
  Monotonic render time orders workers and throttles heartbeats independently of wall-clock corrections.
  State older than 90 days and staging files older than one day are pruned during capture. New scripts
  share one permanent directory lock; legacy per-session lock inodes are retained because unlinking them
  can break exclusion for an old script still holding the inode.
- `observedAt` is a monotonic account/window merge revision. Actual daemon receipt time is stored
  separately as `receivedAt`; clock corrections must not suppress matching heartbeats or distort reset
  eligibility. Incoming capture timestamps more than a minute ahead are rejected, except an exact known
  Claude cached heartbeat matching the current meter. A persisted future evidence watermark is rebased
  after a clock correction, while source revisions continue to reject replay. Browser freshness uses
  server time and local monotonic elapsed time rather than comparing phone and daemon wall clocks.
- Reset journal and inbox durability do not imply push delivery. Pending inbox work is replayed after
  restart, and retries re-read it up to three times per signal. After runtime exhaustion, a local timer
  retries within a minute without waiting for another provider reset. Once acknowledged, the inbox row survives
  but a failed bind, crash or shutdown may lose the queued wake. Wakes are best effort and may coalesce;
  each fetch reads the complete inbox. Old pending resets outside the one-hour notification window are
  not replayed as banners. Usage history is retained for 90 days and inbox rows for one day, with pruning
  at startup and daily rather than at the exact expiry instant.
- Codex usage capture caches up to 128 known rollout paths and reads fresh tails on each turn. Cache
  misses use cooperative filename traversal; individual native filesystem calls remain non-preemptible
  even though the traversal checks cancellation and yields between entries.
- Acceptance combines executable script, ingress, SQLite, WebSocket, notifier and browser tests. There
  was no live long-turn observation through the final assembled feature. Codex weekly eligibility is
  tested through the real journal/inbox, while enabled-delivery tests exercise the shared wake path.
- Cursor and Junie were not researched through live quota probes here. The plan's existing scope says
  Cursor has no selected meter and Junie only reactive limit-failure text; do not present these as new
  verified provider guarantees.
- Codex app-server exposes quota APIs, but adopting it would change kotgent's current tmux-based harness
  integration. This feature deliberately consumes existing rollout output instead.
- Operator status-line visual equivalence and real-device push delivery remain manual acceptance checks.
  A successful generated-script test cannot establish either.

Deferred scope remains a Cursor provider adapter, Junie usage capture, a Prometheus endpoint over stored
samples, a reset-journal UI, a usage CLI/REST read, and chaining project-scope status commands. A per-session
quota column was superseded by the account projection; it is not a pending migration.
