package org.offblink.reverie.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;

/**
 * 故事引擎状态机（SPEC §5）：newStory → 生成段 → （等选择）→ 生成下段 → … → 结局。
 * 编排 LlmClient / StoryStore；事件经 Emitter 推给 JS。
 * 单线程 executor 串行所有状态变更，无锁。
 */
public class StoryEngine {

    public interface Emitter {
        void event(JSONObject ev);   // onEvent（已在 UI 线程投递由桥负责）
        void state();                // 请求推全量快照
    }

    public interface SettingsProvider {   // 解耦：引擎只要这几个值，不碰 Settings 类
        String endpoint();
        String apiKey();
        String chatModel();
        float ttsRate();
        String ttsVoice();
        String ttsMode();       // auto=系统优先无中文切端上（默认）| system | device（M8）
        String asrModel();
        String asrMode();       // "device"=端上模型（默认）| "cloud"=云端 whisper（M7）
        String asrEndpoint();   // 独立 ASR 端点；空 = 跟随 LLM（M6）
        String asrApiKey();     // 独立 ASR key；空 = 跟随 LLM
        int micWindowSec();
    }

    /** 主题风格块（SPEC §5.4） */
    private static final Map<String, String> THEME_STYLE = new HashMap<String, String>() {{
        put("ghost", "怪谈与中式恐怖，惊悚度不限制，怎么吓人怎么来：氛围、灵异、民俗怪谈皆可。");
        put("mystery", "悬疑推理：线索公平地埋给读者，每个互动段结尾留钩子与反转空间。");
        put("love", "恋爱：暧昧、心动、双向奔赴，甜而不腻。");
        put("timetravel", "穿越：时代错位的惊奇感，历史细节扎实。");
        put("fantasy", "奇幻：自洽的魔法体系与异世界风物。");
        put("scifi", "科幻：硬边设定或太空歌剧，想象有边。");
        put("wuxia", "武侠：江湖恩怨、武功招式、侠义抉择。");
        put("palace", "宫斗：权谋交锋，步步惊心。");
        put("campus", "校园：青春日常与成长心事，轻松明快。");
        put("healing", "治愈：温柔慢节奏日常，助眠首选，惊吓与冲突一律不要。");
        put("fairy", "童话：适龄的奇幻冒险，纯真明亮。");
        put("doomsday", "末日生存：资源压力下的人性抉择。");
    }};

    private static final Map<String, String> THEME_NAME = new HashMap<String, String>() {{
        put("ghost", "鬼故事"); put("mystery", "悬疑"); put("love", "恋爱");
        put("timetravel", "穿越"); put("fantasy", "奇幻"); put("scifi", "科幻");
        put("wuxia", "武侠"); put("palace", "宫斗"); put("campus", "校园");
        put("healing", "治愈"); put("fairy", "童话"); put("doomsday", "末日");
    }};

    public static int tierTotal(String tier) {
        if ("medium".equals(tier)) return 30;
        if ("long".equals(tier)) return 50;
        return 10;
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LlmClient llm = new LlmClient();
    private final AsrRecorder recorder;
    private final WhisperClient whisper = new WhisperClient();
    private final DeviceAsr deviceAsr;
    private final StoryStore store;
    private final Emitter emitter;
    private final SettingsProvider settings;
    private final TtsPlayer player;

    private Story story;
    private boolean busy = false;
    private Call inflight;               // 生成中可取消（换故事时）
    private volatile boolean ttsDegraded = false;   // 快照里带给 JS（boot 晚到时补告）
    private boolean asrActive = false;               // 麦克风窗口进行中（exec 线程触发，synchronized 保护）
    private volatile boolean foreground = true;     // 后台不录音（SPEC §6）

    public StoryEngine(Context ctx, StoryStore store, SettingsProvider settings, Emitter emitter) {
        this.store = store;
        this.settings = settings;
        this.emitter = emitter;
        this.recorder = new AsrRecorder(ctx);
        this.deviceAsr = new DeviceAsr(ctx);
        // 端上识别预热（拷模型+加载）。先让路 5s：冷启动时它和 TTS 绑定抢 CPU，
        // 实测抢输会让 TTS 撞 12s 看门狗降级纯文字（M7 复测踩过）
        new Thread(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            deviceAsr.init();
        }, "reverie-asr-init").start();
        this.player = new TtsPlayer(ctx, settings.ttsRate(), settings.ttsMode(), new TtsPlayer.Listener() {
            @Override public void onSpeaking(int seg, String text) {
                emitTts("speaking", seg, text);
            }
            @Override public void onSegmentRead(int seg) {
                emitTts("idle", seg, null);   // 段读完 → JS 弹选项（SPEC §6 时序）
            }
            @Override public void onReplayDone(int lastSeg) {
                emitTts("replay-done", lastSeg, null);
            }
            @Override public void onDegraded(String why) {
                ttsDegraded = true;
                emitTts("degraded", -1, why);
            }
        });
        player.init();
        player.setVoice(settings.ttsVoice());   // 启动前存过音色 → init 就绪后生效（M6）
    }

