# Серверная проверка Telegram Login

Модуль подготовлен и тестируется локально на Node.js 26. Это ещё не развёрнутый Login backend. Действующий Sites-проект публичный; его исходники `c5c6b4be275834d0c92c2765750e311fa5cfbd3d` изучены: есть Bot API proxy и browser version endpoint, Telegram Login endpoints и D1 отсутствуют.

## Подключение к существующему backend

- Подключить D1 binding `DB` в существующем `.openai/hosting.json`; применить `schema.sql` как миграцию.
- Маршрут `/api/account/login/[action]` вызывает `handleTelegramLogin(request, env)` из `router.mjs`.
- `GET config` раскрывает только доступность, публичный client ID и точный redirect URI. `POST begin/complete/cancel/verify/logout` используют JSON; разрешён собственный browser origin либо native запрос без Origin. Отдельные browser CORS разрешения не выдаются.
- Одноразовые попытки расходуются одним `DELETE ... RETURNING`: binding, срок и запрет повторного обмена сохраняются между серверными экземплярами. В D1 хранятся SHA-256 opaque session и зашифрованные AES-GCM payload. PKCE verifier, nonce и профиль не пишутся открытым JSON в базу.
- Токены Telegram проверяются RS256 по официальному JWKS. Отклоняются неверные подпись/issuer/audience/nonce/сроки/алгоритм/удалённый key header. Telegram ID берётся из подписанного `id`, а не отличающегося OIDC `sub`.
- Native приложение получает случайную opaque session на 30 дней, не Telegram ID token или Client Secret. При восстановлении проверяет её сервером. Выход удаляет серверную запись; у TDLib отдельный отзыв клиентской авторизации.

## Конфигурация владельца

В BotFather выбранный бот должен представлять Hunmeng Console. Login Widget должен использовать RS256 и разрешённый URL `https://app<client_id>-login.tg.dev/tglogin`. В Android-настройках регистрируются package `chat.hunmeng.console` и постоянный SHA-256 сертификата из `reports/v0.0.5-signing.md`.

Runtime values задаются как секреты в Sites: `TELEGRAM_LOGIN_CLIENT_SECRET`, `TELEGRAM_LOGIN_DATA_KEY` (32 случайных байта base64url). `TELEGRAM_LOGIN_CLIENT_ID` — публичный идентификатор. Секреты не включаются в исходники, APK, команды shell или ответы пользователю. До заполнения `/config` отвечает `configured: false`, остальные операции — `login_not_configured`, без выдуманного профиля.

Для отдельного TDLib-доступа нужно API-приложение владельца на my.telegram.org. API ID/hash вводятся в приложении и находятся в выбранном зашифрованном аккаунтном хранилище; Bot API-токены туда не попадают. Учётные данные и коды входа не следует передавать в переписке.

## Проверки

`node --test server/telegram-login/*.test.mjs` — 19 тестов пройдены. Они используют собственную RSA-пару, SQLite в памяти, синтетические данные и mock fetch; реальной авторизации и Telegram-запросов нет. Native source/JNI, Android Keystore и UI проверяются отдельно. Старт публичного сервиса также требует лимитов запросов и проверки миграции/конфигурации в реальном Worker.

Официальная документация: [Telegram Login](https://core.telegram.org/bots/telegram-login), [Android SDK](https://github.com/TelegramMessenger/telegram-login-android).
