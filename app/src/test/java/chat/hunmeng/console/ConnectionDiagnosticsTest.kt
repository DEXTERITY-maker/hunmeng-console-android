package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException

class ConnectionDiagnosticsTest {
    private val instant = Instant.parse("2026-10-04T00:00:00Z")
    private val bot = BotUser(987654321, "Private bot", "private_username")
    private fun api(getMeError: Exception? = null, webhookError: Exception? = null, url: String = "") = object : ConnectionApi {
        override suspend fun getMe(): BotUser { getMeError?.let { throw it }; return bot }
        override suspend fun getWebhookInfo(): String { webhookError?.let { throw it }; return url }
    }

    @Test fun successfulStagesAndInstantsAreReported() = runBlocking {
        val progress = mutableListOf<ConnectionChecks>()
        val successes = mutableListOf<Instant>()
        val result = checkConnection(api(), { instant }, progress::add, successes::add)
        assertEquals(CheckStatus.CHECKING, progress.first().telegram)
        assertEquals(CheckStatus.CHECKING, progress.last().webhook)
        assertEquals(CheckStatus.SUCCESS, result.checks.console)
        assertEquals(CheckStatus.SUCCESS, result.checks.telegram)
        assertEquals(CheckStatus.SUCCESS, result.checks.authorization)
        assertEquals(CheckStatus.SUCCESS, result.checks.webhook)
        assertEquals(instant, result.checks.checkedAt)
        assertEquals(listOf(instant, instant), successes)
    }

    @Test fun only401MeansRejectedAuthorization() = runBlocking {
        for (code in listOf(401, 403, 429, 500)) {
            val result = checkConnection(api(getMeError = TelegramApiException(code, "Untrusted description")), { instant })
            assertEquals(CheckStatus.SUCCESS, result.checks.telegram)
            assertEquals(if (code == 401) CheckStatus.ERROR else CheckStatus.IDLE, result.checks.authorization)
            assertEquals(CheckStatus.IDLE, result.checks.webhook)
            assertEquals(FailureSource.GET_ME, result.checks.failureSource)
            assertEquals(code, result.checks.errorCode)
        }
    }

    @Test fun dnsTlsAndTimeoutLeaveTokenUnverified() = runBlocking {
        for (error in listOf(UnknownHostException("DNS"), SSLException("TLS"), SocketTimeoutException("Timeout"))) {
            val result = checkConnection(api(getMeError = TelegramNetworkException(error)), { instant })
            assertEquals(CheckStatus.ERROR, result.checks.telegram)
            assertEquals(CheckStatus.IDLE, result.checks.authorization)
            assertEquals(FailureSource.TRANSPORT, result.checks.failureSource)
            assertNull(result.checks.errorCode)
            assertNull(result.bot)
        }
    }

    @Test fun webhookIsAWarningAndNeverAutoDeleted() = runBlocking {
        val result = checkConnection(api(url = "https://private.example/webhook"), { instant })
        assertEquals(CheckStatus.SUCCESS, result.checks.authorization)
        assertEquals(CheckStatus.WARNING, result.checks.webhook)
        assertEquals(bot, result.bot)
        assertNull(result.error)
    }

    @Test fun webhookFailureKeepsProvenAuthorizationButBlocksPolling() = runBlocking {
        val result = checkConnection(api(webhookError = TelegramNetworkException(IOException("private error"))), { instant })
        assertEquals(bot, result.bot)
        assertEquals(CheckStatus.SUCCESS, result.checks.authorization)
        assertEquals(CheckStatus.ERROR, result.checks.webhook)
        assertEquals(FailureSource.WEBHOOK, result.checks.failureSource)
        assertFalse(canStartPolling(ConsoleUiState(isConnected = true, checks = result.checks)))
    }

    @Test fun cancellationIsNotConvertedToACompletedFailure() = runBlocking {
        for (client in listOf(api(getMeError = CancellationException()), api(webhookError = CancellationException()))) {
            assertTrue(runCatching { checkConnection(client) }.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun reportUsesOnlyAllowedFieldsEvenWithSensitiveState() {
        val token = "123456789:" + "x".repeat(32)
        val state = ConsoleUiState(
            tokenInput = token, bot = bot, webhookUrl = "https://private.example/webhook",
            chatInput = "-1001234567890", messageInput = "Private message", draft = "Draft secret",
            eventQuery = "Secret search", welcome = "Custom secret",
            connectionError = ConsoleText("Private exception $token", "Private exception $token"),
            events = listOf(ConsoleEventRecord("00:00:00", ConsoleEventType.ERROR, ConsoleText(token, token))),
            checks = ConnectionChecks(checkedAt = instant, errorCode = 401, failureSource = FailureSource.GET_ME),
            lastSuccessAt = instant, status = "https://untrusted.example",
        )
        val report = connectionReport(state, "0.0.4-beta", 2)!!
        val json = JSONObject(report)
        assertEquals(setOf("app", "platform", "version", "version_name", "version_code", "checked_at", "checks", "error_code", "failure_source", "bot_state", "last_success_at"), json.keys().asSequence().toSet())
        assertEquals(setOf("console", "telegram", "authorization", "webhook"), json.getJSONObject("checks").keys().asSequence().toSet())
        for (secret in listOf(token, "private_username", "987654321", "Private bot", "-1001234567890", "Private message", "Draft secret", "Secret search", "Custom secret", "https://", "exception")) assertFalse(secret, report.contains(secret))
        assertEquals("error", json.getString("bot_state"))
        assertEquals(instant.toString(), json.getString("checked_at"))
        assertEquals(instant.toString(), json.getString("last_success_at"))
        assertNull(connectionReport(state.copy(isChecking = true), "0.0.4-beta", 2))
        assertNull(connectionReport(ConsoleUiState(), "0.0.4-beta", 2))
    }

    @Test fun reportNullsAndOperationGuardsAreExplicit() {
        val ready = ConsoleUiState(isConnected = true, checks = ConnectionChecks(authorization = CheckStatus.SUCCESS, webhook = CheckStatus.SUCCESS, checkedAt = instant))
        assertTrue(canCheckConnection(ready))
        assertTrue(canStartPolling(ready))
        val busy = listOf(ready.copy(isChecking = true), ready.copy(isConnecting = true), ready.copy(isDisconnecting = true), ready.copy(isPolling = true), ready.copy(isPollingTransition = true), ready.copy(isPreviewing = true), ready.copy(isSending = true), ready.copy(isWebhookRemoving = true), ready.copy(commandSyncStatus = "syncing"))
        busy.forEach { assertFalse(canCheckConnection(it)) }
        val json = JSONObject(connectionReport(ready, "0.0.4-beta", 2)!!)
        assertTrue(json.isNull("last_success_at"))
        assertTrue(json.isNull("error_code"))
        assertTrue(json.isNull("failure_source"))
        CheckStatus.entries.forEach { assertFalse(it.label.ru.isBlank()); assertFalse(it.label.en.isBlank()); assertFalse(it.symbol.isBlank()) }
    }
}
