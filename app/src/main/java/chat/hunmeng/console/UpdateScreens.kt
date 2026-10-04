package chat.hunmeng.console

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun AndroidUpdateDialogs(controller: AndroidUpdateController, language: UiLanguage) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun t(ru: String, en: String) = if (language == UiLanguage.RU) ru else en
    var installError by remember { mutableStateOf(false) }
    var needsPermission by remember { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.installerReturned() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { needsPermission = !context.packageManager.canRequestPackageInstalls() }
    state.decision.available?.let { release ->
        AlertDialog(onDismissRequest = { if (state.phase != UpdatePhase.DOWNLOADING) controller.later() },
            title = { Text(t("Доступно обновление — ", "Update available — ") + release.versionName) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("После обновления я смогу:", "After updating, I can:"))
                Text(release.changes.text(language))
                if (!state.decision.signatureCompatible) Text(t("Подпись установленного приложения отличается. Эта сборка не сможет заменить его обычным обновлением. Приложение не будет удалено автоматически.", "The installed app uses a different signing certificate. This APK cannot replace it as a regular update. The app will not be uninstalled automatically."), color = MaterialTheme.colorScheme.error)
                if (state.phase == UpdatePhase.DOWNLOADING) { CircularProgressIndicator(); Text(t("Скачиваем и проверяем APK…", "Downloading and verifying APK…")) }
                if (state.phase == UpdatePhase.READY) Text(t("APK проверен. Для установки откроется системный установщик Android.", "APK verified. Android's system installer will complete installation."))
                state.error?.let { Text(it.text(language), color = MaterialTheme.colorScheme.error) }
                if (needsPermission) Text(t("Разрешите установку для Hunmeng Console в настройках Android, затем нажмите „Установить“.", "Allow installs from Hunmeng Console in Android settings, then tap Install."))
                if (installError) Text(t("Не удалось открыть системный установщик.", "Could not open the system installer."), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { Button(onClick = {
                installError = false
                val file = controller.preparedFile()
                if (file == null) controller.download()
                else try {
                    if (!context.packageManager.canRequestPackageInstalls()) { needsPermission = true; permission.launch(apkPermissionIntent(context)) }
                    else { needsPermission = false; installer.launch(apkInstallIntent(context, file)) }
                } catch (_: ActivityNotFoundException) { installError = true } catch (_: SecurityException) { installError = true }
            }, enabled = state.decision.signatureCompatible && state.phase != UpdatePhase.DOWNLOADING) { Text(if (state.phase == UpdatePhase.READY) t("Установить", "Install") else t("Обновить", "Update")) } },
            dismissButton = { TextButton(onClick = { controller.cancelDownload(); controller.later() }) { Text(if (state.phase == UpdatePhase.DOWNLOADING) t("Отмена", "Cancel") else t("Позже", "Later")) } })
    }
    state.decision.whatsNew?.let { release -> AlertDialog(onDismissRequest = controller::dismissWhatsNew,
        title = { Text(t("Что нового — ", "What's new — ") + release.versionName) },
        text = { Text(release.changes.text(language), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { Button(onClick = controller::dismissWhatsNew) { Text(t("Понятно", "Got it")) } }) }
}
