package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant

data class ConsoleUiState(
    val language: UiLanguage = UiLanguage.RU,
    val tokenInput: String = "",
    val tokenVisible: Boolean = false,
    val bot: BotUser? = null,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isDisconnecting: Boolean = false,
    val isPolling: Boolean = false,
    val isPollingTransition: Boolean = false,
    val status: String = "disconnected",
    val lastSuccessfulRequest: String? = null,
    val webhookUrl: String? = null,
    val webhookDialogVisible: Boolean = false,
    val isWebhookRemoving: Boolean = false,
    val welcome: String = defaultWelcome(UiLanguage.RU),
    val welcomeCustom: Boolean = false,
    val echoEnabled: Boolean = false,
    val commandSyncStatus: String = "idle",
    val chatInput: String = "",
    val messageInput: String = "",
    val sendPreview: SendPreview? = null,
    val isPreviewing: Boolean = false,
    val isSending: Boolean = false,
    val draft: String? = null,
    val draftRecipient: ChatPreview? = null,
    val draftDelivery: String? = null,
    val versionCheckRequested: Boolean = false,
    val events: List<ConsoleEventRecord> = emptyList(),
    val connectionError: ConsoleText? = null,
    val sendError: ConsoleText? = null,
    val commandError: ConsoleText? = null,
    val pollingError: ConsoleText? = null,
    val webhookError: ConsoleText? = null,
    val isChecking: Boolean = false,
    val checks: ConnectionChecks = ConnectionChecks(),
    val lastSuccessAt: Instant? = null,
    val selectedTab: ConsoleTab = ConsoleTab.BOT,
    val repliesExpanded: Boolean = false,
    val eventQuery: String = "",
    val eventFilter: EventFilter = EventFilter.ALL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val counters: SessionCounters = SessionCounters(),
    val templates: List<MessageTemplate> = emptyList(),
    val favorites: List<FavoriteRecipient> = emptyList(),
    val verifiedRecipient: ChatPreview? = null,
    val toolsError: ConsoleText? = null,
    val selectedOwnedBot: OwnedBot? = null,
    val toolsAccountId: Long? = null,
)

