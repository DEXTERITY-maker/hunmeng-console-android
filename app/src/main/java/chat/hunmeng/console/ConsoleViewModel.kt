package chat.hunmeng.console

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ConsoleViewModel(application: Application) : AndroidViewModel(application), DefaultLifecycleObserver {
    private val lifecycle get() = ProcessLifecycleOwner.get().lifecycle
    private val preferences = application.getSharedPreferences("hunmeng_console", Context.MODE_PRIVATE)
    val console = ConsoleController(object : ConsolePreferences {
        override fun getString(key: String, fallback: String) = preferences.getString(key, fallback)
        override fun getBoolean(key: String, fallback: Boolean) = preferences.getBoolean(key, fallback)
        override fun putString(key: String, value: String) { preferences.edit().putString(key, value).apply() }
        override fun putBoolean(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }
        override fun remove(key: String) { preferences.edit().remove(key).apply() }
    }, viewModelScope, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    private val gateway = TelegramLoginGateway()
    internal val telegramClient = TdLibAccountSession(application, ProcessTelegramClient.runtime, viewModelScope)
    internal val accountSession = AccountSessionCoordinator(AndroidAccountSessionStore(application), gateway, telegramClient) {
        console.clearAccountData()
    }
    internal val accounts = AccountController(accountSession, viewModelScope, telegramClient::inventory, console)
    internal val login = LoginFlow(gateway, AndroidPendingLoginStore(application), { record ->
        accountSession.acceptLogin(record)
        if (accountSession.state.value.phase != AccountPhase.VERIFIED) throw LoginFailure()
    })
    init {
        lifecycle.addObserver(this)
        viewModelScope.launch { accountSession.restore() }
        viewModelScope.launch { login.restore() }
    }
    fun loginCallback(value: String) { viewModelScope.launch { try { login.callback(value) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } } }
    fun cancelLogin() { viewModelScope.launch { login.cancel() } }
    fun connectAccountClient(apiId: String, apiHash: String) {
        val config = try { TelegramApiApplication(apiId.toInt(), apiHash.trim()) } catch (_: Exception) { return }
        viewModelScope.launch { accountSession.connectClient(config) }
    }
    fun resumeAccountClient() { viewModelScope.launch { accountSession.connectClient() } }
    fun retryAccountRestore() { viewModelScope.launch { accountSession.restore() } }
    fun logoutAccount() {
        console.clearAccountData()
        accounts.clear()
        viewModelScope.launch { login.cancel(); accountSession.logout() }
    }
    override fun onStop(owner: LifecycleOwner) { console.onBackground() }
    override fun onStart(owner: LifecycleOwner) { console.onForeground(); accounts.onForeground() }
    override fun onCleared() {
        lifecycle.removeObserver(this)
        console.close()
        super.onCleared()
    }
}
