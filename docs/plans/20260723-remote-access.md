# Удалённый доступ с телефона + UX токена

## Overview

Сегодня попасть в Web UI можно единственным способом: вручную скопировать 64-символьный токен из
`~/.kotgent/token` и склеить `http://127.0.0.1:27508/#token=<токен>`. Доступа с телефона нет вообще —
демон слушает только `127.0.0.1`, а TLS на Kotlin/Native невозможен (проверено: `ktor-server-cio`
3.4.3 для `macosArm64` не содержит `sslConnector`, это JVM-only API).

План решает три отдельные задачи:

1. **Убрать секрет из URL.** Токен перестаёт появляться в адресной строке, истории браузера,
   синхронизации закладок и логах Cloudflare. Браузер получает отдельный stateless cookie-ключ,
   мастер-токен остаётся ключом машины (хуки, CLI).
2. **`kotgent web`** — открыть UI одной командой вместо копипасты.
3. **Вход с телефона по QR** через уже работающий cloudflared named tunnel
   (`kotgent.heapyhop.com`) под Cloudflare Access.

Побочно закрывается дыра, которая появилась бы сама собой вместе с cookie: проверки `Host` и
`Origin`. Без них любая страница на `*.heapyhop.com` могла бы слать запросы к демону с приложенной
cookie — `SameSite` оперирует сайтом (eTLD+1), а значит `sql.heapyhop.com` и `qa.heapyhop.com` для
`kotgent.heapyhop.com` — тот же сайт.

## Context (from discovery)

**Файлы, которых касается план:**

- `src/transport/Auth.kt` — один hex-токен на всё; `presentedToken()` читает `Bearer` **или**
  `?token=`; `authenticated()` — транспарентный child-route с интерцептором в
  `ApplicationCallPipeline.Plugins`; `constantTimeEquals()`, `readOrCreateToken()`; `generateToken()`
  и `readFileBytesOrNull()` — **приватные** (`:321`, `:333`), `writePrivateFile()` — `internal`.
- `src/transport/Server.kt:69-100` — токен захвачен как `private val token: String` и передан в
  `authenticated(token)`; порт **не сохраняется** как свойство (передаётся в `embeddedServer` на
  `:82`, реальное значение доступно только из `suspend fun port()` на `:135`); hook-роуты
  смонтированы **вне** `authenticated {}`; `staticWebUi` открыт намеренно; второй приватный
  `readFileBytesOrNull` на `:248`.
- `src/transport/HookRoutes.kt:52,87` — свой header-check против того же токена.
- `src/transport/EventsWs.kt:47` — KDoc описывает `?token=` на хендшейке.
- `src/cli/AttachClient.kt:106` — терминальный WS URL несёт `?token=` в query.
- `src/cli/ApiClient.kt:100` — `Authorization: Bearer`.
- `src/cli/Cli.kt` — чистый `parseArgs`, `USAGE`, `DEFAULT_PORT = 27508`.
- `src/cli/Commands.kt:144` — `daemon()` зовёт `readOrCreateToken()`, пишет `claude-hook-header` /
  `codex-hook-header` и настройки хуков.
- `resources/webui/app.js:38` — `TOKEN = parseToken(window.location.hash)`;
  `lib/api.js:40` — `apiRequest` шлёт `fetch` без метода для чтений;
  `components/dialogs.js` — три диалога; роутинга в SPA нет.

**Паттерны, на которые опираемся:**

- `test/transport/TransportTest.kt:322` — `withServer` поднимает `KotgentServer` с фейками
  (`CannedAgentFactory`, `FakeEventStore`, `WsFakePtyFactory`, `webUiDir = null`, `port = 0`).
- `test/transport/WebUiServingTest.kt:260-268` — второй харнесс, тоже конструирует `KotgentServer`.
- Host-free ядро + тонкие края: логика решения — чистые функции, в `Auth.kt` остаётся вызов.
- Вендоринг ES-модулей без сборки (`resources/webui/vendor/`: preact, htm, xterm).

**Проверено в klib (важно для объёма работ):**

- `ktor-server-core` для `macosArm64` **содержит** `RequestCookies` и `ResponseCookies` — это core,
  не плагин. Значит `call.request.cookies[...]` и `call.response.cookies.append(...)` доступны, и
  свой парсер `Cookie:` / сериализатор `Set-Cookie` писать не нужно.
- `ktor-server-cio` для `macosArm64` **не содержит** `sslConnector` — TLS на native невозможен.

**Внешнее состояние (уже есть, менять не нам):**

- `~/.cloudflared/config.yml` — named tunnel `d096601f-1580-4d64-a591-8d5834079b26`,
  ingress уже содержит `sql.heapyhop.com` и `qa.heapyhop.com`; cloudflared стоит системным демоном
  (`com.cloudflare.cloudflared.plist`).

**Ограничения:**

- KT-78062 — CommonCrypto через cinterop не линкуется в тестовый бинарь, поэтому SHA-256 и HMAC
  пишем чистым Kotlin.
- CLAUDE.md запрещает запускать демона или настоящего агента в автоматизации — поэтому всё, что
  требует живого туннеля и телефона, живёт в Post-Completion, а не в задачах.

## Development Approach

- **Testing approach**: Regular (реализация, следом тесты в той же задаче).
- каждая задача завершается полностью до перехода к следующей
- маленькие сфокусированные изменения
- **КРИТИЧНО: каждая задача обязана содержать новые/обновлённые тесты** для своего кода
- **КРИТИЧНО: все тесты зелёные перед началом следующей задачи**
- **КРИТИЧНО: план обновляется, если по ходу меняется объём**
- `./kotlin build` перед `./kotlin test` (PtyTest запускает бинарь ptycheck)
- baseline на старте: **253 run / 253 passed / 0 skipped** (значение 204 в исходном плане устарело —
  измерено фактическим прогоном перед Task 1)

## Testing Strategy

- **unit-тесты** — обязательны в каждой задаче
- **e2e** — Playwright в этот план **не входит** (отдельный трек вместе с мобильным UX).
  JS в проекте не исполняется ни в одном тестовом бинаре `macosArm64`; `WebUiServingTest` проверяет
  только факт отдачи файлов. Браузерные инварианты (cookie доезжает до WS-хендшейка, `SameSite`
  отрабатывает, `location.replace` не оставляет тикет в истории, QR отрисовался) проверяются вручную
  в Post-Completion.
