package chat.hunmeng.console

import org.junit.Assert.*
import org.junit.Test

class EventToolsTest {
    private val command = ConsoleEventRecord("12:34:56", ConsoleEventType.RECEIVED_COMMAND, ConsoleText("Принята /ping", "Received /ping"))
    private val failedMenu = ConsoleEventRecord("12:35:00", ConsoleEventType.COMMANDS, ConsoleText("Ошибка меню", "Menu failure"), isError = true)
    private val failedSend = ConsoleEventRecord("12:36:00", ConsoleEventType.SEND, ConsoleText("Отправка отклонена", "Send rejected"), isError = true)
    private val events = listOf(command, failedMenu, failedSend)

    @Test fun searchAndFiltersCombineWithoutChangingTheLog() {
        assertEquals(events, visibleEvents(events, "", EventFilter.ALL, UiLanguage.RU))
        assertEquals(listOf(failedMenu, failedSend), visibleEvents(events, "", EventFilter.ERRORS, UiLanguage.RU))
        assertEquals(listOf(command), visibleEvents(events, "PING", EventFilter.COMMANDS, UiLanguage.RU))
        assertEquals(emptyList<ConsoleEventRecord>(), visibleEvents(events, "ping", EventFilter.ERRORS, UiLanguage.RU))
        assertEquals(listOf(command), visibleEvents(events, "12:34", EventFilter.ALL, UiLanguage.EN))
        assertEquals(3, events.size)
    }

    @Test fun switchingLanguageRecalculatesTextAndSearch() {
        assertEquals(1, visibleEvents(events, "Принята", EventFilter.ALL, UiLanguage.RU).size)
        assertEquals(0, visibleEvents(events, "Принята", EventFilter.ALL, UiLanguage.EN).size)
        assertEquals(1, visibleEvents(events, "RECEIVED", EventFilter.ALL, UiLanguage.EN).size)
        assertTrue(eventDisplayText(command, UiLanguage.EN).contains("Received /ping"))
    }

    @Test fun acceptedCommandsExcludeBotsOtherTargetsAndEditedMessages() {
        val message = TelegramMessage(1, 2, "private", text = "/ping@our_bot confidential argument")
        assertEquals("ping", receivedCommand(TelegramUpdate(1, message = message), "our_bot")?.name)
        assertNull(receivedCommand(TelegramUpdate(1, message = message.copy(text = "/ping@other_bot")), "our_bot"))
        assertNull(receivedCommand(TelegramUpdate(1, message = message.copy(fromIsBot = true)), "our_bot"))
        assertNull(receivedCommand(TelegramUpdate(1, editedMessage = message), "our_bot"))
        assertNull(receivedCommand(TelegramUpdate(1, channelPost = message), "our_bot"))
    }

    @Test fun copiedEventUsesSafeDisplayedFieldsOnly() {
        val token = "123456789:" + "x".repeat(32)
        val event = command.copy(detail = ConsoleText("https://api.telegram.org/bot$token/getMe $token", "Safe text"))
        val text = eventDisplayText(event, UiLanguage.RU)
        assertTrue(text.startsWith("12:34:56"))
        assertFalse(text.contains(token))
        assertFalse(text.contains("api.telegram.org"))
    }

    @Test fun clipboardAndIntentFailuresReturnFallbackWithoutExposingExceptions() {
        var copied: String? = null
        assertTrue(tryCopyText("Safe report") { copied = it })
        assertEquals("Safe report", copied)
        assertFalse(tryCopyText("Safe report") { throw SecurityException("Private exception") })
        val json = "{\"platform\":\"android\"}"
        assertNull(reportCopyFallback(json) { copied = it })
        assertEquals(json, copied)
        assertEquals(json, reportCopyFallback(json) { throw SecurityException("Unavailable") })
        var target: String? = null
        assertTrue(tryOpenLink(BOT_FATHER_URL) { target = it })
        assertEquals("https://t.me/BotFather", target)
        assertFalse(tryOpenLink(BOT_FATHER_URL) { throw IllegalStateException("No handler") })
    }
}
