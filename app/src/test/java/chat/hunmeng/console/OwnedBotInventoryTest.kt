package chat.hunmeng.console

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OwnedBotInventoryTest {
    private class Bridge : TdJsonBridge {
        lateinit var client: TdLibClient
        var actualOwner = 7L
        var role = "chatMemberStatusAdministrator"
        var unavailable = false
        override fun createClientId() = 1
        override fun receive(timeout: Double): String? = null
        override fun execute(request: String): String? = null
        override fun send(clientId: Int, request: String) {
            val input = JSONObject(request)
            val result = when (input.getString("@type")) {
                "getMe" -> JSONObject().put("@type", "user").put("id", actualOwner)
                "getOwnedBots" -> JSONObject("""{"@type":"users","user_ids":[41]}""")
                "getUser" -> JSONObject("""{"@type":"user","id":41,"first_name":"Fixture","type":{"@type":"userTypeBot"},"usernames":{"active_usernames":["fixture_bot"]}}""")
                "loadChats" -> JSONObject("""{"@type":"error","code":404,"message":"TEST_ALL_CHATS_LOADED"}""")
                "getChats" -> JSONObject("""{"@type":"chats","chat_ids":[-1001]}""")
                "getChat" -> JSONObject("""{"@type":"chat","id":-1001,"title":"Fixture channel","type":{"@type":"chatTypeSupergroup","is_channel":true}}""")
                "getChatMember" -> if (unavailable) JSONObject("""{"@type":"error","code":403,"message":"TEST_NO_ACCESS"}""")
                    else JSONObject().put("@type", "chatMember").put("status", JSONObject().put("@type", role).put("is_member", false).put("rights", JSONObject().put("can_post_messages", true)))
                else -> error("Unexpected mock request")
            }
            client.receive(result.put("@extra", input.getString("@extra")))
        }
    }
    private fun source(bridge: Bridge): TdLibInventorySource {
        bridge.client = TdLibClient(1, bridge) {}
        return TdLibInventorySource(bridge.client, VerifiedAccount(7L, "Fixture", null))
    }
    @Test fun ownedBotUsesStableOwnerAndOfficialSource() = runTest {
        val bots = source(Bridge()).ownedBots()
        assertEquals(1, bots.size); assertEquals(7L, bots[0].verifiedOwnerId)
        assertEquals(41L, bots[0].bot.id); assertEquals("tdlib.getOwnedBots", bots[0].source)
    }
    @Test fun foreignAccountAndForeignBotCannotAccessInventory() = runTest {
        val bridge = Bridge().apply { actualOwner = 8L }
        try { source(bridge).ownedBots(); fail("Expected identity rejection") } catch (_: IllegalArgumentException) { }
        val valid = source(Bridge())
        try { valid.availableChats(OwnedBot(BotUser(42L, "Foreign fixture", null), 7L, java.time.Instant.now())); fail("Expected ownership rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun accessibleChatsDeduplicateAndReportBotRoleAndCoverage() = runTest {
        val source = source(Bridge())
        val chats = source.availableChats(source.ownedBots().single())
        assertEquals(1, chats.items.size); assertEquals("channel", chats.items.single().chat.type)
        assertEquals("chatMemberStatusAdministrator", chats.items.single().botRole)
        assertEquals(true, chats.items.single().canPost)
        assertTrue(chats.scanComplete); assertEquals(0, chats.inaccessibleCount)
    }
    @Test fun permissionErrorIsUnknownNotAnAbsentChatAndRestrictedNonMemberIsExcluded() = runTest {
        val errorSource = source(Bridge().apply { unavailable = true })
        val unknown = errorSource.availableChats(errorSource.ownedBots().single())
        assertEquals(1, unknown.inaccessibleCount); assertTrue(unknown.items.isEmpty())
        val leftSource = source(Bridge().apply { role = "chatMemberStatusRestricted" })
        val left = leftSource.availableChats(leftSource.ownedBots().single())
        assertEquals(0, left.inaccessibleCount); assertTrue(left.items.isEmpty())
    }
}
