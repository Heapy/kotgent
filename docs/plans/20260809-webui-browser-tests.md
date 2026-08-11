# Настоящие браузерные тесты Web UI вместо grep по исходникам

## Overview

grep-ярус Web UI — это **десять** файлов, **8136+ строк**, **136** тестов и **1181** вызов `contains(`
поверх served-исходников JS и CSS. HTTP там используется как способ прочитать файл, а утверждения — это
`grep` по тексту.

| Файл | строк | `@Test` | `contains(` |
|---|---|---|---|
| `WebUiServingTest.kt` | 4050 | 49 | 775 |
| `WebUiBoardTest.kt` | 796 | 17 | 117 |
| `WebUiBoardStyleTest.kt` | 585 | 10 | 33 |
| `WebUiScreenRoutingTest.kt` | 551 | 9 | 63 |
| `WebUiTaskDetailTest.kt` | 502 | 13 | 73 |
| `SpaRoutingTest.kt` | 450 | 10 | 6 |
| `WebUiTaskBadgeTest.kt` | 391 | 8 | 24 |
| `WebUiTaskCommandsTest.kt` | 323 | 6 | 31 |
| `WebUiTaskStateTest.kt` | 292 | 8 | 34 |
| `WebUiRouterTest.kt` | 255 | 6 | 25 |

Отсюда две болезни:

1. **Тавтология.** Тест утверждает, что исходник равен самому себе. Переименование локальной переменной
   ломает тест; при этом семантически ломающая правка, сохранившая текст, проходит. Это не проверка
   поведения, а блокировка рефакторинга.
2. **Ложное покрытие.** Инварианты, физически непроверяемые текстом — отдаёт ли `touch-action: none` жест
   терминалу, вычитает ли FitAddon padding, переживает ли drag доски перерисовку под capture, совпадает ли
   `pointerId` у `pointerdown`/`click` — оформлены как проверенные, хотя рядом в CLAUDE.md записано «это
   решается только на живом устройстве».

**Что решаем.** Расслаиваем на три яруса: контракты отдачи остаются в Kotlin, поведение UI начинает
**исполняться** в настоящем браузере, вёрстка проверяется настоящим движком. Классификация всех 136 тестов
выполнена (см. «Диспозиция»): **30 остаются** в Kotlin, **105 переезжают** в браузер, **1 удаляется** без
замены. Бюджет браузерного яруса — **55–70** сценарных тестов (несколько grep-утверждений схлопываются в
один поведенческий).

**Как интегрируется.** Три новых модуля (`fakes`, `webuicheck`, `webuitest`) плюс правки в `ci.yml`.
Ничего в `src/`, в редьюсере, в словаре `AgentEvent` и в tmux/pty-слое не меняется. `resources/webui`
изначально тоже объявлялся неприкосновенным — **это обещание нарушено осознанно**: волна 3 поймала три
настоящих бага продукта, тесты на них верны, и ослаблять их нельзя, поэтому починка вошла в объём
(Task 23a). В остальном:
`KotgentServer` полностью constructor-injected (`src/transport/Server.kt:91` — «everything is
constructor-injected so the whole server is testable end-to-end against fakes»), поэтому браузеру
отдаётся **настоящий** сервер, а подменяются только edges.

**Как исполняется.** План размечен на **шесть волн** (см. «Параллельное исполнение волнами»): внутри волны
задачи имеют попарно непересекающиеся множества файлов и запускаются одновременно, волну закрывает
отдельный проход, который единственный трогает регистры, гоняет валидацию и коммитит. Критическая цепь —
6 звеньев вместо 26, тяжёлых прогонов `build && test` — 6 вместо 26. Полнота проверок `/planning:exec` при
этом не страдает, а при обычном последовательном запуске план исполняется корректно, просто медленно.

## Context (from discovery)

**Файлы и компоненты**

| Область | Файлы |
|---|---|
| Новый модуль двойников | `fakes/` — `FakeTmux`, `FakeEventStore`, `FakeTaskStore`, `FakeProjectFs`, `MemoryProjectFileWriter` |
| Новый сценарный харнесс | `webuicheck/` |
| Новый браузерный ярус | `webuitest/` |
| Манифесты | `project.yaml`, `module.yaml`, `gradle/libs.versions.toml` |
| Сокращаемые/удаляемые тесты | десять файлов из таблицы Overview |
| Новый нативный тест харнесса | `test/transport/WebUiCheckTest.kt` |
| CI | `.github/workflows/ci.yml`, `.gitignore` |
| Документация | `CLAUDE.md` |

**Образцы, которые копируем (якоря проверены против HEAD)**

- Манифест main-бинарника-фикстуры: `ptycheck/module.yaml`.
- **Драйв фикстуры из сюиты:** `test/pty/PtyTest.kt:34-48` — `ProcessRunner.run` экзекает бинарник и
  сверяет `SUMMARY total=$EXPECTED_CHECKS failed=0`; поиск бинарника — `:55`, счётчик — `:73`.
- Конструктор сервера: `src/transport/Server.kt:150`; `terminalBridgeFactory` — `:155` (KDoc `:121`);
  `directoryCompleter`/`fileUploader` — `:156-157`; `taskStore`/`taskService` — `:165-166`; монтирование
  роутов под префиксом — `:234-251`; task-роуты — `:256-268`; push-роуты — `:273-275`;
  `reuseAddress = true` — `:295`; `stop()` — `:338`; `resolveWebUiDir` (`internal`) — см. companion.
- Тикеты: `src/transport/Tickets.kt:154` (`class TicketStore`), `:184` (`issue(boundToken)`).
- Терминальный мост: `src/pty/TerminalBridge.kt:50` — `upstreamCommand`, `seedProvider`, `ptyFactory`,
  `scope`, `env`. Харнесс собирает его напрямую с `realPtyFactory` (`src/pty/RealPtyHandle.kt:38`);
  `terminalBridgeForSession` (`src/pty/RealPtyHandle.kt:73`) не подходит — требует конкретный `Tmux`.
- Двойники-прототипы: `FakeEventStore` — `test/transport/TransportTest.kt:1907`; `FakeTaskStore` —
  `test/transport/TaskEventsTest.kt:445`; интерфейсы `ProjectFs` (`src/task/ProjectFs.kt:16`) и
  `ProjectFileWriter` (`src/task/ProjectFileWriter.kt:51`). `TaskService` — **класс**, не интерфейс
  (`src/daemon/TaskService.kt:51-57`), собирается настоящий поверх фейков.
- **`withServer` (`test/transport/WebUiServingTest.kt:3728`) — уже НЕ годный образец**: его вызов
  `KotgentServer` (`:3853-3862`) не передаёт `taskStore`/`taskService`, поэтому копирование рецепта
  воспроизведёт именно ту поломку, которую харнесс обязан избежать.
- JVM-модуль с тестами: `plugins/build-info/`.

**Зависимости**

- `com.microsoft.playwright:playwright:1.62.0` — единственная новая внешняя зависимость.
- Никакого npm: `package.json`, `node_modules` и `npx playwright install` не появляются.
- **Dependabot её не подхватит.** `.github/dependabot.yml` прямо фиксирует, что `gradle/libs.versions.toml`
  намеренно не отслеживается (gradle-экосистема Dependabot требует `build.gradle(.kts)`, которого здесь нет
  by design). Версию бампаем руками.

## Факты, добытые спайками (проверено — НЕ перепроверять)

1. **Граф `рут(test) → fakes → рут(main)` тулчейн принимает.** Модель грузится без ошибки цикла, задачи
   выстраиваются в DAG (`:kotgent:compileMacosArm64TestDebug -> :fakes:compileMacosArm64Debug ->
   :kotgent:compileMacosArm64Debug`), тестовый фрагмент компилируется, линкуется и тест **проходит**.
2. **Playwright for Java 1.62.0 работает этим тулчейном из Kotlin JVM-модуля.** Зависимость резолвится,
   реальный Chromium запускается и управляется, `PlaywrightAssertions.assertThat(locator)` с
   авто-ожиданием доступен. WebKit скачался автоматически. Модуль был `jvm/lib` с пустым `src/`.
3. **Ловушка: имя тестового класса обязано кончаться на `Test`.** JUnit Platform фильтрует по `.*Tests?`;
   класс `PwSpike` компилировался, линковался и молча давал «0 tests found» с **ненулевым** кодом.
4. **Ловушка (измерено): тач в WebKit непригоден.** Chromium с `hasTouch: true` на `touchscreen().tap()`
   даёт полную цепочку `pointerdown:touch#2 | touchstart | pointerup:touch#2 | touchend | click:touch#2` —
   **один и тот же `pointerId` на down, up и click**, ровно инвариант light dismiss. WebKit на тот же вызов
   не доставил элементу **ничего**. Синтетический `dispatchEvent(new PointerEvent(...))` работает одинаково
   в обоих, но проверяет только наши слушатели против выдуманных событий — не `touch-action`, не
   compatibility-burst, не capture. Пометка CLAUDE.md «решается только на живом iPhone» **остаётся в силе**.
5. `plugins/build-info` — прецедент JVM-модуля с тестами; `./kotlin task :build-info:testJvm` работает.
   Это же даёт быструю петлю: модульные задачи запускаются отдельно от агрегата.
6. Chromium и WebKit уже в `~/Library/Caches/ms-playwright` (~1.2 ГБ).
7. Тулчейн просит держать `modules:` в `project.yaml` отсортированным по алфавиту.

## Ограничения (проверены по коду — НЕ переоткрывать)

