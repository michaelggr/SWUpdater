# 魔灵召唤 · 自动更新

> 面向中国地区《魔灵召唤：天空之役》玩家的游戏更新管理工具

## 功能概述

| 功能 | 说明 |
|------|------|
| **版本检测** | 访问官网解析页面获取最新版本号，本地版本通过 PackageManager 获取 |
| **自动/手动检查** | 支持定期自动检查（WorkManager）和手动刷新 |
| **安装包下载** | 前台服务保障下载，支持断点续传，实时进度反馈 |
| **通知栏进度** | 下载进度实时显示在通知栏，包含大小、速度、百分比 |
| **WiFi 自动下载** | 发现新版本时，WiFi 环境下自动下载更新包 |
| **完整性校验** | 下载后自动校验 MD5 / SHA256 / 文件大小 |
| **Root 自动安装** | 已 Root 设备下载完成后自动静默安装，无需手动确认 |
| **系统安装** | 非 Root 设备自动处理安装权限，FileProvider 安全安装 |
| **壁纸系统** | 随机壁纸、壁纸下载、透明度控制 |
| **后台保活** | 前台服务 + WakeLock + 开机自启 + Root 保活 |

## 兼容性

| 项目 | 值 |
|------|------|
| 最低版本 | Android 7.0 (API 24) |
| 目标版本 | Android 14 (API 34) |
| 编译版本 | Android 14 (API 34) |
| 语言 | Kotlin |

## 架构设计

```
┌──────────────────────────────────────────────────────────┐
│                        UI 层                              │
│  MainActivity ←→ MainViewModel ←→ SettingsActivity       │
└──────────────┬───────────────────────────────────────────┘
               │
┌──────────────▼───────────────────────────────────────────┐
│                        业务层                             │
│  VersionCheckService  │  DownloadManager (全局单例)        │
│  (版本检测+APK链接解析)  │  (OkHttp下载+断点续传+进度推送)  │
└──────────────┬───────────────────────────────────────────┘
               │
┌──────────────▼───────────────────────────────────────────┐
│                        服务层                             │
│  DownloadService        │  VersionCheckWorker             │
│  (前台下载+通知+Root安装) │  (WorkManager定期检查+自动下载) │
│  DownloadNotificationHelper (通知栏进度，共用)              │
│  KeepAliveService       │  BootReceiver / InstallReceiver │
│  (后台保活)              │  (开机自启/安装监听)             │
└──────────────┬───────────────────────────────────────────┘
               │
┌──────────────▼───────────────────────────────────────────┐
│                        工具层                             │
│  AppInfoUtil   │ ChecksumUtil  │ FileUtil      │ AppLog  │
│  (应用信息检测) │ (完整性校验)   │ (文件/格式化)  │ (日志)  │
│  RootInstallHelper            │ WallpaperManager         │
│  (Root静默安装)                │ (壁纸管理)               │
└──────────────────────────────────────────────────────────┘
```

## 下载与安装流程

```
用户点击"立即下载" / WiFi下自动下载
       │
       ▼
  DownloadService.start()
       │
       ├── 清除旧缓存
       ├── 创建前台通知
       ├── DownloadManager.startDownload() (OkHttp + IPv4优先DNS)
       │       │
       │       ├── URL 协议修正 (dn.qpyou.cn → HTTP)
       │       ├── 断点续传 (Range header)
       │       ├── 实时进度 (StateFlow → 通知栏 + UI)
       │       └── 0KB 校验 + 空文件清理
       │
       ▼
  下载完成 → DOWNLOADED
       │
       ▼
  MainViewModel.verifyDownloadedFile()
       ├── 文件大小校验
       ├── MD5 校验
       └── SHA256 校验
       │
       ▼
  校验通过 → VERIFIED
       │
       ├── [Root已开启] → RootInstallHelper.installSilently()
       │       │              su -c "pm install -r -g"
       │       ├── 成功 → 通知"安装完成"
       │       └── 失败 → 通知"安装失败"
       │
       └── [非Root/未开启] → 通知"下载完成"，用户手动点击安装
               │
               ▼
          FileProvider → 系统安装器
```

## 核心模块说明

### VersionCheckService（版本检测服务）

从设置中配置的数据源URL检测最新版本：

1. 访问配置的短链 URL
2. 短链返回 HTML 页面，JS 中包含 APK 直链
3. APK 文件名格式：`smon_919_xxx.apk` → `919` → `9.1.9`

**关键处理**：`dn.qpyou.cn` 仅支持 HTTP，强制将 HTTPS 转为 HTTP 避免 SSL 错误

### DownloadManager（下载管理器，全局单例）

ViewModel 和 DownloadService 共享同一实例，确保 UI 进度条和通知栏始终同步。

| 特性 | 实现 |
|------|------|
| 单例模式 | `object DownloadManager`，全局唯一 |
| 下载引擎 | OkHttp + IPv4 优先 DNS |
| 断点续传 | `Range: bytes={已下载}-` 请求头 |
| 进度推送 | `StateFlow<DownloadProgress>` |
| 速度计算 | 1 秒采样间隔 |
| 0KB 校验 | 下载后检查文件大小，删除空文件 |
| URL 修正 | `normalizeUrl()` 强制 dn.qpyou.cn 使用 HTTP |

### DownloadService（前台下载服务）

