# Импорт сессии по provider session id

## Overview

Дать возможность «импортировать» в kotgent существующую claude/codex сессию, запущенную **вне** kotgent,
по её provider session id. Импорт = регистрация: строка в store со `state = resumable` + `SessionBound`
в event log — **без единого tmux side-эффекта**. Запуск — существующим `SessionManager.resume()`
(`claude --resume <id>` / `codex resume <id>`); ни строчки нового launch-кода.

Решает: разговор, начатый в обычном терминале, сейчас нельзя завести под kotgent (fan-out, push,
web UI, мобильный доступ) без потери истории. Точки входа: CLI `kotgent import` и режим «Import»
в пикере новой сессии Web UI; оба через один `POST /sessions/import`.

## Context (discovery, 2026-07-29; уточнено по plan-review)

- `SessionManager.start` (`src/daemon/SessionManager.kt:273`) создаёт новые сессии;
  `resume` (`:451`) уже умеет `LaunchMode.Resume(providerId)` и блокируется `ResumeBlockedException`
  без захваченного id. Импорту нужен только недостающий кусок — запись с готовым provider id.
- `Reconciler.classify`: dead + transcriptExists → `resumable`. Проба существования —
  `VendorStoreProbe`: claude — `access(F_OK)` на `~/.claude/projects/<encoded-cwd>/<id>.jsonl`
  (`src/daemon/ClaudeVendorStoreProbe.kt`); codex — `CodexRolloutScan.hasRollout` по id в имени
  rollout-файла, архив не считается (`src/daemon/CodexRolloutScan.kt`).
- **`SessionManager` сегодня НЕ имеет `VendorStoreProbe`** — проба строится в `Commands.daemon`
  только для `Reconciler`. Импорту нужны проба + локатор как новые параметры конструктора,
  и их обязан передать продакшен-сайт `SessionManager(...)` в `src/cli/Commands.kt` (~:369).
- **`AgentFactory` — `fun interface { create(agentKind, cwd) }`**; `agentFactoryOf` закрывает map
  builders приватно, «какие kind поддержаны» спросить нельзя, а `create()` гоняет
  `requireAbsoluteBinary` → `AgentBinaryNotFoundException`. Для импорта нужен отдельный источник
  поддержанных kind'ов (см. Technical Details).
- `encodeClaudeProjectDir` необратим → `cwd` по id ищется **сканом** `projects/*/` на `<id>.jsonl`
  + чтением поля `"cwd"` из первых строк JSONL. У Codex `cwd` — в первой строке rollout
  (`session_meta`), парсинг уже есть в `CodexRolloutScan.discoverSessionId` (приватные
  `listDir`/`readHead` — извлечь для переиспользования, не дублировать).
- `ProviderIdCapture.bind` (`src/daemon/ProviderIdCapture.kt:56`) идемпотентно добавляет
  `SessionBound` в event log — replay восстановит provider id.
- `SqliteEventStore.append` (`:238`) никогда не воскрешает мёртвое кэш-состояние → строка
  `resumable` переживает append `SessionBound`. Проверено при дизайне.
- Сериализатор `ProviderSessionId` зовёт primary constructor → кривой UUID из body кидает
  `IllegalArgumentException`, **не** `SerializationException` → существующий catch в роутах его
  пропустит (500). DTO-поле id должно быть `String` (как в `StartSessionRequest`).
- `resume()` пишет только `state`/`state_source`/`pane_id`/`updated_at`;
  `captureModelInBackground` зовётся **только из `start`** — без правки у импортированной
  codex-сессии `model` не появится никогда.
- CLI `runStart` резолвит cwd через `resolveCwdAgainst(currentWorkingDir(), …)` +
  `UnresolvableCwdException` (exit 2) — демон живёт под launchd с cwd `/`, относительный путь
  туда отправлять нельзя. `import --cwd` обязан делать то же.
- Роуты действий: `src/transport/ControlRoutes.kt:213`; тесты роутов — `test/transport/TransportTest.kt`
  (харнес собирает настоящий `SessionManager` над `FakeTmux` + `FakeEventStore`). Прецедент
  wiring-теста: `test/transport/AuthorizeWiringTest.kt`.
