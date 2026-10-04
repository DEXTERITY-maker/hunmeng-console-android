package chat.hunmeng.console

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class InstalledAndroidApp(val versionCode: Int, val certificateSha256: String)
internal interface AndroidReleaseSource {
    suspend fun latest(): AndroidRelease
    suspend fun prepare(release: AndroidRelease): File
    fun discard(file: File)
}
enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, ERROR }
data class AndroidUpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val decision: ReleaseDecision = ReleaseDecision(), val error: ConsoleText? = null)
internal class AndroidUpdateController(
    private val source: AndroidReleaseSource, private val installed: InstalledAndroidApp,
    private val prefs: ConsolePreferences, private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AndroidUpdateState())
    val state: StateFlow<AndroidUpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private var file: File? = null
    fun check() {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(phase = UpdatePhase.CHECKING, error = null)
        job = scope.launch {
            try {
                val release = source.latest()
                val decision = decideRelease(release, installed.versionCode, installed.certificateSha256,
                    prefs.getString("update_later_id", ""), prefs.getString("update_later_at", "")?.let { try { Instant.parse(it) } catch (_: Exception) { null } },
                    prefs.getString("update_seen_installed", ""), Instant.now())
                _state.value = AndroidUpdateState(decision = decision)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(phase = UpdatePhase.ERROR, error = ConsoleText("Не удалось проверить Android-обновления. Консоль доступна.", "Could not check Android updates. The console remains available.")) }
        }
    }
    fun later() {
        val release = state.value.decision.available ?: return
        prefs.putString("update_later_id", release.id); prefs.putString("update_later_at", Instant.now().toString())
        _state.value = _state.value.copy(decision = state.value.decision.copy(available = null))
    }
    fun dismissWhatsNew() {
        state.value.decision.whatsNew?.let { prefs.putString("update_seen_installed", it.id) }
        _state.value = _state.value.copy(decision = state.value.decision.copy(whatsNew = null))
    }
    fun download() {
        val release = state.value.decision.available ?: return
        if (!state.value.decision.signatureCompatible || job?.isActive == true) return
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, error = null)
        job = scope.launch {
            try { file = source.prepare(release); _state.value = _state.value.copy(phase = UpdatePhase.READY) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = _state.value.copy(phase = UpdatePhase.ERROR, error = ConsoleText("APK не прошёл загрузку или проверку. Установка не запущена.", "APK download or verification failed. Installation was not started.")) }
        }
    }
    fun preparedFile(): File? = file.takeIf { state.value.phase == UpdatePhase.READY && state.value.decision.signatureCompatible }
    fun installerReturned() { file?.let(source::discard); file = null; _state.value = _state.value.copy(phase = UpdatePhase.IDLE) }
    fun cancelDownload() { job?.cancel(); installerReturned() }
    fun close() { cancelDownload() }
}

/** The release source is Android metadata from the owner repository, never the website version. */
internal class GithubAndroidReleases(private val context: Context) : AndroidReleaseSource {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()
    private val directory = File(context.cacheDir, "android-updates").apply { mkdirs() }
    init { directory.listFiles()?.forEach { it.delete() } }
    private suspend fun response(url: HttpUrl): Response {
        val call = http.newCall(Request.Builder().url(url).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("Release request failed")) }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response, onCancellation = { _, value, _ -> value.close() }) else response.close()
                }
            })
        }
    }
    override suspend fun latest(): AndroidRelease = withContext(Dispatchers.IO) {
        response("https://raw.githubusercontent.com/DEXTERITY-maker/hunmeng-console-android/main/updates/android.json".toHttpUrl()).use { response ->
            require(response.isSuccessful)
            val source = checkNotNull(response.body).source(); require(!source.request(16_385))
            parseAndroidRelease(source.readUtf8())
        }
    }
    override suspend fun prepare(release: AndroidRelease): File = withContext(Dispatchers.IO) {
        require(validApkReleaseUrl(release.apkUrl, release.id))
        require(release.versionCode > BuildConfig.VERSION_CODE && release.certificateSha256 == installedAndroidApp(context).certificateSha256)
        val partial = File(directory, "${release.id}.partial")
        val ready = File(directory, "${release.id}.apk")
        var accepted = false
        try {
            var url = release.apkUrl.toHttpUrl()
            var download: Response? = null
            for (redirect in 0..4) {
                val result = response(url)
                if (result.code !in setOf(301, 302, 303, 307, 308)) { download = result; break }
                val location = result.header("Location"); result.close()
                url = checkNotNull(location?.let(url::resolve)).also { require(trustedReleaseDownloadUrl(it.toString())) }
            }
            checkNotNull(download).use { response ->
                require(response.isSuccessful)
                val body = checkNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == release.sizeBytes)
                val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
                partial.outputStream().use { output -> body.byteStream().use { input ->
                    val bytes = ByteArray(32_768)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(bytes); if (count < 0) break; length += count; require(length <= release.sizeBytes); digest.update(bytes, 0, count); output.write(bytes, 0, count) }
                } }
                require(length == release.sizeBytes && hexDigest(digest.digest()) == release.apkSha256)
            }
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION") val info = checkNotNull(context.packageManager.getPackageArchiveInfo(partial.absolutePath, flags))
            require(info.packageName == context.packageName && packageVersion(info) == release.versionCode.toLong() && info.versionName == release.versionName && packageCertificate(info) == release.certificateSha256)
            require(partial.renameTo(ready)); accepted = true; ready
        } finally { partial.delete(); if (!accepted) ready.delete() }
    }
    override fun discard(file: File) { if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete() }
}

internal fun trustedReleaseDownloadUrl(raw: String): Boolean = try {
    val url = raw.toHttpUrl()
    url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
} catch (_: Exception) { false }
internal fun hexDigest(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
@Suppress("DEPRECATION") internal fun packageVersion(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
@Suppress("DEPRECATION") internal fun packageCertificate(info: PackageInfo): String {
    val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    require(certificates?.size == 1)
    return hexDigest(MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray()))
}
@Suppress("DEPRECATION") internal fun installedAndroidApp(context: Context): InstalledAndroidApp {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageInfo(context.packageName, flags)
    return InstalledAndroidApp(packageVersion(info).toInt(), packageCertificate(info))
}
internal fun apkInstallIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
}
internal fun apkPermissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
