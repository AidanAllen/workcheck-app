package com.byeallen.workcheck;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

/**
 * App 只有一个 WebView，内容全部来自线上站点。这里只做三件事：
 *   1. 把 WebView 调教成「能跑后台管理系统」的样子（Cookie、localStorage、不反色）
 *   2. 守住导航边界：主站和飞书授权留在壳内，其它一律交系统浏览器
 *   3. 补上手机该有的手感：返回键、加载失败兜底页、状态栏配色
 */
public class MainActivity extends BridgeActivity {

    /** 允许在 App 内直接打开的域名。
        飞书那两个域必须放行：授权页一旦被甩到外部浏览器，回调就再也回不到 App 里。 */
    private static final String[] IN_APP_HOSTS = {
        "workcheck.byeallen.com",
        "feishu.cn",
        "larksuite.com"
    };

    private static final String HOME = "https://workcheck.byeallen.com/";

    private static final String ERROR_PAGE =
        "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
        + "<title>加载失败</title><style>"
        + ":root{color-scheme:dark}"
        + "html,body{height:100%;margin:0}"
        + "body{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;"
        + "background:#1d1519;color:#fbe9ee;font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;"
        + "padding:calc(24px + env(safe-area-inset-top)) 28px calc(24px + env(safe-area-inset-bottom));text-align:center}"
        + ".t{font-size:16px;font-weight:600}.d{font-size:13px;color:#9c8290;line-height:1.7;max-width:300px}"
        + "a{display:inline-block;margin-top:8px;padding:10px 22px;border-radius:999px;background:#ff9fb9;"
        + "color:#3a1f28;font-size:14px;font-weight:600;text-decoration:none}"
        + "</style></head><body>"
        + "<div class='t'>打不开看板</div>"
        + "<div class='d'>可能是网络不通，或者服务没在跑。<br>确认后再试一次。</div>"
        + "<a href='/'>重试</a>"
        + "</body></html>";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean backPressedOnce = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 页面是深色玻璃，状态栏图标用浅色才看得见（edge-to-edge 下 Android 15 靠这个控制）
        WindowInsetsControllerCompat insets =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.setAppearanceLightStatusBars(false);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            getWindow().setStatusBarColor(Color.parseColor("#1d1519"));
        }
        // true = 让系统照常给内容留出系统栏空间，别把 WebView 铺到状态栏底下。
        // targetSdk 35 起 Android 15 会默认强制 edge-to-edge，不声明就是内容被状态栏压住。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        tuneWebView();
        applyStatusBarPadding();
    }

    /**
     * 把 WebView 的内容压到状态栏下面。
     *
     * 为什么不指望网页里的 env(safe-area-inset-top)：那套 CSS 在华为 / 鸿蒙的 WebView 上
     * 经常量出来是 0，撑不开。这里改用原生测量——先看 WebView 实际落在屏幕的哪个 y 位置，
     * 跟状态栏高度比一比，缺多少补多少。这样系统自己留了空间时（某些 ROM）不会重复加，
     * 系统没留时能补齐。
     */
    private void applyStatusBarPadding() {
        Bridge bridge = getBridge();
        WebView webView = bridge != null ? bridge.getWebView() : null;
        if (webView == null) return;
        // 布局没走完时拿到的位置是 0，post 到下一帧再量
        webView.post(() -> {
            int bar = getStatusBarHeight();
            if (bar <= 0) return;
            int[] loc = new int[2];
            webView.getLocationOnScreen(loc);
            webView.setPadding(0, Math.max(0, bar - loc[1]), 0, 0);
        });
    }

    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
    }

    /** 转屏、折叠展开、进分屏之后状态栏高度会变，重新量一次 */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyStatusBarPadding();
    }

    private void tuneWebView() {
        Bridge bridge = getBridge();
        if (bridge == null) return;
        WebView webView = bridge.getWebView();
        WebSettings s = webView.getSettings();

        s.setDomStorageEnabled(true);      // 主题、面板折叠与排序偏好都存 localStorage
        s.setDatabaseEnabled(true);
        s.setSupportZoom(false);           // 看板不需要缩放，禁掉避免误触
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);   // 全站 HTTPS，不放行明文子资源
        s.setUserAgentString(s.getUserAgentString() + " KanbanNiangApp/" + BuildConfig.VERSION_NAME);

        // 华为 / 小米 / OPPO 的深色模式会对 WebView 做算法强制反色，深色玻璃面板会被反成一坨怪色。
        // 页面本身就是深色（CSS 已声明 color-scheme: dark），这里再从系统侧关掉，两端都堵住才稳。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);
        }

        // Android 12 起滚到顶 / 滚到底会有一段把内容拉变形再弹回的动画（取代了老的边缘发光），
        // 在数据看板这种密排面板上看着像「抖了一下」。直接关掉过滚动。
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 首屏加载远程站点时 WebView 是空的，底色调成页面底色才不会白闪一下
        webView.setBackgroundColor(Color.parseColor("#1d1519"));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);   // 只信自家域的 Cookie

        webView.setWebViewClient(new AppWebViewClient(bridge));
    }

    /** 返回键：先看网页能不能后退，不能就双击退出（避免看一半被一按送走） */
    @Override
    public void onBackPressed() {
        Bridge bridge = getBridge();
        WebView webView = bridge != null ? bridge.getWebView() : null;
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (backPressedOnce) {
            super.onBackPressed();
            return;
        }
        backPressedOnce = true;
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(() -> backPressedOnce = false, 2000);
    }

    private static boolean isInApp(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        for (String h : IN_APP_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            /* 机器上没有能打开的浏览器，静默失败好过崩一个对话框 */
        }
    }

    private void showErrorPage(WebView view) {
        mainHandler.post(() -> view.loadDataWithBaseURL(HOME, ERROR_PAGE, "text/html", "utf-8", null));
    }

    private final class AppWebViewClient extends BridgeWebViewClient {

        AppWebViewClient(Bridge bridge) {
            super(bridge);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

            if ("https".equals(scheme)) {
                if (isInApp(uri)) return false;      // 主站与飞书授权：留在壳内
                openExternal(uri);                    // 别的站点：交系统浏览器，壳内不加载
                return true;
            }
            if ("http".equals(scheme)) {
                // 明文一律不进壳：既防中间人，也让写死 localhost 的旧回调有个明确的出口
                openExternal(uri);
                return true;
            }
            // capacitor://、file://、tel: 等交回 Capacitor 自己处理
            return super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // 只管主框架：子资源（图片、接口）失败不至于把整页换掉
            if (request.isForMainFrame()) showErrorPage(view);
        }
    }
}
