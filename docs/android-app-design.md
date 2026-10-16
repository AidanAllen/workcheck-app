# WorkCheck 安卓 / 华为鸿蒙 App 技术设计方案

> 目标：把已部署站点 `https://workcheck.example.com/` 封装成移动端 App，
> 在**普通安卓手机**与**华为手机（含 HarmonyOS）**上都能稳定、完整地使用全部功能
> （看板、日志 SSE、签到日历、接入配置登录、飞书 OAuth 授权）。
>
> 定位：**原生壳 + 线上站点**。数据、逻辑、页面全部在服务端，App 只负责 WebView 容器、
> 系统适配与原生体验。不重写前端、不做离线版。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 推荐方案 | **Capacitor 7（Android）** 做安卓/HarmonyOS 3~4 的壳；**ArkTS ArkWeb** 做 HarmonyOS NEXT 的壳（按需） |
| 加载方式 | 远程 URL `https://workcheck.example.com/`，不打包前端代码进 App |
| 构建方式 | **GitHub Actions 云构建**（本机当前无 JDK / Android SDK / Gradle，无需安装即可出 APK） |
| 体积预估 | Android 壳 ~6–10 MB；鸿蒙壳 ~1 MB |
| 必须先改的服务端项 | **飞书 OAuth 回调地址**（现默认 `http://localhost:3000/feishu/callback`，App 内必然失败） |
| 华为最大坑 | ① HarmonyOS NEXT **不支持 APK**，必须单独出鸿蒙版 ② 系统深色模式对 WebView **强制反色**，会毁掉看板配色 |
| 工期 | 安卓壳 1 天 / 鸿蒙壳 0.5 天 / 联调与真机验收 1 天 |

---

## 1. 现状盘点（已核查，非推测）

| 检查项 | 现状 | 对 App 的影响 |
|---|---|---|
| 站点可达性 | `HTTP 200`，openresty 反代，已开 `Strict-Transport-Security` | ✅ 全 HTTPS，WebView 无需放行明文流量 |
| 会话机制 | `sessions` 表 + `HttpOnly; SameSite=Lax` Cookie，7 天有效 | ✅ 需在 WebView 显式开启 Cookie 与第三方 Cookie 策略 |
| 实时日志 | `GET /api/stream/logs` SSE（`EventSource`） | ⚠️ App 切后台会被断连，回到前台需重建连接（当前前端**没有** `visibilitychange` 重连逻辑） |
| 主题 | 存 `localStorage`（`workcheck.theme`），首屏内联脚本读取 | ⚠️ 需开启 `domStorageEnabled`，否则每次冷启动闪默认主题 |
| 飞书 OAuth 回调 | `server/config.js` 中 `DEFAULT_REDIRECT = 'http://localhost:3000/feishu/callback'` | 🔴 **P0 阻断**：App 内授权后会跳到一个打不开的 localhost 地址，授权链路断死 |
| 移动端适配 | `viewport` 仅 `width=device-width`，**无** `viewport-fit=cover`、无 `safe-area-inset`、无 `theme-color`、无 `manifest` | ⚠️ 刘海屏/挖孔屏状态栏遮挡顶部栏；手势导航条压住底部内容 |
| 页面渲染 | 玻璃拟态 + `backdrop-filter` + 壁纸 | ⚠️ 需确认目标 WebView 为 Chromium 90+；华为/低端机需关闭强制深色 |
| 数据接口 | 看板类接口免登录，管理类接口需登录 | ✅ App 内可完整使用；登录态由 Cookie 承载 |

---

## 2. 运行环境矩阵（决定要不要出两个包）

| 系统 | 内核 | 能装 APK？ | 处理方式 |
|---|---|---|---|
| Android 8 ~ 15（小米/OPPO/vivo/三星/一加…） | Android | ✅ | **同一个 APK** 覆盖 |
| EMUI 12 / HarmonyOS 2·3·4（华为/荣耀老机型） | Android 10~12 兼容层 | ✅ | 同一 APK，追加华为专项适配（见 §5.2） |
| **HarmonyOS NEXT 5.x（纯血鸿蒙，Mate 60 之后新机）** | **鸿蒙内核，非 Android** | ❌ **完全不支持 APK** | 必须单独出 **ArkTS / ArkWeb 鸿蒙应用** |

