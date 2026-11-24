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
import local.abu.ui.model.AndroidConnectionStorage
import local.abu.ui.model.ConnectionPreference
import local.abu.ui.model.SharedPrefsDraftStorage
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
        val connection = ConnectionPreference(
            storage = AndroidConnectionStorage(applicationContext),
            allowCleartext = BuildConfig.DEBUG,
        )
        val drafts = SharedPrefsDraftStorage(applicationContext)
        setContent {
            val dark = theme.mode.isDark(isSystemInDarkTheme())
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(0x00121216)
                    else SystemBarStyle.light(0x00FBFBFD, 0xFF1D1B26.toInt())
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            AbuTheme(dark) { AbuApp(theme, connection, drafts) }
        }
    }
}
