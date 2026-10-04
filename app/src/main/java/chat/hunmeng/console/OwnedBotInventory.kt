package chat.hunmeng.console

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant

enum class InventoryPhase { UNCONNECTED, LOADING, READY, EMPTY, NO_ACCESS, ERROR, STALE }
data class OwnedBot(val bot: BotUser, val verifiedOwnerId: Long, val checkedAt: Instant, val source: String = "tdlib.getOwnedBots")
data class BotChat(val chat: ChatPreview, val botRole: String, val canPost: Boolean?, val canEdit: Boolean?, val canDelete: Boolean?)
data class AccessibleBotChats(val items: List<BotChat>, val checkedAt: Instant, val inaccessibleCount: Int, val scanComplete: Boolean)
data class OwnedBotInventoryState(val phase: InventoryPhase = InventoryPhase.UNCONNECTED, val bots: List<OwnedBot> = emptyList(), val checkedAt: Instant? = null)

internal interface OwnedInventorySource {
    suspend fun ownedBots(): List<OwnedBot>
    suspend fun availableChats(bot: OwnedBot): AccessibleBotChats
}
internal class TdLibInventorySource(private val client: TdLibClient, private val owner: VerifiedAccount, private val clock: () -> Instant = Instant::now) : OwnedInventorySource {
    private suspend fun verifyIdentity() {
        val me = client.request("getMe")
        require(me.optString("@type") == "user" && me.optLong("id") == owner.telegramId) { "Telegram account mismatch" }
    }
    override suspend fun ownedBots(): List<OwnedBot> {
        verifyIdentity()
        val result = client.request("getOwnedBots")
        require(result.optString("@type") == "users")
        val ids = result.getJSONArray("user_ids")
        require(ids.length() <= 10_000)
        val checkedAt = clock()
        val bots = mutableListOf<OwnedBot>()
        for (index in 0 until ids.length()) {
            val id = ids.getLong(index)
            val user = client.request("getUser", JSONObject().put("user_id", id))
            require(user.optString("@type") == "user" && user.optLong("id") == id && user.optJSONObject("type")?.optString("@type") == "userTypeBot")
            val names = user.optJSONObject("usernames")?.optJSONArray("active_usernames")
            val username = names?.optString(0)?.takeIf { it.isNotBlank() }
            bots.add(OwnedBot(BotUser(id, user.getString("first_name"), username), owner.telegramId, checkedAt))
        }
        verifyIdentity()
        return bots.distinctBy { it.bot.id }
    }
    override suspend fun availableChats(bot: OwnedBot): AccessibleBotChats {
        require(bot.verifiedOwnerId == owner.telegramId)
        // A caller-supplied card or ID cannot grant access to another account's bot.
        require(ownedBots().any { it.bot.id == bot.bot.id })
        val candidates = linkedSetOf<Long>()
        var complete = true
        for (list in listOf("chatListMain", "chatListArchive")) {
            var loadedAll = false
            for (page in 0 until 100) {
                try { client.request("loadChats", JSONObject().put("chat_list", JSONObject().put("@type", list)).put("limit", 100)) }
                catch (error: TdLibException) { if (error.code == 404) { loadedAll = true; break } else throw error }
            }
            complete = complete && loadedAll
            val chats = client.request("getChats", JSONObject().put("chat_list", JSONObject().put("@type", list)).put("limit", 10_000)).getJSONArray("chat_ids")
            for (index in 0 until chats.length()) candidates.add(chats.getLong(index))
        }
        val result = mutableListOf<BotChat>()
        var noAccess = 0
        for (chatId in candidates) {
            delay(100)
            val chat = try { client.request("getChat", JSONObject().put("chat_id", chatId)) }
            catch (error: TdLibException) { if (error.code == 429 || error.retryAfter != null) throw error; noAccess++; continue }
            val type = chat.optJSONObject("type") ?: continue
            val channel = type.optString("@type") == "chatTypeSupergroup" && type.optBoolean("is_channel")
            if (type.optString("@type") !in setOf("chatTypeBasicGroup", "chatTypeSupergroup")) continue
            val member = try {
                client.request("getChatMember", JSONObject().put("chat_id", chatId).put("member_id", JSONObject().put("@type", "messageSenderUser").put("user_id", bot.bot.id)))
            } catch (error: TdLibException) {
                if (error.code == 429 || error.retryAfter != null) throw error
                noAccess++; continue
            }
            val status = member.optJSONObject("status")
            if (status == null) { noAccess++; continue }
            val role = status.optString("@type")
            if (role in setOf("chatMemberStatusLeft", "chatMemberStatusBanned")) continue
            if (role == "chatMemberStatusRestricted" && status.opt("is_member") == false) continue
            if (role !in setOf("chatMemberStatusCreator", "chatMemberStatusAdministrator", "chatMemberStatusMember", "chatMemberStatusRestricted")) { noAccess++; continue }
            val rights = status.optJSONObject("rights")
            fun flag(name: String): Boolean? = if (role == "chatMemberStatusCreator") true else rights?.opt(name) as? Boolean
            result.add(BotChat(ChatPreview(chatId, chat.getString("title"), if (channel) "channel" else if (type.optString("@type") == "chatTypeBasicGroup") "group" else "supergroup", null), role, flag("can_post_messages"), flag("can_edit_messages"), flag("can_delete_messages")))
            delay(50)
        }
        verifyIdentity()
        return AccessibleBotChats(result, clock(), noAccess, complete)
    }
}