> 判断方法：华为手机「设置 → 关于本机」看版本号。
> 显示 `HarmonyOS 4.x` 及以下 → APK 可用；显示 `HarmonyOS 5.x / NEXT` → 只能装鸿蒙版。
> **这是本项目最重要的一条分支结论**：不管选什么封装技术，华为 NEXT 用户都拿不到 APK。

---

## 3. 方案对比与选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. Capacitor 7**（推荐） | 与 Node 工程同栈；配置即代码；插件生态（状态栏/启动屏/分享/通知）；真机调试方便；云端可构建 | 包体略大；深定制仍要改原生 | ✅ 主选 |
| B. 纯原生 WebView 壳（Kotlin ~200 行） | 体积极小（1–2 MB）；零第三方依赖；完全可控 | 所有原生能力自己写；插件化能力弱 | 备选（若只要"能打开就行"） |
| C. TWA / Bubblewrap | 最省事，Chrome 渲染，性能好 | **国内机型普遍无 Chrome/Play 服务 → 退化成 Custom Tab，露出地址栏**；无法自定义返回键、错误页 | ❌ 不推荐（国内环境） |
| D. Flutter WebView | 跨端统一 | 引入 Dart 技术栈，团队不同栈，包体 +15 MB | ❌ 不推荐 |
| E. **ArkTS + ArkWeb**（鸿蒙 NEXT） | 鸿蒙官方方案，Web 组件直接加载站点 | 需 DevEco Studio，另建工程 | ✅ 华为 NEXT 必选 |

**最终架构**：一个仓库，两条产物线，共用同一个线上站点。

```
workcheck/
├─ server/  public/  scripts/        ← 现状，不动
└─ mobile/
   ├─ android/                        ← Capacitor 工程（出 APK/AAB，覆盖 Android + HarmonyOS 2~4）
   └─ harmony/                        ← DevEco ArkTS 工程（出 HAP，覆盖 HarmonyOS NEXT）
```

---

## 4. 推荐架构

```
┌──────────────────────── 手机端 ────────────────────────┐
│  SplashScreen（品牌启动屏，遮住白屏）                    │
│  ┌───────────────────────────────────────────────────┐ │
│  │ WebView（Android System WebView / 鸿蒙 ArkWeb）    │ │
│  │  · Cookie 持久化 ← 承载 7 天登录态                 │ │
│  │  · localStorage ← 主题、面板折叠/排序偏好          │ │
│  │  · SSE ← /api/stream/logs 实时日志                 │ │
│  │  · 导航白名单拦截 ← 飞书授权域名在壳内打开         │ │
│  └───────────────────────────────────────────────────┘ │
│  原生层：返回键 / 下拉刷新 / 错误重试页 / 状态栏配色 /   │
│          安全区 / 外部浏览器兜底                         │
└────────────────────────────────────────────────────────┘
                        │ HTTPS (TLS, HSTS)
                        ▼
        https://workcheck.example.com/  (Express + SQLite)
```

---

## 5. 详细设计

### 5.1 Android 壳（Capacitor 7）

#### 5.1.1 工程初始化

```bash
mkdir -p mobile && cd mobile
npm init -y
npm i @capacitor/core@^7 @capacitor/cli@^7
npm i -D @capacitor/android@^7
npx cap init "看板娘" "com.byeallen.workcheck" --web-dir=www
npx cap add android
```

> 独立 `mobile/package.json`，**不污染**服务端依赖（`better-sqlite3` 需要编译，不要牵进移动端构建）。

#### 5.1.2 `capacitor.config.json`（关键）

