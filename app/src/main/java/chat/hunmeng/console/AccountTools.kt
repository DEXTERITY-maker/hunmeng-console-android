package chat.hunmeng.console

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class AccountTools(val accountId: Long, val templates: List<MessageTemplate>, val favorites: List<FavoriteRecipient>) {
    override fun toString() = "AccountTools([private])"
    fun encode(): ByteArray {
        validate()
        val templatesJson = JSONArray(); templates.forEach { templatesJson.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text)) }
        val favoritesJson = JSONArray(); favorites.forEach { favoritesJson.put(JSONObject().put("bot_id", it.botId).put("chat_id", it.chat.id).put("title", it.chat.title).put("type", it.chat.type).put("username", it.chat.username ?: JSONObject.NULL)) }
        return JSONObject().put("version", 1).put("account_id", accountId).put("templates", templatesJson).put("favorites", favoritesJson).toString().toByteArray()
    }
    fun validate() {
        require(accountId > 0 && templates.size <= 20 && favorites.size <= 20)
        require(templates.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it.id) && validTemplate(it.title, it.text) } && templates.map { it.id }.distinct().size == templates.size)
        require(favorites.all { it.botId > 0 && it.chat.id != 0L && it.chat.title.length <= 512 && !containsCredential(it.chat.title) && it.chat.type in setOf("private", "group", "supergroup", "channel") && (it.chat.username == null || Regex("[A-Za-z0-9_]{1,64}").matches(it.chat.username)) })
        require(favorites.map { it.botId to it.chat.id }.distinct().size == favorites.size)
    }
    companion object {
        fun decode(bytes: ByteArray, verifiedAccountId: Long): AccountTools {
            require(bytes.size in 1..524_288)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            require(json.getInt("version") == 1 && json.getLong("account_id") == verifiedAccountId)
            val saved = json.getJSONArray("templates"); val chats = json.getJSONArray("favorites")
            require(saved.length() <= 20 && chats.length() <= 20)
            return AccountTools(verifiedAccountId, (0 until saved.length()).map { saved.getJSONObject(it).let { MessageTemplate(it.getString("id"), it.getString("title"), it.getString("text")) } },
                (0 until chats.length()).map { chats.getJSONObject(it).let { FavoriteRecipient(it.getLong("bot_id"), ChatPreview(it.getLong("chat_id"), it.getString("title"), it.getString("type"), it.optNullableString("username"))) } }).also { it.validate() }
        }
    }
}

internal class AndroidAccountToolsStore(context: Context) {
    private val mutex = Mutex()
    private val vault = EncryptedSessionVault(context, VaultPurpose.ACCOUNT_TOOLS)
    suspend fun load(accountId: Long): AccountTools = mutex.withLock { withContext(Dispatchers.IO) {
        val bytes = vault.load() ?: return@withContext AccountTools(accountId, emptyList(), emptyList())
        try { AccountTools.decode(bytes, accountId) } finally { bytes.fill(0) }
    } }
    suspend fun save(tools: AccountTools, stillVerified: () -> Boolean) = mutex.withLock { withContext(Dispatchers.IO) {
        if (!stillVerified()) return@withContext
        val bytes = tools.encode(); try { vault.save(bytes); if (!stillVerified()) vault.erase() } finally { bytes.fill(0) }
    } }
    suspend fun erase() = mutex.withLock { withContext(Dispatchers.IO) { vault.erase() } }
}
