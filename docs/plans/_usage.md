# Plan — Usage limits from each harness

**Scope (decided with user):** Claude + Codex fully structured; Cursor as a full 4th provider with best-effort limits; Junie researched & deferred. Plus a findings doc.

## TL;DR — what each harness actually exposes

| Harness | Windows | Machine-readable source | Best kotgent path | Verdict |
|---|---|---|---|---|
| **Claude Code** | `five_hour` + `seven_day` (Opus cap separate) | **Status line JSON** → `rate_limits.{five_hour,seven_day}.{used_percentage,resets_at(epoch)}` on every TUI render | New `statusLine` script in generated `--settings` → new ingress (like hook scripts). Optional `StopFailure` hook. | ✅ proactive, structured |
| **Codex CLI** | Server-driven `primary`/`secondary` (5h+weekly/monthly; `credits` on API key) | **Rollout JSONL `TokenCount` record** → `payload.rate_limits.{primary,secondary}.{used_percent,resets_at,window_minutes}` after each turn | Extend `CodexRolloutScan` with a `tokenCountOf` tail-scan; reuse `VendorStoreFs.readTail`. No new process. | ✅ proactive, structured |
| **Junie CLI** | JetBrains AI quota (weekly/monthly, unpublished) | None proactive. `/usage` is TUI-only session tokens. | `StopFailure` hook w/ `error` enum (`rate_limit`/`billing_error`) + free-text `error_details` ("reset at …"). **kotgent already receives this hook & discards the fields.** | ⏸ deferred (reactive only) |
| **Cursor CLI** | Monthly billing cycle + 5h fast-request window (tier multipliers) | ~none account-level. `/usage` (reset date) is TUI-only; `stream-json` may carry per-turn token totals (changelog↔schema conflict). | Best-effort: `stop(status="error")` hook + stderr; optional per-turn tokens from stream-json; no `resets_at`. | ⚠ best-effort only |

**The asymmetry IS the design:** a uniform `limits` feature is fully achievable for Claude + Codex only. Cursor integrates as a provider but its limit surface stays best-effort, honestly labeled.

---

## Part A — Findings doc
Write `docs/usage-limits-research.md`: per-harness window names, payload shapes, `resets_at` encodings (epoch sec vs ISO vs free text), cited URLs, honest gaps. Source material already gathered from the four research passes — no new field work needed to write it.

## Part B — Core data model (shared: Claude + Codex + Cursor)
Mirror the `model`/`archived` "discovered per-session telemetry" precedent end-to-end. **Do NOT touch the closed `AgentEvent` vocab or the reducer** — limits are observability metadata, like `model`.

**B1. New column `token_limits TEXT` (JSON blob, not per-field columns — survives provider schema drift) on `sessions`.**
- `Sessions.sq`: add to create block + `upsert`; leave OUT of `updateCache`/`updateControlState`; add `setTokenLimits:` and `setTokenLimitsForProvider:` (templates: `setModel:` :108, `setModelForProvider:` :119).
- `SqliteEventStore.kt`: runtime `if (!driver.hasColumn("sessions","token_limits")) { ALTER TABLE … }` (template: `archived` guard :142). Update `toMeta()` :445 + `upsertSession` :156.

**B2. `SessionMeta`** — add `val tokenLimits: String? = null` after `model` (SessionMeta.kt:27).

**B3. `src/core/TokenLimits.kt` (new):**
```kotlin
@Serializable data class TokenLimits(
  val windows: List<Window> = emptyList(),   // empty = unknown/cleared
  val source: String,                         // "claude-statusline"|"codex-rollout"|"cursor-streamjson"|"junie-stopfailure"
  val capturedAt: Long                        // epoch millis
) {
  @Serializable data class Window(
    val label: String,                        // "five_hour"/"seven_day"/"primary"/"monthly" — provider-native
    val usedPercent: Double? = null,          // 0.0–100.0
    val resetsAt: Long? = null,               // epoch SECONDS
    val windowSeconds: Long? = null,          // Codex window_minutes → seconds
    val note: String? = null                  // free-text (reactive providers' parsed "reset at …")
  )
}
```
`encodeDefaults = true` so a cleared/empty `windows` round-trips (the `model`-clear invariant).

**B4. `EventStore` + `SqliteEventStore`** — `setTokenLimits(id, limits)` + `setTokenLimitsForProvider(id, providerId, limits): Boolean` (templates `setModel`, `setModelForProvider`). Limits are derived metadata: both SQL updates advance `rev` but leave `updated_at` untouched. The `ForProvider` conditional write is the rebind-race guard.

**B5. DTO/WS:** `SessionDto` add `tokenLimits: TokenLimits? = null` (after `model` :395), map in `SessionMeta.toDto()`. `SessionUpdateDto` add same field after `model` (:164), populate **only** in snapshot `toUpdateDto()` (null in live `SessionUpdate.toDto()`) — **same `snapshot`-discriminated rule as `model`**; document it.

