# Codex adapter (второй провайдер)

## Overview

Добавить второй провайдер — **Codex** — как зеркало Claude-адаптера: TUI в tmux + hooks, нормализация в те
же 7 `AgentEvent`. Терминальный fan-out, event-store, редьюсер, reconciler и транспорт не меняются.
Codex `app-server` в этот срез НЕ входит (остаётся в бэклоге под structured chat UI / мобильный клиент).

## Context (discovery, 2026-07-23, codex-cli 0.145.0)

Проверено на живой установке; факты, на которых стоит дизайн:

- **У Codex есть hooks**, формат файла совпадает с Claude Code:
  `{"hooks": {"<Event>": [{"matcher"?, "hooks": [{"type":"command","command":"…"}]}]}}`.
  События: `PreToolUse`, `PermissionRequest`, `PostToolUse`, `PreCompact`, `PostCompact`, `SessionStart`,
  `SessionEnd`, `UserPromptSubmit`, `SubagentStart`, `SubagentStop`, `Stop`.
- **Доставка хуков — только session-layer.** Проверено через `codex app-server` + `hooks/list`:
  - `-c 'hooks={SessionStart=[{hooks=[{type="command",command="…"}]}]}'` → `source: sessionFlags` ✅
    (действует только на этот запуск, `~/.codex` не трогается);
  - `$CODEX_HOME/hooks.json` и `[hooks]` в `config.toml` → `source: user` — глобально для ВСЕХ сессий
    пользователя, поэтому отвергнуто;
  - `--profile` к `app-server` неприменим (но применим к TUI) — проверить как опцию не удалось, и
    profile-файл всё равно живёт в `$CODEX_HOME`, т.е. это тоже вмешательство в среду пользователя.
- **Хуки стартуют как `untrusted`** (`trustStatus`), поэтому launch несёт `-c bypass_hook_trust=true`.
- **Нет преаллокации session-id** (аналога `claude --session-id` нет). Resume: `codex resume <SESSION_ID>`.
  Id придёт из `SessionStart`-хука ИЛИ из rollout-файла — см. Task 5.
- **rollout JSONL**: `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<session_id>.jsonl`, первая строка —
  `session_meta` с `session_id` и `cwd`. Архив — `~/.codex/archived_sessions/`.
  Approval-события в rollout НЕ пишутся (проверено на 40 свежих сессиях) — как источник состояния не годится.
- `codex --version` печатает `codex-cli 0.145.0` (префикс `codex-cli `, не как у claude).

**Не подтверждено, требует живой приёмки:** срабатывает ли `SessionStart` в TUI (через `app-server
thread/start` хук не выстрелил) и точные имена полей payload. Поэтому provider-id capture опирается на
rollout-скан (Task 5) как на основной путь, а `SessionStart` — как на дополнительный.

## Solution Overview

```
codex TUI (tmux)  ──hooks (curl)──►  POST /hooks/codex  ──CodexHookNormalizer──►  AgentEvent  ──► EventStore
       ▲                                                                                            │
       └── LaunchSpec: codex -c hooks={…} -c bypass_hook_trust=true                     reduce/replay ▼
                                                                                              Projection
```

Маппинг событий (аналогично Claude, но точнее — есть настоящий approval):

| Codex hook | AgentEvent | состояние |
|---|---|---|
| `UserPromptSubmit` | `TurnStarted` | running |
| `PostToolUse` | `ToolCall(tool_name)` | running (сбрасывает pendingApprovals) |
| `PermissionRequest` | `ApprovalRequested(id)` | needs_approval |
| `Stop` | `TurnCompleted` | ready |
| `SessionStart` | `SessionBound(session_id)` | — |
| `SessionEnd` | `Exited` | stopped/crashed |

## Technical Details

- **Hook-команда — отдельный скрипт**, не inline-curl: `/bin/sh ~/.kotgent/codex-hook.sh <Event>` (0600).
  Причина: inline-curl внутри TOML внутри `tmux new-session '<sh>'` даёт три уровня квотинга; скрипт
  оставляет в argv короткую строку без вложенных кавычек. Токен — как у Claude, из `0600` header-файла
  через `curl -H @<file>`, `$TMUX_PANE` раскрывается в момент вызова хука.
- **Ингресс `/hooks/codex`** — тот же каркас, что `/hooks/claude` (токен → event → pane → normalize →
  append), вынесен в общую функцию с параметрами (путь, заголовки, нормализатор).
- **`VendorStoreProbe` получает `agent`** — иначе для codex-сессии reconciler применит claude-путь.
- **Тесты**: всё pure — генерация TOML/скрипта, нормализатор, парс версии, скан rollout — юнит-тестируется
  без живого codex; интеграция с реальным `codex` проверяется вручную (см. Acceptance).

## Implementation Steps

