package com.swupdater.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import com.swupdater.BuildConfig
import com.swupdater.R
import com.swupdater.model.DownloadProgress
import com.swupdater.model.DownloadState
import com.swupdater.network.DownloadManager
import com.swupdater.network.VersionCheckService
import com.swupdater.util.AppLog
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.FileUtil
import com.swupdater.util.WallpaperManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        private var pendingDownloadDir: String? = null
        private var selfUpdateDialog: AlertDialog? = null
        private var progressJob: Job? = null

        private val storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (WallpaperManager.hasStoragePermission(requireContext())) {
                pendingDownloadDir?.let { path ->
                    WallpaperManager.setCustomDownloadDir(requireContext(), path)
                    pendingDownloadDir = null
                }
            } else {
                pendingDownloadDir = null
                view?.let {
                    Snackbar.make(it, "未授权存储权限，目录设置失败", Snackbar.LENGTH_SHORT).show()
                }
            }
        }

        private fun showStoragePermissionDialog() {
            activity?.let { ctx ->
                AlertDialog.Builder(ctx)
                    .setTitle("需要存储权限")
                    .setMessage("更改壁纸下载目录需要「所有文件访问」权限，是否前往设置授权？")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = WallpaperManager.getStoragePermissionIntent(ctx)
                        if (intent != null) {
                            storagePermissionLauncher.launch(intent)
                        }
                    }
                    .setNegativeButton("取消") { _, _ ->
                        pendingDownloadDir = null
                    }
                    .show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            val appearanceCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "外观"
            }
            screen.addPreference(appearanceCategory)

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

            val wallpaperCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "壁纸"
            }
            screen.addPreference(wallpaperCategory)

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

            androidx.preference.EditTextPreference(context).apply {
                key = "pref_custom_download_dir"
                title = getString(R.string.pref_wallpaper_download_dir)
                val currentDir = WallpaperManager.getCustomDownloadDir(requireContext())
                summary = currentDir ?: "默认（公用 Download/SWUpdater/wallpapers）"
                setDefaultValue("")
                setOnPreferenceChangeListener { _, newValue ->
                    val path = newValue.toString().trim()
                    if (path.isEmpty()) {
                        WallpaperManager.setCustomDownloadDir(requireContext(), null)
                        summary = "默认（公用 Download/SWUpdater/wallpapers）"
                    } else {
                        val dir = java.io.File(path)
                        if (!dir.exists() || !dir.isDirectory) {
                            Toast.makeText(requireContext(), "目录不存在，请输入有效路径", Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceChangeListener false
                        }
                        if (!WallpaperManager.hasStoragePermission(requireContext())) {
                            pendingDownloadDir = path
                            showStoragePermissionDialog()
                            return@setOnPreferenceChangeListener false
                        }
                        WallpaperManager.setCustomDownloadDir(requireContext(), path)
                        summary = path
                    }
                    true
                }
                wallpaperCategory.addPreference(this)
            }

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
                setDefaultValue(false)
                updateCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_wifi_only"
                title = getString(R.string.pref_wifi_only)
                summary = getString(R.string.pref_wifi_only_summary)
                setDefaultValue(true)
                updateCategory.addPreference(this)
            }

            val keepAliveCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "后台保活"
            }
            screen.addPreference(keepAliveCategory)

            SwitchPreferenceCompat(context).apply {
                key = "pref_keep_alive_enabled"
                title = "启用后台保活"
                summary = "保持应用在后台运行，确保自动检查更新不被中断"
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    com.swupdater.service.KeepAliveService.setEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "后台保活已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_boot_auto_start"
                title = "开机自启动"
                summary = "设备重启后自动启动应用并开启保活服务"
                setDefaultValue(true)
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    com.swupdater.service.KeepAliveService.setBootAutoStartEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "开机自启已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

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

            SwitchPreferenceCompat(context).apply {
                key = "pref_root_keep_alive"
                title = "Root 保活"
                summary = if (com.swupdater.service.KeepAliveService.isDeviceRooted()) {
                    "已检测到Root权限，启用后可通过Root提升保活能力"
                } else {
                    "未检测到Root权限，此功能需要已Root的设备"
                }
                setDefaultValue(false)
                isEnabled = com.swupdater.service.KeepAliveService.isDeviceRooted()
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    com.swupdater.service.KeepAliveService.setRootKeepAliveEnabled(requireContext(), enabled)
                    AppLog.i("Settings", "Root保活已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_root_auto_install"
                title = "Root 自动安装"
                val isRooted = com.swupdater.util.RootInstallHelper.isDeviceRooted()
                summary = if (isRooted) {
                    "已检测到Root权限，下载完成后自动静默安装，无需手动确认"
                } else {
                    "未检测到Root权限，此功能需要已Root的设备"
                }
                setDefaultValue(isRooted)
                isEnabled = isRooted
                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    AppLog.i("Settings", "Root自动安装已${if (enabled) "开启" else "关闭"}")
                    true
                }
                keepAliveCategory.addPreference(this)
            }

            Preference(context).apply {
                key = "pref_keep_alive_info"
                title = "保活说明"
                summary = "后台保活采用前台服务通知栏、WakeLock防休眠、电量优化白名单、开机自启等多种方式保护应用后台运行。Root保活可通过降低OOM优先级提升存活率。"
                isSelectable = false
                keepAliveCategory.addPreference(this)
            }

            val securityCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "安全设置"
            }
            screen.addPreference(securityCategory)

            val captureCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "配置抓取"
            }
            screen.addPreference(captureCategory)

            val isRooted = com.swupdater.util.RootInstallHelper.isDeviceRooted()

            SwitchPreferenceCompat(context).apply {
                key = "pref_capture_auto_stop"
                title = "抓取后自动停止"
                summary = "捕获到游戏数据后自动停止代理服务，减少对网络的影响"
                setDefaultValue(true)
                isEnabled = isRooted
                setOnPreferenceChangeListener { _, newValue ->
                    com.swupdater.capture.CaptureService.setAutoStopEnabled(requireContext(), newValue as Boolean)
                    true
                }
                captureCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_capture_keep_cert"
                title = "保留 CA 证书"
                summary = "停止抓取后保留系统 CA 证书，避免重复安装。关闭则停止时自动卸载证书"
                setDefaultValue(false)
                isEnabled = isRooted
                setOnPreferenceChangeListener { _, newValue ->
                    com.swupdater.capture.CaptureService.setKeepCertEnabled(requireContext(), newValue as Boolean)
                    true
                }
                captureCategory.addPreference(this)
            }

            Preference(context).apply {
                key = "pref_capture_cert_status"
                title = "CA 证书状态"
                summary = if (isRooted) {
                    if (com.swupdater.capture.CertificateManager.isCaInstalledInSystem(requireContext())) {
                        "CA 证书已安装到系统目录 ✓"
                    } else {
                        "CA 证书未安装，开始抓取时将自动安装"
                    }
                } else {
                    "需要 Root 权限"
                }
                isEnabled = isRooted
                setOnPreferenceClickListener {
                    com.swupdater.capture.CertificateManager.initialize(requireContext())
                    val installed = com.swupdater.capture.CertificateManager.installCaToSystem(requireContext())
                    summary = if (installed) "CA 证书已安装到系统目录 ✓" else "CA 证书安装失败"
                    true
                }
                captureCategory.addPreference(this)
            }

            Preference(context).apply {
                key = "pref_capture_open_dir"
                title = "打开抓取数据目录"
                summary = "查看已导出的 JSON 配置文件"
                isEnabled = isRooted
                setOnPreferenceClickListener {
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ), "SWUpdater/capture"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    try {
                        val uri = android.provider.MediaStore.Files.getContentUri("external")
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "vnd.android.document/directory")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "目录: ${dir.absolutePath}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    true
                }
                captureCategory.addPreference(this)
            }

            Preference(context).apply {
                key = "pref_capture_clear"
                title = "清除抓取数据"
                summary = "删除所有已导出的 JSON 配置文件"
                isEnabled = isRooted
                setOnPreferenceClickListener {
                    val count = com.swupdater.capture.CaptureRepository.clearAllCaptures(requireContext())
                    android.widget.Toast.makeText(
                        requireContext(),
                        "已清除 $count 条抓取记录",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                captureCategory.addPreference(this)
            }

            SwitchPreferenceCompat(context).apply {
                key = "pref_verify_integrity"
                title = getString(R.string.pref_verify_integrity)
                summary = getString(R.string.pref_verify_integrity_summary)
                setDefaultValue(true)
                securityCategory.addPreference(this)
            }

            val debugCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "调试"
            }
            screen.addPreference(debugCategory)

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
                    "https://example.com/backup1",
                    "https://example.com/backup2"
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

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
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

        private fun checkSelfUpdate(currentVersion: String) {
            val pref = findPreference<Preference>("pref_version")
            pref?.summary = "正在检查更新…"

            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        // 检测版本前先清理旧版安装包
                        val clearedCount = FileUtil.clearSelfUpdateCache(requireContext())
                        if (clearedCount > 0) {
                            AppLog.i(TAG, "已清理 $clearedCount 个旧版安装包")
                        }

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

                        val client = com.swupdater.network.DownloadManager.client.newBuilder()
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

                    if (AppInfoUtil.isNewerVersion(latestVersion, currentVersion)) {
                        pref?.summary = "v$currentVersion → v$latestVersion 有新版本！"
                        showSelfUpdateDialog(currentVersion, latestVersion, apkUrl)
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

        private fun showSelfUpdateDialog(currentVersion: String, latestVersion: String, apkUrl: String?) {
            val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("发现新版本")
                .setMessage("当前版本: v$currentVersion\n最新版本: v$latestVersion\n\n是否立即下载更新？")
                .setPositiveButton("立即下载") { _, _ ->
                    if (!apkUrl.isNullOrEmpty()) {
                        startSelfUpdateDownload(latestVersion, apkUrl)
                    } else {
                        try {
                            startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/michaelggr/SWUpdater/releases/latest")
                            ))
                        } catch (_: Exception) {
                            Toast.makeText(requireContext(), "无法打开下载页面", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("稍后", null)
                .setOnDismissListener {
                    progressJob?.cancel()
                }

            if (apkUrl.isNullOrEmpty()) {
                builder.setNeutralButton("镜像下载") { _, _ ->
                    showMirrorDownloadDialog()
                }
            }

            selfUpdateDialog = builder.show()
        }

        private fun startSelfUpdateDownload(versionName: String, apkUrl: String) {
            val targetFile = FileUtil.getApkFile(requireContext(), versionName)

            val dialogView = layoutInflater.inflate(R.layout.dialog_download_progress, null)
            val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_download_title)
            val tvVersion = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_download_version)
            val tvSize = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_download_size)
            val tvSpeed = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_download_speed)
            val tvStatus = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_verify_status)
            val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progress_dialog_download)
            val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_dialog_cancel)
            val btnInstall = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_dialog_install)

            tvTitle.text = "下载更新"
            tvVersion.text = "SWUpdater v$versionName"
            tvSize.text = "0 B / --"
            tvSpeed.text = ""
            tvStatus.visibility = android.view.View.GONE
            progressBar.progress = 0
            btnCancel.text = "取消"
            btnCancel.visibility = android.view.View.VISIBLE
            btnInstall.visibility = android.view.View.GONE

            selfUpdateDialog?.dismiss()

            val downloadDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .setCancelable(false)
                .create()

            btnCancel.setOnClickListener {
                DownloadManager.cancelDownload()
                progressJob?.cancel()
                downloadDialog.dismiss()
                Toast.makeText(requireContext(), "已取消下载", Toast.LENGTH_SHORT).show()
            }

            btnInstall.setOnClickListener {
                downloadDialog.dismiss()
                val file = File(targetFile.absolutePath)
                if (file.exists()) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "无法安装: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            downloadDialog.show()

            progressJob = lifecycleScope.launch {
                // 先重置下载状态，避免与 MainViewModel 冲突
                DownloadManager.reset()

                DownloadManager.startDownload(apkUrl, targetFile, this)

                DownloadManager.progress.collect { progress ->
                    when (progress.state) {
                        DownloadState.DOWNLOADING -> {
                            tvTitle.text = "下载更新"
                            val downloaded = FileUtil.formatFileSize(progress.downloadedBytes)
                            val total = if (progress.totalBytes > 0) FileUtil.formatFileSize(progress.totalBytes) else "--"
                            tvSize.text = "$downloaded / $total"
                            tvSpeed.text = FileUtil.formatSpeed(progress.speed)
                            if (progress.totalBytes > 0) {
                                progressBar.progress = progress.progressPercent
                            }
                            btnCancel.text = "取消"
                            btnCancel.visibility = android.view.View.VISIBLE
                            btnInstall.visibility = android.view.View.GONE
                            tvStatus.visibility = android.view.View.GONE
                        }
                        DownloadState.DOWNLOADED -> {
                            tvTitle.text = "下载完成"
                            tvSize.text = FileUtil.formatFileSize(progress.totalBytes)
                            tvSpeed.text = ""
                            progressBar.progress = 100
                            tvStatus.visibility = android.view.View.VISIBLE
                            tvStatus.text = "校验中..."
                            tvStatus.setTextColor(android.graphics.Color.parseColor("#2196F3"))

                            // 自己进行简单的文件校验（检查文件是否存在且大小不为0）
                            val file = File(progress.filePath)
                            val isValid = file.exists() && file.length() > 0

                            if (isValid) {
                                tvStatus.text = "校验通过"
                                tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                                btnCancel.text = "稍后"
                                btnInstall.visibility = android.view.View.VISIBLE
                            } else {
                                tvStatus.text = "校验失败，请重新下载"
                                tvStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                                btnCancel.text = "关闭"
                            }
                        }
                        DownloadState.FAILED -> {
                            tvTitle.text = "下载失败"
                            tvStatus.visibility = android.view.View.VISIBLE
                            tvStatus.text = "下载失败，请重试"
                            tvStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
                            btnCancel.text = "关闭"
                            btnInstall.visibility = android.view.View.GONE
                        }
                        else -> {}
                    }
                }
            }
        }

        private fun showMirrorDownloadDialog() {
            val downloadMirrors = arrayOf(
                "GitHub（原版）",
                "ghgo 加速",
                "gh-proxy 加速",
                "ghproxy 加速",
                "kgithub 加速"
            )
            val mirrorUrls = listOf(
                "https://github.com/michaelggr/SWUpdater/releases/latest",
                "https://ghgo.xyz/https://github.com/michaelggr/SWUpdater/releases/latest",
                "https://gh-proxy.com/https://github.com/michaelggr/SWUpdater/releases/latest",
                "https://mirror.ghproxy.com/https://github.com/michaelggr/SWUpdater/releases/latest",
                "https://kgithub.com/michaelggr/SWUpdater/releases/latest"
            )

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("选择下载镜像")
                .setItems(downloadMirrors) { _, which ->
                    try {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(mirrorUrls[which])
                        ))
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "无法打开链接", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        override fun onDestroy() {
            super.onDestroy()
            progressJob?.cancel()
            selfUpdateDialog?.dismiss()
        }
    }
}