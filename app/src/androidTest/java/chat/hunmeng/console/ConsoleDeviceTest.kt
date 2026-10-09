package chat.hunmeng.console

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsoleDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")) { "Test output directory was not supplied" }
        val directory = File(output, "screenshots").apply { mkdirs() }
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "Device screenshot unavailable" }
        File(directory, "$name.png").outputStream().use { stream -> check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        screenshot.recycle()
    }
    @Test fun tabsThemesLanguageAndRotationDoNotConnectOrSend() {
        compose.onNode(hasContentDescription("Выбрать язык") or hasContentDescription("Choose language")).performClick()
        compose.onNodeWithText("Русский (RU)").performClick()
        compose.onNodeWithContentDescription("Настройки темы").performClick()
        compose.onNodeWithText("Светлая тема").performClick()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithText("Бот не подключён").assertIsDisplayed()
        capture("console-light")
        compose.onNodeWithText("Мои боты").performClick()
        compose.onNodeWithText("После входа откроется раздел „Мои боты“.").performScrollTo().assertIsDisplayed()
        capture("login-light-details")
        compose.onNodeWithTag("login-brand").performScrollTo().assertIsDisplayed()
        capture("login-light")
        compose.onNodeWithText("Вернуться в консоль").performScrollTo().performClick()
        compose.onNodeWithText("Профиль").performClick()
        compose.onNodeWithText("Проверить обновления").assertExists()
        compose.onNodeWithText("Консоль").performClick()
        compose.onNodeWithText("Бот не подключён").assertIsDisplayed()
        compose.onNodeWithContentDescription("Настройки темы").performClick()
        compose.onNodeWithText("Тёмная тема").performClick()
        compose.onNodeWithText("Готово").performClick()
        capture("console-dark")
        compose.onNodeWithText("Сообщение").performClick()
        compose.onNodeWithText("Шаблоны сообщений").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Новое сообщение").performScrollTo().assertIsDisplayed()
        capture("message-dark")
        compose.onNodeWithText("Предпросмотр").performScrollTo().assertIsDisplayed()
        capture("message-dark-details")
        compose.onNodeWithText("События").performClick()
        compose.onNodeWithText("Поиск событий").assertExists()
        compose.onNodeWithContentDescription("Выбрать язык").performClick()
        compose.onNodeWithText("English (EN)").performClick()
        compose.onNodeWithText("Bot").performClick()
        compose.onNodeWithText("No bot connected").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("No bot connected").assertIsDisplayed()
    }

    private fun withSoftwareKeyboard(action: () -> Unit) {
        val key = "show_ime_with_hard_keyboard"
        val previous = Settings.Secure.getString(compose.activity.contentResolver, key)
        check(previous == null || previous in setOf("0", "1"))
        fun setting(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        setting("settings put secure $key 1")
        try { action() } finally {
            setting(if (previous == null) "settings delete secure $key" else "settings put secure $key $previous")
        }
    }

    private fun closeSoftKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
    }

    @Test fun realKeyboardAndLandscapeKeepDraftAndExplicitControlsAvailable() = withSoftwareKeyboard {
        compose.onNode(hasContentDescription("Выбрать язык") or hasContentDescription("Choose language")).performClick()
        compose.onNodeWithText("Русский (RU)").performClick()
        compose.onNodeWithText("Сообщение").performClick()
        val draft = "Синтетический черновик 🎬"
        val message = hasSetTextAction() and hasText("Текст сообщения (1–4096 символов)")
        compose.onNode(message).performScrollTo().performClick().performTextInput(draft)
        compose.waitUntil(timeoutMillis = 10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithText(draft).assertIsDisplayed()
        compose.onNodeWithText("Запустить").assertIsDisplayed().assertIsNotEnabled()
        capture("message-keyboard")
        closeSoftKeyboard()
        compose.waitUntil(timeoutMillis = 10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(timeoutMillis = 10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            closeSoftKeyboard()
            compose.waitUntil(timeoutMillis = 10_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            compose.onNodeWithText(draft).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Разделы").assertIsDisplayed()
            compose.onNodeWithText("Предпросмотр").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Запустить").assertIsDisplayed().assertIsNotEnabled()
            capture("message-landscape")
            compose.onNodeWithContentDescription("Разделы").performClick()
            compose.onNodeWithText("Профиль").performClick()
            compose.onNodeWithText("Проверить обновления").assertExists()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
        compose.waitUntil(timeoutMillis = 10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.onNodeWithText("Консоль").performClick()
        compose.onNodeWithText(draft).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Предпросмотр").performScrollTo().assertIsDisplayed()
    }
}
