# 开发环境聊天记录

个人文字记录已扩展为可配置的模型聊天。真实回复接入见 model-adapter.md；基础文本后台任务的开发 API 和 Android 共享客户端见下文。安卓任务 UI 已接入并完成开发者自测，真实后端与设备闭环待独立验收。

## 启动
在 backend/ 工作目录运行。通过本机环境设置 ABU_DEV_TOKEN（至少 32 字节的随机值），
可选 ABU_DATA_DIR 指定仅用于本项目的私有目录，然后执行 go run ./cmd/abu。
不要把令牌写入仓库、命令日志或公开截图。缺少令牌时只开放健康检查，不开放记录 API。
安卓 Debug 设置页填写同一令牌并点击“应用令牌”，再到聊天页保存记录。
通过已授权设备的 adb reverse tcp:8080 tcp:8080 连接本机 Go 服务；不要将此开发服务公开部署。

## 已实现约束
- 后端默认监听 `127.0.0.1:8080`，支持通过 `-listen` 参数或 `ABU_LISTEN_ADDR` 环境变量配置合法监听地址。
- 会话管理：`GET /api/chat/sessions?botId=...` 列出当前角色会话（默认会话 `default` 恒在并置顶，其余按最近活跃时间倒序）；`POST /api/chat/sessions` 新建会话；`PUT /api/chat/sessions/{id}` 重命名会话（含默认会话）；`DELETE /api/chat/sessions/{id}?botId=...` 删除非默认会话及其消息。每角色最多 50 个会话（含默认会话）。
- `GET /api/chat/messages`：支持 `?botId=...&sessionId=...&before=<msgId>&limit=1..200` 游标分页，返回 `{messages, modelConfigured, sessionId, hasMore, nextBefore?}`。
- `POST /api/chat/messages`：`{id, content, botId?, sessionId?}`；首次 201，相同 `id/content/sessionId` 重试 200，不重复保存。单会话最多 500 条、全局最多 5000 条消息。
- `PUT /api/chat/messages/{id}`：`{content, botId?, sessionId?}`；允许编辑该会话最新一条消息（非生成中），保存后清空原回复状态便于重发；非最新消息返回 409 `not_latest_message`。
- `POST /api/chat/replies` 与 `POST /api/chat/replies/stream`：支持 `messageId`、`retry`、`regenerate`（允许对当前会话最新一条已完成消息重新生成）、`runId`、`botId`、`sessionId`；流式通过 SSE 推送 `delta`/`done`/`error`。单条回复上限 16000 Unicode 字符。
- `POST /api/chat/replies/cancel`：按 `(botId, sessionId, messageId)` 及可选 `runId` 显式中止在途回复，防止迟到取消误杀新一轮重试；已生成部分以 `interrupted` 保留，可显式重试。
- `DELETE /api/chat/messages/{id}` 与 `DELETE /api/chat/messages`：支持 `?botId=...&sessionId=...`，按会话删除单条消息或清空当前会话记录（回复生成中返回 409）。
- 相同 id 不同内容返回 409；无令牌/错误令牌 401；输入无效 400。用户输入最多 4000 Unicode 字符，不接受 role 等额外字段。
- 安卓聊天详情支持多会话切换/新建/重命名/删除、顶部“加载更早消息”分页、流式渲染与停止、已完成回复重新生成、编辑最新消息并重发、会话内搜索与跳转、增强 Markdown（1~6 级标题/粗斜体/删除线/缩进列表/引用/分割线/GFM 表格/代码块复制/安全确认外部链接）、多行自适应输入、消息复制与单条删除/清空对话。
- 后端本地单文件 JSON 快照（`messages.json` 兼容旧数组与 v2 `{version, sessions, messages}` 格式，上限 32 MiB），互斥写入、临时文件同步后原子替换；单进程开发存储。
- 这不是最终 PostgreSQL 实现；禁止多个后端实例共享该文件，不承诺断电级耐久性。
- 安卓设置页支持配置服务端地址（Debug 支持 `http://`/`https://`，Release 强制 `https://`）并通过 AndroidKeyStore AES-GCM 加密持久化访问令牌（重开自动恢复，支持一键清除）；未发送草稿按 `(botId, sessionId)` 持久化到应用私有存储；未确认提交的重试编号仍仅在当前进程内保留。
- 账号连接、长期记忆抽取、数据导出与生产级数据库仍待实现。

