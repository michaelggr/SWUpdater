package com.swupdater.network

import com.swupdater.model.DownloadProgress
import com.swupdater.model.DownloadState
import com.swupdater.util.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadManager {

    private const val TAG = "DownloadMgr"
    private const val BUFFER_SIZE = 8192
    private const val SPEED_SAMPLE_INTERVAL = 1000L

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    fun updateProgress(value: DownloadProgress) {
        _progress.value = value
    }

    private var downloadJob: Job? = null
    @Volatile
    private var isCancelled = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(NetworkUtil.Ipv4PreferredDns())
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
        AppLog.section(TAG, "启动下载任务")
        AppLog.i(TAG, "目标文件: ${targetFile.name}")
        downloadJob = coroutineScope.launch(Dispatchers.IO) {
            downloadFile(url, targetFile)
        }
    }

    fun cancelDownload() {
        isCancelled = true
        downloadJob?.cancel()
        _progress.value = DownloadProgress(state = DownloadState.IDLE)
        AppLog.w(TAG, "下载任务已取消")
    }

    private fun normalizeUrl(url: String): String = NetworkUtil.normalizeUrl(url)

    private suspend fun downloadFile(url: String, targetFile: File) {
        try {
            _progress.value = DownloadProgress(state = DownloadState.DOWNLOADING)

            val actualUrl = normalizeUrl(url)
            if (actualUrl != url) {
                AppLog.i(TAG, "URL 协议修正: HTTP( dn.qpyou.cn 不支持 HTTPS)")
            }

            var downloadedBytes = 0L
            val requestBuilder = Request.Builder()
                .url(actualUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

            if (targetFile.exists() && targetFile.length() > 0) {
                downloadedBytes = targetFile.length()
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
                AppLog.i(TAG, "断点续传: 已有 ${downloadedBytes / 1024} KB，从断点继续")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            val responseCode = response.code
            val totalBytes: Long

            when {
                responseCode == 200 -> {
                    totalBytes = response.body?.contentLength() ?: -1L
                    downloadedBytes = 0L
                    if (targetFile.exists()) targetFile.delete()
                    AppLog.i(TAG, "服务器响应 200，全新下载，总大小: ${formatSize(totalBytes)}")
                }
                responseCode == 206 -> {
                    val remainingBytes = response.body?.contentLength() ?: -1L
                    totalBytes = if (remainingBytes > 0) downloadedBytes + remainingBytes else -1L
                    AppLog.i(TAG, "服务器响应 206，续传成功，剩余: ${formatSize(remainingBytes)}")
                }
                else -> {
                    _progress.value = DownloadProgress(
                        state = DownloadState.FAILED,
                        filePath = targetFile.absolutePath
                    )
                    AppLog.e(TAG, "下载失败: HTTP $responseCode, URL: $actualUrl")
                    return
                }
            }

            val finalUrl = response.request.url.toString()
            AppLog.i(TAG, "下载源: $finalUrl")

            val body = response.body
            if (body == null) {
                _progress.value = DownloadProgress(
                    state = DownloadState.FAILED,
                    filePath = targetFile.absolutePath
                )
                AppLog.e(TAG, "下载失败: 响应 body 为空")
                return
            }

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
                            AppLog.w(TAG, "下载已取消")
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
                val fileSize = targetFile.length()
                if (fileSize == 0L) {
                    AppLog.e(TAG, "下载失败: 文件大小为 0")
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
                AppLog.section(TAG, "下载完成")
                AppLog.i(TAG, "文件: ${targetFile.name}, 大小: ${formatSize(fileSize)}")
            }

        } catch (e: CancellationException) {
            _progress.value = DownloadProgress(state = DownloadState.IDLE)
            AppLog.w(TAG, "下载被协程取消")
        } catch (e: Exception) {
            AppLog.e(TAG, "下载异常: ${e.message}")
            if (targetFile.exists() && targetFile.length() == 0L) {
                targetFile.delete()
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

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) "${"%.2f".format(mb / 1024)} GB" else "${"%.1f".format(mb)} MB"
    }
}
