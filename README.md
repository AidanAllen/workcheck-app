# WorkCheck 安卓壳工程（App 名：看板娘）

把一个线上看板站点封装成安卓 App。
**网页端代码在另一个仓库（https://github.com/AidanAllen/workcheck）** —— ，两边各自独立演进，不需要互相同步。

> **文档里的 `workcheck.example.com` 是占位域名**，不是真实站点。
> `example.com` 是 RFC 2606 保留的文档专用域，不会解析到任何真实主机。
> App 本身也不写死任何站点地址 —— 实际地址由使用者首次启动时填写（见下节）。

App 本身不含任何业务页面：只有一个原生 WebView，内容全部实时从线上站点加载。
所以改网页不需要重新发 App 版本，服务器重启后所有已安装的 App 立刻看到新版本。

## 目录结构

```
workcheck-app/
├── .github/workflows/
│   └── android-release.yml  出 APK 的云端构建（打 v* tag 自动跑，也可手动触发）
├── mobile/
│   ├── android/              Capacitor 7 生成的安卓工程（包名 com.byeallen.workcheck）
│   ├── capacitor.config.json 导航白名单、WebView 参数、androidScheme
│   ├── package.json          Capacitor CLI 依赖
│   ├── www/index.html        App 内首帧配置页（填站点地址用，不是业务页面）
│   └── resources/            App 图标原始素材
├── scripts/gen-icons.py      从一张原图生成全套安卓图标
├── docs/
│   └── android-app-design.md 方案与真机验收清单（方案阶段记录，与代码不一致时以代码为准）
└── README.md
```

## 覆盖范围

`minSdkVersion = 23`（Android 6.0）起可安装，重点验证 Android 8~15 与华为 HarmonyOS 2 / 3 / 4。
`compileSdkVersion` / `targetSdkVersion` 均为 35，在 `mobile/android/variables.gradle` 里。

| 产物 | 覆盖机型 |
|---|---|
| APK | Android 6~15、华为 HarmonyOS 2 / 3 / 4 |
| 待做 HAP | 华为 HarmonyOS NEXT 5 —— **不支持 APK**，需另出 ArkTS + ArkWeb 鸿蒙包 |

## 站点地址由使用者填写

App **不写死任何站点地址**。首次启动会落在本地配置页（`www/index.html`），
让使用者填自己的看板地址，存进本机 SharedPreferences，之后每次启动直接打开。

- 没写 `https://` 会自动补齐
- 只接受 HTTPS —— 明文 http 被 `usesCleartextTraffic=false` 禁掉，填了也加载不了
- **配过之后启动直达站点**：`onCreate()` 里直接 `loadUrl`，顶掉 Capacitor 刚发起的本地页加载，
  不再经过中转页（旧版要干等 1.5 秒才跳）
- 地址填错会落到内置错误页，上面有「换个地址」回到配置页 —— 走 `https://localhost/#setup`，
  带 `#setup` 才停在填写表单上，否则会被自动跳走
- 导航白名单只放行「用户填的那个站点 + 飞书两个域（`feishu.cn` / `larksuite.com`）」，其余一律交系统浏览器

这样同一个 APK 可以分发给不同的人，各自连到自己的站点。
想彻底重来一次的话，清除 App 数据即可回到配置页。

## 本地构建

```bash
cd mobile
npm ci
npx cap sync android      # 把 capacitor.config.json 同步进原生工程
npx cap open android      # 或直接用 Android Studio 打开 mobile/android
```

命令行出包：

```bash
cd mobile/android
./gradlew assembleRelease        # 签名 release 包
./gradlew assembleDebug          # 调试包
```

**JDK 必须是 21。** Capacitor 7 的 `capacitor-android` 用 Java 21 编译，
用 JDK 17 会在编译期报「无效的源发行版: 21」。

## 云端构建（推荐）

push 一个 `v*` 类型的 tag 到**本仓库**，`.github/workflows/android-release.yml`
会自动构建，并把 APK 直接附到对应的 Release 上。也可以在 Actions 页面手动触发一次
（手动触发只出产物、不上传 Release）。

JDK 21、Android SDK、Node 22 全在 runner 上现装，本机不需要任何安卓工具链。

### 签名

配了 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
四个 Secrets 就用正式密钥签名。

**没配也会签名** —— 此时由 CI 现场生成一把临时密钥（`keytool`，有效期 30 年）并完成签名。
这是必须的：未签名的 release 包是 `app-release-unsigned.apk`，**装不上**
（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。

