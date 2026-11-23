package local.abu.ui.components

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import local.abu.R
import local.abu.miniapps.GeneratedOpenPage
import local.abu.miniapps.GeneratedPageHost
import local.abu.miniapps.GeneratedWebView
import local.abu.ui.model.GeneratedRoute
import local.abu.ui.model.GeneratedUiState
import local.abu.ui.model.PublicationIntent
import local.abu.ui.theme.LocalAbuDark

@Composable
internal fun GeneratedPageScreen(
    state: GeneratedUiState, route: GeneratedRoute.View, scope: CoroutineScope, back: () -> Unit,
) {
    val model = state.page?.takeIf {
        it.page.bundle.manifest.appId == route.appId && (it.page.bundle.manifest.version == 0) == (route.previewJob != null)
    }
    val page = model?.page
    val dark = LocalAbuDark.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    var installedTheme by remember(page) { mutableStateOf(dark) }
    var host by remember(page) { mutableStateOf<GeneratedWebView?>(null) }
    var intent by remember(page) { mutableStateOf<PublicationIntent.Publish?>(null) }
    var reviewingPending by remember(page) { mutableStateOf(false) }
    var message by remember(page) { mutableStateOf<String?>(null) }

    // A remount changes busy/page. Only dispatch from their observer; a key change must not cancel the reopen.
    val desiredDark by rememberUpdatedState(dark)
    val reviewingInstall by rememberUpdatedState(intent != null)
    LaunchedEffect(dark, installedTheme, page, state.busy, model?.approval, model?.pending, intent) {
        if (page != null && dark != installedTheme && !state.busy && model?.approval == null &&
            model?.pending == null && intent == null) {
            val target = dark
            state.requestTheme(scope, route, page) { desiredDark == target && !reviewingInstall }
        }
    }

    DisposableEffect(host,lifecycle,page) {
        val view = host
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    view?.onPause()
                    intent = null
                    if (page != null) state.page?.approval?.let { state.dismiss(page,it) }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val approval = model?.approval
    LaunchedEffect(approval, lifecycleState) {
        if (approval != null && page != null) {
            if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                state.dismiss(page,approval)
            } else {
                val now = System.nanoTime() / 1_000_000
                delay((approval.expires - now).coerceAtLeast(0))
                state.dismiss(page,approval)
                message = "确认已超时。请在页面重新提出变更。"
            }
        }
    }
    if (approval != null && page != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        GeneratedReviewDialog(
            title = "确认保存数据？",
            appName = route.title,
            revision = approval.update.baseRevision,
            old = approval.prior.document, next = approval.update.document,
            confirmLabel = "确认保存",
            onConfirm = { scope.launch { state.confirm(page,approval) } },
            onDismiss = { state.dismiss(page,approval) },
            enabled = !state.busy,
        )
    }
    if (reviewingPending && model?.pending != null && page != null) {
        val pending = model.pending
        GeneratedReviewDialog(
            title = "核对未确认写入",
            appName = route.title,
            revision = pending.update.baseRevision,
            old = model.confirmed?.snapshot?.document,
            next = pending.update.document,
            confirmLabel = if (pending.conflict) "放弃原提交" else "重试原提交",
            onConfirm = {
                reviewingPending = false
                scope.launch { if (pending.conflict) state.discard(page) else state.retryData(page) }
            },
            onDismiss = { reviewingPending = false },
            enabled = !state.busy && state.retryGate.allows(page) && model.online,
        )
    }
    intent?.let { bound ->
        AlertDialog(
            onDismissRequest = { intent = null },
            title = { Text("确认安装并启用？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(route.title)
                Text(if (bound.version == 0) "首次安装；不会改变数据。" else "从资源 v${bound.version} 更新；不改变数据。")
                Text("预期激活修订：${bound.revision}")
                Text("已预览资源校验指纹：${bound.hash}")
                Text("仅安装此预览对应的版本。生成页面仍是不可信内容。")
            } },
            confirmButton = { TextButton(onClick = {
                intent = null
                scope.launch { state.publish(bound) }
            }, enabled = !state.busy) { Text("确认安装") } },
            dismissButton = { TextButton(onClick = { intent = null }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, enabled = state.canGoBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(route.title, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(if (route.previewJob != null) "草稿预览 · 无生产数据"
                    else page?.let { "资源 v${it.bundle.manifest.version}" }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium)
            }
            if (route.previewJob == null && page != null) {
                TextButton(onClick = { scope.launch { state.refreshData() } }, enabled = !state.busy) { Text("刷新数据") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (model?.confirmed != null) {
            val stamp = model.confirmed
            Text(
                if (model.online) "数据修订 ${stamp.snapshot.revision}"
                else "离线只读 · 数据修订 ${stamp.snapshot.revision} · ${DateFormat.getDateTimeInstance().format(Date(stamp.millis))}",
                Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        } else if (route.previewJob == null && page != null) {
            Text("没有已确认快照。可尝试刷新数据。", Modifier.padding(12.dp))
        }
        if (model?.pending != null) {
            TextButton(onClick = { reviewingPending = true }, enabled = !state.busy) {
                Text(if (model.pending.conflict) "有修订冲突 · 核对原操作" else "写入未确认 · 先刷新再核对")
            }
        }
        if (state.publication.pending != null) {
            TextButton(onClick = { scope.launch { state.retryPublication() } }, enabled = !state.busy) { Text("重试原版本操作") }
        }
        (message ?: state.notice ?: state.publication.error)?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.discardedDocument?.let { draft ->
            var reveal by remember(draft) { mutableStateOf(false) }
            TextButton(onClick = { reveal = true }) { Text("查看保留的数据草稿") }
            if (reveal) AlertDialog(onDismissRequest = { reveal = false }, title = { Text("数据草稿") },
                text = { SelectionContainer { Text(draft, Modifier.verticalScroll(rememberScrollState())) } },
                confirmButton = { TextButton(onClick = { reveal = false }) { Text("返回") } })
        }
        if (page != null && !state.renderingFailed) {
            key(page) {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        FrameLayout(context).apply {
                            try {
                                val mounted = GeneratedPageHost.mount(context,state.runtime,page,dark) {
                                    state.rendererFailed(route)
                                }
                                host = mounted
                                installedTheme = dark
                                addView(mounted, FrameLayout.LayoutParams(-1,-1))
                            } catch (_: Exception) {
                                post { state.rendererFailed(route) }
                            }
                        }
                    },
                    onRelease = { frame -> (frame.getChildAt(0) as? GeneratedWebView)?.release() },
                    update = {},
                )
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(if (state.busy) "正在准备页面…" else "页面未打开。可返回应用中心。")
            }
        }
        val readyDraft = state.job?.takeIf { it.summary.id == route.previewJob && it.publishedVersion == null }
        if (readyDraft != null && page != null && !state.renderingFailed && state.publication.pending == null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    val view = host
                    if (view == null || view.progress < 100 || view.url != page.origin.entryUrl || !view.isShown) {
                        message = "页面还未完成预览，请稍后再确认。"
                    } else {
                        view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (state.runtime.active(page) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    state.acceptPreview(page)
                                    scope.launch { intent = state.preparePublication(readyDraft) }
                                }
                            }
                        })
                    }
                }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("核对并安装")
                }
            }
        }
    }
}

@Composable
private fun GeneratedReviewDialog(
    title: String, appName: String, revision: Long,
    old: JsonObject?, next: JsonObject, confirmLabel: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, enabled: Boolean,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("应用：$appName")
                Text("操作基准数据修订 $revision")
                val changed = (old?.keys.orEmpty() + next.keys).filter { old?.get(it) != next[it] }
                if (changed.isNotEmpty()) Text("变化字段：${changed.take(16).joinToString("、")}")
                Text("原数据", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(old?.let(::prettyJson) ?: "没有已确认快照") }
                Text("拟保存的数据", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(prettyJson(next)) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val reviewJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
private fun prettyJson(value: JsonObject) = reviewJson.encodeToString(JsonObject.serializer(),value)
