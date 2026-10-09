package chat.hunmeng.console

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { UpdateReleaseTitle(t("Доступно обновление", "Update available"), release.versionName) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("После обновления я смогу:", "After updating, I can:"), fontWeight = FontWeight.SemiBold)
                ReleaseChanges(release.changes.text(language))
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
            }, enabled = state.decision.signatureCompatible && state.phase != UpdatePhase.DOWNLOADING,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.phase == UpdatePhase.READY) t("Установить", "Install") else t("Обновить", "Update")) } },
            dismissButton = { OutlinedButton(onClick = { controller.cancelDownload(); controller.later() },
                shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.phase == UpdatePhase.DOWNLOADING) t("Отмена", "Cancel") else t("Позже", "Later")) } })
    }
    state.decision.whatsNew?.let { release -> AlertDialog(onDismissRequest = controller::dismissWhatsNew,
        shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surface,
        title = { UpdateReleaseTitle(t("Что нового", "What's new"), release.versionName) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("Теперь я могу:", "Now I can:"), fontWeight = FontWeight.SemiBold)
            ReleaseChanges(release.changes.text(language))
        } },
        confirmButton = { Button(onClick = controller::dismissWhatsNew, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(t("Понятно", "Got it")) } }) }
}

@Composable
private fun UpdateReleaseTitle(title: String, versionName: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(painterResource(R.drawable.ic_refresh), null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(12.dp).size(28.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(versionName, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ReleaseChanges(changes: String) {
    OutlinedCard(shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            changes.lines().filter(String::isNotBlank).forEachIndexed { index, change ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_template), null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(change, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
