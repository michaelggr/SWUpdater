package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.swupdater.service.DownloadNotificationHelper
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.AppLog
import com.swupdater.util.FileUtil

/**
 * 安装完成广播接收器
 *
 * 监听游戏包的安装/更新/卸载事件：
 * - 安装/更新完成后：删除安装包 + 显示"安装完成"通知
 * - 本应用更新：忽略
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (packageName in AppInfoUtil.POSSIBLE_PACKAGE_NAMES) {
                    AppLog.i(TAG, "魔灵召唤已安装: $packageName")
                    onGameInstalled(context)
                }
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                if (packageName in AppInfoUtil.POSSIBLE_PACKAGE_NAMES) {
                    AppLog.i(TAG, "魔灵召唤已更新: $packageName")
                    onGameInstalled(context)
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLog.i(TAG, "本应用已更新: $packageName")
                onSelfInstalled(context)
            }
        }
    }

    /**
     * 本应用自身安装/更新完成
     * - 删除安装包
     */
    private fun onSelfInstalled(context: Context) {
        // 删除已下载的自身安装包
        val count = FileUtil.clearSelfUpdateCache(context)
        if (count > 0) {
            AppLog.i(TAG, "已清除 $count 个旧安装包")
        }
    }

    /**
     * 游戏安装/更新完成
     * - 删除安装包
     * - 显示安装完成通知
     * - 根据设置自动启动游戏
     */
    private fun onGameInstalled(context: Context) {
        // 删除已下载的安装包
        val count = FileUtil.clearDownloadCache(context)
        if (count > 0) {
            AppLog.i(TAG, "已清除 $count 个安装包")
        }

        // 显示安装完成通知
        DownloadNotificationHelper.showInstallCompleteNotification(context)

        // 检查是否需要自动启动游戏
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val autoLaunch = prefs.getBoolean("pref_auto_launch_game", true)
        if (autoLaunch) {
            AppLog.i(TAG, "自动启动游戏已开启，正在启动...")
            AppInfoUtil.launchGame(context)
        } else {
            AppLog.i(TAG, "自动启动游戏已关闭")
        }
    }
}
