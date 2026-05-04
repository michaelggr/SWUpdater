package com.swupdater.util

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RootInstallHelper {

    private const val TAG = "RootInstall"

    fun isDeviceRooted(): Boolean {
        // 方式1：尝试执行 su 命令（比 which 更可靠）
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            reader.close()
            process.waitFor()
            if (output?.contains("uid=0") == true) {
                AppLog.i(TAG, "Root 检测: su 可执行 → $output")
                return true
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "Root 检测: su 执行失败 → ${e.message}")
        }

        // 方式2：检查常见 su 路径
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
            // 根据Android版本选择不同的安装参数
            val command = buildInstallCommand(apkPath)
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

    /**
     * 根据Android版本构建pm install命令
     * -g 在 Android 12+ 不再自动授予所有权限，但仍可保留
     * Android 14+ 建议使用会话安装模式
     */
    private fun buildInstallCommand(apkPath: String): String {
        return when {
            Build.VERSION.SDK_INT >= 34 -> {
                // Android 14+: 使用会话安装（更可靠）
                "pm install-create -S ${File(apkPath).length()} | " +
                    "grep -o '[0-9]*' | " +
                    "xargs -I{} sh -c 'pm install-write -S ${File(apkPath).length()} {} \"$apkPath\" && pm install-commit {}'"
            }
            else -> {
                // Android 13 及以下：传统方式
                "pm install -r -g \"$apkPath\""
            }
        }
    }

    data class InstallResult(
        val success: Boolean,
        val message: String
    )
}
