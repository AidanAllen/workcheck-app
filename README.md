# WorkCheck 安卓壳工程（App 名：看板娘）

把线上站点 <https://workcheck.byeallen.com/> 封装成安卓 App。
**网页端代码在另一个仓库** —— <https://github.com/AidanAllen/workcheck>，两边各自独立演进，不需要互相同步。

App 本身不含任何业务页面：只有一个原生 WebView，内容全部实时从线上站点加载。
所以改网页不需要重新发 App 版本，服务器重启后所有已安装的 App 立刻看到新版本。

## 目录结构

```
workcheck-app/
├── mobile/
│   ├── android/              Capacitor 7 生成的安卓工程（包名 com.byeallen.workcheck）
│   ├── capacitor.config.json 导航白名单、WebView 参数、androidScheme
│   ├── package.json          Capacitor CLI 依赖
│   ├── www/index.html        App 内首帧配置页（填站点地址用，不是业务页面）
│   └── resources/            App 图标原始素材
├── scripts/gen-icons.py      从一张原图生成全套安卓图标
├── docs/
│   ├── android-app-design.md 方案与真机验收清单（方案阶段记录，与代码不一致时以代码为准）
│   └── ci/android-release.yml 出 APK 的云端构建（需手工放进 .github/workflows/）
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
- 已配过之后，启动页停留约 1.5 秒，期间可以点「切换站点」重新填写
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

> **首次启用要在 GitHub 网页上做一次**：把 `docs/ci/android-release.yml` 原样放进
> `.github/workflows/android-release.yml` 即可（Add file → Create new file，
> 路径框里直接填全路径，内容粘贴进去）。
> 原因是本仓库最初是用一个缺 `workflow` 权限的令牌推的，
> GitHub 会拒绝「通过 git 创建 `.github/workflows/*`」，网页创建则不受此限。
> 该文件放在 `docs/ci/` 而不是 `.github/`，就是为了绕开这个限制。

push 一个 `v*` 类型的 tag 到**本仓库**，`.github/workflows/android-release.yml`
会自动构建，APK 挂在 Actions 产物里直接下载。也可以在 Actions 页面手动触发一次。

配了 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
四个 Secrets 就出签名包，没配则出未签名的包。

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

- **状态栏**：`MainActivity` 里只调状态栏配色与图标明暗，系统栏间距交给
  CapacitorWebView 自带的 edge-to-edge 处理（配置里的
  `android.adjustMarginsForEdgeToEdge = force`）。
  **不要碰 `WindowCompat.setDecorFitsSystemWindows`**：它在 Android 15 上已失效，
  却会把 WindowInsets 提前消耗掉，导致 Capacitor 读到的 top inset 变成 0。
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

## 待办

- 首次真机冒烟（验收清单见 `docs/android-app-design.md` 第 7 节）
- 替换启动图（现为 Capacitor 默认图）
- 鸿蒙 NEXT 包（`mobile/harmony` ArkTS 工程，取决于目标机型）
- 把 `docs/ci/android-release.yml` 放进 `.github/workflows/`
