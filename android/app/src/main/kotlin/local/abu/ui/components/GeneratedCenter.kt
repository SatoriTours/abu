package local.abu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import local.abu.R
import local.abu.data.Bot
import local.abu.data.GeneratedApp
import local.abu.data.GeneratedJob
import local.abu.ui.model.*

@Composable
internal fun GeneratedCenter(
    state: GeneratedUiState, bots: List<Bot>, scope: CoroutineScope, author: Bot?,
    onTodo: (() -> Unit)?, onBack: () -> Unit,
) {
    val route = state.route
    var requestedVersion by remember(route) { mutableStateOf<Int?>(null) }
    var discardForm by remember(route is GeneratedRoute.Form) { mutableStateOf(false) }
    fun back() {
        val form = (state.route as? GeneratedRoute.Form)?.form
        if (!state.canGoBack) return
        if (form != null && state.generation.pending == null &&
            (form.request.isNotEmpty() || form.action == GenerationAction.NEW && form.name.isNotEmpty())) {
            discardForm = true
        } else onBack()
    }
    // Keeps real input across settings-only configuration changes; never saves the development credential.
    androidx.activity.compose.BackHandler(route != GeneratedRoute.Center || author != null) { back() }
    if (discardForm) {
        AlertDialog(onDismissRequest = { discardForm = false },
            title = { Text("放弃这次需求？") },
            text = { Text("未提交的需求不会送给模型。") },
            confirmButton = { TextButton(onClick = { discardForm = false; onBack() }) { Text("放弃需求") } },
            dismissButton = { TextButton(onClick = { discardForm = false }) { Text("继续编辑") } })
    }
    if (route is GeneratedRoute.View) {
        GeneratedPageScreen(state, route, scope, ::back)
        return
    }
    if (route is GeneratedRoute.Center) {
        LaunchedEffect(state, author?.id) { state.refreshCenter() }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (route != GeneratedRoute.Center || author != null) {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back, enabled = !state.busy) {
                    Icon(painterResource(R.drawable.ic_arrow_back), if (author != null && route == GeneratedRoute.Center) "返回对话" else "返回应用中心")
                }
                Text(
                    when (route) {
                        GeneratedRoute.Center -> "${author?.name.orEmpty()} · 小程序"
                        is GeneratedRoute.Form -> route.form.action.title
                        is GeneratedRoute.Job -> "生成任务"
                        is GeneratedRoute.Versions -> "资源版本"
                        else -> "小程序"
                    },
                    fontSize = if (route == GeneratedRoute.Center) 26.sp else 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        }

        if (route == GeneratedRoute.Center) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (author == null) Text("小程序", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "生成后可反复使用，打开、刷新和保存不会调用 AI。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.start(GenerationAction.NEW, author?.id) },
                            enabled = state.hasToken && !state.busy && !state.denial &&
                                state.generation.pending == null && state.modelConfigured != false) {
                            Text("创建小程序")
                        }
                        OutlinedButton(onClick = { scope.launch { state.refreshCenter() } },
                            enabled = state.hasToken && !state.busy) { Text("刷新应用中心") }
                    }
                    if (!state.hasToken) Text("请先到设置应用开发令牌。")
                    if (state.modelConfigured == false) Text("后台未配置生成模型。已安装应用仍可使用。")
                    GeneratedNotices(state,scope)
                }
                if (onTodo != null) item {
                    GeneratedCard {
                        Text("个人待办", style = MaterialTheme.typography.titleMedium)
                        Text("内置工具 · 保留 v1、v2、v3 与已保存的待办")
                        OutlinedButton(onClick = onTodo) { Text("打开个人待办") }
                    }
                }
                item { Text("已安装应用", style = MaterialTheme.typography.titleMedium) }
                val entries = (state.apps + state.cached).distinctBy { it.id }
                items(entries, key = { "app/${it.id}" }) { app ->
                    GeneratedCard {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        val live = app.id in state.apps.map { it.id }
                        Text(if (live) "后台资源 v${app.currentVersion} · 激活修订 ${app.revision}" else "仅有已验证的离线缓存")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { state.open(app, offline = !live) } }, enabled = !state.busy) {
                                Text(if (live) "打开应用" else "离线打开")
                            }
                            if (live) OutlinedButton(onClick = { state.manage(app) }, enabled = !state.busy) { Text("版本管理") }
                        }
                        if (author != null && live) AuthorActions(state, app, author.id)
                    }
                }
                if (entries.isEmpty()) item { Text("还没有已安装的生成应用。") }
                item { Text(if (author == null) "生成与建议任务" else "此角色的生成与建议任务", style = MaterialTheme.typography.titleMedium) }
                val jobs = state.jobs.asReversed().filter { author == null || it.botId == author.id }
                items(jobs, key = { "job/${it.id}" }) { entry ->
                    GeneratedCard {
                        Text(entry.name ?: entries.firstOrNull { it.id == entry.appId }?.name ?: "数据建议")
                        Text("${statusLabel(entry.status)} · ${bots.firstOrNull { it.id == entry.botId }?.name ?: entry.botId}")
                        TextButton(onClick = { scope.launch { state.seeJob(entry.id) } }, enabled = !state.busy) {
                            Text("查看任务")
                        }
                    }
                }
                if (jobs.isEmpty()) item { Text("暂无生成任务。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GeneratedNotices(state,scope)
                when (route) {
                    is GeneratedRoute.Form -> GenerationEditor(state,route.form,bots,author)
                    is GeneratedRoute.Job -> JobDetail(state,route.id,bots,scope)
                    is GeneratedRoute.Versions -> {
                        val app = route.app
                        Text(app.name, style = MaterialTheme.typography.headlineSmall)
                        Text("后台当前资源 v${app.currentVersion} · 激活修订 ${app.revision}")
                        Text("回退仅切换页面资源，绝不回退数据。当前未确认的数据写入必须先处理。")
                        AuthorActions(state, app, author?.id)
                        Button(onClick = { scope.launch { state.open(app) } }, enabled = !state.busy) { Text("打开应用") }
                        if (state.cached.any { it.id == app.id }) {
                            OutlinedButton(onClick = { scope.launch { state.open(app,offline = true) } }, enabled = !state.busy) {
                                Text("打开已验证的缓存")
                            }
                        }
                        for (version in app.versions.reversed()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("资源 v$version", Modifier.weight(1f))
                                if (version == app.currentVersion) Text("当前版本")
                                else OutlinedButton(
                                    onClick = { requestedVersion = version },
                                    enabled = !state.busy && state.publication.pending == null
                                ) { Text(if (version < app.currentVersion) "回退到 v$version" else "切换到 v$version") }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            if (route is GeneratedRoute.Form && state.generation.pending == null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = {
                        keyboard?.hide()
                        scope.launch { state.submit(route.form) }
                    }, enabled = state.canGenerate(route.form), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (route.form.action == GenerationAction.SUGGEST) "请求 AI 建议" else "开始生成")
                    }
                }
            }
        }
    }
    requestedVersion?.let { version ->
        if (route is GeneratedRoute.Versions) {
            AlertDialog(onDismissRequest = { requestedVersion = null },
                title = { Text("切换资源到 v$version？") },
                text = { Text("应用：${route.app.name}。先验证完整资源，再切换；数据修订不变。") },
                confirmButton = { TextButton(onClick = {
                    requestedVersion = null
                    scope.launch { state.switchVersion(route.app,version) }
                }) { Text("确认切换") } },
                dismissButton = { TextButton(onClick = { requestedVersion = null }) { Text("取消") } })
        }
    }
}

