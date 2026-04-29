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
        // 华为应用市场代理
        DownloadChannel(
            id = "huawei",
            name = "华为应用市场",
            url = "https://app.hicloud.com/app/C100000000", // 示例，需要实际URL
            type = DownloadChannel.ChannelType.CUSTOM,
            description = "华为应用市场下载",
            isRecommended = false,
            requireVpn = false
        ),
        // 小米应用商店代理
        DownloadChannel(
            id = "xiaomi",
            name = "小米应用商店",
            url = "https://app.mi.com/details?id=com.com2us.smon", // 示例
            type = DownloadChannel.ChannelType.CUSTOM,
            description = "小米应用商店下载",
            isRecommended = false,
            requireVpn = false
        ),
        // 腾讯应用宝代理
        DownloadChannel(
            id = "tencent",
            name = "腾讯应用宝",
            url = "https://a.app.qq.com/o/simple.jsp?pkgname=com.com2us.smon", // 示例
            type = DownloadChannel.ChannelType.CUSTOM,
            description = "腾讯应用宝下载",
            isRecommended = false,
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
