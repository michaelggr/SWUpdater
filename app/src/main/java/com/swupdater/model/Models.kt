package com.swupdater.model

/**
 * 版本信息数据类
 */
data class VersionInfo(
    val versionName: String,       // 版本名，如 "9.1.9"
    val versionCode: Long = 0,     // 版本号
    val downloadUrl: String = "",  // 默认下载链接（保持向后兼容）
    val fileSize: Long = 0,        // 文件大小（字节）
    val md5: String = "",          // MD5 校验值
    val sha256: String = "",       // SHA256 校验值
    val changelog: String = "",    // 更新日志
    val releaseDate: String = "",  // 发布日期
    val downloadChannels: List<DownloadChannel> = emptyList()  // 可用下载渠道
)

/**
 * 应用安装信息
 */
data class AppInstallInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isInstalled: Boolean
)

/**
 * 下载状态
 */
enum class DownloadState {
    IDLE,           // 空闲
    DOWNLOADING,    // 下载中
    PAUSED,         // 已暂停
    DOWNLOADED,     // 已下载
    VERIFYING,      // 校验中
    VERIFIED,       // 校验通过
    VERIFY_FAILED,  // 校验失败
    INSTALLING,     // 安装中
    FAILED          // 失败
}

/**
 * 下载进度信息
 */
data class DownloadProgress(
    val state: DownloadState = DownloadState.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speed: Long = 0,           // 字节/秒
    val filePath: String = ""
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0

    val isCompleted: Boolean
        get() = state == DownloadState.DOWNLOADED || state == DownloadState.VERIFIED
}

/**
 * 更新检查结果
 */
sealed class UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: VersionInfo
    ) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
    data object Checking : UpdateCheckResult()
}

/**
 * 数据源检测结果（用于日志模式展示）
 */
data class SourceCheckResult(
    val sourceName: String,
    val success: Boolean,
    val versionName: String?,
    val downloadUrl: String?,
    val error: String?
) {
    val displayText: String
        get() = if (success) {
            "✅ $sourceName: v$versionName"
        } else {
            "❌ $sourceName: ${error ?: "未获取到版本号"}"
        }
}

/**
 * 下载渠道
 */
data class DownloadChannel(
    val id: String,               // 渠道 ID
    val name: String,             // 渠道名称
    val url: String,              // 下载/跳转链接
    val type: ChannelType,        // 渠道类型
    val description: String = "", // 渠道描述
    val isRecommended: Boolean = false, // 是否推荐
    val requireVpn: Boolean = false     // 是否需要 VPN
) {
    enum class ChannelType {
        OFFICIAL_WEB,     // 官方网页（浏览器打开）
        APP_STORE,        // 应用商店（Intent 跳转）
        APK_DIRECT,       // APK 直链下载
        ACCELERATOR,      // 加速器应用
        CUSTOM            // 用户自定义
    }
}