```json
{
  "appId": "com.byeallen.workcheck",
  "appName": "看板娘",
  "webDir": "www",
  "server": {
    "url": "https://workcheck.example.com/",
    "cleartext": false,
    "androidScheme": "https"
  },
  "android": {
    "allowMixedContent": false,
    "captureInput": true,
    "webContentsDebuggingEnabled": false,
    "loggingBehavior": "none"
  },
  "plugins": {
    "SplashScreen": { "launchAutoHide": false, "backgroundColor": "#1b1520", "showSpinner": false },
    "StatusBar":    { "style": "DarkContent", "backgroundColor": "#1b1520" },
    "Keyboard":     { "resize": "body" }
  }
}
```

- `server.url` 走远程站点；本地调试时改成本机局域网地址即可，改完 `npx cap sync`。
- `webDir: www` 只放一个**极简 loading 页**（纯 CSS 动画 + 品牌色），它只在首帧闪一下，
  真正内容由远程站点接管 —— 弱网下也不至于白屏。
- ⚠️ **Capacitor 已知坑**：远程 URL 模式下，若页面要调用原生插件（如通知、分享），
  服务端页面必须引入 `@capacitor/core` 的 JS（npm 包或把 `capacitor.js` 放到 `public/` 静态托管）。
  **本期只做纯壳，不调用插件，此坑自动规避**；后续加插件时再补。

#### 5.1.3 `MainActivity.java` — WebView 关键配置

Capacitor 的 `BridgeActivity` 已封装大部分，但以下**必须在 `MainActivity.onCreate` 里显式追加**
（写在 `mobile/android/app/src/main/java/com/byeallen/workcheck/MainActivity.java`）：

```java
public class MainActivity extends BridgeActivity {
  @Override public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    configureWebView();
  }

  private void configureWebView() {
    WebView wv = getBridge().getWebView();
    WebSettings s = wv.getSettings();

    s.setDomStorageEnabled(true);            // 主题 / 面板偏好（localStorage）
    s.setDatabaseEnabled(true);
    s.setJavaScriptEnabled(true);
    s.setLoadWithOverviewMode(false);
    s.setUseWideViewPort(true);
    s.setSupportZoom(false);                 // 禁用缩放，避免误触
    s.setBuiltInZoomControls(false);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);   // 全站 HTTPS
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    s.setUserAgentString(s.getUserAgentString() + " WorkCheckApp/" + BuildConfig.VERSION_NAME);

    // 🔴 华为 / 小米 / OPPO 深色模式会强制反色，直接毁掉看板配色 —— 必须关掉
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      s.setForceDark(WebSettings.FORCE_DARK_OFF);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      s.setAlgorithmicDarkeningAllowed(false);
    }

    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    cm.setAcceptThirdPartyCookies(wv, false);  // 只信自家域，第三方 Cookie 不放开

    wv.setLongClickable(false);                // 禁长按菜单（可选）
    wv.setWebViewClient(new BridgeWebViewClient(this) { /* 见 5.1.4 */ });
  }
}
```

#### 5.1.4 导航白名单（决定"哪些页面留在 App 内"）

这是**飞书授权能不能走通**的关键：授权页若被甩到外部浏览器，回调就回不到 App。

| 域名 | 处理 |
|---|---|
| `workcheck.example.com` | ✅ WebView 内打开（主站） |
| `open.feishu.cn` / `accounts.feishu.cn` / `passport.feishu.cn` / `*.feishu.cn` | ✅ WebView 内打开（OAuth 授权链路） |
| 其它一切 | ⤴ 交给系统浏览器（`Intent.ACTION_VIEW`），壳内不留 |

```java
private static final String[] IN_APP_HOSTS = {
  "workcheck.example.com", ".feishu.cn"
};

@Override
public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
  Uri u = req.getUrl();
  String scheme = u.getScheme();
  if ("https".equals(scheme) && isInApp(u.getHost())) return false;          // 壳内打开
  if ("http".equals(scheme)) return true;                                     // 明文一律拒绝
  if ("tel".equals(scheme) || "mailto".equals(scheme)) { /* 交系统 */ }
  startActivity(new Intent(Intent.ACTION_VIEW, u));                           // 其余外跳
  return true;
}
```

