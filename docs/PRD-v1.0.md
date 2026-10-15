# CompanionShare 产品需求文档（PRD v1.0）

文档日期：2026-10-11  
状态：需求基线；尚未编码、构建或通过实机验证。  
目标消费方：Android 客户端开发者、轻量信令服务开发者、测试验收人员。

## 1. 产品目标

开发一套用于本人手机主动授权共享的双端 Android 应用：被观看端为运行 HyperOS 3 / Android 16 的已 Root 小米手机；观看端为无需 Root 的 Android 手机。双方可异地联网。被观看端长期预授权后，指定观看端可请求观看允许的摄像头、麦克风和屏幕共享内容，并通过文字消息沟通。被观看端在本机能识别共享状态、限制共享源并随时撤销。观看端绝不获得设备操作、Root 或文件管理权限。

“长期预授权”表示本机用户明确开启并维持共享可用状态，不代表免除 Android 系统要求的屏幕共享逐次授权，也不保证重启、系统终止或锁屏后所有采集源仍可自动启用。

## 2. 角色与范围

- Owner（被观看端）：配置权限、批准初始绑定、开启或关闭长期共享、管理允许的媒体源；可见当前观看者和采集状态；可立即断开和解绑。
- Viewer（观看端）：一名已绑定的观看者；请求已允许的媒体源、切换前后摄像头、接收音视频、发送和接收文字消息、主动断开。
- 服务端：仅提供设备在线状态、信令交换、必要的打洞辅助与 TURN 中继。默认不录音录像、不存储音视频或聊天正文。
- V1 仅支持一对一绑定、同一时刻一条视频流；不支持多观看者、文件访问、远程触控、命令执行或第三方设备管理。

## 3. 功能要求

**P0：必须交付**

1. 绑定与撤销：二维码/一次性邀请码配对；本机确认绑定，凭设备密钥鉴权；本机一键暂停共享、断开会话、撤销设备绑定。
2. 长期可用状态：Owner 主动从可见界面开启“允许共享”后，启动系统允许且持续明示状态的后台连接/前台服务；退出应用界面后继续响应请求；不承诺强行重启被用户停止的服务。
3. 远程摄像头：在权限和系统状态允许时，仅对已授权 Viewer 开启前置或后置摄像头；支持切换与关闭；空闲时关闭 Camera 会话和编码器。
4. 远程麦克风：在授权会话内推送麦克风音频；空闲时停止 AudioRecord；在音频资源冲突时显示错误，不强行占用通话音频。
5. 屏幕共享：经 Owner 针对每次新 MediaProjection 会话在本机完成系统授权后，Viewer 可观看屏幕；屏幕锁定、系统停止或授权失效时结束该流并释放资源。
6. 文字聊天：双向实时消息；只在应用聊天界面或系统通知中呈现，不允许 Viewer 借消息操作其他应用。
7. 连接：不同 Wi-Fi/移动网络异地通信；WebRTC 直连优先，失败时经自有 TURN 中继；断网可重新连接，界面明确显示离线、等待本机授权、播放中和错误状态。
8. 只读隔离：Viewer 无触控、输入法、ADB、Shell、Root、文件读取/删除/下载、系统设置更改或第三方应用控制接口。来源切换和发消息是限定的产品操作，不授予通用设备操作权。
9. 隐私提示：Owner 常驻显示“允许共享”状态；实际采集时有明显活动提示和当前媒体源；本机随时可关闭；Viewer 不能隐藏状态或禁用撤销入口。

**P1：视 P0 验收后决定**

- 双向语音对讲；允许捕获的媒体播放音频（受播放应用自身的捕获策略限制）。
- Mi Push 等系统推送唤醒，以降低长期网络保活的待机功耗；需要开发者资质、SDK 和真机验证，不能预先当作既成能力。

**不在本项目范围**

- 电话上下行通话录音或获取所有“听筒音频”；安全界面/受保护画面的强行捕获；隐蔽采集、隐藏系统采集提示、系统授权绕过。
- 远程文件管理、设备操作、录制存档、多人观看、跨平台桌面客户端。

## 4. 状态与主要业务流程

`DISABLED`（共享关闭）→ Owner 本机启用 → `ARMED_IDLE`（可响应请求但不采集）→ Viewer 认证并选择媒体源 → 检查本机允许的媒体源与 Android 授权条件 → `STREAMING`（按需打开采集和 WebRTC）→ Viewer 离开或 Owner 停止 → 立即释放采集/编码资源 → `ARMED_IDLE`。

当 Android 需要本机再次授权时进入 `WAIT_LOCAL_CONSENT`；授权拒绝、超时、权限撤回、锁屏停止屏幕共享或资源被占用时，返回可观察错误并释放本次资源。Owner 关闭长期共享则无条件进入 `DISABLED`，立即拒绝新请求并终止活跃会话。

设备重启后允许恢复轻量注册和连接能力，但摄像头、麦克风或屏幕的敏感采集不能从 `BOOT_COMPLETED` 直接启动；如需要 Owner 重新进入界面启用，Viewer 显示“等待本机重新启用”。系统强制停止应用后不自行绕过用户决定恢复。

## 5. 技术架构和复用策略

