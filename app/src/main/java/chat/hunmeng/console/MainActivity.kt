package chat.hunmeng.console

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme(primary = Color(0xFF1769AA), background = Color(0xFFF5F7FB))) {
        ConsoleScreen(state, viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleScreen(state: ConsoleUiState, vm: ConsoleViewModel) {
    val context = LocalContext.current
    val ru = state.language == UiLanguage.RU
    fun t(ruText: String, enText: String) = if (ru) ruText else enText
    val connectionBusy = state.isConnecting || state.isDisconnecting
    val sendBusy = state.isPreviewing || state.isSending
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
                        Button(onClick = vm::startPolling, enabled = state.isConnected && !state.isPolling && !state.isPollingTransition && state.webhookUrl.isNullOrBlank(), modifier = Modifier.weight(1f)) {
                            Text(t("Запустить", "Start"))
                        }
                        OutlinedButton(onClick = vm::stopPolling, enabled = state.isPolling && !state.isPollingTransition, modifier = Modifier.weight(1f)) {
                            Text(t("Остановить", "Stop"))
                        }
                    }
                    Text(statusText(state.status).text(state.language), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        containerColor = Color(0xFFF5F7FB),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                state.error?.let { Text(it.text(state.language), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard(t("Получение сообщений", "Message receiving")) {
                Text(t("Получение обновлений работает, пока приложение открыто. В фоне оно приостанавливается; после возвращения нажми «Запустить».", "Updates are received while the app is open. Polling pauses in the background; tap Start when you return."), style = MaterialTheme.typography.bodySmall)
                Text(t("Поворот экрана сохраняет текущую сессию. Второй цикл получения обновлений не создаётся.", "Rotating the screen preserves the current session. It does not create a second polling loop."), style = MaterialTheme.typography.bodySmall)
                Text(t("Статус: ", "Status: ") + statusText(state.status).text(state.language), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(t("Команды и ответы", "Commands and replies")) {
                OutlinedTextField(value = state.welcome, onValueChange = vm::setWelcome, modifier = Modifier.fillMaxWidth(), label = { Text(t("Приветствие /start", "/start greeting")) }, minLines = 2)
                OutlinedButton(onClick = vm::resetWelcome) { Text(t("Стандартное приветствие", "Default greeting")) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("Эхо личных сообщений", "Echo private messages"), modifier = Modifier.weight(1f).padding(top = 14.dp))
                    Switch(checked = state.echoEnabled, onCheckedChange = vm::setEcho)
                }
                Text(t("/start, /help, /ping, /id, /version", "/start, /help, /ping, /id, /version"), style = MaterialTheme.typography.bodySmall)
                Text(commandSyncText(state.commandSyncStatus).text(state.language), color = if (state.commandSyncStatus == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::synchronizeCommands, enabled = state.isConnected && state.commandSyncStatus != "syncing") { Text(t("Синхронизировать меню", "Synchronize menu")) }
                Text(t("Меню обновляется при подключении и смене языка. Эхо работает только в личных чатах, для обычного текста.", "The menu updates on connection and language change. Echo works only for ordinary text in private chats."), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(t("Ручная отправка", "Manual send")) {
                OutlinedTextField(value = state.chatInput, onValueChange = vm::setChatInput, enabled = !sendBusy, modifier = Modifier.fillMaxWidth(), label = { Text(t("ID чата или @username", "Chat ID or @username")) }, singleLine = true, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                OutlinedTextField(value = state.messageInput, onValueChange = vm::setMessageInput, enabled = !sendBusy, modifier = Modifier.fillMaxWidth(), label = { Text(t("Сообщение (1–4096 символов)", "Message (1–4096 characters)")) }, minLines = 3)
                Text("${countTelegramCharacters(state.messageInput)}/4096", style = MaterialTheme.typography.labelSmall)
                Button(onClick = vm::previewSend, enabled = state.isConnected && !sendBusy) { Text(if (state.isPreviewing) t("Проверяем…", "Checking…") else t("Проверить получателя", "Preview recipient")) }
                state.draft?.let {
                    Text(if (state.draftDelivery == "unknown") t("Доставка неизвестна. Черновик остался в памяти. Проверь чат перед повторной отправкой.", "Delivery is unknown. The draft remains in memory. Check the chat before sending again.") else t("Telegram отклонил отправку. Черновик остался в памяти.", "Telegram rejected the send. The draft remains in memory."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::restoreDraft, enabled = !sendBusy) { Text(t("Вернуть черновик в поле", "Restore draft to field")) }
                    state.draftRecipient?.let { recipient ->
                        Text("${recipient.title} · ID ${recipient.id}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { openDraftChat(context, recipient, ru) }) { Text(if (recipient.username != null) t("Открыть чат", "Open chat") else t("Открыть Telegram", "Open Telegram")) }
                    }
                }
            }

            SectionCard(t("События", "Events")) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("В памяти: ${state.events.size}/150", "In memory: ${state.events.size}/150"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::clearEvents) { Text(t("Очистить", "Clear")) }
                }
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF17202A), RoundedCornerShape(8.dp)).padding(10.dp)) {
                    if (state.events.isEmpty()) {
                        Text(t("Событий пока нет", "No events yet"), color = Color.LightGray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.events) { event ->
                                Text("${event.time}  [${event.type.label.text(state.language)}] ${event.detail.text(state.language)}", color = Color(0xFFE6EDF3), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Text(t("Прокрути список, чтобы увидеть все сохранённые события. Журнал не записывается на диск.", "Scroll the list to see all retained events. The log is not written to disk."), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(t("Обновление", "Updates")) {
                Text(t("Что изменилось в $DISPLAY_VERSION", "Changes in $DISPLAY_VERSION"), style = MaterialTheme.typography.titleSmall)
                Text(t("Безопасное подключение бота, получение обновлений без второго цикла, меню команд на RU/EN и отправка точного текста после проверки получателя.", "Safe bot connection, a single polling loop, RU/EN command menus, and sending the exact text after recipient review."), style = MaterialTheme.typography.bodySmall)
                Text(t("Журнал последних 150 событий, пауза в фоне и черновик при неудачной или неизвестной доставке.", "The last 150 events, a background pause, and a draft after rejected or unknown delivery."), style = MaterialTheme.typography.bodySmall)
                Text(t("История версий: $DISPLAY_VERSION — текущая бета-версия.", "Version history: $DISPLAY_VERSION — the current beta release."), style = MaterialTheme.typography.bodySmall)
                Text(updateSourceUnavailable.text(state.language), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::checkUpdates) { Text(t("Проверить обновления", "Check for updates")) }
                if (state.versionCheckRequested) Text(t("Проверка не выполнена: источник обновлений не задан.", "Check not performed: no update source is configured."), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(t("Версия", "Version")) {
                Text("Hunmeng Console $DISPLAY_VERSION", style = MaterialTheme.typography.titleMedium)
                Text(t("Версия Android-приложения: 0.0.3-beta", "Android application version: 0.0.3-beta"), style = MaterialTheme.typography.bodySmall)
                Text(t("Я могу подключить бота, получать сообщения, отвечать на команды, включать эхо и отправлять текст после твоего подтверждения.", "I can connect a bot, receive messages, answer commands, enable echo, and send text after your confirmation."), style = MaterialTheme.typography.bodySmall)
                Text(t("Для отправки бот должен иметь доступ к выбранному чату.", "The bot must have access to the selected chat to send messages."), style = MaterialTheme.typography.bodySmall)
            }
        }
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
