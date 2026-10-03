package chat.hunmeng.console

/** Both translations stay in memory so changing language also changes past events. */
data class ConsoleText(val ru: String, val en: String) {
    fun text(language: UiLanguage): String = if (language == UiLanguage.RU) ru else en
}

enum class ConsoleEventType(val label: ConsoleText) {
    CONNECTION(ConsoleText("подключение", "connection")),
    COMMANDS(ConsoleText("команды", "commands")),
    RECEIVED_COMMAND(ConsoleText("команда", "command")),
    POLLING(ConsoleText("обновления", "polling")),
    WEBHOOK(ConsoleText("webhook", "webhook")),
    UPDATE(ConsoleText("сообщение", "message")),
    EDIT(ConsoleText("редактирование", "edit")),
    CHANNEL(ConsoleText("канал", "channel")),
    REPLY(ConsoleText("ответ", "reply")),
    ECHO(ConsoleText("эхо", "echo")),
    SEND(ConsoleText("отправка", "send")),
    ERROR(ConsoleText("ошибка", "error")),
}

data class ConsoleEventRecord(val time: String, val type: ConsoleEventType, val detail: ConsoleText, val isError: Boolean = type == ConsoleEventType.ERROR)

enum class ConsoleTab(val label: ConsoleText) {
    BOT(ConsoleText("Бот", "Bot")), MESSAGE(ConsoleText("Сообщение", "Message")), EVENTS(ConsoleText("События", "Events")),
}

enum class EventFilter(val label: ConsoleText) {
    ALL(ConsoleText("Все", "All")), ERRORS(ConsoleText("Ошибки", "Errors")), COMMANDS(ConsoleText("Команды", "Commands")),
}

fun eventDisplayText(event: ConsoleEventRecord, language: UiLanguage): String =
    "${event.time}  [${event.type.label.text(language)}] ${redactEventText(event.detail.text(language))}"

fun visibleEvents(events: List<ConsoleEventRecord>, query: String, filter: EventFilter, language: UiLanguage): List<ConsoleEventRecord> = events.filter {
    (when (filter) {
        EventFilter.ALL -> true
        EventFilter.ERRORS -> it.isError
        EventFilter.COMMANDS -> it.type == ConsoleEventType.RECEIVED_COMMAND
    }) && eventDisplayText(it, language).contains(query.trim(), ignoreCase = true)
}

/** Commands accepted by this console, without arguments or sender identifiers. */
fun receivedCommand(update: TelegramUpdate, botUsername: String?): CommandResult? =
    update.message?.takeUnless { it.fromIsBot }?.text?.let { parseCommand(it, botUsername) }

/** The exact recipient and text the user saw in the confirmation dialog. */
data class SendPreview(val chat: ChatPreview, val text: String)

const val DISPLAY_VERSION = "v0.0.4beta"

val updateSourceUnavailable = ConsoleText(
    "Проверка обновлений пока не настроена. Источник новой версии не задан.",
    "Update checking is not configured yet. No new version source is set.",
)

fun defaultWelcome(language: UiLanguage): String = ConsoleText(
    "Привет! Напиши /help, чтобы увидеть команды.",
    "Hi! Send /help to see the available commands.",
).text(language)

fun validateRecipient(value: String): ConsoleText? {
    val destination = value.trim()
    return when {
        destination.isEmpty() -> ConsoleText("Укажите ID чата или @username", "Enter a chat ID or @username")
        destination.startsWith("@") && Regex("@[A-Za-z][A-Za-z0-9_]{4,31}").matches(destination) -> null
        Regex("-?[0-9]+").matches(destination) && destination.toLongOrNull()?.let { it != 0L } == true -> null
        else -> ConsoleText(
            "Получатель: числовой ID чата или @username (5–32 латинских символа, цифры и подчёркивание).",
            "Recipient: a numeric chat ID or @username (5–32 Latin letters, digits and underscores).",
        )
    }
}

fun localizedTextValidation(reason: String): ConsoleText = if (reason.startsWith("Message cannot")) {
    ConsoleText("Сообщение не может быть пустым", "Message cannot be empty")
} else {
    ConsoleText("Сообщение длиннее 4096 символов", "Message is longer than 4096 characters")
}

fun consoleError(error: Throwable): ConsoleText = when (error) {
    is TelegramApiException -> when (error.errorCode) {
        401 -> ConsoleText("Токен отклонён Telegram", "Telegram rejected the token")
        403 -> ConsoleText("Telegram запретил действие: проверьте права бота", "Telegram denied the action: check bot permissions")
        409 -> ConsoleText("Конфликт получения обновлений: остановите другую сессию", "Polling conflict: stop the other session")
        429 -> ConsoleText("Telegram ограничил запросы; попробуйте позже", "Telegram rate limited the request; try later")
        else -> ConsoleText("Ошибка Telegram (код ${error.errorCode})", "Telegram error (code ${error.errorCode})")
    }
    is TelegramNetworkException -> ConsoleText("Сетевая ошибка; токен не считается неверным", "Network error; the token is not considered invalid")
    else -> ConsoleText("Непредвиденная ошибка", "Unexpected error")
}

fun statusText(status: String): ConsoleText = when (status) {
    "connecting" -> ConsoleText("Подключение…", "Connecting…")
    "checking" -> ConsoleText("Проверка подключения…", "Checking connection…")
    "disconnecting" -> ConsoleText("Отключение…", "Disconnecting…")
    "starting" -> ConsoleText("Запуск получения обновлений…", "Starting polling…")
    "ready", "stopped" -> ConsoleText("Готов", "Ready")
    "connected" -> ConsoleText("Подключено", "Connected")
    "retrying" -> ConsoleText("Повтор запроса…", "Retrying…")
    "offline" -> ConsoleText("Нет сети", "Offline")
    "conflict" -> ConsoleText("Конфликт с другой сессией", "Conflict with another session")
    "webhook" -> ConsoleText("Webhook активен", "Webhook active")
    "background" -> ConsoleText("Пауза в фоне", "Paused in background")
    "authentication_failed" -> ConsoleText("Telegram отклонил токен", "Telegram rejected the token")
    "permission_denied" -> ConsoleText("Telegram запретил действие: проверьте права бота", "Telegram denied the action: check bot permissions")
    "error" -> ConsoleText("Ошибка", "Error")
    else -> ConsoleText("Отключено", "Disconnected")
}

fun commandSyncText(status: String): ConsoleText = when (status) {
    "syncing" -> ConsoleText("Меню команд: синхронизация…", "Command menu: synchronizing…")
    "synced" -> ConsoleText("Меню команд синхронизировано", "Command menu synchronized")
    "failed" -> ConsoleText("Меню команд не синхронизировано. Можно повторить.", "Command menu is not synchronized. You can retry.")
    else -> ConsoleText("Меню команд ещё не синхронизировано", "Command menu is not synchronized yet")
}

/** API URLs and bot tokens must never end up in the event panel. */
fun redactEventText(text: String): String = redactTelegramSecrets(text)
    .replace(Regex("https?://api\\.telegram\\.org/bot[^\\s]+", RegexOption.IGNORE_CASE), "[redacted]")
    .replace(Regex("[0-9]{6,12}:[A-Za-z0-9_-]{20,}"), "[redacted]")
