package local.abu

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import local.abu.ui.AbuApp
import local.abu.ui.theme.AbuTheme
import local.abu.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        val theme = ThemePreference(
            load = { prefs.getString("mode", null) },
            save = { prefs.edit().putString("mode", it).apply() }
        )
        setContent {
            val dark = theme.mode.isDark(isSystemInDarkTheme())
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(0x00151619)
                    else SystemBarStyle.light(0x00FAF9FC, 0xFF302839.toInt())
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            AbuTheme(dark) { AbuApp(theme) }
        }
    }
}