> ⚠️ 临时密钥每次构建都不同，用它签出的包之间**无法覆盖升级**（要先卸载）。
> 正式分发前务必配好上面四个 Secrets，保持签名证书稳定。

## 发布签名

签名落在 `mobile/android/workcheck.jks`，口令见同目录 `keystore.properties`，
两者都已被 `.gitignore` 排除。

> ⚠️ **这个文件一旦丢了就再也出不了升级包**，务必单独备份一份。

> ⚠️ **debug 包和 release 包不能混装。** Android 用「包名 + 签名」认定应用身份，
> 两者是两套证书，同包名不同签名**无法覆盖安装**。装过 debug 版的话必须先卸载。
> 同一个 release 证书签出来的版本之间（`versionCode` 递增）才能直接覆盖升级。

## 发版约定

每次出包都要递增 `mobile/android/app/build.gradle` 里的 `versionCode`，
签名证书保持不变。这样用户直接覆盖安装即可，不需要卸载。

## 换 App 图标

```bash
python scripts/gen-icons.py 原图.png --assets mobile/resources
```

会写出 5 档密度的传统方形 / 圆形图标，以及自适应图标的前景与背景两层，
并把压缩版原图存进 `mobile/resources/`。依赖 Pillow（`pip install Pillow`）。

前景内容固定缩到画布的 80% —— 系统遮罩只保证中间 66.7% 可见，
留这点余量是防止人物主体被圆形裁掉。

> 启动图（`res/drawable*/splash.png`）不在这个脚本范围内，目前还是壳工程自带的默认图。

## 已知适配要点

- **状态栏（沉浸式）**：系统栏只吃颜色、不吃图片。要让页面背景（含背景图）顶到屏幕最上沿，
  只能是「状态栏透明 + WebView 铺满整屏」——`MainActivity.applyTransparentSystemBars()`
  把状态栏 / 导航条一起设成透明，配置里 `android.adjustMarginsForEdgeToEdge = disable`
  让 Capacitor 不再给 WebView 加 margin（加了 margin 状态栏那一条就只剩窗口背景色，即黑条）。
  API 29+ 还要关掉 `setStatusBarContrastEnforced`，否则系统为保证图标可见会自己糊一层半透明遮罩。
- **顶栏「↺ 默认排序」**：手机上看板不做拖拽排序，`injectPageTweaks()` 注入
  `#resetOrder{display:none !important}` 藏掉。它由页面 JS 按排序状态切 `hidden` 属性，
  不用 `!important` 压不住。
- **滚动遮挡**：站点的 `.topbar` 是 `position: sticky; top: 0`，吸的是 WebView 视口顶
  （= 屏幕物理顶），沉浸后滚动时标题会和状态栏重叠。`injectPageTweaks()` 给它垫高：
  `padding-top: calc(var(--app-safe-top, 0px) + 14px)`，毛玻璃背景顺便成了状态栏的背景，
  滚上去的内容从它后面过会被 `backdrop-filter` 模糊掉。
- **顶部避让**：沉浸之后内容会跑到状态栏底下，避让靠 `injectPageTweaks()`：
  页面加载完注入 `--app-safe-top` / `--app-safe-bottom`（原生量出来的真实高度，单位 CSS px）；
  再探测一次 `env(safe-area-inset-top)`，WebView 老内核恒为 0，此时补一层透明 `border` 把内容顶下去
  （用 border 而非 padding，不会顶掉页面原有的 padding，背景图也照样画满状态栏那一条）。
  远程页面有固定顶栏的话，直接在页面里用 `var(--app-safe-top)` 比 `env()` 可靠。
  ⚠️ **避让只能做一次**：页面 `env()` 与原生注入同时生效，顶部会出现两条空白，
  看着比黑条更糟（v1.0.2 就栽在这）。所以注入前先探测，`env()` 有值就收手。
  **不要碰 `WindowCompat.setDecorFitsSystemWindows`**：它在 Android 15 上已失效，
  却会把 WindowInsets 提前消耗掉，导致 WebView 读到的 top inset 变成 0。
- **深色模式**：华为会对 WebView 做算法强制反色，原生端 `FORCE_DARK_OFF`
  加上页面自身声明 `color-scheme: dark`，两端都堵住才稳。
- **滚动边缘拉伸**：`setOverScrollMode(OVER_SCROLL_NEVER)` 关掉了 Android 12+ 的拉伸反馈动画。
- **飞书授权**：回调地址由服务端按访问域名推导，需要把
  `https://<域名>/feishu/callback` 加进飞书开放平台的重定向白名单，否则报 20029。
