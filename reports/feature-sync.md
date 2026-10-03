# Перенос функций веб-версии в Android v0.0.4beta

Реализовано по NEXT-UPDATE.md от 4 октября 2026 года. Версия, package и иконка сохранены.

## Изменения кода

| Функция | Реализация | Автоматические проверки |
| --- | --- | --- |
| Четыре этапа подключения | ConnectionDiagnostics.kt; ConsoleController.runDiagnostic | Успех, 401 и другие API-ошибки, DNS/TLS/таймаут, webhook warning, сбой webhook после getMe |
| Повтор и отмена | Общий job диагностики, generation сессии, canCheckConnection, закрытие HTTP-клиента | Параллельные попытки, отключение и новый токен, запоздавший ответ, повтор без ввода токена |
| Безопасный JSON | connectionReport: явный список полей, Instant для UTC/ISO 8601, BuildConfig для метаданных | Тест с токеном, username, ID, сообщениями, URL, журналом и исключением; разрешённые поля и null |
| Буфер обмена | ClipboardManager; reportCopyFallback; SelectionContainer в диалоге | Внедряемая операция копирования: успех, исключение и точное совпадение JSON для ручного копирования |
| События | Структурированные isError и RECEIVED_COMMAND; visibleEvents и eventDisplayText | Совместные поиск/фильтры, RU/EN, редактирования/каналы/боты/чужие команды, скрытие токенов, 150 записей |
| Вкладки и ответы | PrimaryTabRow; selectedTab, repliesExpanded и поиск в общем контроллере, ViewModel сохраняет его при пересоздании Activity | Сохранение полей и журнала, одного polling-цикла, пауза в фоне, ручной запуск после возврата |
| @BotFather | Постоянная HTTPS-ссылка и ACTION_VIEW, перехват отсутствия обработчика | Правильный URL, успех/ошибка операции открытия |

Состояние и задания вынесены из AndroidViewModel в ConsoleController. ViewModel отвечает за жизненный цикл приложения и сохранение только языка, приветствия и эхо. Переключение вкладок не выполняет сетевых запросов и не создаёт контроллер заново.

Ошибки соединения, webhook, отправки, меню и polling разделены. Ошибка отправки или меню не меняет результат авторизации. При сбое webhook после getMe клиент остаётся в памяти, но запуск polling запрещён до успешной проверки webhook. В polling ответ 401 означает отклонённый токен; 403 отображается как запрет действия и не меняет результат проверки токена.

## Документация

- Нативные вкладки используют PrimaryTabRow/Tab из [официального руководства Android](https://developer.android.com/develop/ui/compose/components/tabs).
- Работа ClipboardManager и ClipData описана в [руководстве копирования Android](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste).
- ACTION_VIEW и обработка отсутствия принимающего приложения: [Android Intents](https://developer.android.com/training/basics/intents/sending).
- getMe и getWebhookInfo используют прямой HTTPS по [официальному Telegram Bot API](https://core.telegram.org/bots/api#getme).

## Границы проверки

MockWebServer получает только вымышленные данные. Тесты платформенных действий вызывают внедряемые операции, а не реальный clipboard или Telegram на телефоне. Тесты контроллера проверяют состояние и сетевые задания, а не нажатия в Compose или физический поворот устройства.

Проверка Android-интерфейса, системного clipboard, открытия @BotFather и реального подключения на телефоне должна быть отдельно подтверждена после установки владельцем. Приложение автоматически не удаляется. Постоянный ключ подписи пока не настроен. Результаты конкретной CI-сборки, метаданные APK и совместимость подписи фиксируются в BUILD-RESULT.md после получения артефактов.

## Финальная сборка

[GitHub Actions 37155736851](https://github.com/DEXTERITY-maker/hunmeng-console-android/actions/runs/37155736851), коммит `00948ae08954e54d1dc474e0f1512a153cbbcf9e`: 54 теста пройдены, 0 пропущенных; lint — 0 ошибок и 25 предупреждений. [APK опубликован](https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/v0.0.4-beta-sync), а метаданные версии сохранены по плану. [Результаты и подпись](../BUILD-RESULT.md).