1. **`ProcessRunner` не умеет писать в stdin ребёнка.** `src/tmux/ProcessRunner.kt:106` — `popen(cmd, "r")`;
   `src/push/VapidSigner.kt:30-31` фиксирует это как осознанное свойство. `posix_spawn` в тестовый бинарник
   не линкуется (KT-78062), ручной `fork`+`exec` небезопасен под рантаймом K/N. **Следствие:** нативная
   сюита не драйвит stdin-протокол; stdin принадлежит исключительно JVM-фикстуре.
2. **Сеанса «уронить сокеты, не трогая порт» не существует.** Наружу выставлен только `stop()`
   (`src/transport/Server.kt:338`), который гасит и слушателя, и pty. Команда `drop-ws` не вводится; её
   потребитель закрывается закрытием сокета из `page.evaluate`.
3. **`NoopEventStore` не обслужит сценарий с сессиями** (`test/transport/WebUiServingTest.kt:3871`).
   Нужен `FakeEventStore` (`test/transport/TransportTest.kt:1907`). Он **намеренно держит собственный
   небуферизованный `reliableSessionUpdates`** — сохранить при извлечении, а не заменять дефолтом
   интерфейса.
4. **`stop()` гасит терминальные мосты первым.** `restart` убивает pty-ребёнка, поэтому «терминал вернулся
   с прежним содержимым» ложно by construction; ассерт — «сокет переоткрылся и новые байты идут».
5. **Весь token-gated API живёт под `/api/v1`.** `src/transport/Server.kt:84`
   (`const val API_PREFIX = "/api/v1"`), роуты — `:234-251`. Голый `/version` теперь 404. **Вне префикса
   намеренно остаются `/auth`, `/auth/exchange`, `/auth/ticket` и `/hooks/*`** — логин тикетом не меняется,
   и «чинить» его не надо. Браузер знает префикс в одном месте: `resources/webui/lib/api.js`.
6. **Без `taskStore`/`taskService` ломается ЛЮБОЙ сценарий, а не только доска.** Параметры —
   `src/transport/Server.kt:165-166`, роуты монтируются только когда оба не-null (`:256-268`), а
   `eventsWs(store, prefs, taskStore, json)` (`:250`) без него молча теряет task-ветку сокета. При этом
   `app.js` читает `GET /api/v1/projects` безусловно на каждом монтировании, поэтому 404 кладёт красную
   строку в статус сайдбара во всех сценариях.
7. **`FakeEventStore` «как есть» БРОСАЛ на task-связях.** `setTaskRef`, `setProjectId`,
   `sessionsHoldingTask` и — обнаружено волной 1 — `clearTaskRefIf` имеют дефолты интерфейса, кидающие
   исключение; без переопределения любой link/unlink/`transition(done)` из браузера даёт 500 вместо
   бейджа. **Закрыто волной 1**: все четыре переопределены, см. «Модуль `fakes`».
8. **Пишущих на диск edges теперь ТРИ.** К `posixDirectoryCompleter`/`posixFileUploader`
   (`src/transport/Server.kt:156-157`) добавился `ProjectFileWriter`, достижимый из браузера двумя формами
   доски. `directoryCompleter` тоже получил второго потребителя — поле пути в New project.
9. **У SPA появился клиентский роутер и deep-link.** Выбор сессии **меняет `location.pathname`**; есть
   маршруты `/s/{id}` и `/tasks/{ref}`, серверный SPA-fallback и `popstate`. Это меняет ассерты задач
   сайдбара и реаттача.

## Development Approach

- **testing approach**: Regular — с оговоркой: начиная с волны 3 «тест» и есть продукт задачи.
- **Исполнение волнами.** Внутри волны задачи запускаются одновременно; см. отдельный раздел ниже. При
  обычном последовательном `/planning:exec` план тоже корректен — секции идут по порядку.
- **КРИТИЧНО: участник волны не запускает `./kotlin` и не трогает git** — валидацию и коммит делает
  закрывающий проход волны.
- **КРИТИЧНО: план обновляется, если объём меняется по ходу.**
- `./kotlin build` **обязателен перед** `./kotlin test` — теперь по двум причинам: `PtyTest` экзекает
  `ptycheck`, а `WebUiCheckTest` и `webuitest` — `webuicheck`.
- **Цена агрегата растёт осознанно.** После волны 2 `./kotlin test` требует браузеров и сети на холодной
  машине. Быстрые петли: `./kotlin task :kotgent:testMacosArm64Debug`, `./kotlin task :webuitest:testJvm`.
- Обратная совместимость: `resources/webui` и `src/` не трогаем вообще.

## Testing Strategy

- **нативная сюита**: остаётся зелёной. Baseline на старте — **1432 нативных теста / 0 skipped**, 7
  JVM-тестов `build-info`, 11 проверок `ptycheck`. По мере переезда число нативных **уменьшается**, поэтому
  приёмка сверяет не абсолют, а «0 skipped + дельта равна числу удалённых тестов».
- **самопроверка харнесса — только то, чего нативный тест не может.** `webuicheck --self-check` держит
  1–2 проверки, зависящие от cinterop (настоящий `Pty` под настоящим `TerminalBridge` внутри настоящего
  сервера), печатает `SUMMARY total=N failed=0`, и `EXPECTED_CHECKS` становится **константой, которую не
  правит ни одна другая задача**. Всё остальное про харнесс проверяет браузерный ярус, который эти же
  факты трогает первым. Причина ограничения — прецедент `ptycheck` действует только там, где действует
  KT-78062; тащить под него обычные проверки значит менять внятные ассерты на самодельный счётчик.
- **e2e/браузерные тесты**: `webuitest`, Playwright for Java, ~55–70 тестов.
- **Движок по умолчанию — Chromium** (`hasTouch: true`). WebKit **не вводится авансом**: ни одно
  запланированное утверждение его не требует, а всё, ради чего он был бы нужен (safe-area, `dvh`,
  `navigator.standalone`), план сам отправляет в ручную проверку. WebKit добавляется точечно и только под
  тест, который на нём **измеримо** расходится с Chromium, и расхождение фиксируется как факт — так же, как
  факт 4 зафиксировал непригодность тача.
- **правило геометрии**: утверждения о вёрстке читают геометрию (`getBoundingClientRect`, `cols`/`rows`,
  видимость, computed color в двух состояниях), а не строковое равенство `getComputedStyle`.
- **правило замещения**: у восьми новых grep-файлов единственный владелец, поэтому там правило сильное —
  **браузерный тест удаляет свой grep-файл в том же коммите волны**. Для `WebUiServingTest.kt` владельцев
  девять, поэтому удаления из него физически выполняет одна финальная задача, а связь пинуется строкой
  `[replaces] task N: WebUiServingTest.<имя>` в прогресс-файле и сверяется там же.
- диагностика падений: скриншот и `page.content()` в лог; `context.tracing()` в артефакт CI.

## Progress Tracking

- выполненные пункты помечать `[x]` — это делает **закрывающий проход волны**, не участник;
- новые задачи добавлять с префиксом ➕;
- блокеры фиксировать с префиксом ⚠️;
- участники пишут только однострочные записи `task N: …` и `[replaces] task N: …`.

## Solution Overview

**Ярус 1 — контракты отдачи (Kotlin).** ~30 тестов в `WebUiServingTest.kt` + `SpaRoutingTest.kt` целиком
(последний — настоящий HTTP-тест грамматики SPA-fallback, а не grep). Плюс три-четыре маленьких
source-guard'а, которые именно исходником и обязаны быть: замок «словаря классов» доски, сверка
`DEEP_LINK_PARAM` с `sw.js`, запрет `pushState` в обход роутера, импорт `PROJECT_NAME_MAX_LENGTH`
(константа нативного рут-модуля, JVM-модуль `webuitest` её не видит).

**Ярус 2 — сценарный харнесс `webuicheck` (macos/app).** Калька с `ptycheck`. Поднимает **настоящий**
`KotgentServer` на фейковых edges, печатает порт и одноразовый тикет, принимает команды на stdin, умирает
по EOF или сторожевому таймеру. Main-бинарник линкует cinterop (KT-78062 бьёт только по тестовым
бинарникам), поэтому терминал сажается на **настоящий** `Pty`.

**Ярус 3 — браузерный `webuitest` (jvm/lib).** Playwright for Java спавнит харнесс `ProcessBuilder`-ом,
логинится тикетом через настоящую форму `/auth`, гоняет реальный DOM, жесты и вёрстку.

**Почему харнесс, а не «Kotlin-тест поднимает сервер».** Один сервер на прогон означал бы фиксированный
сценарий, текущее между тестами состояние и необходимость либо тестовых роутов в проде, либо моков в
браузере — то есть возврат к проверке фикции.

**Почему Java-биндинг Playwright, а не npm.** Зависимость объявляется как обычная библиотека каталога;
тесты остаются на Kotlin; вход остаётся один. `resources/webui` сохраняет свойство `no-build`.

## Technical Details

### Модуль `fakes` (kmp/lib, macosArm64) — ГОТОВ (волна 1, `da8bfb1`)

Двойники, видимые **и** рутовому тестовому сорс-сету (`test-dependencies: - ./fakes`), **и** `webuicheck`
(обычные `dependencies`). Модуль существует; ниже — его **фактические** сигнатуры, а не задание. Волна 2
кодирует против этого текста и файлы модуля не открывает.