- Web UI: диалог новой сессии — `resources/webui/components/dialogs.js` (там же `CLI_HELP` и список
  Controls), API-клиент — `resources/webui/lib/api.js`, метки агентов — `lib/agents.js`.
- `SessionMeta` (`src/core/SessionMeta.kt`): `providerSessionId`/`cliVersion`/`cliPath`/`model`
  nullable; но `tmuxSession`/`name`/`createdAt`/`updatedAt` обязательны (NOT NULL в `Sessions.sq`),
  и `resume()` читает `meta.tmuxSession` (`isPaneAlive`).

## Development Approach

- **testing approach: TDD** — в каждой задаче сначала тесты (падают), затем реализация до зелёного
- каждая задача завершается полностью до перехода к следующей; изменения маленькие и сфокусированные
- **CRITICAL: каждая задача обязана включать новые/обновлённые тесты** — success и error сценарии,
  отдельными пунктами чек-листа
- **CRITICAL: все тесты зелёные перед следующей задачей** — без исключений
- **CRITICAL: план обновляется при изменении скоупа по ходу реализации**
- `./kotlin build` **перед** `./kotlin test` (PtyTest execs ptycheck); изменённые JS-модули — `node --check`
- обратная совместимость: существующие start/reconcile не меняют поведения; `resume()` получает
  одно аддитивное улучшение (model capture, см. Technical Details)

## Testing Strategy

- **unit**: чистая логика отдельно от POSIX-края (паттерн KT-78062) — парсинг `cwd` на строках,
  FS-края на временных каталогах (`claudeDir`/`codexDir` инжектируются; реальные `~/.claude`/`~/.codex`
  не трогаем — probe/scan только читают); `importSession` — на фейках store/probe/locator/tmux
- **wiring**: новые параметры конструктора `SessionManager` — **без default-значений** (каждый
  call-site обязан выбрать), плюс wiring-тест по образцу `AuthorizeWiringTest` — против бага
  «фейки в тестах зелёные, а в продакшен ушла заглушка» (уже случался в этом репо, см. шапку
  `ClaudeVendorStoreProbe.kt`)
- **e2e**: JS-харнеса в репо намеренно нет — `node --check` для изменённых модулей, serving-контракты
  в `test/transport/WebUiServingTest.kt` (только если появится новый файл), браузерное поведение —
  ручной чек-лист в Post-Completion

## Progress Tracking

- завершённые пункты — `[x]` сразу, не пачкой
- новые обнаруженные задачи — с префиксом ➕
- проблемы/блокеры — с префиксом ⚠️
- план синхронизируется с фактически сделанной работой

## Solution Overview

```
CLI `kotgent import` ─┐
                      ├─► POST /sessions/import ─► SessionManager.importSession (под import-Mutex):
Web UI (Import) ──────┘        agent ∈ supportedAgentKinds? → дубликат providerSessionId?
                               → cwd (явный | VendorSessionLocator) → access(cwd)
                               → VendorStoreProbe.hasTranscript(agent, cwd, id)   ← та же пара, что
                               → upsertSession(resumable, полная строка)             увидит Reconciler
                               → ProviderIdCapture.bind (SessionBound в event log)
затем клиент (CLI по умолчанию / Web UI по умолчанию, оба с opt-out) ─► существующий POST /sessions/{id}/resume
```

Ключевые решения:

- **Импорт side-effect-free** (без tmux-побочек); запуск — только существующий `resume()`. Нет
  дублирования launch-логики и компенсаций; неудачный запуск оставляет сессию честно `resumable`.
- **Event log остаётся истиной**: provider id попадает и в строку, и в `SessionBound` через
  `ProviderIdCapture.bind` — replay согласован.
- **Бинарник агента при импорте не проверяем** — fail-fast с подсказкой `kotgent install` уже есть
  в `resume()`; импорт валидирует kind по `supportedAgentKinds` (см. Technical Details), и тест
  прямо фиксирует: импорт поддержанного kind проходит при отсутствующем бинарнике.
