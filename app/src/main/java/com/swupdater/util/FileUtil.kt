package com.swupdater.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        val dir = getDownloadDir(context)
        val baseName = "summoners_war_${versionName}.apk"
        var target = File(dir, baseName)
        if (!target.exists()) return target
        val nameWithoutExt = baseName.substringBeforeLast(".")
        val ext = baseName.substringAfterLast(".", "apk")
        var index = 1
        while (target.exists()) {
            target = File(dir, "${nameWithoutExt}_$index.$ext")
            index++
        }
        return target
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

    /**
     * 通知媒体库扫描文件，使 APK 在文件管理器中立即可见
     * Android 10+ 通过 DownloadManager 的 MediaStore 记录，低版本用广播扫描
     */
    fun notifyFileScanned(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: 通过 MediaStore Downloads 插入记录
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/SWUpdater/updates")
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    put(MediaStore.Downloads.SIZE, file.length())
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }
            // 所有版本都执行 MediaScannerConnection 扫描
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath),
                arrayOf("application/vnd.android.package-archive"), null)
        } catch (_: Exception) {}
    }
}
