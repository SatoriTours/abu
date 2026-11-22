package local.abu.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

internal enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色");

    fun isDark(systemDark: Boolean) = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

internal class ThemePreference(load: () -> String?, private val save: (String) -> Unit) {
    var mode by mutableStateOf(load().let { stored ->
        ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    })
        private set

    fun choose(value: ThemeMode) {
        save(value.name)
        mode = value
    }
}

private val Light = lightColorScheme(
    primary = Color(0xFF6B4C92), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4D6F6), onPrimaryContainer = Color(0xFF35244A),
    background = Color(0xFFFAF9FC), onBackground = Color(0xFF292632),
    surface = Color(0xFFFAF9FC), onSurface = Color(0xFF292632),
    surfaceVariant = Color(0xFFF0ECF5), onSurfaceVariant = Color(0xFF655A72),
    secondaryContainer = Color(0xFFEEE6F9), onSecondaryContainer = Color(0xFF382B49),
    outline = Color(0xFF82748F), outlineVariant = Color(0xFFDDD4E6),
    error = Color(0xFF98233F), errorContainer = Color(0xFFFFDCE3), onErrorContainer = Color(0xFF650D24),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC9B5F4), onPrimary = Color(0xFF2C2041),
    primaryContainer = Color(0xFFB5A2E6), onPrimaryContainer = Color(0xFF211A30),
    background = Color(0xFF151619), onBackground = Color(0xFFF2EFF8),
    surface = Color(0xFF151619), onSurface = Color(0xFFF2EFF8),
    surfaceVariant = Color(0xFF24232B), onSurfaceVariant = Color(0xFFBEB5CB),
    secondaryContainer = Color(0xFF28252F), onSecondaryContainer = Color(0xFFF2EFF8),
    outline = Color(0xFF948AA3), outlineVariant = Color(0xFF45404F),
    error = Color(0xFFFFB2BA), errorContainer = Color(0xFF662834), onErrorContainer = Color(0xFFFFD9DE),
)

internal val LocalAbuDark = staticCompositionLocalOf { false }

@Composable
internal fun AbuTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAbuDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
