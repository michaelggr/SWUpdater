package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 安装完成广播接收器
 * 监听游戏包的安装/更新/卸载事件
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return

        // 只关心魔灵召唤的包（检查所有可能的包名）
        if (packageName !in com.swupdater.util.AppInfoUtil.POSSIBLE_PACKAGE_NAMES) {
            return
        }

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                Log.i(TAG, "魔灵召唤已安装: $packageName")
                // 可在此发送通知或更新 UI
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.i(TAG, "魔灵召唤已更新: $packageName")
                // 更新完成，清除已下载的旧版本 APK
                clearOldApkFiles(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "本应用已更新: $packageName")
            }
        }
    }

    private fun clearOldApkFiles(context: Context) {
        val count = com.swupdater.util.FileUtil.clearDownloadCache(context)
        if (count > 0) {
            Log.i(TAG, "已清除 $count 个旧版 APK 缓存文件")
        }
    }
}
