package com.swupdater.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 壁纸管理器
 * 负责壁纸的下载、缓存、随机选择和自动更换
 */
object WallpaperManager {

    private const val TAG = "WallpaperManager"
    private const val PREFS_NAME = "sw_updater_prefs"
    private const val PREF_AUTO_CHANGE = "pref_auto_change_wallpaper"
    private const val PREF_CURRENT_WALLPAPER = "pref_current_wallpaper"
    private const val PREF_CACHE_COUNT = "pref_wallpaper_cache_count"

    private const val DEFAULT_CACHE_COUNT = 10
    private const val WALLPAPER_DIR = "wallpapers"

    /** 壁纸遮罩透明度：0=全透明，100=全不透明，默认70 */
    private const val PREF_OVERLAY_ALPHA = "pref_wallpaper_overlay_alpha"
    private const val DEFAULT_OVERLAY_ALPHA = 70

    /** 自定义缓存下载目录路径 */
    private const val PREF_CUSTOM_DOWNLOAD_DIR = "pref_custom_download_dir"

    // ========== 目录与缓存 ==========

    /**
     * 获取壁纸缓存目录（应用外部存储，无需额外权限）
     * 路径: /Android/data/<package>/files/Pictures/SWUpdater/wallpapers
     * 缓存是应用内部使用的，无需用户直接访问
     */
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
     * 无公共目录权限时回退到应用私有目录
     */
    fun getWallpaperDownloadDir(context: Context): File {
        val customPath = getPrefs(context).getString(PREF_CUSTOM_DOWNLOAD_DIR, null)
        if (!customPath.isNullOrEmpty()) {
            val dir = File(customPath)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        // Android 10+ 需要检查是否有公共目录写入权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!android.os.Environment.isExternalStorageManager()) {
                // 无权限时回退到应用私有目录
                val fallbackDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "SWUpdater/wallpapers"
                )
                if (!fallbackDir.exists()) fallbackDir.mkdirs()
                return fallbackDir
            }
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
     * 检查是否具有公共目录写入权限（MANAGE_EXTERNAL_STORAGE）
     * Android 10+ 需要此权限才能写入公共下载目录
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * 获取请求 MANAGE_EXTERNAL_STORAGE 权限的 Intent
     * 用于引导用户前往设置页面授权
     */
    fun getStoragePermissionIntent(context: Context): android.content.Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        } else {
            null
        }
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
     * 预加载壁纸：复制内置壁纸到缓存
     * 返回本次新添加的数量
     */
    fun preloadWallpapers(context: Context): Int {
        val added = copyBuiltInWallpapers(context)
        Log.i(TAG, "预加载: 复制内置壁纸 $added 张")
        return added
    }

    /**
     * 清理超出上限的旧缓存（保留内置壁纸）
     */
    private fun trimCache(context: Context, maxCount: Int) {
        val cached = getCachedWallpapers(context)
        val currentName = getCurrentWallpaperName(context)

        // 保留内置壁纸，按时间排序删除最旧的非内置壁纸
        val downloadedFiles = cached
            .filter { it.name !in BUILT_IN_WALLPAPERS }
            .sortedBy { it.lastModified() }

        var toDelete = cached.size - maxCount
        for (file in downloadedFiles) {
            if (toDelete <= 0) break
            if (file.name == currentName) continue
            if (file.delete()) {
                toDelete--
                Log.d(TAG, "清理旧壁纸: ${file.name}")
            }
        }
    }

    /**
     * 清除壁纸缓存（保留内置壁纸）
     */
    fun clearCache(context: Context): Int {
        val dir = getWallpaperDir(context)
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in BUILT_IN_WALLPAPERS) {
                if (file.delete()) count++
            }
        }
        // 如果当前壁纸不是内置的，也清除
        val currentName = getCurrentWallpaperName(context)
        if (currentName != null && currentName !in BUILT_IN_WALLPAPERS) {
            setCurrentWallpaperName(context, null)
        }
        return count
    }

    // ========== 默认壁纸 ==========

    /** 内置壁纸文件名列表 */
    private val BUILT_IN_WALLPAPERS = listOf(
        "library_01.png", "library_02.png", "library_03.png", "library_04.png", "library_05.png",
        "library_06.png", "library_07.png", "library_08.png", "library_09.png", "library_10.png",
        "library_11.png", "library_12.png", "library_13.png", "library_14.png", "library_15.png"
    )

    /**
     * 将内置壁纸从 assets 复制到缓存目录
     * 返回复制的壁纸文件数
     */
    private fun copyBuiltInWallpapers(context: Context): Int {
        val dir = getWallpaperDir(context)
        if (!dir.exists()) dir.mkdirs()

        var copied = 0
        for (filename in BUILT_IN_WALLPAPERS) {
            val targetFile = File(dir, filename)
            if (!targetFile.exists()) {
                try {
                    context.assets.open("wallpapers/$filename").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    copied++
                    Log.i(TAG, "复制内置壁纸: $filename")
                } catch (e: Exception) {
                    Log.e(TAG, "复制内置壁纸失败: $filename", e)
                }
            }
        }
        return copied
    }

    /**
     * 确保有默认壁纸可用
     * 优先使用已有当前壁纸或缓存壁纸，没有则从 assets 复制内置壁纸
     * 返回当前壁纸文件
     */
    fun ensureDefaultWallpaper(context: Context): File? {
        val current = getCurrentWallpaperFile(context)
        if (current != null) return current

        val cached = getCachedWallpapers(context)
        if (cached.isNotEmpty()) {
            val picked = cached.random()
            setCurrentWallpaperName(context, picked.name)
            return picked
        }

        // 没有缓存，复制内置壁纸
        copyBuiltInWallpapers(context)
        val newCached = getCachedWallpapers(context)
        if (newCached.isNotEmpty()) {
            val picked = newCached.random()
            setCurrentWallpaperName(context, picked.name)
            return picked
        }

        return null
    }

    // ========== 随机换壁纸（含下载） ==========

    /**
     * 随机换壁纸：从缓存中随机选择一张
     */
    fun randomWallpaper(context: Context): File? {
        ensureDefaultWallpaper(context)
        val picked = pickRandomFromCache(context)
        if (picked != null) {
            Log.i(TAG, "随机更换壁纸: ${picked.name}")
        }
        return picked
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
    fun downloadCurrentWallpaper(context: Context): WallpaperDownloadResult {
        val currentFile = getCurrentWallpaperFile(context)
        if (currentFile == null || !currentFile.exists()) {
            val picked = randomWallpaper(context)
            if (picked == null) return WallpaperDownloadResult(
                success = false, error = "没有可用的壁纸"
            )
        }

        val source = getCurrentWallpaperFile(context) ?: return WallpaperDownloadResult(
            success = false, error = "壁纸文件不存在"
        )
        val downloadDir = getWallpaperDownloadDir(context)
        val targetFile = resolveUniqueFile(downloadDir, source.name)

        try {
            source.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            notifyMediaScanner(context, targetFile)
            Log.i(TAG, "壁纸已保存到: ${targetFile.absolutePath}")
            return WallpaperDownloadResult(
                success = true,
                filePath = targetFile.absolutePath,
                fileName = targetFile.name
            )
        } catch (e: Exception) {
            Log.e(TAG, "保存壁纸失败", e)
            return WallpaperDownloadResult(success = false, error = e.message ?: "保存失败")
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

    /**
     * 通知媒体库扫描文件，使文件在图库/文件管理器中立即可见
     * 已通过 File API 写入公共目录，只需通知系统扫描即可
     */
    private fun notifyMediaScanner(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf(getMimeType(file.name)), null)
        } catch (e: Exception) {
            Log.w(TAG, "通知媒体库扫描失败: ${e.message}")
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