**B6. UI/CLI:** `sessionSubline(s)` in `resources/webui/lib/sessions.js` adds a compact hint (e.g. `agent · model · 5h 23% · wk 41%`, percent-only, reset time in `title=`). `Sidebar.js` consumes the subline. CLI: prefer a `kotgent session <id>` detail view (already backed by `GET /sessions/{id}`) over cluttering the list columns. Register any new JS module in `WebUiServingTest`.

## Part C — Claude (proactive, structured)
**C1. Status line as data pump (primary).** Claude feeds a JSON object to a `statusLine` script on stdin, incl. `rate_limits.{five_hour,seven_day}.{used_percentage,resets_at}` (epoch sec). Implement by mirroring the hook-script pattern:
1. `ClaudeHookConfig.generate(...)` root builder (:109) — add sibling `put("statusLine", {type:"command", command: statusLineCommand(...)})` next to `putJsonObject("hooks")` (documented top-level key).
2. Status-line command: `/bin/sh '<script>'` doing `curl --max-time 5 -sS -o /dev/null -X POST '<ingress>' -H '@<headerFile>' -H 'X-Kotgent-Source: statusline' --data-binary @-`. Reuse the `0600` header file (token off argv). **Writes nothing to stdout** (Claude renders stdout as the status line — stay invisible).
3. New `Route.claudeStatusLineRoutes(...)` top-level + `loopbackOnly` (like hook ingresses): read stdin JSON, resolve pane via payload `session_id` (fallback `$TMUX_PANE`), parse `rate_limits` → `TokenLimits(source="claude-statusline")` → `store.setTokenLimits`.
4. New `src/transport/ClaudeLimitsCapture.kt` mirroring `ClaudeModelCapture` (:36).

