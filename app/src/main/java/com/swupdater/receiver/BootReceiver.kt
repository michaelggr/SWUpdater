package com.swupdater.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import com.swupdater.service.KeepAliveService
import com.swupdater.service.VersionCheckWorker
import com.swupdater.util.AppLog

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PREF_AUTO_CHECK = "pref_auto_check"
        const val PREF_CHECK_INTERVAL = "pref_check_interval"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT
        )
        if (action !in validActions) return

        AppLog.section(TAG, "系统事件: $action")

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val autoCheck = prefs.getBoolean(PREF_AUTO_CHECK, true)
        val intervalHours = prefs.getString(PREF_CHECK_INTERVAL, "6")?.toLongOrNull() ?: 6L

        if (autoCheck) {
            VersionCheckWorker.schedulePeriodicCheck(context, intervalHours)
            AppLog.i(TAG, "已调度自动检查任务，间隔: ${intervalHours}h")
        }

        if (KeepAliveService.isEnabled(context) || KeepAliveService.isBootAutoStartEnabled(context)) {
            KeepAliveService.start(context)
            AppLog.i(TAG, "保活服务已启动")
        }
    }
}
