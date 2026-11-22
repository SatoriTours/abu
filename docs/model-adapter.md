# 模型接入

## 当前配置
- 服务：https://magpie.home.jimxl.com/v1
- 模型：group/deepseek-flash-4.1
- 密钥：用户授权的 ~/.config/abu/model.env，仅后端读取，不进入仓库或安卓。
- 已验证 /v1/models 包含模型，真实 /v1/chat/completions 返回 assistant 文本及 stop。
- 用户也可用 ABU_MODEL_BASE_URL、ABU_MODEL_NAME、ABU_MODEL_API_KEY 环境变量覆盖文件配置。

## 启动
在 backend/ 目录运行：
```sh
go run ./cmd/abu -model-env "$HOME/.config/abu/model.env"
```
同时必须配置独立的 ABU_DEV_TOKEN（至少 32 字节随机值）供安卓访问本机 API；这不是模型 API Key。
记录目录由 ABU_DATA_DIR 指定，默认当前工作目录 data。不要公开此开发服务。
-model-env 默认不读取任何文件；没有模型密钥时仍可使用记录模式。
文件权限须仅当前用户可读写（600），解析器只接收已知 KEY=value，不执行 shell，不展开变量。
模型文件可包含 API_KEY 对应变量及上述 Base URL/Name 变量；不接受未知或重复条目。
请不要把模型 API Key 填入安卓的“开发令牌”。

## 接口与回复
Go backend/internal/model/ 包提供 Completer 接口与非流式 Chat Completions 实现。
Go 回复 API 与安卓发送流程已接通：
1. 保存用户消息。
2. POST /api/chat/replies（messageId，retry 可选）。
3. 先保存 running，再调用模型；成功保存 completed/reply；失败保存 failed。
4. 已完成请求重取缓存，不再调用模型；失败或重启遗留 interrupted 必须明确 retry=true。
5. 同时只生成一个回复；只有最新用户消息允许新增回复，旧的已完成回复仍可取回。

上下文最多最近 20 条用户记录及其已完成回复。用户内容与单条回复均限制 4000 Unicode 字符。
聊天接口只支持同步纯文本，没有工具调用、流式输出或多机器人路由。独立的持久后台任务接口见 development-chat.md。
取消/网络错误可能发生在供应商已执行之后，因此明确重试仍可能再次计费，不承诺计费上的 exactly-once。
APP 切页或进程退出可能取消请求；刷新历史后查看结果，不能把该同步流程视作持久后台任务。

## 安全与限制
HTTPS 强制（本机回环测试例外），拒绝 URL 凭据/查询参数，禁止重定向。
45 秒模型超时，1 MiB 上游响应上限；Go 写超时65秒、安卓请求超时60秒。
不会自动重试或把上游错误正文、密钥、用户内容写入错误日志。
返回截断、工具调用、空回复、无效结构或超长回复时明确失败，不冒充完成。
失败后的用户消息保留；回复落盘失败返回错误，可刷新确认，不能保证断电级耐久性。
本地 JSON 是单进程开发存储，生产仍计划使用 PostgreSQL。

## 验证
Go 全量 race/test/vet/build 通过，覆盖配置、鉴权、回复持久化、幂等、显式重试、并发与中断恢复。
Android 11 项共享层测试、Debug 构建、Lint 命令通过；已有 Lint 警告仍保留。
真实 Go → 模型 → 回复保存 → 重复取回 → 服务重启读取验证通过。
获准模拟器 192.168.2.138:5556 实测发送、真实回复展示、Go/APP 重启后重新输入开发令牌恢复历史通过。
已查看发送及恢复截图；测试服务、临时数据和转发已清理，APK 保留。
独立验收仍未完成；旋转、多屏幕、离线发送恢复、流式输出不在本轮已验证范围。
真实验证只发送无敏感内容的 OK 请求；首次直接探测21 token，其他调用 usage 未采集，记为未知。