```
io.kotgent.daemon.FakeTmux(seedPanes: List<TmuxPane> = emptyList()) : TmuxControl
io.kotgent.store.FakeEventStore(now: () -> Long = { 1L }) : EventStore, PreferencesStore
io.kotgent.store.FakeTaskStore(updatesBuffer: Int = 1024, now: () -> Long = { 1_000L }) : TaskStore
io.kotgent.task.FakeProjectFs(dirs: List<String> = emptyList(),
                              files: Map<String, String> = emptyMap(),
                              symlinks: Map<String, String> = emptyMap()) : ProjectFs
io.kotgent.task.MemoryProjectFileWriter(fs: FakeProjectFs,
                                        newId: () -> ProjectId = { ProjectId.mint() }) : ProjectFileWriter
```

Что важно знать потребителю:

- **`fakes/module.yaml` называет `$libs.kotlinx.coroutines.core` явно**, вопреки исходному заданию
  «`dependencies: - ..`». Рутовый модуль тянет корутины **без** `exported: true`, поэтому потребителю `..`
  не видны `Mutex`/`SharedFlow`/`Channel`, из которых собран каждый двойник. Не откатывать.
  `webuicheck` получает корутины транзитивно через `../sysnative` (тот их экспортирует); если там всё же
  не разрешится `Mutex`/`SharedFlow` — причина эта, и лечится прямой записью в `webuicheck/module.yaml`,
  а не правкой `fakes`.
- **Бросающих дефолтов `EventStore` оказалось четыре, не три.** К `setTaskRef`, `setProjectId`,
  `sessionsHoldingTask` добавился `clearTaskRefIf`, чей дефолт вдобавок неатомарен (get-then-set), а фейк
  достижим с движковых потоков сервера — проверка и запись сведены в один шаг под его `Mutex`. Его
  `emitFromMeta` теперь несёт `taskRef`/`projectId`: именно из `taskRef` рисуется бейдж.
- **`FakeTaskStore` шире прототипа**: все 24 члена, без заглушек. Он выводит `blocked`, а не принимает его
  seed-ручкой, перештампывает и переизлучает обратные зависимости при смене состояния, использует
  настоящую арифметику зазоров и проверяет все четыре отказа по зависимостям через `wouldCycle`.
  Прототипных `baselineEntered`/`baselineGate` в нём **нет** — сценарий не может гейтиться на входе в
  baseline. `updatesBuffer = 0` превращает `taskUpdates` в рандеву.
- **`MemoryProjectFileWriter` публикует в тот же `FakeProjectFs`**, поэтому `SafeEdges.kt` обязан
  конструировать их вместе, а не порознь — иначе второе создание в том же каталоге не подхватит первый
  uuid. Он выставляет `calls` и набор `failOn` для пути «место недоступно для записи».
- `FakeProjectFs` держит CAS-подменяемый неизменяемый снимок: методы `ProjectFs` не `suspend`, корутинный
  `Mutex` им недоступен.

Пять существовавших `private class FakeEventStore` были **вложенными** (`TaskLinkRoutesTest.kt`,
`TaskWriteRoutesTest.kt`, `TaskReadRoutesTest.kt`, `TransportTest.kt`, `TaskServiceTest.kt`), коллизии имён
не возникло; правился только `TransportTest.kt`.

### Модуль `webuicheck` (macos/app, macosArm64)

Зависимости: `..`, `../sysnative`, `./fakes`. `entryPoint: io.kotgent.webuicheck.main`.

**Режимы.** `--self-check` — прогоняет cinterop-зависимые проверки в процессе, печатает
`SUMMARY total=N failed=0`, выходит; **stdin не читает**, поэтому идёт через `popen`.
`--scenario=<name> --webui-dir=<abs> [--exit-after-ms=<n>]` — рабочий режим.

**Хендшейк — ровно три строки в stdout, всё остальное в stderr:**

```
PORT=<n>
TICKET=<code>
READY
```

Тикет — настоящий `TicketStore.issue(token)`. После `restart` печатается **только** повторный `READY`.

**Жизненный цикл.** EOF на stdin → graceful stop, выход `0`. Плюс `--exit-after-ms` как страховка от
подвисшего драйвера. Мусор во входе — строка в stderr и **ненулевой выход**: фикстура обязана падать
громко.

**Команды stdin — у каждой назван потребитель:**

| Команда | Что делает | Потребитель |
|---|---|---|
| `restart` | стоп и подъём на **том же** порту, с **тем же** `TokenHolder`, `TicketStore`, `FakeTaskStore` и `TaskService` | задача реаттача |
| `emit <id> <state>` | `SessionUpdate` в поток `/api/v1/events` | задача бейджей и notify-edge |
| `task <ref> <state>` | `TaskUpdate` с новым rev → фрейм `task_update` | задачи доски и состояний |
| `task-add <ref>` | ref, которого сокет ещё не нёс → `task_row` | задача доски |
| `task-del <ref>` | `TaskUpdate` с `entry = null` → `task_removed` | задача доски |
| `task-race <ref>` | шаг состояния на уровне стора, без сессионных side-effect'ов → `task_update` | старшая половина гонки newest-rev-wins |

`restart` на том же порту возможен потому, что `src/transport/Server.kt:295` уже ставит
`reuseAddress = true`. **Тот же `TokenHolder` обязателен** — cookie есть `HMAC-SHA256(master-token,
"v1|"+issuedAt)`, новый токен разлогинил бы все страницы. **Тот же `FakeTaskStore`/`TaskService`
обязательны** — иначе после restart браузер получает 404 на `/api/v1/projects` и пустую доску там, где до
restart всё работало. Вложенный `runBlocking` в `stop()` нельзя звать из корутины на однопоточном
диспетчере.

**Безопасность edges.** `SafeEdges.kt` подставляет фейковый `DirectoryCompleter` (два потребителя: New
session и поле пути New project), записывающий-в-память `FileUploader`, `FakeProjectFs` и
`MemoryProjectFileWriter`. `webUiDir` приходит абсолютным аргументом (`resolveWebUiDir` — `internal`).

**Сценарии** — одна `Map<String, Scenario>`; второго списка имён нет.

| Сценарий | Содержимое | Потребитель |
|---|---|---|
| `empty` | сессий нет, задач нет | первый запуск, пустые состояния |
| `sessions` | сессии в разных состояниях, cwd `/a/b`, `/a/c`, `/d` | сайдбар, дерево, маршруты |
| `attention` | сессия готова уйти в `needs_approval` | бейджи, notify-edge |
| `terminal` | настоящий pty | терминал, свайп, геометрия, реаттач |
| `restart` | store продолжает сообщать сессию живой после перезапуска | реаттач |
| `board` | проект с задачами во всех колонках | доска, drag-and-drop |
| `board-empty` | проект без задач | пустая доска |
| `task-detail` | задача с комментариями и зависимостями | детали задачи |
| `task-linked-session` | задача, связанная с сессией | бейджи, link/unlink |
| `deep-link` | старт браузера прямо на `/s/{id}` и `/tasks/{ref}` | роутер, deep-link |

**Терминал.** `TerminalBridge` собирается напрямую с `realPtyFactory` и детерминированной командой
(`/bin/sh -c 'printf …; cat'`) — воспроизводимо побайтно.

**Чего харнесс не делает:** не пишет в `~/.kotgent`, не поднимает tmux-сервер, не спавнит агентов, не
трогает SQLite, не пишет через uploader, **не создаёт `.kotgent.json` нигде на диске**.

### Модуль `webuitest` (jvm/lib)

`test-dependencies: - $libs.playwright`; в `gradle/libs.versions.toml` нужны **обе** записи —
`playwright = "1.62.0"` в `[versions]` и строка в `[libraries]`. `src/` может остаться пустым.

**Каталог артефактов заморожен волной 1: `test-results/`.** CI выгружает скриншоты и трейсы падений
только из `test-results/` и `webuitest/test-results/` (оба в `.gitignore`), поэтому браузерные тесты
волны 3 обязаны писать `page.screenshot` и архивы `context.tracing()` именно туда — иначе красный прогон
выгрузит пустоту. Кэш браузеров в CI ключуется литералом `1.62.0` **без** `restore-keys`, так что версию
Playwright надо бампать в `ci.yml` и `gradle/libs.versions.toml` одним коммитом.

**API фикстуры замораживается на волне 2** и после этого не меняется; участнику волны 3, которому нужен
хелпер, кладёт его в свой файл:

```
class Harness(scenario: String) : AutoCloseable   // спавн, парсинг хендшейка с таймаутом
    val port: Int; val ticket: String; val baseUrl: String
    fun send(line: String)                        // команда в stdin
fun BrowserContext.loginWithTicket(ticket: String, baseUrl: String)
fun touchChromium(pw: Playwright): Browser        // hasTouch = true
```

**Cookie не привязана к порту**, поэтому переиспользования состояния между харнессами нет: каждый тест
получает свежий `BrowserContext` и логинится своим тикетом.

**Правило имён:** каждый класс обязан кончаться на `Test` (факт 3).

### Диспозиция

Итог классификации всех 136 тестов: **30 keep-in-kotlin**, **90 move-to-browser**, **15
split-keep-and-move**, **1 delete-no-replacement**.

**Пофайлово, с владельцем переезда:**

