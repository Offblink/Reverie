# Reverie — 睡前互动故事 App · 技术规格

> Android 原生壳 + WebView 全 UI。**纯文字**（M12）：LLM 现编故事逐句上屏 → 段末弹选项（点击）→ LLM 续写。
> 语音（TTS 朗读 / ASR 语音选择）与设备侧诊断面板在 **M12 已整体移除**——历史代码见 git（`2781b22` 及更早）。
> 仓库：`Offblink/Reverie` · 本地：`Desktop/Vibe Coding/useful/Reverie`

## 1. 已确认决策（2026-10-30 用户拍板）

| 项 | 决定 |
|---|---|
| 项目名 | **Reverie** |
| 语音（TTS/ASR） | **不做**（M12 移除）。M0–M11 的双后端 TTS（系统 + 端上 VITS/Kokoro）、端上 ASR、语音选择、设备侧诊断面板全部删除，APK 从 209MB 降到 1.6MB；历史见 git。**用户拍板「直接去掉 ASR 和 TTS」**（2026-10-31），理由是端上合成慢（Kokoro 真机 RTF 2–4）、端上模型体积过大 |
| LLM | **设置页自填 OpenAI 兼容配置**：baseURL / apiKey / chatModel，key 不进 git |
| 人称 | **每本书可选**（M8）：第一人称「我」亲历 / 第三人称旁观（默认）；只作用于正文，选项保持第二人称指令口吻 |
| 无响应行为 | **不做哄睡自动选择**：段末停在选项卡，睡着 = 停在当前选择页，下次续听。M12 起无开麦，选项纯点击 |
| 设计 | **深色 Liquid Glass**（Mobile-Design 的 glass 组件移植）+ **Blinvo zoom-transition** 页间转场；**设置页禁止原生控件**（select/radio 一律自绘分段胶囊/抽屉） |
| 鬼故事 | **惊悚度不做应用侧限制**，按 LLM 提供方自身边界 |
| 长度 | 短 ≈20min / 中 ≈60min / 长 ≈100min（阅读时长口径，见 §5） |
| 技术栈 | Java、minSdk 26 / targetSdk 37 / compileSdk 37、OkHttp、单 Activity |

## 2. 产品核心循环

```
选主题 + 选长度 ─→ LLM 生成第 1 段（逐句上屏） ─→ 段末弹选项卡
     ↑                                                ↓
     │                                     用户点击选项
     │                                                ↓
     └──── LLM 按选择续写下一段 ←─────────────────────┘
                            …… 循环至目标互动数 ─→ 结局段（is_final）→ 完结
```

- 每个「互动单元」= 一段正文（约 400 字）+ 2–4 个选项。
- 故事状态每次变更即落盘（§4），杀进程 / 锁屏都不丢。
- 已生成的故事可**反复看**：书架打开续读，不重新生成。
- M12 起无 TTS 朗读、无 ASR 语音选择——纯文字阅读 + 点击。

## 3. 架构

### 3.1 域分工

| 域 | 组件 | 职责 |
|---|---|---|
| 原生 | `MainActivity` | WebView 壳、edge-to-edge、权限请求 |
| 原生 | `ReverieBridge` | `@JavascriptInterface`，JS→原生全部入口 |
| 原生 | `engine/LlmClient` | OkHttp SSE 流式 chat/completions |
| 原生 | `engine/StoryEngine` | 状态机：生成→选项→选择→生成…；编排者 |
| 原生 | `engine/Story` + `StoryStore` | 数据模型 + `filesDir/stories/<id>.json` |
| 原生 | `Settings` | SharedPreferences：endpoint / apiKey / chatModel |
| WebView | `assets/web/` | **全部可见 UI**：主页 / 书架 / 故事页 / 设置页，深色玻璃视觉 |

M12 已删除（历史见 git `2781b22` 及更早）：`engine/TtsPlayer`、`engine/DeviceTts`（sherpa-onnx VITS/Kokoro）、`engine/AsrRecorder`、`engine/DeviceAsr`、`engine/WhisperClient`、`sherpa-onnx-android-1.13.8.aar`、`assets/asr_model/`、`assets/tts_model/`，以及设置页的音色抽屉 / 语速 / 麦克风窗口 / 诊断面板。

