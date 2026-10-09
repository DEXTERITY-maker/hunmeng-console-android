package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

@OptIn(ExperimentalCoroutinesApi::class)
class LoginFlowTest {
    private val clientId = "123456"
    private val state = "s".repeat(43)
    private val redirect = "https://app654321-login.tg.dev/tglogin"
    private fun attempt(binding: String = "b".repeat(43)): LoginAttempt {
        val params = mapOf("client_id" to clientId, "redirect_uri" to redirect, "state" to state, "response_type" to "code", "scope" to "openid profile", "code_challenge_method" to "S256", "nonce" to "n".repeat(43), "code_challenge" to "c".repeat(43))
        val query = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        return LoginAttempt(state, binding, "https://oauth.telegram.org/auth?$query", redirect, 1200)
    }
    private class Store : PendingLoginStore {
        var saved: LoginAttempt? = null
        override suspend fun load() = saved
        override suspend fun save(attempt: LoginAttempt) { saved = attempt }
        override suspend fun erase() { saved = null }
    }
    private inner class Gateway : LoginGateway {
        var completed = 0; var revoked = 0; var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun configuration() = LoginConfiguration(true, clientId, redirect)
        override suspend fun begin(binding: String) = attempt(binding)
        override suspend fun complete(attempt: LoginAttempt, code: String): LoginResult { completed++; gate?.await(); return LoginResult(VerifiedAccount(7, "Fixture", null), "r".repeat(43)) }
        override suspend fun cancel(attempt: LoginAttempt) { cancelled++ }
        override suspend fun verify(serverSession: String) = VerifiedAccount(7, "Fixture", null)
        override suspend fun revoke(serverSession: String) { revoked++ }
    }
    @Test fun restoresPendingAfterRecreationAndConsumesCallbackOnce() = runTest {
        val store = Store(); val gateway = Gateway(); var accepted = 0
        val first = LoginFlow(gateway, store, { accepted++; it.clearKey() }, clientId, redirect) { 1000 }
        assertNotNull(first.begin()); assertNotNull(store.saved)
        val restored = LoginFlow(gateway, store, { accepted++; it.clearKey() }, clientId, redirect) { 1000 }
        restored.restore()
        assertEquals(LoginPhase.WAITING, restored.state.value.phase)
        restored.callback("$redirect?state=$state&code=test-code")
        restored.callback("$redirect?state=$state&code=test-code")
        assertEquals(1, accepted); assertEquals(1, gateway.completed); assertNull(store.saved)
    }
    @Test fun wrongHostPathStateDuplicateAndFragmentCannotConsumeValidAttempt() = runTest {
        val store = Store(); val gateway = Gateway()
        val flow = LoginFlow(gateway, store, { fail("Unexpected session") }, clientId, redirect) { 1000 }
        flow.begin()
        for (uri in listOf("https://attacker.invalid/tglogin?state=$state&code=x", "$redirect/extra?state=$state&code=x", "$redirect?state=foreign&code=x", "$redirect?state=$state&state=$state&code=x", "$redirect?state=$state&code=x#fragment")) flow.callback(uri)
        assertNotNull(store.saved); assertEquals(0, gateway.completed)
    }
    @Test fun expiredAndDeniedCallbacksNeverCreateAccount() = runTest {
        val gateway = Gateway(); val store = Store().apply { saved = attempt() }
        val expired = LoginFlow(gateway, store, { fail("Expired") }, clientId, redirect) { 1300 }
        expired.callback("$redirect?state=$state&code=x")
        assertEquals(0, gateway.completed); assertNull(store.saved)
        val denied = LoginFlow(gateway, store, { fail("Denied") }, clientId, redirect) { 1000 }
        denied.begin(); denied.callback("$redirect?state=$state&error=access_denied")
        assertEquals(LoginPhase.IDLE, denied.state.value.phase); assertEquals(0, gateway.completed)
    }
    @Test fun cancellationStopsVerificationAndLateAccountCreation() = runTest {
        val gateway = Gateway().apply { gate = CompletableDeferred() }; val store = Store(); var accepted = false
        val flow = LoginFlow(gateway, store, { accepted = true }, clientId, redirect) { 1000 }
        flow.begin()
        val verify = launch { flow.callback("$redirect?state=$state&code=x") }
        yield(); flow.cancel(); gateway.gate!!.complete(Unit); verify.join()
        assertFalse(accepted); assertNull(store.saved); assertEquals(LoginPhase.IDLE, flow.state.value.phase)
    }
    @Test fun unverifiedProviderUrlOrExtraPermissionsAreRejected() {
        for (url in listOf(attempt().authorizationUrl.replace("oauth.telegram.org", "attacker.invalid"), attempt().authorizationUrl.replace("openid+profile", "openid+profile+phone"))) {
            val invalid = LoginAttempt(state, "b".repeat(43), url, redirect, 1200)
            assertThrows(IllegalArgumentException::class.java) { validateLoginAttempt(invalid, clientId, redirect) }
        }
        assertEquals("LoginAttempt([redacted])", attempt().toString())
    }
    @Test fun clientIdAndNativeAppUrlStayIndependentAndCallbackIsPinned() {
        validateLoginAttempt(attempt(), clientId, redirect)
        assertThrows(IllegalArgumentException::class.java) {
            validateLoginAttempt(attempt(), clientId, "https://app$clientId-login.tg.dev/tglogin")
        }
        for (uri in listOf("https://attacker.invalid/tglogin", "$redirect?extra=1", "$redirect#fragment", "$redirect/extra", "https://app654321-login.tg.dev:443/tglogin")) {
            assertFalse(validLoginRedirectUri(uri))
        }
    }
}
