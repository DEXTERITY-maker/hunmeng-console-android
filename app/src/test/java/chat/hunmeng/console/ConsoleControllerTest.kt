package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConsoleControllerTest {
    private class MemoryPreferences : ConsolePreferences {
        val saved = mutableMapOf<String, Any>()
        override fun getString(key: String, fallback: String) = saved[key] as? String ?: fallback
        override fun getBoolean(key: String, fallback: Boolean) = saved[key] as? Boolean ?: fallback
        override fun putString(key: String, value: String) { saved[key] = value }
        override fun putBoolean(key: String, value: Boolean) { saved[key] = value }
        override fun remove(key: String) { saved.remove(key) }
    }
    private fun ok(body: String) = MockResponse().setBody("""{"ok":true,"result":$body}""")
    private fun me() = ok("""{"id":7,"first_name":"Test","username":"our_bot"}""")
    private fun webhook(url: String = "") = ok("""{"url":"$url"}""")
    private fun failure(code: Int) = MockResponse().setResponseCode(code).setBody("""{"ok":false,"error_code":$code,"description":"private description"}""")

    private suspend fun withConsole(block: suspend (ConsoleController, MockWebServer, MemoryPreferences) -> Unit) {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server = MockWebServer().also { it.start() }
        val prefs = MemoryPreferences()
        val console = ConsoleController(prefs, scope, apiFactory = { TelegramApiClient.forTesting(it, server.url("/"), OkHttpClient()) })
        try { withContext(dispatcher) { block(console, server, prefs) } }
        finally {
            withContext(dispatcher) { console.close() }
            scope.cancel()
            dispatcher.close()
            server.shutdown()
        }
    }
    private suspend fun ConsoleController.await(predicate: (ConsoleUiState) -> Boolean): ConsoleUiState = withTimeout(20_000) { state.first(predicate) }
    private suspend fun connected(console: ConsoleController, server: MockWebServer, url: String = "") {
        server.enqueue(me()); server.enqueue(webhook(url)); server.enqueue(ok("true"))
        console.setTokenInput("TEST_TOKEN")
        console.connect()
        console.await { it.isConnected && it.commandSyncStatus == "synced" }
    }

    @Test fun repeatUsesMemorySessionAndNeverRunsAlongsideAnotherDiagnostic() = runBlocking {
        withConsole { console, server, prefs ->
            connected(console, server)
            assertEquals("", console.state.value.tokenInput)
            server.enqueue(me().setBodyDelay(200, TimeUnit.MILLISECONDS)); server.enqueue(webhook()); server.enqueue(ok("true"))
            console.retryConnectionCheck()
            console.retryConnectionCheck()
            console.connect()
            assertTrue(console.state.value.isChecking)
            console.await { !it.isChecking && it.commandSyncStatus == "synced" }
            assertEquals(6, server.requestCount)
            assertFalse(prefs.saved.containsKey("token"))
            assertTrue(prefs.saved.isEmpty())
        }
    }

    @Test fun disconnectAndNewTokenCannotAcceptTheOldResponse() = runBlocking {
        withConsole { console, server, _ ->
            server.enqueue(me().setBodyDelay(500, TimeUnit.MILLISECONDS))
            console.setTokenInput("OLD_TEST_TOKEN")
            console.connect()
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            console.disconnect()
            console.await { !it.isDisconnecting }
            assertEquals(ConnectionChecks(), console.state.value.checks)
            assertEquals("", console.state.value.tokenInput)
            server.enqueue(me()); server.enqueue(webhook()); server.enqueue(ok("true"))
            console.setTokenInput("NEW_TEST_TOKEN")
            console.connect()
            console.await { it.commandSyncStatus == "synced" }
            delay(600)
            assertTrue(console.state.value.isConnected)
            assertEquals(CheckStatus.SUCCESS, console.state.value.checks.webhook)
            assertFalse(console.state.value.isChecking)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun webhookFailureKeepsSessionForRetryAndBlocksStart() = runBlocking {
        withConsole { console, server, _ ->
            server.enqueue(me()); server.enqueue(MockResponse().setBody("not JSON")); server.enqueue(ok("true"))
            console.setTokenInput("TEST_TOKEN"); console.connect()
            console.await { it.commandSyncStatus == "synced" }
            assertTrue(console.state.value.isConnected)
            assertEquals(CheckStatus.SUCCESS, console.state.value.checks.authorization)
            assertEquals(CheckStatus.ERROR, console.state.value.checks.webhook)
            assertNotNull(console.state.value.webhookError)
            assertNull(console.state.value.connectionError)
            console.startPolling()
            assertEquals(3, server.requestCount)
            server.enqueue(me()); server.enqueue(webhook()); server.enqueue(ok("true"))
            console.retryConnectionCheck()
            console.await { !it.isChecking && it.commandSyncStatus == "synced" }
            assertTrue(canStartPolling(console.state.value))
        }
    }

    @Test fun tabsPreserveInputsLogSearchRepliesAndOnePollingCycle() = runBlocking {
        withConsole { console, server, prefs ->
            connected(console, server)
            console.setChatInput("123456789"); console.setMessageInput("Private draft")
            console.setEventQuery("подключ"); console.setEventFilter(EventFilter.ERRORS)
            console.setWelcome("Custom greeting"); console.toggleReplies()
            val before = console.state.value
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            console.startPolling()
            console.await { it.isPolling }
            withContext(Dispatchers.IO) { repeat(4) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) } }
            for (tab in ConsoleTab.entries) { console.selectTab(tab); console.startPolling(); console.retryConnectionCheck() }
            val after = console.state.value
            assertEquals(before.chatInput, after.chatInput)
            assertEquals(before.messageInput, after.messageInput)
            assertEquals(before.eventQuery, after.eventQuery)
            assertEquals(before.eventFilter, after.eventFilter)
            assertEquals(before.welcome, after.welcome)
            assertTrue(after.repliesExpanded)
            assertTrue(after.events.containsAll(before.events))
            assertTrue(after.isPolling)
            assertEquals(4, server.requestCount)
            console.onBackground()
            console.await { !it.isPolling && !it.isPollingTransition }
            console.onForeground()
            assertFalse(console.state.value.isPolling)
            assertEquals(4, server.requestCount)
            assertEquals(setOf("welcome", "welcome_custom"), prefs.saved.keys)
        }
    }

    @Test fun sendAndMenuErrorsDoNotInvalidateTheAuthorizationCheck() = runBlocking {
        withConsole { console, server, _ ->
            server.enqueue(me()); server.enqueue(webhook()); server.enqueue(failure(403))
            console.setTokenInput("TEST_TOKEN"); console.connect()
            console.await { it.commandSyncStatus == "failed" }
            assertNotNull(console.state.value.commandError)
            assertNull(console.state.value.connectionError)
            console.setChatInput("123456789"); console.setMessageInput("Private text")
            server.enqueue(failure(403)); console.previewSend()
            console.await { !it.isPreviewing }
            assertNotNull(console.state.value.sendError)
            assertEquals(CheckStatus.SUCCESS, console.state.value.checks.authorization)
            assertNull(console.state.value.connectionError)
            assertEquals(2, visibleEvents(console.state.value.events, "", EventFilter.ERRORS, UiLanguage.EN).size)
        }
    }

    @Test fun webhookRemovalRequiresConfirmationAndPreservesPendingUpdates() = runBlocking {
        withConsole { console, server, _ ->
            connected(console, server, "https://private.example/webhook")
            assertEquals(CheckStatus.WARNING, console.state.value.checks.webhook)
            console.confirmWebhookRemoval()
            assertEquals(3, server.requestCount)
            console.requestWebhookRemoval()
            assertTrue(console.state.value.webhookDialogVisible)
            server.enqueue(ok("true"))
            console.confirmWebhookRemoval()
            console.retryConnectionCheck()
            console.await { !it.isWebhookRemoving }
            assertTrue(canStartPolling(console.state.value))
            withContext(Dispatchers.IO) {
                repeat(3) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
                val removal = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertTrue(removal.path!!.endsWith("deleteWebhook"))
                assertEquals("drop_pending_updates=false", removal.body.readUtf8())
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun unknownSendKeepsDraftAcrossTabsAndIsNeverRetried() = runBlocking {
        withConsole { console, server, _ ->
            connected(console, server)
            console.setChatInput("123456789"); console.setMessageInput("Private text")
            server.enqueue(ok("""{"id":123456789,"type":"private","first_name":"Recipient"}"""))
            console.previewSend(); console.await { it.sendPreview != null }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            console.confirmSend(); console.await { it.draftDelivery == "unknown" }
            ConsoleTab.entries.forEach(console::selectTab)
            assertEquals("Private text", console.state.value.draft)
            console.restoreDraft()
            assertEquals("Private text", console.state.value.messageInput)
            assertEquals("123456789", console.state.value.chatInput)
            assertEquals(5, server.requestCount)
            assertEquals(CheckStatus.SUCCESS, console.state.value.checks.authorization)
        }
    }

    @Test fun acceptedCommandsLogNamesWithoutArgumentsAndKeep150Entries() = runBlocking {
        withConsole { console, server, _ ->
            connected(console, server)
            val polls = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.endsWith("getUpdates") == true && polls.incrementAndGet() == 1 -> {
                        val updates = (1..155).joinToString(",") { id -> """{"update_id":$id,"message":{"message_id":$id,"chat":{"id":42,"type":"private"},"text":"/ping@our_bot PRIVATE_ARGUMENT"}}""" }
                        ok("[$updates]")
                    }
                    request.path?.endsWith("sendMessage") == true -> ok("""{"message_id":1}""")
                    else -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            console.startPolling()
            console.await { it.status == "connected" && it.events.size == 150 }
            console.stopPolling(); console.await { !it.isPolling && !it.isPollingTransition }
            val events = console.state.value.events
            assertEquals(150, events.size)
            val commands = visibleEvents(events, "", EventFilter.COMMANDS, UiLanguage.EN)
            assertTrue(commands.isNotEmpty())
            assertTrue(commands.all { it.detail.en == "Received /ping" })
            assertTrue(events.none { it.detail.en.contains("PRIVATE_ARGUMENT") || it.detail.ru.contains("PRIVATE_ARGUMENT") })
            console.setLanguage(UiLanguage.EN)
            console.setEventQuery("Received")
            assertTrue(visibleEvents(console.state.value.events, console.state.value.eventQuery, EventFilter.COMMANDS, UiLanguage.EN).isNotEmpty())
            console.clearEvents()
            assertTrue(console.state.value.events.isEmpty())
        }
    }
}