原则：原生是引擎（生成、持久化），web 是皮（渲染、输入、转场）。web 重载后主动 `getState` 拉全量快照即可恢复画面。

### 3.2 桥契约（改一边必须同步另一边）

**JS → 原生** `window.Reverie.<method>(...)`（同步，JSON 字符串返回，异常回 null）：

| 方法 | 参数 | 返回 |
|---|---|---|
| `getState` | — | 全量快照 `{settings(不含key), story, busy}` |
| `newStory` | `{theme, tier, pov?}` | `{id}` 立即建档并开始生成第 1 段；pov：first/third，缺省 third |
| `choose` | `{index}` | `{ok}` 记录选择并触发下一段生成 |
| `listStories` | — | `[{id,title,theme,tier,status,updated,progress}]` |
| `openStory` | `{id}` | `{ok}` |
| `deleteStory` | `{id}` | `{ok}` |
| `getSettings` | — | 设置对象（**不含 apiKey**，仅 `hasKey:bool`）；M12 起只有 `endpoint / chatModel / hasKey` |
| `setSettings` | `{...}` | `{ok}` |
| `retry` | — | `{ok}` 重新请求当前段（`error{stage:llm}` 后用） |
| `testEndpoint` | — | `{ok, latencyMs, msg}` 连通性探测 |

**原生 → JS** `Host.onEvent(ev)`：

| event | 载荷 | 时机 |
|---|---|---|
| `segment-delta` | `{index, text}` | 流式增量，web 追加渲染 |
| `segment-done` | `{index, total, text, options, isFinal}` | 一段生成完毕 → web 立即弹选项卡 |
| `choice-made` | `{index, choiceIndex}` | 选择已记录 |
| `error` | `{stage: llm, msg}` | 生成失败 → web 显示「重试」 |

M12 已删除的桥方法与事件：`replay` / `stopReplay` / `replaySegment` / `getVoices` / `previewTts` / `getDiag` / `micTest` / `copyText` / `startAsr` / `stopAsr`，以及 `tts-state` / `asr-state` / `asr-level` / `mic-test` / `story-done` 事件。`Host.onState(st)` 现在只含 `{settings, story, busy}`（`ttsDegraded` 已删）。

### 3.3 页面与导航

单 Activity + 单 `index.html` + 四视图页内路由（zoom-transition 需要同文档动画，跨 Activity 做不到）：

```
主页(主题网格12 + 长度三挡 + 开始)   ←→   书架(进行中/已完成)
        │ zoom-in                      │ zoom-in
        └────────→ 故事页 ←────────────┘
                    ↕ 页内滑动/淡入
                 设置页(顶栏齿轮)
```

**生成中的等待反馈（M11，M12 收敛为单相位）**：`newStory`/`choose` 后到首句抵达前有一段纯等待，
页面不再静默——正文流末尾追加一块呼吸骨架（三条错位横线 + 一颗呼吸点，`正在编织…`），收到该段首个
`segment-delta` 即撤；`busy` 快照兜底（换故事/页面重载也能亮起）；`error` 一律清掉，不留与重试坞同屏的僵骨架。
骨架追加在正文末尾、不遮正文；`prefers-reduced-motion` 下动画消失、留静态半透明形态（§8）。
M11 原有的「文本已到齐、TTS 尚未开读」第二相位（`正在准备朗读…`）随 TTS 一并删除。

## 4. 数据模型与持久化

`filesDir/stories/<id>.json`（org.json 手写序列化，一书一份，选择即写盘）：

```json
{
  "id": "1730300000000", "title": "雾港来信", "theme": "悬疑", "tier": "short",
  "created": 1730300000000, "updated": 1730301200000,
  "status": "active",             // active | done
  "choicesTarget": 10,
  "synopsis": "（LLM 维护的滚动剧情梗概）",
  "segments": [
    { "index": 0, "text": "…400字…",
      "options": ["…","…","…"],   // 结局段为空数组
      "choice": -1,                // -1=未选；0..n=用户选的下标
      "isFinal": false }
  ],
  "replayState": { "pos": 0 }     // M12 起不再读写，旧档里可能仍有该字段
}
```

