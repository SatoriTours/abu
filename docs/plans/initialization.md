# 项目初始化实施计划

**目标：** 保存需求并建立最小可测试 Go 工程，不将初始化扩展为完整产品。
**架构：** 单仓库，backend/ 放 Go，android/ 与 miniapps/ 先记录边界。
**技术：** Go 标准库；模块路径 abu.local/backend 是本地占位，不代表公开域名。
**设计：** ../product.md

## 约束与验证
复用当前目录和分支；不提交、不部署、不安装新依赖、不连接真实账号。
本轮不创建未验证的 Android Gradle 工程或声称已有可安装 APP。
检查健康检查 JSON、错误路径 404、写方法 405、无业务接口误开放。
Go 全量测试、race、vet 和构建；独立验收缺通道时明确记录未完成。

## 步骤
- [x] 写入产品、架构、路线和目录说明。
- [x] 在 backend/internal/httpapi/handler_test.go 写健康检查与路由边界测试，确认缺少 NewHandler 时失败。
- [x] 实现 NewHandler() http.Handler，GET /healthz 返回 application/json 与 {"status":"ok"}；未知路径 404，POST 405。
- [x] 在 backend/cmd/abu/main.go 接入固定本机监听 127.0.0.1:8080，设置 HTTP 读取/写入/空闲超时。
- [x] go test -race ./...、go vet ./...、go build ./...，保存证据及未验收项。

## 执行结果
Go 测试先因 NewHandler 未实现失败，再实现后通过；race、vet、build 退出码均为 0。
独立验收未完成：未核实到符合当前模型/通道边界的原生独立验收入口。
Android/PWA 尚未实现或设备验收；未启动常驻服务，未提交或推送。