## 文本后台任务协议

后端复用聊天的模型配置和 `Authorization: Bearer <ABU_DEV_TOKEN>`，仍只监听 `127.0.0.1:8080`。没有令牌配置时不注册任务路由。任务只向模型发送一条用户文本，不带聊天历史，不执行工具、脚本、邮件或定时动作。

| 请求 | 成功响应 |
| --- | --- |
| `POST /api/tasks`，JSON `{"id":"…","content":"…"}` | 首次保存后立即返回 201 和排队快照；相同 ID 和原文返回 200 和已有记录，不再次运行 |
| `GET /api/tasks` | 200，`{"tasks":[…],"modelConfigured":true,"storageAvailable":true}`，按创建顺序 |
| `GET /api/tasks/{id}` | 200，单条记录 |
| `POST /api/tasks/{id}/cancel`，不带请求体 | 200，返回实际状态；已取消可重复请求，其他终态保持原样 |

单条响应为 `{"task":{…},"modelConfigured":true,"storageAvailable":true}`。记录包含 `id`、`content`、`status`、UTC `createdAt`、`updatedAt`。状态为 `queued`、`running`、`completed`（带 `result`）、`failed`、`interrupted` 或 `cancelled`。任务失败的 `error` 可能是 `model_failed`、`model_timeout`；中断的 `error` 是 `interrupted` 或 `storage_uncertain`，不会返回模型或存储内部错误。

- ID 由客户端生成，只允许 1–128 个 ASCII 字母、数字、下划线或连字符。正文非空白且最多 4000 Unicode 码点，保留原文；结果在码点边界截到最多 4000。提交 JSON 只接受两个准确字段，禁止重复字段和多余值；写请求体最多 64 KiB。
- 错误响应为 `{"error":"代码"}`：400 `invalid_task`，401 `unauthorized`，404 `task_not_found`，409 `id_conflict` 或 `task_limit`，413 `body_too_large`，503 `model_not_configured`、`storage_unavailable` 或 `shutting_down`。无模型时仍可读取、取消和查回已有提交，但拒绝新任务。最多保留 200 条（含终态），满了不删旧记录。
- 单 worker 按队列顺序执行，45 秒模型调用超时；APP 退出或请求断开不会中止任务。取消先保存状态，再取消正在运行的调用，忽略迟到结果；取消不保证模型服务停止计算或计费。
- 快照默认在 `backend/data/tasks.json`，也可用 `ABU_DATA_DIR` 指定私有目录。写入用同目录临时文件同步后原子替换。重启重新调度已保存的 `queued`，把 `running` 改为 `interrupted`，不自动补跑。受控退出取消运行中任务并保存中断状态，保留剩余队列。
- 写入故障会暂停 worker 并拒绝新写入；同进程仍可读取最后确认的记录（`storageAvailable:false`）。运行结果未能确认落盘时显示 `interrupted/storage_uncertain`，不会伪报完成。启动时如果任务快照无效或无法保存恢复状态，只禁用任务，不影响已正常初始化的聊天和健康检查。任务 API 仍要求令牌：缺少或错误令牌返回 401；令牌正确返回 503 `storage_unavailable`，不返回空列表。后端保留原快照，不启动任务 worker 或自动重试；修复快照路径和可写性后再重启。仅供单进程开发使用：不能由多个进程共享同一文件，不承诺断电级耐久性，也没有清理、分页或生产持久化。
- Android 共享层提供 `TaskClient(http)` 的 `create/list/detail/cancel` 方法。使用现有禁止重定向的 `createHealthHttpClient()`，调用方负责关闭 HTTP 客户端。客户端不自动重试或持久化提交；UI 必须保留未确认提交的原 ID 和正文（即使 HTTP 请求超时也重用），确认后用户若明确选择重跑则生成新 ID，旧记录不变。

## 验证
最新模型聊天闭环已在真实后端与获准模拟器通过开发者自测，详见 model-adapter.md。
此前设备离线/检查工具退出137为历史失败，相关日志保留；后续恢复后已补发送/回复/历史恢复验证。
Go race/test/vet/build、Android11项共享层测试与构建通过。独立验收未完成。
