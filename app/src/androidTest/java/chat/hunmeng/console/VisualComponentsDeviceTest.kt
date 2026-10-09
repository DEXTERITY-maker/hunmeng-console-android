package chat.hunmeng.console

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic UI fixtures only: no account, Bot API, credential vault or installer is used. */
@RunWith(AndroidJUnit4::class)
class VisualComponentsDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class Preferences : ConsolePreferences {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, fallback: String) = values[key] ?: fallback
        override fun getBoolean(key: String, fallback: Boolean) = fallback
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun remove(key: String) { values.remove(key) }
    }

    private fun capture(name: String, expectedTheme: ThemeMode) {
        compose.waitForIdle()
        val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
        val directory = File(output, "screenshots").apply { mkdirs() }
        // UiAutomation may see the previous Surface frame even after Compose is idle.
        // Wait for the actual screenshot pixels instead of labelling a stale light frame dark.
        val expected = if (expectedTheme == ThemeMode.DARK) 0x192631 else 0xffffff
        var rendered: Bitmap? = null
        compose.waitUntil(timeoutMillis = 5_000) {
            val frame = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val matching = (3..16).sumOf { x -> (3..16).count { y -> (frame.getPixel(frame.width * x / 20, frame.height * y / 20) and 0xffffff) == expected } }
            if (matching > 15) { rendered = frame; true } else { frame.recycle(); false }
        }
        val screenshot = checkNotNull(rendered)
        try {
            File(directory, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { screenshot.recycle() }
    }

    @Test fun updateDialogsShowActualMetadataInBothThemesAndLanguagesWithoutInstalling() {
        val certificate = "b".repeat(64)
        val release = AndroidRelease("v0.0.5-beta", 3, "0.0.5-beta",
            "https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.5-beta/Hunmeng-Console-0.0.5-beta.apk",
            "a".repeat(64), certificate, 1000,
            ConsoleText("Сохранять сообщения как шаблоны.\nВыбирать избранные чаты.\nВидеть итоги текущей сессии.\nВыбирать светлую или тёмную тему.",
                "Save messages as templates.\nChoose favorite chats.\nSee this session's totals.\nChoose a light or dark theme."))
        var downloads = 0
        val source = object : AndroidReleaseSource {
            override suspend fun latest() = release
            override suspend fun prepare(release: AndroidRelease): File { downloads++; error("Installation is forbidden in this UI test") }
            override fun discard(file: File) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val preferences = Preferences()
        val controller = AndroidUpdateController(source, InstalledAndroidApp(2, certificate), preferences, scope)
        var displayed by mutableStateOf(controller)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var language by mutableStateOf(UiLanguage.RU)
        try {
            compose.setContent { ConsoleTheme(theme) { AndroidUpdateDialogs(displayed, language) } }
            compose.runOnIdle { controller.check() }
            compose.waitUntil { controller.state.value.decision.available != null }
            compose.onNodeWithText("Доступно обновление").assertIsDisplayed()
            compose.onNodeWithText(release.versionName).assertIsDisplayed()
            compose.onNodeWithText("Сохранять сообщения как шаблоны.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Позже").assertIsDisplayed()
            compose.onNodeWithText("Обновить").assertIsEnabled()
            capture("update-light", theme)
            compose.runOnIdle { theme = ThemeMode.DARK }
            capture("update-dark", theme)
            compose.runOnIdle { language = UiLanguage.EN }
            compose.onNodeWithText("Update available").assertIsDisplayed()
            compose.onNodeWithText("Save messages as templates.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Later").performClick()
            compose.onNodeWithText("Update available").assertDoesNotExist()
            compose.runOnIdle { controller.check() }
            compose.waitUntil { controller.state.value.phase != UpdatePhase.CHECKING }
            assertNull(controller.state.value.decision.available)
            assertEquals(release.id, preferences.values["update_later_id"])

            val installed = AndroidUpdateController(source, InstalledAndroidApp(3, certificate), preferences, scope)
            compose.runOnIdle { displayed = installed; installed.check() }
            compose.waitUntil { installed.state.value.decision.whatsNew != null }
            compose.onNodeWithText("What's new").assertIsDisplayed()
            compose.onNodeWithText("Now I can:").assertExists()
            compose.onNodeWithText("Got it").performClick()
            compose.runOnIdle { installed.check() }
            compose.waitUntil { installed.state.value.phase != UpdatePhase.CHECKING }
            compose.onNodeWithText("What's new").assertDoesNotExist()
            assertEquals(release.id, preferences.values["update_seen_installed"])
            assertEquals(0, downloads)
        } finally { scope.cancel() }
    }

    @Test fun accountCardsUseVerifiedLabelsAndShowUnknownRightsOnlyWhenExpanded() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var selections = 0
        val permissions = "Публикация текста: не установлено; редактирование: нет; удаление: да"
        compose.setContent {
            ConsoleTheme(theme) {
                Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AccountProfileHeader(VerifiedAccount(123, "Синтетический аккаунт", "fixture_account"), UiLanguage.RU)
                        OwnedBotCard("Синтетический бот", "fixture_bot", "Создан вашим аккаунтом", true) { selections++ }
                        ChatRoleCard("Синтетический канал", "Роль бота: администратор", true, permissions)
                    }
                }
            }
        }
        compose.onNodeWithText("Профиль подтверждён Telegram Login").assertIsDisplayed()
        compose.onNodeWithText("@fixture_bot").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, selections) }
        compose.onNodeWithText("Роль бота: администратор").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(permissions).assertDoesNotExist()
        capture("account-cards-light", theme)
        compose.runOnIdle { theme = ThemeMode.DARK }
        capture("account-cards-dark", theme)
        compose.onNodeWithText("Синтетический канал").performClick()
        compose.onNodeWithText(permissions).performScrollTo().assertIsDisplayed()
    }
}
