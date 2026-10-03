# Результат сборки Hunmeng Console v0.0.4beta

Дата проверки: 04.10.2026 00:08 МСК.

## APK для скачивания

- [Скачать Hunmeng Console v0.0.4beta](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.4-beta/Hunmeng-Console-0.0.4-beta.apk).
- [Страница выпуска](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/v0.0.4-beta); [SHA-256](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.4-beta/Hunmeng-Console-0.0.4-beta.apk.sha256).
- Выпуск опубликован в публичном репозитории как бета-версия.

## Подтверждённая сборка

- [GitHub Actions](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37153368435), попытка 1: success.
- Собранный коммит: `02bc49c5fa4147ab2f9ea5bff21a3eb4f056c1a7`.
- Команда: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain`.
- Ubuntu x64, JDK 17; SDK 36, Build Tools 35.0.0; Gradle 8.13, AGP 8.13.2, Kotlin 2.3.20.

| Проверка | Результат |
| --- | --- |
| Unit-тесты | 32 пройдены, 0 ошибок, 0 пропущенных |
| Lint | 0 ошибок, 25 предупреждений |
| Сборка и ZIP/CRC APK | Успешно |
| Подпись APK | apksigner verify: успешно, схема v2 |
| Метаданные | chat.hunmeng.console, versionName 0.0.4-beta, versionCode 2 |
| Android | min API 26, target API 36 |

Предупреждения lint относятся к новым версиям Android и зависимостей, рекомендациям KTX и оформлению альтернативных PNG/adaptive/themed icons.

## Файлы

- Проект: `/storage/emulated/0/Hunmeng Console`.
- APK: `app/build/outputs/releases/v0.0.4-beta/Hunmeng-Console-0.0.4-beta.apk`.
- Стандартный выход сборки: `app/build/outputs/apk/debug/app-debug.apk`.
- На телефоне: `/storage/emulated/0/Download/Hunmeng-Console-0.0.4-beta.apk`.
- Размер: 12302946 байт.
- SHA-256: `31394a4e91d2ecdc70fc2eccd2316bf60a673ef67a1300694ba397e2b2dd905c`.
- Отчёты: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`.
- Новая иконка владельца включена в ресурсы APK.

## Подпись и границы проверки

Это debug APK. Сертификат подписи отличается от APK v0.0.3beta; обычное обновление поверх установленной v0.0.3beta невозможно. Старое приложение автоматически не удаляется. Постоянный ключ подписи для следующих выпусков не настроен.

Предыдущий APK и отчёты сохранены локально в `app/build/outputs/releases/v0.0.3-beta/`. [Отчёт предыдущей сборки](reports/build-v0.0.3-beta.md).

- Реальный Telegram API во время разработки не вызывался. Mock-тесты не подтверждают подключение настоящего бота.
- Токены не включены в исходники, CI, отчёты или APK; токен приложения хранится только в памяти процесса. Файлы .env и конфигурации Telegram с токенами не читались.
- Установка и отображение интерфейса именно v0.0.4beta на телефоне пока не подтверждены. Ранее владелец подтвердил установку v0.0.3beta.
- Polling работает на переднем плане; постоянная фоновая служба и источник обновлений APK не настроены.
- Сборка выполнена в CI, а не в локальном Termux ARM64.

Последующие изменения документации не меняют опубликованный APK.
