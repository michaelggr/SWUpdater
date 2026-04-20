package com.swupdater.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * 通用工具扩展
 */
object CommonUtil {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /**
     * 格式化时间戳
     */
    fun formatTimestamp(timestamp: Long): String {
        return dateFormat.format(Date(timestamp))
    }

    /**
     * 获取当前时间戳
     */
    fun currentTimestamp(): Long {
        return System.currentTimeMillis()
    }
}

/**
 * 安全执行块，捕获异常返回 null
 */
inline fun <T> tryOrNull(block: () -> T): T? {
    return try {
        block()
    } catch (e: Exception) {
        null
    }
}

/**
 * 安全执行块，捕获异常返回默认值
 */
inline fun <T> tryOrDefault(default: T, block: () -> T): T {
    return try {
        block()
    } catch (e: Exception) {
        default
    }
}
