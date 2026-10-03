package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramApiClient private constructor(
    token: String,
    private val endpoint: HttpUrl,
    private val http: OkHttpClient,
) : Closeable {
    private val lifecycleLock = Any()
    private var accessToken: String? = token
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    constructor(token: String) : this(
        token,
        "https://api.telegram.org/".toHttpUrl(),
        secureTransport(OkHttpClient()),
    )

    private suspend fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val networkCall = synchronized(lifecycleLock) {
            val credential = accessToken ?: throw IllegalStateException("Telegram client is closed")
            val form = FormBody.Builder().apply {
                params.forEach { (key, value) -> add(key, value) }
            }.build()
            // OkHttp can replay HTTP 503 with Retry-After: 0 even when connection
            // retries are disabled. A one-shot body also forbids these follow-ups.
            val body = object : RequestBody() {
                override fun contentType() = form.contentType()
                override fun contentLength() = form.contentLength()
                override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
                override fun isOneShot() = true
            }
            val request = Request.Builder()
                .url(endpoint.newBuilder().addPathSegment("bot$credential").addPathSegment(method).build())
                .post(body)
                .build()
            http.newCall(request).also { activeCalls.add(it) }
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { networkCall.cancel() }
            val callback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCalls.remove(call)
                    if (continuation.isActive) {
                        continuation.resumeWithException(safeNetworkError(e, call))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!continuation.isActive) return
                            val raw = response.body?.string().orEmpty()
                            if (raw.isBlank()) {
                                throw TelegramNetworkException(IOException("Empty Telegram response (${response.code})"))
                            }
                            val json = try {
                                JSONObject(raw)
                            } catch (_: Exception) {
                                // Do not attach the untrusted response body to an exception.
                                throw TelegramNetworkException(IOException("Invalid Telegram response (${response.code})"))
                            }
                            if (!json.optBoolean("ok") || !response.isSuccessful) {
                                throw TelegramApiException(
                                    errorCode = json.optInt("error_code", response.code),
                                    message = redactForCall(json.optString("description", "Telegram request failed"), call),
                                    retryAfterSeconds = json.optJSONObject("parameters")
                                        ?.optLong("retry_after")?.takeIf { it > 0 },
                                )
                            }
                            if (continuation.isActive) continuation.resume(json)
                        }
                    } catch (error: CancellationException) {
                        continuation.cancel(error)
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                if (error is TelegramApiException || error is TelegramNetworkException) error
                                else safeNetworkError(error, call),
                            )
                        }
                    } finally {
                        activeCalls.remove(call)
                    }
                }
            }
            try {
                networkCall.enqueue(callback)
            } catch (error: CancellationException) {
                activeCalls.remove(networkCall)
                continuation.cancel(error)
            } catch (error: Exception) {
                activeCalls.remove(networkCall)
                if (continuation.isActive) continuation.resumeWithException(safeNetworkError(error, networkCall))
            }
        }
    }

    private fun redactForCall(text: String, call: Call): String {
        val credential = call.request().url.pathSegments.dropLast(1).lastOrNull()?.removePrefix("bot")
        return redactTelegramSecrets(text, credential)
    }

    private fun safeNetworkError(error: Throwable, call: Call): TelegramNetworkException =
        TelegramNetworkException(IOException(redactForCall(error.message ?: "Telegram transport failed", call)))

    suspend fun getMe(): BotUser {
        val user = call("getMe").getJSONObject("result")
        return BotUser(user.getLong("id"), user.optString("first_name"), user.optNullableString("username"))
    }

    suspend fun getWebhookInfo(): String? = call("getWebhookInfo").getJSONObject("result").optNullableString("url")

    suspend fun deleteWebhook(): Boolean = call("deleteWebhook", mapOf("drop_pending_updates" to "false")).optBoolean("result")

    suspend fun setMyCommands(language: UiLanguage) {
        val commands = JSONArray().apply {
            put(JSONObject().put("command", "start").put("description", if (language == UiLanguage.RU) "Запустить бота" else "Start the bot"))
            put(JSONObject().put("command", "help").put("description", if (language == UiLanguage.RU) "Справка" else "Help"))
            put(JSONObject().put("command", "ping").put("description", if (language == UiLanguage.RU) "Проверить связь" else "Check connection"))
            put(JSONObject().put("command", "id").put("description", if (language == UiLanguage.RU) "Показать ID чата" else "Show chat ID"))
            put(JSONObject().put("command", "version").put("description", if (language == UiLanguage.RU) "Версия консоли" else "Console version"))
        }
        call("setMyCommands", mapOf("commands" to commands.toString()))
    }

    suspend fun getUpdates(offset: Long?, timeoutSeconds: Int = 50): List<TelegramUpdate> {
        require(timeoutSeconds in 0..50) { "Polling timeout must be between 0 and 50 seconds" }
        val params = mutableMapOf(
            "timeout" to timeoutSeconds.toString(),
            "allowed_updates" to JSONArray(listOf("message", "edited_message", "channel_post", "edited_channel_post", "my_chat_member")).toString(),
        )
        if (offset != null) params["offset"] = offset.toString()
        return call("getUpdates", params).getJSONArray("result").toTelegramUpdates()
    }

    suspend fun getChat(chatIdOrUsername: String): ChatPreview {
        val chat = call("getChat", mapOf("chat_id" to chatIdOrUsername)).getJSONObject("result")
        val fullName = listOfNotNull(chat.optNullableString("first_name"), chat.optNullableString("last_name"))
            .filter { it.isNotBlank() }.joinToString(" ")
        val title = chat.optNullableString("title")?.takeIf { it.isNotBlank() }
            ?: fullName.takeIf { it.isNotBlank() } ?: chatIdOrUsername
        return ChatPreview(chat.getLong("id"), title, chat.optString("type"), chat.optNullableString("username"))
    }

    suspend fun sendMessage(chatId: Long, text: String): Long {
        require(validateTelegramText(text) == null) { validateTelegramText(text) ?: "Invalid message" }
        // One API attempt only. A failed transport can still mean Telegram accepted the message.
        val result = call("sendMessage", mapOf("chat_id" to chatId.toString(), "text" to text)).getJSONObject("result")
        return result.getLong("message_id")
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (accessToken == null) return
            accessToken = null
            activeCalls.forEach { it.cancel() }
            activeCalls.clear()
        }
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    fun clear() = close()

    companion object {
        // The public client always uses Telegram HTTPS. Tests use a local mock server.
        internal fun forTesting(token: String, endpoint: HttpUrl, transport: OkHttpClient): TelegramApiClient =
            TelegramApiClient(token, endpoint, secureTransport(transport))

        private fun secureTransport(transport: OkHttpClient): OkHttpClient = transport.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .build()
    }
}
