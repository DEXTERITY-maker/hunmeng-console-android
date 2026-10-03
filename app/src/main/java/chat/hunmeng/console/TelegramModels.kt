package chat.hunmeng.console

import org.json.JSONArray
import org.json.JSONObject

enum class UiLanguage { RU, EN }

data class BotUser(val id: Long, val firstName: String, val username: String?)

data class ChatPreview(val id: Long, val title: String, val type: String, val username: String?)

data class EventRecord(val time: String, val type: String, val detail: String)

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage? = null,
    val editedMessage: TelegramMessage? = null,
    val channelPost: TelegramMessage? = null,
    val editedChannelPost: TelegramMessage? = null,
    val myChatMember: TelegramMembership? = null,
)

data class TelegramMembership(val chatId: Long, val chatType: String, val status: String)

data class TelegramMessage(
    val messageId: Long,
    val chatId: Long,
    val chatType: String,
    val chatTitle: String? = null,
    val text: String? = null,
    val fromId: Long? = null,
    val fromIsBot: Boolean = false,
    val fromUsername: String? = null,
)

data class CommandResult(val name: String, val argument: String? = null)

class TelegramApiException(
    val errorCode: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
) : Exception(redactTelegramSecrets(message))

// Do not retain the original exception: an OkHttp failure may contain a credential URL.
class TelegramNetworkException(cause: Throwable) : Exception(redactTelegramSecrets(cause.message ?: "Telegram transport failed"))

internal fun redactTelegramSecrets(text: String, exactToken: String? = null): String {
    val exactRedacted = if (exactToken.isNullOrBlank()) text else text.replace(exactToken, "[redacted]")
    return exactRedacted
        .replace(Regex("https?://api\\.telegram\\.org/bot[^/\\s]+", RegexOption.IGNORE_CASE), "https://api.telegram.org/bot[redacted]")
        .replace(Regex("[0-9]{5,16}:[A-Za-z0-9_-]{20,}"), "[redacted]")
        .take(512)
}

internal fun JSONObject.optNullableString(name: String): String? = if (has(name) && !isNull(name)) optString(name) else null

internal fun JSONObject.toTelegramUpdate(): TelegramUpdate {
    val updateId = optLong("update_id")
    return TelegramUpdate(
        updateId = updateId,
        message = optJSONObject("message")?.toTelegramMessage(),
        editedMessage = optJSONObject("edited_message")?.toTelegramMessage(),
        channelPost = optJSONObject("channel_post")?.toTelegramMessage(),
        editedChannelPost = optJSONObject("edited_channel_post")?.toTelegramMessage(),
        myChatMember = optJSONObject("my_chat_member")?.let { membership ->
            val chat = membership.optJSONObject("chat") ?: JSONObject()
            TelegramMembership(chat.optLong("id"), chat.optString("type"), membership.optJSONObject("new_chat_member")?.optString("status").orEmpty())
        },
    )
}

private fun JSONObject.toTelegramMessage(): TelegramMessage {
    val chat = optJSONObject("chat") ?: JSONObject()
    val from = optJSONObject("from")
    return TelegramMessage(
        messageId = optLong("message_id"),
        chatId = chat.optLong("id"),
        chatType = chat.optString("type"),
        chatTitle = chat.optNullableString("title") ?: chat.optNullableString("username"),
        text = optNullableString("text") ?: optNullableString("caption"),
        fromId = from?.optLong("id"),
        fromIsBot = from?.optBoolean("is_bot") ?: false,
        fromUsername = from?.optNullableString("username"),
    )
}

internal fun JSONArray.toTelegramUpdates(): List<TelegramUpdate> = buildList {
    for (index in 0 until length()) add(getJSONObject(index).toTelegramUpdate())
}
