﻿package com.swupdater.util

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root 静默安装工具
 *
 * 使用 su 权限执行 pm install，无需用户确认
 * 适用于已 Root 设备的自动安装场景
 */
object RootInstallHelper {

    private const val TAG = "RootInstall"

    /**
     * 检查设备是否已 Root
     */
    fun isDeviceRooted(): Boolean {
        // 方式1：检查 which su
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val path = reader.readLine()
            reader.close()
            process.waitFor()
            if (!path.isNullOrEmpty()) return true
        } catch (_: Exception) {}

        // 方式2：检查常见 su 路径
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        for (path in suPaths) {
            if (File(path).exists()) return true
        }
        return false
    }

    /**
     * 使用 Root 权限静默安装 APK
     *
     * @param apkPath APK 文件绝对路径
     * @return 安装结果
     */
    fun installSilently(apkPath: String): InstallResult {
        val file = File(apkPath)
        if (!file.exists()) {
            return InstallResult(false, "APK 文件不存在: $apkPath")
        }

        return try {
            // 使用 pm install -r -g 覆盖安装并自动授予所有权限
            val command = "pm install -r -g \"$apkPath\""
            Log.i(TAG, "Root 静默安装: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = reader.readText().trim()
            val error = errorReader.readText().trim()
            reader.close()
            errorReader.close()

            val exitCode = process.waitFor()
            Log.i(TAG, "安装结果: exitCode=$exitCode, output=$output, error=$error")

            if (output.contains("Success", ignoreCase = true)) {
                AppLog.i(TAG, "Root 静默安装成功: $apkPath")
                InstallResult(true, "安装成功")
            } else {
                val msg = if (exitCode != 0) "exitCode=$exitCode, ${error.ifEmpty { output }}" else error.ifEmpty { output }
                AppLog.e(TAG, "Root 静默安装失败: $msg")
                InstallResult(false, msg.ifEmpty { "安装失败 (exitCode=$exitCode)" })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root 静默安装异常", e)
            AppLog.e(TAG, "Root 静默安装异常: ${e.message}")
            InstallResult(false, e.message ?: "安装异常")
        }
    }

    /**
     * 安装结果
     */
    data class InstallResult(
        val success: Boolean,
        val message: String
    )
}
