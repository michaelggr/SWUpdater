package com.swupdater.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.swupdater.model.AppInstallInfo

/**
 * 应用信息工具类
 * 通过 Android 系统 API 获取已安装应用信息
 *
 * 修复：
 * - Android 11+ 包可见性：需在 Manifest 声明 <queries>
 * - 支持多包名自动检测
 */
object AppInfoUtil {

    private const val TAG = "AppInfoUtil"

    // 魔灵召唤 - 全球服包名（Hive / Google Play 版）
    const val PACKAGE_NAME_CN = "com.com2us.smon.normal.freefull.google.kr.android.common"

    // 魔灵召唤 - 所有可能的包名列表（按优先级排序）
    val POSSIBLE_PACKAGE_NAMES = listOf(
        "com.com2us.smon.normal.freefull.google.kr.android.common",  // 全球服 / Hive 版
        "com.com2us.smon.normal.freefull.google.kr.android.official", // 可能的官方版
        "com.com2us.smon.normal.freefull.google.kr.android",          // 可能的短包名
    )

    /**
     * 自动检测已安装的魔灵召唤包名
     * 遍历可能的包名列表，返回第一个已安装的
     * @return 已安装的包名，如果都没安装则返回默认包名
     */
    fun detectInstalledPackageName(context: Context): String {
        AppLog.d(TAG, "开始检测已安装的魔灵召唤包名...")
        AppLog.d(TAG, "系统版本: Android ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")

        for (packageName in POSSIBLE_PACKAGE_NAMES) {
            val installed = isAppInstalled(context, packageName)
            AppLog.d(TAG, "  检测 $packageName: ${if (installed) "✅ 已安装" else "❌ 未安装"}")
            if (installed) {
                if (packageName != PACKAGE_NAME_CN) {
                    AppLog.i(TAG, "检测到非默认包名: $packageName")
                }
                return packageName
            }
        }

        // 都没检测到，输出诊断信息
        AppLog.w(TAG, "所有包名均未检测到，可能原因：")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AppLog.w(TAG, "  1. Android 11+ 包可见性限制（<queries> 未正确声明）")
        }
        AppLog.w(TAG, "  2. 游戏确实未安装")
        AppLog.w(TAG, "  3. 游戏使用了未在列表中的包名")

        // 尝试使用 getInstalledApplications 查找
        tryFindGameByApplicationList(context)

        return PACKAGE_NAME_CN
    }

    /**
     * 尝试通过 getInstalledApplications 查找魔灵召唤
     * 这需要 QUERY_ALL_PACKAGES 权限或 Manifest queries 声明
     */
    private fun tryFindGameByApplicationList(context: Context) {
        try {
            val apps = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val com2usApps = apps.filter {
                it.packageName.contains("com2us", ignoreCase = true) ||
                it.packageName.contains("smon", ignoreCase = true)
            }
            if (com2usApps.isNotEmpty()) {
                AppLog.i(TAG, "通过应用列表找到 Com2uS 相关应用:")
                com2usApps.forEach { app ->
                    AppLog.d(TAG, "  - ${app.packageName}: ${app.loadLabel(context.packageManager)}")
                }
            } else {
                AppLog.d(TAG, "应用列表中也未找到 Com2uS 相关应用")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "无法获取应用列表: ${e.message}")
        }
    }

    /**
     * 获取已安装应用信息
     */
    fun getInstalledAppInfo(context: Context, packageName: String): AppInstallInfo {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA
            )
            val info = AppInstallInfo(
                packageName = packageName,
                versionName = packageInfo.versionName ?: "未知",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
                isInstalled = true
            )
            AppLog.d(TAG, "获取到应用信息: $packageName v${info.versionName} (${info.versionCode})")
            info
        } catch (e: PackageManager.NameNotFoundException) {
            AppLog.d(TAG, "应用未安装或不可见: $packageName (Android ${Build.VERSION.SDK_INT})")
            AppInstallInfo(
                packageName = packageName,
                versionName = "",
                versionCode = 0,
                isInstalled = false
            )
        }
    }

    /**
     * 获取所有可能的包名的安装状态
     */
    fun getAllInstalledPackages(context: Context): List<AppInstallInfo> {
        return POSSIBLE_PACKAGE_NAMES.map { packageName ->
            getInstalledAppInfo(context, packageName)
        }
    }

    /**
     * 检查应用是否已安装
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 检查任意一个包名的魔灵召唤是否已安装
     */
    fun isAnyVersionInstalled(context: Context): Boolean {
        return POSSIBLE_PACKAGE_NAMES.any { isAppInstalled(context, it) }
    }

    /**
     * 获取应用的启动 Intent
     */
    fun getLaunchIntent(context: Context, packageName: String): android.content.Intent? {
        return context.packageManager.getLaunchIntentForPackage(packageName)
    }

    /**
     * 从版本名解析版本号数组，用于版本比较
     * 例如 "9.1.9" -> [9, 1, 9]
     */
    fun parseVersionName(versionName: String): List<Int> {
        return versionName
            .removePrefix("v")
            .removePrefix("V")
            .split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    /**
     * 比较两个版本名
     * @return >0 表示 v1 较新，<0 表示 v2 较新，0 表示相同
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = parseVersionName(v1)
        val parts2 = parseVersionName(v2)
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    /**
     * 判断 v1 是否比 v2 更新
     */
    fun isNewerVersion(v1: String, v2: String): Boolean {
        return compareVersions(v1, v2) > 0
    }
}
