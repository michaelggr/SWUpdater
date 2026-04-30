package com.swupdater.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 壁纸管理器
 * 负责壁纸的下载、缓存、随机选择和自动更换
 */
object WallpaperManager {

    private const val TAG = "WallpaperManager"
    private const val PREFS_NAME = "sw_updater_prefs"
    private const val PREF_AUTO_CHANGE = "pref_auto_change_wallpaper"
    private const val PREF_CURRENT_WALLPAPER = "pref_current_wallpaper"
    private const val PREF_WALLPAPER_SOURCE = "pref_wallpaper_source"
    private const val PREF_CACHE_COUNT = "pref_wallpaper_cache_count"

    private const val DEFAULT_CACHE_COUNT = 10
    private const val WALLPAPER_DIR = "wallpapers"

    /** 壁纸遮罩透明度：0=全透明，100=全不透明，默认70 */
    private const val PREF_OVERLAY_ALPHA = "pref_wallpaper_overlay_alpha"
    private const val DEFAULT_OVERLAY_ALPHA = 70

    /** 自定义缓存下载目录路径 */
    private const val PREF_CUSTOM_DOWNLOAD_DIR = "pref_custom_download_dir"

    // ========== 壁纸源定义 ==========

    /**
     * 壁纸源：魔灵召唤官网场景图
     */
    object OfficialSource {
        const val NAME = "魔灵召唤官网"
        const val ID = "official"

        // 场景图（高质量，适合壁纸）
        val SCENE_URLS = listOf(
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_1_1.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_1_2.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_1_3.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_1_4.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_1_5.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_2_1.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_2_2.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_2_3.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_2_4.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_2_5.jpg"
        )

        // 中文场景图
        val ZH_SCENE_URLS = listOf(
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_3_1_zh-hans.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_3_2_zh-hans.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_3_3_zh-hans.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/home/game/scene/scene_3_4_zh-hans.jpg"
        )

        // 12周年庆典美术图
        val ANNIVERSARY_URLS = listOf(
            "https://event-fn.qpyou.cn/event/brand/smon_v2/event/12th_anniversary/assets/update3_screenshot_zh-hans.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/event/12th_anniversary/assets/update4_screenshot_zh-hans.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/event/12th_anniversary/assets/summonerswar_12anniv_1_cn.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/event/12th_anniversary/assets/summonerswar_12anniv_2_cn.jpg",
            "https://event-fn.qpyou.cn/event/brand/smon_v2/event/12th_anniversary/assets/summonerswar_DR.PLASMA.jpg"
        )

        // 所有可用壁纸URL
        val ALL_URLS: List<String> = SCENE_URLS + ZH_SCENE_URLS + ANNIVERSARY_URLS
    }

    /**
     * 壁纸源：SWC 电竞艺术壁纸（缩略图，适合手机壁纸）
     */
    object SwcArtSource {
        const val NAME = "SWC 电竞艺术"
        const val ID = "swc_art"

        // SWC 2025
        val SWC_2025 = listOf(
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2025_1.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2025_2.png"
        )

        // SWC 2024
        val SWC_2024 = listOf(
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_1.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_2.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_3.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_4.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_5.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_6.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_7.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_8.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_9.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_10.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_11.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_12.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2024_13.png"
        )

        // SWC 2023
        val SWC_2023 = listOf(
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_1.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_2.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_3.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_4.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_5.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_6.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_7.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_8.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_9.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_10.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_11.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_12.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_13.png",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2023_14.png"
        )

        // SWC 2022
        val SWC_2022 = listOf(
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_1.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_2.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_3.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_4.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_5.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_6.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_7.jpg",
            "https://hive-fn.qpyou.cn/markup/img/brand/smon/swc/art/thumb_v1/2022_8.jpg"
        )

        val ALL_URLS: List<String> = SWC_2025 + SWC_2024 + SWC_2023 + SWC_2022
    }

    /**
     * 壁纸源：官网资料库相册
     * 来源: summonerswar.com/zh-hans/skyarena/library 相册分类
     * 每个条目有缩略图(thumb)和点击后的大图(full)，这里用大图获得更高质量
     */
    object LibrarySource {
        const val NAME = "资料库相册"
        const val ID = "library"