- **Vitest не добавляем** — после выпиливания `parseToken` чистых функций в `lib/` остаётся немного
  и все тривиальные; npm-тулинг в репозиторий без шага сборки не тащим.

## Progress Tracking

- отмечать выполненное `[x]` сразу
- новые задачи — с префиксом ➕
- блокеры — с префиксом ⚠️
- обновлять план, если реализация расходится с изначальным объёмом

## Solution Overview

**Два ключа с разными ролями.**

*Мастер-токен* `~/.kotgent/token` — ключ **машины**: хуки claude/codex, весь CLI по `Bearer`, выпуск
тикетов. Внутри демона превращается из `val` в атомарный провайдер, чтобы ротироваться на лету.

*Cookie* — ключ **браузера**: `v1.<issuedAt>.<hmac>`, где `hmac = HMAC-SHA256(мастер-токен,
"v1|" + issuedAt)`. `HttpOnly; SameSite=Strict; Path=/`, `Max-Age` на 10 лет (без явного срока Safari
на телефоне потеряет её при перезапуске), `Secure` — когда запрос пришёл на публичный хост.
Stateless: никакой таблицы сессий, никаких миграций схемы. «Отозвать все устройства» = ротация
мастер-токена, после которой все HMAC мертвы одновременно.

**Поток входа — тикет живёт во фрагменте:**

1. `POST /auth/ticket` (Bearer, только loopback) → `{ticket, localUrl, publicUrl, expiresAt}`,
   где URL имеют вид `https://kotgent.heapyhop.com/auth#ticket=…`
2. `GET /auth` — без авторизации, без параметров, отдаёт статическую страницу
3. её JS читает `location.hash`, постит на `POST /auth/exchange` — тикет сгорает, приходит
   `Set-Cookie`, дальше `location.replace("/")`

Фрагмент вместо query решает сразу три вещи: тикет **вообще не доходит до сервера** на шаге 2, а
значит его физически не может сжечь префетчер, сканер ссылок или антивирус; он не попадает в логи
Cloudflare; и он не зависит от того, сохраняет ли Access query-параметры через цепочку SSO-редиректов
(фрагмент браузер переносит через редиректы сам).

**Единое правило авторизации.** Чистая функция `authorize(...)`: allowlist по `Host` и проверка
`Origin` — **обязательного на не-GET и на WS-хендшейках, проверяемого на совпадение всегда, когда он
присутствует**. Браузер не шлёт `Origin` на same-origin GET, поэтому требовать его на чтениях
нельзя — это убило бы весь UI. Безопасность от этого не страдает: cross-site `fetch` уходит в
CORS-режиме и потому **всегда** несёт `Origin`; все изменения состояния — POST; а WS-хендшейк,
единственный браузерный канал в обход CORS, `Origin` несёт всегда.

`Bearer` не требует `Origin` никогда — это не браузер. `/hooks/*` и выпуск тикетов дополнительно
ограничены loopback-хостом: наружу публикуется только браузерная поверхность.

**Ключевые решения и почему именно так:**

| Решение | Почему |
|---|---|
| Stateless cookie вместо таблицы сессий | Просили «бессрочные + отозвать все» — под это состояние не нужно |
| SHA-256 чистым Kotlin | KT-78062: CommonCrypto через cinterop не линкуется в тестовый бинарь |
| Тикеты in-memory, не в SQLite | Живут 10 минут; рестарт демона их убивает — и правильно |
| Тикет во фрагменте, не в query | Сервер его не видит на `GET /auth` → нечего сжигать префетчеру, нечего писать в логи Cloudflare, нечего терять на редиректах Access |
| `Origin` обязателен только на не-GET и WS | Браузеры не шлют `Origin` на same-origin GET; требование на чтениях положило бы UI |
| loopback-матчинг игнорирует порт | Харнессы биндят `port = 0`, реальный порт известен только после `start()` |
| Cookie через core-API Ktor | `RequestCookies`/`ResponseCookies` есть в native-klib — свой парсер писать незачем |
| QR ведёт на список сессий | Deep-link потребовал бы hash-роутинга в SPA и открыл бы open-redirect в параметре `to` |
| `?token=` и `#token=` выпиливаются целиком | Оставленный «на всякий случай» запасной путь отменяет всю цель затеи |
| Автодетект public-url из cloudflared — нет | YAML-парсер на K/N ради значения, которое вводится раз в жизни |

## Technical Details

**Формат cookie:** `kotgent_session=v1.<issuedAtMillis>.<hmacHex>`
`hmac = HMAC-SHA256(key = мастер-токен как UTF-8, message = "v1|" + issuedAtMillis)`.
Проверка — пересчёт и `constantTimeEquals` (уже есть в `Auth.kt`). `issuedAt` нужен, чтобы значение
различалось между устройствами.

**Формат тикета:** 32 байта из `/dev/urandom` в hex. Хранение — map в памяти демона, TTL 10 минут,
одноразовость, чистка протухших при каждом обращении. Время инжектируется как `now: () -> Long`
(в проекте так принято; `getTimeMillis()` deprecated на уровне ERROR).

**Ответ `POST /auth/ticket`:**
```json
{"ticket":"…64 hex…","localUrl":"http://127.0.0.1:27508/auth#ticket=…",
 "publicUrl":"https://kotgent.heapyhop.com/auth#ticket=…","expiresAt":1753280000000}
```
`publicUrl` — `null`, если `public-url` не настроен; тогда `PhoneDialog` показывает инструкцию
вместо QR.

**Порядок проверок в интерцепторе:**
```
Host не в allowlist                                  → 403
роут loopback-only, а Host не loopback               → 403
Origin присутствует и НЕ в allowlist                 → 403
метод не GET/HEAD или WS-хендшейк, а Origin отсутствует
  и credential — cookie                              → 403
Bearer валиден                                       → allow
cookie валидна                                       → allow
иначе                                                → 401
```
WS-хендшейк определяется наличием заголовка `Sec-WebSocket-Key`, а не совпадением пути.

**Конфиг** `~/.kotgent/config.json`, `0600`: `{"publicUrl":"https://kotgent.heapyhop.com"}`.
Демон читает на старте и **передаёт значение в `KotgentServer` конструктором** — транспорт не читает
файлы конфигурации сам (сегодня зависимость идёт cli → transport, и разворачивать её нельзя).
`config set` допечатывает подсказку про `launchctl kickstart -k gui/$(id -u)/io.kotgent.daemon`.

