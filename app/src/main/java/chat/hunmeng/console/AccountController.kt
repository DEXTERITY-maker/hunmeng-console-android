package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

enum class AppDestination { MY_BOTS, CONSOLE, PROFILE }
data class AccountUiState(
    val destination: AppDestination = AppDestination.CONSOLE,
    val inventory: OwnedBotInventoryState = OwnedBotInventoryState(),
    val query: String = "", val selectedBot: OwnedBot? = null,
    val chatsPhase: InventoryPhase = InventoryPhase.UNCONNECTED, val chats: AccessibleBotChats? = null,
    val retryAfter: Int? = null,
)

/** Account ID + generation guards apply to both lists. Navigation never starts polling. */
internal class AccountController(
    val session: AccountSessionCoordinator,
    private val scope: CoroutineScope,
    private val source: (VerifiedAccount) -> OwnedInventorySource,
    private val console: ConsoleController,
) {
    private val _state = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()
    private var generation = 0L
    private var inventoryJob: Job? = null
    private var chatsJob: Job? = null
    private var connectedAccount: Long? = null
    init {
        scope.launch {
            session.state.collect { account ->
                val id = account.account?.telegramId.takeIf { account.phase == AccountPhase.VERIFIED }
                if (id != connectedAccount) {
                    invalidate()
                    connectedAccount = id
                    _state.value = AccountUiState(destination = if (id == null) AppDestination.CONSOLE else AppDestination.MY_BOTS)
                }
                if (account.clientPhase == AccountClientPhase.READY && _state.value.inventory.phase == InventoryPhase.UNCONNECTED) refreshBots()
                if (account.clientPhase != AccountClientPhase.READY && _state.value.inventory.phase != InventoryPhase.UNCONNECTED) {
                    invalidate()
                    _state.value = _state.value.copy(inventory = OwnedBotInventoryState(), selectedBot = null, chats = null, chatsPhase = InventoryPhase.UNCONNECTED)
                }
            }
        }
    }
    fun destination(value: AppDestination) { _state.value = _state.value.copy(destination = value) }
    fun query(value: String) { _state.value = _state.value.copy(query = value.take(200)) }
    private fun invalidate() { generation++; inventoryJob?.cancel(); chatsJob?.cancel() }
    fun clear() { invalidate(); _state.value = AccountUiState(); connectedAccount = null }
    fun refreshBots() {
        val account = session.state.value.account ?: return
        if (session.state.value.phase != AccountPhase.VERIFIED || session.state.value.clientPhase != AccountClientPhase.READY || inventoryJob?.isActive == true) return
        val lease = ++generation
        chatsJob?.cancel()
        _state.value = _state.value.copy(inventory = _state.value.inventory.copy(phase = InventoryPhase.LOADING), chatsPhase = InventoryPhase.UNCONNECTED, chats = null, retryAfter = null)
        inventoryJob = scope.launch {
            try {
                val bots = source(account).ownedBots()
                require(bots.all { it.verifiedOwnerId == account.telegramId })
                if (valid(lease, account)) {
                    val selected = _state.value.selectedBot?.let { old -> bots.firstOrNull { it.bot.id == old.bot.id } }
                    if (selected == null && _state.value.selectedBot != null) console.close(preserveAccountTools = true)
                    _state.value = _state.value.copy(inventory = OwnedBotInventoryState(if (bots.isEmpty()) InventoryPhase.EMPTY else InventoryPhase.READY, bots, Instant.now()), selectedBot = selected)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (valid(lease, account)) _state.value = _state.value.copy(inventory = _state.value.inventory.copy(phase = errorPhase(error)), retryAfter = (error as? TdLibException)?.retryAfter)
            }
        }
    }
    fun selectBot(id: Long) {
        if (_state.value.inventory.phase != InventoryPhase.READY) return
        val bot = _state.value.inventory.bots.firstOrNull { it.bot.id == id } ?: return
        val account = session.state.value.account ?: return
        require(bot.verifiedOwnerId == account.telegramId)
        chatsJob?.cancel()
        val lease = ++generation
        _state.value = _state.value.copy(selectedBot = bot, chats = null, chatsPhase = InventoryPhase.LOADING, retryAfter = null)
        chatsJob = scope.launch {
            try {
                val chats = source(account).availableChats(bot)
                if (valid(lease, account)) _state.value = _state.value.copy(chats = chats, chatsPhase = if (chats.items.isEmpty() && chats.inaccessibleCount == 0 && chats.scanComplete) InventoryPhase.EMPTY else InventoryPhase.READY)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(lease, account)) _state.value = _state.value.copy(chatsPhase = errorPhase(error), retryAfter = (error as? TdLibException)?.retryAfter) }
        }
    }
    fun backToBots() { chatsJob?.cancel(); generation++; _state.value = _state.value.copy(selectedBot = null, chats = null, chatsPhase = InventoryPhase.UNCONNECTED) }
    fun openConsole() {
        val bot = _state.value.selectedBot ?: return
        if (_state.value.inventory.phase != InventoryPhase.READY || session.state.value.clientPhase != AccountClientPhase.READY || bot.verifiedOwnerId != session.state.value.account?.telegramId) return
        if (console.state.value.selectedOwnedBot?.bot?.id != bot.bot.id) console.selectOwnedBot(bot)
        destination(AppDestination.CONSOLE)
    }
    fun onForeground() {
        val at = _state.value.inventory.checkedAt ?: return
        if (_state.value.inventory.phase in setOf(InventoryPhase.READY, InventoryPhase.EMPTY) && Instant.now().isAfter(at.plusSeconds(300))) {
            _state.value = _state.value.copy(inventory = _state.value.inventory.copy(phase = InventoryPhase.STALE), chatsPhase = InventoryPhase.STALE)
        }
    }
    private fun valid(lease: Long, account: VerifiedAccount) = lease == generation && session.state.value.phase == AccountPhase.VERIFIED && session.state.value.account?.telegramId == account.telegramId && session.state.value.clientPhase == AccountClientPhase.READY
    private fun errorPhase(error: Exception) = if (error is TdLibException && error.code in setOf(401, 403)) InventoryPhase.NO_ACCESS else InventoryPhase.ERROR
}
