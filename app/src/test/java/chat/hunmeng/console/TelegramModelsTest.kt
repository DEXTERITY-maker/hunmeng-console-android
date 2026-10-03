package chat.hunmeng.console

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class TelegramModelsTest {
    @Test fun parsesEditedChannelCaptionAndBotSender() {
        val update = JSONObject("""{"update_id":50,"edited_channel_post":{"message_id":9,"caption":"Updated caption","chat":{"id":-1001234567890,"type":"channel","title":"Test channel"},"from":{"id":42,"is_bot":true,"username":"test_bot"}}}""").toTelegramUpdate()
        assertNull(update.message)
        assertEquals(50L, update.updateId)
        assertEquals("Updated caption", update.editedChannelPost?.text)
        assertEquals(-1001234567890L, update.editedChannelPost?.chatId)
        assertEquals(true, update.editedChannelPost?.fromIsBot)
    }

    @Test fun parsesMembershipChangesAsSeparateUpdates() {
        val update = JSONObject("""{"update_id":51,"my_chat_member":{"chat":{"id":-42,"type":"supergroup"},"new_chat_member":{"status":"administrator"}}}""").toTelegramUpdate()
        assertEquals(TelegramMembership(-42, "supergroup", "administrator"), update.myChatMember)
        assertNull(update.message)
    }

    @Test fun networkErrorsDiscardUnsafeCausesAndRedactCredentialUrls() {
        val fakeCredential = "123456:" + "TEST_ONLY_".repeat(4)
        val raw = IOException("Cannot reach https://api.telegram.org/bot$fakeCredential/getMe: $fakeCredential")
        val failure = TelegramNetworkException(raw)
        assertFalse(failure.stackTraceToString().contains(fakeCredential))
        assertNull(failure.cause)
        assertEquals("Rejected [redacted]", redactTelegramSecrets("Rejected TEST_TOKEN", "TEST_TOKEN"))
    }
}