- 单仓库、两个独立 Android APK（`owner-app`、`viewer-app`），共享协议与安全基础模块。Kotlin + 原生 Android UI。
- 采集：Camera2/CameraX、AudioRecord、MediaProjection；编码优先 MediaCodec 硬件 H.264，实时音频采用 WebRTC/Opus。起点配置为 720p、15 FPS，自适应码率，按机型能力与网络动态调整。
- 传输：Android 原生 WebRTC；音视频采用 WebRTC 既有安全传输机制，文字通过鉴权的 RTCDataChannel；不单独重复设计媒体加密算法。
- 最小后端：Linux VPS 上的信令服务（WebSocket/HTTPS）和 coturn。P2P 成功时媒体不经过业务服务器；P2P 失败时 TURN 中继，需按实际带宽计费。1 vCPU / 1 GB RAM 可作为信令服务的小规模测试起点，并非 TURN 带宽、并发或可用性保证。
- 设备身份：双端分别生成设备密钥，配对时绑定公钥；会话信令和媒体能力必须按绑定关系与当前本机授权状态校验；撤销后旧会话和旧身份均不可继续访问。
- Root：用户设备具备 Root，但 P0 不依赖 Root 绕过 Android 的授权和采集限制。仅在真机验证表明必要且有明确作用时评估本机辅助组件；绝不向 Viewer 暴露高权限调用链。
- 开源复用：优先审计 Android IP Camera 的 Camera/H.264 实现与 ScreenStream 的屏幕/音频实现；二者主仓库当前均标注 MIT。采用代码前检查实际依赖、许可证和与 Android 16 的兼容性。ScreenStreamWeb 包含 Google Play Integrity、Cloudflare Turnstile 等专用集成，原则上不整体复制其服务端。

## 6. 低功耗与后台行为

- `ARMED_IDLE` 禁止运行摄像头、AudioRecord、MediaProjection 和持续视频编码；不轮询屏幕、音视频或本机输入。
- 轻量控制连接优先事件驱动；有限次断线退避，网络变化时恢复；不持有无限时长 CPU WakeLock，不设置高频轮询。
- Owner 按 HyperOS 3 实机设置允许后台自启动、应用电量策略“无限制”、允许通知；设置入口和是否足以保活以实测为准。
- 暂定实测目标：无连接请求的稳定网络待机阶段，应用平均 CPU 使用率低于单核的 1%，无媒体采集资源占用；8 小时待机对比系统基线测量额外电量消耗。上述指标是研发目标，真实值和电量阈值以同机同网络 A/B 测试校准；不能宣称待机完全零 CPU 或零耗电。
- 服务或系统回收后优先安全降级为离线/待本机启用，而不是为了“永久在线”绕过用户的停止行为。

## 7. 开发顺序与验收门

**阶段 0：Android 16 / HyperOS 3 能力验证**

在真实小米设备上分别验证：本机主动开启共享服务后退后台/锁屏的摄像头与麦克风行为；新 MediaProjection 会话授权与锁屏终止；开机后恢复连接但不启动敏感采集；应用被系统回收的反馈。此阶段应先于后台“随时观看”功能承诺。不可用时采取显示本机授权需求的降级路径。

**阶段 1：最小双端连通**

完成双端绑定、身份认证、信令与 WebRTC P2P/TURN，收发状态和文字消息。验收：异地网络连接、断线恢复、错误身份拒绝、解绑失效。

**阶段 2：媒体分享**

分批实现前后摄像头、麦克风、屏幕共享。验收：仅已允许的设备可观看；每项本机提示有效；权限撤销立即终止；屏幕锁定自动停流；不同媒体源切换后无残留采集资源。

**阶段 3：后台与性能收敛**

启用自启动设置指引、共享状态前台服务、低功耗连接与异常恢复。使用 Android Studio Profiler、Perfetto、`batterystats` 对比 8 小时待机和实际推流负载。验收：待机没有媒体采集；后台服务状态准确；耗电实测并与基线比较；系统强制停止不自动重启采集。

**阶段 4：交付**

分别构建并安装 Owner APK 与 Viewer APK；在 Android 16 真机及一台无 Root Android 观看机上执行集成测试；交付源码、两个 APK、最小 VPS 配置与操作文档，记录实际通过/失败项。不得用模拟器、编译通过或代码审查替代真实设备验收。

## 8. 当前决策记录与未验证事实

**已确认**：一对一双 Android 端、Owner 小米 HyperOS 3/Android 16/Root、Viewer 无 Root；长期预授权 A；远程只读；可随时本机撤销；低功耗待机；异地网络；允许使用小型 Linux 服务端兜底。

**采纳默认**：一个视频源同时共享、文字聊天、无云存档、设备密钥绑定、WebRTC + 最小信令/TURN、核心功能不依赖 Root 绕过系统限制、优先审计 MIT 开源实现。

**待真机证实**：具体小米机型/固件在前台服务已由本机启动后，后台新开 Camera/AudioRecord 的可用性；系统进程回收后的恢复能力；真机耗电与建立连接时延；NAT 直连率；Android 16 具体 ROM 对投屏、后台通知和锁屏行为的附加限制。

当这些试验不满足预期，必须落实明确的本机再次授权/前台启动降级路径，不能以 Root 或电池“无限制”虚构功能成功。

## 9. 参考依据（2026-10-11 核查）

- Android 官方：MediaProjection 每次新会话授权及锁屏自动结束。https://developer.android.com/media/grow/media-projection
- Android 官方：后台启动带使用时权限的前台服务限制。https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 官方：Android 15 后从 `BOOT_COMPLETED` 启动敏感前台服务的限制。https://developer.android.com/about/versions/15/behavior-changes-15
- Android 官方：播放音频捕获条件。https://developer.android.com/media/platform/av-capture
- 小米澎湃 OS：自启动权限管理。https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624
- WebRTC 官方：STUN、TURN、信令。https://webrtc.org/getting-started/peer-connections
- 开源参考：Android IP Camera（MIT）。https://github.com/DigitallyRefined/android-ip-camera
- 开源参考：ScreenStream（MIT）。https://github.com/dkrivoruchko/ScreenStream
- 开源参考：ScreenStreamWeb（MIT，含额外服务配置依赖）。https://github.com/dkrivoruchko/ScreenStreamWeb