| Файл | всего | keep | переезд | Задача-владелец |
|---|---|---|---|---|
| `SpaRoutingTest.kt` | 10 | 10 | 0 | — (не трогаем) |
| `WebUiServingTest.kt` | 49 | 17 | 31 (+1 DEL) | задачи 9–18, удаление — задача 24 |
| `WebUiBoardTest.kt` | 17 | 1 | 16 | задача 19 |
| `WebUiBoardStyleTest.kt` | 10 | 0 | 10 | задача 20 |
| `WebUiTaskDetailTest.kt` | 13 | 0 | 13 | задача 21 |
| `WebUiScreenRoutingTest.kt` | 9 | 0 | 9 | задача 22 |
| `WebUiRouterTest.kt` | 6 | 1 | 5 | задача 22 |
| `WebUiTaskBadgeTest.kt` | 8 | 0 | 8 | задача 17 |
| `WebUiTaskStateTest.kt` | 8 | 1 | 7 | задача 17 |
| `WebUiTaskCommandsTest.kt` | 6 | 0 | 6 | задача 18 |

**`WebUiServingTest.kt` — поимённо.** KEEP (17): `daemonServesIndexHtmlAtRoot`,
`daemonServesTheAppEntryModule`, `theImportMapResolvesToVendoredModulesThatAreActuallyServed`,
`daemonServesTheComponentAndLibModules`, `daemonServesTheWebManifestWithItsOwnMediaType`,
`daemonServesTheAppleTouchIconAndTheSourceArtwork`, `indexHtmlDeclaresThePwaInstallSurface`,
`revisionedAssetsAreImmutableAndEverythingElseRevalidates`,
`theServedShellCarriesARealRevisionAndNoHandBumpedToken`, `theRevisionPrefixOnlyChangesTheAddress`,
`strippingTheRevisionPrefixLeavesTraversalVisibleToTheGuard`, `anyChangedByteChangesTheRevision`,
`daemonServesTheServiceWorkerAtTheRootScope` (grep-половина про тело воркера → DEL),
`daemonServesTheVendoredXtermFromANestedPath`, `daemonServesTheStylesheets`, `aMissingStaticFileIs404`,
`theStaticCatchAllDoesNotShadowTheTokenGatedApi`, `versionApiIsAuthenticatedAndOutranksTheStaticCatchAll`,
`theBrowserLearnsTheApiPrefixInExactlyOnePlaceAndExemptsTheAuthBootstrap` (KEEP-с-усилением: в браузере
перехватить все запросы страницы и проверить, что каждый несёт `/api/v1`, кроме `/auth*`),
`theWebUiWiresTheBrowserPushSubscriptionFlow` (сжать до контракта роутов `/api/v1/push/*`),
`theUnicodeAddonsAreVendoredAndLoadedOnlyWhenThePreferenceSelectsThem` (половина «завендорены и отдаются»).

DEL без замены: `sessionAndPaletteRowsSharePillInteractionStates` — сравнение CSS-строк для `:hover`/`:active`.

Переезд — по задачам: 9 сайдбар и маршрут (`webUiGroupsSessionsIntoARecursiveDirectoryTree`,
`webUiRendersTheCurrentVersionInTheSidebarFooter`); 10 палитра (четыре теста реестра, listbox, биндинга и
десктопного хрома); 11 light dismiss; 12 свайп; 13 реаттач; 14 геометрия и вёрстка (FitAddon, drawer,
сворачивание сайдбара, карточки, клавиатурный сайзинг, `theNotificationsToggleIsDrawnInTheShellsOwnAccent`
— computed color в двух состояниях; утверждения о внутренностях SVG-маски и об отсутствии литералов замены
не имеют и удаляются); 15 диалоги сессии (девять тестов New session / Import / Preferences / Help / Phone
access / второе действие жизненного цикла); 16 mobile features (key bar, upload, unicode-преференс).

### Контракт для волны 3 — что построили волны 1–2 (`b10b548`)

Инфраструктура существует и зелена. Четырнадцать участников волны 3 кодируют **против этого раздела** и
файлы `webuicheck/`/`webuitest/` не открывают. Всё ниже — измеренный факт, а не задание.

**Фикстура шире, чем замороженный список.** Пакет `io.kotgent.webuitest`, всё в
`webuitest/test/HarnessFixture.kt`:

```kotlin
class Harness(scenario: String) : AutoCloseable      // port, ticket, baseUrl ("http://127.0.0.1:<port>")
    fun send(line: String)
fun BrowserContext.loginWithTicket(ticket: String, baseUrl: String)
fun touchChromium(pw: Playwright): Browser
fun Browser.touchContext(width = 390, height = 844, deviceScaleFactor = 3.0, mobile = true): BrowserContext
fun testResultsDir(): Path                            // webuitest/test-results, путь заморожен CI
fun BrowserContext.traced(name: String, block: () -> Unit)   // трейс + скриншот ТОЛЬКО при падении
```

Три вещи, которые иначе не найти:

- **`hasTouch` — свойство КОНТЕКСТА, а не браузера.** `touchChromium` сам по себе тап не доставит,
  `touchscreen().tap()` бросит. Любой жестовый тест идёт через `touchContext`.
- **`send("restart")` блокируется** до второго `READY`; остальные команды возвращаются сразу.
- **`Harness.close()` утверждает код выхода 0.** Коды: 0 ok, 1 self-check, 2 usage, 3 плохой stdin,
  4 watchdog. Тест, пославший кривую команду, поэтому падает в `close()`, а не на `send()`.

Константы: `SESSIONS_SCENARIO`, `AUTH_PAGE_PATH`, `HEADED_ENV` (`KOTGENT_WEBUITEST_HEADED=1` — смотреть
браузер глазами). Свежий `BrowserContext` на тест обязателен: cookie не привязана к порту.

**Часы.** Сессии проштампованы `1_700_000_000_000 + n`, карточки доски — `1_000`. Ни одно место Web UI
сегодня не рисует возраст сессии, поэтому **не утверждать про отрисованную дату** ни в каком виде.

**stdout харнесса закрыт структурно:** `main` дублирует настоящий stdout в приватный дескриптор и
направляет fd 1 в stderr, так что никакой `println` — свой, чужой или ктровский — не может испортить
хендшейк.

**Терминальная нагрузка** (`terminal`, `restart`, `attention`, `sessions`; у `empty` её нет):
`["/bin/sh", "-c", "printf '<payload>'; exec cat"]`, где payload — `ESC[?1006h ESC[?1000h`, затем
`LINE 01`…`LINE 08`, затем баннер. Баннеры: `KOTGENT-SESSIONS-READY`, `KOTGENT-ATTENTION-READY`,
`KOTGENT-TERMINAL-READY`, `KOTGENT-RESTART-READY`. Баннер **последний**, поэтому дождаться его = дождаться
всего. Восемь строк — заведомо меньше любого вьюпорта, чтобы картинка не зависела от размера окна. Мышиные
режимы включены не для красоты: `installSwipeScroll` отдаёт жест обратно при
`mouseTrackingMode === "none"`, а xterm 6.0 убрал собственные touch-обработчики — без активного трекинга
свайп по терминалу не делает **ничего**. SGR (`?1006h`) стоит перед трекером, чтобы отчёты шли через
`term.onData`, а не `term.onBinary`; `cat` возвращает их эхом, что и делает их наблюдаемыми.
Для задачи 13: `stop()` убивает pty-ребёнка, реаттач поднимает **новый** `/bin/sh`, который печатает
баннер заново — однозначный дискриминатор даёт маркер, записанный через
`POST /api/v1/sessions/{id}/input` до рестарта (`cat` его эхнет, пережить нового ребёнка он не может).

**Посевные данные.** Порядок строк сайдбара = порядок посева; `basePath` по умолчанию пуст, поэтому дерево
плоское, пока тест сам не выставит базовый путь.

| Сценарий | Содержимое |
|---|---|
| `empty` | пусто; терминального апстрима нет |
| `sessions` | `s-alpha` claude `/a/b` running · `s-beta` codex `/a/b` ready · `s-gamma` junie `/a/c` needs_approval · `s-delta` shell `/d` resumable. При `basePath="/"` и уровне 2: `/a` (агрегат 3) → `/a/b` (2), `/a/c` (1), плюс `/d` (1) |
| `attention` | `s-quiet` ready, unread 0 — уходит в attention командой `emit s-quiet needs_approval` (фронт `false → true`); `s-unread` running, unread 3 |
| `restart` | `s-restart-a` running, `s-restart-b` ready — обе живы после рестарта; вторая нужна, чтобы было куда переключиться |
| `terminal` | одна строка `s-term` claude `/w/terminal` running |
| `board` | проект «Board Fixture» `/repo/board`; `local:1..10`: todo `1,2,3,4,10` · in_progress `5,6` · review `7` · done `8,9`; `local:10` блокирована зависимостью от `local:5`; **`local:3` — мишень `task-race`**, единственная без рёбер |
| `board-empty` | проект «Empty Fixture» `/repo/empty`, задач нет |
| `task-detail` | «Detail Fixture» `/repo/detail`; фокус `local:3`, зависит от `local:1` и `local:2` (blocked), от него зависит `local:4`; на `local:3` два комментария, один от автора `s-detail-1`, которому намеренно не соответствует ни одна строка сессии |
| `task-linked-session` | «Linked Fixture» `/repo/linked`; `s-linked-1` → `local:1` (бейдж разрешается), `s-linked-2` без ссылки (от неё линкуют), `s-linked-3` → `local:404` (намеренно висячая, рисует `task-badge-unknown`) |
| `deep-link` | «Deep Link Fixture» `/repo/deep`; сессия `deep-session` ↔ задача `local:7`, так что `/s/deep-session` и `/tasks/local:7` ссылаются друг на друга |

