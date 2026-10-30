package org.offblink.reverie;

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
 * 范围：getState / getSettings / setSettings + 故事方法
 * （newStory / choose / listStories / openStory / deleteStory / retry）+ testEndpoint。
 * 语音（ASR/TTS）与诊断（getDiag/copyText/micTest）相关方法在 M12 已删除。
 */
public class ReverieBridge {

    interface Host {   // 原生 → JS 的回调出口，由 MainActivity 实现
        void onEvent(String evJson);
        void onState(String stJson);
    }

    private final Settings settings;
    private final Host host;
    private StoryEngine engine;   // M2 起注入

    public ReverieBridge(Settings settings, Host host) {
        this.settings = settings;
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
            return ok();
        } catch (JSONException e) {
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

    /** 供引擎推事件/快照到 JS（桥已在 UI 线程 evaluateJavascript） */
    void emitEvent(JSONObject ev) { host.onEvent(ev.toString()); }

    /** 引擎请求推全量快照：自己组装（engine 在桥的引用里） */
    void pushStateQuiet() {
        String st = getState();
        host.onState(st);
    }
}
