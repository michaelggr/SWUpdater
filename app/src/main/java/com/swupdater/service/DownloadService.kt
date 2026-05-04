package com.swupdater.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
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
                AppLog.i(TAG, "已有下载任务进行中，跳过")
                return
            }
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_URL, url)
                putExtra(EXTRA_VERSION_NAME, versionName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun isWifiConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = cm.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected &&
                        networkInfo.type == ConnectivityManager.TYPE_WIFI
            }
        }

        private const val TAG = "DownloadSvc"
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
                // Android 14+ 必须指定前台服务类型
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, initialNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }

                isDownloading = true
                AppLog.section(TAG, "前台下载服务启动")
                AppLog.i(TAG, "版本: $versionName, URL: ${url.take(80)}...")
                startDownload(url, versionName)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                DownloadManager.cancelDownload()
                isDownloading = false
                AppLog.w(TAG, "用户取消下载")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(url: String, versionName: String) {
        val cleared = FileUtil.clearDownloadCache(this)
        if (cleared > 0) {
            AppLog.i(TAG, "已清理 $cleared 个旧安装包")
        }

        val targetFile = FileUtil.getApkFile(this, versionName)
        AppLog.i(TAG, "下载目标: ${targetFile.name}")
        DownloadManager.startDownload(url, targetFile, serviceScope)

        progressJob?.cancel()
        progressJob = serviceScope.launch {
            DownloadManager.progress.collect { progress ->
                DownloadNotificationHelper.updateProgress(this@DownloadService, progress)

                when (progress.state) {
                    DownloadState.VERIFIED -> {
                        isDownloading = false
                        AppLog.i(TAG, "下载校验通过，准备安装")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(false)
                        }

                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@DownloadService)
                        val rootAutoInstall = prefs.getBoolean("pref_root_auto_install", false)
                        val isRooted = RootInstallHelper.isDeviceRooted()

                        if (rootAutoInstall && isRooted && progress.filePath.isNotEmpty()) {
                            AppLog.i(TAG, "Root 自动安装模式，开始静默安装")
                            DownloadNotificationHelper.updateProgress(this@DownloadService,
                                progress.copy(state = DownloadState.INSTALLING))

                            serviceScope.launch(Dispatchers.IO) {
                                val result = RootInstallHelper.installSilently(progress.filePath)
                                if (result.success) {
                                    AppLog.i(TAG, "Root 自动安装成功")
                                    DownloadNotificationHelper.showInstallCompleteNotification(this@DownloadService)
                                    val apkFile = java.io.File(progress.filePath)
                                    if (apkFile.exists()) apkFile.delete()
                                    AppLog.i(TAG, "安装包已清理: ${apkFile.name}")
                                    // 根据设置决定是否自动启动游戏
                                    val autoLaunch = PreferenceManager
                                        .getDefaultSharedPreferences(this@DownloadService)
                                        .getBoolean("pref_auto_launch_game", true)
                                    if (autoLaunch) {
                                        AppLog.i(TAG, "自动启动游戏已开启，正在启动...")
                                        com.swupdater.util.AppInfoUtil.launchGame(this@DownloadService)
                                    }
                                } else {
                                    AppLog.e(TAG, "Root 自动安装失败: ${result.message}")
                                    DownloadNotificationHelper.showInstallFailedNotification(this@DownloadService, result.message)
                                }
                                stopSelf()
                            }
                        } else {
                            AppLog.i(TAG, "等待用户手动安装，显示通知")
                            DownloadNotificationHelper.updateProgress(this@DownloadService, progress)
                            stopSelf()
                        }
                    }
                    DownloadState.DOWNLOADED,
                    DownloadState.FAILED,
                    DownloadState.VERIFY_FAILED -> {
                        isDownloading = false
                        if (progress.state == DownloadState.FAILED) {
                            AppLog.e(TAG, "下载失败")
                        } else if (progress.state == DownloadState.VERIFY_FAILED) {
                            AppLog.e(TAG, "校验失败")
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        AppLog.i(TAG, "下载服务已销毁")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
