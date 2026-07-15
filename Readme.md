# Copilot Remote Control

通过手机远程操控 VS Code 中 GitHub Copilot 的完整解决方案。包含一个 VS Code 扩展（WebSocket 服务端）和一个 Android App（远程控制端）。

## 架构概览

```
┌─────────────────┐         WebSocket (LAN)        ┌──────────────────┐
│   Android App   │  <──────────────────────────>  │  VS Code 扩展     │
│  (Remote Control)│     ws://IP:9876 + Key        │  (WS Server)     │
│                 │                                │                  │
│  - Chat 对话     │                                │  - Copilot API   │
│  - Model 选择    │                                │  - vscode.lm     │
│  - Agent 选择    │                                │  - Commands      │
│  - Skills 管理   │                                │  - Extensions    │
│  - 插件管理      │                                │  - MCP Servers   │
│  - MCP 管理      │                                │  - Workspace     │
│  - Commands      │                                │                  │
│  - Workspace    │                                │  GitHub Copilot  │
└─────────────────┘                                └──────────────────┘
```

## 功能列表

### VS Code 扩展功能
- 通过 WebSocket 暴露 Copilot 全部能力
- Key 认证保护
- 自动启动 / 手动启动
- 状态栏显示连接状态
- QR 码快速连接
- 局域网访问 (0.0.0.0 绑定)

### Android App 功能
- **实时对话**: 流式输出 Copilot 回复，支持取消
- **模型选择**: 列出并选择所有可用 AI 模型 (GPT-4o, Claude, o1 等)
- **Agent 选择**: 选择 @workspace, @vscode, @terminal, @github 等 Participants
- **Skills 管理**: 查看/调用 Copilot 技能 (Explain, Fix, Tests, Docs 等)
- **插件管理**: 列出/启用/禁用 VS Code 扩展
- **MCP 管理**: 添加/删除/查看 MCP Server 配置
- **Commands**: 搜索/执行 VS Code 命令
- **Workspace**: 查看工作区、打开的文件、活跃编辑器
- **设置持久化**: WS URL 和 Key 自动保存，App 重启自动连接
- **现代化 UI**: Material 3 设计，深色/浅色主题自适应

## 项目结构

```
copilot-remote/
├── copilot-remote-vscode/          # VS Code 扩展
│   ├── package.json                # 扩展清单
│   ├── tsconfig.json
│   ├── esbuild.js                  # 打包配置
│   ├── .vscodeignore
│   └── src/
│       ├── extension.ts            # 入口：命令注册、自动启动
│       ├── server.ts               # WebSocket 服务端
│       ├── copilot-api.ts          # Copilot API 封装
│       └── types.ts                # 共享类型定义
│
├── copilot-remote-android/         # Android App
│   ├── build.gradle.kts            # 根构建配置
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── app/
│   │   ├── build.gradle.kts        # App 模块配置
│   │   ├── proguard-rules.pro
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/copilot/remote/
│   │       │   ├── CopilotApplication.kt
│   │       │   ├── MainActivity.kt
│   │       │   ├── data/
│   │       │   │   ├── Models.kt           # 数据模型 + JSON 解析
│   │       │   │   ├── SettingsStore.kt    # DataStore 持久化
│   │       │   │   └── WebSocketManager.kt # WebSocket 客户端
│   │       │   ├── viewmodel/
│   │       │   │   └── CopilotViewModel.kt # 主 ViewModel
│   │       │   └── ui/
│   │       │       ├── theme/
│   │       │       │   ├── Color.kt
│   │       │       │   ├── Theme.kt
│   │       │       │   └── Type.kt
│   │       │       ├── components/
│   │       │       │   └── CommonComponents.kt
│   │       │       ├── navigation/
│   │       │       │   └── AppNavigation.kt
│   │       │       └── screens/
│   │       │           ├── ChatScreen.kt
│   │       │           ├── ModelsScreen.kt
│   │       │           ├── AgentsScreen.kt
│   │       │           ├── SkillsScreen.kt
│   │       │           ├── CommandsScreen.kt
│   │       │           ├── ExtensionsScreen.kt
│   │       │           ├── McpScreen.kt
│   │       │           ├── WorkspaceScreen.kt
│   │       │           └── SettingsScreen.kt
│   │       └── res/
│   │           ├── values/
│   │           │   ├── strings.xml
│   │           │   ├── themes.xml
│   │           │   ├── colors.xml
│   │           │   └── ic_launcher_background.xml
│   │           ├── drawable/
│   │           │   └── ic_launcher_foreground.xml
│   │           └── mipmap-anydpi-v26/
│   │               ├── ic_launcher.xml
│   │               └── ic_launcher_round.xml
│   └── gradle/wrapper/
│       └── gradle-wrapper.properties
│
└── README.md
```

## 快速开始

