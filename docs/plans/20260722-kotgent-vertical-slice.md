# Kotgent — First Vertical Slice (Claude + local-only)

## Overview

Kotgent — local-first диспетчер агентских сессий: процессы агентов живут в `tmux` независимо от интерфейса, а IDE, браузер и (позже) телефон — взаимозаменяемые клиенты. Ценность — **restart-safe control plane** над coding-агентами.

Этот план реализует **первый вертикальный срез**, доказывающий ценность:

> `kotgent start` запускает **Claude** → закрываем IDEA (Detach — отваливается WS-подписчик fan-out; единственный upstream `tmux attach` daemon'а живёт) → открываем браузер → продолжаем ту же сессию → видим, что она требует внимания (`needs attention`).

Всё на **local-only** (`127.0.0.1` + токен). Codex, PWA, cloudflared-туннель, Web Push, diff viewer — в бэклоге.

Архитектура целиком проработана в брейншторме (6 слоёв, event-sourcing спина, adapter-шов). Дизайн заимствует паттерны из `umputun/agterm` (push-статус через хуки агента, host-free ядро) и JetBrains `agent-workbench` (event-sourcing: `Flow<событий>` → чистый редьюсер → состояние; `RuntimeInput`/`StopMode`; capability-by-type).

## Context (from discovery)

- **Проект:** greenfield, только `idea.md`, не git-репозиторий. Язык — **Kotlin/Native** (единый нативный бинарь, macOS arm64).
- **idea.md устарел в 2 местах:** там `pty4j` (JVM — НЕ применимо на K/N, идём в POSIX cinterop) и «свой relay» (заменён на cloudflared, в бэклоге).
- **Память `kotlin-native-stack`:** pty4j/JDBC/JVM-only библиотеки исключены.
- **Стек:** POSIX cinterop (`forkpty`/`openpty` из `<util.h>`, `ioctl(TIOCSWINSZ)`, `termios`); SQLDelight native-driver; Ktor CIO (HTTP+WS); kotlinx.coroutines + serialization; xterm.js (Web UI).
- **Reference-реализации изучены** (паттерны заимствованы, не код): agterm, agent-workbench.

## Development Approach

- **Testing approach: TDD (тесты сначала).** Для host-free ядра (домен, редьюсер, event store, нормализация адаптера, генерация launch-спеки/plist) — полноценный TDD с плотными юнит-тестами. Для краёв (forkpty/PTY, tmux, Ktor-WS, tty-raw в `attach`) TDD адаптируется: сначала пишем интеграционный/smoke-тест, фиксирующий контракт (IO, внешние процессы), затем реализацию.
- complete each task fully before moving to the next; small, focused changes.
- **CRITICAL: каждая задача включает новые/обновлённые тесты** (отдельными пунктами чеклиста, success + error/edge).
- **CRITICAL: все тесты зелёные перед следующей задачей.**
- **CRITICAL: обновлять этот файл при изменении scope.**
- run tests after each change.

## Testing Strategy

- **unit-тесты (обязательны каждую задачу):** host-free ядро — редьюсер (переходы v1, правило «вход в running сбрасывает pendingApprovals», interrupt-reset, replay-детерминизм), сериализация событий, генерация команд/конфигов/plist, event store (append/read/seq/транзакция).
- **интеграционные тесты (края):** forkpty round-trip; Ktor WS echo; tmux-обёртка против реального `tmux -L kotgent-test` (skip-guard, если tmux отсутствует); PTY fan-out (несколько подписчиков одного upstream); transport endpoints.
- **e2e/manual:** браузерный сквозной проход (срез) — в v1 нет e2e-фреймворка (Playwright — бэклог); проверяется вручную в Task 17. Юнит-тестируем то, что тестируемо в Web UI (парсинг токена из URL-фрагмента, API-клиент).
- Команды: тесты `./gradlew macosArm64Test`; сборка `./gradlew build` / линк `./gradlew linkDebugExecutableMacosArm64`.

## Progress Tracking

- отмечать `[x]` сразу по завершении; новые задачи — с префиксом ➕; блокеры — с ⚠️; синхронизировать план с фактом.

## Solution Overview

**Слои (packages под `io.kotgent`):**
- `core/` — **host-free**: `AgentEvent`, `SessionState`, `SessionMeta`, `Reducer` (лог → проекция). Без IO, максимум тестов.
- `store/` — `EventStore` интерфейс + SQLDelight/SQLite реализация (single-writer, WAL, `seq` монотонный per-session; append события + обновление кэша `sessions` в одной транзакции).
- `pty/` — PTY-примитив (`openpty`+`posix_spawn`) + fan-out. **Lazy lifecycle:** upstream `tmux attach` на сессию поднимается при ПЕРВОМ подписчике и гаснет при уходе последнего (Claude живёт в tmux независимо от моста). Это даёт Detach-семантику И снимает respawn после рестарта daemon (мост пере-поднимется на первый terminal-WS-коннект). Broadcast байтов; resize `TIOCSWINSZ`; сид новых подписчиков `capture-pane -e`.
- `tmux/` — обёртка над `tmux -L kotgent`.
- `adapter/` — `AgentAdapter` контракт (launch/resume-спека + `events: Flow<AgentEvent>`) + `ClaudeAdapter` (launch/resume, hook-config, session-id preallocation) + нормализация хук-событий. Capability-интерфейсы — в бэклоге (нужны со 2-м адаптером).
- `daemon/` — session manager, reconciliation, provider-id capture, StopMode.
- `transport/` — Ktor: control REST, events WS, terminal WS, токен-auth, hook ingress, статика Web UI.
- `cli/` — субкоманды + `attach` raw-passthrough.
- `launchd/` — генерация plist + install.

**Ключевое решение — event-sourcing:** состояние сессии не хранится как поле, а **выводится чистым редьюсером** из append-only лога `events`. Любой адаптер лишь нормализует свои сигналы в канонический `AgentEvent` — редьюсер и состояния неизменны (это и есть adapter-шов). Restart-safety = `replay` лога.

**Risk-first порядок:** первыми идут спайки самых рисковых мест на K/N — forkpty-cinterop (Task 2) и Ktor-CIO-WS (Task 3). Если платформа их не тянет — узнаём сразу, до вложений в ядро.

## Technical Details

- **Идентичность сессии:** логический ключ — имя tmux-сессии `kt-<shortid>`; рантайм-корреляция — `pane_id` (из `new-session -P -F '#{pane_id}'`, пересбор через `list-panes` на старте daemon). Хуки сообщают `$TMUX_PANE` (tmux выставляет корректно per-pane). `KOTGENT_SESSION_ID` — только debug-лейбл, маршрутизации НЕ доверяем (грабля env-poisoning tmux).
- **Состояния (7):** живые `running / needs_approval / needs_answer / ready`; мёртвые `stopped / crashed / resumable`. В Claude-срезе `needs_answer` **forward-modeled** — интерактивный Claude не даёт сигнала «задал вопрос и ждёт» (и вопрос, и конец хода → `Stop`→`ready`); ни один v1-адаптер его не производит.
- **`AgentEvent` (v1-словарь):** `TurnStarted`, `TurnCompleted`, `ApprovalRequested`, `ApprovalResolved`, `ToolCall`, `Exited(code)`, `SessionBound(providerSessionId)`. `QuestionAsked/QuestionAnswered` — в бэклог (не производятся Claude; про будущий Codex structured-протокол).
- **Claude hook-маппинг:** `UserPromptSubmit`/`PostToolUse`→running, `Stop`→ready, `Notification`→needs_approval, `SessionStart`→`SessionBound`. ⚠️ Реальные `Notification`-пейлоады Claude НЕ подтверждены (в дефолтном `settings.json` хука `Notification` нет; он же срабатывает на idle-60с). Для среза — спайк реальных пейлоадов (Task 10) + **грубый маппинг любого `Notification`→needs_attention** (точное approval-vs-idle — в бэклог). У Claude НЕТ «permission answered» → **вход в `running` (`UserPromptSubmit`/`PostToolUse`) сбрасывает `pendingApprovals=0`** (правило редьюсера, Task 5).
- **Ввод (срез):** только `TerminalInput(bytes)` — браузер продолжает и аппрувит через terminal-passthrough (xterm.js). `UserMessage`/`ApprovalResolved` — backlog-seam (structured-ввод нужен со structured-адаптером), в v1 не реализуются.
- **Остановка:** `StopMode { Detach, Interrupt, Graceful, Terminate, Kill }` (`Detach`≠`Kill` = долговечность №1; `Interrupt` = сброс залипшего `running`).
- **Схема БД:** `events(session_id, seq, ts, type, source, payload)` PK `(session_id,seq)`, индекс `(session_id,seq)`; `sessions(id, name, tags, agent, provider_session_id, model, cli_version, cli_path, cwd, repository, worktree, branch, tmux_session, pane_id, state, state_source, last_seq, read_cursor, created_at, updated_at)`.
- **Provider-id capture:** preallocate UUID → `claude --session-id <uuid>` (version-gated; fallback — из `SessionStart`-хука); `SessionBound` пишет `provider_session_id`. Гарантия: буксует → retry + пометка «id pending» (resume недоступен, пока не привязан), не терять молча.
- **Transport:** `127.0.0.1:PORT`, control REST + events WS (`?from=<seq>`, restart-safe курсор) + terminal WS (binary fan-out, `capture-pane -e` сид + resize). Токен в `~/.kotgent/token` (0600); CLI читает файл; Web UI — из URL-фрагмента `#token=`. Один токен на всё (bearer + hook); отдельный hook-токен — бэклог-харденинг.
- **`kotgent attach`** = raw-passthrough на terminal-WS (локальный tty в raw через `termios`, stdin→WS, WS→stdout, `SIGWINCH`→resize), НЕ прямой `tmux attach` (держим инвариант «один upstream tmux-клиент на сессию, размер решает daemon»).

## What Goes Where

- **Implementation Steps** (`[ ]`): весь код, тесты, схема, скрипты сборки в этом репозитории.
- **Post-Completion** (без чекбоксов): ручной браузерный проход, установка/проверка launchd на реальной машине, настройка Playwright-e2e (бэклог), проверка на конкретных версиях `claude`/`tmux`.

## Implementation Steps

### Task 1: Каркас проекта и Gradle KMP-сборка

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `src/nativeMain/kotlin/io/kotgent/Main.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/SmokeTest.kt`
- Create: `.gitignore`

- [ ] настроить KMP-плагин, target `macosArm64 { binaries { executable { entryPoint = "io.kotgent.main" } } }`
- [ ] подключить и **запинить проверенные версии** в `libs.versions.toml` (риск WS-на-native завязан на версию Ktor): Ktor 3.x CIO server + websockets, SQLDelight native-driver + gradle-plugin, kotlinx-coroutines-core, kotlinx-serialization-json, CLI-либа (kotlinx-cli/clikt native), kotlin-test
- [ ] `Main.kt` с `main()`, печатающим версию (заглушка `kotgent --version`)
- [ ] `.gitignore` (`.gradle`, `build/`, `*.klib`) — git уже инициализирован при коммите плана
- [ ] write smoke-тест, проверяющий запуск `nativeTest` на K/N
- [ ] run `./gradlew build && ./gradlew macosArm64Test` — должно пройти до Task 2

### Task 2: cinterop-спайк — PTY-примитив через openpty + posix_spawn (РИСК)

**Files:**
- Create: `src/nativeInterop/cinterop/pty.def`
- Create: `src/nativeMain/kotlin/io/kotgent/pty/Pty.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/pty/PtyTest.kt`

- [ ] write интеграционный тест (контракт, **bounded read + timeout** — анти-флаки): открыть PTY, заспавнить `/bin/cat`, записать в мастер строку, прочитать её эхо, задать winsize — ASSERT round-trip
- [ ] `pty.def` с `#include <util.h> <sys/ioctl.h> <termios.h> <spawn.h>`, экспорт `openpty`/`ioctl`/`TIOCSWINSZ`/`winsize`/`posix_spawn`/`posix_spawnattr_*`/`POSIX_SPAWN_SETSID`
- [ ] `Pty.kt`: `open(...)` через **`openpty` + `posix_spawn(POSIX_SPAWN_SETSID)`**, НЕ `forkpty` — fork-без-exec небезопасен для рантайма K/N (GC/аллокации в пост-fork ребёнке); все C-строки argv/envp/cwd маршалятся ДО спавна. Мастер-fd в родителе; `read()`/`write()`; `resize()` через `ioctl(TIOCSWINSZ)`; `close()`
- [ ] модель чтения: **выделенный reader-thread** (`newSingleThreadContext`/Worker) с блокирующим `read()` → `Channel` (на native нет `Dispatchers.IO`; kqueue для горстки сессий преждевременно — YAGNI)
- [ ] write тесты: exit-код ребёнка, resize, ошибка спавна несуществующей команды
- [ ] run tests — PTY round-trip зелёный перед Task 3

### Task 3: Ktor CIO HTTP+WS-спайк на native (РИСК)

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/transport/SpikeServer.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/transport/WsSpikeTest.kt`

- [ ] write интеграционный тест: поднять сервер на `127.0.0.1:0`, подключиться Ktor-client'ом, HTTP GET round-trip + WS echo round-trip — ASSERT
- [ ] `SpikeServer.kt`: `embeddedServer(CIO)` с одним HTTP-роутом и одним WS-echo-роутом; проверить установку `WebSockets` плагина на native
- [ ] проверить бинарный WS-фрейм (Frame.Binary) — нужен для terminal-канала
- [ ] ⚠️ если WS-плагин CIO на native не покрывает нужное — зафиксировать блокер и эскалировать (это меняет транспортное решение)
- [ ] write тест: сервер отдаёт статический файл (для будущей Web UI)
- [ ] run tests — WS зелёный перед Task 4

### Task 4: Домен — AgentEvent, SessionState, модель сессии (host-free)

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/core/AgentEvent.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/core/SessionState.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/core/SessionMeta.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/core/Ids.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/core/DomainTest.kt`

- [ ] write тесты: `@Serializable` round-trip каждого v1-`AgentEvent`-подтипа; инварианты value-class id (`SessionId`, регекс провайдера)
- [ ] `Ids.kt`: value-class'ы `SessionId`, `Seq`, `ProviderSessionId`, `PaneId`
- [ ] `AgentEvent.kt`: sealed-иерархия v1-словаря (`TurnStarted/TurnCompleted/ApprovalRequested/ApprovalResolved/ToolCall/Exited/SessionBound`) + `EventSource` (`Question*` — бэклог)
- [ ] `SessionState.kt`: enum 7 состояний + группировка живые/мёртвые + `needsAttention`; `needs_answer` помечен forward-modeled (не производится v1-адаптером)
- [ ] `SessionMeta.kt`: data class с полями сессии (агент, cwd/worktree/branch, cli-версия/путь, model, tmux/pane, tags)
- [ ] run tests — домен зелёный перед Task 5

### Task 5: Редьюсер — лог событий → проекция состояния (host-free, ядро TDD)

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/core/Reducer.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/core/Projection.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/core/ReducerTest.kt`

- [ ] write тесты переходов v1: start→running; ApprovalRequested→needs_approval; TurnCompleted/Stop→ready; ответ→running; Exited(0)→stopped vs Exited(≠0)→crashed; SessionBound пишет provider-id
- [ ] write тест правила разрешения approval у Claude (нет «permission answered»): **вход в `running` (`UserPromptSubmit`/`PostToolUse`) сбрасывает `pendingApprovals=0`** → цепочка `Notification→PostToolUse→running` гасит `needs_approval`
- [ ] write тесты: `Interrupt` сбрасывает залипший `running`; `Detach` — НЕ меняет состояние; `replay(events)` детерминирован (property: fold-с-нуля == инкрементальный)
- [ ] `Reducer.kt`: чистая `reduce(projection, event): Projection`; `Projection.kt`: read-model (state, pendingApprovals, last_seq, unread). Waiting-логика v1 — approval-only (`needs_answer` в срезе не достижим)
- [ ] run tests — редьюсер зелёный перед Task 6

### Task 6: EventStore — SQLDelight-схема и SQLite-реализация

**Files:**
- Create: `src/nativeMain/sqldelight/io/kotgent/db/Events.sq`, `src/nativeMain/sqldelight/io/kotgent/db/Sessions.sq`
- Create: `src/nativeMain/kotlin/io/kotgent/store/EventStore.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/store/SqliteEventStore.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/store/EventStoreTest.kt`

- [ ] write тесты: `append`→`read(fromSeq)` round-trip; `seq` монотонный per-session; append+обновление кэша `sessions` атомарны (одна транзакция); `replay` из стора восстанавливает состояние; `subscribe(fromSeq)` эмитит новые события
- [ ] `.sq`: таблицы `events`/`sessions` (из Technical Details) + PRAGMA WAL + индексы + запросы (insert-event, next-seq, upsert-session, list-sessions, read-from-seq)
- [ ] `EventStore.kt`: интерфейс `append/read/subscribe`; `SqliteEventStore.kt`: реализация, single-writer (сериализующий mutex/actor), WAL
- [ ] write тесты: конкурентные читатели не блокируют писателя; протухший курсор в `subscribe`
- [ ] run tests — стор зелёный перед Task 7

### Task 7: Обёртка над tmux (`tmux -L kotgent`)

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/tmux/Tmux.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/tmux/ProcessRunner.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/tmux/TmuxTest.kt`

- [ ] write интеграционные тесты против `tmux -L kotgent-test` (skip-guard если tmux не в PATH): `newSession` возвращает `pane_id`; `listSessions`/`listPanes` парсятся; `capturePane` отдаёт содержимое; `killSession`
- [ ] `ProcessRunner.kt`: запуск процесса через `posix_spawn`, сбор stdout/stderr/exit
- [ ] `Tmux.kt`: `ensureServer()`, `newSession(id,cwd,cmd,cols,rows)→PaneId`, `listSessions()`, `listPanes()`, `capturePane(id)`, `killSession(id)`, `sendKeys(id, bytes)` (для Interrupt), `paneAlive(id)`/`panePid(id)`
- [ ] аккуратный парсинг `-F` форматов; экранирование аргументов
- [ ] write тесты: несуществующая сессия, двойной `killSession`
- [ ] run tests — обёртка зелёная перед Task 8

### Task 8: PTY fan-out — lazy upstream-мост + broadcaster + capture-pane сид

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/pty/TerminalBridge.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/pty/Broadcaster.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/pty/TerminalBridgeTest.kt`

- [ ] write интеграционный тест: первый подписчик поднимает upstream `tmux attach` к сессии с `cat`; два подписчика оба получают вывод; ввод любого доходит до ребёнка; resize пробрасывается; новый подписчик получает `capture-pane -e` сид (fails until Task 7)
- [ ] write тест **lazy lifecycle**: уход ПОСЛЕДНЕГО подписчика гасит upstream-мост, но tmux-сессия/Claude живут (Detach); НОВЫЙ подписчик заново поднимает мост — это же снимает respawn после рестарта daemon
- [ ] `TerminalBridge.kt`: на сессию — **lazy** `Pty.open("tmux -L kotgent attach -t kt-<id>")` при первом подписчике, reader-loop → `Broadcaster`, close при уходе последнего
- [ ] `Broadcaster.kt`: набор подписчиков (WS-каналы), fan-out байтов; вход любого → запись в upstream; политика размера «последний активный» → `resize()`. ⚠️ `window-size` по умолчанию `latest`: `capture-pane`-сид новому xterm иного размера даст reflow — косметика, не баг
- [ ] сид нового подписчика: `capturePane(-e)` → стартовая отрисовка, дальше живые дельты
- [ ] run tests — fan-out зелёный перед Task 9

### Task 9: Контракт AgentAdapter (+ FakeAdapter)

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/adapter/AgentAdapter.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/adapter/LaunchSpec.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/adapter/FakeAdapter.kt`, `src/nativeTest/kotlin/io/kotgent/adapter/AdapterContractTest.kt`

- [ ] write тесты: `FakeAdapter` эмитит поток `AgentEvent`, редьюсер сворачивает его в ожидаемые состояния (контракт «адаптер → события → редьюсер»)
- [ ] `AgentAdapter.kt`: ядро — `buildLaunchSpec(mode: New|Resume)` + `events: Flow<AgentEvent>`
- [ ] `LaunchSpec.kt`: `command: List<String>`, `env`, `cwd`, `preallocatedSessionId?`
- [ ] (capability-интерфейсы `SupportsApprovalResolution`/… — **бэклог**, нужны со 2-м адаптером; в срезе НЕ вводим — YAGNI)
- [ ] write тест: контракт-прогон через `FakeAdapter` покрывает все v1-события
- [ ] run tests — контракт зелёный перед Task 10

### Task 10: ClaudeAdapter — launch/resume-спека, hook-config, session-id preallocation

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/adapter/claude/ClaudeAdapter.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/adapter/claude/ClaudeHookConfig.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/adapter/claude/ClaudeCli.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/adapter/claude/ClaudeAdapterTest.kt`

- [ ] **спайк (перед маппингом): вживую вызвать у Claude permission-prompt и залогировать реальные `Notification`-пейлоады**; зафиксировать дискриминатор permission-vs-idle (или подтвердить, что для среза берём любой `Notification`→needs_attention)
- [ ] write тесты: `buildLaunchSpec(New)` содержит преаллоцированный `--session-id <uuid>` (version-gated) + `--settings <hook-config>`; `buildLaunchSpec(Resume)` → `claude --resume <id>`; генерация hook-config (токен + daemon-URL) корректна
- [ ] `ClaudeCli.kt`: путь/версия `claude`; version-gating `--session-id` (подтверждено: есть в 2.1.217 без ограничения `--print`; fallback — capture из `SessionStart`)
- [ ] `ClaudeHookConfig.kt`: settings-файл с хуками (`UserPromptSubmit`/`PostToolUse`/`Stop`/`Notification`/`SessionStart`), курлящими `POST /hooks/claude` с токеном и `$TMUX_PANE`
- [ ] `ClaudeAdapter.kt`: реализация контракта (транскрипт-вотч `~/.claude/*.jsonl` — **бэклог**, в срезе НЕ вводим)
- [ ] write тесты: version-gating (старый CLI без `--session-id` → fallback-путь), корректность resume-спеки
- [ ] run tests — адаптер зелёный перед Task 11

### Task 11: Hook ingress + нормализация Claude-событий

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/adapter/claude/ClaudeHookNormalizer.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/transport/HookRoutes.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/adapter/claude/HookNormalizerTest.kt`

- [ ] write тесты (нормализатор — чистая функция, тестируется здесь полноценно): пейлоады → ожидаемый `AgentEvent` (`Notification`→ApprovalRequested/needs_attention; `Stop`→TurnCompleted/ready; `PostToolUse`→running-событие, сбрасывающее pendingApprovals; `SessionStart`→SessionBound)
- [ ] `ClaudeHookNormalizer.kt`: чистая `(hookPayload, paneId) → AgentEvent`; для среза любой `Notification`→needs_attention (грубо, надёжно)
- [ ] `HookRoutes.kt`: `POST /hooks/claude` — валидация токена, чтение `$TMUX_PANE`+пейлоада, мапинг pane→сессия (**partial-dep:** `pane_id` пишет Task 12 → тест через засиженный store), нормализация, `append`
- [ ] обработать «нет permission-answered у Claude» → вход в running (`PostToolUse`) обнуляет pendingApprovals (правило редьюсера Task 5)
- [ ] write тесты: неизвестный pane → корректная ошибка; `[x] невалидный токен → 401 (route-level, fails until Task 13-харнесс)`
- [ ] run tests — нормализатор зелёный перед Task 12 (route-level — с Task 13)

### Task 12: Session manager + reconciliation + provider-id capture

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/daemon/SessionManager.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/daemon/Reconciler.kt`
- Create: `src/nativeMain/kotlin/io/kotgent/daemon/ProviderIdCapture.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/daemon/ReconcilerTest.kt`, `.../SessionManagerTest.kt`

- [ ] write тесты reconciliation: (строки `sessions` × состояние tmux × наличие vendor-файла) → классификация running/resumable/crashed/stopped (табличные, host-free через фейковые Tmux/Store)
- [ ] write тесты provider-id capture: preallocated → мгновенно `SessionBound`; discovery буксует → «id pending» + retry, resume заблокирован пока не привязан
- [ ] `SessionManager.kt`: `start` (tmux new-session → pane_id → upsert sessions → запустить capture; **мост НЕ спавнится здесь — он lazy на первый terminal-WS-подписчик**) / `stop`/`resume`/`interrupt`/`detach` по `StopMode`
- [ ] `Reconciler.kt`: старт daemon — `list-sessions`/`list-panes` + vendor-store → пересбор `pane_id`, классификация. **Мосты не пере-поднимаем** (lazy восстановит на первый коннект); восстанавливаем только реестр/состояние. `ProviderIdCapture.kt`: гарантия сохранения id
- [ ] write интеграционный тест: `start` создаёт tmux-сессию и захватывает `pane_id`; после «рестарта» (новый Reconciler над теми же tmux+store) состояние живой сессии восстановлено, а terminal-WS-подписка заново поднимает мост
- [ ] run tests — daemon-ядро зелёное перед Task 13

### Task 13: Transport — control REST + events WS + terminal WS + токен-auth

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/transport/Server.kt`, `.../ControlRoutes.kt`, `.../EventsWs.kt`, `.../TerminalWs.kt`, `.../Auth.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/transport/TransportTest.kt`

- [ ] write интеграционные тесты: `POST /sessions` → сессия в `GET /sessions`; events-WS получает смену состояния; terminal-WS стримит байты и принимает ввод; отсутствие токена → 401
- [ ] `Auth.kt`: чтение/генерация токена `~/.kotgent/token` (0600); один bearer-токен на всё (отдельный hook-токен — бэклог)
- [ ] `ControlRoutes.kt`: `GET /sessions`, `GET /sessions/{id}`, `POST /sessions`, `POST /sessions/{id}/{stop|resume|interrupt|detach}`, `POST /sessions/{id}/input` (в срезе — только `TerminalInput`). `PATCH /sessions/{id}` (rename/tags) — **бэклог**
- [ ] `EventsWs.kt`: `GET /events?from=<seq>` — `store.subscribe`, restart-safe курсор (протухший→hard-error+ресинк); `TerminalWs.kt`: мост к **lazy** `Broadcaster` (первый коннект поднимает upstream) + `capture-pane` сид + resize-фреймы; статика Web UI на `/`
- [ ] write тесты: restart-safe курсор (from за пределами → ошибка), resize-фрейм → `TIOCSWINSZ`
- [ ] run tests — transport зелёный перед Task 14

### Task 14: CLI-субкоманды + `attach` raw-passthrough

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/cli/Cli.kt`, `.../Commands.kt`, `.../AttachClient.kt`, `.../ApiClient.kt`
- Modify: `src/nativeMain/kotlin/io/kotgent/Main.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/cli/CliTest.kt`

- [ ] write тесты: парсинг субкоманд; `ApiClient` шлёт корректные HTTP-запросы (против stub-сервера), читая токен из файла
- [ ] `Commands.kt`: `daemon` (запуск сервера), `start/list/stop/resume` (HTTP), `install`/`uninstall` (Task 15)
- [ ] `AttachClient.kt`: raw-passthrough — локальный tty в raw (`termios`), stdin→terminal-WS, WS→stdout, `SIGWINCH`→resize, восстановление tty на выходе
- [ ] `Main.kt`: диспатч субкоманд
- [ ] write тесты: `list` рендерит ответ API; аргументы `start` (cwd/agent) валидируются; `attach` passthrough — smoke (tty-raw помечен manual в Post-Completion)
- [ ] run tests — CLI зелёный перед Task 15

### Task 15: launchd LaunchAgent install

**Files:**
- Create: `src/nativeMain/kotlin/io/kotgent/launchd/Plist.kt`, `.../Install.kt`
- Create: `src/nativeTest/kotlin/io/kotgent/launchd/PlistTest.kt`

- [ ] write тесты: генерация plist содержит `Label`, `ProgramArguments`=[бинарь, `daemon`], `RunAtLoad`, `KeepAlive`, `ThrottleInterval`, `EnvironmentVariables.PATH` (с `/opt/homebrew/bin`), `StandardOut/ErrorPath`
- [ ] `Plist.kt`: чистая генерация XML-plist (тестируется напрямую)
- [ ] `Install.kt`: `install` пишет `~/Library/LaunchAgents/io.kotgent.daemon.plist` + `launchctl bootstrap`; `uninstall` — `bootout` + удаление
- [ ] обработать существующий plist (перезапись/идемпотентность)
- [ ] write тесты: путь plist, идемпотентность install
- [ ] run tests — launchd зелёный перед Task 16

### Task 16: Минимальный Web UI (статический SPA + xterm.js)

**Files:**
- Create: `src/webui/index.html`, `src/webui/app.js`, `src/webui/style.css`, `src/webui/vendor/xterm.js` (vendored)
- Create: `src/webui/app.test.js` (или node-based unit для парсинга токена/API)

- [ ] write юнит-тест тестируемой логики: парсинг токена из URL-фрагмента `#token=`, формирование запросов API-клиента
- [ ] `index.html`+`app.js`: список сессий (`GET /sessions`), живость через events-WS (бейджи состояний, очередь «needs attention»)
- [ ] xterm.js на terminal-WS: рендер сырых байтов, отправка ввода, `resize`-фреймы
- [ ] daemon отдаёт статику на `/`; токен из фрагмента подставляется в API/WS
- [ ] write тест: рендер строки сессии по состоянию (needs_approval → индикатор внимания)
- [ ] run unit-тесты; браузерный сквозной проход — Task 17 (manual)

### Task 17: Проверка acceptance-критериев (сквозной срез)

- [ ] verify требования из Overview: `kotgent start` Claude запускает сессию; закрытие `attach` (Detach) оставляет сессию живой; браузер продолжает ту же сессию; `needs attention` виден при approval
- [ ] verify reconciliation: рестарт daemon (`launchctl kickstart`) — живые сессии переклассифицируются, а terminal-WS в браузере ЗАНОВО поднимает lazy-мост (upstream пере-attach); убитая сессия → `crashed`; после «ребута» (kill tmux-сервера) → `resumable`, `resume` восстанавливает разговор
- [ ] verify provider-id capture: у каждой запущенной сессии сохранён `provider_session_id`; «id pending» блокирует resume честно
- [ ] run full test suite: `./gradlew macosArm64Test`
- [ ] manual: браузерный проход среза (start → Detach → браузер → needs attention) + запись GIF для README

### Task 18: Документация

- [ ] создать `README.md` (сборка, `kotgent daemon`/`install`/`start`/`attach`, требования: tmux, claude)
- [ ] создать `CLAUDE.md` (паттерны: host-free ядро, event-sourcing + редьюсер, cinterop-модель PTY, инвариант single upstream-клиента, идентичность по pane_id)
- [ ] обновить `idea.md`-ссылку/статус (что реализовано в срезе)
- [ ] переместить этот план в `docs/plans/completed/`

## Post-Completion

*Требуют ручного вмешательства/внешних систем — без чекбоксов.*

**Manual verification:**
- сквозной браузерный проход среза на реальной машine (Task 17 GIF).
- `kotgent attach` в raw-tty — интерактивная проверка (ввод/ресайз/восстановление терминала) в реальном IDEA/iTerm.
- проверка launchd на реальном логине/ребуте (RunAtLoad + KeepAlive + reconciliation после старта).
- проверка на конкретных версиях `claude` (наличие/поведение `--session-id`) и `tmux`.

**External / backlog (следующие итерации):**
- Codex-адаптер: rollout-JSONL watch → app-server (за тем же контрактом).
- PWA + cloudflared-туннель + Cloudflare Access + Web Push (seams готовы: localhost-listener + токен).
- diff viewer (независимый git-запрос), импорт внешне-стартованных сессий (те же вотчеры), снапшоты проекции при росте логов.
- e2e-фреймворк (Playwright) для браузерного среза.
