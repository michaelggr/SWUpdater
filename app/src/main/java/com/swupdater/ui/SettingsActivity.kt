package com.swupdater.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swupdater.BuildConfig
import com.swupdater.R
import com.swupdater.network.VersionCheckService
import com.swupdater.util.AppLog
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.FileUtil
import com.swupdater.util.WallpaperManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class SettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_settings)
        toolbar.setNavigationOnClickListener { finish() }

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        // 应用自身更新相关变量
        private var selfUpdateDownloadDialog: Dialog? = null
        private var selfUpdateDownloadJob: Job? = null
        private var selfUpdateApkFile: File? = null
        private var shouldCancelSelfUpdate = false

        private val storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (WallpaperManager.hasStoragePermission(requireContext())) {
                openDirectoryPicker()
            } else {
                Toast.makeText(requireContext(), "存储权限未授予", Toast.LENGTH_SHORT).show()
            }
        }

        private val directoryPickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            uri?.let {
                // 授予持久化权限
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                // 获取目录路径
                val path = DocumentsContract.getTreeDocumentId(uri)
                val documentId = path.split(":").last()
                val dirPath = "/storage/emulated/0/$documentId"

                // 保存设置
                WallpaperManager.setCustomDownloadDir(requireContext(), dirPath)

                // 更新摘要
                findPreference<Preference>("pref_custom_download_dir")?.summary = dirPath

                Toast.makeText(requireContext(), "壁纸下载目录已设置为: $dirPath", Toast.LENGTH_SHORT).show()
            }
        }

        private fun openDirectoryPicker() {
            directoryPickerLauncher.launch(null)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            // === 外观设置 ===
            val appearanceCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "外观"
            }
            screen.addPreference(appearanceCategory)

            // 主题模式（日间/夜间/跟随系统）
            DropDownPreference(context).apply {
                key = "pref_theme_mode"
                title = "主题模式"
                entries = arrayOf("跟随系统", "日间模式", "夜间模式")
                entryValues = arrayOf("-1", "1", "2")
                setDefaultValue("-1")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    val mode = (newValue as String).toInt()
                    AppCompatDelegate.setDefaultNightMode(mode)
                    true
                }
                appearanceCategory.addPreference(this)
            }

            // === 壁纸设置 ===
            val wallpaperCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "壁纸"
            }
            screen.addPreference(wallpaperCategory)

            // 启动时自动更换壁纸
            SwitchPreferenceCompat(context).apply {
                key = "pref_auto_change_wallpaper"
                title = getString(R.string.pref_auto_change_wallpaper)
                summary = getString(R.string.pref_auto_change_wallpaper_summary)
                setDefaultValue(true)
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    WallpaperManager.setAutoChangeEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "自动更换壁纸已${if (enabled) "开启" else "关闭"}")
                    true
                }
                wallpaperCategory.addPreference(this)
            }



            // 缓存壁纸数量
            DropDownPreference(context).apply {
                key = "pref_wallpaper_cache_count"
                title = getString(R.string.pref_wallpaper_cache_count)
                summary = getString(R.string.pref_wallpaper_cache_count_summary)
                entries = arrayOf("5张", "10张", "15张", "20张")
                entryValues = arrayOf("5", "10", "15", "20")
                setDefaultValue("10")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    val count = (newValue as String).toIntOrNull() ?: 10
                    WallpaperManager.setCacheCountPref(requireContext(), count)
                    true
                }
                wallpaperCategory.addPreference(this)
            }

            // 清除壁纸缓存
            Preference(context).apply {
                key = "pref_clear_wallpaper_cache"
                title = getString(R.string.pref_clear_wallpaper_cache)
                summary = getString(R.string.pref_clear_wallpaper_cache_summary)
                setOnPreferenceClickListener {
                    val count = WallpaperManager.clearCache(requireContext())
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.wallpaper_cache_cleared)} ($count 张)",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                wallpaperCategory.addPreference(this)
            }

            // 壁纸下载目录
            androidx.preference.Preference(context).apply {
                key = "pref_custom_download_dir"
                title = getString(R.string.pref_wallpaper_download_dir)
                val currentDir = WallpaperManager.getCustomDownloadDir(requireContext())
                summary = currentDir ?: "默认（公用 Download/SWUpdater/wallpapers）"
                setOnPreferenceClickListener {
                    // 先检查存储权限
                    if (WallpaperManager.hasStoragePermission(requireContext())) {
                        openDirectoryPicker()
                    } else {
                        // 请求存储权限
                        val intent = WallpaperManager.getStoragePermissionIntent(requireContext())
                        if (intent != null) {
                            storagePermissionLauncher.launch(intent)
                        }
                    }
                    true
                }
                wallpaperCategory.addPreference(this)
            }

            // === 更新设置 ===
            val updateCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "更新设置"
            }
            screen.addPreference(updateCategory)

            SwitchPreferenceCompat(context).apply {
                key = "pref_auto_check_on_launch"
                title = getString(R.string.pref_auto_check_on_launch)
                summary = getString(R.string.pref_auto_check_on_launch_summary)
                setDefaultValue(true)
                updateCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_auto_check"
                title = getString(R.string.pref_auto_check)
                summary = getString(R.string.pref_auto_check_summary)
                setDefaultValue(true)
                updateCategory.addPreference(this)
            }

            DropDownPreference(context).apply {
                key = "pref_check_interval"
                title = getString(R.string.pref_check_interval)
                entries = arrayOf(
                    getString(R.string.interval_1h),
                    getString(R.string.interval_3h),
                    getString(R.string.interval_6h),
                    getString(R.string.interval_12h),
                    getString(R.string.interval_24h)
                )
                entryValues = arrayOf("1", "3", "6", "12", "24")
                setDefaultValue("6")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
                updateCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_auto_download"
                title = getString(R.string.pref_auto_download)
                summary = getString(R.string.pref_auto_download_summary)
                setDefaultValue(true) // 有权限默认开启
                updateCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_wifi_only"
                title = getString(R.string.pref_wifi_only)
                summary = getString(R.string.pref_wifi_only_summary)
                setDefaultValue(true)
                updateCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_auto_launch_game"
                title = getString(R.string.pref_auto_launch_game)
                summary = getString(R.string.pref_auto_launch_game_summary)
                setDefaultValue(true)
                updateCategory.addPreference(this)
            }

            // === 后台保活设置 ===
            val keepAliveCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "后台保活"
            }
            screen.addPreference(keepAliveCategory)

            // 保活开关 - 有权限默认开启
            SwitchPreferenceCompat(context).apply {
                key = "pref_keep_alive_enabled"
                title = "启用后台保活"
                summary = "保持应用在后台运行，确保自动检查更新不被中断"
                setDefaultValue(true) // 有权限默认开启
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    com.swupdater.service.KeepAliveService.setEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "后台保活已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            // 开机自启动 - 有权限默认开启
            SwitchPreferenceCompat(context).apply {
                key = "pref_boot_auto_start"
                title = "开机自启动"
                summary = "设备重启后自动启动应用并开启保活服务"
                setDefaultValue(true) // 有权限默认开启
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    com.swupdater.service.KeepAliveService.setBootAutoStartEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "开机自启已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            // 电池优化白名单
            Preference(context).apply {
                key = "pref_battery_optimization"
                title = "忽略电池优化"
                summary = if (com.swupdater.service.KeepAliveService.isIgnoringBatteryOptimization(requireContext())) {
                    "已加入电池优化白名单 ✓"
                } else {
                    "未加入白名单，可能被系统限制后台运行，点击设置"
                }
                setOnPreferenceClickListener {
                    com.swupdater.service.KeepAliveService.requestIgnoreBatteryOptimization(requireContext())
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            // Root保活 - 有权限默认开启
            SwitchPreferenceCompat(context).apply {
                key = "pref_root_keep_alive"
                title = "Root 保活"
                summary = if (com.swupdater.service.KeepAliveService.isDeviceRooted()) {
                    "已检测到Root权限，启用后可通过Root提升保活能力"
                } else {
                    "未检测到Root权限，此功能需要已Root的设备"
                }
                setDefaultValue(true) // 有权限默认开启
                isEnabled = com.swupdater.service.KeepAliveService.isDeviceRooted()
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    com.swupdater.service.KeepAliveService.setRootKeepAliveEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "Root保活已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            // Root 自动安装
            SwitchPreferenceCompat(context).apply {
                key = "pref_root_auto_install"
                title = "Root 自动安装"
                val isRooted = com.swupdater.util.RootInstallHelper.isDeviceRooted()
                summary = if (isRooted) {
                    "已检测到Root权限，下载完成后自动静默安装，无需手动确认"
                } else {
                    "未检测到Root权限，此功能需要已Root的设备"
                }
                setDefaultValue(isRooted) // 有 Root 默认开启
                isEnabled = isRooted
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    AppLog.i("Settings", "Root自动安装已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            // 保活说明
            Preference(context).apply {
                key = "pref_keep_alive_info"
                title = "保活说明"
                summary = "后台保活采用前台服务通知栏、WakeLock防休眠、电量优化白名单、开机自启等多种方式保护应用后台运行。Root保活可通过降低OOM优先级提升存活率。"
                isSelectable = false
                keepAliveCategory.addPreference(this)
            }

            // === 安全设置 ===
            val securityCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "安全设置"
            }
            screen.addPreference(securityCategory)

            SwitchPreferenceCompat(context).apply {
                key = "pref_verify_integrity"
                title = getString(R.string.pref_verify_integrity)
                summary = getString(R.string.pref_verify_integrity_summary)
                setDefaultValue(true)
                securityCategory.addPreference(this)
            }

            // === 调试设置 ===
            val debugCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "调试"
            }
            screen.addPreference(debugCategory)

            // 日志模式开关
            SwitchPreferenceCompat(context).apply {
                key = "pref_log_mode"
                title = "日志模式"
                summary = "开启后将检测所有数据源并记录详细日志，便于排查版本检测问题"
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    AppLog.setLogModeEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "日志模式已${if (enabled) "开启" else "关闭"}")
                    true
                }
                debugCategory.addPreference(this)
            }

            // 测试自动下载
            Preference(context).apply {
                key = "pref_test_auto_download"
                title = "测试自动下载"
                summary = "立即触发一次后台版本检查，模拟自动下载流程。需开启「自动下载」且在WiFi环境下才会自动下载"
                setOnPreferenceClickListener {
                    com.swupdater.service.VersionCheckWorker.scheduleOneTimeCheck(requireContext())
                    Toast.makeText(requireContext(), "已触发后台版本检查，请查看通知栏", Toast.LENGTH_LONG).show()
                    AppLog.i("Settings", "手动触发自动下载测试")
                    true
                }
                debugCategory.addPreference(this)
            }

            // 查看日志
            Preference(context).apply {
                key = "pref_view_log"
                title = "查看日志"
                summary = "查看版本检测的详细日志信息"
                setOnPreferenceClickListener {
                    showLogDialog()
                    true
                }
                debugCategory.addPreference(this)
            }

            // 导出日志
            Preference(context).apply {
                key = "pref_export_log"
                title = "导出日志"
                summary = "将日志导出到文件，便于分享和排查问题"
                setOnPreferenceClickListener {
                    AppLog.flushToFile(requireContext())
                    val logFile = AppLog.getLogFile(requireContext())
                    Toast.makeText(
                        requireContext(),
                        "日志已导出至: ${logFile.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }
                debugCategory.addPreference(this)
            }

            // === 数据源设置 ===
            val sourceCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "数据源"
            }
            screen.addPreference(sourceCategory)

            androidx.preference.ListPreference(context).apply {
                key = "pref_source_url"
                title = getString(R.string.pref_source_url)
                summary = VersionCheckService.DEFAULT_SOURCE_URL
                entries = arrayOf(
                    "友皆乐（官方推荐）",
                    "备用1",
                    "备用2"
                )
                entryValues = arrayOf(
                    "https://play.qpyou.cn/b?i=8387&g=8109&gc=7976",
                    "https://example.com/backup1", // 需要实际URL
                    "https://example.com/backup2"  // 需要实际URL
                )
                setDefaultValue(VersionCheckService.DEFAULT_SOURCE_URL)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = newValue.toString()
                    true
                }
                sourceCategory.addPreference(this)
            }

            androidx.preference.EditTextPreference(context).apply {
                key = "pref_package_name"
                title = getString(R.string.pref_package_name)
                summary = AppInfoUtil.PACKAGE_NAME_CN
                setDefaultValue(AppInfoUtil.PACKAGE_NAME_CN)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = newValue.toString()
                    true
                }
                sourceCategory.addPreference(this)
            }

            // === 其他 ===
            val otherCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "其他"
            }
            screen.addPreference(otherCategory)

            Preference(context).apply {
                key = "pref_clear_cache"
                title = getString(R.string.pref_clear_cache)
                summary = getString(R.string.pref_clear_cache_summary)
                setOnPreferenceClickListener {
                    val count = FileUtil.clearDownloadCache(requireContext())
                    Toast.makeText(
                        requireContext(),
                        "${getString(R.string.cache_cleared)} ($count 个文件)",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                otherCategory.addPreference(this)
            }

            Preference(context).apply {
                key = "pref_version"
                title = getString(R.string.pref_version)
                summary = "v${BuildConfig.VERSION_NAME}"
                isSelectable = true
                setOnPreferenceClickListener {
                    // 检查应用自身的新版本
                    checkSelfUpdate(BuildConfig.VERSION_NAME)
                    true
                }
                otherCategory.addPreference(this)
            }

            preferenceScreen = screen
        }

        private fun showLogDialog() {
            val logText = AppLog.getLogText()
            val displayText = if (logText.isBlank()) "暂无日志，请先执行一次版本检查" else logText

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("检测日志")
                .setMessage(displayText)
                .setPositiveButton("确定", null)
                .setNeutralButton("复制") { _, _ ->
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("日志", displayText))
                    Toast.makeText(requireContext(), "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        /**
         * 检查本应用自身是否有新版本
         * 从 GitHub Release 获取最新版本号和 APK 下载链接
         * 发现新版本后显示下载地址选择对话框
         */
        private fun checkSelfUpdate(currentVersion: String) {
            val pref = findPreference<Preference>("pref_version")
            pref?.summary = "正在检查更新…"

            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        // 检查版本前先清理旧安装包
                        val clearedCount = FileUtil.clearSelfUpdateCache(requireContext())
                        if (clearedCount > 0) {
                            AppLog.i("Settings", "已清除 $clearedCount 个旧安装包")
                        }

                        // GitHub API 镜像列表（原版 + 国内加速），依次尝试
                        val apiMirrors = listOf(
                            "https://api.github.com/repos/michaelggr/SWUpdater/releases/latest",
                            "https://ghgo.xyz/https://api.github.com/repos/michaelggr/SWUpdater/releases/latest",
                            "https://gh-proxy.com/https://api.github.com/repos/michaelggr/SWUpdater/releases/latest",
                            "https://mirror.ghproxy.com/https://api.github.com/repos/michaelggr/SWUpdater/releases/latest",
                            "https://api.kgithub.com/repos/michaelggr/SWUpdater/releases/latest",
                            "https://hub.fastgit.xyz/michaelggr/SWUpdater/releases/latest",
                            "https://gitclone.com/api/github.com/repos/michaelggr/SWUpdater/releases/latest",
                            "https://gh.jianmu.dev/api.github.com/repos/michaelggr/SWUpdater/releases/latest"
                        )

                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                            .build()

                        for (url in apiMirrors) {
                            try {
                                val request = okhttp3.Request.Builder().url(url).build()
                                val response = client.newCall(request).execute()
                                if (!response.isSuccessful) {
                                    response.close()
                                    continue
                                }
                                val body = response.body?.string()
                                response.close()
                                if (body.isNullOrBlank()) continue

                                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                                val tagName = json.get("tag_name")?.asString ?: continue
                                val versionName = tagName.removePrefix("v")

                                // 提取 APK 下载链接
                                var apkUrl = ""
                                val assets = json.get("assets")?.asJsonArray
                                if (assets != null) {
                                    for (asset in assets) {
                                        val assetObj = asset.asJsonObject
                                        val name = assetObj.get("name")?.asString ?: ""
                                        if (name.endsWith(".apk", ignoreCase = true)) {
                                            apkUrl = assetObj.get("browser_download_url")?.asString ?: ""
                                            break
                                        }
                                    }
                                }

                                return@withContext Triple(versionName, apkUrl, url)
                            } catch (_: Exception) {
                                continue
                            }
                        }
                        null
                    }

                    if (result == null) {
                        pref?.summary = "v$currentVersion（检查失败，稍后重试）"
                        Toast.makeText(requireContext(), "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val (latestVersion, apkUrl, _) = result

                    if (latestVersion != currentVersion) {
                        pref?.summary = "v$currentVersion → v$latestVersion 有新版本！"
                        // 显示下载地址选择对话框
                        showSelfUpdateDownloadChoiceDialog(currentVersion, latestVersion, apkUrl)
                    } else {
                        pref?.summary = "v$currentVersion（已是最新版本）"
                        Toast.makeText(requireContext(), "当前已是最新版本 v$currentVersion", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    pref?.summary = "v$currentVersion（检查失败）"
                    Toast.makeText(requireContext(), "检查更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    AppLog.e("Settings", "检查应用更新失败: ${e.message}")
                }
            }
        }

        /**
         * 显示应用自身更新的下载地址选择对话框
         */
        private fun showSelfUpdateDownloadChoiceDialog(currentVersion: String, latestVersion: String, apkUrl: String) {
            // 下载链接镜像列表
            val downloadMirrors = mutableListOf<Pair<String, String>>()
            
            // 如果有直链，添加到开头
            if (apkUrl.isNotEmpty()) {
                downloadMirrors.add("直接下载（推荐）" to apkUrl)
                // 添加加速镜像
                downloadMirrors.add("ghgo 加速" to "https://ghgo.xyz/$apkUrl")
                downloadMirrors.add("gh-proxy 加速" to "https://gh-proxy.com/$apkUrl")
                downloadMirrors.add("ghproxy 加速" to "https://mirror.ghproxy.com/$apkUrl")
            }
            
            // 添加入口页面
            downloadMirrors.add("GitHub Release 页面" to "https://github.com/michaelggr/SWUpdater/releases/latest")

            val mirrorNames = downloadMirrors.map { it.first }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("发现新版本 v$latestVersion")
                .setMessage("当前版本: v$currentVersion\n最新版本: v$latestVersion\n\n请选择下载方式：")
                .setItems(mirrorNames) { _, which ->
                    val url = downloadMirrors[which].second
                    if (which == 0 && apkUrl.isNotEmpty()) {
                        // 选择直接下载，显示下载对话框
                        showSelfUpdateDownloadDialog(url, latestVersion)
                    } else if (which <= 3 && apkUrl.isNotEmpty()) {
                        // 选择加速下载
                        showSelfUpdateDownloadDialog(url, latestVersion)
                    } else {
                        // 选择打开页面
                        try {
                            startActivity(Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url)
                            ))
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("稍后", null)
                .show()
        }

        /**
         * 显示应用自身更新的下载对话框
         */
        private fun showSelfUpdateDownloadDialog(url: String, versionName: String) {
            shouldCancelSelfUpdate = false

            // 创建对话框
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_self_update_download, null)
            val dialog = Dialog(requireContext())
            dialog.setContentView(dialogView)
            dialog.setCancelable(false)

            val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
            val progressBar = dialogView.findViewById<ProgressBar>(R.id.progress_bar)
            val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
            val tvSizeInfo = dialogView.findViewById<TextView>(R.id.tv_size_info)
            val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
            val btnInstall = dialogView.findViewById<Button>(R.id.btn_install)

            tvTitle.text = "正在下载 SWUpdater v$versionName"

            // 取消按钮
            btnCancel.setOnClickListener {
                shouldCancelSelfUpdate = true
                selfUpdateDownloadJob?.cancel()
                dialog.dismiss()
                Toast.makeText(requireContext(), "下载已取消", Toast.LENGTH_SHORT).show()
            }

            // 安装按钮初始不可用
            btnInstall.isEnabled = false
            btnInstall.setOnClickListener {
                installApk()
                dialog.dismiss()
            }

            dialog.show()
            selfUpdateDownloadDialog = dialog

            // 开始下载
            selfUpdateDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    downloadSelfUpdateApk(url, versionName, progressBar, tvProgress, tvSizeInfo, btnInstall, tvTitle)
                } catch (e: Exception) {
                    AppLog.e("Settings", "下载失败: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        /**
         * 下载应用自身更新的 APK
         */
        private suspend fun downloadSelfUpdateApk(
            url: String,
            versionName: String,
            progressBar: ProgressBar,
            tvProgress: TextView,
            tvSizeInfo: TextView,
            btnInstall: Button,
            tvTitle: TextView
        ) = withContext(Dispatchers.IO) {
            // 准备下载文件
            val targetFile = FileUtil.getSelfUpdateApkFile(requireContext(), versionName)
            selfUpdateApkFile = targetFile

            // 确保目录存在
            targetFile.parentFile?.mkdirs()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("下载失败: ${response.code}")
            }

            val contentLength = response.body?.contentLength() ?: 0L
            val inputStream = response.body?.byteStream() ?: throw IOException("无法获取响应流")

            // 写入文件
            var bytesRead: Long = 0
            val buffer = ByteArray(8192)
            var readCount: Int

            targetFile.outputStream().use { outputStream ->
                while (inputStream.read(buffer).also { readCount = it } != -1) {
                    if (shouldCancelSelfUpdate) {
                        // 删除部分下载的文件
                        targetFile.delete()
                        return@withContext
                    }

                    outputStream.write(buffer, 0, readCount)
                    bytesRead += readCount

                    // 更新进度
                    if (contentLength > 0) {
                        val progress = (bytesRead * 100 / contentLength).toInt()
                        withContext(Dispatchers.Main) {
                            progressBar.progress = progress
                            tvProgress.text = "$progress%"
                            tvSizeInfo.text = "${formatSize(bytesRead)} / ${formatSize(contentLength)}"
                        }
                    }
                }
            }

            inputStream.close()

            // 下载完成
            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                tvProgress.text = "100%"
                tvSizeInfo.text = "${formatSize(bytesRead)} / ${formatSize(bytesRead)}"
                btnInstall.isEnabled = true
                tvTitle.text = "下载完成 v$versionName"
            }

            AppLog.i("Settings", "应用自身更新下载完成: ${targetFile.absolutePath}")
        }

        /**
         * 安装 APK
         */
        private fun installApk() {
            val apkFile = selfUpdateApkFile ?: return
            if (!apkFile.exists()) {
                Toast.makeText(requireContext(), "APK 文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    requireContext(),
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")

            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "无法安装 APK: ${e.message}", Toast.LENGTH_SHORT).show()
                AppLog.e("Settings", "安装 APK 失败: ${e.message}")
            }
        }

        /**
         * 格式化文件大小
         */
        private fun formatSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "${size / 1024 / 1024} MB"
            }
        }

        /**
         * 显示更新对话框，提供多个下载镜像
         */
        private fun showUpdateDialog(currentVersion: String, latestVersion: String, releaseUrl: String?) {
            // 下载链接镜像列表
            val downloadMirrors = listOf(
                "GitHub（原版）" to "https://github.com/michaelggr/SWUpdater/releases/latest",
                "ghgo 加速" to "https://ghgo.xyz/https://github.com/michaelggr/SWUpdater/releases/latest",
                "gh-proxy 加速" to "https://gh-proxy.com/https://github.com/michaelggr/SWUpdater/releases/latest",
                "ghproxy 加速" to "https://mirror.ghproxy.com/https://github.com/michaelggr/SWUpdater/releases/latest",
                "kgithub 加速" to "https://kgithub.com/michaelggr/SWUpdater/releases/latest",
                "FastGit 加速" to "https://hub.fastgit.xyz/michaelggr/SWUpdater/releases",
                "GitClone 加速" to "https://gitclone.com/github.com/michaelggr/SWUpdater/releases/latest"
            )

            val mirrorNames = downloadMirrors.map { it.first }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("发现新版本 v$latestVersion")
                .setMessage("当前版本: v$currentVersion\n最新版本: v$latestVersion\n\n请选择下载方式：")
                .setItems(mirrorNames) { _, which ->
                    val url = downloadMirrors[which].second
                    try {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        ))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }
}
