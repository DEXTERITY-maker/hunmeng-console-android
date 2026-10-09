package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AccountControllerTest {
    private class Preferences : ConsolePreferences {
        override fun getString(key: String, fallback: String) = fallback
        override fun getBoolean(key: String, fallback: Boolean) = fallback
        override fun putString(key: String, value: String) { }
        override fun putBoolean(key: String, value: Boolean) { }
        override fun remove(key: String) { }
    }
    private class Store : AccountSessionStore {
        override suspend fun load(): AccountSessionRecord? = null
        override suspend fun save(session: AccountSessionRecord) { }
        override suspend fun erase() { }
    }
    private class Verifier : AccountSessionVerifier {
        override suspend fun verify(serverSession: String) = VerifiedAccount(7, "Fixture owner", null)
        override suspend fun revoke(serverSession: String) { }
    }
    private class Client : TelegramClientSession {
        override suspend fun resume(session: AccountSessionRecord) = 7L
        override suspend fun revokeCloseAndErase() = ClientSessionCleanup(true, true)
        override suspend fun closeWithoutErasing() = true
    }
    private class Source : OwnedInventorySource {
        val bot = OwnedBot(BotUser(10, "Fixture bot", "fixture_bot"), 7, Instant.parse("2026-10-04T00:00:00Z"))
        var fail = false; var gate: CompletableDeferred<Unit>? = null; var chatsCalls = 0
        override suspend fun ownedBots(): List<OwnedBot> { gate?.await(); if (fail) error("Fixture outage"); return listOf(bot) }
        override suspend fun availableChats(bot: OwnedBot): AccessibleBotChats { chatsCalls++; return AccessibleBotChats(emptyList(), Instant.now(), 1, false) }
    }
    private fun record() = AccountSessionRecord(7, "r".repeat(43), ByteArray(32))
    @Test fun profileNavigatesToBotsImmediatelyAndClientConsentAloneLoadsInventory() = runTest {
        val console = ConsoleController(Preferences(), backgroundScope, true)
        val session = AccountSessionCoordinator(Store(), Verifier(), Client(), console::clearAccountData)
        val source = Source(); val controller = AccountController(session, backgroundScope, { source }, console)
        yield(); session.acceptLogin(record()); yield()
        assertEquals(AppDestination.MY_BOTS, controller.state.value.destination)
        assertEquals(InventoryPhase.UNCONNECTED, controller.state.value.inventory.phase)
        session.connectClient(TelegramApiApplication(1000, "a".repeat(32))); yield(); yield()
        assertEquals(InventoryPhase.READY, controller.state.value.inventory.phase)
        controller.selectBot(source.bot.bot.id); yield()
        assertEquals(InventoryPhase.READY, controller.state.value.chatsPhase)
        assertFalse(controller.state.value.chats!!.scanComplete)
        controller.openConsole()
        assertEquals(AppDestination.CONSOLE, controller.state.value.destination)
        assertEquals(10L, console.state.value.selectedOwnedBot?.bot?.id)
        assertFalse(console.state.value.isConnected || console.state.value.isPolling)
    }
    @Test fun failedListIsNotEmptyAndLogoutInvalidatesInFlightResults() = runTest {
        val console = ConsoleController(Preferences(), backgroundScope, true)
        val session = AccountSessionCoordinator(Store(), Verifier(), Client(), console::clearAccountData)
        val source = Source().apply { fail = true }; val controller = AccountController(session, backgroundScope, { source }, console)
        session.acceptLogin(record()); session.connectClient(TelegramApiApplication(1000, "a".repeat(32))); yield(); yield()
        assertEquals(InventoryPhase.ERROR, controller.state.value.inventory.phase)
        source.fail = false; source.gate = CompletableDeferred(); controller.refreshBots(); yield()
        session.logout(); yield(); source.gate!!.complete(Unit); yield()
        assertTrue(controller.state.value.inventory.bots.isEmpty())
        assertEquals(InventoryPhase.UNCONNECTED, controller.state.value.inventory.phase)
        assertNull(console.state.value.selectedOwnedBot)
    }
}
