package chat.hunmeng.console

import org.junit.Assert.*
import org.junit.Test

class AccountToolsTest {
    @Test fun roundTripRequiresVerifiedAccountAndDoesNotPersistPermissions() {
        val tools = AccountTools(7, listOf(MessageTemplate("fixture-id", "Название", "Привет 👋")), listOf(FavoriteRecipient(10, ChatPreview(-100, "Канал", "channel", null, true))))
        val bytes = tools.encode(); val restored = AccountTools.decode(bytes, 7)
        assertEquals("Привет 👋", restored.templates.single().text)
        assertNull(restored.favorites.single().chat.defaultCanSendMessages)
        assertThrows(IllegalArgumentException::class.java) { AccountTools.decode(bytes, 8) }
        assertEquals("AccountTools([private])", tools.toString())
    }
    @Test fun duplicateItemsAndCredentialsCannotBeSaved() {
        val template = MessageTemplate("fixture", "Название", "Привет")
        assertThrows(IllegalArgumentException::class.java) { AccountTools(7, listOf(template, template), emptyList()).encode() }
        assertThrows(IllegalArgumentException::class.java) { AccountTools(7, listOf(template.copy(text = "123456789:FAKE_TEST_CREDENTIAL_123456789012345")), emptyList()).encode() }
    }
}