**C2. Optional `StopFailure` hook (event signal):** matchers `rate_limit`/`overloaded`/`billing_error`. Add to `ClaudeHookConfig.HOOK_EVENTS` + a side-channel branch (still maps to `TurnCompleted` — don't expand the event vocab) that bumps a `Window.note`. Field-probe the StopFailure payload first.

**C3. No rebind work for Claude** (it preallocates `--session-id`). Field-checks (C4): confirm status-line `rate_limits` field names + pane correlation for a tmux-detached pane + capture one real `StopFailure` payload.

## Part D — Codex (proactive, structured)
**D1. Rollout `TokenCount` tail-scan (primary).** The rollout JSONL carries `{type:"token_count", payload:{ info, rate_limits:{ primary:{used_percent,window_minutes,resets_at}, secondary:{…}, credits:{…?} }}}` after turns. `resets_at`=epoch sec. No new process — kotgent already scans this rollout for `model`.
1. `CodexRolloutScan.kt` — add `tokenCountOf(providerSessionId): String?` mirroring `modelOf` (:150) but reading the **tail** via `VendorStoreFs.readTail(path, TOKENCOUNT_TAIL_BYTES=256KB)` (`TokenCount` sits near end; the current 256KB head window can miss it on long sessions).
2. New `src/adapter/codex/CodexLimitsScan.kt` (mirrors `ModelScan.kt`): pure `extractTokenLimits(text): TokenLimits?` — finds the **last** `rate_limits` object, maps fields; handle `credits` (API-key) shape as a `Window(label="credits", note=…)` so the UI doesn't infer a missing meter.
3. `captureCodexLimitsOnce(store, scan, meta)` mirroring `captureCodexModelOnce`; writes via `setTokenLimitsForProvider` (conditional — survives rebind without changing activity ordering).
4. Poll in `Commands.kt` `captureModelInBackground` lambda (:442) alongside the codex model poll, own `LIMITS_CAPTURE_ATTEMPTS`/`INTERVAL`.
5. Field-check: confirm the last `TokenCount` is reachable in 256KB tail on a real rollout.

**D2. NO app-server client** — `account/rateLimits/read` is richest but breaks the tmux model. Rollout scan is the tmux-compatible equivalent. Record trade-off in findings.

**D3. No hook work for Codex** — its 6 wired hooks carry no usage; limits hit as an in-stream error item.

**D4. Rebind correction** — extend `SessionManager.onProviderIdRebound` to also `store.setTokenLimits(id, null)` (the neighbour's `TokenCount` would persist a wrong meter). The conditional `setTokenLimitsForProvider` (D1.3) is the second guarantee — the exact two-guarantee pattern proven for `model`.

## Part E — Cursor as 4th provider + best-effort limits
Mirror the Junie commit (`ed94fbde`) structure-for-structure.

**E1. Cursor provider files (new), mirror `src/adapter/junie/` + `JunieSessionScan.kt`:**
- `CursorAdapter.kt` — `agent [--resume <chatId>] --plugin-dir <hookPluginDir> --workspace <cwd> --output-format stream-json` (TUI in tmux). `preallocatedSessionId=null`.
- `CursorCli.kt` — locate `agent`, parse `--version`.
- `CursorHookConfig.kt` — generate the **plugin dir** (`--plugin-dir` = Cursor's per-launch hook isolation, per `cursor-cli-research.md`): `.cursor-plugin/plugin.json` + `hooks/hooks.json`. Events: `sessionStart`/`sessionEnd`/`beforeSubmitPrompt`/`preToolUse`/`postToolUse`/`stop`/`afterAgentResponse`.
- `CursorHookNormalizer.kt` — `beforeSubmitPrompt`→`TurnStarted`; `postToolUse`→`ToolCall`; `stop(completed)`→`TurnCompleted`; `stop(error)`→`TurnCompleted` **+ side-channel** (E3); `sessionStart`→`SessionBound` if id present; `sessionEnd`→`Exited(0)`. Vocab untouched.
- `CursorSessionScan.kt` — `cursorVendorStoreProbe`, `cursorSessionLocator`, `discoverSessionId`, `captureCursorModelOnce`. **Transcript layout undocumented** (field-check) — probe `~/.cursor` + `CURSOR_TRANSCRIPT_PATH` from hook payload.

**E2. Wire 4th provider (3 dispatch sites + factory map):** `CURSOR_AGENT_KIND="cursor"` (SessionManager.kt:74); add `cursorDir` param + entry in `productionVendorStoreProbe` (Reconciler.kt:48) and `productionSessionLocator` (VendorSessionLocator.kt:58); `CursorAdapter` in `agentBuilders` (Commands.kt ~397); `writeCursorHookPlugin`; id-discovery + model-poll branches; `Route.cursorHookRoutes` (HookRoutes.kt:139 pattern) + mount in Server.kt:162; `defaultCursorDir()` (env `CURSOR_CONFIG_DIR`→`~/.cursor`) in VendorStoreFs.kt.

**E3. Cursor best-effort limits (honest, labeled):** no machine-readable meter exists. (1) `stop(status="error")` hook → side-channel `TokenLimits(source="cursor-stop", windows=[], note="last turn errored — possibly rate-limited")` — reactive, no percent/reset. (2) Per-turn tokens from `stream-json` if field-check confirms (else skip). (3) `/usage` TUI scrape — **NOT in plan** (brittle). Cursor's `token_limits` is mostly null → correct & honest; do NOT fabricate a meter.

**E4. Field-checks:** plugin-dir hook layout fires per-launch; transcript root via `CURSOR_TRANSCRIPT_PATH`; whether `stream-json` per-turn token totals actually appear.

## Part F — Tests (keep `./kotlin build` + `./kotlin test` green; the recorded "765 native + 7 JVM" baseline predates the browser tier — re-measure before starting)
Pure logic in core, I/O behind existing seams: `TokenLimitsTest` (round-trip incl. empty/clear); `CodexLimitsScanTest` (primary/secondary/credits/missing/last-match); `ClaudeLimitsCaptureTest` (status-line parse); migration test (mirror `archived`); `EventStore` test (setters incl. conditional-write-false-on-rebind); DTO round-trip (snapshot vs live null); Cursor provider set (mirror `test/adapter/junie/` + `JunieSessionScanTest` + extend `ImportWiringTest`); `HookRoutesTest` cursor case; `WebUiServingTest` register new JS (registry only — it is no longer a place for UI claims); any UI behaviour the per-session limits field adds goes in `webuitest/` as a browser test against `webuicheck`; `node --check` changed webui; all IO tests `withTimeout`. Field-check probes (C4/D1.5/E4) are manual verification, recorded in findings, gating which reactive paths ship.

## Part G — Out of scope (recorded, not done)
- **Junie** — only reactive `StopFailure`; `JunieHookNormalizer` already receives & discards `error`/`error_details`. Future plan: parse enum, regex `error_details` for "reset at …", set `TokenLimits(source="junie-stopfailure")`. Deferred.
- **Codex app-server** (richest, breaks tmux) — D2. **Cursor `/usage` scrape** — E3.3. **New `AgentEvent`/reducer state** — rejected (ride the `model`-style side-channel). **Per-provider global meter panel** — user chose per-session field.

## Execution order
1. Findings doc → 2. Core data model (Part B, no provider yet) → 3. Codex (Part D, lowest risk — reuses existing `CodexRolloutScan`) → 4. Claude (Part C) → 5. Cursor provider (E1/E2 stubbed, then E3) → 6. UI/CLI surface (B6). Each part green independently + reviewable commit.

---
**Note:** I could not write the plan doc to disk (plan mode is read-only). On approval I'll create `docs/plans/20260731-usage-limits.md` + `docs/usage-limits-research.md` and begin execution per the order above.
