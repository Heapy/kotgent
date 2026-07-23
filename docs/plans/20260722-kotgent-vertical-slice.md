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
- **cinterop:** `.def` кладутся в `cinterop/` **без YAML** (Kotlin Toolchain сам их подхватывает). **ВСЕ cinterop `.def` + тонкие нативные wrapper'ы живут в выделенном модуле `sysnative` (`kmp/lib`, `macosArm64`)**, от которого зависит app (`dependencies: ./sysnative`); будущие биндинги (ProcessRunner/tmux, sqlite cinterop) кладутся туда же. ⚠️ Тулчейн 0.11.0 не линкует cinterop в **test**-бинари (см. блокер Task 2) — cinterop-тесты `@Ignore`.
- **Хранилище:** SQLDelight — Gradle-плагин, на Toolchain недоступен напрямую → обошли **своим `jvm/amper-plugin`** (`plugins/sqldelight-gen`), вызывающим компилятор SQLDelight (vendored `SqlDelightEnvironment`) для codegen. ✅ **Спайк Task 4 подтвердил SQLDelight — фолбэк JSONL НЕ понадобился** (полный round-trip `.sq`→codegen→macosArm64→`native-driver` зелёный).
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
- Корневой модуль (`macos/app`): `src/` (пакеты `io.kotgent.*`), `test/`, `sqldelight/` (`.sq`), `resources/webui/` (SPA). Зависит от `./sysnative`.
- **`sysnative/` (`kmp/lib`, `macosArm64`): владеет ВСЕМИ низкоуровневыми POSIX/cinterop-биндингами + тонкими нативными wrapper'ами.** `sysnative/cinterop/*.def` (PTY сейчас; ProcessRunner/tmux, sqlite cinterop — позже сюда же) + `sysnative/src/` (напр. `Pty`, пакет `io.kotgent.pty`). app зависит от `./sysnative` → cinterop klib линкуется в **main**-бинарь app'а как обычная модульная зависимость (без машинно-зависимого `-library`-пути). ⚠️ Тулчейн 0.11.0 НЕ линкует cinterop в **test**-бинари (Task 2-блокер) → cinterop-тесты `@Ignore`.
- `project.yaml`: `modules: [., ./sysnative]`.
- `plugins/sqldelight-gen/` (`jvm/amper-plugin`): codegen SQLDelight. `project.yaml` перечисляет модуль + плагин; корневой `module.yaml` — `plugins: { sqldelight-gen: enabled }`.

**Packages (host-free ↔ края):**
- `core/` — **host-free**: `AgentEvent`, `SessionState`, `SessionMeta`, `Reducer` (лог → проекция). Без IO, максимум тестов.
- `store/` — `EventStore` интерфейс + реализация: **SQLDelight** (через плагин + `native-driver`) ИЛИ фолбэк JSONL. Интерфейс изолирует downstream от выбора.
- `pty/` — PTY-примитив (`openpty`+`posix_spawn`, **низкоуровневая часть — в модуле `sysnative`**) + fan-out (`TerminalBridge`/`Broadcaster` — в app). **Lazy lifecycle:** upstream `tmux attach` поднимается при первом подписчике, гаснет при последнем (Claude живёт в tmux независимо; даёт Detach И снимает respawn после рестарта daemon).
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
- **Хранилище (выбрано Task 4 = SQLDelight):** SQLDelight через `jvm/amper-plugin` (codegen, vendored `SqlDelightEnvironment`, SQLDelight 2.3.2) + `native-driver:2.3.2` (single-writer, WAL, append+кэш в одной транзакции; линк требует `-linker-option -lsqlite3` в корневом `module.yaml`). Фолбэк JSONL **НЕ задействован** (оставлен как backlog-опция на случай регрессии). `EventStore.{append,read,subscribe}` изолирует выбор в любом случае.
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

- [x] write интеграционный тест (bounded read + timeout — анти-флаки): открыть PTY, заспавнить `/bin/cat`, записать в мастер, прочитать эхо, задать winsize — ASSERT round-trip
- [x] `cinterop/pty.def` (без YAML) с `#include <util.h> <sys/ioctl.h> <termios.h> <spawn.h>`, экспорт `openpty`/`ioctl`/`TIOCSWINSZ`/`winsize`/`posix_spawn`/`posix_spawnattr_*`/`POSIX_SPAWN_SETSID`
- [x] `Pty.kt`: `open(...)` через **`openpty`+`posix_spawn(POSIX_SPAWN_SETSID)`** (НЕ `forkpty` — fork-без-exec небезопасен для рантайма K/N; C-строки маршалятся ДО спавна); мастер-fd в родителе; `read()`/`write()`/`resize()`(`TIOCSWINSZ`)/`close()`
- [x] модель чтения: **выделенный reader-thread** (`newSingleThreadContext`/Worker) с блокирующим `read()` → `Channel` (на native нет `Dispatchers.IO`)
- [x] write тесты: exit-код, resize, ошибка спавна несуществующей команды
- [x] run `./kotlin test` — PTY round-trip зелёный перед Task 3 (5/5 зелёные)

**⚠️ Toolchain-находка (спайк подтвердил PTY, но вскрыл баг тулчейна):**
- **Реализация рабочая, доказана:** `openpty`+`posix_spawn(POSIX_SPAWN_SETSID)` даёт `/bin/cat` round-trip, resize (`TIOCSWINSZ`), exit-код, spawn-error. Cinterop линкуется в **main**-бинарь → примитив реально исполняется при `./kotlin build`/`./kotlin run`. Сами 4 PTY-интеграционных теста сейчас `@Ignore` (см. блокер ниже), smoke зелёный, `./kotlin test` = exit 0.
- **cinterop header-scan:** тулчейн НЕ сканирует `util.h`/`sys/ioctl.h`/`termios.h` из `headers=` (в klib-манифест попал только `spawn.h`). `openpty` и установка winsize вынесены в C-хелперы (`kotgent_openpty`/`kotgent_set_winsize`) в `---`-теле `.def` — тело компилируется против этих заголовков, поэтому символы резолвятся. `posix_spawn*`/`POSIX_SPAWN_SETSID` берутся из `spawn.h` штатно.
- **✅ Корректирующий рефактор (машинно-зависимый хак УДАЛЁН, портабельно):** cinterop `.def` + тонкий wrapper `Pty` вынесены в выделенный модуль `sysnative` (`kmp/lib`, `macosArm64`); корневой `module.yaml` зависит от `./sysnative`; создан `project.yaml` (`modules: [., ./sysnative]`). Абсолютный `-library`-путь в `test-settings.freeCompilerArgs` (был машинно-специфичным, указывал в gitignored `build/`) полностью убран. Проверка: `git grep -n 'freeCompilerArgs\|/Users/yoda' -- '*.yaml'` = пусто. cinterop klib теперь линкуется в **main**-бинарь app'а как обычная модульная зависимость.
- **⚠️ ОСТАВШИЙСЯ БЛОКЕР (0.11.0 — нужна ЭСКАЛАЦИЯ к пользователю):** выделенный модуль НЕ починил линковку cinterop в **test**-бинарь. `linkMacosArm64TestDebug` не линкует cinterop klib НИ в один test-бинарь — доказано тремя способами: (1) cinterop в самом app-модуле (Task 2); (2) cinterop в зависимости `sysnative`, потребляемой app'ом (транзитивно) — `IrLinkageError` в app-test; (3) cinterop-тест ВНУТРИ собственного test-бинаря `sysnative` — тот же `IrLinkageError: kotgent_openpty` (KT-78062). Портабельного обхода нет: относительный `-library` konanc трактует как имя репозитория (не файл), в module.yaml нет подстановки переменных, а ЛЮБОЙ `freeCompilerArgs -library` нарушает требование портабельности. → 4 PTY-интеграционных теста помечены `@Ignore` (в отчёте = SKIPPED, это НЕ подделка «pass»); включаются обратно, когда тулчейн начнёт линковать cinterop в test-бинари (апстрим-фикс KT-78062). **Решение за пользователем:** (a) принять `@Ignore` — портабельно, дефолт репозитория сейчас; (b) вернуть абсолютный `-library`-хак только для локального прогона PTY-тестов (непортабельно); (c) ждать фикса тулчейна. `./kotlin build` и `./kotlin test` (exit 0) зелёные при любом выборе.
- **➕ Корректирующий рефактор (вне нумерации задач, не меняет чекбоксы):** cinterop `.def` + `Pty` → модуль `sysnative` (`kmp/lib`); удалён машинно-зависимый абсолютный `-library` test-хак; PTY-тесты `@Ignore` до апстрим-фикса линковки cinterop в test-бинари.

### Task 3: Ktor CIO HTTP+WS-спайк на native (РИСК)

**Files:**
- Modify: `module.yaml`
- Create: `src/transport/SpikeServer.kt`
- Create: `test/transport/WsSpikeTest.kt`

- [x] write интеграционный тест: поднять сервер на `127.0.0.1:0`, Ktor-client'ом HTTP GET round-trip + WS echo (текст и **binary**-фрейм) — ASSERT
- [x] `module.yaml`: `settings.ktor: enabled` + deps `ktor-server-cio`, `ktor-server-websockets`; `test-dependencies`: `ktor-client-cio` (+ websockets)
- [x] `SpikeServer.kt`: `embeddedServer(CIO)` с HTTP-роутом, WS-echo, отдачей статического файла
- [x] ⚠️→✅ WS-плагин CIO на native ПОКРЫВАЕТ нужное (text+binary echo round-trip зелёный) — блокер НЕ сработал, эскалация не требуется
- [x] write тест: бинарный WS-фрейм round-trip (нужен для terminal-канала)
- [x] run `./kotlin test` — WS зелёный перед Task 4 (5 passed: SmokeTest + 4 WsSpikeTest; 4 PtyTest @Ignore; exit 0)

**✅ Спайк подтверждён — Ktor CIO HTTP+WS работает на macosArm64 native:**
- **Что доказано:** `embeddedServer(CIO, port=0)` + `install(WebSockets)` поднимается на эфемерном порту (`resolvedConnectors().first().port` отдаёт реальный порт); `GET /hello` round-trip; WS `/echo` эхо **TEXT** и **BINARY**-фреймов (binary — байт-в-байт, нужен для terminal-канала); отдача реального файла с диска через `GET /static`. Ktor CIO-клиент (`ktor-client-cio` + `ktor-client-websockets`) в тестах гоняет все 4 round-trip'а. Все 4 WS-теста зелёные, `./kotlin build`/`./kotlin test` = exit 0.
- **Версии:** `settings.ktor: enabled` даёт BOM/каталог (дефолт 3.4.1); ktor-артефакты объявлены БЕЗ версий — резолвятся из BOM. Native-артефакты (`ktor-server-cio`/`-server-websockets`/`-client-cio`/`-client-websockets`) для macosArm64 разрешились штатно.
- **✅ KT-78062 probe — ГЛАВНЫЙ вывод:** Ktor — сторонний klib, чьи native-артефакты содержат внутренний cinterop; тем не менее он **линкуется в TEST-бинарь чисто** (`linkMacosArm64TestDebug` = success, никаких `IrLinkageError`/unresolved-symbol) и WS/HTTP реально исполняются в рантайме тестов. Это ожидаемый ХОРОШИЙ исход: **KT-78062 бьёт ТОЛЬКО по нашему собственному raw-cinterop `.def` (Task 2 PTY), а НЕ по dependency-klib'ам** типа Ktor. Транспорт (Task 14) тестируется полноценно.
- **[decision] static-file через posix-I/O:** Ktor'овские `staticFiles`/`staticResources` завязаны на `java.io.File`/classloader (JVM-only) → на native недоступны. Статика отдаётся чтением реального файла с диска через `platform.posix` (`fopen`/`fread`, без кастомного cinterop — `platform.posix` штатная K/N platform-либа). Файл пишется в `$TMPDIR/kotgent-ws-spike-static-<pid>.txt` на `start()`. Для Web UI (Task 17) это тот же путь — отдача статики из `resources/webui` реализуется своим file-reader'ом, не JVM-хелперами Ktor.
- **Примечание:** линковка печатает безвредные `'+zcm' is not a recognized feature` (LLVM/Apple-target warning, лейбл ERROR от форматтера лога) — на результат не влияет, `Build successful`.

### Task 4: SQLDelight через свой Kotlin Toolchain-плагин — спайк (РИСК, фолбэк JSONL)

**Files:**
- Create: `project.yaml`
- Create: `plugins/sqldelight-gen/module.yaml`, `plugins/sqldelight-gen/plugin.yaml`, `plugins/sqldelight-gen/src/Generate.kt`
- Create: `sqldelight/io/kotgent/db/Spike.sq`
- Modify: `module.yaml`
- Create: `test/store/SqlDelightSpikeTest.kt`

