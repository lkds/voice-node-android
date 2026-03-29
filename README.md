# Voice Node for Android

OpenClaw Gateway 的 Android 语音节点，实现"贾维斯式"语音交互体验。

## 功能特性

- 🎤 **语音输入**: 语音转文字，发送给 Agent 处理
- 🔊 **语音输出**: Agent 回复自动转语音播放
- 🎯 **后台运行**: Foreground Service 保持后台监听
- ⚡ **WebSocket 连接**: 实时双向通信

## 技术栈

- Kotlin + Android SDK 26+
- OkHttp WebSocket
- Android TextToSpeech + SpeechRecognizer
- Coroutines + Flow

## 构建方式

### 本地构建

```bash
./gradlew assembleDebug
```

### GitHub Actions 自动构建

推送代码到 main 分支后，GitHub Actions 会自动编译 APK。

下载方式：
1. 进入 Actions 页面
2. 选择最新的 workflow run
3. 在 Artifacts 中下载 `voice-node-debug`

## 使用方法

1. 安装 APK
2. 输入 OpenClaw Gateway 地址（如 `ws://192.168.1.100:18789`）
3. 点击"连接 Gateway"
4. 点击"启动语音服务"
5. 开始语音对话

## 权限说明

| 权限 | 用途 |
|------|------|
| RECORD_AUDIO | 语音识别 |
| INTERNET | WebSocket 连接 |
| FOREGROUND_SERVICE | 后台服务 |
| POST_NOTIFICATIONS | 状态通知 |

## 项目结构

```
app/src/main/java/com/openclaw/voicenode/
├── MainActivity.kt          # 主界面
├── service/
│   └── VoiceNodeService.kt  # 后台语音服务
├── websocket/
│   └── WebSocketManager.kt  # WebSocket 连接管理
└── voice/
    ├── STTClient.kt         # 语音识别
    └── TTSClient.kt         # 语音合成
```

## 开发计划

- [ ] V1: 基础语音对话
- [ ] V2: 唤醒词检测
- [ ] V3: 持续对话模式
- [ ] V4: 视觉能力（camera/screen）

## License

MIT