- **Консистентность discovery ↔ probe**: шаг-проба (`hasTranscript`) выполняется с **тем же** `cwd`,
  который будет сохранён в строку — и это тот же `(agent, cwd, id)`, которым `Reconciler` будет
  ре-пробировать при каждом старте демона. Discovery, чей `cwd` не проходит пробу (например,
  записанный в транскрипте claude `cwd` ре-энкодится не в тот каталог: `/tmp` vs `/private/tmp`),
  даёт понятную ошибку, называющую `--cwd` как обходной путь — а не сессию, которая после
  рестарта молча деградирует `resumable → crashed`.
- **409 на дубликат**, а не «вернуть существующую»: в теле id существующей kotgent-сессии
  (+ пометка, если она архивная — тогда правильный ход Restore, не импорт). Дубликат-проверка и
  запись выполняются под одним daemon-wide import-`Mutex` — два конкурентных импорта одного id
  не дают двух строк.
- **Почему discovery в v1, а не только явный cwd**: у Web UI/телефона нет pwd — ручной ввод
  абсолютного пути на телефоне и есть главная точка ошибок; у codex discovery почти бесплатен
  (id в имени файла, парсинг первой строки уже есть), claude-скан — единственная нетривиальная
  часть, и она ограничена одним файлом.

## Technical Details

- **Новый seam** `fun interface VendorSessionLocator { fun cwdOf(agent: String, id: ProviderSessionId): String? }`
  (`src/daemon/`), продакшен-фабрика диспатчит по agent kind (как `byAgentVendorStoreProbe`):
  - claude: по инжектируемому `claudeDir` — один `opendir` по `projects/`, затем `access()` на
    `<dir>/<id>.jsonl` в каждом подкаталоге (не листинг всех транскриптов); при находке — чистая
    `claudeTranscriptCwd(lines)`: первые ~25 строк, поле `"cwd"`; первое совпадение (id — UUID)
  - codex: `CodexRolloutScan.cwdOf(id)` — rollout по id в имени файла, `cwd` из первой строки;
    приватные POSIX-хелперы `listDir`/`readHead` **извлекаются** для переиспользования claude-сканом
    (public — toolchain 0.11 не даёт тестам видеть `internal`); архивные rollout не считаются
- **`supportedAgentKinds`**: builders-map строится в одном месте (`Commands`, там же где
  `agentFactoryOf`); из **той же** map берутся и фабрика, и `builders.keys` → оба передаются в
  `SessionManager`. Один источник истины, никакого второго списка kind'ов.
- **`SessionManager.importSession(agentKind, providerId, cwd?, name?, tags?)`** — новые параметры
  конструктора (`vendorProbe`, `sessionLocator`, `supportedAgentKinds`) **без default-значений**;
  весь метод под daemon-wide import-`Mutex` (импорты редки, простота важнее параллелизма). Порядок:
  1. `agentKind !in supportedAgentKinds` → ошибка (адаптер не создаётся, бинарник не трогается)
  2. дубликат `providerSessionId` среди сессий kotgent, включая архивные → conflict c existingId
  3. `cwd`: явный параметр побеждает; иначе `sessionLocator.cwdOf`; не нашли → ошибка
  4. `access(cwd, F_OK)` → понятная ошибка, если каталог проекта удалён
  5. `vendorProbe.hasTranscript(agent, cwd, id)` с тем самым `cwd`, что пойдёт в строку → ошибка
     с текстом, называющим `--cwd` (claude-mismatch) и архивные codex-сессии как причины
  6. полная строка: `freshSessionId()` → `shortId` → `tmuxSession = tmux.sessionName(shortId)`
     (чистый форматтер, не side-эффект), `name = name ?: tmuxSession`, `state = resumable`,
     `providerSessionId = id`, `paneId = null`, `cliVersion/cliPath/model = null`,
     `createdAt = updatedAt = now()` → `upsertSession` → `idCapture.bind(sessionId, providerId)`
  - сбой `bind` после `upsertSession` — **принятый residual**, записать в KDoc: строка несёт
    providerSessionId без `SessionBound` в логе; `resume` читает строку, поэтому функционально
    сессия жива, расхождение replay ограничено provider id импортированной сессии
