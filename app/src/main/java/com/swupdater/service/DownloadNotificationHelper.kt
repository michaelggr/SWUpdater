package com.swupdater.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.swupdater.R
import com.swupdater.model.DownloadProgress
import com.swupdater.model.DownloadState
import com.swupdater.receiver.NotificationInstallReceiver
import com.swupdater.ui.MainActivity
import com.swupdater.util.FileUtil

/**
 * 下载通知助手
 *
 * 管理下载进度通知栏显示，供 ViewModel 和 DownloadService 共用
 * - 下载完成时显示"安装"按钮
 * - 安装完成时显示"安装完成"通知
 * - 安装失败时显示"安装失败"通知
 */
object DownloadNotificationHelper {

    private const val CHANNEL_ID = "download_channel"
    private const val NOTIFICATION_ID = 1001

    // 安装完成通知使用独立 ID，避免被下载进度通知覆盖
    private const val INSTALL_COMPLETE_NOTIFICATION_ID = 1002

    /**
     * 确保通知渠道已创建
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "游戏更新下载进度通知"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 根据下载进度更新通知
     */
    fun updateProgress(context: Context, progress: DownloadProgress) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // IDLE 时不发通知
        if (progress.state == DownloadState.IDLE) return

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = when (progress.state) {
            DownloadState.DOWNLOADING -> {
                val detail = "${FileUtil.formatFileSize(progress.downloadedBytes)} / ${FileUtil.formatFileSize(progress.totalBytes)}  ${FileUtil.formatSpeed(progress.speed)}"
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title))
                    .setContentText(detail)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, progress.progressPercent, false)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build()
            }
            DownloadState.VERIFYING -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title))
                    .setContentText(context.getString(R.string.verifying_integrity))
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(100, 100, true)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build()
            }
            DownloadState.INSTALLING -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title))
                    .setContentText(context.getString(R.string.notification_installing))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setProgress(100, 100, true)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build()
            }
            DownloadState.VERIFIED -> {
                // 下载完成，显示"安装"按钮
                val installIntent = NotificationInstallReceiver.createInstallIntent(context, progress.filePath)
                val installPendingIntent = PendingIntent.getBroadcast(
                    context, 0, installIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title))
                    .setContentText(context.getString(R.string.download_complete_notification))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .addAction(
                        android.R.drawable.ic_menu_save,
                        context.getString(R.string.btn_install),
                        installPendingIntent
                    )
                    .build()
            }
            DownloadState.DOWNLOADED -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title))
                    .setContentText(context.getString(R.string.status_downloaded))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()
            }
            DownloadState.FAILED, DownloadState.VERIFY_FAILED -> {
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.download_notification_title))
                    .setContentText(context.getString(R.string.notification_download_failed))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build()
            }
            else -> return
        }

        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 取消下载通知
     */
    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    /**
     * 创建初始前台通知（供 DownloadService.startForeground 使用）
     */
    fun createInitialNotification(context: Context): Notification {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(context.getString(R.string.notification_preparing_download))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * 显示 Root 自动安装成功通知
     */
    fun showInstallCompleteNotification(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(context.getString(R.string.install_complete_notification))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // 使用安装完成专用 ID，避免覆盖下载进度通知
        manager.notify(INSTALL_COMPLETE_NOTIFICATION_ID, notification)
        // 取消下载进度通知
        manager.cancel(NOTIFICATION_ID)
    }

    /**
     * 显示 Root 自动安装失败通知
     */
    fun showInstallFailedNotification(context: Context, error: String?) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_notification_title))
            .setContentText(context.getString(R.string.notification_auto_install_failed, if (!error.isNullOrEmpty()) ": $error" else ""))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
