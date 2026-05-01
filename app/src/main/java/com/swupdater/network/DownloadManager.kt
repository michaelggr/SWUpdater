package com.swupdater.network

import android.util.Log
import com.swupdater.model.DownloadProgress
import com.swupdater.model.DownloadState
import com.swupdater.util.FileUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 下载管理器（全局单例）
 *
 * 功能：
 * - 支持断点续传
 * - 实时进度反馈
 * - 速度计算
 * - 取消下载
 * - 使用 OkHttp（IPv4 优先 DNS + 自动重定向）
 *
 * ViewModel 和 DownloadService 共享此单例，UI 和通知栏进度同步
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val BUFFER_SIZE = 8192
    private const val SPEED_SAMPLE_INTERVAL = 1000L

    internal val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    private var downloadJob: Job? = null
    private var isCancelled = false

    private class Ipv4PreferredDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
            val ipv4 = addresses.filter { it is Inet4Address }
            return if (ipv4.isNotEmpty()) ipv4 else addresses
        }
    }

    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Ipv4PreferredDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun startDownload(
        url: String,
        targetFile: File,
        coroutineScope: CoroutineScope
    ) {
        isCancelled = false
        downloadJob = coroutineScope.launch(Dispatchers.IO) {
            downloadFile(url, targetFile)
        }
    }

    fun cancelDownload() {
        isCancelled = true
        downloadJob?.cancel()
        _progress.value = DownloadProgress(state = DownloadState.IDLE)
    }

    /**
     * 规范化下载 URL
     * - dn.qpyou.cn 仅支持 HTTP，必须用 HTTP
     * - 其他域名保持原样
     */
    private fun normalizeUrl(url: String): String {
        return if (url.contains("dn.qpyou.cn")) {
            url.replace("https://", "http://")
        } else {
            url
        }
    }

    private suspend fun downloadFile(url: String, targetFile: File) {
        try {
            _progress.value = DownloadProgress(state = DownloadState.DOWNLOADING)

            val actualUrl = normalizeUrl(url)
            if (actualUrl != url) {
                Log.i(TAG, "URL 协议修正: $url → $actualUrl")
            }

            // 断点续传：检查已有文件大小
            var downloadedBytes = 0L
            val requestBuilder = Request.Builder()
                .url(actualUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

            if (targetFile.exists() && targetFile.length() > 0) {
                downloadedBytes = targetFile.length()
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            val responseCode = response.code
            val totalBytes: Long

            when {
                responseCode == 200 -> {
                    totalBytes = response.body?.contentLength() ?: -1L
                    downloadedBytes = 0L
                    // 删除旧文件再创建新的
                    if (targetFile.exists()) targetFile.delete()
                }
                responseCode == 206 -> {
                    val remainingBytes = response.body?.contentLength() ?: -1L
                    totalBytes = if (remainingBytes > 0) downloadedBytes + remainingBytes else -1L
                }
                else -> {
                    _progress.value = DownloadProgress(
                        state = DownloadState.FAILED,
                        filePath = targetFile.absolutePath
                    )
                    throw Exception("HTTP $responseCode, URL: $actualUrl")
                }
            }

            val finalUrl = response.request.url.toString()
            Log.i(TAG, "开始下载: $actualUrl → $finalUrl, 总大小: $totalBytes, 已下载: $downloadedBytes")

            val body = response.body
            if (body == null) {
                _progress.value = DownloadProgress(
                    state = DownloadState.FAILED,
                    filePath = targetFile.absolutePath
                )
                Log.e(TAG, "响应 body 为空")
                return
            }

            // 速度计算变量
            var lastSpeedBytes = downloadedBytes
            var lastSpeedTime = System.currentTimeMillis()
            var currentSpeed = 0L

            body.byteStream().use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0L).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            _progress.value = DownloadProgress(state = DownloadState.IDLE)
                            Log.i(TAG, "下载已取消")
                            return
                        }

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastSpeedTime >= SPEED_SAMPLE_INTERVAL) {
                            currentSpeed = (downloadedBytes - lastSpeedBytes) * 1000 / (now - lastSpeedTime)
                            lastSpeedBytes = downloadedBytes
                            lastSpeedTime = now
                        }

                        _progress.value = DownloadProgress(
                            state = DownloadState.DOWNLOADING,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            speed = currentSpeed,
                            filePath = targetFile.absolutePath
                        )
                    }
                }
            }

            if (!isCancelled) {
                // 校验下载结果
                val fileSize = targetFile.length()
                if (fileSize == 0L) {
                    Log.e(TAG, "下载文件大小为 0，下载失败")
                    _progress.value = DownloadProgress(
                        state = DownloadState.FAILED,
                        filePath = targetFile.absolutePath
                    )
                    targetFile.delete()
                    return
                }

                _progress.value = DownloadProgress(
                    state = DownloadState.DOWNLOADED,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    speed = 0,
                    filePath = targetFile.absolutePath
                )
                Log.i(TAG, "下载完成: ${targetFile.absolutePath}, 大小: ${fileSize / 1024 / 1024} MB")
            }

        } catch (e: CancellationException) {
            _progress.value = DownloadProgress(state = DownloadState.IDLE)
            Log.i(TAG, "下载被取消")
            if (targetFile.exists()) {
                targetFile.delete()
                Log.i(TAG, "已清理下载临时文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载失败: ${e.message}", e)
            if (targetFile.exists()) {
                targetFile.delete()
                Log.i(TAG, "已清理下载临时文件")
            }
            _progress.value = DownloadProgress(
                state = DownloadState.FAILED,
                filePath = targetFile.absolutePath
            )
        }
    }

    fun reset() {
        cancelDownload()
        _progress.value = DownloadProgress()
    }
}