- **`resume()` — одно аддитивное улучшение**: после успешного запуска зовёт существующий seam
  `captureModelInBackground(meta)` (сейчас зовётся только из `start`) — иначе `model`
  импортированной codex-сессии не появится никогда (claude чинится сам: hooks после resume).
  `cliVersion`/`cliPath` остаются null — заполнять их значило бы запускать бинарник при импорте
  (см. Post-Completion, известные ограничения).
- **Типизированные исключения** — все **самостоятельные** (не подтипы друг друга и существующих;
  в этом репо иерархия исключений load-bearing, см. `TmuxCopyModeException`):
  `UnknownAgentKindException`, `ImportCwdException` (нет каталога / discovery не нашёл),
  `TranscriptNotFoundException`, `DuplicateImportException(existingId, archived)`.
- **HTTP-маппинг** (роут ловит каждое отдельно, порядок безразличен — иерархии нет): **400** —
  неизвестный agent, кривой id, cwd-ошибки, транскрипт не найден (по конвенции `controlRoutes`
  404 означает «нет такой сессии `{id}`», а `/sessions/import` не адресует ресурс — поэтому 400
  с различимыми сообщениями, не 404); **409** — дубликат (тело: existingId + archived-пометка);
  **201** + `SessionDto` — успех.
- **`POST /sessions/import`**: body `{agent: String, providerSessionId: String, cwd?, name?, tags?}` —
  id в DTO — **`String`** (как `StartSessionRequest`), `ProviderSessionId` конструируется в
  handler'е с catch на `IllegalArgumentException` → 400 (сериализатор value-класса кинул бы её
  мимо существующего catch `SerializationException` → 500). Авторизация стандартная (`authorize`,
  Origin на POST, cookie или Bearer), **не** loopback-only — как у `/sessions/{id}/resume`.
- **CLI**: `kotgent import <agent> <session-id> [--cwd <dir>] [--name <name>] [--tag <t>]… [--no-start]`.
  `--cwd` резолвится через `resolveCwdAgainst(currentWorkingDir(), …)` ровно как в `runStart`
  (включая `UnresolvableCwdException` → exit 2) — демон живёт с cwd `/`. По умолчанию после 201
  сразу `POST /sessions/{id}/resume`; `--no-start` — только регистрация. На 409 печатает
  existingId и подсказку (`kotgent resume <id>` / Restore); на 400 — сообщение сервера. Один
  новый метод `ApiClient` со стандартным `HttpTimeout`.
- **Web UI**: режим «Import» в диалоге новой сессии (`dialogs.js`) — те же метки агентов, поле
  session id, опциональный cwd, чекбокс «только зарегистрировать» (аналог `--no-start`: телефон —
  клиент, которому труднее всего проверить, не жива ли сессия в чужом терминале); по умолчанию
  import → resume → выбор сессии/открытие терминала; ошибки 400/409 — текстом из ответа в
  обычном месте ошибок формы.

## What Goes Where

- **Implementation Steps** (`[ ]`): код, тесты, документация — всё в этом репозитории
- **Post-Completion** (без чекбоксов): ручная браузерная/мобильная проверка, известные ограничения

## Implementation Steps

### Task 1: VendorSessionLocator — поиск cwd по provider id (Claude + Codex)

**Files:**
- Create: `src/daemon/VendorSessionLocator.kt`
- Modify: `src/daemon/CodexRolloutScan.kt` (извлечь `listDir`/`readHead`, добавить `cwdOf`)
- Create: `test/daemon/VendorSessionLocatorTest.kt`
- Modify: `test/daemon/CodexRolloutScanTest.kt`

- [x] TDD: тесты `claudeTranscriptCwd` — строка с `"cwd"`, `cwd` не в первой строке, отсутствие поля,
      мусорные/пустые строки, пустой файл
- [x] TDD: тесты claude-скана на временном каталоге — `<id>.jsonl` найден в одном из `projects/*/`,
      id отсутствует, каталог `projects` отсутствует
