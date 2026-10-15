# Changelog

## 0.2.1 — 2026-10-15

- 修复开机自动恢复链：用户先前完成注册并主动开启共享后，重启仅恢复 `remoteMessaging` 前台服务和消息连接；所有摄像头、麦克风和屏幕共享都保持关闭，需 Owner 再次在本机开启。用户点「暂停」后不再自动恢复。
- 消息连接待机时允许正常聊天；Owner 在应用未打开时收到消息会看到不显示正文的通知，最多 20 条消息暂存于进程内供再次打开时读取，进程结束后不保留聊天记录。
- 服务端严格执行五分钟一次性配对有效期和 60 秒观看请求超时，包括在周期清理之前到达的迟到批准；新增两项回归测试（16/16 Node 本地通过）。
- 增补音频设备设置权限；更新 Android 双端版本号。GitHub CI `38668657617` 已通过 Android 36 编译；真机后台和开机行为仍需实测。
- 增加 Android 16（API 36）模拟器 Owner/Viewer 安装与启动烟测工作流；它只验证应用启动，不替代媒体功能、实机及后台耗电测试。
- 新增 Linux CI 中实际启动 Node 信令与 coturn 容器的检测，包括容器内健康检查和信令容器重启；不将此结果外推为公网 TURN 中继成功。

## 0.2.0 — 2026-10-15

- GitHub Actions CI：修正 setup-android 默认安装已移除的旧版 SDK `tools` 包的问题，改用 `packages: ''`；Android SDK 36 编译成功，产物包含 Owner/Viewer 两份 Debug APK。
- 增加 Linux/Windows 两平台服务端测试和 Docker Compose 配置检查工作流；GitHub Actions #38666630029 的三个 Job 全部通过。

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
