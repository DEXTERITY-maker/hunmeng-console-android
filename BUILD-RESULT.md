# Результат сборки Hunmeng Console v0.0.4beta — синхронизация функций

Дата проверки: 04.10.2026 00:46 МСК.

## Проверка разрабатываемой v0.0.5beta

Ниже сохранён отчёт последнего публичного v0.0.4beta. В ветке разработки [CI 37171249282](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37171249282), source `8fe2302`, подтвердил 93 unit-теста без ошибок/пропусков, lint 0 ошибок / 33 предупреждения, обе библиотеки TDLib в APK и успешные UI/Keystore/JNI проверки на Android 15 x86_64 (обычный шрифт и 1.6).

Это исторический debug artifact с метаданными 0.0.4-beta / код 2, не выпуск v0.0.5beta. Настоящий Telegram Login и установка на телефоне ещё требуют проверки. [Текущий отчёт](reports/v0.0.5-implementation.md).

APK из финального CI: 64 262 261 байт; SHA-256 `f043f69b39f06cf79a6fdbe3d07110257c6e7d9d3efeaef25391236fe6bd7a61`. ZIP/CRC, подпись v2 и AAPT-метаданные проверены локально. Лицензии и обе библиотеки TDLib в пакете совпадают с проверенными исходными artifacts. Полные сведения — в текущем отчёте выше.

Следующий CI 37172566676 также подтвердил 93 Android / 20 серверных тестов, debug/release lint без ошибок и unsigned release APK. Локально получен первый non-debug APK с постоянной подписью и Client ID `0`: 60 189 736 байт, SHA-256 `93e3ad023f55ddcf5fd7e3e07650069b64c20491b6e19639eff51fe8018c4fc1`. Это сохранённый исторический кандидат.

4 октября 2026 года, 06:51 МСК: [CI 37174913542](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37174913542), source `bd8719f3d5e362130ff271c72ed09caa5b4adbba`, успешно собрал APK с публичным Client ID `8883240190`. 93 unit-теста / 0 failures / 0 ignored, 20 серверных и 10 packaging-тестов пройдены; debug/release lint каждый: 0 ошибок / 33 предупреждения. Все пять jobs успешны. UI job отдельно выполнила три instrumentation-теста дважды на Android 15 x86_64, с обычным шрифтом и 1.6, но её APK использовал Client ID `0`; это не проверка настоящего Login.

Новый локально подписанный APK: `.cache/signed-candidate-37174913542/Hunmeng-Console-0.0.4-beta-candidate.apk`, 60 189 736 байт, SHA-256 `03577e926291af9c9817a54b8a353540406414e2294f654b18ebb4f63f059825`. Подтверждены постоянный сертификат, apksigner v2/v3, package/version, точный manifest host `app8883240190-login.tg.dev`, ZIP/ELF alignment 16 KB и неизменные native SHA-256. [Отчёт кандидата](reports/v0.0.5-release-candidate.md). APK не опубликован и не установлен.

Сервер применил runtime revision 3: config HTTP 200 / true, begin HTTP 200 с PKCE S256 и только openid/profile, cancel HTTP 200 / true. Telegram Android assetlinks HTTP 404; регистрация приложения и реальный callback не подтверждены. Правильность Client Secret ещё не проверена настоящим обменом кода. Авторизованных ADB-устройств нет.

### Исправленный App URL — актуальный кандидат

Native App URL ID `1853479971` отличается от OIDC Client ID `8883240190`. Actual assetlinks HTTP 200 подтверждает package/постоянный сертификат/relation. Прежний 404 относился к неверно вычисленному домену; кандидат 37174913542 использовать для Login нельзя.

[CI 37176423096](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37176423096), source `6ae18a5ebf8ae018db02fa75f121a46bff6ea555`: все пять jobs успешны; 94 Android / 22 server / 10 packaging tests, debug/release lint каждый 0 ошибок / 33 предупреждения. UI job: три теста дважды, Client ID `0`.

Новый подписанный APK: 60 189 736 байт; SHA-256 `f1adfa260fcd1a5f3787c5bb4d4c724c1c959a3b2ac87d7914c045f2ec59bb71`. Проверены native host, постоянная v2/v3 подпись, целостность/16 KB alignment. Копия: `/storage/emulated/0/Download/Hunmeng-Console-Login-candidate-37176423096.apk`; системному установщику передана успешно, установка не подтверждена.

Backend version 10 / runtime revision 4 опубликован, source `b3a67da2306ac2b3f200c9b69aaa07a1e58776e5`, deployment succeeded. Config/begin/cancel HTTP 200 с правильным redirect, PKCE S256 и openid/profile; actual App Links HTTP 200. Полный Login/TDLib не проверен. [Детали кандидата](reports/v0.0.5-release-candidate.md).

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

## Актуальная проверка интерфейса — 10 октября 2026

[CI 37998764751](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37998764751), source `ee8d7c12e4b1c9073b967d972eba56a773b128c6`: все пять jobs завершились success. 94 Android unit-теста / 0 failures / 0 ignored (11.450 s), 22 серверных теста / 0 failures, 10 packaging-тестов. Debug/release lint каждый: 0 ошибок / 31 предупреждение. Шесть instrumentation-тестов прошли дважды на Android 15 x86_64: font 1.0 и 1.6; получены 24 PNG.

Вход, карточки ботов/чатов, обзор, форма сообщения и диалог обновления приведены к композиции макетов. Исправлены реальные перекрытия переключателя языка в landscape и нижней карточки standalone-входа системными панелями. Проверки измеряют границы элементов относительно insets, нажимают язык, открывают настоящую IME, поворачивают Activity и проверяют сохранение синтетического черновика. Консоль и профиль используют один настоящий AndroidUpdateController; устаревший placeholder обновления удалён.

[Снимки интерфейса](reports/ui-v005-ee8d7c1/README.md), [визуальная сверка](reports/v0.0.5-visual-alignment.md), [полный аудит требований](reports/v0.0.5-requirements-audit.md), [проверка телефона](reports/v0.0.5-device-verification.md).

Подписанный non-debug кандидат: **60,226,992** байт; SHA-256 `3e7922bdf651d55369fd4395e142b293720f4d4c0c1ad64f6e489094376155fc`. Package `chat.hunmeng.console`, versionName `0.0.4-beta`, versionCode `2`; версия не повышалась. Проверены постоянная v2/v3 подпись, ZIP/CRC, обе TDLib ABI, лицензии, ELF/ZIP 16 KB и точные App Link metadata. Все 11 PNG бренда, обычного и адаптивного launcher совпадают по пикселям с ресурсами; хеш исходной иконки владельца сохранён.

Файл на телефоне: `/storage/emulated/0/Download/Hunmeng-Console-UI-candidate-37998764751.apk`; копия и соседний `.sha256` проверены. Команда `termux-open` завершилась успешно и передала APK установщику; установка не подтверждена. ADB работает с очищенными LD_LIBRARY_PATH/LD_PRELOAD, авторизованных устройств нет. Backend config HTTP 200 / configured=true, actual assetlinks HTTP 200 / правильные package, сертификат и relation.

Это кандидат, не публичный v0.0.5beta. UI job использует Client ID `0`, компоненты аккаунта и окно обновления — синтетические fixtures; настоящий Login/TDLib, реальное восстановление/выход, права чатов и совместимость установки ещё требуют проверки телефона. [Публичный Android-выпуск](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/v0.0.4-beta-sync) и `updates/android.json` не изменялись.