## What Goes Where

- **Implementation Steps** — всё, что делается в этом репозитории.
- **Post-Completion** — настройка cloudflared/Access, ручные браузерные проверки, отложенные треки.

## Implementation Steps

### Task 1: SHA-256, HMAC и hex

**Files:**
- Create: `src/crypto/Sha256.kt` (`package io.kotgent.crypto`)
- Create: `src/crypto/Hmac.kt`
- Create: `src/crypto/Hex.kt`
- Create: `test/crypto/Sha256Test.kt`
- Create: `test/crypto/HmacTest.kt`
- Modify: `src/transport/Auth.kt` (переиспользовать общий `hex`, убрать дублирующую логику из
  `generateToken`; открыть публичный `randomBytes(n: Int): ByteArray`, на котором `generateToken`
  и строится — чтобы источник энтропии остался один)

- [x] реализовать `sha256(bytes: ByteArray): ByteArray` — чистый Kotlin, без cinterop (KT-78062)
- [x] реализовать `hmacSha256(key: ByteArray, message: ByteArray): ByteArray` по RFC 2104
      (padding ключа, ipad/opad, ключ длиннее блока хешируется)
- [x] `hex(bytes: ByteArray): String` в `Hex.kt`; `Auth.kt:325` переходит на него
- [x] открыть `randomBytes(n)` в `Auth.kt` (32 байта из `/dev/urandom`, фолбэк на `Random`),
      `generateToken()` = `hex(randomBytes(32))`
- [x] написать тесты SHA-256 на векторах NIST (пустая строка, "abc", строка длиннее блока,
      границы паддинга 55/56/64 байта)
- [x] написать тесты HMAC на векторах RFC 4231 (случаи 1–4, плюс ключ длиннее блока)
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 2

➕ добавлено сверх плана: `hmacSha256Hex(key: String, message: String)` (форма, в которой значение
нужно cookie), константы `SHA256_BLOCK_BYTES` / `SHA256_DIGEST_BYTES` / `SECRET_BYTES`.
⚠️ baseline в «Development Approach» устарел: фактический прогон до Task 1 — **253**, а не 204;
после Task 1 — **268 run / 268 passed / 0 skipped**.

### Task 2: Формат cookie-сессии

**Files:**
- Create: `src/transport/SessionCookie.kt`
- Create: `test/transport/SessionCookieTest.kt`

- [x] `issueSessionCookie(token: String, issuedAt: Long): String` → `v1.<issuedAt>.<hmacHex>`
- [x] `verifySessionCookie(token: String, value: String?): Boolean` — разбор, отбраковка мусора,
      `constantTimeEquals` для HMAC
- [x] константа имени `kotgent_session`; **чтение и запись cookie — через core-API Ktor**
      (`call.request.cookies[...]`, `call.response.cookies.append(name, value, maxAgeInSeconds,
      path = "/", secure = …, httpOnly = true, extensions = mapOf("SameSite" to "Strict"))`).
      Свой парсер `Cookie:` и сериализатор `Set-Cookie` НЕ пишем — они есть в native-klib
- [x] тесты: round-trip; чужой токен не проходит; испорченный hmac; мусорный формат; пустая строка;
      `null`; ротация токена убивает ранее выданную cookie
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 3

➕ добавлено сверх плана: тонкие обёртки над core-API Ktor — `ApplicationCall.sessionCookie()` и
`ApplicationCall.setSessionCookie(value, secure)` (в них же живут `HttpOnly` / `SameSite=Strict` /
`Path=/` / `Max-Age`), константа `SESSION_COOKIE_MAX_AGE_SECONDS` (10 лет) и `CookieEncoding.RAW` на
обоих концах — подписанные байты совпадают с байтами на проводе. Роутов ещё нет, поэтому обёртки
проверены на голом `embeddedServer(CIO, port = 0)` в самом тесте: это же и подтверждает посылку плана
про наличие `RequestCookies`/`ResponseCookies` в native-klib (проверено вживую, не только по strings).
`verifySessionCookie` дополнительно отбраковывает пустой мастер-токен (пустой ключ HMAC — валидный
ключ, иначе битый `~/.kotgent/token` дал бы всем валидную cookie) и неканоничный `issuedAt` (`+17`).
После Task 2 — **277 run / 277 passed / 0 skipped**.

### Task 3: Чистая функция authorize()

**Files:**
- Create: `src/transport/Authorization.kt`
- Create: `test/transport/AuthorizationTest.kt`

- [x] описать вход: `RequestFacts(host, origin, authHeader, cookie, method, isWebSocket)` и
      `AuthDecision` (`Allow` / `Deny(status, reason)`)
- [x] `isLoopbackHost(host: String): Boolean` — **игнорирует порт**: `127.0.0.1`, `localhost`,
      `[::1]` на любом порту (харнессы биндят `port = 0`, реальный порт известен только после
      `start()`)
- [x] `allowedOrigins(publicUrl: String?): Set<String>` — origin публичного хоста плюс loopback-формы
- [x] реализовать `authorize(facts, publicUrl, loopbackOnly, verifyToken, verifyCookie)` строго по
      таблице из Technical Details
- [x] тесты таблицей: чужой Host → 403; loopback-only роут с внешнего Host → 403; **GET с валидной
      cookie и БЕЗ Origin → allow** (браузеры не шлют Origin на same-origin GET); GET с валидной
      cookie и чужим Origin → 403; POST с cookie без Origin → 403; POST с cookie и валидным
      Origin → allow; WS-хендшейк с cookie без Origin → 403; валидный Bearer без Origin (любой
      метод) → allow; ничего не предъявлено → 401; невалидный Bearer → 401
- [x] тесты на `isLoopbackHost`: совпадение на произвольном порту, включая `:0`
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 4

