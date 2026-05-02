package com.swupdater.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swupdater.model.*
import com.swupdater.network.DownloadChannels
import com.swupdater.network.DownloadManager
import com.swupdater.network.VersionCheckService
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.AppLog
import com.swupdater.util.ChecksumUtil
import com.swupdater.util.FileUtil
import com.swupdater.service.DownloadNotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val versionCheckService = VersionCheckService()

    var targetPackageName: String = AppInfoUtil.PACKAGE_NAME_CN
        private set

    // 版本检查结果
    private val _checkResult = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Checking)
    val checkResult: StateFlow<UpdateCheckResult> = _checkResult

    // 当前已安装版本信息
    private val _installedInfo = MutableStateFlow<AppInstallInfo?>(null)
    val installedInfo: StateFlow<AppInstallInfo?> = _installedInfo

    // 最新版本信息
    private val _latestVersion = MutableStateFlow<VersionInfo?>(null)
    val latestVersion: StateFlow<VersionInfo?> = _latestVersion

    // 下载进度（共享 DownloadManager 单例）
    val downloadProgress: StateFlow<DownloadProgress> = DownloadManager.progress

    // 是否正在检查更新
    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking

    // 日志模式：数据源检测详情
    private val _sourceCheckResults = MutableStateFlow<List<SourceCheckResult>>(emptyList())
    val sourceCheckResults: StateFlow<List<SourceCheckResult>> = _sourceCheckResults

    // 日志文本
    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText

    init {
        refreshInstalledInfo()

        // 监听下载进度
        viewModelScope.launch {
            DownloadManager.progress.collect { progress ->
                if (progress.state == DownloadState.DOWNLOADED && progress.filePath.isNotEmpty()) {
                    // 通知媒体库扫描，使 APK 在文件管理器中可见
                    FileUtil.notifyFileScanned(getApplication(), File(progress.filePath))
                    verifyDownloadedFile(progress.filePath)
                }
            }
        }

        // 监听日志
        AppLog.addListener { entry ->
            val current = _logText.value
            val newLine = entry.toString()
            // 保持最近 200 行
            val lines = (current + "\n" + newLine).lines().takeLast(200)
            _logText.value = lines.joinToString("\n")
        }
    }

    fun refreshInstalledInfo() {
        val context = getApplication<Application>()

        // 自动检测：遍历所有可能的包名
        val detectedPackage = AppInfoUtil.detectInstalledPackageName(context)
        targetPackageName = detectedPackage

        val info = AppInfoUtil.getInstalledAppInfo(context, targetPackageName)
        _installedInfo.value = info

        if (info.isInstalled) {
            AppLog.i(TAG, "本地应用信息: installed=true, packageName=$targetPackageName, version=${info.versionName}")
        } else {
            // 输出所有包名的检测结果
            AppLog.i(TAG, "本地应用信息: installed=false, 已检测以下包名均未安装:")
            AppInfoUtil.POSSIBLE_PACKAGE_NAMES.forEach { pkg ->
                val installed = AppInfoUtil.isAppInstalled(context, pkg)
                AppLog.d(TAG, "  - $pkg: ${if (installed) "已安装" else "未安装"}")
            }
        }
    }

    fun checkUpdate() {
        if (_isChecking.value) return

        viewModelScope.launch {
            _isChecking.value = true
            _checkResult.value = UpdateCheckResult.Checking
            _sourceCheckResults.value = emptyList()

            val isLogMode = AppLog.isLogModeEnabled(getApplication())

            try {
                val context = getApplication<Application>()

                // 如果日志模式开启，执行详细检测并记录每个步骤的结果
                if (isLogMode) {
                    AppLog.i(TAG, "日志模式已开启，执行详细数据源检测...")
                    val details = versionCheckService.checkAllSourcesWithDetails(context)
                    _sourceCheckResults.value = details.map {
                        SourceCheckResult(
                            sourceName = it.sourceName,
                            success = it.success,
                            versionName = it.versionName,
                            downloadUrl = it.downloadUrl,
                            error = it.error
                        )
                    }

                    // 取最终结果
                    val finalResult = details.lastOrNull()
                    if (finalResult != null && finalResult.success && finalResult.versionName != null) {
                        val latestInfo = VersionInfo(
                            versionName = finalResult.versionName,
                            downloadUrl = finalResult.downloadUrl ?: ""
                        )
                        _latestVersion.value = latestInfo
                        compareVersions(latestInfo)
                    } else {
                        _checkResult.value = UpdateCheckResult.Error("数据源检测失败，请检查网络连接或数据源URL配置")
                    }
                } else {
                    // 标准模式：使用设置中的数据源检查
                    val latestInfo = versionCheckService.checkLatestVersion(context)
                    if (latestInfo == null) {
                        _checkResult.value = UpdateCheckResult.Error("无法获取最新版本信息，请检查网络连接或数据源URL配置")
                        return@launch
                    }
                    _latestVersion.value = latestInfo
                    compareVersions(latestInfo)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "检查更新失败: ${e.message}")
                _checkResult.value = UpdateCheckResult.Error(e.message ?: "未知错误")
            } finally {
                _isChecking.value = false
                AppLog.flushToFile(getApplication())
            }
        }
    }

    private fun compareVersions(latestInfo: VersionInfo) {
        val currentVersion = _installedInfo.value?.versionName
        if (currentVersion.isNullOrEmpty()) {
            // 游戏未安装
            _checkResult.value = UpdateCheckResult.UpdateAvailable(
                currentVersion = "未安装",
                latestVersion = latestInfo
            )
            AppLog.i(TAG, "游戏未安装，最新版本: ${latestInfo.versionName}")
        } else if (AppInfoUtil.isNewerVersion(latestInfo.versionName, currentVersion)) {
            _checkResult.value = UpdateCheckResult.UpdateAvailable(
                currentVersion = currentVersion,
                latestVersion = latestInfo
            )
            AppLog.i(TAG, "发现新版本: $currentVersion -> ${latestInfo.versionName}")
        } else {
            _checkResult.value = UpdateCheckResult.UpToDate(currentVersion)
            AppLog.i(TAG, "已是最新版本: $currentVersion")
        }
    }

    /**
     * 获取可用下载渠道列表
     */
    fun getDownloadChannels(): List<DownloadChannel> {
        val latest = _latestVersion.value
        return latest?.downloadChannels ?: DownloadChannels.getRecommendedChannels()
    }

    /**
     * 通过指定渠道下载
     * 根据渠道类型执行不同操作：
     * - OFFICIAL_WEB / APP_STORE / ACCELERATOR：浏览器打开
     * - APK_DIRECT：下载管理器下载
     */
    fun downloadViaChannel(channel: DownloadChannel) {
        AppLog.i(TAG, "选择下载渠道: ${channel.name} (${channel.type})")

        when (channel.type) {
            DownloadChannel.ChannelType.APK_DIRECT -> {
                val latest = _latestVersion.value ?: return
                val targetFile = FileUtil.getApkFile(getApplication(), latest.versionName)
                // 使用已解析的 APK 直链，而非渠道短链接
                val downloadUrl = latest.downloadUrl.ifEmpty { channel.url }
                AppLog.i(TAG, "APK 直链下载: $downloadUrl")
                DownloadManager.startDownload(downloadUrl, targetFile, viewModelScope)
            }
            DownloadChannel.ChannelType.CUSTOM -> {
                // 自定义链接可能是任意类型
                val url = channel.url
                if (url.endsWith(".apk", ignoreCase = true)) {
                    val latest = _latestVersion.value ?: return
                    val targetFile = FileUtil.getApkFile(getApplication(), latest.versionName)
                    DownloadManager.startDownload(url, targetFile, viewModelScope)
                } else {
                    openInBrowser(url)
                }
            }
            DownloadChannel.ChannelType.OFFICIAL_WEB,
            DownloadChannel.ChannelType.APP_STORE,
            DownloadChannel.ChannelType.ACCELERATOR -> {
                openInBrowser(channel.url)
            }
        }
    }

    /**
     * 直接下载 APK
     * 通过 DownloadService 前台服务下载，同时更新 UI 进度和通知栏
     */
    fun startDownload() {
        val latest = _latestVersion.value ?: return
        val downloadUrl = latest.downloadUrl
        if (downloadUrl.isEmpty()) {
            AppLog.e(TAG, "下载链接为空，请先检查更新")
            return
        }

        // 如果 DownloadService 正在后台下载，不要重复启动
        if (com.swupdater.service.DownloadService.isDownloading) {
            AppLog.i(TAG, "DownloadService 已在下载中")
            return
        }

        // 启动 DownloadService（处理前台通知 + 实际下载）
        com.swupdater.service.DownloadService.start(getApplication(), downloadUrl, latest.versionName)
    }

    /**
     * 在浏览器中打开链接
     */
    private fun openInBrowser(url: String) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "已在浏览器打开: $url")
        } catch (e: Exception) {
            AppLog.e(TAG, "无法打开浏览器: ${e.message}")
        }
    }

    fun cancelDownload() {
        DownloadManager.cancelDownload()
    }

    private fun verifyDownloadedFile(filePath: String) {
        viewModelScope.launch {
            val file = File(filePath)
            if (!file.exists()) return@launch

            DownloadManager._progress.value = DownloadManager.progress.value.copy(
                state = DownloadState.VERIFYING
            )

            val latest = _latestVersion.value
            var isVerified: Boolean
            var verifyDetail = ""
            try {
                isVerified = true
                if (latest != null && latest.fileSize > 0) {
                    if (!ChecksumUtil.verifyFileSize(file, latest.fileSize)) {
                        isVerified = false
                        verifyDetail = "文件大小不匹配: 期望=${latest.fileSize}, 实际=${file.length()}"
                    }
                }
                if (isVerified && latest != null && latest.md5.isNotEmpty()) {
                    val result = ChecksumUtil.verifyMd5Detail(file, latest.md5)
                    if (!result.success) {
                        isVerified = false
                        verifyDetail = result.detail
                    }
                }
                if (isVerified && latest != null && latest.sha256.isNotEmpty()) {
                    val result = ChecksumUtil.verifySha256Detail(file, latest.sha256)
                    if (!result.success) {
                        isVerified = false
                        verifyDetail = result.detail
                    }
                }
            } catch (e: Exception) {
                isVerified = false
                verifyDetail = "校验异常: ${e.message}"
            }

            if (!isVerified) {
                AppLog.e(TAG, "完整性校验失败: $verifyDetail")
            }

            DownloadManager._progress.value = DownloadManager.progress.value.copy(
                state = if (isVerified) DownloadState.VERIFIED else DownloadState.VERIFY_FAILED
            )
        }
    }

    fun installApk(): Boolean {
        val progress = DownloadManager.progress.value
        val filePath = progress.filePath
        if (filePath.isEmpty()) return false

        val file = File(filePath)
        if (!file.exists()) return false

        val context = getApplication<Application>()

        // 优先使用 Root 静默安装
        val prefs = context.getSharedPreferences("sw_updater_prefs", android.content.Context.MODE_PRIVATE)
        val rootAutoInstall = prefs.getBoolean("pref_root_auto_install", false)
        if (rootAutoInstall && com.swupdater.util.RootInstallHelper.isDeviceRooted()) {
            AppLog.i(TAG, "使用 Root 静默安装...")
            DownloadManager._progress.value = progress.copy(state = DownloadState.INSTALLING)
            viewModelScope.launch {
                val result = com.swupdater.util.RootInstallHelper.installSilently(filePath)
                if (result.success) {
                    AppLog.i(TAG, "Root 安装成功")
                    refreshInstalledInfo()
                    // 安装完成，删除安装包
                    val apkFile = File(filePath)
                    if (apkFile.exists()) apkFile.delete()
                    AppLog.i(TAG, "安装包已删除")
                    // 通知栏显示安装完成
                    com.swupdater.service.DownloadNotificationHelper.showInstallCompleteNotification(context)
                } else {
                    AppLog.e(TAG, "Root 安装失败: ${result.message}")
                    // Root 安装失败，回退到系统安装器
                    installViaSystemInstaller(context, file)
                }
            }
            return true
        }

        // 无 Root，走系统安装器
        return installViaSystemInstaller(context, file)
    }

    /**
     * 通过系统安装器安装 APK
     */
    private fun installViaSystemInstaller(context: android.content.Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) return false
        }

        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val progress = DownloadManager.progress.value
            DownloadManager._progress.value = progress.copy(state = DownloadState.INSTALLING)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun requestInstallPermission(): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${getApplication<Application>().packageName}")
        }
    }

    fun launchGame(): Boolean {
        val context = getApplication<Application>()
        val intent = AppInfoUtil.getLaunchIntent(context, targetPackageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else false
    }

    fun isGameInstalled(): Boolean = _installedInfo.value?.isInstalled == true

    fun setTargetPackageName(packageName: String) {
        targetPackageName = packageName
        refreshInstalledInfo()
    }

    fun clearLog() {
        AppLog.clear()
        _logText.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        DownloadManager.cancelDownload()
    }
}
