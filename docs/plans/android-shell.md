# 安卓应用壳与后端连接计划及结果

范围：安卓应用壳与 Go 通信，不接模型、真实账号或公开网络服务。
后续授权：允许联网下载依赖；安卓框架和结构参考 Daily。

已执行：
- 采用 Daily 的 KMP + Compose、app/shared、版本目录、Gradle Wrapper 组织。
- shared/commonTest 先验证 HealthClient 缺失导致失败，再实现共享客户端。
- 覆盖正常 JSON、空白 JSON、HTTP 错误、无效响应、超长响应、连接异常、协程取消。
- 实现四个界面入口，设置页异步健康检查，其他界面不模拟产品能力。
- 固定本机连接、禁止重定向、超时与响应长度限制；仅 Debug 本机 HTTP 例外。
- Debug 构建、7 项共享层测试与 Lint 通过；Go race/test/vet 回归通过。

纠正记录：
旧 AGP/Kotlin 和 app 下的测试草稿已由 Daily 对齐方案替换。
Ktor readAvailable 是扩展函数，需要显式导入；暴露 HttpClient 的共享接口要求 api 依赖。
MockEngine 使用真实调度，runTest 虚拟时钟导致误超时，测试改为 runBlocking；取消断言显式 Unit 返回。
Lint 要求网络 domain 明确 includeSubdomains=false，已修正。
allTests/testReleaseUnitTest 并非此 KMP Android 配置提供的任务，失败保留，最终执行实际存在的 Debug 测试入口。

未完成：设备安装、真实 Go 联调、交互/视觉验收、独立验收。
已发现现有设备但归属未确认，未操作；未核实到符合当前执行边界的原生独立验收通道。
Lint 警告见 android/README.md；不在本轮为了版本提示偏离 Daily 工具链。
没有发布、提交、推送；没有启动常驻后端。

## 设备验证补充
用户已授权模拟器 192.168.2.138:5556。安装版本 0.1.0-dev（versionCode 1）。
四页可见/切换、连接失败提示、真实 Go 成功、切页返回保留状态共 8 项断言通过。
已查看四页截图，当前尺寸无明显遮挡。测试后移除转发并停止仅属本任务的 Go 子进程。
以上为开发者自测，不替代独立验收；旋转/进程重建尚未测试。