➕ добавлено сверх плана: `isAllowedHost(host, publicUrl)` и `isAllowedOrigin(origin, publicUrl)`
(предикаты, которыми выражена таблица) и чистый `bearerToken(authHeader)` — на него в Task 9
переведётся `presentedToken()`, чтобы разбор `Bearer ` жил в одном месте. Loopback-формы в
`allowedOrigins` хранятся БЕЗ порта, а кандидат канонизируется так же — так «любой порт» проходит
обычной проверкой членства в `Set`. Шаг таблицы «credential — cookie» реализован как «Bearer не
прошёл проверку» (а не «заголовка нет»): строго уже, и `verifyToken` при этом вызывается максимум
один раз. Отдельно отбраковывается литеральный `Origin: null` (его шлют sandboxed-iframe и
`file://`) и любой origin с путём/query/userinfo. `Host` без значения — 403, а не подстановка
дефолта. После Task 3 — **294 run / 294 passed / 0 skipped**.

### Task 4: Конфиг ~/.kotgent/config.json

**Files:**
- Create: `src/cli/Config.kt`
- Create: `test/cli/ConfigTest.kt`
- Modify: `src/transport/Auth.kt` (сделать `writePrivateFile` публичной; открыть публичный
  `readFileTextOrNull` и оставить его единственным читателем — приватный дубль в `Server.kt:248`
  переводится на него)
- Modify: `src/transport/Server.kt` (использование общего читателя вместо своей копии)

- [x] `KotgentConfig(publicUrl: String?)` c `@Serializable`
- [x] `readConfig(path): KotgentConfig` — отсутствующий файл даёт пустой конфиг, битый JSON —
      внятную ошибку с путём, а не падение сериализатора
- [x] `writeConfig(path, config)` — атомарно через `writePrivateFile`, `0600`
- [x] `defaultConfigPath()` рядом с `defaultTokenPath()`
- [x] валидация `publicUrl`: только `https://` (или `http://` для loopback), без пути и query
- [x] тесты: round-trip; отсутствующий файл; битый JSON; отклонение невалидного URL; права `0600`
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 5

➕ добавлено сверх плана: `publicUrlProblem(value): String?` — чистая функция «почему URL не годится»
(текст печатается пользователю в Task 10), `KotgentConfig.normalized()` (канонизация: trim, lowercase,
без хвостового `/`) и `ConfigException`. Валидация работает на ОБОИХ концах: `readConfig` отбраковывает
руками отредактированный файл, `writeConfig` — до записи (отклонённая запись оставляет прежний конфиг
нетронутым). Конфиг читается через общий `readFileTextOrNull` из `Auth.kt`, но `Server.kt` переведён на
общий `readFileBytesOrNull` (публичный, `limit` по умолчанию — весь файл): статике нужны БАЙТЫ, декод в
текст испортил бы png/svg. Заодно закрыт латентный баг того же читателя: у файла нулевого размера
`ftell` даёт 0, и при неограниченном `limit` старый код пытался выделить `ByteArray(Int.MAX_VALUE)` —
т.е. пустой `config.json` (или обрезанный `~/.kotgent/token`) уронил бы демон OOM'ом вместо «ничего не
настроено». `writeConfig` создаёт `~/.kotgent` (`0700`), если его ещё нет — `config set` может выполниться
раньше первого запуска демона. После Task 4 — **306 run / 306 passed / 0 skipped**.

### Task 5: Токен как атомарный провайдер + ротация

**Files:**
- Create: `src/transport/TokenHolder.kt`
- Create: `test/transport/TokenHolderTest.kt`
- Modify: `src/transport/Auth.kt` (`authenticated` принимает `() -> String`)
- Modify: `src/transport/Server.kt` (`token: String` → провайдер, в т.ч. в `production(...)`)
- Modify: `src/transport/HookRoutes.kt` (оба hook-роута читают через провайдер)
- Modify: `src/cli/Commands.kt` (собрать holder, передать persist-колбэк)
- Modify: `test/transport/TransportTest.kt` (конструктор на `:341`)
- Modify: `test/transport/WebUiServingTest.kt` (конструктор на `:260-268`)
- Modify: `test/transport/HookRoutesTest.kt` (`:66`, `:205`)

- [x] `TokenHolder(initial: String, persist: (String) -> Unit)` поверх `AtomicReference`:
      `current()`, `rotate(): String` (сгенерировать, вызвать `persist`, опубликовать)
- [x] протянуть провайдер через `KotgentServer`, `authenticated()`, `claudeHookRoutes`,
      `codexHookRoutes` — нигде не остаётся захваченной строки
- [x] в `Commands.daemon` собрать `persist`: перезапись `~/.kotgent/token` + обоих hook-header
      файлов (переиспользовать `writeClaudeHookSettings` / `writeCodexHookScript`)
- [x] тесты `TokenHolder`: `rotate` меняет значение, `persist` вызван с новым значением,
      старое значение больше не совпадает
- [x] тест уровня сервера: после ротации запрос со старым Bearer → 401, с новым → 200
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 6

➕ добавлено сверх плана: `generateToken()` из `Auth.kt` стала публичной — теперь мастер-токен чеканится
ровно в одном месте (`readOrCreateToken` на первом старте и `TokenHolder.rotate` на ротации), а не двумя
копиями «32 байта + hex». Порядок в `rotate()` — **persist, затем publish**: упавший `persist` оставляет
старый токен в силе (иначе живым оказался бы секрет, которого нет ни на диске, ни в hook-header файлах, и
рестарт всё равно откатил бы его назад); тест `aFailingPersistAbortsTheRotation…` это пиннит. Реализация
на `kotlin.concurrent.atomics.AtomicReference` (`@ExperimentalAtomicApi`) — есть в stdlib 2.4.10, `Mutex`
не годится: читатели на каждом запросе, включая WS-хендшейк, и они не suspend. Grace-периода «принимаем
оба токена» намеренно нет. `withIngress` в `HookRoutesTest` получил параметр `tokenProvider` (по умолчанию
прежний фиксированный токен), на нём стоит ротационный тест ингресса; серверный тест ротации живёт на
голом `embeddedServer` с единственным роутом под `authenticated(holder::current)` — падение указывает на
сам гейт, а не на фейки демона. После Task 5 — **314 run / 314 passed / 0 skipped**.

### Task 6: Хранилище одноразовых тикетов

**Files:**
- Create: `src/transport/Tickets.kt`
- Create: `test/transport/TicketsTest.kt`

- [x] `TicketStore(now: () -> Long, ttlMillis: Long = 600_000)`
- [x] `issue(): Ticket` — `hex(randomBytes(32))`, `expiresAt = now() + ttl`
- [x] `redeem(value: String): Boolean` — валиден только один раз; просроченный не проходит
- [x] чистка протухших при каждом обращении (потолок с вытеснением НЕ делаем: TTL уже ограничивает
      рост, а вытеснение дало бы непонятный отказ «мой тикет перестал работать после 17 обновлений»)
