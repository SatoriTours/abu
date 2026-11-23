# 生成小程序底座协议（B）

## 安装与安全边界

只安装一个 Abu APK。用户已接受常规 WebView 的残余联网风险，不要求 OS 级彻底断网。生成页仍是不可信输入：宿主限制精确的虚拟 HTTPS 来源和三文件白名单，设置 CSP、禁止常规网络加载、外部导航、子框架、弹窗、下载、文件／内容访问、Worker 和设备权限；关闭 Cookie 和 DOM 持久化。受控端口不传令牌、系统密钥、任意 URL 或原生方法。上述设置不是绝对断网保证，不证明能覆盖 WebRTC 等浏览器能力或 WebView 漏洞。旧可信 personal-todo 协议与资源不变。

打开、刷新、读取和经原生确认的保存直接调用现有 Go API，不经过模型。只有显式生成／修改或请求 AI 数据建议才调用模型。没有生成或运行任意服务端代码，也没有另装组件。

## HTTP 入口

仍是 `http://127.0.0.1:8080`。除两个 `/data` 端点外都要求现有开发 Bearer token；`/data` 只接受该生成应用的短期 `appToken`。不与旧 `/api/apps` 的会话密钥或目录混用。botId 必填，绑定生成角色而非授权。appId 由原生创建，格式 `gen-[a-z0-9][a-z0-9-]{0,47}`；任务 id、mutationId、激活请求 id 为 1–128 个 ASCII 字母、数字、下划线或连字符。新 appId 与 job id 分开；用随机稳定 ID，不从名称生成。

| 方法与路径 | 请求 → 响应 |
| --- | --- |
| `POST /api/app-drafts` | `{id,botId,appId,name,request,baseVersion}` → Job，首次 201，等价输入重试 200；新建 baseVersion=0，修改传已有资源版本 |
| `POST /api/generated-apps/{appId}/proposals` | `{id,botId,request,baseRevision}` → Job，同一任务 id 命名空间、同一幂等规则。绑定当前数据修订；只生成建议，不写数据 |
| `GET /api/app-drafts` | 200 `{jobs:[{id,botId,appId,kind,status,name?}],modelConfigured}`，创建顺序。列表不带正文或建议 |
| `GET /api/app-drafts/{id}` | 200 Job |
| `POST /api/app-drafts/{id}/cancel` | 空 body → 200 Job；排队／运行可取消，终态原样返回，迟到模型输出不能覆盖取消 |
| `GET /api/app-drafts/{id}/assets/{path}` | 200 原始资源，只能下载 ready 草稿；不签发草稿数据会话 |
| `POST /api/app-drafts/{id}/publish` | `{hash,expectedVersion,expectedRevision}` → 200 `{appId,version,revision}`；首次发布传 0/0，更新须与该草稿的基版本及当前激活修订一致 |
| `GET /api/generated-apps` | 200 `{apps:[{id,name,currentVersion,revision,versions:[1,...]}]}`，revision 是资源激活修订，不是数据修订 |
| `GET /api/generated-apps/{appId}/versions/{version}` | 200 Manifest |
| `GET /api/generated-apps/{appId}/versions/{version}/assets/{path}` | 200 原始资源，旧版本继续可读 |
| `PUT /api/generated-apps/{appId}/active` | `{id,version,expectedRevision}` → 200 `{appId,version,revision}`；显式切换旧资源，资源激活修订加一 |
| `POST /api/generated-apps/{appId}/session` | 空 body → 200 `{appToken,expiresAt}`；仅已发布应用，约 15 分钟，服务重启失效 |
| `GET /api/generated-apps/{appId}/data` | 200 `{revision,document}`，初始 `{"revision":0,"document":{}}` |
| `PUT /api/generated-apps/{appId}/data` | `{mutationId,baseRevision,document}` → 200 新快照；只从原生可信确认流程调用 |

Job 在 summary 字段之外返回 `request,baseVersion,error?,hash?,manifest?,proposal?,publishedVersion?`。kind 为 `draft/proposal`，状态为 `queued/running/ready/failed/cancelled/interrupted`。ready 草稿含 hash+manifest；ready 建议含 `proposal:{baseRevision,document}`。任务详细回执不返回角色系统说明、原始模型响应或令牌；旧文本任务列表不掺入生成任务。UI 聊天可保存 `botId + jobId` 链接，按 job 查询，不解析普通模型回复触发操作。

Manifest 为 `{schemaVersion:1,appId,version,hash,entry:"index.html",assets:[{path,mime,size,sha256}]}`。草稿 version=0，发布为 1–16 的数字，资源固定顺序 `index.html,app.css,app.js`；bundle hash 是三个小写十六进制 SHA-256 顺次拼接后再做 SHA-256。清单必须与实际原始资源字节匹配，校验不证明内容可信。只允许固定路径精确读取，拒绝编码别名、查询和点路径。HTTP 资源头使直接加载不会授权脚本运行；宿主仍须核验来源、页面会话和应用数据权限，不能仅凭资源哈希放行越权请求。

