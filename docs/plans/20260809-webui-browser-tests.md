# Настоящие браузерные тесты Web UI вместо grep по исходникам

## Overview

`test/transport/WebUiServingTest.kt` вырос до 3935 строк и **47** тестов, в которых **729** вызовов
`contains(` и **169** `indexOf(` навешены поверх **118** `bodyAsText()`. HTTP там используется как способ
прочитать файл, а утверждения — это `grep` по исходному тексту JS и CSS.

Отсюда две болезни:

1. **Тавтология.** Тест утверждает, что исходник равен самому себе. Переименование локальной переменной
   ломает тест; при этом семантически ломающая правка, сохранившая текст, проходит. Это не проверка
   поведения, а блокировка рефакторинга.
2. **Ложное покрытие.** Инварианты, физически непроверяемые текстом — отдаёт ли `touch-action: none` жест
   терминалу, вычитает ли FitAddon padding, совпадает ли `pointerId` у `pointerdown`/`click`, не съедает ли
   safe-area нижний inset — оформлены как проверенные, хотя рядом в CLAUDE.md записано «это решается только
   на живом устройстве».

**Что решаем.** Расслаиваем на три яруса: контракты отдачи остаются в Kotlin, поведение UI начинает
**исполняться** в настоящем браузере, вёрстка проверяется настоящим движком. Целевой результат —
`WebUiServingTest.kt` ужимается с 3935 строк примерно до 400 и снова становится тем, чем называется, а
поведение покрывают ~25–30 сценарных браузерных тестов (бюджет выведен из таблицы диспозиции ниже, а не
назначен сверху).

**Как интегрируется.** Три новых модуля (`fakes`, `webuicheck`, `webuitest`) плюс правки в `ci.yml`.
Ничего в `src/`, в редьюсере, в словаре `AgentEvent`, в tmux/pty-слое и в `resources/webui` не меняется:
`KotgentServer` уже полностью constructor-injected (`src/transport/Server.kt:50` — «everything is
constructor-injected so the whole server is testable end-to-end against fakes»), поэтому браузеру
отдаётся **настоящий** сервер, а подменяются только edges.

## Context (from discovery)

**Файлы и компоненты**

| Область | Файлы |
|---|---|
| Новый модуль двойников | `fakes/` (целиком): переезд `test/daemon/FakeTmux.kt` + извлечение `FakeEventStore` из `test/transport/TransportTest.kt:1802` |
| Новый сценарный харнесс | `webuicheck/` (целиком) |
| Новый браузерный ярус | `webuitest/` (целиком) |
| Манифесты | `project.yaml`, `module.yaml`, `gradle/libs.versions.toml` |
| Сокращаемый тест | `test/transport/WebUiServingTest.kt` |
| Новый нативный тест харнесса | `test/transport/WebUiCheckTest.kt` |
| CI | `.github/workflows/ci.yml`, `.gitignore` |
| Документация | `CLAUDE.md` |

**Образцы, которые копируем (не изобретаем заново)**

- Манифест и заголовочный комментарий main-бинарника-фикстуры: `ptycheck/module.yaml`.
- **Драйв фикстуры из сюиты:** `test/pty/PtyTest.kt:34-48` — `ProcessRunner.run` экзекает бинарник и
  сверяет `SUMMARY total=$EXPECTED_CHECKS failed=0` в stdout; поиск бинарника с громким падением —
  `:55` (`ptycheckBinary()`), счётчик — `:73`.
- Сборка сервера на фейках: `test/transport/WebUiServingTest.kt:3728` (`withServer`).
- Конструктор сервера: `src/transport/Server.kt:98`; `terminalBridgeFactory` на `:103`;
  `directoryCompleter`/`fileUploader` на `:147-148`; `stop()` на `:299`; `reuseAddress = true` на `:256`;
  `resolveWebUiDir` — `internal` на `:368`.
- Тикеты: `src/transport/Tickets.kt:154` (`class TicketStore`), `:184` (`issue(boundToken)`).
- Терминальный мост: `src/pty/TerminalBridge.kt:50` — конструктор берёт `upstreamCommand: List<String>`,
  `seedProvider`, `ptyFactory`, `scope`, `env`. Значит харнесс собирает его напрямую с `realPtyFactory`
  (`src/pty/RealPtyHandle.kt:38`); `terminalBridgeForSession` (`src/pty/RealPtyHandle.kt:73`) не подходит —
  он требует конкретный `Tmux` ради `tmuxPath`/`socket`/`capturePane`.
- JVM-модуль с тестами: `plugins/build-info/` (7 JVM-тестов в baseline).

**Зависимости**

- `com.microsoft.playwright:playwright:1.62.0` — единственная новая внешняя зависимость.
- Никакого npm: `package.json`, `node_modules` и `npx playwright install` не появляются.
- **Dependabot её не подхватит.** `.github/dependabot.yml` прямо фиксирует, что
  `gradle/libs.versions.toml` намеренно не отслеживается: gradle-экосистема Dependabot требует
  `build.gradle(.kts)` до того, как что-либо прочитает, а его здесь нет by design. Отслеживается только
  `github-actions`. Версию Playwright бампаем руками, как и все прочие записи каталога.

## Факты, добытые спайками (проверено в этой сессии — НЕ перепроверять)

Спайки выполнены на живом проекте и откачены; дерево чистое. Эти факты — основание дизайна.

1. **Граф `рут(test) → fakes → рут(main)` тулчейн принимает.** Модель грузится без ошибки цикла, задачи
   выстраиваются в честный DAG:
   `:kotgent:compileMacosArm64TestDebug -> :fakes:compileMacosArm64Debug -> :kotgent:compileMacosArm64Debug`.
   Тестовый фрагмент компилируется, линкуется и тест **проходит** против класса из такого модуля.
   То есть общий модуль двойников возможен и не требует извлечения отдельного `contracts`-слоя.
2. **Playwright for Java 1.62.0 работает этим тулчейном из Kotlin JVM-модуля.** Зависимость резолвится,
   реальный Chromium запускается и управляется, `PlaywrightAssertions.assertThat(locator).hasText()` с
   авто-ожиданием доступен. WebKit скачался автоматически в `~/Library/Caches/ms-playwright`.
   Модуль был `jvm/lib` с пустым `src/` — этого хватило.
