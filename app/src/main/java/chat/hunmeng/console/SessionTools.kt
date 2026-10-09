package chat.hunmeng.console

data class SessionCounters(val received: Long = 0, val replied: Long = 0, val errors: Long = 0, val sent: Long = 0)
data class MessageTemplate(val id: String, val title: String, val text: String)
data class FavoriteRecipient(val botId: Long, val chat: ChatPreview)

internal fun validTemplate(title: String, body: String): Boolean =
    title.trim().length in 1..60 && validateTelegramText(body) == null &&
        !containsCredential(title) && !containsCredential(body)

// Explicit save cannot accidentally persist a pasted credential or a bearer token.
internal fun containsCredential(text: String): Boolean =
    Regex("[0-9]{5,16}:[A-Za-z0-9_-]{20,}").containsMatchIn(text) ||
        Regex("(?i)(?:bearer\\s+|api_hash\\s*[:=]|client_secret\\s*[:=]|sk-(?:proj-)?)[A-Za-z0-9_-]{8,}").containsMatchIn(text) ||
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").containsMatchIn(text)
