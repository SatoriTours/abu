package local.abu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.abu.R
import local.abu.data.BotAvatar as AvatarPreset
import local.abu.ui.model.BotsState

@Composable
internal fun BotEditorScreen(
    state: BotsState, onBack: () -> Unit, onSave: () -> Unit, onRefreshConflict: () -> Unit,
    onDelete: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val fields = state.fields
    val locked = state.busy || state.pendingCreate != null
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && state.canDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("删除角色「${fields.name.ifBlank { state.editingBotId.orEmpty() }}」？", fontWeight = FontWeight.SemiBold) },
            text = { Text("将同时清除该角色的聊天记录和已结束的后台任务，且无法恢复。若存在正在进行的回复、排队/执行中任务或关联小程序，将阻止删除。", color = colors.onSurfaceVariant) },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        keyboard?.hide()
                        onDelete()
                    },
                ) { Text("确认删除", color = colors.error) }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = !state.busy) {
                Icon(painterResource(R.drawable.ic_arrow_back), "返回角色列表")
            }
            Text(
                if (state.editingExisting) "编辑角色" else "创建角色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                        "给搭子起个名字，说明它负责什么。职责只用于这个角色，不增加工具权限。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    val nameSize = fields.name.codePointCount(0, fields.name.length)
                    AbuTextField(
                        value = fields.name,
                        onValueChange = { if (it.length <= 160) state.editFields(fields.copy(name = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = "角色名称",
                        placeholder = "例如：小叶、旅行搭子",
                        singleLine = true,
                        enabled = !locked,
                        supportingText = "$nameSize / 40 字符",
                        isError = nameSize > 40,
                    )
                    val instructionSize = fields.instructions.codePointCount(0, fields.instructions.length)
                    AbuTextField(
                        value = fields.instructions,
                        onValueChange = { if (it.length <= 16000) state.editFields(fields.copy(instructions = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = "角色说明（可选）",
                        placeholder = "描述它的风格与负责的事情…",
                        singleLine = false,
                        enabled = !locked,
                        minLines = 3,
                        maxLines = 6,
                        supportingText = "$instructionSize / 4000 字符",
                        isError = instructionSize > 4000,
                    )
                }
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
                    Text("头像预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AvatarPreset.entries.forEach { avatar ->
                            val selected = fields.avatar == avatar
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) colors.primaryContainer else colors.background,
                                border = BorderStroke(
                                    if (selected) 1.5.dp else 0.75.dp,
                                    if (selected) colors.secondary else colors.outlineVariant,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = !locked) { state.editFields(fields.copy(avatar = avatar)) },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    BotAvatar(30.dp, avatar = avatar)
                                    Text(
                                        avatar.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            if (state.pendingCreate != null && !state.busy) {
                Text(
                    "创建尚未确认。重试会使用同一角色编号、原名称、说明和头像。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (state.conflict) {
                OutlinedButton(
                    onClick = { keyboard?.hide(); onRefreshConflict() },
                    enabled = !state.busy,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("刷新并核对最新角色")
                }
                state.review?.let { latest ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surface,
                        border = BorderStroke(0.75.dp, colors.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("后台最新修订 ${latest.revision}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("名称：${latest.name}")
                            Text("头像：${latest.avatar.label}")
                            Text("说明：${latest.instructions.ifBlank { "未设置" }}")
                            Text("核对后可继续使用上方草稿；还需点击保存才会提交。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            TextButton(onClick = state::acceptReview, enabled = !state.busy) { Text("已核对，保留我的草稿") }
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = if (state.canDelete) Arrangement.SpaceBetween else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.canDelete) {
                TextButton(
                    onClick = { keyboard?.hide(); confirmDelete = true },
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("删除角色", color = colors.error)
                }
            }
            Button(
                onClick = { keyboard?.hide(); onSave() },
                enabled = state.canSave,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(when {
                    state.busy -> "正在处理…"
                    state.pendingCreate != null -> "重试创建"
                    state.editingExisting -> "保存角色"
                    else -> "创建角色"
                }, fontWeight = FontWeight.Medium)
            }
        }
    }
}