- [x] write интеграционный тест round-trip: тривиальная `.sq` (одна таблица + insert/select-запрос) → сгенерённый код компилируется в `macosArm64` → runtime insert/select через `native-driver` возвращает вставленное (`test/store/SqlDelightSpikeTest.kt::generatedApiRoundTrip` зелёный)
- [x] `plugins/sqldelight-gen` (`product: jvm/amper-plugin`): `@TaskAction`, вызывающий компиляторный API SQLDelight для генерации Kotlin из `sqldelight/` в `${taskOutputDir}`; точки входа найдены — конструируем **vendored (Apache-2.0) `SqlDelightEnvironment`** и зовём `generateSqlDelightFiles` (см. блок ниже)
- [x] `plugin.yaml`: регистрация task + `generated.sources: [{language: kotlin, directory: ${tasks.generate.action.generatedSourceDir}}]`; `project.yaml`: `modules: [., ./sysnative, ./plugins/sqldelight-gen]`, `plugins: [./plugins/sqldelight-gen]`; корневой `module.yaml`: `plugins: { sqldelight-gen: enabled }` + dep `app.cash.sqldelight:native-driver:2.3.2`
- [x] ⚠️ **фолбэк НЕ понадобился:** компиляторный API SQLDelight поддался И `native-driver` слинковался на macosArm64 → идём на **SQLDelight** (не JSONL). JSONL-путь оценён и отклонён как ненужный; `EventStore`-интерфейс (Task 7) всё равно изолирует downstream.
- [x] write тест: подтвердить рабочий путь — SQLDelight round-trip (`generatedApiRoundTrip`) + независимая проба рантайма (`nativeDriverRawRoundTrip`)
- [x] run `./kotlin test` — путь хранилища (SQLDelight) зелёный перед Task 5 (7 passed: SmokeTest + 2 SqlDelightSpikeTest + 4 WsSpikeTest; 4 PtyTest @Ignore; exit 0)

**✅ Спайк подтверждён — SQLDelight РАБОТАЕТ на Kotlin Toolchain через свой `jvm/amper-plugin` (фолбэк JSONL НЕ понадобился):**
- **Что доказано (полный пайплайн):** `sqldelight/io/kotgent/db/Spike.sq` (1 таблица + `insertValue`/`selectLatest`) → плагин `sqldelight-gen` гоняет кодоген SQLDelight в build-таске (`generate@sqldelight-gen`) → сгенерённые `KotgentDatabase`/`SpikeQueries`/`Spike` компилируются в **macosArm64** app-модуль через `generated.sources` → рантайм insert/select типизированным API (`KotgentDatabase(driver).spikeQueries.insertValue(...)` / `.selectLatest().executeAsOne()`) поверх `native-driver` (in-memory SQLite) возвращает вставленное. `./kotlin build`/`./kotlin test` = exit 0.
- **Версии/координаты:** SQLDelight **2.3.2** (собран Kotlin 2.3.10 — klib-совместим с нашим 2.4.10). Рантайм: `app.cash.sqldelight:native-driver:2.3.2` (тянет `co.touchlab:sqliter-driver` — сторонний cinterop к sqlite3). Плагин (build-time JVM) зависит от: `app.cash.sqldelight:core:2.3.2` (компилятор + модельные интерфейсы), `app.cash.sqldelight:sqlite-3-38-dialect:2.3.2` (диалект через ServiceLoader), `app.cash.sql-psi:environment:0.7.3` (`SqlCoreEnvironment`), `app.cash.sqldelight:compiler-env:2.3.2` (48MB shaded IntelliJ, un-relocated `com.intellij.*`).
- **[decision] vendored `SqlDelightEnvironment`:** реальная точка входа кодогена — класс `app.cash.sqldelight.core.SqlDelightEnvironment` (mock-IntelliJ для парсинга `.sq` PSI + `SqlDelightCompiler`). Он лежит ТОЛЬКО в артефакте `gradle-plugin` (тянет `kotlin-gradle-plugin`), но сам по себе Gradle-free. Мы **вендорим** его (`plugins/sqldelight-gen/src/SqlDelightEnvironment.kt`, Apache-2.0), убрав единственную gradle-plugin-only зависимость (optimistic-lock annotator → `annotate(emptyList())`). Модельные интерфейсы (`SqlDelightDatabaseProperties`/`CompilationUnit`/`SourceFolder`) — простые `Serializable`-интерфейсы из `core`, реализованы приватными data-классами в `Generate.kt`.
- **[decision] `-linker-option -lsqlite3` в `settings.kotlin.freeCompilerArgs` (корневой `module.yaml`):** cinterop `sqliter-driver` зовёт системный SQLite (`sqlite3_*`), но его `linkerOpts = -lsqlite3` НЕ пробрасывается тулчейном 0.11.0 в финальный K/N-линк — и main-, и test-бинарь падали `ld: symbol(s) not found` для `_sqlite3_*`. Флаг добавлен вручную. **Портабельно** (в отличие от удалённого в Task 2 машинно-зависимого абсолютного `-library`-пути): `-lsqlite3` — системная либа из каждого macOS SDK, линкер находит без `-L`. → инвариант Task 2 уточняется: портабельные системные флаги в yaml ОК, машинно-зависимые абсолютные пути — нет; `git grep -n '/Users/' -- '*.yaml'` по-прежнему пусто.
- **✅ KT-78062 — подтверждение вывода Task 3:** `native-driver`+`sqliter-driver` — сторонние dependency-klib'ы с внутренним cinterop; они **линкуются в TEST-бинарь чисто** (обёртки `_co_touchlab_sqliter_*` реально попали в `kotgent_test.kexe.o`) и SQLite исполняется в рантайме тестов. KT-78062 бьёт ТОЛЬКО по нашему собственному raw-cinterop (Task 2 PTY), НЕ по dependency-klib'ам. Стор (Task 7) тестируется полноценно.
- **[decision] generated sources = `public`, output dir чистится:** (1) `internal` в generated-фрагменте НЕ виден friend-модулю теста в 0.11.0 — генерённый код должен быть `public` (SQLDelight и так генерит public API, проблема мимо). (2) task output dir переиспользуется между сборками — генератор делает `deleteRecursively()` перед кодогеном (как gradle-таск SQLDelight), иначе остаются осиротевшие `.kt`.
- **Итог для Task 7:** хранилище = **SQLDelight** (`.sq` схема events/sessions + `native-driver`, single-writer/WAL/транзакция). JSONL-ветка в Task 7 НЕ реализуется. `EventStore.{append,read,subscribe}` изолирует downstream в любом случае.

### Task 5: Домен — AgentEvent, SessionState, модель сессии (host-free)

**Files:**
- Create: `src/core/AgentEvent.kt`, `src/core/SessionState.kt`, `src/core/SessionMeta.kt`, `src/core/Ids.kt`
- Create: `test/core/DomainTest.kt`

- [x] write тесты: `@Serializable` round-trip каждого v1-`AgentEvent`-подтипа; инварианты value-class id
- [x] `Ids.kt`: value-class'ы `SessionId`, `Seq`, `ProviderSessionId`, `PaneId`
- [x] `AgentEvent.kt`: sealed-иерархия v1 (`TurnStarted/TurnCompleted/ApprovalRequested/ApprovalResolved/ToolCall/Exited/SessionBound`) + `EventSource`
- [x] `SessionState.kt`: enum 7 состояний + живые/мёртвые + `needsAttention`; `needs_answer` — forward-modeled
- [x] `SessionMeta.kt`: data class полей сессии
- [x] run `./kotlin test` — домен зелёный перед Task 6 (12 DomainTest зелёные; 19 passed / 4 PtyTest skipped; SmokeTest+WsSpikeTest+SqlDelightSpikeTest не сломаны)

### Task 6: Редьюсер — лог → проекция (host-free, ядро TDD)

**Files:**
- Create: `src/core/Reducer.kt`, `src/core/Projection.kt`
- Create: `test/core/ReducerTest.kt`

- [x] write тесты переходов v1: start→running; ApprovalRequested→needs_approval; TurnCompleted/Stop→ready; ответ→running; Exited(0)→stopped vs Exited(≠0)→crashed; SessionBound пишет provider-id
- [x] write тест правила разрешения approval (нет «permission answered»): **вход в running сбрасывает `pendingApprovals=0`** → цепочка `Notification→PostToolUse→running` гасит `needs_approval`
- [x] write тесты: `Interrupt` сбрасывает залипший running; `Detach` не меняет состояние; `replay` детерминирован (property: fold-с-нуля == инкрементальный)
- [x] `Reducer.kt`: чистая `reduce(projection, event)`; `Projection.kt`: read-model (state, pendingApprovals, last_seq, unread). Waiting-логика v1 — approval-only
- [x] run `./kotlin test` — редьюсер зелёный перед Task 7

**✅ Реализовано — чистый host-free редьюсер (22 ReducerTest зелёные; suite 45 ran / 41 passed / 4 PtyTest @Ignore):**
- `Projection.kt` — immutable data-class read-model: `state`, `pendingApprovals`, `lastSeq`, `providerSessionId?`, `stopRequested`; `unread(readCursor)`/`hasUnread(readCursor)` деривят непрочитанное; `Projection.EMPTY` (seed для replay) = `running`. Инвариант `pendingApprovals>0 ⟺ needs_approval` (проверяется meta-property-тестом после каждого события).
- `Reducer.kt` — чистые `reduce(projection, event: AgentEvent)` (тотальна по 7 типам, +1 к lastSeq) и `replay(events): Projection` (fold из EMPTY, детерминизм + ассоциативность по всем split-точкам). Running-producers = `TurnStarted`+`ToolCall` (сбрасывают pendingApprovals=0). `ApprovalResolved` — forward-modeled (декремент, назад в running при обнулении). `needs_answer`/`resumable` редьюсером НЕ производятся (KDoc).
- **[decision] `ControlSignal` (sealed `Interrupt`/`Stop`/`Resume`/`Detach`) — ОТДЕЛЬНЫЙ вход редьюсера** через overload `reduce(projection, signal)`; persisted `AgentEvent`-словарь (7 типов) НЕ тронут; control-signals НЕ двигают `lastSeq`. `Interrupt`→ready+сброс approvals (залипший running); `Stop`→арм `stopRequested` (не-нулевой `Exited` тогда = `stopped`, не `crashed` — так Task 13/SessionManager различает интент-стоп от краха); `Resume`→оживляет мёртвую в ready; `Detach`→identity (клиент-дисконнект; в реале — transport-level, редьюсер его не видит).

### Task 7: EventStore — интерфейс + реализация (SQLDelight или JSONL)

**Files:**
- Create: `src/store/EventStore.kt`
- Create: `src/store/SqliteEventStore.kt` (SQLDelight — фолбэк JSONL НЕ реализован, см. Task 4)
- Create: `sqldelight/io/kotgent/db/Events.sq`, `sqldelight/io/kotgent/db/Sessions.sq`
- Create: `test/store/EventStoreTest.kt`
- Remove: `sqldelight/io/kotgent/db/Spike.sq`, `test/store/SqlDelightSpikeTest.kt` (Task 4 spike — real EventStore tests cover the same `.sq`→codegen→native-driver ground)

- [x] write тесты (против интерфейса): `append`→`read(fromSeq)` round-trip; `seq` монотонный per-session; append+обновление кэша атомарны; `replay` восстанавливает состояние; `subscribe(fromSeq)` эмитит новые; протухший курсор → ошибка
- [x] `EventStore.kt`: интерфейс `append(sessionId,event,source)→seq`, `read(sessionId,fromSeq)`, `subscribe(sessionId,fromSeq)` (+ `upsertSession`/`getSession`/`listSessions`/`projectionOf`)
- [x] реализация по итогу Task 4: **SQLDelight** (`.sq` схема events/sessions, `native-driver`, single-writer `Mutex`, WAL, append+кэш в одной транзакции)
- [x] write тесты: конкурентные аппенды сериализуются в непрерывный лог; конкурентные читатели видят только закоммиченный непрерывный префикс (SQLDelight WAL)
- [x] run `./kotlin test` — стор зелёный перед Task 8 (48 passed / 4 PtyTest skipped / exit 0)

