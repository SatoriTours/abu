package local.abu.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import local.abu.BuildConfig
import local.abu.data.Bot
import local.abu.data.TaskRecord
import local.abu.data.TaskStatus
import local.abu.ui.model.TaskRecordLimit
import local.abu.ui.model.TaskTextLimit
import local.abu.ui.model.TasksState
import local.abu.ui.model.TaskKey
import local.abu.ui.model.key

@Composable
internal fun TasksScreen(
    state: TasksState,
    bots: List<Bot>,
    onRefresh: () -> Unit,
    onSubmit: (String) -> Unit,
    onRerun: (TaskKey) -> Unit,
    onCancel: (TaskKey) -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }
    var confirmAbandon by remember { mutableStateOf(false) }
    val pending = state.pending
    val selected = state.selectedBotId
    var filterOpen by remember { mutableStateOf(false) }
    fun botName(id: String) = bots.firstOrNull { it.id == id }?.name ?: id

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onRefresh, enabled = state.hasToken && !state.busy) {
                        Text("刷新")
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(
                        onClick = { filterOpen = true },
                        enabled = state.hasToken && !state.busy,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                        modifier = Modifier.heightIn(min = 42.dp),
                    ) {
                        Text(if (selected == null) "范围：全部角色" else "范围：${botName(selected)}")
                    }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                        DropdownMenuItem(text = { Text("全部角色") }, onClick = {
                            state.selectBot(null); filterOpen = false
                        })
                        bots.forEach { bot ->
                            DropdownMenuItem(text = { Text(bot.name) }, onClick = {
                                state.selectBot(bot.id); filterOpen = false
                            })
                        }
                    }
                }
                if (selected == null && pending == null) {
                    Text(
                        "所选范围为全部角色。新建前请选择负责的角色。",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text(
                    "每条任务只执行一次文本请求。关闭应用后任务仍可继续，但 Go 后台须保持运行。取消不保证远端停止计算或计费。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!state.hasToken) {
                item {
                    TaskNotice(
                        if (BuildConfig.DEBUG) "请先到设置应用开发令牌，再回来查看或创建任务。重开应用后需重新填写。"
                        else "此版本尚未配置生产连接和设备配对。"
                    )
                }
            }
            state.operation?.let { operation ->
                item {
                    Text(
                        when (operation) {
                            TasksState.Operation.REFRESH -> "正在读取任务…"
                            TasksState.Operation.SUBMIT -> "正在提交任务…"
                            TasksState.Operation.CANCEL -> "正在请求取消…"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            state.error?.let { message -> item { TaskNotice(message, isError = true) } }
            if (state.modelConfigured == false) item { TaskNotice("后台未配置模型。仍可查看任务记录。") }
            if (state.storageAvailable == false) item { TaskNotice("任务存储不可写。仍可查看上次确认的记录。") }
            if (state.atCapacity) item { TaskNotice("已达 $TaskRecordLimit 条记录上限，不能再新建任务。") }

            item {
                TaskSurface {
                    Text(if (pending != null) "待确认 · ${botName(pending.botId)}"
                        else selected?.let { "新建任务 · ${botName(it)}" } ?: "单次文本任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    AbuTextField(
                        value = pending?.content ?: state.draft,
                        onValueChange = state::edit,
                        label = if (pending == null) "任务正文" else "待确认正文",
                        placeholder = "输入要后台执行的文本任务…",
                        readOnly = state.busy || pending != null,
                        enabled = state.hasToken && (selected != null || pending != null),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        isError = pending == null && state.draftLength > TaskTextLimit,
                        supportingText = if (pending == null) {
                            if (state.draftLength > TaskTextLimit) "最多 $TaskTextLimit 字符，请缩短正文。"
                            else "${state.draftLength} / $TaskTextLimit 字符"
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pending != null && state.operation != TasksState.Operation.SUBMIT) {
                        Text(
                            "尚未确认提交。重试会使用同一任务编号和原文，不会另建任务。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val target = pending?.botId ?: selected
                    Button(onClick = { target?.let(onSubmit) }, enabled = target?.let(state::canSubmitFor) == true) {
                        Text(if (pending == null) "创建任务" else "重试提交")
                    }
                    if (pending != null) {
                        TextButton(onClick = { confirmAbandon = true }, enabled = !state.busy) {
                            Text("放弃未确认提交")
                        }
                    }
                }
            }

            item {
                Text("任务记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (state.visibleTasks.isEmpty()) {
                item {
                    val message = when {
                        !state.hasToken -> "应用开发令牌后可读取记录。"
                        state.operation == TasksState.Operation.REFRESH -> "正在从后台加载记录。"
                        state.storageAvailable == null -> "尚未读取记录，可点击刷新。"
                        else -> if (selected == null) "还没有任务" else "这个角色还没有任务"
                    }
                    TaskNotice(message)
                }
            }

            items(state.visibleTasks.asReversed(), key = { "${it.botId}:${it.id}" }) { task ->
                TaskCard(
                    task = task,
                    botName = botName(task.botId),
                    canCancel = state.canCancel(task),
                    canRerun = state.canRerun(task),
                    onCancel = { onCancel(task.key) },
                    onRerun = { onRerun(task.key) }
                )
            }
        }
    }

    if (confirmAbandon && pending != null) {
        AlertDialog(
            onDismissRequest = { confirmAbandon = false },
            title = { Text("放弃此次重试？") },
            text = { Text("任务可能已在后台创建。放弃只会清除本次重试信息，不会取消任务。请先刷新查看记录，避免重复创建。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.abandon()
                        confirmAbandon = false
                    },
                    enabled = !state.busy
                ) { Text("确认放弃") }
            },
            dismissButton = { TextButton(onClick = { confirmAbandon = false }) { Text("继续保留") } }
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskRecord,
    botName: String,
    canCancel: Boolean,
    canRerun: Boolean,
    onCancel: () -> Unit,
    onRerun: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    TaskSurface {
        Text(
            when (task.status) {
                TaskStatus.QUEUED -> "排队中"
                TaskStatus.RUNNING -> "执行中"
                TaskStatus.COMPLETED -> "已完成"
                TaskStatus.FAILED -> "执行失败"
                TaskStatus.INTERRUPTED -> "已中断"
                TaskStatus.CANCELLED -> "已取消"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Text("负责角色：$botName", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) {
            val context = LocalContext.current
            fun copyText(text: String) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
                clipboard.setPrimaryClip(ClipData.newPlainText("abu-task", text))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(task.content) }
                task.result?.let { resultText ->
                    Text(
                        "结果：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MarkdownContent(
                        text = resultText,
                        onCopyCode = ::copyText,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                task.errorCode?.let {
                    Text(
                        when (it) {
                            "model_failed" -> "模型调用失败。"
                            "model_timeout" -> "模型调用超时。"
                            "interrupted" -> "任务在后台退出或重启时中断。"
                            "storage_uncertain" -> "最终结果未能确认写入存储。"
                            else -> "任务未成功完成。"
                        },
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text("更新：${task.updatedAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(task.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "查看详情") }
            when (task.status) {
                TaskStatus.QUEUED, TaskStatus.RUNNING ->
                    TextButton(onClick = onCancel, enabled = canCancel) { Text("取消任务") }
                TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELLED ->
                    TextButton(onClick = onRerun, enabled = canRerun) { Text("重新执行") }
                TaskStatus.COMPLETED -> Unit
            }
        }
    }
}

@Composable
private fun TaskSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun TaskNotice(text: String, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) colors.errorContainer.copy(alpha = 0.45f) else colors.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.error else colors.onSurfaceVariant,
        )
    }
}