- [x] потокобезопасность (`Mutex` — как в `SqliteEventStore`)
- [x] тесты: выкуп работает один раз; повторный выкуп — `false`; выкуп после TTL — `false`;
      неизвестное значение — `false`; протухшие вычищаются
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 7

➕ добавлено сверх плана: константа `TICKET_TTL_MILLIS` (вместо голого `600_000` в сигнатуре),
`suspend fun outstandingCount()` — единственный способ проверить из теста, что чистка действительно
случилась, а не что «выкуп вернул false»; `require(ttlMillis > 0)` в `init` (нулевой TTL сделал бы
каждый тикет мёртвым при рождении — лучше громкий отказ на проводке); приватный `ticketEpochMillis()`
как дефолтные часы (по образцу `systemEpochMillis` в `SqliteEventStore`, чтобы transport не тянул
`daemonEpochMillis` из `daemon`). Решение о сроке годности живёт в ОДНОМ месте: `redeem` сначала
подметает протухшие, а потом делает простое `remove(value) != null` — поэтому просроченный тикет не
просто отвергается, а исчезает, и часы, шагнувшие назад (NTP, разбуженный ноутбук), не могут его
воскресить (тест `aRedemptionAfterTheTtlDoesNotResurrectTheTicketLater`). Граница TTL — дедлайн, а не
льгота: тикет жив при `now < expiresAt`. Сравнение — обычный поиск по map, а не `constantTimeEquals`:
значение — 256 бит энтропии, тайминг хеш-лукапа в подделку не разворачивается, а O(n) constant-time
скан добавил бы второе место, где можно ошибиться. После Task 6 — **329 run / 329 passed / 0 skipped**.

### Task 7: Обвязка authorize() — интерцептор, loopback-only, hook-роуты

**Files:**
- Modify: `src/transport/Auth.kt` (интерцептор `authenticated` зовёт `authorize`; новый
  транспарентный child-route `Route.loopbackOnly(build)` со своим селектором и интерцептором —
  по образцу `AuthRouteSelector:126-131`)
- Modify: `src/transport/HookRoutes.kt` (оба роута — под loopback-проверкой)
- Modify: `src/transport/Server.kt` (`publicUrl: String?` конструктором и в `production(...)`)
- Modify: `src/cli/Commands.kt` (`daemon()` читает конфиг и передаёт `publicUrl`)
- Modify: `test/transport/TransportTest.kt`, `test/transport/WebUiServingTest.kt`,
  `test/transport/HookRoutesTest.kt`
- Create: `test/transport/AuthorizeWiringTest.kt`

- [x] `authenticated()` вызывает `authorize()` вместо прямого сравнения токена; cookie-путь
      подключён (значение выдаётся тестами напрямую через `issueSessionCookie`, роутов ещё нет)
- [x] `Route.loopbackOnly { }` — второй транспарентный child-route; hook-роуты и (в Task 8)
      `/auth/ticket` + `/auth/rotate` заворачиваются в него
- [x] WS-хендшейк детектится по `HttpHeaders.SecWebSocketKey`, не по пути
- [x] `Secure` для cookie выводится из «Host совпал с публичным хостом», **не** из
      `X-Forwarded-Proto` (тот подделывается локальным клиентом и привёл бы к `Secure`-cookie на
      `http://127.0.0.1`, которую браузер молча выбросит)
- [x] тесты: существующие маршруты продолжают работать с Bearer при `port = 0`; GET с cookie без
      Origin → 200; POST с cookie и чужим Origin → 403; hook-роут с внешним `Host` → 403,
      с loopback → как раньше
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 8

➕ добавлено сверх плана: `ApplicationCall.requestFacts()` (сбор фактов в одном месте — край Ktor не
принимает решений) и `requiresSecureCookie(host, publicUrl)` в `Authorization.kt` — чистое правило для
`Secure`, которым Task 8 воспользуется при выдаче cookie (`https` + Host == публичный хост; loopback
всегда `false`, иначе браузер молча выбросил бы cookie на `http://127.0.0.1`). `loopbackOnly` — гейт
ТОЛЬКО по `Host` (`isLoopbackHost`), без проверки credential: это ровно первые две строки таблицы при
`loopbackOnly = true` (loopback всегда входит в allowlist), а credential остаётся за тем, кто заворачивает
(hook-роуты проверяют свой header-токен сами, `/auth/ticket` в Task 8 вложится в `authenticated {}`).
Поэтому параметр `loopbackOnly` у чистой `authorize()` остаётся выражением того же правила «одной
функцией», но в проводке Task 7 не используется. Гейт живёт ВНУТРИ `hookRoutes` (а не на месте монтажа) —
ингресс невозможно смонтировать без него, включая тесты. `?token=` пока жив: `requestFacts` собирает
`authHeader` через `presentedToken()`, т.е. query-форма превращается в синтетический `Bearer` — так Task 7
не забирает работу Task 9 и все WS-тесты остаются зелёными. `daemon()` при битом `~/.kotgent/config.json`
печатает ошибку и выходит с кодом 1 (конфиг load-bearing для авторизации), а при настроенном
`publicUrl` допечатывает вторую строку «also reachable at …». Тесты `TransportTest`/`WebUiServingTest`
править не пришлось: `publicUrl` у конструктора — с дефолтом `null`; вместо этого в `TransportTest`
добавлен тест «cookie авторизует НАСТОЯЩИЙ сервер» (доказывает, что гейт смонтирован именно в
`KotgentServer`). После Task 7 — **343 run / 343 passed / 0 skipped**.

### Task 8: Роуты /auth/ticket, /auth, /auth/exchange, /auth/rotate

**Files:**
- Create: `src/transport/AuthRoutes.kt` (включая HTML страницы `/auth` как **строковую константу** —
  так роут не зависит от `webUiDir`, который в обоих харнессах либо `null`, либо резолвится с диска)
- Create: `test/transport/AuthRoutesTest.kt`
- Modify: `src/transport/Server.kt` (монтаж)