У каждого проекта в `FakeProjectFs` лежит настоящий `.kotgent.json`, поэтому `POST /projects` по тому же
пути принимает существующий uuid, а не минтит второй. Каталоги, видимые автодополнению:
`/a/b`, `/a/c`, `/a/.hidden`, `/d`, `/projects/kotgent`, `/projects/kotgent-web`.

### Измерено волной 3 — обязательно к прочтению перед любым новым браузерным тестом

Четыре факта, каждый стоил отдельного разбора. Ни один не выводится из документации Playwright.

- **`Pattern.quote` с Playwright НЕ РАБОТАЕТ.** Java-паттерн не исполняется в Java: драйвер отправляет его
  исходный текст в Node и матчит как JS `RegExp`, где `\Q…\E` не значит ничего (`\Q` — просто
  экранированная `Q`). Результат обязан начинаться с литерала `Q` и не совпадает никогда, а сообщение об
  ошибке печатает java-написание рядом с URL, который ему очевидно удовлетворяет. Экранировать самим:
  `regexLiteral()` над `\^$.|?*+()[]{}`.
- **Харнесс минтит ОДИН тикет, и `TicketStore` сжигает его при погашении.** Один `loginWithTicket` на
  харнесс. Тесту, которому нужно N контекстов, нужно N харнессов.
- **Эффект, выполняющийся после отрисовки, обгоняется следующим нажатием.** Три места, одна форма: фокус
  лидер-режима ставится в `useEffect([mode])`, `dialog.close()` ставит своё событие в очередь (элемент
  ещё смонтирован с `open === false`, и `⌘K` в этом окне переключает устаревшее состояние на невидимый
  диалог), `activeIndex` выставляется в `useEffect([query])`. Ждать наблюдаемое состояние, а не жать
  вслепую.
- **Chromium тратит первый Esc непустого `<input type="search">` на собственную очистку поля.** Поле
  запроса палитры — именно такой input, и `CommandPalette.js` про Escape не знает вообще: это платформа.
  Очищать поле перед Esc, чтобы одно нажатие значило одно.

Подтверждено, а не предположено: контекст с `hasTouch` **действительно** разрешает
`@media (any-pointer: coarse)` — проверено и на 390 px, и на 1024 px. И `gson` резолвится транзитивно из
POM Playwright, поэтому CDP (`context.newCDPSession`) доступен — им дотягиваются жесты, которых нет в
`Touchscreen`: настоящий тач-drag через `Input.dispatchTouchEvent`.

**Гонка newest-rev-wins** — это команда `task-race <ref>`, а не сценарий: посев отрабатывает до того, как
сервер начал слушать, и порядка не порождает. Рецепт: придержать `GET` детали через `route.fetch()` без
`fulfill`, послать `task-race local:3`, убедиться, что карточка переехала, затем отдать придержанное тело и
убедиться, что она **не** вернулась назад.

## Параллельное исполнение волнами

### Разметка

У каждой задачи есть `**Wave:** K` и `**Depends:** <номера|—>`. Закрывающие проходы — настоящие секции
`### Task N: Закрытие волны K` со своими чекбоксами, поэтому при обычном последовательном `/planning:exec`
план исполняется корректно: секции идут по порядку, закрывающая догоняет валидацию и коммит.
Реструктуризация — строгое надмножество, а не форк. Волновой режим требует **явного** указания при
запуске («исполнять волнами по разделу "Параллельное исполнение"»), потому что `SKILL.md` в цепочку
оверрайдов не входит и его запрет batch-spawn сам собой не снимается.

### Правила участника волны

Переопределение `prompts/task.md` кладётся в `.claude/exec-plan/prompts/task.md`:

- задача адресуется **по имени** («Task 10: Браузерные тесты командной палитры»), а не «первая секция с
  `[ ]`» — иначе два участника выберут одну секцию;
- **не править файл плана**: вместо простановки галочек участник возвращает блок `DONE:` с дословными
  строками выполненных чекбоксов и блок `FILES:` со всеми созданными/изменёнными/удалёнными путями;
- **не запускать `git`** — ни `add`, ни `commit`, ни `stash`, ни `checkout`;
- **не запускать `./kotlin`** и вообще ничего из `## Validation Commands`;
- прогресс — только однострочный режим `task N: …`; браузерные задачи дополнительно логируют по строке
  `[replaces] task N: WebUiServingTest.<имя>` на каждый замещаемый тест этого файла.

### Контракт закрывающего прохода

Один сабагент, строго после возврата всех участников волны:

1. `git status --porcelain` — множество изменённых файлов обязано совпадать с объединением `FILES:`
   участников; лишний файл — стоп с отчётом;
2. применить регистровые правки волны (`project.yaml`, корневой `module.yaml`);
3. прогнать `## Validation Commands` целиком — **один раз на волну**;
4. при падении — не чинить широко, а вернуть перечень «файл участника → ошибка»; оркестратор перезапускает
   **только** упавших участников с текстом ошибки (волновой аналог `task_retries`);
5. при успехе — пометить `[x]` все чекбоксы всех задач волны;
6. один коммит на волну, включающий файл плана;
7. многострочный блок прогресса.

**Фанаут эмитит оркестратор** — участники это N блоков `tool_use` в одном ответе главной сессии, без
`run_in_background`. Делегировать волну «бригадиру»-сабагенту нельзя: у сабагентов нет доступа к Agent tool.

### Волны

| Волна | Задачи | Ширина |
|---|---|---|
| 1 — фундамент | 1 `fakes`, 2 CI → 3 закрытие | 2 |
| 2 — харнесс и фикстура | 4 каркас, 5 сценарии+команды, 6 сценарии доски, 7 `webuitest` → 8 закрытие | 4 |
| 3 — браузерные тесты | 9–22 → 23 закрытие | **14** |
| 4 — дочистка | 24 | 1 |
| 5 — приёмка | 25 | 1 |
| 6 — документация | 26 | 1 |

**Непересечение файлов.** Волна 1: `fakes/**` ∪ `{TransportTest.kt, test/daemon/FakeTmux.kt}` против
`{ci.yml, .gitignore}` = ∅; регистры — только у закрытия. Волна 2: `{Main,SafeEdges,SelfCheck}.kt +
WebUiCheckTest.kt` против `{Scenarios,Commands}.kt + scenarios/{Empty,Sessions,Attention,Restart,Terminal}.kt`
против `scenarios/Board*.kt` против `webuitest/** + gradle/libs.versions.toml` = ∅. Волна 3: четырнадцать
разных файлов в `webuitest/test/`, плюс восемь grep-файлов, у каждого ровно один владелец; **никто не
открывает `test/transport/WebUiServingTest.kt`**. Файл, у которого есть KEEP-тесты, участник не удаляет, а
ужимает до них — свод в реестр и удаление остатка делает задача 24 по строкам `[keep]`.

**Почему T4 не в волне 1**, хотя файлы позволяют: извлечение `FakeEventStore` затрагивает 1432 нативных
теста, и дешевле узнать о поломке до того, как на неё сядут четыре автора волны 2.

**Замороженные швы волны 2** (участники пишут вслепую друг относительно друга, поэтому контракт лежит
здесь, а не выясняется из чужого кода): `SelfCheck.kt` (владелец — задача 4) объявляет
`class SelfCheckCase(val name: String, val run: suspend () -> Unit)` и
`fun runSelfCheck(cases: List<SelfCheckCase>): Int`; `Scenarios.kt` (задача 5) объявляет `SCENARIO_NAMES`,
`fun scenarioByName(name: String): Scenario?` и заранее называет board-сценарии, делегируя в файлы задачи 6;
`Commands.kt` (задача 5) — `fun handleCommand(line: String, ctx: HarnessContext): Boolean`.
**`EXPECTED_CHECKS` пишет только задача 4** и больше никто — он константа.

### Коллизии и развязки

| Файл | Кто хотел бы править | Развязка |
|---|---|---|
| `test/transport/WebUiServingTest.kt` | 9–18 и 24 | Все удаления → только задача 24 (волна 4). Задачи 9–18 файл не открывают; связь — `[replaces]` + сверка |
| `webuicheck/src/Main.kt` | 4, 5, 6 | Владелец — задача 4; остальные подключаются через замороженные швы |
| `test/transport/WebUiCheckTest.kt` | 4, 5, 6 | Владелец — задача 4; `EXPECTED_CHECKS` — константа |
| `project.yaml`, корневой `module.yaml` | 1, 4, 7 | Только закрывающий проход волны |
| `gradle/libs.versions.toml` | 7 | Владелец один, оставить |
| `CLAUDE.md` | 26 + ревью-фиксеры | Задача 26 — одиночная волна, ревью идёт после всех волн |
| Файл плана, индекс git, `build/`, порты, кэш браузеров | все | Только закрывающий проход |

**Отвергнутые развязки.** Предварительно разрезать `WebUiServingTest.kt` по областям — цель ровно
обратная, и файл параллельно растёт. Отдельный worktree на участника — не снимает слепоту, добавляет мердж
и вторую копию плана. `flock` на `.git/index` — оставляет N коммитов вместо одного и не решает драку за
`build/`.

### Полнота проверок `/planning:exec` не страдает

- **Ревью-фазы (шаги 7–10) читают `git diff DEFAULT_BRANCH...HEAD`**, то есть ветку целиком, и о задачах
  не знают. Единственное условие — каждая волна закоммичена до шага 7; это и обеспечивает закрывающий
  проход. Худший случай фанаута не меняется: 5 + 4×2 = 13 ревьюеров, до 5 фиксеров в фазе 1, плюс smells,
  до 10 внешних итераций и финальный проход из двух агентов.
