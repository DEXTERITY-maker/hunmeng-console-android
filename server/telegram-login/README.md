# Серверная проверка Telegram Login

Модуль развёрнут на существующем публичном Sites backend: версия 10, source `b3a67da2306ac2b3f200c9b69aaa07a1e58776e5`, runtime revision 4, deployment `appgdep_6ac25545b00081919e530a5ac8902173` — succeeded. Browser sessions/D1 и Android-таблицы сохранены; новая миграция не нужна.

Config HTTP 200 / true возвращает OIDC Client ID `8883240190` и независимый redirect `https://app1853479971-login.tg.dev/tglogin`. Begin/cancel прошли с этим redirect, PKCE S256 и только openid/profile. Реальный вход и обмен кода ещё не проверены.

## Развёрнутая архитектура

- Используется существующий D1 binding `DB` в `.openai/hosting.json`. Три Android-таблицы добавлены отдельной Drizzle-миграцией; существующие browser-таблицы сохранены. `schema.sql` описывает схему этих новых таблиц.
- Маршрут `/api/account/login/[action]` вызывает `handleTelegramLogin(request, env)` из `router.mjs`.
- `GET config` раскрывает только доступность, публичный client ID и точный redirect URI. `POST begin/complete/cancel/verify/logout` используют JSON; разрешён собственный browser origin либо native запрос без Origin. Отдельные browser CORS разрешения не выдаются.
- Одноразовые попытки расходуются одним `DELETE ... RETURNING`: binding, срок и запрет повторного обмена сохраняются между серверными экземплярами. В D1 хранятся SHA-256 opaque session и зашифрованные AES-GCM payload. PKCE verifier, nonce и профиль не пишутся открытым JSON в базу.
- Токены Telegram проверяются RS256 по официальному JWKS. Отклоняются неверные подпись/issuer/audience/nonce/сроки/алгоритм/удалённый key header. Telegram ID берётся из подписанного `id`, а не отличающегося OIDC `sub`.
- Native приложение получает случайную opaque session на 30 дней, не Telegram ID token или Client Secret. При восстановлении проверяет её сервером. Выход удаляет серверную запись; у TDLib отдельный отзыв клиентской авторизации.

## Конфигурация владельца

Для **@hunmeng_official_bot** OIDC Client ID `8883240190` и native App URL ID `1853479971` различаются. Фактический App URL установлен по скриншоту BotFather; assetlinks HTTP 200 подтвердил package `chat.hunmeng.console`, полный постоянный SHA-256 сертификата и право открывать ссылки. [Данные BotFather](OWNER-SETUP.md).

Login Widget использует RS256 и точный redirect `https://app1853479971-login.tg.dev/tglogin`. Native App URL читается из регистрации Telegram, а не вычисляется из Client ID. Сервер разрешает точный HTTPS host `app<числовой native ID>-login.tg.dev` / path `/tglogin`, без userinfo/port/query/fragment.

Runtime: `TELEGRAM_LOGIN_CLIENT_ID` и `TELEGRAM_LOGIN_REDIRECT_URI` публичны; `TELEGRAM_LOGIN_CLIENT_SECRET` и `TELEGRAM_LOGIN_DATA_KEY` — защищённые секреты. При отсутствии отдельного redirect config возвращает false; fallback из Client ID удалён. Секреты в исходники, APK, shell или отчёты не включаются.

TDLib подключается отдельно по согласию; API ID/hash вводятся в приложении, сохраняются в выбранном зашифрованном хранилище. Токены Bot API остаются только в памяти.

## Проверки

`node --test server/telegram-login/*.test.mjs` — 22 теста пройдены на Node.js 26, локально и в CI 37176423096. Добавлены различные client/native IDs, отсутствие redirect и точные границы разрешённого URI. SQLite/RSA/mock fetch используют синтетические данные; настоящий обмен токенов не выполняется. TypeScript и portable Linux ARM64 build повторены перед публикацией версии 10.

На действующем backend config/begin/cancel каждый HTTP 200: оба публичных идентификатора/redirect совпали с APK, PKCE S256 и openid/profile подтверждены. Свежая проверочная попытка удалена; URL/state/nonce/binding не выводились. Actual assetlinks HTTP 200, package/сертификат/relation совпадают. Полный Login, корректность Client Secret при обмене и данные TDLib остаются проверкой на устройстве.

Прежний 404 относился к ошибочно вычисленному из Client ID домену; регистрация владельца была правильной. Кандидат 37174913542 устарел, использовать исправленный 37176423096.

Официальная документация: [Telegram Login](https://core.telegram.org/bots/telegram-login), [Android SDK](https://github.com/TelegramMessenger/telegram-login-android).
