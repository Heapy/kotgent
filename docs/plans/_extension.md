# Firefox New Tab extension for kotgent — prototype plan

## Цель
Прототип WebExtension для Firefox, заменяющий New Tab страницу на свою, с UI под список kotgent-сессий («задач»). Сейчас источник данных — мок внутри расширения; интеграция с kotgent daemon вынесена в отдельное решение (см. «Не входит»).

## Решение по развилкам
- **Установка:** temporary add-on через `about:debugging` для разработки; позже — `web-ext sign` как unlisted (нужен AMO API key), constants не критично.
- **Источник данных:** мок-файл (`mock-sessions.js`) с переключателем на форму, чтобы потом заменить на `fetch` к kotgent одним движением. Exchange-код/Bearer/Native Messaging — отложены (см. «Не входит»).

## Структура расширения
```
firefox-newtab/
  manifest.json          # Manifest V2 (Firefox-нативный, v3 тоже ок, но V2 проще для newtab override)
  newtab.html            # страница-оверрайд New Tab
  newtab.css             # тёмная тема в стиле kotgent webui
  newtab.js              # рендер списка сессий из источника данных
  options.html           # настройки (пока: выбор источника мок/HTTP, поле host:port на будущее)
  options.js
  mock-sessions.js       # захардкоженный список + интерфейс dataSource
  README.md              # как загрузить как temporary add-on
```
Лежит **вне** kotgent-репозитория — отдельная папка/репо (расширение не должно зависеть от kotgent-сборки). Предлагаемое место: `/Users/yoda/dev/pet/kotgent-newtab/` (рядом), но не внутри `kotgent/`. Подтвердить место при реализации.

## manifest.json — ключевые поля
- `"manifest_version": 2`
- `"name": "kotgent newtab"`, `"version": "0.1.0"`
- `"chrome_url_overrides": { "newtab": "newtab.html" }` — **главное**, заменяет `about:newtab`
- `"options_ui": { "page": "options.html" }`
- `"browser_specific_settings": { "gecko": { "id": "kotgent-newtab@local" } }` — нужен stable id для подписи/сохранения настроек
- `"permissions": []` — для мока никаких не нужно. На будущее для HTTP к kotgent: `"host_permissions"` (V3) или `"permissions": ["http://127.0.0.1:27508/"]` (V2), плюс когда-то `"storage"` для настроек.

## UI (newtab.html/js)
- Простой список карточек: имя сессии, агент (claude/codex), `state` (running/needs_approval/...), `cwd`, `updatedAt`. Формат сознательно повторяет поля `SessionDto` из kotgent (`ControlRoutes.kt:388-414`), чтобы замена мока на реальный fetch не потребовала переделки UI.
- `dataSource.getSessions(): Promise<Session[]>` — единый интерфейс. `mock-sessions.js` экспортирует реализацию из массива; позже добавляется `http-sessions.js` с тем же интерфейсом.
- Тёмная тема, моноширинный шрифт — в духе kotgent webui. Без фреймворков (vanilla JS), чтобы держать расширение zero-dependency и мгновенно грузящимся на New Tab.

## CSP-замечание (важно для будущей интеграции)
Страница-оверрайд наследует строгий CSP браузера Firefox для `about:newtab` (`script-src 'self'`, **никакого инлайн-скрипта и инлайн-обработчиков**). Поэтому:
- Весь JS — только во внешних `.js` файлах, никаких `onclick="..."` в HTML (только `addEventListener`).
- Это же правило в будущем разрешит `fetch` к `127.0.0.1` (это не скрипт, CSP не режет connect), но **CORS и Origin-чек kotgent всё равно станут проблемой** — это и есть причина вынести интеграцию отдельно.

## Проверка
- `node --check` для каждого `.js` (соглашение по стилю kotgent для JS-файлов, хоть файлы и вне репо — привычка).
- Ручная проверка: загрузить как Temporary Add-on в `about:debugging#/runtime/this-firefox`, открыть новую вкладку, убедиться что виден мок-список; открыть options, переключить/сохранить настройку.

## Не входит (отдельным заходом)
Интеграция с реальным kotgent daemon требует серверного/архитектурного решения, потому что текущая модель безопасности kotgent блокирует расширение:
- `SameSite=Strict` на `kotgent_session` (`SessionCookie.kt:129`) → cookie не уйдёт cross-site из `moz-extension://`.
- Origin-чек отклоняет `moz-extension://` (`Authorization.kt:101-107`, `AuthRoutes.kt:438-449`) → даже `POST /auth/exchange` вернёт 403.
- Нет CORS-обработчика в `Server.kt` → любой preflight cross-origin fetch упадёт.

Три варианта для следующего захода: (A) доработать kotgent — разрешить extension origin opt-in + CORS; (B) Bearer master-token из `~/.kotgent/token` в options; (C) Native Messaging host. Решить после того, как прототип покажет, что именно стоит тянуть.

## Файлы kotgent — НЕ трогаются
Этот план ничего не меняет в `src/`, `module.yaml`, тестах. Только создаёт новую папку расширения (вне репо).