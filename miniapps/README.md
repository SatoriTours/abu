# 内嵌小程序

## 固定待办｜开发协议
阶段 2 首版仅内置可信的 `personal-todo`，使用现有 loopback 开发服务，不支持上传、安装或生成任意页面。Android 工作范围是**离线打开与查看，联网新增、修改、勾选和删除**；离线编辑和冲突同步不在首版。

| 路由 | Authorization: Bearer | 成功响应 |
| --- | --- | --- |
| `GET /api/apps` | 开发令牌 | `{"apps":[{"id":"personal-todo","name":"个人待办","currentVersion":"v3","versions":["v1","v2","v3"]}]}` |
| `GET /api/apps/{appId}/versions/{version}` | 开发令牌 | `{schemaVersion:1,id,version,entry,capabilities:["todo.read","todo.write"],assets:[{path,mime,size,sha256}]}` |
| `GET /api/apps/{appId}/versions/{version}/assets/{path}` | 开发令牌 | 原始资源 bytes 和匹配的 Content-Type |
| `POST /api/apps/{appId}/session`（空 body） | 开发令牌 | `{appToken,expiresAt}`，约 15 分钟，原生内存使用 |
| `GET /api/apps/{appId}/data` | 该应用的 appToken | `{revision,items:[{id,text,done}]}` |
| `PUT /api/apps/{appId}/data` | 该应用的 appToken | 提交 `{mutationId,baseRevision,items}`，返回保存后的快照 |

所有成功响应为 200。固定资源最多 16 个，单个 1 字节至 256 KiB，总计 ≤1 MiB。路径最长 192 个 ASCII 字符，按 `/` 分隔，每段以字母或数字开头，其余只允许字母、数字、下划线、点和连字符（单段最多 64 字符）；不接受百分号编码、查询、绝对路径或路径穿越。MIME 按文件类型固定，入口须为 HTML。SHA-256 校验的是原始字节，提高完整性，不提供生产级签名或发布保证。v3 或 v2 回退只更换页面版本，不回退数据。默认不启用 Service Worker。

Go 内置 `backend/internal/httpapi/builtinapps/personal-todo/{v1,v2,v3}/index.html`。旧版 v1/v2 保持原字节，仍共用 v1 的脚本和样式；v3 有独立资源，使用布灵深浅配色。服务从原始字节生成清单，无需手改 SHA-256。Android 只接受这一个应用、三个固定版本和 HTML、JS、CSS 三条固定资源路径。

数据启动为空列表及 revision 0，最多 100 项。条目 ID 和 mutationId 只允许 1–128 个 ASCII 字母、数字、下划线或连字符，条目 ID 唯一，正文非空白且 ≤200 Unicode 码点。PUT 最多 256 KiB，只接受协议中精确的字段、不接受重复键或无效 Unicode。一次成功 PUT 即使清单内容相同也增加 revision；最新一次相同 mutationId、相同完整请求字节的重试返回原结果。当前 mutationId 的正文变化或基准修订不匹配返回 409，不自动覆盖或合并；不保留无限去重历史。会话由进程临时 HMAC 密钥签发，过期或进程重启后需显式申请，不是生产设备配对或账户系统。

每应用独立文件；默认待办在 `backend/data/app-personal-todo.json`（或 `ABU_DATA_DIR`）。损坏或保存失败会禁用该应用数据，不覆盖损坏文件、不影响目录／资源及其他服务；修复文件后重启。此单进程开发快照不支持多进程共享或断电级耐久。错误响应为 `{"error":"代码"}`：400 `invalid_data`，401 `unauthorized` / `invalid_app_token`，403 `app_scope_mismatch`，404 `app_not_found`，409 `revision_conflict` / `mutation_conflict` / `revision_limit`，413 `body_too_large`，503 `storage_unavailable`。

### 原生接入约束
- 共享层提供 `AppsClient(http)`：`catalog`、`manifest`、`asset`、`openSession`、`getData`、`putData`。使用既有禁止重定向的 HTTP 客户端，下载通过 Content-Type、长度和 SHA-256 核验；读缓存可用 `verifyAppAsset` 重验原始 bytes。会话对象绑定 appId，不给页面序列化。客户端不自动续期或重试。
- 缓存按开发令牌对应的本地会话身份及 appId 隔离；不能用短期 appToken 当缓存身份。切换开发令牌时不向新会话暴露旧缓存。原生固定当前应用范围，网页走受控数据消息，不获得开发令牌、appToken 或模型密钥，也不直连 Go。
- 离线只读，明确标注快照时间，不假报写成功、不排后台写队列。提交结果不确定时（包括写请求取消、客户端超时或 `AppsException.writeOutcomeUnknown`），保留原 mutationId、baseRevision、items，仅允许显式重试；冲突要求刷新，不静默吞草稿。
- 完整校验版本的清单与全部资源后才激活，保留上一完整版本。数据快照不跟着页面版本回退。前置探针不是正式 SDK。

