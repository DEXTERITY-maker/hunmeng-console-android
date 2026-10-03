package chat.hunmeng.console

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val error: ConsoleText? = null,
)

class ConsoleViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val prefs = application.getSharedPreferences("hunmeng_console", Context.MODE_PRIVATE)
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
    private val processLifecycle get() = ProcessLifecycleOwner.get().lifecycle
    private var foreground = processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private val handledUpdates = LinkedHashSet<Long>()

    init { processLifecycle.addObserver(this) }

    private fun loadState(): ConsoleUiState {
        val language = if (prefs.getString("language", "RU") == "EN") UiLanguage.EN else UiLanguage.RU
        val custom = prefs.getBoolean("welcome_custom", false)
        return ConsoleUiState(
            language = language,
            // An old saved default must not override the chosen language.
            welcome = if (custom) prefs.getString("welcome", "") ?: "" else defaultWelcome(language),
            welcomeCustom = custom,
            echoEnabled = prefs.getBoolean("echo", false),
        )
    }

    fun setTokenInput(value: String) {
        if (!state.value.isConnecting && !state.value.isConnected && !state.value.isDisconnecting) update { it.copy(tokenInput = value, error = null) }
    }

    fun toggleTokenVisibility() = update { it.copy(tokenVisible = !it.tokenVisible) }

    fun connect() {
        if (state.value.isConnecting || state.value.isConnected || state.value.isDisconnecting) return
        val token = state.value.tokenInput.trim()
        if (token.isBlank()) return setError(ConsoleText("Введите токен бота", "Enter the bot token"))
        val generation = ++session
        update { it.copy(isConnecting = true, status = "connecting", error = null) }
        connectJob = viewModelScope.launch {
            val client = TelegramApiClient(token)
            connectingApi = client
            var retained = false
            try {
                val bot = client.getMe()
                val webhook = client.getWebhookInfo()
                if (generation != session) return@launch
                api = client
                connectingApi = null
                retained = true
                polling = PollingController(viewModelScope)
                nextOffset = null
                handledUpdates.clear()
                update { it.copy(tokenInput = "", tokenVisible = false, bot = bot, isConnected = true, isConnecting = false, status = if (webhook.isNullOrBlank()) "ready" else "webhook", webhookUrl = webhook, lastSuccessfulRequest = now(), error = null) }
                addEvent(ConsoleEventType.CONNECTION, ConsoleText("Подключён бот ${botDisplay(bot)}", "Connected bot ${botDisplay(bot)}"))
                synchronizeCommands()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) {
                    update { it.copy(isConnecting = false, status = "error", error = consoleError(error)) }
                    addEvent(ConsoleEventType.ERROR, consoleError(error))
                }
            } finally {
                if (!retained) client.close()
                if (connectingApi === client) connectingApi = null
                if (generation == session && state.value.isConnecting) update { it.copy(isConnecting = false) }
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
        update { it.copy(tokenInput = "", tokenVisible = false, bot = null, isConnected = false, isConnecting = false, isDisconnecting = true, isPolling = false, isPollingTransition = false, webhookUrl = null, webhookDialogVisible = false, isWebhookRemoving = false, status = "disconnecting", sendPreview = null, isPreviewing = false, isSending = false, commandSyncStatus = "idle", lastSuccessfulRequest = null, draft = if (uncertainSend) pendingSend.text else it.draft, draftRecipient = if (uncertainSend) pendingSend.chat else it.draftRecipient, draftDelivery = if (uncertainSend) "unknown" else it.draftDelivery, error = if (uncertainSend) unknownDelivery else null) }
        viewModelScope.launch {
            jobs.forEach { it.cancelAndJoin() }
            oldPolling?.stop()
            if (generation == session) {
                update { it.copy(isDisconnecting = false, status = "disconnected") }
                addEvent(ConsoleEventType.CONNECTION, ConsoleText("Бот отключён; токен удалён из памяти", "Bot disconnected; token cleared from memory"))
                if (uncertainSend) addEvent(ConsoleEventType.SEND, unknownDelivery)
            }
        }
    }

    fun synchronizeCommands() {
        val client = api ?: return
        if (state.value.commandSyncStatus == "syncing") return
        val generation = session
        val language = state.value.language
        update { it.copy(commandSyncStatus = "syncing") }
        commandJob = viewModelScope.launch {
            try {
                client.setMyCommands(language)
                if (generation == session) {
                    update { it.copy(commandSyncStatus = "synced", lastSuccessfulRequest = now()) }
                    addEvent(ConsoleEventType.COMMANDS, commandSyncText("synced"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) {
                    update { it.copy(commandSyncStatus = "failed") }
                    addEvent(ConsoleEventType.COMMANDS, consoleError(error))
                }
            }
        }
    }

    fun startPolling() {
        if (state.value.isPolling || state.value.isPollingTransition || state.value.isDisconnecting) return
        val client = api ?: return setError(ConsoleText("Сначала подключите бота", "Connect a bot first"))
        if (!foreground) return setError(ConsoleText("Получение обновлений доступно на переднем плане", "Polling is available while the app is in the foreground"))
        if (!state.value.webhookUrl.isNullOrBlank()) return update { it.copy(webhookDialogVisible = true) }
        val generation = session
        val controller = polling ?: PollingController(viewModelScope).also { polling = it }
        update { it.copy(isPollingTransition = true, status = "starting", error = null) }
        pollingActionJob = viewModelScope.launch {
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
                        update { it.copy(status = if (!foreground) "background" else status, isPolling = controller.isRunning(), lastSuccessfulRequest = if (status == "connected") now() else it.lastSuccessfulRequest) }
                        if (previous != status && status in setOf("offline", "retrying", "conflict", "authentication_failed", "error")) addEvent(ConsoleEventType.POLLING, statusText(status))
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
        pollingActionJob = viewModelScope.launch {
            previousAction?.join()
            controller.stop()
            if (generation != session) return@launch
            nextOffset = controller.currentOffset() ?: nextOffset
            update { it.copy(isPolling = false, isPollingTransition = false, status = if (!foreground || background) "background" else "ready") }
            addEvent(ConsoleEventType.POLLING, if (background) ConsoleText("Получение обновлений приостановлено в фоне", "Polling paused in background") else ConsoleText("Получение обновлений остановлено", "Polling stopped"))
        }
    }

    fun requestWebhookRemoval() {
        if (!state.value.isWebhookRemoving) update { it.copy(webhookDialogVisible = true) }
    }

    fun confirmWebhookRemoval() {
        val client = api ?: return
        if (state.value.isWebhookRemoving) return
        val generation = session
        update { it.copy(isWebhookRemoving = true) }
        webhookJob = viewModelScope.launch {
            try {
                client.deleteWebhook()
                if (generation == session) {
                    update { it.copy(webhookUrl = null, webhookDialogVisible = false, isWebhookRemoving = false, status = "ready", lastSuccessfulRequest = now(), error = null) }
                    addEvent(ConsoleEventType.WEBHOOK, ConsoleText("Webhook удалён без сброса ожидающих обновлений", "Webhook removed without dropping pending updates"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) update { it.copy(webhookDialogVisible = false, isWebhookRemoving = false, error = consoleError(error)) }
            }
        }
    }

    fun cancelWebhookRemoval() {
        if (!state.value.isWebhookRemoving) update { it.copy(webhookDialogVisible = false) }
    }

    fun setLanguage(language: UiLanguage) {
        if (state.value.language == language) return
        prefs.edit().putString("language", language.name).apply()
        commandJob?.cancel()
        update { current -> current.copy(language = language, welcome = if (current.welcomeCustom) current.welcome else defaultWelcome(language), commandSyncStatus = "idle") }
        synchronizeCommands()
    }

    fun setWelcome(value: String) {
        prefs.edit().putString("welcome", value).putBoolean("welcome_custom", true).apply()
        update { it.copy(welcome = value, welcomeCustom = true) }
    }

    fun resetWelcome() {
        prefs.edit().remove("welcome").putBoolean("welcome_custom", false).apply()
        update { it.copy(welcome = defaultWelcome(it.language), welcomeCustom = false) }
    }

    fun setEcho(enabled: Boolean) {
        prefs.edit().putBoolean("echo", enabled).apply()
        update { it.copy(echoEnabled = enabled) }
    }

    fun setChatInput(value: String) {
        if (!state.value.isSending && !state.value.isPreviewing) update { it.copy(chatInput = value, sendPreview = null, error = null) }
    }

    fun setMessageInput(value: String) {
        if (!state.value.isSending && !state.value.isPreviewing) update { it.copy(messageInput = value, sendPreview = null, error = null) }
    }

    fun previewSend() {
        if (state.value.isPreviewing || state.value.isSending || state.value.sendPreview != null) return
        val client = api ?: return setError(ConsoleText("Сначала подключите бота", "Connect a bot first"))
        val text = state.value.messageInput
        validateTelegramText(text)?.let { return setError(localizedTextValidation(it)) }
        val destination = state.value.chatInput.trim()
        validateRecipient(destination)?.let { return setError(it) }
        val generation = session
        update { it.copy(isPreviewing = true, error = null) }
        previewJob = viewModelScope.launch {
            try {
                val chat = client.getChat(destination)
                if (generation == session) update { it.copy(sendPreview = SendPreview(chat, text), isPreviewing = false, lastSuccessfulRequest = now(), error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == session) update { it.copy(isPreviewing = false, error = consoleError(error)) }
            }
        }
    }

    fun cancelSend() {
        if (!state.value.isSending) update { it.copy(sendPreview = null) }
    }

    fun confirmSend() {
        if (state.value.isSending) return
        val client = api ?: return
        val snapshot = state.value.sendPreview ?: return
        val generation = session
        update { it.copy(isSending = true, error = null) }
        sendJob = viewModelScope.launch {
            try {
                val id = client.sendMessage(snapshot.chat.id, snapshot.text)
                if (generation == session) {
                    update { it.copy(sendPreview = null, isSending = false, draft = null, draftRecipient = null, draftDelivery = null, lastSuccessfulRequest = now(), error = null) }
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
        update { it.copy(sendPreview = null, isSending = false, draft = snapshot.text, draftRecipient = snapshot.chat, draftDelivery = delivery, error = error) }
        addEvent(ConsoleEventType.SEND, error)
    }

    fun restoreDraft() {
        if (!state.value.isSending && !state.value.isPreviewing) state.value.draft?.let { draft -> update { it.copy(messageInput = draft, chatInput = it.draftRecipient?.id?.toString() ?: it.chatInput, sendPreview = null) } }
    }

    fun clearEvents() = update { it.copy(events = emptyList()) }
    fun checkUpdates() = update { it.copy(versionCheckRequested = true) }

    override fun onStop(owner: LifecycleOwner) { onBackground() }
    override fun onStart(owner: LifecycleOwner) { onForeground() }

    fun onBackground() {
        foreground = false
        if (state.value.isConnected) pausePolling(background = true)
    }

    fun onForeground() {
        foreground = true
        update { if (it.status == "background") it.copy(status = if (!it.webhookUrl.isNullOrBlank()) "webhook" else "ready") else it }
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
        val summary = redactEventText(message.text.orEmpty()).take(80)
        val detail = if (summary.isNotBlank()) ConsoleText("${chatTypeText(message.chatType).ru}: $summary", "${chatTypeText(message.chatType).en}: $summary") else ConsoleText("${chatTypeText(message.chatType).ru}: медиа или служебное сообщение", "${chatTypeText(message.chatType).en}: media or service message")
        val type = when {
            update.editedMessage != null || update.editedChannelPost != null -> ConsoleEventType.EDIT
            update.channelPost != null -> ConsoleEventType.CHANNEL
            else -> ConsoleEventType.UPDATE
        }
        addEvent(type, detail)
        // Edited messages and channel posts are observable, but never trigger replies.
        if (update.message == null || message.fromIsBot) return
        val command = message.text?.let { parseCommand(it, state.value.bot?.username) }
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
                update { it.copy(lastSuccessfulRequest = now()) }
                if (command != null) addEvent(ConsoleEventType.REPLY, ConsoleText("Ответ на /${command.name} отправлен", "Reply to /${command.name} sent"))
                else addEvent(ConsoleEventType.ECHO, ConsoleText("Эхо отправлено", "Echo sent"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == session) addEvent(ConsoleEventType.ERROR, consoleError(error))
        }
    }

    private fun addEvent(type: ConsoleEventType, detail: ConsoleText) {
        val safeDetail = ConsoleText(redactEventText(detail.ru), redactEventText(detail.en))
        update { it.copy(events = (listOf(ConsoleEventRecord(now(), type, safeDetail)) + it.events).take(150)) }
    }

    private fun setError(error: ConsoleText) = update { it.copy(error = error) }
    private fun update(transform: (ConsoleUiState) -> ConsoleUiState) { _state.value = transform(_state.value) }
    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    private fun botDisplay(bot: BotUser): String = bot.username?.let { "@$it" } ?: bot.firstName

    override fun onCleared() {
        processLifecycle.removeObserver(this)
        session++
        api?.close()
        connectingApi?.close()
        api = null
        connectingApi = null
        _state.value = ConsoleUiState(language = state.value.language)
        super.onCleared()
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
