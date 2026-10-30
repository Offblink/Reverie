package org.offblink.reverie.engine;

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
    private final StoryStore store;
    private final Emitter emitter;
    private final SettingsProvider settings;

    private Story story;
    private boolean busy = false;
    private Call inflight;               // 生成中可取消（换故事时）
    private String lastSentence = "";    // 供 M3 TTS 接入：每句回调

    public interface SettingsProvider {   // 解耦：引擎只要三个值，不碰 Settings 类
        String endpoint();
        String apiKey();
        String chatModel();
    }

    public StoryEngine(StoryStore store, SettingsProvider settings, Emitter emitter) {
        this.store = store;
        this.settings = settings;
        this.emitter = emitter;
    }

    public synchronized Story story() { return story; }
    public synchronized boolean busy() { return busy; }

    /** 新故事：建档落盘，立即生成第 1 段。返回故事 id。 */
    public String newStory(final String themeId, final String tier) {
        final Story s = new Story();
        s.id = String.valueOf(System.currentTimeMillis());
        s.themeId = themeId;
        s.themeName = THEME_NAME.getOrDefault(themeId, themeId);
        s.tier = tier;
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
                    lastSentence = sentence;
                    emitDelta(sentence);
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
                    emitter.state();
                    // 结局段后不再自动续（choose 只在 !isFinal 时可用）
                }

                @Override public void onError(final String msg) {
                    synchronized (StoryEngine.this) { busy = false; }
                    emitError("llm", msg);
                }
            });
        } catch (Exception e) {
            busy = false;
            emitError("llm", "请求失败: " + e.getMessage());
        }
    }

    /** system + user 组装（SPEC §5.1/5.2） */
    private static List<String[]> buildMessages(Story s, int nextIndex) {
        List<String[]> msgs = new ArrayList<>();

        boolean isFinalTurn = nextIndex >= s.choicesTarget;   // 互动数用满 → 写结局
        String style = THEME_STYLE.getOrDefault(s.themeId, "讲一个好故事。");

        StringBuilder sys = new StringBuilder();
        sys.append("你是「Reverie」的睡前故事叙述者。").append(style).append('\n');
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
}
