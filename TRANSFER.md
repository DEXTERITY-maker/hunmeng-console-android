# Перенос проекта Hunmeng Console

Проект расположен в `/storage/emulated/0/Hunmeng Console`.

- Время переноса (UTC): 20261003T203540Z.
- Перенесены исходники, ресурсы, оригинальная иконка, генератор иконок, Git-история, Gradle Wrapper, отчёты, APK и служебные файлы.
- Перед удалением старого каталога сверены SHA-256 всех 295 файлов, включая Git-метаданные и APK.
- Полная резервная копия: `/data/data/com.termux/files/home/backups/hunmeng-console-full-before-move-20261003T203540Z.tar.gz`.
- Файл сверки: `/data/data/com.termux/files/home/backups/hunmeng-console-move-verification-20261003T203540Z.json`.
- Старый путь `~/hunmeng-console-android` является символической ссылкой на новую папку.
- Контрольная сумма перенесённого APK v0.0.3beta: `d156869b519b19dae6d2e953849f55e4119fc451e7ee5ec3a7c633e8e7fd50b3`.

## Работа в новой папке

Общая память Android использует ограничения прав и общего владельца файлов. Скрипты вызываются через `sh`; Git запускается с параметром `-c safe.directory="/storage/emulated/0/Hunmeng Console"`. Глобальные настройки доверия Git не менялись. Локально установлен `core.fileMode=false`; исходные права в Git-истории и резервной копии сохранены.

Сборка Android выполняется через существующий workflow GitHub Actions. На момент переноса иконка и метаданные v0.0.4beta были подготовлены; новая сборка ещё не выполнялась. Текущий опубликованный выпуск описан в BUILD-RESULT.md.
