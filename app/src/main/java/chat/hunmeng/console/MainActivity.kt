package chat.hunmeng.console

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ConsoleViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Credentials live only in the ViewModel; Android autofill must not retain them.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent { HunmengConsole(viewModel) }
    }
}

@Composable
private fun HunmengConsole(viewModel: ConsoleViewModel) {
    val state by viewModel.console.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = Color(0xFF1769AA), background = Color(0xFFF5F7FB))) {
        ConsoleScreen(state, viewModel.console)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleScreen(state: ConsoleUiState, vm: ConsoleController) {
    val context = LocalContext.current
    val ru = state.language == UiLanguage.RU
    fun t(ruText: String, enText: String) = if (ru) ruText else enText
    val connectionBusy = state.isConnecting || state.isDisconnecting || state.isChecking
    val sendBusy = state.isPreviewing || state.isSending || state.isChecking || state.isWebhookRemoving || state.isDisconnecting
    var reportFallback by remember { mutableStateOf<String?>(null) }
    val botScroll = rememberScrollState()
    val messageScroll = rememberScrollState()
    val eventsScroll = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hunmeng Console", style = MaterialTheme.typography.titleMedium)
                        Text(DISPLAY_VERSION, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    FilterChip(selected = ru, onClick = { vm.setLanguage(UiLanguage.RU) }, label = { Text("RU") })
                    Spacer(Modifier.padding(3.dp))
                    FilterChip(selected = !ru, onClick = { vm.setLanguage(UiLanguage.EN) }, label = { Text("EN") })
                    Spacer(Modifier.padding(6.dp))
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = vm::startPolling, enabled = canStartPolling(state), modifier = Modifier.weight(1f)) {
                            Text(t("Запустить", "Start"))
                        }
                        OutlinedButton(onClick = vm::stopPolling, enabled = state.isPolling && !state.isPollingTransition, modifier = Modifier.weight(1f)) {
                            Text(t("Остановить", "Stop"))
                        }
                    }
                    Text(statusText(state.status).text(state.language), style = MaterialTheme.typography.labelSmall)
                    state.pollingError?.let { Text(it.text(state.language), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        containerColor = Color(0xFFF5F7FB),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            ConsoleTab.entries.forEach { tab ->
                Tab(selected = state.selectedTab == tab, onClick = { vm.selectTab(tab) }, text = { Text(tab.label.text(state.language)) })
            }
        }
        val scroll = when (state.selectedTab) { ConsoleTab.BOT -> botScroll; ConsoleTab.MESSAGE -> messageScroll; ConsoleTab.EVENTS -> eventsScroll }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.selectedTab == ConsoleTab.BOT) {
            Text(t("Управление Telegram-ботом", "Telegram bot management"), style = MaterialTheme.typography.titleMedium)
            Text(t("Подключи своего бота, настрой ответы и отправляй сообщения.", "Connect your bot, configure replies and send messages."), style = MaterialTheme.typography.bodySmall)
            SectionCard(t("Подключение", "Connection")) {
                OutlinedTextField(
                    value = state.tokenInput,
                    onValueChange = vm::setTokenInput,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !connectionBusy && !state.isConnected,
                    label = { Text(t("Токен бота", "Bot token")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    visualTransformation = if (state.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = vm::toggleTokenVisibility, enabled = !state.isConnected) { Text(if (state.tokenVisible) t("Скрыть", "Hide") else t("Показать", "Show")) } },
                )
                TextButton(onClick = {
                    if (!tryOpenLink(BOT_FATHER_URL) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }) {
                        Toast.makeText(context, t("Не найдено приложение для открытия @BotFather", "No application can open @BotFather"), Toast.LENGTH_SHORT).show()
                    }
                }) { Text("@BotFather") }
                Text(t("Токен хранится только в памяти и удаляется при отключении.", "The token is kept only in memory and cleared on disconnect."), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::connect, enabled = !state.isConnected && !connectionBusy) { Text(if (state.isConnecting) t("Подключение…", "Connecting…") else t("Подключить", "Connect")) }
                    OutlinedButton(onClick = vm::disconnect, enabled = (state.isConnected || state.isConnecting) && !state.isDisconnecting) { Text(t("Отключить", "Disconnect")) }
                }
                Text(statusText(state.status).text(state.language), color = if (state.status in setOf("error", "authentication_failed", "conflict")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                state.bot?.let { bot -> Text(t("Бот: ", "Bot: ") + (bot.username?.let { "@$it" } ?: bot.firstName), style = MaterialTheme.typography.bodyMedium) }
                state.lastSuccessfulRequest?.let { Text(t("Последний успешный запрос: ", "Last successful request: ") + it, style = MaterialTheme.typography.bodySmall) }
                state.webhookUrl?.takeIf { it.isNotBlank() }?.let {
                    Text(t("Webhook активен: получение обновлений недоступно", "Webhook is active: polling is unavailable"), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::requestWebhookRemoval, enabled = !state.isWebhookRemoving) { Text(t("Удалить webhook…", "Remove webhook…")) }
                }
                state.connectionError?.let { Text(it.text(state.language), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard(t("Проверка подключения", "Connection checks")) {
                val rows = listOf(
                    ConsoleText("Консоль", "Console") to state.checks.console,
                    ConsoleText("Telegram", "Telegram") to state.checks.telegram,
                    ConsoleText("Токен", "Token") to state.checks.authorization,
                    ConsoleText("Webhook", "Webhook") to state.checks.webhook,
                )
                rows.forEach { (name, check) ->
                    Text("${check.symbol}  ${name.text(state.language)} — ${check.label.text(state.language)}",
                        color = if (check == CheckStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text(t("Консоль — локальная готовность приложения. Авторизация подтверждается ответом Telegram, а не форматом токена.", "Console means local app readiness. Authorization is confirmed by Telegram, not by the token format."), style = MaterialTheme.typography.bodySmall)
                state.webhookError?.let { Text(it.text(state.language), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = vm::retryConnectionCheck, enabled = canCheckConnection(state) && (state.isConnected || state.tokenInput.isNotBlank())) { Text(t("Повторить проверку", "Repeat checks")) }
                OutlinedButton(onClick = {
                    val report = connectionReport(state, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
                    if (report != null) {
                        val fallback = reportCopyFallback(report) { writeClipboard(context, "Hunmeng Console report", it) }
                        if (fallback == null) {
                            Toast.makeText(context, t("Отчёт скопирован", "Report copied"), Toast.LENGTH_SHORT).show()
                        } else reportFallback = fallback
                    }
                }, enabled = state.checks.checkedAt != null && !state.isChecking) { Text(t("Скопировать отчёт", "Copy report")) }
            }

            SectionCard(t("Получение сообщений", "Message receiving")) {
                Text(t("Получение обновлений работает, пока приложение открыто. В фоне оно приостанавливается; после возвращения нажми «Запустить».", "Updates are received while the app is open. Polling pauses in the background; tap Start when you return."), style = MaterialTheme.typography.bodySmall)
                Text(t("Поворот экрана сохраняет текущую сессию. Второй цикл получения обновлений не создаётся.", "Rotating the screen preserves the current session. It does not create a second polling loop."), style = MaterialTheme.typography.bodySmall)
                Text(t("Статус: ", "Status: ") + statusText(state.status).text(state.language), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(t("Команды и ответы", "Commands and replies")) {
                TextButton(onClick = vm::toggleReplies) { Text(if (state.repliesExpanded) t("Свернуть ▲", "Collapse ▲") else t("Развернуть ▼", "Expand ▼")) }
                state.commandError?.let { Text(it.text(state.language), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.repliesExpanded) {
                OutlinedTextField(value = state.welcome, onValueChange = vm::setWelcome, modifier = Modifier.fillMaxWidth(), label = { Text(t("Приветствие /start", "/start greeting")) }, minLines = 2)
                OutlinedButton(onClick = vm::resetWelcome) { Text(t("Стандартное приветствие", "Default greeting")) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("Эхо личных сообщений", "Echo private messages"), modifier = Modifier.weight(1f).padding(top = 14.dp))
                    Switch(checked = state.echoEnabled, onCheckedChange = vm::setEcho)
                }
                Text(t("/start, /help, /ping, /id, /version", "/start, /help, /ping, /id, /version"), style = MaterialTheme.typography.bodySmall)
                Text(commandSyncText(state.commandSyncStatus).text(state.language), color = if (state.commandSyncStatus == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::synchronizeCommands, enabled = state.isConnected && state.commandSyncStatus != "syncing" && !state.isChecking && !state.isDisconnecting) { Text(t("Синхронизировать меню", "Synchronize menu")) }
                Text(t("Меню обновляется при подключении и смене языка. Эхо работает только в личных чатах, для обычного текста.", "The menu updates on connection and language change. Echo works only for ordinary text in private chats."), style = MaterialTheme.typography.bodySmall)
            }

                }
            }
            if (state.selectedTab == ConsoleTab.MESSAGE) {
            SectionCard(t("Ручная отправка", "Manual send")) {
                OutlinedTextField(value = state.chatInput, onValueChange = vm::setChatInput, enabled = !sendBusy, modifier = Modifier.fillMaxWidth(), label = { Text(t("ID чата или @username", "Chat ID or @username")) }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                OutlinedTextField(value = state.messageInput, onValueChange = vm::setMessageInput, enabled = !sendBusy, modifier = Modifier.fillMaxWidth(), label = { Text(t("Сообщение (1–4096 символов)", "Message (1–4096 characters)")) }, minLines = 3)
                Text("${countTelegramCharacters(state.messageInput)}/4096", style = MaterialTheme.typography.labelSmall)
                Button(onClick = vm::previewSend, enabled = state.isConnected && !sendBusy) { Text(if (state.isPreviewing) t("Проверяем…", "Checking…") else t("Проверить получателя", "Preview recipient")) }
                state.sendError?.let { Text(it.text(state.language), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.draft?.let {
                    Text(if (state.draftDelivery == "unknown") t("Доставка неизвестна. Черновик остался в памяти. Проверь чат перед повторной отправкой.", "Delivery is unknown. The draft remains in memory. Check the chat before sending again.") else t("Telegram отклонил отправку. Черновик остался в памяти.", "Telegram rejected the send. The draft remains in memory."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::restoreDraft, enabled = !sendBusy) { Text(t("Вернуть черновик в поле", "Restore draft to field")) }
                    state.draftRecipient?.let { recipient ->
                        Text("${recipient.title} · ID ${recipient.id}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { openDraftChat(context, recipient, ru) }) { Text(if (recipient.username != null) t("Открыть чат", "Open chat") else t("Открыть Telegram", "Open Telegram")) }
                    }
                }
            }

            }
            if (state.selectedTab == ConsoleTab.EVENTS) {
            SectionCard(t("События", "Events")) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("В памяти: ${state.events.size}/150", "In memory: ${state.events.size}/150"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::clearEvents) { Text(t("Очистить", "Clear")) }
                }
                OutlinedTextField(value = state.eventQuery, onValueChange = vm::setEventQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(t("Поиск событий", "Search events")) })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EventFilter.entries.forEach { filter ->
                        FilterChip(selected = state.eventFilter == filter, onClick = { vm.setEventFilter(filter) }, label = { Text(filter.label.text(state.language)) })
                    }
                }
                val events = visibleEvents(state.events, state.eventQuery, state.eventFilter, state.language)
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF17202A), RoundedCornerShape(8.dp)).padding(10.dp)) {
                    if (events.isEmpty()) {
                        Text(if (state.events.isEmpty()) t("Событий пока нет", "No events yet") else t("По этому запросу событий нет", "No events match this search"), color = Color.LightGray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(events) { event ->
                                Column {
                                    Text(eventDisplayText(event, state.language), color = if (event.isError) Color(0xFFFFB4AB) else Color(0xFFE6EDF3), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                    TextButton(onClick = {
                                        val copied = tryCopyText(eventDisplayText(event, state.language)) { writeClipboard(context, "Hunmeng Console event", it) }
                                        Toast.makeText(context, if (copied) t("Событие скопировано", "Event copied") else t("Не удалось скопировать событие", "Could not copy the event"), Toast.LENGTH_SHORT).show()
                                    }) { Text(t("Копировать", "Copy"), color = Color(0xFFB9DCFF)) }
                                }
                            }
                        }
                    }
                }
                Text(t("Журнал хранится только в памяти. Поиск и фильтры не удаляют записи. Копия события содержит отображаемый текст и не является отчётом диагностики.", "The log is memory-only. Search and filters keep the original entries. An event copy includes displayed text and is not a diagnostic report."), style = MaterialTheme.typography.bodySmall)
            }
            }

            if (state.selectedTab == ConsoleTab.BOT) {
            SectionCard(t("Обновление", "Updates")) {
                Text(t("Что изменилось в $DISPLAY_VERSION", "Changes in $DISPLAY_VERSION"), style = MaterialTheme.typography.titleSmall)
                Text(t("Новый значок приложения: белый робот-терминал на синем фоне. Подготовлены изображения для разных плотностей экрана и адаптивная иконка Android.", "New application icon: a white terminal robot on a blue background. Includes density-specific images and an Android adaptive icon."), style = MaterialTheme.typography.bodySmall)
                Text(t("В этой сборке: пошаговая диагностика, безопасный отчёт, поиск и фильтры событий с копированием, вкладки и ссылка @BotFather.", "In this build: step-by-step checks, a safe report, event search and filters with copying, tabs and the @BotFather link."), style = MaterialTheme.typography.bodySmall)
                Text(t("История версий: $DISPLAY_VERSION — пользовательская иконка; v0.0.3beta — первая Android-сборка.", "Version history: $DISPLAY_VERSION — custom application icon; v0.0.3beta — first Android build."), style = MaterialTheme.typography.bodySmall)
                Text(updateSourceUnavailable.text(state.language), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::checkUpdates) { Text(t("Проверить обновления", "Check for updates")) }
                if (state.versionCheckRequested) Text(t("Проверка не выполнена: источник обновлений не задан.", "Check not performed: no update source is configured."), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(t("Версия", "Version")) {
                Text("Hunmeng Console $DISPLAY_VERSION", style = MaterialTheme.typography.titleMedium)
                Text(t("Версия Android-приложения: 0.0.4-beta", "Android application version: 0.0.4-beta"), style = MaterialTheme.typography.bodySmall)
                Text(t("Я могу подключить бота, получать сообщения, отвечать на команды, включать эхо и отправлять текст после твоего подтверждения.", "I can connect a bot, receive messages, answer commands, enable echo, and send text after your confirmation."), style = MaterialTheme.typography.bodySmall)
                Text(t("Для отправки бот должен иметь доступ к выбранному чату.", "The bot must have access to the selected chat to send messages."), style = MaterialTheme.typography.bodySmall)
            }
            }
        }
        }
    }

    reportFallback?.let { report ->
        AlertDialog(
            onDismissRequest = { reportFallback = null },
            title = { Text(t("Безопасный отчёт", "Safe report")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Буфер обмена недоступен. Выделите текст и скопируйте вручную.", "Clipboard unavailable. Select the text and copy it manually."))
                    SelectionContainer { Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { reportFallback = null }) { Text(t("Закрыть", "Close")) } },
        )
    }

    if (state.webhookDialogVisible) {
        AlertDialog(
            onDismissRequest = vm::cancelWebhookRemoval,
            title = { Text(t("Удалить webhook?", "Remove webhook?")) },
            text = { Text(t("Это позволит получать обновления в приложении. Ожидающие обновления сохранятся.", "This enables receiving updates in the app. Pending updates will be kept.")) },
            confirmButton = { Button(onClick = vm::confirmWebhookRemoval, enabled = !state.isWebhookRemoving) { Text(if (state.isWebhookRemoving) t("Удаление…", "Removing…") else t("Удалить", "Remove")) } },
            dismissButton = { TextButton(onClick = vm::cancelWebhookRemoval, enabled = !state.isWebhookRemoving) { Text(t("Отмена", "Cancel")) } },
        )
    }
    state.sendPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = vm::cancelSend,
            title = { Text(t("Подтвердить отправку", "Confirm send")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Получатель: ", "Recipient: ") + preview.chat.title)
                    Text("ID: ${preview.chat.id}")
                    Text(t("Тип: ", "Type: ") + chatTypeLabel(preview.chat.type, ru))
                    Text(t("Имя пользователя: ", "Username: ") + (preview.chat.username?.let { "@$it" } ?: t("не указан", "not set")))
                    HorizontalDivider()
                    Text(preview.text)
                }
            },
            confirmButton = { Button(onClick = vm::confirmSend, enabled = !state.isSending) { Text(if (state.isSending) t("Отправка…", "Sending…") else t("Отправить", "Send")) } },
            dismissButton = { TextButton(onClick = vm::cancelSend, enabled = !state.isSending) { Text(t("Отмена", "Cancel")) } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

private fun chatTypeLabel(type: String, ru: Boolean): String = when (type) {
    "private" -> if (ru) "личный чат" else "private chat"
    "group" -> if (ru) "группа" else "group"
    "supergroup" -> if (ru) "супергруппа" else "supergroup"
    "channel" -> if (ru) "канал" else "channel"
    else -> if (ru) "неизвестен" else "unknown"
}

private fun openDraftChat(context: Context, chat: ChatPreview, ru: Boolean) {
    val target = chat.username?.let { "https://t.me/${Uri.encode(it)}" } ?: "https://t.me/"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, if (ru) "Не найдено приложение для открытия Telegram" else "No application can open Telegram", Toast.LENGTH_SHORT).show()
    }
}

private fun writeClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: error("Clipboard unavailable")
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