| 特性 | 说明 |
|------|------|
| 前台服务 | `foregroundServiceType="dataSync"`，确保下载不被回收 |
| 防重复 | `@Volatile isDownloading` 标志 |
| 进度通知 | 通过 `DownloadNotificationHelper` 更新通知栏 |
| Root 安装 | VERIFIED 后自动检查设置，执行静默安装 |
| 生命周期 | 安装完成后才 `stopSelf()`，避免中断 Root 安装协程 |

### DownloadNotificationHelper（通知助手，全局单例）

| 状态 | 通知内容 |
|------|----------|
| DOWNLOADING | 进度条 + 大小 + 速度 |
| VERIFYING | 不确定进度条 "校验中" |
| INSTALLING | 不确定进度条 "正在安装" |
| VERIFIED | "下载完成，点击安装" |
| FAILED / VERIFY_FAILED | "下载失败，请重试" |
| Root 安装成功 | "更新已自动安装完成" |
| Root 安装失败 | "自动安装失败: xxx" |

### RootInstallHelper（Root 静默安装工具）

| 方法 | 说明 |
|------|------|
| `isDeviceRooted()` | 检测 `which su` 及常见 su 路径 |
| `installSilently(apkPath)` | 执行 `su -c "pm install -r -g"` |

**默认行为**：检测到 Root 权限时自动开启，可在设置中关闭

### VersionCheckWorker（定期检查 + 自动下载）

```
WorkManager 定时触发
    │
    ▼
检查最新版本
    │
    ├── 有更新 → 读取设置
    │       │
    │       ├── [自动下载开启 + WiFi满足] → DownloadService.start()
    │       │
    │       └── [不自动下载] → 发送更新提醒通知
    │
    └── 无更新 → 静默结束
```

## 项目结构

```
SWUpdater/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/swupdater/
│           ├── SWUpdaterApp.kt
│           ├── model/
│           │   └── Models.kt
│           ├── network/
│           │   ├── VersionCheckService.kt
│           │   ├── DownloadManager.kt
│           │   └── DownloadChannels.kt
│           ├── receiver/
│           │   ├── BootReceiver.kt
│           │   └── InstallReceiver.kt
│           ├── service/
│           │   ├── DownloadService.kt
│           │   ├── DownloadNotificationHelper.kt
│           │   ├── KeepAliveService.kt
│           │   └── VersionCheckWorker.kt
│           ├── ui/
│           │   ├── MainActivity.kt
│           │   ├── MainViewModel.kt
│           │   └── SettingsActivity.kt
│           └── util/
│               ├── AppInfoUtil.kt
│               ├── AppLog.kt
│               ├── ChecksumUtil.kt
│               ├── CommonUtil.kt
│               ├── FileUtil.kt
│               ├── RootInstallHelper.kt
│               └── WallpaperManager.kt
└── build.gradle.kts
```

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 主要开发语言 |
| ViewBinding | 视图绑定 |
| OkHttp 4.12 | HTTP 网络请求 + IPv4 优先 DNS |
| Jsoup | HTML 页面解析 |
| Coroutines + Flow | 异步和响应式编程 |
| WorkManager 2.9 | 后台定期任务调度 |
| Foreground Service | 前台下载保障 |
| FileProvider | APK 安全安装 |
| SharedPreferences | 偏好设置存储 |
| Material Design 3 | UI 设计规范 |

## 构建说明

### 环境要求

- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android SDK 34

### 构建

```bash
cd SWUpdater
chmod +x gradlew
./gradlew assembleDebug

# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET | 访问官网获取版本信息 & 下载 APK |
| ACCESS_NETWORK_STATE | 检测网络状态（WiFi/移动数据） |
| WRITE_EXTERNAL_STORAGE | 保存文件（Android 9 及以下） |
| READ_EXTERNAL_STORAGE | 读取文件（Android 12 及以下） |
| MANAGE_EXTERNAL_STORAGE | 管理外部存储（Android 11+） |
| REQUEST_INSTALL_PACKAGES | 安装 APK 更新包 |
| FOREGROUND_SERVICE | 前台下载服务 |
| FOREGROUND_SERVICE_DATA_SYNC | 前台服务类型 |
| POST_NOTIFICATIONS | 下载进度通知（Android 13+） |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| WAKE_LOCK | 防止 CPU 休眠 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 电池优化白名单 |
| SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | 精确闹钟 |

## 存储路径

| 内容 | 路径 |
|------|------|
| APK 下载 | `公共 Download/SWUpdater/updates/` |
| 壁纸缓存 | `公共 Pictures/SWUpdater/wallpapers/` |
| 壁纸下载 | `公共 Download/SWUpdater/wallpapers/` |

> 使用公共目录而非应用私有目录，卸载应用后文件不丢失

## 版本历史

| 版本 | 变更 |
|------|------|
| v1.0.0 | 初始版本：版本检测、APK 下载、安装 |
| v1.1.0 | 修复版本检测，增加日志模式、日/夜模式 |
| v1.2.0 | 修复 Google Play IPv6、Com2uS DNS、本地版本检测 |
| v1.3.0 | 国内下载方案，qpyou.cn 作为主数据源 |
| v1.4.0 | 应用图标、底部 Footer、壁纸系统 |
| v1.5.0 | 内容透明化、FAB 按钮、数据源 URL 统一 |
| v1.6.0 | 壁纸透明度设置、FAB 移至右下角、刷新图标 |
| **v1.7.0** | 缓存目录迁移至公用目录、修复 64KB/0KB 下载问题、去除渠道选择直接下载、WiFi 自动下载 + 通知栏进度、Root 自动静默安装、后台保活 |
