package org.offblink.reverie;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.offblink.reverie.engine.LlmClient;
import org.offblink.reverie.engine.StoryEngine;

/**
 * JS 桥宿主：暴露给 WebView 的 window.Reverie。
 * 契约见 SPEC §3.2 与 assets/web/common.js 头注释——改一边必须同步另一边。
 * **只对方法加 @JavascriptInterface，JS 才看得见**。
 *
 * M2 范围：getState / getSettings / setSettings + 故事方法
 * （newStory / choose / listStories / openStory / deleteStory / retry）。
 * M5 起：startAsr / stopAsr（含 RECORD_AUDIO 运行时申请）。
 */
public class ReverieBridge {

    interface Host {   // 原生 → JS 的回调出口，由 MainActivity 实现
        void onEvent(String evJson);
        void onState(String stJson);
    }

    static final int REQ_MIC = 7701;   // RECORD_AUDIO 请求码

    private final Settings settings;
    private final Host host;
    private final Activity act;        // 运行时权限申请需要 Activity
    private StoryEngine engine;        // M2 起注入
    private boolean pendingAsr;        // 授权弹窗期间挂起的开麦请求

    public ReverieBridge(Settings settings, Activity act, Host host) {
        this.settings = settings;
        this.act = act;
        this.host = host;
    }

    void attach(StoryEngine engine) { this.engine = engine; }

    private String err(Exception e) {
        return "{\"error\":\"" + JSONObject.quote(String.valueOf(e.getMessage())) + "\"}";
    }

    private String ok() {
        return "{\"ok\":true}";
    }

    /** 全量快照：设置视图（无 key）+ 当前故事 + busy */
    @JavascriptInterface
    public String getState() {
        try {
            JSONObject o = new JSONObject();
            o.put("settings", settings.publicView());
            synchronized (this) {
                if (engine != null && engine.story() != null) {
                    o.put("story", engine.story().toState());
                    o.put("busy", engine.busy());
                } else {
                    o.put("story", JSONObject.NULL);
                    o.put("busy", false);
                }
                o.put("ttsDegraded", engine != null && engine.ttsDegraded());
            }
            return o.toString();
        } catch (JSONException e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String getSettings() {
        try {
            return settings.publicView().toString();
        } catch (JSONException e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String setSettings(String patchJson) {
        try {
            settings.apply(new JSONObject(patchJson));
            if (engine != null) engine.applyTtsSettings();   // 语速/音色即时生效（M6）
            return ok();
        } catch (JSONException e) {
            return err(e);
        }
    }

    /** TTS 音色清单（M6，SPEC §3.2）：TTS 未就绪时空表，设置页据此提示 */
    @JavascriptInterface
    public String getVoices() {
        try {
            JSONArray arr = new JSONArray();
            if (engine != null) {
                for (String v : engine.voices()) arr.put(v);
            }
            return arr.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    /** 试听音色（M9）：{name} → {ok}；生成中拒绝，会打断当前朗读 */
    @JavascriptInterface
    public String previewTts(String argsJson) {
        try {
            String name = new JSONObject(argsJson).optString("name", "");
            JSONObject o = new JSONObject();
            o.put("ok", engine != null && engine.previewVoice(name));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    /* ---------- 故事（M2） ---------- */

    @JavascriptInterface
    public String newStory(String argsJson) {
        try {
            JSONObject a = new JSONObject(argsJson);
            String id = engine.newStory(a.getString("theme"), a.getString("tier"),
                    a.optString("pov", "third"));
            JSONObject o = new JSONObject();
            o.put("id", id);
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String choose(String argsJson) {
        try {
            JSONObject a = new JSONObject(argsJson);
            boolean ok = engine.choose(a.getInt("index"));
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String retry() {
        engine.retry();
        return ok();
    }

    @JavascriptInterface
    public String listStories() {
        try {
            JSONArray arr = new JSONArray();
            for (java.util.Map<String, Object> m : engine.list()) {
                JSONObject o = new JSONObject();
                for (java.util.Map.Entry<String, Object> e : m.entrySet()) o.put(e.getKey(), e.getValue());
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String openStory(String argsJson) {
        try {
            JSONObject a = new JSONObject(argsJson);
            JSONObject o = new JSONObject();
            o.put("ok", engine.open(a.getString("id")));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String deleteStory(String argsJson) {
        try {
            engine.delete(new JSONObject(argsJson).getString("id"));
            return ok();
        } catch (Exception e) {
            return err(e);
        }
    }

    /* ---------- 重听（M4，SPEC §3.2） ---------- */

    /** 重听全文：不带参 = 从头；带 fromSegment = 从指定段 */
    @JavascriptInterface
    public String replay(String argsJson) {
        try {
            Integer from = null;
            if (argsJson != null && !argsJson.isEmpty()) {
                JSONObject a = new JSONObject(argsJson);
                if (a.has("fromSegment") && !a.isNull("fromSegment")) from = a.getInt("fromSegment");
            }
            JSONObject o = new JSONObject();
            o.put("ok", engine.replay(from));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String replaySegment(String argsJson) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", engine.replaySegment(new JSONObject(argsJson).getInt("index")));
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    @JavascriptInterface
    public String stopReplay() {
        engine.stopReplay();
        return ok();
    }

    @JavascriptInterface
    public String testEndpoint() {
        try {
            long ms = new LlmClient()
                    .test(settings.endpoint(), settings.apiKey(), settings.chatModel());
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("latencyMs", ms);
            return o.toString();
        } catch (Exception e) {
            try {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("msg", String.valueOf(e.getMessage()));
                return o.toString();
            } catch (JSONException je) {
                return err(je);
            }
        }
    }

    /* ---------- 语音选择（M5，SPEC §7） ---------- */

    /** 开麦：没权限先弹系统授权，结果经 onRequestPermissionsResult 续上 */
    @JavascriptInterface
    public String startAsr() {
        try {
            if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingAsr = true;
                act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("needPermission", true);
                return o.toString();
            }
            JSONObject o = new JSONObject();
            o.put("ok", engine != null && engine.startAsr());
            return o.toString();
        } catch (Exception e) {
            return err(e);
        }
    }

    /** 手动关麦（丢弃当前录音） */
    @JavascriptInterface
    public String stopAsr() {
        if (engine != null) engine.stopAsr();
        return ok();
    }

    /** MainActivity.onRequestPermissionsResult 转进来：授权成功则续上挂起的开麦 */
    void onMicPermission(boolean granted) {
        if (granted) {
            if (pendingAsr) {
                pendingAsr = false;
                if (engine != null) engine.startAsr();
            }
        } else {
            pendingAsr = false;
            try {
                JSONObject ev = new JSONObject();
                ev.put("type", "asr-state");
                ev.put("state", "error");
                ev.put("msg", "麦克风权限被拒绝，语音选择不可用（仍可点击选择）");
                emitEvent(ev);
            } catch (JSONException ignored) { }
        }
    }

    /** 供引擎推事件/快照到 JS（桥已在 UI 线程 evaluateJavascript） */
    void emitEvent(JSONObject ev) { host.onEvent(ev.toString()); }

    /** 引擎请求推全量快照：自己组装（engine 在桥的引用里） */
    void pushStateQuiet() {
        String st = getState();
        host.onState(st);
    }
}
