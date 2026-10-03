package chat.hunmeng.console

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TelegramApiClientTest {
    @Test fun malformedSuccessAndErrorEnvelopesNeverProveAuthorization() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), OkHttpClient())
        try {
            for (body in listOf("{}", "{\"ok\":false}", "{\"ok\":true,\"result\":{}}", "not JSON")) {
                server.enqueue(MockResponse().setBody(body))
                val failure = runCatching { client.getMe() }.exceptionOrNull()
                assertTrue(failure is TelegramNetworkException)
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun cancelCoroutineAlsoCancelsTheInFlightHttpRequest() = runBlocking {
        val canceled = CountDownLatch(1)
        val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) canceled.countDown()
            }
        }).build()
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), transport)
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val poll = async(Dispatchers.Default) { client.getUpdates(null) }
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
            withTimeout(1_000) { poll.cancelAndJoin() }
            assertTrue("HTTP call must be canceled, not just its coroutine", canceled.await(3, TimeUnit.SECONDS))
            assertTrue(poll.isCancelled)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun sendIsNotRepeatedAfterServerReadTheRequestThenDisconnected() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), OkHttpClient())
        try {
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"id":7,"first_name":"Test","username":"test_bot"}}"""))
            client.getMe() // Warm the connection: retry of a stale pooled socket is a real risk.
            server.takeRequest(3, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":999}}"""))
            val failure = runCatching { withTimeout(3_000) { client.sendMessage(1, "Test text") } }.exceptionOrNull()
            assertTrue(failure is TelegramNetworkException)
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS)?.path?.endsWith("/sendMessage") == true)
            assertNull("An unknown send must never be replayed", server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertEquals(2, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun http503RetryAfterZeroCannotReplayASend() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), OkHttpClient())
        try {
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0")
                .setBody("""{"ok":false,"error_code":503,"description":"Service unavailable"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"message_id":999}}"""))
            val failure = runCatching { withTimeout(3_000) { client.sendMessage(1, "Test text") } }.exceptionOrNull()
            assertTrue(failure is TelegramApiException)
            assertEquals(503, (failure as TelegramApiException).errorCode)
            assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun credentialIsRedactedEvenIfServerReflectsItInAnError() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), OkHttpClient())
        try {
            server.enqueue(MockResponse().setResponseCode(401)
                .setBody("""{"ok":false,"error_code":401,"description":"Rejected TEST_TOKEN"}"""))
            val failure = runCatching { client.getMe() }.exceptionOrNull()!!
            assertTrue(failure is TelegramApiException)
            assertFalse(failure.stackTraceToString().contains("TEST_TOKEN"))
            assertNull(failure.cause)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun closeIsIdempotentAndForbidsNewRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), OkHttpClient())
        try {
            client.close()
            client.clear()
            val failure = runCatching { client.getMe() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0, server.requestCount)
            assertFalse(failure?.message.orEmpty().contains("TEST_TOKEN"))
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun explicitTelegramRetryAfterIsReturnedUnchanged() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = TelegramApiClient.forTesting("TEST_TOKEN", server.url("/"), OkHttpClient())
        try {
            server.enqueue(MockResponse().setResponseCode(429)
                .setBody("""{"ok":false,"error_code":429,"description":"Too many requests","parameters":{"retry_after":120}}"""))
            val failure = runCatching { client.getUpdates(null) }.exceptionOrNull() as TelegramApiException
            assertEquals(120L, failure.retryAfterSeconds)
            assertEquals(429, failure.errorCode)
        } finally {
            client.close()
            server.shutdown()
        }
    }
}