- [x] `POST /auth/ticket` — внутри `authenticated {}` и `loopbackOnly {}`; отдаёт
      `{ticket, localUrl, publicUrl, expiresAt}`, URL с фрагментом `#ticket=…`,
      `publicUrl = null` без конфига
- [x] `GET /auth` — вне авторизации, **без параметров**, отдаёт страницу-константу; сервер тикет
      здесь не видит вообще (он во фрагменте)
- [x] страница: читает `location.hash`, постит на `/auth/exchange`, на успехе `location.replace("/")`,
      на ошибке — «тикет недействителен, выпусти новый: `kotgent web`» без указания причины
- [x] `POST /auth/exchange` — вне `authenticated {}` (credential — сам тикет), но **под Host-allowlist
      и Origin-проверкой** (это POST, браузер всегда шлёт Origin); гасит тикет, ставит cookie
- [x] `POST /auth/rotate` — под Bearer и loopback; зовёт `TokenHolder.rotate()`
- [x] тесты: выпуск тикета требует Bearer; выпуск с внешнего Host → 403; `GET /auth` ничего не гасит
      (двойной GET, затем успешный exchange); exchange гасит и возвращает cookie; повторный
      exchange → 400; exchange с чужим Origin → 403; запрос к `/sessions` с полученной cookie → 200;
      после `/auth/rotate` та же cookie → 401
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 9

➕ добавлено сверх плана: `KotgentServer` конструктор перешёл с `token: () -> String` на `tokens:
TokenHolder` (и `production(...)` вместе с ним) — `/auth/rotate` зовёт `rotate()`, которого нет за голым
`current`; `TicketStore` тоже стал конструкторным параметром (дефолт — свежий in-memory store).
`authorizeTicketExchange(facts, publicUrl)` вынесена отдельной чистой функцией: `authorize()` не умеет
выразить «credential — сам тикет, cookie ещё нет» (она всегда кончается «no valid credential»), а правило
браузера (Host-allowlist + **обязательный** Origin на этом POST) переиспользует те же `isAllowedHost` /
`isAllowedOrigin`. `refusalBody` в `Auth.kt` из `private` стала `internal` — теперь тем же телом отвечает и
exchange. Страница `/auth` — англоязычная (весь остальной UI в `resources/webui` на английском), одним
файлом-константой `AUTH_PAGE_HTML` без внешних зависимостей (никакого fetch до входа). `now: () -> Long`
инжектится и в `authRoutes` (штамп cookie), и в `TicketStore` — тесты детерминированы. `POST /auth/ticket`
берёт origin из `Host` запроса, поэтому эфемерный порт харнесса и `--port` отражаются без знания транспорта
о номере. После Task 8 — **355 run / 355 passed / 0 skipped**.

### Task 9: Выпиливание ?token= и #token=

**Files:**
- Modify: `src/transport/Auth.kt` (`presentedToken` — только `Authorization`; удалить
  `TOKEN_QUERY_PARAM` и переписать KDoc `:62-64`)
- Modify: `src/transport/EventsWs.kt` (KDoc `:47` про `?token=`)
- Modify: `src/transport/Server.kt` (KDoc про auth-layering `:54-60` — описание `#token=` и
  обоснование открытого статического бутстрапа устарели)
- Modify: `src/cli/AttachClient.kt` (`Authorization` в WS-хендшейке вместо query)
- Modify: `resources/webui/lib/api.js` (убрать `parseToken`, `wsUrl` без токена,
  `credentials: "same-origin"`)
- Modify: `resources/webui/app.js` (убрать `TOKEN`, `NO_TOKEN_STATUS`, `NO_TOKEN_HINT`)
- Modify: `resources/webui/components/TerminalPane.js` (WS без токена)
- Modify: `resources/webui/components/dialogs.js` (текст HelpDialog про `#token=` устарел)
- Modify: `test/transport/AuthTest.kt`, `test/transport/TransportTest.kt`
- Modify: `test/cli/CliTest.kt` (`:218-225` ассертит старую форму URL)

- [x] `presentedToken()` перестаёт читать query; константа и упоминания удаляются
- [x] `terminalWsUrl` больше не принимает токен; `AttachClient` шлёт `Authorization` заголовком
- [x] SPA переходит на cookie: без заголовка `Authorization`, с `credentials: "same-origin"`;
      на 401 — предложение выполнить `kotgent web`
- [x] обновить тесты, опиравшиеся на `?token=` (в частности
      `missingOrWrongTokenIsRejectedOnRestAndOnWsHandshake` и `terminalWsUrlIsBuiltFromTheHttpOrigin`)
- [x] тест: WS-хендшейк с `Authorization` проходит; тот же с `?token=` → 401
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 10

➕ уточнения по объёму: `TOKEN_QUERY_PARAM` удалён целиком (нет других ссылок), `presentedToken()`
переведён на общий `bearerToken(...)` — разбор `Bearer ` теперь в одном месте. `Server.kt` править НЕ
пришлось: его auth-layering KDoc уже переписан в Task 5–8 и `#token=` не упоминает. `AuthTest.kt` тоже
не тронут: `?token=`/`TOKEN_QUERY_PARAM` в нём нет (оба файла были в списке плана «на всякий случай»).
Негативный тест «`?token=` → 401» встроен как ассерт в `missingOrWrongTokenIsRejectedOnRestAndOnWsHandshake`
(шлёт РЕАЛЬНЫЙ токен в query и требует отказа — доказывает, что query-форма мертва даже с верным
секретом), а не новым `@Test`, поэтому число тестов остаётся **355 run / 355 passed / 0 skipped**.

### Task 10: CLI — web, token rotate, config

**Files:**
- Modify: `src/cli/Cli.kt` (`CliCommand`, `parseArgs`, `USAGE`)
- Modify: `src/cli/Commands.kt` (обработчики)
- Modify: `src/cli/ApiClient.kt` (`issueTicket()`, `rotateToken()`)
- Modify: `test/cli/CliTest.kt`

- [x] `kotgent web [--print]` — `POST /auth/ticket`, затем `open <localUrl>` через существующий
      `ProcessRunner` (cloexec там уже есть); `--print` печатает URL вместо открытия
- [x] `kotgent token rotate` — `POST /auth/rotate`; печать нового значения и честного
      предупреждения: **новые** запросы и **новые** подключения отвергаются, уже открытые WS
      (`/events`, терминал, живой `kotgent attach`) продолжают работать до переподключения —
      авторизация вычисляется один раз, в фазе `Plugins` (`Auth.kt:96-102`)
