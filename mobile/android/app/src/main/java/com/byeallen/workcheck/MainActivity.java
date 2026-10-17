package com.byeallen.workcheck;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

/**
 * App 只有一个 WebView，内容全部来自「使用者自己填的站点」。这里做四件事：
 *   1. 站点地址不写死：首次启动先落在本地配置页，填完存起来，之后直接打开
 *   2. 把 WebView 调教成「能跑后台管理系统」的样子（Cookie、localStorage、不反色）
 *   3. 守住导航边界：自己的站点和飞书授权留在壳内，其它一律交系统浏览器
 *   4. 补上手机该有的手感：返回键、加载失败兜底页、状态栏沉浸式
 */
public class MainActivity extends BridgeActivity {

    private static final String PREFS = "workcheck";
    private static final String PREF_SITE = "siteUrl";
    /** 登录凭证的最后一次快照。见 persistSessionCookie() 的说明。 */
    private static final String PREF_COOKIE = "sessionCookie";

    /** 会话 Cookie 名，与站点 server/index.js 的 SESSION_COOKIE 保持一致。 */
    private static final String SESSION_COOKIE = "wc_session";

    /** 凭证快照的有效期，跟站点的 SESSION_MAX_AGE（7 天）对齐。 */
    private static final int SESSION_MAX_AGE_SEC = 7 * 24 * 60 * 60;

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
        + "a.alt{background:none;color:#9c8290;border:1px solid rgba(255,159,185,.22)}"
        + "</style></head><body>"
        + "<div class='t'>打不开看板</div>"
        + "<div class='d'>可能是网络不通，或者服务没在跑。<br>确认后再试一次。</div>"
        + "<a href='/'>重试</a>"
        // 直达站点之后就没有中转页了，地址填错时得有个能回去改的入口。
        // #setup 是给本地配置页看的：带它就停在填写表单上，不再自动跳走。
        + "<a class='alt' href='https://localhost/#setup'>换个地址</a>"
        + "</body></html>";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean backPressedOnce = false;

    /** 左下角那个「换/改站点地址」的圆形齿轮。默认藏着，每次进页面才露 1 秒。 */
    private ImageView fabSettings;
    private GradientDrawable fabBg;
    private final Runnable fabHide = new Runnable() {
        @Override
        public void run() {
            if (fabSettings == null) return;
            fabSettings.animate().alpha(0f).setDuration(350)
                .withEndAction(() -> {
                    fabSettings.setVisibility(View.GONE);
                    fabSettings.setClickable(false);
                }).start();
        }
    };

    /** 已保存的站点地址，结尾带 /。空串 = 还没配置过。 */
    private String siteUrl = "";

