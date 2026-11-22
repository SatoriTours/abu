package local.abu.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import local.abu.R
import local.abu.ui.navigation.NavTab

@Composable
internal fun AbuBottomBar(selected: NavTab, onSelect: (NavTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(color = colors.outlineVariant)
    NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
        for (tab in NavTab.entries) {
            val icon = when (tab) {
                NavTab.CHAT -> R.drawable.ic_tab_chat
                NavTab.TASKS -> R.drawable.ic_tab_tasks
                NavTab.MINIAPPS -> R.drawable.ic_tab_apps
                NavTab.SETTINGS -> R.drawable.ic_tab_settings
            }
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
                label = { Text(tab.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.primary,
                    indicatorColor = colors.secondaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant
                )
            )
        }
    }
}
