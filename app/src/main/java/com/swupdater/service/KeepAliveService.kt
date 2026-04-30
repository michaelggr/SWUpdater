package com.swupdater.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.swupdater.R
import com.swupdater.receiver.BootReceiver
import com.swupdater.ui.MainActivity
import com.swupdater.util.AppInfoUtil
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 后台保活服务
 *
 * 采用多种保护方式确保应用在后台持续运行：
 * 1. 前台服务通知栏 — 提升进程优先级，防止被系统回收
 * 2. WakeLock — 防止 CPU 休眠
 * 3. 电量优化白名单 — 防止 Doze 模式限制
 * 4. 开机自启动 — 设备重启后自动启动服务
 * 5. 定时心跳 — 周期性检查服务状态
 * 6. Root 保活 — 如设备已 Root，使用 Root 权限保活
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 10001
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val CHANNEL_NAME = "后台保活"

        // SharedPreferences
        private const val PREFS_NAME = "sw_updater_prefs"
        private const val PREF_KEEP_ALIVE_ENABLED = "pref_keep_alive_enabled"
        private const val PREF_BOOT_AUTO_START = "pref_boot_auto_start"
        private const val PREF_ROOT_KEEP_ALIVE = "pref_root_keep_alive"
        private const val PREF_BATTERY_OPTIMIZATION = "pref_battery_optimization"

        // 心跳间隔（分钟）
        private const val HEARTBEAT_INTERVAL_MINUTES = 15L

        /**
         * 启动保活服务
         */
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "保活服务已请求启动")
        }

        /**
         * 停止保活服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
            Log.i(TAG, "保活服务已请求停止")
        }

        /**
         * 是否启用保活
         */
        fun isEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEEP_ALIVE_ENABLED, false)
        }

        /**
         * 设置是否启用保活
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_KEEP_ALIVE_ENABLED, enabled).apply()

            if (enabled) {
                start(context)
            } else {
                stop(context)
            }
        }

        /**
         * 是否启用开机自启
         */
        fun isBootAutoStartEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_BOOT_AUTO_START, true)
        }

        /**
         * 设置是否开机自启
         */
        fun setBootAutoStartEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_BOOT_AUTO_START, enabled).apply()
        }

        /**
         * 是否启用Root保活
         */
        fun isRootKeepAliveEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_ROOT_KEEP_ALIVE, false)
        }

        /**
         * 设置是否启用Root保活
         */
        fun setRootKeepAliveEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_ROOT_KEEP_ALIVE, enabled).apply()
        }

        /**
         * 请求忽略电池优化
         */
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
                        Log.i(TAG, "已请求忽略电池优化")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "请求忽略电池优化失败: ${e.message}")
                    }
                }
            }
            return false
        }

        /**
         * 检查是否已忽略电池优化
         */
        fun isIgnoringBatteryOptimization(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }
            return true
        }

        /**
         * 检查设备是否已Root
         */
        fun isDeviceRooted(): Boolean {
            // 检查 su 命令是否可用
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
                val input = process.inputStream.bufferedReader().readText()
                process.waitFor()
                input.isNotEmpty()
            } catch (e: Exception) {
                // 检查常见 Root 路径
                val paths = listOf(
                    "/system/bin/su",
                    "/system/xbin/su",
                    "/sbin/su",
                    "/data/local/xbin/su",
                    "/data/local/bin/su"
                )
                paths.any { java.io.File(it).exists() }
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatExecutor: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "保活服务创建")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        startForeground(NOTIFICATION_ID, buildNotification())

        // 获取 WakeLock
        acquireWakeLock()

        // 启动心跳
        startHeartbeat()

        // Root 保活
        if (isRootKeepAliveEnabled(this)) {
            applyRootKeepAlive()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "保活服务启动")

        // 如果被系统杀死后重启，确保通知存在
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // 确保自动检查任务已调度
        val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val autoCheck = prefs.getBoolean(BootReceiver.PREF_AUTO_CHECK, true)
        if (autoCheck) {
            val intervalHours = prefs.getString(BootReceiver.PREF_CHECK_INTERVAL, "6")?.toLongOrNull() ?: 6L
            VersionCheckWorker.schedulePeriodicCheck(this, intervalHours)
        }

        return START_STICKY  // 被杀后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null  // 不支持绑定
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "保活服务销毁")

        // 释放 WakeLock
        releaseWakeLock()

        // 停止心跳
        stopHeartbeat()

        // 如果保活仍启用，尝试重启服务
        if (isEnabled(this)) {
            Log.i(TAG, "保活仍启用，尝试重启服务")
            try {
                val restartIntent = Intent(this, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "重启服务失败: ${e.message}")
            }
        }
    }

    // ============================================================
    //  通知栏
    // ============================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW  // 低优先级，不发出声音
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
        // 点击通知打开主界面
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
            .setOngoing(true)  // 不可滑动删除
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ============================================================
    //  WakeLock
    // ============================================================

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SWUpdater::KeepAliveWakeLock"
            ).apply {
                acquire(6 * 60 * 60 * 1000L)  // 最多6小时，定期续期
            }
            Log.i(TAG, "WakeLock 已获取")
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WakeLock 已释放")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败: ${e.message}")
        }
    }

    // ============================================================
    //  心跳机制
    // ============================================================

    private fun startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
        heartbeatExecutor?.scheduleAtFixedRate({
            try {
                // 续期 WakeLock
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                    it.acquire(6 * 60 * 60 * 1000L)
                }

                // 更新通知
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification())

                Log.d(TAG, "心跳检测正常")
            } catch (e: Exception) {
                Log.e(TAG, "心跳异常: ${e.message}")
            }
        }, HEARTBEAT_INTERVAL_MINUTES, HEARTBEAT_INTERVAL_MINUTES, TimeUnit.MINUTES)

        Log.i(TAG, "心跳机制已启动，间隔: ${HEARTBEAT_INTERVAL_MINUTES}分钟")
    }

    private fun stopHeartbeat() {
        heartbeatExecutor?.shutdownNow()
        heartbeatExecutor = null
        Log.i(TAG, "心跳机制已停止")
    }

    // ============================================================
    //  Root 保活
    // ============================================================

    private fun applyRootKeepAlive() {
        if (!isDeviceRooted()) {
            Log.w(TAG, "设备未Root，无法使用Root保活")
            return
        }

        try {
            // 使用 su 命令设置进程为不可杀
            val commands = arrayOf(
                "echo 0 > /proc/self/oom_score_adj",  // 降低 OOM 优先级
                "renice -20 ${android.os.Process.myPid()}"  // 提高进程优先级
            )

            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i(TAG, "Root 命令执行成功: $cmd")
                } else {
                    Log.w(TAG, "Root 命令执行失败: $cmd, exitCode=$exitCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root 保活失败: ${e.message}")
        }
    }
}
