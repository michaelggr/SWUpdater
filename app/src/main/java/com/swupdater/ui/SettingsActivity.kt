package com.swupdater.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.DropDownPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.swupdater.R
import com.swupdater.network.VersionCheckService
import com.swupdater.util.AppLog
import com.swupdater.util.AppInfoUtil
import com.swupdater.util.FileUtil
import com.swupdater.util.WallpaperManager

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

            // 内容透明度（控制卡片、工具栏等所有UI元素的透明度）
            SeekBarPreference(context).apply {
                key = "pref_wallpaper_overlay_alpha"
                title = "内容透明度"
                summary = "数值越小壁纸越清晰，数值越大内容越清晰。控制所有界面的透明度"
                min = 20
                max = 100
                setDefaultValue(70)
                seekBarIncrement = 5
                showSeekBarValue = true
                setOnPreferenceChangeListener { _, newValue ->
                    val alpha = newValue as Int
                    WallpaperManager.setOverlayAlpha(requireContext(), alpha)
                    true
                }
                wallpaperCategory.addPreference(this)
            }

            // 壁纸源选择
            DropDownPreference(context).apply {
                key = "pref_wallpaper_source"
                title = getString(R.string.pref_wallpaper_source)
                val sourceNames = WallpaperManager.SOURCES.map { it.second }.toTypedArray()
                val sourceIds = WallpaperManager.SOURCES.map { it.first }.toTypedArray()
                entries = sourceNames
                entryValues = sourceIds
                setDefaultValue(WallpaperManager.SOURCES.first().first)
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    WallpaperManager.setWallpaperSource(requireContext(), newValue.toString())
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
                        if (dir.exists() && dir.isDirectory) {
                            WallpaperManager.setCustomDownloadDir(requireContext(), path)
                            summary = path
                        } else {
                            Toast.makeText(requireContext(), "目录不存在，请输入有效路径", Toast.LENGTH_SHORT).show()
                            return@setOnPreferenceChangeListener false
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

            // === 后台保活设置 ===
            val keepAliveCategory = androidx.preference.PreferenceCategory(context).apply {
                title = "后台保活"
            }
            screen.addPreference(keepAliveCategory)

            // 保活开关
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

            // 开机自启动
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

            // Root保活
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

            androidx.preference.EditTextPreference(context).apply {
                key = "pref_source_url"
                title = getString(R.string.pref_source_url)
                summary = VersionCheckService.DEFAULT_SOURCE_URL
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
                summary = "v1.7.0"
                isSelectable = false
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
    }
}