    /** 系统栏高度，单位是 CSS px（= dp），给网页避让用。 */
    private int safeTopCssPx = 0;
    private int safeBottomCssPx = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        siteUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SITE, "");
        applyTransparentSystemBars();
        tuneWebView();
        setupSettingsFab();
        // 已经配过站点：一启动就 loadUrl 直达看板，顶掉 Capacitor 刚发起的本地页加载
        // （那一版要经过中转页干等 1.5 秒才跳）。没配过就留在本地配置页填地址。
        // 补 Cookie 必须排在 loadUrl 前面：顺序反了首屏会先按未登录渲染一遍，
        // 然后再纠回来，看着像登录失效又自己恢复了。
        if (!siteUrl.isEmpty()) {
            restoreSessionCookie();
            loadSite(siteUrl);
        }
    }

    /**
     * 状态栏 / 导航条整条透明，WebView 铺满整屏画到它们底下 —— 页面的背景（包括背景图）
     * 才能一直顶到屏幕最上沿，和状态栏连成一片。
     *
     * 系统栏只吃颜色、不吃图片，所以「状态栏显示背景图」只能这么做：
     * 透明 + 让网页自己顶上去，而不是给状态栏设一个色值。
     * 这里千万别碰 WindowCompat.setDecorFitsSystemWindows：它在 Android 15 上已失效，
     * 却会把 WindowInsets 提前消耗掉，导致 WebView 读到的 top inset 变成 0。
     */
    private void applyTransparentSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 透明之后系统会为保证图标看得见，自动糊一层半透明遮罩 ——
            // 那层灰就是「明明设了透明、还是有一条」的元凶，关掉它。
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        // 背景是深色玻璃 / 深底图，图标用浅色才看得见
        WindowInsetsControllerCompat insets =
            WindowCompat.getInsetsController(w, w.getDecorView());
        insets.setAppearanceLightStatusBars(false);
    }

    /**
     * 读一次系统栏的真实高度，换算成 CSS px 存起来。
     * WebView 的 env(safe-area-inset-*) 只在「自己与系统栏重叠」且 Chromium 够新时才有值，
     * 老内核恒为 0 —— 兜底注入要用这里的数。
     */
    private void readSystemBarInsets() {
        WindowInsetsCompat wi = ViewCompat.getRootWindowInsets(getWindow().getDecorView());
        if (wi == null) return;
        Insets bars = wi.getInsets(
            WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        float density = getResources().getDisplayMetrics().density;
        safeTopCssPx = density > 0 ? Math.round(bars.top / density) : 0;
        safeBottomCssPx = density > 0 ? Math.round(bars.bottom / density) : 0;
    }

    /**
     * 页面加载完注入。做四件事：
     *   1. 挂上 --app-safe-top / --app-safe-bottom，页面想用直接用（比 env() 可靠）
     *   2. html 自己没背景图、body 有的话，把背景图同步到 html —— 保证状态栏那一条
     *      画的是页面那张背景图，而不是 WebView 的默认底色
     *   3. 隐藏顶栏的「↺ 默认排序」按钮；并给 sticky 的 .topbar 垫高状态栏 ——
     *      它吸的是 WebView 视口顶（= 屏幕物理顶），不垫的话滚动后标题和状态栏重叠。
     *      垫高后毛玻璃背景正好盖住状态栏区域，滚上去的内容从它后面过会被模糊掉。
     *   4. 没有 sticky 顶栏的页面，才补一层透明 border 把内容顶下去 ——
     *      用 border 而不是 padding，不会顶掉页面原有的 padding，
     *      背景图（background-clip 默认 border-box）也照样画满状态栏那一条。
     *      有 .topbar 的页面不能再加 border-top：topbar 吸顶后自己已经垫高，加了会双重。
     */
    private void injectPageTweaks(WebView view) {
        readSystemBarInsets();
        if (safeTopCssPx <= 0 && safeBottomCssPx <= 0) return;
        final String js =
            "(function(){try{"
            + "var T=" + safeTopCssPx + ",B=" + safeBottomCssPx + ";"
            + "var r=document.documentElement;if(!r)return;"
            + "r.style.setProperty('--app-safe-top',T+'px');"
            + "r.style.setProperty('--app-safe-bottom',B+'px');"
            + "var h=getComputedStyle(r),b=document.body?getComputedStyle(document.body):null;"
            + "if(b&&h.backgroundImage==='none'&&b.backgroundImage&&b.backgroundImage!=='none'){"
            + "r.style.backgroundImage=b.backgroundImage;r.style.backgroundSize=b.backgroundSize;"
            + "r.style.backgroundPosition=b.backgroundPosition;r.style.backgroundRepeat=b.backgroundRepeat;"
            + "r.style.backgroundAttachment=b.backgroundAttachment}"
            // 顶栏「↺ 默认排序」：手机上看板不做拖拽排序，留着只是占地方。
            // 它是 JS 按排序状态切换 hidden 属性的，用 !important 才压得住。
            // 同时给 sticky 的 .topbar 垫高状态栏：它吸的是 WebView 视口顶（= 屏幕物理顶），
            // 不垫的话滚动后标题和状态栏重叠。垫完后毛玻璃背景正好充当状态栏底色。
            // 站点自己的 padding-top 是 14px，这里只加 6px —— 再宽标题就离状态栏太远了。
            + "if(!document.getElementById('app-page-tweaks')){"
            + "var t=document.createElement('style');t.id='app-page-tweaks';"
            + "t.textContent='#resetOrder,button[title*=\"\\u9ed8\\u8ba4\\u6392\\u5e8f\"]{display:none !important}"
            + ".topbar{padding-top:calc(var(--app-safe-top,0px) + 6px) !important}';"
            + "(document.head||r).appendChild(t)}"
            + "var p=document.createElement('div');"
            + "p.style.cssText='position:absolute;left:0;top:0;width:0;height:0;"
            + "padding-top:env(safe-area-inset-top);visibility:hidden;pointer-events:none';"
            + "r.appendChild(p);"
            + "var v=parseFloat(window.getComputedStyle(p).paddingTop)||0;p.remove();"
            // WebView 自己也报了一个 top inset：取它和原生读数里较小的那个。
            // 两者理论上都等于状态栏高度，谁偏大就是谁把别的东西（刘海区之类）算进去了。
            + "if(v>0&&v<T){T=v;r.style.setProperty('--app-safe-top',T+'px');}"
            + "if(v>0)return;"                     // WebView 自己报对了，页面会按它避让，别重复加
            + "if(document.getElementById('app-safe-fallback'))return;"
            // 有 sticky 顶栏的页面顶部已由 .topbar 垫高，再加 border-top 会双重
            + "var rl='body{border-top:'+T+'px solid transparent !important;"
            + "border-bottom:'+B+'px solid transparent !important;box-sizing:border-box !important}';"
            + "if(document.querySelector('.topbar'))"
            + "rl='body{border-bottom:'+B+'px solid transparent !important;box-sizing:border-box !important}';"
            + "var s=document.createElement('style');s.id='app-safe-fallback';"
            + "s.textContent=rl;"
            + "(document.head||r).appendChild(s);"
            + "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 页面侧补几件静态的小事，以及为什么这里**不再做滚动期降级**：
     *   站点毛玻璃（.topbar 与一屏好几个 .panel 上的 backdrop-filter: blur(14px)）
     *   是中低端机掉帧的主因，v1.0.6 曾用「滚动时给 body 挂 wc-scrolling 关掉 blur、
     *   停手 160ms 恢复」来省这笔开销 —— 结果是每次起停都要重算图层 + 重绘，
     *   连续滚动时肉眼就是一阵闪。省下来的帧远抵不上闪一下的观感损失，整套撤掉。
     *   只留下不会引起状态切换的静态项，性能收益交给原生侧的渲染进程优先级。
     *
     * 留下来的两条都是从 web-updates.patch 里挑的（那套站点补丁还没合进上游）：
     *   overscroll-behavior: none —— 滚到头别起橡皮筋/下拉刷新，本身也消除一点抖动
     *   color-scheme: dark —— 站点没声明，表单控件会忽然冒一块白底；
     *                         原生侧的 FORCE_DARK_OFF 只管得住 ROM 的算法反色
     */
    private void injectPerfTweaks(WebView view) {
        final String js =
            "(function(){try{"
            + "if(window.__wcPerf)return;window.__wcPerf=1;"
            + "var st=document.createElement('style');st.id='app-perf';"
            + "st.textContent='html,body{overscroll-behavior:none}'"
            + "+':root{color-scheme:dark}';"
            + "(document.head||document.documentElement).appendChild(st);"
            + "}catch(e){}})();";
        view.evaluateJavascript(js, null);
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

        // WebView 只在「自己跟系统栏重叠」时才把 safe-area 报给网页，而且老内核恒报 0。
        // 这里原样返回 insets（不消费），让新内核能拿到完整的 systemBars / displayCutout 尺寸。
        ViewCompat.setOnApplyWindowInsetsListener(webView, (v, windowInsets) -> {
            readSystemBarInsets();
            return windowInsets;
        });

        // 首屏加载远程站点时 WebView 是空的，底色调成页面底色才不会白闪一下
        webView.setBackgroundColor(Color.parseColor("#1d1519"));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);   // 只信自家域的 Cookie

        // 本地配置页靠这个拿到 / 保存站点地址
        webView.addJavascriptInterface(new SiteBridge(), "WorkCheckNative");
        // 页面用它把当前主题色交回来，给左下角齿轮上色
        webView.addJavascriptInterface(new StyleBridge(), "WorkCheckStyle");

        // WebView 的渲染进程默认是「可牺牲」的：内存吃紧时系统会先降它的优先级，
        // 表现就是滚着滚着突然卡一拍、严重时画面先白一下。设为 IMPORTANT 让它跟前台同位。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }

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

    /* ------------------------------------------------------------- 登录凭证快照
       站点登录后发的是 httpOnly Cookie（Max-Age 7 天），理论上是持久的，
       但 WebView 把它写进自己的 SQLite 是异步的 —— 从最近任务划掉 App 时，
       那一瞬间常常还没落盘，下次进来就变成未登录。所以两手准备：
         退后台时：flush 一次，再顺手把这枚 Cookie 抄一份进 SharedPreferences
         进 App 时：先把快照喂回 WebView，再 loadUrl
       getCookie() 能读到 httpOnly（它是原生侧读的），所以这条备份走得通。 */

    private void persistSessionCookie() {
        CookieManager.getInstance().flush();   // 先把内存里的改动催到磁盘上
        if (siteUrl.isEmpty()) return;
        String raw = CookieManager.getInstance().getCookie(siteUrl);
        String token = null;
        if (raw != null) {
            for (String part : raw.split(";")) {
                String kv = part.trim();
                if (kv.startsWith(SESSION_COOKIE + "=")) {
                    token = kv;
                    break;
                }
            }
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (token == null) {
            // 登出过 / 站点清了 Cookie：快照必须跟着作废，
            // 否则下次启动会把一枚已经失效的凭证又塞回去，页面永远停在「已登录但 401」。
            sp.edit().remove(PREF_COOKIE).apply();
            return;
        }
        sp.edit().putString(PREF_COOKIE, token).apply();
    }

    private void restoreSessionCookie() {
        if (siteUrl.isEmpty()) return;
        String token = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_COOKIE, "");
        if (token.isEmpty()) return;
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        // 补上属性，保证它是持久 Cookie 而不是会话 Cookie
        cm.setCookie(siteUrl, token + "; Path=/; Max-Age=" + SESSION_MAX_AGE_SEC);
        cm.flush();
    }

    // 必须是 public：BridgeActivity 把 onPause() 放宽成了 public，子类不能收窄访问权限
    @Override
    public void onPause() {
        persistSessionCookie();
        super.onPause();
    }

    /* --------------------------------------------------- 左下角「改地址」的齿轮 */

    /**
     * 直达站点之后就没有「切换站点」按钮了，地址写错只能卸 App。
     * 补一个悬浮齿轮：进页面先露出来，1 秒后淡出，点到就回配置页重新填。
     * 每次加载新站点页都会重新露一次 —— 不会永久沉底再也找不着。
     * 外观不写死：颜色由页面主题回传（见 StyleBridge），换肤会跟着变。
     */
    private void setupSettingsFab() {
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(44 * d);

        fabBg = new GradientDrawable();
        fabBg.setShape(GradientDrawable.OVAL);
        fabBg.setColor(Color.parseColor("#332620"));                    // 缺省色，会被页面主题覆盖
        fabBg.setStroke(Math.max(1, Math.round(d)), Color.parseColor("#33FFBED2"));

        fabSettings = new ImageView(this);
        fabSettings.setImageResource(R.drawable.ic_settings);
        fabSettings.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        fabSettings.setPadding(Math.round(10 * d), Math.round(10 * d),
            Math.round(10 * d), Math.round(10 * d));
        fabSettings.setBackground(fabBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fabSettings.setElevation(6 * d);
        }
        fabSettings.setOnClickListener(v -> {
            hideSettingsFabNow();
            loadSite("https://localhost/#setup");      // #setup 让本地页停在表单上
        });

        FrameLayout.LayoutParams lp =
            new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Math.round(14 * d);
        lp.bottomMargin = Math.round(14 * d);
        fabSettings.setVisibility(View.GONE);
        // 挂在 DecorView 上而不是 WebView 里：网页怎么滚、怎么换都不受影响
        ((ViewGroup) getWindow().getDecorView()).addView(fabSettings, lp);

        // 手势导航时底 inset 是 0，三键导航得把按钮抬到导航栏上方
        fabSettings.post(() -> {
            readSystemBarInsets();
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) fabSettings.getLayoutParams();
            p.bottomMargin = Math.round((14 + safeBottomCssPx) * getResources().getDisplayMetrics().density);
            fabSettings.setLayoutParams(p);
        });
    }

    /** 露出来，并从 1 秒重新开始倒计时。同时按页面当前主题重新上一次色。 */
    private void flashSettingsFab() {
        if (fabSettings == null) return;
        mainHandler.removeCallbacks(fabHide);
        mainHandler.removeCallbacks(fabThemeRun);
        fabSettings.animate().cancel();
        fabSettings.setAlpha(1f);
        fabSettings.setVisibility(View.VISIBLE);
        fabSettings.setClickable(true);
        // 站点配色是运行时按 localStorage 里的偏好挂主题的，晚一点读才读得到切换后的值
        mainHandler.postDelayed(fabThemeRun, 500);
        mainHandler.postDelayed(fabHide, 1000);
    }

    /** 延期执行（flashSettingsFab 用）：从页面把主题色取回来。 */
    private final Runnable fabThemeRun = new Runnable() {
        @Override
        public void run() {
            Bridge bridge = getBridge();
            if (bridge != null) fetchPageTheme(bridge.getWebView());
        }
    };

    /**
     * 让页面把当前主题的几个变量交出来，齿轮照着上色 ——
     * 站点有好几套皮肤（初樱 / 雨夜…），写死一个色必然跟其中几套打架。
     */
    private void fetchPageTheme(WebView view) {
        final String js =
            "(function(){try{"
            + "if(typeof WorkCheckStyle==='undefined')return;"
            + "var c=getComputedStyle(document.documentElement);"
            + "function p(n){return (c.getPropertyValue(n)||'').trim()}"
            + "WorkCheckStyle.applyTheme(p('--bg'),p('--surface'),p('--border'),p('--accent'),p('--text'));"
            + "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    /**
     * 解析颜色。站点变量两种写法都出现过：#rrggbb 和 rgba(...)，
     * 而 Color.parseColor 只认前者，rgba 得自己拆。
     */
    private static int parseColorLoose(String value, int fallback) {
        if (value == null) return fallback;
        String v = value.trim();
        if (v.isEmpty()) return fallback;
        if (v.startsWith("#")) {
            try {
                return Color.parseColor(v);
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
        // rgba(r, g, b[, a]) / rgb(r, g, b)
        int open = v.indexOf('(');
        int close = v.lastIndexOf(')');
        if (open < 0 || close <= open) return fallback;
        String[] parts = v.substring(open + 1, close).split(",");
        if (parts.length < 3) return fallback;
        try {
            int r = clamp255(Integer.parseInt(parts[0].trim()));
            int g = clamp255(Integer.parseInt(parts[1].trim()));
            int b = clamp255(Integer.parseInt(parts[2].trim()));
            int a = 255;
            if (parts.length > 3) {
                a = Math.round(Float.parseFloat(parts[3].trim()) * 255);
            }
            return Color.argb(clamp255(a), r, g, b);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 按页面主题给齿轮上色：底取 --bg，描边取 --border，图标取 --accent。 */
    private void applyFabTheme(String bg, String surface, String border, String accent, String text) {
        if (fabBg == null || fabSettings == null) return;
        int base = parseColorLoose(bg, Color.parseColor("#332620"));
        int stroke = parseColorLoose(border, Color.parseColor("#33FFBED2"));
        // 图标优先跟着主色（--accent）走，主题没给主色时才退回正文色
        int tint = parseColorLoose(accent, parseColorLoose(text, Color.parseColor("#ff9fb9")));
        // 底色本身偏深时给它加一点不透明度，压住底下的网页内容，别让字透上来打架
        fabBg.setColor(Color.argb(0xE8, Color.red(base), Color.green(base), Color.blue(base)));
        fabBg.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)),
            Color.argb(0x55, Color.red(stroke), Color.green(stroke), Color.blue(stroke)));
        fabSettings.setColorFilter(tint);
        fabSettings.invalidate();
    }

    private void hideSettingsFabNow() {
        mainHandler.removeCallbacks(fabHide);
        if (fabSettings == null) return;
        fabSettings.animate().cancel();
        fabSettings.setAlpha(0f);
        fabSettings.setVisibility(View.GONE);
        fabSettings.setClickable(false);
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
            return "ERR:地址格式不对，形如 https://workcheck.example.com/";
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
    }

    /**
     * 给页面用的第二座桥：把当前主题的配色变量交回来。
     * 站点有多套皮肤，齿轮写死一个色必然跟其中几套打架。
     */
    private final class StyleBridge {

        /** 运行在 WebView 的后台线程，改 UI 必须切回主线程。 */
        @JavascriptInterface
        public void applyTheme(String bg, String surface, String border, String accent, String text) {
            mainHandler.post(() -> applyFabTheme(bg, surface, border, accent, text));
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
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            injectPageTweaks(view);
            injectPerfTweaks(view);
            // 配置页自己就有表单，不用再挂齿轮；站点页（含加载失败的兜底页）才露
            if (url != null && !isLocalPage(Uri.parse(url))) {
                flashSettingsFab();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // 只管主框架：子资源（图片、接口）失败不至于把整页换掉
            if (request.isForMainFrame()) showErrorPage(view);
        }
    }
}
