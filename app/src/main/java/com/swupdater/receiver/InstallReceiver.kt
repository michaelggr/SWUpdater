package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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

        // 只关心魔灵召唤的包（检查所有可能的包名）
        if (packageName !in AppInfoUtil.POSSIBLE_PACKAGE_NAMES) {
            return
        }

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                AppLog.i(TAG, "魔灵召唤已安装: $packageName")
                onGameInstalled(context)
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                AppLog.i(TAG, "魔灵召唤已更新: $packageName")
                onGameInstalled(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLog.i(TAG, "本应用已更新: $packageName")
                onSelfUpdated(context)
            }
        }
    }

    /**
     * 游戏安装/更新完成
     * - 删除安装包
     * - 显示安装完成通知
     */
    private fun onGameInstalled(context: Context) {
        // 删除已下载的安装包
        val count = FileUtil.clearDownloadCache(context)
        if (count > 0) {
            AppLog.i(TAG, "已清除 $count 个安装包")
        }

        // 显示安装完成通知
        DownloadNotificationHelper.showInstallCompleteNotification(context)
    }

    /**
     * 本应用更新完成
     * - 清理旧版本安装包
     */
    private fun onSelfUpdated(context: Context) {
        val count = FileUtil.clearSelfUpdateCache(context)
        if (count > 0) {
            AppLog.i(TAG, "已清除 $count 个旧版本安装包")
        }
    }
}
