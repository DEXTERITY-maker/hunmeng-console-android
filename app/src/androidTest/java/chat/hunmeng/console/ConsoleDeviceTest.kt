package chat.hunmeng.console

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
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
        File(directory, "$name.png").outputStream().use { output -> compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output) }
    }
    @Test fun tabsThemesLanguageAndRotationDoNotConnectOrSend() {
        compose.onNodeWithText("RU").performClick()
        compose.onNodeWithContentDescription("Настройки темы").performClick()
        compose.onNodeWithText("Светлая тема").performClick()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithText("Бот не подключён").assertIsDisplayed()
        capture("console-light")
        compose.onNodeWithContentDescription("Настройки темы").performClick()
        compose.onNodeWithText("Тёмная тема").performClick()
        compose.onNodeWithText("Готово").performClick()
        capture("console-dark")
        compose.onNodeWithText("Сообщение").performClick()
        compose.onNodeWithText("Шаблоны сообщений").performScrollTo().assertIsDisplayed()
        capture("message-dark")
        compose.onNodeWithText("События").performClick()
        compose.onNodeWithText("Поиск событий").assertExists()
        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Bot").performClick()
        compose.onNodeWithText("No bot connected").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("No bot connected").assertIsDisplayed()
    }
}
