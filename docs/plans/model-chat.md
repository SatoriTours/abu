# 真实模型聊天闭环

已授权服务 https://magpie.home.jimxl.com，模型 group/deepseek-flash-4.1。
用户授权读取 ~/.config/abu/model.env；不回显密钥、不复制到仓库或安卓。
实际验证 /v1/models 存在目标模型，/v1/chat/completions 返回 HTTP 200、assistant OK、stop，usage 21 token。

范围：
- 后端通过显式 -model-env 加载配置（不执行 shell），未指定且无环境密钥仍是记录模式。
- POST /api/chat/replies 接受 messageId/retry；生成结果附在原记录的 reply/replyStatus 字段。
- 先持久化 running，再调用模型；成功保存 completed；失败标记 failed。重启把遗留 running 改为 interrupted。
- 成功重复请求返回已有回复；失败只接受明确 retry=true 再调用。单会话同时最多一次生成。
- 仅最新用户消息可新生成；已有回复可重取。上下文最多最近 20 条记录，单条回复上限 4000 字符。
- HTTP 写超时提高到 65 秒，安卓客户端超时覆盖模型 45 秒限制。
- 安卓先确认用户消息保存，再请求回复；刷新历史展示状态，失败可显式重试。
- 不执行工具，不增加系统权限，不自动读取账号，不自动重新调用失败请求。

验证：配置、请求幂等、失败、恢复及上下文测试先红后绿；全量 Go/Android；有限真实服务端到端测试。
设备离线时如实保留 UI 验收缺口，独立验收不能由自测代替。

结果：已实现并通过 Go/Android 自动化及真实模型、实际设备发送/恢复自测。截图已查看。独立验收仍缺口。
首次探测21 token，后续真实调用 usage 未采集，保留未知；未调用其他项目凭据。