> 注意：不要把 `http://` 放行。`DEFAULT_REDIRECT` 现在就是 `http://localhost:3000/...`，
> 改不动代码的话至少要在服务端 `config.json` 里配成 https 域名（见 §5.3）。

#### 5.1.5 返回键 / 下拉刷新 / 错误页

| 能力 | 实现 |
|---|---|
| 返回键 | `onBackPressed()`：`webView.canGoBack()` → `goBack()`；否则 **2 秒内二次点击退出**（Toast 提示），防止误触直接退出查看一半的看板 |
| 下拉刷新 | `SwipeRefreshLayout` 包 WebView → `webView.reload()`；配色跟随主题深色，进度圈用品牌色 |
| 加载失败 | `onReceivedError` / `onReceivedHttpError` → 显示内置错误页（"网络不可用 / 服务暂时打不开" + **重试**按钮 + 打开外部浏览器兜底），不要让用户对着 WebView 默认报错页 |
| 前后台 | `onResume` → 注入 `document.dispatchEvent(new Event('visibilitychange'))` 或直接 `webView.evaluateJavascript("location.reload()")` 视情况；推荐前者更轻（配合 §5.3 前端重连） |
| 进程被杀 | `onSaveInstanceState` 保存当前 URL，`onCreate` 恢复，避免回到首页丢失上下文 |

#### 5.1.6 状态栏 / 刘海 / 手势条

- 状态栏：`WindowInsetsControllerCompat`，`isAppearanceLightStatusBars = false`（页面是深色玻璃，状态栏图标须为浅色），背景色 `#1b1520`（初樱深部色）。
- Android 15（API 35）默认 **edge-to-edge**：用 `ViewCompat.setOnApplyWindowInsetsListener` 处理 `systemBars` / `displayCutout` insets，别让顶部栏被挖孔屏切掉。
- 前端配合 `viewport-fit=cover` + `env(safe-area-inset-*)`（§5.3），两端一起做才彻底。

#### 5.1.7 图标与启动屏

- 自适应图标（Adaptive Icon）：前景 108dp 安全区内的品牌图形，背景取壁纸主色；提供 `mipmap-anydpi-v26/ic_launcher.xml` + 各密度 PNG/WebP。
- 启动屏：Android 12+ 用官方 `SplashScreen` API（Capacitor SplashScreen 插件已封装），
  `launchAutoHide=false` → 页面 `onPageFinished` 后再隐藏，避免白屏断层。
- 品牌资产可从现有壁纸取色，图标建议单独出一张（可用 🌸 / 📊 语义图形，纯色底 + 单色符号，小尺寸才认得出）。

---

### 5.2 华为 / HarmonyOS 专项适配（本次重点）

#### 5.2.1 HarmonyOS 3/4（APK 兼容层）— 五个必做项

| # | 问题 | 处理 |
|---|---|---|
| 1 | **深色模式强制反色**：华为/小米系 ROM 的"深色模式"会对 WebView 做算法反色，深色玻璃面板会变成一坨怪色 | `FORCE_DARK_OFF` + `setAlgorithmicDarkeningAllowed(false)`（已在 5.1.3）+ 前端 `color-scheme: dark`（§5.3）。**两端都要做**，只做一端无效 |
| 2 | **纯净模式拦截安装**：非应用市场 APK 安装时被"纯净模式/增强防护"挡下 | 分发时附一句说明：设置 → 系统和更新 → 纯净模式 → 关闭（或"增强防护"关掉）；正规做法是上架华为应用市场 |
| 3 | **后台被杀 / SSE 断连**：华为"手机管家"默认禁止应用自启动与后台活动，切回来日志流不刷新 | ① 前端做 `visibilitychange` 重连（§5.3）② 首次启动弹一次引导："设置 → 应用 → 应用启动管理 → 关闭本应用的自动管理 → 允许后台活动"，只弹一次并允许"不再提示" |
| 4 | **电池优化**：省电策略会限制网络与定时器 | 引导加入电池优化白名单（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，谨慎使用，仅提示不强制） |
| 5 | **无 GMS**：华为新机没有 Google 服务 | 不能用 FCM。轻量需求用 **WorkManager 定时轮询**（如每 30 分钟查一次余额/签到）；强实时需求接 **HMS Push Kit**（见下） |

