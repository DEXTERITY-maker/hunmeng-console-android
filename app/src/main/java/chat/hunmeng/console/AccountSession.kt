package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class VerifiedAccount(val telegramId: Long, val displayName: String, val username: String?)

/** Deliberately not a data class: diagnostic toString must never include credentials. */
internal class TelegramApiApplication(val apiId: Int, val apiHash: String) {
    init { require(apiId > 0 && Regex("[a-fA-F0-9]{32}").matches(apiHash)) }
    override fun toString() = "TelegramApiApplication([redacted])"
}
internal class AccountSessionRecord(val accountId: Long, val serverSession: String, val databaseKey: ByteArray, val apiApplication: TelegramApiApplication? = null) {
    init {
        require(accountId in 1..9_007_199_254_740_991L)
        require(Regex("[A-Za-z0-9_-]{43}").matches(serverSession))
        require(databaseKey.size == 32)
    }
    override fun toString() = "AccountSessionRecord([redacted])"
    fun clearKey() = databaseKey.fill(0)
    fun encode(): ByteArray = JSONObject().put("version", 1).put("account_id", accountId)
        .put("server_session", serverSession).put("database_key", Base64.getEncoder().encodeToString(databaseKey))
        .put("api_application", apiApplication?.let { JSONObject().put("id", it.apiId).put("hash", it.apiHash) } ?: JSONObject.NULL)
        .toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(payload: ByteArray): AccountSessionRecord {
            require(payload.size in 1..16_384)
            val json = JSONObject(String(payload, Charsets.UTF_8))
            require(json.optInt("version") == 1 && json.opt("account_id") is Number)
            val app = json.optJSONObject("api_application")?.let { TelegramApiApplication(it.getInt("id"), it.getString("hash")) }
            return AccountSessionRecord(json.getLong("account_id"), json.getString("server_session"), Base64.getDecoder().decode(json.getString("database_key")), app)
        }
        fun new(account: VerifiedAccount, serverSession: String) = AccountSessionRecord(account.telegramId, serverSession, ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

internal interface AccountSessionStore {
    suspend fun load(): AccountSessionRecord?
    suspend fun save(session: AccountSessionRecord)
    suspend fun erase()
}

internal interface AccountSessionVerifier {
    // Stable ID comes from a server-verified Telegram identity, never a UI field.
    suspend fun verify(serverSession: String): VerifiedAccount
    suspend fun revoke(serverSession: String)
}

internal interface TelegramClientSession {
    /** Return the actual authenticated Telegram getMe user ID. */
    suspend fun resume(session: AccountSessionRecord): Long
    /** Revoke the Telegram authorization when reachable, close native client, delete its private directory. */
    suspend fun revokeCloseAndErase(): ClientSessionCleanup
    suspend fun closeWithoutErasing(): Boolean
}
internal data class ClientSessionCleanup(val localDeleted: Boolean, val remoteRevoked: Boolean)

enum class AccountPhase { SIGNED_OUT, RESTORING, VERIFIED, UNAVAILABLE, SIGNING_OUT, CLEANUP_REQUIRED }
enum class AccountClientPhase { UNCONNECTED, CONNECTING, READY, ERROR, CLEANUP_REQUIRED }
data class AccountSessionState(val phase: AccountPhase = AccountPhase.SIGNED_OUT, val account: VerifiedAccount? = null, val remoteRevocationUnconfirmed: Boolean = false, val clientPhase: AccountClientPhase = AccountClientPhase.UNCONNECTED, val hasClientConfiguration: Boolean = false)

/** Logout invalidates in-flight results before cleanup starts; persistence is serialized. */
internal class AccountSessionCoordinator(
    private val store: AccountSessionStore,
    private val verifier: AccountSessionVerifier,
    private val telegram: TelegramClientSession,
    private val clearConsoleAndRequests: () -> Unit,
) {
    private val generation = AtomicLong()
    private val lock = Mutex()
    private val operationLock = Mutex()
    private val _state = MutableStateFlow(AccountSessionState())
    val state: StateFlow<AccountSessionState> = _state.asStateFlow()
    private var current: AccountSessionRecord? = null
    private val activeOperation = AtomicReference<Job?>()

    suspend fun restore() = operationLock.withLock {
        if (_state.value.phase in setOf(AccountPhase.SIGNING_OUT, AccountPhase.CLEANUP_REQUIRED, AccountPhase.VERIFIED)) return@withLock
        val job = currentCoroutineContext()[Job]
        activeOperation.set(job)
        val lease = generation.incrementAndGet()
        _state.value = AccountSessionState(AccountPhase.RESTORING)
        try {
            val saved = lock.withLock { store.load() } ?: run {
                if (lease == generation.get()) _state.value = AccountSessionState()
                return@withLock
            }
            activate(saved, lease, persist = false)
        } catch (_: TimeoutCancellationException) {
            if (lease == generation.get()) _state.value = AccountSessionState(AccountPhase.UNAVAILABLE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (lease == generation.get()) _state.value = AccountSessionState(AccountPhase.UNAVAILABLE)
        } finally { activeOperation.compareAndSet(job, null) }
    }

    suspend fun acceptLogin(record: AccountSessionRecord) = operationLock.withLock {
        check(_state.value.phase in setOf(AccountPhase.SIGNED_OUT, AccountPhase.UNAVAILABLE))
        val job = currentCoroutineContext()[Job]
        activeOperation.set(job)
        val lease = generation.incrementAndGet()
        _state.value = AccountSessionState(AccountPhase.RESTORING)
        try { activate(record, lease, persist = true) }
        catch (_: TimeoutCancellationException) {
            if (lease == generation.get()) _state.value = AccountSessionState(AccountPhase.UNAVAILABLE)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (lease == generation.get()) _state.value = AccountSessionState(AccountPhase.UNAVAILABLE)
        } finally { activeOperation.compareAndSet(job, null) }
    }

    private suspend fun activate(record: AccountSessionRecord, lease: Long, persist: Boolean) {
        var retained = false
        try {
            val profile = withTimeout(15_000) { verifier.verify(record.serverSession) }
            require(profile.telegramId == record.accountId)
            if (lease != generation.get()) return
            lock.withLock {
                if (lease != generation.get()) return@withLock
                if (persist) store.save(record)
                if (lease != generation.get()) { store.erase(); return@withLock }
                current?.clearKey()
                current = record
                retained = true
                _state.value = AccountSessionState(AccountPhase.VERIFIED, profile, hasClientConfiguration = record.apiApplication != null)
            }
        } finally {
            if (!retained) {
                if (persist) withContext(NonCancellable) { withTimeoutOrNull(15_000) { try { verifier.revoke(record.serverSession) } catch (_: Exception) { } } }
                record.clearKey()
            }
        }
    }

    /** OIDC proves the profile. The separate client consent alone permits the inventory. */
    suspend fun connectClient(application: TelegramApiApplication? = null) = operationLock.withLock {
        check(_state.value.phase == AccountPhase.VERIFIED && _state.value.clientPhase != AccountClientPhase.READY)
        val saved = checkNotNull(current)
        val config = application ?: saved.apiApplication ?: throw IllegalStateException("Client application required")
        val job = currentCoroutineContext()[Job]
        activeOperation.set(job)
        val lease = generation.incrementAndGet()
        _state.value = _state.value.copy(clientPhase = AccountClientPhase.CONNECTING)
        var ready = false
        try {
            val profile = withTimeout(15_000) { verifier.verify(saved.serverSession) }
            require(profile.telegramId == saved.accountId)
            val updated = AccountSessionRecord(saved.accountId, saved.serverSession, saved.databaseKey.copyOf(), config)
            try {
                lock.withLock {
                    if (lease != generation.get()) return@withLock
                    store.save(updated)
                    current = updated
                    saved.clearKey()
                    _state.value = _state.value.copy(hasClientConfiguration = true)
                }
                if (lease != generation.get()) { updated.clearKey(); return@withLock }
                val id = withTimeout(300_000) { telegram.resume(updated) }
                if (id != profile.telegramId) {
                    val cleanup = withContext(NonCancellable) { telegram.revokeCloseAndErase() }
                    if (!cleanup.localDeleted) _state.value = _state.value.copy(clientPhase = AccountClientPhase.CLEANUP_REQUIRED)
                    throw IllegalStateException("Account identity mismatch")
                }
                if (lease == generation.get()) {
                    ready = true
                    _state.value = _state.value.copy(clientPhase = AccountClientPhase.READY, hasClientConfiguration = true)
                }
            } catch (error: Exception) {
                if (current !== updated) updated.clearKey()
                throw error
            }
        } catch (_: TimeoutCancellationException) {
            if (lease == generation.get() && _state.value.clientPhase != AccountClientPhase.CLEANUP_REQUIRED) _state.value = _state.value.copy(clientPhase = AccountClientPhase.ERROR)
        } catch (cancelled: CancellationException) {
            if (lease == generation.get()) _state.value = _state.value.copy(clientPhase = AccountClientPhase.UNCONNECTED)
            throw cancelled
        } catch (_: Exception) {
            if (lease == generation.get() && _state.value.clientPhase != AccountClientPhase.CLEANUP_REQUIRED) _state.value = _state.value.copy(clientPhase = AccountClientPhase.ERROR)
        } finally {
            if (!ready) withContext(NonCancellable) {
                val closed = withTimeoutOrNull(30_000) { try { telegram.closeWithoutErasing() } catch (_: Exception) { false } } ?: false
                if (!closed && lease == generation.get()) _state.value = _state.value.copy(clientPhase = AccountClientPhase.CLEANUP_REQUIRED)
            }
            activeOperation.compareAndSet(job, null)
        }
    }

    fun cancelClientConnection() { if (_state.value.clientPhase == AccountClientPhase.CONNECTING) activeOperation.get()?.cancel() }

    suspend fun logout() {
        val lease = generation.incrementAndGet()
        _state.value = AccountSessionState(AccountPhase.SIGNING_OUT)
        clearConsoleAndRequests()
        activeOperation.get()?.cancel()
        // Cancellation or a network outage must not skip local erasure.
        withContext(NonCancellable) {
            operationLock.withLock {
                var clean = true
                var serverRevoked = false
                var telegramRevoked = false
                val saved = try { lock.withLock { store.load() } } catch (_: Exception) { null }
                val credential = current?.serverSession ?: saved?.serverSession
                saved?.clearKey()
                if (credential != null) withTimeoutOrNull(15_000) {
                    try { verifier.revoke(credential); serverRevoked = true } catch (_: Exception) { /* local cleanup still runs */ }
                }
                try {
                    val result = telegram.revokeCloseAndErase()
                    clean = result.localDeleted
                    telegramRevoked = result.remoteRevoked
                } catch (_: Exception) { clean = false }
                lock.withLock {
                    current?.clearKey(); current = null
                    try { store.erase() } catch (_: Exception) { clean = false }
                }
                if (lease == generation.get()) _state.value = AccountSessionState(if (clean) AccountPhase.SIGNED_OUT else AccountPhase.CLEANUP_REQUIRED, remoteRevocationUnconfirmed = !serverRevoked || !telegramRevoked)
            }
        }
    }
}
