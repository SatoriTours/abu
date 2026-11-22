# 安卓客户端

按用户要求参考 /home/jimxl/projects/Daily 的框架与结构；未修改 Daily，未复制业务代码或签名配置。

## 结构
- app/src/main/kotlin/local/abu/：安卓入口与 ui/ 界面。
- shared/src/commonMain/：跨平台数据与网络逻辑（Ktor）。
- shared/src/androidMain/：平台 HTTP 引擎（OkHttp）。
- shared/src/commonTest/：共享逻辑测试。
- gradle/libs.versions.toml：集中版本管理；Gradle Wrapper 固定版本。

与 Daily 对齐：Kotlin Multiplatform 2.4.21、Compose Multiplatform 1.12.2、Material3 1.9.0、
AGP 9.5.0、Gradle 9.6.0、Ktor 3.6.1，JVM 17 字节码，构建使用 JDK 21。
compile SDK 37、target SDK 36、min SDK 26。
保留 Daily 的 AGP 兼容选项 android.builtInKotlin=false 与 android.newDsl=false。
不提前引入未使用的 Koin、SQLDelight、模型 SDK 和业务模块；需要时沿用 Daily 的分层方式。

## 已实现
对话、任务、小程序、设置使用固定底栏；对话详情与待办内页使用各自返回入口。聊天支持真实模型回复与历史；小程序目前仅提供内置的个人待办。
Debug 设置页可应用开发令牌和检查 Go /healthz。聊天及任务使用同一开发令牌，没有生产配对。
健康检查、聊天和任务共用 Compose 容器管理的 HTTP 客户端，不跟随重定向，连接和请求有超时。

### 布灵外观
- 设置 → 外观可选择跟随系统（默认）、浅色或深色，重开仍保留。对话列表只显示真实助手阿布；点击标题旁的搜索才展开，关闭即清除筛选。单行消息输入的键盘“完成”只收起键盘，点击发送才提交。
- 系统关闭动画时不播放布灵动效。布灵双主题、原生输入法、头像动效和待办 v3 已在指定设备 Android 16（API 36）、WebView 133 通过独立实机验收，包含断网恢复、重试保留、前后台轨迹和旧缓存兼容。其他设备与生产发布不在已验范围。批准稿不是实拍。

### 使用任务页
- 进入任务页或返回前台会读取后台记录，也可手动刷新。单次任务最多 4000 个 Unicode 字符；最多保留 200 条任务。
- 展开任务可查看正文、结果或错误。排队中和执行中的任务可以取消；取消不保证远端停止计算或计费。失败、中断或取消后，可明确重新执行：新建一条任务，旧记录不变。
- 提交未确认时，请点“重试提交”复用原编号和原文。若放弃重试，任务仍可能已经创建，请刷新核对再新建。
- 状态切页保留。关闭 APP 不会停止已提交的任务，但 Go 后台须继续运行。重开 APP 后重新应用令牌即可加载已保存的记录；令牌、草稿和未确认提交信息不跨进程保留。

### 使用个人待办
- 在设置应用开发令牌，打开“小程序 → 个人待办”。首次下载页面和读取数据需要联网；之后可离线查看带快照时间的上次已确认记录。
- 新增、编辑、勾选和删除需要与后台连接；删除需要二次确认。断线后点击“刷新数据”重新连接。提交未确认时只能显式重试，不能离线编辑或自动同步。
- 在页顶版本菜单下载或切换 v1/v2/v3。首次联网默认 v3，已有版本不会自动换，v1/v2 保持原网页外观。回退只换页面，不回退数据。重开 APP 后须重新应用同一开发令牌，才能使用本地已验证缓存。详见 [小程序协议](../miniapps/README.md)。

## 构建
```sh
cd android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --no-daemon :shared:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

APK：app/build/outputs/apk/debug/app-debug.apk。
后台任务开发自测覆盖共享层 15 项和 APP 16 项单测（任务状态 9 项、导航 7 项），Debug 构建与 Lint 命令通过。
Lint 仍有仓库既有警告（依赖、target SDK、图标、备份规则等），第三方 lifecycle Lint 检查因 API 不兼容而跳过。
已在获准模拟器验证任务入口、无令牌禁用态、离线提示和刷新、多行草稿切页保留，并目视检查对应截图。后台任务真实模型闭环与独立验收、旋转和其他尺寸仍未覆盖。

## 本机开发连接

后续安卓测试固定使用用户指定的 `adb connect 192.168.2.138:5556`，每条设备命令显式指定 `-s 192.168.2.138:5556`。只有用户明确要求才可更换地址；连接失败时报告，不自动选择其他在线设备。历史证据中的其他地址仅为当时记录。
在电脑运行 backend/ 下的 go run ./cmd/abu。
确认测试设备后，执行 adb -s <设备ID> reverse tcp:8080 tcp:8080，
安装 Debug APK，在设置页检查连接。已在获准模拟器安装并验证；测试后移除转发并停止临时 Go 服务，APK 保留。
仅 debug 对 127.0.0.1 开放 HTTP；release 禁止明文且不显示开发检查按钮。
Go 仍只监听本机，生产 HTTPS、设备配对另行实现。

最新聊天联调：已在授权模拟器发送并显示真实回复，重启后恢复历史通过；详见 ../docs/model-adapter.md。