**后台数据更新策略（不接推送也能用）**：

```
WorkManager 周期任务（15 分钟下限，非精确）
  → GET /api/checkin/daily?days=1
  → 与上次快照对比，出现「签到失败 / 余额低于阈值」才发通知
  → 通知渠道 IMPORTANCE_DEFAULT，避免打扰
```
> 轮询接口用**看板类免登录接口**，不涉及管理员会话，App 侧无需存凭证。

**若要强实时**：接入 HMS Push Kit（`com.huawei.hms:push`），服务端 `server/` 增加推送下发（用 AppGallery Connect 的 App ID/Secret，走 `data/config.json` 同款配置方式）。这是**可选二期**，不影响一期出包。

#### 5.2.2 HarmonyOS NEXT 5.x（纯血鸿蒙）— 必须单独出包

> APK 在其上**无法安装**，任何安卓封装方案都救不了。需要 DevEco Studio + ArkTS。
> 好在它是纯壳，代码量极小（约 80 行）。

`mobile/harmony/entry/src/main/ets/pages/Index.ets` 骨架：

```ts
import { webview } from '@kit.ArkWeb';
import { BusinessError } from '@kit.BasicServicesKit';

@Entry
@Component
struct Index {
  private controller: webview.WebviewController = new webview.WebviewController();
  @State progress: number = 0;
  @State failed: boolean = false;

  build() {
    Stack() {
      Web({ src: 'https://workcheck.example.com/', controller: this.controller })
        .domStorageAccess(true)          // localStorage：主题/折叠偏好
        .fileAccess(false)
        .javaScriptAccess(true)
        .imageAccess(true)
        .cacheMode(CacheMode.Default)
        .mixedMode(MixedMode.None)       // 禁止明文
        .userAgent('WorkCheckHM/1.0')
        .width('100%').height('100%')
        .onPageBegin(() => { this.failed = false; })
        .onProgressChange(e => { this.progress = e.newProgress; })
        .onErrorReceive(() => { this.failed = true; })
        .onHttpErrorReceive(() => { this.failed = true; })

      if (this.failed) { /* 错误页 + 重试按钮：this.controller.loadUrl(HOME) */ }
    }
    .width('100%').height('100%')
  }

  onBackPress(): boolean {                // 返回键：先网页后退
    if (this.controller.accessBackward()) { this.controller.backward(); return true; }
    return false;                          // 交系统退出
  }
}
```

配套：
- `module.json5` 声明权限：`ohos.permission.INTERNET`、`ohos.permission.GET_NETWORK_INFO`
- 网络安全配置禁止明文（`network_config.json` cleartextPermitted=false）
- 启动页/图标走 DevEco 模板；签名用 AppGallery Connect 证书
- 鸿蒙 Web 组件同样受深色模式影响 → ArkWeb 侧用 `.darkMode(WebDarkMode.Off)` 显式关闭
- 上架：AppGallery Connect，需华为开发者账号 + 备案/隐私政策

#### 5.2.3 上架要点（华为应用市场）

- 包格式：**AAB 优先**（Google Play）/ **APK 或 HAP**（华为），华为当前主收 APK，鸿蒙 NEXT 收 HAP
- `targetSdkVersion` 跟随市场最新要求（撰写时主流要求 ≥ 34），每年复核一次
- 必须提供：**隐私政策 URL + 应用备案**（国内上架硬门槛）
- 权限最小化：只申请 `INTERNET` / `ACCESS_NETWORK_STATE`，不申请通讯录、定位、存储
- 隐私政策要写清：日志 IP 只入库不下发；管理员凭证仅存服务端

