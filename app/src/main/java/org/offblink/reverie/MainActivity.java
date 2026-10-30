package org.offblink.reverie;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * M0 壳：只验证 asset 管道（file:///android_asset/web/index.html 能加载）。
 * M1 起补：JS 桥（ReverieBridge）、四视图路由、edge-to-edge insets。
 * 全部可见 UI 都在 WebView 里——原生不放任何 UI 控件（SPEC §3.1）。
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);          // file:///android_asset 需要
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // TTS/音频策略在原生域，先关 WebView 手势门

        webView.setBackgroundColor(0xFF0B0B10);
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
