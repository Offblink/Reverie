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
        webView.loadUrl("file:///android_asset/web/index.html");
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

    /** Android 返回键：先问 web（Host.back），页面不消费才 finish（Spore 同款契约） */
    @Override
    public void onBackPressed() {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.Host && Host.back ? Host.back() : false", value -> {
                    if (!"true".equals(value)) finish();
                }));
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
