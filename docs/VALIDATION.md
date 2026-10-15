# CompanionShare 验证记录

日期：2026-10-15（北京时间）
版本：源码预览 0.2.0

## 已实际执行

- Node.js v22.16.0：`cd server && npm test`，14 个测试全部通过（见 `server-test-results.txt`）。新增 Viewer 初始化就绪握手、旧会话 SDP/ICE 拒绝和握手超时测试。
- Linux 本机 Node 进程启动：在临时 ENROLLMENT_KEY 与 DATA_FILE 下启动服务，GET `/healthz` 返回 HTTP 200 和 `{"status":"ok"}`；测试结束后进程已关闭。未再次运行 Linux 封装脚本。
- Node.js `--check`：信令模块 JavaScript 静态语法检查通过。
- Python XML 检查：两份 AndroidManifest.xml 和 strings.xml 可正常解析；两份 Kotlin Activity/Service 引用的字符串资源均存在。
- 配置文件 YAML：使用 Python PyYAML 解析，验证为有效 YAML（不替代 Docker Compose 执行）。
- 已检索公开 Maven 和 Android 官方开发文档，选择 WebRTC Android SDK 150.7871.01、AGP 8.13.2、Gradle 8.13、compileSdk 36；这仅证明版本选择有公开依据，并非依赖成功下载或编译。
- 2026-10-15 通过 coturn 4.18.0 官方发布信息确认 `--no-cli` 已移除；Docker Compose 删除该参数，未执行真实容器启动。
- Android 两端针对 WebRTC 会话竞态做了源码级修复与一致性审查；此项未经过 Kotlin/Android 编译验证。

## 未通过验证、禁止宣称完成

1. **Owner APK / Viewer APK：未构建。** 当前工作容器没有 Android SDK 和 Gradle，命令行无法解析 Android SDK 仓库域名。需要 GitHub Actions 或可用 Android SDK 的构建机实际编译。
2. **真实双端 WebRTC 音视频：未测试。** 尚未安装到两台 Android 手机。
3. **HyperOS 3 / Android 16：未真机测试。** 后台摄像头、麦克风、锁屏 MediaProjection、中断恢复和耗电数据 Unknown。
4. **Windows / Linux Docker 部署：未启动容器验证。** 当前环境没有 Docker；需 Windows 和 Linux 主机进行各自启动、端口和 TURN 中继测试。
5. **安全穿透：未经过专业审计。** 密钥绑定、抗重放、网络中间人、设备被 Root 后本机凭证安全等方面均需后续测试。
6. **8 小时耗电和 CPU 基线：未测试。** 目标“小于单核 1%”未验证，不能当作交付性能承诺。

## 真机验收判据（后续执行）

- 安装两个 APK，Owner 绑定且批准唯一 Viewer；其他设备用错误凭证无法接入。
- Owner 主动启用后，熄屏及切后台情况下，被请求的摄像头与麦克风采集与通知在实际固件允许范围内工作。
- 新的屏幕共享请求必须触发本机系统授权；拒绝后 Viewer 不得收到屏幕视频。
- 暂停/解绑立即阻断媒体和凭证，不允许通过缓存的旧 token 恢复访问。
- Wi-Fi 与移动数据切换，分别测试 ICE 直连失败后 TURN 中继路径；统计连接时延与失败率。
- 无 Viewer 时 MediaProjection、摄像头、音频采集与编码器均不应处于活动状态。
- 完整执行 8 小时待机对照，并记录设备电量、CPU 时间与系统省电限制。

**结论：这是带有服务端自动化测试的源码预览包，不是两个已经验证通过、可直接安装的 APK。**