- 写盘时机：新档、每段生成完毕、每次选择。写入走临时文件 + rename（崩溃安全）。
- 设置存 SharedPreferences：`endpoint / apiKey / chatModel`（M12 起仅此三项；旧档里的
  `asrMode / asrModel / asrEndpoint / asrApiKey / ttsMode / ttsVoice / ttsRate / micWindowSec`
  仍是死键，留在偏好文件里无害但不会再被读取）。apiKey 只在原生域，**永不**经桥下发给 JS。
- 端点在**保存时归一化**（去换行/全角空白、补 https://、剥离重复的 `/chat/completions` 等路径尾巴、去尾斜杠）；HTTP 报错统一带真实请求 URL，响应体为空时明说并指向路径填法（M8，修「配置没问题却 404 且报错空洞」）。

## 5. LLM 契约

### 5.1 请求

`POST {endpoint}/chat/completions`，`stream: true`，OpenAI 兼容。messages：

- **system**：叙述者人设 + 主题风格块（§5.4）+ JSON 输出契约（§5.3）+ 进度指令：
  「你在写第 {i}/{total} 个互动段；当 i == total 时写结局段，`is_final: true` 且 `options: []`」。
- **user**：`synopsis` + 最近 6 段原文 + **全部选择史**（每次选择的选项文本）+ 本次新选择。

### 5.2 上下文策略

- 选择史全量携带（每条几十字，50 段也廉价）。
- 正文只带最近 6 段原文；更早剧情靠 LLM 自维护的 `synopsis` 字段（每次响应更新）。
- 32K 上下文足够：最坏 ≈ 6×400 + 50×50 + synopsis ≈ 8K token。

### 5.3 输出契约

```json
{ "title": "…",            // 仅第 1 段返回
  "segment": "…约350–450字…",
  "options": ["…", "…"],   // 2–4 个；结局段 []
  "is_final": false,
  "synopsis": "…" }
```

- system 里强制 JSON；解析时容忍 markdown code fence，取首个 `{…}` 块。
- **流式切句**：增量文本中提取 `segment` 字段值，按 `。！？!?…` 切句逐句送 TTS——第 1 句 1–2 秒即开讲，选项在段末（JSON 尾部）到达时才浮现。这是核心体验，不等整段生成完。
- 生成失败（SSE 断 / JSON 解析失败）：丢弃半段不落盘，`error{stage:llm}` → web 显示「重试」按钮（重新请求同一段）。

### 5.4 主题清单（12）

| 主题 | 风格块要点 |
|---|---|
| 鬼故事 | 惊悚度**不限制**；氛围、怪谈、中式恐怖均可 |
| 悬疑推理 | 线索公平、每段埋钩子 |
| 恋爱 | 暧心动人、双向奔赴 |
| 穿越 | 时代错位感、历史细节 |
| 奇幻 | 魔法体系、异世界 |
| 科幻 | 硬边设定或太空歌剧 |
| 武侠 | 江湖恩怨、武功招式 |
| 古风宫斗 | 权谋、步步惊心 |
| 校园青春 | 轻松日常、成长 |
| 治愈日常 | 温柔慢节奏，助眠首选 |
| 童话幻想 | 适龄奇幻冒险 |
| 末日生存 | 生存压力、人性抉择 |

### 5.5 长度挡位

| 挡位 | 互动数 total | 全文字数 | 目标阅读时长（约 200 字/分） |
|---|---|---|---|
| short | 10 | ≈4,000 字 | ≈20 min |
| medium | 30 | ≈12,000 字 | ≈60 min |
| long | 50 | ≈20,000 字 | ≈100 min |

段均 350–450 字由 system 约束；实际允许 ±20%。M12 起无朗读，时长为纯阅读口径。

## 6. TTS 规格

