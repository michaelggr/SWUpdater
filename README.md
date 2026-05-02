# 魔灵召唤 · 自动更新

[![CI Build](https://github.com/michaelggr/SWUpdater/actions/workflows/simple-build.yml/badge.svg)](https://github.com/michaelggr/SWUpdater/actions/workflows/simple-build.yml)
[![Latest Release](https://img.shields.io/github/v/release/michaelggr/SWUpdater?label=latest)](https://github.com/michaelggr/SWUpdater/releases/latest)

> 面向中国地区《魔灵召唤：天空之役》玩家的游戏更新管理工具

## 功能概述

| 功能 | 说明 |
|------|------|
| **首次引导** | 首次启动引导获取权限，说明每个权限用途 |
| **版本检测** | 访问数据源获取最新版本，自动解析版本号 |
| **自动下载** | 发现新版本时自动下载（仅限无下载且无本地包时） |
| **WiFi 自动下载** | 可设置仅在 WiFi 环境下自动下载 |
| **断点续传** | 支持断点续传，下载失败可继续 |
| **进度通知** | 通知栏实时显示下载进度、速度、百分比 |
| **完整性校验** | 下载后自动校验 MD5 + SHA256 + 文件大小 |
| **Root 自动安装** | 已 Root 设备下载完成后自动静默安装 |
| **系统安装** | 非 Root 设备通过 FileProvider 安全安装 |
| **自动清理** | 安装完成后自动清理安装包，节省空间 |
| **壁纸系统** | 随机壁纸、壁纸下载、应用到手机壁纸 |
| **后台保活** | 前台服务 + WorkManager + 开机自启 |

## 截图

> 暂无

## 权限说明

首次安装启动时会引导获取以下权限：

| 权限 | 用途 |
|------|------|
| **存储权限（所有文件访问）** | 保存壁纸到公共下载目录和保存安装包 |
| **悬浮窗权限** | 显示下载进度和安装完成通知 |
| **安装未知应用权限** | 安装 APK 更新包 |
| **通知权限** | 显示下载和安装进度通知（Android 13+） |
| **后台保活权限** | 防止应用被系统清理，确保更新检测正常运行 |

> ROOT 用户可跳过部分权限

## 兼容性

| 项目 | 值 |
|------|------|
| 最低版本 | Android 7.0 (API 24) |
| 目标版本 | Android 14 (API 34) |
| 编译版本 | Android 14 (API 34) |
| 语言 | Kotlin |

## 项目结构

```
SWUpdater/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/swupdater/
│       │   ├── SWUpdaterApp.kt
│       │   ├── model/Models.kt
│       │   ├── network/
│       │   │   ├── VersionCheckService.kt
│       │   │   └── DownloadManager.kt
│       │   ├── receiver/
│       │   │   ├── BootReceiver.kt
│       │   │   ├── InstallReceiver.kt
│       │   │   └── NotificationInstallReceiver.kt
│       │   ├── service/
│       │   │   ├── DownloadService.kt
│       │   │   ├── KeepAliveService.kt
│       │   │   └── VersionCheckWorker.kt
│       │   ├── ui/
│       │   │   ├── SplashActivity.kt      # 首次引导
│       │   │   ├── MainActivity.kt        # 主界面
│       │   │   ├── MainViewModel.kt
│       │   │   └── SettingsActivity.kt    # 设置界面
│       │   └── util/
│       │       ├── AppInfoUtil.kt
│       │       ├── AppLog.kt
│       │       ├── ChecksumUtil.kt
│       │       ├── FileUtil.kt
│       │       └── WallpaperManager.kt
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_settings.xml
│           │   ├── activity_splash.xml    # 引导页
│           │   └── dialog_download_progress.xml
│           └── ...
└── build.gradle.kts
```

## 核心流程

### 下载与安装流程

```
用户点击下载 / WiFi下自动下载
       │
       ▼
  DownloadService.start()
       │
       ├── 检查是否正在下载
       ├── 创建前台通知
       └── DownloadManager.startDownload()
              │
              ├── 断点续传 (Range header)
              ├── 实时进度 (StateFlow)
              └── 0KB 校验
              │
              ▼
        下载完成 → 校验
              │
              ├── MD5 + SHA256 + 文件大小
              │
              ▼
        校验通过 → 安装
              │
              ├── [Root] → RootInstallHelper.installSilently()
              │
              └── [非Root] → FileProvider → 系统安装器
              │
              ▼
         安装完成 → 自动清理安装包
```

### 版本自动下载条件

```
发现新版本
    │
    ├── 正在下载中？ → 跳过
    │
    ├── 本地已有安装包？ → 跳过
    │
    ├── 自动下载开启？ → 否 → 发送通知
    │
    ├── WiFi 仅开？ → WiFi 连接？ → 否 → 发送通知
    │
    └── 满足条件 → 自动下载
```

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 主要开发语言 |
| ViewBinding | 视图绑定 |
| OkHttp 4.12 | HTTP 网络请求 |
| Coroutines + Flow | 异步和响应式编程 |
| WorkManager 2.9 | 后台定期任务调度 |
| Foreground Service | 前台下载保障 |
| FileProvider | APK 安全安装 |
| Material Design 3 | UI 设计规范 |

## 构建说明

### 环境要求

- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android SDK 34

### 构建

```bash
cd SWUpdater
./gradlew assembleDebug

# 输出: app/build/outputs/apk/debug/app-debug.apk
```

## 发布新版本

```bash
# 1. 提交代码
git add . && git commit -m "your changes"

# 2. 推送
git push

# 3. 创建 tag 触发 CI 构建
git tag v1.x.x
git push origin v1.x.x

# GitHub Actions 会自动：
# - 编译签名 Release APK
# - 创建 GitHub Release
```

## 存储路径

| 内容 | 路径 |
|------|------|
| APK 下载 | `公共 Download/SWUpdater/updates/` |
| 壁纸缓存 | `应用私有目录/cache/wallpapers/` |
| 壁纸下载 | `公共 Download/SWUpdater/wallpapers/` |

## 更新日志

| 版本 | 变更 |
|------|------|
| v1.9.18 | 首次安装引导、权限说明对话框 |
| v1.9.17 | 修复存储权限对话框闪退 |
| v1.9.15 | 下载进度对话框、设置界面版本更新 |
| v1.9.13 | 恢复网络下载壁纸 |
| v1.9.0 | 内存优化、内置壁纸精简 |
| v1.8.0 | 壁纸系统重构 |
| v1.7.0 | 首次正式版本 |
