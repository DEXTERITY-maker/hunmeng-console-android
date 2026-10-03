package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant

enum class CheckStatus(val wire: String, val label: ConsoleText, val symbol: String) {
    IDLE("idle", ConsoleText("Не проверено", "Not checked"), "○"),
    CHECKING("checking", ConsoleText("Проверяем…", "Checking…"), "…"),
    SUCCESS("success", ConsoleText("Успешно", "Success"), "✓"),
    ERROR("error", ConsoleText("Ошибка", "Error"), "✕"),
    WARNING("warning", ConsoleText("Предупреждение", "Warning"), "!"),
}

enum class FailureSource(val wire: String) {
    CONSOLE("console"), GET_ME("get_me"), TRANSPORT("transport"), WEBHOOK("webhook"),
}

data class ConnectionChecks(
    val console: CheckStatus = CheckStatus.IDLE,
    val telegram: CheckStatus = CheckStatus.IDLE,
    val authorization: CheckStatus = CheckStatus.IDLE,
    val webhook: CheckStatus = CheckStatus.IDLE,
    val checkedAt: Instant? = null,
    val errorCode: Int? = null,
    val failureSource: FailureSource? = null,
)

interface ConnectionApi {
    suspend fun getMe(): BotUser
    suspend fun getWebhookInfo(): String?
}

data class ConnectionResult(
    val checks: ConnectionChecks,
    val bot: BotUser? = null,
    val webhookUrl: String? = null,
    val error: ConsoleText? = null,
)

/** Transport failure never means the credential was rejected. No raw error is retained. */
internal suspend fun checkConnection(
    client: ConnectionApi,
    clock: () -> Instant = Instant::now,
    onProgress: (ConnectionChecks) -> Unit = {},
    onSuccess: (Instant) -> Unit = {},
): ConnectionResult {
    var checks = ConnectionChecks(console = CheckStatus.SUCCESS, telegram = CheckStatus.CHECKING, authorization = CheckStatus.CHECKING)
    onProgress(checks)
    val bot = try {
        client.getMe().also { onSuccess(clock()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val apiError = error as? TelegramApiException
        checks = checks.copy(
            telegram = if (apiError != null) CheckStatus.SUCCESS else CheckStatus.ERROR,
            authorization = if (apiError?.errorCode == 401) CheckStatus.ERROR else CheckStatus.IDLE,
            checkedAt = clock(),
            errorCode = apiError?.errorCode,
            failureSource = if (apiError != null) FailureSource.GET_ME else FailureSource.TRANSPORT,
        )
        return ConnectionResult(checks, error = consoleError(error))
    }
    checks = checks.copy(telegram = CheckStatus.SUCCESS, authorization = CheckStatus.SUCCESS, webhook = CheckStatus.CHECKING)
    onProgress(checks)
    return try {
        val url = client.getWebhookInfo()
        onSuccess(clock())
        ConnectionResult(checks.copy(webhook = if (url.isNullOrBlank()) CheckStatus.SUCCESS else CheckStatus.WARNING, checkedAt = clock()), bot, url)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ConnectionResult(
            checks.copy(webhook = CheckStatus.ERROR, checkedAt = clock(), errorCode = (error as? TelegramApiException)?.errorCode, failureSource = FailureSource.WEBHOOK),
            bot = bot,
            error = consoleError(error),
        )
    }
}

/** Explicit allowlist: no state object, API result, error message, URL or identifier. */
fun connectionReport(state: ConsoleUiState, versionName: String, versionCode: Int): String? {
    val checks = state.checks
    if (checks.checkedAt == null || state.isChecking) return null
    val allowedBotStates = setOf("disconnected", "connecting", "disconnecting", "starting", "ready", "stopped", "connected", "retrying", "offline", "conflict", "webhook", "background", "authentication_failed", "error", "checking")
    return JSONObject()
        .put("app", "Hunmeng Console")
        .put("platform", "android")
        .put("version", DISPLAY_VERSION)
        .put("version_name", versionName)
        .put("version_code", versionCode)
        .put("checked_at", checks.checkedAt.toString())
        .put("checks", JSONObject()
            .put("console", checks.console.wire)
            .put("telegram", checks.telegram.wire)
            .put("authorization", checks.authorization.wire)
            .put("webhook", checks.webhook.wire))
        .put("error_code", checks.errorCode?.takeIf { it in 100..599 } ?: JSONObject.NULL)
        .put("failure_source", checks.failureSource?.wire ?: JSONObject.NULL)
        .put("bot_state", state.status.takeIf { it in allowedBotStates } ?: "error")
        .put("last_success_at", state.lastSuccessAt?.toString() ?: JSONObject.NULL)
        .toString(2)
}

fun canCheckConnection(state: ConsoleUiState): Boolean = !state.isChecking && !state.isConnecting && !state.isDisconnecting &&
    !state.isPolling && !state.isPollingTransition && !state.isPreviewing && !state.isSending && !state.isWebhookRemoving &&
    state.commandSyncStatus != "syncing"

fun canStartPolling(state: ConsoleUiState): Boolean = state.isConnected && !state.isChecking && !state.isDisconnecting &&
    !state.isPolling && !state.isPollingTransition && !state.isWebhookRemoving &&
    state.checks.authorization == CheckStatus.SUCCESS && state.checks.webhook == CheckStatus.SUCCESS
