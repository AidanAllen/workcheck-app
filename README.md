# WorkCheck 安卓壳工程（App 名：看板娘）

把线上站点 <https://workcheck.byeallen.com/> 封装成安卓 App。
**网页端代码不在本分支** —— 它在 `main` 分支，两边各自独立演进，不需要互相同步。

App 本身不含任何业务页面：只有一个原生 WebView，内容全部实时从线上站点加载。
所以改网页不需要重新发 App 版本，服务器重启后所有已安装的 App 立刻看到新版本。

## 目录结构

```
mobile/
├── android/              Capacitor 7 生成的安卓工程（包名 com.byeallen.workcheck）
├── capacitor.config.json 远程站点地址、导航白名单、WebView 参数
├── package.json          Capacitor CLI 依赖
├── www/index.html        App 内首帧占位页（防白屏，不是业务页面）
└── resources/            App 图标原始素材
scripts/gen-icons.py      从一张原图生成全套安卓图标
docs/android-app-design.md 完整设计与真机验收清单
.github/workflows/        出 APK 的云端构建
```

## 覆盖范围

| 产物 | 覆盖机型 |
|---|---|
| APK | Android 8~15、华为 HarmonyOS 2 / 3 / 4 |
| 待做 HAP | 华为 HarmonyOS NEXT 5 —— **不支持 APK**，需另出 ArkTS + ArkWeb 鸿蒙包 |

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

push 一个 `v*` 类型的 tag 到**本分支**，`.github/workflows/android-release.yml`
会自动构建，APK 挂在 Actions 产物里直接下载。

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

- **状态栏**：`MainActivity` 里用原生测量方式给 WebView 补顶部间距，
  不依赖 CSS `env(safe-area-inset-top)` —— 华为 / 鸿蒙的 WebView 上那个值经常量出来是 0。
- **深色模式**：华为会对 WebView 做算法强制反色，原生端 `FORCE_DARK_OFF`
  加上页面自身声明 `color-scheme: dark`，两端都堵住才稳。
- **滚动边缘拉伸**：`setOverScrollMode(OVER_SCROLL_NEVER)` 关掉了 Android 12+ 的拉伸反馈动画。
- **飞书授权**：回调地址由服务端按访问域名推导，需要把
  `https://<域名>/feishu/callback` 加进飞书开放平台的重定向白名单，否则报 20029。
