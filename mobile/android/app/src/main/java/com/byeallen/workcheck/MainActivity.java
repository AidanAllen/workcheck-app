package com.byeallen.workcheck;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

/**
 * App 只有一个 WebView，内容全部来自「使用者自己填的站点」。这里做四件事：
 *   1. 站点地址不写死：首次启动先落在本地配置页，填完存起来，之后直接打开
 *   2. 把 WebView 调教成「能跑后台管理系统」的样子（Cookie、localStorage、不反色）
 *   3. 守住导航边界：自己的站点和飞书授权留在壳内，其它一律交系统浏览器
 *   4. 补上手机该有的手感：返回键、加载失败兜底页、状态栏配色
 */
public class MainActivity extends BridgeActivity {

    private static final String PREFS = "workcheck";
    private static final String PREF_SITE = "siteUrl";

    /** 无论用户填的是什么站点，这几个域都放行。
        飞书那两个必须放行：授权页一旦被甩到外部浏览器，回调就再也回不到 App 里。 */
    private static final String[] FIXED_IN_APP_HOSTS = {
        "feishu.cn",
        "larksuite.com"
    };

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

    /** 已保存的站点地址，结尾带 /。空串 = 还没配置过。 */
    private String siteUrl = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        siteUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SITE, "");
        // 页面是深色玻璃，状态栏图标用浅色才看得见（edge-to-edge 下 Android 15 靠这个控制）
        WindowInsetsControllerCompat insets =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.setAppearanceLightStatusBars(false);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            getWindow().setStatusBarColor(Color.parseColor("#1d1519"));
        }
        // 只调状态栏配色，系统栏间距交给 CapacitorWebView 自带的 edge-to-edge 处理
        // （配置里的 android.adjustMarginsForEdgeToEdge = force）。
        // 这里千万别碰 WindowCompat.setDecorFitsSystemWindows：它在 Android 15 上已失效，
        // 却会把 WindowInsets 提前消耗掉，导致 Capacitor 读到的 top inset 变成 0。
        tuneWebView();
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

        // 本地配置页靠这个拿到 / 保存站点地址
        webView.addJavascriptInterface(new SiteBridge(), "WorkCheckNative");

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

    /** 本地配置页（www/index.html）是哪个 URL：取决于 androidScheme，一般是 https://localhost/ */
    private static boolean isLocalPage(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        return host != null && ("localhost".equals(host.toLowerCase()) || "127.0.0.1".equals(host));
    }

    private boolean isInApp(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        String configured = hostOf(siteUrl);
        if (configured != null && (host.equals(configured) || host.endsWith("." + configured))) {
            return true;
        }
        for (String h : FIXED_IN_APP_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return null;
        Uri u = Uri.parse(url);
        String h = u.getHost();
        return h == null ? null : h.toLowerCase();
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            /* 机器上没有能打开的浏览器，静默失败好过崩一个对话框 */
        }
    }

    private void showErrorPage(WebView view) {
        final String base = siteUrl.isEmpty() ? "https://localhost/" : siteUrl;
        mainHandler.post(() -> view.loadDataWithBaseURL(base, ERROR_PAGE, "text/html", "utf-8", null));
    }

    /**
     * 把用户填的东西整理成可用地址。
     *
     * @return 正常就返回 URL（结尾带 /）；不合法则返回 "ERR:<提示>"。
     */
    private static String normalizeSite(String input) {
        String s = (input == null ? "" : input).trim();
        if (s.isEmpty()) return "ERR:请填写看板站点地址";
        if (!s.matches("(?i)^https?://.+$")) {
            s = "https://" + s;   // 没写协议就按 https 补，省得用户手打
        }
        Uri u = Uri.parse(s);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            return "ERR:地址格式不对，形如 https://workcheck.byeallen.com/";
        }
        if (!"https".equals(scheme)) {
            // Manifest 里 usesCleartextTraffic=false，明文就算存了也加载不了，直接挡在入口上
            return "ERR:仅支持 HTTPS 站点（明文 http 已被系统禁止）";
        }
        if (!s.endsWith("/")) s = s + "/";
        return s;
    }

    /** 给本地配置页（www/index.html）用的桥。 */
    private final class SiteBridge {

        /** @return 已保存的站点地址；没配置过返回空串 */
        @JavascriptInterface
        public String getSavedSite() {
            return siteUrl;
        }

        /** @return 空串表示成功（随后由原生加载），否则返回错误提示 */
        @JavascriptInterface
        public String saveSite(String input) {
            String result = normalizeSite(input);
            if (result.startsWith("ERR:")) return result.substring(4);
            final String url = result;
            mainHandler.post(() -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_SITE, url).apply();
                siteUrl = url;
                loadSite(url);
            });
            return "";
        }

        /** 已配置过，直接打开（跳过校验） */
        @JavascriptInterface
        public void openSite(String url) {
            if (url == null || url.isEmpty()) return;
            mainHandler.post(() -> loadSite(url));
        }

        @JavascriptInterface
        public String appVersion() {
            return BuildConfig.VERSION_NAME;
        }
    }

    private void loadSite(String url) {
        Bridge bridge = getBridge();
        if (bridge != null) {
            bridge.getWebView().loadUrl(url);
        }
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
                if (isLocalPage(uri)) {
                    // 本地配置页：交回 Capacitor 自己处理
                    return super.shouldOverrideUrlLoading(view, request);
                }
                if (isInApp(uri)) return false;      // 自己的站点与飞书授权：留在壳内
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
