package com.swupdater.network

import com.swupdater.model.DownloadChannel

/**
 * 下载渠道配置
 *
 * 只保留核心下载渠道，数据源通过设置配置
 */
object DownloadChannels {

    val CHANNELS = listOf(
        // 首选：友皆乐 — 国内官方渠道，APK 直链下载
        DownloadChannel(
            id = "qpyou",
            name = "友皆乐（官方推荐）",
            url = "https://play.qpyou.cn/b?i=8387&g=8109&gc=7976",
            type = DownloadChannel.ChannelType.APK_DIRECT,
            description = "国内直连，APK 直链下载，无需VPN",
            isRecommended = true,
            requireVpn = false
        ),
    )

    fun getRecommendedChannels(): List<DownloadChannel> {
        return CHANNELS.filter { !it.requireVpn }
    }

    fun getAllChannels(): List<DownloadChannel> {
        return CHANNELS
    }

    fun getChannelById(id: String): DownloadChannel? {
        return CHANNELS.find { it.id == id }
    }

    fun getDefaultChannel(): DownloadChannel {
        return CHANNELS.first { it.isRecommended }
    }
}
