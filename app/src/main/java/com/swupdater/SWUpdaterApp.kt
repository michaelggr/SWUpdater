package com.swupdater

import android.app.Application
import android.util.Log
import com.swupdater.receiver.BootReceiver
import com.swupdater.service.KeepAliveService
import com.swupdater.service.VersionCheckWorker

/**
 * 应用入口
 * 初始化全局配置和后台任务
 */
class SWUpdaterApp : Application() {

    companion object {
        private const val TAG = "SWUpdaterApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "魔灵召唤 · 自动更新 应用启动 v1.7.0")

        // 根据设置初始化自动检查任务
        initAutoCheck()

        // 如果保活已启用，启动保活服务
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this)
            Log.i(TAG, "保活服务已启动")
        }
    }

    private fun initAutoCheck() {
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, MODE_PRIVATE)
        val autoCheck = prefs.getBoolean(BootReceiver.PREF_AUTO_CHECK, true)
        val intervalHours = prefs.getString(BootReceiver.PREF_CHECK_INTERVAL, "6")
            ?.toLongOrNull() ?: 6L

        if (autoCheck) {
            VersionCheckWorker.schedulePeriodicCheck(this, intervalHours)
            Log.i(TAG, "自动检查已启用，间隔: ${intervalHours}小时")
        } else {
            VersionCheckWorker.cancelPeriodicCheck(this)
            Log.i(TAG, "自动检查已禁用")
        }
    }
}
