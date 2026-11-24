# 多角色助手开发协议（切片 A）

沿用 `127.0.0.1:8080` 和 `Authorization: Bearer <ABU_DEV_TOKEN>`，缺少开发令牌配置时不开放产品接口。角色只是职责与上下文配置，不是账号或安全权限；所有已鉴权开发客户端仍可管理全部角色。角色不能授予工具、外网、账号或记忆权限。

## 角色

| 请求 | JSON 与响应 |
| --- | --- |
| `GET /api/bots` | 200 `{"bots":[...]}`，阿布置顶，其余按创建顺序 |
| `POST /api/bots` | 请求 `{id,name,instructions,avatar}`；首次保存 201 + Bot，原创建内容重提交 200 + 当前 Bot |
| `PUT /api/bots/{id}` | 请求 `{name,instructions,avatar,revision}`；匹配后 200 + Bot，revision 加 1 |
| `DELETE /api/bots/{id}` | 200 `{"deleted":true,"id":...}`；禁止删除 `abu`，需无在途回复/排队或执行中任务/关联生成小程序；通过写前意图与增量回滚保证跨快照一致性 |

Bot 包含 `id,name,instructions,avatar,revision`。冷启动缺少角色文件时只合成 `{"id":"abu","name":"阿布","instructions":"","avatar":"abu","revision":1}`，读取不写文件。只有阿布默认存在，可编辑但不可删除；其他角色均由用户创建，且支持删除（同时清理该角色的会话/聊天记录和已结束任务）。id 允许 1–128 个 ASCII 字母、数字、下划线、连字符，区分大小写；名称非空白、1–40 Unicode 码点；说明最多 4000 码点，空白说明不会送入模型。头像为 `abu/leaf/flame/wave/fluff`。总上限 32（含阿布），请求体上限 32 KiB。创建 revision 为 1，id 不可变。即使已编辑角色，原始创建字段的重提交仍幂等返回已保存的当前角色；同 id 异创建字段返回 409。编辑冲突须刷新、核对后显式再提交，不自动覆盖。

错误响应 `{"error":"代码"}`：400 `invalid_bot`/`invalid_bot_id`，401 `unauthorized`，404 `bot_not_found`，409 `id_conflict`、`revision_conflict`、`bot_limit`、`cannot_delete_default_bot`、`bot_busy` 或 `bot_in_use`，413 `body_too_large`，503 `storage_unavailable`。角色库存储在 `ABU_DATA_DIR/bots.json`（默认从 backend/ 启动时为 `backend/data/bots.json`）。损坏时拒绝启动开发后端，不将损坏角色库当成全新的阿布；写入失败不改变已确认记录。

## 聊天及任务的 botId

聊天消息和任务记录都返回 `botId`。没有 `botId` 的旧记录只归 `abu`；没有角色范围参数的旧客户端也只操作 `abu`。**明确错误的范围不会回落阿布**。非空未知 id 为 404 `bot_not_found`；空、格式非法或歧义范围为 400（`invalid_bot_id`，或 JSON 结构无效时该端点原有输入错误码）。只在表中指定的位置传范围；请求 JSON 字段大小写敏感，不接受重复字段或显式 null。

| 现有端点 | 如何指定角色 |
| --- | --- |
| `GET /api/chat/messages` | 查询 `?botId=leaf` |
| `POST /api/chat/messages` | JSON `{id,content,botId}` |
| `POST /api/chat/replies` | JSON `{messageId,retry,botId}` |
| `POST /api/tasks` | JSON `{id,content,botId}` |
| `GET /api/tasks` | 默认只列阿布；`?botId=leaf` 筛角色；`?allBots=true` 显式列全部，不与 botId 同传 |
| `GET /api/tasks/{id}` | 查询 `?botId=leaf`；全局任务页也必须用记录所属角色查详情 |
| `POST /api/tasks/{id}/cancel` | 查询 `?botId=leaf`，请求体仍为空；不支持全局取消 |

消息 id 和任务 id 分别在各自的 `(botId,id)` 范围去重；不同角色允许使用相同 id。不同角色的记录不会被详情、回复或取消命中：如果该角色中没有此 id，与从未存在的响应相同（`message_not_found` / `task_not_found`；未配置模型时回复入口仍可先报 `model_not_configured`）。全局列表保留创建顺序，不改变读写命名空间。聊天与任务分别全局保留原 200 条上限，不删除或隐式淘汰。

回复仅使用选中角色当前说明（非空白时为 system message）和该角色最近 20 个用户轮次及已完成回复。只允许回复该角色最新待回复记录，其他角色不会阻塞这一检查。任务继续复用一个 worker；提交时将角色说明复制到任务私有快照，重启、排队和运行时均不重新读当前说明，也不在任务 API 里返回快照。旧任务无说明快照，按旧语义执行；显式重跑用新的任务 id。读取旧聊天和任务不执行迁移落盘，后续正常保存时写入 botId；原有 running 恢复规则仍适用。发现坏聊天文件或未知归属时不重写；任务文件无效只禁任务接口。

## Android 共享层与 UI 接线

- `BotsClient(http).list(token): List<Bot>`
- `create(token, id, name, instructions, avatar: BotAvatar): Bot`
- `update(token, bot: Bot): Bot`（传正在编辑的旧 revision，替换草稿字段）。`BotAvatar` 为 `ABU/LEAF/FLAME/WAVE/FLUFF`，`revision: Long`。
- `ChatClient.history/loadHistory(token, botId: String? = null)`、`send(token, id, content, botId = null)`、`reply(token, messageId, retry, botId = null)`，`ChatMessage.botId` 提供归属。
- `TaskClient.list(token, botId: String? = null)`、`listAll(token)`、`create(token, id, content, botId = null)`、`detail/cancel(token, id, botId = null)`，`TaskRecord.botId` 提供归属。
- Kotlin 的 `null` 表示不发送范围（兼容阿布）；传入非法范围会被客户端参数检查或服务端拒绝。用现有禁重定向的 HTTP 客户端。`BotsException`、`ChatException` 和 `TaskException` 提供 `code` 与可选 `statusCode`，协程取消会继续向调用方传播。
- 草稿、待确认提交和迟到响应均按开发令牌会话代次 + botId 隔离；请求开始时捕获 botId，不在请求返回后从当前选中角色取值。全局任务列表使用 `listAll`，列表行键以及详情／取消操作都使用 botId 与 id。创建未确认时复用原 id 和原输入；修改 conflict 不清草稿。预设只用于创建表单，不出现在实际角色列表、聊天或任务数据里。

当前仍是单进程本地快照，不支持多个后端实例共用一套数据文件；没有生产账号、角色授权系统或跨角色信息共享。旧个人待办资源与数据路径未改。原生角色界面已接入，并与后端在 Android API 36／WebView 133 完成切片 A 独立开发验收；小程序切片 B 的底座和正式入口已接入，本地模型桩流程已独立验收；真实 AI 生成尚未通过，按用户决定后续再完善，当前优先聊天。
