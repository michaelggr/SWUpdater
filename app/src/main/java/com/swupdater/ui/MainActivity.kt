package com.swupdater.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
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
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.swupdater.R
import com.swupdater.databinding.ActivityMainBinding
import com.swupdater.model.*
import com.swupdater.capture.CaptureRepository
import com.swupdater.capture.CaptureService
import com.swupdater.util.AppLog
import com.swupdater.util.FileUtil
import com.swupdater.util.RootInstallHelper
import com.swupdater.util.ThemeManager
import com.swupdater.util.WallpaperManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

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
        // 权限请求返回后，直接尝试下载
        lifecycleScope.launch {
            performDownloadWallpaper()
        }
    }

    // 悬浮窗权限设置页返回监听
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                SnackbarHelper.success(binding.root, "悬浮窗权限已授予").show()
            } else {
                SnackbarHelper.warning(binding.root, "未授予悬浮窗权限，抓取时将无法显示状态悬浮窗").show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            ThemeManager.applyTheme(this)
        } catch (e: Exception) {
            setTheme(R.style.Theme_SWUpdater_Game)
        }
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            AppLog.e(TAG, "初始化布局失败", e)
            // 如果布局加载失败，至少显示一个简单的界面
            try {
                val textView = android.widget.TextView(this)
                textView.text = "应用启动失败，请重新安装"
                textView.gravity = android.view.Gravity.CENTER
                setContentView(textView)
            } catch (_: Exception) {}
            return
        }

        try { setupToolbar() } catch (e: Exception) { AppLog.e(TAG, "setupToolbar失败", e) }
        try { setupSwipeRefresh() } catch (e: Exception) { AppLog.e(TAG, "setupSwipeRefresh失败", e) }
        try { setupButtons() } catch (e: Exception) { AppLog.e(TAG, "setupButtons失败", e) }
        try { setupWallpaper() } catch (e: Exception) { AppLog.e(TAG, "setupWallpaper失败", e) }
        try { setupCapture() } catch (e: Exception) { AppLog.e(TAG, "setupCapture失败", e) }
        try { setupFooterButtons() } catch (e: Exception) { AppLog.e(TAG, "setupFooterButtons失败", e) }
        try { observeViewModel() } catch (e: Exception) { AppLog.e(TAG, "observeViewModel失败", e) }

        // 延迟执行可能导致崩溃的操作
        binding.root.postDelayed({
            try { checkAndRequestPermissions() } catch (e: Exception) { AppLog.e(TAG, "checkAndRequestPermissions失败", e) }
            try { loadWallpaperOnStart() } catch (e: Exception) { AppLog.e(TAG, "loadWallpaperOnStart失败", e) }
        }, 300)
    }

    // ========== 壁纸功能 ==========

    private fun setupCapture() {
        val isRooted = RootInstallHelper.isDeviceRooted()

        if (!isRooted) {
            binding.tvCaptureStatus.text = "设备未 Root，无法使用配置抓取功能"
            binding.btnStartCapture.isEnabled = false
        }

        updateCaptureUI()

        binding.btnStartCapture.setOnClickListener {
            // 先做环境检测，有问题就不启动
            val checkResult = checkCaptureEnvironment()
            if (!checkResult.canStart) {
                SnackbarHelper.error(binding.root, checkResult.message).show()
                return@setOnClickListener
            }

            // 有警告但可以继续
            if (checkResult.hasWarning) {
                SnackbarHelper.warning(binding.root, checkResult.message).show()
            }

            // 开始抓取，显示步骤引导
            startCaptureWithGuide()
        }

        binding.btnStopCapture.setOnClickListener {
            CaptureService.stop(this)
            updateCaptureUI()
        }
    }

    /**
     * 抓取前环境检测
     * 检测Root权限、CA证书、系统代理、VPN等可能影响抓取的因素
     */
    private fun checkCaptureEnvironment(): CaptureCheckResult {
        // 1. Root权限检测
        if (!RootInstallHelper.isDeviceRooted()) {
            return CaptureCheckResult(
                canStart = false,
                hasWarning = false,
                message = "❌ 设备未Root，无法使用抓取功能"
            )
        }

        // 2. 系统代理检测（使用系统属性，兼容所有版本）
        val proxyHost = System.getProperty("http.proxyHost") ?: ""
        val proxyPort = System.getProperty("http.proxyPort")?.toIntOrNull() ?: 0
        if (proxyHost.isNotEmpty() && proxyPort > 0) {
            return CaptureCheckResult(
                canStart = false,
                hasWarning = false,
                message = "❌ 检测到系统代理($proxyHost:$proxyPort)，请先关闭系统代理再抓取"
            )
        }

        // 3. VPN检测
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager?.activeNetwork
            val networkCapabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            if (networkCapabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) {
                return CaptureCheckResult(
                    canStart = false,
                    hasWarning = false,
                    message = "❌ 检测到VPN连接中，请先关闭VPN再抓取，否则流量无法被拦截"
                )
            }
        }

        // 4. CA证书状态检测（警告级别，不阻止启动）
        val certInstalled = com.swupdater.capture.CertificateManager.isCaInstalledInSystem(this)
        val certWarning = if (!certInstalled) {
            "⚠️ CA证书未安装，将在启动时自动安装"
        } else null

        // 5. 游戏是否已安装
        val gameInstalled = try {
            packageManager.getLaunchIntentForPackage(com.swupdater.util.AppInfoUtil.PACKAGE_NAME_CN) != null
        } catch (e: Exception) {
            false
        }
        if (!gameInstalled) {
            return CaptureCheckResult(
                canStart = false,
                hasWarning = false,
                message = "❌ 未检测到魔灵召唤，请先安装游戏"
            )
        }

        // 6. 悬浮窗权限检测（警告级别，不影响抓取，引导用户授权）
        var overlayWarning: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                // 弹窗引导用户去设置页面授权
                showOverlayPermissionDialog()
                overlayWarning = "⚠️ 未授予悬浮窗权限，无法在游戏上显示抓取状态"
            }
        }

        val allWarnings = listOfNotNull(certWarning, overlayWarning)

        return CaptureCheckResult(
            canStart = true,
            hasWarning = allWarnings.isNotEmpty(),
            message = if (allWarnings.isNotEmpty()) allWarnings.joinToString("\n") else "环境检测通过"
        )
    }

    /**
     * 带步骤引导的抓取启动
     */
    private fun startCaptureWithGuide() {
        binding.btnStartCapture.visibility = View.GONE
        binding.btnStopCapture.visibility = View.VISIBLE

        lifecycleScope.launch {
            // 步骤1：初始化证书
            binding.tvCaptureStatus.text = "步骤 1/4 · 初始化证书..."
            SnackbarHelper.info(binding.root, "📋 步骤1：初始化CA证书").show()
            delay(500)

            // 步骤2：安装证书
            binding.tvCaptureStatus.text = "步骤 2/4 · 安装CA证书..."
            SnackbarHelper.info(binding.root, "📋 步骤2：安装CA证书到系统").show()

            // 启动抓取服务
            CaptureService.start(this@MainActivity)

            // 等待服务启动
            delay(2000)

            if (!CaptureService.isRunning) {
                binding.tvCaptureStatus.text = "抓取服务启动失败"
                SnackbarHelper.error(binding.root, "抓取服务启动失败，请查看日志").show()
                updateCaptureUI()
                return@launch
            }

            // 步骤3：设置流量重定向
            binding.tvCaptureStatus.text = "步骤 3/4 · 设置流量重定向..."
            SnackbarHelper.info(binding.root, "📋 步骤3：设置iptables流量重定向").show()
            delay(500)

            // 步骤4：启动游戏
            binding.tvCaptureStatus.text = "步骤 4/4 · 启动游戏..."
            SnackbarHelper.info(binding.root, "📋 步骤4：启动魔灵召唤").show()
            delay(300)

            launchGame()

            // 等待游戏启动
            delay(1500)
            binding.tvCaptureStatus.text = "抓取中... 请在游戏中完成操作"
            SnackbarHelper.success(binding.root, "✅ 抓取服务已就绪，请在游戏中操作").show()
        }
    }

    data class CaptureCheckResult(
        val canStart: Boolean,
        val hasWarning: Boolean,
        val message: String
    )

    /**
     * 引导用户授予悬浮窗权限
     * Android 6.0+ 需要用户手动在系统设置中授权
     * Android 8.0+ 严格限制 TYPE_APPLICATION_OVERLAY 必须有此权限
     */
    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("抓取功能需要在游戏上层显示状态悬浮窗。\n\n请前往系统设置，找到本应用并开启「显示在其他应用上层」权限。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun updateCaptureUI() {
        val running = CaptureService.isRunning
        binding.btnStartCapture.visibility = if (running) View.GONE else View.VISIBLE
        binding.btnStopCapture.visibility = if (running) View.VISIBLE else View.GONE

        if (running) {
            binding.tvCaptureStatus.text = "抓取服务运行中，请打开游戏"
        } else {
            val isRooted = RootInstallHelper.isDeviceRooted()
            binding.tvCaptureStatus.text = if (isRooted) {
                "需要 Root 权限 · 点击开始将自动启动游戏并抓取"
            } else {
                "设备未 Root，无法使用配置抓取功能"
            }
        }

        val latest = CaptureRepository.getLatestCapture(this)
        if (latest != null) {
            val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(latest.timestamp))
            binding.tvCaptureLastResult.text = "最近抓取: $date · 魔灵:${latest.unitCount} 符文:${latest.runeCount} 遗物:${latest.artifactCount}"
            binding.tvCaptureLastResult.visibility = View.VISIBLE
        }
    }

    /**
     * 启动魔灵召唤游戏
     * 优先使用包管理器启动，失败则尝试am命令
     */
    private fun launchGame() {
        try {
            val packageName = com.swupdater.util.AppInfoUtil.PACKAGE_NAME_CN
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                SnackbarHelper.info(binding.root, "游戏已启动，抓取服务正在后台运行").show()
                AppLog.i("MainActivity", "游戏已启动: $packageName")
            } else {
                // 尝试通过am命令启动
                try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "am start -n $packageName/com.com2us.smon.normal.freefull.google.kr.android.common.ActivityMain")
                    )
                    process.waitFor()
                    process.destroy()
                    SnackbarHelper.info(binding.root, "游戏已通过Root启动").show()
                    AppLog.i("MainActivity", "游戏已通过Root启动: $packageName")
                } catch (e: Exception) {
                    SnackbarHelper.warning(binding.root, "无法启动游戏，请手动打开").show()
                    AppLog.e("MainActivity", "Root启动游戏失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AppLog.e("MainActivity", "启动游戏失败: ${e.message}")
            SnackbarHelper.warning(binding.root, "启动游戏失败，请手动打开游戏").show()
        }
    }

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
        SnackbarHelper.info(binding.root, getString(R.string.wallpaper_changing)).show()

        lifecycleScope.launch {
            try {
                WallpaperManager.ensureDefaultWallpaper(this@MainActivity)
                val wallpaperFile = WallpaperManager.randomWallpaper(this@MainActivity)
                if (wallpaperFile != null) {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(wallpaperFile.absolutePath)
                    }
                    if (bitmap != null) {
                        showWallpaperBitmap(bitmap)
                        SnackbarHelper.success(binding.root, getString(R.string.wallpaper_changed)).show()
                    } else {
                        SnackbarHelper.error(binding.root, getString(R.string.wallpaper_change_failed)).show()
                    }
                } else {
                    SnackbarHelper.error(binding.root, getString(R.string.wallpaper_change_failed)).show()
                }
            } catch (e: Exception) {
                AppLog.e("MainActivity", "更换壁纸失败: ${e.message}")
                SnackbarHelper.error(binding.root, getString(R.string.wallpaper_change_failed)).show()
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
     * 显示壁纸缩略图到卡片内
     */
    private var currentWallpaperBitmap: Bitmap? = null

    private fun showWallpaperBitmap(bitmap: Bitmap) {
        currentWallpaperBitmap?.recycle()
        currentWallpaperBitmap = bitmap
        binding.ivWallpaper.setImageBitmap(bitmap)
        binding.ivWallpaper.visibility = View.VISIBLE
        binding.tvNoWallpaper.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        currentWallpaperBitmap?.recycle()
        currentWallpaperBitmap = null
    }

    private fun applyWallpaperToSystem() {
        if (binding.ivWallpaper.visibility != View.VISIBLE) {
            SnackbarHelper.warning(binding.root, getString(R.string.wallpaper_apply_no_wallpaper)).show()
            return
        }

        lifecycleScope.launch {
            try {
                val wallpaperFile = WallpaperManager.getCurrentWallpaperFile(this@MainActivity)
                if (wallpaperFile == null || !wallpaperFile.exists()) {
                    SnackbarHelper.warning(binding.root, getString(R.string.wallpaper_apply_no_wallpaper)).show()
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
                    SnackbarHelper.success(binding.root, getString(R.string.wallpaper_apply_success)).show()
                    AppLog.i("MainActivity", "壁纸已应用到手机系统壁纸")
                } else {
                    SnackbarHelper.error(binding.root, getString(R.string.wallpaper_apply_failed)).show()
                }
            } catch (e: Exception) {
                SnackbarHelper.error(binding.root, "${getString(R.string.wallpaper_apply_failed)}: ${e.message}").show()
                AppLog.e("MainActivity", "应用壁纸失败: ${e.message}")
            }
        }
    }

    private fun downloadCurrentWallpaper() {
        // 检查是否有存储权限
        if (!WallpaperManager.hasStoragePermission(this)) {
            // 提示需要权限，然后去请求
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("保存壁纸到公共目录需要存储权限，请授权")
                .setPositiveButton("去授权") { _, _ ->
                    val intent = WallpaperManager.getStoragePermissionIntent(this)
                    if (intent != null) {
                        storagePermissionLauncher.launch(intent)
                    } else {
                        // 如果无法获取权限Intent，直接尝试下载（保存到私有目录）
                        lifecycleScope.launch {
                            performDownloadWallpaper()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            lifecycleScope.launch {
                performDownloadWallpaper()
            }
        }
    }

    private suspend fun performDownloadWallpaper() {
        try {
            val result = WallpaperManager.downloadCurrentWallpaper(this@MainActivity)
            if (result.success) {
                AppLog.i("MainActivity", "壁纸已保存: ${result.filePath}")
                SnackbarHelper.success(binding.root, "壁纸已保存: ${result.fileName}", Snackbar.LENGTH_LONG)
                    .setAction("查看") { openWallpaperFolder() }
                    .show()
            } else {
                SnackbarHelper.error(binding.root, "壁纸保存失败: ${result.error}").show()
            }
        } catch (e: Exception) {
            SnackbarHelper.error(binding.root, "壁纸保存失败: ${e.message}").show()
            AppLog.e("MainActivity", "壁纸保存失败: ${e.message}")
        }
    }

    private fun openWallpaperFolder() {
        try {
            val dir = WallpaperManager.getWallpaperDownloadDir(this@MainActivity)
            if (!dir.exists()) dir.mkdirs()
            AppLog.i("MainActivity", "打开壁纸目录: ${dir.absolutePath}")

            var opened = false

            // 方式1: 使用 Storage Access Framework 打开下载目录
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
                        AppLog.i("MainActivity", "通过 SAF 打开下载目录成功")
                    }
                } catch (e: Exception) {
                    AppLog.d("MainActivity", "SAF 方式失败: ${e.message}")
                }
            }

            // 方式2: 尝试各品牌文件管理器，并传入目录路径
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
                    ),
                    Triple(
                        "com.oppo.filemanager",
                        "com.coloros.filemanager.main.MainActivity",
                        dirPath
                    ),
                    Triple(
                        "com.vivo.filemanager",
                        "com.vivo.filemanager.activity.MainActivity",
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
                        AppLog.i("MainActivity", "通过文件管理器打开: $pkg")
                        break
                    } catch (_: Exception) { continue }
                }
            }

            // 方式3: 使用 ACTION_OPEN_DOCUMENT 作为回退
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
                    AppLog.i("MainActivity", "通过文档选择器打开")
                } catch (e: Exception) {
                    AppLog.d("MainActivity", "文档选择器失败: ${e.message}")
                }
            }

            // 所有方式都失败，显示路径并支持复制
            if (!opened) {
                AppLog.w("MainActivity", "无法打开目录，显示路径: ${dir.absolutePath}")
                SnackbarHelper.info(binding.root, "壁纸目录: ${dir.absolutePath}", Snackbar.LENGTH_LONG)
                    .setAction("复制路径") {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("壁纸目录", dir.absolutePath))
                        Toast.makeText(this@MainActivity, "路径已复制", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        } catch (e: Exception) {
            AppLog.e("MainActivity", "打开目录失败: ${e.message}")
            SnackbarHelper.error(binding.root, "无法打开目录: ${e.message}").show()
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
        binding.btnDownload.setOnClickListener { showDownloadChannelDialog() }
        binding.btnOpenStore.setOnClickListener { viewModel.startDownload() }
        binding.btnStopDownload.setOnClickListener { viewModel.cancelDownload() }
        binding.btnInstall.setOnClickListener { attemptInstall() }
        binding.btnOpenGame.setOnClickListener {
            if (!viewModel.launchGame()) Toast.makeText(this, "无法启动游戏", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示下载渠道选择对话框
     */
    private fun showDownloadChannelDialog() {
        val latest = viewModel.latestVersion.value ?: return
        val channels = viewModel.getDownloadChannels()
        if (channels.isEmpty()) {
            Toast.makeText(this, "没有可用的下载渠道", Toast.LENGTH_SHORT).show()
            return
        }

        val channelNames = channels.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择下载渠道")
            .setItems(channelNames) { _, which ->
                val selectedChannel = channels[which]
                viewModel.downloadViaChannel(selectedChannel)
            }
            .setNegativeButton("取消", null)
            .show()
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
                    }
                    DownloadState.DOWNLOADED -> {
                        binding.tvDownloadTitle.text = getString(R.string.status_downloaded)
                        binding.btnStopDownload.visibility = View.GONE
                        binding.progressDownload.progress = 100
                    }
                    DownloadState.VERIFYING -> {
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.verifying_integrity)
                        binding.tvVerifyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.info))
                    }
                    DownloadState.VERIFIED -> {
                        AppLog.i("MainActivity", "下载校验通过")
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.integrity_pass)
                        binding.tvVerifyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                        binding.btnInstall.visibility = View.VISIBLE

                        // 检查是否启用Root自动安装
                        val rootAutoInstallEnabled = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                            .getBoolean("pref_root_auto_install", false)
                        val isRooted = com.swupdater.util.RootInstallHelper.isDeviceRooted()
                        
                        if (rootAutoInstallEnabled && isRooted) {
                            AppLog.i("MainActivity", "Root自动安装已启用，开始静默安装...")
                            Toast.makeText(this@MainActivity, "已启用Root自动安装，正在后台安装…", Toast.LENGTH_SHORT).show()
                            binding.cardDownload.visibility = View.GONE
                            viewModel.installApk()
                        } else {
                            AppLog.i("MainActivity", "Root自动安装未启用，显示安装对话框")
                            showInstallDialog()
                        }
                    }
                    DownloadState.VERIFY_FAILED -> {
                        AppLog.e("MainActivity", "下载校验失败")
                        binding.tvVerifyStatus.visibility = View.VISIBLE
                        binding.tvVerifyStatus.text = getString(R.string.integrity_fail)
                        binding.tvVerifyStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
                    }
                    DownloadState.INSTALLING -> {
                        AppLog.i("MainActivity", "正在安装中...")
                        binding.tvStatus.text = getString(R.string.status_installing)
                    }
                    DownloadState.FAILED -> {
                        AppLog.e("MainActivity", "下载失败")
                        binding.tvStatus.text = getString(R.string.status_error)
                        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.error)
                        binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.error))
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
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
        if (::binding.isInitialized) {
            updateCaptureUI()
        }
    }
}