- **Условие останова** — «в плане не осталось `[ ]`» — работает как прежде.
- **Предел 50 итераций** не достигается: 6 эмиссий и 6 закрытий.
- **Финализатор** сжимает историю при 5+ коммитах; волновых коммитов ровно 6.
- Что теряется честно: **гранулярность истории** (6 коммитов вместо 26) и **валидация на задачу** (ошибка
  компиляции всплывает на закрытии волны). Первое компенсируется телом коммита волны, второе — узкой
  шириной кодовых волн и замороженными швами.

### Выигрыш

| Метрика | Последовательно | Волнами |
|---|---|---|
| Слотов исполнения | 26 | **6** |
| Тяжёлых прогонов `build && test` | 26 | **6** |
| Критическая цепь | 26 звеньев | **6**: задача 1 → 4 → самая долгая из 9–22 → 24 → 25 → 26 |
| Самая широкая волна | 1 | **14** |

**Если волна упала:** закрывающий возвращает «файл участника → ошибка», оркестратор перезапускает только
упавших. Если падений в волне 3 больше трёх — разделить её на 7+7 (файлы попарно непересекающиеся,
разделение бесплатно) и закрыть двумя проходами; цепь удлиняется на одно звено.

## Validation Commands

Запускает **только** закрывающий проход волны (и одиночные задачи 24/25/26).
Участники волны не запускают отсюда ничего.

```
./kotlin build     # обязателен перед test: PtyTest экзекает ptycheck,
                   # WebUiCheckTest и webuitest — webuicheck
./kotlin test
# быстрые петли, не заменяющие агрегат:
#   ./kotlin task :kotgent:testMacosArm64Debug
#   ./kotlin task :webuitest:testJvm
```

## What Goes Where

- **Implementation Steps** (`[ ]`): всё, что делается в этом репозитории.
- **Post-Completion** (без чекбоксов): проверки на живом железе, которые Playwright не закрывает.

## Implementation Steps

### Task 1: Модуль `fakes` — пять двойников

**Wave:** 1 · **Depends:** —

**Files:**
- Create: `fakes/module.yaml`, `fakes/src/daemon/FakeTmux.kt`, `fakes/src/store/FakeEventStore.kt`, `fakes/src/store/FakeTaskStore.kt`, `fakes/src/task/FakeProjectFs.kt`, `fakes/src/task/MemoryProjectFileWriter.kt`
- Modify: `test/transport/TransportTest.kt`
- Delete: `test/daemon/FakeTmux.kt`

- [x] создать `fakes/module.yaml`: `kmp/lib`, `platforms: [macosArm64]`, `dependencies: - ..`, Kotlin 2.4.10; в комментарии — зачем модуль нужен и что граф `рут(test) → fakes → рут(main)` тулчейном проверен
- [x] перенести `FakeTmux` без изменения пакета `io.kotgent.daemon`
- [x] извлечь `FakeEventStore` из `TransportTest.kt:1907` в top-level `public`, сохранив его собственный небуферизованный `reliableSessionUpdates`
- [x] **переопределить в нём `setTaskRef`, `setProjectId`, `sessionsHoldingTask`** поверх той же `LinkedHashMap` с `++revCounter` и `emitFromMeta` — дефолты интерфейса бросают, и без этого любой link/unlink даёт 500
- [x] написать `FakeTaskStore` по прототипу `TaskEventsTest.kt:445` (`TaskStore` + `TaskTracker`), `FakeProjectFs` и `MemoryProjectFileWriter` (оба интерфейса, всё в памяти)
- [x] `TransportTest.kt` получает импорт; при требовании импорта в других потребителях — добавить импорт, не откатывать переезд
- [x] отчитаться блоками `DONE:` и `FILES:`; регистры (`project.yaml`, корневой `module.yaml`) **не трогать** — их правит закрытие волны

### Task 2: CI — кэш браузеров, артефакты падений, `.gitignore`

**Wave:** 1 · **Depends:** —

**Files:**
- Modify: `.github/workflows/ci.yml`, `.gitignore`

- [x] добавить шаг `actions/cache` на `~/Library/Caches/ms-playwright` с ключом от версии Playwright `1.62.0`; в комментарии записать, что Node ставить не нужно — драйвер вшит в артефакт
- [x] добавить загрузку артефактов при падении: скриншоты и трейсы
- [x] добавить в `.gitignore` каталог вывода Playwright
- [x] в комментарии отметить: `project.yaml` **входит** в ключ кэша тулчейна (`ci.yml:28`), поэтому регистрация модулей его инвалидирует; неинвалидирующими остаются три **новых** `*/module.yaml`, которых glob не ловит
- [x] отчитаться `DONE:` и `FILES:`

### Task 3: Закрытие волны 1

**Wave:** 1 · **Depends:** 1, 2

- [x] сверить `git status --porcelain` с объединением `FILES:` задач 1–2; лишний файл — стоп с отчётом
- [x] зарегистрировать `- ./fakes` в `project.yaml` (список алфавитный) и в `test-dependencies:` корневого `module.yaml`
- [x] прогнать `## Validation Commands`: baseline **1432 нативных теста / 0 skipped** обязан сохраниться — это и есть тест переезда
- [x] при падении вернуть «файл участника → ошибка» без широкого чинения
- [x] пометить `[x]` чекбоксы задач 1–2, один коммит волны вместе с файлом плана, многострочная запись в прогресс

### Task 4: Каркас `webuicheck` — аргументы, безопасные edges, `--self-check`

**Wave:** 2 · **Depends:** 1

**Files:**
- Create: `webuicheck/module.yaml`, `webuicheck/src/Main.kt`, `webuicheck/src/SafeEdges.kt`, `webuicheck/src/SelfCheck.kt`, `test/transport/WebUiCheckTest.kt`

- [x] создать `webuicheck/module.yaml` по образцу `ptycheck/module.yaml`: `macos/app`, `macosArm64`, зависимости `..`, `../sysnative`, `./fakes`, `entryPoint: io.kotgent.webuicheck.main`
- [x] разобрать `--self-check`, `--scenario=<name>`, `--webui-dir=<abs>`, `--exit-after-ms=<n>`; неизвестный сценарий и мусор на stdin — ненулевой выход
- [x] `SafeEdges.kt`: фейковый `DirectoryCompleter`, uploader в память, `FakeProjectFs`, `MemoryProjectFileWriter` — три пишущих edge вместо прежних двух
- [x] поднять `KotgentServer(port = 0)` на `FakeTmux` + `FakeEventStore` + `FakeTaskStore` + настоящем `TaskService` + безопасных edges; **передать `taskStore` и `taskService`**, иначе `/api/v1/projects` даёт 404 и каждый сценарий стартует с красной строкой
- [x] напечатать ровно `PORT=`/`TICKET=`/`READY` в stdout (тикет — `TicketStore.issue`), всё прочее в stderr; EOF → graceful stop, плюс сторожевой таймер
- [x] объявить швы: `SelfCheckCase`, `runSelfCheck`, `handleCommand`, `HarnessContext` — их подключат задачи 5 и 6
- [x] `--self-check` содержит **только cinterop-зависимые** проверки (настоящий `Pty` под настоящим `TerminalBridge` внутри настоящего сервера); `EXPECTED_CHECKS` — константа, которую больше никто не правит
- [x] написать `test/transport/WebUiCheckTest.kt` по образцу `PtyTest`: найти бинарник, выполнить `--self-check` через `ProcessRunner.run`, сверить `SUMMARY` и код `0`, печатать захваченные потоки при падении
- [x] отчитаться `DONE:` и `FILES:`; `project.yaml` не трогать

### Task 5: Содержимое харнесса — сценарии и команды

**Wave:** 2 · **Depends:** 1, 4 (швы)

**Files:**
- Create: `webuicheck/src/Scenarios.kt`, `webuicheck/src/Commands.kt`, `webuicheck/src/scenarios/{Empty,Sessions,Attention,Restart,Terminal}.kt`

- [x] `Scenarios.kt`: одна `Map<String, Scenario>` со **всеми** именами, включая board-сценарии задачи 6 (делегирование в её файлы через шов)
- [x] реализовать `empty`, `sessions` (cwd `/a/b`, `/a/c`, `/d`), `attention`, `restart` (store продолжает сообщать сессию живой после перезапуска)
- [x] `terminal`: `TerminalBridge` напрямую с `realPtyFactory` и детерминированной командой `/bin/sh -c 'printf …; cat'`, воспроизводимой побайтно
- [x] `Commands.kt`: `restart` (тот же порт, тот же `TokenHolder`, `TicketStore`, `FakeTaskStore`, `TaskService`; печатать только повторный `READY`; вложенный `runBlocking` не звать из корутины на однопоточном диспетчере) и `emit <id> <state>`
- [x] отчитаться `DONE:` и `FILES:`; `Main.kt` и `WebUiCheckTest.kt` **не открывать**

### Task 6: Сценарии доски и команды задач

**Wave:** 2 · **Depends:** 1, 4 (швы)

**Files:**
- Create: `webuicheck/src/scenarios/Board.kt`, `webuicheck/src/scenarios/TaskDetail.kt`, `webuicheck/src/scenarios/DeepLink.kt`, `webuicheck/src/TaskCommands.kt`