- **返回键**：先让网页后退，退不动了再双击退出。

## 本次清理掉的多余代码

| 删除项 | 判定依据 |
|---|---|
| `src/test/…/ExampleUnitTest.java`、`src/androidTest/…/ExampleInstrumentedTest.java` | Capacitor 模板示例。前者断言 `4 == 2+2`；后者断言包名 `com.getcapacitor.app`，与本工程包名 `com.byeallen.workcheck` 不符，**一跑必失败** |
| `app/build.gradle` 的 `junit` / `androidx.test.ext:junit` / `espresso-core` 三行与 `testInstrumentationRunner` | 测试目录删掉后成为无源依赖 |
| `variables.gradle` 的 `junitVersion` / `androidxJunitVersion` / `androidxEspressoCoreVersion` | 同上，无引用 |
| `res/layout/activity_main.xml` | Capacitor 7 的 `BridgeActivity` 用的是库内 `capacitor_bridge_layout_main`，工程里这份无引用 |
| `res/xml/file_paths.xml` + Manifest 里的 `FileProvider` | 不做文件分享 / 相机，无引用；且 `external-path path="."` 会把整个外存根目录纳入可授权范围 |
| `MainActivity` 的 `SiteBridge.appVersion()` | `www/index.html` 未调用；版本号已通过 UA 里的 `KanbanNiangApp/<version>` 带出去 |
| `strings.xml` 的 `package_name` / `custom_url_scheme` | 全仓库无引用（包名用 `BuildConfig.APPLICATION_ID`） |
| `app/build.gradle` 末尾的 google-services try-catch 块 + 根 `build.gradle` 的 `google-services` classpath | 没有 FCM / 推送需求，仓库里也没有 `google-services.json`，该分支从不生效 |
| `scripts/gen-icons.py` 里 `save()` 的 `colors=None` 分支 | 三处调用都传了值，分支不可达 |

**刻意保留**（看着像多余，实际不能删）：

- `app/build.gradle` 的 `flatDir` 仓库与 `capacitor-cordova-android-plugins` 依赖、
  `settings.gradle` 里的对应 `include` —— Capacitor 插件机制的入口，加了插件才会生成该目录。
- `res/drawable*/splash.png` —— 启动屏占位图，等品牌素材替换（图标脚本不覆盖启动图）。
- `variables.gradle` 的 `androidxActivityVersion` / `androidxFragmentVersion` ——
  Capacitor 模板的标准变量，原生插件的 Gradle 片段按名取用，删了会在加插件时踩坑。

## 数据脱敏约定

仓库文档与打包产物里**不出现真实站点域名**。统一用占位域 `workcheck.example.com`，
仓库地址用 `<组织>/workcheck`。合计 13 处（站点域名 11 处 + 仓库地址 2 处）：

| 位置 | 站点域名 | 仓库地址 |
|---|---|---|
| `docs/android-app-design.md` | 8 | 1 |
| `README.md` | 1（+1 见下方说明） | 1 |
| `MainActivity.java` 地址校验错误提示 | 1 | — |
| `www/index.html` 输入框占位符 | 1 | — |

> README 里现有 2 处 `workcheck.example.com` 字面量：开头说明 1 处（替换所得），
> 本节约定说明 1 处（为讲清占位规则新增）。

后两行**会打进 APK**：用户填错地址时的报错文案、首次启动配置页的输入框示例，
都是使用者直接看到的内容，属于本次脱敏里最该处理的部分。

**未做替换**：包名 `com.byeallen.workcheck`。它是代码标识符，同时存在于
`build.gradle` / `capacitor.config.json` / `AndroidManifest.xml` / 源码目录结构，
只改文档会造成文档与代码不一致；且它已随 APK 包名公开发布，改动会中断覆盖升级路径。

> 注意：Git 历史里仍有脱敏前的地址（本仓库的提交历史未改写）。
> 若需要彻底清除，必须重写历史并强制推送 —— 这会改变所有已有提交的哈希，
> 影响已克隆该仓库的所有副本，需另行决定是否执行。

## 待办

- 首次真机冒烟（验收清单见 `docs/android-app-design.md` 第 7 节）
- 替换启动图（现为 Capacitor 默认图）
- 鸿蒙 NEXT 包（`mobile/harmony` ArkTS 工程，取决于目标机型）
- 配置签名的四个 Secrets（`KEYSTORE_B64` 等）—— 目前出的是 CI 临时密钥签的包，
  正式分发前需要换成固定证书，否则版本之间无法覆盖升级
