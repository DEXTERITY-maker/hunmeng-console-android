package chat.hunmeng.console

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LoginFailure(val category: String = "login_failed") : Exception(category)
internal data class LoginConfiguration(val configured: Boolean, val clientId: String? = null, val redirectUri: String? = null)
internal class LoginAttempt(val state: String, val binding: String, val authorizationUrl: String, val redirectUri: String, val expiresAt: Long) {
    override fun toString() = "LoginAttempt([redacted])"
    fun encode() = JSONObject().put("state", state).put("binding", binding).put("authorization_url", authorizationUrl)
        .put("redirect_uri", redirectUri).put("expires_at", expiresAt).toString().toByteArray()
    companion object {
        fun decode(bytes: ByteArray): LoginAttempt = JSONObject(String(bytes, Charsets.UTF_8)).let {
            LoginAttempt(it.getString("state"), it.getString("binding"), it.getString("authorization_url"), it.getString("redirect_uri"), it.getLong("expires_at"))
        }
    }
}
internal class LoginResult(val account: VerifiedAccount, val session: String) { override fun toString() = "LoginResult([redacted])" }
internal interface LoginGateway : AccountSessionVerifier {
    suspend fun configuration(): LoginConfiguration
    suspend fun begin(binding: String): LoginAttempt
    suspend fun complete(attempt: LoginAttempt, code: String): LoginResult
    suspend fun cancel(attempt: LoginAttempt)
}

/** No redirects, implicit retries, URL logging, raw error bodies or JWT on the device. */
internal class TelegramLoginGateway(
    backend: String = BuildConfig.LOGIN_BACKEND_URL,
    private val publicClientId: String = BuildConfig.TELEGRAM_LOGIN_CLIENT_ID,
    private val expectedRedirectUri: String = BuildConfig.TELEGRAM_LOGIN_REDIRECT_URI,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build(),
) : LoginGateway {
    private val base = backend.toHttpUrl().also { require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null) }
    private suspend fun call(action: String, data: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(base.newBuilder().addPathSegments("api/account/login/$action").build())
        if (data != null) {
            val bytes = data.toString().toByteArray(Charsets.UTF_8)
            request.post(object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            })
        }
        val active = http.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { active.cancel() }
            active.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(LoginFailure("network_unavailable")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!continuation.isActive) return
                            val source = response.body?.source() ?: throw LoginFailure()
                            if (source.request(65_537)) throw LoginFailure("invalid_response")
                            val json = JSONObject(source.readUtf8())
                            if (!response.isSuccessful) {
                                val category = json.optString("error").takeIf { it in setOf("login_not_configured", "login_expired_or_used", "unauthorized", "rate_limited") } ?: "login_failed"
                                throw LoginFailure(category)
                            }
                            if (continuation.isActive) continuation.resume(json)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(if (error is LoginFailure) error else LoginFailure("invalid_response")) }
                    }
                }
            })
        }
    }
    override suspend fun configuration(): LoginConfiguration {
        if (publicClientId == "0") return LoginConfiguration(false)
        val json = call("config")
        if (json.opt("configured") == false) return LoginConfiguration(false)
        val id = json.getString("clientId")
        val redirect = json.getString("redirectUri")
        require(id == publicClientId && Regex("[1-9][0-9]{4,15}").matches(id) && redirect == expectedRedirectUri && validLoginRedirectUri(redirect))
        return LoginConfiguration(true, id, redirect)
    }
    override suspend fun begin(binding: String): LoginAttempt {
        val config = configuration()
        if (!config.configured) throw LoginFailure("login_not_configured")
        val json = call("begin", JSONObject().put("binding", binding))
        return LoginAttempt(json.getString("state"), binding, json.getString("authorizationUrl"), json.getString("redirectUri"), json.getLong("expiresAt"))
            .also { validateLoginAttempt(it, checkNotNull(config.clientId), expectedRedirectUri) }
    }
    override suspend fun complete(attempt: LoginAttempt, code: String): LoginResult {
        val json = call("complete", JSONObject().put("state", attempt.state).put("binding", attempt.binding).put("code", code).put("callbackUri", attempt.redirectUri))
        val session = json.getString("session"); require(Regex("[A-Za-z0-9_-]{43}").matches(session))
        return LoginResult(profile(json.getJSONObject("account")), session)
    }
    override suspend fun cancel(attempt: LoginAttempt) { call("cancel", JSONObject().put("state", attempt.state).put("binding", attempt.binding)) }
    override suspend fun verify(serverSession: String) = profile(call("verify", JSONObject().put("session", serverSession)).getJSONObject("account"))
    override suspend fun revoke(serverSession: String) { call("logout", JSONObject().put("session", serverSession)) }
    private fun profile(json: JSONObject): VerifiedAccount {
        val id = json.getLong("telegramId"); val name = json.getString("displayName"); val username = json.optNullableString("username")
        require(id in 1..9_007_199_254_740_991L && name.length in 1..200 && (username == null || Regex("[A-Za-z0-9_]{1,64}").matches(username)))
        return VerifiedAccount(id, name, username)
    }
}

internal fun loginQuery(value: String): Map<String, String> {
    val raw = URI(value).rawQuery ?: return emptyMap()
    require(raw.length <= 8192)
    val pairs = raw.split('&').map { item -> item.split('=', limit = 2).let { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
    require(pairs.map { it.first }.distinct().size == pairs.size)
    return pairs.toMap()
}
internal fun validLoginRedirectUri(value: String) = Regex("https://app[1-9][0-9]{4,15}-login\\.tg\\.dev/tglogin").matches(value)
internal fun validateLoginAttempt(attempt: LoginAttempt, clientId: String, expectedRedirectUri: String) {
    require(Regex("[1-9][0-9]{4,15}").matches(clientId))
    require(Regex("[A-Za-z0-9_-]{43}").matches(attempt.state) && Regex("[A-Za-z0-9_-]{43}").matches(attempt.binding))
    require(validLoginRedirectUri(expectedRedirectUri) && attempt.redirectUri == expectedRedirectUri)
    val uri = URI(attempt.authorizationUrl)
    require(uri.scheme == "https" && uri.host == "oauth.telegram.org" && uri.port == -1 && uri.userInfo == null && uri.rawPath == "/auth" && uri.fragment == null)
    val query = loginQuery(attempt.authorizationUrl)
    require(query["client_id"] == clientId && query["redirect_uri"] == attempt.redirectUri && query["state"] == attempt.state && query["response_type"] == "code")
    require(query["scope"] == "openid profile" && query["code_challenge_method"] == "S256" && Regex("[A-Za-z0-9_-]{43}").matches(query["nonce"].orEmpty()) && Regex("[A-Za-z0-9_-]{43}").matches(query["code_challenge"].orEmpty()))
}

/** Exact origin/path and one code/state. No callback or provider error is ever displayed. */
internal fun loginCallbackCode(value: String, attempt: LoginAttempt): String? {
    val uri = URI(value); val expected = URI(attempt.redirectUri)
    require(value.length <= 8192 && uri.scheme == expected.scheme && uri.host == expected.host && uri.port == -1 && uri.userInfo == null && uri.rawPath == expected.rawPath && uri.fragment == null)
    val query = loginQuery(value)
    require(query["state"] == attempt.state)
    if (query.containsKey("error")) { require(!query.containsKey("code")); return null }
    return checkNotNull(query["code"]).also { require(it.length in 1..4096) }
}
