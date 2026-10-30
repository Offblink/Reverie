package org.offblink.reverie;

import android.webkit.JavascriptInterface;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * JS 桥宿主：暴露给 WebView 的 window.Reverie。
 * 契约见 SPEC §3.2 与 assets/web/common.js 头注释——改一边必须同步另一边。
 * **只对方法加 @JavascriptInterface，JS 才看得见**。
 *
 * M1 范围：getState / getSettings / setSettings。
 * 故事方法（newStory/choose/…）随 M2 引擎接入注册。
 */
public class ReverieBridge {

    interface Host {   // 原生 → JS 的回调出口，由 MainActivity 实现
        void onEvent(String evJson);
        void onState(String stJson);
    }

    private final Settings settings;
    private final Host host;

    public ReverieBridge(Settings settings, Host host) {
        this.settings = settings;
        this.host = host;
    }

    private String err(Exception e) {
        return "{\"error\":\"" + JSONObject.quote(String.valueOf(e.getMessage())) + "\"}";
    }

    /** 全量快照：设置视图（无 key）+ 故事位（M2 填）+ busy */
    @JavascriptInterface
    public String getState() {
        try {
            JSONObject o = new JSONObject();
            o.put("settings", settings.publicView());
            o.put("story", JSONObject.NULL);   // M2: 当前故事快照
            o.put("busy", false);
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
            JSONObject o = new JSONObject();
            o.put("ok", true);
            return o.toString();
        } catch (JSONException e) {
            return err(e);
        }
    }

    /** 供引擎（M2+）推事件/快照到 JS */
    void emitEvent(JSONObject ev) { host.onEvent(ev.toString()); }
    void pushState(JSONObject st) { host.onState(st.toString()); }
}