- [x] TDD: тесты `CodexRolloutScan.cwdOf` — существующий rollout возвращает cwd из `session_meta`,
      неизвестный id → null, архивный rollout не считается
- [x] реализовать `claudeTranscriptCwd` (чистая функция) + скан (`opendir` по `projects/` +
      `access()` на `<dir>/<id>.jsonl`, без листинга транскриптов) по инжектируемому `claudeDir`
- [x] извлечь POSIX-хелперы `listDir`/`readHead` из `CodexRolloutScan` (public) и реализовать
      `cwdOf`, переиспользуя first-line парсинг `discoverSessionId`
- [x] `VendorSessionLocator` (fun interface) + продакшен-фабрика с диспатчем по agent kind
- [x] `./kotlin build && ./kotlin test` — зелёные (706 passed / 0 skipped)

### Task 2: SessionManager.importSession + продакшен-wiring

**Files:**
- Modify: `src/daemon/SessionManager.kt`
- Modify: `src/cli/Commands.kt` (wiring: probe + locator + supportedAgentKinds в `SessionManager(...)`)
- Create: `test/daemon/SessionImportTest.kt` (в `SessionManagerTest.kt` уже 33 теста — отдельный файл)

- [x] TDD: happy path — полная строка (`state = resumable`, `providerSessionId`, `paneId = null`,
      `tmuxSession = tmux.sessionName(shortId)`, `name ?: tmuxSession`, `cliVersion/cliPath/model = null`,
      `createdAt`/`updatedAt`), `SessionBound` в event log, state остаётся `resumable` после bind;
      **никаких tmux side-эффектов**: `FakeTmux` записывает вызовы — assert, что new-session/kill/send
      пусты (`sessionName` — чистый форматтер, разрешён)
- [x] TDD: импорт → `Reconciler.reconcile()` → state **остаётся** `resumable` (страж консистентности
      `(agent, cwd, id)`: без него рассинхрон discovery↔probe молча деградирует в `crashed` после
      рестарта демона)
- [x] TDD: импорт поддержанного kind **проходит при отсутствующем бинарнике** (фиксирует решение
      «бинарник не проверяем»; проверка остаётся в `resume()`)
- [x] TDD: дубликат providerSessionId (включая archived-сессию) → `DuplicateImportException` с
      existingId; два конкурентных импорта одного id → ровно одна строка (import-Mutex)
- [x] TDD: неизвестный kind; транскрипт не найден пробой (текст называет `--cwd` и архивные codex);
      discovery не нашёл cwd; явный `cwd` побеждает discovery; cwd-каталог удалён → ошибка;
      claude-mismatch (discovery нашёл файл, но записанный `cwd` ре-энкодится в другой каталог) →
      ошибка, не тихий успех
- [x] TDD: `resume()` зовёт `captureModelInBackground` (model импортированной codex-сессии
      появляется после resume)
- [x] реализовать `importSession` (порядок из Technical Details, import-Mutex, исключения без
      иерархии, residual про сбой `bind` — в KDoc) + `resume()`-однострочник
- [x] wiring: новые параметры конструктора **без default**; `Commands.daemon` передаёт настоящие
      `byAgentVendorStoreProbe(...)` + locator-фабрику + `builders.keys` — через извлечённые
      `productionVendorStoreProbe()` / `productionSessionLocator()` (инжектируемые dirs), которые
      wiring-тест `test/daemon/ImportWiringTest.kt` (по образцу `AuthorizeWiringTest`) гоняет над
      throwaway-домами: реальные locator+probe для claude и codex, claude cwd-mismatch, архивный
      codex rollout
- [x] `./kotlin build && ./kotlin test` — зелёные (722 passed / 0 skipped)

### Task 3: POST /sessions/import

**Files:**
- Modify: `src/transport/ControlRoutes.kt` (+ DTO там, где живут остальные; id — `String`)
- Modify: `test/transport/TransportTest.kt` (харнес получает новые фейки probe/locator)

