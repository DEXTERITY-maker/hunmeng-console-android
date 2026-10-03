# Результат сборки Hunmeng Console v0.0.4beta — синхронизация функций

Дата проверки: 04.10.2026 00:46 МСК.

## APK для скачивания

- [Скачать APK с пятью перенесёнными функциями](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.4-beta-sync/Hunmeng-Console-0.0.4-beta-sync.apk).
- [Страница выпуска](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/v0.0.4-beta-sync); [SHA-256](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.4-beta-sync/Hunmeng-Console-0.0.4-beta-sync.apk.sha256).
- Скачивание без входа в GitHub проверено: HTTP 200, полный файл и совпадающий SHA-256.
- Метаданные версии сохранены по NEXT-UPDATE.md: `0.0.4-beta`, код `2`. Отдельное имя файла и тег `v0.0.4-beta-sync` отличают эту сборку от ранее опубликованного APK с иконкой.
- Предыдущий выпуск `v0.0.4-beta` сохранён. Его коммит и контрольная сумма отличаются; [исторический отчёт](reports/build-v0.0.4-beta-icon.md).

## Подтверждённая сборка

- [GitHub Actions](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37155736851), попытка 1: success.
- Собранный коммит: `00948ae08954e54d1dc474e0f1512a153cbbcf9e`.
- Команда: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain`.
- Ubuntu x64, JDK 17; SDK 36, Build Tools 35.0.0; Gradle 8.13, AGP 8.13.2, Kotlin 2.3.20.

| Проверка | Результат |
| --- | --- |
| Unit-тесты | 54 пройдены, 0 ошибок, 0 пропущенных; 11.109s |
| Lint | 0 ошибок, 25 предупреждений |
| ZIP/CRC APK | Успешно |
| Подпись APK | apksigner verify: успешно, схема v2, один подписант |
| Метаданные | chat.hunmeng.console, versionName 0.0.4-beta, versionCode 2 |
| Android | min API 26, target API 36 |
| Иконка | Все 10 скомпилированных launcher PNG совпадают с предыдущим APK v0.0.4beta |
| Новые функции в пакете | Диагностика, вкладки, фильтры и копирование отчёта присутствуют в DEX |

Предупреждения lint относятся к новым версиям Android/зависимостей, рекомендациям KTX и оформлению альтернативных PNG/adaptive/themed icons; новых категорий предупреждений не появилось.

## Функции и тесты

Реализованы четыре этапа диагностики, безопасный JSON и ручное копирование при сбое, поиск/фильтры/копирование событий, три вкладки со сворачиваемыми ответами и ссылка @BotFather. Существующие ограничения и поведение токена, отправки и polling сохранены. Ответ polling 403 теперь относится к правам бота, а не к отклонённому токену.

22 новых теста дополняют прежние 32. [Описание реализации и границ проверок](reports/feature-sync.md).

Первый прогон [37155394818](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37155394818) остановился при компиляции теста из-за недоступного в Android JSONObject.keySet; тест исправлен на keys(). Следующий [37155564841](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37155564841) прошёл успешно. Финальный прогон выше также включает исправление классификации 403 и прошёл полностью.

## Файлы

- Проект: `/storage/emulated/0/Hunmeng Console`.
- APK: `app/build/outputs/releases/v0.0.4-beta-sync/Hunmeng-Console-0.0.4-beta-sync.apk`.
- Стандартный выход: `app/build/outputs/apk/debug/app-debug.apk`.
- На телефоне: `/storage/emulated/0/Download/Hunmeng-Console-0.0.4-beta-sync.apk`.
- Размер: 12352098 байт.
- SHA-256: `85df3e542259b20b9ae5c799f2761f446e942853d67bcb9c26bb88af1d535786`.
- Отчёты: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`.

## Подпись и проверка телефона

Это debug APK. Сертификат отличается от обоих прежних APK: v0.0.3beta и v0.0.4beta с иконкой. Обычное обновление поверх них невозможно. Локальный debug-ключ Termux проверен по открытому сертификату: он также не совпадает с прежними APK; ключей `.jks`/`.keystore` внутри проекта не найдено. Постоянный ключ CI не настроен, существующий локальный ключ не использовался для подмены подписи.

Установленное приложение не удалялось и новый APK не устанавливался автоматически. На устройстве отдельно нужны подтверждения новой сборки, иконки, вкладок, физического поворота, системного clipboard, ссылки @BotFather и подключения настоящего бота. Успешная CI-сборка этого не подтверждает.

- MockWebServer получает только вымышленные данные; реальные Telegram-запросы разработки не выполнялись.
- .env и локальные Telegram-файлы с токенами не читались; токен не включён в исходники, отчёты и APK. Проверка DEX не обнаружила строки, соответствующие формату Telegram-токена.
- Токен, сообщения, события и диагностика остаются в памяти; постоянная фоновая служба и источник автоматических APK-обновлений не добавлялись.
- Сборка выполнена в CI, а не в Termux ARM64. Новые исходники и отчёты опубликованы в репозитории; APK доступен в отдельном бета-выпуске.
