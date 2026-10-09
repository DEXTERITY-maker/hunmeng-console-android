package chat.hunmeng.console

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionToolsTest {
    @Test fun templateValidationPreservesUnicodeAndRejectsCredentials() {
        assertTrue(validTemplate("Новость", "🎬 Текст\nВторой абзац"))
        assertFalse(validTemplate("", "text"))
        assertFalse(validTemplate("Title", ""))
        assertFalse(validTemplate("Title", "a".repeat(4097)))
        // Synthetic fixtures only, never a working Telegram token.
        assertFalse(validTemplate("Title", "123456789:FAKE_TEST_CREDENTIAL_VALUE_123456789"))
        assertFalse(validTemplate("Title", "Bearer FAKE_TEST_SESSION_VALUE"))
        assertFalse(validTemplate("Title", "eyJtest.test_payload.test_signature"))
    }
    @Test fun channelRequiresExplicitPublicationPermission() {
        val chat = ChatPreview(-100L, "Channel", "channel", null)
        assertTrue(canSendToChat(chat, JSONObject("""{"status":"creator"}""")))
        assertTrue(canSendToChat(chat, JSONObject("""{"status":"administrator","can_post_messages":true}""")))
        assertFalse(canSendToChat(chat, JSONObject("""{"status":"administrator"}""")))
        assertFalse(canSendToChat(chat, JSONObject("""{"status":"member"}""")))
    }
    @Test fun groupRejectsUnknownAndRestrictedPermissions() {
        val unknown = ChatPreview(-5L, "Group", "supergroup", null)
        val writable = unknown.copy(defaultCanSendMessages = true)
        assertFalse(canSendToChat(unknown, JSONObject("""{"status":"member"}""")))
        assertTrue(canSendToChat(writable, JSONObject("""{"status":"member"}""")))
        assertFalse(canSendToChat(writable, JSONObject("""{"status":"restricted","is_member":true,"can_send_messages":false}""")))
        assertTrue(canSendToChat(writable, JSONObject("""{"status":"restricted","is_member":true,"can_send_messages":true}""")))
        assertFalse(canSendToChat(writable, JSONObject("""{"status":"left"}""")))
    }
}
