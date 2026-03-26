package org.MrZ.vscode_webview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private FileSystemBridge fsBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 沉浸式全屏：隐藏状态栏和导航栏
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }

        webView = findViewById(R.id.webview);

        // 配置 WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 允许通过 file:// 访问（polyfill 注入需要）
        settings.setAllowFileAccess(true);

        // 注册 JS 桥接
        fsBridge = new FileSystemBridge(this, webView);
        webView.addJavascriptInterface(fsBridge, "AndroidBridge");

        // 页面加载完成后注入 polyfill
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectPolyfill(view);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                android.util.Log.d("WebConsole",
                        msg.message() + " [" + msg.sourceId() + ":" + msg.lineNumber() + "]");
                return super.onConsoleMessage(msg);
            }
        });

        webView.loadUrl("https://vscode.dev");
    }

    /**
     * 从 assets 加载 polyfill.js 并注入到页面中
     */
    private void injectPolyfill(WebView view) {
        try {
            InputStream is = getAssets().open("polyfill.js");
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            is.close();
            String js = new String(buffer, "UTF-8");
            view.evaluateJavascript(js, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 接收文件/文件夹选择器返回的结果，转发给桥接层
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (fsBridge != null) {
            fsBridge.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
