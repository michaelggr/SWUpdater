package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.swupdater.service.DownloadNotificationHelper
import com.swupdater.util.AppLog
import com.swupdater.util.FileUtil
import com.swupdater.util.RootInstallHelper
import java.io.File

/**
 * 通知栏安装按钮点击接收器
 *
 * 用户点击通知栏"安装"按钮时触发，启动系统安装器或 Root 静默安装
 */
class NotificationInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifyInstall"
        const val ACTION_INSTALL_APK = "com.swupdater.action.INSTALL_APK"
        const val EXTRA_FILE_PATH = "file_path"

        /**
         * 创建安装 Intent
         */
        fun createInstallIntent(context: Context, filePath: String): Intent {
            return Intent(context, NotificationInstallReceiver::class.java).apply {
                action = ACTION_INSTALL_APK
                putExtra(EXTRA_FILE_PATH, filePath)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_APK -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return
                AppLog.i(TAG, "通知栏点击安装: $filePath")
                installApk(context, filePath)
            }
        }
    }

    private fun installApk(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            AppLog.e(TAG, "APK 文件不存在: $filePath")
            DownloadNotificationHelper.showInstallFailedNotification(context, "APK 文件不存在")
            return
        }

        // 检查 Root 自动安装
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rootAutoInstall = prefs.getBoolean("pref_root_auto_install", false)
        val isRooted = RootInstallHelper.isDeviceRooted()

        AppLog.i(TAG, "Root自动安装设置: $rootAutoInstall, 设备Root状态: $isRooted")

        if (rootAutoInstall && isRooted) {
            // Root 静默安装
            AppLog.i(TAG, "开始Root静默安装")
            Thread {
                val result = RootInstallHelper.installSilently(filePath)
                if (result.success) {
                    AppLog.i(TAG, "Root 安装成功")
                    DownloadNotificationHelper.showInstallCompleteNotification(context)
                    // 安装完成后删除安装包
                    deleteApkFile(filePath)
                    // 根据设置决定是否自动启动游戏
                    val autoLaunch = PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("pref_auto_launch_game", true)
                    if (autoLaunch) {
                        AppLog.i(TAG, "自动启动游戏已开启，正在启动...")
                        com.swupdater.util.AppInfoUtil.launchGame(context)
                    }
                } else {
                    AppLog.e(TAG, "Root 安装失败: ${result.message}")
                    // 回退到系统安装器
                    installViaSystem(context, file)
                }
            }.start()
        } else {
            AppLog.i(TAG, "使用系统安装器")
            installViaSystem(context, file)
        }
    }

    private fun installViaSystem(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            AppLog.i(TAG, "已启动系统安装器")
        } catch (e: Exception) {
            AppLog.e(TAG, "启动安装器失败: ${e.message}")
            DownloadNotificationHelper.showInstallFailedNotification(context, e.message)
        }
    }

    private fun deleteApkFile(filePath: String) {
        val file = File(filePath)
        if (file.exists() && file.delete()) {
            AppLog.i(TAG, "安装包已删除: $filePath")
        }
    }
}
