package com.swupdater.service

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.*
import com.swupdater.network.VersionCheckService
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VersionCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CheckWorker"
        const val WORK_NAME = "version_check_periodic"
        const val KEY_VERSION_FOUND = "version_found"
        const val KEY_LATEST_VERSION = "latest_version"
        const val KEY_CURRENT_VERSION = "current_version"

        fun getDefaultPrefs(context: Context) =
            PreferenceManager.getDefaultSharedPreferences(context)

        fun schedulePeriodicCheck(context: Context, intervalHours: Long = 6) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<VersionCheckWorker>(
                intervalHours, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )

            AppLog.i(TAG, "已调度定期检查任务，间隔: ${intervalHours}h")
        }

        fun cancelPeriodicCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            AppLog.i(TAG, "已取消定期检查任务")
        }

        fun scheduleOneTimeCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeWork = OneTimeWorkRequestBuilder<VersionCheckWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeWork)
            AppLog.i(TAG, "已调度一次性检查任务")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            AppLog.section(TAG, "后台版本检查开始")

            val packageName = inputData.getString("package_name")
                ?: AppInfoUtil.PACKAGE_NAME_CN

            val installedInfo = AppInfoUtil.getInstalledAppInfo(applicationContext, packageName)
            AppLog.i(TAG, "本地版本: ${if (installedInfo.isInstalled) installedInfo.versionName else "未安装"}")

            val versionCheckService = VersionCheckService()
            val latestVersion = versionCheckService.checkLatestVersion(applicationContext)

            if (latestVersion == null) {
                AppLog.w(TAG, "无法获取最新版本信息，检查失败")
                return@withContext Result.failure()
            }

            val hasUpdate = if (!installedInfo.isInstalled) {
                AppLog.i(TAG, "游戏未安装，视为有更新")
                true
            } else {
                AppInfoUtil.isNewerVersion(latestVersion.versionName, installedInfo.versionName)
            }

            val outputData = workDataOf(
                KEY_VERSION_FOUND to hasUpdate,
                KEY_LATEST_VERSION to latestVersion.versionName,
                KEY_CURRENT_VERSION to (if (installedInfo.isInstalled) installedInfo.versionName else "未安装")
            )

            if (hasUpdate) {
                AppLog.i(TAG, "发现新版本: ${latestVersion.versionName} (当前: ${installedInfo.versionName ?: "未安装"})")

                val prefs = getDefaultPrefs(applicationContext)
                val autoDownload = prefs.getBoolean("pref_auto_download", false)
                val wifiOnly = prefs.getBoolean("pref_wifi_only", true)

                val canAutoDownload = if (autoDownload) {
                    if (wifiOnly) {
                        val isWifi = DownloadService.isWifiConnected(applicationContext)
                        AppLog.i(TAG, "自动下载: 开启, 仅WiFi: ${if (isWifi) "是" else "否"}")
                        isWifi
                    } else {
                        AppLog.i(TAG, "自动下载: 开启, 不限网络类型")
                        true
                    }
                } else {
                    AppLog.i(TAG, "自动下载: 未开启，仅发送通知")
                    false
                }

                if (canAutoDownload && latestVersion.downloadUrl.isNotEmpty()) {
                    AppLog.i(TAG, "满足自动下载条件，启动下载: ${latestVersion.versionName}")
                    DownloadService.start(applicationContext, latestVersion.downloadUrl, latestVersion.versionName)
                } else {
                    AppLog.i(TAG, "不满足自动下载条件，发送更新通知")
                    notifyUpdate(latestVersion.versionName, if (installedInfo.isInstalled) installedInfo.versionName else null)
                }
            } else {
                AppLog.i(TAG, "已是最新版本: ${installedInfo.versionName}")
            }

            Result.success(outputData)
        } catch (e: Exception) {
            AppLog.e(TAG, "后台版本检查异常: ${e.message}")
            Result.retry()
        }
    }

    private fun notifyUpdate(latestVersion: String, currentVersion: String?) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

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
            .setContentTitle("魔灵召唤 - 发现新版本")
            .setContentText("最新版本: $latestVersion (当前: ${currentVersion ?: "未安装"})")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
        AppLog.i(TAG, "已发送更新通知: v$currentVersion → v$latestVersion")
    }
}
