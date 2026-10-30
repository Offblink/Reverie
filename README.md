# Reverie

选个主题，AI 现编一段故事读给你看。每段读完给你 2–4 个选择——点一下，故事照你的选择往下走。进度自动保存，下次接着看。

**M12 起为纯文字版**：语音（TTS 朗读 / ASR 语音选择）与设备侧诊断面板已整体移除。要恢复语音见 [SPEC.md §12](SPEC.md)。

## 特性

- **流式生成**：第 1 句 1–2 秒上屏，不等整段写完
- **人称可选**：第一人称「我」亲历 / 第三人称旁观（每本书独立）
- **等待有反馈**：生成下一段时正文末尾浮一块呼吸骨架（`正在编织…`），首句一到就撤
- **深色 Liquid Glass** 界面 + 页面缩放转场；设置页全部自绘控件
- **书架**：进行中 / 已完结，长按可删除；支持断点续读
- **APK 仅 1.6MB**：无内置模型、无 AAR 依赖（OkHttp 之外零第三方库）

## 配置

主页右上角齿轮进设置，填 OpenAI 兼容端点即可开玩：

| 字段 | 说明 | 示例 |
|---|---|---|
| BASE URL | OpenAI 兼容 base URL（保存时自动纠正：补协议、剥多余路径、去换行） | `https://api.xiaomimimo.com/v1` |
| API KEY | 只存本机，不进仓库 | `sk-…` |
| CHAT MODEL | 对话模型 | `mimo-v2.6-flash` |

> 连接失败时报错会带上真实请求 URL，路径填错一眼可见。设置页「测试连接」可快速验证端点连通性。

## 构建

```bash
gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

要求 Android 8.0+（minSdk 26）。无运行时权限申请。

## 架构

原生是引擎（生成、持久化），WebView 是皮（全部可见 UI）。

| 模块 | 职责 |
|---|---|
| `engine/StoryEngine` | 生成 → 选项 → 选择 状态机 |
| `engine/LlmClient` | SSE 流式对话 + 增量切句 + 端点归一化 |
| `assets/web/` | 四视图 UI、深色玻璃、页面转场 |

完整产品决策与接口契约见 [SPEC.md](SPEC.md)。

## 状态

故事全流程、书架与设置均可用。M12 已移除语音，纯文字阅读版。

## License

MIT