- [x] голую команду `kotgent token` НЕ добавляем — это `cat ~/.kotgent/token`
- [x] `kotgent config set public-url <url>` / `kotgent config get` — запись/чтение конфига плюс
      подсказка про `launchctl kickstart`
- [x] обновить `USAGE`
- [x] тесты парсинга: `web`, `web --print`, `token rotate`, `config set public-url <url>`,
      `config get`; ошибочные формы (`config set` без значения, `token` без подкоманды) дают
      `Invalid` с внятным текстом
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 11

➕ добавлено сверх плана: `ApiClient.issueTicket()` возвращает весь `TicketResponse` (не только
`localUrl`) — Task 11 переиспользует `publicUrl`/`expiresAt` из того же вызова. Парсинг `token` и
`config` вынесен в `parseToken`/`parseConfig`/`parseConfigSet` (по образцу `parseStart`); `web`
принимает ТОЛЬКО `--print` — любой другой аргумент это `Invalid` (а не молчаливое игнорирование), а
`config set` понимает лишь ключ `public-url` (незнакомый ключ → exit 2 с внятным текстом ДО чтения
файла). `config set` читает текущий конфиг и делает `.copy(publicUrl = …)` вместо записи с нуля —
неизвестные ключи будущих версий переживают правку; валидация URL живёт в `writeConfig` (отклонённое
значение не трогает файл, exit 2). Подсказка печатает литеральный `launchctl kickstart -k
gui/$(id -u)/io.kotgent.daemon` (метка из `DAEMON_LABEL`) — `$(id -u)` резолвит сам шелл юзера.
`web` при неудаче `open` (exit≠0) печатает URL как фолбэк и всё равно возвращает 0 — поток входа не
становится тупиком; новый токен `token rotate` идёт в stdout, предупреждение — в stderr (stdout
остаётся чистым для секрета). ApiClient-тесты бьют по `/auth/ticket` и `/auth/rotate` через тот же
embedded-stub, что и остальные (2 теста), плюс 3 теста парсинга. После Task 10 —
**360 run / 360 passed / 0 skipped**.

### Task 11: PhoneDialog и QR

**Files:**
- Create: `resources/webui/vendor/qrcode.module.js` (вендоренный генератор, ES-модуль)
- Create: `resources/webui/lib/qr.js` (обёртка: строка → SVG-разметка)
- Modify: `resources/webui/components/dialogs.js` (новый `PhoneDialog`)
- Modify: `resources/webui/app.js` (кнопка в шапке, состояние диалога)
- Modify: `resources/webui/index.html` (import map)
- Modify: `resources/webui/style.css`
- Modify: `test/transport/WebUiServingTest.kt`

- [x] вендорить QR-генератор как ES-модуль рядом с preact/htm/xterm; никакого шага сборки, никаких
      внешних запросов
- [x] `lib/qr.js` — рендер в **SVG**, без canvas
- [x] `PhoneDialog`: `POST /auth/ticket` → QR по `publicUrl`, под ним URL текстом, кнопка
      «Обновить», предупреждение «одноразовый, 10 минут, полный доступ к терминалам»
- [x] при `publicUrl == null` — инструкция вместо QR: `kotgent config set public-url …` и готовый
      фрагмент ingress для `~/.cloudflared/config.yml`
- [x] кнопка в шапке рядом с Preferences и Help
- [x] расширить `WebUiServingTest`: новые файлы (`lib/qr.js`, вендоренный модуль) отдаются с верным
      content-type
- [x] `./kotlin build && ./kotlin test` — зелено перед Task 12

➕ добавлено сверх плана: QR-генератор `vendor/qrcode.module.js` — дословный, без зависимостей порт
Project Nayuki's «QR Code generator library» (MIT), урезанный до byte-mode UTF-8 (URL всегда кодируется,
а numeric/alphanumeric-режимы выкинуты); заводится через import map как bare-specifier `qrcode` — как
preact/htm, а не относительным путём. `lib/qr.js` рисует один `<path>` по тёмным модулям на белой
quiet-zone и фиксирует чёрное-на-белом (тема НЕ инвертирует QR: инвертированный код срывает часть
сканеров телефона). `PhoneDialog` держит fetch-состояние (`loading`/`ready`/`error`), «Try again» на
ошибке; при `publicUrl == null` печатает готовый ingress-фрагмент с РЕАЛЬНЫМ портом демона из
`window.location.port` (fallback 27508). Kнопку в шапке пришлось добавлять в `Sidebar.js` (там живут
Help/Prefs — файла нет в списке Task 11, это правка сверх него): новый проп `onOpenPhone`, глиф `📱`,
`id="phone-button"`. JS в тестовом бинаре не исполняется, поэтому корректность порта дополнительно
подтверждена прогоном через node: выбор версии совпал с известными границами ёмкости byte-mode (14 байт
→ v1, 15 → v2; 105-символьный URL → v6/41×41), все внутренние ассерты порта (длины бит, диапазоны байт
RS, границы штрафа, счётчик модулей) прошли на нескольких входах. После Task 11 —
**361 run / 361 passed / 0 skipped**.

### Task 12: Verify acceptance criteria

- [x] секрет не появляется в URL нигде: `git grep -nE '[?#&]token=' -- src resources test` даёт РОВНО
      два вида попаданий, оба — доказательство, а не нарушение: (1) негативный тест
      `TransportTest.kt:216-226` (шлёт ВЕРНЫЙ токен как `?token=` на WS-хендшейк и требует отказа —
      пиннит, что query-форма мертва даже с правильным секретом) и (2) KDoc `Auth.kt:68` («старые формы
      `?token=` / `#token=` убраны целиком»). Ни один живой путь не кладёт и не читает секрет из URL —
      проверено чтением кода: `presentedToken()` (`Auth.kt:83`) читает только `Authorization: Bearer` и
      явно не имеет query-фолбэка; SPA `lib/api.js:11-15` строит `wsUrl` как `proto//host+path` без
      токена и шлёт REST c `credentials: "same-origin"` без `Authorization`; `AttachClient.terminalWsUrl`
      (`AttachClient.kt:104`) не несёт токен, а `AttachClient` ставит `Authorization: Bearer` заголовком
      на хендшейке (`:165`). `Cli.kt:127,141` (`parseToken`) — это подкоманда `kotgent token`, не URL
