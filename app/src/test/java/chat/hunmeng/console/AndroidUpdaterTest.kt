package chat.hunmeng.console

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidUpdaterTest {
    private class Preferences : ConsolePreferences {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, fallback: String) = values[key] ?: fallback
        override fun getBoolean(key: String, fallback: Boolean) = fallback
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { }
        override fun remove(key: String) { values.remove(key) }
    }
    private fun release() = AndroidRelease("v0.0.5-beta", 3, "0.0.5-beta", "https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.5-beta/Hunmeng-Console-0.0.5-beta.apk", "a".repeat(64), "b".repeat(64), 1000, ConsoleText("Проверенная возможность", "Verified feature"))
    private inner class Source : AndroidReleaseSource {
        var downloaded = 0; var discarded = 0; var failCheck = false
        override suspend fun latest(): AndroidRelease { if (failCheck) error("Fixture outage"); return release() }
        override suspend fun prepare(release: AndroidRelease): File { downloaded++; return File("synthetic-verified.apk") }
        override fun discard(file: File) { discarded++ }
    }
    @Test fun checkingDoesNotDownloadAndIncompatibleCertificateCannotInstall() = runTest {
        val source = Source(); val controller = AndroidUpdateController(source, InstalledAndroidApp(2, "c".repeat(64)), Preferences(), this)
        controller.check(); yield()
        assertNotNull(controller.state.value.checkedAt)
        assertNotNull(controller.state.value.decision.available); assertEquals(0, source.downloaded)
        controller.download(); yield(); assertEquals(0, source.downloaded); assertNull(controller.preparedFile())
    }
    @Test fun compatibleDownloadIsExplicitAndInstallerReturnDiscardsTemporaryApk() = runTest {
        val source = Source(); val controller = AndroidUpdateController(source, InstalledAndroidApp(2, "b".repeat(64)), Preferences(), this)
        controller.check(); yield(); controller.download(); yield()
        assertEquals(1, source.downloaded); assertEquals(UpdatePhase.READY, controller.state.value.phase)
        assertNotNull(controller.preparedFile()); controller.installerReturned()
        assertNull(controller.preparedFile()); assertEquals(1, source.discarded)
    }
    @Test fun sourceOutageIsIndependentAndLaterAndWhatsNewAreStoredExplicitly() = runTest {
        val source = Source().apply { failCheck = true }; val prefs = Preferences()
        val controller = AndroidUpdateController(source, InstalledAndroidApp(2, "b".repeat(64)), prefs, this)
        controller.check(); yield(); assertEquals(UpdatePhase.ERROR, controller.state.value.phase)
        assertNull(controller.state.value.checkedAt)
        source.failCheck = false; controller.check(); yield(); controller.later()
        assertEquals(release().id, prefs.values["update_later_id"]); assertNull(controller.state.value.decision.available)
        val installed = AndroidUpdateController(source, InstalledAndroidApp(3, "b".repeat(64)), prefs, this)
        installed.check(); yield(); assertNotNull(installed.state.value.decision.whatsNew)
        installed.dismissWhatsNew(); assertEquals(release().id, prefs.values["update_seen_installed"])
    }
    @Test fun redirectsRequireHttpsAndSpecificGithubAssetHosts() {
        assertTrue(trustedReleaseDownloadUrl("https://release-assets.githubusercontent.com/fixture.apk?signature=fixture"))
        for (url in listOf("http://github.com/file", "https://github.com.attacker.invalid/file", "https://user:password@github.com/file", "https://github.com:8443/file", "https://attacker.invalid/file")) assertFalse(trustedReleaseDownloadUrl(url))
    }
}