### Task 1: `CodexCli` — locate/version
`src/adapter/codex/CodexCli.kt` + тест. Парс `codex-cli 0.145.0`; `locate()` как у `ClaudeCli`.
Версионного гейта фич нет (нечего гейтить), но версия нужна для `sessions.cli_version`.

### Task 2: `CodexHookConfig` — hook-скрипт + TOML для `-c`
`src/adapter/codex/CodexHookConfig.kt` + тест. Чистая генерация:
- `hookScript(port, headerFilePath)` — текст `codex-hook.sh` (POST stdin на ингресс, событие из `$1`).
  Вызывается как `/bin/sh '<script>' <Event>`, поэтому файл пишется `0600` без бита исполнения;
- `hooksToml(scriptPath)` — inline-TOML `hooks={…}` для всех 6 событий (`PostToolUse` с `matcher="*"`);
- константы событий, `INGRESS_PATH = /hooks/codex`, заголовки (общие с Claude по смыслу, свои значения).

### Task 3: `CodexHookNormalizer`
`src/adapter/codex/CodexHookNormalizer.kt` + тест. Маппинг из таблицы выше; неизвестное событие → `null`;
`SessionStart` без валидного UUID → `null`; `PermissionRequest` → `ApprovalRequested` с id из payload.

### Task 4: `CodexAdapter`
`src/adapter/codex/CodexAdapter.kt` + тест. `buildLaunchSpec`:
- `New` → `codex -c hooks={…} -c bypass_hook_trust=true`, `preallocatedSessionId = null`;
- `Resume(id)` → `codex resume <id> -c … -c …`.

### Task 5: provider-id capture из rollout
`src/daemon/CodexRolloutScan.kt` + тест. Скан `~/.codex/sessions/<YYYY>/<MM>/<DD>/` на POSIX
`opendir/readdir`; из имени файла достаётся `session_id`, из первой строки — `cwd`. Две функции:
- `discoverSessionId(cwd, startedAfter)` — для `ProviderIdCapture.captureInBackground`;
- `hasRollout(providerSessionId)` — для пробы resumable.

`archived_sessions/` НЕ сканируется: архивация выводит сессию из-под `codex resume`, поэтому считать её
resumable значило бы предлагать оживление, которое упадёт.

### Task 6: agent-aware `VendorStoreProbe`
Расширить `VendorStoreProbe.hasTranscript(agent, cwd, providerSessionId)`; `claudeVendorStoreProbe` и новый
`codexVendorStoreProbe` + композит `byAgentVendorStoreProbe(map)`. Обновить `Reconciler` и его тесты.

### Task 7: мультипровайдерная `AgentFactory`
Заменить `claudeOnlyAgentFactory` на `agentFactoryOf(map: Map<String, (cwd) -> AgentAdapter>)`;
`CODEX_AGENT_KIND = "codex"`; текст `UnsupportedAgentException` перечисляет поддерживаемые виды.

### Task 8: ингресс `/hooks/codex`
Обобщить `HookRoutes.kt`: общий `hookRoutes(...)`, поверх него `claudeHookRoutes` и `codexHookRoutes`.
Смонтировать в `Server.kt`. Тесты — зеркало `HookRoutesTest`.

### Task 9: wiring в `Commands.daemon`
Писать `codex-hook.sh` (0600) и свой header-файл, строить `CodexAdapter`, регистрировать оба вида в
фабрике, отдать reconciler-у композитный probe, повесить rollout-скан на fallback-путь id-capture.

### Task 10: документация
`README.md` (статус: два провайдера), `CLAUDE.md` (раздел про Codex-специфику), `docs/plans/` — этот файл.

## Acceptance (ручная, на живой сессии)

1. `kotgent start codex` → сессия поднимается, `list` показывает `codex`.
2. Хуки долетают: после первого промпта состояние `running`, после ответа `ready`.
3. `PermissionRequest` → `needs_approval` (запросить у агента команду вне sandbox).
4. `provider_session_id` заполняется (через `SessionStart` или rollout-скан) → `resume` разблокирован.
5. Убийство пары `kill` → reconciler классифицирует `resumable` (rollout на месте), `resume` оживляет
   разговор через `codex resume <id>`.
6. Терминальный fan-out (IDE + браузер) работает так же, как для Claude.

## Progress Tracking

- [x] Task 1 — CodexCli
- [x] Task 2 — CodexHookConfig
- [x] Task 3 — CodexHookNormalizer
- [x] Task 4 — CodexAdapter
- [x] Task 5 — rollout-скан
- [x] Task 6 — agent-aware VendorStoreProbe
- [x] Task 7 — мультипровайдерная фабрика
- [x] Task 8 — ингресс /hooks/codex
- [x] Task 9 — wiring
- [x] Task 10 — документация
