package com.swupdater.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
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
        private const val TAG = "MainVM"
    }

    private val versionCheckService = VersionCheckService()

    var targetPackageName: String = AppInfoUtil.PACKAGE_NAME_CN
        private set

    private val _checkResult = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Checking)
    val checkResult: StateFlow<UpdateCheckResult> = _checkResult

    private val _installedInfo = MutableStateFlow<AppInstallInfo?>(null)
    val installedInfo: StateFlow<AppInstallInfo?> = _installedInfo

    private val _latestVersion = MutableStateFlow<VersionInfo?>(null)
    val latestVersion: StateFlow<VersionInfo?> = _latestVersion

    val downloadProgress: StateFlow<DownloadProgress> = DownloadManager.progress

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking

    private val _sourceCheckResults = MutableStateFlow<List<SourceCheckResult>>(emptyList())
    val sourceCheckResults: StateFlow<List<SourceCheckResult>> = _sourceCheckResults

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText

    init {
        refreshInstalledInfo()

        viewModelScope.launch {
            DownloadManager.progress.collect { progress ->
                if (progress.state == DownloadState.DOWNLOADED && progress.filePath.isNotEmpty()) {
                    verifyDownloadedFile(progress.filePath)
                }
            }
        }

        AppLog.addListener { entry ->
            val current = _logText.value
            val newLine = entry.toString()
            val lines = (current + "\n" + newLine).lines().takeLast(200)
            _logText.value = lines.joinToString("\n")
        }
    }

    fun refreshInstalledInfo() {
        val context = getApplication<Application>()

        val detectedPackage = AppInfoUtil.detectInstalledPackageName(context)
        targetPackageName = detectedPackage

        val info = AppInfoUtil.getInstalledAppInfo(context, targetPackageName)
        _installedInfo.value = info

        if (info.isInstalled) {
            AppLog.i(TAG, "本地应用: $targetPackageName v${info.versionName}")
        } else {
            AppLog.i(TAG, "本地应用: 未安装 (已检测 ${AppInfoUtil.POSSIBLE_PACKAGE_NAMES.size} 个包名)")
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

                if (isLogMode) {
                    AppLog.section(TAG, "版本检查 (日志模式)")
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

                    val finalResult = details.lastOrNull()
                    if (finalResult != null && finalResult.success && finalResult.versionName != null) {
                        val latestInfo = VersionInfo(
                            versionName = finalResult.versionName,
                            downloadUrl = finalResult.downloadUrl ?: ""
                        )
                        _latestVersion.value = latestInfo
                        compareVersions(latestInfo)
                    } else {
                        AppLog.w(TAG, "数据源检测全部失败")
                        _checkResult.value = UpdateCheckResult.Error("数据源检测失败，请检查网络连接或数据源URL配置")
                    }
                } else {
                    AppLog.section(TAG, "版本检查 (标准模式)")
                    val latestInfo = versionCheckService.checkLatestVersion(context)
                    if (latestInfo == null) {
                        AppLog.w(TAG, "版本检查失败: 无法获取最新版本信息")
                        _checkResult.value = UpdateCheckResult.Error("无法获取最新版本信息，请检查网络连接或数据源URL配置")
                        return@launch
                    }
                    _latestVersion.value = latestInfo
                    compareVersions(latestInfo)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "版本检查异常: ${e.message}")
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
            AppLog.i(TAG, "发现新版本: $currentVersion → ${latestInfo.versionName}")
        } else {
            _checkResult.value = UpdateCheckResult.UpToDate(currentVersion)
            AppLog.i(TAG, "已是最新版本: $currentVersion")
        }
    }

    fun getDownloadChannels(): List<DownloadChannel> {
        val latest = _latestVersion.value
        return latest?.downloadChannels ?: DownloadChannels.getRecommendedChannels()
    }

    fun downloadViaChannel(channel: DownloadChannel) {
        AppLog.i(TAG, "选择渠道: ${channel.name} (${channel.type})")

        when (channel.type) {
            DownloadChannel.ChannelType.APK_DIRECT -> {
                val latest = _latestVersion.value ?: return
                val targetFile = FileUtil.getApkFile(getApplication(), latest.versionName)
                val downloadUrl = latest.downloadUrl.ifEmpty { channel.url }
                AppLog.i(TAG, "APK 直链下载: $downloadUrl")
                DownloadManager.startDownload(downloadUrl, targetFile, viewModelScope)
            }
            DownloadChannel.ChannelType.CUSTOM -> {
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

    fun startDownload() {
        val latest = _latestVersion.value ?: return
        val downloadUrl = latest.downloadUrl
        if (downloadUrl.isEmpty()) {
            AppLog.e(TAG, "下载失败: 下载链接为空，请先检查更新")
            return
        }

        if (com.swupdater.service.DownloadService.isDownloading) {
            AppLog.i(TAG, "DownloadService 已在下载中，跳过重复启动")
            return
        }

        AppLog.i(TAG, "启动 DownloadService 下载: v${latest.versionName}")
        com.swupdater.service.DownloadService.start(getApplication(), downloadUrl, latest.versionName)
    }

    private fun openInBrowser(url: String) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLog.i(TAG, "已打开浏览器: $url")
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
            if (!file.exists()) {
                AppLog.e(TAG, "校验失败: 文件不存在 $filePath")
                return@launch
            }

            AppLog.section(TAG, "文件校验")
            AppLog.i(TAG, "文件: ${file.name}, 大小: ${FileUtil.formatFileSize(file.length())}")
            DownloadManager.updateProgress(DownloadManager.progress.value.copy(
                state = DownloadState.VERIFYING
            ))

            val latest = _latestVersion.value
            var isVerified: Boolean
            try {
                isVerified = true
                if (latest != null && latest.fileSize > 0) {
                    val sizeMatch = ChecksumUtil.verifyFileSize(file, latest.fileSize)
                    AppLog.i(TAG, "大小校验: 期望=${latest.fileSize}, 实际=${file.length()}, 结果=${if (sizeMatch) "通过" else "不匹配"}")
                    if (!sizeMatch) isVerified = false
                }
                if (isVerified && latest != null && latest.md5.isNotEmpty()) {
                    val md5Match = ChecksumUtil.verifyMd5(file, latest.md5)
                    AppLog.i(TAG, "MD5 校验: ${if (md5Match) "通过" else "不匹配"}")
                    if (!md5Match) isVerified = false
                }
                if (isVerified && latest != null && latest.sha256.isNotEmpty()) {
                    val sha256Match = ChecksumUtil.verifySha256(file, latest.sha256)
                    AppLog.i(TAG, "SHA256 校验: ${if (sha256Match) "通过" else "不匹配"}")
                    if (!sha256Match) isVerified = false
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "校验异常: ${e.message}")
                isVerified = false
            }

            val finalState = if (isVerified) DownloadState.VERIFIED else DownloadState.VERIFY_FAILED
            AppLog.i(TAG, "校验结果: ${if (isVerified) "✓ 全部通过" else "✗ 校验失败"}")
            DownloadManager.updateProgress(DownloadManager.progress.value.copy(
                state = finalState
            ))
        }
    }

    fun installApk(): Boolean {
        val progress = DownloadManager.progress.value
        val filePath = progress.filePath

        if (filePath.isEmpty()) {
            AppLog.e(TAG, "安装失败: 文件路径为空")
            return false
        }

        val file = File(filePath)
        if (!file.exists()) {
            AppLog.e(TAG, "安装失败: 文件不存在 $filePath")
            return false
        }

        val context = getApplication<Application>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rootAutoInstall = prefs.getBoolean("pref_root_auto_install", false)
        val isRooted = com.swupdater.util.RootInstallHelper.isDeviceRooted()

        if (rootAutoInstall && isRooted) {
            AppLog.section(TAG, "Root 静默安装")
            DownloadManager.updateProgress(progress.copy(state = DownloadState.INSTALLING))
            viewModelScope.launch {
                val result = com.swupdater.util.RootInstallHelper.installSilently(filePath)
                if (result.success) {
                    AppLog.i(TAG, "Root 安装成功")
                    refreshInstalledInfo()
                    val apkFile = File(filePath)
                    if (apkFile.exists()) apkFile.delete()
                    AppLog.i(TAG, "安装包已清理")
                    DownloadNotificationHelper.showInstallCompleteNotification(context)
                    // 根据设置决定是否自动启动游戏
                    val autoLaunch = PreferenceManager
                        .getDefaultSharedPreferences(context)
                        .getBoolean("pref_auto_launch_game", true)
                    if (autoLaunch) {
                        AppLog.i(TAG, "自动启动游戏已开启，正在启动...")
                        launchGame()
                    }
                } else {
                    AppLog.e(TAG, "Root 安装失败: ${result.message}，回退到系统安装器")
                    installViaSystemInstaller(context, file)
                }
            }
            return true
        }

        AppLog.i(TAG, "使用系统安装器")
        return installViaSystemInstaller(context, file)
    }

    private fun installViaSystemInstaller(context: android.content.Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = context.packageManager.canRequestPackageInstalls()
            if (!canInstall) {
                AppLog.e(TAG, "缺少安装未知应用权限 (Android ${Build.VERSION.SDK_INT})")
                return false
            }
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
            DownloadManager.updateProgress(progress.copy(state = DownloadState.INSTALLING))
            AppLog.i(TAG, "系统安装器已启动")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "系统安装器启动失败: ${e.message}")
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