3. **Ловушка: имя тестового класса обязано кончаться на `Test`.** JUnit Platform фильтрует классы по
   `.*Tests?`. Класс `PwSpike` компилировался, линковался и молча давал «0 tests found» с **ненулевым**
   кодом возврата. У нативного раннера такого фильтра нет, поэтому правило неочевидно и должно быть
   записано комментарием в `webuitest/module.yaml`.
4. **Ловушка (измерено): тач в WebKit непригоден.** Chromium с `hasTouch: true` на `touchscreen().tap()`
   даёт полную настоящую цепочку
   `pointerdown:touch#2 | touchstart | pointerup:touch#2 | touchend | click:touch#2` — то есть **один и тот
   же `pointerId` на down, up и click**, ровно инвариант, на котором держится light dismiss. WebKit на тот
   же вызов не доставил элементу **ничего** (в варианте без `touch-action: none` пришёл только `click` с
   `pointerType: "mouse"`).
   **Следствие:** Chromium — движок жестов (pointer, свайпы, key bar), WebKit — движок вёрстки (safe-area,
   `dvh`, UA-стили `<dialog>`). Синтетический `dispatchEvent(new PointerEvent(...))` работает одинаково в
   обоих, но проверяет только наши слушатели против выдуманных событий — не `touch-action`, не
   compatibility-burst, не capture. Пометка CLAUDE.md «решается только на живом iPhone» **остаётся в силе**.
5. `plugins/build-info` и `plugins/sqldelight-gen` — существующий прецедент JVM-модулей с тестами;
   `./kotlin task :build-info:testJvm` работает и находит 7 тестов. Это же даёт быструю петлю: модульные
   задачи вида `./kotlin task :<module>:test…` запускаются отдельно от агрегата.
6. Chromium и WebKit уже лежат в `~/Library/Caches/ms-playwright` (~1.2 ГБ).
7. Тулчейн подсказывает держать список `modules:` в `project.yaml` отсортированным по алфавиту.

## Ограничения, найденные ревью плана (тоже проверены по коду)

Эти четыре факта переопределили дизайн по сравнению с первой редакцией. Не «переоткрывать» их заново.

1. **`ProcessRunner` не умеет писать в stdin ребёнка.** `src/tmux/ProcessRunner.kt:106` — это
   `popen(commandLine, "r")`, читающая труба и только она; `src/push/VapidSigner.kt:30-31` фиксирует это
   как осознанное свойство. `posix_spawn` в тестовый бинарник не линкуется (KT-78062), ручной
   `fork`+`exec` небезопасен под рантаймом K/N. **Следствие:** нативная сюита не может драйвить stdin-протокол
   харнесса. Она драйвит его режимом `--self-check` по образцу `ptycheck`; stdin-протокол принадлежит
   исключительно JVM-фикстуре, где `ProcessBuilder` даёт настоящие трубы.
2. **Сеанса «уронить сокеты, не трогая порт» не существует.** `eventsWs`/`terminalWs` — лямбды роутов,
   реестра живых сокетов сервер не держит, наружу выставлен только `stop()` (`src/transport/Server.kt:299`),
   который вдобавок гасит слушателя и через `terminalRegistry.shutdownAll()` — pty. Команда `drop-ws`
   потребовала бы правок в `src/transport`, что противоречит и Overview, и смыслу затеи. **Команда
   вычеркнута;** её единственный потребитель (реаттач по возврату сокета событий) закрывается закрытием
   сокета со стороны браузера через `page.evaluate` — что честнее, потому что проверяет собственную защёлку
   клиента, а не серверный люк.
3. **`NoopEventStore` не может обслужить сценарий с сессиями.** `/sessions`, `/events`, `/preferences` и
   `/sessions/{id}/read` читают `EventStore`+`PreferencesStore` (`src/transport/Server.kt:178-183`), а
   рецепт из `withServer` подставляет `NoopEventStore` (`test/transport/WebUiServingTest.kt:3871`),
   возвращающий пустоту. Нужный двойник уже написан — `private class FakeEventStore`
   (`test/transport/TransportTest.kt:1802`, ~140 строк, `LinkedHashMap<SessionId, SessionMeta>`), — но он
   приватный и вложенный. Его извлечение входит в Task 1.
4. **`stop()` гасит терминальные мосты первым.** Значит `restart` убивает pty-ребёнка, и утверждение
   «терминал вернулся с прежним содержимым» ложно by construction. Ассерт формулируется как «сокет
   переоткрылся и новые байты идут».

## Development Approach

- **testing approach**: Regular (код, затем тесты) — с оговоркой: начиная с Task 8 «тест» и есть продукт
  задачи, поэтому там порядок обратный по существу.
- каждая задача доводится до конца перед переходом к следующей;
- изменения мелкие и сфокусированные;
- **КРИТИЧНО: каждая задача включает новые/обновлённые тесты**;
- **КРИТИЧНО: все тесты зелёные перед началом следующей задачи**;
- **КРИТИЧНО: план обновляется, если объём меняется по ходу**;
- `./kotlin build` **обязателен перед** `./kotlin test` — теперь по двум причинам: `PtyTest` экзекает
  `ptycheck`, а `WebUiCheckTest` и `webuitest` — `webuicheck`;
- **цена агрегата растёт осознанно.** После Task 6 `./kotlin test` требует браузеров (~1.2 ГБ) и сети на
  холодной машине. Быстрая петля для нативной работы — `./kotlin task :kotgent:testMacosArm64Debug`, по
  образцу `./kotlin task :build-info:testJvm` (факт 5). Это фиксируется в CLAUDE.md в Task 18;
- обратная совместимость: `resources/webui` и `src/` не трогаем вообще.

## Testing Strategy

- **нативная сюита**: остаётся зелёной на всех шагах. Baseline на старте — **921 нативный тест / 0
  skipped**, 7 JVM-тестов `build-info`, 11 проверок `ptycheck`.
- **самопроверка харнесса**: `webuicheck --self-check` прогоняет собственные утверждения в процессе и
  печатает `SUMMARY total=N failed=0`; `test/transport/WebUiCheckTest.kt` экзекает его через
  `ProcessRunner.run` и сверяет счётчик — точная калька `PtyTest`. Это дешёвый барьер без браузера: если
  сломан харнесс, браузерные тесты не должны быть местом, где это выясняется.
