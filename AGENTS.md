# AGENTS.md — 共享剪贴-卢（ClipDitto）

> 给 AI 编码代理的项目说明。改动代码前请先读完本文件。

## 项目概述

仿 Windows Ditto 的**安卓本地剪贴板记录工具**：悬浮球 + 剪贴板自动记录（文字/图片/音视频/文件）+ 无障碍点击粘贴 + 时间段清理 + zip 备份导入 + 局域网多设备同步。

- 包名 / namespace：`com.clipditto.app`
- 语言：Kotlin（纯 Kotlin，无 Java 源码）
- UI：传统 View 体系（XML 布局 + RecyclerView），**没有 Compose**

## 构建与运行

- 工具链：AGP 8.5.0 / Kotlin 1.9.24 / Gradle 8.7（`gradle-8.7/` 目录随仓库携带）/ JDK 17
- `compileSdk 34`，`minSdk 26`，`targetSdk 34`
- 数据库：Room 2.6.1（**KSP**，不是 kapt）
- 常用命令（在项目根目录执行）：
  - 组装 debug 包：`./gradlew assembleDebug`（无 gradlew 时用 `gradle-8.7/bin/gradle`）
  - 安装到设备：`adb install -r app/build/outputs/apk/debug/剪贴板_v<versionName>.apk`
  - 无单元测试 / UI 测试目录，验证靠真机运行
- 打包产物文件名固定为 `剪贴板_v${versionName}.apk`（见 `app/build.gradle` 的 `applicationVariants` 配置），发版时在 `app/build.gradle` 里递增 `versionCode` 并改 `versionName`

## 目录结构（app/src/main/java/com/clipditto/app/）

```
├── App.kt                        # Application 入口 + 通知渠道
├── data/                         # Room：ClipItem / ClipDao / ClipDatabase / ClipRepository（媒体落盘）
├── service/
│   ├── ClipboardService.kt       # 核心服务：剪贴板监听 + 悬浮球 + 悬浮面板 + 粘贴
│   ├── PasteAccessibilityService.kt  # 无障碍粘贴（对聚焦输入框执行 ACTION_PASTE）
│   ├── BootReceiver.kt           # 开机自启恢复监听
│   ├── ShizukuClipboard.kt       # Shizuku 桥接读取剪贴板
│   └── ClipboardShellService.kt  # Shizuku 用户服务（shell 权限）
├── sync/                         # 局域网同步：设备发现 / 服务端 / 客户端 / 设置
├── backup/BackupManager.kt       # zip 备份 / 导入（clips.json + 媒体文件）
├── ui/                           # MainActivity / DevicesActivity / HistoryAdapter / 悬浮窗视图
└── util/                         # FuzzySearch / StorageStats / TokenUtils / UrlUtils
```

另有 `app/src/main/aidl/` 下的 `IClipboardBridge.aidl`（Shizuku 桥接接口；AGP 8 需 `aidl true`，已开启）。

## 关键机制与坑（改动前必读）

- **Android 10+ 后台剪贴板限制**：系统禁止后台 App 读剪贴板。现行方案：检测到变化时临时加 1px 透明悬浮窗抢焦点读取，读完立即移除；另有 Shizuku 通道（`ShizukuClipboard` / `ClipboardShellService`）。不要"简化"掉这段逻辑。
- **文字粘贴链路**：先写系统剪贴板 → 无障碍服务对聚焦的可编辑节点 `ACTION_PASTE`；无障碍未开启时降级为"复制到剪贴板手动粘贴"。
- **媒体粘贴**：经 `FileProvider` 暴露私有目录文件（配置在 `res/xml/file_paths.xml`），以 Uri 放回系统剪贴板。
- **媒体文件持久化**：复制入库时媒体拷贝到应用私有目录，删除记录（含按时间段批量删）必须同步删文件，见 `ClipRepository`。
- **BuildConfig 依赖**：Shizuku 用户服务用到 `BuildConfig.APPLICATION_ID` / `DEBUG`，已开 `buildConfig true`，不要关。
- `settings.gradle` 使用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS`——仓库只能在 `settings.gradle` 声明，不要往模块级 build.gradle 加 `repositories`。

## 代码约定

- 注释、提交信息、面向用户的字符串用**中文**；APK 文件名也含中文。
- 新增依赖前先确认 mavenCentral / google 已有版本，尽量复用现有库（material / recyclerview / lifecycle / room）。
- 改 Room 实体（`ClipItem`）意味着数据库迁移：同步更新 `ClipDatabase` 的 version 和迁移逻辑。
- 悬浮窗相关 UI 在 `service/ClipboardService.kt` 内动态构建 + `res/layout/view_floating_*.xml`，改动时注意悬浮球拖动/单击展开的手势逻辑。

## 仓库内其他文件

- `start-shizuku.ps1` / `start-shizuku.bat`：一键为所有 adb 连接设备启动 Shizuku 服务的辅助脚本（开发调试用，非 App 组成部分）。
- `shizuku-v13.6.0.apk`：调试用的 Shizuku 安装包，勿删。
- `README.md` / `README.en.md`：用户向说明，功能变更后需同步更新。
