package chat.hunmeng.console

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ReleaseControllerTest {
    private class MemoryPreferences : ConsolePreferences {
        val strings = mutableMapOf<String, String>()
        override fun getString(key: String, fallback: String) = strings[key] ?: fallback
        override fun getBoolean(key: String, fallback: Boolean) = fallback
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun remove(key: String) { strings.remove(key) }
    }

    private val fakeRelease = AppRelease(
        "v0.0.5-beta",
        "New release",
        "https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.5-beta/Hunmeng-Console.apk",
        "https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/v0.0.5-beta",
    )

    @Test fun updateNoticeIsDismissedButCanBeOpenedManually() = runBlocking {
        val prefs = MemoryPreferences()
        val source = UpdateReleaseSource { fakeRelease }
        val controller = ConsoleController(prefs, this, releaseSource = source)

        controller.checkUpdates()
        yield()
        assertEquals(UpdateStatus.AVAILABLE, controller.state.value.updateStatus)
        assertTrue(controller.state.value.updateDialogVisible)
        controller.dismissUpdate()
        assertFalse(controller.state.value.updateDialogVisible)
        assertEquals("v0.0.5-beta", prefs.strings["dismissed_release"])

        controller.checkUpdates(silent = true)
        yield()
        assertEquals(UpdateStatus.AVAILABLE, controller.state.value.updateStatus)
        assertFalse(controller.state.value.updateDialogVisible)

        controller.showAvailableUpdate()
        assertTrue(controller.state.value.updateDialogVisible)
        controller.close()
    }

    @Test fun noNewVersionShowsUpToDate() = runBlocking {
        val controller = ConsoleController(
            MemoryPreferences(), this,
            releaseSource = UpdateReleaseSource { null },
        )
        controller.checkUpdates()
        yield()
        assertEquals(UpdateStatus.UP_TO_DATE, controller.state.value.updateStatus)
        assertNull(controller.state.value.availableUpdate)
        assertFalse(controller.state.value.updateDialogVisible)
        controller.close()
    }
}
