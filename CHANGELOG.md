# Changelog

## 0.2.0 — 2026-10-15

- GitHub Actions CI：修正 setup-android 默认安装已移除的旧版 SDK `tools` 包的问题，改用 `packages: ''`；Android SDK 36 编译成功，产物包含 Owner/Viewer 两份 Debug APK。
- 增加 Linux/Windows 两平台服务端测试和 Docker Compose 配置检查工作流（结果以 CI 实际运行记录为准）。

- 协议信令新增 Viewer 初始化就绪屏障；有效 Viewer 准备完成后 Owner 才开始采集和 Offer，过期会话自动结束。
- 修复切换媒体源时旧 SDP/ICE 和迟到的屏幕投射关闭回调影响新会话的竞态。
- Viewer 连接或播放器初始化失败时释放资源；不可恢复的配对认证失败停止自动重试。
- Owner 认证失败时撤销本机已启动的共享状态；屏幕会话结束后恢复非投屏的前台服务类型。
- 删除 coturn 4.18.0 已移除的 `--no-cli` 启动参数。
- 信令回归测试从 10 项增加到 14 项，全部在 Node.js 22 通过。GitHub Actions 已实际编译两份 Debug APK；双端媒体和 HyperOS 3 真机仍未验证。


## 0.1.1 — 2026-10-14

- 修正 Viewer 一次性配对成功后的重连认证：使用持久化令牌，不再重发已消耗的配对码。
- Owner 成功注册后，连接重建不再重发首次入网密钥。
- 仅做源码静态核对；Android SDK 和真机不可用，相关改动尚未完成 APK 编译或设备验证。


## 0.1.0 — 2026-10-13

- 新增单仓库三模块 Android 工程：Owner、Viewer、共享 WebRTC 网络与密钥层。
- 新增显式授权的前台采集服务与配对 UI、屏幕逐次同意、双向聊天。
- 新增 Node.js 零依赖信令服务：单 Owner/Viewer、临时配对码、本机批准、媒体信令路由、断线清理、TURN REST 凭证。
- 新增 Windows/Linux 原生与 Docker Compose 部署配置、GitHub Actions 构建脚本。
- 信令服务 10 项测试通过；APK、Docker 及 Android 16 真机尚未测试。
