# 她 · Android WebView APK

把现有的 PWA 聊天前端（`she-pwa-chat/`）装进一个全屏 WebView 壳，做成真正能装到手机的 APK。

**它不包含聊天逻辑**——界面还是 `chat.html`，大脑还是 AstrBot。APK 只是一个"没有地址栏的专用浏览器"，固定打开你的 PWA 地址。

```
手机 APK →(Tailscale 加密隧道)→ 电脑 server.js:8080 → AstrBot:6185
```

## 一、默认地址（改这里换域名）

打开 `app/src/main/java/com/myzy/she/MainActivity.kt`，顶部有一行：

```kotlin
const val START_URL = "http://desktop-j4flem2.tailae05bb.ts.net:8080"
```

这是你的 Tailscale 域名 + 8080 端口。改完推送到 GitHub 会重新编译。

**但多数情况不用改代码**——装到手机后可以运行时修改（见第四节）。

## 二、编译 APK（GitHub Actions 云编译，本地不用装 Android Studio）

### 1. 建仓库并上传

在 GitHub 新建一个空仓库（比如 `she-apk`），把本目录**所有文件**（含隐藏的 `.github` 文件夹）传上去：

```bash
cd she-apk
git init
git add .
git commit -m "init she webview apk"
git remote add origin https://github.com/你的用户名/she-apk.git
git branch -M main
git push -u origin main
```

> 注意：`.github` 是隐藏文件夹，Windows 资源管理器看不到它，但 `git add .` 会包含它。用 Git 命令行操作最稳。

### 2. 等编译完成

推送后进入仓库的 **Actions** 标签页，会看到 **"Build 她 APK"** 工作流在跑（约 3–8 分钟，首次稍慢）。

### 3. 下载 APK

工作流跑完（绿色对勾）→ 点进这一次运行 → 页面底部 **Artifacts** 里点 **她-app-debug** 下载 → 解压得到 `app-debug.apk`。

## 三、安装到手机

1. 把 `app-debug.apk` 传到手机（数据线 / 微信文件传输助手 / 网盘都行）
2. 手机上点开安装 → 系统提示"未知来源"→ 选**允许**（一加：设置 → 安全 → 安装未知应用 → 给文件管理器/微信授权）
3. 桌面出现粉色「她」图标，点开即全屏聊天界面

> 也可以用数据线装：`adb install -r app-debug.apk`（adb 在之前下载的 `downloads/platform-tools/` 里）

## 四、运行时改地址（不用重新编译）

**在页面顶部区域长按 3 秒** → 弹出"设置服务器地址"对话框 → 输入新地址 → 保存并自动重新加载。

- 地址会自动补全 `http://`，末尾斜杠无所谓
- 有"恢复默认地址"按钮，一键回到 `START_URL`
- 设置存在手机本地（SharedPreferences），下次打开自动用新地址

> 为什么是顶部长按：避免与聊天消息的长按复制冲突。

## 五、前置条件（连不上时按顺序查）

APK 访问的是电脑上的服务，任何一环断了都会显示"连不上她"页面：

1. ✅ 电脑开机、**不休眠**（睡眠了外网就访问不到，去电源设置关掉睡眠）
2. ✅ 电脑 Tailscale 在线（`tailscale status` 显示 `desktop-j4flem2` 无 offline）
3. ✅ 手机 Tailscale 打开并登录**同一个账号**
4. ✅ 电脑上 `server.js` 正在运行（`cd she-pwa-chat && node server.js`）
5. ✅ 地址没写错：默认是 `http://desktop-j4flem2.tailae05bb.ts.net:8080`

断网页面上有「重试连接」和「设置服务器地址」两个按钮，不用退出 App。

## 六、常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| 装完打不开 / 解析包错误 | 手机系统版本太低 | 需 Android 6.0+（minSdk 23） |
| 显示"连不上她" | 上面第五节某条不满足 | 逐条排查 |
| 提示"未知来源"不给装 | 未授权安装未知应用 | 设置→安全→给所用 App 授权 |
| 发图点"+"没反应 | 文件选择器权限 | 一般是系统延迟，重试；确认前端 `accept="image/*"` 未改 |
| 改了前端没生效 | WebView 设了 `LOAD_NO_CACHE` | 正常，刷新即可；如仍缓存，清 App 数据 |
| 键盘弹出挡住输入框 | `adjustResize` 已配 | 若个别机型异常，反馈具体型号 |

## 七、工程结构

```
she-apk/
├── app/
│   ├── build.gradle                 # 模块构建配置（依赖版本）
│   └── src/main/
│       ├── AndroidManifest.xml      # 权限/竖屏/明文流量
│       ├── java/com/myzy/she/
│       │   └── MainActivity.kt      # ★核心：WebView + 改地址 + 发图 + 断网页
│       └── res/
│           ├── layout/activity_main.xml   # WebView + 启动遮罩
│           ├── values/                     # 应用名"她"、主题色
│           ├── xml/network_security_config.xml  # 放行 HTTP 明文
│           └── mipmap-*/ic_launcher.png    # 各密度图标
├── .github/workflows/build.yml      # 云编译配置
├── build.gradle / settings.gradle   # 顶层构建
├── gradle/wrapper/                  # Gradle 8.7 wrapper
└── gradlew / gradlew.bat            # 本地编译入口（可选）
```

## 八、本地编译（可选，需自行装 Android Studio 或 JDK+SDK）

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```
