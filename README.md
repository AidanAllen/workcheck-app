# 看板娘 · Android 壳工程

把一个自建的在线看板站点，封装成一个 Android App。

**这个仓库只有一个原生 WebView 壳，不含任何业务页面** —— 内容全部实时从使用者自己的站点加载。
所以改网页不需要发 App 新版本，服务器重启之后所有已安装的 App 立刻看到新版。

网页端代码在**另一个仓库**（`AidanAllen/workcheck`），两边各自独立演进，不需要互相同步。
App **不写死任何站点地址** —— 实际地址由使用者首次启动时自己填。

> 文档里的 `workcheck.example.com` 是占位域名：
> `example.com` 是 RFC 2606 保留的文档专用域，不会解析到任何真实主机。

---

## 目录

- [获取安装包](#获取安装包)
- [装到手机上](#装到手机上)
- [出包](#出包)
- [签名](#签名)
- [版本与升级](#版本与升级)
- [仓库结构](#仓库结构)
- [App 的行为约定](#app-的行为约定)
- [适配要点（改代码前必读）](#适配要点改代码前必读)
- [站点侧待办：App 补不了的两件事](#站点侧待办app-补不了的两件事)
- [换 App 图标](#换-app-图标)
- [裁剪掉的模板残留](#裁剪掉的模板残留)
- [脱敏约定](#脱敏约定)
- [排查手册](#排查手册)
- [待办](#待办)

---

## 获取安装包

最新正式版的 APK 在 GitHub Releases 上，取标记为 **Latest** 的那条：

<https://github.com/AidanAllen/workcheck-app/releases/latest>

版本号不在这个文档里维护 —— **写了就一定会过期**，用三处去查：

| 想知道 | 看哪 |
|---|---|
| 最新正式版是哪个 | Releases 页标记为 **Latest** 的那条 |
| 工程里的版本 | `mobile/android/app/build.gradle` 的 `versionName` / `versionCode` |
| 手机上装的是哪个 | 系统设置里的应用版本；服务端日志的 UA 也会带 `KanbanNiangApp/<version>` |

正式出包一律用同一把密钥、`versionCode` 递增 —— 这两条齐备，
用户直接覆盖安装就能升级，不需要卸载。

---

## 装到手机上

1. 从上面的 Releases 页面下载 APK，传到手机安装（会提示「未知来源」，放行即可）
2. 首次打开会停在**配置页**，填自己的看板站点地址
3. 保存后直达看板。之后每次打开直接进站点，不再经过配置页

**想换个地址**：站点页面左下角会浮出一个圆形齿轮，进页面 1 秒后自动淡出，
点它就回到配置页。每次加载站点页都会重新露一次，不存在藏深了找不着的情况。
想彻底重来一次，清除 App 数据也能回到配置页。

**地址规则**：

- 没写 `https://` 会自动补齐
- **只接受 HTTPS** —— 明文 http 被 `usesCleartextTraffic=false` 禁掉，填了也加载不了
- 同一个 APK 可以分发给不同的人，各自连到自己的站点

> 左下角齿轮的颜色**跟着站点主题走**：`StyleBridge` 让页面把 `--bg / --surface / --border / --accent / --text`
> 交回原生，据此给底色、描边、图标着色。站点换皮肤（初樱 / 雨夜…）它跟着换，不用改代码。
> 取色延迟 500ms —— 站点是运行时按 localStorage 挂主题 CSS 的，页面刚 finish 时读到的还是默认值。

---

## 出包

### 云端（推荐）

推一个 `v*` 类型的 tag 到本仓库，`.github/workflows/android-release.yml` 会自动构建，
并把 APK 直接附到对应的 Release 上：

```bash
git tag -a vX.Y.Z -m "一句话说明"
git push origin vX.Y.Z
```

也可以在 Actions 页面手动触发一次 —— **手动触发只出构建产物、不上传 Release**，
适合改动后先自己拉一个验证包真机试一遍。

JDK 21、Android SDK、Node 22 全在 runner 上现装，**本机不需要任何安卓工具链**，约 2 分钟出包。

### 本地

```bash
cd mobile
npm ci
npx cap sync android      # 把 capacitor.config.json 与 www/ 同步进原生工程
npx cap open android      # 或直接用 Android Studio 打开 mobile/android
```

命令行出包：

```bash
cd mobile/android
./gradlew assembleRelease        # 签名 release 包
./gradlew assembleDebug          # 调试包
```

> **JDK 必须是 21。** Capacitor 7 的 `capacitor-android` 用 Java 21 编译，
> 用 JDK 17 会在编译期报「无效的源发行版: 21」。

本地出的 APK 落在 `mobile/android/app/build/outputs/apk/`，已由 `.gitignore` 排除，不进版本库。

### 出完包怎么验

本机没有 javac 也能做几项关键校验（包体解压即验）：

```bash
python - <<'EOF'
import zipfile, re
z = zipfile.ZipFile('app-release.apk')
txt = z.read('AndroidManifest.xml').decode('utf-16-le', 'ignore')
print('版本:', sorted(set(re.findall(r'\d+\.\d+\.\d+', txt))))
print('capacitor.config.json 字节:', len(z.read('assets/capacitor.config.json')))   # 见下方说明
dex = z.read('classes.dex')
for k in [b'overscroll-behavior', b'sessionCookie']:
    print(k.decode(), '->', '命中' if k in dex else '未命中')
EOF
unzip -p app-release.apk META-INF/CERT.RSA > /tmp/c.rsa
openssl pkcs7 -inform DER -in /tmp/c.rsa -print_certs -out /tmp/c.pem
openssl x509 -in /tmp/c.pem -noout -fingerprint -sha256
```

其中 **`assets/capacitor.config.json` 的字节数**最值得看：配置里有语法错误时它会静默退化成
`{}`（3 字节），白构建一趟。证书指纹记下来，之后判断「这个包是不是正式密钥签的」
比对同一串即可 —— 它应当始终与本地 `mobile/workcheck.jks` 的一致。

---

## 签名

构建脚本读 `mobile/android/keystore.properties`（同目录还有 `workcheck.jks`），
两者都已被 `.gitignore` 排除。

> ⚠️ **这个文件一旦丢了就再也出不了升级包**，务必单独备份一份。

CI 侧由 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
四个 Secrets 现场拼出这个文件，缺一个就会退回临时密钥（下面一条）。

**没配也会签名**：此时由 CI 现场生成一把临时密钥（`keytool`，有效期 30 年）并完成签名。
这是必须的 —— 未签名的 release 包是 `app-release-unsigned.apk`，**根本装不上**
（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。

> ⚠️ 临时密钥每次构建都不同，用它签出的包之间**无法覆盖升级**（要先卸载）。

> ⚠️ **debug 包和 release 包不能混装。** Android 用「包名 + 签名」认定应用身份，
> 两者是两套证书，同包名不同签名无法覆盖安装。装过 debug 版的话必须先卸载。

---

## 版本与升级

每次出包都要递增 `mobile/android/app/build.gradle` 里的 `versionCode`，签名证书保持不变。
这样用户直接覆盖安装即可，不需要卸载。

> CI 产物与本地同名 APK **大小常常一字节不差、SHA256 却不同** —— 这是构建非确定性
> （签名块时间戳、zip 条目顺序），属正常现象，不要误判为产物错误。
> 判断产物对不对，用上面那段脚本里的版本串 + 配置字节 + dex 关键串，而不是哈希。

---

## 仓库结构

```
workcheck-app/
├── .github/workflows/
│   └── android-release.yml   出 APK 的云端构建（推 v* tag 自动跑，也可手动触发）
├── mobile/
│   ├── android/              Capacitor 7 生成的安卓工程（包名 com.byeallen.workcheck）
│   ├── capacitor.config.json 导航白名单、WebView 参数、androidScheme
│   ├── package.json          Capacitor CLI 依赖
│   ├── www/index.html        App 内首帧配置页（填站点地址用，不是业务页面）
│   └── resources/            App 图标原始素材
├── scripts/gen-icons.py      从一张原图生成全套安卓图标
├── docs/
│   ├── android-app-design.md 方案与真机验收清单（第 7 节）
│   └── web-updates.patch     站点侧补丁存档（见「站点侧待办」一节）
└── README.md
```

**覆盖范围**：`minSdkVersion = 23`（Android 6.0）起可安装，重点验证 Android 8~15 与
华为 HarmonyOS 2 / 3 / 4。`compileSdkVersion` / `targetSdkVersion` 均为 35，
在 `mobile/android/variables.gradle` 里。

鸿蒙 NEXT 5 **不支持 APK**，需要另出一套 ArkTS + ArkWeb 的鸿蒙包。归在[待办](#待办)里，
开始前先确认目标机型里有没有 NEXT。

---

## App 的行为约定

所有原生逻辑集中在 `mobile/android/app/src/main/java/com/byeallen/workcheck/MainActivity.java`，
三个注入点与两座 JS 桥：

| 入口 | 时机 | 做什么 |
|---|---|---|
| `applyTransparentSystemBars()` | `onCreate` | 状态栏 / 导航条整条透明 |
| `injectPageTweaks()` | `onPageFinished` | 安全区变量、顶栏垫高、隐藏「默认排序」、背景图上提 |
| `injectPerfTweaks()` | `onPageFinished` | `overscroll-behavior` / `color-scheme` 两条静态 CSS |
| `WorkCheckNative` 桥 | 配置页调用 | `getSavedSite()` / `saveSite()` / `openSite()` |
| `WorkCheckStyle` 桥 | `injectPageTweaks` 后 500ms | 页面把主题配色交回来，给齿轮上色 |

WebView 本身的设置（`tuneWebView()`）：开 DomStorage（主题、折叠偏好存 localStorage）、
禁缩放、禁混合内容、UA 追加 `KanbanNiangApp/<version>`、关 ROM 强制反色、
只接受自家域 Cookie、渲染进程优先级设为 `IMPORTANT`。

**导航边界**：只有在本地配置页、使用者填的那个站点、以及飞书两个域
（`feishu.cn` / `larksuite.com`）才留在壳内，其余一律交系统浏览器。
明文 http 也一律甩出去 —— 既防中间人，也给写死 localhost 的旧回调一个明确出口。

**返回键**：先让网页后退，退不动了再双击退出，避免看一半被一按送走。

---

## 适配要点（改代码前必读）

这一节是踩过的坑，每一条都有对应的真机现象。**改动前先看这里**，否则很容易改回去。

> 文里的 px、毫秒之类均是实现细节，**以 `MainActivity` 为准** —— 文档只解释为什么这么定。

### 状态栏只能是「透明 + 网页顶上去」

系统栏**只吃颜色、不吃图片**。要让页面背景（含背景图）顶到屏幕最上沿，
唯一做法是 `applyTransparentSystemBars()` 把状态栏 / 导航条设成透明，
再让 WebView 铺满整屏画到它们底下。

配套两条，缺一条就退回黑边：

- 配置里 `android.adjustMarginsForEdgeToEdge = disable` —— 默认值 `force` 会让 Capacitor
  给 WebView 加一个等于状态栏高度的 top margin，那一条就只剩窗口背景色
- API 29+ 关掉 `setStatusBarContrastEnforced` —— 否则系统为保证图标可见会自己糊一层半透明遮罩，
  那层灰就是「明明设了透明、顶上还是有一条」的元凶

> ⚠️ **不要碰 `WindowCompat.setDecorFitsSystemWindows`**：它在 Android 15 上已失效，
> 却会把 WindowInsets 提前消耗掉，导致 WebView 读到的 top inset 变成 0。

### 顶部避让只能做一次

沉浸之后内容会跑到状态栏底下，避让靠 `injectPageTweaks()`：

1. 注入 `--app-safe-top` / `--app-safe-bottom`（原生量出来的真实高度，单位 CSS px）
2. 再探测一次 `env(safe-area-inset-top)`：WebView 老内核恒返回 0，
   此时才补一层透明 `border` 把内容顶下去（用 border 而非 padding，
   不会顶掉页面原有的 padding，背景图也照样画满状态栏那一条）
3. 两者都非零时**取较小的那个** —— 理论上都等于状态栏高度，谁偏大通常是把刘海区也算进去了

> ⚠️ **页面 `env()` 与原生注入同时生效，顶部会出现两条空白，看着比黑条更糟**
> （v1.0.2 就栽在这）。所以注入前先探测，`env()` 有值就收手。

远程页面有固定顶栏的话，直接在页面里用 `var(--app-safe-top)` 比 `env()` 可靠。

### sticky 顶栏要自己垫高状态栏

站点的 `.topbar` 是 `position: sticky; top: 0`，它吸的是 **WebView 视口顶 = 屏幕物理顶**。
沉浸之后一滚动，顶栏吸到 y=0，标题正好和状态栏时钟叠在一起。

处理：`injectPageTweaks()` 给它加 `padding-top: calc(var(--app-safe-top, 0px) + 6px)`。
垫高后毛玻璃背景正好盖住状态栏区域，滚上去的内容从它后面过会被 `backdrop-filter` 模糊掉，
是标准的「沉浸 + 毛玻璃吸顶」效果。

站点原本的上下边距是 14px，这里只再加 6px —— 再宽标题就离状态栏太远了。

> 有 `.topbar` 的页面**不能再加 `border-top` 兜底**：顶栏吸顶后自己已经垫高，加了会双重。
> 所以注入前用 `document.querySelector('.topbar')` 判断，命中就只保留 `border-bottom`。

### 登录态：丢在 WebView 的落盘时机上

站点发的是 `wc_session` Cookie（`Max-Age` 7 天、`HttpOnly`、`SameSite=Lax`），
**服务端是持久的**，问题出在 WebView 把它写进自己的 SQLite 是**异步**的 ——
从最近任务划掉 App 时常常还没落盘，下次进来就成了未登录。两道保险：

- `onPause()` 里先 `CookieManager.flush()`，再把这枚 Cookie 抄一份进 SharedPreferences
- `onCreate()` 里**先把快照喂回 WebView 再 `loadUrl`** —— 顺序反了首屏会先按未登录
  渲染一遍再纠回来，看着像登录自己跳了一下

`CookieManager.getCookie()` 是原生侧读取，能读到 `HttpOnly`，所以备份这条路走得通。
登出 / 站点清 Cookie 时快照会一并删掉，否则下次会把失效凭证塞回去，
卡在「显示已登录但接口全 401」。

### 深色模式要两端都堵

华为 / 小米 / OPPO 会对 WebView 做算法强制反色，深色玻璃面板会被反成一坨怪色。
原生侧 `FORCE_DARK_OFF`（API 29+）+ `setAlgorithmicDarkeningAllowed(false)`（API 33+），
页面侧再声明 `color-scheme: dark` —— 只堵一端不够稳。

### 滚动：别用「滚动期切 CSS class」的办法省帧

真正吃帧的是**站点那层毛玻璃** —— `.topbar`（sticky）和一屏好几个 `.panel` 上都挂着
`backdrop-filter: blur(14px)`，sticky + 模糊意味着每帧要重新取背景做一次模糊。

App 侧已经做的两件事：

- `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)`（API 26+）——
  WebView 渲染进程默认是「可牺牲」的，内存吃紧时系统先降它的优先级，
  表现就是滚着突然卡一拍、严重时画面先白一下
- 注入 `overscroll-behavior: none` + 原生 `setOverScrollMode(OVER_SCROLL_NEVER)` ——
  消掉 Android 12+ 滚到头的拉伸回弹，在密排面板上看着像「抖了一下」

> ⚠️ **不要再做「滚动时降级毛玻璃」**：v1.0.6 试过这套（滚动时挂 `body.wc-scrolling` 关掉 blur、
> 停手 160ms 恢复），确实省了 GPU，但每次起停都要重算图层 + 重绘，
> 连续滚动时肉眼就是一阵闪 —— 省下的帧抵不上闪一下的观感损失，已整套撤掉。

要解决根子上的开销，得改站点：把 blur 半径降下来，或给窄屏单做一套无模糊样式。

### 顶栏「↺ 默认排序」

手机上看板不做拖拽排序，`injectPageTweaks()` 注入 `#resetOrder{display:none !important}` 藏掉。
它由页面 JS 按排序状态切 `hidden` 属性，**不用 `!important` 压不住**。

### 飞书授权

回调地址由服务端按访问域名推导，需要把 `https://<域名>/feishu/callback`
加进飞书开放平台的重定向白名单，否则报 20029。导航白名单里放行
`feishu.cn` / `larksuite.com` 也是为了这条链路 ——
授权页一旦被甩到外部浏览器，回调就再也回不到 App 里。

---

## 站点侧待办：App 补不了的两件事

早前分支留下一份站点补丁，已存档到 `docs/web-updates.patch`。
往站点仓库应用前先比对一次上游 —— **这些改动随时可能已经被合进去了**。

| 补丁里的改动 | 影响 | App 侧能否补 |
|---|---|---|
| `app.js` SSE 重连（`visibilitychange`/`pageshow`/`online` 时重建 `EventSource`） | **App 切后台再回来，日志流标志还写着「已连接」其实已经冻死**，看不见新日志也不报错 | 补不了：实例在站点闭包内部，Shell 拿不到句柄。只能整页 reload，代价太大 |
| `config.js` / `feishu.js` 回调地址按当前访问域名推导 | **App 里点「飞书授权」会失败** —— 回调写死 `http://localhost:3000`，手机上没有那个端口，授权链路断死 | 补不了：地址由服务端拼，只有改站点 |
| `view` 加 `viewport-fit=cover` | 关系到 `env(safe-area-inset-*)` 在 WebView 里是否有值（现在靠原生注入兜底） | 注入时机太晚，改 meta 无效，不建议在这绕 |
| `style.css` 加 `color-scheme: dark` | 表单控件在深色页上冒白底 | **已在 App 侧注入补上** |
| `style.css` 加 `overscroll-behavior: none` | 滚到头的橡皮筋 | **已在 App 侧注入补上** |
| `scripts/test-redirect.js` | 回调推导规则的单测 | — |

前两项是 App 里**只靠 Shell 拿不到**的功能缺陷，建议按补丁直接改进站点仓库。
改完之后，注入 `color-scheme` 与 `overscroll-behavior` 这两条就可以一并撤掉，
让这些事回到站点自己的 CSS 里。

---

## 换 App 图标

```bash
python scripts/gen-icons.py 原图.png --assets mobile/resources
```

会写出 5 档密度的传统方形 / 圆形图标，以及自适应图标的前景与背景两层，
并把压缩版原图存进 `mobile/resources/`。依赖 Pillow（`pip install Pillow`）。

前景内容固定缩到画布的 80% —— 系统遮罩只保证中间 66.7% 可见，
留这点余量是防止人物主体被圆形裁掉。

> 启动图不在这个脚本范围内 —— 要换就直接覆盖 `res/drawable*/splash.png`。

---

## 裁剪掉的模板残留

以下内容看着像标准配置，实为 Capacitor 模板残留，已删除，**不要再加回来**：

- `ExampleUnitTest` / `ExampleInstrumentedTest` —— 后者断言包名 `com.getcapacitor.app`，
  与本工程包名不符，一跑必失败
- `FileProvider` + `res/xml/file_paths.xml` —— 不做文件分享 / 相机；
  且 `external-path path="."` 会把整个外存根目录纳入可授权范围
- `google-services` classpath 与 `app/build.gradle` 末尾的 try-catch 块 ——
  没有推送需求，仓库里也没有 `google-services.json`，该分支从不生效
- `res/layout/activity_main.xml` —— `BridgeActivity` 用的是库内布局，工程里这份无引用

**刻意保留**（看着像多余，实际不能删）：

- `flatDir` 仓库与 `capacitor-cordova-android-plugins` 依赖、以及 `settings.gradle` 里的
  对应 `include` —— 这是 Capacitor 插件机制的入口
- `variables.gradle` 的 `androidxActivityVersion` / `androidxFragmentVersion` ——
  原生插件的 Gradle 片段按名取用，删了会在加插件时踩坑

---

## 脱敏约定

**站点域名**一律用占位域 `workcheck.example.com`。出现在：

| 位置 | 是否打进 APK |
|---|---|
| 本 README | 否 |
| `docs/android-app-design.md` | 否 |
| `MainActivity.java` 地址校验的错误提示 | **是**（用户填错地址时看到的文案） |
| `www/index.html` 输入框占位符 | **是**（首次启动看到的内容） |

后两行属于这套约定里最该留心的部分 —— 它们是用户直接看到的内容。

**未做替换**：包名 `com.byeallen.workcheck`。它是代码标识符，同时存在于
`build.gradle` / `capacitor.config.json` / `AndroidManifest.xml` / 源码目录结构，
只改文档会造成文档与代码不一致；且它已随 APK 包名公开发布，改动会中断覆盖升级路径。

> Git 历史里仍有脱敏前的地址（本仓库的提交历史未改写）。
> 要彻底清除就得重写历史并强推 —— 那会改变所有已有提交的哈希，
> 影响已克隆该仓库的所有副本，需另行决定。

---

## 排查手册

| 现象 | 原因与处理 |
|---|---|
| 装不上，`INSTALL_PARSE_FAILED_NO_CERTIFICATES` | 包未签名 —— 只可能出现在本地 `assembleRelease` 且缺 `keystore.properties` 时 |
| 覆盖安装失败，要求先卸载 | 和已装版本签名不同（比如装过 debug 包），或 versionCode 倒退（装过更高的） |
| 状态栏是一条黑边 | `adjustMarginsForEdgeToEdge` 没保持 `disable`，或 API 29+ 没关 `setStatusBarContrastEnforced` |
| 顶部出现**两条**空白 | 页面 `env()` 与原生注入同时生效 —— 双重避让，见「顶部避让只能做一次」 |
| 滚动时标题和状态栏时钟叠在一起 | `.topbar` 没垫高 —— 检查 `injectPageTweaks` 的 `.topbar` 规则是否命中 |
| 连续滚动一阵一阵闪 | 有人在用「滚动期切 CSS class」降级毛玻璃 —— 别做，见对应一节 |
| 退出再进要重新登录 | WebView Cookie 异步落盘；检查 `onPause` 是否被调用（`onPause` 覆写必须是 `public`） |
| 「显示已登录」但接口全 401 | 凭证快照没跟着登出清掉 —— 快照要在 token 取不到时 `remove` |
| 首屏白闪一下 | `webView.setBackgroundColor` 的底色没设或被改回默认 |
| 地址填了打不开 | 站点不可达 / 服务没起 → 落到内置错误页，上面有「换个地址」回配置页 |
| 表单控件忽然冒一块白底 | `color-scheme` 没注入到，或只堵了原生侧没堵页面侧 |

`onPause` 那条有坑：Capacitor 的 `BridgeActivity.onPause()` 是 `public`，
子类覆写成 `protected` 编译不过（`attempting to assign weaker access privileges`）。

---

## 待办

- 替换启动图（现为 Capacitor 默认图）
- 鸿蒙 NEXT 包：需要另起一套 ArkTS + ArkWeb 工程，先确认目标机型里有没有 NEXT
- 站点侧两项缺陷待按 `docs/web-updates.patch` 改进（飞书回调、SSE 重连）
