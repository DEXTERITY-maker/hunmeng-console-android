package chat.hunmeng.console

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.util.Base64

/** Client credentials and database encryption key are supplied from the encrypted account record. */
internal class TdLibAccountSession(context: Context, private val runtime: TdLibRuntime, private val scope: CoroutineScope) : TelegramClientSession {
    private val directory = File(context.noBackupFilesDir, "telegram-client")
    private var client: TdLibClient? = null
    private var watcher: Job? = null
    private val _phase = MutableStateFlow("unconnected")
    val phase: StateFlow<String> = _phase.asStateFlow()
    private val _qrLink = MutableStateFlow<String?>(null)
    val qrLink: StateFlow<String?> = _qrLink.asStateFlow()

    override suspend fun resume(session: AccountSessionRecord): Long {
        val config = session.apiApplication ?: throw IllegalStateException("Telegram client application is not configured")
        check(client == null) { "Telegram client is already connected" }
        val active = runtime.client()
        client = active
        _phase.value = "connecting"
        watcher = scope.launch {
            launch { active.authorization.collect { _phase.value = it } }
            launch { active.qrLink.collect { _qrLink.value = it } }
        }
        active.request("getAuthorizationState")
        if (active.authorization.value == "authorizationStateWaitTdlibParameters") {
            active.request("setTdlibParameters", JSONObject()
                .put("use_test_dc", false)
                .put("database_directory", directory.absolutePath)
                .put("files_directory", File(directory, "files").absolutePath)
                .put("database_encryption_key", Base64.getEncoder().encodeToString(session.databaseKey))
                .put("use_file_database", false).put("use_chat_info_database", false)
                .put("use_message_database", false).put("use_secret_chats", false)
                .put("api_id", config.apiId).put("api_hash", config.apiHash)
                .put("system_language_code", "en").put("device_model", "Hunmeng Console Android")
                .put("system_version", "Android ${Build.VERSION.RELEASE}")
                .put("application_version", BuildConfig.VERSION_NAME))
        }
        val auth = active.authorization.first { it in setOf("authorizationStateReady", "authorizationStateWaitPhoneNumber", "authorizationStateWaitOtherDeviceConfirmation", "authorizationStateWaitPassword", "authorizationStateClosed") }
        if (auth == "authorizationStateWaitPhoneNumber") active.request("requestQrCodeAuthentication", JSONObject().put("other_user_ids", org.json.JSONArray()))
        active.authorization.first { it == "authorizationStateReady" || it == "authorizationStateClosed" }
        check(active.authorization.value == "authorizationStateReady") { "Telegram authorization cancelled" }
        val me = active.request("getMe")
        check(me.optString("@type") == "user" && me.optLong("id") > 0)
        return me.getLong("id")
    }

    suspend fun confirmPassword(password: String) {
        val active = client ?: return
        check(active.authorization.value == "authorizationStateWaitPassword")
        // Password is never persisted, echoed or attached to an exception.
        active.request("checkAuthenticationPassword", JSONObject().put("password", password))
    }
    fun inventory(account: VerifiedAccount): TdLibInventorySource {
        val active = client ?: throw IllegalStateException("Telegram client is not connected")
        check(active.authorization.value == "authorizationStateReady")
        return TdLibInventorySource(active, account)
    }
    override suspend fun revokeCloseAndErase(): ClientSessionCleanup {
        val active = client
        var revoked = false
        var closed = active == null
        if (active != null) {
            try { active.request("logOut", timeout = 10_000); revoked = true } catch (_: Exception) { }
            closed = active.close()
        }
        watcher?.cancelAndJoin(); watcher = null
        _qrLink.value = null
        if (!closed) { _phase.value = "cleanup_required"; return ClientSessionCleanup(false, revoked) }
        client = null
        val deleted = withContext(Dispatchers.IO) {
            try {
                if (directory.exists()) Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                    override fun postVisitDirectory(dir: Path, exception: java.io.IOException?): FileVisitResult { if (exception != null) throw exception; Files.delete(dir); return FileVisitResult.CONTINUE }
                })
                !directory.exists()
            } catch (_: Exception) { false }
        }
        _phase.value = if (deleted) "unconnected" else "cleanup_required"
        return ClientSessionCleanup(deleted, revoked)
    }
}
