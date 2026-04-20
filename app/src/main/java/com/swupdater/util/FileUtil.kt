package com.swupdater.util

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.DecimalFormat

/**
 * 文件与格式化工具
 */
object FileUtil {

    private val sizeFormat = DecimalFormat("#,##0.##")

    /**
     * 获取应用下载目录（公用 Download 目录）
     * 路径: /sdcard/Download/SWUpdater/updates
     */
    @Suppress("UNUSED_PARAMETER")
    fun getDownloadDir(context: Context): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取 APK 下载目标文件
     */
    fun getApkFile(context: Context, versionName: String): File {
        return File(getDownloadDir(context), "summoners_war_${versionName}.apk")
    }

    /**
     * 清除下载缓存
     */
    fun clearDownloadCache(context: Context): Int {
        val dir = getDownloadDir(context)
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".apk")) {
                if (file.delete()) count++
            }
        }
        return count
    }

    /**
     * 获取缓存大小
     */
    fun getCacheSize(context: Context): Long {
        val dir = getDownloadDir(context)
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return "${sizeFormat.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    /**
     * 格式化下载速度
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatFileSize(bytesPerSecond)}/s"
    }

    /**
     * 检查是否有可用存储空间
     */
    fun hasAvailableSpace(context: Context, requiredBytes: Long): Boolean {
        val stat = android.os.StatFs(getDownloadDir(context).absolutePath)
        val availableBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.availableBytes
        } else {
            @Suppress("DEPRECATION")
            stat.availableBlocks.toLong() * stat.blockSize.toLong()
        }
        return availableBytes > requiredBytes
    }

    /**
     * 从 URL 提取文件名
     */
    fun getFileNameFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?").ifEmpty { "update.apk" }
    }

    /**
     * 从下载 URL 中解析版本号
     * 支持格式：
     * - .../SummonersWar_9.1.9.apk
     * - .../smon-9.1.9.apk
     * - .../version/9.1.9/...
     * - .../v9.1.9/...
     */
    fun parseVersionFromUrl(url: String): String? {
        // 尝试从文件名中提取版本号
        val patterns = listOf(
            Regex("""[_\-\./]v?(\d+\.\d+\.\d+)\.apk""", RegexOption.IGNORE_CASE),
            Regex("""[_\-\./]v?(\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE),
            Regex("""version[/_\-](\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE),
        )

        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}