- **e2e/браузерные тесты**: модуль `webuitest`, Playwright for Java, ~25–30 тестов. Chromium для жестов и
  поведения, WebKit для вёрстки.
- **правило геометрии**: утверждения о вёрстке читают **геометрию** (`getBoundingClientRect`, `cols`/`rows`,
  видимость), а не строковое равенство `getComputedStyle`. Иначе новый ярус импортирует ровно ту болезнь,
  ради которой построен.
- **правило замещения**: каждый браузерный тест удаляет свой grep-аналог **в том же коммите**.
- диагностика падений: скриншот и `page.content()` в лог; `context.tracing()` пишется в артефакт CI (для
  просмотра трейса нужен разовый внешний `npx playwright show-trace`, вне проекта).

## Progress Tracking

- выполненные пункты помечать `[x]` сразу;
- новые задачи добавлять с префиксом ➕;
- блокеры фиксировать с префиксом ⚠️;
- при отклонении от объёма — править план.

## Solution Overview

Три яруса и три новых модуля.

**Ярус 1 — контракты отдачи (остаётся в Kotlin).** ~20 тестов, ~400 строк: коды ответов, `Content-Type`,
`immutable` против `no-cache`, подстановка ревизии, traversal-гард, приоритет API над static-catch-all,
404, реестр обслуживаемых модулей.

**Ярус 2 — сценарный харнесс `webuicheck` (macos/app).** Калька с `ptycheck`: test fixture, не продукт.
Поднимает **настоящий** `KotgentServer` с фейковыми edges, печатает порт и одноразовый тикет, принимает
команды на stdin, умирает по EOF или по сторожевому таймеру. Сценарии пишутся на Kotlin рядом с доменными
типами, поэтому состояния вроде `needs_approval` или «демон перезапустился» задаются как состояние, а не как
мок в браузере. Main-бинарник линкует cinterop (KT-78062 бьёт только по тестовым бинарникам), поэтому
терминал сажается на **настоящий** `Pty` под настоящим `TerminalBridge`.

**Ярус 3 — браузерный `webuitest` (jvm/lib).** Playwright for Java спавнит харнесс `ProcessBuilder`-ом,
логинится тикетом через настоящую форму `/auth`, гоняет реальный DOM, реальные жесты и реальную вёрстку.

**Почему харнесс, а не «Kotlin-тест поднимает сервер».** Один сервер на весь прогон означал бы фиксированный
сценарий, текущее между тестами состояние и необходимость либо тестовых роутов в проде, либо моков в
браузере — то есть возврат к проверке фикции. Отдельный процесс на сценарий даёт изоляцию, ноль тестового
кода в `Server.kt` и доступ к настоящему pty.

**Почему Java-биндинг Playwright, а не npm.** Зависимость объявляется как обычная библиотека каталога;
тесты остаются на Kotlin с `kotlin.test`; вход остаётся один — `./kotlin test`. Проект принципиально
`no-build` для `resources/webui`, и этот выбор сохраняет свойство: npm-острова не появляется. (Про
Dependabot см. раздел «Зависимости» — он этот каталог не читает, бампаем руками.)

## Technical Details

### Модуль `fakes` (kmp/lib, macosArm64)

Общие тестовые двойники, видимые **и** рутовому тестовому сорс-сету, **и** `webuicheck`. Рут подключает
через `test-dependencies: - ./fakes`; `webuicheck` — через обычные `dependencies`.

Состав намеренно узкий — только то, что нужно двум потребителям:

- `FakeTmux` (`io.kotgent.daemon`, 87 строк) — переезжает файлом;
- `FakeEventStore` (`EventStore` + `PreferencesStore`, ~140 строк) — **извлекается** из
  `test/transport/TransportTest.kt:1802`, где он `private` и вложенный: становится top-level и `public`,
  `TransportTest` продолжает компилироваться против него.

**Не переезжают** (YAGNI): `FakeAdapter` — в рецепте `withServer` он уже заменён пятистрочным анонимным
объектом (`test/transport/WebUiServingTest.kt:3735-3742`), второго потребителя нет; `FakePtyHandle` — вместе
с отменённым режимом `--pty=fake` (см. ниже) у него тоже нет второго потребителя.

**Пакеты не меняются.** Тогда девять файлов, использующих `FakeTmux`, не правятся: тесты в `test/daemon/`
лежат в том же пакете (импорта нет), `test/transport/` сохраняет существующий импорт. Если какой-то файл
всё же потребует импорта — **добавить импорт и идти дальше**, это не повод откатывать переезд.

Заметка: `EventStore.reliableSessionUpdates` (`src/store/EventStore.kt:249`) — дефолт интерфейса,
делегирующий в `sessionUpdates`; для фейка этого достаточно.

### Модуль `webuicheck` (macos/app, macosArm64)

Зависимости: `..` (`KotgentServer`, домен, `TerminalBridge`), `../sysnative` (настоящий `Pty`), `./fakes`.
`entryPoint: io.kotgent.webuicheck.main`.

**Два режима запуска.**

`webuicheck --self-check` — прогоняет собственные утверждения в процессе, печатает
`SUMMARY total=N failed=0` и выходит. **Stdin не читает вообще**, поэтому его можно гнать через `popen`.
Это единственный способ проверить харнесс из нативной сюиты (ограничение 1).

`webuicheck --scenario=<name> --webui-dir=<abs>` — рабочий режим для браузера. Порт всегда `0`.

**Хендшейк — ровно три строки в stdout, всё остальное в stderr:**

```
PORT=<n>
TICKET=<code>
READY
```

Тикет минтится настоящим `TicketStore.issue(token)`, поэтому браузер логинится ровно тем путём, которым
логинится телефон. Любой посторонний вывод в stdout сломает парсер — отсюда жёсткое разделение потоков.
После `restart` печатается **только** повторный `READY`: порт тот же и мастер-токен тот же, значит новый
`PORT`/`TICKET` не нужны и контракт «ровно три строки при старте» не нарушается.

