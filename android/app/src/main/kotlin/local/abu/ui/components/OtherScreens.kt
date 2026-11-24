package local.abu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.abu.BuildConfig
import local.abu.ui.theme.*

@Composable
internal fun SettingsScreen(
    devToken: String,
    onDevTokenChange: (String) -> Unit,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    checking: Boolean,
    onCheckHealth: () -> Unit,
    healthMessage: String,
    theme: ThemePreference,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "设置",
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.selectableGroup()) {
                        ThemeMode.entries.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 46.dp).selectable(
                                    selected = theme.mode == option,
                                    role = Role.RadioButton,
                                    onClick = { theme.choose(option) },
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = theme.mode == option, onClick = null)
                                Spacer(Modifier.width(10.dp))
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "开发连接检查",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "此检查只证明 Go 服务存活，不代表模型或数据库已就绪。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "地址：127.0.0.1:8080",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = onTokenInputChange,
                            label = { Text("开发令牌") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                onDevTokenChange(tokenInput.trim())
                            },
                            enabled = tokenInput.trim().length >= 32
                        ) {
                            Text("应用令牌")
                        }

                        Text(
                            text = if (devToken.isBlank()) "尚未配置令牌" else "令牌已应用",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (devToken.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "令牌仅保留在本次运行内，重开后需重新填写；生产配对尚未实现。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "请先启动电脑上的 Go 服务，并通过 adb reverse 转发设备端口。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            enabled = !checking,
                            onClick = onCheckHealth
                        ) {
                            Text(if (checking) "检查中…" else "检查连接")
                        }

                        Text(
                            text = healthMessage,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(
                            text = "生产连接和设备配对尚未配置。",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
