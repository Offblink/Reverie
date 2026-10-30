package org.offblink.reverie;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.offblink.reverie.engine.StoryEngine;
import org.offblink.reverie.engine.StoryStore;

/**
 * 壳：WebView 宿主 + 桥注册 + edge-to-edge + 返回键转发。
 * 全部可见 UI 在 assets/web（SPEC §3.1），原生不放 UI 控件。
 */
public class MainActivity extends Activity implements ReverieBridge.Host {

    private WebView webView;
    private ReverieBridge bridge;
    private StoryEngine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Settings settings = new Settings(this);
        bridge = new ReverieBridge(settings, this, this);

        // 故事引擎（M2）+ TTS 播放器（M4）+ ASR（M5）：事件经桥推 JS；快照变化桥主动推
        StoryEngine engine = new StoryEngine(
                this,
                new StoryStore(this),
                new StoryEngine.SettingsProvider() {
                    @Override public String endpoint() { return settings.endpoint(); }
                    @Override public String apiKey() { return settings.apiKey(); }
                    @Override public String chatModel() { return settings.chatModel(); }
                    @Override public float ttsRate() { return settings.ttsRate(); }
                    @Override public String ttsVoice() { return settings.ttsVoice(); }
                    @Override public String asrModel() { return settings.asrModel(); }
                    @Override public String asrMode() { return settings.asrMode(); }
                    @Override public String asrEndpoint() { return settings.asrEndpoint(); }
                    @Override public String asrApiKey() { return settings.asrApiKey(); }
                    @Override public int micWindowSec() { return settings.micWindowSec(); }
                },
                new StoryEngine.Emitter() {
                    @Override public void event(JSONObject ev) { bridge.emitEvent(ev); }
                    @Override public void state() { bridge.pushStateQuiet(); }
                });
        bridge.attach(engine);
        this.engine = engine;

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);          // file:///android_asset 需要
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setBackgroundColor(0xFF0B0B10);
        webView.addJavascriptInterface(bridge, "Reverie");
        webView.setWebViewClient(new WebViewClient());
        // console.log → logcat（排错：adb logcat | grep reverie）
        webView.setWebChromeClient(new WebChromeClient());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        applyInsets();
        registerBackHandler();
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    /**
     * 返回键双轨（SPEC §3.2 契约：先问 web，页面不消费才 finish）：
     * API 33+ 走 OnBackInvokedCallback（targetSdk 37 的派发路径，onBackPressed 不触发）；
     * 低版本走 onBackPressed 覆写。
     */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void handleBack() {
        android.util.Log.d("reverie", "back pressed, asking JS");
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.Host && Host.back ? Host.back() : false", value -> {
                    android.util.Log.d("reverie", "back result=" + value);
                    if (!"true".equals(value)) finish();
                }));
    }

    /** 状态栏透明后，把系统栏 inset 传给 WebView 由 CSS env(safe-area-inset-*) 消费 */
    private void applyInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            webView.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                v.setPadding(0, 0, 0, 0); // inset 走 CSS env()，原生不垫
                return insets;
            });
        } else {
            webView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /** Android 返回键（API < 33 路径；33+ 走 OnBackInvokedCallback） */
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** 前后台跟踪：退后台即关麦（录音需前台，SPEC §6） */
    @Override
    protected void onStart() {
        super.onStart();
        if (engine != null) engine.setForeground(true);
    }

    @Override
    protected void onStop() {
        if (engine != null) engine.setForeground(false);
        super.onStop();
    }

    /** RECORD_AUDIO 授权结果 → 桥续上挂起的开麦 / 发 error 事件 */
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != ReverieBridge.REQ_MIC) return;
        bridge.onMicPermission(results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    /* ---------- ReverieBridge.Host：原生 → JS ---------- */

    @Override
    public void onEvent(String evJson) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.Host && Host.onEvent && Host.onEvent(" + evJson + ")", null));
    }

    @Override
    public void onState(String stJson) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.Host && Host.onState && Host.onState(" + stJson + ")", null));
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.shutdown();   // 停 TTS，防泄漏
        webView.destroy();
        super.onDestroy();
    }
}
