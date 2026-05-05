package com.swupdater

import android.app.Application
import androidx.preference.PreferenceManager
import com.swupdater.receiver.BootReceiver
import com.swupdater.service.KeepAliveService
import com.swupdater.service.VersionCheckWorker
import com.swupdater.util.AppLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SWUpdaterApp : Application() {

    companion object {
        private const val TAG = "SWUpdaterApp"
    }

    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(Thread.getDefaultUncaughtExceptionHandler()))

        super.onCreate()

        AppLog.init(this)

        AppLog.section(TAG, "魔灵召唤 · 自动更新 启动 v2.6.2")

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

    private inner class CrashHandler(private val defaultHandler: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.close()
                val stackTrace = sw.toString()

                android.util.Log.e("CrashHandler", "未捕获异常", throwable)

                val dir = File(filesDir, "crash")
                if (!dir.exists()) dir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val crashFile = File(dir, "crash_$timestamp.log")
                crashFile.writeText("Time: ${Date()}\nThread: ${thread.name}\n\n$stackTrace")
            } catch (_: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
