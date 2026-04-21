package com.swupdater.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.swupdater.R
import com.swupdater.databinding.ActivityMainBinding
import com.swupdater.model.*
import com.swupdater.network.DownloadChannels
import com.swupdater.util.AppLog
import com.swupdater.util.FileUtil
import com.swupdater.util.WallpaperManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            viewModel.checkUpdate()
        } else {
            showPermissionDeniedDialog()
        }
    }

    // 安装权限请求
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                viewModel.installApk()
            } else {
                Toast.makeText(this, R.string.dialog_install_permission_message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = MainViewModel(this.application)

        setupToolbar()
        setupSwipeRefresh()
        setupButtons()
        setupWallpaperFab()
        setupFooterButtons()
        observeViewModel()

        // 首次启动检查权限并自动检查更新
        checkAndRequestPermissions()

        // 启动时自动更换壁纸
        if (WallpaperManager.isAutoChangeEnabled(this)) {
            applyRandomWallpaper()
        } else {
            restoreWallpaper()
        }
    }

    // ========== 壁纸功能 ==========

    private fun setupWallpaperFab() {
        // 随机壁纸按钮（右下角大FAB，刷新图标）
        binding.fabRandomWallpaper.setOnClickListener {
            applyRandomWallpaper()
        }

        // 壁纸下载按钮（右下角小FAB）
        binding.fabDownloadWallpaper.setOnClickListener {
            downloadCurrentWallpaper()
        }

        // 应用到手机壁纸按钮（右下角小FAB）
        binding.fabApplyWallpaper.setOnClickListener {
            applyWallpaperToSystem()
        }

        // 打开缓存目录按钮（右下角小FAB）
        binding.fabOpenFolder.setOnClickListener {
            openWallpaperFolder()
        }
    }

    /**
     * 随机更换壁纸
     */
    private fun applyRandomWallpaper() {
        lifecycleScope.launch {
            try {
                val wallpaperFile = WallpaperManager.randomWallpaper(this@MainActivity)
                if (wallpaperFile != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                    }
                    if (bitmap != null) {
                        applyWallpaperBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                AppLog.e("MainActivity", "更换壁纸失败: ${e.message}")
            }
        }
    }

    /**
     * 恢复上次使用的壁纸
     */
    private fun restoreWallpaper() {
        lifecycleScope.launch {
            val wallpaperFile = WallpaperManager.getCurrentWallpaperFile(this@MainActivity)
            if (wallpaperFile != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                }
                if (bitmap != null) {
                    applyWallpaperBitmap(bitmap)
                }
            }
        }
    }

    /**
     * 将 Bitmap 应用到背景，并根据设置调整遮罩透明度
     * 横版图片使用 fitCenter 完整显示，竖版图片使用 centerCrop 填满屏幕
     */
    private fun applyWallpaperBitmap(bitmap: Bitmap) {
        // 根据图片宽高比选择 ScaleType
        val isLandscape = bitmap.width > bitmap.height
        binding.ivWallpaper.scaleType = if (isLandscape) {
            ImageView.ScaleType.FIT_CENTER  // 横版：完整显示，上下留黑边
        } else {
            ImageView.ScaleType.CENTER_CROP // 竖版：裁剪填满屏幕
        }
        binding.ivWallpaper.setImageBitmap(bitmap)
        binding.ivWallpaper.visibility = View.VISIBLE

        // 根据设置的透明度更新所有UI元素的透明度
        updateOverlayAlpha()
    }

    /**
     * 根据设置更新所有UI元素的透明度
     * alphaPercent: 0=全透明(壁纸全可见), 100=全不透明(壁纸不可见)
     *
     * 所有UI元素（遮罩层、卡片、工具栏、版本小卡）都响应此设置
     */
    fun updateOverlayAlpha() {
        val alphaPercent = WallpaperManager.getOverlayAlpha(this)
        val hasWallpaper = binding.ivWallpaper.visibility == View.VISIBLE

        if (hasWallpaper) {
            binding.viewWallpaperOverlay.visibility = View.VISIBLE

            // 将百分比转为0-255的alpha值
            val alpha255 = (alphaPercent * 255 / 100).coerceIn(0, 255)

            // 根据当前主题选择遮罩底色
            val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            val baseColor = if (isDarkMode) Color.parseColor("#0D0B1A") else Color.parseColor("#FFFFFF")
            val cardBaseColor = if (isDarkMode) Color.parseColor("#1E1B35") else Color.parseColor("#FFFFFF")
            val versionCardBaseColor = if (isDarkMode) Color.parseColor("#252244") else Color.parseColor("#F3EFFF")

            // 1. 背景遮罩层 — 使用完整透明度
            binding.viewWallpaperOverlay.setBackgroundColor(
                Color.argb(alpha255, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            )

            // 2. 工具栏背景 — 使用完整透明度
            binding.appBarLayout.background = ColorDrawable(
                Color.argb(alpha255, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
            )

            // 3. 卡片背景 — 使用稍微降低的透明度，让卡片内容更清晰
            val cardAlpha255 = alpha255.coerceAtLeast((alphaPercent * 255 / 100 * 0.85).toInt())
            val cardBgColor = Color.argb(cardAlpha255, Color.red(cardBaseColor), Color.green(cardBaseColor), Color.blue(cardBaseColor))

            // 设置所有 MaterialCardView 的背景
            setCardBackground(binding.cardGameInfo, cardBgColor)
            // card_download 和 card_changelog 可能在运行时可见/隐藏
            setCardBackground(binding.cardDownload, cardBgColor)
            setCardBackground(binding.cardChangelog, cardBgColor)

            // 4. 版本信息小卡背景 — 使用稍微降低的透明度
            val versionBgColor = Color.argb(cardAlpha255, Color.red(versionCardBaseColor), Color.green(versionCardBaseColor), Color.blue(versionCardBaseColor))
            binding.layoutCurrentVersion.backgroundTintList = android.content.res.ColorStateList.valueOf(versionBgColor)
            binding.layoutLatestVersion.backgroundTintList = android.content.res.ColorStateList.valueOf(versionBgColor)

            // 5. 底部按钮区域 — 也可以半透明
            // Outlined按钮保持不变（透明背景只有边框），Primary按钮加透明度
            // 不对按钮做透明度处理，因为按钮文字需要清晰可读

        } else {
            // 没有壁纸时，恢复默认不透明背景
            binding.viewWallpaperOverlay.visibility = View.GONE

            // 恢复工具栏
            binding.appBarLayout.background = null
            binding.appBarLayout.setBackgroundResource(0)

            // 恢复卡片
            resetCardBackground(binding.cardGameInfo)
            resetCardBackground(binding.cardDownload)
            resetCardBackground(binding.cardChangelog)

            // 恢复版本小卡
            binding.layoutCurrentVersion.backgroundTintList = null
            binding.layoutLatestVersion.backgroundTintList = null
        }
    }

    /**
     * 设置卡片背景色
     */
    private fun setCardBackground(card: com.google.android.material.card.MaterialCardView, color: Int) {
        card.setCardBackgroundColor(color)
    }

    /**
     * 重置卡片背景为默认颜色
     */
    private fun resetCardBackground(card: com.google.android.material.card.MaterialCardView) {
        val defaultColor = ContextCompat.getColor(this, R.color.card_background)
        card.setCardBackgroundColor(defaultColor)
    }

    /**
     * 将当前背景壁纸应用到手机系统壁纸
     * 使用 WallpaperManager.setBitmap() 设置系统壁纸
     */
    private fun applyWallpaperToSystem() {
        // 检查当前是否有壁纸
        val drawable = binding.ivWallpaper.drawable
        if (drawable == null || binding.ivWallpaper.visibility != View.VISIBLE) {
            Snackbar.make(
                binding.root,
                getString(R.string.wallpaper_apply_no_wallpaper),
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch {
            try {
                val wallpaperFile = WallpaperManager.getCurrentWallpaperFile(this@MainActivity)
                if (wallpaperFile == null || !wallpaperFile.exists()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.wallpaper_apply_no_wallpaper),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                    if (bitmap == null) return@withContext false

                    val wm = android.app.WallpaperManager.getInstance(this@MainActivity)
                    wm.setBitmap(bitmap)
                    bitmap.recycle()
                    true
                }

                if (result) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.wallpaper_apply_success),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    AppLog.i("MainActivity", "壁纸已应用到手机系统壁纸")
                } else {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.wallpaper_apply_failed),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    "${getString(R.string.wallpaper_apply_failed)}: ${e.message}",
                    Snackbar.LENGTH_SHORT
                ).show()
                AppLog.e("MainActivity", "应用壁纸失败: ${e.message}")
            }
        }
    }

    /**
     * 下载当前壁纸到用户目录，完成后 Snackbar 显示保存地址和文件名
     */
    private fun downloadCurrentWallpaper() {
        lifecycleScope.launch {
            try {
                val result = WallpaperManager.downloadCurrentWallpaper(this@MainActivity)
                if (result.success) {
                    AppLog.i("MainActivity", "壁纸已保存: ${result.filePath}")
                    // 用 Snackbar 气泡显示保存地址和壁纸名
                    val snackbar = Snackbar.make(
                        binding.root,
                        "壁纸已保存: ${result.fileName}",
                        Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction("查看") {
                        openWallpaperFolder()
                    }
                    snackbar.show()
                } else {
                    Snackbar.make(
                        binding.root,
                        "壁纸保存失败: ${result.error}",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Snackbar.make(
                    binding.root,
                    "壁纸保存失败: ${e.message}",
                    Snackbar.LENGTH_SHORT
                ).show()
                AppLog.e("MainActivity", "壁纸保存失败: ${e.message}")
            }
        }
    }

    /**
     * 打开壁纸缓存下载目录
     * 使用 ACTION_VIEW 直接打开公用目录
     */
    private fun openWallpaperFolder() {
        try {
            val dir = WallpaperManager.getWallpaperDownloadDir(this@MainActivity)
            if (!dir.exists()) dir.mkdirs()

            // 尝试直接用文件管理器打开目录
            var opened = false

            // 方式1: 使用 ACTION_VIEW 打开公用 Download 目录
            if (!opened) {
                try {
                    // 构建公用 Download 目录的 content URI
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    val relativePath = "SWUpdater/wallpapers"
                    val targetDir = java.io.File(downloadDir, relativePath)
                    if (!targetDir.exists()) targetDir.mkdirs()

                    // 尝试用系统文件管理器打开
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FSWUpdater%2Fwallpapers"),
                            "vnd.android.document/directory"
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    opened = true
                } catch (_: Exception) {}
            }

            // 方式2: 用常见文件管理器打开
            if (!opened) {
                val fileManagers = listOf(
                    "com.google.android.apps.nbu.files" to "com.google.android.apps.nbu.files.home.HomeActivity",
                    "com.sec.android.app.myfiles" to "com.sec.android.app.myfiles.common.MainActivity",
                    "com.huawei.hidisk" to "com.huawei.hidisk.HomeActivity",
                    "com.xiaomi.fileexplorer" to "com.xiaomi.fileexplorer.FileExplorerActivity",
                    "com.oppo.filemanager" to "com.coloros.filemanager.main.MainActivity",
                    "com.vivo.filemanager" to "com.vivo.filemanager.activity.MainActivity"
                )

                for ((pkg, activity) in fileManagers) {
                    try {
                        val intent = Intent().apply {
                            component = ComponentName(pkg, activity)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        opened = true
                        break
                    } catch (_: Exception) { continue }
                }
            }

            // 方式3: 用 SAF 打开（Android 自带文件选择器）
            if (!opened) {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    opened = true
                } catch (_: Exception) {}
            }

            // 都失败了，显示目录路径供用户手动查看
            if (!opened) {
                Snackbar.make(
                    binding.root,
                    "壁纸目录: ${dir.absolutePath}",
                    Snackbar.LENGTH_LONG
                ).setAction("复制路径") {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("壁纸目录", dir.absolutePath))
                    Toast.makeText(this@MainActivity, "路径已复制", Toast.LENGTH_SHORT).show()
                }.show()
            }

        } catch (e: Exception) {
            AppLog.e("MainActivity", "打开目录失败: ${e.message}")
            Snackbar.make(
                binding.root,
                "无法打开目录: ${e.message}",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // ========== 底部功能 ==========

    private fun setupFooterButtons() {
        // 魔灵工具箱按钮
        binding.btnToolbox.setOnClickListener {
            openUrl("https://www.ggrmm.top/%E6%B8%B8%E6%88%8F/%E9%AD%94%E7%81%B5%E5%8F%AC%E5%94%A4/")
        }

        // 反馈留言按钮 → B站私信页面
        binding.btnFeedback.setOnClickListener {
            openUrl("https://message.bilibili.com/")
        }

        // Design by 文字点击 → B站主页
        binding.tvDesignBy.setOnClickListener {
            openUrl("https://b23.tv/pZjkolF")
        }
    }

    /**
     * 在浏览器中打开链接
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
            AppLog.e("MainActivity", "无法打开链接: $url, ${e.message}")
        }
    }

    // ========== 原有功能 ==========

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            com.google.android.material.R.color.design_default_color_primary
        )
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshInstalledInfo()
            viewModel.checkUpdate()
        }
    }

    private fun setupButtons() {
        binding.btnCheckUpdate.setOnClickListener {
            if (checkNetworkPermission()) {
                viewModel.checkUpdate()
            }
        }

        binding.btnDownload.setOnClickListener {
            viewModel.startDownload()
        }

        binding.btnOpenStore.setOnClickListener {
            viewModel.startDownload()
        }

        binding.btnStopDownload.setOnClickListener {
            viewModel.cancelDownload()
        }

        binding.btnInstall.setOnClickListener {
            attemptInstall()
        }

        binding.btnOpenGame.setOnClickListener {
            if (!viewModel.launchGame()) {
                Toast.makeText(this, "无法启动游戏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        // 已安装信息
        lifecycleScope.launch {
            viewModel.installedInfo.collect { info ->
                info?.let {
                    binding.tvCurrentVersion.text = if (it.isInstalled) it.versionName else getString(R.string.version_not_installed)
                    binding.tvGamePackage.text = it.packageName
                    binding.btnOpenGame.visibility = if (it.isInstalled) View.VISIBLE else View.GONE
                }
            }
        }

        // 检查结果
        lifecycleScope.launch {
            viewModel.checkResult.collect { result ->
                binding.swipeRefresh.isRefreshing = false

                when (result) {
                    is UpdateCheckResult.Checking -> {
                        binding.tvStatus.text = getString(R.string.status_checking)
                        binding.viewStatusDot.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.info)
                        binding.tvStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.info)
                        )
                        binding.btnDownload.visibility = View.GONE
                        binding.btnOpenStore.visibility = View.GONE
                    }
                    is UpdateCheckResult.UpToDate -> {
                        binding.tvLatestVersion.text = result.currentVersion
                        binding.tvStatus.text = getString(R.string.status_up_to_date)
                        binding.viewStatusDot.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.success)
                        binding.tvStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.success)
                        )
                        binding.btnDownload.visibility = View.GONE
                        binding.btnOpenStore.visibility = View.GONE
                    }
                    is UpdateCheckResult.UpdateAvailable -> {
                        binding.tvLatestVersion.text = result.latestVersion.versionName
                        binding.tvStatus.text = getString(R.string.status_update_available)
                        binding.viewStatusDot.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.warning)
                        binding.tvStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.warning)
                        )
                        binding.btnDownload.visibility = View.VISIBLE
                        binding.btnDownload.text = getString(R.string.btn_download_update)
                        binding.btnOpenStore.visibility = View.GONE

                        if (result.latestVersion.changelog.isNotEmpty()) {
                            binding.cardChangelog.visibility = View.VISIBLE
                            binding.tvChangelog.text = result.latestVersion.changelog
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        binding.tvStatus.text = "${getString(R.string.status_error)}: ${result.message}"
                        binding.viewStatusDot.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.error)
                        binding.tvStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.error)
                        )
                        binding.btnDownload.visibility = View.GONE
                        binding.btnOpenStore.visibility = View.GONE
                    }
                }
            }
        }

        // 下载进度
        lifecycleScope.launch {
            viewModel.downloadProgress.collect { progress ->
                when (progress.state) {
                    DownloadState.IDLE -> {
                        binding.cardDownload.visibility = View.GONE
                        binding.btnStopDownload.visibility = View.GONE
                        binding.btnInstall.visibility = View.GONE
                    }
                    DownloadState.DOWNLOADING -> {
                        binding.cardDownload.visibility = View.VISIBLE
                        binding.btnStopDownload.visibility = View.VISIBLE
                        binding.btnDownload.visibility = View.GONE
                        binding.btnInstall.visibility = View.GONE
                        binding.tvDownloadTitle.text = getString(R.string.download_progress)
                        binding.progressDownload.progress = progress.progressPercent
                        binding.tvDownloadSize.text = String.format(
                            "%s / %s",
                            FileUtil.formatFileSize(progress.downloadedBytes),
                            FileUtil.formatFileSize(progress.totalBytes)
                        )
                        binding.tvDownloadSpeed.text = FileUtil.formatSpeed(progress.speed)
                        binding.tvVerifyStatus.visibility = View.GONE
                    }
                    DownloadState.DOWNLOADED -> {
                        binding.tvDownloadTitle.text = getString(R.string.status_downloaded)
                        binding.btnStopDownload.visibility = View.GONE
                        binding.progressDownload.progress = 100
                    }
                    DownloadState.VERIFYING -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.verifying_integrity)
                        binding.tvVerifyStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.info)
                        )
                    }
                    DownloadState.VERIFIED -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.integrity_pass)
                        binding.tvVerifyStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.success)
                        )
                        binding.btnInstall.visibility = View.VISIBLE
                        showInstallDialog()
                    }
                    DownloadState.VERIFY_FAILED -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.integrity_fail)
                        binding.tvVerifyStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.error)
                        )
                    }
                    DownloadState.INSTALLING -> {
                        binding.tvStatus.text = getString(R.string.status_installing)
                    }
                    DownloadState.FAILED -> {
                        binding.tvStatus.text = getString(R.string.status_error)
                        binding.viewStatusDot.backgroundTintList =
                            ContextCompat.getColorStateList(this@MainActivity, R.color.error)
                        binding.tvStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, R.color.error)
                        )
                    }
                    else -> {}
                }
            }
        }

        // 检查中
        lifecycleScope.launch {
            viewModel.isChecking.collect { isChecking ->
                binding.btnCheckUpdate.isEnabled = !isChecking
            }
        }
    }

    private fun showInstallDialog() {
        val latest = viewModel.latestVersion.value
        val progress = viewModel.downloadProgress.value

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_install_title)
            .setMessage(
                getString(
                    R.string.dialog_install_message,
                    latest?.versionName ?: "未知",
                    FileUtil.formatFileSize(progress.totalBytes)
                )
            )
            .setPositiveButton(R.string.dialog_btn_install) { _, _ ->
                attemptInstall()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun attemptInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_permission_title)
                    .setMessage(R.string.dialog_install_permission_message)
                    .setPositiveButton(R.string.dialog_btn_go_settings) { _, _ ->
                        installPermissionLauncher.launch(viewModel.requestInstallPermission())
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
        }

        if (!viewModel.installApk()) {
            Toast.makeText(this, "安装失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            viewModel.checkUpdate()
        }
    }

    private fun checkNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_permission_title)
            .setMessage(R.string.dialog_storage_permission_message)
            .setPositiveButton(R.string.dialog_btn_go_settings) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalledInfo()
        // 从设置返回时刷新透明度
        updateOverlayAlpha()
    }
}
