package chat.hunmeng.console

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The only update source is this Android app's public GitHub Releases feed. */
private const val RELEASES_API = "https://api.github.com/repos/DEXTERITY-maker/hunmeng-console-android/releases?per_page=30"
private const val APK_PREFIX = "https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/"
private val APK_URL = Regex(
    """^https://github\.com/DEXTERITY-maker/hunmeng-console-android/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\.apk$""",
    RegexOption.IGNORE_CASE,
)
private val VERSION_TAG = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-.]?[A-Za-z][A-Za-z0-9.-]*)?$""")

data class AppRelease(val version: String, val notes: String, val apkUrl: String, val releaseUrl: String)

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR }

fun interface UpdateReleaseSource {
    suspend fun check(installedVersion: String): AppRelease?
}

class GithubReleaseSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = RELEASES_API,
) : UpdateReleaseSource {
    override suspend fun check(installedVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Hunmeng-Console-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Release service HTTP " + response.code)
            val json = response.body?.string() ?: throw IOException("Empty releases response")
            findLatestAndroidUpdate(JSONArray(json), installedVersion)
        }
    }
}

internal fun findLatestAndroidUpdate(releases: JSONArray, installedVersion: String): AppRelease? {
    val current = numericVersion(installedVersion) ?: throw IllegalArgumentException("Invalid installed version")
    var best: List<Int> = current
    var candidate: AppRelease? = null
    for (index in 0 until releases.length()) {
        val release = releases.optJSONObject(index) ?: continue
        if (release.optBoolean("draft", true)) continue
        val tag = release.optString("tag_name")
        val parsed = numericVersion(tag) ?: continue
        if (compareVersions(parsed, best) <= 0) continue
        val assets = release.optJSONArray("assets") ?: continue
        var apk: String? = null
        for (assetIndex in 0 until assets.length()) {
            val asset = assets.optJSONObject(assetIndex) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.lowercase(Locale.ROOT).endsWith(".apk") &&
                asset.optString("state") == "uploaded" &&
                asset.optLong("size", 0) > 0 &&
                url.startsWith(APK_PREFIX) && APK_URL.matches(url)
            ) {
                apk = url
                break
            }
        }
        if (apk == null) continue
        val page = release.optString("html_url")
        if (!page.startsWith("https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/")) continue
        best = parsed
        candidate = AppRelease(
            version = tag,
            notes = release.optString("body").trim().take(1800),
            apkUrl = apk,
            releaseUrl = page,
        )
    }
    return candidate
}

private fun numericVersion(version: String): List<Int>? {
    val match = VERSION_TAG.matchEntire(version) ?: return null
    return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
}

private fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (index in 0..2) {
        val difference = a[index].compareTo(b[index])
        if (difference != 0) return difference
    }
    return 0
}