**Жизненный цикл.** Процесс живёт, пока stdin открыт; EOF → graceful stop (`server.stop()`, отмена скоупов,
выход `0`). Если драйвер упадёт, ядро закроет пайп и харнесс умрёт сам — висящих процессов, держащих порт,
не остаётся. Дополнительно `--exit-after-ms=<n>` (сторожевой таймер, по умолчанию несколько минут), чтобы
время жизни не зависело от stdin в одиночку.

**Протокол команд на stdin (две команды, до EOF):**

| Команда | Что делает | Зачем |
|---|---|---|
| `emit <id> <state>` | толкает `SessionUpdate` | notify-edge, конфляция на сокете |
| `restart` | останавливает и поднимает сервер на **том же** порту, **с тем же `TokenHolder`** | единственный честный источник сигнала «демон вернулся» |

`restart` на том же порту работоспособен потому, что `src/transport/Server.kt:256` уже ставит
`reuseAddress = true` — там же комментарий ровно про EADDRINUSE после разрыва. **Тот же `TokenHolder`
обязателен:** cookie — это `HMAC-SHA256(master-token, "v1|"+issuedAt)`, и новый токен разлогинил бы все
открытые страницы, из-за чего тест реаттача падал бы по причине, к реаттачу отношения не имеющей.

`drop-ws` **вычеркнута** (ограничение 2).

**Безопасность edges — обязательна, дефолты конструктора этого не дают.** `directoryCompleter` и
`fileUploader` по умолчанию `posixDirectoryCompleter`/`posixFileUploader` (`src/transport/Server.kt:147-148`),
то есть автодополнение cwd читало бы, а загрузка файлов **писала бы** в реальную ФС разработчика. Харнесс
обязан подставить фейковый completer и записывающий-в-память uploader. Отдельно: `webUiDir` по умолчанию
cwd-относительный, а `resolveWebUiDir` — `internal` (`src/transport/Server.kt:368`) и из `webuicheck` не
виден, поэтому абсолютный путь приходит аргументом `--webui-dir` от JVM-фикстуры.

**Сценарий** — именованная функция, собирающая состояние (список `SessionMeta`; `SessionDto` сервер строит
сам) и отдающая сконфигурированный сервер. Реестр — одна `Map<String, Scenario>` в одном файле; второго
списка нет (тот же принцип, что у `lib/commands.js` для палитры).

Стартовый набор: `empty`, `sessions` (разные состояния и cwd — материал для дерева сайдбара), `attention`,
`terminal`, `restart` (store продолжает сообщать сессию живой **после** перезапуска — иначе тест покажет
уничтожение кандидата, то есть обратное проверяемому инварианту).

**Терминал.** `TerminalBridge` собирается напрямую с `realPtyFactory` и детерминированной командой
(`/bin/sh -c 'printf …; cat'`) — xterm в браузере получает живые байты, тест печатает и читает `term.buffer`.
Режим `--pty=fake` **не вводится**: сломать upstream было бы нечем (команды такой нет), а мёртвый режим —
это YAGNI.

**Чего харнесс не делает:** не пишет в `~/.kotgent`, не поднимает tmux-сервер, не спавнит агентов, не
трогает SQLite, не пишет в ФС через uploader. Терминирующий и безопасный в автоматизации.

### Модуль `webuitest` (jvm/lib)

`test-dependencies: - $libs.playwright`; в `gradle/libs.versions.toml` нужны **обе** записи —
`playwright = "1.62.0"` в `[versions]` и строка в `[libraries]`. `platforms: [jvm]` для `jvm/*` избыточен;
`src/` может остаться пустым (в спайке так и было).

Фикстура спавнит `webuicheck` через `ProcessBuilder`, читает `PORT=`/`TICKET=` из stdout с таймаутом,
пишет команды в stdin, закрывает stdin в `finally`. Бинарник ищется по фиксированным путям
`build/tasks/_webuicheck_linkMacosArm64{Debug,Release}/webuicheck.kexe`, как `PtyTest` ищет `ptycheck`;
при отсутствии — **громкое падение** с точной командой, не скип.

**Cookie не привязана к порту.** Все харнессы слушают `127.0.0.1` на эфемерных портах, поэтому cookie,
выданная харнессом A, уедет к харнессу B, не пройдёт HMAC и даст `401` → `location.replace("/auth")`.
Поэтому **переиспользования состояния контекста нет**: каждый тест получает свежий `BrowserContext` и
логинится своим тикетом.

**Правило имён:** каждый класс обязан кончаться на `Test` (факт 3), иначе «0 tests found» с ненулевым
кодом. Записать комментарием в `webuitest/module.yaml`.

**Деление движков:** Chromium (`hasTouch: true`) — жесты и поведение; WebKit — вёрстка.

### Диспозиция всех 47 тестов `WebUiServingTest.kt`

Решено сейчас, а не в конце. `KEEP` = остаётся контрактом отдачи; `T<n>` = закрывается браузерным тестом
задачи n и удаляется в её же коммите; `DEL` = удаляется без замены.

