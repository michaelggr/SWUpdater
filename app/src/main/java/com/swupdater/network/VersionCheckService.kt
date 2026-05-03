package com.swupdater.network

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.swupdater.model.VersionInfo
import com.swupdater.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 版本检测服务（v6）
 *
 * 只使用设置中配置的数据源URL进行版本检测
 * 默认数据源: https://play.qpyou.cn/b?i=8387&g=8109&gc=7976
 *
 * 检测流程：
 * 1. 访问配置的下载短链
 * 2. 短链返回 HTML 页面，JS 中包含 APK 直链
 * 3. APK 文件名格式：smon_919_xxx.apk → 919 → 9.1.9
 */
class VersionCheckService {

    companion object {
        private const val TAG = "VersionCheck"

        // 默认数据源URL（友皆乐）
        const val DEFAULT_SOURCE_URL = "https://play.qpyou.cn/b?i=8387&g=8109&gc=7976"

        const val PREF_SOURCE_URL = "pref_source_url"

        // 魔灵召唤可能的包名列表（用于本地检测）
        val POSSIBLE_PACKAGE_NAMES = listOf(
            "com.com2us.smon.normal.freefull.google.kr.android.common",
            "com.com2us.smon.normal.freefull.google.kr.android.official",
            "com.com2us.smon.normal.freefull.google.kr.android",
        )

        const val PACKAGE_NAME = "com.com2us.smon.normal.freefull.google.kr.android.common"
    }

