# 魔灵召唤 · 自动更新

[![CI Build](https://github.com/michaelggr/SWUpdater/actions/workflows/simple-build.yml/badge.svg)](https://github.com/michaelggr/SWUpdater/actions/workflows/simple-build.yml)
[![Latest Release](https://img.shields.io/github/v/release/michaelggr/SWUpdater?label=latest)](https://github.com/michaelggr/SWUpdater/releases/latest)

面向中国地区《魔灵召唤：天空之役》玩家的游戏更新管理工具，支持电脑模拟器。

## 主要功能

**游戏更新**
- 版本自动检测
- WiFi 环境自动下载
- 断点续传
- 安装包完整性校验
- Root 设备自动安装

**应用管理**
- 首次启动权限引导
- 后台定期检查更新
- 安装后自动清理

**壁纸功能**
- 随机更换壁纸
- 壁纸下载保存

## 支持平台

- Android 7.0 (API 24) 及以上
- 支持所有安卓设备及模拟器（BlueStacks、LDPlayer、夜神等）

## 技术实现

- Kotlin + Coroutines
- OkHttp 网络请求
- WorkManager 后台调度
- Foreground Service 下载保障
- FileProvider 安全安装

## 下载与构建

从 [Releases](https://github.com/michaelggr/SWUpdater/releases/latest) 下载最新 APK，或自行编译：

```bash
./gradlew assembleDebug
```

发布新版本：
```bash
git tag v1.x.x
git push origin v1.x.x
```
