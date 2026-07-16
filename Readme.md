# Copilot Remote

[![Build](https://github.com/himenekocn/copilot-remote/actions/workflows/build.yml/badge.svg)](https://github.com/himenekocn/copilot-remote/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/himenekocn/copilot-remote)](https://github.com/himenekocn/copilot-remote/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

通过 Android 手机或平板远程使用 VS Code 中的 GitHub Copilot Chat。项目由 VS Code WebSocket 扩展、Miuix Android 客户端，以及可选的 Copilot Chat Remote Bridge 补丁组成。

> [!IMPORTANT]
> 这是社区维护的非官方项目，与 GitHub、Microsoft 或 OpenAI 没有隶属关系。使用前需要在 VS Code 中安装并登录 GitHub Copilot。

## 功能

- ChatGPT 手机端风格的聊天布局，使用 [Compose Miuix](https://compose-miuix-ui.github.io/miuix/zh_CN/) 实现
- 手机单栏布局与平板自适应布局
- Copilot 流式回复、思考过程折叠、工具调用聚合与取消生成
- 模型、Agent 和思考强度选择，包含最大思考模式
- 查看当前工作区的全部历史对话，切换 VS Code 项目时自动隔离和刷新
- 多 VS Code 窗口/工作区发现与切换
- 文件浏览与编辑、终端、命令、Git、Pull Request、扩展、MCP 和 Skills 管理
- 窗口截图与短时录屏
- 多服务器配置、自动重连和可选 FCM 通知
- GitHub Actions 自动构建 Android APK 与 VS Code VSIX

## 架构

```text
Android App
  │
  │ WebSocket + shared key
  ▼
Copilot Remote VS Code Extension
  ├── VS Code Extension/Languages/Commands API
  ├── GitHub Copilot Chat
  └── Optional patched Remote Bridge
```

WebSocket 默认监听 `0.0.0.0:9876`，Android 客户端通过认证密钥连接。多窗口场景下，主扩展实例能够发现并代理其他 VS Code 窗口。

## 下载

前往 [Releases](https://github.com/himenekocn/copilot-remote/releases/latest) 下载：

- `copilot-remote-android-*.apk`：Android 客户端
- `copilot-remote-vscode-*.vsix`：VS Code 扩展
- `copilot-chat-patched-*.vsix`：加入 Remote Bridge 的 GitHub Copilot Chat 修补版

最低要求：

- VS Code 1.90 或更高版本
- 已安装并登录 GitHub Copilot Chat
- Android 8.0 / API 26 或更高版本
- 手机与电脑位于同一可信局域网，或通过安全 VPN 互通

## 安装与连接

### 1. 安装修补版 GitHub Copilot Chat

先在 VS Code 中运行 `Extensions: Install from VSIX...`，安装 Release 中的 `copilot-chat-patched-*.vsix`。它会替换相同版本的官方 GitHub Copilot Chat，并增加 App 所需的本地 Remote Bridge。

> [!WARNING]
> 修补版基于 GitHub Copilot Chat 0.57.1，并使用 `0.57.100` 版本号，避免同版本的官方扩展通过自动更新或扩展同步静默覆盖 Remote Bridge。升级到更新的 Copilot Chat 基线时，需要同步更新修补版。使用 Copilot 仍需要有效的 GitHub Copilot 账号和订阅。

### 2. 安装 Copilot Remote 扩展

在 VS Code 中运行 `Extensions: Install from VSIX...`，选择 Release 中下载的 VSIX，然后重新加载窗口。

在命令面板运行：

1. `Copilot Remote: Generate New Key`
2. `Copilot Remote: Start Server`
3. 可选：`Copilot Remote: Show Connection QR Code`

主要设置：

| 设置项 | 默认值 | 说明 |
| --- | --- | --- |
| `copilotRemote.key` | 空 | WebSocket 认证密钥，必须设置为强随机值 |
| `copilotRemote.host` | `0.0.0.0` | 监听地址；仅本机使用可改为 `127.0.0.1` |
| `copilotRemote.port` | `9876` | WebSocket 服务端口 |
| `copilotRemote.autoStart` | `true` | VS Code 启动时自动启动服务 |
| `copilotRemote.patchedCopilotBridge.enabled` | `false` | 是否优先使用可选的 Copilot Chat Remote Bridge |

启用完整历史会话桥接时，将 `copilotRemote.patchedCopilotBridge.enabled` 设为 `true`。默认桥接地址为 `127.0.0.1:9877`。

### 3. 安装 Android 客户端

在设备上安装 Release APK。Android 可能会要求允许安装未知来源应用。

打开 App 后配置：

- 地址：`ws://<电脑局域网 IP>:9876`
- 密钥：与 `copilotRemote.key` 完全一致

连接成功后，可从侧栏选择 VS Code 实例和当前工作区的历史对话。

## 从源码构建

### VS Code 扩展

需要 Node.js 20 或更高版本：

```bash
cd copilot-remote-vscode
npm ci
npm run compile
npm run build
npx @vscode/vsce package
```

### 修补版 GitHub Copilot Chat

需要 Node.js 22.14 或更高版本：

```bash
cd vscode-copilot-chat
node script/restoreBuildManifest.mjs
npm ci --ignore-scripts
npm run postinstall
npm run build
npx @vscode/vsce package --no-dependencies --allow-package-secrets sendgrid --out copilot-chat-patched.vsix
```

`restoreBuildManifest.mjs` 会从 lockfile 恢复生产打包前被剥离的依赖字段。大型 simulation SQLite 缓存不是扩展运行或打包所必需，因此不会提交到仓库。VSCE 的 SendGrid 规则会将压缩后的 `SG.prototype.entries` 标识符误判为密钥，因此仅精确放行 `sendgrid` 这一项，其他包秘密扫描仍保持启用。

### Android

需要 JDK 17 和 Android SDK：

```bash
cd copilot-remote-android
./gradlew :app:assembleDebug
```

Debug APK 位于：

```text
copilot-remote-android/app/build/outputs/apk/debug/app-debug.apk
```

Release 构建不会在源码中保存签名信息。需要通过环境变量提供签名：

```text
COPILOT_SIGNING_STORE_FILE
COPILOT_SIGNING_STORE_PASSWORD
COPILOT_SIGNING_KEY_ALIAS
COPILOT_SIGNING_KEY_PASSWORD
```

未提供签名时，Gradle 仍可生成 unsigned release；GitHub Actions 使用仓库 Secrets 生成签名 APK。

## GitHub Actions

[Build workflow](.github/workflows/build.yml) 会在以下场景执行：

- 推送到 `main`
- Pull Request
- 手动触发 `workflow_dispatch`

构建产物包含 Android release APK、Copilot Remote VSIX 与修补版 Copilot Chat VSIX。签名使用以下 GitHub Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

不要将 JKS、密码、`.env`、`local.properties` 或服务账户 JSON 提交到仓库。

## 项目结构

```text
.
├── .github/workflows/          # CI 构建
├── copilot-remote-android/     # Kotlin + Jetpack Compose + Miuix App
├── copilot-remote-vscode/      # TypeScript VS Code WebSocket 扩展
└── vscode-copilot-chat/        # 可选 Remote Bridge 补丁源码
```

## 安全说明

- WebSocket 连接必须经过共享密钥认证。
- `ws://` 不提供传输加密，只应在可信局域网使用。
- 公网远程访问建议使用 WireGuard、Tailscale 等 VPN，不要直接暴露端口。
- Firebase 服务账户文件必须存放在仓库外部。
- Android release 签名库及密码不应提交到 Git。

## 已知限制

- 部分能力依赖当前 VS Code 和 GitHub Copilot Chat 版本，升级后可能需要同步适配。
- 未启用 Remote Bridge 时，历史对话使用本地 VS Code 会话存储作为回退来源。
- FCM 推送需要自行配置 Firebase 项目和服务账户。

## 许可证

本项目采用 [MIT License](LICENSE)。`vscode-copilot-chat` 中保留的第三方源码及资源仍遵循其目录内声明的许可证和版权信息。
