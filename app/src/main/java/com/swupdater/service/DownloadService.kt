﻿package com.swupdater.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.swupdater.model.DownloadState
import com.swupdater.network.DownloadManager
import com.swupdater.util.AppLog
import com.swupdater.util.FileUtil
import com.swupdater.util.RootInstallHelper
import kotlinx.coroutines.*

/**
 * 下载前台服务
 *
 * 用于后台自动下载（由 VersionCheckWorker 触发）和前台手动下载
 * - 前台运行确保下载不被系统回收
 * - 通知栏实时显示下载进度（大小、速度、百分比）
 * - 下载完成后通知可点击跳转
 * - 与 ViewModel 共享 DownloadManager 单例，UI 和通知栏进度同步
 */
class DownloadService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.swupdater.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.swupdater.action.CANCEL_DOWNLOAD"

        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_VERSION_NAME = "version_name"

        /** 标记是否正在下载，防止重复启动 */
        @Volatile
        var isDownloading = false
            private set

        /**
         * 启动下载服务
         */
        fun start(context: Context, url: String, versionName: String) {
            if (isDownloading) {
                AppLog.i("DownloadService", "已有下载任务进行中，跳过")
                return
            }
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_URL, url)
                putExtra(EXTRA_VERSION_NAME, versionName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 检查当前是否连接了 WiFi
         */
        @Suppress("DEPRECATION")
        fun isWifiConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = cm.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected &&
                    networkInfo.type == ConnectivityManager.TYPE_WIFI
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
                val versionName = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "unknown"

                // 先创建前台通知（Android 8+ 必须在 5 秒内调用）
                val initialNotification = DownloadNotificationHelper.createInitialNotification(this)
                startForeground(NOTIFICATION_ID, initialNotification)

                isDownloading = true
                startDownload(url, versionName)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                DownloadManager.cancelDownload()
                isDownloading = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(url: String, versionName: String) {
        // 先清除旧安装包
        val cleared = FileUtil.clearDownloadCache(this)
        if (cleared > 0) {
            AppLog.i("DownloadService", "已清除 $cleared 个旧安装包")
        }

        val targetFile = FileUtil.getApkFile(this, versionName)
        AppLog.i("DownloadService", "开始下载 APK: $url → ${targetFile.absolutePath}")
        DownloadManager.startDownload(url, targetFile, serviceScope)

        // 监听进度更新通知（只启动一个 collect）
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            DownloadManager.progress.collect { progress ->
                DownloadNotificationHelper.updateProgress(this@DownloadService, progress)

                when (progress.state) {
                    DownloadState.VERIFIED -> {
                        isDownloading = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(false)
                        }

                        // 校验通过后，检查是否需要 Root 自动安装
                        val prefs = getSharedPreferences("sw_updater_prefs", Context.MODE_PRIVATE)
                        val rootAutoInstall = prefs.getBoolean("pref_root_auto_install", false)
                        if (rootAutoInstall && RootInstallHelper.isDeviceRooted() && progress.filePath.isNotEmpty()) {
                            AppLog.i("DownloadService", "Root 自动安装已开启，开始静默安装...")
                            // 更新通知：正在安装
                            DownloadNotificationHelper.updateProgress(this@DownloadService,
                                progress.copy(state = DownloadState.INSTALLING))

                            // 在 IO 线程执行 Root 安装，安装完成后再 stopSelf
                            serviceScope.launch(Dispatchers.IO) {
                                val result = RootInstallHelper.installSilently(progress.filePath)
                                if (result.success) {
                                    AppLog.i("DownloadService", "Root 自动安装成功")
                                    DownloadNotificationHelper.showInstallCompleteNotification(this@DownloadService)
                                    // 安装完成，删除安装包
                                    val apkFile = java.io.File(progress.filePath)
                                    if (apkFile.exists()) apkFile.delete()
                                    AppLog.i("DownloadService", "安装包已删除")
                                } else {
                                    AppLog.e("DownloadService", "Root 自动安装失败: ${result.message}")
                                    DownloadNotificationHelper.showInstallFailedNotification(this@DownloadService, result.message)
                                }
                                // 安装完成后才停止服务
                                stopSelf()
                            }
                        } else {
                            // 非 Root 或未开启自动安装，显示完成通知，用户手动点击安装
                            DownloadNotificationHelper.updateProgress(this@DownloadService, progress)
                            stopSelf()
                        }
                    }
                    DownloadState.DOWNLOADED,
                    DownloadState.FAILED,
                    DownloadState.VERIFY_FAILED -> {
                        isDownloading = false
                        // 下载完成时通知媒体库扫描，使 APK 在文件管理器中可见
                        if (progress.state == DownloadState.DOWNLOADED && progress.filePath.isNotEmpty()) {
                            FileUtil.notifyFileScanned(this@DownloadService, java.io.File(progress.filePath))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(false)
                        }
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        serviceScope.cancel()
        isDownloading = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
