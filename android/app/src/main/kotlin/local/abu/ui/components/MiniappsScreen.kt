package local.abu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import local.abu.miniapps.TodoCommand
import local.abu.miniapps.TodoPortGate
import local.abu.miniapps.TodoState
import local.abu.miniapps.TodoTheme
import local.abu.miniapps.TodoVersions
import local.abu.ui.theme.LocalAbuDark
import local.abu.miniapps.TodoWebView

@Composable
internal fun MiniappsScreen(state: TodoState, scope: CoroutineScope) {
    val generation = state.generation
    val gate = remember { TodoPortGate() }
    val dark = LocalAbuDark.current
    val pageBackground = MaterialTheme.colorScheme.background
    val theme = rememberUpdatedState(if (dark) TodoTheme.DARK else TodoTheme.LIGHT)
    var versionsOpen by remember(generation, state.viewing) { mutableStateOf(false) }
    var versionChange by remember(generation, state.viewing) { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(generation) {
        if (state.hasToken && !state.viewing) state.refreshCatalog()
    }

    versionChange?.let { (version, downloadAgain) ->
        AlertDialog(
            onDismissRequest = { versionChange = null },
            title = { Text(if (downloadAgain) "重新下载 $version？" else "切换到 $version？") },
            text = { Text("此操作只更换页面，不会更改已保存的待办。若正在旧版页面编辑，请先返回保存草稿；继续将关闭当前编辑页面。") },
            dismissButton = { TextButton(onClick = { versionChange = null }) { Text("返回编辑") } },
            confirmButton = {
                TextButton(onClick = {
                    versionChange = null
                    scope.launch { state.switchVersion(version, downloadAgain) }
                }, enabled = state.pending == null && !state.busy) { Text("继续") }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            if (state.viewing) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = state::close) { Text("返回目录") }
                    Box {
                        TextButton(onClick = { versionsOpen = true }, enabled = state.bundle != null && !state.busy) {
                            Text("版本 ${state.preferred.orEmpty()}")
                        }
                        DropdownMenu(expanded = versionsOpen, onDismissRequest = { versionsOpen = false }) {
                            if (state.pending != null) {
                                DropdownMenuItem(
                                    text = { Text("请先处理待确认操作") }, onClick = {}, enabled = false
                                )
                            } else {
                                TodoVersions.forEach { version ->
                                    val current = version == state.preferred
                                    val latest = state.catalog?.currentVersion == version
                                    val label = when {
                                        current -> "$version · 当前"
                                        version in state.cachedVersions -> "切换到 $version（已缓存）"
                                        latest -> "下载并切换到新版 $version"
                                        else -> "下载并切换到 $version"
                                    }
                                    val available = version in state.cachedVersions ||
                                        version in (state.catalog?.versions ?: TodoVersions)
                                    DropdownMenuItem(text = { Text(label) }, enabled = !current && available, onClick = {
                                        versionsOpen = false
                                        versionChange = version to false
                                    })
                                }
                                val latest = state.catalog?.currentVersion ?: state.preferred
                                if (latest != null) {
                                    DropdownMenuItem(text = { Text("重新下载 $latest") }, onClick = {
                                        versionsOpen = false
                                        versionChange = latest to true
                                    })
                                }
                            }
                        }
                    }
                }
                val bundle = state.bundle
                if (bundle == null) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("个人待办", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        val message = when {
                            state.authRejected -> state.notice
                            state.busy -> "正在准备页面与数据…"
                            else -> "没有可用的已验证页面。第一次打开需要联网下载。"
                        }
                        Text(message ?: "暂时无法打开个人待办。")
                        if (!state.authRejected) state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!state.authRejected) {
                            OutlinedButton(onClick = { scope.launch { state.open() } }, enabled = !state.busy) {
                                Text("重试打开")
                            }
                        }
                    }
                } else {
                    key(generation, bundle) {
                        var rendererGone by remember { mutableStateOf(false) }
                        if (rendererGone) {
                            Text("待办页面已关闭，请返回目录重新打开。", Modifier.padding(24.dp))
                        } else {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { context ->
                                    TodoWebView(context, bundle, gate, {
                                        generation == state.generation && state.viewing && state.bundle === bundle
                                    }, { command, isActive, reply ->
                                        scope.launch {
                                            if (isActive()) {
                                                state.command(command)
                                                if (isActive() && command !is TodoCommand.Draft) reply(state.pageState(theme.value))
                                            }
                                        }
                                    }, { rendererGone = true })
                                },
                                onRelease = TodoWebView::release,
                                update = {
                                    it.setBackgroundColor(pageBackground.toArgb())
                                    it.updatePageState(state.pageState(theme.value))
                                },
                            )
                        }
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("小程序", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { scope.launch { state.refreshCatalog() } },
                            enabled = state.hasToken && !state.busy
                        ) { Text("刷新目录") }
                    }
                    Text("打开内置的个人待办。离线可查看上次确认的快照，新增或修改需要连接后台。")
                    if (!state.hasToken) Text("请先到设置应用开发令牌。重开应用后需重新填写。")
                    if (state.busy) Text("正在读取小程序…")
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Surface(
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("个人待办", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    state.cachedVersions.isNotEmpty() -> "已缓存版本：${state.cachedVersions.joinToString("、")}"
                                    state.catalog != null -> "可下载版本：${state.catalog?.currentVersion}"
                                    else -> "首次打开需要联网"
                                }
                            )
                            Button(
                                enabled = state.hasToken && !state.busy && !state.authRejected,
                                onClick = { scope.launch { state.open() } }
                            ) { Text("打开个人待办") }
                        }
                    }
                }
            }
        }

    }
}
