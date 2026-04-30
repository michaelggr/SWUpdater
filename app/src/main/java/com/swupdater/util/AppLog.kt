package com.swupdater.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用日志工具
 * 支持内存日志缓冲 + 文件持久化，可在设置中开关
 */
object AppLog {

    private const val PREFS_NAME = "sw_updater_prefs"
    private const val PREF_LOG_MODE = "pref_log_mode"
    private const val MAX_LOG_ENTRIES = 500
    private const val LOG_FILE_NAME = "sw_updater.log"

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 内存日志缓冲
    private val buffer = mutableListOf<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        val formattedTime: String get() = dateFormat.format(Date(timestamp))
        override fun toString(): String = "${formattedTime} [$level] $tag: $message"
    }

    /**
     * 是否启用日志模式
     */
    fun isLogModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_LOG_MODE, false)
    }

    /**
     * 设置日志模式
     */
    fun setLogModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_LOG_MODE, enabled).apply()
    }

    /**
     * 添加日志监听器（用于 UI 实时展示）
     */
    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    // ---- 日志方法 ----

    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String) = log("ERROR", tag, message)

    private fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        // 始终输出到 Android Logcat
        when (level) {
            "DEBUG" -> android.util.Log.d(tag, message)
            "INFO" -> android.util.Log.i(tag, message)
            "WARN" -> android.util.Log.w(tag, message)
            "ERROR" -> android.util.Log.e(tag, message)
        }

        // 缓冲区
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_LOG_ENTRIES) {
                buffer.removeAt(0)
                if (lastFlushIndex > 0) lastFlushIndex--
            }
        }

        // 通知监听器
        listeners.forEach { it(entry) }
    }

    /**
     * 获取所有日志
     */
    fun getLogs(): List<LogEntry> = synchronized(buffer) { buffer.toList() }

    /**
     * 获取日志文本
     */
    fun getLogText(): String = getLogs().joinToString("\n") { it.toString() }

    private var lastFlushIndex = 0

    fun flushToFile(context: Context) {
        val logFile = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), LOG_FILE_NAME)
        logFile.parentFile?.mkdirs()
        try {
            val logsToWrite = synchronized(buffer) {
                if (lastFlushIndex >= buffer.size) return@synchronized emptyList<LogEntry>()
                buffer.subList(lastFlushIndex, buffer.size).also {
                    lastFlushIndex = buffer.size
                }
            }
            if (logsToWrite.isEmpty()) return
            PrintWriter(FileWriter(logFile, true)).use { writer ->
                logsToWrite.forEach { writer.println(it.toString()) }
            }
        } catch (e: Exception) {
            android.util.Log.e("AppLog", "写入日志文件失败", e)
        }
    }

    fun clear() = synchronized(buffer) {
        buffer.clear()
        lastFlushIndex = 0
    }

    /**
     * 获取日志文件
     */
    fun getLogFile(context: Context): File {
        return File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), LOG_FILE_NAME)
    }
}