**已移除（M12）**。M0–M11 曾有双后端 TTS（系统 `TextToSpeech` + 端上 sherpa-onnx，模型先后为
VITS fanchen-C → Kokoro v1.1-zh → VITS fanchen-wnj，含音色抽屉、语速、试听、选项朗读、
句尾静音垫、合成/播放两级队列）。Kokoro 在小米真机上 RTF 稳定 2–4（合成耗时 = 音频时长 2–4 倍），
句间必然断粮；换 VITS fanchen-wnj 后模拟器 RTF 降到 0.20–0.23，但模型仍占 APK 绝大部分体积。
**用户拍板整体移除语音**（2026-10-31），历史实现见 git `2781b22` 及更早的 `engine/TtsPlayer`、
`engine/DeviceTts`、`app/src/main/assets/tts_model/`。要回退：从该提交 checkout 即可，无需重下模型。

## 7. ASR（语音选择）规格

**已移除（M12）**。M5–M11 曾有录音 + 能量 VAD + 本地选项匹配 + 端上 zipformer2 CTC 识别（26MB 内置）
+ 云端 whisper 回落，含麦克风倒计时环、超时峰值音量自诊断、`RECORD_AUDIO` 运行时权限。
现在选项纯点击。历史实现见 git `2781b22` 及更早的 `engine/AsrRecorder`、`engine/DeviceAsr`、
`engine/WhisperClient`、`app/src/main/assets/asr_model/`。

## 8. 设计规范

- 视觉：移植 Mobile-Design `shared/glass.css` + `tokens.css` 的深色配方——玻璃只属于浮在内容上的控件层（顶栏、选项卡、删除确认面板），正文层永远不透明深色底；Reduce Transparency 回退；`prefers-reduced-motion` 降级。
- 转场：移植 `blinvo-design/zoom-transition`（FLIP，进 280ms / 出 240ms，`fast_out_slow_in`，不回弹）用于 主页/书架 → 故事页。
- 故事页：正文流式追加渲染；底部玻璃选项卡 2–4 枚；顶栏玻璃条显示 `第 3/10 段 · 主题`。M12 起无当前句高亮（原随 `tts-state` 移动）、无麦克风倒计时环。
- 应用图标（M11，M12 保留）：**自适应图标**（minSdk 26 → 只需 `mipmap-anydpi-v26` 两层，不需要 legacy `mipmap-*dpi`）。背景纯夜色 `#050C22`（取自图主体外缘，遮罩怎么切都无缝），前景 `drawable-nodpi/ic_launcher_foreground.png`（432×432 一张，系统缩放）在画布中央放 76dp 圆角玻璃月牙片，主体外 alpha=0。原图 1536² 带「豆包 AI 生成」水印 → 先按主体方框裁掉水印再缩放。
- 生成骨架（M11）：动画走 `--damped`/`--spring`；`prefers-reduced-motion` 由 `glass.css` 全局把 `animation-duration` 压到 0.01ms，骨架另有一套静态半透明形态兜底。
- 文件安全：不引 CDN、不用 ES modules、不用 fetch 外链（file:// 约束，与 Mobile-Design 同规则）。
- edge-to-edge：透明系统栏全屏 WebView，web 侧用 `env(safe-area-inset-*)` 留边（参考 android/skills `system/edge-to-edge`）。

## 9. 权限与安全

- 仅 `INTERNET` + `ACCESS_NETWORK_STATE`。**无** `RECORD_AUDIO`（M12 随 ASR 删除）、无相机/定位/前台服务。
- apiKey 存 SharedPreferences，构建期零注入、git 零出现；发布前跑一遍密钥扫描。
- WebView：`file:///android_asset/web/index.html`，禁文件访问收敛、JS 桥只暴露 §3.2 方法。

## 10. 增量计划与验收