- [x] реализовать сценарии `board`, `board-empty`, `task-detail`, `task-linked-session`, `deep-link` поверх `FakeTaskStore`
- [x] реализовать команды `task <ref> <state>` (→ `task_update`), `task-add <ref>` (→ `task_row`), `task-del <ref>` (→ `task_removed`)
- [x] добавить сценарий гонки «тот же ref пришёл и ответом REST, и фреймом» — единственный способ проверить newest-rev-wins в живом браузере
- [x] отчитаться `DONE:` и `FILES:`; `Scenarios.kt`, `Commands.kt`, `Main.kt` **не открывать**

### Task 7: Модуль `webuitest` — фикстура, логин тикетом, дымовой тест

**Wave:** 2 · **Depends:** 4 (хендшейк и путь к бинарнику)

**Files:**
- Create: `webuitest/module.yaml`, `webuitest/test/HarnessFixture.kt`, `webuitest/test/SmokeTest.kt`
- Modify: `gradle/libs.versions.toml`

- [x] добавить в каталог **обе** записи: `playwright = "1.62.0"` в `[versions]` и строку в `[libraries]`
- [x] `webuitest/module.yaml`: `jvm/lib`, `test-dependencies: - $libs.playwright`; **в комментарии зафиксировать правило суффикса `Test`** и что его нарушение даёт молчаливое «0 tests found»
- [x] `HarnessFixture.kt` строго по замороженному API из Technical Details: `Harness`, `loginWithTicket`, `touchChromium`; поиск бинарника по фиксированным путям с громким падением и инструкцией `./kotlin build`; передача абсолютного `--webui-dir`; закрытие stdin и ожидание выхода в `finally`
- [x] свежий `BrowserContext` на каждый тест — cookie не привязана к порту, переиспользование ломает логин
- [x] `SmokeTest`: сценарий `sessions`, после логина виден сайдбар с ожидаемым числом строк; отрицательный путь — неверный тикет оставляет форму
- [x] отчитаться `DONE:` и `FILES:`; `project.yaml` не трогать

### Task 8: Закрытие волны 2

**Wave:** 2 · **Depends:** 4, 5, 6, 7

- [x] сверить `git status --porcelain` с объединением `FILES:` задач 4–7
- [x] зарегистрировать `- ./webuicheck` и `- ./webuitest` в `project.yaml`
- [x] прогнать `## Validation Commands` — впервые с браузерами; зафиксировать время прогона в прогресс-файле
- [x] при падении вернуть «файл участника → ошибка»; оркестратор перезапускает только упавших
- [x] пометить `[x]` чекбоксы задач 4–7, один коммит волны вместе с файлом плана

### Task 9: Браузерные тесты сайдбара и маршрута

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/SidebarTest.kt`

- [ ] сценарий `sessions`: из реального DOM восстанавливается ожидаемая иерархия каталогов
- [ ] смена уровня группировки в Preferences меняет дерево; в футере видна текущая версия
- [ ] **выбор строки сайдбара двигает `location.pathname`** на `/s/{id}`, Back возвращает
- [ ] сценарий `empty`: первый запуск показывает прямое действие «Start a session»
- [ ] залогировать `[replaces] task 9: …` для замещаемых тестов `WebUiServingTest`

### Task 10: Браузерные тесты командной палитры

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/CommandPaletteTest.kt`

- [ ] поиск: `⌘K`, ввод подстроки, фильтрация, `aria-activedescendant` следует за стрелками, Enter выполняет команду
- [ ] лидер-режим: аккорд открывается, мнемоника срабатывает, зарезервированные чорды видимы, но неактивны
- [ ] палитра уступает клавиатуру другому диалогу; точки входа, убранные из десктопного хрома, достижимы
- [ ] палитра отвечает за тот экран, на котором открыта
- [ ] залогировать `[replaces] task 10: …`

### Task 11: Браузерные тесты light dismiss диалогов

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/DialogDismissTest.kt`

- [ ] tap по backdrop закрывает, tap по панели — нет
- [ ] press внутри панели с отпусканием снаружи **не** закрывает (решает геометрия, не target)
- [ ] второй контакт не закрывает диалог, начатый первым — инвариант одного `pointerId` на down/up/click
- [ ] экран в состоянии busy не закрывается ни жестом, ни backdrop-ом, но Esc и × работают всегда
- [ ] перенести объясняющий комментарий (ключевая часть проектного журнала); залогировать `[replaces] task 11: …`

### Task 12: Браузерные тесты свайпа в терминале

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/TerminalSwipeTest.kt`

- [ ] сценарий `terminal`: вертикальный свайп даёт wheel-события и видимый скролл
- [ ] горизонтальный свайп жест не захватывает
- [ ] свайп не вызывает клавиатуру (фокус не уходит в helper-textarea)
- [ ] залогировать `[replaces] task 12: …`

### Task 13: Браузерные тесты реаттача терминала

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/TerminalReattachTest.kt`

- [ ] `restart` → сокет переоткрылся и новые байты идут (**не** «прежнее содержимое вернулось»: `stop()` убивает pty-ребёнка)
- [ ] закрытие сокета событий из `page.evaluate` → переподключение по его возврату
- [ ] ветки «кандидат уничтожен на 4xx» и «кандидат сохранён при недостижимом демоне» — перехватом запросов в браузере, без участия харнесса
- [ ] переключение на другую сессию уничтожает кандидата; учесть, что оно теперь ещё и меняет маршрут
- [ ] залогировать `[replaces] task 13: …`

### Task 14: Браузерные тесты геометрии и вёрстки

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/LayoutTest.kt`

- [ ] FitAddon получает размер за вычетом padding — сверяются `getBoundingClientRect` и `cols`/`rows`, последняя строка не обрезана
- [ ] mobile drawer открывается и закрывается; десктопный сайдбар сворачивается, не перегружая мобильный ящик
- [ ] карточки не переносят padding на хост терминала; размер шрифта из преференсов меняет `cols`/`rows`
- [ ] переключатель уведомлений рисуется акцентом оболочки — computed color в двух состояниях
- [ ] все утверждения читают геометрию, не строки `getComputedStyle`; safe-area **не** проверяется
- [ ] залогировать `[replaces] task 14: …`

### Task 15: Браузерные тесты диалогов и действий над сессией

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/SessionDialogsTest.kt`

- [ ] New session: выбор агента в один клик без предвыбранного значения, радио доступны с клавиатуры, запланированные агенты видимы, но не выбираемы
- [ ] отсутствующий бинарник агента даёт внятное сообщение, а не молчаливый отказ
- [ ] Import адоптирует сессию и не предлагает Shell
- [ ] второе действие жизненного цикла отклоняется вслух
- [ ] Preferences, Help и Phone access открываются и несут ожидаемое содержимое
- [ ] залогировать `[replaces] task 15: …`

### Task 16: Браузерные тесты key bar, загрузки файлов и unicode

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/MobileFeaturesTest.kt`

- [ ] key bar: специальные клавиши уходят бинарными кадрами и не крадут фокус у xterm
- [ ] загрузка: `setInputFiles` на пикере палитры, uploader харнесса получает ожидаемое имя и содержимое; частичный батч сообщает про каждое упавшее имя
- [ ] unicode: аддон **не** запрашивается по умолчанию, после переключения преференса запрос происходит и `term.unicode.activeVersion` меняется
- [ ] залогировать `[replaces] task 16: …`

### Task 17: Браузерные тесты бейджей и состояний задач

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/TaskBadgeTest.kt`
- Delete: `test/transport/WebUiTaskBadgeTest.kt`, `test/transport/WebUiTaskStateTest.kt`

- [ ] сценарий `task-linked-session`: бейдж рисуется, link/unlink меняет его без перезагрузки
- [ ] `emit` и `task <ref> <state>` двигают состояние живьём; notify-edge срабатывает один раз
- [ ] перенести объясняющие комментарии; удалить `WebUiTaskBadgeTest.kt` целиком и ужать `WebUiTaskStateTest.kt` до его единственного KEEP-теста — **`WebUiServingTest.kt` не открывать**, свод в реестр делает задача 24; залогировать `[keep] task 17: …` на оставленный тест

### Task 18: Браузерные тесты команд задач в палитре

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/TaskCommandsTest.kt`
- Delete: `test/transport/WebUiTaskCommandsTest.kt`

- [ ] доска и её задачи достижимы из палитры; команды исполняются и меняют состояние
- [ ] слои реестра команд не конфликтуют между экранами
- [ ] перенести комментарии; удалить grep-файл в этом же коммите волны
- [ ] залогировать `[replaces] task 18: …` для теста реестра команд в `WebUiServingTest`, если он затронут

### Task 19: Браузерные тесты доски и drag-and-drop

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/BoardTest.kt`
- Delete: `test/transport/WebUiBoardTest.kt`

- [ ] сценарий `board`: колонки и карточки отрисованы; `board-empty` даёт пустое состояние
- [ ] **drag карточки** — слоп, переживание перерисовки под capture, `pointercancel` без сетевых запросов, порядок PATCH→POST `/move`, модифицированный клик. Chromium-only по той же причине, что light dismiss (факт 4)
- [ ] один-колоночный вид на ширине 390 px
- [ ] гонка «тот же ref пришёл REST-ответом и фреймом» разрешается newest-rev-wins
- [ ] перенести комментарии; ужать `WebUiBoardTest.kt` до его KEEP-тестов (замок словаря классов, `PROJECT_NAME_MAX_LENGTH`) — **`WebUiServingTest.kt` не открывать**; залогировать `[keep] task 19: …` на каждый оставленный тест

### Task 20: Браузерные тесты стилей доски

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/BoardStyleTest.kt`
- Delete: `test/transport/WebUiBoardStyleTest.kt`

