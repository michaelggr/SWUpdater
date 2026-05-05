package com.swupdater.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.swupdater.R
import com.swupdater.receiver.BootReceiver
import com.swupdater.ui.MainActivity
import com.swupdater.util.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val NOTIFICATION_ID = 10001
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val CHANNEL_NAME = "后台保活"

        private const val PREF_KEEP_ALIVE_ENABLED = "pref_keep_alive_enabled"
        private const val PREF_BOOT_AUTO_START = "pref_boot_auto_start"
        private const val PREF_ROOT_KEEP_ALIVE = "pref_root_keep_alive"

        private const val HEARTBEAT_INTERVAL_MINUTES = 15L

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            AppLog.i(TAG, "保活服务已请求启动")
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
            AppLog.i(TAG, "保活服务已请求停止")
        }

        fun isEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PREF_KEEP_ALIVE_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putBoolean(PREF_KEEP_ALIVE_ENABLED, enabled).apply()

            if (enabled) {
                start(context)
            } else {
                stop(context)
            }
        }

        fun isBootAutoStartEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PREF_BOOT_AUTO_START, true)
        }

        fun setBootAutoStartEnabled(context: Context, enabled: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putBoolean(PREF_BOOT_AUTO_START, enabled).apply()
        }

        fun isRootKeepAliveEnabled(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PREF_ROOT_KEEP_ALIVE, false)
        }

        fun setRootKeepAliveEnabled(context: Context, enabled: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().putBoolean(PREF_ROOT_KEEP_ALIVE, enabled).apply()
        }

        fun requestIgnoreBatteryOptimization(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        AppLog.i(TAG, "已请求忽略电池优化")
                        return true
                    } catch (e: Exception) {
                        AppLog.e(TAG, "请求忽略电池优化失败: ${e.message}")
                    }
                }
            }
            return false
        }

        fun isIgnoringBatteryOptimization(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }
            return true
        }

        fun isDeviceRooted(): Boolean {
            return com.swupdater.util.RootInstallHelper.isDeviceRooted()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatExecutor: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.section(TAG, "保活服务创建")

        createNotificationChannel()
        startForegroundCompat()
        acquireWakeLock()
        startHeartbeat()

        if (isRootKeepAliveEnabled(this)) {
            applyRootKeepAlive()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "保活服务 onStartCommand")

        startForegroundCompat()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoCheck = prefs.getBoolean(BootReceiver.PREF_AUTO_CHECK, true)
        if (autoCheck) {
            val intervalHours = prefs.getString(BootReceiver.PREF_CHECK_INTERVAL, "6")?.toLongOrNull() ?: 6L
            VersionCheckWorker.schedulePeriodicCheck(this, intervalHours)
            AppLog.i(TAG, "已确保定期检查任务调度，间隔: ${intervalHours}h")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i(TAG, "保活服务销毁")

        releaseWakeLock()
        stopHeartbeat()

        if (isEnabled(this)) {
            AppLog.w(TAG, "保活仍启用，尝试重启服务")
            try {
                val restartIntent = Intent(this, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "重启服务失败: ${e.message}")
            }
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持应用在后台运行，自动检查游戏更新"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("魔灵召唤 · 自动更新")
            .setContentText("正在后台运行，自动检查更新")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SWUpdater::KeepAliveWakeLock"
            ).apply {
                acquire(6 * 60 * 60 * 1000L)
            }
            AppLog.i(TAG, "WakeLock 已获取，最长持有 6h")
        } catch (e: Exception) {
            AppLog.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    AppLog.i(TAG, "WakeLock 已释放")
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "释放 WakeLock 失败: ${e.message}")
        }
    }

    private fun startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutor?.scheduleAtFixedRate({
            try {
                wakeLock?.let {
                    if (!it.isHeld) {
                        it.acquire(6 * 60 * 60 * 1000L)
                    }
                }

                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification())

                AppLog.d(TAG, "心跳检测正常")
            } catch (e: Exception) {
                AppLog.e(TAG, "心跳异常: ${e.message}")
            }
        }, HEARTBEAT_INTERVAL_MINUTES, HEARTBEAT_INTERVAL_MINUTES, TimeUnit.MINUTES)

        AppLog.i(TAG, "心跳机制已启动，间隔: ${HEARTBEAT_INTERVAL_MINUTES}min")
    }

    private fun stopHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
        AppLog.i(TAG, "心跳机制已停止")
    }

    private fun applyRootKeepAlive() {
        if (!isDeviceRooted()) {
            AppLog.w(TAG, "设备未 Root，无法使用 Root 保活")
            return
        }

        try {
            val commands = arrayOf(
                "echo 0 > /proc/self/oom_score_adj",
                "renice -20 ${android.os.Process.myPid()}"
            )

            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    AppLog.i(TAG, "Root 命令执行成功: $cmd")
                } else {
                    AppLog.w(TAG, "Root 命令执行失败: $cmd, exitCode=$exitCode")
                }
            }
            AppLog.i(TAG, "Root 保活配置完成 (OOM优先级 + 进程优先级)")
        } catch (e: Exception) {
            AppLog.e(TAG, "Root 保活失败: ${e.message}")
        }
    }
}
