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
            val wrapper = mutableMapOf<String, Any?>(
                "timestamp" to System.currentTimeMillis(),
                "date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                "source" to "SWUpdater"
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

    private object FileUtil {
        fun getCaptureBaseDir(context: Context): File {
            val externalDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            return File(externalDir, "SWUpdater")
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