    /**
     * IPv4 优先的 DNS 解析器
     */
    private class Ipv4PreferredDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
                val ipv4 = addresses.filter { it is Inet4Address }
                val ipv6 = addresses.filter { it !is Inet4Address }
                val result = if (ipv4.isNotEmpty()) ipv4 + ipv6 else addresses
                AppLog.d(TAG, "DNS $hostname -> ${result.map { it.hostAddress }}")
                return result
            } catch (e: UnknownHostException) {
                AppLog.w(TAG, "DNS 解析失败: $hostname -> ${e.message}")
                throw e
            }
        }
    }

    private val okHttpClient = okhttp3.OkHttpClient.Builder()
        .dns(Ipv4PreferredDns())
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val fallbackClient = okhttp3.OkHttpClient.Builder()
        .dns(Ipv4PreferredDns())
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // ============================================================
    //  核心检查方法
    // ============================================================

    /**
     * 从设置中的数据源URL获取版本和下载链接
     *
     * 流程：
     * 1. 访问配置的短链URL
     * 2. 短链返回 HTML 页面，JS 中包含 APK 直链
     * 3. APK 文件名格式：smon_919_xxx.apk → 919 → 9.1.9
     */
    suspend fun checkLatestVersion(context: Context? = null): VersionInfo? = withContext(Dispatchers.IO) {
        // 获取设置中的数据源URL
        val sourceUrl = getSourceUrl(context)
        AppLog.i(TAG, "===== 开始版本检查 =====")
        AppLog.i(TAG, "数据源: $sourceUrl")

        try {
            // 步骤1：访问短链，解析 APK 直链
            val apkUrl = resolveApkUrl(sourceUrl)
            if (apkUrl.isNullOrEmpty()) {
                AppLog.w(TAG, "未获取到 APK 下载链接")
                return@withContext null
            }
            AppLog.i(TAG, "APK 直链: $apkUrl")

            // 步骤2：从 APK 文件名提取版本号
            val versionName = extractVersionFromApkUrl(apkUrl)
            AppLog.i(TAG, "版本号: ${versionName ?: "未提取到"}")

            // 步骤3：获取文件大小
            val fileSize = fetchApkFileSize(apkUrl)
            AppLog.i(TAG, "文件大小: ${fileSize / 1024 / 1024} MB")

            if (versionName.isNullOrEmpty()) {
                AppLog.w(TAG, "版本号提取失败")
                return@withContext null
            }

            val result = VersionInfo(
                versionName = versionName,
                downloadUrl = apkUrl,
                fileSize = fileSize,
                downloadChannels = DownloadChannels.CHANNELS
            )

            AppLog.i(TAG, "===== 版本检查成功: $versionName =====")
            result
        } catch (e: Exception) {
            AppLog.e(TAG, "===== 版本检查失败: ${e.message} =====")
            null
        }
    }

    /**
     * 获取设置中的数据源URL
     */
    private fun getSourceUrl(context: Context?): String {
        if (context != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getString(PREF_SOURCE_URL, DEFAULT_SOURCE_URL) ?: DEFAULT_SOURCE_URL
        }
        return DEFAULT_SOURCE_URL
    }

    // ============================================================
    //  APK 链接解析
    // ============================================================

    /**
     * 解析短链/下载页，获取 APK 直链
     *
     * 支持两种情况：
     * 1. 短链返回 HTML 页面，JS 中有 location.href = "http://dn.qpyou.cn/smon/smon_919_xxx.apk"
     * 2. 直接就是 APK 直链
     */
    private fun resolveApkUrl(url: String): String? {
        // 如果URL本身是APK直链，直接返回
        if (url.endsWith(".apk", ignoreCase = true)) {
            AppLog.d(TAG, "URL 直接是 APK 链接: $url")
            return url
        }

        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.w(TAG, "请求失败: HTTP ${response.code}")
                return null
            }

            val body = response.body?.string() ?: return null
            AppLog.d(TAG, "页面内容长度: ${body.length}")

            // 从 JS 中提取 APK 链接
            // 格式1: location.href = "http://dn.qpyou.cn/smon/smon_919_NucRxWzPu7G4C2.apk"
            val jsPattern = Regex("""location\.href\s*=\s*["']([^"']*\.apk[^"']*)["']""")
            jsPattern.find(body)?.groupValues?.get(1)?.let {
                AppLog.d(TAG, "从 JS 提取 APK 链接: $it")
                return ensureHttps(it)
            }

            // 格式2: window.location = "xxx.apk"
            val windowPattern = Regex("""window\.location\s*=\s*["']([^"']*\.apk[^"']*)["']""")
            windowPattern.find(body)?.groupValues?.get(1)?.let {
                AppLog.d(TAG, "从 window.location 提取 APK 链接: $it")
                return ensureHttps(it)
            }

            // 格式3: 搜索 dn.qpyou.cn 的 APK 链接
            val dnPattern = Regex("""(https?://dn\.qpyou\.cn/[^\s"'<>]+\.apk)""")
            dnPattern.find(body)?.groupValues?.get(1)?.let {
                AppLog.d(TAG, "从页面提取 dn.qpyou.cn APK 链接: $it")
                return ensureHttps(it)
            }

            // 格式4: 搜索任意 APK 链接
            val anyApkPattern = Regex("""(https?://[^\s"'<>]+\.apk)""")
            anyApkPattern.find(body)?.groupValues?.get(1)?.let {
                AppLog.d(TAG, "从页面提取 APK 链接(通用): $it")
                return ensureHttps(it)
            }

            // 格式5: 搜索 <a href="xxx.apk"> 标签
            val linkPattern = Regex("""<a\s[^>]*href=["']([^"']*\.apk[^"']*)["']""", RegexOption.IGNORE_CASE)
            linkPattern.find(body)?.groupValues?.get(1)?.let {
                AppLog.d(TAG, "从 <a> 标签提取 APK 链接: $it")
                return ensureHttps(it)
            }

            AppLog.w(TAG, "页面中未找到 APK 下载链接")

        } catch (e: Exception) {
            AppLog.e(TAG, "解析下载页失败: ${e.message}")
        }
        return null
    }

    /**
     * 规范化 URL 协议
     *
     * - dn.qpyou.cn 仅支持 HTTP，强制转 HTTPS 会导致 SSL 错误
     * - 其他域名保持原样，由 OkHttp 自动处理
     */
    private fun ensureHttps(url: String): String {
        // dn.qpyou.cn 不支持 HTTPS，强制使用 HTTP
        if (url.contains("dn.qpyou.cn")) {
            return url.replace("https://", "http://")
        }
        // 其他域名保持原样（OkHttp 会自动处理重定向）
        return url
    }

    // ============================================================
    //  版本号提取
    // ============================================================

    /**
     * 从 APK URL 文件名提取版本号
     * 示例: smon_919_NucRxWzPu7G4C2.apk → 919 → 9.1.9
     */
    private fun extractVersionFromApkUrl(apkUrl: String): String? {
        val filename = apkUrl.substringAfterLast("/")

        // 模式1: smon_919_xxx.apk → 919
        val smonPattern = Regex("smon_(\\d{3,})")
        smonPattern.find(filename)?.groupValues?.get(1)?.let { digits ->
            val version = convertDigitsToVersion(digits)
            AppLog.d(TAG, "文件名 $filename → 版本号 $version")
            return version
        }

        // 模式2: 标准版本号 x.y.z
        val standardPattern = Regex("(\\d+\\.\\d+\\.\\d+)")
        standardPattern.find(filename)?.groupValues?.get(1)?.let {
            AppLog.d(TAG, "标准版本号: $it")
            return it
        }

        return null
    }

    /**
     * 将连续数字转换为版本号
     * 919 → 9.1.9, 9120 → 9.1.20, 9119 → 9.1.19
     */
    private fun convertDigitsToVersion(digits: String): String {
        return when {
            digits.length == 3 -> "${digits[0]}.${digits[1]}.${digits[2]}"
            digits.length == 4 -> "${digits[0]}.${digits[1]}.${digits.substring(2)}"
            digits.length >= 5 -> "${digits[0]}.${digits[1]}.${digits.substring(2)}"
            else -> digits
        }
    }

    /**
     * 获取 APK 文件大小（HEAD 请求）
     * 如果获取失败不影响主流程，返回 0
     */
    private fun fetchApkFileSize(apkUrl: String): Long {
        return try {
            // dn.qpyou.cn 必须用 HTTP
            val url = if (apkUrl.contains("dn.qpyou.cn")) {
                apkUrl.replace("https://", "http://")
            } else {
                apkUrl
            }
            val request = okhttp3.Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val size = response.body?.contentLength() ?: 0
            if (size > 0) size else 0
        } catch (e: Exception) {
            AppLog.w(TAG, "获取文件大小失败: ${e.message}")
            0
        }
    }

    // ============================================================
    //  日志模式 - 详细检测
    // ============================================================

    /**
     * 日志模式：详细检测数据源并记录每个步骤的结果
     */
    suspend fun checkAllSourcesWithDetails(context: Context? = null): List<SourceCheckDetail> {
        AppLog.i(TAG, "===== 详细检测数据源 =====")

        val sourceUrl = getSourceUrl(context)
        val details = mutableListOf<SourceCheckDetail>()

        // 步骤1：检测短链是否可访问
        try {
            AppLog.i(TAG, "[步骤1] 检测数据源URL: $sourceUrl")
            val request = okhttp3.Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            val step1Success = response.isSuccessful
            val step1Info = "HTTP ${response.code}, 大小: ${response.body?.contentLength() ?: "?"} 字节"
            AppLog.i(TAG, "[步骤1] $step1Info")

            details.add(SourceCheckDetail(
                sourceName = "数据源URL可访问性",
                success = step1Success,
                versionName = null,
                downloadUrl = null,
                error = if (step1Success) null else step1Info
            ))
        } catch (e: Exception) {
            AppLog.e(TAG, "[步骤1] 访问失败: ${e.message}")
            details.add(SourceCheckDetail(
                sourceName = "数据源URL可访问性",
                success = false,
                versionName = null,
                downloadUrl = null,
                error = e.message
            ))
        }

        // 步骤2：检测APK直链解析
        try {
            val apkUrl = resolveApkUrl(sourceUrl)
            val step2Success = !apkUrl.isNullOrEmpty()
            AppLog.i(TAG, "[步骤2] APK直链: ${apkUrl ?: "未获取到"}")

            details.add(SourceCheckDetail(
                sourceName = "APK直链解析",
                success = step2Success,
                versionName = null,
                downloadUrl = apkUrl,
                error = if (step2Success) null else "未能从页面解析出APK下载链接"
            ))
        } catch (e: Exception) {
            details.add(SourceCheckDetail(
                sourceName = "APK直链解析",
                success = false,
                versionName = null,
                downloadUrl = null,
                error = e.message
            ))
        }

        // 步骤3：版本号提取
        try {
            val apkUrl = resolveApkUrl(sourceUrl)
            val versionName = if (!apkUrl.isNullOrEmpty()) extractVersionFromApkUrl(apkUrl) else null
            val step3Success = !versionName.isNullOrEmpty()
            AppLog.i(TAG, "[步骤3] 版本号: ${versionName ?: "未提取到"}")

            details.add(SourceCheckDetail(
                sourceName = "版本号提取",
                success = step3Success,
                versionName = versionName,
                downloadUrl = null,
                error = if (step3Success) null else "未能从APK文件名中提取版本号"
            ))
        } catch (e: Exception) {
            details.add(SourceCheckDetail(
                sourceName = "版本号提取",
                success = false,
                versionName = null,
                downloadUrl = null,
                error = e.message
            ))
        }

        // 步骤4：完整结果
        val result = checkLatestVersion(context)
        details.add(SourceCheckDetail(
            sourceName = "最终结果",
            success = result != null && result.versionName.isNotEmpty(),
            versionName = result?.versionName,
            downloadUrl = result?.downloadUrl,
            error = if (result != null) null else "版本检查失败"
        ))

        return details
    }

    data class SourceCheckDetail(
        val sourceName: String,
        val success: Boolean,
        val versionName: String?,
        val downloadUrl: String?,
        val error: String?
    )
}