---

### 5.3 服务端 / 前端必须改造项

| 级别 | 项 | 改法 |
|---|---|---|
| 🔴 **P0** ✅ 已实施 | 飞书 OAuth 回调地址 | `server/config.js` 新增 `getRedirect(req)`：显式配置优先 → 未配则按当前访问域名推导 → 常量兜底。**特例**：配置里存着 `localhost` 而这次是从公网/手机访问的，按访问地址重算（手机上没有那个 3000 端口）。规则由 `npm run test:redirect`（11 项）守住。用户侧只剩一件事：把 `https://<域名>/feishu/callback` 加进飞书开放平台的重定向白名单 |
| 🟠 P1 ✅ 已实施 | `viewport-fit=cover` + 安全区 | 两个页面的 viewport 补了 `viewport-fit=cover`；`.topbar` 用 `env(safe-area-inset-top)` 让开刘海，`main` 底部与 `.toast` 用 `env(safe-area-inset-bottom)` 让开手势条，左右也留了横屏刘海的量。桌面端 `env()` 恒为 0，观感不变 |
| 🟠 P1 ✅ 已实施 | 声明 `color-scheme` | `style.css` 的 `:root` 加了 `color-scheme: dark`。这是**告诉 WebView 页面自己就是深色、别来反色**的标准做法，与 §5.1.3 的 `FORCE_DARK_OFF` 双保险。主题文件只覆盖颜色变量，不需要各自再写一遍 |
| 🟠 P1 ✅ 已实施 | SSE 前后台重连 | `public/app.js` 的 `connectStream()` 重写：`CLOSED` 走指数退避重建（1s→2s→…上限 30s），`visibilitychange` / `pageshow` / `online` 回前台时若不是 `OPEN` 就立刻重建。移动端切后台是常态，没这个日志流会"假死" |
| 🟡 P2 部分 | `theme-color` / manifest | `theme-color` 已加（`#1d1519`）；`manifest.json` 未做 —— 纯壳方案用不上，等真要做 PWA 再说 |
| 🟡 P2 | UA 识别（可选） | 服务端可通过 UA 里的 `WorkCheckApp/1.0` 识别 App 来源，用于日志区分（仅统计，不影响逻辑） |

---

### 5.4 构建、签名与 CI

本机现状：**无 JDK / 无 Android SDK / 无 Gradle** → 走 GitHub Actions 云构建，本地只写源码。

```yaml
# .github/workflows/android.yml（要点）
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: '21' }        # 必须 21：Capacitor 7 用 Java 21 编译，17 会报"无效的源发行版: 21"
- uses: android-actions/setup-android@v3
- run: npm ci && npx cap sync android
  working-directory: mobile
- run: ./gradlew :app:assembleRelease                        # 或 bundleRelease 出 AAB
  working-directory: mobile/android
- uses: actions/upload-artifact@v4                           # 产物 APK
```

签名（本地做一次，之后全走 Secrets）：

```bash
keytool -genkeypair -v -keystore workcheck.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias workcheck
# → base64 编码后存 GH Secrets: KEYSTORE_B64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
```

- `.gitignore` 必须排除 `*.jks` / `keystore.properties` —— 签了名的包一旦密钥泄露，升级包就被冒名顶替。
- 版本号：`versionName` 语义化，`versionCode` 单调递增（华为市场对 versionCode 有校验）。
- 鸿蒙版：DevEco Studio 本地构建 HAP，或 `hvigorw` 命令行，同样可挂 Actions（需 Linux runner + 命令行工具）。

---

### 5.5 安全边界

- **域名白名单**硬编码在壳里（§5.1.4），杜绝页面被劫持跳到任意站点后在壳内打开。
- 明文流量全禁（`cleartext: false` + `networkSecurityConfig`），依赖站点已开的 HSTS。
- 生产包关闭 `webContentsDebuggingEnabled`（防他人用 DevTools 窥看 Cookie/报文）。
- 不申请任何敏感权限；不开启 `setAllowFileAccessFromFileURLs` / `setAllowUniversalAccessFromFileURLs`。
- 管理员会话靠 Cookie，7 天有效；App 卸载即失效。若设备丢失，在 `/setup.html` 改一次密码即可踢掉所有会话（现有能力，无需改造）。
- 可选加固：检测到 Root / 模拟器时提示风险（不做强拦，避免误伤）。