- [x] TDD: 201 + SessionDto; 400 — неизвестный agent, кривой id (`IllegalArgumentException` из
      конструктора `ProviderSessionId` в handler'е, не 500 из сериализатора), cwd-ошибки,
      транскрипт не найден — различимые сообщения; 409 — existingId в теле + archived-пометка
- [x] TDD: авторизация — Origin обязателен на POST, роут доступен не только с loopback
- [x] реализовать роут + маппинг четырёх исключений на коды
- [x] `./kotlin build && ./kotlin test` — зелёные (727 passed / 0 skipped)

### Task 4: ApiClient + CLI `kotgent import`

**Files:**
- Modify: `src/cli/ApiClient.kt`
- Modify: `src/cli/Cli.kt`
- Modify: `src/cli/Commands.kt`
- Modify: `test/cli/CliTest.kt` (+ тесты Commands там, где живут существующие)

- [x] TDD: parseArgs — `import <agent> <session-id>`, флаги `--cwd/--name/--tag/--no-start`,
      ошибки на недостающие аргументы
- [x] TDD: `--cwd` резолвится через `resolveCwdAgainst(currentWorkingDir(), …)` как в `runStart`;
      нерезолвимый путь → `UnresolvableCwdException` → exit 2
- [x] TDD: Commands.import — успех печатает id и (по умолчанию) резюмит; `--no-start` только
      регистрирует; 409 печатает existingId и подсказку; 400 печатает сообщение сервера
- [x] реализовать метод ApiClient (стандартный HttpTimeout) + команду + `USAGE` в `Cli.kt`
- [x] `./kotlin build && ./kotlin test` — зелёные (738 passed / 0 skipped)

### Task 5: Web UI — режим Import в пикере новой сессии

**Files:**
- Modify: `resources/webui/components/dialogs.js` (диалог + `CLI_HELP`/Controls — новая команда)
- Modify: `resources/webui/app.js` (склейка import → resume → выбор сессии)
- Modify: `resources/webui/style.css` (сегмент-переключатель режима + checkbox-строка; `.field input`
  иначе раздувает чекбокс) — по факту вместо `lib/api.js`, которому правки не понадобились
- Modify: `test/transport/WebUiServingTest.kt` (обновлён закреплённый текст + новый import-контракт)

- [x] режим «Import» в диалоге: выбор агента (существующие метки из `lib/agents.js`), поле
      session id, опциональный cwd, чекбокс «только зарегистрировать»
- [x] сабмит: `POST /sessions/import` → (если не «только зарегистрировать») `POST /sessions/{id}/resume`
      → выбор сессии и открытие терминала; ошибки 400/409 текстом из ответа в обычном месте
      ошибок формы
- [x] обновить `CLI_HELP` в `dialogs.js` — команда `kotgent import` (+ пункт Import в Controls)
- [x] `node --check` на каждый изменённый модуль (dialogs.js, app.js; api.js не потребовал правок —
      `apiRequest` уже универсален)
- [x] если появился новый JS-файл — регистрация в `test/transport/WebUiServingTest.kt` (нового файла
      нет; вместо этого обновлён закреплённый текст ошибки выбора агента и добавлен
      serving-контракт `webUiOffersImportingASessionStartedOutsideKotgent`)
- [x] `./kotlin build && ./kotlin test` — зелёные (739 passed / 0 skipped)

### Task 6: Verify acceptance criteria

- [x] все требования Overview реализованы: CLI-импорт одной командой даёт живую сессию
      (`runImportCommand` регистрирует и резюмит; `importCommandRegistersThenResumesByDefault`);
      `--no-start`/чекбокс оставляют `resumable` без единого tmux-вызова
      (`importCommandNoStartOnlyRegisters`,
      `importRegistersAFullResumableRowAndBindsTheProviderIdWithNoTmuxSideEffects`); Web UI импортирует
      и открывает терминал (`dialogs.js` Import-режим + `app.js` import → resume → выбор;
      `webUiOffersImportingASessionStartedOutsideKotgent`)
- [x] краевые случаи: дубликат (в т.ч. конкурентный) → 409
      (`aDuplicateProviderIdConflictsAndNamesTheExistingSessionIncludingArchived`,
      `twoConcurrentImportsOfTheSameIdYieldExactlyOneRow`, TransportTest 6b); архивный codex rollout →
      400 с подсказкой (`anArchivedCodexRolloutIsNotDiscoverable`); кривой id → 400 (не 500 — catch
      `IllegalArgumentException` в handler'е); удалённый cwd → 400
      (`aDeletedProjectDirectoryFailsBeforeTheProbe`); claude cwd-mismatch → 400 с подсказкой про
      `--cwd` (`aRecordedCwdThatReEncodesElsewhereFailsTheImportLoudly`,
      `aDiscoveredCwdThatFailsTheProbeFailsLoudlyNotSilently`); импорт при отсутствующем бинарнике
      проходит (`importOfASupportedKindSucceedsEvenWhenTheAgentBinaryIsMissing`), `resume` падает с
      подсказкой `kotgent install` (TransportTest, 400 + hint)
- [x] импортированная сессия переживает рестарт демона: `reconcile` держит `resumable`
      (`anImportedSessionStaysResumableThroughReconcile`; wiring-тест гоняет реальные locator+probe
      через `Reconciler`)
- [x] `./kotlin build`, затем `./kotlin test` — полный прогон: 739 passed / 0 failed / 0 skipped
      (baseline вырос с 692)
- [x] `ptycheck` `EXPECTED_CHECKS` не менялся (`test/pty/PtyTest.kt:73` = 11; `git diff` по
      `ptycheck/` и `test/pty/` за все коммиты плана пуст)
- [x] `node --check` на всех изменённых JS-модулях (`node` на машине отсутствует — эквивалентная
      parse-only проверка `Bun.Transpiler.transformSync` для `dialogs.js`, `app.js`, `api.js`: все OK)

### Task 7: [Final] Update documentation

- [x] README: команда `kotgent import` + **рецепт получения id по провайдерам** (claude: пикер
      `claude --resume` или имя транскрипта в `~/.claude/projects/…/<id>.jsonl`; codex: пикер
      `codex resume` или имя rollout-файла `rollout-<ts>-<id>.jsonl`) — usage-блок, bullet под
      `start` с рецептом/дубликатом/caveat, пункт в «In the slice», backlog очищен от
      external-session import; заодно обновлён устаревший счётчик тестов (688 → 739)
- [x] CLAUDE.md: короткая запись — импорт = регистрация `resumable` + `SessionBound`, запуск через
      существующий resume; known limitations (живая копия в чужом терминале; `cliVersion`/`cliPath`
      навсегда null у импортированных); обновить число baseline-тестов (692 → 739; + VendorSessionLocator
      в «Where things live»)
- [x] переместить этот план в `docs/plans/completed/` (перемещение выполняет harness после
      завершения всех фаз — файл намеренно не тронут)

## Post-Completion

**Ручная проверка** (в automation агентов не запускаем — правило репо):
- импортировать реальную claude-сессию, начатую в обычном терминале; убедиться, что resume
  продолжает разговор и hooks снова репортят состояние
- то же для codex; убедиться, что архивный rollout даёт 400 с подсказкой, а `model` появляется
  после resume
- импорт с телефона через PWA/туннель (проверка Origin/cookie-пути), включая чекбокс
  «только зарегистрировать»
- повторный импорт того же id → 409 с понятным текстом в UI и CLI

**Известные ограничения** (записаны, не чинятся):
- сессия, живая в чужом терминале, недетектируема: resume поверх неё запустит вторую CLI-копию
  того же разговора — ответственность оператора
- `cliVersion`/`cliPath` у импортированной сессии остаются null навсегда (заполнить их — значит
  запускать бинарник при импорте, что противоречит «импорт без проверки бинарника»); `model`
  появляется после первого resume (claude — через hooks, codex — через model capture в `resume()`)
- сбой `idCapture.bind` после `upsertSession` оставляет строку без `SessionBound` в логе:
  `resume` работает по строке, расхождение replay ограничено provider id этой сессии
