package chat.hunmeng.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ConsoleHeader(
    language: UiLanguage,
    authorization: Boolean,
    onTheme: () -> Unit,
    onLanguage: (UiLanguage) -> Unit,
    onBack: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (largeText || maxWidth < 310.dp) {
                Column {
                    HeaderIdentity(language, authorization, onBack, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        HeaderControls(language, onTheme, onLanguage)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeaderIdentity(language, authorization, onBack, Modifier.weight(1f))
                    HeaderControls(language, onTheme, onLanguage)
                }
            }
        }
    }
}

@Composable
private fun HeaderIdentity(language: UiLanguage, authorization: Boolean, onBack: () -> Unit, modifier: Modifier) {
    val ru = language == UiLanguage.RU
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (authorization) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), if (ru) "Вернуться в консоль" else "Back to console") }
            Text(if (ru) "Авторизация" else "Authorization", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
        } else {
            Image(painterResource(R.drawable.brand_icon), null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f)) {
                Text("Hunmeng Console", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(DISPLAY_VERSION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HeaderControls(language: UiLanguage, onTheme: () -> Unit, onLanguage: (UiLanguage) -> Unit) {
    val ru = language == UiLanguage.RU
    var languageMenu by remember { mutableStateOf(false) }
    IconButton(onClick = onTheme) {
        Icon(painterResource(R.drawable.ic_theme), if (ru) "Настройки темы" else "Theme settings")
    }
    Box {
        OutlinedButton(onClick = { languageMenu = true }, contentPadding = PaddingValues(horizontal = 10.dp),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(language.name, style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(R.drawable.ic_chevron_down), if (ru) "Выбрать язык" else "Choose language", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
            UiLanguage.entries.forEach { item ->
                DropdownMenuItem(text = { Text(if (item == UiLanguage.RU) "Русский (RU)" else "English (EN)") }, onClick = { onLanguage(item); languageMenu = false })
            }
        }
    }
}

@Composable
internal fun LoginBrand() {
    Column(Modifier.fillMaxWidth().testTag("login-brand").padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource(R.drawable.brand_icon), null, Modifier.size(88.dp).clip(RoundedCornerShape(18.dp)))
        Text("Hunmeng Console", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(DISPLAY_VERSION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AccountProfileHeader(profile: VerifiedAccount, language: UiLanguage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(painterResource(R.drawable.ic_profile), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp).size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            profile.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (language == UiLanguage.RU) "Профиль подтверждён Telegram Login" else "Profile verified by Telegram Login",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MyBotsNotice(language: UiLanguage) {
    val ru = language == UiLanguage.RU
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_bot), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (ru) "Мои боты" else "My bots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (ru) "После входа откроется раздел „Мои боты“." else "After sign in, the My bots section will open.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun OwnedBotCard(name: String, username: String?, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_bot), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(10.dp).size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(username?.let { "@$it" } ?: name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                if (username != null) Text(name, style = MaterialTheme.typography.bodySmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ChatRoleCard(title: String, role: String, channel: Boolean, permissions: String) {
    var expanded by remember(title, role, permissions) { mutableStateOf(false) }
    OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(if (channel) R.drawable.ic_channel else R.drawable.ic_groups), null,
                    modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right), null, modifier = Modifier.size(20.dp))
            }
            if (expanded) Text(permissions, style = MaterialTheme.typography.bodySmall)
        }
    }
}