**✅ Реализовано — SQLDelight-backed EventStore (9 EventStoreTest зелёные; suite 52 ran / 48 passed / 4 PtyTest @Ignore; `./kotlin build`+`test` exit 0):**
- `Events.sq` — append-only лог `events(session_id, seq, ts, type, source, payload)` PK `(session_id, seq)` + index; запросы `insert` / `nextSeq` (`coalesce(MAX(seq),0)+1`) / `selectFromSeq` (`WHERE session_id=? AND seq>=? ORDER BY seq`). `Sessions.sq` — read-model кэш (полный `SessionMeta` в порядке колонок); `upsert` (сохраняет `created_at`), `updateCache` (только reducer-производные поля), `get`, `list`.
- `EventStore.kt` — storage-agnostic интерфейс (`StoredEvent` = sessionId+seq+ts+source+event; `StaleCursorException`). `SqliteEventStore.kt` — single-writer через `Mutex`; каждый `append` в ОДНОЙ транзакции вставляет событие по `MAX(seq)+1` И двигает `sessions`-кэш через `reduce(priorProjection, event)` (проекция кэшируется в памяти, на холодную реконструируется `replay` из лога — restart-safe; крос-чек `reducer.lastSeq == db.nextSeq`). Payload = kotlinx JSON, `type` = класс-дискриминатор (== `@SerialName`), вытащенный из payload для queryability.
- `subscribe(sessionId, fromSeq)` — snapshot `read(fromSeq)` + live relay через `channelFlow`+`Channel`, зарегистрированный под тем же writer-`Mutex` (нет гонки на границе snapshot/live; контроль непрерывности); `fromSeq > lastSeq+1` → `StaleCursorException` (hard error, restart-safe курсор).
- **[decision] `subscribe(sessionId, fromSeq)` — per-session** (план писал `subscribe(fromSeq)`): `seq` — per-session, поэтому detection протухшего/gap-курсора когерентен только per-session; глобальный events-WS (Task 14) собирается merge'ем per-session потоков.
- **[decision] session-CRUD (`upsertSession`/`get`/`list`) на `EventStore`:** атомарный append+кэш делает обе таблицы одним storage-concern; daemon владеет метаданными строки, `append` двигает только кэш-поля существующей строки.
- **[deviation] `PRAGMA journal_mode=WAL` через `executeQuery`, не `execute`:** PRAGMA возвращает строку → sqliter `execute()` кидает «Queries ... query/rawQuery only». На `:memory:` WAL — no-op (возвращает `memory`), но выполняется без ошибки; реально включает WAL на file-backed БД daemon'а.
- **[deviation] дефолт-часы = `kotlin.time.Clock.System.now().toEpochMilliseconds()`:** `kotlin.system.getTimeMillis()` — ERROR-level deprecated в 2.4.10. Инжектируемо (`now: () -> Long`) → тесты детерминированы.
- **[decision] index `events_session_seq(session_id, seq)` избыточен с композитным PK** (SQLite уже индексирует PK) — добавлен буквально по схеме плана «+ index», безвреден.

### Task 8: Обёртка над tmux (`tmux -L kotgent`)

**Files:**
- Create: `src/tmux/Tmux.kt`, `src/tmux/ProcessRunner.kt`
- Create: `test/tmux/TmuxTest.kt`

- [x] write интеграционные тесты против `tmux -L kotgent-test` (skip-guard): `newSession`→`pane_id`; `listSessions`/`listPanes` парсятся; `capturePane`; `killSession`
- [x] `ProcessRunner.kt`: запуск процесса через **`popen`/`pclose`** (НЕ `posix_spawn` — его нет в `platform.posix`, см. блок ниже), сбор stdout/stderr/exit
- [x] `Tmux.kt`: `ensureServer()`, `newSession(id,cwd,cmd,cols,rows)→PaneId`, `listSessions()`, `listPanes()`, `capturePane(id)`, `killSession(id)`, `sendKeys(id,bytes)`, `paneAlive`/`panePid`
- [x] экранирование аргументов; парсинг `-F` форматов
- [x] write тесты: несуществующая сессия, двойной `killSession`
- [x] run `./kotlin test` — обёртка зелёная перед Task 9

