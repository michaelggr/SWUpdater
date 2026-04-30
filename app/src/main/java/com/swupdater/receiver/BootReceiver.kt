package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.swupdater.service.KeepAliveService
import com.swupdater.service.VersionCheckWorker

/**
 * 开机自启广播接收器
 *
 * 设备启动后：
 * 1. 根据设置调度定期版本检查任务
 * 2. 如果保活启用，启动保活服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "sw_updater_prefs"
        const val PREF_AUTO_CHECK = "pref_auto_check"
        const val PREF_CHECK_INTERVAL = "pref_check_interval"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "设备启动完成，检查设置")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 自动检查更新
        val autoCheck = prefs.getBoolean(PREF_AUTO_CHECK, true)
        val intervalHours = prefs.getString(PREF_CHECK_INTERVAL, "6")?.toLongOrNull() ?: 6L

        if (autoCheck) {
            VersionCheckWorker.schedulePeriodicCheck(context, intervalHours)
            Log.i(TAG, "已调度自动检查任务，间隔: ${intervalHours}小时")
        }

        // 2. 保活服务（保活启用 或 开机自启启用时启动）
        if (KeepAliveService.isEnabled(context) || KeepAliveService.isBootAutoStartEnabled(context)) {
            KeepAliveService.start(context)
            Log.i(TAG, "已启动保活服务")
        }
    }
}
