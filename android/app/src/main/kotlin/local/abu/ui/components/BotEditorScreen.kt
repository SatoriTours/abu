package local.abu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import local.abu.R
import local.abu.data.BotAvatar as AvatarPreset
import local.abu.ui.model.BotsState

@Composable
internal fun BotEditorScreen(
    state: BotsState, onBack: () -> Unit, onSave: () -> Unit, onRefreshConflict: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val fields = state.fields
    val locked = state.busy || state.pendingCreate != null
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !state.busy) {
                Icon(painterResource(R.drawable.ic_arrow_back), "返回角色列表")
            }
            Text(if (state.editingExisting) "编辑角色" else "创建角色", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("给搭子起个名字，说明它负责什么。职责只用于这个角色，不增加工具权限。")
            val nameSize = fields.name.codePointCount(0, fields.name.length)
            OutlinedTextField(
                fields.name, { if (it.length <= 160) state.editFields(fields.copy(name = it)) },
                Modifier.fillMaxWidth(), label = { Text("角色名称") }, singleLine = true, enabled = !locked,
                supportingText = { Text("$nameSize / 40 字符") }, isError = nameSize > 40,
            )
            val instructionSize = fields.instructions.codePointCount(0, fields.instructions.length)
            OutlinedTextField(
                fields.instructions, { if (it.length <= 16000) state.editFields(fields.copy(instructions = it)) },
                Modifier.fillMaxWidth(), label = { Text("角色说明（可选）") }, enabled = !locked, minLines = 3, maxLines = 6,
                supportingText = { Text("$instructionSize / 4000 字符") }, isError = instructionSize > 4000,
            )
            Text("头像预设", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarPreset.entries.forEach { avatar ->
                    FilterChip(
                        selected = fields.avatar == avatar,
                        onClick = { state.editFields(fields.copy(avatar = avatar)) },
                        label = { Text(avatar.label) }, leadingIcon = { BotAvatar(32.dp, avatar = avatar) },
                        enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            state.error?.let { Text(it, color = colors.error) }
            if (state.pendingCreate != null && !state.busy) {
                Text("创建尚未确认。重试会使用同一角色编号、原名称、说明和头像。")
            }
            if (state.conflict) {
                OutlinedButton(onClick = { keyboard?.hide(); onRefreshConflict() }, enabled = !state.busy) {
                    Text("刷新并核对最新角色")
                }
                state.review?.let { latest ->
                    Surface(color = colors.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("后台最新修订 ${latest.revision}", style = MaterialTheme.typography.titleSmall)
                            Text("名称：${latest.name}")
                            Text("头像：${latest.avatar.label}")
                            Text("说明：${latest.instructions.ifBlank { "未设置" }}")
                            Text("核对后可继续使用上方草稿；还需点击保存才会提交。")
                            TextButton(onClick = state::acceptReview, enabled = !state.busy) { Text("已核对，保留我的草稿") }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { keyboard?.hide(); onSave() }, enabled = state.canSave,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(when {
                    state.busy -> "正在处理…"
                    state.pendingCreate != null -> "重试创建"
                    state.editingExisting -> "保存角色"
                    else -> "创建角色"
                })
            }
        }
    }
}
