package com.swupdater.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.swupdater.R
import com.swupdater.ui.MainActivity
import com.swupdater.util.AppLog
import com.swupdater.util.RootInstallHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        private const val NOTIFICATION_ID = 20001
        private const val CHANNEL_ID = "capture_channel"
        private const val CHANNEL_NAME = "配置抓取"

        private const val ACTION_START = "com.swupdater.action.START_CAPTURE"
        private const val ACTION_STOP = "com.swupdater.action.STOP_CAPTURE"

        private const val PREFS_NAME = "sw_updater_capture"
        private const val PREF_CAPTURE_RUNNING = "capture_running"
        private const val PREF_AUTO_STOP = "capture_auto_stop"
        private const val PREF_KEEP_CERT = "capture_keep_cert"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isAutoStopEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_AUTO_STOP, true)
        }

        fun setAutoStopEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_AUTO_STOP, enabled).apply()
        }

        fun isKeepCertEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(PREF_KEEP_CERT, false)
        }

        fun setKeepCertEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_KEEP_CERT, enabled).apply()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxyServer: CaptureProxyServer? = null
    private var parser: GameDataParser? = null
    private var captureJob: Job? = null

    private val capturedData = mutableMapOf<String, Any?>()
    @Volatile
    private var hasGameData = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cleanupCapture()
    }

    private fun startCapture() {
        if (isRunning) return

        if (!RootInstallHelper.isDeviceRooted()) {
            AppLog.e(TAG, "设备未 Root，无法启动抓取")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在启动抓取服务..."))
        isRunning = true

        serviceScope.launch {
            try {
                // 初始化证书管理器
                CertificateManager.initialize(this@CaptureService)

                // 安装 CA 证书到系统目录
                if (!CertificateManager.isCaInstalledInSystem(this@CaptureService)) {
                    val installed = CertificateManager.installCaToSystem(this@CaptureService)
                    if (!installed) {
                        AppLog.e(TAG, "CA 证书安装失败")
                        updateNotification("CA 证书安装失败")
                        stopCapture()
                        return@launch
                    }
                    AppLog.i(TAG, "CA 证书安装成功")
                }

                // 初始化数据解析器
                parser = GameDataParser().apply {
                    onDataParsed = { command, data ->
                        onGameDataParsed(command, data)
                    }
                }

                // 启动代理服务器
                proxyServer = CaptureProxyServer(8080, CertificateManager, parser!!).apply {
                    onGameTrafficDetected = { hostname ->
                        AppLog.d(TAG, "检测到游戏流量: $hostname")
                    }
                }

                val started = proxyServer!!.start()
                if (!started) {
                    AppLog.e(TAG, "代理服务器启动失败")
                    updateNotification("代理服务器启动失败")
                    stopCapture()
                    return@launch
                }

                // 设置 iptables 规则
                val gameUid = IptablesManager.getGameUid()
                val redirectOk = IptablesManager.setupRedirect(8080, gameUid)
                if (!redirectOk) {
                    AppLog.e(TAG, "iptables 规则设置失败")
                    updateNotification("流量重定向设置失败")
                    stopCapture()
                    return@launch
                }

                AppLog.i(TAG, "抓取服务启动成功")
                updateNotification("抓取服务运行中，启动游戏即可抓取配置")

                // 启动监控循环
                startMonitoring()

            } catch (e: Exception) {
                Log.e(TAG, "抓取服务启动异常", e)
                AppLog.e(TAG, "抓取服务启动异常: ${e.message}")
                updateNotification("启动异常: ${e.message}")
                stopCapture()
            }
        }
    }

    private fun startMonitoring() {
        captureJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                if (hasGameData && isAutoStopEnabled(this@CaptureService)) {
                    AppLog.i(TAG, "已捕获游戏数据，自动停止抓取")
                    saveCapturedData()
                    stopCapture()
                    break
                }
            }
        }
    }

    @Synchronized
    private fun onGameDataParsed(command: String, data: Map<String, Any?>) {
        hasGameData = true
        capturedData[command] = data

        val summary = when (command) {
            "HubUserLogin" -> "登录数据"
            "HubUnitList" -> "魔灵列表 (${(data["count"] as? Int) ?: "?"}个)"
            "HubUserRunes", "HubGetRuneList" -> "符文列表 (${(data["count"] as? Int) ?: "?"}个)"
            "HubGetArtifactList" -> "遗物列表 (${(data["count"] as? Int) ?: "?"}个)"
            else -> command
        }

        AppLog.i(TAG, "已抓取: $summary")
        updateNotification("已抓取: $summary")
    }

    private fun saveCapturedData() {
        if (capturedData.isEmpty()) return

        val repository = CaptureRepository
        val file = repository.saveCapture(this, capturedData.toMap())
        if (file != null) {
            AppLog.i(TAG, "抓取数据已保存: ${file.absolutePath}")
        } else {
            AppLog.e(TAG, "抓取数据保存失败")
        }
    }

    private fun stopCapture() {
        if (!isRunning) return

        serviceScope.launch {
            cleanupCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupCapture() {
        captureJob?.cancel()
        captureJob = null

        IptablesManager.cleanupRedirect()

        proxyServer?.stop()
        proxyServer = null

        if (hasGameData && !capturedData.isEmpty()) {
            saveCapturedData()
        }

        if (!isKeepCertEnabled(this)) {
            CertificateManager.uninstallCaFromSystem(this)
        }

        parser?.reset()
        parser = null
        capturedData.clear()
        hasGameData = false
        isRunning = false

        AppLog.i(TAG, "抓取服务已停止")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "游戏配置抓取服务状态"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("魔灵召唤 · 配置抓取")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
