package chat.hunmeng.console

import java.util.Locale

private val supportedCommands = setOf("start", "help", "ping", "id", "version")
private val commandPattern = Regex("^/([A-Za-z0-9_]{1,32})(?:@([A-Za-z0-9_]+))?(?:\\s+([\\s\\S]*))?$")
private val commandPrefixPattern = Regex("^/[A-Za-z0-9_]+(?:@[A-Za-z0-9_]+)?(?:\\s|$)")

fun parseCommand(text: String, botUsername: String?): CommandResult? {
    val match = commandPattern.matchEntire(text.trim()) ?: return null
    val command = match.groupValues[1].lowercase(Locale.ROOT)
    if (command !in supportedCommands) return null
    val target = match.groupValues[2].takeIf { it.isNotBlank() }
    val username = botUsername?.trim()?.removePrefix("@")
    if (target != null && !target.equals(username, ignoreCase = true)) return null
    return CommandResult(command, match.groupValues[3].takeIf { it.isNotBlank() })
}

// Unknown commands and commands for another bot must not fall through to echo.
fun isBotCommand(text: String): Boolean = commandPrefixPattern.containsMatchIn(text.trimStart())

fun shouldEchoMessage(message: TelegramMessage): Boolean {
    val text = message.text ?: return false
    return message.chatType == "private" && !message.fromIsBot &&
        validateTelegramText(text) == null && !isBotCommand(text)
}

fun countTelegramCharacters(text: String): Int = text.codePointCount(0, text.length)

fun validateTelegramText(text: String): String? = when {
    text.isBlank() -> "Message cannot be empty"
    countTelegramCharacters(text) > 4096 -> "Message is longer than 4096 characters"
    else -> null
}
