# План: Полевое исследование Cursor CLI

Дата сверки с документацией: 2026-07-30

Статус: документированный baseline заполнен; проверки на машине с реальным Cursor CLI ещё не
выполнены.

## Контекст и цель

Kotgent интегрирует CLI-агентов через единый «адаптер-шов»:
`AgentAdapter.buildLaunchSpec()` + поток нормализованных `AgentEvent`-ов. Для Claude и Codex основным
каналом событий служат HTTP-хуки провайдера, а сам интерактивный агент живёт как TUI внутри `tmux`.

Первоначальная версия этого плана исходила из трёх предположений:

1. у Cursor CLI нет lifecycle-хуков;
2. существуют только интерактивный TUI и one-shot headless `stream-json`;
3. документация ничего не говорит о сохранении транскриптов.

Все три предположения больше нельзя считать верными:

- Cursor добавил CLI hooks в январе 2026 года, а текущая документация описывает lifecycle, prompt,
  tool, response и stop events;
- кроме TUI и headless существует long-lived режим `agent acp` — JSON-RPC/NDJSON протокол поверх
  stdio для собственных клиентов;
- CLI сохраняет JSONL-транскрипты, включая headless runs; точный layout хранилища всё ещё не
  документирован, зато каждый hook получает `transcript_path`, когда транскрипты включены.

Цель полевого исследования теперь уже не «найти хоть какой-нибудь сигнал», а проверить три реально
доступных integration surface и выбрать тот, который лучше сохраняет инварианты kotgent:

1. **TUI + native hooks**, доставленные только в конкретный запуск через временный plugin directory;
2. **ACP**, если нужен полностью структурированный long-lived transport и наблюдаемые approvals;
3. **headless `stream-json`**, если процесс-на-turn допустим или полезен как резервный/тестовый путь.

Исследование по-прежнему не пишет adapter. Оно должно дать точные payload-ы установленной версии,
проверить идентичность session id между поверхностями, установить layout хранилища и подтвердить
поведение внутри `tmux`.

## Официальные источники