        // 大图（点击后打开的高清版本）
        val FULL_URLS = listOf(
            "https://event-fn.qpyou.cn/event/event/smon/20260402_181247_NDbFPHRkFr.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_181138_AqUZSUlfLj.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_181112_v7VWuRjqEa.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_180959_tjgse1QQAb.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_180937_XN59WbmT2X.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260220_142225_BC9IwgltQD.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260220_142200_7VPS8VmMju.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153544_sqnK8ZGXzG.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153459_N6PLTC9GKS.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153523_2Xs55SmwBv.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153250_SnozU97RQ4.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251121_115521_jGG1H05JNh.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251121_115444_aTfX5iXdQR.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251020_133356_v0FHXXjJuD.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251020_133320_MIPrwD6t2u.png"
        )

        // 缩略图（列表展示用的较小版本）
        val THUMB_URLS = listOf(
            "https://event-fn.qpyou.cn/event/event/smon/20260402_181239_6gJHLaoOQk.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_181130_TPFd4IFiwP.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_181108_Wdb7TRok3o.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_180952_8JgLOa78Sm.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260402_180930_YVSMv9ZIAM.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260220_142220_5XeVmIleBC.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260220_142155_ekTxvqw8T2.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153538_gQNBVfwaZy.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153453_QVi6IqgNWV.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153517_rPuqtkuJcl.png",
            "https://event-fn.qpyou.cn/event/event/smon/20260115_153237_WNR6KOWi0y.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251121_115516_KVZuhuZYEd.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251121_115438_Lm2BWGBFoq.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251020_133351_iRqq5w1gLB.png",
            "https://event-fn.qpyou.cn/event/event/smon/20251020_133315_m7KFSsbwyu.png"
        )

        // 合并大图+缩略图（共30张，每个条目2个版本）
        val ALL_URLS: List<String> = FULL_URLS + THUMB_URLS
    }

    /**
     * 可选壁纸源列表
     */
    val SOURCES = listOf(
        LibrarySource.ID to LibrarySource.NAME,
        OfficialSource.ID to OfficialSource.NAME,
        SwcArtSource.ID to SwcArtSource.NAME
    )

    // ========== OkHttp 客户端 ==========

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ========== 目录与缓存 ==========