---

## 6. 实施里程碑

| # | 任务 | 复杂度 | 状态 |
|---|---|---|---|
| 1 | 服务端 P0：飞书回调按访问域名推导 + 单测 | 低 | ✅ 已完成（11 项用例通过） |
| 2 | 前端 P1：`viewport-fit` / `color-scheme` / SSE 重连 | 中 | ✅ 已完成 |
| 3 | `mobile/android` Capacitor 工程 + WebView 配置 + 导航白名单 | 中 | ✅ 已完成（待首次真机构建验证） |
| 4 | 返回键 / 错误重试页 / 启动屏 / 状态栏 | 中 | ✅ 已完成（下拉刷新暂缺，见下） |
| 5 | GitHub Actions 云构建 + 签名 | 中 | ✅ 已完成（Secrets 待填） |
| 6 | 首次出包 + 真机冒烟（含华为深色模式、后台、纯净模式） | 中 | ⬜ 待做 —— 需先构建一次 |
| 7 | 品牌图标与启动图替换（现为 Capacitor 默认图） | 低 | ⬜ 待做 |
| 8 | （按需）`mobile/harmony` ArkTS 壳，覆盖 NEXT | 中 | ⬜ 待定，取决于目标机型 |
| 9 | （二期）WorkManager 轮询通知 / HMS Push Kit | 高 | ⬜ 可选 |

**下拉刷新**这次没做：Capacitor 的启动布局是 `BridgeLayout`（CoordinatorLayout），
再套一层 `SwipeRefreshLayout` 要改模板布局并与 WebView 的滚动手势打架，性价比不高。
看板本身有面板级「刷新」按钮、SSE 自动推流，缺的是整页下拉 —— 需要的话二期再加。

---

## 7. 真机验收清单

**通用（每台都要过）**
- [ ] 冷启动：启动屏 → 看板，无白屏、无默认主题闪现
- [ ] 看板四面板数据正常，折叠/排序偏好重启后保留（localStorage 生效）
- [ ] 切到「日志与报文」：SSE 实时日志持续滚动；**锁屏 1 分钟后回来仍能继续**
- [ ] 日历点任意一天出明细；翻月正常
- [ ] 下拉刷新可用；返回键逐层后退，主页再按一次退出
- [ ] 断网：出现内置错误页 + 重试可恢复（不是 WebView 原生报错页）
- [ ] 进 `/setup.html`：登录成功，刷新页面仍登录（Cookie 持久化），重启 App 仍登录
- [ ] **飞书授权全流程在 App 内完成，不跳出外部浏览器，回调后能成功拉取**
- [ ] 系统深色模式开/关各测一遍：配色**不被反色**

**华为专项**
- [ ] HarmonyOS 3/4 机型：以上全通过（重点看深色模式与后台存活）
- [ ] 纯净模式开启时，能给出明确的可操作提示
- [ ] 后台 30 分钟后回前台：日志流自动恢复，无需杀进程重启
- [ ] HarmonyOS NEXT 机型：确认走鸿蒙版（APK 装不上属预期，需提前告知用户）

---

## 8. 风险与已知限制