| 增量 | 内容 | 验收 |
|---|---|---|
| M0 | Gradle 脚手架、Manifest、空 MainActivity | `assembleDebug` 过，装机出空壳页 |
| M1 | WebView 壳 + 桥 + 四视图静态页 + glass 视觉 + zoom 转场 | 真机/模拟器可导航，桥自检日志过 |
| M2 | StoryEngine + LlmClient + StoryStore + 文字版闭环（原 M3 文字版验收并入） | 选主题→流式生成→点击选择→到结局→落盘可重开 |
| M4 | TTS 逐句朗读 + 句高亮 + 读完才弹选项 + 重听 | 短故事全程听读，断点续听、重听 |
| M5 | ASR：录音/VAD/whisper/匹配 + 倒计时环 + 运行时权限 | 语音选择闭环，超时关麦回落点击（语音命中链路需真机复验，模拟器无音频输入） |
| M6 | 书架删除手势 + 设置音色/语速 + 边角打磨 | 三挡各跑通一遍，60min 无崩溃 |
| M7 | 端上识别：sherpa-onnx zipformer2 CTC int8（26MB 内置），默认；ASR 独立端点 | 说出「第一个」端上转写命中续写；模型零配置离线可用 |
| M8 | 404 报错自证（URL+归一化）；设置页去原生控件（分段胶囊+音色抽屉）；端上 TTS（VITS fanchen-C 137MB 内置，auto 兜底系统无中文）；人称选择（first/third） | 系统无中文引擎的设备（小米）auto 模式自动切端上出声；人称注入 system prompt（单测钉）；脏端点保存即归一 |
| M9 | 选项朗读（段文后念「请选择。一，…」再弹坞开麦）；端上 TTS 换代 Kokoro v1.1-zh（100 中文音色/默认男声 zm_010/句尾静音垫/试听）；ASR VAD 收敛加速+超时带峰值音量自诊断 | 闭眼听选项可直接语音选；kokoro 出声且男声、句间停顿自然；轻声说话能检出（peakRms 诊断区分没出声/没过阈） |
| M10 | 设备侧诊断：环形日志（llm 每句到达 / tts 合成耗时+RTF+断粮等待 / asr 电平与超时）+ 设置页诊断面板（实时电平条 / 最近 80 条 / 麦克风自检 6s）+ 零信号自动换 MIC 音源 | 真机不连 adb 也能自证：句间隔大是「llm 到句慢」还是「tts RTF>1」；麦克风是「没信号」还是「没过阈」|
| M11 | 生成中的呼吸骨架（等文本 / 等开读两相位）；端上 TTS 换代 **VITS fanchen-wnj**（137MB 内置，rule FSTs + 拼音 lexicon + jieba dict，单男声 sid 0，16kHz）；自适应应用图标 | `newStory`/`choose` 后骨架亮、首个 `segment-delta` 即撤（`busy` 快照兜底）；诊断面板 `vits 就绪 sr=16000 speakers=1`，`gen … RTF` 显著 < 1（模拟器实测 0.20–0.23，真机以 RTF 行为准），句间不再 `断粮`；桌面/抽屉图标为玻璃月牙片 |
| M12 | **移除全部语音与诊断**：删 TTS（系统 + 端上）、ASR（端上 + whisper）、重听、音色抽屉、语速/麦克风窗口、设备侧诊断面板、`RECORD_AUDIO`、`sherpa-onnx` AAR 与两个模型资产树；`segment-done` 即弹选项卡（不再等 TTS 读完）；骨架收敛为单相位 | APK 从 209MB → **1.6MB**；设置页只剩 BASE URL / API KEY / CHAT MODEL；选主题→流式生成→点选项→到结局→落盘可重开，11 例单测全绿 |

每增量收尾：`assembleDebug` + 该增量直接触及路径的冒烟；UI 改动真机截图验收。

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 纯文字后失去「听故事」卖点 | 用户主动拍板移除语音（2026-10-31）；要恢复从 git `2781b22` checkout |
| 长故事 LLM 漂移（角色/伏笔丢失） | synopsis 字段 + 选择史全量携带 |
| SSE 中断 | 半段丢弃 + 重试按钮，不落盘脏数据 |
| WebView file:// 限制 | 与 Mobile-Design/Spore 同规则：无模块、无外链 fetch |

## 12. Out of scope（v1 不做）

语音（TTS 朗读 / ASR 语音选择）/ 设备侧诊断面板 / 哄睡自动选择 / 重听模式 / 账号同步 / iOS / 前台服务后台播放。

若要恢复语音：从 git `2781b22` checkout 整个 `engine/` 目录与 `assets/{asr,tts}_model/`，
`settings.gradle` 里 `include ':app'` 之外不需改（AAR 在 `app/libs/`，`app/build.gradle`
需重新加 `implementation files('libs/sherpa-onnx-android-1.13.8.aar')`）。建议等端上模型 RTF
在真机稳定 < 0.5 再考虑回迁，否则句间断粮问题会原样复现。
