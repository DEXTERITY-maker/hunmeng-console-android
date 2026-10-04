package chat.hunmeng.console

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AndroidReleaseTest {
    private val raw = """{"platform":"android","package":"chat.hunmeng.console","release_id":"v0.0.5-beta","version_code":3,"version_name":"0.0.5-beta","apk_url":"https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.5-beta/Hunmeng-Console-0.0.5-beta.apk","apk_sha256":"${"a".repeat(64)}","certificate_sha256":"${"b".repeat(64)}","size_bytes":1000,"changes":{"ru":"Тестовая возможность","en":"Test feature"}}"""
    @Test fun releaseRequiresAndroidPlatformExactRepositoryAndExpectedPackage() {
        assertEquals(3, parseAndroidRelease(raw).versionCode)
        for ((from, to) in listOf("\"android\"" to "\"browser\"", "chat.hunmeng.console" to "other.package", "github.com" to "attacker.invalid", "DEXTERITY-maker" to "foreign-owner")) {
            assertThrows(IllegalArgumentException::class.java) { parseAndroidRelease(raw.replace(from, to)) }
        }
    }
    @Test fun rejectsHttpTraversalQueryForeignReleaseAndInvalidHash() {
        assertFalse(validApkReleaseUrl("http://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v1/Hunmeng-Console-1.apk", "v1"))
        assertFalse(validApkReleaseUrl("https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v1/../../foreign.apk", "v1"))
        assertFalse(validApkReleaseUrl("https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v1/Hunmeng-Console-1.apk?redirect=foreign", "v1"))
        assertThrows(IllegalArgumentException::class.java) { parseAndroidRelease(raw.replace("a".repeat(64), "invalid")) }
    }
    @Test fun laterIsReleaseSpecificAndWaits24HoursAndWhatsNewIsOnce() {
        val release = parseAndroidRelease(raw); val now = Instant.parse("2026-10-04T00:00:00Z")
        assertNull(decideRelease(release, 2, "b".repeat(64), release.id, now, null, now.plusSeconds(100)).available)
        assertNotNull(decideRelease(release, 2, "b".repeat(64), release.id, now, null, now.plusSeconds(86_400)).available)
        assertNotNull(decideRelease(release, 2, "b".repeat(64), "old-release", now, null, now).available)
        assertNotNull(decideRelease(release, 3, "b".repeat(64), null, null, null, now).whatsNew)
        assertNull(decideRelease(release, 3, "b".repeat(64), null, null, release.id, now).whatsNew)
    }
    @Test fun signatureMismatchDoesNotClaimCompatibleUpgradeOrInstalledRelease() {
        val release = parseAndroidRelease(raw); val now = Instant.now()
        val upgrade = decideRelease(release, 2, "c".repeat(64), null, null, null, now)
        assertNotNull(upgrade.available); assertFalse(upgrade.signatureCompatible)
        assertNull(decideRelease(release, 3, "c".repeat(64), null, null, null, now).whatsNew)
        assertNull(decideRelease(release, 4, "b".repeat(64), null, null, null, now).available)
    }
}
