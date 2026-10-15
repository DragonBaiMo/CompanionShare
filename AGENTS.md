# 项目工程指引

遵守 [Coding Rule](https://github.com/DragonBaiMo/Agentic-Agent/blob/main/source/skills/coding-rule/SKILL.md)。不得改动 Owner 长期预授权、显式指示与可撤销性，不得引入远程高权限控制或绕过 Android 系统媒体授权。

## 可复用工具索引

| 工具 | 路径 | 使用场景 | 调用 |
|---|---|---|---|
| 信令协议回归测试 | `server/test/relay.test.mjs` | 修改配对/鉴权/会话协议后 | `cd server && npm test` |
| Linux 原生启动 | `deploy/start-linux.sh` | 非 Docker 本机服务 | `./deploy/start-linux.sh`（先设置环境变量） |
| Windows 原生启动 | `deploy/start-windows.ps1` | 非 Docker 本机服务 | `powershell -File deploy/start-windows.ps1` |
| Docker 双平台部署 | `deploy/docker-compose.yml` | TLS + 信令 + TURN | `docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d` |
| Android 双 APK 构建 | `.github/workflows/build.yml` | GitHub Actions Android SDK 构建 | 手动触发 workflow 或 push |

开发顺序：先对照 `docs/PRD-v1.0.md`，先实现影响范围内的最小测试，再编写代码。服务端测试成功不能证明 APK 可运行；真机验收必须如实记录。
