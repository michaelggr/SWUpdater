package com.swupdater.network

import com.swupdater.util.AppLog
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

object NetworkUtil {

    private const val TAG = "NetworkUtil"

    // dn.qpyou.cn 不支持 HTTPS，必须使用 HTTP
    private const val HTTP_ONLY_HOST = "dn.qpyou.cn"

    class Ipv4PreferredDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val addresses = Dns.SYSTEM.lookup(hostname)
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

    // dn.qpyou.cn 仅支持 HTTP，强制转 HTTPS 会导致 SSL 错误
    fun normalizeUrl(url: String): String {
        if (url.contains(HTTP_ONLY_HOST)) {
            return url.replace("https://", "http://")
        }
        return url
    }

    fun isHttpOnlyHost(url: String): Boolean = url.contains(HTTP_ONLY_HOST)
}
