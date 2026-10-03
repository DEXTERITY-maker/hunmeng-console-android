package chat.hunmeng.console

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

data class VerifiedAccount(val telegramId: Long, val displayName: String, val username: String?)

/** Deliberately not a data class: diagnostic toString must never include credentials. */
internal class AccountSessionRecord(val accountId: Long, val serverSession: String, val databaseKey: ByteArray) {
    init {
        require(accountId in 1..9_007_199_254_740_991L)
        require(serverSession.length in 32..4096 && !containsCredential(serverSession))
        require(databaseKey.size == 32)
    }
    override fun toString() = "AccountSessionRecord([redacted])"
    fun clearKey() = databaseKey.fill(0)
    fun encode(): ByteArray = JSONObject().put("version", 1).put("account_id", accountId)
        .put("server_session", serverSession).put("database_key", Base64.getEncoder().encodeToString(databaseKey))
        .toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(payload: ByteArray): AccountSessionRecord {
            val json = JSONObject(String(payload, Charsets.UTF_8))
            require(json.optInt("version") == 1 && json.opt("account_id") is Number)
            return AccountSessionRecord(json.getLong("account_id"), json.getString("server_session"), Base64.getDecoder().decode(json.getString("database_key")))
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
}
internal data class ClientSessionCleanup(val localDeleted: Boolean, val remoteRevoked: Boolean)

enum class AccountPhase { SIGNED_OUT, RESTORING, VERIFIED, UNAVAILABLE, SIGNING_OUT, CLEANUP_REQUIRED }
data class AccountSessionState(val phase: AccountPhase = AccountPhase.SIGNED_OUT, val account: VerifiedAccount? = null, val remoteRevocationUnconfirmed: Boolean = false)

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

    suspend fun restore() = operationLock.withLock {
        val lease = generation.incrementAndGet()
        _state.value = AccountSessionState(AccountPhase.RESTORING)
        try {
            val saved = lock.withLock { store.load() } ?: run {
                if (lease == generation.get()) _state.value = AccountSessionState()
                return@withLock
            }
            activate(saved, lease, persist = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (lease == generation.get()) _state.value = AccountSessionState(AccountPhase.UNAVAILABLE)
        }
    }

    suspend fun acceptLogin(record: AccountSessionRecord) = operationLock.withLock {
        check(_state.value.phase in setOf(AccountPhase.SIGNED_OUT, AccountPhase.UNAVAILABLE))
        val lease = generation.incrementAndGet()
        _state.value = AccountSessionState(AccountPhase.RESTORING)
        try { activate(record, lease, persist = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (lease == generation.get()) _state.value = AccountSessionState(AccountPhase.UNAVAILABLE)
        }
    }

    private suspend fun activate(record: AccountSessionRecord, lease: Long, persist: Boolean) {
        var retained = false
        try {
            val profile = withTimeout(15_000) { verifier.verify(record.serverSession) }
            require(profile.telegramId == record.accountId)
            if (lease != generation.get()) return
            val telegramId = withTimeout(30_000) { telegram.resume(record) }
            require(telegramId == profile.telegramId)
            lock.withLock {
                if (lease != generation.get()) return@withLock
                if (persist) store.save(record)
                if (lease != generation.get()) { store.erase(); return@withLock }
                current?.clearKey()
                current = record
                retained = true
                _state.value = AccountSessionState(AccountPhase.VERIFIED, profile)
            }
        } finally { if (!retained) record.clearKey() }
    }

    suspend fun logout() {
        val lease = generation.incrementAndGet()
        _state.value = AccountSessionState(AccountPhase.SIGNING_OUT)
        clearConsoleAndRequests()
        // Cancellation or a network outage must not skip local erasure.
        withContext(NonCancellable) {
            operationLock.withLock {
                var clean = true
                var serverRevoked = false
                var telegramRevoked = false
                val credential = current?.serverSession
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
