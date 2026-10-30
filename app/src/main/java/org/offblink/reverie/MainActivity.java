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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Settings settings = new Settings(this);
        bridge = new ReverieBridge(settings, this);

        // 故事引擎（M2）：事件经桥推 JS；快照变化桥主动推
        StoryEngine engine = new StoryEngine(
                new StoryStore(this),
                new StoryEngine.SettingsProvider() {
                    @Override public String endpoint() { return settings.endpoint(); }
                    @Override public String apiKey() { return settings.apiKey(); }
                    @Override public String chatModel() { return settings.chatModel(); }
                },
                new StoryEngine.Emitter() {
                    @Override public void event(JSONObject ev) { bridge.emitEvent(ev); }
                    @Override public void state() { bridge.pushStateQuiet(); }
                });
        bridge.attach(engine);

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
        webView.destroy();
        super.onDestroy();
    }
}
