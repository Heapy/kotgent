# Kotgent — First Vertical Slice (Claude + local-only)

## Overview

Kotgent — local-first диспетчер агентских сессий: процессы агентов живут в `tmux` независимо от интерфейса, а IDE, браузер и (позже) телефон — взаимозаменяемые клиенты. Ценность — **restart-safe control plane** над coding-агентами.

Этот план реализует **первый вертикальный срез**, доказывающий ценность:

> `kotgent start` запускает **Claude** → закрываем IDEA (Detach — отваливается WS-подписчик fan-out; единственный upstream `tmux attach` daemon'а живёт) → открываем браузер → продолжаем ту же сессию → видим, что она требует внимания (`needs attention`).

Всё на **local-only** (`127.0.0.1` + токен). Codex, PWA, cloudflared-туннель, Web Push, diff viewer — в бэклоге.

Сборка — **JetBrains Kotlin Toolchain** (декларативный `module.yaml`, CLI `./kotlin`), НЕ Gradle. Архитектура (6 слоёв, event-sourcing спина, adapter-шов) заимствует паттерны из `umputun/agterm` и JetBrains `agent-workbench`.

## Context (from discovery)

- **Проект:** Kotlin/Native, единый нативный бинарь (macOS arm64), собирается **Kotlin Toolchain**. Scaffold уже инициализирован пользователем (`./kotlin init`): `module.yaml` (`product: macos/app` / `macosArm64`, Kotlin 2.4.10, `entryPoint: io.kotgent.main`), `src/main.kt` (`versionLine()`+`main()`), `test/SmokeTest.kt`, `.gitignore`, `./kotlin` wrapper. Git инициализирован; план закоммичен.
- **idea.md устарел:** `pty4j` (JVM — идём в POSIX cinterop) и «свой relay» (заменён на cloudflared, бэклог).
- **Layout `amper`:** исходники в `src/`, тесты в `test/` (НЕ `src/nativeMain/kotlin/…`). Пакеты `io.kotgent.*`; директории под пакеты не обязаны совпадать, но организуем по областям (`src/core/…` = `package io.kotgent.core`).
- **cinterop:** `.def` кладутся в `cinterop/` **без YAML** (Kotlin Toolchain сам их подхватывает).
- **Хранилище:** SQLDelight — Gradle-плагин, на Toolchain недоступен напрямую → обходим **своим `jvm/amper-plugin`**, вызывающим компилятор SQLDelight для codegen (спайк Task 4, фолбэк JSONL).
- **Тулчейн подтверждён:** JDK 25, Xcode, tmux 3.7b, claude 2.1.217, codex, Maven Central доступен.

## Development Approach

- **Testing approach: TDD.** host-free ядро (домен, редьюсер, event store поверх интерфейса, нормализация адаптера, генерация launch-спеки/plist) — полноценный TDD. Края (cinterop/PTY, tmux, Ktor-WS, tty-raw, custom-plugin codegen) — сначала интеграционный/smoke-тест контракта, затем реализация.
- **CRITICAL:** каждая задача включает новые/обновлённые тесты (отдельными пунктами, success + error), все зелёные перед следующей задачей; обновлять этот файл при изменении scope.
- Команды: тесты `./kotlin test`; сборка `./kotlin build`; запуск `./kotlin run`.

## Testing Strategy

- **unit (host-free, каждую задачу):** редьюсер (переходы v1, правило «вход в running сбрасывает pendingApprovals», interrupt-reset, replay-детерминизм), сериализация событий, генерация команд/конфигов/plist, EventStore-контракт.
- **интеграционные (края):** PTY round-trip (`openpty`+`posix_spawn`); Ktor WS echo (native); SQLDelight-plugin codegen round-trip; tmux против реального `tmux -L kotgent-test` (skip-guard); PTY fan-out; transport endpoints.
- **e2e/manual:** браузерный сквозной проход — v1 без e2e-фреймворка (Playwright бэклог), проверяется вручную (Task 18). Юнит-тестируем тестируемое в Web UI.
- Команды: `./kotlin test`, `./kotlin build`.

## Progress Tracking

- `[x]` сразу по завершении; новые задачи — `➕`; блокеры — `⚠️`; синхронизировать с фактом.

## Solution Overview

**Модули / layout:**
- Корневой модуль (`macos/app`): `src/` (пакеты `io.kotgent.*`), `test/`, `cinterop/` (`.def`), `sqldelight/` (`.sq`), `resources/webui/` (SPA).
- `plugins/sqldelight-gen/` (`jvm/amper-plugin`): codegen SQLDelight. `project.yaml` перечисляет модуль + плагин; корневой `module.yaml` — `plugins: { sqldelight-gen: enabled }`.

**Packages (host-free ↔ края):**
- `core/` — **host-free**: `AgentEvent`, `SessionState`, `SessionMeta`, `Reducer` (лог → проекция). Без IO, максимум тестов.
- `store/` — `EventStore` интерфейс + реализация: **SQLDelight** (через плагин + `native-driver`) ИЛИ фолбэк JSONL. Интерфейс изолирует downstream от выбора.
- `pty/` — PTY-примитив (`openpty`+`posix_spawn`) + fan-out. **Lazy lifecycle:** upstream `tmux attach` поднимается при первом подписчике, гаснет при последнем (Claude живёт в tmux независимо; даёт Detach И снимает respawn после рестарта daemon).
- `tmux/` — обёртка над `tmux -L kotgent`.
- `adapter/` — `AgentAdapter` контракт (launch/resume + `events: Flow<AgentEvent>`) + `ClaudeAdapter` + нормализация. Capability-интерфейсы — бэклог.
- `daemon/` — session manager, reconciliation, provider-id capture, StopMode.
- `transport/` — Ktor CIO: control REST, events WS, terminal WS, токен-auth, hook ingress, статика Web UI.
- `cli/` — субкоманды + `attach` raw-passthrough.
- `launchd/` — генерация plist + install.

**Event-sourcing:** состояние — проекция чистого редьюсера над append-only логом `events`. Адаптер лишь нормализует свои сигналы в канонический `AgentEvent`; редьюсер/состояния неизменны. Restart-safety = `replay`.

**Risk-first порядок:** первыми — спайки самых хрупких мест на Kotlin Toolchain/K/N: PTY-cinterop (Task 2), Ktor-CIO-WS (Task 3), SQLDelight-via-custom-plugin (Task 4). Проваленный спайк узнаём сразу; storage-спайк имеет фолбэк.

## Technical Details

- **Сборка:** `module.yaml` — `settings.ktor: enabled` + deps `io.ktor:ktor-server-cio`/`ktor-server-websockets` (+ `ktor-client-cio` в `test-dependencies`); `settings.kotlin.serialization: json`; dep `org.jetbrains.kotlinx:kotlinx-coroutines-core`; CLI-либа (`kotlinx-cli` или clikt native). Рантайм-БД: dep `app.cash.sqldelight:native-driver`.
- **Идентичность сессии:** логический ключ — имя tmux-сессии `kt-<shortid>`; рантайм-корреляция — `pane_id` (`new-session -P -F '#{pane_id}'`, пересбор `list-panes` на старте daemon). Хуки шлют `$TMUX_PANE`. `KOTGENT_SESSION_ID` — только debug-лейбл (env-poisoning не доверяем).
- **Состояния (7):** живые `running / needs_approval / needs_answer / ready`; мёртвые `stopped / crashed / resumable`. `needs_answer` в Claude-срезе **forward-modeled** (интерактивный Claude не даёт «задал вопрос и ждёт»).
- **`AgentEvent` (v1):** `TurnStarted`, `TurnCompleted`, `ApprovalRequested`, `ApprovalResolved`, `ToolCall`, `Exited(code)`, `SessionBound(providerSessionId)`. `Question*` — бэклог.
- **Claude hook-маппинг:** `UserPromptSubmit`/`PostToolUse`→running, `Stop`→ready, `Notification`→needs_approval, `SessionStart`→`SessionBound`. ⚠️ Реальные `Notification`-пейлоады НЕ подтверждены → спайк (Task 11) + грубый любой `Notification`→needs_attention. Нет «permission answered» → **вход в running сбрасывает `pendingApprovals=0`** (Task 6).
- **Ввод (срез):** только `TerminalInput(bytes)` (браузер аппрувит через terminal-passthrough). `UserMessage`/`ApprovalResolved` — backlog-seam.
- **Остановка:** `StopMode { Detach, Interrupt, Graceful, Terminate, Kill }` (`Detach`≠`Kill`; `Interrupt`=сброс залипшего running).
- **Схема БД:** `events(session_id, seq, ts, type, source, payload)` PK `(session_id,seq)`; `sessions(id, name, tags, agent, provider_session_id, model, cli_version, cli_path, cwd, repository, worktree, branch, tmux_session, pane_id, state, state_source, last_seq, read_cursor, created_at, updated_at)`. Через `.sq` (SQLDelight) либо эквивалент на JSONL (фолбэк).
- **Хранилище:** SQLDelight через `jvm/amper-plugin` (codegen) + `native-driver` (single-writer, WAL, append+кэш в одной транзакции). Фолбэк: JSONL append-only лог на сессию + read-model в памяти (перестраивается replay'ем; «needs attention» = фильтр в памяти). `EventStore.{append,read,subscribe}` изолирует выбор.
- **Provider-id capture:** preallocate UUID → `claude --session-id <uuid>` (version-gated; fallback `SessionStart`-хук) → `SessionBound`. Гарантия: буксует → retry + «id pending» (resume заблокирован), не терять молча.
- **Transport:** `127.0.0.1:PORT` (Ktor CIO), control REST + events WS (`?from=<seq>`, restart-safe курсор) + terminal WS (binary fan-out, `capture-pane -e` сид + resize). Токен `~/.kotgent/token` (0600); Web UI — URL-фрагмент `#token=`; один токен на всё.
- **`kotgent attach`** = raw-passthrough на terminal-WS (tty raw через `termios`, stdin→WS, WS→stdout, `SIGWINCH`→resize), НЕ прямой `tmux attach`.

## What Goes Where

- **Implementation Steps** (`[ ]`): код, тесты, `.sq`, плагин, `module.yaml`/`project.yaml`, статика.
- **Post-Completion** (без чекбоксов): ручной браузерный проход, launchd на реальной машине, Playwright (бэклог), проверка версий `claude`/`tmux`.

## Implementation Steps

### Task 1: Принять Kotlin Toolchain scaffold + базовые зависимости

**Files:**
- Modify: `module.yaml`
- Verify: `src/main.kt`, `test/SmokeTest.kt`, `.gitignore`, `./kotlin`

- [x] проверить существующий scaffold: `./kotlin build` и `./kotlin test` зелёные (macosArm64)
- [x] в `module.yaml` включить базовые зависимости, нужные далее: `settings.kotlin.serialization: json`, dep `kotlinx-coroutines-core` (пин версий, совместимых с Kotlin 2.4.10)
- [x] убедиться, что `SmokeTest` проходит после добавления зависимостей (`./kotlin test`)
- [x] закоммитить scaffold + правки (`stage-and-commit.sh`)
- [x] (Ktor/SQLDelight-зависимости добавляются в своих спайках Task 3/4)

### Task 2: cinterop-спайк — PTY-примитив через openpty + posix_spawn (РИСК)

**Files:**
- Create: `cinterop/pty.def`
- Create: `src/pty/Pty.kt`
- Create: `test/pty/PtyTest.kt`

- [ ] write интеграционный тест (bounded read + timeout — анти-флаки): открыть PTY, заспавнить `/bin/cat`, записать в мастер, прочитать эхо, задать winsize — ASSERT round-trip
- [ ] `cinterop/pty.def` (без YAML) с `#include <util.h> <sys/ioctl.h> <termios.h> <spawn.h>`, экспорт `openpty`/`ioctl`/`TIOCSWINSZ`/`winsize`/`posix_spawn`/`posix_spawnattr_*`/`POSIX_SPAWN_SETSID`
- [ ] `Pty.kt`: `open(...)` через **`openpty`+`posix_spawn(POSIX_SPAWN_SETSID)`** (НЕ `forkpty` — fork-без-exec небезопасен для рантайма K/N; C-строки маршалятся ДО спавна); мастер-fd в родителе; `read()`/`write()`/`resize()`(`TIOCSWINSZ`)/`close()`
- [ ] модель чтения: **выделенный reader-thread** (`newSingleThreadContext`/Worker) с блокирующим `read()` → `Channel` (на native нет `Dispatchers.IO`)
- [ ] write тесты: exit-код, resize, ошибка спавна несуществующей команды
- [ ] run `./kotlin test` — PTY round-trip зелёный перед Task 3

### Task 3: Ktor CIO HTTP+WS-спайк на native (РИСК)

**Files:**
- Modify: `module.yaml`
- Create: `src/transport/SpikeServer.kt`
- Create: `test/transport/WsSpikeTest.kt`

- [ ] write интеграционный тест: поднять сервер на `127.0.0.1:0`, Ktor-client'ом HTTP GET round-trip + WS echo (текст и **binary**-фрейм) — ASSERT
- [ ] `module.yaml`: `settings.ktor: enabled` + deps `ktor-server-cio`, `ktor-server-websockets`; `test-dependencies`: `ktor-client-cio` (+ websockets)
- [ ] `SpikeServer.kt`: `embeddedServer(CIO)` с HTTP-роутом, WS-echo, отдачей статического файла
- [ ] ⚠️ если WS-плагин CIO на native не покрывает нужное — зафиксировать блокер и эскалировать (меняет транспортное решение)
- [ ] write тест: бинарный WS-фрейм round-trip (нужен для terminal-канала)
- [ ] run `./kotlin test` — WS зелёный перед Task 4

### Task 4: SQLDelight через свой Kotlin Toolchain-плагин — спайк (РИСК, фолбэк JSONL)

**Files:**
- Create: `project.yaml`
- Create: `plugins/sqldelight-gen/module.yaml`, `plugins/sqldelight-gen/plugin.yaml`, `plugins/sqldelight-gen/src/Generate.kt`
- Create: `sqldelight/io/kotgent/db/Spike.sq`
- Modify: `module.yaml`
- Create: `test/store/SqlDelightSpikeTest.kt`

- [ ] write интеграционный тест round-trip: тривиальная `.sq` (одна таблица + insert/select-запрос) → сгенерённый код компилируется в `macosArm64` → runtime insert/select через `native-driver` возвращает вставленное
- [ ] `plugins/sqldelight-gen` (`product: jvm/amper-plugin`): `@TaskAction`, вызывающий компиляторный API SQLDelight (`SqlDelightCompiler`/env) для генерации Kotlin из `sqldelight/` в `${taskOutputDir}`; исследовать реальные точки входа по артефактам SQLDelight
- [ ] `plugin.yaml`: регистрация task + `generated.sources: [{language: kotlin, directory: …}]`; `project.yaml`: `modules: [.]`, `plugins: [./plugins/sqldelight-gen]`; корневой `module.yaml`: `plugins: { sqldelight-gen: enabled }` + dep `app.cash.sqldelight:native-driver`
- [ ] ⚠️ **фолбэк:** если компиляторный API SQLDelight не поддаётся в разумных пределах ИЛИ `native-driver` не линкуется на macosArm64 — НЕ тонуть: пометить `[deviation]`, задокументировать в этом файле переход на JSONL-хранилище (Task 6 реализует `EventStore` поверх JSONL), спайк закрыть как «SQLDelight отклонён, идём на JSONL»
- [ ] write тест: подтвердить рабочий путь (SQLDelight round-trip ЛИБО, при фолбэке, JSONL append/read round-trip)
- [ ] run `./kotlin test` — выбранный путь хранилища зелёный перед Task 5

### Task 5: Домен — AgentEvent, SessionState, модель сессии (host-free)

**Files:**
- Create: `src/core/AgentEvent.kt`, `src/core/SessionState.kt`, `src/core/SessionMeta.kt`, `src/core/Ids.kt`
- Create: `test/core/DomainTest.kt`

- [ ] write тесты: `@Serializable` round-trip каждого v1-`AgentEvent`-подтипа; инварианты value-class id
- [ ] `Ids.kt`: value-class'ы `SessionId`, `Seq`, `ProviderSessionId`, `PaneId`
- [ ] `AgentEvent.kt`: sealed-иерархия v1 (`TurnStarted/TurnCompleted/ApprovalRequested/ApprovalResolved/ToolCall/Exited/SessionBound`) + `EventSource`
- [ ] `SessionState.kt`: enum 7 состояний + живые/мёртвые + `needsAttention`; `needs_answer` — forward-modeled
- [ ] `SessionMeta.kt`: data class полей сессии
- [ ] run `./kotlin test` — домен зелёный перед Task 6

### Task 6: Редьюсер — лог → проекция (host-free, ядро TDD)

**Files:**
- Create: `src/core/Reducer.kt`, `src/core/Projection.kt`
- Create: `test/core/ReducerTest.kt`

- [ ] write тесты переходов v1: start→running; ApprovalRequested→needs_approval; TurnCompleted/Stop→ready; ответ→running; Exited(0)→stopped vs Exited(≠0)→crashed; SessionBound пишет provider-id
- [ ] write тест правила разрешения approval (нет «permission answered»): **вход в running сбрасывает `pendingApprovals=0`** → цепочка `Notification→PostToolUse→running` гасит `needs_approval`
- [ ] write тесты: `Interrupt` сбрасывает залипший running; `Detach` не меняет состояние; `replay` детерминирован (property: fold-с-нуля == инкрементальный)
- [ ] `Reducer.kt`: чистая `reduce(projection, event)`; `Projection.kt`: read-model (state, pendingApprovals, last_seq, unread). Waiting-логика v1 — approval-only
- [ ] run `./kotlin test` — редьюсер зелёный перед Task 7

### Task 7: EventStore — интерфейс + реализация (SQLDelight или JSONL)

**Files:**
- Create: `src/store/EventStore.kt`
- Create: `src/store/EventStoreImpl.kt` (SQLDelight) ИЛИ `src/store/JsonlEventStore.kt` (фолбэк)
- Create: `sqldelight/io/kotgent/db/Events.sq`, `sqldelight/io/kotgent/db/Sessions.sq` (если SQLDelight)
- Create: `test/store/EventStoreTest.kt`

- [ ] write тесты (против интерфейса): `append`→`read(fromSeq)` round-trip; `seq` монотонный per-session; append+обновление кэша атомарны; `replay` восстанавливает состояние; `subscribe(fromSeq)` эмитит новые; протухший курсор → ошибка
- [ ] `EventStore.kt`: интерфейс `append(sessionId,event)→seq`, `read(sessionId,fromSeq)`, `subscribe(fromSeq)`
- [ ] реализация по итогу Task 4: **SQLDelight** (`.sq` схема events/sessions, `native-driver`, single-writer, WAL, транзакция) ЛИБО **JSONL** (append-only на сессию + in-memory read-model, fsync, игнор частичной последней строки при replay)
- [ ] write тесты: конкурентные читатели не блокируют писателя (SQLDelight WAL) / потокобезопасность in-memory (JSONL)
- [ ] run `./kotlin test` — стор зелёный перед Task 8

### Task 8: Обёртка над tmux (`tmux -L kotgent`)

**Files:**
- Create: `src/tmux/Tmux.kt`, `src/tmux/ProcessRunner.kt`
- Create: `test/tmux/TmuxTest.kt`

- [ ] write интеграционные тесты против `tmux -L kotgent-test` (skip-guard): `newSession`→`pane_id`; `listSessions`/`listPanes` парсятся; `capturePane`; `killSession`
- [ ] `ProcessRunner.kt`: запуск процесса через `posix_spawn`, сбор stdout/stderr/exit
- [ ] `Tmux.kt`: `ensureServer()`, `newSession(id,cwd,cmd,cols,rows)→PaneId`, `listSessions()`, `listPanes()`, `capturePane(id)`, `killSession(id)`, `sendKeys(id,bytes)`, `paneAlive`/`panePid`
- [ ] экранирование аргументов; парсинг `-F` форматов
- [ ] write тесты: несуществующая сессия, двойной `killSession`
- [ ] run `./kotlin test` — обёртка зелёная перед Task 9

### Task 9: PTY fan-out — lazy upstream-мост + broadcaster + capture-pane сид

**Files:**
- Create: `src/pty/TerminalBridge.kt`, `src/pty/Broadcaster.kt`
- Create: `test/pty/TerminalBridgeTest.kt`

- [ ] write интеграционный тест: первый подписчик поднимает upstream `tmux attach` к сессии с `cat`; два подписчика получают вывод; ввод любого доходит; resize пробрасывается; новый подписчик получает `capture-pane -e` сид (fails until Task 8)
- [ ] write тест **lazy lifecycle**: уход последнего подписчика гасит мост, tmux/Claude живут (Detach); новый подписчик заново поднимает мост (снимает respawn после рестарта)
- [ ] `TerminalBridge.kt`: **lazy** `Pty.open("tmux -L kotgent attach -t kt-<id>")` при первом подписчике, reader-loop → `Broadcaster`, close при последнем
- [ ] `Broadcaster.kt`: подписчики, fan-out; ввод любого → upstream; размер «последний активный» → `resize()`. ⚠️ `window-size` по умолчанию `latest`: сид иного размера даст reflow — косметика
- [ ] run `./kotlin test` — fan-out зелёный перед Task 10

### Task 10: Контракт AgentAdapter (+ FakeAdapter)

**Files:**
- Create: `src/adapter/AgentAdapter.kt`, `src/adapter/LaunchSpec.kt`
- Create: `test/adapter/FakeAdapter.kt`, `test/adapter/AdapterContractTest.kt`

- [ ] write тесты: `FakeAdapter` эмитит `AgentEvent`, редьюсер сворачивает в ожидаемые состояния (контракт «адаптер→события→редьюсер»)
- [ ] `AgentAdapter.kt`: `buildLaunchSpec(mode: New|Resume)` + `events: Flow<AgentEvent>`
- [ ] `LaunchSpec.kt`: `command: List<String>`, `env`, `cwd`, `preallocatedSessionId?`
- [ ] (capability-интерфейсы — бэклог, в срезе НЕ вводим)
- [ ] write тест: контракт-прогон покрывает все v1-события
- [ ] run `./kotlin test` — контракт зелёный перед Task 11

### Task 11: ClaudeAdapter — launch/resume, hook-config, session-id preallocation

**Files:**
- Create: `src/adapter/claude/ClaudeAdapter.kt`, `src/adapter/claude/ClaudeHookConfig.kt`, `src/adapter/claude/ClaudeCli.kt`
- Create: `test/adapter/claude/ClaudeAdapterTest.kt`

- [ ] **спайк (перед маппингом): вживую вызвать у Claude permission-prompt и залогировать реальные `Notification`-пейлоады**; зафиксировать дискриминатор permission-vs-idle (или подтвердить любой `Notification`→needs_attention)
- [ ] write тесты: `buildLaunchSpec(New)` содержит `--session-id <uuid>` (version-gated) + `--settings <hook-config>`; `buildLaunchSpec(Resume)` → `claude --resume <id>`; hook-config корректен
- [ ] `ClaudeCli.kt`: путь/версия `claude`; version-gating `--session-id` (подтверждено в 2.1.217; fallback `SessionStart`)
- [ ] `ClaudeHookConfig.kt`: settings-файл с хуками (`UserPromptSubmit`/`PostToolUse`/`Stop`/`Notification`/`SessionStart`), курлящими `POST /hooks/claude` с токеном и `$TMUX_PANE`
- [ ] `ClaudeAdapter.kt`: реализация контракта (транскрипт-вотч — бэклог)
- [ ] write тесты: version-gating, resume-спека
- [ ] run `./kotlin test` — адаптер зелёный перед Task 12

### Task 12: Hook ingress + нормализация Claude-событий

**Files:**
- Create: `src/adapter/claude/ClaudeHookNormalizer.kt`, `src/transport/HookRoutes.kt`
- Create: `test/adapter/claude/HookNormalizerTest.kt`

- [ ] write тесты (нормализатор — чистая функция): пейлоады → `AgentEvent` (`Notification`→ApprovalRequested/needs_attention; `Stop`→TurnCompleted/ready; `PostToolUse`→running-событие, сбрасывающее pendingApprovals; `SessionStart`→SessionBound)
- [ ] `ClaudeHookNormalizer.kt`: чистая `(hookPayload, paneId) → AgentEvent`; любой `Notification`→needs_attention
- [ ] `HookRoutes.kt`: `POST /hooks/claude` — валидация токена, чтение `$TMUX_PANE`+пейлоада, мапинг pane→сессия (**partial-dep:** `pane_id` пишет Task 13 → тест через засиженный store), нормализация, `append`
- [ ] обработать «нет permission-answered» → вход в running (`PostToolUse`) обнуляет pendingApprovals
- [ ] write тесты: неизвестный pane → ошибка; `[x] невалидный токен → 401 (route-level, fails until Task 14-харнесс)`
- [ ] run `./kotlin test` — нормализатор зелёный перед Task 13

### Task 13: Session manager + reconciliation + provider-id capture

**Files:**
- Create: `src/daemon/SessionManager.kt`, `src/daemon/Reconciler.kt`, `src/daemon/ProviderIdCapture.kt`
- Create: `test/daemon/ReconcilerTest.kt`, `test/daemon/SessionManagerTest.kt`

- [ ] write тесты reconciliation: (строки `sessions` × состояние tmux × наличие vendor-файла) → running/resumable/crashed/stopped (табличные, host-free через фейковые Tmux/Store)
- [ ] write тесты provider-id capture: preallocated → мгновенно `SessionBound`; discovery буксует → «id pending» + retry, resume заблокирован
- [ ] `SessionManager.kt`: `start` (tmux new-session → pane_id → upsert sessions → capture; **мост НЕ спавнится — lazy на первый terminal-WS-подписчик**) / `stop`/`resume`/`interrupt`/`detach` по `StopMode`
- [ ] `Reconciler.kt`: старт daemon — `list-sessions`/`list-panes` + vendor-store → пересбор `pane_id`, классификация. **Мосты не пере-поднимаем.** `ProviderIdCapture.kt`: гарантия id
- [ ] write интеграционный тест: `start` создаёт tmux-сессию и захватывает `pane_id`; после «рестарта» (новый Reconciler) состояние восстановлено, terminal-WS-подписка заново поднимает мост
- [ ] run `./kotlin test` — daemon-ядро зелёное перед Task 14

### Task 14: Transport — control REST + events WS + terminal WS + токен-auth

**Files:**
- Create: `src/transport/Server.kt`, `src/transport/ControlRoutes.kt`, `src/transport/EventsWs.kt`, `src/transport/TerminalWs.kt`, `src/transport/Auth.kt`
- Create: `test/transport/TransportTest.kt`

- [ ] write интеграционные тесты: `POST /sessions` → сессия в `GET /sessions`; events-WS получает смену состояния; terminal-WS стримит байты и принимает ввод; нет токена → 401
- [ ] `Auth.kt`: токен `~/.kotgent/token` (0600); один bearer на всё (hook-токен — бэклог)
- [ ] `ControlRoutes.kt`: `GET /sessions`, `GET /sessions/{id}`, `POST /sessions`, `POST /sessions/{id}/{stop|resume|interrupt|detach}`, `POST /sessions/{id}/input` (только `TerminalInput`). `PATCH` — бэклог
- [ ] `EventsWs.kt`: `GET /events?from=<seq>` — `subscribe`, restart-safe курсор; `TerminalWs.kt`: мост к lazy `Broadcaster` + `capture-pane` сид + resize; статика Web UI на `/`
- [ ] write тесты: restart-safe курсор, resize-фрейм → `TIOCSWINSZ`
- [ ] run `./kotlin test` — transport зелёный перед Task 15

### Task 15: CLI-субкоманды + `attach` raw-passthrough

**Files:**
- Create: `src/cli/Cli.kt`, `src/cli/Commands.kt`, `src/cli/AttachClient.kt`, `src/cli/ApiClient.kt`
- Modify: `src/main.kt`
- Create: `test/cli/CliTest.kt`

- [ ] write тесты: парсинг субкоманд; `ApiClient` шлёт корректные HTTP (против stub-сервера), читая токен из файла
- [ ] `Commands.kt`: `daemon`, `start/list/stop/resume` (HTTP), `install`/`uninstall` (Task 16)
- [ ] `AttachClient.kt`: raw-passthrough — tty raw (`termios`), stdin→WS, WS→stdout, `SIGWINCH`→resize, восстановление tty на выходе
- [ ] `main.kt`: диспатч субкоманд (сохранить `versionLine()`/`--version`)
- [ ] write тесты: `list` рендерит ответ; `start` валидирует cwd/agent; `attach` — smoke (tty-raw → manual)
- [ ] run `./kotlin test` — CLI зелёный перед Task 16

### Task 16: launchd LaunchAgent install

**Files:**
- Create: `src/launchd/Plist.kt`, `src/launchd/Install.kt`
- Create: `test/launchd/PlistTest.kt`

- [ ] write тесты: plist содержит `Label`, `ProgramArguments`=[бинарь, `daemon`], `RunAtLoad`, `KeepAlive`, `ThrottleInterval`, `EnvironmentVariables.PATH` (`/opt/homebrew/bin`), `StandardOut/ErrorPath`
- [ ] `Plist.kt`: чистая генерация XML-plist
- [ ] `Install.kt`: `install` пишет `~/Library/LaunchAgents/io.kotgent.daemon.plist` + `launchctl bootstrap`; `uninstall` — `bootout`+удаление; путь бинаря — из build-выхода `macos/app`
- [ ] обработать существующий plist (идемпотентность)
- [ ] write тесты: путь plist, идемпотентность
- [ ] run `./kotlin test` — launchd зелёный перед Task 17

### Task 17: Минимальный Web UI (статика + xterm.js)

**Files:**
- Create: `resources/webui/index.html`, `resources/webui/app.js`, `resources/webui/style.css`, `resources/webui/vendor/xterm.js` (vendored)
- Create: `test/webui/AppLogicTest.kt` (или node-based unit для парсинга токена/API)

- [ ] write юнит-тест тестируемой логики: парсинг токена из `#token=`, формирование запросов API-клиента
- [ ] `index.html`+`app.js`: список сессий (`GET /sessions`), живость через events-WS (бейджи, очередь «needs attention»)
- [ ] xterm.js на terminal-WS: рендер байтов, отправка ввода, `resize`-фреймы
- [ ] daemon отдаёт статику из `resources/webui` на `/`; токен из фрагмента → API/WS
- [ ] write тест: рендер строки сессии по состоянию (needs_approval → индикатор)
- [ ] run `./kotlin test`; браузерный проход — Task 18 (manual)

### Task 18: Проверка acceptance-критериев (сквозной срез)

- [ ] verify: `kotgent start` Claude запускает сессию; закрытие `attach` (Detach) оставляет живой; браузер продолжает ту же сессию; `needs attention` виден при approval
- [ ] verify reconciliation: рестарт daemon (`launchctl kickstart`) — живые сессии переклассифицируются, terminal-WS в браузере заново поднимает lazy-мост; убитая → `crashed`; после kill tmux-сервера → `resumable`, `resume` восстанавливает разговор
- [ ] verify provider-id capture: у каждой запущенной сессии сохранён `provider_session_id`; «id pending» блокирует resume честно
- [ ] run full suite: `./kotlin test`
- [ ] manual: браузерный проход среза (start → Detach → браузер → needs attention) + GIF для README

### Task 19: Документация

- [ ] `README.md` (требования: Kotlin Toolchain/`./kotlin`, tmux, claude; сборка/`daemon`/`install`/`start`/`attach`)
- [ ] `CLAUDE.md` (паттерны: Kotlin Toolchain layout, host-free ядро, event-sourcing+редьюсер, cinterop-модель PTY, single-upstream инвариант, идентичность по pane_id, storage-путь SQLDelight-плагин/JSONL)
- [ ] обновить статус в `idea.md` (что реализовано)
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion

*Ручное вмешательство/внешние системы — без чекбоксов.*

**Manual verification:**
- сквозной браузерный проход среза (Task 18 GIF).
- `kotgent attach` в raw-tty — интерактивно (ввод/ресайз/восстановление терминала).
- launchd на реальном логине/ребуте (RunAtLoad + KeepAlive + reconciliation).
- версии `claude` (`--session-id`) и `tmux`.
- при фолбэке хранилища на JSONL — зафиксировать решение в этом файле и `CLAUDE.md`.

**External / backlog:**
- Codex-адаптер (rollout-JSONL → app-server); PWA + cloudflared + Access + Web Push; diff viewer; импорт внешних сессий; снапшоты; e2e (Playwright).
- если SQLDelight-плагин взлетел — вынести в переиспользуемый Kotlin Toolchain-плагин; иначе — дозреть JSONL-стор (ротация/снапшоты).
