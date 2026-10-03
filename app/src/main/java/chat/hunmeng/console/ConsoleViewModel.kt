package chat.hunmeng.console

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope

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
    init { lifecycle.addObserver(this) }
    override fun onStop(owner: LifecycleOwner) { console.onBackground() }
    override fun onStart(owner: LifecycleOwner) { console.onForeground() }
    override fun onCleared() {
        lifecycle.removeObserver(this)
        console.close()
        super.onCleared()
    }
}
