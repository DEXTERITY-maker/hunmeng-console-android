package chat.hunmeng.console

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TdLibSmokeDeviceTest {
    @Test fun nativeBindingLoadsAndClosesWithoutAuthorizingAnyAccount() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val client = TdLibRuntime(NativeTdJsonBridge, scope).client()
            val state = client.request("getAuthorizationState")
            assertEquals("authorizationStateWaitTdlibParameters", state.optString("@type"))
            assertTrue(client.close())
        } finally { scope.cancel() }
    }
}
