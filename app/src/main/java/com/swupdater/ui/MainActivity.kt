package com.swupdater.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.swupdater.databinding.DialogDownloadProgressBinding
import com.swupdater.model.*
import com.swupdater.util.AppLog
import com.swupdater.util.FileUtil
import com.swupdater.util.WallpaperManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private var downloadDialog: AlertDialog? = null
    private var dialogBinding: DialogDownloadProgressBinding? = null

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

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (WallpaperManager.hasStoragePermission(this)) {
            downloadCurrentWallpaperInternal()
        } else {
            Snackbar.make(binding.root, "需要存储权限才能保存到公共目录", Snackbar.LENGTH_SHORT).show()
        }
    }

    private var pendingDownloadRunnable: Runnable? = null

    private fun showStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage("保存壁纸到公共下载目录需要「所有文件访问」权限，是否前往设置授权？")
            .setPositiveButton("去设置") { _, _ ->
                val intent = WallpaperManager.getStoragePermissionIntent(this@MainActivity)
                if (intent != null) {
                    storagePermissionLauncher.launch(intent)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                Snackbar.make(binding.root, "壁纸将保存到应用私有目录", Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = MainViewModel(this.application)

        setupToolbar()
        setupSwipeRefresh()
        setupButtons()
        setupWallpaper()
        setupFooterButtons()
        observeViewModel()

        checkAndRequestPermissions()
        loadWallpaperOnStart()

        if (intent.getBooleanExtra(SplashActivity.EXTRA_FROM_SPLASH, false)) {
            lifecycleScope.launch {
                val count = WallpaperManager.preloadWallpapers(this@MainActivity)
                if (count > 0) {
                    loadWallpaperOnStart()
                }
            }
        }
    }

    // ========== 壁纸功能 ==========

    private fun setupWallpaper() {
        binding.btnRandomWallpaper.setOnClickListener {
            applyRandomWallpaper()
        }
        binding.btnDownloadWallpaper.setOnClickListener {
            downloadCurrentWallpaper()
        }
        binding.btnApplyWallpaper.setOnClickListener {
            applyWallpaperToSystem()
        }
        binding.btnOpenFolder.setOnClickListener {
            openWallpaperFolder()
        }
    }

    private fun loadWallpaperOnStart() {
        lifecycleScope.launch {
            if (WallpaperManager.isAutoChangeEnabled(this@MainActivity)) {
                applyRandomWallpaper()
            } else {
                restoreWallpaper()
            }
        }
    }

    private fun applyRandomWallpaper() {
        binding.btnRandomWallpaper.isEnabled = false
        Snackbar.make(binding.root, R.string.wallpaper_changing, Snackbar.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val wallpaperFile = WallpaperManager.randomWallpaper(this@MainActivity)
                if (wallpaperFile != null) {
                    val displayMetrics = resources.displayMetrics
                    val targetWidth = displayMetrics.widthPixels
                    val targetHeight = (160 * displayMetrics.density).toInt()
                    val bitmap = withContext(Dispatchers.IO) {
                        loadBitmapOptimized(wallpaperFile.absolutePath, targetWidth, targetHeight)
                    }
                    if (bitmap != null) {
                        showWallpaperBitmap(bitmap)
                        Snackbar.make(binding.root, R.string.wallpaper_changed, Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(binding.root, R.string.wallpaper_change_failed, Snackbar.LENGTH_SHORT).show()
                    }
                } else {
                    Snackbar.make(binding.root, R.string.wallpaper_change_failed, Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLog.e("MainActivity", "更换壁纸失败: ${e.message}")
                Snackbar.make(binding.root, R.string.wallpaper_change_failed, Snackbar.LENGTH_SHORT).show()
            } finally {
                binding.btnRandomWallpaper.isEnabled = true
            }
        }
    }

    private fun restoreWallpaper() {
        lifecycleScope.launch {
            var wallpaperFile = WallpaperManager.getCurrentWallpaperFile(this@MainActivity)
            if (wallpaperFile == null) {
                wallpaperFile = WallpaperManager.ensureDefaultWallpaper(this@MainActivity)
            }
            if (wallpaperFile != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                }
                if (bitmap != null) {
                    showWallpaperBitmap(bitmap)
                }
            }
        }
    }

    /**
     * 显示壁纸缩略图到卡片内（内存优化版本）
     */
    private fun showWallpaperBitmap(bitmap: Bitmap) {
        binding.ivWallpaper.setImageBitmap(bitmap)
        binding.ivWallpaper.visibility = View.VISIBLE
        binding.tvNoWallpaper.visibility = View.GONE
    }

    /**
     * 内存优化方式加载图片
     * @param filePath 图片文件路径
     * @param maxWidth 最大显示宽度
     * @param maxHeight 最大显示高度
     */
    private fun loadBitmapOptimized(filePath: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)

            options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            BitmapFactory.decodeFile(filePath, options)
        } catch (e: Exception) {
            AppLog.e("MainActivity", "加载图片失败: $filePath", e)
            null
        }
    }

    /**
     * 计算采样率
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun applyWallpaperToSystem() {
        if (binding.ivWallpaper.visibility != View.VISIBLE) {
            Snackbar.make(binding.root, getString(R.string.wallpaper_apply_no_wallpaper), Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val wallpaperFile = WallpaperManager.getCurrentWallpaperFile(this@MainActivity)
                if (wallpaperFile == null || !wallpaperFile.exists()) {
                    Snackbar.make(binding.root, getString(R.string.wallpaper_apply_no_wallpaper), Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(wallpaperFile.absolutePath, options)

                    val maxSize = 2048
                    val sampleSize = calculateInSampleSize(options, maxSize, maxSize)

                    options.inJustDecodeBounds = false
                    options.inSampleSize = sampleSize
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888

                    val bitmap = BitmapFactory.decodeFile(wallpaperFile.absolutePath, options)
                    if (bitmap == null) return@withContext false

                    val wm = android.app.WallpaperManager.getInstance(this@MainActivity)
                    try {
                        wm.setBitmap(bitmap)
                        bitmap.recycle()
                        true
                    } catch (e: Exception) {
                        AppLog.e("MainActivity", "设置壁纸失败", e)
                        bitmap.recycle()
                        false
                    }
                }

                if (result) {
                    Snackbar.make(binding.root, getString(R.string.wallpaper_apply_success), Snackbar.LENGTH_SHORT).show()
                    AppLog.i("MainActivity", "壁纸已应用到手机系统壁纸")
                } else {
                    Snackbar.make(binding.root, getString(R.string.wallpaper_apply_failed), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "${getString(R.string.wallpaper_apply_failed)}: ${e.message}", Snackbar.LENGTH_SHORT).show()
                AppLog.e("MainActivity", "应用壁纸失败: ${e.message}")
            }
        }
    }

    private fun downloadCurrentWallpaper() {
        if (!WallpaperManager.hasStoragePermission(this)) {
            showStoragePermissionDialog()
            return
        }
        downloadCurrentWallpaperInternal()
    }

    private fun downloadCurrentWallpaperInternal() {
        lifecycleScope.launch {
            try {
                val result = WallpaperManager.downloadCurrentWallpaper(this@MainActivity)
                if (result.success) {
                    AppLog.i("MainActivity", "壁纸已保存: ${result.filePath}")
                    Snackbar.make(binding.root, "壁纸已保存: ${result.fileName}", Snackbar.LENGTH_LONG)
                        .setAction("查看") { openWallpaperFolder() }
                        .show()
                } else {
                    Snackbar.make(binding.root, "壁纸保存失败: ${result.error}", Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Snackbar.make(binding.root, "壁纸保存失败: ${e.message}", Snackbar.LENGTH_SHORT).show()
                AppLog.e("MainActivity", "壁纸保存失败: ${e.message}")
            }
        }
    }

    private fun openWallpaperFolder() {
        try {
            val dir = WallpaperManager.getWallpaperDownloadDir(this@MainActivity)
            if (!dir.exists()) dir.mkdirs()

            var opened = false

            // 方式1: 使用 ACTION_VIEW 打开目录（需要 DocumentsContract 支持）
            if (!opened) {
                try {
                    val uri = android.provider.MediaStore.Files.getContentUri("external")
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        opened = true
                    }
                } catch (_: Exception) {}
            }

            // 方式2: 使用系统文件管理器浏览目录
            if (!opened) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                            "vnd.android.document/directory"
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        opened = true
                    }
                } catch (_: Exception) {}
            }

            // 方式3: 打开各品牌文件管理器并尝试导航到目录
            if (!opened) {
                val dirPath = dir.absolutePath
                val fileManagers = listOf(
                    Triple(
                        "com.google.android.apps.nbu.files",
                        "com.google.android.apps.nbu.files.home.HomeActivity",
                        dirPath
                    ),
                    Triple(
                        "com.sec.android.app.myfiles",
                        "com.sec.android.app.myfiles.common.MainActivity",
                        dirPath
                    ),
                    Triple(
                        "com.huawei.hidisk",
                        "com.huawei.hidisk.HomeActivity",
                        dirPath
                    ),
                    Triple(
                        "com.xiaomi.fileexplorer",
                        "com.xiaomi.fileexplorer.FileExplorerActivity",
                        dirPath
                    )
                )
                for ((pkg, activity, path) in fileManagers) {
                    try {
                        val intent = Intent().apply {
                            component = ComponentName(pkg, activity)
                            putExtra("dir_path", path)
                            putExtra("folder_path", path)
                            putExtra("current_dir", path)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        opened = true
                        break
                    } catch (_: Exception) { continue }
                }
            }

            // 所有方式都失败时，显示路径并支持复制
            Snackbar.make(binding.root, "壁纸目录: ${dir.absolutePath}", Snackbar.LENGTH_LONG)
                .setAction("复制路径") {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("壁纸目录", dir.absolutePath))
                    Toast.makeText(this@MainActivity, "路径已复制", Toast.LENGTH_SHORT).show()
                }.show()
        } catch (e: Exception) {
            AppLog.e("MainActivity", "打开目录失败: ${e.message}")
            Snackbar.make(binding.root, "无法打开目录: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ========== 底部功能 ==========

    private fun setupFooterButtons() {
        binding.btnToolbox.setOnClickListener {
            openUrl("https://www.ggrmm.top/%E6%B8%B8%E6%88%8F/%E9%AD%94%E7%81%B5%E5%8F%AC%E5%94%A4/")
        }
        binding.btnFeedback.setOnClickListener {
            openUrl("https://message.bilibili.com/")
        }
        binding.tvDesignBy.setOnClickListener {
            openUrl("https://b23.tv/pZjkolF")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
            if (checkNetworkPermission()) viewModel.checkUpdate()
        }
        binding.btnDownload.setOnClickListener {
            showDownloadDialog()
            viewModel.startDownload()
        }
        binding.btnOpenStore.setOnClickListener { viewModel.startDownload() }
        binding.btnStopDownload.setOnClickListener { viewModel.cancelDownload() }
        binding.btnInstall.setOnClickListener { attemptInstall() }
        binding.btnOpenGame.setOnClickListener {
            if (!viewModel.launchGame()) Toast.makeText(this, "无法启动游戏", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.installedInfo.collect { info ->
                info?.let {
                    binding.tvCurrentVersion.text = if (it.isInstalled) it.versionName else getString(R.string.version_not_installed)
                    binding.btnOpenGame.visibility = if (it.isInstalled) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            viewModel.checkResult.collect { result ->
                binding.swipeRefresh.isRefreshing = false
                when (result) {
                    is UpdateCheckResult.Checking -> {
                        binding.tvStatus.text = getString(R.string.status_checking)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.info)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.info))
                        binding.btnDownload.visibility = View.GONE
                        binding.btnOpenStore.visibility = View.GONE
                    }
                    is UpdateCheckResult.UpToDate -> {
                        binding.tvLatestVersion.text = result.currentVersion
                        binding.tvStatus.text = getString(R.string.status_up_to_date)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.success)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                        binding.btnDownload.visibility = View.GONE
                        binding.btnOpenStore.visibility = View.GONE
                    }
                    is UpdateCheckResult.UpdateAvailable -> {
                        binding.tvLatestVersion.text = result.latestVersion.versionName
                        binding.tvStatus.text = getString(R.string.status_update_available)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.warning)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.warning))
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
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.error)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                        binding.btnDownload.visibility = View.GONE
                        binding.btnOpenStore.visibility = View.GONE
                    }
                }
            }
        }

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
                        binding.tvDownloadSize.text = String.format("%s / %s",
                            FileUtil.formatFileSize(progress.downloadedBytes),
                            FileUtil.formatFileSize(progress.totalBytes))
                        binding.tvDownloadSpeed.text = FileUtil.formatSpeed(progress.speed)
                        binding.tvVerifyStatus.visibility = View.GONE
                        updateDownloadDialog(progress)
                    }
                    DownloadState.DOWNLOADED -> {
                        binding.tvDownloadTitle.text = getString(R.string.status_downloaded)
                        binding.btnStopDownload.visibility = View.GONE
                        binding.progressDownload.progress = 100
                        updateDownloadDialog(progress)
                    }
                    DownloadState.VERIFYING -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.verifying_integrity)
                        binding.tvVerifyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.info))
                        updateDownloadDialog(progress)
                    }
                    DownloadState.VERIFIED -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.integrity_pass)
                        binding.tvVerifyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                        binding.btnInstall.visibility = View.VISIBLE
                        updateDownloadDialog(progress)
                    }
                    DownloadState.VERIFY_FAILED -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.integrity_fail)
                        binding.tvVerifyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                        updateDownloadDialog(progress)
                    }
                    DownloadState.INSTALLING -> {
                        binding.tvStatus.text = getString(R.string.status_installing)
                        dismissDownloadDialog()
                    }
                    DownloadState.FAILED -> {
                        binding.tvStatus.text = getString(R.string.status_error)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.error)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                        updateDownloadDialog(progress)
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isChecking.collect { isChecking ->
                binding.btnCheckUpdate.isEnabled = !isChecking
            }
        }
    }

    private fun showDownloadDialog() {
        if (downloadDialog?.isShowing == true) return

        val db = DialogDownloadProgressBinding.inflate(layoutInflater)
        dialogBinding = db

        val latest = viewModel.latestVersion.value
        db.tvDialogDownloadVersion.text = if (latest != null) {
            getString(R.string.dialog_download_version, latest.versionName)
        } else {
            ""
        }
        db.btnDialogInstall.visibility = View.GONE
        db.tvDialogVerifyStatus.visibility = View.GONE
        db.btnDialogCancel.setOnClickListener {
            viewModel.cancelDownload()
            dismissDownloadDialog()
        }
        db.btnDialogInstall.setOnClickListener {
            attemptInstall()
        }

        downloadDialog = AlertDialog.Builder(this)
            .setView(db.root)
            .setCancelable(false)
            .create()
        downloadDialog?.show()
    }

    private fun updateDownloadDialog(progress: DownloadProgress) {
        val db = dialogBinding ?: return
        when (progress.state) {
            DownloadState.DOWNLOADING -> {
                db.progressDialogDownload.progress = progress.progressPercent
                db.tvDialogDownloadSize.text = String.format("%s / %s",
                    FileUtil.formatFileSize(progress.downloadedBytes),
                    FileUtil.formatFileSize(progress.totalBytes))
                db.tvDialogDownloadSpeed.text = FileUtil.formatSpeed(progress.speed)
                db.tvDialogVerifyStatus.visibility = View.GONE
                db.btnDialogInstall.visibility = View.GONE
                db.btnDialogCancel.text = getString(R.string.cancel)
            }
            DownloadState.DOWNLOADED -> {
                db.progressDialogDownload.progress = 100
                db.tvDialogDownloadSize.text = String.format("%s / %s",
                    FileUtil.formatFileSize(progress.downloadedBytes),
                    FileUtil.formatFileSize(progress.totalBytes))
                db.tvDialogDownloadSpeed.text = ""
            }
            DownloadState.VERIFYING -> {
                db.tvDialogVerifyStatus.visibility = View.VISIBLE
                db.tvDialogVerifyStatus.text = getString(R.string.verifying_integrity)
                db.tvDialogVerifyStatus.setTextColor(ContextCompat.getColor(this, R.color.info))
            }
            DownloadState.VERIFIED -> {
                db.tvDialogVerifyStatus.visibility = View.VISIBLE
                db.tvDialogVerifyStatus.text = getString(R.string.integrity_pass)
                db.tvDialogVerifyStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
                db.btnDialogInstall.visibility = View.VISIBLE
                db.btnDialogCancel.text = getString(R.string.dialog_btn_later)
                db.tvDialogDownloadTitle.text = getString(R.string.status_downloaded)
            }
            DownloadState.VERIFY_FAILED -> {
                db.tvDialogVerifyStatus.visibility = View.VISIBLE
                db.tvDialogVerifyStatus.text = getString(R.string.integrity_fail)
                db.tvDialogVerifyStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                db.btnDialogCancel.text = getString(R.string.ok)
            }
            DownloadState.FAILED -> {
                db.tvDialogDownloadTitle.text = getString(R.string.status_error)
                db.btnDialogCancel.text = getString(R.string.ok)
            }
            else -> {}
        }
    }

    private fun dismissDownloadDialog() {
        downloadDialog?.dismiss()
        downloadDialog = null
        dialogBinding = null
    }

    private fun showInstallDialog() {
        val latest = viewModel.latestVersion.value
        val progress = viewModel.downloadProgress.value
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_install_title)
            .setMessage(getString(R.string.dialog_install_message,
                latest?.versionName ?: "未知",
                FileUtil.formatFileSize(progress.totalBytes)))
            .setPositiveButton(R.string.dialog_btn_install) { _, _ -> attemptInstall() }
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
        if (!viewModel.installApk()) Toast.makeText(this, "安装失败", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            // 检查设置：是否在打开APP时自动检测更新
            val prefs = getSharedPreferences("sw_updater_prefs", MODE_PRIVATE)
            val autoCheckOnLaunch = prefs.getBoolean("pref_auto_check_on_launch", true)
            if (autoCheckOnLaunch) {
                viewModel.checkUpdate()
            }
        }
    }

    private fun checkNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_permission_title)
            .setMessage(R.string.dialog_storage_permission_message)
            .setPositiveButton(R.string.dialog_btn_go_settings) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalledInfo()
    }
}
