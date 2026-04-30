package com.swupdater.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.swupdater.R
import com.swupdater.model.AppInstallInfo
import com.swupdater.network.VersionCheckService
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 定期版本检查 Worker
 *
 * 使用 WorkManager 实现后台定期检查更新。
 * 根据用户设置：
 * - 发现新版本时发送通知
 * - 如果开启了自动下载且在 WiFi 下，自动启动下载
 */
class VersionCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "VersionCheckWorker"
        const val WORK_NAME = "version_check_periodic"
        const val KEY_VERSION_FOUND = "version_found"
        const val KEY_LATEST_VERSION = "latest_version"
        const val KEY_CURRENT_VERSION = "current_version"

        const val PREFS_NAME = "sw_updater_prefs"

        /**
         * 调度定期检查任务
         * @param intervalHours 检查间隔（小时）
         */
        fun schedulePeriodicCheck(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<VersionCheckWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )

            Log.i(TAG, "已调度定期检查任务，间隔: ${intervalHours}小时")
        }

        /**
         * 取消定期检查
         */
        fun cancelPeriodicCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * 执行一次性检查
         */
        const val ONE_TIME_WORK_NAME = "version_check_onetime"

        fun scheduleOneTimeCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeWork = OneTimeWorkRequestBuilder<VersionCheckWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                oneTimeWork
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            AppLog.i(TAG, "开始执行后台版本检查...")

            val packageName = inputData.getString("package_name")
                ?: AppInfoUtil.PACKAGE_NAME_CN

            // 获取本地版本
            val installedInfo = AppInfoUtil.getInstalledAppInfo(applicationContext, packageName)

            // 获取远程版本 — 传入 applicationContext 以读取设置中的数据源URL
            val versionCheckService = VersionCheckService()
            val latestVersion = versionCheckService.checkLatestVersion(applicationContext)

            if (latestVersion == null) {
                AppLog.w(TAG, "无法获取最新版本信息")
                return@withContext Result.failure()
            }

            val hasUpdate = if (!installedInfo.isInstalled) {
                true // 未安装，视为有更新
            } else {
                AppInfoUtil.isNewerVersion(latestVersion.versionName, installedInfo.versionName)
            }

            val outputData = workDataOf(
                KEY_VERSION_FOUND to hasUpdate,
                KEY_LATEST_VERSION to latestVersion.versionName,
                KEY_CURRENT_VERSION to (if (installedInfo.isInstalled) installedInfo.versionName else "未安装")
            )

            if (hasUpdate) {
                AppLog.i(TAG, "发现新版本: ${latestVersion.versionName}")

                // 读取用户设置
                val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val autoDownload = prefs.getBoolean("pref_auto_download", false)
                val wifiOnly = prefs.getBoolean("pref_wifi_only", true)

                // 判断是否可以自动下载
                val canAutoDownload = if (autoDownload) {
                    if (wifiOnly) {
                        val isWifi = DownloadService.isWifiConnected(applicationContext)
                        AppLog.i(TAG, "自动下载: 开启, 仅WiFi: $isWifi")
                        isWifi
                    } else {
                        AppLog.i(TAG, "自动下载: 开启, 不限网络")
                        true
                    }
                } else {
                    AppLog.i(TAG, "自动下载: 未开启，仅发送通知")
                    false
                }

                if (canAutoDownload && latestVersion.downloadUrl.isNotEmpty()) {
                    // 自动下载：启动 DownloadService
                    AppLog.i(TAG, "WiFi 下自动下载: ${latestVersion.versionName}")
                    DownloadService.start(applicationContext, latestVersion.downloadUrl, latestVersion.versionName)
                    // 下载通知由 DownloadService 管理，不再发送更新提醒通知
                } else {
                    // 不自动下载：发送更新提醒通知
                    notifyUpdate(latestVersion.versionName, if (installedInfo.isInstalled) installedInfo.versionName else null)
                }
            } else {
                AppLog.i(TAG, "已是最新版本")
            }

            Result.success(outputData)
        } catch (e: Exception) {
            AppLog.e(TAG, "版本检查失败: ${e.message}")
            Result.retry()
        }
    }

    private fun notifyUpdate(latestVersion: String, currentVersion: String?) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

        // 创建通知渠道
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "update_channel",
                "更新提醒",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = android.content.Intent(applicationContext, com.swupdater.ui.MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(applicationContext, "update_channel")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.notification_update_found))
            .setContentText(applicationContext.getString(R.string.notification_update_text, latestVersion, currentVersion ?: "未安装"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
    }
}
