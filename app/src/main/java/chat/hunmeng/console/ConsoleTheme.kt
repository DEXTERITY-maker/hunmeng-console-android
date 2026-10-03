package chat.hunmeng.console

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    // Slightly darker button fill keeps white text above 4.5:1 contrast.
    primary = Color(0xFF0068D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EEFF), onPrimaryContainer = Color(0xFF004EAF),
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF152238),
    surface = Color.White, onSurface = Color(0xFF152238),
    surfaceVariant = Color(0xFFECF1F8), onSurfaceVariant = Color(0xFF52617A),
    outline = Color(0xFF6B7890), error = Color(0xFFB3261E),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF66B7FF), onPrimary = Color(0xFF002D57),
    primaryContainer = Color(0xFF123D69), onPrimaryContainer = Color(0xFFCCE5FF),
    background = Color(0xFF0E1923), onBackground = Color(0xFFF2F6FA),
    surface = Color(0xFF192631), onSurface = Color(0xFFF2F6FA),
    surfaceVariant = Color(0xFF243444), onSurfaceVariant = Color(0xFFB5C5D8),
    outline = Color(0xFF8D9FB2), error = Color(0xFFFFB4AB),
)

@Composable
fun ConsoleTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val defaults = Typography()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = defaults.copy(
            bodyLarge = defaults.bodyLarge.copy(fontSize = 16.sp),
            bodyMedium = defaults.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
            bodySmall = defaults.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
            titleLarge = defaults.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
            labelSmall = defaults.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        ),
        shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(20.dp)),
        content = content,
    )
}
