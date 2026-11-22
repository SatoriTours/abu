package local.abu.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import local.abu.BuildConfig
import local.abu.data.TaskRecord
import local.abu.data.TaskStatus
import local.abu.ui.model.TaskRecordLimit
import local.abu.ui.model.TaskTextLimit
import local.abu.ui.model.TasksState

@Composable
internal fun TasksScreen(
    state: TasksState,
    onRefresh: () -> Unit,
    onSubmit: () -> Unit,
    onRerun: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }
    var confirmAbandon by remember { mutableStateOf(false) }
    val pending = state.pending

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
                    Text("任务", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = onRefresh, enabled = state.hasToken && !state.busy) {
                        Text("刷新")
                    }
                }
            }
            item {
                Text(
                    "每条任务只执行一次文本请求。关闭应用后任务仍可继续，但 Go 后台须保持运行。取消不保证远端停止计算或计费。",
                    style = MaterialTheme.typography.bodyMedium,
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
                    Text("单次文本任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = pending?.content ?: state.draft,
                        onValueChange = state::edit,
                        label = { Text(if (pending == null) "任务正文" else "待确认正文") },
                        readOnly = state.busy || pending != null,
                        enabled = state.hasToken,
                        minLines = 3,
                        maxLines = 6,
                        isError = pending == null && state.draftLength > TaskTextLimit,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (pending == null) {
                                Text(
                                    if (state.draftLength > TaskTextLimit) "最多 $TaskTextLimit 字符，请缩短正文。"
                                    else "${state.draftLength} / $TaskTextLimit 字符"
                                )
                            }
                        }
                    )
                    if (pending != null && state.operation != TasksState.Operation.SUBMIT) {
                        Text(
                            "尚未确认提交。重试会使用同一任务编号和原文，不会另建任务。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onSubmit, enabled = state.canSubmit) {
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
            if (state.tasks.isEmpty()) {
                item {
                    val message = when {
                        !state.hasToken -> "应用开发令牌后可读取记录。"
                        state.operation == TasksState.Operation.REFRESH -> "正在从后台加载记录。"
                        state.storageAvailable == null -> "尚未读取记录，可点击刷新。"
                        else -> "还没有任务"
                    }
                    TaskNotice(message)
                }
            }

            items(state.tasks.asReversed(), key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    canCancel = state.canCancel(task),
                    canRerun = state.canRerun(task),
                    onCancel = { onCancel(task.id) },
                    onRerun = { onRerun(task.id) }
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
        if (expanded) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.content)
                    task.result?.let { Text("结果：\n$it") }
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun TaskNotice(text: String, isError: Boolean = false) {
    TaskSurface {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
