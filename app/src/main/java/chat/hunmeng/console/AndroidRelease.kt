package chat.hunmeng.console

import org.json.JSONObject
import java.net.URI
import java.time.Instant

data class AndroidRelease(
    val id: String, val versionCode: Int, val versionName: String,
    val apkUrl: String, val apkSha256: String, val certificateSha256: String,
    val sizeBytes: Long, val changes: ConsoleText,
)
data class ReleaseDecision(val available: AndroidRelease? = null, val signatureCompatible: Boolean = false, val whatsNew: AndroidRelease? = null)

internal fun parseAndroidRelease(raw: String): AndroidRelease {
    require(raw.length <= 16_384)
    val json = JSONObject(raw)
    require(json.getString("platform") == "android" && json.getString("package") == "chat.hunmeng.console")
    val id = json.getString("release_id")
    require(Regex("v[0-9][A-Za-z0-9._-]{1,80}").matches(id))
    val code = json.getInt("version_code"); require(code > 0)
    val name = json.getString("version_name"); require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?").matches(name))
    val apk = json.getString("apk_url")
    require(validApkReleaseUrl(apk, id))
    val sha = json.getString("apk_sha256").lowercase()
    val certificate = json.getString("certificate_sha256").lowercase()
    require(Regex("[a-f0-9]{64}").matches(sha) && Regex("[a-f0-9]{64}").matches(certificate))
    val bytes = json.getLong("size_bytes"); require(bytes in 1..157_286_400)
    val changes = json.getJSONObject("changes")
    val ru = changes.getString("ru"); val en = changes.getString("en")
    require(ru.length in 1..4000 && en.length in 1..4000 && !containsCredential(ru) && !containsCredential(en))
    return AndroidRelease(id, code, name, apk, sha, certificate, bytes, ConsoleText(ru, en))
}

internal fun validApkReleaseUrl(value: String, releaseId: String): Boolean = try {
    val uri = URI(value)
    val prefix = "/DEXTERITY-maker/hunmeng-console-android/releases/download/$releaseId/"
    uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.rawPath.startsWith(prefix) && Regex("Hunmeng-Console-[A-Za-z0-9._-]+\\.apk").matches(uri.rawPath.removePrefix(prefix))
} catch (_: Exception) { false }

internal fun decideRelease(
    release: AndroidRelease, installedCode: Int, installedCertificate: String,
    postponedId: String?, postponedAt: Instant?, seenInstalledId: String?, now: Instant,
): ReleaseDecision {
    val compatible = release.certificateSha256.equals(installedCertificate, ignoreCase = true)
    val later = postponedId == release.id && postponedAt != null && now.isBefore(postponedAt.plusSeconds(86_400))
    return ReleaseDecision(
        available = release.takeIf { it.versionCode > installedCode && !later },
        signatureCompatible = compatible,
        whatsNew = release.takeIf { it.versionCode == installedCode && compatible && seenInstalledId != release.id },
    )
}