| Тест | Диспозиция |
|---|---|
| `daemonServesIndexHtmlAtRoot` | KEEP |
| `daemonServesTheAppEntryModule` | KEEP |
| `theImportMapResolvesToVendoredModulesThatAreActuallyServed` | KEEP |
| `daemonServesTheComponentAndLibModules` | KEEP (реестр модулей) |
| `daemonServesTheWebManifestWithItsOwnMediaType` | KEEP |
| `daemonServesTheAppleTouchIconAndTheSourceArtwork` | KEEP |
| `indexHtmlDeclaresThePwaInstallSurface` | KEEP |
| `revisionedAssetsAreImmutableAndEverythingElseRevalidates` | KEEP |
| `theServedShellCarriesARealRevisionAndNoHandBumpedToken` | KEEP |
| `theRevisionPrefixOnlyChangesTheAddress` | KEEP |
| `strippingTheRevisionPrefixLeavesTraversalVisibleToTheGuard` | KEEP |
| `anyChangedByteChangesTheRevision` | KEEP |
| `daemonServesTheServiceWorkerAtTheRootScope` | KEEP (путь + заголовки); grep-половина про тело воркера → DEL |
| `daemonServesTheVendoredXtermFromANestedPath` | KEEP |
| `daemonServesTheStylesheets` | KEEP |
| `aMissingStaticFileIs404` | KEEP |
| `theStaticCatchAllDoesNotShadowTheTokenGatedApi` | KEEP |
| `versionApiIsAuthenticatedAndOutranksTheStaticCatchAll` | KEEP |
| `theWebUiWiresTheBrowserPushSubscriptionFlow` | сжать до KEEP-контракта роутов `/push/*` (смонтированы и аутентифицированы); браузерная половина → Post-Completion, см. ниже |
| `theUnicodeAddonsAreVendoredAndLoadedOnlyWhenThePreferenceSelectsThem` | KEEP (аддоны завендорены и отдаются) + **T15** (грузятся только по преференсу) |
| `webUiRendersTheCurrentVersionInTheSidebarFooter` | **T8** |
| `webUiGroupsSessionsIntoARecursiveDirectoryTree` | **T8** |
| `theCommandRegistryIsTheServedSourceOfSearchAndLeaderCommands` | **T9** |
| `theCommandPaletteShipsAnAccessibleSearchListbox` | **T9** |
| `theAppOwnsThePaletteBindingAndCommandContext` | **T9** |
| `thePaletteReplacesRedundantDesktopChromeWithoutRemovingEntryPoints` | **T9** |
| `everyDialogIsDismissableWithoutAKeyboard` | **T10** |
| `theWebUiBridgesPhoneSwipesIntoXtermWheelEvents` | **T11** |
| `theWebUiReattachesAClosedAliveTerminalAfterBackgroundingOrDaemonRestart` | **T12** |
| `xtermFitSubtractsThePaddingThatFramesTerminalContent` | **T13** |
| `theWebUiShipsTheMobileDrawerAndViewportRules` | **T13** |
| `theDesktopSidebarCollapsesWithoutOverloadingTheMobileDrawer` | **T13** |
| `theShellFloatsCardsWithoutMovingPaddingOntoTheTerminalHost` | **T13** |
| `theWebUiShipsKeyboardAwareTerminalSizingAndFontPreferences` | **T13** (геометрия) + Post-Completion (safe-area) |
| `webUiExposesSessionCreationAndLifecycleControls` | **T14** |
| `webUiUsesOneClickAgentPickerWithoutADefaultSelection` | **T14** |
| `theAgentRadiosAreHiddenWithoutLeavingTheKeyboardOrTheDarkTheme` | **T14** |
| `plannedAgentsAreShownWithoutBecomingChoosable` | **T14** |
| `webUiReportsAMissingAgentInsteadOfSilentlyRefusingToStart` | **T14** |
| `webUiOffersImportingASessionStartedOutsideKotgent` | **T14** |
| `webUiExposesThePreferencesScreen` | **T14** |
| `webUiExposesTheHelpScreen` | **T14** |
| `webUiExposesThePhoneAccessScreen` | **T14** |
| `aSecondLifecycleActionIsRefusedOutLoudRatherThanDroppedSilently` | **T14** |
| `mobilePaletteUploadsPickedFilesToTheSelectedSessionsCurrentFolder` | **T15** |
| `theWebUiShipsTheMobileSpecialKeysBar` | **T15** |
| `sessionAndPaletteRowsSharePillInteractionStates` | **DEL** — сравнение CSS-строк для `:hover`/`:active`; поведения за этим нет, а правило геометрии такие утверждения запрещает |

**Про push отдельно.** Харнесс намеренно не поднимает `pushStore`/`vapidPublicKey`, поэтому `/push/*` даже
не смонтированы (`src/transport/Server.kt:188-190`). Полный браузерный флоу всё равно невоспроизводим:
`pushManager.subscribe` требует настоящего push-сервиса, которого у headless-браузера нет. Поэтому от теста
остаётся честный серверный контракт, а разрешение и подписка уходят в ручную проверку.

## What Goes Where

- **Implementation Steps** (`[ ]`): всё, что делается в этом репозитории.
- **Post-Completion** (без чекбоксов): проверки на живом железе, которые Playwright принципиально не
  закрывает.

## Implementation Steps

### Task 1: Модуль `fakes` — `FakeTmux` и извлечённый `FakeEventStore`

**Files:**
- Create: `fakes/module.yaml`
- Create: `fakes/src/daemon/FakeTmux.kt` (переезд из `test/daemon/FakeTmux.kt`)
- Create: `fakes/src/store/FakeEventStore.kt` (извлечение из `test/transport/TransportTest.kt:1802`)
- Modify: `project.yaml`, `module.yaml`, `test/transport/TransportTest.kt`

- [ ] создать `fakes/module.yaml`: `kmp/lib`, `platforms: [macosArm64]`, `dependencies: - ..`, Kotlin 2.4.10; в заголовочном комментарии — зачем модуль нужен (два потребителя) и что граф `рут(test) → fakes → рут(main)` тулчейном проверен
- [ ] перенести `FakeTmux` **без изменения пакета** (`io.kotgent.daemon`), удалив оригинал
- [ ] извлечь `FakeEventStore` из `TransportTest`: сделать top-level и `public`, оставить реализацию `EventStore` + `PreferencesStore` как есть; убедиться, что `TransportTest` компилируется против него
- [ ] зарегистрировать `- ./fakes` в `project.yaml` (список держать отсортированным) и в `test-dependencies:` рутового `module.yaml`
- [ ] проверить, что `fakes` компилируется как **main**-код `kmp/lib` (другой набор предупреждений и API-поверхность, чем у тестового фрагмента); при требовании импорта в потребителях — добавить импорт, а не откатывать переезд
- [ ] запустить `./kotlin build && ./kotlin test` — **921 / 0 skipped**, это и есть тест переезда
- [ ] тесты должны пройти до перехода к Task 2

### Task 2: Каркас `webuicheck` — `--self-check`, хендшейк, безопасные edges

**Files:**
- Create: `webuicheck/module.yaml`
- Create: `webuicheck/src/Main.kt`
- Create: `webuicheck/src/SafeEdges.kt`
- Create: `test/transport/WebUiCheckTest.kt`
- Modify: `project.yaml`

