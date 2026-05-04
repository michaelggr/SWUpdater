package com.swupdater

import android.app.Application
import androidx.preference.PreferenceManager
import com.swupdater.receiver.BootReceiver
import com.swupdater.service.KeepAliveService
import com.swupdater.service.VersionCheckWorker
import com.swupdater.util.AppLog

class SWUpdaterApp : Application() {

    companion object {
        private const val TAG = "SWUpdaterApp"
    }

    override fun onCreate() {
        super.onCreate()

        AppLog.init(this)

        AppLog.section(TAG, "魔灵召唤 · 自动更新 启动 v2.6.0")

        initAutoCheck()

        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this)
            AppLog.i(TAG, "保活服务已启动")
        }

        AppLog.i(TAG, "应用初始化完成")
    }

    private fun initAutoCheck() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoCheck = prefs.getBoolean(BootReceiver.PREF_AUTO_CHECK, true)
        val intervalHours = prefs.getString(BootReceiver.PREF_CHECK_INTERVAL, "6")
            ?.toLongOrNull() ?: 6L

        if (autoCheck) {
            VersionCheckWorker.schedulePeriodicCheck(this, intervalHours)
            AppLog.i(TAG, "自动检查已启用，间隔: ${intervalHours}h")
        } else {
            VersionCheckWorker.cancelPeriodicCheck(this)
            AppLog.i(TAG, "自动检查已禁用")
        }
    }
}
