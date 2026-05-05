package com.swupdater.util

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

object AppLog {

    private const val PREF_LOG_MODE = "pref_log_mode"
    private const val MAX_LOG_ENTRIES = 500
    private const val LOG_FILE_NAME = "sw_updater.log"
    private const val LOG_RETENTION_DAYS = 7

    private val LOG_RETENTION_MS = LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L

    // 包含年份的完整时间格式，方便跨年排查问题
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 日志级别缩写映射，输出对齐更美观
    private val levelAbbrev = mapOf(
        "DEBUG" to "DBG",
        "INFO"  to "INF",
        "WARN"  to "WRN",
        "ERROR" to "ERR"
    )

    @Volatile
    private var logModeEnabled = false

    private val buffer = mutableListOf<LogEntry>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(LogEntry) -> Unit>()

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        val formattedTime: String get() = dateFormat.format(Date(timestamp))
        val levelShort: String get() = levelAbbrev[level] ?: level
        override fun toString(): String = "${formattedTime} [${levelShort}] $tag: $message"
    }

    /**
     * 初始化日志系统，应在 Application.onCreate 中调用
     */
    fun init(context: Context) {
        logModeEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_LOG_MODE, false)
        cleanOldEntries()
        cleanOldLogFile(context)
    }

    fun isLogModeEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_LOG_MODE, false)
    }

    fun setLogModeEnabled(context: Context, enabled: Boolean) {
        logModeEnabled = enabled
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(PREF_LOG_MODE, enabled).apply()
        if (!enabled) {
            clear()
            cleanOldLogFile(context)
        }
    }

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun d(tag: String, message: String) = log("DEBUG", tag, message, null)
    fun d(tag: String, message: String, throwable: Throwable?) = log("DEBUG", tag, message, throwable)

    fun i(tag: String, message: String) = log("INFO", tag, message, null)
    fun i(tag: String, message: String, throwable: Throwable?) = log("INFO", tag, message, throwable)

    fun w(tag: String, message: String) = log("WARN", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable?) = log("WARN", tag, message, throwable)

    fun e(tag: String, message: String) = log("ERROR", tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable?) = log("ERROR", tag, message, throwable)

    /**
     * 记录一个带分隔线的段落标题，用于区分不同业务流程
     * 例如: AppLog.section("VersionCheck", "开始版本检查") 会输出:
     *   ──────── 开始版本检查 ────────
     */
    fun section(tag: String, title: String) {
        val line = "──────── $title ────────"
        i(tag, line)
    }

    private fun log(level: String, tag: String, message: String, throwable: Throwable?) {
        val fullMessage = if (throwable != null) {
            "$message\n${android.util.Log.getStackTraceString(throwable)}"
        } else {
            message
        }

        when (level) {
            "DEBUG" -> if (throwable != null) android.util.Log.d(tag, message, throwable) else android.util.Log.d(tag, message)
            "INFO" -> if (throwable != null) android.util.Log.i(tag, message, throwable) else android.util.Log.i(tag, message)
            "WARN" -> if (throwable != null) android.util.Log.w(tag, message, throwable) else android.util.Log.w(tag, message)
            "ERROR" -> if (throwable != null) android.util.Log.e(tag, message, throwable) else android.util.Log.e(tag, message)
        }

        if (!logModeEnabled) return

        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = fullMessage
        )

        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_LOG_ENTRIES) {
                buffer.removeAt(0)
            }
        }

        listeners.forEach { it(entry) }
    }

    private fun cleanOldEntries() {
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
        synchronized(buffer) {
            val iterator = buffer.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().timestamp < cutoff) {
                    iterator.remove()
                } else {
                    break
                }
            }
        }
    }

    private fun cleanOldLogFile(context: Context) {
        val logFile = getLogFile(context)
        if (!logFile.exists()) return

        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
        val tempFile = File(logFile.parent, "${LOG_FILE_NAME}.tmp")

        try {
            val retainedLines = mutableListOf<String>()
            BufferedReader(FileReader(logFile)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val timestamp = parseTimestampFromLine(line)
                    if (timestamp != null && timestamp >= cutoff) {
                        retainedLines.add(line)
                    }
                    line = reader.readLine()
                }
            }

            PrintWriter(FileWriter(tempFile, false)).use { writer ->
                retainedLines.forEach { writer.println(it) }
            }

            if (tempFile.exists()) {
                logFile.delete()
                tempFile.renameTo(logFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("AppLog", "清理日志文件失败", e)
            tempFile.delete()
        }
    }

    /**
     * 从日志行中解析时间戳
     * 兼容两种格式: "yyyy-MM-dd HH:mm:ss.SSS" 和旧版 "MM-dd HH:mm:ss.SSS"
     */
    private fun parseTimestampFromLine(line: String): Long? {
        return try {
            // 优先尝试新格式（含年份）
            val newFormat = parseWithFormat(line, "yyyy-MM-dd HH:mm:ss.SSS", 23)
            if (newFormat != null) return newFormat
            // 回退到旧格式
            parseWithFormat(line, "MM-dd HH:mm:ss.SSS", 18)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWithFormat(line: String, pattern: String, prefixLen: Int): Long? {
        if (line.length < prefixLen) return null
        val dateStr = line.substring(0, prefixLen).trim()
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        val date = sdf.parse(dateStr) ?: return null
        val cal = Calendar.getInstance()
        val currentYear = cal.get(Calendar.YEAR)
        cal.time = date
        // 旧格式不含年份，补上当前年份
        if (pattern.startsWith("MM-dd")) {
            cal.set(Calendar.YEAR, currentYear)
        }
        return cal.timeInMillis
    }

    fun getLogs(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    fun getLogText(): String = getLogs().joinToString("\n") { it.toString() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun flushToFile(context: Context) {
        val logFile = getLogFile(context)
        logFile.parentFile?.mkdirs()
        try {
            PrintWriter(FileWriter(logFile, true)).use { writer ->
                getLogs().forEach { writer.println(it.toString()) }
            }
        } catch (e: Exception) {
            android.util.Log.e("AppLog", "写入日志文件失败", e)
        }
    }

    fun getLogFile(context: Context): File {
        val externalDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        return File(externalDir, LOG_FILE_NAME)
    }
}