- [ ] создать `webuicheck/module.yaml` по образцу `ptycheck/module.yaml`: `macos/app`, `macosArm64`, зависимости `..`, `../sysnative`, `./fakes`, `entryPoint: io.kotgent.webuicheck.main`; в комментарии — зачем это main-бинарник и что он не трогает `~/.kotgent`/tmux/SQLite/ФС
- [ ] реализовать разбор аргументов `--self-check`, `--scenario=<name>`, `--webui-dir=<abs>`, `--exit-after-ms=<n>`; неизвестный сценарий — падение с перечислением известных имён
- [ ] реализовать `SafeEdges.kt`: фейковый `DirectoryCompleter` и записывающий-в-память `FileUploader`, чтобы автодополнение и загрузка не касались реальной ФС
- [ ] поднять `KotgentServer(port = 0)` на `FakeTmux` + `FakeEventStore` + безопасных edges + переданном `webUiDir`, сминтить тикет через `TicketStore.issue(token)`, напечатать ровно `PORT=`/`TICKET=`/`READY` в stdout, всё прочее — в stderr
- [ ] реализовать цикл чтения stdin (EOF → graceful stop, выход `0`) и сторожевой таймер `--exit-after-ms`
- [ ] реализовать режим `--self-check`: проверить старт, хендшейк, ответ `/`, редемпцию тикета, отказ неизвестного сценария; напечатать `SUMMARY total=N failed=0`; stdin не читать
- [ ] написать `test/transport/WebUiCheckTest.kt` по образцу `PtyTest`: найти бинарник (громкое падение с инструкцией `./kotlin build`), выполнить `--self-check` через `ProcessRunner.run`, сверить `SUMMARY total=$EXPECTED_CHECKS failed=0` и код `0`; при падении печатать захваченные stdout/stderr
- [ ] зарегистрировать `- ./webuicheck` в `project.yaml`
- [ ] `./kotlin build && ./kotlin test` — зелено перед Task 3

### Task 3: Реестр сценариев и состояния сессий

**Files:**
- Create: `webuicheck/src/Scenarios.kt`
- Modify: `webuicheck/src/Main.kt`

- [ ] ввести тип `Scenario` и **одну** `Map<String, Scenario>` — второго списка имён быть не должно
- [ ] реализовать `empty`, `sessions` (несколько `SessionMeta` в разных состояниях и с cwd `/a/b`, `/a/c`, `/d`), `attention`
- [ ] прокинуть выбор сценария из `--scenario` в сборку состояния
- [ ] добавить в `--self-check` проверки: для каждого сценария `/sessions` отдаёт ожидаемое число строк и ожидаемые состояния; сценарий `sessions` отдаёт именно те cwd, на которых строится дерево
- [ ] обновить `EXPECTED_CHECKS` в `WebUiCheckTest.kt`
- [ ] `./kotlin test` — зелено перед Task 4

### Task 4: Команды stdin `emit` и `restart`

**Files:**
- Create: `webuicheck/src/Commands.kt`
- Modify: `webuicheck/src/Main.kt`, `webuicheck/src/Scenarios.kt`

- [ ] реализовать построчный разбор команд; неизвестная команда — строка в stderr, процесс живёт
- [ ] `emit <id> <state>` — толкнуть `SessionUpdate` в поток, который читает `/events`
- [ ] `restart` — `server.stop()` и повторный старт на **том же** порту с **тем же** `TokenHolder`; печатать повторный `READY` и только его
- [ ] добавить сценарий `restart`, чей store продолжает сообщать сессию живой после перезапуска
- [ ] добавить в `--self-check` проверки обеих команд, вызываемые внутри процесса (не через stdin): `emit` порождает `session_update`, `restart` оставляет порт обслуживающим и cookie валидной
- [ ] обновить `EXPECTED_CHECKS`; `./kotlin test` — зелено перед Task 5

### Task 5: Сценарий `terminal` на настоящем pty

**Files:**
- Modify: `webuicheck/src/Scenarios.kt`

- [ ] собрать `TerminalBridge` напрямую (`upstreamCommand` + `seedProvider` + `realPtyFactory` + `scope`) и передать в `terminalBridgeFactory` сервера
- [ ] использовать детерминированную команду `/bin/sh -c 'printf …; cat'`; убедиться, что вывод воспроизводим побайтно (никаких меток времени и зависящего от окружения текста)
- [ ] добавить в `--self-check` проверку: подключение к терминальному WS отдаёт ожидаемый префикс, записанные байты возвращаются эхом
- [ ] обновить `EXPECTED_CHECKS`
- [ ] `./kotlin build && ./kotlin test` — зелено перед Task 6

### Task 6: Модуль `webuitest` — фикстура, логин тикетом, дымовой тест

**Files:**
- Create: `webuitest/module.yaml`, `webuitest/test/HarnessFixture.kt`, `webuitest/test/SmokeTest.kt`
- Modify: `project.yaml`, `gradle/libs.versions.toml`

- [ ] добавить в каталог **обе** записи: `playwright = "1.62.0"` в `[versions]` и `playwright = { module = "com.microsoft.playwright:playwright", version.ref = "playwright" }` в `[libraries]`; зарегистрировать модуль в `project.yaml`
- [ ] создать `webuitest/module.yaml`: `jvm/lib`, `test-dependencies: - $libs.playwright`; **в комментарии зафиксировать правило суффикса `Test`** и что его нарушение даёт молчаливое «0 tests found»
- [ ] написать `HarnessFixture.kt`: `ProcessBuilder` на `webuicheck` (поиск бинарника, громкое падение с инструкцией), передача абсолютного `--webui-dir`, парсинг хендшейка с таймаутом, отправка команд, закрытие stdin и ожидание выхода в `finally`
- [ ] добавить логин: свежий `BrowserContext` на каждый тест, открыть `/auth`, ввести тикет, дождаться перехода на `/`; переиспользования состояния между харнессами **не делать** (cookie не привязана к порту)
- [ ] написать `SmokeTest`: сценарий `sessions`, Chromium, после логина виден сайдбар с ожидаемым числом строк
- [ ] написать тест отрицательного пути: неверный тикет не пускает и оставляет форму
- [ ] `./kotlin build && ./kotlin test` — зелено перед Task 7

### Task 7: CI — кэш браузеров, артефакты падений, `.gitignore`

