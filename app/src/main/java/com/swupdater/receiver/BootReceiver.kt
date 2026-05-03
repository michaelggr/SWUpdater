package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swupdater.service.KeepAliveService
import com.swupdater.service.VersionCheckWorker
import com.swupdater.util.AppLog

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREFS_NAME = "sw_updater_prefs"
        const val PREF_AUTO_CHECK = "pref_auto_check"
        const val PREF_CHECK_INTERVAL = "pref_check_interval"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        AppLog.section(TAG, "设备启动完成")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val autoCheck = prefs.getBoolean(PREF_AUTO_CHECK, true)
        val intervalHours = prefs.getString(PREF_CHECK_INTERVAL, "6")?.toLongOrNull() ?: 6L

        if (autoCheck) {
            VersionCheckWorker.schedulePeriodicCheck(context, intervalHours)
            AppLog.i(TAG, "已调度自动检查任务，间隔: ${intervalHours}h")
        }

        if (KeepAliveService.isEnabled(context)) {
            KeepAliveService.start(context)
            AppLog.i(TAG, "已启动保活服务")
        }

        if (KeepAliveService.isBootAutoStartEnabled(context)) {
            KeepAliveService.start(context)
            AppLog.i(TAG, "开机自启已启用，启动保活服务")
        }
    }
}