- [ ] каждая колонка и карточка проверяется геометрией и computed color, а не сравнением CSS-строк
- [ ] деталь задачи всплывает над доской, а не сжимает её
- [ ] удалить `WebUiBoardStyleTest.kt` целиком: его половина замка «словаря классов» живёт в `WebUiBoardTest.kt`, который сохраняет задача 19

### Task 21: Браузерные тесты деталей задачи

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/TaskDetailTest.kt`
- Delete: `test/transport/WebUiTaskDetailTest.kt`

- [ ] сценарий `task-detail`: лента активности подгружается, комментарий добавляется, зависимости отображаются
- [ ] × стоит в углу и закрывает панель; layout панели проверяется геометрией
- [ ] удалить grep-файл в этом же коммите волны

### Task 22: Браузерные тесты роутера и маршрутов экранов

**Wave:** 3 · **Depends:** 7

**Files:**
- Create: `webuitest/test/RouterTest.kt`
- Delete: `test/transport/WebUiRouterTest.kt`, `test/transport/WebUiScreenRoutingTest.kt`

- [ ] сценарий `deep-link`: старт браузера прямо на `/s/{id}` и `/tasks/{ref}` открывает нужный экран
- [ ] Back/Forward через `popstate` работают в обе стороны
- [ ] каждый экран имеет свой маршрут и восстанавливается по перезагрузке
- [ ] удалить `WebUiScreenRoutingTest.kt` целиком и ужать `WebUiRouterTest.kt` до его KEEP-тестов (сверка `DEEP_LINK_PARAM` с `sw.js`, запрет `pushState` в обход роутера) — **`WebUiServingTest.kt` не открывать**; залогировать `[keep] task 22: …`

### Task 23: Закрытие волны 3

**Wave:** 3 · **Depends:** 9–22

- [ ] сверить `git status --porcelain` с объединением `FILES:` задач 9–22; лишний файл — стоп
- [ ] убедиться, что **ни один** участник не открыл `test/transport/WebUiServingTest.kt`
- [ ] прогнать `## Validation Commands`
- [ ] при падении вернуть «файл участника → ошибка»; при более чем трёх падениях разделить волну на 7+7 и закрыть двумя проходами
- [ ] пометить `[x]` чекбоксы задач 9–22, один коммит волны вместе с файлом плана

### Task 23a: Починка трёх багов продукта, найденных волной 3

**Wave:** 3½ (между закрытием волны 3 и дочисткой) · **Depends:** 23

Отклонение от Overview, санкционированное явно: тесты верны, ослаблять их нельзя, значит чинится продукт.

**Files:**
- Modify: `resources/webui/components/TerminalPane.js`, `resources/webui/app.js`, `resources/webui/components/Board.js`

- [ ] **unicode-аддоны мертвы:** `lib/unicode.js` пишет `term.unicode.activeVersion`, но `new Terminal({…})` не ставит `allowProposedApi: true`, а `term.unicode` спрятан за `_checkProposedApi` — любой выбор режима бросает. Ловит `MobileFeaturesTest`
- [ ] **Preferences не закрывается после Save:** guard `sameForm` отличает чужую правку по ревизии формы, но демон рассылает `preferences_update` про эту же запись, и по петле кадр обгоняет ответ на PUT — защита срабатывает на собственном эхе. Сохранить назначение guard'а, а не удалить его. Ловят `SidebarTest` ×3 и `LayoutTest` ×1
- [ ] **drag доски почти неначинаем:** capture берётся только после слопа в 8 px, а ручка `⠿` — примерно 12×16 px, так что курсор уходит с элемента раньше квалифицирующего `pointermove`. Брать capture на `pointerdown`: у ручки нет конкурирующего жеста, ради которого слоп нужен диалогу. Слоп сохранить для отличения клика от перетаскивания. Ловят `BoardTest` ×4 и `BoardStyleTest` ×1
- [ ] `node --check` на каждый изменённый модуль; прогнать `./kotlin task :webuitest:testJvm`

### Task 24: Дочистка `WebUiServingTest.kt` до контрактов отдачи

**Wave:** 4 · **Depends:** 23

**Files:**
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] собрать все строки `[replaces]` из прогресс-файла и сверить с таблицей диспозиции: каждый переезжающий тест заявлен **ровно один раз**; ни один KEEP не заявлен
- [ ] удалить заявленные тесты; сжать `theWebUiWiresTheBrowserPushSubscriptionFlow` до контракта роутов `/api/v1/push/*`; удалить `sessionAndPaletteRowsSharePillInteractionStates` и grep-половину `daemonServesTheServiceWorkerAtTheRootScope`
- [ ] собрать строки `[keep]` из прогресс-файла: перенести уцелевшие KEEP-тесты из ужатых `WebUiBoardTest.kt`,
      `WebUiRouterTest.kt` и `WebUiTaskStateTest.kt` в `WebUiServingTest.kt`, после чего удалить эти три файла;
      свернуть дубли serving-контракта в один реестр модулей
- [ ] удалить осиротевшие хелперы (`sliceBetween`, `descriptorOf`, `agentPickerOf`, `cssRuleOf` и прочие)
- [ ] обновить KDoc класса: чем файл теперь является и чем перестал быть
- [ ] прогнать `## Validation Commands`, пометить `[x]`, закоммитить

### Task 25: Приёмка

**Wave:** 5 · **Depends:** 24

- [ ] проверить, что требования Overview выполнены и ни одного нового grep-утверждения не добавлено
- [ ] пересчитать браузерные тесты и сверить с бюджетом 55–70; зафиксировать фактическое время прогона и объём кэша браузеров
- [ ] нативная сюита: **0 skipped**, а дельта к 1432 равна числу удалённых тестов
- [ ] крайние случаи: отсутствующий `webuicheck.kexe` даёт внятное падение с инструкцией; убитый драйвер не оставляет висящих процессов; сторожевой таймер срабатывает; повторный прогон не конфликтует по портам
- [ ] харнесс не создал ничего в `~/.kotgent`, в ФС проекта, на tmux-сокетах и не написал `.kotgent.json`
- [ ] прогнать `## Validation Commands`, пометить `[x]`, закоммитить

### Task 26: [Final] Обновить документацию

**Wave:** 6 · **Depends:** 25

**Files:**
- Modify: `CLAUDE.md`

- [ ] раздел про ярус: где живут UI-тесты, правило суффикса `Test`, правило геометрии вместо `getComputedStyle`, Chromium по умолчанию и условие добавления WebKit (измеренное расхождение), факт 4 как обоснование Chromium-only для жестов
- [ ] записать, почему нативная сюита драйвит харнесс через `--self-check` и почему в нём только cinterop-зависимые проверки
- [ ] добавить `fakes`, `webuicheck`, `webuitest` в «Module structure» и «Where things live»
- [ ] обновить baseline и записать, что `./kotlin build` обязателен перед `./kotlin test` по двум причинам; записать цену агрегата и быстрые петли
- [ ] переписать «There is deliberately no JavaScript test harness» — JS-сборки нет, но поведение проверяется браузером; правку вносить в `CLAUDE.md` (корневой `AGENTS.md` — указатель на него)
- [ ] чекбокс «переместить план в `completed/`» пометить `[x]` **без фактического перемещения** — план двигает сам харнесс на своём шаге; перемещение изнутри задачи ломает последующие фазы, читающие путь к плану
- [ ] прогнать `## Validation Commands`, закоммитить

## Post-Completion

*Требует ручных действий или внешних систем — без чекбоксов.*

**Ручная проверка на живом железе (Playwright её не закрывает):**

- На актуальном iPhone и iPad подтвердить, что backdrop-овые `pointerdown`/`pointerup`/`click` несут
  **один и тот же** `pointerId`, в том числе в двухпальцевой последовательности. Chromium это подтверждает,
  WebKit-ветка остаётся непроверенной: Playwright WebKit не доставляет touch-указатели вовсе (факт 4).
- Safe-area и inset: `env(safe-area-inset-*)` в headless равен нулю, `navigator.standalone` и признак
  установленного приложения не воспроизводятся. Проверить вручную, что нижний inset отдаётся только при
  наличии key bar, а подсказки не наезжают на Home-индикатор.
- Начать захваченный свайп на iPad и отдельно сделать экран busy, нажать Esc, свернуть приложение — каждый
  случай должен пружинить назад или исчезать без застрявшего `transform`.
- Открыть каждый диалог на телефоне, планшете и ноутбуке с тачем: ручка, скомпенсированный padding,
  панорамирование за заголовок, 44-пиксельный × у палитры.
- Drag карточек доски пальцем на реальном телефоне и планшете.
- Web Push целиком: `pushManager.subscribe` требует настоящего push-сервиса, которого у headless-браузера
  нет. Проверить вручную запрос разрешения из жеста, подписку, доставку и отписку.

**Возможное продолжение (сейчас намеренно вне объёма):**

- Ярус тестов против **настоящего** `kotgent daemon` с временным `$HOME`, throwaway tmux-сокетом и
  сессиями типа `shell` — покрыл бы реконсиляцию, миграции схемы и хуки, но медленный, требует tmux и не
  умеет производить состояния вроде `needs_approval`. Заводить только если появится конкретный класс
  регрессий, который нынешние ярусы пропускают.
- Общий модуль двойников по **реальной** дупликации в дереве (пять приватных копий `FakeEventStore`, шесть
  прототипов `FakeTaskStore`, три копии рецепта `withServer`) — отдельная работа, не часть этой.
