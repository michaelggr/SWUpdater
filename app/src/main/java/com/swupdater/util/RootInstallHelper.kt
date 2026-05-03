package com.swupdater.util

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootInstallHelper {

    private const val TAG = "RootInstall"

    fun isDeviceRooted(): Boolean {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val path = reader.readLine()
            reader.close()
            process.waitFor()
            if (!path.isNullOrEmpty()) {
                AppLog.i(TAG, "Root 检测: which su → $path")
                return true
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "Root 检测: which su 失败 → ${e.message}")
        }

        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        for (path in suPaths) {
            if (File(path).exists()) {
                AppLog.i(TAG, "Root 检测: 发现 su → $path")
                return true
            }
        }
        AppLog.i(TAG, "Root 检测: 设备未 Root")
        return false
    }

    fun installSilently(apkPath: String): InstallResult {
        val file = File(apkPath)
        if (!file.exists()) {
            AppLog.e(TAG, "Root 安装失败: APK 文件不存在 $apkPath")
            return InstallResult(false, "APK 文件不存在: $apkPath")
        }

        return try {
            val command = "pm install -r -g \"$apkPath\""
            AppLog.i(TAG, "Root 静默安装命令: $command")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = reader.readText().trim()
            val error = errorReader.readText().trim()
            reader.close()
            errorReader.close()

            val exitCode = process.waitFor()
            AppLog.i(TAG, "Root 安装结果: exitCode=$exitCode, output=$output, error=$error")

            if (output.contains("Success", ignoreCase = true) || exitCode == 0) {
                AppLog.i(TAG, "Root 静默安装成功: $apkPath")
                InstallResult(true, "安装成功")
            } else {
                val msg = error.ifEmpty { output }
                AppLog.e(TAG, "Root 静默安装失败: exitCode=$exitCode, message=$msg")
                InstallResult(false, msg)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Root 静默安装异常: ${e.message}")
            InstallResult(false, e.message ?: "安装异常")
        }
    }

    data class InstallResult(
        val success: Boolean,
        val message: String
    )
}