发布绑定 draft hash、当前版本及单调资源激活修订；同一已发布草稿的原请求持久幂等返回历史回执，不再激活或调用模型，修改字段冲突；激活接口保留最近一次激活请求回执，过时修订冲突。数据与资源修订独立，版本发布或回退不改变数据。PUT 的最新一次 mutationId 与**完全相同的请求字节**重试不改变修订，异正文或过期修订为 409，不自动合并。服务端不会得到可信的 Android 点击证明；只向持有 appToken 的原生端开放，网页不能获取 token。不要把 receipt 当成本地缓存切换通知：下载并校验全包后，才允许切换本地已验证版本。

## 边界和错误

最多 15 个生成应用、16 版本/应用；无自动淘汰。三份资源各 ≤64 KiB、总 ≤192 KiB；HTML 非空，CSS/JS 可为空。自由生成页面，不是模板拼装。名称非空白且 ≤80 码点、需求非空白且 ≤4000 码点。数据为严格 JSON 对象、≤64 KiB（输入与规范化存储表示均校验）、最多 8 层容器；重复键、错误 Unicode、非有限或超出互通范围的数字拒绝。请求体上限 128 KiB。

单进程开发快照 `ABU_DATA_DIR/generated.json`，同一任务的结果与 ready 状态原子保存，发布回执与激活一起保存，不依赖两个文件之间的事务。最多保留 300 个生成／建议任务和 48 MiB 生成快照（包括保留结果和任务记录）。旧 text worker 轮流处理两类队列；仅三文件草稿生成（新建或修改，kind=draft）允许最多 90 秒，聊天、普通后台文本与数据建议仍最多 45 秒。模型 HTTP 请求遵守调用截止时间；未指定时默认 45 秒。更早取消或截止优先，超时不自动重发；角色说明和建议的输入数据在提交时冻结。运行中断重启转 interrupted，不重发模型；从未发出的 queued 工作可正常开跑。生成模型输出必须完整 JSON，超限或无效只记录通用错误，不截断代码后置 ready。单快照开发模式不承诺断电级耐久或生产配对，不跨进程共享。

通用错误 `{"error":"..."}`：400 `invalid_generated_request`；401 `unauthorized/invalid_app_token`；403 `app_scope_mismatch`；404 `generated_not_found/bot_not_found`；409 `id_conflict/revision_conflict/mutation_conflict/version_conflict/draft_not_ready/draft_hash_conflict/publication_conflict/generated_job_limit/generated_app_limit/version_limit/generated_storage_limit`；413 `body_too_large`；503 `generation_unavailable/storage_unavailable`。损坏生成文件不会被覆盖，不阻断旧聊天、任务及内置应用。

## Android 共享与原生接入

`GeneratedAppsClient(http)` 使用现有禁止重定向的 HttpClient。方法（dev 表示开发令牌）：
- `createDraft(dev, DraftRequest): GeneratedJob`、`propose(dev, appId, ProposalRequest): GeneratedJob`、`jobs(dev): GeneratedJobs`、`job(dev,id)`、`cancel(dev,id)`。
- `publish(dev,draftId,hash,expectedVersion,expectedRevision)`、`activate(dev,appId,requestId,version,expectedRevision)` → GeneratedPublication。需用户确认，不能由页面触发；复用稳定参数重试。
- `catalog(dev): List<GeneratedApp>`、`manifest(dev,appId,version)`、`asset(dev,manifest,path,draftId=null): ByteArray`。下载、缓存均验证 MIME/长度/摘要；`draftId` 仅用于 version=0。
- `openSession(dev,appId)`、`data(session)`、`write(session,GeneratedUpdate)`；客户端无隐式续期、重定向或模型/写入重试。共享 Android HTTP 工厂关闭 OkHttp 连接故障恢复，也禁止把 `503 Retry-After: 0` 当成自动重发指令（仍保留 503 状态和正文）。写入后丢回执须视为未确认，保留原请求供刷新后显式重试；协程取消继续向上传播。

同 APP 模块中，按每次令牌会话创建 `GeneratedRuntimeState(GeneratedRuntimeNetwork(client, dev), cacheRoot, dev, identityStillActive, changed)`。原生准备 `openPreview(jobId)` 或 `openPublished(appId, version?, online)` 后，在主线程调用 `GeneratedPageHost.mount(context, runtime, page, dark, onFailure): GeneratedWebView` 并挂入原生容器；一个页面仅可有一个宿主。离开入口或切换主题重建时调用 `view.release()`；有待确认变更时保持当前 WebView，处理后才应用主题重建。换身份先 `runtime.retire()`。原生确认 UI 使用 `changed` 中的 `approval`；渲染出错时通过 `onFailure(code)` 回到原生出口，不自动重开。API 29+ 监听 renderer 无响应，API 26–28 保留原生退出及 renderer 崩溃处理；不提高 minSdk。缓存按身份、appId 和版本隔离。