    /** 全量快照字段：TTS 是否已降级（JS boot 晚到时据此补 toast） */
    public boolean ttsDegraded() { return ttsDegraded; }

    /** 设置变更后即时生效（M6）：语速 + 音色，无需重启朗读 */
    public void applyTtsSettings() {
        player.setRate(settings.ttsRate());
        player.setVoice(settings.ttsVoice());
    }

    /** TTS 音色清单（设置页用；引擎未就绪时空表） */
    public java.util.List<String> voices() { return player.voices(); }

    /** 重听单段（SPEC §3.2 replaySegment） */
    public synchronized boolean replaySegment(int index) {
        if (story == null || busy) return false;
        if (index < 0 || index >= story.segments.size()) return false;
        Story.Segment seg = story.segments.get(index);
        player.beginReplay(index);
        for (String sent : splitSentences(seg.text)) player.replay(index, sent);
        player.endReplay();
        return true;
    }

    /** 重听全文：从 fromIndex 到最后（SPEC §3.2 replay） */
    public synchronized boolean replay(Integer fromIndex) {
        if (story == null || busy || story.segments.isEmpty()) return false;
        int from = fromIndex == null ? 0 : Math.max(0, fromIndex);
        if (from >= story.segments.size()) return false;
        int last = story.segments.size() - 1;
        player.beginReplay(last);
        for (int i = from; i <= last; i++) {
            for (String sent : splitSentences(story.segments.get(i).text)) player.replay(i, sent);
        }
        player.endReplay();
        return true;
    }

    public void stopReplay() { player.stopReplay(); }

    public void shutdown() { stopAsr(); player.shutdown(); }

    /** 按句末标点切句（重听入队粒度；与 LlmClient 流式切句同标点集） */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if ("。！？!?…".indexOf(c) >= 0) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    public synchronized Story story() { return story; }
    public synchronized boolean busy() { return busy; }

    /** 新故事：建档落盘，立即生成第 1 段。返回故事 id。pov：first|third（其余回落 third）。 */
    public String newStory(final String themeId, final String tier, final String pov) {
        stopAsr();                // 上一本还在开麦/在念的话，先停
        player.halt();
        final Story s = new Story();
        s.id = String.valueOf(System.currentTimeMillis());
        s.themeId = themeId;
        s.themeName = THEME_NAME.getOrDefault(themeId, themeId);
        s.tier = tier;
        s.pov = "first".equals(pov) ? "first" : "third";
        s.choicesTarget = tierTotal(tier);
        s.created = System.currentTimeMillis();
        try { store.save(s); } catch (Exception ignored) { }
        synchronized (this) { story = s; }
        generate();          // 先置 busy 再推快照，JS 拿到的 busy 才是真值
        emitter.state();
        return s.id;
    }

    /** 打开已有故事（书架续听） */
    public synchronized boolean open(String id) {
        Story s = store.load(id);
        if (s == null) return false;
        if (inflight != null) { inflight.cancel(); }
        stopAsr();
        player.halt();
        story = s;
        busy = false;
        emitter.state();
        return true;
    }

