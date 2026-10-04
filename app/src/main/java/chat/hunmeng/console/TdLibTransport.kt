package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class TdLibException(val code: Int, val retryAfter: Int? = null) : Exception("Telegram client request failed ($code)")
internal interface TdJsonBridge {
    fun createClientId(): Int
    fun send(clientId: Int, request: String)
    fun receive(timeout: Double): String?
    fun execute(request: String): String?
}
internal object NativeTdJsonBridge : TdJsonBridge {
    override fun createClientId() = JsonClient.createClientId()
    override fun send(clientId: Int, request: String) = JsonClient.send(clientId, request)
    override fun receive(timeout: Double) = JsonClient.receive(timeout)
    override fun execute(request: String) = JsonClient.execute(request)
}

/** One native receiver in the process; Telegram Bot API polling remains a separate single loop. */
internal class TdLibRuntime(private val bridge: TdJsonBridge, private val scope: CoroutineScope) {
    private val clients = ConcurrentHashMap<Int, TdLibClient>()
    private var receiver: Job? = null
    @Synchronized fun client(): TdLibClient {
        try { return createClient() } catch (_: LinkageError) { throw IllegalStateException("Telegram client runtime unavailable") }
    }
    private fun createClient(): TdLibClient {
        if (receiver?.isActive != true) {
            bridge.execute(JSONObject().put("@type", "setLogStream").put("log_stream", JSONObject().put("@type", "logStreamEmpty")).toString())
            bridge.execute(JSONObject().put("@type", "setLogVerbosityLevel").put("new_verbosity_level", 0).toString())
            receiver = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val raw = bridge.receive(0.5) ?: continue
                    // Unneeded message/profile updates are neither stored nor logged.
                    val json = try { JSONObject(raw) } catch (_: Exception) { continue }
                    clients[json.optInt("@client_id")]?.receive(json)
                }
            }
        }
        val id = bridge.createClientId()
        return TdLibClient(id, bridge) { clients.remove(id) }.also { clients[id] = it }
    }
}

internal class TdLibClient(private val id: Int, private val bridge: TdJsonBridge, private val remove: () -> Unit) {
    private val sequence = AtomicLong()
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val _authorization = MutableStateFlow("authorizationStateWaitTdlibParameters")
    val authorization: StateFlow<String> = _authorization.asStateFlow()
    private val _qrLink = MutableStateFlow<String?>(null)
    val qrLink: StateFlow<String?> = _qrLink.asStateFlow()
    @Volatile private var closed = false

    suspend fun request(type: String, fields: JSONObject = JSONObject(), timeout: Long = 30_000): JSONObject {
        check(!closed) { "Telegram client is closed" }
        val requestId = sequence.incrementAndGet().toString()
        val answer = CompletableDeferred<JSONObject>()
        requests[requestId] = answer
        try {
            bridge.send(id, JSONObject(fields.toString()).put("@type", type).put("@extra", requestId).toString())
            return withTimeout(timeout) { answer.await() }
        } finally { requests.remove(requestId); answer.cancel() }
    }

    fun receive(json: JSONObject) {
        if (closed) return
        val extra = json.opt("@extra") as? String
        if (extra != null) {
            val answer = requests.remove(extra) ?: return
            if (json.optString("@type") == "error") {
                val retry = Regex("(?:FLOOD_WAIT_|retry after )(\\d+)", RegexOption.IGNORE_CASE).find(json.optString("message"))?.groupValues?.get(1)?.toIntOrNull()
                answer.completeExceptionally(TdLibException(json.optInt("code"), retry))
            } else {
                if (json.optString("@type").startsWith("authorizationState")) authorizationUpdate(json)
                answer.complete(json)
            }
            return
        }
        if (json.optString("@type") == "updateAuthorizationState") {
            val auth = json.optJSONObject("authorization_state") ?: return
            authorizationUpdate(auth)
            if (_authorization.value == "authorizationStateClosed") {
                closed = true
                requests.values.forEach { it.cancel() }; requests.clear()
                _qrLink.value = null
                remove()
            }
        }
    }
    private fun authorizationUpdate(auth: JSONObject) {
        _authorization.value = auth.optString("@type")
        _qrLink.value = auth.optString("link").takeIf { _authorization.value == "authorizationStateWaitOtherDeviceConfirmation" && it.startsWith("tg://login?token=") }
    }

    suspend fun close(): Boolean {
        if (closed) return true
        try { request("close", timeout = 10_000) } catch (_: Exception) { /* still await native closure */ }
        return withTimeoutOrNull(15_000) { authorization.first { it == "authorizationStateClosed" }; true } ?: false
    }
}

/** Activity recreation cannot create a second native receiver. */
internal object ProcessTelegramClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val directoryLock = Mutex()
    val runtime: TdLibRuntime by lazy { TdLibRuntime(NativeTdJsonBridge, scope) }
    fun closeLater(session: TelegramClientSession) { scope.launch { try { session.closeWithoutErasing() } catch (_: Exception) { } } }
}