### 第一步：安装 VS Code 扩展

```bash
cd copilot-remote-vscode
npm install
npm run build
```

然后在 VS Code 中：
1. 按 `F5` 打开扩展开发宿主窗口（或使用 `Extensions: Install from VSIX`）
2. 或者运行 `npx vsce package` 生成 `.vsix` 文件后安装

### 第二步：配置扩展

在 VS Code 设置中搜索 `Copilot Remote`：

| 设置项 | 默认值 | 说明 |
|--------|--------|------|
| `copilotRemote.key` | (空) | 认证密钥（必填） |
| `copilotRemote.port` | 9876 | WebSocket 端口 |
| `copilotRemote.host` | 0.0.0.0 | 绑定地址（0.0.0.0 = 局域网访问） |
| `copilotRemote.autoStart` | true | VS Code 启动时自动启动服务 |

**生成密钥**：运行命令 `Copilot Remote: Generate New Key`

**启动服务**：运行命令 `Copilot Remote: Start Server`

**查看 QR 码**：运行命令 `Copilot Remote: Show Connection QR Code`

### 第三步：构建 Android App

```bash
cd copilot-remote-android
# 使用 Android Studio 打开项目，或命令行构建：
./gradlew assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 第四步：连接使用

1. 确保 VS Code 和手机在同一局域网
2. 在 VS Code 中启动 Copilot Remote 服务
3. 打开 Android App
4. 在设置页面输入：
   - **WebSocket URL**: `ws://<电脑IP>:9876`
   - **Key**: 扩展中设置的密钥
5. 点击连接，开始远程操控 Copilot！

## WebSocket 协议

### 客户端 → 服务端

| 消息类型 | 说明 |
|----------|------|
| `auth` | 认证（发送 key） |
| `listModels` | 获取可用 AI 模型列表 |
| `chat` | 发送聊天消息（流式响应） |
| `cancelChat` | 取消正在进行的聊天 |
| `listCommands` | 获取 VS Code 命令列表 |
| `executeCommand` | 执行 VS Code 命令 |
| `listParticipants` | 获取 Chat Participants (Agents) |
| `listExtensions` | 获取已安装扩展列表 |
| `toggleExtension` | 启用/禁用扩展 |
| `listMcpServers` | 获取 MCP Server 列表 |
| `addMcpServer` | 添加 MCP Server |
| `removeMcpServer` | 删除 MCP Server |
| `listSkills` | 获取 Skills/Tools 列表 |
| `invokeSkill` | 调用 Skill |
| `getWorkspaceInfo` | 获取工作区信息 |
| `getOpenEditors` | 获取打开的编辑器 |
| `getFileContent` | 读取文件内容 |
| `saveFileContent` | 保存文件内容 |
| `getStatus` | 获取服务状态 |
| `sendToCopilotChat` | 发送消息到 VS Code Copilot Chat 面板 |
| `getActiveEditorContent` | 获取当前编辑器内容 |
| `insertText` | 在当前编辑器插入文本 |
| `ping` | 心跳检测 |

### 服务端 → 客户端

| 消息类型 | 说明 |
|----------|------|
| `authResult` | 认证结果 |
| `models` | 模型列表 |
| `chatStart` | 聊天开始 |
| `chatDelta` | 聊天流式片段 |
| `chatDone` | 聊天完成 |
| `chatError` | 聊天错误 |
| `chatCancelled` | 聊天已取消 |
| `commands` | 命令列表 |
| `commandResult` | 命令执行结果 |
| `participants` | Agent 列表 |
| `extensions` | 扩展列表 |
| `mcpServers` | MCP Server 列表 |
| `skills` | Skill 列表 |
| `workspaceInfo` | 工作区信息 |
| `status` | 服务状态 |
| `error` | 错误消息 |
| `pong` | 心跳响应 |

## 技术栈

### VS Code 扩展
- TypeScript
- VS Code Extension API (`vscode.lm`, `vscode.commands`, `vscode.chat`)
- WebSocket (`ws` library)
- esbuild 打包

### Android App
- Kotlin
- Jetpack Compose (Material 3)
- OkHttp WebSocket
- DataStore (偏好设置持久化)
- MVVM + StateFlow
- Navigation Compose

## 安全说明

- 所有 WebSocket 连接需要 Key 认证
- 默认绑定 `0.0.0.0` 允许局域网访问，可改为 `127.0.0.1` 仅限本机
- WebSocket 使用 `ws://`（明文），建议仅在同一可信局域网内使用
- 如需更高安全性，可在 VS Code 和手机之间使用 VPN 或 SSH 隧道

## 前置条件

- VS Code >= 1.90.0
- GitHub Copilot 扩展已安装并登录
- Android 设备 >= API 26 (Android 8.0)
- VS Code 和手机在同一局域网

## 许可证

MIT License
