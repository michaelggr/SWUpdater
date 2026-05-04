package com.swupdater.capture

import android.content.Context
import com.google.gson.GsonBuilder
import com.swupdater.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CaptureRepository {

    private const val TAG = "CaptureRepo"
    private const val CAPTURE_DIR = "SWUpdater/capture"

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()

    fun saveCapture(context: Context, data: Map<String, Any?>): File? {
        val captureDir = getCaptureDir(context)
        if (!captureDir.exists()) captureDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "capture_$timestamp.json"
        val file = File(captureDir, fileName)

        return try {
            // 输出格式兼容 sw-exporter，方便第三方工具（swop、swarfarm等）使用
            val wrapper = mutableMapOf<String, Any?>(
                "timestamp" to System.currentTimeMillis(),
                "date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                "source" to "SWUpdater",
                // sw-exporter 兼容字段
                "installer" to "SWUpdater-Android",
                "version" to getAppVersion(context)
            )
            wrapper.putAll(data)

            file.writeText(gson.toJson(wrapper))
            AppLog.i(TAG, "抓取数据已保存: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            AppLog.e(TAG, "保存抓取数据失败: ${e.message}")
            null
        }
    }

    fun getCaptureHistory(context: Context): List<CaptureRecord> {
        val captureDir = getCaptureDir(context)
        if (!captureDir.exists()) return emptyList()

        return captureDir.listFiles()
            ?.filter { it.name.startsWith("capture_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseRecord(it) }
            ?: emptyList()
    }

    fun getLatestCapture(context: Context): CaptureRecord? {
        return getCaptureHistory(context).firstOrNull()
    }

    fun deleteCapture(context: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val deleted = file.delete()
            if (deleted) {
                AppLog.i(TAG, "已删除抓取记录: $filePath")
            }
            deleted
        } catch (e: Exception) {
            AppLog.e(TAG, "删除抓取记录失败: ${e.message}")
            false
        }
    }

    fun clearAllCaptures(context: Context): Int {
        val captureDir = getCaptureDir(context)
        if (!captureDir.exists()) return 0

        val files = captureDir.listFiles()
            ?.filter { it.name.startsWith("capture_") && it.name.endsWith(".json") }
            ?: emptyList()

        var count = 0
        for (file in files) {
            if (file.delete()) count++
        }
        AppLog.i(TAG, "已清理 $count 条抓取记录")
        return count
    }

    fun readCaptureFile(file: File): String? {
        return try {
            file.readText()
        } catch (e: Exception) {
            AppLog.e(TAG, "读取抓取文件失败: ${e.message}")
            null
        }
    }

    private fun parseRecord(file: File): CaptureRecord? {
        return try {
            val content = file.readText()
            val json = gson.fromJson(content, Map::class.java) as? Map<String, Any?> ?: return null

            val unitCount = extractCount(json, "HubUnitList", "count")
            val runeCount = extractCount(json, "HubUserRunes", "count") +
                    extractCount(json, "HubGetRuneList", "count")
            val artifactCount = extractCount(json, "HubGetArtifactList", "count")

            CaptureRecord(
                timestamp = file.lastModified(),
                filePath = file.absolutePath,
                fileName = file.name,
                unitCount = unitCount,
                runeCount = runeCount,
                artifactCount = artifactCount
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractCount(json: Map<String, Any?>, commandKey: String, countKey: String): Int {
        val commandData = json[commandKey] as? Map<String, Any?> ?: return 0
        return (commandData[countKey] as? Number)?.toInt() ?: 0
    }

    private fun getCaptureDir(context: Context): File {
        return File(FileUtil.getCaptureBaseDir(context), CAPTURE_DIR)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private object FileUtil {
        /**
         * 获取抓取数据基础目录
         * Android 10+: 应用专属外部存储（无需权限）
         * Android 9-: 公共 Download 目录
         */
        fun getCaptureBaseDir(context: Context): File {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val dir = java.io.File(
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "SWUpdater"
                )
                if (!dir.exists()) dir.mkdirs()
                dir
            } else {
                @Suppress("DEPRECATION")
                val externalDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                java.io.File(externalDir, "SWUpdater")
            }
        }
    }
}

data class CaptureRecord(
    val timestamp: Long,
    val filePath: String,
    val fileName: String,
    val unitCount: Int,
    val runeCount: Int,
    val artifactCount: Int
)
