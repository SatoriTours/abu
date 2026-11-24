package local.abu.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.abu.R
import local.abu.ui.navigation.NavTab

@Composable
internal fun AbuBottomBar(selected: NavTab, onSelect: (NavTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant.copy(alpha = 0.85f))
    NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
        for (tab in NavTab.entries) {
            val icon = when (tab) {
                NavTab.CHAT -> R.drawable.ic_tab_chat
                NavTab.TASKS -> R.drawable.ic_tab_tasks
                NavTab.MINIAPPS -> R.drawable.ic_tab_apps
                NavTab.SETTINGS -> R.drawable.ic_tab_settings
            }
            val isSelected = selected == tab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(icon), null, Modifier.size(21.dp)) },
                label = {
                    Text(
                        tab.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    unselectedTextColor = colors.onSurfaceVariant.copy(alpha = 0.72f),
                )
            )
        }
    }
}
