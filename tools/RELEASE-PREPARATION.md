# Подготовка подписанного Android APK

Этот процесс создаёт локальный кандидат и его проверяемые метаданные. Публикация GitHub Release, изменение `updates/android.json` и установка на телефон выполняются отдельно после настройки и проверки настоящего входа.

## Сборка в CI

Workflow `android.yml` проверяет сервер на Node.js 26, нативные библиотеки, unit-тесты, debug/release lint, debug APK и unsigned release APK. Artifact `hunmeng-console-unsigned-release` содержит исходный неподписанный APK, AAPT-метаданные и SHA-256. Ключ подписи в GitHub не передаётся.

Ручной запуск принимает `telegram_login_client_id`: только публичный OIDC Client ID из BotFather. Значение `0` оставляет вход ненастроенным. Native App URL имеет отдельный идентификатор: для выбранного бота публичные Client ID `8883240190` и App URL ID `1853479971` явно записаны в `gradle.properties`. Их нельзя вычислять друг из друга. Gradle принимает отдельный `telegramLoginAppId` для другой регистрации и проверяет согласованность конфигурации. Client Secret находится в защищённых настройках backend; API hash вводится отдельно в приложении для TDLib.

## Подпись на телефоне

`prepare_android_release.py` использует постоянный ключ из приватного Termux-каталога `~/.config/hunmeng-console/signing/`. Пароль читает `apksigner` из приватного файла; в код, аргументы с открытым паролем и вывод он не попадает. Каталог/ключ/пароль должны иметь права 0700/0600. Отпечаток сертификата фиксирован в инструменте и [отчёте подписи](../reports/v0.0.5-signing.md).

Пример для проверяемой сборки с сохранёнными текущими метаданными:

```sh
gh run download 37998764751 --repo DEXTERITY-maker/hunmeng-console-android --name hunmeng-console-unsigned-release --dir .cache/unsigned-release-37998764751
python tools/prepare_android_release.py .cache/unsigned-release-37998764751/app/build/outputs/apk/release/app-release-unsigned.apk --output-dir .cache/signed-candidate-37998764751 --source-commit ee8d7c12e4b1c9073b967d972eba56a773b128c6 --ci-run-id 37998764751 --version-name 0.0.4-beta --version-code 2 --client-id 8883240190 --app-id 1853479971
```

Для следующей сборки брать run ID и полный commit SHA из её GitHub Actions, ожидаемую версию — из согласованных метаданных, Client ID и отдельный native App URL ID — из публичной конфигурации BotFather. Номер версии этот инструмент не меняет. Существующий output-каталог не перезаписывает.

## Проверки кандидата

- Правильные package/versionCode/versionName/minSdk/targetSdk; debug APK отклоняется.
- Точный Login host в manifest соответствует зарегистрированному native App URL; OIDC Client ID фиксируется отдельно.
- ZIP/CRC; обе TDLib arm64-v8a/x86_64 и лицензии; ELF архитектура и 16 KB LOAD alignment.
- Выравнивание `zipalign -P 16` до подписи, затем `apksigner` v2/v3.
- После подписи: один постоянный сертификат, ZIP/CRC, повторная проверка выравнивания, неизменные manifest-метаданные и native SHA-256.
- SHA-256/размер итогового APK, локальный файл `.sha256` и `candidate.json`.

`candidate.json` содержит `status: candidate`, `published: false`, `installed_on_device: false`. Он не является JSON для Android-updater. Публичный Client ID ещё не доказывает готовность входа: при ненулевом значении записывается `requires_live_verification`.

До публикации нужны настоящий вход/отмена/возврат, проверка TDLib/прав, повышенный versionCode и реальные release notes. Метаданные updater формируются из именно опубликованного подписанного APK. Новый постоянный сертификат не совместим со старыми debug-подписями; автоматического удаления приложения нет.

## Автоматические проверки инструмента

```sh
python -m unittest discover -s tools -p 'test_prepare_android_release.py' -v
```

Используются синтетические APK/ELF, mock subprocess и временные тестовые файлы. Настоящие ключи и Telegram-сессии тесты не читают.

Официальные рекомендации: [apksigner — подпись и проверка](https://developer.android.com/tools/apksigner), [zipalign — выравнивание до подписи и проверка 16 KB](https://developer.android.com/tools/zipalign), [Android с размером страниц 16 KB](https://developer.android.com/guide/practices/page-sizes). Проверено 4 октября 2026 года.