### 页面端 ABI（v1）

- 将 CSS 和脚本分别从 HTML 引用为 `./app.css`、`./app.js`；不使用内联脚本、外部库、数据 URL 脚本或网络请求。`app.js` 加载即监听 `window` 的 `message`，以便收到完成加载后发送的握手。
- 当 `event.data === "abu-generated-v1"` 且收到一个端口时，绑定 `event.ports[0].onmessage` 解析 JSON，再 `port.start()` 并发送 `port.postMessage(JSON.stringify(["read"]))`。只接受首次端口。入口绑定宿主的当前页面、身份和来源；页面不能选 appId、身份、URL 或 native 方法。
- 状态形如 `{revision,document,mode,theme,snapshotTimeMillis?,error?}`。theme 为 `light/dark`；mode 为 `preview/online/pending/offline/unavailable`。只有 online 且没有未确认变更时提供保存操作；offline 仅展示已确认快照及时间，unavailable 的空对象只是占位，不是已确认的生产数据。
- “刷新”再次发送 `["read"]`。在线对当前应用发起实时 Go 读，单在途；预览始终是空的示例数据，不开生产数据会话；显式离线只读缓存。超限、畸形、忙、读取失败分别使用 `rate_limited/invalid_message/busy/read_unavailable`，离线或不能提议使用 `offline/proposal_unavailable`。页面读取不能代替原生显式刷新，从而解锁未确认写入。普通连接故障可保留只读快照；当前页的数据读取或原生刷新若收到明确授权拒绝（401/403、`unauthorized`、`invalid_app_token`、`app_scope_mismatch`），立即关闭该页和端口，不向生成页返回快照。私有确认快照及未确认写入日志保留，供以后合法恢复。
- “保存”发送 `["propose",revision,document]`（发送 JSON 字符串，最多 64 KiB 文档，整消息上限 64 KiB + 128 字节）。原生展示确认后才写入；无网页确认命令。桥每页每秒最多 12 条进入处理，离开、切换身份或 renderer 退出后旧端口关闭。页面收到状态或错误后渲染即可，勿自动高频重试。

原生确认状态绑定当前页面，不信网页自报身份。原生决定页面来源、绑定应用和会话代次；固定消息格式 `["read"]` 或 `["propose",revision,document]`，有限大小/速率，不能选择 app、命令、路径或自带确认布尔值。`reviewModelProposal(page,jobId)` 在当前身份下重取 job 并验证归属和修订。原生应展示 app、旧文档与新文档，调用 `confirm(page,ticket)` / `dismiss(page,ticket)`；票据一次性、最多一个且 60 秒过期，换页、换身份或修订变更即失效。下一条页面消息或原生提议会清掉过期票据，合法新提议可直接换票，无需定时器。确认后先保存原 mutationId/baseRevision/document；每次发送前锁定重试，只有收到响应并持久化确认态才解除。取消、网络异常或确认态落盘失败均不报成功；必须显式 `refresh(page)` 成功后才能 `retry(page)`，不会后台排队；冲突保留正文，原生可审阅后 `discardPending(page)` 并新建变更。不允许重放旧票据。

## 正式 Android 入口

- 在设置应用开发令牌，从“小程序 → 创建小程序”填写名称和需求、选择作者角色；也可在角色对话标题栏打开“此角色的小程序操作”，由当前角色发起生成、修改或数据建议。普通模型回复不会自动触发小程序操作。
- “生成与建议任务”可返回原任务手动刷新、取消或查看结果。超时后在同一令牌会话内用“重试原生成请求”复用原编号与正文。旋转或进程重建遵循现有登录策略，需重新应用令牌；已进入后台的任务可从任务列表找回，未提交的表单与仅在内存中保留的生成／版本请求不跨重建。
- 草稿预览不读取生产数据。“核对并安装”展示与资源指纹和激活修订绑定的原生确认；取消不会发布。运行期间“版本管理”可明确下载、更新或回退，数据修订保持独立。未确认数据写入时不可切版。
- 网页拟议和 AI 数据建议都要在原生对比后确认。数据写入未确认时，原请求保存在当前身份的应用私有存储。先用原生“刷新数据”，再人工重试；刷新网页本身不能解锁。应用有已验证缓存时可离线查看确认快照和时间，但不能离线写入或排队。
- 开发者已在正式 MainActivity 用本地桩验证创建、预览、原生确认安装、打开已安装页面。真实模型效果与完整设备独立验收仍待执行。当前防护不等于 OS 出站沙箱。