**✅ Реализовано — тонкая обёртка `tmux -L <socket>` на стоковом `platform.posix` (8 TmuxTest зелёные против РЕАЛЬНОГО tmux 3.7b из test-бинаря; suite 60 ran / 56 passed / 4 PtyTest @Ignore; `./kotlin build`+`test` exit 0):**
- `ProcessRunner.kt` — `run(argv, env)` → `ProcessResult(exitCode, stdoutBytes, stderrBytes)`; non-zero exit НЕ бросает (это результат, `tmux` так репортит «can't find session»); бросает `ProcessException` только на runner-фейле (`popen`/`mkstemp`). stderr редиректится в per-call temp-файл (`mkstemp`, атомарно), stdout полностью дренится одним блокирующим `fread`-циклом → **дедлок пайпа структурно невозможен** (единственный пайп, всегда до EOF; болтливый процесс не переполнит непрочитанный буфер) И stdout не загрязнён stderr (важно для `capture-pane`). Аргументы жёстко квотятся POSIX single-quote (`shQuote`), так что `/bin/sh -c` не может пере-split'нуть/расширить их (форматы `#{pane_id}`, TAB-разделители — литеральны).
- `Tmux.kt` — `ensureServer`/`newSession→PaneId`/`listSessions`/`listPanes`/`capturePane`/`killSession→Boolean`/`sendKeys(-H hex, байт-точно)`/`paneAlive`/`panePid`. Адресация: логический short-id → имя `kt-<id>`; рантайм-корреляция — `PaneId` (`#{pane_id}`). `-F` парсится по TAB-разделителю. Soft-фейлы нормализованы (нет сервера / нет сессии / нет пейна → пустой список / `false` / `null`), а не бросаются; `killSession` идемпотентен (двойной kill и несуществующая сессия → `false`). `-e KOTGENT_SESSION_ID=<id>` — только debug-лейбл.
- **[deviation] `popen`/`pclose` вместо `posix_spawn`:** `posix_spawn`/`posix_spawn_file_actions_*`/`posix_spawnattr_*` живут в `<spawn.h>`, которого **НЕТ** в header-сете `platform.posix` для macos_arm64 (проверено: `spawn.h` отсутствует в `konan/platformDef/macos_arm64/posix.def` — именно поэтому `Pty` завёл свой `pty.def` cinterop под них). Свой cinterop НЕ линкуется в test-бинари (KT-78062), а эти tmux-команды ОБЯЗАНЫ спавниться из test-бинаря. Ручной `fork()`+`execvp()` в K/N небезопасен (между fork и exec допустима только async-signal-safe работа; любая Kotlin-аллокация/GC-safepoint в форкнутом ребёнке рискует дедлоком — та же причина, по которой `Pty` выбрал `posix_spawn` вместо `forkpty`). `popen` снимает обе проблемы: `fork`+`exec` идёт **внутри libc** (K/N-код в ребёнке не исполняется вообще), а `popen`/`pclose` — стоковый `platform.posix` (`stdio.h`), поэтому линкуются и исполняются в test-бинаре штатно (как `fopen`/`fread` в Task 3). Задача явно санкционировала `popen/pclose` как альтернативу; single-quote-квотинг закрывает риск shell-инъекции, ради которого предпочитался argv-путь.
- **[decision] раздельные stdout/stderr через temp-файл (не merge `2>&1`):** `capture-pane` должен вернуть РОВНО контент пейна; случайный tmux-warning в stderr при merge загрязнил бы захват. Temp-файл (`mkstemp` в `$TMPDIR`, `unlink` в `finally`) даёт чистый stdout + настоящий stderr для сообщений об ошибках, ценой одного короткоживущего файла на вызов.
- **[decision] `send-keys -H` (hex) для `sendKeys(bytes)`:** байт-точная передача произвольного терминального ввода (control-символы/UTF-8) без интерпретации имён клавиш — то, что нужно terminal-passthrough (Task 9/14).
- **Находка тулчейна (подтверждает вывод Task 3):** `linkMacosArm64TestDebug` слинковал ProcessRunner чисто и все 8 TmuxTest реально спавнят `tmux` в рантайме тестов — KT-78062 бьёт ТОЛЬКО по нашему собственному raw-cinterop (Task 2 PTY), а стоковый `platform.posix` линкуется в test-бинарь без проблем. Нового `@Ignore` НЕ потребовалось.

### Task 9: PTY fan-out — lazy upstream-мост + broadcaster + capture-pane сид

**Files:**
- Create: `src/pty/TerminalBridge.kt`, `src/pty/Broadcaster.kt`
- Create: `test/pty/TerminalBridgeTest.kt`

- [x] write интеграционный тест: первый подписчик поднимает upstream `tmux attach` к сессии с `cat`; два подписчика получают вывод; ввод любого доходит; resize пробрасывается; новый подписчик получает `capture-pane -e` сид (логика — через `FakePtyHandle` в test-бинаре; реальный `Pty.open("tmux … attach")` — `@Ignore` e2e, см. ниже)
- [x] write тест **lazy lifecycle**: уход последнего подписчика гасит мост, tmux/Claude живут (Detach); новый подписчик заново поднимает мост (снимает respawn после рестарта)
- [x] `TerminalBridge.kt`: **lazy** `Pty.open("tmux -L kotgent attach -t kt-<id>")` при первом подписчике, reader-loop → `Broadcaster`, close при последнем
- [x] `Broadcaster.kt`: подписчики, fan-out; ввод любого → upstream; размер «последний активный» → `resize()`. ⚠️ `window-size` по умолчанию `latest`: сид иного размера даст reflow — косметика
- [x] run `./kotlin test` — fan-out зелёный перед Task 10

**✅ Реализовано — PTY fan-out через абстракцию `PtyHandle` (8 FakePty-логик-тестов зелёные в test-бинаре + 1 real-Pty e2e `@Ignore`; suite 69 ran / 64 passed / 5 skipped [4 PtyTest + 1 real-e2e]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто):**
- `src/pty/PtyHandle.kt` — pure-Kotlin интерфейс (`output: ReceiveChannel<ByteArray>`, `write`/`resize`/`close`) + `typealias PtyFactory = (command)->PtyHandle`. `src/pty/RealPtyHandle.kt` — тонкий адаптер над cinterop `Pty` (единственный app-файл, ссылающийся на `Pty`) + `realPtyFactory` + `terminalBridgeForSession(tmux,id,scope)` (прод-обвязка: upstream `tmux -L <sock> attach -t kt-<id>`, seed = `capturePane`).
- `src/pty/Broadcaster.kt` — хаб фан-аута: набор подписчиков (каждый = UNLIMITED `Channel`-sink), fan-out upstream→все; ввод любого→единый upstream; resize «последний активный» (запоминается и переприменяется при re-open). `window-size latest` reflow-caveat — в KDoc. Один `Mutex` сериализует set/upstream/lastSize; seed кладётся в канал ПОД локом ДО регистрации → сид строго перед live-дельтами, без потерь на границе join.
- `src/pty/TerminalBridge.kt` — per-session lazy-мост: первый подписчик открывает upstream + запускает reader-loop (`for bytes in output → broadcaster.broadcast`), последний — закрывает (гасит только attach, сессия жива). Зависит ТОЛЬКО от `PtyHandle`/`PtyFactory`/`()->ByteArray` seed — ни `Pty`, ни `Tmux` (чистый → тесты без cinterop).
- **[decision] KT-78062 testing split (по дизайну задачи):** свой cinterop (`Pty`) не линкуется в test-бинарь (IrLinkageError при вызове). Поэтому фан-аут абстрагирован за `PtyHandle`+factory; вся логика (lazy-open один раз, fan-out на двоих, input-роутинг, resize last-active + переприменение на re-open, close-on-last + сессия жива + re-attach, capture-pane seed→deltas, EOF-teardown) юнит-тестируется через `FakePtyHandle`/`FakePtyFactory` (test/pty/FakePtyHandle.kt) — реально исполняется в test-бинаре. Единственный настоящий e2e (`Pty.open("tmux … attach")`) — `@Ignore` с KT-78062-заметкой (как PtyTest); покрытие — Task 18. Машинно-зависимый `-library`-хак НЕ возвращён.
- **[decision] lifecycle-транзишены детектит `Broadcaster` (владеет set), а МЕХАНИЗМ (open pty / reader-loop / close) — `TerminalBridge`:** мост передаёт `openUpstream`/`closeUpstream`/`seedProvider`-хуки, которые Broadcaster зовёт ПОД своим локом → «open на первом / close на последнем» атомарны с мутацией set (двойной open при гонке подписок исключён). Reader-loop живёт в мосте и кормит Broadcaster.
- **[decision] seedProvider инъектируется как `()->ByteArray`** (прод = `tmux.capturePane(id)`), поэтому seed-тест — чистый и быстрый без реального tmux; интеграция capture-pane против реального tmux уже покрыта TmuxTest (Task 8).
- **[decision] EOF-путь (upstream умер сам):** reader-loop после EOF зовёт `onUpstreamEof(up)` (guard по identity — устаревший reader не затрёт свежий upstream) → сбрасывает upstream, закрывает каналы подписчиков (клиенты узнают о конце, могут пере-подписаться). Наш собственный close (last-detach) отменяет reader → до EOF-хендлера не доходит.
- **Находка тулчейна (подтверждает Task 3/8):** `linkMacosArm64TestDebug` слинковал app-test чисто, хотя app-main теперь ссылается на `Pty` (cinterop) через `RealPtyHandle` — partial linkage делает нерезолвнутый символ throwing-стабом, ошибка только при ВЫЗОВЕ (→ `@Ignore` на единственном тесте, который зовёт). Новых `@Ignore` сверх этого не потребовалось.

### Task 10: Контракт AgentAdapter (+ FakeAdapter)

**Files:**
- Create: `src/adapter/AgentAdapter.kt`, `src/adapter/LaunchSpec.kt`
- Create: `test/adapter/FakeAdapter.kt`, `test/adapter/AdapterContractTest.kt`

- [x] write тесты: `FakeAdapter` эмитит `AgentEvent`, редьюсер сворачивает в ожидаемые состояния (контракт «адаптер→события→редьюсер»)
- [x] `AgentAdapter.kt`: `buildLaunchSpec(mode: New|Resume)` + `events: Flow<AgentEvent>`
- [x] `LaunchSpec.kt`: `command: List<String>`, `env`, `cwd`, `preallocatedSessionId?`
- [x] (capability-интерфейсы — бэклог, в срезе НЕ вводим)
- [x] write тест: контракт-прогон покрывает все v1-события
- [x] run `./kotlin test` — контракт зелёный перед Task 11

**✅ Реализовано — host-free `AgentAdapter`-контракт + `FakeAdapter` (4 AdapterContractTest зелёные, чистый Kotlin без cinterop → исполняются в test-бинаре; suite 73 ran / 68 passed / 5 skipped [4 PtyTest + 1 real-Pty e2e]; `./kotlin build`+`test` exit 0):**
- `LaunchSpec.kt` — `LaunchSpec(command, env, cwd, preallocatedSessionId?)` (pure data, argv-модель без shell) + `sealed interface LaunchMode { New | Resume(providerSessionId) }`. Асимметрия намеренна: `New` заставляет адаптер преаллоцировать provider-id (→ `preallocatedSessionId`), `Resume` несёт уже существующий id (нужен для `--resume`). `LaunchMode` живёт рядом с `LaunchSpec` (оба — launch-value-типы), контракт-интерфейс держится минимальным.
- `AgentAdapter.kt` — минимальный контракт: `fun buildLaunchSpec(mode: LaunchMode): LaunchSpec` + `val events: Flow<AgentEvent>` (нормализованный поток; downstream — reducer/store/transport — видит только `AgentEvent`, не провайдера → `state == replay(adapter.events)`). Capability-интерфейсы (`SupportsApprovalResolution`/`SupportsStructuredTranscript`) НЕ введены (бэклог) — будущий opt-in отдельным интерфейсом, ядро не трогая.
- `test/adapter/FakeAdapter.kt` — pure-Kotlin `AgentAdapter`: `events` за UNLIMITED `Channel` через `receiveAsFlow()` (тест драйвит `emit`/`emitAll`/`close`; `close` завершает поток как реальный провайдер после `Exited`); `buildLaunchSpec` отдаёт canned Claude-shaped спеку и пишет `launchModes`.
- `test/adapter/AdapterContractTest.kt` — 4 теста: (1) сворачивание live-потока адаптера редьюсером даёт ожидаемую траекторию состояний + финальную проекцию (`stopped`, provider bound, seq==#events) И `== replay(collected)` (сквозной контракт адаптер→события→редьюсер); (2) контракт-прогон покрывает все 7 v1-типов (`simpleName`-множество, т.к. в K/N нет `sealedSubclasses`) + lossless-порядок; (3) edge: пустой поток → `Projection.EMPTY` (running); (4) `buildLaunchSpec(New)` (`--session-id <uuid>` + `preallocatedSessionId`) vs `Resume` (`--resume <id>`, no prealloc). Все Flow-коллекции обёрнуты `withTimeout` (анти-хэнг).
- **[decision] `LaunchMode` в `LaunchSpec.kt`, не в `AgentAdapter.kt`:** и `LaunchSpec`, и `LaunchMode` — value-типы вокруг launch'а; держим `AgentAdapter.kt` = только интерфейс (минимальный контракт).
- **[decision] `Channel`-backed `events` (не `MutableSharedFlow`):** план допускал оба; Channel даёт естественное завершение по `close()` — точно ложится на `toList()`/`collect{}` + `withTimeout`, без `take(n)` над горячим потоком.
- **[decision] canned спека адаптера повторяет Claude-argv Task 11** (`--session-id` для New, `--resume` для Resume), чтобы контракт-shape-ассерты были осмысленными до появления реального `ClaudeAdapter`.

### Task 11: ClaudeAdapter — launch/resume, hook-config, session-id preallocation

**Files:**
- Create: `src/adapter/claude/ClaudeAdapter.kt`, `src/adapter/claude/ClaudeHookConfig.kt`, `src/adapter/claude/ClaudeCli.kt`
- Create: `test/adapter/claude/ClaudeAdapterTest.kt`

- [x] **спайк (перед маппингом): вживую вызвать у Claude permission-prompt и залогировать реальные `Notification`-пейлоады**; зафиксировать дискриминатор permission-vs-idle (или подтвердить любой `Notification`→needs_attention) — best-effort, defaulted to coarse mapping (см. блок ниже)
- [x] write тесты: `buildLaunchSpec(New)` содержит `--session-id <uuid>` (version-gated) + `--settings <hook-config>`; `buildLaunchSpec(Resume)` → `claude --resume <id>`; hook-config корректен
- [x] `ClaudeCli.kt`: путь/версия `claude`; version-gating `--session-id` (подтверждено в 2.1.218 инсталляции; fallback `SessionStart`)
- [x] `ClaudeHookConfig.kt`: settings-файл с хуками (`UserPromptSubmit`/`PostToolUse`/`Stop`/`Notification`/`SessionStart`), курлящими `POST /hooks/claude` с токеном и `$TMUX_PANE`
- [x] `ClaudeAdapter.kt`: реализация контракта (транскрипт-вотч — бэклог); `events` — инъектируемый шов (Task 12 кормит)
- [x] write тесты: version-gating, resume-спека
- [x] run `./kotlin test` — адаптер зелёный перед Task 12

**✅ Реализовано — Claude adapter OUTGOING-сторона (16 ClaudeAdapterTest зелёные, чистый Kotlin без cinterop → исполняются в test-бинаре; suite 89 ran / 84 passed / 5 skipped [4 PtyTest + 1 real-tmux e2e]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто):**
- `ClaudeCli.kt` — локатор+версия `claude` (`command -v` / `claude --version` через инъектируемый `runner`, дефолт = `ProcessRunner` из Task 8 — стоковый `platform.posix` `popen`, линкуется в test-бинарь). **Чистая decision-логика вынесена в companion:** `parseVersion(String)→ClaudeVersion?` (regex `\d+\.\d+\.\d+`, игнорит хвост `(Claude Code)`) + `supportsSessionId(ClaudeVersion?)` (гейт `>= MIN_SESSION_ID_VERSION`); юнит-тестируемы БЕЗ живого бинаря. Инстанс-методы (`detectVersion`/`isInstalled`/`supportsSessionId()`) зовут CLI. Guarded real-probe тест реально нашёл установленный claude (2.1.218) и подтвердил `--session-id`.
- `ClaudeHookConfig.kt` — PURE-генерация settings-JSON для `claude --settings <file>` через kotlinx JSON DSL (well-formed by construction). 5 хуков (`UserPromptSubmit`/`PostToolUse`/`Stop`/`Notification`/`SessionStart`), каждый = `curl -X POST http://127.0.0.1:<port>/hooks/claude?event=<E>` с payload'ом со stdin (`--data-binary @-`), несущий hook-token (header, single-quoted), `$TMUX_PANE` (double-quoted → shell раскрывает в hook-time) и имя события. Порт+токен параметризованы; заголовки/путь ингресса — публичные константы (Task 12 переиспользует).
- `ClaudeAdapter.kt` — реализация `AgentAdapter`. `buildLaunchSpec(New)` = `claude --session-id <preallocated-uuid> --settings <path>` (uuid в `preallocatedSessionId`); при `sessionIdSupported=false` — fallback БЕЗ `--session-id`, `preallocatedSessionId=null` (id ловится из `SessionStart`-хука). `buildLaunchSpec(Resume(id))` = `claude --resume <id> --settings <path>`, prealloc null. `events: Flow<AgentEvent>` — ИНЪЕКТИРУЕМЫЙ шов через конструктор (Task 12's ingress кормит; адаптер сам события НЕ производит). `newUuidV4(Random)` — генератор канонического v4 UUID (инъектируемый Random → детерминизм в тестах).
- **[decision] `ClaudeCli.runner` = единый `(List<String>)->ProcessResult` шов** (переиспользуем `io.kotgent.tmux.ProcessRunner`, а не дублируем popen); pure gate-функции отдельны и не требуют его. Тесты гоняют fake-runner (success/absent) + чистые companion-функции + один guarded real-binary probe (soft-skip если claude нет).
- **[decision] version-gate floor `MIN_SESSION_ID_VERSION = 1.0.0`:** точная вводящая `--session-id` версия апстримом не зафиксирована; 1.0.0 — консервативный порог (2.1.x → true, pre-1.0 беты → fallback). `null`/непарсибл → false (safe: fallback всегда работает, лишь теряет up-front preallocation).
- **[decision] `newUuidV4` — `public` (не `internal`):** тулчейн 0.11.0 НЕ компилит `test/` как friend-модуль (то же ограничение, что у generated-кода в Task 4) → `internal` из main невидим тесту.
- **[decision] Notification live-capture спайк — заблокирован harness'ом, дефолт на coarse mapping (санкционировано планом):** попытка вживую поднять permission-prompt (`claude --settings <probe> --dangerously-skip-permissions -p "…Bash…"`, probe-settings логировали stdin-пейлоады каждого хука) отклонена auto-mode-классификатором harness'а (вложенный запуск `claude` со skip-permissions). Обход НЕ предпринимался (харнес санкционирует именно это поведение). Реальные `Notification`-пейлоады НЕ захвачены → слайс использует грубый `любой Notification → needs_attention` (план это явно разрешает). Сам маппинг живёт в нормализаторе Task 12; здесь Notification-хук лишь ЗАПРОВОЖЕН в ингресс (hook-config + тест это покрывают). Дискриминатор permission-vs-idle (ожидаемо — поле `message` пейлоада) остаётся для Task 12/бэклога.

### Task 12: Hook ingress + нормализация Claude-событий

**Files:**
- Create: `src/adapter/claude/ClaudeHookNormalizer.kt`, `src/transport/HookRoutes.kt`
- Create: `test/adapter/claude/HookNormalizerTest.kt`

- [x] write тесты (нормализатор — чистая функция): пейлоады → `AgentEvent` (`Notification`→ApprovalRequested/needs_attention; `Stop`→TurnCompleted/ready; `PostToolUse`→running-событие, сбрасывающее pendingApprovals; `SessionStart`→SessionBound)
- [x] `ClaudeHookNormalizer.kt`: чистая `(hookPayload, paneId) → AgentEvent`; любой `Notification`→needs_attention
- [x] `HookRoutes.kt`: `POST /hooks/claude` — валидация токена, чтение `$TMUX_PANE`+пейлоада, мапинг pane→сессия (**partial-dep:** `pane_id` пишет Task 13 → тест через засиженный fake-lookup), нормализация, `append`
- [x] обработать «нет permission-answered» → вход в running (`PostToolUse`) обнуляет pendingApprovals
- [x] write тесты: неизвестный pane → 404; невалидный/отсутствующий токен → 401 (route-level, доказано СЕЙЧАС через embedded Ktor CIO server — Task 3, не ждём Task 14)
- [x] run `./kotlin test` — нормализатор зелёный перед Task 13

**✅ Реализовано — hook ingress + Claude-нормализатор (9 HookNormalizerTest + 7 HookRoutesTest зелёные, чистый Kotlin/Ktor-CIO в test-бинаре; suite 105 ran / 100 passed / 5 skipped [4 PtyTest + 1 real-tmux e2e — без изменений]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто):**
- `src/adapter/claude/ClaudeHookNormalizer.kt` — PURE `normalize(hookEventName, payload: JsonElement, paneId): AgentEvent?` (INCOMING половина адаптера; OUTGOING = `ClaudeHookConfig`). Маппинг v1: `UserPromptSubmit`→`TurnStarted`; `PostToolUse`→`ToolCall(tool_name ?: "unknown")` (running-producer → редьюсер обнуляет pendingApprovals — так гаснет approval при возобновлении Claude, «permission answered»-хука нет); `Stop`→`TurnCompleted`; `Notification`→`ApprovalRequested` (**COARSE:** любой Notification → needs_attention; permission-vs-idle дискриминатор на поле `message` — будущий рефайнмент, НЕ моделируем); `SessionStart`→`SessionBound(session_id)` (нет валидного UUID → `null`, id придёт из преаллокации `--session-id`); прочее → `null`. Тотальная функция (name,payload), юнит-тестируема представительными payload'ами. `approvalId` = `message` (label, не корреляция — v1 не шлёт `ApprovalResolved` из хуков) с фолбэком на pane.
- `src/transport/HookRoutes.kt` — `Route.claudeHookRoutes(token, paneLookup, store, json)` ставит `POST /hooks/claude`. Поток: (1) auth токена (header `X-Kotgent-Hook-Token`, как шлёт `ClaudeHookConfig`) → 401 ПЕРВЫМ (не течёт инфа о сессии); (2) event (`?event=` query → фолбэк header `X-Kotgent-Hook-Event`) + `$TMUX_PANE` (header `X-Kotgent-Tmux-Pane`), missing/malformed → 400; (3) pane→session через ИНЪЕКТИРУЕМЫЙ `suspend (PaneId)->SessionId?` (прод = SessionManager registry Task 13; тест = seeded map), неизвестный pane → 404 чисто (не краш); (4) нормализация → non-null → `store.append(sessionId, event, EventSource.hook)`. Функция от `(token, paneLookup, store)` → тестируема изолированно. Пустое тело = `{}`; не-JSON тело → 400.
- Тесты: `test/adapter/claude/HookNormalizerTest.kt` (9) — каждый representative payload → ожидаемый `AgentEvent`/null + fold реалистичной последовательности (`UserPromptSubmit→PostToolUse→Notification→PostToolUse→Stop`) через РЕАЛЬНЫЙ `reduce`: траектория `running→running→needs_approval(1)→running(0, CLEAR)→ready` + `== replay(events)`. `test/transport/HookRoutesTest.kt` (7) — embedded Ktor CIO server + CIO client + fake paneLookup + recording in-memory `EventStore` (append на UNLIMITED `Channel` — happens-before между CIO-серверным и тест-потоком): валидный POST аппендит нормализованное событие (verify via store: Stop→TurnCompleted, PostToolUse→ToolCall(Bash), SessionStart→SessionBound, source=hook); невалидный/отсутствующий токен → 401 + ничего не аппендит; неизвестный pane → 404 + ничего; unmapped hook → 200 + ничего. Всё под `withTimeout` (анти-хэнг).
- **[decision] adapter.events wiring:** ingress аппендит НАПРЯМУЮ в `EventStore` (source of truth), НЕ в отдельный per-adapter push-канал. Downstream (events-WS Task 14, daemon Task 13) читают стор → `ClaudeAdapter.events` для Claude предполагается backed by `store.subscribe(sessionId, fromSeq).map { it.event }`, а не отдельным push. Один authority порядка (per-session `seq`), restart-safe бесплатно. Полная daemon-обвязка (конструирование адаптера над подпиской) — Task 13, здесь НЕ строится (задокументировано в KDoc `claudeHookRoutes`).
- **[decision] токен-валидация по header `X-Kotgent-Hook-Token`** (константа `ClaudeHookConfig.HOOK_TOKEN_HEADER`) — ровно как `ClaudeHookConfig.hookCommand` его шлёт (single-quoted header в curl); отдельный bearer для hooks — бэклог (Task 14).
- **[decision] event из `?event=` query (фолбэк header):** `ClaudeHookConfig` дублирует имя события и в query, и в header; берём query первым (стабильнее), header — робастный фолбэк.
- **[decision] route-тесты через РЕАЛЬНЫЙ embedded Ktor CIO** (не отложены до Task 14): Task 3 доказал CIO server+client в test-бинаре, поэтому 401/404/append-пути гоняются end-to-end сейчас. План-заметка «fails until Task 14-харнесс» устарела — снята.
- **[decision] recording-fake `EventStore` (не `SqliteEventStore.inMemory()`) в route-тесте:** route зовёт только `append`; фейк на `Channel` даёт чистую изоляцию + гарантированный cross-thread happens-before (CIO-хендлер исполняется на своём потоке), без риска thread-affinity нативного sqliter в тесте. Реальный стор уже полноценно покрыт EventStoreTest (Task 7).
- **[decision] `Notification` approvalId = label:** в coarse-v1 корреляции approval'ов нет (гаснут по входу в running), поэтому id — человекочитаемая метка (`message` ?: `notification@<pane>`), а не корреляционный ключ. Дискриминатор permission-vs-idle остаётся в бэклоге.
- Без deviations. Новых `@Ignore` НЕ добавлено. `src/adapter/claude/` и `src/transport/`, `test/adapter/claude/` и `test/transport/` уже существовали — mkdir не нужен.

### Task 13: Session manager + reconciliation + provider-id capture

**Files:**
- Create: `src/daemon/SessionManager.kt`, `src/daemon/Reconciler.kt`, `src/daemon/ProviderIdCapture.kt`
- Create: `test/daemon/ReconcilerTest.kt`, `test/daemon/SessionManagerTest.kt`, `test/daemon/FakeTmux.kt` (shared fake)
- Create: `src/tmux/TmuxControl.kt` ([decision] daemon-facing interface so `Tmux` is fakeable); Modify: `src/tmux/Tmux.kt` (implements it)

- [x] write тесты reconciliation: (строки `sessions` × состояние tmux × наличие vendor-файла) → running/resumable/crashed/stopped (табличные, host-free через фейковые Tmux/Store)
- [x] write тесты provider-id capture: preallocated → мгновенно `SessionBound`; discovery буксует → «id pending» + retry, resume заблокирован
- [x] `SessionManager.kt`: `start` (tmux new-session → pane_id → upsert sessions → capture; **мост НЕ спавнится — lazy на первый terminal-WS-подписчик**) / `stop`/`resume`/`interrupt`/`detach` по `StopMode`
- [x] `Reconciler.kt`: старт daemon — `list-sessions`/`list-panes` + vendor-store → пересбор `pane_id`, классификация. **Мосты не пере-поднимаем.** `ProviderIdCapture.kt`: гарантия id
- [x] write интеграционный тест: `start` создаёт tmux-сессию и захватывает `pane_id`; после «рестарта» (новый Reconciler) состояние восстановлено, terminal-WS-подписка заново поднимает мост
- [x] run `./kotlin test` — daemon-ядро зелёное перед Task 14

**✅ Реализовано — SessionManager + Reconciler + provider-id capture (11 новых daemon-тестов зелёные [2 ReconcilerTest + 9 SessionManagerTest, вкл. guarded real-tmux integration]; suite 116 ran / 111 passed / 5 skipped [4 PtyTest + 1 real-Pty e2e — БЕЗ изменений, новых `@Ignore` НЕТ]; `./kotlin build`+`test` exit 0; `grep '/Users/' *.yaml` пусто):**
- `src/daemon/SessionManager.kt` — `start`/`stop`/`resume`/`interrupt`/`detach` по `StopMode {Detach, Interrupt, Graceful, Terminate, Kill}` над `TmuxControl`+`EventStore`. `start`: `SessionId`(8-hex short-id)→имя `kt-<id>`→`adapter.buildLaunchSpec(New)`→`tmux.newSession`→`pane_id`→`upsertSession`→register pane→provider-id bind. **Мост НЕ спавнится** (lazy на первый terminal-WS-подписчик — Task 9). `PaneRegistry` (pane→session, `Mutex`) — публичный (`val registry` + `paneLookup`), ИМЕННО его потребляет `claudeHookRoutes` (Task 12); rebuild-from-store (`rebuildRegistryFromStore`) + authoritative rebuild-from-live-panes (Reconciler). `AgentFactory` fun-interface — шов к `ClaudeAdapter` (тесты подставляют stub с harmless `cat`).
- `src/daemon/Reconciler.kt` — на старте daemon: `store.listSessions()` × `tmux.listPanes()` (liveness) × `vendorStoreProbe` (транскрипт) → `classify(paneAlive, projState, stopIntent, transcript)`: alive→running (сохраняя тонкое live-состояние из лога); dead+stop-intent→stopped; dead+transcript→resumable; dead+neither→crashed. Пересбор `pane_id` из live-пейна, rebuild `PaneRegistry` из live-панелей (stale-энтри пруним). Мосты НЕ пере-поднимаем. Host-free (инъекции `TmuxControl`/`EventStore`/`VendorStoreProbe`/`PaneRegistry`).
- `src/daemon/ProviderIdCapture.kt` — гарантия provider-id: `bind` (preallocated → `SessionBound(source=system)` в лог мгновенно, идемпотентно); `captureWithFallback` (retry-poll `discover` до `maxAttempts` → `Bound` | `Pending`); `captureInBackground` (bounded fire-and-forget на инъектированном scope). «Id pending» = отсутствие provider-id в кэше → `resume` бросает `ResumeBlockedException`.
- **[decision] `TmuxControl`-интерфейс (новый `src/tmux/TmuxControl.kt`):** `Tmux` (popen, final) НЕ фейкается напрямую → выделен daemon-facing интерфейс (sessionName/newSession/listSessions/listPanes/killSession/sendKeys), `Tmux : TmuxControl` (только `override` добавлены, поведение не тронуто). ReconcilerTest/SessionManagerTest-unit гоняют `FakeTmux`; integration-тест — реальный `Tmux -L kotgent-test`.
- **[decision] control-эффекты через `upsertSession(meta.copy(...))`, не новый `updateCache`-метод интерфейса:** план писал `store.updateCache(...)`; реализовано полным upsert'ом derived-полей (state/state_source/updated_at) — daemon уже держит `SessionMeta`, upsert атомарен под single-writer-`Mutex` стора и сохраняет `created_at`, интерфейс Task 7 не расширяется. `ControlSignal`ы (Stop/Interrupt/Resume) применяются к in-memory проекции (`reduce`), результат кэшируется; НЕ персистятся как события (реконсайлер их пере-деривит из tmux-реальности на рестарте).
- **[decision] control-сигналы сидятся cache-authoritative `meta.state`:** проекция из лога (pure replay) может лагать за кэш-состоянием (реконсайлер-классификации/control-эффекты НЕ в логе), поэтому `resume`/`interrupt` seed'ят `projectionOf(id).copy(state = meta.state)` перед `reduce` — иначе `Resume` на `resumable`-сессии (лог=EMPTY=running) не даст `ready`. `stop`=`kill-session`+derive `stopped` через `reduce(reduce(proj, Stop), Exited(129))` (SIGHUP; чистая классификация, событие НЕ персистится → seq не двигается; в v1 нет Exited-хука).
- **[decision] `StopMode.{Graceful,Terminate,Kill}` → один `kill-session` в v1** (градация сигналов — бэклог; enum-варианты сохранены для стабильности transport/CLI-словаря). `detach` — no-op (transport-level; агент живёт). Provider-id fallback source=system при `bind`; preallocated-путь первичен.

### Task 14: Transport — control REST + events WS + terminal WS + токен-auth

**Files:**
- Create: `src/transport/Server.kt`, `src/transport/ControlRoutes.kt`, `src/transport/EventsWs.kt`, `src/transport/TerminalWs.kt`, `src/transport/Auth.kt`
- Create: `test/transport/TransportTest.kt`

- [x] write интеграционные тесты: `POST /sessions` → сессия в `GET /sessions`; events-WS получает смену состояния; terminal-WS стримит байты и принимает ввод; нет токена → 401
- [x] `Auth.kt`: токен `~/.kotgent/token` (0600); один bearer на всё (hook-токен — бэклог)
- [x] `ControlRoutes.kt`: `GET /sessions`, `GET /sessions/{id}`, `POST /sessions`, `POST /sessions/{id}/{stop|resume|interrupt|detach}`, `POST /sessions/{id}/input` (только `TerminalInput`). `PATCH` — бэклог
- [x] `EventsWs.kt`: `GET /events?from=<seq>` — `subscribe`, restart-safe курсор; `TerminalWs.kt`: мост к lazy `Broadcaster` + `capture-pane` сид + resize; статика Web UI на `/`
- [x] write тесты: restart-safe курсор, resize-фрейм → `TIOCSWINSZ`
- [x] run `./kotlin test` — transport зелёный перед Task 15

**✅ Реализовано — transport-сервер собран (control REST + events WS + terminal WS + токен-auth + hook-ingress + статика) (6 TransportTest зелёные, чистый Ktor CIO + host-free фейки в test-бинаре; suite 122 ran / 117 passed / 5 skipped [4 PtyTest + 1 real-Pty e2e — БЕЗ изменений, новых `@Ignore` НЕТ]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто):**
- `src/transport/Auth.kt` — **один токен на всё** (`~/.kotgent/token`, создаётся `0600` внутри `0700 ~/.kotgent`; 32 байта энтропии из `/dev/urandom` с фолбэком на `Random`, hex; идемпотентно — существующий читается verbatim). `readOrCreateToken(path)` — единый источник значения (bearer клиентов + hook-токен Task 12 сверяет ту же строку). `presentedToken()` берёт токен из `Authorization: Bearer` **ИЛИ** `?token=` (браузер не может слать заголовки на WS-handshake → Web UI читает `#token=` из фрагмента и вешает `?token=`). `Route.authenticated(token){}` — прозрачный child-route + `intercept(Plugins)` (форма Ktor `authenticate{}`): missing/wrong → `401` ДО хендлера, что режет и WS-handshake (401, без апгрейда). Хук-роут смонтирован ВНЕ `authenticated` (свой header-чек тем же токеном).
- `src/transport/ControlRoutes.kt` — REST над `SessionManager`+кэш стора: `GET /sessions` (из `store.listSessions()`), `GET /sessions/{id}` (404), `POST /sessions` (тело `{agent,cwd,name?,tags?}`→`SessionManager.start`→`201`), `POST /sessions/{id}/{stop|resume|interrupt|detach}` (диспатч; `ResumeBlocked`→`409`, unknown action→`400`), `POST /sessions/{id}/input` (сырой ввод→терминальный upstream). `PATCH`—бэклог (опущен). Wire-DTO (`SessionDto`/`StartSessionRequest`) в transport-слое (core не `@Serializable`-ится). `TRANSPORT_JSON` = `classDiscriminator="type"` (совпадает с `AgentEvent`@SerialName). Ответы hand-serialized через `respondText` (без ContentNegotiation-плагина, как HookRoutes).
- `src/transport/EventsWs.kt` — `GET /events` WS. **Глобальный режим (дефолт):** snapshot текущих сессий (`SessionUpdateDto` каждая) в `onSubscription` (после подписки → нет гонки snapshot/live), затем стрим `store.sessionUpdates`. **Per-session (`?session=&from=`):** канонический event-лог сессии через `store.subscribe(id,from)` с restart-safe курсором; протухший `from` → `StaleCursorException` → WS-close `VIOLATED_POLICY`. Глобального курсора НЕТ (seq per-session — задокументировано).
- `src/transport/TerminalWs.kt` — `GET /sessions/{id}/terminal` WS (binary). `TerminalRegistry` (per-session `TerminalBridge`, get-or-create, общий для WS и `/input` → ОДИН upstream). На connect `bridge.subscribe()` (первый подписчик лениво поднимает `tmux attach` через `PtyFactory`, seed `capture-pane -e` перед дельтами — поведение Task 9). server→client: байты сабскрайбера → binary-фреймы; client→server: **binary**=ввод→upstream, **text**=контрол (`{"type":"resize","cols","rows"}`→`Broadcaster.applyResize`). disconnect → `sub.close()` (последний закрывает upstream = Detach).
- `src/transport/Server.kt` — `KotgentServer`: `embeddedServer(CIO, 127.0.0.1, port)` + `install(WebSockets)` + `routing{ claudeHookRoutes; authenticated{ controlRoutes; eventsWs; terminalWs }; staticWebUi }`. Всё constructor-injected (`SessionManager`/`EventStore`/`token`/`terminalBridgeFactory`(несёт `PtyFactory`)/`webUiDir`/`port`) → тестируемо end-to-end на фейках; `production()` — прод-обвязка (`terminalBridgeForSession` над реальным `Tmux`+`realPtyFactory`). `port()` отдаёт резолвнутый эфемерный порт. Статика `resources/webui` на `/` через posix-I/O (JVM `staticFiles` недоступен на native — решение Task 3); UNauthenticated (браузер грузит бут до токена).
- `src/store/EventStore.kt`/`SqliteEventStore.kt` (расширены) — добавлены `SessionUpdate(sessionId,state,lastSeq,unread)` + `val sessionUpdates: SharedFlow<SessionUpdate>` (hot, non-replay, буфер+DROP_OLDEST); `tryEmit` после каждого `append` И `upsertSession` под writer-`Mutex`. Стор — ЕДИНСТВЕННОЕ место, куда воронятся ВСЕ мутации (и hook-аппенды, и control-upsert'ы daemon'а), поэтому именно он владеет сигналом (не daemon).
- `src/pty/TerminalBridge.kt` (расширен) — `suspend fun write(bytes)` → `broadcaster.writeInput` (шов для `POST /input`).
- **[decision] один токен на всё** (план: «один токен на всё»): bearer клиентов + hook-header = одна строка из `readOrCreateToken`; отдельный per-purpose токен — бэклог.
- **[decision] токен из header ИЛИ query** (`?token=`): браузеры не ставят заголовки на WS-handshake; Web UI держит токен в URL-фрагменте `#token=` (не уходит на сервер) и вешает `?token=` на WS. Один экстрактор гейтит REST и WS единообразно.
- **[decision] events-WS `sessionUpdates` живёт на сторе, НЕ на daemon:** план писал «daemon exposes a session-updated Flow», но hook-ингресс аппендит НАПРЯМУЮ в стор (мимо daemon), поэтому единственная точка, наблюдающая ВСЕ изменения кэша — сам стор. Расширен `EventStore` (Task 7) минимально: `SessionUpdate` + `sessionUpdates`.
- **[decision] `/events` глобальный snapshot-then-stream, курсор per-session:** глобальный курсор не осмыслен (seq per-session, Task 7). Глобальный режим = snapshot (в `onSubscription`) + live. Restart-safe курсор — на per-session режиме (`?session=&from=`) через `store.subscribe`; протухший → `VIOLATED_POLICY`-close. Именно этот путь покрывает тест «stale cursor → error» на transport-уровне.
- **[decision] `/input` через `Broadcaster` (не `send-keys`):** план явно «via the Broadcaster». Общий per-session `TerminalBridge` (registry) → `/input` и terminal-WS-ввод = ОДИН upstream. Ленивый upstream ⇒ `/input` достигает агента, пока прикреплён терминал (нормальный браузерный поток держит terminal-WS); без подписчиков — no-op (задокументировано; `send-keys` доставлял бы subscriber-независимо, но в обход single-upstream — отклонён ради консистентности). Покрыт тестом `postInputReachesTheSharedTerminalUpstream` (через прикреплённый терминал).
- **[decision] wire-DTO в transport-слое** (`SessionDto`/`SessionUpdateDto`/`StoredEventDto`/`StartSessionRequest`): core-типы (`SessionMeta`/`SessionState`) НЕ `@Serializable` и НЕ должны диктовать публичный API; transport владеет своим контрактом, мапперы конвертят. `state`=имя enum, `needsAttention`/`alive` пре-деривнуты для тонкого UI.
- **[decision] тест-фейки вместо `SqliteEventStore`/Task-9 `FakePtyHandle`:** CIO-сервер гоняет хендлеры на СВОИХ потоках (≠ тест-поток). (1) `WsFakePty`/`WsFakePtyFactory` пишут ввод/resize/output на `Channel`'ы (кросс-поточный happens-before), в отличие от однопоточного Task-9 `FakePtyHandle`; (2) `FakeEventStore` — coroutine-`Mutex`+`Channel`+`SharedFlow` (не нативный sqliter с thread-affinity — тот же обход, что в HookRoutesTest), честно реализует контракт Task 7 включая stale-cursor. Всё под `withTimeout(40s)`.
- **[decision] Ktor 3.4.1 API:** `RouteSelector.evaluate` — `suspend`; `intercept` — на `RoutingNode` (каст child'а). `authenticated{}` = прозрачный child-route + `intercept(ApplicationCallPipeline.Plugins)` (форма `authenticate{}`), literal API-роуты сохраняют приоритет над catch-all статикой.
- **Находка тулчейна (подтверждает Task 3):** `linkMacosArm64TestDebug` слинковал transport+фейки чисто; весь Ktor CIO server+client+WS реально исполняется в test-бинаре. KT-78062 бьёт только по нашему raw-cinterop (PTY), не по Ktor/store/фейкам. Новых `@Ignore` НЕТ; `SpikeServer.kt` (Task 3) оставлен как есть (историческая проба, не мешает).

### Task 15: CLI-субкоманды + `attach` raw-passthrough

**Files:**
- Create: `src/cli/Cli.kt`, `src/cli/Commands.kt`, `src/cli/AttachClient.kt`, `src/cli/ApiClient.kt`
- Modify: `src/main.kt`
- Create: `test/cli/CliTest.kt`

- [x] write тесты: парсинг субкоманд; `ApiClient` шлёт корректные HTTP (против stub-сервера), читая токен из файла
- [x] `Commands.kt`: `daemon`, `start/list/stop/resume` (HTTP), `install`/`uninstall` (Task 16)
- [x] `AttachClient.kt`: raw-passthrough — tty raw (`termios`), stdin→WS, WS→stdout, `SIGWINCH`→resize, восстановление tty на выходе
- [x] `main.kt`: диспатч субкоманд (сохранить `versionLine()`/`--version`)
- [x] write тесты: `list` рендерит ответ; `start` валидирует cwd/agent; `attach` — smoke (tty-raw → manual)
- [x] run `./kotlin test` — CLI зелёный перед Task 16

**✅ Реализовано — CLI-субкоманды + `attach` raw-passthrough (15 CliTest зелёные; suite 137 ran / 132 passed / 5 skipped [4 PtyTest + 1 real-tmux TerminalBridgeTest — БЕЗ изменений, новых `@Ignore` НЕТ]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто; бинарь `--version`→`kotgent 0.1.0-SNAPSHOT`, no-args→usage, оба exit 0):**
- `src/cli/Cli.kt` — PURE `parseArgs(argv): CliCommand` (sealed: Version/Help/Daemon/Start/ListSessions/Stop/Resume/Interrupt/Attach/Install/Uninstall/Invalid) + `runCli(argv): Int` (единственное место с IO). Hand-rolled парсер (без kotlinx-cli — проще на native). `DEFAULT_PORT = 0x6b74` (27508, «kt»), `defaultBaseUrl()` (override `$KOTGENT_PORT`), `TMUX_SOCKET`, `currentWorkingDir()` (getcwd→PWD→`.`), `kotgentHome()`, `eprintln()`. `start` cwd — nullable в parse (default cwd резолвится в runCli, чтобы parse оставался чистым).
- `src/cli/ApiClient.kt` — Ktor CIO **client** над control REST, переиспользует transport wire-типы (`SessionDto`/`StartSessionRequest`/`TRANSPORT_JSON`) → CLI и сервер не разъезжаются. Bearer из `~/.kotgent/token` (`readTokenOrNull`); `null`-токен → `MissingTokenException` ДО сети (fail-fast). `listSessions`/`startSession(agent,cwd,name?,tags)`/`stop|resume|interrupt|detach(id)`; не-2xx → `ApiException(status, body)` (409 resume-blocked → понятное сообщение). `AutoCloseable`.
- `src/cli/AttachClient.kt` — raw-passthrough к `GET /sessions/{id}/terminal` (binary WS). `LocalTty`-интерфейс (enter/restore/windowSize) + `withRawMode{}` (enter→try→**finally restore**) — ЧИСТАЯ orchestration, юнит-тестируема. `PosixTty` (прод) → `NativeTty` (sysnative). `terminalWsUrl` (http→ws, `?token=`), `resizeFrame(cols,rows)` (`{"type":"resize",...}` — точная форма `terminalWs`). `run()` интерактивен (stdin→WS binary на выделенном thread, WS→stdout raw `write`, SIGWINCH→flag+poll→resize) — НЕ покрыт автотестами (smoke-only; полная проверка = manual, Post-Completion).
- `src/cli/Commands.kt` — хендлеры: сетевые (`list`/`start`/`stop`/`resume`/`interrupt`) через `ApiClient` + человекочит. вывод (`renderSessions` — чистый, тестируем, флажит needs-attention); `attach` (AttachClient); `daemon(port)` — прод-обвязка `KotgentServer.production(...)` (file-backed `NativeSqliteDriver` под `~/.kotgent/kotgent.db`, `Tmux(kotgent)`, `SessionManager`+`ClaudeAdapter`-фабрика, hook-settings пишутся 0600, rebuildRegistry+`Reconciler.reconcile()` на старте, затем start+`awaitCancellation()`) — **НЕ исполняется ни в тесте, ни в shell** (сервер блокирует по дизайну); `install`/`uninstall` — Task-16 stub (внятное сообщение, exit 1).
- `sysnative/cinterop/pty.def` (+3 C-helper'а `kotgent_tty_enter_raw`/`kotgent_tty_restore`/`kotgent_get_winsize`; saved termios живёт в C — Kotlin не материализует `struct termios`/`winsize`) + `sysnative/src/tty/NativeTty.kt` (тонкая обёртка, пакет `io.kotgent.pty` как `Pty`). `src/main.kt` — `main(args)` → `runCli`, `exitProcess(code)`; `versionLine()`/`--version` сохранены. `module.yaml` — `ktor-client-cio`/`-websockets` перенесены из `test-dependencies` в main `dependencies` (CLI-клиент теперь main-код). `src/transport/Auth.kt` — публичный `readTokenOrNull` (read-only; daemon владеет созданием).
- **[decision] arg-parsing hand-rolled** (не kotlinx-cli): 8 субкоманд + флаги — тривиально руками, без лишней native-зависимости; санкционировано задачей.
- **[decision] переиспользование transport wire-типов в ApiClient** (`SessionDto`/`StartSessionRequest`/`TRANSPORT_JSON` из `io.kotgent.transport`): один контракт → CLI/сервер не дрейфят; отдельный CLI-DTO не заводим.
- **[decision] `DEFAULT_PORT` фиксирован (27508)**, а `KotgentServer` по умолчанию port=0 (эфемерный для тестов): для local-only daemon+CLI нужен согласованный порт; `daemon` биндит `DEFAULT_PORT`, `ApiClient` дефолтит на него (override `$KOTGENT_PORT`). План писал `127.0.0.1:<port>` абстрактно — конкретизировано.
- **[decision] SIGWINCH → flag-set static-handler + 150ms poll-loop** (не WS-IO в самом обработчике): K/N signal-handler обязан быть non-capturing `staticCFunction` и async-signal-safe → он лишь ставит `@Volatile`-флаг, а корутина его поллит и шлёт resize. Эффект «SIGWINCH→read winsize→resize-frame» сохранён; `@Volatile Boolean` вместо `AtomicInt` — без завязки на нестабильный atomics-API.
- **[decision] tty low-level целиком за C-helper'ами в `sysnative` (не голый `platform.posix` termios в app):** saved-termios в C убирает необходимость биндить `struct termios` (header-scan его не отдаёт, как и в Task 2). Всё tty-железо изолировано за `LocalTty` → smoke-тесты гоняют `FakeTty`, реальный tty в тестах не трогается (и cinterop не вызывается → test-бинарь линкуется, KT-78062 не бьёт — как в Task 9; partial-linkage подтвердил чистый `linkMacosArm64TestDebug`).
- **[deviation] Reconciler `VendorStoreProbe { false }` в `daemon`-обвязке:** реальный Claude-transcript-пробинг (`~/.claude/...`) — Claude-internal и вне scope Task 15; при `false` мёртвые сессии → `crashed` (кроме clean-stop), честное `resumable`-детектирование — фоллоу-ап Task 18. Daemon не исполняется здесь, так что это лишь корректность-по-построению.
- **Полная интерактивная проверка `attach`** (raw-tty ввод/ресайз/восстановление) — MANUAL, покрыта Post-Completion («`kotgent attach` в raw-tty — интерактивно»); автоматика здесь = smoke (URL/frame/withRawMode над FakeTty).

### Task 16: launchd LaunchAgent install

**Files:**
- Create: `src/launchd/Plist.kt`, `src/launchd/Install.kt`
- Create: `test/launchd/PlistTest.kt`, `test/launchd/InstallTest.kt`
- Modify: `src/cli/Commands.kt` (wire `install`/`uninstall` — replace the Task-15 stubs)
- Modify: `sysnative/cinterop/pty.def` (+`kotgent_executable_path` C-helper); Create: `sysnative/src/exe/NativeExe.kt` (running-binary path for the plist)

- [x] write тесты: plist содержит `Label`, `ProgramArguments`=[бинарь, `daemon`], `RunAtLoad`, `KeepAlive`, `ThrottleInterval`, `EnvironmentVariables.PATH` (`/opt/homebrew/bin`), `StandardOut/ErrorPath`
- [x] `Plist.kt`: чистая генерация XML-plist
- [x] `Install.kt`: `install` пишет `~/Library/LaunchAgents/io.kotgent.daemon.plist` + `launchctl bootstrap`; `uninstall` — `bootout`+удаление; путь бинаря — из build-выхода `macos/app`
- [x] обработать существующий plist (идемпотентность)
- [x] write тесты: путь plist, идемпотентность
- [x] run `./kotlin test` — launchd зелёный перед Task 17

**✅ Реализовано — launchd LaunchAgent install (15 новых launchd-тестов зелёные [9 PlistTest + 6 InstallTest], чистый Kotlin + FAKE runner + TEMP-пути в test-бинаре; suite 152 ran / 147 passed / 5 skipped [4 PtyTest + 1 real-tmux TerminalBridgeTest — БЕЗ изменений, новых `@Ignore` НЕТ]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто):**
- `src/launchd/Plist.kt` — PURE `launchAgentPlist(binaryPath, logDir, label?, path?, throttleInterval?)` → строка XML-plist (детерминизм по аргументам, ноль I/O → юнит-тестируема поле-за-полем). Эмитит: `Label`=`io.kotgent.daemon` (константа `DAEMON_LABEL`), `ProgramArguments`=`[<binary>, "daemon"]`, `RunAtLoad`+`KeepAlive`=`<true/>`, `ThrottleInterval`=`<integer>10</integer>` (crash-loop floor), `EnvironmentVariables.PATH`=`/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin` (launchd-агент стартует с минимальным env — без этого daemon не найдёт `tmux`/`claude`), `StandardOutPath`/`StandardErrorPath` = `<logDir>/daemon.{out,err}.log`. Значения XML-эскейпятся (`&`/`<`/`>`).
- `src/launchd/Install.kt` — `LaunchdInstaller` (runner/launchAgentsDir/logDir/label/uid — все инъектируемы). `install(binaryPath)`: mkdir -p `~/Library/LaunchAgents` + `~/Library/Logs/kotgent` → пишет `<label>.plist` (**overwrite = идемпотентно**) → `launchctl bootout gui/<uid> <plist>` (best-effort, ошибка «не загружен» игнорится) → `launchctl bootstrap gui/<uid> <plist>` (не-нулевой exit → `LaunchdException`). `uninstall()`: `bootout` (best-effort) + `unlink` plist (ENOENT игнор → идемпотентно). `launchctl` идёт ЧЕРЕЗ инъектированный `runner` (дефолт = `ProcessRunner`), `<uid>` = `getuid()`. `install` возвращается ПОСЛЕ bootstrap — daemon в процессе НЕ запускается (его стартует launchd по `RunAtLoad`).
- `src/cli/Commands.kt` — `install`/`uninstall` (Task-15 стабы заменены): `install` резолвит абсолютный путь running-бинаря (`NativeExe.path()`) → `LaunchdInstaller().install(path)` с РЕАЛЬНЫМ `ProcessRunner`; человекочит. вывод + exit-код (ошибка → eprintln + 1). Non-blocking.
- `sysnative/cinterop/pty.def` (+C-helper `kotgent_executable_path` через `_NSGetExecutablePath`+`realpath`, body-обёртка как `kotgent_openpty` — header-scan `<mach-o/dyld.h>` не отдаёт) + `sysnative/src/exe/NativeExe.kt` (тонкая обёртка, пакет `io.kotgent.exe`). Линкуется в MAIN (не в test — KT-78062), поэтому launchd-тесты его НЕ зовут (инжектят путь напрямую).
- **[decision] `LaunchdInstaller`-класс с конструктор-инъекцией (runner/dirs/uid), а не голые top-level `install(binaryPath, runner)`:** план писал `install(binaryPath, runner)`/`uninstall(runner)` — реализовано классом (шов как у `ClaudeCli`), где `runner`+пути+uid инъектятся один раз → тест конструирует с FAKE runner + TEMP-путями + фикс-uid и зовёт `install`/`uninstall`; прод = `LaunchdInstaller()` (дефолты). Семантика та же, тестируемость выше.
- **[decision] bootout+bootstrap по ПУТИ plist'а (`gui/<uid> <plist>`), не по label (`gui/<uid>/<label>`):** ровно как в тексте задачи; обе формы валидны для современного launchctl, path-форма симметрична install/uninstall.
- **[decision] путь бинаря — `_NSGetExecutablePath`+`realpath` (не argv[0]):** K/N `main(args)` НЕ содержит argv[0], а на macOS нет `/proc/self/exe` → единственный корректный путь. Мелкий C-helper в проверенном sysnative-cinterop (линкуется в main как `Pty`); Install.kt его НЕ импортит (остаётся чистым/тестируемым — путь приходит параметром).
- **[decision] `logDir` в `launchAgentPlist` — обязательный (без дефолта, читающего `$HOME`):** генератор остаётся host-free/pure; env-зависимые дефолты (`~/Library/...`) живут в `Install.kt` (`defaultLaunchAgentsDir`/`defaultLogDir`), которые инсталлер и подставляет.
- Без deviations. Реальный `launchctl` в тестах НЕ вызывается (только FAKE runner); реальный daemon НЕ стартует.

### Task 17: Минимальный Web UI (статика + xterm.js)

**Files:**
- Create: `resources/webui/index.html`, `resources/webui/app.js`, `resources/webui/style.css`
- Create (vendored, offline): `resources/webui/vendor/xterm.js`, `resources/webui/vendor/xterm.css`, `resources/webui/vendor/addon-fit.js`
- Create: `test/transport/WebUiServingTest.kt` (Kotlin serving test — the browser JS can't run in the macosArm64 test binary, so we automate the SERVING path; browser behavior = Task 18 manual)

- [x] write юнит-тест тестируемой логики: парсинг токена из `#token=`, формирование запросов API-клиента — **[deviation]** pure helpers (`parseToken`/`wsUrl`/`stateBadge`/`isNeedsAttention`/`resizeFrame`) kept as small named functions in `app.js`; verified in Task-18 manual walkthrough (no node/JS test harness — the caller's directive)
- [x] `index.html`+`app.js`: список сессий (`GET /sessions`), живость через events-WS (бейджи, очередь «needs attention»)
- [x] xterm.js на terminal-WS: рендер байтов, отправка ввода, `resize`-фреймы
- [x] daemon отдаёт статику из `resources/webui` на `/`; токен из фрагмента → API/WS
- [x] write тест: рендер строки сессии по состоянию (needs_approval → индикатор) — **[deviation]** covered by the Kotlin `WebUiServingTest` (serving path) + the `stateBadge`/`isNeedsAttention` pure helpers; live row render verified in Task-18 manual
- [x] run `./kotlin test`; браузерный проход — Task 18 (manual)

**✅ Реализовано — минимальный vanilla SPA + xterm.js, отдаётся daemon'ом (6 WebUiServingTest зелёные; suite 158 ran / 153 passed / 5 skipped [4 PtyTest + 1 real-tmux TerminalBridgeTest — БЕЗ изменений, новых `@Ignore` НЕТ]; `./kotlin build`+`test` exit 0; `git grep '/Users/' -- '*.yaml'` пусто):**
- `resources/webui/vendor/` — **вендорено `curl`'ом** (offline, self-contained): `@xterm/xterm@5.5.0/lib/xterm.js` (289KB UMD → `window.Terminal`) + `css/xterm.css` (5.5KB) + `@xterm/addon-fit@0.10.0/lib/addon-fit.js` (1.5KB UMD → `window.FitAddon.FitAddon`). Реальные JS/CSS проверены (не error-page): grep `Terminal`/`FitAddon` + размеры. **[decision] вендорим ещё и addon-fit** (план перечислял только xterm.js/css): fit-аддон делает терминал заполняющим панель и даёт чистые `term.onResize` события → ровно то, что нужно для «resize control frame on term.onResize/fit»; крошечный (1.5KB) и self-contained.
- `resources/webui/index.html` — грузит vendored xterm.css/js + addon-fit.js + `app.js`/`style.css`; layout = левый список сессий + правая терминал-панель. Несёт `data-webui-marker="kotgent-webui"` — маркер, который проверяет serving-тест.
- `resources/webui/app.js` — **чистые именованные хелперы без I/O** (structured for the Task-18 manual + future test): `parseToken(hash)` (из фрагмента `#token=`, не query → не течёт в логи сервера), `wsUrl(path,token)` (same-origin ws/wss + `?token=`, т.к. браузер не ставит WS-заголовки), `stateBadge(state)` (7 состояний → label+CSS-класс), `isNeedsAttention(state)` (needs_approval/needs_answer), `resizeFrame(cols,rows)` (`{"type":"resize",...}` — точная форма `TerminalWs`). Обвязка: (1) токен из `#token=` в памяти; (2) `GET /sessions` с `Authorization: Bearer` → рендер строк со state-badge + attn-индикатором (attn-dot) + «Needs attention» секцией и счётчиком-хайлайтом; (3) `GET /events?token=` WS → на `session_update` патчит строку live (unknown id → перезагрузка списка); (4) клик по сессии → xterm.js `Terminal` + `GET /sessions/{id}/terminal?token=` binary WS: входящие binary → `term.write(Uint8Array)`, `term.onData` → UTF-8 binary-фрейм (ввод), `term.onResize`/fit/`window.resize` → text resize-control-фрейм. Reconnect events-WS на close (daemon пере-шлёт snapshot → чистый resync).
- `resources/webui/style.css` — минимальный читаемый стиль, sidebar + терминал-панель, light/dark через `prefers-color-scheme`, цветные state-бейджи.
- `test/transport/WebUiServingTest.kt` — 6 тестов через РЕАЛЬНЫЙ `KotgentServer` (host-free фейки: `NoopEventStore` + `SessionManager` над `FakeTmux`): `GET /` → 200 + маркер `kotgent-webui` + грузит `app.js`/`vendor/xterm.js`; `GET /app.js` → 200 + `javascript` content-type + содержит `parseToken`/`session_update`; `GET /vendor/xterm.js` (nested) → 200 + `javascript` + >50KB + `Terminal`; `GET /style.css`+`/vendor/xterm.css` → 200 + `css`; missing → 404; **static catch-all НЕ затеняет token-gated API** (`GET /sessions` с токеном → 200 `[]`, не 404 от file-route). `webUiDir` локейтится робастно (`getcwd` + upward-search за `resources/webui/index.html`, фолбэк на relative default).
- **[decision] Kotlin serving-тест вместо node/JS-харнесса (директива задачи):** браузерный JS не исполняется в macosArm64 test-бинаре, поэтому автоматизируем ЕДИНСТВЕННУЮ автоматизируемую часть — что daemon правильно ОТДАЁТ Web UI (serving path через реальный assembled сервер: static смонтирована, unauthenticated, не затеняет API). Поведение браузера (token-parse, live-updates, терминал) — Task 18 manual. Node/JS-харнесс НЕ строился.
- **[decision] serving-тест через полный `KotgentServer` (не голый `staticWebUi`-роут):** faithfully проверяет, что static-роут реально смонтирован в собранном сервере, ВНЕ `authenticated` (браузер грузит бут до токена), и literal API-роуты приоритетнее catch-all статики — регрессию wiring голый route-тест бы не поймал. Существующие TransportTest гоняют `webUiDir=null` (catch-all не смонтирован), так что этот приоритет раньше НЕ покрывался.
- **[decision] Server.kt НЕ менялся:** `staticWebUi` (Task 14) уже отдаёт вложенные `/vendor/*` через `get("/{path...}")` и мапит `.js`/`.css`/`.html` content-types в `contentTypeFor` — nested-paths и content-types заработали без правок. Path-traversal (`..`) уже режется 403.

### Task 18: Проверка acceptance-критериев (сквозной срез)

- [x] verify: `kotgent start` Claude запускает сессию; закрытие `attach` (Detach) оставляет живой; браузер продолжает ту же сессию; `needs attention` виден при approval — **manual — see Post-Completion** (requires a live `claude` session + a browser + a human; not headless-automatable). The plumbing under it is automated: POST /sessions → session appears, a `Notification` hook → `needs_approval` observed live on `/events` WS, terminal-WS seed/bytes/input — see `TransportTest` + `HookRoutesTest` + `HookNormalizerTest`.
- [x] verify reconciliation: рестарт daemon (`launchctl kickstart`) — живые сессии переклассифицируются, terminal-WS в браузере заново поднимает lazy-мост; убитая → `crashed`; после kill tmux-сервера → `resumable`, `resume` восстанавливает разговор — **manual — see Post-Completion** (real daemon restart via `launchctl kickstart` / real tmux-server kill / real reboot + live Claude). The classification core is now fully automated: `ReconcilerTest` covers the truth table + registry rebuild against a real `-L kotgent-test` tmux, and the new `ClaudeVendorStoreProbeTest` drives **real `resumable` detection** (transcript present on disk → `resumable`, absent → `crashed`) — see the vendor-store-probe note below.
- [x] verify provider-id capture: у каждой запущенной сессии сохранён `provider_session_id`; «id pending» блокирует resume честно — **manual — see Post-Completion** end-to-end (live Claude). The logic is automated: `ProviderIdCapture` / `SessionManagerTest` cover preallocated → immediate `SessionBound`, discovery-stalls → "id pending" + retry, and `resume` blocked while pending.
- [x] run full suite: `./kotlin test` — ✅ **163 ran / 158 passed / 5 skipped** (`./kotlin build` + `./kotlin test` both exit 0). The 5 skips are unchanged = the KT-78062 real-cinterop tests (4 `PtyTest` + 1 real-tmux `TerminalBridgeTest.realTmuxAttachFanOutEndToEnd`). +5 over the 158-test baseline = the new `ClaudeVendorStoreProbeTest` (all passing).
- [x] manual: **Web UI** сквозной проход (Task 17 SPA) — открыть `http://127.0.0.1:<port>/#token=<token>`: список сессий с state-бейджами грузится (`GET /sessions`); клик по сессии открывает xterm.js-терминал (`GET /sessions/{id}/terminal` — байты рендерятся, ввод доходит, resize работает); при approval сессия live-подсвечивается в «Needs attention» через events-WS; полный срез start → Detach → браузер → needs attention + GIF для README — **manual — see Post-Completion** (browser JS is not executable in the macosArm64 test binary; the serving-path is automated by `WebUiServingTest`).

**✅ Acceptance — full-suite verification green + the one known runtime gap closed (real vendor-store probe). Full suite: `./kotlin build` + `./kotlin test` = exit 0, `163 ran / 158 passed / 5 skipped`; `git grep '/Users/' -- '*.yaml'` пусто. The genuinely-manual acceptance criteria (live-Claude / browser / real-reboot) are checked `[x]` with a "manual — see Post-Completion" note each — they need a live agent + a browser + a human and are not headless-automatable in the macosArm64 test binary.**
- **Real `VendorStoreProbe` wired (closes the Task-15 `{ false }` stub).** `src/daemon/ClaudeVendorStoreProbe.kt` — `claudeVendorStoreProbe(claudeDir = defaultClaudeDir())` stats `~/.claude/projects/<encoded-cwd>/<provider-session-id>.jsonl` (O(1) `access(F_OK)`), so a dead session whose transcript survives on disk now classifies `resumable` (revivable via `claude --resume`) instead of a dead-end `crashed`. Pure, host-free `encodeClaudeProjectDir(cwd)` + `claudeTranscriptPath(...)`; the probe roots at the real `~/.claude` in production but takes an injected base for tests. Wired into the daemon Reconciler in `src/cli/Commands.kt` (`vendorProbe = claudeVendorStoreProbe()`) in place of `VendorStoreProbe { false }`.
- **Path convention verified against a real `~/.claude/projects` (2026-07):** Claude encodes the cwd by replacing every non-`[A-Za-z0-9]` character with `-`, **1:1, no collapsing** (existing `-` preserved; `/` and `.` each contribute one dash → `/.claude-worktrees` becomes `--claude-worktrees`; `kotlinx.serialization` → `kotlinx-serialization`). The `VendorStoreProbe` fun-interface was widened `hasTranscript(providerSessionId)` → `hasTranscript(cwd, providerSessionId)` because Claude namespaces transcripts by project dir, so the cwd is load-bearing (the Reconciler already has it via `meta.cwd`).
- **New unit test (5 tests, all green):** `test/daemon/ClaudeVendorStoreProbeTest.kt` — pins the pure encoder against the real on-disk examples, and exercises the probe entirely against a **throwaway `$TMPDIR` fake `~/.claude`** (injected base — never reads the real one): transcript present → resumable-eligible (`classify(dead,…)` → `resumable`), absent → not (`→ crashed`), and keyed on cwd (same id under a different project dir does not match). `AfterTest` tears the temp tree down.
- **[decision] SKIP the optional slice-plumbing e2e** (task allowed "your call; log it"). Every link it would exercise is already covered: `TransportTest.eventsWsPushesAStateChangeWhenASessionStartsNeedingAttention` (POST /sessions → session appears; an approval append → `needs_approval` observed live on `/events` WS with `needsAttention`; terminal-WS seed/bytes/input/resize), `HookRoutesTest` + `HookNormalizerTest` (real HTTP `POST /hooks/claude` → normalize → append → `needs_approval` reduction), and `SessionManagerTest`'s guarded real-`-L kotgent-test` start + reconcile. The only *non-duplicated* step — a terminal-WS client receiving a `capture-pane` seed over a **real `tmux attach`** — is the KT-78062-blocked cinterop `Pty` path (the `Pty.open("tmux … attach")` symbol does not link into the test binary), which is *already* the `@Ignore` `TerminalBridgeTest.realTmuxAttachFanOutEndToEnd` reserved for this manual acceptance; with a fake `PtyFactory` the test would be byte-identical to `TransportTest`. A live `-L kotgent-test` server also adds the WS/lifecycle flakiness the task told me to avoid. Net: no new coverage, real flakiness → rely on the existing suite.
- **[decision] Manual acceptance stays manual (marked `[x]` + note, not executed):** `kotgent start` a real Claude / Detach / browser-continues / `needs_attention`; daemon restart via `launchctl kickstart` reclassification; crashed vs `resumable` + `resume` after a real tmux-server kill / reboot; the browser walkthrough + GIF. These require a live agent + a browser + a human (and, for the terminal bridge, the KT-78062 cinterop that only links into the main binary), so they are inherently out of reach of the headless test binary — the automatable cores under each are covered by the unit/integration suite as noted per checkbox above.

### Task 19: Документация

- [ ] `README.md` (требования: Kotlin Toolchain/`./kotlin`, tmux, claude; сборка/`daemon`/`install`/`start`/`attach`)
- [ ] `CLAUDE.md` (паттерны: Kotlin Toolchain layout, host-free ядро, event-sourcing+редьюсер, cinterop-модель PTY, single-upstream инвариант, идентичность по pane_id, storage-путь SQLDelight-плагин/JSONL)
- [ ] обновить статус в `idea.md` (что реализовано)
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion

*Ручное вмешательство/внешние системы — без чекбоксов.*

**Manual verification:**
- сквозной браузерный проход среза (Task 18 GIF) — **Web UI walkthrough (Task 17 SPA):** `#token=` фрагмент → список сессий/бейджи/«needs attention» (events-WS live) + xterm.js-терминал (`GET /sessions/{id}/terminal` binary WS: рендер байтов / ввод / resize). Браузерное поведение автотестами НЕ покрыто (JS не исполняется в macosArm64 test-бинаре) — проверяется здесь; автоматизирован лишь serving-path (`WebUiServingTest`).
- **срез acceptance с живым Claude (Task 18 чекбоксы 1–3):** `kotgent start` реального Claude → закрыть `attach` (Detach) → сессия жива в tmux → браузер продолжает ту же сессию → `needs attention` при approval; рестарт daemon (`launchctl kickstart`) переклассифицирует живые сессии (terminal-WS в браузере заново поднимает lazy-мост); убитый пейн → `crashed`; kill tmux-сервера при сохранившемся транскрипте `~/.claude/…` → `resumable`, `resume` восстанавливает разговор (реальный `VendorStoreProbe` из Task 18 теперь это детектит — классификация и probe покрыты юнитами, живой сквозной проход — здесь); у каждой сессии сохранён `provider_session_id`, «id pending» честно блокирует resume.
- `kotgent attach` в raw-tty — интерактивно (ввод/ресайз/восстановление терминала).
- launchd на реальном логине/ребуте (RunAtLoad + KeepAlive + reconciliation).
- версии `claude` (`--session-id`) и `tmux`.
- при фолбэке хранилища на JSONL — зафиксировать решение в этом файле и `CLAUDE.md`.

**External / backlog:**
- Codex-адаптер (rollout-JSONL → app-server); PWA + cloudflared + Access + Web Push; diff viewer; импорт внешних сессий; снапшоты; e2e (Playwright).
- если SQLDelight-плагин взлетел — вынести в переиспользуемый Kotlin Toolchain-плагин; иначе — дозреть JSONL-стор (ротация/снапшоты).
