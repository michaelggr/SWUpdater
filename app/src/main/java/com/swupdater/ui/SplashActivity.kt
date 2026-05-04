package com.swupdater.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swupdater.R
import com.swupdater.databinding.ActivitySplashBinding
import com.swupdater.util.AppLog
import com.swupdater.util.WallpaperManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    // 用于请求安装未知应用权限
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 继续下一个权限请求
        requestOverlayPermission()
    }

    // 用于请求悬浮窗权限
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            AppLog.i(TAG, "悬浮窗权限获取成功")
        }
        // 继续请求存储权限
        requestStoragePermission()
    }

    // 用于请求存储权限（Android 11+ MANAGE_EXTERNAL_STORAGE）
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (WallpaperManager.hasStoragePermission(this)) {
            AppLog.i(TAG, "存储权限获取成功")
        }
        // 继续请求通知权限
        requestNotificationPermission()
    }

    // 用于请求通知权限（Android 13+）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLog.i(TAG, "通知权限获取成功")
        }
        // 继续请求电池优化白名单
        requestBatteryOptimization()
    }

    // 用于请求电池优化白名单
    private val batteryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 所有权限请求完成，进入主页
        onAllEssentialPermissionsGranted()
    }

    companion object {
        private const val TAG = "SplashActivity"
        const val EXTRA_FROM_SPLASH = "extra_from_splash"
        private const val PREFS_NAME = "splash_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }

    @Volatile
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.swupdater.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // 检查是否是首次安装，或者是否需要权限引导
        if (!isFirstLaunch() && areAllEssentialPermissionsGranted()) {
            skipToMain()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        updatePermissionStatus()

        if (isRootUser()) {
            binding.tvRootHint.visibility = View.VISIBLE
        }
    }

    private fun isFirstLaunch(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    private fun markFirstLaunchComplete() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    private fun areAllEssentialPermissionsGranted(): Boolean {
        val storageGranted = WallpaperManager.hasStoragePermission(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        val installGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        return storageGranted && overlayGranted && installGranted && notificationGranted
    }

    private fun setupClickListeners() {
        binding.btnRequestPermission.setOnClickListener {
            showPermissionExplainDialog()
        }

        binding.btnSkip.setOnClickListener {
            if (isRootUser()) {
                skipToMain()
            } else {
                showSkipConfirmDialog()
            }
        }
    }

    /**
     * 更新权限状态显示
     */
    private fun updatePermissionStatus() {
        val storageGranted = WallpaperManager.hasStoragePermission(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        val installGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val batteryGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        binding.tvStorageStatus.text = if (storageGranted) getString(R.string.granted) else getString(R.string.not_granted)
        binding.tvStorageStatus.setTextColor(getColor(if (storageGranted) R.color.success else R.color.error))

        binding.tvOverlayStatus.text = if (overlayGranted) getString(R.string.granted) else getString(R.string.not_granted)
        binding.tvOverlayStatus.setTextColor(getColor(if (overlayGranted) R.color.success else R.color.error))

        binding.tvInstallStatus.text = if (installGranted) getString(R.string.granted) else getString(R.string.not_granted)
        binding.tvInstallStatus.setTextColor(getColor(if (installGranted) R.color.success else R.color.error))

        binding.tvNotificationStatus.text = if (notificationGranted) getString(R.string.granted) else getString(R.string.not_granted)
        binding.tvNotificationStatus.setTextColor(getColor(if (notificationGranted) R.color.success else R.color.error))

        binding.tvBatteryStatus.text = if (batteryGranted) getString(R.string.granted) else getString(R.string.not_granted)
        binding.tvBatteryStatus.setTextColor(getColor(if (batteryGranted) R.color.success else R.color.error))

        val allGranted = storageGranted && overlayGranted && installGranted && notificationGranted && batteryGranted
        binding.btnRequestPermission.isEnabled = !allGranted
        if (allGranted) {
            binding.btnRequestPermission.text = getString(R.string.permissions_all_granted)
            binding.btnSkip.visibility = View.GONE
        }
    }

    /**
     * 显示权限说明对话框
     */
    private fun showPermissionExplainDialog() {
        val message = HtmlCompat.fromHtml("""
            <b>${getString(R.string.permission_storage)}</b><br/>
            ${getString(R.string.permission_storage_desc)}<br/><br/>
            <b>${getString(R.string.permission_overlay)}</b><br/>
            ${getString(R.string.permission_overlay_desc)}<br/><br/>
            <b>${getString(R.string.permission_install)}</b><br/>
            ${getString(R.string.permission_install_desc)}<br/><br/>
            <b>${getString(R.string.permission_notification)}</b><br/>
            ${getString(R.string.permission_notification_desc)}<br/><br/>
            <b>${getString(R.string.permission_battery)}</b><br/>
            ${getString(R.string.permission_battery_desc)}<br/><br/>
            <font color="#666666">${getString(R.string.permission_root_hint)}</font>
        """.trimIndent(), HtmlCompat.FROM_HTML_MODE_COMPACT)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.permission_start) { _, _ ->
                requestPermissionsStepByStep()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 逐步请求权限
     */
    private fun requestPermissionsStepByStep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                requestInstallPermission()
                return
            }
        }
        requestOverlayPermission()
    }

    /**
     * 请求安装未知应用权限
     */
    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                installPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "请求安装权限失败", e)
                requestOverlayPermission()
            }
        } else {
            requestOverlayPermission()
        }
    }

    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "请求悬浮窗权限失败", e)
                requestStoragePermission()
            }
        } else {
            requestStoragePermission()
        }
    }

    /**
     * 请求存储权限（MANAGE_EXTERNAL_STORAGE）
     */
    private fun requestStoragePermission() {
        val intent = WallpaperManager.getStoragePermissionIntent(this)
        if (intent != null) {
            storagePermissionLauncher.launch(intent)
        } else {
            requestNotificationPermission()
        }
    }

    /**
     * 请求通知权限
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBatteryOptimization()
        }
    }

    /**
     * 请求电池优化白名单
     */
    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    batteryPermissionLauncher.launch(intent)
                    return
                } catch (e: Exception) {
                    AppLog.e(TAG, "请求电池优化权限失败", e)
                }
            }
        }
        onAllEssentialPermissionsGranted()
    }

    /**
     * 所有必要权限获取完成
     */
    private fun onAllEssentialPermissionsGranted() {
        if (isNavigating) return
        isNavigating = true
        AppLog.i(TAG, "所有必要权限已获取，准备进入主页")
        binding.tvStatus.text = getString(R.string.permission_ready)
        markFirstLaunchComplete()
        skipToMain()
    }

    /**
     * 显示存储权限被拒绝的提示
     */
    private fun showStorageDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_storage_denied_title)
            .setMessage(R.string.permission_storage_denied_message)
            .setPositiveButton(R.string.continue_btn) { _, _ ->
                onAllEssentialPermissionsGranted()
            }
            .setNegativeButton(R.string.retry) { _, _ ->
                requestStoragePermission()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 显示悬浮窗权限被拒绝的提示
     */
    private fun showOverlayDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_overlay_denied_title)
            .setMessage(R.string.permission_overlay_denied_message)
            .setPositiveButton(R.string.continue_btn) { _, _ ->
                requestStoragePermission()
            }
            .setNegativeButton(R.string.retry) { _, _ ->
                requestOverlayPermission()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 显示确认跳过权限请求的对话框
     */
    private fun showSkipConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_skip_title)
            .setMessage(R.string.permission_skip_message)
            .setPositiveButton(R.string.confirm_skip) { _, _ ->
                skipToMain()
            }
            .setNegativeButton(R.string.continue_permission, null)
            .show()
    }

    /**
     * 跳过权限请求，直接进入主页
     */
    private fun skipToMain() {
        if (isNavigating) return
        isNavigating = true
        markFirstLaunchComplete()
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(EXTRA_FROM_SPLASH, true)
        startActivity(intent)
        finish()
    }

    /**
     * 检查是否是 Root 用户
     */
    private fun isRootUser(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = process.inputStream.bufferedReader()
            val output = reader.readLine()
            reader.close()
            output?.contains("uid=0") == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (isNavigating) return

        updatePermissionStatus()

        if (areAllEssentialPermissionsGranted()) {
            if (binding.btnRequestPermission.text != getString(R.string.permissions_all_granted)) {
                binding.tvStatus.text = getString(R.string.permission_ready)
                onAllEssentialPermissionsGranted()
            }
        }
    }
}