- [Cursor CLI: Installation](https://cursor.com/docs/cli/installation)
- [Cursor CLI: Parameters](https://cursor.com/docs/cli/reference/parameters)
- [Cursor CLI: Authentication](https://cursor.com/docs/cli/reference/authentication)
- [Cursor CLI: Using Agent](https://cursor.com/docs/cli/using)
- [Cursor CLI: Headless mode](https://cursor.com/docs/cli/headless)
- [Cursor CLI: Output format](https://cursor.com/docs/cli/reference/output-format)
- [Cursor CLI: Configuration](https://cursor.com/docs/cli/reference/configuration)
- [Cursor CLI: Permissions](https://cursor.com/docs/cli/reference/permissions)
- [Cursor CLI: ACP](https://cursor.com/docs/cli/acp)
- [Cursor Hooks](https://cursor.com/docs/hooks)
- [Cursor Plugins](https://cursor.com/docs/plugins)
- [Cursor Plugins reference](https://cursor.com/docs/reference/plugins)
- [Cursor CLI changelog](https://cursor.com/docs/cli/changelog)

Документация менялась быстро. В частности, старые поисковые копии называют бинарник
`cursor-agent` и `stream-json` форматом по умолчанию; текущие страницы называют бинарник `agent` и
указывают default `text`. Источник истины для полевой машины — установленный `agent --help` плюс
точная версия из `agent --version`/`agent about --format json`.

## Что документация уже решила

### 1. Бинарник и основные команды

Текущая инструкция устанавливает standalone CLI в `~/.local/bin` и запускает его как `agent`:

```shell
curl https://cursor.com/install -fsS | bash
agent --version
agent
```

Документированы:

- `agent status --format json` и эквивалентный `agent whoami --format json`;
- `agent about --format json` — версия, система и account info;
- `agent login`, `agent logout`, `agent update`;
- `agent ls`, `agent resume`, `agent --continue`, `agent --resume [chatId]`;
- `agent create-chat` — создать пустой chat и вернуть его id;
- `--workspace <path>`;
- `--plugin-dir <path>` — повторяемый путь к локальному plugin directory;
- `--output-format text|json|stream-json`;
- `--stream-partial-output`;
- `--force` и его alias `--yolo`;
- `--sandbox enabled|disabled`, `--trust`, `--mode plan|ask`.

Точный формат строки `agent --version`, поведение alias `cursor-agent`, вывод `create-chat` и
актуальное полное множество флагов всё равно надо снять с реального бинарника.

### 2. Native hooks существуют и дают почти весь adapter seam

CLI changelog фиксирует hooks с января 2026 года. Текущая hooks reference описывает, среди прочих:

- `sessionStart` / `sessionEnd`;
- `beforeSubmitPrompt`;
- `preToolUse` / `postToolUse` / `postToolUseFailure`;
- `beforeShellExecution` / `afterShellExecution`;
- `beforeMCPExecution` / `afterMCPExecution`;
- `beforeReadFile` / `afterFileEdit`;
- `afterAgentResponse` / `afterAgentThought`;
- `stop`;
- `preCompact`;
- `subagentStart` / `subagentStop`.

Command hooks — отдельные процессы. Cursor передаёт JSON через stdin и читает JSON из stdout. Общая
часть hook payload содержит:

```json
{
  "conversation_id": "stable across turns",
  "generation_id": "changes for every user message",
  "model": "legacy model slug",
  "model_id": "structured model id when available",
  "model_params": [{ "id": "thinking", "value": "true" }],
  "hook_event_name": "beforeSubmitPrompt",
  "cursor_version": "string",
  "workspace_roots": ["/absolute/project/path"],
  "user_email": "string or null",
  "transcript_path": "string or null"
}
```

Наиболее важные provider-specific поля:

- `sessionStart.session_id` — тот же id, что `conversation_id`;
- `sessionStart` также несёт `is_background_agent` и `composer_mode`;
- `beforeSubmitPrompt` несёт `prompt` и attachments;
- `preToolUse` несёт `tool_name`, `tool_input`, `tool_use_id`, `cwd`, `agent_message`;
- `postToolUseFailure.failure_type` различает `error`, `timeout`, `permission_denied`;
- `afterAgentResponse.text` содержит завершённый assistant message;
- `stop.status` различает `completed`, `aborted`, `error`;
- `sessionEnd.reason` различает `completed`, `aborted`, `error`, `window_close`, `user_close`.

Это делает возможным как минимум следующий кандидат на нормализацию:

| Cursor signal | Кандидат в kotgent |
| --- | --- |
| `sessionStart` | `SessionBound` + capture `model`/`cwd`/`transcript_path` |
| `beforeSubmitPrompt` | `TurnStarted` |
| `preToolUse` | `ToolCall` |
| `stop(status=completed)` | `TurnCompleted` |
| `sessionEnd` / смерть pane | `Exited` |

Это пока только mapping hypothesis. На машине надо проверить порядок, кратность и поведение при
interrupt/error/resume: например, может ли `afterAgentResponse` сработать несколько раз внутри одного
turn, и всегда ли `stop` приходит после последнего tool call.

### 3. Hook config можно изолировать через plugin candidate

Обычные native hooks загружаются из:

- `<project>/.cursor/hooks.json`;
- `~/.cursor/hooks.json`;
- team/enterprise sources.

Ни project, ни user config нельзя безусловно менять из kotgent: это затронет другие запуски Cursor.
Однако CLI документирует `--plugin-dir <path>`, а plugin reference документирует hooks как компонент
плагина:

```text
kotgent-cursor-hooks/
├── .cursor-plugin/
│   └── plugin.json
├── hooks/
│   └── hooks.json
└── scripts/
    └── deliver-hook
```

Минимальный manifest требует только `name`; `hooks/hooks.json` обнаруживается автоматически. Отсюда
следует перспективный per-launch канал:

```shell
agent --plugin-dir /path/to/kotgent-cursor-hooks
```

Это документированная композиция двух возможностей, но не отдельная гарантия формулировкой «plugin
hooks из `--plugin-dir` работают в CLI». Её надо проверить первой. Успех означает, что kotgent сможет
генерировать временный plugin directory с HTTP-delivery script и не писать ни в home config Cursor, ни
в рабочий репозиторий пользователя.

### 4. Headless schema уже документирована

`agent -p` — one-shot процесс. Текущий default output — `text`, поэтому для событий нужен явный
`--output-format stream-json`.

Документированная последовательность NDJSON:

1. `system/init`;
2. `user`;
3. один или несколько `assistant`;
4. `tool_call started` / `tool_call completed`;
5. terminal `result`.

`system/init` содержит `cwd`, `session_id`, `model`, `permissionMode`, `apiKeySource`. Все события
одного запуска используют один `session_id`. Документация обозначает его как UUID.

Без `--stream-partial-output` каждый `assistant` — полный message segment между tool calls, а не
character delta. С `--stream-partial-output` появляются три разновидности assistant events:

- `timestamp_ms` есть, `model_call_id` нет — новый streaming delta, его надо append;
- оба поля есть — duplicate buffered flush перед tool call, его надо пропустить;
- обоих полей нет — duplicate final flush, его надо пропустить.

На успехе `json` и terminal `result` содержат:

```json
{
  "type": "result",
  "subtype": "success",
  "is_error": false,
  "duration_ms": 1234,
  "duration_api_ms": 1234,
  "result": "<full assistant text>",
  "session_id": "<uuid>",
  "request_id": "<optional request id>"
}
```

На ошибке процесс завершается non-zero, пишет сообщение в stderr и может не выдать ни well-formed
JSON result, ни terminal event. Поэтому stdout и stderr нельзя смешивать через `2>&1` до NDJSON
parser-а.

### 5. ACP — третья и наиболее структурированная форма запуска

`agent acp` запускает long-lived server:

- transport — stdio;
- framing — один JSON-RPC 2.0 object на строку;
- client пишет requests/notifications в stdin;
- Cursor пишет responses/notifications в stdout;
- логи могут идти в stderr.

Документированный flow:

1. `initialize`;
2. `authenticate` с `methodId: "cursor_login"` (или предварительная CLI/API-key auth);
3. `session/new` либо `session/load`;
4. один или много `session/prompt`;
5. streaming `session/update`;
6. при необходимости blocking `session/request_permission`;
7. опциональный `session/cancel`.

`session/request_permission` — настоящий наблюдаемый approval channel. Клиент отвечает одним из:

- `allow-once`;
- `allow-always`;
- `reject-once`.

Если клиент не отвечает, tool execution может зависнуть. ACP также поддерживает blocking Cursor
extensions `cursor/ask_question` и `cursor/create_plan`, то есть `needs_answer`/plan approval нельзя
игнорировать в минимальном корректном клиенте.

ACP устраняет one-turn-exit и даёт структурированные события, но меняет продуктовую форму: пользователь
больше не взаимодействует с provider TUI в `tmux`; kotgent должен стать ACP-клиентом и отрисовать/прокси
permission/question UX сам. Поэтому это не drop-in adapter, а отдельная архитектурная ветка.

### 6. Транскрипты существуют, но storage layout не документирован

Официальный CLI changelog говорит:

- sessions сохраняются как JSONL transcripts;
- headless mode тоже пишет transcripts;
- в феврале headless transcripts стали Claude Code-compatible JSONL;
- ошибки headless runs записываются в transcript;
- `--continue`, `--resume`, `agent ls` и ACP `session/load` восстанавливают conversation context.

Hooks reference дополнительно даёт абсолютный `transcript_path` и environment variable
`CURSOR_TRANSCRIPT_PATH`.

Не документированы:

- корневой каталог и workspace encoding;
- имя файла/каталога и связь с chat id;
- структура локального индекса для `agent ls`;
- стабильность JSONL schema;
- архивирование и критерий resumability;
- machine-readable listing существующих chats.

Для сессии, запущенной kotgent, `transcript_path` может снять необходимость в blind scan. Для
`kotgent import cursor <id>` всё ещё нужен locator существующей чужой сессии, поэтому layout/index
остаётся критической полевой проверкой.

### 7. Approval modes документированы частично

Конфигурация знает:

- `approvalMode`: `allowlist`, `auto-review`, `unrestricted`;
- `permissions.allow` / `permissions.deny`, причём deny побеждает;
- `sandbox.mode` и `sandbox.networkAccess`;
- `--force` / `--yolo`;
- `--sandbox enabled|disabled`.

Документация headless говорит: без `--force` изменения предлагаются, но не применяются; с
`--force` команды разрешаются, кроме явно denied. Changelog уточняет, что
`approvalMode: "unrestricted"` работает без `--force`, а с июня 2026 есть Auto-review.

В `stream-json` отдельное permission event не документировано. В native hooks нет прямого
`PermissionRequest`, хотя `beforeShellExecution`/`beforeMCPExecution` могут вернуть
`permission: "ask"`, а отказ может проявиться как
`postToolUseFailure.failure_type = "permission_denied"`. ACP, напротив, выдаёт явный
`session/request_permission`.

## Предварительные условия

На машине должен быть установлен и реально использоваться Cursor CLI:

```shell
curl https://cursor.com/install -fsS | bash
export PATH="$HOME/.local/bin:$PATH"
agent --version
```

Аутентификация:

```shell
agent login
```

либо:

```shell
export CURSOR_API_KEY=...
```

Не записывать API key, auth token, cookie, email или содержимое credential store в отчёт. JSON-вывод
`status`, `whoami`, `about`, hook payloads и config перед сохранением редактировать: секреты удалить,
email заменить на `<redacted>`.

Исследование проводить в отдельном временном Git-репозитории, чтобы:

- не загрузить случайные `.cursor/hooks.json`, rules, plugins и MCP из рабочего проекта;
- безопасно проверить write/shell tools;
- однозначно увидеть новые файлы Cursor;
- не загрязнить kotgent.

Из-за auto-update зафиксировать `agent --version` и `agent about --format json` в начале и конце
исследования. Если версии разошлись — результаты разделить по версиям.

Все результаты сохранить в этом файле под разделом `Полевые результаты`, добавив raw dumps в
`docs/research/cursor-cli/` только после redaction.

## Блок 1. Версия, расположение и help

### Уже известно из документации

- Каноническая команда текущей документации — `agent`.
- Standalone install добавляет бинарник/launcher в `~/.local/bin`.
- `status` и `whoami` поддерживают `--format json`.
- `about` поддерживает `--format json`.
- `create-chat`, `acp`, `--plugin-dir`, `--workspace`, `--resume`, `--continue` документированы.

### Что проверить на машине

- `command -v agent`, `type -a agent`, `ls -l "$(command -v agent)"`.
- Есть ли aliases/shims `cursor-agent` и `cursor`, куда они указывают.
- Полные raw dumps:

  ```shell
  agent --version
  agent --help
  agent help
  agent about --format json
  agent status --format json
  agent whoami --format json
  agent create-chat --help
  agent acp --help
  ```

- Точный формат version string и стабильный SEMVER/build-id parser candidate.
- Выходит ли `create-chat` после печати id, какой у него exit code и только ли id находится в stdout.
- Есть ли в installed help флаги, которые changelog упоминает, но parameters page не перечисляет
  (`--auto-review`, `--disable-auto-update`, interactive `--trust`).
- Работает ли auth из detached `tmux` и из окружения, близкого к launchd. На macOS отдельно проверить,
  не требует ли credential access интерактивно разблокированного Keychain.

**Артефакты:** `version.txt`, `help.txt`, `about.redacted.json`,
`status.redacted.json`, `create-chat-help.txt`, `acp-help.txt`.

## Блок 2. Интерактивный TUI + native hooks — приоритет 1

### Уже известно из документации

- `agent` без команды запускает interactive Agent mode.
- Это диалоговый UI с review, modes, approvals и multi-turn history.
- CLI работает с hooks; TUI не является «глухим» снаружи.
- `sessionStart` даёт provider id, `model`, workspace и transcript path без disk scan.
- `beforeSubmitPrompt`, tool hooks, response hooks и `stop` потенциально покрывают reducer events.
- `--plugin-dir` + plugin hooks дают кандидата на per-launch hook delivery.
- CLI documentation отдельно упоминает tmux key behavior; это не заменяет настоящую PTY-проверку.

### Что проверить на машине

1. Запустить `agent` в обычном терминале и зафиксировать:
   - full-screen/alternate-screen или inline TUI;
   - raw mode;
   - mouse/focus/bracketed-paste sequences;
   - clean terminal reset после Ctrl+D/Ctrl+C/process death.
2. Запустить его внутри изолированной detached tmux-сессии и проверить `capture-pane`, attach,
   resize, detach/reattach и Unicode.
3. Создать минимальный временный plugin:

   ```text
   <tmp>/kotgent-cursor-probe/
   ├── .cursor-plugin/plugin.json
   ├── hooks/hooks.json
   └── scripts/capture-hook
   ```

   Подключить только этим argv:

   ```shell
   agent --plugin-dir <tmp>/kotgent-cursor-probe
   ```

   Не создавать `~/.cursor/hooks.json` и не менять `.cursor/hooks.json` исследуемого проекта.
4. В probe зарегистрировать и записать отдельными JSONL:
   - `sessionStart`;
   - `beforeSubmitPrompt`;
   - `preToolUse`, `postToolUse`, `postToolUseFailure`;
   - `beforeShellExecution`, `afterShellExecution`;
   - `beforeReadFile`, `afterFileEdit`;
   - `afterAgentResponse`;
   - `stop`;
   - `sessionEnd`.
5. Выполнить сценарий:
   - первый prompt без tools;
   - чтение файла;
   - запись файла;
   - shell command;
   - отклонённое разрешение;
   - одобренное разрешение;
   - второй prompt;
   - Ctrl+C во время generation/tool;
   - штатный выход из TUI;
   - resume той же сессии.
6. Для каждого hook установить:
   - точный JSON;
   - порядок;
   - кратность на turn;
   - synchronous/fire-and-forget behavior;
   - timeout;
   - запускается ли command из plugin root;
   - наследует ли command environment;
   - есть ли hook при resume и меняется ли `sessionStart`/`session_id`.
7. Сравнить:
   - `sessionStart.session_id`;
   - common `conversation_id`;
   - `generation_id` по двум turns;
   - id в transcript;
   - id из `agent ls`/resume.
8. Проверить, не загружается ли probe-plugin в параллельный обычный `agent` без `--plugin-dir`.

### Критерий результата

Если per-launch plugin hooks работают и payload ordering достаточен, **TUI + hooks** становится
основным кандидатом: он сохраняет tmux/fan-out модель kotgent и требует только нового adapter +
hook ingress/normalizer.

Если `--plugin-dir` не грузит hooks, повторить probe в disposable project через
`.cursor/hooks.json`, но результат явно пометить как unsuitable для production delivery до появления
изолированного config channel.

## Блок 3. Headless `stream-json` — приоритет 2 как reference surface

### Уже известно из документации

- `-p` запускает non-interactive turn.
- Нужен явный `--output-format stream-json`; current default — `text`.
- stdout — NDJSON, stderr — logs/errors.
- Успех заканчивается `result`; failure — non-zero, terminal result может отсутствовать. Фактический
  success exit code всё равно записать в полевом дампе.
- `--resume=<id>` позволяет продолжать сохранённый chat в новом процессе.
- Headless runs пишут transcripts. CLI hooks документированы в целом, но их применение именно к
  headless надо подтвердить полевым запуском.
- `--force`/`--yolo` разрешает изменения, кроме explicit deny.

### Что проверить на машине

Не смешивать stderr с NDJSON:

```shell
agent -p "Say hello in one sentence" \
  --output-format stream-json \
  > /tmp/cursor-stream.stdout.jsonl \
  2> /tmp/cursor-stream.stderr.log
```

Проверки:

1. Полный success dump и exit code.
2. Реальный read/write/shell сценарий:

   ```shell
   agent -p --force \
     "Read probe.txt, create probe-summary.txt, then run a harmless shell command" \
     --output-format stream-json
   ```

3. Failure dump: invalid model либо другой безопасный воспроизводимый error.
4. Точные shapes:
   - `system/init`;
   - `user`;
   - `assistant`;
   - `readToolCall`;
   - `writeToolCall`;
   - shell/другой tool (`tool_call.function` или иной shape);
   - failed tool;
   - `result`.
5. Равенство `session_id` во всех lines и фактический UUID variant/version.
6. Формат `model` и соотношение с hook `model`/`model_id`.
7. Отдельный run с `--output-format json`.
8. Отдельный run с `--stream-partial-output`; реализовать documented de-dup rule и доказать, что
   reconstructed text равен `result`.
9. Preallocation/resume:
   - получить `CHAT_ID="$(agent create-chat)"`;
   - выполнить первый `agent -p --resume="$CHAT_ID" ...`;
   - выполнить второй `agent -p --resume="$CHAT_ID" ...`, попросив вспомнить первый turn;
   - сравнить `CHAT_ID`, stream `session_id`, hook `conversation_id`, transcript id.
10. Проверить, завершает ли каждый `-p` процесс после turn и что сохраняется между процессами.
11. Проверить hooks в headless: те же payloads и порядок относительно stream events.

**Артефакты:** раздельные `.stdout.jsonl`, `.stderr.log`, exit codes, redacted hook JSONL и небольшой
parser report, а не только визуальное описание.

## Блок 4. ACP — обязательная архитектурная ветка

### Уже известно из документации

- `agent acp` — long-lived stdio JSON-RPC server.
- Есть `session/new`, `session/load`, `session/prompt`, `session/update`, `session/cancel`.
- Есть настоящий blocking `session/request_permission`.
- Есть blocking question/plan extensions.
- Один process может обслуживать несколько turns одной session.
- Logs отделены в stderr.

### Что проверить на машине

1. Взять минимальный client из официальной ACP page и сохранить полный bidirectional wire dump с
   timestamps и redaction.
2. Зафиксировать `initialize` capabilities установленной версии.
3. Проверить auth:
   - предварительный `agent login`;
   - `CURSOR_API_KEY`;
   - не отправлять секрет в dump.
4. Выполнить:
   - `session/new`;
   - два последовательных `session/prompt`;
   - tool read/write/shell;
   - `session/cancel`;
   - штатное завершение.
5. Зафиксировать все разновидности `session/update` и их correlation ids.
6. Вызвать реальный approval и ответить по очереди:
   - `allow-once`;
   - `reject-once`;
   - при безопасном сценарии `allow-always`.
7. Вызвать `cursor/ask_question` и `cursor/create_plan`, чтобы понять минимальный обязательный client
   contract.
8. Завершить ACP process, запустить новый, выполнить `session/load` и проверить восстановление
   контекста.
9. Сравнить ACP `sessionId` с:
   - `create-chat` id;
   - TUI/headless `session_id`;
   - hook `conversation_id`;
   - transcript filename/content;
   - `agent ls`.
10. Проверить, срабатывают ли native hooks и transcript capture в ACP mode.
11. Проверить liveness semantics: что означает process exit, session stop и завершение одного prompt
    при остающемся живым server process.

### Вопрос для синтеза

ACP выигрывает по наблюдаемости approvals/questions и multi-turn protocol, но требует отказаться от
provider TUI как пользовательского интерфейса. В отчёте отдельно ответить, можно ли:

- оставить tmux лишь как process container;
- транслировать ACP updates в существующий terminal WebSocket без эмуляции чужого TUI;
- либо ACP потребует отдельного UI/protocol slice и потому не подходит для первого Cursor adapter.

## Блок 5. On-disk хранилище, resumability и import

### Уже известно из документации

- JSONL transcripts существуют и включают headless.
- Hook payload даёт `transcript_path`.
- Chats возобновляются через `--resume`, `--continue`, `agent resume`, `agent ls`, ACP `session/load`.
- С июля 2026 `agent ls`, `agent --resume` и `/resume` показывают chats across workspaces.
- Документированного `agent ls --format json` нет.

### Что проверить на машине

1. До эксперимента снять только filenames/metadata, не содержимое credential/config files:

   ```shell
   find ~/.cursor -type f -print 2>/dev/null
   ```

2. После TUI, headless, `create-chat` и ACP повторить snapshot и diff.
3. Начать от `CURSOR_TRANSCRIPT_PATH`/hook `transcript_path`, а не только от blind scan:
   - существует ли файл;
   - является ли он JSONL;
   - содержит ли chat id, cwd, model, timestamps и turn events;
   - совпадает ли id с именем файла/родительского каталога;
   - дописывается ли тот же файл после resume.
4. Определить:
   - workspace directory encoding;
   - session directory/file naming;
   - index/cache, из которого строится `agent ls`;
   - связь index entry ↔ transcript;
   - что происходит при удалённом/перемещённом workspace;
   - что происходит при archived/deleted chat, если UI это позволяет.
5. Проверить `agent ls`:
   - обычный запуск;
   - внутри PTY/tmux;
   - `agent ls --help`;
   - наличие undocumented text/JSON mode не предполагать без факта.
6. Проверить resume по id из четырёх источников:
   - `create-chat`;
   - stream-json;
   - hook;
   - ACP.
7. Проверить cwd:
   - current process cwd без `--workspace`;
   - `--workspace <absolute>`;
   - путь с `.`/`..`;
   - symlinked prefix;
   - resume из другого current directory.
8. Проверить влияние `CURSOR_CONFIG_DIR`:
   - меняет ли он только config;
   - переносит ли chats/transcripts/index;
   - можно ли безопасно изолировать весь Cursor state для теста.
9. Проверить, можно ли по одному provider id:
   - доказать наличие живого transcript (`VendorStoreProbe`);
   - получить canonical cwd (`VendorSessionLocator`);
   - получить model;
   - отличить resumable от archived/unavailable.

### Критерий результата

Для managed sessions hook `transcript_path` может быть достаточен, если kotgent сохранит его или сможет
восстановить. Для import нужен стабильный provider-owned locator. Если единственный locator —
недокументированный внутренний index, в отчёте оценить его формат и устойчивость отдельно от
transcript schema.

## Блок 6. Одобрения и разрешения

### Уже известно из документации

- Interactive CLI спрашивает разрешение на shell commands.
- `--force`/`--yolo` auto-allow, кроме explicit deny.
- `approvalMode` имеет `allowlist`, `auto-review`, `unrestricted`.
- Deny rules имеют приоритет.
- Native hooks могут вернуть `allow`, `deny`, иногда `ask`.
- `preToolUse` принимает `ask` по schema, но документация прямо говорит, что сегодня его не
  исполняет; для shell/MCP нужен соответствующий before-hook.
- ACP выдаёт `session/request_permission`.
- Отдельного permission event в stream-json schema нет.

### Что проверить на машине

1. TUI в режимах:
   - default/allowlist;
   - auto-review;
   - unrestricted;
   - `--force`;
   - explicit deny при `--force`.
2. Точный TUI approval UX и поведение через tmux.
3. Ordering native hooks относительно реального prompt:
   - вызывается ли `beforeShellExecution` до решения built-in allowlist;
   - означает ли сам hook event, что prompt обязательно появится;
   - заставляет ли ответ `{"permission":"ask"}` показать prompt;
   - получает ли approved action `postToolUse`;
   - получает ли rejected action `postToolUseFailure(permission_denied)`;
   - сохраняется ли один `tool_use_id` через цепочку.
4. Можно ли из hooks честно построить:
   - `ApprovalRequested` только когда prompt действительно показан;
   - `ApprovalResolved`/эквивалентный clear signal после решения.
5. Headless без `--force`: зависает, отказывает, только предлагает diff либо иным образом завершает
   turn; какие hooks/exit code при этом.
6. ACP permission flow и correlation с tool update.

### Возможные выводы

- **TUI + hooks:** точный `needs_approval` возможен только если hook ordering/payload различает
  фактический prompt; иначе состояние остаётся приближённым.
- **Headless + force:** approvals отсутствуют по замыслу, `needs_approval` не моделируется.
- **ACP:** approval state наблюдаем точно, но kotgent обязан реализовать ответ клиента.

## Блок 7. Конфигурация и окружение

### Уже известно из документации

Global config:

```text
~/.cursor/cli-config.json
```

Project config:

```text
<project>/.cursor/cli.json
```

На project level разрешено настраивать только permissions; остальные CLI settings — global.

Config overrides:

- `CURSOR_CONFIG_DIR`;
- `XDG_CONFIG_HOME` на Linux/BSD.

Документированные важные поля:

- `version: 1`;
- `model`;
- `approvalMode`;
- `permissions.allow` / `permissions.deny`;
- `sandbox.mode` / `sandbox.networkAccess`;
- `notifications`;
- `network.useHttp1ForAgent`.

Hooks живут отдельно в `.cursor/hooks.json`/`~/.cursor/hooks.json` или в plugin. Явного
`--config-file`/`--settings` для native hook config в parameters page нет. Вместо него
session-scoped candidate — `--plugin-dir`.

Без `--workspace` CLI использует current working directory. С `--workspace` можно задать root явно.

### Что проверить на машине

- Redacted реальную структуру `cli-config.json`, включая поля, которых нет в docs.
- Реальную структуру project `.cursor/cli.json`.
- Precedence global/project/flags.
- Допустимые реальные значения `sandbox.mode` и `sandbox.networkAccess`.
- Влияет ли `CURSOR_CONFIG_DIR` на hooks/plugins/chats или только на CLI config.
- Работает ли `--plugin-dir` одновременно с project/user plugins и каков merge/priority.
- Наследуют ли hook scripts `CURSOR_PROJECT_DIR`, `CURSOR_VERSION`,
  `CURSOR_TRANSCRIPT_PATH`.
- Не требуется ли workspace trust до загрузки project/plugin hooks.
- Latest changelog говорит, что `--trust` теперь работает и в interactive mode, тогда как parameters
  page всё ещё помечает его headless-only: проверить installed behavior.
- Как Cursor ведёт себя при minimal launchd-like `PATH`, без locale и без interactive shell.

## Блок 8. Единая матрица provider identity

Документация использует несколько имён:

- hook `conversation_id`;
- hook `sessionStart.session_id`;
- stream `session_id`;
- `agent create-chat` id;
- `agent --resume=<chatId>`;
- ACP `sessionId`;
- transcript identity.

Документированы только две локальные гарантии:

- hook `sessionStart.session_id == conversation_id`;
- stream `session_id` одинаков во всех events одного execution и обозначен как UUID.

Нельзя без полевой проверки считать, что все поверхности используют один namespace.

Заполнить таблицу реальными значениями для одной и той же conversation:

| Surface | Поле | Значение | Совпало с canonical id? |
| --- | --- | --- | --- |
| `create-chat` | stdout |  |  |
| TUI hook | `sessionStart.session_id` |  |  |
| любой hook | `conversation_id` |  |  |
| headless init | `session_id` |  |  |
| headless result | `session_id` |  |  |
| ACP new/load | `sessionId` |  |  |
| transcript | filename/field |  |  |
| `agent ls` | displayed id |  |  |

Также записать:

- UUID variant/version, если это UUID;
- case/hyphen normalization;
- меняется ли id после resume;
- новый ли id получает fork/branch;
- может ли `create-chat` надёжно preallocate id до tmux side-effect.

Если `create-chat` id можно передать в `agent --resume=<id>` и тот же id приходит в `sessionStart`,
Cursor в отличие от Codex позволит kotgent связать provider id **до** интерактивного запуска.

## Блок 9. Синтез совместимости с kotgent

### Документированный prior до полевых проверок

| Форма | Multi-turn process | Структурированные события | Approval observable | Provider TUI | Resume documented |
| --- | --- | --- | --- | --- | --- |
| TUI + hooks | Да | Да, через hooks | Под вопросом | Да | Да |
| Headless stream-json | Нет, process per turn | Да, stdout; hooks проверить | Нет в stream; hooks под вопросом | Нет | Да |
| ACP | Да | Да, JSON-RPC | Да | Нет | Да |

### Приоритет принятия решения

1. **Сначала TUI + per-launch plugin hooks.** Это единственная форма, которая документированно может
   сохранить текущие tmux, terminal fan-out и HTTP-hook seams.
2. **Затем ACP.** Это лучший structured protocol, если TUI hooks не дают честных approvals/state или
   plugin isolation не работает. Цена — отдельный client/UI architecture.
3. **Headless оставить reference/fallback.** Он полезен для точного normalizer и автоматизации, но
   process-per-turn плохо совпадает с нынешней моделью постоянной интерактивной сессии.

### Вопросы, на которые должен ответить итоговый отчёт

1. Загружаются ли native hooks из plugin, переданного только через `--plugin-dir`?
2. Достаточны ли TUI hook payload/order для `TurnStarted`, `ToolCall`, `TurnCompleted`, `Exited`?
3. Можно ли точно наблюдать TUI approval, не меняя permission policy пользователя?
4. Совпадают ли ids hooks, stream-json, ACP, transcript, `create-chat` и resume?
5. Может ли `create-chat` безопасно preallocate provider id?
6. Даёт ли `transcript_path` стабильный probe, а on-disk index — locator для import?
7. Работает ли resume после process restart для TUI, headless и ACP?
8. Корректно ли interactive TUI живёт в kotgent-style detached tmux/PTY?
9. Если TUI path неполон, оправдывает ли точность ACP стоимость нового client/UI slice?

## Формат полевого отчёта

Добавить в конец этого файла:

```text
## Полевые результаты
### Окружение и версия
### TUI/tmux
### Native hooks и plugin isolation
### Headless stream-json
### ACP
### Storage/index/import
### Approvals
### Identity matrix
### Архитектурный вывод
```

Raw dumps хранить в `docs/research/cursor-cli/`:

- `help.txt`;
- `about.redacted.json`;
- `hooks/*.redacted.jsonl`;
- `headless/*.stdout.jsonl`;
- `headless/*.stderr.log`;
- `acp/wire.redacted.jsonl`;
- `storage/file-list-before.txt`;
- `storage/file-list-after.txt`;
- `storage/transcript-sample.redacted.jsonl`;
- `tmux/capture-pane.txt`.

Не коммитить:

- API keys/auth tokens;
- email/account ids;
- содержимое credential store;
- полный пользовательский config без redaction;
- приватный source/transcript content, не нужный для schema.

Итог этого исследования станет входом для
`docs/plans/<date>-cursor-adapter.md`.

## Что этот план не делает

- Не пишет adapter.
- Не выбирает окончательно TUI/headless/ACP до полевых результатов.
- Не запускает Cursor на текущей машине.
- Не меняет user/project Cursor config.
- Не меняет application code или runtime behavior kotgent.
- Не считает undocumented storage layout стабильным API только потому, что он найден экспериментом.
