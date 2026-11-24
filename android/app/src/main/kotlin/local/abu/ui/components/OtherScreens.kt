package local.abu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.abu.BuildConfig
import local.abu.ui.theme.*

@Composable
internal fun SettingsScreen(
    serverUrl: String,
    serverUrlInput: String,
    onServerUrlInputChange: (String) -> Unit,
    devToken: String,
    tokenPersisted: Boolean,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    connectionError: String?,
    onApplyConnection: () -> Unit,
    onClearConnection: () -> Unit,
    checking: Boolean,
    onCheckHealth: () -> Unit,
    healthMessage: String,
    theme: ThemePreference,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("清除已保存的访问令牌？", fontWeight = FontWeight.SemiBold) },
            text = { Text("将从本机安全存储中删除令牌并清空未发送草稿，之后需重新填写令牌才能连接服务。", color = colors.onSurfaceVariant) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearConnection()
                    },
                ) { Text("确认清除", color = colors.error) }
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                Text(
                    text = "设置",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    color = colors.onSurface,
                )
                Text(
                    text = "外观偏好与个人服务连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surface,
                border = BorderStroke(0.75.dp, colors.outlineVariant),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "外观",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceVariant)
                            .padding(4.dp)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ThemeMode.entries.forEach { option ->
                            val selected = theme.mode == option
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) colors.surface else Color.Transparent)
                                    .then(
                                        if (selected) Modifier.border(0.6.dp, colors.outlineVariant, RoundedCornerShape(9.dp))
                                        else Modifier
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { theme.choose(option) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = colors.surface,
                border = BorderStroke(0.75.dp, colors.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "服务连接",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        val connected = devToken.isNotBlank()
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(
                                    if (connected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant,
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (connected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)),
                            )
                            Text(
                                text = if (connected) "已配置" else "未配置",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (connected) {
                                    colors.onSecondaryContainer
                                } else {
                                    colors.onSecondaryContainer.copy(alpha = 0.78f)
                                },
                            )
                        }
                    }

                    Text(
                        text = if (BuildConfig.DEBUG) {
                            "支持本机回环（adb reverse）、局域网 HTTP 或 HTTPS 服务地址。"
                        } else {
                            "正式版本仅支持 https:// 开头的加密服务地址。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )

                    AbuTextField(
                        value = serverUrlInput,
                        onValueChange = onServerUrlInputChange,
                        label = "服务端地址",
                        placeholder = if (BuildConfig.DEBUG) "http://127.0.0.1:8080" else "https://abu.example.com",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    AbuTextField(
                        value = tokenInput,
                        onValueChange = onTokenInputChange,
                        label = "访问令牌",
                        placeholder = "至少 32 个字符的访问令牌",
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    connectionError?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onApplyConnection,
                            enabled = serverUrlInput.trim().isNotEmpty() && tokenInput.trim().length >= 32,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Text("保存并应用", fontWeight = FontWeight.Medium)
                        }
                        if (devToken.isNotBlank()) {
                            OutlinedButton(
                                onClick = { confirmClear = true },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(0.75.dp, colors.outlineVariant),
                                modifier = Modifier.height(44.dp),
                            ) {
                                Text("清除凭证", color = colors.error)
                            }
                        }
                    }

                    Text(
                        text = when {
                            devToken.isBlank() -> "尚未配置访问令牌"
                            tokenPersisted -> "已连接配置：$serverUrl（令牌已加密保存，重开自动恢复）"
                            else -> "已连接配置：$serverUrl（仅本次运行有效，加密保存不可用）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (devToken.isBlank()) colors.onSurfaceVariant else colors.secondary,
                    )

                    HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "连接健康检查",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface,
                            )
                            Text(
                                text = healthMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(
                            enabled = !checking && serverUrl.isNotBlank(),
                            onClick = onCheckHealth,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surfaceVariant,
                                contentColor = colors.onSurface,
                            ),
                        ) {
                            Text(if (checking) "检查中…" else "检查连接")
                        }
                    }
                }
            }
        }
    }
}