### Android 使用与边界
- 设置应用开发令牌后，从“小程序”目录打开个人待办。每条最多 200 Unicode 码点，最多 100 项。新增、编辑、勾选和二次确认删除都须后台明确确认，不作乐观更新。
- 初次需要联网；再次打开可查看最后确认快照和时间。请求失败后禁写，使用“刷新数据”重新申请会话。若开发令牌收到授权拒绝，当前页面和快照会隐藏；离线无法检查曾经有效的令牌是否已撤销。
- 应用私有缓存以令牌 SHA-256 指纹和应用 ID 分区，最多 4 个身份；不自动淘汰可能含未确认操作的身份。令牌不跨 APP 进程持久化，重新应用同一令牌才可读回缓存。v1/v2/v3 资源完整校验后才原子替换，版本切换不改变数据快照。既有版本不会自动升级；v3 只接收原生提供的 `light` / `dark` 主题值，不执行外部样式或脚本。v3 未提交草稿仅保留在当前身份的应用内存中；从旧版切换前会提示先保存。缓存不是备份，哈希不是生产签名。
- 未确认写入在发送前原子记录完整请求，仅用于用户点“重试提交”；不后台重试、不做离线写队列。409 后需刷新，再处理冲突、恢复文字草稿；不会自动覆盖后台数据。待确认过程中不允许切换页面版本。
- 页面固定在 `https://personal-todo.apps.abu.invalid`，仅由原生缓存提供白名单资源；不访问真实网站、不加载其他来源或 Worker。原生仅向精确来源传递一个临时 WebMessagePort，绑定当前页面和令牌会话。端口只处理待办 JSON 命令，不提供令牌、文件、任意代码、外部链接或系统权限。固定可信页面不使用浏览器持久化存储；这不是承载任意生成脚本的沙箱。
- 旧版固定待办（v1/v2）已在 Android 16（API 36）通过独立验收，覆盖在线增改勾删、离线快照和缓存版本切换。布灵界面与 v3 已在同一 API 36／WebView 133 环境通过独立设备和视觉验收，包含主题、在线操作、离线快照、旧缓存迁移及身份隔离。其他设备／WebView 版本和生产发布能力不在已验范围。

## Android WebView 工程验证（仅 Debug）
使用已有 Android SDK/JDK，从 `android/` 执行 `./gradlew --offline :app:assembleDebug`。授权设备上执行：

```sh
adb -s <设备> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <设备> shell am start -W -n local.abu/.miniapps.probe.MiniAppProbeActivity
adb -s <设备> shell dumpsys activity activities | grep topResumedActivity
adb -s <设备> logcat -d -s AbuMiniProbe:I '*:S'
```

确认实验 Activity 在前台。它不接收 URL、读取凭据或连接后端。依次点 **B → A v2 → A v1 → Close WebView → B**；每步等待页面输出。重启独立的 `local.abu:miniapps_probe` 进程后，重新显式启动实验 Activity，分别查看 A 和 B 的 `prior`、`peer` 和 Worker 结果。点 **Test popup / Test navigation / Choose File**，应保留当前页面且日志记录拒绝。API 28 以下不启用探针（无法使用独立数据目录）。不要用真实站点或文件替换固定目标。

### 已验证与限制
- Android 16（API 36）、WebView `com.google.android.webview 133.0.6943.137` 模拟器：A/B 的虚拟 HTTPS 页面为安全上下文，值分别保存在 localStorage、IndexedDB；切换、销毁 WebView、重启实验进程后仍保留，另一应用的标记不可读。A-v1 → A-v2 → A-v1 在同一个 URL 切换打包 HTML，不清用户数据。
- 探针尝试读取另一虚拟来源、未列出的路径、虚拟外部站点、环回 8080 和固定 `file:/content:` 目标：没有取得对应内容。路径与 HTTP(S) 请求在宿主拒绝日志中可见；跨来源 frame 读取抛出 `SecurityError`，`file:/content:` fetch 由浏览器拒绝。用户触发的弹窗、外部导航和文件选择也被宿主拒绝。
- 两类网络加载都关闭。不配置 Worker 脚本拦截时，安全上下文仍存在，但注册在全局 Worker 拦截处失败；平台 `ServiceWorkerClient` 只提供 A 的固定脚本后，A 的注册、ready、根 scope 与页面控制成功。未列入原生路由的 `/offline.html` 从 Cache Storage 命中并打开为文档，进程重启后仍可用；B 看不到 A 缓存，A 也看不到 B 缓存，B 注册 A 脚本被浏览器拒绝。原生打包入口也在同一阻网设置下打开；没有改系统网络或启动服务器。
- 这些结果只涵盖一个设备配置和固定可信样本，不等于可承载任意生成 JavaScript 的安全验收。全局 Worker 回调不带所属 WebView，不能拿它给各应用路由页面或数据。兄弟子域的存储结果不意味着 Cookie 或所有浏览器能力都已隔离。正式方案建议先用每应用固定 HTTPS 来源和原生版本化资源缓存，Worker 是否及如何开放另行决定，避免两套更新机制；还需独立验证设备矩阵、不可信代码、数据权限和资源签名/回滚策略。
