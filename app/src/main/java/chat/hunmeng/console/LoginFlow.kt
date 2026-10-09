package chat.hunmeng.console

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class LoginPhase { IDLE, PREPARING, WAITING, VERIFYING, ERROR, UNCONFIGURED }
data class LoginUiState(val phase: LoginPhase = LoginPhase.IDLE, val error: String? = null)
internal interface PendingLoginStore {
    suspend fun load(): LoginAttempt?
    suspend fun save(attempt: LoginAttempt)
    suspend fun erase()
}
internal class AndroidPendingLoginStore(context: Context) : PendingLoginStore {
    private val vault = EncryptedSessionVault(context, VaultPurpose.LOGIN_PENDING)
    override suspend fun load(): LoginAttempt? = withContext(Dispatchers.IO) {
        val bytes = vault.load() ?: return@withContext null
        try { LoginAttempt.decode(bytes) } finally { bytes.fill(0) }
    }
    override suspend fun save(attempt: LoginAttempt) = withContext(Dispatchers.IO) {
        val bytes = attempt.encode(); try { vault.save(bytes) } finally { bytes.fill(0) }
    }
    override suspend fun erase() = withContext(Dispatchers.IO) { vault.erase() }
}
/** Only the encrypted pending attempt survives process death; Intent data is consumed once. */
internal class LoginFlow(
    private val gateway: LoginGateway, private val store: PendingLoginStore,
    private val accept: suspend (AccountSessionRecord) -> Unit,
    private val clientId: String = BuildConfig.TELEGRAM_LOGIN_CLIENT_ID,
    private val redirectUri: String = BuildConfig.TELEGRAM_LOGIN_REDIRECT_URI,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private var attempt: LoginAttempt? = null
    private val generation = AtomicLong()
    private val active = AtomicReference<Job?>()
    suspend fun restore() = mutex.withLock {
        try {
            val saved = store.load() ?: return@withLock
            validateLoginAttempt(saved, clientId, redirectUri)
            if (saved.expiresAt <= now()) { store.erase(); return@withLock }
            attempt = saved; _state.value = LoginUiState(LoginPhase.WAITING)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { store.erase(); _state.value = LoginUiState(LoginPhase.ERROR, "invalid_pending") }
    }
    suspend fun begin(): String? = mutex.withLock {
        if (_state.value.phase in setOf(LoginPhase.PREPARING, LoginPhase.VERIFYING, LoginPhase.WAITING)) return@withLock null
        _state.value = LoginUiState(LoginPhase.PREPARING)
        val job = currentCoroutineContext()[Job]; active.set(job)
        val lease = generation.incrementAndGet()
        var fresh: LoginAttempt? = null
        var retained = false
        try {
            val binding = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            fresh = gateway.begin(binding)
            validateLoginAttempt(fresh, clientId, redirectUri)
            require(fresh.expiresAt in (now() + 1)..(now() + 360))
            if (lease != generation.get()) return@withLock null
            store.save(fresh); attempt = fresh; retained = true
            _state.value = LoginUiState(LoginPhase.WAITING)
            fresh.authorizationUrl
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            _state.value = LoginUiState(if (error is LoginFailure && error.category == "login_not_configured") LoginPhase.UNCONFIGURED else LoginPhase.ERROR, "login_failed")
            null
        } finally {
            active.compareAndSet(job, null)
            if (!retained && fresh != null) withContext(NonCancellable) { withTimeoutOrNull(15_000) { try { gateway.cancel(fresh) } catch (_: Exception) { } } }
        }
    }
    suspend fun callback(uri: String) = mutex.withLock {
        val pending = attempt ?: store.load()?.also { validateLoginAttempt(it, clientId, redirectUri) } ?: return@withLock
        val code = try { loginCallbackCode(uri, pending) } catch (_: Exception) { return@withLock }
        if (pending.expiresAt <= now()) { attempt = null; store.erase(); _state.value = LoginUiState(LoginPhase.ERROR, "login_expired"); return@withLock }
        // Erase before exchanging, so duplicate Intents and crashes cannot reuse this callback.
        attempt = null; store.erase(); _state.value = LoginUiState(LoginPhase.VERIFYING)
        val job = currentCoroutineContext()[Job]; active.set(job)
        val lease = generation.incrementAndGet()
        if (code == null) {
            try { gateway.cancel(pending) } catch (_: Exception) { } finally { active.compareAndSet(job, null) }
            _state.value = LoginUiState(); return@withLock
        }
        var result: LoginResult? = null
        try {
            result = gateway.complete(pending, code)
            if (lease != generation.get()) return@withLock
            accept(AccountSessionRecord.new(result.account, result.session))
            _state.value = LoginUiState()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.value = LoginUiState(LoginPhase.ERROR, "login_failed") }
        finally {
            active.compareAndSet(job, null)
            if (_state.value.phase != LoginPhase.IDLE && result != null) withContext(NonCancellable) { withTimeoutOrNull(15_000) { try { gateway.revoke(result.session) } catch (_: Exception) { } } }
        }
    }
    suspend fun cancel() {
        generation.incrementAndGet()
        active.get()?.cancel()
        mutex.withLock {
        val pending = attempt ?: store.load()
        attempt = null; store.erase(); _state.value = LoginUiState()
        if (pending != null) try { gateway.cancel(pending) } catch (_: Exception) { }
        }
    }
}