**Files:**
- Modify: `.github/workflows/ci.yml`, `.gitignore`

- [ ] добавить шаг `actions/cache` на `~/Library/Caches/ms-playwright` с ключом от версии Playwright; в комментарии записать, что Node ставить не нужно (драйвер вшит в артефакт)
- [ ] добавить загрузку артефактов при падении: скриншоты и трейсы
- [ ] добавить в `.gitignore` каталог вывода Playwright
- [ ] отметить в комментарии, что ключ кэша тулчейна `hashFiles('kotlin', 'project.yaml', 'module.yaml')` не инвалидируется тремя новыми манифестами модулей
- [ ] убедиться, что порядок шагов сохраняет инвариант «`./kotlin build` перед `./kotlin test`» и что прогон проходит при пустом кэше
- [ ] `./kotlin build && ./kotlin test` локально — зелено перед Task 8

### Task 8: Браузерные тесты сайдбара

**Files:**
- Create: `webuitest/test/SidebarTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест: сценарий `sessions`, из реального DOM восстанавливается ожидаемая иерархия каталогов
- [ ] тест: смена уровня группировки в Preferences меняет дерево
- [ ] тест: в футере видна текущая версия
- [ ] перенести объясняющие комментарии и удалить `webUiGroupsSessionsIntoARecursiveDirectoryTree`, `webUiRendersTheCurrentVersionInTheSidebarFooter`
- [ ] `./kotlin test` — зелено перед Task 9

### Task 9: Браузерные тесты командной палитры

**Files:**
- Create: `webuitest/test/CommandPaletteTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест поиска: `⌘K`, ввод подстроки, фильтрация, `aria-activedescendant` следует за стрелками, Enter выполняет команду
- [ ] тест лидер-режима: аккорд открывается, мнемоника срабатывает, зарезервированные чорды видимы, но неактивны
- [ ] тест: палитра уступает клавиатуру, когда открыт другой диалог; точки входа, убранные из десктопного хрома, остаются достижимыми
- [ ] перенести комментарии и удалить `theCommandRegistryIsTheServedSourceOfSearchAndLeaderCommands`, `theCommandPaletteShipsAnAccessibleSearchListbox`, `theAppOwnsThePaletteBindingAndCommandContext`, `thePaletteReplacesRedundantDesktopChromeWithoutRemovingEntryPoints`
- [ ] `./kotlin test` — зелено перед Task 10

### Task 10: Браузерные тесты light dismiss диалогов

**Files:**
- Create: `webuitest/test/DialogDismissTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест: в Chromium с `hasTouch` tap по backdrop закрывает диалог, tap по панели — нет
- [ ] тест: press внутри панели с отпусканием снаружи **не** закрывает (решает геометрия, не target)
- [ ] тест: второй контакт не закрывает диалог, начатый первым — инвариант одного `pointerId` на down/up/click
- [ ] тест: экран в состоянии busy не закрывается ни жестом, ни backdrop-ом, но Esc и × работают всегда
- [ ] перенести весь объясняющий комментарий (ключевая часть проектного журнала) и удалить `everyDialogIsDismissableWithoutAKeyboard`
- [ ] `./kotlin test` — зелено перед Task 11

### Task 11: Браузерные тесты свайпа в терминале

**Files:**
- Create: `webuitest/test/TerminalSwipeTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест: сценарий `terminal`, вертикальный свайп приводит к wheel-событиям и видимому скроллу
- [ ] тест: горизонтальный свайп жест не захватывает
- [ ] тест: свайп не вызывает появления клавиатуры (фокус не уходит в helper-textarea)
- [ ] перенести комментарии и удалить `theWebUiBridgesPhoneSwipesIntoXtermWheelEvents`
- [ ] `./kotlin test` — зелено перед Task 12

### Task 12: Браузерные тесты реаттача терминала

**Files:**
- Create: `webuitest/test/TerminalReattachTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест: команда `restart` → сокет переоткрылся и новые байты идут (**не** «прежнее содержимое вернулось»: `stop()` гасит мосты и убивает pty-ребёнка)
- [ ] тест: закрытие сокета событий из `page.evaluate` → переподключение по его возврату
- [ ] тест: переключение на другую сессию уничтожает кандидата на реаттач
- [ ] перенести комментарии и удалить `theWebUiReattachesAClosedAliveTerminalAfterBackgroundingOrDaemonRestart`
- [ ] `./kotlin test` — зелено перед Task 13

### Task 13: Браузерные тесты вёрстки в WebKit

**Files:**
- Create: `webuitest/test/LayoutTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест: FitAddon получает размер за вычетом padding — сверяются `getBoundingClientRect` и `cols`/`rows`, последняя строка не обрезана
- [ ] тест: mobile drawer открывается и закрывается; десктопный сайдбар сворачивается, не перегружая мобильный ящик
- [ ] тест: карточки не переносят padding на хост терминала (проверяется геометрией)
- [ ] тест: размер шрифта терминала из преференсов меняет `cols`/`rows`
- [ ] все утверждения читают геометрию, а не строки `getComputedStyle`; safe-area **не** проверяется — она нулевая в headless и уходит в Post-Completion
- [ ] перенести комментарии и удалить `xtermFitSubtractsThePaddingThatFramesTerminalContent`, `theWebUiShipsTheMobileDrawerAndViewportRules`, `theDesktopSidebarCollapsesWithoutOverloadingTheMobileDrawer`, `theShellFloatsCardsWithoutMovingPaddingOntoTheTerminalHost`, `theWebUiShipsKeyboardAwareTerminalSizingAndFontPreferences`
- [ ] `./kotlin test` — зелено перед Task 14

### Task 14: Браузерные тесты диалогов и действий над сессией

