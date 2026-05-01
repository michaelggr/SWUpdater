package com.swupdater.service

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

class DownloadService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.swupdater.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.swupdater.action.CANCEL_DOWNLOAD"

        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_VERSION_NAME = "version_name"

        @Volatile
        var isDownloading = false
            private set

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
        val cleared = FileUtil.clearDownloadCache(this)
        if (cleared > 0) {
            AppLog.i("DownloadService", "已清除 $cleared 个旧安装包")
        }

        val targetFile = FileUtil.getApkFile(this, versionName)
        AppLog.i("DownloadService", "开始下载 APK: $url → ${targetFile.absolutePath}")
        DownloadManager.startDownload(url, targetFile, serviceScope)

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

                        val prefs = getSharedPreferences("sw_updater_prefs", Context.MODE_PRIVATE)
                        val rootAutoInstall = prefs.getBoolean("pref_root_auto_install", false)
                        if (rootAutoInstall && RootInstallHelper.isDeviceRooted() && progress.filePath.isNotEmpty()) {
                            AppLog.i("DownloadService", "Root 自动安装已开启，开始静默安装...")
                            DownloadNotificationHelper.updateProgress(this@DownloadService,
                                progress.copy(state = DownloadState.INSTALLING))

                            serviceScope.launch(Dispatchers.IO) {
                                val result = RootInstallHelper.installSilently(progress.filePath)
                                if (result.success) {
                                    AppLog.i("DownloadService", "Root 自动安装成功")
                                    DownloadNotificationHelper.showInstallCompleteNotification(this@DownloadService)
                                    val apkFile = java.io.File(progress.filePath)
                                    if (apkFile.exists()) apkFile.delete()
                                    AppLog.i("DownloadService", "安装包已删除")
                                } else {
                                    AppLog.e("DownloadService", "Root 自动安装失败: ${result.message}")
                                    DownloadNotificationHelper.showInstallFailedNotification(this@DownloadService, result.message)
                                }
                                stopSelf()
                            }
                        } else {
                            DownloadNotificationHelper.updateProgress(this@DownloadService, progress)
                            stopSelf()
                        }
                    }
                    DownloadState.DOWNLOADED,
                    DownloadState.FAILED,
                    DownloadState.VERIFY_FAILED -> {
                        isDownloading = false
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