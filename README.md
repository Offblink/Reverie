# Reverie

选个主题，AI 现编一段故事讲给你听。每段读完给你 2–4 个选择——说出来或点一下，故事照你的选择往下走。睡着了也不丢：进度自动保存，下次接着听。

## 特性

- **流式生成**：第 1 句 1–2 秒开讲，不等整段写完
- **逐句朗读**：系统 TTS 念一句、高亮一句；读完才弹选项
- **语音选择**：段末自动开麦（倒计时环），说出选项继续；内置 26MB 中文识别模型，**离线可用**；没听清就点
- **深色 Liquid Glass** 界面 + 页面缩放转场
- **书架**：进行中 / 已完结，长按可删除；支持断点续听、整本重听
- **严格超时**：超时只关麦，选项永远可点，不做哄睡自动选择

## 配置

主页右上角齿轮进设置，填 OpenAI 兼容端点即可开玩：

| 字段 | 说明 | 示例 |
|---|---|---|
| BASE URL | OpenAI 兼容 base URL | `https://api.xiaomimimo.com/v1` |
| API KEY | 只存本机，不进仓库 | `sk-…` |
| CHAT MODEL | 对话模型 | `mimo-v2.6-flash` |
| 语音识别方式 | 端上（默认，离线）或云端 whisper | 端上识别 |
| ASR MODEL | 云端模式的 whisper 模型 | `whisper-1` |
| ASR BASE URL | 云端模式独立端点，留空跟随主端点 | `https://api.openai.com/v1` |
| ASR KEY | 云端模式独立 key，留空跟随主 KEY | `sk-…` |
| 音色 / 语速 / 麦克风窗口 | 可选，均有默认值 | 0.9 / 10 秒 |

> 默认走端上模型，语音选择不需要任何 ASR 配置。切到云端 whisper 时，默认与 LLM 共用端点的 `/audio/transcriptions`；网关不带该接口（如 MiMo）时可独立指定。

## 构建

```bash
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

要求 Android 8.0+（minSdk 26）。首次开麦会请求麦克风权限，拒绝也能纯点击玩。

## 架构

原生是引擎（生成、播音、录音、持久化），WebView 是皮（全部可见 UI）。

| 模块 | 职责 |
|---|---|
| `engine/StoryEngine` | 生成 → 朗读 → 开麦 → 选择 状态机 |
| `engine/LlmClient` | SSE 流式对话 + 增量切句 |
| `engine/TtsPlayer` | 系统 TTS 逐句队列与看门狗 |
| `engine/AsrRecorder` / `DeviceAsr` | 录音能量 VAD / sherpa-onnx 端上转写（内置中文模型） |
| `engine/WhisperClient` | 云端 whisper 上传（可选模式） |
| `assets/web/` | 四视图 UI、深色玻璃、页面转场 |

完整产品决策与接口契约见 [SPEC.md](SPEC.md)。

## 状态

故事全流程、语音选择（端上/云端）、书架与设置均可用。语音识别建议在真机上验证一次。

## License

MIT
