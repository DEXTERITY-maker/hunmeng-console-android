package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionTest {
    private class Store : AccountSessionStore {
        var payload: ByteArray? = null
        var saves = 0
        var failErase = false
        override suspend fun load() = payload?.let(AccountSessionRecord::decode)
        override suspend fun save(session: AccountSessionRecord) { payload = session.encode(); saves++ }
        override suspend fun erase() { if (failErase) error("TEST_ERASE_FAILURE"); payload?.fill(0); payload = null }
    }
    private class Verifier : AccountSessionVerifier {
        var userId = 7L
        var failRevoke = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun verify(serverSession: String): VerifiedAccount {
            gate?.await()
            return VerifiedAccount(userId, "Fixture", null)
        }
        override suspend fun revoke(serverSession: String) { if (failRevoke) error("TEST_OFFLINE") }
    }
    private class Client : TelegramClientSession {
        var userId = 7L
        var cleanup = ClientSessionCleanup(true, true)
        var cleaned = false
        override suspend fun resume(session: AccountSessionRecord) = userId
        override suspend fun revokeCloseAndErase(): ClientSessionCleanup { cleaned = true; return cleanup }
    }
    private fun record() = AccountSessionRecord(7L, "TEST_OPAQUE_ACCOUNT_SESSION_".repeat(3), ByteArray(32) { 9 })

    @Test fun persistsOnlyAfterBothIdentitiesAreVerifiedThenErasesOnLogout() = runTest {
        val store = Store(); val verifier = Verifier(); val client = Client()
        var consoleCleared = false
        val coordinator = AccountSessionCoordinator(store, verifier, client) { consoleCleared = true }
        val record = record()
        coordinator.acceptLogin(record)
        assertEquals(AccountPhase.VERIFIED, coordinator.state.value.phase)
        assertEquals(1, store.saves)
        coordinator.logout()
        assertTrue(consoleCleared && client.cleaned)
        assertNull(store.payload)
        assertTrue(record.databaseKey.all { it == 0.toByte() })
        assertEquals(AccountPhase.SIGNED_OUT, coordinator.state.value.phase)
        assertFalse(coordinator.state.value.remoteRevocationUnconfirmed)
    }
    @Test fun foreignServerOrTelegramIdentityNeverGrantsAccessOrPersists() = runTest {
        for (serverMismatch in listOf(true, false)) {
            val store = Store(); val verifier = Verifier(); val client = Client()
            if (serverMismatch) verifier.userId = 8L else client.userId = 8L
            val coordinator = AccountSessionCoordinator(store, verifier, client) {}
            val record = record()
            coordinator.acceptLogin(record)
            assertEquals(AccountPhase.UNAVAILABLE, coordinator.state.value.phase)
            assertNull(coordinator.state.value.account)
            assertEquals(0, store.saves)
            assertTrue(record.databaseKey.all { it == 0.toByte() })
        }
    }
    @Test fun restoredBytesDoNotProveIdentityAndRequireServerRevalidation() = runTest {
        val store = Store().apply { payload = record().encode() }
        val verifier = Verifier().apply { userId = 8L }
        val coordinator = AccountSessionCoordinator(store, verifier, Client()) {}
        coordinator.restore()
        assertEquals(AccountPhase.UNAVAILABLE, coordinator.state.value.phase)
        assertNull(coordinator.state.value.account)
    }
    @Test fun logoutSuppressesLateLoginAndPreventsNewFile() = runTest {
        val store = Store(); val verifier = Verifier(); val client = Client()
        val gate = CompletableDeferred<Unit>(); verifier.gate = gate
        var cleared = false
        val coordinator = AccountSessionCoordinator(store, verifier, client) { cleared = true }
        val login = launch { coordinator.acceptLogin(record()) }
        yield()
        val logout = launch { coordinator.logout() }
        yield()
        assertTrue(cleared)
        assertEquals(AccountPhase.SIGNING_OUT, coordinator.state.value.phase)
        gate.complete(Unit)
        login.join(); logout.join()
        assertNull(store.payload)
        assertEquals(0, store.saves)
        assertEquals(AccountPhase.SIGNED_OUT, coordinator.state.value.phase)
    }
    @Test fun networkRevocationFailureStillDeletesLocalSessionAndIsReportedHonestly() = runTest {
        val store = Store(); val verifier = Verifier().apply { failRevoke = true }
        val client = Client().apply { cleanup = ClientSessionCleanup(true, false) }
        val coordinator = AccountSessionCoordinator(store, verifier, client) {}
        coordinator.acceptLogin(record()); coordinator.logout()
        assertNull(store.payload)
        assertEquals(AccountPhase.SIGNED_OUT, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.remoteRevocationUnconfirmed)
    }
    @Test fun cleanupFailureDoesNotClaimSuccessfulErasureAndCanRetry() = runTest {
        val store = Store(); val coordinator = AccountSessionCoordinator(store, Verifier(), Client()) {}
        coordinator.acceptLogin(record()); store.failErase = true
        coordinator.logout()
        assertEquals(AccountPhase.CLEANUP_REQUIRED, coordinator.state.value.phase)
        store.failErase = false
        coordinator.logout()
        assertEquals(AccountPhase.SIGNED_OUT, coordinator.state.value.phase)
        assertNull(store.payload)
    }
    @Test fun recordDiagnosticsNeverContainSessionAndRejectBotToken() {
        val record = record()
        assertEquals("AccountSessionRecord([redacted])", record.toString())
        assertThrows(IllegalArgumentException::class.java) { AccountSessionRecord(7L, "123456789:FAKE_TEST_TOKEN_VALUE_1234567890", ByteArray(32)) }
    }
}
