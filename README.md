# CompanionShare

**项目状态：0.2.0 Debug 测试版。Owner / Viewer APK 已通过 GitHub Actions 的 Android 36 实际构建；HyperOS 3 / Android 16 双机实测尚未完成，不属于稳定版。**

CompanionShare 是供手机持有人主动授权的一对一实时共享系统。Owner 为 HyperOS 3 / Android 16 手机；Viewer 是普通 Android 手机。Owner 打开共享后，Viewer 可按权限请求前置摄像头、后置摄像头、麦克风或屏幕画面，并互发文字信息。屏幕共享每次会话需要 Owner 在本机确认 Android 系统弹窗。Root 不用于绕过系统授权，也不暴露到 Viewer。

## 交付内容与现状

| 部分 | 文件 | 当前验证状态 |
|---|---|---|
| 信令服务 | `server/`（Node.js 22+，零 NPM 运行时依赖） | 14 项集成测试通过 |
| Linux 部署 | `deploy/start-linux.sh`、`deploy/docker-compose.yml` | 配置编写完成，正式公网部署未测试 |
| Windows 部署 | `deploy/start-windows.ps1`、Docker Compose | 配置编写完成，Windows 实机未测试 |
| Owner APK 源码 | `android/owner/` | GitHub Actions 已完成 Debug APK 编译，尚未真机安装测试 |
| Viewer APK 源码 | `android/viewer/` | GitHub Actions 已完成 Debug APK 编译，尚未真机安装测试 |
| Android WebRTC | `android/common/` | 已编写，未进行双机媒体传输测试 |
| GitHub CI | `.github/workflows/build.yml` | [GitHub Actions 构建通过](https://github.com/DragonBaiMo/CompanionShare/actions/runs/38666010522) |

0.2.0 增加 Viewer 先初始化、Owner 后发送媒体 Offer 的会话就绪握手，并拒绝旧会话的异步信令；已修正屏幕投射生命周期的资源清理。Android 构建已完成，真机功能验证仍待完成。

**GitHub Actions 已生成两份 Debug APK。** 在成功的 [构建记录](https://github.com/DragonBaiMo/CompanionShare/actions/runs/38666010522) 的 Artifacts 中下载 `CompanionShare-Android-debug`。请在安装前核对它们来自该工作流；构建成功不等于摄像头、麦克风、屏幕投射已通过 HyperOS 3 真机验收。仓库不提交 APK 二进制。

## 推荐部署（Windows / Linux 共用）

前提：已安装 Docker Compose，拥有一个指向服务器公网 IP 的 `share.example.com` 域名（用于有效 TLS），另一个 `turn.example.com` 指向同一公网 IP；放通服务器 TCP 80/443/3478，UDP 443/3478/49160–49200。防火墙和云安全组均需配置。

1. 把 `deploy/.env.example` 复制为 `deploy/.env`。
2. 设置 `DOMAIN`、`TURN_HOST`、`PUBLIC_IP` 为真实值。
3. **分别生成两个不同的高熵密钥**，填写 `TURN_SECRET`（64 字符十六进制）和 `ENROLLMENT_KEY`（32 字节 Base64URL，无 padding）。不要使用示例值。生成方式（Windows PowerShell / Linux 均可在 Node.js 22 中执行）：

   ```bash
   node -e "const c=require('node:crypto');console.log('TURN_SECRET='+c.randomBytes(32).toString('hex'));console.log('ENROLLMENT_KEY='+c.randomBytes(32).toString('base64url'))"
   ```

4. 从仓库根目录运行：

   ```bash
   docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d
   ```

5. 验证 `https://<DOMAIN>/healthz` 返回 `{"status":"ok"}`。Caddy 为外部连接自动申请证书；服务器没有 TLS 证书时 Android 客户端不能连接。检查 TURN 公网候选，需要使用独立的 WebRTC ICE/TURN 测试工具或实际两网设备。

6. 首次在 Owner App 输入 `wss://<DOMAIN>/ws` 和 `ENROLLMENT_KEY`，完成注册后密钥从手机的临时授权保存中移除。Owner 的长期设备密钥和 Viewer 的绑定凭证存入各自 Android Keystore 加密存储中。

7. 打开 Owner 的「主动开启共享」，授予摄像头、麦克风、通知权限；选「生成八位配对码」。Viewer 输入相同的信令 URL 与配对码，Owner 在手机上确认绑定。之后 Viewer 连接、选择媒体源即可。Owner 通过常驻通知和应用内「暂停」随时停止采集，解绑会使旧 Viewer 凭证失效。

8. 媒体会话使用两阶段协商：Viewer 收到接受通知后先初始化 WebRTC 播放器，再发送 `viewer.session.ready`；只有此时 Owner 才创建 Offer 和采集资源。这避免初次连接时跨 WebSocket 的消息竞态。旧会话的 SDP 和 ICE 信令会被拒绝。

注意：Docker Desktop 在 Windows 上对 TURN UDP 中继端口映射的实际可达性与 Linux 主机不同。生产部署必须测试 TURN relay candidate 是否可用；没有经过真实 NAT 互通测试时，不能保证跨所有运营商成功。

## 不使用 Docker：Windows 或 Linux 原生 Node 服务

在 Windows PowerShell 中：

```powershell
$env:ENROLLMENT_KEY = "<使用上面命令生成的 43 字符 key>"
$env:TURN_HOST = "turn.example.com"
$env:TURN_SECRET = "<随机 hex secret>"
.\deploy\start-windows.ps1
```

在 Linux Shell 中：

```bash
export ENROLLMENT_KEY='<43-character-base64url-key>'
export TURN_HOST='turn.example.com'
export TURN_SECRET='<random-hex-secret>'
./deploy/start-linux.sh
```

默认仅监听本机 `127.0.0.1:8087`，供 Caddy / Nginx 反向代理。生产不能把未加密的 WebSocket 直接暴露到公网。也支持设置 `TLS_CERT`、`TLS_KEY` 和 `BIND=0.0.0.0` 运行原生 HTTPS，证书需自行配置。原生模式如需 TURN，必须另外部署 coturn。`data/devices.json` 保存已注册设备的**凭证哈希**，必须定期备份且保护文件权限。

## 构建两份 APK

需要 Android SDK Platform 36、JDK 21、Gradle 8.13 和网络（首次下载 Maven 依赖）。打开 `android/` Gradle 工程，执行：

```bash
cd android
gradle :owner:assembleDebug :viewer:assembleDebug
```

成功后预期位置：

- `android/owner/build/outputs/apk/debug/owner-debug.apk`
- `android/viewer/build/outputs/apk/debug/viewer-debug.apk`

专用仓库已经完成 `Build Android APKs and test signaling` 工作流，实际生成了 `owner-debug.apk` 和 `viewer-debug.apk`；对应 CI 提交 `662e57d41d9cea28e4f39ebe0c9f1e0553786d88`。本机容器未安装 Android SDK，故不把 GitHub 上的编译结果描述为本机编译或真机安装验证。

## 实际业务限制

- 只有 Owner 本机主动开启前台共享服务后，后台摄像头与麦克风请求才可以尝试启动；重启手机或系统杀死服务后，必须再次由 Owner 开启。长期开启不等于无条件无限保活。
- 屏幕共享需要每次新会话本机授权，并可能在锁屏时由系统中断。受保护内容不得绕过 Android 安全机制。
- 没有远程触摸、ADB、Shell、文件访问、监控隐藏、系统提示抑制或通话录音功能。
- 同时仅一路视频；环境麦克风音频随媒体会话启用，单独聊天可通过 TLS WebSocket 转发。媒体会话内优先通过 WebRTC DataChannel 聊天。
- 信令服务器可以看到非媒体会话时经 WSS 发送的文本消息；音视频使用 WebRTC DTLS-SRTP，服务器只负责信令与必要时 TURN 转发。部署者能够读取 TLS 终止后的信令数据，因此不要把此版本视为完全端到端加密的聊天产品。
- 待机时不创建 Camera、MediaProjection、AudioRecord 或编码器，按需创建媒体会话。小米 HyperOS 3 上的保活、唤醒、电量、摄像头授权边界必须实测。

## 验证命令与文档

```bash
cd server && npm test
```

配对、权限隔离及错误分支测试见 `server/test/relay.test.mjs`；详细实测缺口见 `docs/VALIDATION.md`；原 PRD 在 `docs/PRD-v1.0.md`。
