package chat.hunmeng.console

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AccountAppScreen(vm: ConsoleViewModel, console: ConsoleUiState) {
    val state by vm.accounts.state.collectAsStateWithLifecycle()
    val session by vm.accountSession.state.collectAsStateWithLifecycle()
    val login by vm.login.state.collectAsStateWithLifecycle()
    val phase by vm.telegramClient.phase.collectAsStateWithLifecycle()
    val qr by vm.telegramClient.qrLink.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shortWindow = LocalConfiguration.current.screenHeightDp < 480
    val keyboardVisible = WindowInsets.isImeVisible
    val standaloneLogin = state.destination == AppDestination.MY_BOTS && session.phase == AccountPhase.SIGNED_OUT
    val scope = rememberCoroutineScope()
    val ru = console.language == UiLanguage.RU
    fun t(ru: String, en: String) = if (console.language == UiLanguage.RU) ru else en
    var settings by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var loginLaunchFailed by remember { mutableStateOf(false) }
    SideEffect {
        // Authorization links and entered client secrets cannot be captured by other apps.
        (context as? ComponentActivity)?.window?.let { window ->
            if (session.clientPhase == AccountClientPhase.CONNECTING || login.phase == LoginPhase.WAITING) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            if (!keyboardVisible) ConsoleHeader(console.language,
                authorization = state.destination != AppDestination.CONSOLE && session.phase == AccountPhase.SIGNED_OUT,
                onTheme = { settings = true }, onLanguage = vm.console::setLanguage,
                onBack = { vm.accounts.destination(AppDestination.CONSOLE) },
                onDestination = if (shortWindow) vm.accounts::destination else null)
        },
        bottomBar = {
            if (!standaloneLogin && !shortWindow && !keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(selected = state.destination == destination, onClick = { vm.accounts.destination(destination) },
                        icon = { Icon(painterResource(when (destination) { AppDestination.MY_BOTS -> R.drawable.ic_bot; AppDestination.CONSOLE -> R.drawable.ic_console; AppDestination.PROFILE -> R.drawable.ic_profile }), null) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        label = { Text(when (destination) { AppDestination.MY_BOTS -> t("Мои боты", "My bots"); AppDestination.CONSOLE -> t("Консоль", "Console"); AppDestination.PROFILE -> t("Профиль", "Profile") }) })
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (state.destination) {
                AppDestination.CONSOLE -> ConsoleScreen(console, vm.console, update, vm.updates::check)
                AppDestination.MY_BOTS, AppDestination.PROFILE -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.destination == AppDestination.PROFILE) AccountCard(t("Обновления приложения", "App updates")) {
                        Text(t("Установлено: ", "Installed: ") + DISPLAY_VERSION)
                        OutlinedButton(onClick = vm.updates::check, enabled = update.phase !in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) { Text(t("Проверить обновления", "Check for updates")) }
                        if (update.phase == UpdatePhase.CHECKING) CircularProgressIndicator()
                        update.error?.let { Text(it.text(console.language), color = MaterialTheme.colorScheme.error) }
                    }
                    when (session.phase) {
                        AccountPhase.SIGNED_OUT -> {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                LoginBrand()
                                Text(t("Войти через Telegram", "Log in with Telegram"), style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                Text(t("Подтвердите вход своим Telegram-аккаунтом на официальной странице Telegram.", "Confirm sign in with your Telegram account on Telegram's official page."),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                Button(onClick = {
                                    loginLaunchFailed = false
                                    scope.launch {
                                        val url = vm.login.begin() ?: return@launch
                                        try { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
                                        catch (_: ActivityNotFoundException) { loginLaunchFailed = true; vm.login.cancel() }
                                    }
                                }, enabled = login.phase !in setOf(LoginPhase.PREPARING, LoginPhase.VERIFYING, LoginPhase.WAITING),
                                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                    Icon(painterResource(R.drawable.ic_plane), null); Spacer(Modifier.width(8.dp)); Text(t("Войти через Telegram", "Log in with Telegram"))
                                }
                                Text(t("Мы получаем только имя, username и ID аккаунта. Подтверждение входа выполняется в Telegram.", "We receive only your name, username and account ID. Sign in is confirmed in Telegram."),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MyBotsNotice(console.language)
                                if (login.phase in setOf(LoginPhase.PREPARING, LoginPhase.VERIFYING)) { CircularProgressIndicator(); Text(t("Проверяем вход…", "Verifying sign in…")) }
                                if (login.phase == LoginPhase.WAITING) {
                                    Text(t("Ожидаем подтверждения. Если вы закрыли страницу, отмените попытку и начните заново.", "Waiting for confirmation. If you closed the page, cancel and try again."))
                                    OutlinedButton(onClick = vm::cancelLogin) { Text(t("Отменить вход", "Cancel sign in")) }
                                }
                                if (login.phase == LoginPhase.UNCONFIGURED) Text(t("Вход ещё не настроен для этой сборки. Требуется настройка приложения в BotFather и сервера Hunmeng Console.", "Sign in is not configured for this build. Hunmeng Console's BotFather application and server must be configured."), color = MaterialTheme.colorScheme.error)
                                if (login.phase == LoginPhase.ERROR || loginLaunchFailed) Text(t("Не удалось войти. Проверьте сеть и попробуйте снова.", "Could not sign in. Check the network and try again."), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { vm.accounts.destination(AppDestination.CONSOLE) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                    Text(t("Вернуться в консоль", "Back to console"))
                                }
                            }
                        }
                        AccountPhase.RESTORING -> AccountCard(t("Проверка аккаунта", "Verifying account")) { CircularProgressIndicator(); Text(t("Восстанавливаем зашифрованную сессию…", "Restoring the encrypted session…")) }
                        AccountPhase.UNAVAILABLE -> AccountCard(t("Аккаунт недоступен", "Account unavailable")) {
                            Text(t("Не удалось подтвердить сохранённый вход. Список ботов закрыт до проверки.", "The saved sign in could not be verified. The bot list is unavailable until verification."))
                            Button(onClick = vm::retryAccountRestore) { Text(t("Повторить проверку", "Retry verification")) }
                            OutlinedButton(onClick = { logout = true }) { Text(t("Удалить локальную сессию", "Delete local session")) }
                        }
                        AccountPhase.SIGNING_OUT -> AccountCard(t("Выход", "Signing out")) { CircularProgressIndicator(); Text(t("Закрываем запросы и удаляем локальные данные…", "Closing requests and removing local data…")) }
                        AccountPhase.CLEANUP_REQUIRED -> AccountCard(t("Нужно завершить выход", "Finish signing out")) {
                            Text(t("Удаление локальных данных не подтверждено. Вход и список ботов заблокированы.", "Local deletion could not be confirmed. Sign in and bot inventory are blocked."))
                            Button(onClick = vm::logoutAccount) { Text(t("Повторить удаление", "Retry deletion")) }
                        }
                        AccountPhase.VERIFIED -> {
                            val profile = checkNotNull(session.account)
                            AccountProfileHeader(profile, console.language)
                            if (state.destination == AppDestination.PROFILE) {
                                AccountCard(t("Хранение и выход", "Storage and sign out")) {
                                    Text(t("Сессия аккаунта хранится на телефоне зашифрованной и исключена из резервного копирования. Токены ботов остаются только в памяти.", "The account session is stored encrypted on this phone and excluded from backups. Bot tokens stay in memory only."))
                                    Button(onClick = { logout = true }) { Text(t("Выйти из аккаунта", "Sign out")) }
                                }
                            } else if (session.clientPhase != AccountClientPhase.READY) {
                                ClientConsentScreen(vm, session, phase, qr, console.language)
                            } else {
                                BotInventoryScreen(vm.accounts, state, console.language)
                            }
                        }
                    }
                    if (session.remoteRevocationUnconfirmed) Text(t("Локальный выход выполнен. Удалённый отзыв не подтверждён: проверьте активные сеансы в Telegram.", "Local sign out completed. Remote revocation was not confirmed: check active Telegram sessions."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text(t("Оформление", "Appearance")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode -> FilterChip(selected = console.themeMode == mode, onClick = { vm.console.setThemeMode(mode) }, label = { Text(when (mode) { ThemeMode.SYSTEM -> t("Системная тема", "System theme"); ThemeMode.LIGHT -> t("Светлая тема", "Light theme"); ThemeMode.DARK -> t("Тёмная тема", "Dark theme") }) }) }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text(t("Готово", "Done")) } })
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text(t("Выйти из аккаунта?", "Sign out?")) }, text = { Text(t("Будут остановлены запросы и удалена зашифрованная сессия на телефоне. Приложение не удаляет сообщения в Telegram.", "Requests will stop and the encrypted session on this phone will be deleted. Telegram messages are retained.")) }, confirmButton = { TextButton(onClick = { logout = false; vm.logoutAccount() }) { Text(t("Выйти", "Sign out")) } }, dismissButton = { TextButton(onClick = { logout = false }) { Text(t("Отмена", "Cancel")) } })
    AndroidUpdateDialogs(vm.updates, console.language)
}

@Composable
private fun ClientConsentScreen(vm: ConsoleViewModel, session: AccountSessionState, phase: String, qr: String?, language: UiLanguage) {
    fun t(ru: String, en: String) = if (language == UiLanguage.RU) ru else en
    var id by remember { mutableStateOf("") }; var hash by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    AccountCard(t("Подключить список моих ботов", "Connect my bot inventory")) {
        Text(t("Вход подтвердил профиль. Для списка созданных вами ботов нужен отдельный Telegram-клиент. Он проверяет ваш аккаунт, получает ваших ботов и проверяет их роли в доступных вам каналах и группах.", "Sign in verified your profile. Your owned bot list requires a separate Telegram client. It verifies your account, retrieves your owned bots and checks their roles in channels and groups available to you."))
        Text(t("Приложение не хранит историю сообщений и не отправляет сообщения от личного аккаунта.", "The app does not retain message history or send messages from your personal account."), style = MaterialTheme.typography.bodySmall)
        if (session.clientPhase == AccountClientPhase.CONNECTING) {
            CircularProgressIndicator()
            Text(when (phase) { "authorizationStateWaitOtherDeviceConfirmation" -> t("Подтвердите отдельный вход в Telegram.", "Confirm the separate sign in in Telegram."); "authorizationStateWaitPassword" -> t("Telegram запросил пароль двухэтапной проверки.", "Telegram requested your two step verification password."); else -> t("Подключаем Telegram-клиент…", "Connecting Telegram client…") })
            qr?.let { link -> Button(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: ActivityNotFoundException) { error = true } }) { Text(t("Подтвердить в Telegram", "Confirm in Telegram")) } }
            if (phase == "authorizationStateWaitPassword") {
                OutlinedTextField(password, { password = it }, label = { Text(t("Пароль Telegram", "Telegram password")) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { val value = password; password = ""; scope.launch { try { vm.telegramClient.confirmPassword(value); error = false } catch (_: Exception) { error = true } } }, enabled = password.isNotBlank()) { Text(t("Подтвердить пароль", "Confirm password")) }
            }
            OutlinedButton(onClick = { password = ""; vm.accountSession.cancelClientConnection() }) { Text(t("Отменить подключение", "Cancel connection")) }
        } else if (session.clientPhase == AccountClientPhase.CLEANUP_REQUIRED) {
            Text(t("Telegram-клиент не закрылся. Завершите выход через профиль, прежде чем подключаться снова.", "Telegram client did not close. Sign out in Profile before connecting again."), color = MaterialTheme.colorScheme.error)
        } else {
            if (session.hasClientConfiguration) Button(onClick = vm::resumeAccountClient) { Text(t("Продолжить подключение", "Continue connecting")) }
            Text(t("API ID и API Hash берутся из вашего приложения на my.telegram.org. Они сохраняются только в зашифрованной аккаунтной сессии.", "Get API ID and API Hash from your application on my.telegram.org. They are saved only in the encrypted account session."), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(id, { id = it.filter(Char::isDigit).take(10) }, label = { Text("API ID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(hash, { hash = it.take(32) }, label = { Text("API Hash") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { val apiHash = hash; val apiId = id; hash = ""; id = ""; vm.connectAccountClient(apiId, apiHash) }, enabled = id.toIntOrNull()?.let { it > 0 } == true && Regex("[a-fA-F0-9]{32}").matches(hash), modifier = Modifier.fillMaxWidth()) { Text(t("Разрешить подключение", "Allow connection")) }
        }
        if (session.clientPhase == AccountClientPhase.ERROR || error) Text(t("Подключение не подтверждено. Проверьте сеть, данные API-приложения и выбранный аккаунт Telegram.", "Connection was not verified. Check the network, API application and selected Telegram account."), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BotInventoryScreen(controller: AccountController, state: AccountUiState, language: UiLanguage) {
    fun t(ru: String, en: String) = if (language == UiLanguage.RU) ru else en
    val bot = state.selectedBot
    if (bot == null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t("Мои боты", "My bots"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(t("Боты, созданные вашим аккаунтом. Источник: Telegram getOwnedBots.", "Bots created by your account. Source: Telegram getOwnedBots."), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(state.query, controller::query, label = { Text(t("Найти бота", "Find a bot")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) }, shape = MaterialTheme.shapes.medium)
        InventoryStateText(state.inventory.phase, language)
        val filtered = state.inventory.bots.filter { it.bot.firstName.contains(state.query, true) || it.bot.username.orEmpty().contains(state.query.removePrefix("@"), true) }
        if (state.inventory.phase == InventoryPhase.READY && filtered.isEmpty() && state.inventory.bots.isNotEmpty()) Text(t("По этому запросу ботов нет", "No bots match this search"))
        filtered.forEach { owned -> key(owned.bot.id) {
            OwnedBotCard(owned.bot.firstName, owned.bot.username, t("Создан вашим аккаунтом", "Created by your account"),
                enabled = state.inventory.phase == InventoryPhase.READY, onClick = { controller.selectBot(owned.bot.id) })
        } }
        Button(onClick = controller::refreshBots, enabled = state.inventory.phase != InventoryPhase.LOADING,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
            Icon(painterResource(R.drawable.ic_refresh), null); Spacer(Modifier.width(8.dp)); Text(t("Обновить список", "Refresh list"))
        }
        state.inventory.checkedAt?.let { Text(t("Проверено: ", "Checked: ") + it, style = MaterialTheme.typography.bodySmall) }
        state.retryAfter?.let { Text(t("Telegram просит подождать ${it} с перед повтором.", "Telegram asks you to wait ${it}s before retrying.")) }
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = controller::backToBots) {
            Icon(painterResource(R.drawable.ic_back), null); Spacer(Modifier.width(8.dp)); Text(t("Назад к ботам", "Back to bots"))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_bot), null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).size(36.dp))
            }
            Text(bot.bot.username?.let { "@$it" } ?: bot.bot.firstName, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text(t("Доступные каналы и группы", "Available channels and groups"), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text(t("Показаны чаты, доступные вашему аккаунту, где удалось проверить присутствие бота. Недоступные чужие чаты сюда не входят.", "Shows chats available to your account where the bot's membership could be verified. Inaccessible chats are outside this list."), style = MaterialTheme.typography.bodySmall)
        InventoryStateText(state.chatsPhase, language)
        state.chats?.let { chats ->
            for ((type, title) in listOf(true to t("Каналы", "Channels"), false to t("Группы", "Groups"))) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val items = chats.items.filter { (it.chat.type == "channel") == type }
                if (items.isEmpty()) Text(t("Проверенных чатов нет", "No verified chats"))
                items.forEach { item ->
                    val role = when (item.botRole) { "chatMemberStatusCreator" -> t("владелец", "owner"); "chatMemberStatusAdministrator" -> t("администратор", "administrator"); "chatMemberStatusRestricted" -> t("ограничен", "restricted"); else -> t("участник", "member") }
                    fun right(value: Boolean?) = when (value) { true -> t("да", "yes"); false -> t("нет", "no"); null -> t("не установлено", "unknown") }
                    key(item.chat.id) {
                        ChatRoleCard(item.chat.title, t("Роль бота: ", "Bot role: ") + role, channel = type,
                            permissions = t("Публикация текста: ${right(item.canPost)}; редактирование чужих сообщений: ${right(item.canEdit)}; удаление чужих сообщений: ${right(item.canDelete)}", "Post text: ${right(item.canPost)}; edit others' messages: ${right(item.canEdit)}; delete others' messages: ${right(item.canDelete)}"))
                    }
                }
            }
            Text(t("Проверено: ", "Checked: ") + chats.checkedAt, style = MaterialTheme.typography.bodySmall)
            if (chats.inaccessibleCount > 0 || !chats.scanComplete) Text(t("Список неполный: часть чатов недоступна или сканирование ограничено.", "The list is incomplete: some chats are inaccessible or scanning was limited."))
        }
        OutlinedButton(onClick = { controller.selectBot(bot.bot.id) }, enabled = state.chatsPhase != InventoryPhase.LOADING) { Text(t("Обновить чаты", "Refresh chats")) }
        Button(onClick = controller::openConsole, enabled = state.inventory.phase == InventoryPhase.READY, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium) { Icon(painterResource(R.drawable.ic_console), null); Spacer(Modifier.width(8.dp)); Text(t("Открыть консоль бота", "Open bot console")) }
        Text(t("Для подключения консоли потребуется токен выбранного бота. Он не подтверждает владение и хранится только в памяти.", "Connecting the console requires the selected bot's token. It does not prove ownership and stays in memory only."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InventoryStateText(phase: InventoryPhase, language: UiLanguage) {
    fun t(ru: String, en: String) = if (language == UiLanguage.RU) ru else en
    when (phase) {
        InventoryPhase.LOADING -> { CircularProgressIndicator(); Text(t("Загружаем и проверяем…", "Loading and verifying…")) }
        InventoryPhase.EMPTY -> Text(t("Проверка завершена: список пуст", "Verified: the list is empty"))
        InventoryPhase.UNCONNECTED -> Text(t("Источник ещё не подключён", "Source is not connected"))
        InventoryPhase.NO_ACCESS -> Text(t("Telegram не разрешил доступ", "Telegram denied access"), color = MaterialTheme.colorScheme.error)
        InventoryPhase.ERROR -> Text(t("Не удалось проверить список. Повторите запрос.", "Could not verify the list. Try again."), color = MaterialTheme.colorScheme.error)
        InventoryPhase.STALE -> Text(t("Данные устарели. Обновите список перед выбором.", "Data is stale. Refresh before selecting."))
        InventoryPhase.READY -> Unit
    }
}

@Composable
private fun AccountCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); content() }
    }
}