@Composable
internal fun GeneratedCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun GeneratedNotices(state: GeneratedUiState, scope: CoroutineScope) {
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (state.busy) Text("正在联系后台…")
    state.generation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.generation.pending != null) {
        Text("存在未确认的生成或建议请求。重试不会另建任务。")
        OutlinedButton(onClick = { scope.launch { state.retryGeneration() } }, enabled = !state.busy) { Text("重试原生成请求") }
    }
    state.publication.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.publication.pending != null) {
        Text("资源变更尚未确认，不会自动重试。")
        OutlinedButton(onClick = { scope.launch { state.retryPublication() } }, enabled = !state.busy) { Text("重试原版本操作") }
    }
}

@Composable
private fun AuthorActions(state: GeneratedUiState, app: GeneratedApp, author: String?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !state.busy && state.generation.pending == null && state.modelConfigured != false,
            onClick = { state.start(GenerationAction.UPDATE,author,app) }) { Text("生成新版本") }
        TextButton(enabled = !state.busy && state.generation.pending == null && state.modelConfigured != false,
            onClick = { state.start(GenerationAction.SUGGEST,author,app) }) { Text("请 AI 建议数据修改") }
    }
}

@Composable
private fun GenerationEditor(state: GeneratedUiState, form: GenerationForm, bots: List<Bot>, fixedAuthor: Bot?) {
    var chooseBot by remember { mutableStateOf(false) }
    val enabled = !state.busy && state.generation.pending == null
    val bot = bots.firstOrNull { it.id == form.botId }
    form.target?.let { Text(it.name, style = MaterialTheme.typography.titleLarge) }
    if (fixedAuthor != null) {
        Text("作者角色：${fixedAuthor.name}")
    } else Box {
        OutlinedButton(onClick = { chooseBot = true }, enabled = enabled) { Text(bot?.name?.let { "作者角色：$it" } ?: "选择作者角色") }
        DropdownMenu(chooseBot, onDismissRequest = { chooseBot = false }) {
            bots.forEach { role ->
                DropdownMenuItem(text = { Text(role.name) }, onClick = {
                    chooseBot = false
                    state.setForm(form.copy(botId = role.id))
                })
            }
        }
    }
    if (form.action == GenerationAction.NEW) {
        AbuTextField(
            value = form.name,
            onValueChange = { if (it.length <= 320) state.setForm(form.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = "小程序名称",
            placeholder = "例如：喝水记录、阅读清单",
            singleLine = true,
            enabled = enabled,
            supportingText = "${form.name.codePointCount(0, form.name.length)} / 80 字符",
        )
    }
    AbuTextField(
        value = form.request,
        onValueChange = { if (it.length <= 16000) state.setForm(form.copy(request = it)) },
        modifier = Modifier.fillMaxWidth(),
        label = "需求说明",
        placeholder = "描述你希望这个小程序有哪些功能与界面…",
        singleLine = false,
        minLines = 4,
        maxLines = 12,
        enabled = enabled,
        supportingText = "${form.request.codePointCount(0, form.request.length)} / 4000 字符",
    )
    Text(if (form.action == GenerationAction.SUGGEST)
        "只生成数据建议，不会写入。请核对改动后再确认。"
        else "只有点击开始生成才调用 AI。生成完成后需预览并确认安装，不会自动发布。")
}

@Composable
private fun JobDetail(state: GeneratedUiState, id: String, bots: List<Bot>, scope: CoroutineScope) {
    val job = state.job?.takeIf { it.summary.id == id }
    val summary = job?.summary ?: state.jobs.firstOrNull { it.id == id }
    Text(summary?.name ?: if (summary?.kind == "proposal") "AI 数据建议" else "草稿任务",
        style = MaterialTheme.typography.headlineSmall)
    if (summary != null) {
        Text("状态：${statusLabel(summary.status)}")
        Text("作者：${bots.firstOrNull { it.id == summary.botId }?.name ?: summary.botId}")
    }
    job?.let {
        Text("需求：${it.request}")
        it.publishedVersion?.let { version -> Text("已发布资源 v$version") }
        if (it.error != null) Text(jobFailure(it), color = MaterialTheme.colorScheme.error)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch { state.seeJob(id) } }, enabled = !state.busy) { Text("刷新任务") }
        if (summary?.status in listOf("queued","running")) {
            OutlinedButton(onClick = { scope.launch { state.cancel(id) } }, enabled = !state.busy) { Text("取消生成") }
        }
        if (job != null && summary?.status == "ready") {
            if (summary.kind == "draft") Button(onClick = { scope.launch { state.preview(job) } }, enabled = !state.busy) {
                Text("预览草稿")
            } else Button(onClick = { scope.launch { state.reviewProposal(job) } }, enabled = !state.busy) {
                Text("审阅数据建议")
            }
        }
        if (job != null && summary?.status in listOf("failed","cancelled","interrupted")) {
            TextButton(onClick = { state.redo(job) }, enabled = !state.busy && state.generation.pending == null) {
                Text("用原需求建立新任务")
            }
        }
    }
}

private val GenerationAction.title get() = when (this) {
    GenerationAction.NEW -> "创建小程序"
    GenerationAction.UPDATE -> "生成新版本"
    GenerationAction.SUGGEST -> "AI 数据建议"
}

private fun statusLabel(value: String) = when (value) {
    "queued" -> "排队中"
    "running" -> "执行中"
    "ready" -> "已就绪"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    "interrupted" -> "已中断"
    else -> "未读取"
}
private fun jobFailure(job: GeneratedJob) = when (job.summary.status) {
    "failed" -> "生成未完成或结果未通过校验。可用原需求明确创建新任务。"
    "interrupted" -> "后台重启或关闭中断了此任务，未自动重发模型请求。"
    else -> "本次任务没有可用结果。"
}
