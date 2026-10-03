package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TdLibTransportTest {
    private class Bridge : TdJsonBridge {
        var onRequest: (JSONObject) -> Unit = {}
        override fun createClientId() = 1
        override fun send(clientId: Int, request: String) { onRequest(JSONObject(request)) }
        override fun receive(timeout: Double): String? = null
        override fun execute(request: String): String? = null
    }
    @Test fun requestCorrelatesOnlyItsResponseAndDoesNotRetainRawErrors() = runTest {
        val bridge = Bridge()
        val client = TdLibClient(1, bridge) {}
        bridge.onRequest = { request ->
            client.receive(JSONObject().put("@extra", "foreign").put("@type", "ok"))
            client.receive(JSONObject().put("@extra", request.getString("@extra")).put("@type", "error").put("code", 429).put("message", "FLOOD_WAIT_8 TEST_PRIVATE_ERROR"))
        }
        val error = try { client.request("getOwnedBots"); error("Expected rejection") } catch (error: TdLibException) { error }
        assertEquals(429, error.code)
        assertEquals(8, error.retryAfter)
        assertFalse(error.message.orEmpty().contains("TEST_PRIVATE_ERROR"))
    }
    @Test fun closedClientCancelsOutstandingRequestsAndRemovesNativeInstance() = runTest {
        val bridge = Bridge(); var removed = false
        val client = TdLibClient(1, bridge) { removed = true }
        val waiting = async { client.request("getOwnedBots") }
        yield()
        client.receive(JSONObject("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateClosed"}}"""))
        assertTrue(removed)
        assertEquals("authorizationStateClosed", client.authorization.value)
        assertTrue(client.close())
        try { waiting.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
    }
    @Test fun authQrIsClearedWhenStateChangesAndUnneededMessageIsIgnored() {
        val client = TdLibClient(1, Bridge()) {}
        client.receive(JSONObject("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateWaitOtherDeviceConfirmation","link":"tg://login?token=TEST_QR_FIXTURE"}}"""))
        assertNotNull(client.qrLink.value)
        client.receive(JSONObject("""{"@type":"updateNewMessage","message":{"text":"TEST_PRIVATE_MESSAGE"}}"""))
        assertEquals("authorizationStateWaitOtherDeviceConfirmation", client.authorization.value)
        client.receive(JSONObject("""{"@type":"updateAuthorizationState","authorization_state":{"@type":"authorizationStateReady"}}"""))
        assertNull(client.qrLink.value)
    }
}
