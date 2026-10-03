package chat.hunmeng.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    @Test fun acceptsCommandAddressedToThisBot() {
        assertEquals(CommandResult("ping"), parseCommand("/ping@Hunmeng_official_bot", "hunmeng_official_bot"))
    }

    @Test fun ignoresCommandAddressedToAnotherBot() {
        assertNull(parseCommand("/ping@other_bot", "hunmeng_official_bot"))
    }

    @Test fun ignoresAddressedCommandUntilThisBotUsernameIsKnown() {
        assertNull(parseCommand("/ping@other_bot", null))
    }

    @Test fun acceptsCommandCasingAndKeepsMultilineArguments() {
        assertEquals(CommandResult("start", "first\nsecond"), parseCommand(" /START@TEST_bot first\nsecond ", "@test_bot"))
    }

    @Test fun commandsForOthersAndUnknownCommandsDoNotFallThroughToEcho() {
        val message = TelegramMessage(1, 2, "private", text = "/ping@other_bot")
        assertTrue(isBotCommand(message.text!!))
        assertFalse(shouldEchoMessage(message))
        assertTrue(isBotCommand(" /unknown payload"))
        assertFalse(shouldEchoMessage(message.copy(text = "/unknown payload")))
    }

    @Test fun echoRequiresPrivateHumanText() {
        val message = TelegramMessage(1, 2, "private", text = "Hello 😀")
        assertTrue(shouldEchoMessage(message))
        assertFalse(shouldEchoMessage(message.copy(fromIsBot = true)))
        assertFalse(shouldEchoMessage(message.copy(chatType = "group")))
        assertFalse(shouldEchoMessage(message.copy(text = null)))
        assertFalse(shouldEchoMessage(message.copy(text = " ")))
    }

    @Test fun emojiSequencesUseCodePointsRatherThanUtf16UnitsOrGraphemes() {
        assertEquals(1, countTelegramCharacters("😀"))
        assertEquals(2, countTelegramCharacters("🇷🇺"))
        assertEquals(7, countTelegramCharacters("👨‍👩‍👧‍👦"))
        assertEquals(2, countTelegramCharacters("é"))
    }

    @Test fun rejectsEmptyAndWhitespaceButAcceptsOneCharacter() {
        assertTrue(validateTelegramText("") != null)
        assertTrue(validateTelegramText(" \n\t") != null)
        assertNull(validateTelegramText("я"))
    }

    @Test fun countsEmojiAsOneTelegramCharacter() {
        val text = "😀".repeat(4096)
        assertEquals(4096, countTelegramCharacters(text))
        assertTrue(validateTelegramText(text) == null)
        assertTrue(validateTelegramText(text + "x") != null)
    }
}
