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
    primary = Color(0xFF745EE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DFFB),
    onPrimaryContainer = Color(0xFF241F35),
    secondary = Color(0xFF745EE0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3F0FD),
    onSecondaryContainer = Color(0xFF1E1C28),
    background = Color(0xFFFBFBFD),
    onBackground = Color(0xFF1D1B26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1B26),
    surfaceVariant = Color(0xFFF3F3F8),
    onSurfaceVariant = Color(0xFF7B788A),
    outline = Color(0xFFCBC7D8),
    outlineVariant = Color(0xFFEAE8F1),
    error = Color(0xFFD9435F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE8EC),
    onErrorContainer = Color(0xFF7A1C30),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA798F7),
    onPrimary = Color(0xFF1A1629),
    primaryContainer = Color(0xFFB3A2F3),
    onPrimaryContainer = Color(0xFF1C182B),
    secondary = Color(0xFFA798F7),
    onSecondary = Color(0xFF1A1629),
    secondaryContainer = Color(0xFF22202B),
    onSecondaryContainer = Color(0xFFF1EFF8),
    background = Color(0xFF121216),
    onBackground = Color(0xFFF2F1F7),
    surface = Color(0xFF19191F),
    onSurface = Color(0xFFF2F1F7),
    surfaceVariant = Color(0xFF22222A),
    onSurfaceVariant = Color(0xFF9894A8),
    outline = Color(0xFF434052),
    outlineVariant = Color(0xFF2A2934),
    error = Color(0xFFF8859A),
    onError = Color(0xFF470D19),
    errorContainer = Color(0xFF4B1E28),
    onErrorContainer = Color(0xFFFFD8DF),
)

internal val LocalAbuDark = staticCompositionLocalOf { false }

@Composable
internal fun AbuTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAbuDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