    /**
     * 获取壁纸缓存目录（应用私有外部存储目录）
     * 默认路径: /Android/data/<package>/files/Pictures/SWUpdater/wallpapers
     */
    @Suppress("UNUSED_PARAMETER")
    fun getWallpaperDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "Pictures")
        val dir = File(baseDir, "SWUpdater/$WALLPAPER_DIR")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取壁纸下载保存目录（用户可自定义）
     * 默认路径: /sdcard/Download/SWUpdater/wallpapers（公共目录，文件管理器可见）
     */
    fun getWallpaperDownloadDir(context: Context): File {
        val customPath = getPrefs(context).getString(PREF_CUSTOM_DOWNLOAD_DIR, null)
        if (!customPath.isNullOrEmpty()) {
            val dir = File(customPath)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SWUpdater/wallpapers"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 设置自定义下载保存目录
     */
    fun setCustomDownloadDir(context: Context, path: String?) {
        getPrefs(context).edit().putString(PREF_CUSTOM_DOWNLOAD_DIR, path).apply()
    }

    /**
     * 获取自定义下载保存目录路径（null表示使用默认）
     */
    fun getCustomDownloadDir(context: Context): String? {
        return getPrefs(context).getString(PREF_CUSTOM_DOWNLOAD_DIR, null)
    }

    /**
     * 获取已缓存的壁纸文件列表
     */
    fun getCachedWallpapers(context: Context): List<File> {
        val dir = getWallpaperDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jpg") || it.name.endsWith(".png") || it.name.endsWith(".webp")) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 获取缓存壁纸数量
     */
    fun getCachedCount(context: Context): Int = getCachedWallpapers(context).size

    /**
     * 获取设置中的缓存数量上限
     */
    fun getCacheCountPref(context: Context): Int {
        val prefs = getPrefs(context)
        return prefs.getInt(PREF_CACHE_COUNT, DEFAULT_CACHE_COUNT)
    }

    /**
     * 设置缓存数量上限
     */
    fun setCacheCountPref(context: Context, count: Int) {
        getPrefs(context).edit().putInt(PREF_CACHE_COUNT, count).apply()
    }

    // ========== 壁纸源 ==========

    /**
     * 获取当前壁纸源ID
     */
    fun getWallpaperSource(context: Context): String {
        return getPrefs(context).getString(PREF_WALLPAPER_SOURCE, LibrarySource.ID) ?: LibrarySource.ID
    }

    /**
     * 设置壁纸源
     */
    fun setWallpaperSource(context: Context, sourceId: String) {
        getPrefs(context).edit().putString(PREF_WALLPAPER_SOURCE, sourceId).apply()
    }

    /**
     * 根据源ID获取壁纸URL列表
     */
    fun getUrlsForSource(sourceId: String): List<String> {
        return when (sourceId) {
            LibrarySource.ID -> LibrarySource.ALL_URLS
            SwcArtSource.ID -> SwcArtSource.ALL_URLS
            else -> OfficialSource.ALL_URLS
        }
    }

    // ========== 自动更换 ==========

    /**
     * 是否启用启动时自动更换壁纸
     */
    fun isAutoChangeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_AUTO_CHANGE, true)
    }

    /**
     * 设置是否自动更换壁纸
     */
    fun setAutoChangeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_AUTO_CHANGE, enabled).apply()
    }

    // ========== 当前壁纸 ==========

    /**
     * 获取当前壁纸文件名
     */
    fun getCurrentWallpaperName(context: Context): String? {
        return getPrefs(context).getString(PREF_CURRENT_WALLPAPER, null)
    }

    /**
     * 设置当前壁纸文件名
     */
    fun setCurrentWallpaperName(context: Context, name: String?) {
        getPrefs(context).edit().putString(PREF_CURRENT_WALLPAPER, name).apply()
    }

    /**
     * 获取当前壁纸文件
     */
    fun getCurrentWallpaperFile(context: Context): File? {
        val name = getCurrentWallpaperName(context) ?: return null
        val file = File(getWallpaperDir(context), name)
        return if (file.exists()) file else null
    }

    /**
     * 获取当前壁纸Bitmap
     */
    fun getCurrentWallpaper(context: Context): Bitmap? {
        val file = getCurrentWallpaperFile(context)
        if (file != null && file.exists()) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        return null
    }

    // ========== 随机选择 ==========

    /**
     * 从缓存中随机选一张壁纸
     */
    fun pickRandomFromCache(context: Context): File? {
        val cached = getCachedWallpapers(context)
        if (cached.isEmpty()) return null
        val currentName = getCurrentWallpaperName(context)
        // 尽量不选当前这张
        val candidates = if (cached.size > 1 && currentName != null) {
            cached.filter { it.name != currentName }
        } else {
            cached
        }
        val picked = candidates.random()
        setCurrentWallpaperName(context, picked.name)
        return picked
    }

    // ========== 预加载与缓存 ==========

    /**
     * 预加载壁纸：确保缓存有足够数量的壁纸
     * 返回本次新下载的数量
     */
    suspend fun preloadWallpapers(context: Context): Int = withContext(Dispatchers.IO) {
        val sourceId = getWallpaperSource(context)
        val maxCount = getCacheCountPref(context)
        val cached = getCachedWallpapers(context)
        val existingNames = cached.map { it.name }.toSet()

        val urls = getUrlsForSource(sourceId).shuffled() // 随机顺序，确保多样性
        var downloaded = 0

        for (url in urls) {
            if (cached.size + downloaded >= maxCount) break

            val fileName = getFileNameFromUrl(url)
            if (fileName in existingNames) continue // 已缓存跳过

            try {
                val success = downloadWallpaper(context, url, fileName)
                if (success) {
                    downloaded++
                    Log.d(TAG, "预下载壁纸: $fileName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "预下载壁纸失败: $url, ${e.message}")
            }
        }

        // 清理超出上限的旧缓存
        trimCache(context, maxCount)

        Log.i(TAG, "壁纸预加载完成: 新下载 $downloaded 张, 缓存 ${getCachedCount(context)} 张")
        downloaded
    }

    /**
     * 下载单张壁纸到缓存目录
     */
    private suspend fun downloadWallpaper(context: Context, url: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return@withContext false
                }

                val targetFile = File(getWallpaperDir(context), fileName)
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                response.close()
                true
            } catch (e: Exception) {
                Log.e(TAG, "下载壁纸失败: $url", e)
                false
            }
        }

    /**
     * 清理超出上限的旧缓存
     */
    private fun trimCache(context: Context, maxCount: Int) {
        val cached = getCachedWallpapers(context).sortedBy { it.lastModified() }
        val currentName = getCurrentWallpaperName(context)
        var toDelete = cached.size - maxCount

        for (file in cached) {
            if (toDelete <= 0) break
            // 不删当前正在使用的壁纸
            if (file.name == currentName) continue
            if (file.delete()) {
                toDelete--
                Log.d(TAG, "清理旧壁纸: ${file.name}")
            }
        }
    }

    /**
     * 清除所有壁纸缓存
     */
    fun clearCache(context: Context): Int {
        val dir = getWallpaperDir(context)
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                if (file.delete()) count++
            }
        }
        setCurrentWallpaperName(context, null)
        return count
    }

    // ========== 默认壁纸 ==========

    /**
     * 确保有默认壁纸可用
     * 首次启动时从 assets 复制默认壁纸到缓存目录
     * 返回当前壁纸文件（可能是已有的，也可能是新复制的默认壁纸）
     */
    fun ensureDefaultWallpaper(context: Context): File? {
        // 已有当前壁纸，无需默认
        val current = getCurrentWallpaperFile(context)
        if (current != null) return current

        // 已有缓存壁纸，选一张
        val cached = getCachedWallpapers(context)
        if (cached.isNotEmpty()) {
            val picked = cached.random()
            setCurrentWallpaperName(context, picked.name)
            return picked
        }

        // 从 assets 复制默认壁纸
        try {
            val dir = getWallpaperDir(context)
            val targetFile = File(dir, "default_wallpaper.jpg")

            if (!targetFile.exists()) {
                context.assets.open("wallpapers/default_wallpaper.jpg").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "默认壁纸已复制到: ${targetFile.absolutePath}")
            }

            setCurrentWallpaperName(context, targetFile.name)
            return targetFile
        } catch (e: Exception) {
            Log.e(TAG, "复制默认壁纸失败", e)
            return null
        }
    }

    // ========== 随机换壁纸（含下载） ==========

    /**
     * 随机换壁纸：先从缓存中选，缓存不足则下载
     */
    suspend fun randomWallpaper(context: Context): File? = withContext(Dispatchers.IO) {
        // 确保有缓存
        if (getCachedCount(context) < 2) {
            preloadWallpapers(context)
        }

        // 从缓存中随机选
        val picked = pickRandomFromCache(context)
        if (picked != null) {
            Log.i(TAG, "随机更换壁纸: ${picked.name}")
        }
        picked
    }

    // ========== 壁纸下载到用户目录 ==========

    /**
     * 壁纸下载结果
     */
    data class WallpaperDownloadResult(
        val success: Boolean,
        val filePath: String = "",
        val fileName: String = "",
        val error: String = ""
    )

    /**
     * 将当前壁纸复制到用户可访问的下载目录
     * 返回下载结果（含文件名和完整路径）
     */
    suspend fun downloadCurrentWallpaper(context: Context): WallpaperDownloadResult = withContext(Dispatchers.IO) {
        val currentFile = getCurrentWallpaperFile(context)
        if (currentFile == null || !currentFile.exists()) {
            // 如果没有当前壁纸，先随机选一张
            val picked = randomWallpaper(context)
            if (picked == null) return@withContext WallpaperDownloadResult(
                success = false, error = "没有可用的壁纸"
            )
        }

        val source = getCurrentWallpaperFile(context) ?: return@withContext WallpaperDownloadResult(
            success = false, error = "壁纸文件不存在"
        )
        val downloadDir = getWallpaperDownloadDir(context)
        // 使用原始文件名，如果重名则加序号
        val targetFile = resolveUniqueFile(downloadDir, source.name)

        try {
            source.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "壁纸已保存到: ${targetFile.absolutePath}")
            WallpaperDownloadResult(
                success = true,
                filePath = targetFile.absolutePath,
                fileName = targetFile.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "保存壁纸失败", e)
            WallpaperDownloadResult(success = false, error = e.message ?: "保存失败")
        }
    }

    /**
     * 解析不重名的文件名
     */
    private fun resolveUniqueFile(dir: File, baseName: String): File {
        var target = File(dir, baseName)
        if (!target.exists()) return target
        val nameWithoutExt = baseName.substringBeforeLast(".")
        val ext = baseName.substringAfterLast(".", "jpg")
        var index = 1
        while (target.exists()) {
            target = File(dir, "${nameWithoutExt}_$index.$ext")
            index++
        }
        return target
    }

    /**
     * 获取壁纸遮罩透明度 (0-100, 0=全透, 100=全遮)
     */
    fun getOverlayAlpha(context: Context): Int {
        return getPrefs(context).getInt(PREF_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
    }

    /**
     * 设置壁纸遮罩透明度
     */
    fun setOverlayAlpha(context: Context, alpha: Int) {
        getPrefs(context).edit().putInt(PREF_OVERLAY_ALPHA, alpha.coerceIn(0, 100)).apply()
    }

    // ========== 工具方法 ==========

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getFileNameFromUrl(url: String): String {
        return url.substringAfterLast("/").substringBefore("?").ifEmpty { "wallpaper_${System.currentTimeMillis()}.jpg" }
    }
}