interface ConsolePreferences {
    fun getString(key: String, fallback: String): String?
    fun getBoolean(key: String, fallback: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

/** State and jobs live here in memory, independent of Activity composition and tabs. */
class ConsoleController(
    private val prefs: ConsolePreferences,
    private val scope: CoroutineScope,
    initialForeground: Boolean = true,
    private val apiFactory: (String) -> TelegramApiClient = ::TelegramApiClient,
    private val clock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<ConsoleUiState> = _state.asStateFlow()
    private var api: TelegramApiClient? = null
    private var connectingApi: TelegramApiClient? = null
    private var polling: PollingController? = null
    private var nextOffset: Long? = null
    private var session = 0L
    private var connectJob: Job? = null
    private var commandJob: Job? = null
    private var pollingActionJob: Job? = null
    private var webhookJob: Job? = null
    private var previewJob: Job? = null
    private var sendJob: Job? = null
    private var foreground = initialForeground
    private val handledUpdates = LinkedHashSet<Long>()


    private fun loadState(): ConsoleUiState {
        val language = if (prefs.getString("language", "RU") == "EN") UiLanguage.EN else UiLanguage.RU
        val custom = prefs.getBoolean("welcome_custom", false)
        return ConsoleUiState(
            language = language,
            // An old saved default must not override the chosen language.
            welcome = if (custom) prefs.getString("welcome", "") ?: "" else defaultWelcome(language),
            welcomeCustom = custom,
            echoEnabled = prefs.getBoolean("echo", false),
            themeMode = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", "SYSTEM") } ?: ThemeMode.SYSTEM,
        )
    }

    fun setTokenInput(value: String) {
        if (!state.value.isConnecting && !state.value.isConnected && !state.value.isDisconnecting && !state.value.isChecking) {
            // A new credential invalidates a previous completed diagnostic report.
            update { it.copy(tokenInput = value, connectionError = null, checks = ConnectionChecks(), lastSuccessAt = null, lastSuccessfulRequest = null) }
        }
    }

    fun toggleTokenVisibility() = update { it.copy(tokenVisible = !it.tokenVisible) }

    fun connect() {
        if (state.value.isConnected || !canCheckConnection(state.value)) return
        val token = state.value.tokenInput.trim()
        if (token.isBlank()) {
            update { it.copy(connectionError = ConsoleText("Введите токен бота", "Enter the bot token")) }
            return
        }
        val client = try { apiFactory(token) } catch (_: Exception) {
            update { it.copy(checks = ConnectionChecks(console = CheckStatus.ERROR, checkedAt = clock(), failureSource = FailureSource.CONSOLE), connectionError = ConsoleText("Не удалось подготовить API-клиент", "Could not prepare the API client")) }
            return
        }
        connectingApi = client
        runDiagnostic(client, ++session, isNew = true)
    }

    fun retryConnectionCheck() {
        if (!canCheckConnection(state.value)) return
        val client = api
        if (client == null) connect() else runDiagnostic(client, session, isNew = false)
    }

    private fun runDiagnostic(client: TelegramApiClient, generation: Long, isNew: Boolean) {
        update { it.copy(isChecking = true, isConnecting = isNew, status = if (isNew) "connecting" else "checking", checks = ConnectionChecks(), webhookDialogVisible = false, connectionError = null, webhookError = null) }
        connectJob = scope.launch {
            var retained = !isNew
            try {
                val result = checkConnection(client, clock,
                    onProgress = { checks -> if (generation == session) update { it.copy(checks = checks) } },
                    onSuccess = { instant -> if (generation == session) markSuccess(instant) },
                )
                if (generation != session) return@launch
                val expected = state.value.selectedOwnedBot?.bot?.id
                if (result.bot != null && expected != null && result.bot.id != expected) {
                    update { it.copy(isConnecting = false, isChecking = false, isConnected = false, tokenInput = "", tokenVisible = false, status = "error", bot = null, checks = result.checks.copy(authorization = CheckStatus.ERROR, webhook = CheckStatus.IDLE, checkedAt = clock()), connectionError = ConsoleText("Токен принадлежит другому боту. Используйте токен выбранного бота.", "This token belongs to another bot. Use the selected bot's token.")) }
                    addEvent(ConsoleEventType.CONNECTION, ConsoleText("Токен не соответствует выбранному боту", "Token does not match the selected bot"), isError = true)
                    return@launch
                }
                if (result.bot != null) {
                    api = client
                    connectingApi = null
                    retained = true
                    if (isNew) {
                        polling = PollingController(scope)
                        nextOffset = null
                        handledUpdates.clear()
                        update { it.copy(counters = SessionCounters(), verifiedRecipient = null) }
                    }
                } else if (!isNew && result.checks.errorCode == 401) {
                    client.close()
                    api = null
                    polling = null
                }
                val connected = result.bot != null || (!isNew && result.checks.errorCode != 401)
                update { it.copy(
                    isChecking = false, isConnecting = false, isConnected = connected,
                    tokenInput = if (connected) "" else it.tokenInput, tokenVisible = false,
                    bot = result.bot ?: if (connected) it.bot else null,
                    checks = result.checks, webhookUrl = result.webhookUrl,
                    status = when (result.checks.webhook) { CheckStatus.SUCCESS -> "ready"; CheckStatus.WARNING -> "webhook"; else -> "error" },
                    connectionError = if (result.checks.failureSource == FailureSource.WEBHOOK) null else result.error,
                    webhookError = if (result.checks.failureSource == FailureSource.WEBHOOK) result.error else null,
                ) }
                if (result.error != null) addEvent(if (result.checks.failureSource == FailureSource.WEBHOOK) ConsoleEventType.WEBHOOK else ConsoleEventType.CONNECTION, result.error, isError = true)
                else addEvent(ConsoleEventType.CONNECTION, ConsoleText("Проверка подключения завершена", "Connection check completed"))
                if (isNew && result.bot != null) addEvent(ConsoleEventType.CONNECTION, ConsoleText("Подключён бот ${botDisplay(result.bot)}", "Connected bot ${botDisplay(result.bot)}"))
                if (result.bot != null) synchronizeCommands()
            } finally {
                if (!retained) client.close()
                if (connectingApi === client) connectingApi = null
                if (generation == session) update { it.copy(isChecking = false, isConnecting = false) }
            }
        }
    }

    fun disconnect() {
        if (state.value.isDisconnecting) return
        val generation = ++session
        val pendingSend = state.value.sendPreview
        val uncertainSend = state.value.isSending && pendingSend != null
        val oldPolling = polling
        val jobs = listOfNotNull(connectJob, commandJob, pollingActionJob, webhookJob, previewJob, sendJob)
        jobs.forEach { it.cancel() }
        api?.close()
        connectingApi?.close()
        api = null
        connectingApi = null
        polling = null
        nextOffset = null
        handledUpdates.clear()
        update { it.copy(tokenInput = "", tokenVisible = false, bot = null, isConnected = false, isConnecting = false, isDisconnecting = true, isPolling = false, isPollingTransition = false, webhookUrl = null, webhookDialogVisible = false, isWebhookRemoving = false, status = "disconnecting", sendPreview = null, isPreviewing = false, isSending = false, commandSyncStatus = "idle", lastSuccessfulRequest = null, draft = if (uncertainSend) pendingSend.text else it.draft, draftRecipient = if (uncertainSend) pendingSend.chat else it.draftRecipient, draftDelivery = if (uncertainSend) "unknown" else it.draftDelivery, sendError = if (uncertainSend) unknownDelivery else it.sendError, connectionError = null, webhookError = null, pollingError = null, commandError = null, isChecking = false, checks = ConnectionChecks(), lastSuccessAt = null) }
        scope.launch {
            jobs.forEach { it.cancelAndJoin() }
            oldPolling?.stop()
            if (generation == session) {
                update { it.copy(isDisconnecting = false, status = "disconnected") }
                addEvent(ConsoleEventType.CONNECTION, ConsoleText("Бот отключён; токен удалён из памяти", "Bot disconnected; token cleared from memory"))
                if (uncertainSend) addEvent(ConsoleEventType.SEND, unknownDelivery, isError = true)
            }
        }
    }

    fun synchronizeCommands() {
        val client = api ?: return
        if (state.value.commandSyncStatus == "syncing" || state.value.isChecking || state.value.isDisconnecting) return
        val generation = session
        val language = state.value.language
        update { it.copy(commandSyncStatus = "syncing", commandError = null) }
        commandJob = scope.launch {
            try {
                client.setMyCommands(language)
                if (generation == session) {
                    update { it.copy(commandSyncStatus = "synced", lastSuccessfulRequest = now(), lastSuccessAt = clock()) }
                    addEvent(ConsoleEventType.COMMANDS, commandSyncText("synced"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) {
                    update { it.copy(commandSyncStatus = "failed", commandError = consoleError(error)) }
                    addEvent(ConsoleEventType.COMMANDS, consoleError(error), isError = true)
                }
            }
        }
    }

    fun startPolling() {
        if (!canStartPolling(state.value)) return
        val client = api ?: return
        if (!foreground) return update { it.copy(pollingError = ConsoleText("Получение обновлений доступно на переднем плане", "Polling is available while the app is in the foreground")) }
        if (!state.value.webhookUrl.isNullOrBlank()) return update { it.copy(webhookDialogVisible = true) }
        val generation = session
        val controller = polling ?: PollingController(scope).also { polling = it }
        update { it.copy(isPollingTransition = true, status = "starting", pollingError = null) }
        pollingActionJob = scope.launch {
            val started = controller.start(
                initialOffset = nextOffset,
                source = { offset -> client.getUpdates(offset) },
                onUpdates = { updates ->
                    if (generation == session) {
                        if (updates.isNotEmpty()) nextOffset = updates.maxOf { it.updateId } + 1
                        updates.forEach { handleUpdate(it, client, generation) }
                    }
                },
                onStatus = { status ->
                    if (generation == session) {
                        val previous = state.value.status
                        update { it.copy(status = if (!foreground) "background" else status, isPolling = controller.isRunning(), lastSuccessfulRequest = if (status == "connected") now() else it.lastSuccessfulRequest, lastSuccessAt = if (status == "connected") clock() else it.lastSuccessAt, pollingError = if (status in setOf("offline", "retrying", "conflict", "authentication_failed", "permission_denied", "error")) statusText(status) else null) }
                        if (previous != status && status in setOf("offline", "retrying", "conflict", "authentication_failed", "permission_denied", "error")) addEvent(ConsoleEventType.POLLING, statusText(status), isError = true)
                    }
                },
            )
            if (generation != session) return@launch
            if (!foreground) {
                controller.stop()
                nextOffset = controller.currentOffset() ?: nextOffset
            }
            update { it.copy(isPollingTransition = false, isPolling = controller.isRunning(), status = if (!foreground) "background" else it.status) }
            if (started) addEvent(ConsoleEventType.POLLING, ConsoleText("Получение обновлений запущено", "Polling started"))
        }
    }

    fun stopPolling() = pausePolling(background = false)

    private fun pausePolling(background: Boolean) {
        val controller = polling ?: return
        if (!state.value.isPolling && !state.value.isPollingTransition) {
            if (background) update { it.copy(status = "background") }
            return
        }
        val generation = session
        val previousAction = pollingActionJob
        update { it.copy(isPollingTransition = true) }
        pollingActionJob = scope.launch {
            previousAction?.join()
            controller.stop()
            if (generation != session) return@launch
            nextOffset = controller.currentOffset() ?: nextOffset
            update { it.copy(isPolling = false, isPollingTransition = false, status = if (!foreground || background) "background" else "ready") }
            addEvent(ConsoleEventType.POLLING, if (background) ConsoleText("Получение обновлений приостановлено в фоне", "Polling paused in background") else ConsoleText("Получение обновлений остановлено", "Polling stopped"))
        }
    }

    fun requestWebhookRemoval() {
        if (canCheckConnection(state.value)) update { it.copy(webhookDialogVisible = true) }
    }

    fun confirmWebhookRemoval() {
        val client = api ?: return
        if (!state.value.webhookDialogVisible || !canCheckConnection(state.value)) return
        val generation = session
        update { it.copy(isWebhookRemoving = true, webhookError = null) }
        webhookJob = scope.launch {
            try {
                client.deleteWebhook()
                if (generation == session) {
                    update { it.copy(webhookUrl = null, webhookDialogVisible = false, isWebhookRemoving = false, status = "ready", lastSuccessfulRequest = now(), lastSuccessAt = clock(), checks = it.checks.copy(webhook = CheckStatus.SUCCESS, checkedAt = clock(), errorCode = null, failureSource = null), webhookError = null) }
                    addEvent(ConsoleEventType.WEBHOOK, ConsoleText("Webhook удалён без сброса ожидающих обновлений", "Webhook removed without dropping pending updates"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) {
                    update { it.copy(webhookDialogVisible = false, isWebhookRemoving = false, webhookError = consoleError(error)) }
                    addEvent(ConsoleEventType.WEBHOOK, consoleError(error), isError = true)
                }
            }
        }
    }

    fun cancelWebhookRemoval() {
        if (!state.value.isWebhookRemoving) update { it.copy(webhookDialogVisible = false) }
    }

    fun setLanguage(language: UiLanguage) {
        if (state.value.language == language) return
        prefs.putString("language", language.name)
        commandJob?.cancel()
        update { current -> current.copy(language = language, welcome = if (current.welcomeCustom) current.welcome else defaultWelcome(language), commandSyncStatus = "idle") }
        synchronizeCommands()
    }

    fun setWelcome(value: String) {
        if (containsCredential(value)) {
            update { it.copy(commandError = ConsoleText("Приветствие не может содержать токен или другие секреты", "The greeting cannot contain a token or other secrets")) }
            return
        }
        prefs.putString("welcome", value)
        prefs.putBoolean("welcome_custom", true)
        update { it.copy(welcome = value, welcomeCustom = true) }
    }

    fun resetWelcome() {
        prefs.remove("welcome")
        prefs.putBoolean("welcome_custom", false)
        update { it.copy(welcome = defaultWelcome(it.language), welcomeCustom = false) }
    }

    fun setEcho(enabled: Boolean) {
        prefs.putBoolean("echo", enabled)
        update { it.copy(echoEnabled = enabled) }
    }

    fun setChatInput(value: String) {
        if (!state.value.isSending && !state.value.isPreviewing) update { it.copy(chatInput = value, sendPreview = null, sendError = null, verifiedRecipient = null) }
    }

    fun setMessageInput(value: String) {
        if (!state.value.isSending && !state.value.isPreviewing) update { it.copy(messageInput = value, sendPreview = null, sendError = null) }
    }

    fun previewSend() {
        if (state.value.isChecking || state.value.isConnecting || state.value.isDisconnecting || state.value.isWebhookRemoving || state.value.isPreviewing || state.value.isSending || state.value.sendPreview != null) return
        val client = api ?: return
        val text = state.value.messageInput
        validateTelegramText(text)?.let { return setSendError(localizedTextValidation(it)) }
        val destination = state.value.chatInput.trim()
        validateRecipient(destination)?.let { return setSendError(it) }
        val generation = session
        update { it.copy(isPreviewing = true, sendError = null) }
        previewJob = scope.launch {
            try {
                val chat = client.getChat(destination)
                client.verifySendRights(chat, state.value.bot?.id ?: return@launch)
                if (generation == session) update { it.copy(sendPreview = SendPreview(chat, text), verifiedRecipient = chat, isPreviewing = false, lastSuccessfulRequest = now(), lastSuccessAt = clock(), sendError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) {
                    update { it.copy(isPreviewing = false, sendError = consoleError(error)) }
                    addEvent(ConsoleEventType.SEND, consoleError(error), isError = true)
                }
            }
        }
    }

    fun cancelSend() {
        if (!state.value.isSending) update { it.copy(sendPreview = null) }
    }

    fun confirmSend() {
        if (state.value.isSending || state.value.isChecking || state.value.isDisconnecting || state.value.isWebhookRemoving) return
        val client = api ?: return
        val snapshot = state.value.sendPreview ?: return
        val generation = session
        update { it.copy(isSending = true, sendError = null) }
        sendJob = scope.launch {
            // A preview can remain open while Telegram permissions change.
            // Failure before sendMessage is a known non-delivery, not an uncertain send.
            try {
                if (snapshot.chat.type != "private") {
                    val fresh = client.getChat(snapshot.chat.id.toString())
                    if (fresh.id != snapshot.chat.id) throw IllegalStateException("Recipient changed")
                    client.verifySendRights(fresh, state.value.bot?.id ?: return@launch)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == session) retainFailedSend(snapshot, "not_sent", ConsoleText("Не удалось подтвердить права. Сообщение не отправлено; черновик сохранён.", "Could not confirm permissions. Message was not sent; draft saved."))
                return@launch
            }
            if (generation != session) return@launch
            try {
                val id = client.sendMessage(snapshot.chat.id, snapshot.text)
                if (generation == session) {
                    update { it.copy(counters = it.counters.copy(sent = it.counters.sent + 1)) }
                    update { it.copy(sendPreview = null, isSending = false, draft = null, draftRecipient = null, draftDelivery = null, lastSuccessfulRequest = now(), lastSuccessAt = clock(), sendError = null) }
                    addEvent(ConsoleEventType.SEND, ConsoleText("Сообщение отправлено, ID $id", "Message sent, ID $id"))
                }
            } catch (cancelled: CancellationException) {
                if (generation == session) retainFailedSend(snapshot, "unknown", unknownDelivery)
                throw cancelled
            } catch (error: TelegramApiException) {
                if (generation == session) {
                    if (error.errorCode >= 500) retainFailedSend(snapshot, "unknown", unknownDelivery)
                    else {
                        val reason = consoleError(error)
                        retainFailedSend(snapshot, "rejected", ConsoleText("Telegram отклонил отправку. Черновик сохранён. ${reason.ru}", "Telegram rejected the send. Draft saved. ${reason.en}"))
                    }
                }
            } catch (_: Exception) {
                if (generation == session) retainFailedSend(snapshot, "unknown", unknownDelivery)
            }
        }
    }

    private fun retainFailedSend(snapshot: SendPreview, delivery: String, error: ConsoleText) {
        update { it.copy(sendPreview = null, isSending = false, draft = snapshot.text, draftRecipient = snapshot.chat, draftDelivery = delivery, sendError = error) }
        addEvent(ConsoleEventType.SEND, error, isError = true)
    }

    fun restoreDraft() {
        if (!state.value.isSending && !state.value.isPreviewing) state.value.draft?.let { draft -> update { it.copy(messageInput = draft, chatInput = it.draftRecipient?.id?.toString() ?: it.chatInput, sendPreview = null) } }
    }

    fun clearEvents() = update { it.copy(events = emptyList()) }
    fun checkUpdates() = update { it.copy(versionCheckRequested = true) }


    fun onBackground() {
        foreground = false
        if (state.value.isConnected) pausePolling(background = true)
    }

    fun onForeground() {
        foreground = true
        update { if (it.status == "background") it.copy(status = if (!it.webhookUrl.isNullOrBlank()) "webhook" else if (it.checks.webhook == CheckStatus.SUCCESS) "ready" else "error") else it }
    }

    private suspend fun handleUpdate(update: TelegramUpdate, client: TelegramApiClient, generation: Long) {
        if (generation != session || !handledUpdates.add(update.updateId)) return
        if (handledUpdates.size > 1_000) handledUpdates.remove(handledUpdates.first())
        update.myChatMember?.let { membership ->
            val membershipStatus = membershipText(membership.status)
            addEvent(ConsoleEventType.CONNECTION, ConsoleText("Статус бота в чате ${membership.chatId}: ${membershipStatus.ru}", "Bot status in chat ${membership.chatId}: ${membershipStatus.en}"))
            return
        }
        val message = update.message ?: update.editedMessage ?: update.channelPost ?: update.editedChannelPost ?: return
        update { it.copy(counters = it.counters.copy(received = it.counters.received + 1)) }
        val acceptedCommand = receivedCommand(update, state.value.bot?.username)
        val summary = acceptedCommand?.let { "/${it.name}" } ?: redactEventText(message.text.orEmpty()).take(80)
        val detail = if (summary.isNotBlank()) ConsoleText("${chatTypeText(message.chatType).ru}: $summary", "${chatTypeText(message.chatType).en}: $summary") else ConsoleText("${chatTypeText(message.chatType).ru}: медиа или служебное сообщение", "${chatTypeText(message.chatType).en}: media or service message")
        val type = when {
            update.editedMessage != null || update.editedChannelPost != null -> ConsoleEventType.EDIT
            update.channelPost != null -> ConsoleEventType.CHANNEL
            else -> ConsoleEventType.UPDATE
        }
        if (acceptedCommand != null) addEvent(ConsoleEventType.RECEIVED_COMMAND, ConsoleText("Принята /${acceptedCommand.name}", "Received /${acceptedCommand.name}"))
        else addEvent(type, detail)
        // Edited messages and channel posts are observable, but never trigger replies.
        if (update.message == null || message.fromIsBot) return
        val command = acceptedCommand
        val answer = if (command != null) when (command.name) {
            "start" -> state.value.welcome
            "help" -> ConsoleText("Команды: /start, /help, /ping, /id, /version", "Commands: /start, /help, /ping, /id, /version").text(state.value.language)
            "ping" -> "pong"
            "id" -> "chat_id: ${message.chatId}"
            "version" -> "Hunmeng Console $DISPLAY_VERSION"
            else -> return
        } else if (state.value.echoEnabled && shouldEchoMessage(message)) message.text ?: return else return
        validateTelegramText(answer)?.let {
            addEvent(ConsoleEventType.ERROR, localizedTextValidation(it))
            return
        }
        try {
            client.sendMessage(message.chatId, answer)
            if (generation == session) {
                update { it.copy(counters = it.counters.copy(replied = it.counters.replied + 1)) }
                update { it.copy(lastSuccessfulRequest = now(), lastSuccessAt = clock()) }
                if (command != null) addEvent(ConsoleEventType.REPLY, ConsoleText("Ответ на /${command.name} отправлен", "Reply to /${command.name} sent"))
                else addEvent(ConsoleEventType.ECHO, ConsoleText("Эхо отправлено", "Echo sent"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == session) addEvent(ConsoleEventType.ERROR, consoleError(error))
        }
    }

    private fun addEvent(type: ConsoleEventType, detail: ConsoleText, isError: Boolean = type == ConsoleEventType.ERROR) {
        val safeDetail = ConsoleText(redactEventText(detail.ru), redactEventText(detail.en))
        update { it.copy(events = (listOf(ConsoleEventRecord(now(), type, safeDetail, isError)) + it.events).take(150), counters = if (isError) it.counters.copy(errors = it.counters.errors + 1) else it.counters) }
    }

    private fun setSendError(error: ConsoleText) = update { it.copy(sendError = error) }
    internal var onSavedToolsChanged: ((AccountTools) -> Unit)? = null
    private fun update(transform: (ConsoleUiState) -> ConsoleUiState) {
        val before = _state.value
        val after = transform(before)
        _state.value = after
        if (after.toolsAccountId != null && (before.templates != after.templates || before.favorites != after.favorites))
            onSavedToolsChanged?.invoke(AccountTools(after.toolsAccountId, after.templates, after.favorites))
    }
    internal fun bindAccountTools(tools: AccountTools) {
        tools.validate()
        _state.value = _state.value.copy(toolsAccountId = tools.accountId, templates = tools.templates, favorites = tools.favorites)
    }
    internal fun toolsStorageError() { update { it.copy(toolsError = ConsoleText("Не удалось сохранить или прочитать инструменты аккаунта", "Could not save or read account tools")) } }
    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    private fun botDisplay(bot: BotUser): String = bot.username?.let { "@$it" } ?: bot.firstName

    fun selectTab(tab: ConsoleTab) = update { it.copy(selectedTab = tab) }
    fun toggleReplies() = update { it.copy(repliesExpanded = !it.repliesExpanded) }
    fun setEventQuery(query: String) = update { it.copy(eventQuery = query) }
    fun setEventFilter(filter: EventFilter) = update { it.copy(eventFilter = filter) }
    fun setThemeMode(mode: ThemeMode) {
        prefs.putString("theme", mode.name)
        update { it.copy(themeMode = mode) }
    }

    // Until a server-verified account is bound, explicitly saved tools remain in memory.
    // Persisting account-scoped tools is handled by the authenticated account repository.
    fun saveTemplate(title: String, replaceId: String? = null) {
        if (!validTemplate(title, state.value.messageInput)) {
            update { it.copy(toolsError = ConsoleText("Введите название и текст без секретов", "Enter a title and text without secrets")) }
            return
        }
        val existing = state.value.templates
        if (replaceId != null && existing.none { it.id == replaceId }) return
        if (replaceId == null && existing.size >= 20) {
            update { it.copy(toolsError = ConsoleText("Можно сохранить до 20 шаблонов", "You can save up to 20 templates")) }; return
        }
        val template = MessageTemplate(replaceId ?: java.util.UUID.randomUUID().toString(), title.trim(), state.value.messageInput)
        update { it.copy(templates = if (replaceId == null) it.templates + template else it.templates.map { saved -> if (saved.id == replaceId) template else saved }, toolsError = null) }
    }
    fun insertTemplate(id: String) { state.value.templates.firstOrNull { it.id == id }?.let { setMessageInput(it.text) } }
    fun deleteTemplate(id: String) = update { it.copy(templates = it.templates.filterNot { saved -> saved.id == id }) }
    fun saveFavorite() {
        val bot = state.value.bot ?: return
        val chat = state.value.verifiedRecipient ?: return
        if (containsCredential(chat.title)) return
        val favorites = state.value.favorites.filterNot { it.botId == bot.id && it.chat.id == chat.id }
        if (favorites.size >= 20) return update { it.copy(toolsError = ConsoleText("Можно сохранить до 20 чатов", "You can save up to 20 chats")) }
        update { it.copy(favorites = favorites + FavoriteRecipient(bot.id, chat), toolsError = null) }
    }
    fun selectFavorite(chatId: Long) {
        val favorite = state.value.favorites.firstOrNull { it.botId == state.value.bot?.id && it.chat.id == chatId } ?: return
        setChatInput(favorite.chat.id.toString())
    }
    fun deleteFavorite(chatId: Long) = update { it.copy(favorites = it.favorites.filterNot { saved -> saved.botId == it.bot?.id && saved.chat.id == chatId }) }
    private fun markSuccess(instant: Instant) = update { it.copy(lastSuccessAt = instant, lastSuccessfulRequest = now()) }

    fun close(preserveAccountTools: Boolean = false) {
        val tools = if (preserveAccountTools) state.value else null
        val oldPolling = polling
        session++
        listOfNotNull(connectJob, commandJob, pollingActionJob, webhookJob, previewJob, sendJob).forEach { it.cancel() }
        scope.launch { oldPolling?.stop() }
        api?.close()
        connectingApi?.close()
        api = null
        connectingApi = null
        polling = null
        nextOffset = null
        handledUpdates.clear()
        _state.value = ConsoleUiState(language = state.value.language, themeMode = state.value.themeMode, welcome = defaultWelcome(state.value.language), toolsAccountId = tools?.toolsAccountId, templates = tools?.templates ?: emptyList(), favorites = tools?.favorites ?: emptyList())
    }

    fun clearAccountData() {
        listOf("welcome", "welcome_custom", "echo").forEach(prefs::remove)
        close()
    }

    /** The account controller passes a card from its verified inventory, never an external ID. */
    fun selectOwnedBot(bot: OwnedBot) {
        val templates = state.value.templates
        val favorites = state.value.favorites
        val accountId = state.value.toolsAccountId
        close()
        update { it.copy(selectedOwnedBot = bot, templates = templates, favorites = favorites, toolsAccountId = accountId, welcome = if (prefs.getBoolean("welcome_custom", false)) prefs.getString("welcome", "") ?: "" else defaultWelcome(it.language), welcomeCustom = prefs.getBoolean("welcome_custom", false), echoEnabled = prefs.getBoolean("echo", false)) }
    }

    companion object {
        private val unknownDelivery = ConsoleText(
            "Доставка неизвестна. Черновик сохранён в памяти; автоматического повтора не будет. Проверьте чат перед повторной отправкой.",
            "Delivery is unknown. Draft saved in memory; there will be no automatic retry. Check the chat before sending again.",
        )
    }
}


private fun chatTypeText(type: String): ConsoleText = when (type) {
    "private" -> ConsoleText("личный чат", "private chat")
    "group" -> ConsoleText("группа", "group")
    "supergroup" -> ConsoleText("супергруппа", "supergroup")
    "channel" -> ConsoleText("канал", "channel")
    else -> ConsoleText("чат", "chat")
}

private fun membershipText(status: String): ConsoleText = when (status) {
    "creator" -> ConsoleText("владелец", "owner")
    "administrator" -> ConsoleText("администратор", "administrator")
    "member" -> ConsoleText("участник", "member")
    "restricted" -> ConsoleText("ограничен", "restricted")
    "left" -> ConsoleText("вышел", "left")
    "kicked" -> ConsoleText("заблокирован", "banned")
    else -> ConsoleText("неизвестен", "unknown")
}