**Files:**
- Create: `webuitest/test/SessionDialogsTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест New session: выбор агента в один клик без предвыбранного значения, радио доступны с клавиатуры, запланированные агенты видимы, но не выбираемы
- [ ] тест: отсутствующий бинарник агента даёт внятное сообщение, а не молчаливый отказ
- [ ] тест Import: режим адоптирует сессию и не предлагает Shell
- [ ] тест: второе действие жизненного цикла отклоняется вслух, а не теряется
- [ ] тест: экраны Preferences, Help и Phone access открываются и несут ожидаемое содержимое
- [ ] перенести комментарии и удалить девять соответствующих тестов из таблицы диспозиции
- [ ] `./kotlin test` — зелено перед Task 15

### Task 15: Браузерные тесты key bar, загрузки файлов и unicode-аддонов

**Files:**
- Create: `webuitest/test/MobileFeaturesTest.kt`
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] тест key bar: специальные клавиши уходят бинарными кадрами и не крадут фокус у xterm
- [ ] тест загрузки: `setInputFiles` на пикере палитры, записывающий-в-память `FileUploader` харнесса получает ожидаемое имя и содержимое; частичный батч сообщает про каждое упавшее имя
- [ ] тест unicode: аддон **не** запрашивается по умолчанию, а после переключения преференса запрос происходит и `term.unicode.activeVersion` меняется
- [ ] перенести комментарии и удалить `theWebUiShipsTheMobileSpecialKeysBar`, `mobilePaletteUploadsPickedFilesToTheSelectedSessionsCurrentFolder`, а из `theUnicodeAddonsAreVendoredAndLoadedOnlyWhenThePreferenceSelectsThem` — только поведенческую половину
- [ ] `./kotlin test` — зелено перед Task 16

### Task 16: Дочистка `WebUiServingTest.kt` до контрактов отдачи

**Files:**
- Modify: `test/transport/WebUiServingTest.kt`

- [ ] сверить файл с таблицей диспозиции: каждый из 47 тестов либо KEEP, либо уже удалён вместе со своим браузерным преемником
- [ ] сжать `theWebUiWiresTheBrowserPushSubscriptionFlow` до серверного контракта `/push/*`; удалить `sessionAndPaletteRowsSharePillInteractionStates` и grep-половину `daemonServesTheServiceWorkerAtTheRootScope`
- [ ] удалить осиротевшие хелперы (`sliceBetween`, `descriptorOf`, `agentPickerOf`, `cssRuleOf` и прочие), если после чистки они не используются
- [ ] обновить KDoc класса: чем этот файл теперь является и чем перестал быть
- [ ] убедиться, что файл ужался примерно до 400 строк и что реестр обслуживаемых модулей на месте
- [ ] `./kotlin test` — зелено перед Task 17

### Task 17: Verify acceptance criteria

- [ ] проверить, что все требования из Overview выполнены и что ни одного нового grep-утверждения не добавлено
- [ ] пересчитать браузерные тесты и сверить с бюджетом ~25–30; зафиксировать фактическое время прогона
- [ ] проверить крайние случаи: отсутствующий `webuicheck.kexe` даёт внятное падение с инструкцией; убитый драйвер не оставляет висящих процессов; сторожевой таймер срабатывает; повторный прогон не конфликтует по портам
- [ ] проверить, что харнесс не создал ничего в `~/.kotgent`, в ФС проекта и на tmux-сокетах
- [ ] прогнать полную сюиту: `./kotlin build && ./kotlin test`
- [ ] сверить итоговые числа: нативные тесты, 7 JVM `build-info`, 11 проверок `ptycheck`, `EXPECTED_CHECKS` у `webuicheck`, тесты `webuitest`; скипов по-прежнему **ноль**

### Task 18: [Final] Обновить документацию

- [ ] добавить в CLAUDE.md раздел про этот ярус: где живут UI-тесты, правило «класс обязан кончаться на `Test`», правило геометрии вместо `getComputedStyle`, деление «Chromium — жесты, WebKit — вёрстка» с обосновывающим замером из факта 4
- [ ] записать, почему нативная сюита драйвит харнесс через `--self-check`, а не через stdin (`popen` без пишущей трубы, KT-78062)
- [ ] добавить `fakes`, `webuicheck`, `webuitest` в разделы «Module structure» и «Where things live»
- [ ] обновить baseline числа тестов и записать, что `./kotlin build` обязателен перед `./kotlin test` теперь по двум причинам
- [ ] записать цену агрегата (браузеры ~1.2 ГБ, сеть на холодной машине) и быструю петлю `./kotlin task :kotgent:testMacosArm64Debug`
- [ ] переписать утверждение «There is deliberately no JavaScript test harness» — JS-сборки по-прежнему нет, но поведение проверяется браузером; правку вносить в `CLAUDE.md` (корневой `AGENTS.md` — 11-байтный указатель на него)
- [ ] переместить этот план в `docs/plans/completed/`

## Post-Completion

*Требует ручных действий или внешних систем — без чекбоксов.*

**Ручная проверка на живом железе (Playwright её не закрывает):**

- На актуальном iPhone и iPad подтвердить, что backdrop-овые `pointerdown`/`pointerup`/`click` несут
  **один и тот же** `pointerId`, в том числе в двухпальцевой последовательности. Chromium это подтверждает,
  но WebKit-ветка остаётся непроверенной: Playwright WebKit не доставляет touch-указатели вовсе (факт 4).
- Safe-area и inset: `env(safe-area-inset-*)` в headless равен нулю, `navigator.standalone` и признак
  установленного приложения не воспроизводятся. Проверить вручную, что нижний inset отдаётся только при
  наличии key bar, а подсказки не наезжают на Home-индикатор.
- Начать захваченный свайп на iPad и отдельно сделать экран busy, нажать Esc, свернуть приложение —
  каждый случай должен пружинить назад или исчезать без застрявшего `transform`.
- Открыть каждый диалог на телефоне, планшете и ноутбуке с тачем: ручка, скомпенсированный padding,
  панорамирование за заголовок, 44-пиксельный × у палитры.
- Web Push целиком: `pushManager.subscribe` требует настоящего push-сервиса, которого у headless-браузера
  нет. Проверить вручную запрос разрешения из жеста, подписку, доставку и отписку на реальном устройстве.

**Возможное продолжение (сейчас намеренно вне объёма):**

- Ярус тестов против **настоящего** `kotgent daemon` с временным `$HOME`, throwaway tmux-сокетом и
  сессиями типа `shell` — он покрыл бы реконсиляцию, миграции схемы и хуки, но медленный, требует tmux и
  не умеет производить состояния вроде `needs_approval`. Заводить только если появится конкретный класс
  регрессий, который нынешние ярусы пропускают.
