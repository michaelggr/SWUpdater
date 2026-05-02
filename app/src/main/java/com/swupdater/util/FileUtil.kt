package com.swupdater.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.DecimalFormat

object FileUtil {

    private const val TAG = "FileUtil"
    private val sizeFormat = DecimalFormat("#,##0.##")

    fun getDownloadDir(context: Context): File {
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

    fun clearDownloadCache(context: Context): Int {
        var count = 0

        // 清除公共目录下的 APK
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        Log.i(TAG, "清除公共目录: ${publicDir.absolutePath}, 存在: ${publicDir.exists()}")
        if (publicDir.exists() && publicDir.isDirectory) {
            publicDir.listFiles()?.forEach { file ->
                Log.d(TAG, "检查文件: ${file.absolutePath}, 大小: ${file.length()}")
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    if (file.delete()) {
                        count++
                        Log.i(TAG, "已删除: ${file.name}")
                    } else {
                        Log.w(TAG, "删除失败: ${file.name}")
                    }
                }
            }
        }

        // 清除应用私有目录下的 APK
        val privateDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        Log.i(TAG, "清除私有目录: ${privateDir.absolutePath}, 存在: ${privateDir.exists()}")
        if (privateDir.exists() && privateDir.isDirectory) {
            privateDir.listFiles()?.forEach { file ->
                Log.d(TAG, "检查文件: ${file.absolutePath}, 大小: ${file.length()}")
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    if (file.delete()) {
                        count++
                        Log.i(TAG, "已删除: ${file.name}")
                    } else {
                        Log.w(TAG, "删除失败: ${file.name}")
                    }
                }
            }
        }

        Log.i(TAG, "清除缓存完成: 共删除 $count 个文件")
        return count
    }

    fun clearSelfUpdateCache(context: Context): Int {
        var count = 0

        // 清除公共目录下本应用的更新包
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (publicDir.exists() && publicDir.isDirectory) {
            publicDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true) &&
                    file.name.contains("swupdater", ignoreCase = true)) {
                    if (file.delete()) {
                        count++
                        Log.i(TAG, "已删除旧版安装包: ${file.name}")
                    }
                }
            }
        }

        // 清除应用私有目录下本应用的更新包
        val privateDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (privateDir.exists() && privateDir.isDirectory) {
            privateDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true) &&
                    file.name.contains("swupdater", ignoreCase = true)) {
                    if (file.delete()) {
                        count++
                        Log.i(TAG, "已删除旧版安装包: ${file.name}")
                    }
                }
            }
        }

        Log.i(TAG, "清除旧版安装包完成: 共删除 $count 个文件")
        return count
    }

    fun getCacheSize(context: Context): Long {
        var totalSize = 0L

        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (publicDir.exists()) {
            totalSize += publicDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }

        val privateDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/updates"
        )
        if (privateDir.exists()) {
            totalSize += privateDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }

        return totalSize
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return "${sizeFormat.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        return "${formatFileSize(bytesPerSecond)}/s"
    }

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

    fun getFileNameFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?").ifEmpty { "update.apk" }
    }

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

    fun notifyFileScanned(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath),
                arrayOf("application/vnd.android.package-archive"), null)
        } catch (_: Exception) {}
    }
}