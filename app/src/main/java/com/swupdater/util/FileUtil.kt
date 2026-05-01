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
     * 获取应用下载目录
     * 优先使用公共 Download 目录，无权限时回退到应用私有目录
     */
    fun getDownloadDir(context: Context): File {
        // Android 10+ 需要检查是否有公共目录写入权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val fallbackDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "SWUpdater/updates"
                )
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                return fallbackDir
            }
        }
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
     * 清除下载缓存（同时清除公共目录和私有目录）
     */
    fun clearDownloadCache(context: Context): Int {
        var count = 0

        // 清除公共目录下的 APK
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (publicDir.exists()) {
            publicDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk")) {
                    if (file.delete()) count++
                }
            }
        }

        // 清除应用私有目录下的 APK
        val privateDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (privateDir.exists()) {
            privateDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk")) {
                    if (file.delete()) count++
                }
            }
        }

        return count
    }

    /**
     * 获取缓存大小（同时计算公共目录和私有目录）
     */
    fun getCacheSize(context: Context): Long {
        var totalSize = 0L

        // 计算公共目录大小
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (publicDir.exists()) {
            totalSize += publicDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }

        // 计算应用私有目录大小
        val privateDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (privateDir.exists()) {
            totalSize += privateDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }

        return totalSize
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