    public synchronized void delete(String id) {
        store.delete(id);
        if (story != null && id.equals(story.id)) { story = null; emitter.state(); }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Story s : store.list()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.id);
            m.put("title", s.title);
            m.put("theme", s.themeId);
            m.put("themeName", s.themeName);
            m.put("tier", s.tier);
            m.put("tierName", "short".equals(s.tier) ? "短" : "medium".equals(s.tier) ? "中" : "长");
            m.put("status", s.status);
            m.put("updated", s.updated);
            m.put("progress", s.segments.size() + "/" + s.choicesTarget);
            out.add(m);
        }
        return out;
    }

    /** 用户选择：记录 → 落盘 → 生成下一段。busy 时忽略（防双击双生成）。 */
    public synchronized boolean choose(int idx) {
        if (busy || story == null) return false;
        Story.Segment last = story.last();
        if (last == null || last.isFinal || last.choice != -1 || idx < 0 || idx >= last.options.size()) return false;
        player.stopReplay();   // 正在重听的话，选择即打断
        stopAsr();             // 选择已下（点击或语音）→ 关麦
        last.choice = idx;
        try { store.save(story); } catch (Exception ignored) { }
        emitChoiceMade(story.segments.size() - 1, idx, null);
        emitter.state();
        generate();
        return true;
    }

    /** M5：语音识别命中后走这里（带识别原文） */
    public synchronized boolean chooseHeard(int idx, String heard) {
        if (choose(idx)) {
            Story.Segment last = story.last();
            if (last != null) last.heard = heard;
            try { store.save(story); } catch (Exception ignored) { }
            return true;
        }
        return false;
    }

    /* ---------- ASR（M5，SPEC §7） ---------- */

    public void setForeground(boolean fg) {
        foreground = fg;
        if (!fg) stopAsr();   // 退后台即关麦（录音需前台，SPEC §6）
    }

    /**
     * 开麦：由 JS 在弹选项坞时触发（单一触发点——重听/补报的 tts idle
     * 不弹坞也就不会误开麦）。守卫：有故事、非 busy、落点未回答、前台、没在录。
     */
    public synchronized boolean startAsr() {
        if (!foreground || story == null || busy) return false;
        Story.Segment last = story.last();
        if (last == null || last.isFinal || last.choice != -1) return false;
        if (asrActive) return true;
        final int idx = last.index;
        final int win = settings.micWindowSec();
        asrActive = true;
        boolean started = recorder.start(win, new AsrRecorder.Listener() {
            @Override public void onAudio(byte[] wav) { exec.execute(() -> transcribe(idx, wav)); }
            @Override public void onTimeout() { exec.execute(() -> asrEnded("timeout", idx, null, null, null)); }
            @Override public void onError(String msg) { exec.execute(() -> asrEnded("error", idx, null, null, msg)); }
        });
        if (!started) { asrActive = false; return false; }
        emitAsr("listening", idx, null, null, win, null);
        return true;
    }

    /** 手动关麦：丢弃当前录音，无事件（JS 自己清倒计时环） */
    public synchronized void stopAsr() {
        asrActive = false;
        recorder.stop();
    }

    /** 检出语音 → 转文字（M7：端上模型优先，云端 whisper 回落；回调回 exec 处理结果） */
    private void transcribe(int idx, byte[] wav) {
        final String mode;
        final String ep, key, model;
        synchronized (this) {
            if (!asrActive) return;
            mode = settings.asrMode();
            ep = settings.asrEndpoint().isEmpty() ? settings.endpoint() : settings.asrEndpoint();
            key = settings.asrApiKey().isEmpty() ? settings.apiKey() : settings.asrApiKey();
            model = settings.asrModel();
        }
        if ("device".equals(mode)) {
            try {
                if (!deviceAsr.ready()) throw new IllegalStateException("识别引擎还在预热，稍后再试");
                String text = deviceAsr.transcribe(DeviceAsr.wavToFloat(wav));
                exec.execute(() -> handleHeard(idx, text));
            } catch (Throwable e) {
                exec.execute(() -> asrEnded("error", idx, null, null, "端上识别失败: " + e.getMessage()));
            }
            return;
        }
        whisper.transcribe(ep, key, model, wav,
                new WhisperClient.Listener() {
                    @Override public void onText(String text) { exec.execute(() -> handleHeard(idx, text)); }
                    @Override public void onError(String msg) { exec.execute(() -> asrEnded("error", idx, null, null, msg)); }
                });
    }

    /** 识别文本 → 本地匹配 → 命中续写 / 未命中回落点击 */
    private void handleHeard(int idx, String text) {
        String heard = (text == null || text.isEmpty()) ? null : text;
        int choice = -1;
        synchronized (this) {
            if (!asrActive) return;
            asrActive = false;
            if (heard != null && story != null) {
                Story.Segment last = story.last();
                if (last != null && last.index == idx) choice = OptionMatcher.match(heard, last.options);
            }
        }
        if (heard == null) { emitAsr("timeout", idx, null, null, null, null); return; }
        if (choice >= 0) {
            emitAsr("matched", idx, heard, choice, null, null);
            chooseHeard(choice, heard);
        } else {
            emitAsr("timeout", idx, heard, null, null, null);   // 未命中 = 无效响应（SPEC §7）
        }
    }

    private synchronized void asrEnded(String state, int idx, String heard, Integer choice, String msg) {
        asrActive = false;
        emitAsr(state, idx, heard, choice, null, msg);
    }

    /** 麦克风状态机事件（SPEC §3.2 asr-state） */
    private void emitAsr(String state, int index, String heard, Integer choice, Integer windowSec, String msg) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "asr-state");
            ev.put("state", state);
            ev.put("index", index);
            if (heard != null) ev.put("heard", heard);
            if (choice != null) ev.put("choiceIndex", choice);
            if (windowSec != null) ev.put("windowSec", windowSec);
            if (msg != null) ev.put("msg", msg);
            emitter.event(ev);
        } catch (Exception ignored) { }
    }

    /** 重试当前段（error 后） */
    public synchronized void retry() {
        if (!busy) generate();
    }

    /* ---------- 生成 ---------- */

    private synchronized void generate() {
        if (busy || story == null) return;
        if (story.status.equals("done")) return;
        // 已到目标互动数 → 下一段是结局；最后一段已 final → 完结
        int nextIndex = story.segments.size();
        if (nextIndex > 0) {
            Story.Segment last = story.last();
            if (last != null && last.isFinal) { story.status = "done"; emitter.state(); return; }
        }
        busy = true;

        final String endpoint = settings.endpoint();
        final String apiKey = settings.apiKey();
        final String model = settings.chatModel();
        if (apiKey.isEmpty()) {
            busy = false;
            emitError("llm", "未配置 API key —— 先到设置里填写");
            return;
        }

        final List<String[]> messages = buildMessages(story, nextIndex);
        try {
            inflight = llm.stream(endpoint, apiKey, model, messages, new LlmClient.Listener() {
                @Override public void onSentence(final String sentence) {
                    emitDelta(sentence);
                    player.enqueue(nextIndex, sentence);   // 流式边生成边读（SPEC §6）
                }

                @Override public void onComplete(final LlmClient.Result res) {
                    synchronized (StoryEngine.this) {
                        Story.Segment seg = new Story.Segment();
                        seg.index = story.segments.size();
                        seg.text = res.segment;
                        seg.options = new ArrayList<>(res.options);
                        seg.isFinal = res.isFinal;
                        story.segments.add(seg);
                        if (!res.title.isEmpty() && story.title.isEmpty()) story.title = res.title;
                        if (!res.synopsis.isEmpty()) story.synopsis = res.synopsis;
                        if (res.isFinal) story.status = "done";
                        try { store.save(story); } catch (Exception ignored) { }
                        busy = false;
                    }
                    emitDone(res);
                    player.seal(nextIndex);   // 文本到齐；TTS 念完最后一句 → idle → JS 弹选项
                    emitter.state();
                    // 结局段后不再自动续（choose 只在 !isFinal 时可用）
                }

                @Override public void onError(final String msg) {
                    synchronized (StoryEngine.this) { busy = false; }
                    player.abandon(nextIndex);   // 半截文不念出来
                    emitError("llm", msg);
                }
            });
        } catch (Exception e) {
            busy = false;
            emitError("llm", "请求失败: " + e.getMessage());
        }
    }

    /** system + user 组装（SPEC §5.1/5.2）。包私有：单测钉人称注入与进度指令 */
    static List<String[]> buildMessages(Story s, int nextIndex) {
        List<String[]> msgs = new ArrayList<>();

        boolean isFinalTurn = nextIndex >= s.choicesTarget;   // 互动数用满 → 写结局
        String style = THEME_STYLE.getOrDefault(s.themeId, "讲一个好故事。");

        StringBuilder sys = new StringBuilder();
        sys.append("你是「Reverie」的睡前故事叙述者。").append(style).append('\n');
        // 叙述人称（M8）：作用于正文；选项永远是对听众的指令，保持第二人称口吻
        if ("first".equals(s.pov)) {
            sys.append("叙述人称：第一人称——正文以主角「我」的口吻讲述。\n");
        } else {
            sys.append("叙述人称：第三人称——正文以「他/她」或角色名讲述主角，勿用「你」称呼主角。\n");
        }
        sys.append("options 是给听众的指令，保持第二人称口吻（如「推开门看看」），不受叙述人称影响。\n");
        sys.append("输出必须是且只是一个 JSON 对象，字段顺序固定：segment, options, is_final, synopsis");
        if (s.title.isEmpty()) sys.append(", title");
        sys.append("。不要输出 JSON 之外的任何文字、解释或 markdown 围栏。\n");
        sys.append("segment：350–450 字的正文，中文，叙事完整、画面感强，结尾停在一个自然的抉择点。\n");
        sys.append("options：2–4 个给听众的选择，每个 6–20 字，方向彼此明显不同。\n");
        sys.append("is_final：true 仅当这是结局段；结局段 options 必须为 []。\n");
        sys.append("synopsis：100 字以内的滚动剧情梗概（角色、已发生的关键事件、未了线索），供后续续写保持连贯。\n");
        if (s.title.isEmpty()) sys.append("title：故事名，4–8 字，有意境。\n");
        sys.append("这是第 ").append(nextIndex + 1).append("/").append(s.choicesTarget)
           .append(" 个互动段。");
        if (isFinalTurn) sys.append("互动次数已用满，本段必须是结局（is_final=true，options=[]），收束全部伏笔。");
        msgs.add(new String[]{"system", sys.toString()});

        StringBuilder user = new StringBuilder();
        user.append("故事标题：").append(s.title.isEmpty() ? "（尚未取名，本次给出）" : s.title).append('\n');
        user.append("剧情梗概：").append(s.synopsis.isEmpty() ? "（刚开始，无）" : s.synopsis).append('\n');
        if (!s.segments.isEmpty()) {
            user.append("已发生（最近 ").append(Math.min(6, s.segments.size())).append(" 段）：\n");
            int from = Math.max(0, s.segments.size() - 6);
            for (int i = from; i < s.segments.size(); i++) {
                Story.Segment seg = s.segments.get(i);
                user.append("[").append(i + 1).append("] ").append(seg.text).append('\n');
                if (!seg.isFinal && seg.choice >= 0 && seg.choice < seg.options.size()) {
                    user.append("听众选择了：").append(seg.options.get(seg.choice)).append('\n');
                }
            }
        } else {
            user.append("请写开场段：介绍主角与处境，抛出第一个抉择。\n");
        }
        if (nextIndex > 0 && s.last() != null && s.last().choice >= 0) {
            Story.Segment last = s.last();
            user.append("听众刚刚的选择：").append(last.options.get(last.choice)).append('\n');
        }
        msgs.add(new String[]{"user", user.toString()});
        return msgs;
    }

    /* ---------- 事件投递（桥会在 UI 线程 evaluateJavascript） ---------- */

    private void emitDelta(String sentence) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "segment-delta");
            synchronized (this) {
                if (story == null) return;
                ev.put("index", story.segments.size());
            }
            ev.put("text", sentence);
            emitter.event(ev);
        } catch (Exception ignored) { }
    }

    private void emitDone(LlmClient.Result res) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "segment-done");
            synchronized (this) {
                ev.put("index", story.segments.size() - 1);
                ev.put("total", story.choicesTarget);
            }
            ev.put("text", res.segment);
            ev.put("options", new JSONArray(res.options));
            ev.put("isFinal", res.isFinal);
            emitter.event(ev);
        } catch (Exception ignored) { }
    }

    private void emitChoiceMade(int index, int choiceIndex, String heard) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "choice-made");
            ev.put("index", index);
            ev.put("choiceIndex", choiceIndex);
            ev.put("heard", heard == null ? JSONObject.NULL : heard);
            emitter.event(ev);
        } catch (Exception ignored) { }
    }

    private void emitError(String stage, String msg) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "error");
            ev.put("stage", stage);
            ev.put("msg", msg);
            emitter.event(ev);
        } catch (Exception ignored) { }
    }

    /**
     * TTS 事件 → JS（tts-state）。
     * state: speaking（句高亮）/ idle（段读完→弹选项）/ replay-done / degraded。
     * 由 TtsPlayer 锁内回调触发——这里只构造 JSON + 经桥投递，不碰 player。
     */
    private void emitTts(String state, int index, String text) {
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "tts-state");
            ev.put("state", state);
            ev.put("index", index);
            if (text != null) ev.put("text", text);
            emitter.event(ev);
        } catch (Exception ignored) { }
    }
}
