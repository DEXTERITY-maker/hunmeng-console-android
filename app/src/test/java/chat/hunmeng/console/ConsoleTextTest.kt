package chat.hunmeng.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConsoleTextTest {
    @Test fun recipientRejectsUrlsZeroOverflowAndMalformedNames() {
        listOf("", "0", "https://t.me/example", "@abc", "@username/path", "@username?send=yes", "name", "9999999999999999999999999", "@" + "a".repeat(33), "@русский").forEach {
            assertNotNull("Must reject recipient $it", validateRecipient(it))
        }
        listOf("123456789", "-1001234567890", " @username_123 ", "@" + "a".repeat(32)).forEach {
            assertNull("Must accept recipient $it", validateRecipient(it))
        }
    }

    @Test fun retainedEventChangesLanguageWithoutRewritingItsHistory() {
        val event = ConsoleEventRecord("12:00:00", ConsoleEventType.SEND, ConsoleText("Сообщение отправлено", "Message sent"))
        assertEquals("отправка", event.type.label.text(UiLanguage.RU))
        assertEquals("send", event.type.label.text(UiLanguage.EN))
        assertEquals("Сообщение отправлено", event.detail.text(UiLanguage.RU))
        assertEquals("Message sent", event.detail.text(UiLanguage.EN))
        assertEquals("12:00:00", event.time)
    }

    @Test fun requestErrorsNeverExposeRawApiDescriptionOrToken() {
        val fakeCredential = "123456" + "789:" + "x".repeat(32)
        val raw = "failure https://api.telegram.org/bot$fakeCredential/getUpdates $fakeCredential"
        assertFalse(redactEventText(raw).contains(fakeCredential))
        assertFalse(consoleError(TelegramApiException(401, raw)).text(UiLanguage.RU).contains(fakeCredential))
        assertTrue(consoleError(TelegramNetworkException(IOException(raw))).text(UiLanguage.EN).contains("Network"))
        assertFalse(consoleError(TelegramNetworkException(IOException(raw))).text(UiLanguage.EN).contains("rejected"))
    }

    @Test fun allPermanentPollingStatesHaveBothTranslations() {
        for (status in listOf("authentication_failed", "permission_denied", "conflict", "error", "starting", "stopped", "background")) {
            assertFalse(statusText(status).text(UiLanguage.RU).isBlank())
            assertFalse(statusText(status).text(UiLanguage.EN).isBlank())
            assertFalse(statusText(status).text(UiLanguage.EN) == "Disconnected")
        }
    }

    @Test fun welcomeAndUpdateNoticeMatchSelectedLanguage() {
        assertEquals("Привет! Напиши /help, чтобы увидеть команды.", defaultWelcome(UiLanguage.RU))
        assertTrue(defaultWelcome(UiLanguage.EN).startsWith("Hi!"))
        assertTrue(androidReleaseSourceExplanation.text(UiLanguage.RU).startsWith("Проверяем Android-выпуски"))
        assertTrue(androidReleaseSourceExplanation.text(UiLanguage.EN).contains("website version does not change the installed app"))
    }
}