1. **HarmonyOS NEXT 不支持 APK** —— 不是技术问题，是平台限制。若目标用户里有纯血鸿蒙机型，鸿蒙壳（里程碑 7）不能省。
2. **强依赖网络**：纯壳方案离线不可用。看板本身是实时数据，离线意义不大，但首屏弱网体验要靠启动屏 + 错误页兜住。
3. **WebView 版本差异**：老机型（Android 8 / WebView 60-）对 `backdrop-filter`、现代色彩函数支持不全，可能出现玻璃效果退化。处理方式：前端对关键样式保留 fallback 背景色，视觉降级但不影响功能。
4. **后台保活不可靠**：安卓与华为都会杀后台，SSE 断连是常态而非异常。方案是"回前台重连"，不是"后台保活"，不要在这点上过度设计。
5. **国内推送无统一通道**：不接厂商推送就没有可靠实时通知。一期用轮询（WorkManager），二期再接 HMS Push / 极光 / 个推。
6. **上架合规**：国内市场上架需要隐私政策与备案，这块时间不可控，建议先用直接分发 APK（签名 + 下载页）跑起来。

---

## 9. 云服务器部署下的落地步骤

现状：Docker Compose，`build.context` 直接指向 GitHub 仓库的 tag，数据落在卷 `workcheck-data:/data`。

### 9.1 让代码改动生效 —— 必须打新 tag

compose 里钉的是 `https://github.com/<组织>/workcheck.git#v1.0.0`，
所以改完代码直接 `docker compose up -d --build` **拿不到新代码**（还是旧 tag）。流程：

```bash
git tag v1.1.0 && git push origin v1.1.0
# 服务器上把 compose 里两处版本号一起改（context 的 #v1.1.0 与 image 的 :v1.1.0）
docker compose up -d --build
```

镜像 tag 与代码 tag 一一对应，回滚就是改回上一个版本号重新 build。
想直接用服务器上的当前目录构建，把 `context` 换成 `.`。

### 9.2 服务端不需要为 App 新增任何配置

`WORKCHECK_DATA_DIR=/data` 在卷里，容器重建不丢数据；三组凭证仍在 `config.json`，页面改完即生效。
唯一与 App 相关的是回调地址 —— 现在按访问域名自动推导，不用手工改。
想确认实际生效值：看 `/setup.html → 配置信息`，或 `GET /api/feishu/status` 返回的 `redirectUri`。

### 9.3 唯一必须手工做的一步：飞书白名单

飞书开发者后台 →「安全设置 → 重定向 URL」加入：

```
https://workcheck.example.com/feishu/callback
```

不加会报 20029（授权页报错，用户完全看不出是地址问题）。页面上有「回调地址比对」工具可逐字核对。

### 9.4 反代的两条建议（对 App 比浏览器更关键）

```nginx
# 让服务端能拼出正确的 https 回调地址，省掉兜底逻辑的不确定性
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host  $host;

# SSE 实时日志：关掉缓冲，否则日志会攒着一批一批发，表现成"不刷新"
proxy_buffering off;
proxy_read_timeout 3600s;
```

> 手机网络切换频繁，SSE 断连是常态（前端已做回前台重连），但被 Nginx 缓冲是另一种"假死"，
> 重连也救不回来，所以这条在 App 场景下必须确认。

### 9.5 出包与分发

```bash
# 一次性动作：生成签名密钥（丢了就再也出不了升级包，务必备份）
keytool -genkeypair -v -keystore workcheck.jks -keyalg RSA -keysize 2048 -validity 10000 -alias workcheck
base64 -w0 workcheck.jks     # 结果填进 GitHub Secrets 的 KEYSTORE_B64
# 另三个 Secrets：KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD

git tag v1.1.0 && git push origin v1.1.0     # Actions 自动构建，产物里下载 APK
```

华为手机直接装 APK 前要关「设置 → 系统和更新 → 纯净模式」，或走应用市场分发。

### 9.6 服务器侧的安全边界（App 发出去之后）

App 一旦装到别人手机上，管理接口就等于跟着暴露到公网了：

- `/setup.html` 与所有管理接口仍要登录，但**管理员密码是明文过网的** —— HTTPS 不能断（站点已开 HSTS）
- 反代要把真实 IP 传给 Node，否则登录限流会把所有用户当成同一个 IP
- `ADMIN_TOKEN` 这类一次性后门用完记得撤
- App 内已关闭明文流量与 WebView 调试，不额外申请任何权限