- [x] `kotgent web` открывает браузер и попадает в UI без ручной копипасты — manual (нужен живой демон +
      браузер; CLAUDE.md запрещает запуск демона в автоматизации); см. Post-Completion. Парсинг и вызов
      `POST /auth/ticket` покрыты ApiClient-тестами Task 10
- [x] cookie переживает перезагрузку страницы и закрытие браузера — manual (браузерный инвариант,
      `Max-Age` 10 лет; см. Post-Completion). Формат/`Max-Age`/`SameSite=Strict` покрыты
      `SessionCookieTest`
- [x] `kotgent token rotate` отвергает новые запросы со старым ключом; хуки живых сессий продолжают
      доставлять события (header-файлы перезаписаны) — код-леваль покрыт:
      `AuthorizeWiringTest.rotatingTheMasterTokenInvalidatesAnAlreadyIssuedCookie`,
      `AuthRoutesTest.rotatingTheTokenReturnsANewValueAndKillsTheCookie`,
      `HookRoutesTest.rotatingTheTokenFlipsTheIngressToTheNewValueWithoutARestart` (ингресс подхватывает
      новый токен без рестарта) и `TokenHolderTest` (persist вызван новым значением). Реальный
      прогон живого демона с claude/codex — manual, см. Post-Completion
- [x] hook-ингрессы и выпуск тикетов недоступны с внешнего Host — покрыто тестами:
      `HookRoutesTest.aHookArrivingUnderAForeignHostIs403AndAppendsNothing`,
      `HookRoutesTest.theCodexIngressIsLocalOnlyToo`, `AuthRoutesTest.issuingATicketFromTheTunnelIs403`,
      `AuthorizeWiringTest.aLoopbackOnlyRouteIsServedLocallyAndRefusedFromTheTunnel`
- [x] запрос с cookie и чужим Origin отклоняется; **GET с cookie и без Origin проходит** — покрыто:
      `AuthorizeWiringTest.aGetWithAValidCookieAndAForeignOriginIs403` +
      `AuthorizeWiringTest.aGetWithAValidCookieAndNoOriginIsServed`; чистая функция —
      `AuthorizationTest.aGetWithAValidCookieAndASameSiteButForeignOriginIsRefused` +
      `AuthorizationTest.aGetWithAValidCookieAndNoOriginIsAllowed`; на реальном сервере —
      `TransportTest.aSessionCookieAuthenticatesTheControlPlaneJustLikeABearer` (GET без Origin → 200)
- [x] `./kotlin build && ./kotlin test` — полный прогон, ноль skipped: `Build successful`, затем
      **361 run / 361 passed / 0 skipped** (33 test cases, 1874 ms)
- [x] сверить число тестов с baseline и зафиксировать новое: чекбокс говорит «было 204», но это значение
      устарело ещё до Task 1 — фактический pre-plan baseline **253** (зафиксировано в «Development
      Approach» и в примечании Task 1). Текущее — **361 / 361 / 0**, совпадает с записанным в Task 11
      (план добавил +108 тестов поверх 253)

### Task 13: [Final] Документация

- [ ] `README.md` — раздел про доступ: `kotgent web`, вход с телефона, требования к туннелю; снять
      устаревшее описание `#token=` (строки ~88-105, 123, 143, 161) и обновить Status & limitations
- [ ] `CLAUDE.md` — новые инварианты: два ключа и их роли; stateless cookie и почему нет таблицы
      сессий; правило «Origin обязателен на не-GET и WS, проверяется всегда при наличии»;
      loopback-only поверхность; SHA-256 чистым Kotlin из-за KT-78062; TLS на native отсутствует,
      отсюда туннель
- [ ] `CLAUDE.md` — обновить дерево «Where things live»: `src/crypto/`, `src/cli/Config.kt`,
      `src/transport/{SessionCookie,Authorization,TokenHolder,Tickets,AuthRoutes}.kt`
- [ ] `CLAUDE.md` — обновить baseline числа тестов
- [ ] `idea.md:9` — снять «только local-only; cloudflared-туннель в бэклоге»
- [ ] переместить план в `docs/plans/completed/`

## Post-Completion

*Требует ручных действий или внешних систем — без чекбоксов.*

**Настройка туннеля:**

- добавить в `~/.cloudflared/config.yml` третьим правилом:
  ```yaml
    - hostname: kotgent.heapyhop.com
      service: http://127.0.0.1:27508
  ```
- `cloudflared tunnel route dns d096601f-1580-4d64-a591-8d5834079b26 kotgent.heapyhop.com`
- перезапустить системный демон cloudflared
- Cloudflare Access: политика строго на личный email. За этим хостом висит терминал с правом
  выполнять что угодно на маке — щедрая политика здесь стоит дороже, чем где-либо ещё

**Проверки, которые нельзя сделать из тестов (нужен живой туннель, телефон и SSO):**

- демон видит `Host: kotgent.heapyhop.com`, а не `127.0.0.1:27508` — на этом стоит весь allowlist;
  если cloudflared перепишет заголовок, переключиться на `X-Forwarded-Host`
- Access пропускает WebSocket-апгрейд на `/events` и на терминал (иначе UI на телефоне будет
  статичным списком без live-обновлений)
- фрагмент `#ticket=` переживает цепочку SSO-редиректов Access (браузер обязан переносить его сам;
  если вдруг нет — это правка одной строки в странице `/auth`, не смена дизайна)
- вход по QR с телефона в чистом профиле: SSO → страница обмена → список сессий
- терминал сессии на телефоне: рендер, ввод, live-обновления
- `location.replace` не оставил тикет в истории телефона
- повторное открытие того же QR даёт «тикет недействителен»
- вход с ноутбука по `kotgent web` при уже открытой сессии на телефоне — оба клиента живут

**Отложенные треки (в этот план не входят):**

- мобильный UX: PWA-манифест и иконка на домашний экран, ряд клавиш Esc/Ctrl/Tab/стрелки
  (на софт-клавиатуре их нет), кнопки апрува вместо ввода «1 + Enter» в терминал
- Playwright: 4–5 тестов на браузерные инварианты, мишенью — `uicheck`-бинарь по образцу
  `ptycheck` (сервер с фейками на эфемерном порту, без настоящего агента и tmux)
