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

    @Volatile
    private var isNavigating = false

    @Volatile
    private var isRequestingPermissions = false

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isNavigating) return@registerForActivityResult
        updatePermissionStatus()
        continuePermissionChain()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isNavigating) return@registerForActivityResult
        if (Settings.canDrawOverlays(this)) {
            AppLog.i(TAG, "悬浮窗权限获取成功")
        }
        updatePermissionStatus()
        continuePermissionChain()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isNavigating) return@registerForActivityResult
        if (WallpaperManager.hasStoragePermission(this)) {
            AppLog.i(TAG, "存储权限获取成功")
        }
        updatePermissionStatus()
        continuePermissionChain()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (isNavigating) return@registerForActivityResult
        if (granted) {
            AppLog.i(TAG, "通知权限获取成功")
        }
        updatePermissionStatus()
        continuePermissionChain()
    }

    private val batteryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isNavigating) return@registerForActivityResult
        updatePermissionStatus()
        continuePermissionChain()
    }

    companion object {
        private const val TAG = "SplashActivity"
        const val EXTRA_FROM_SPLASH = "extra_from_splash"
        private const val PREFS_NAME = "splash_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.swupdater.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        if (!isFirstLaunch() && areAllEssentialPermissionsGranted()) {
            isNavigating = true
            navigateToMain()
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

    override fun onResume() {
        super.onResume()
        if (isNavigating) return
        if (isRequestingPermissions) return

        updatePermissionStatus()

        if (areAllEssentialPermissionsGranted()) {
            onAllEssentialPermissionsGranted()
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

    private fun updatePermissionStatus() {
        if (isNavigating) return

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

    private fun requestPermissionsStepByStep() {
        isRequestingPermissions = true
        continuePermissionChain()
    }

    private fun continuePermissionChain() {
        if (isNavigating) return

        if (areAllEssentialPermissionsGranted()) {
            isRequestingPermissions = false
            onAllEssentialPermissionsGranted()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            requestInstallPermission()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (!WallpaperManager.hasStoragePermission(this)) {
            val intent = WallpaperManager.getStoragePermissionIntent(this)
            if (intent != null) {
                try {
                    storagePermissionLauncher.launch(intent)
                    return
                } catch (e: Exception) {
                    AppLog.e(TAG, "请求存储权限失败", e)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                requestBatteryOptimization()
                return
            }
        }

        isRequestingPermissions = false
        onAllEssentialPermissionsGranted()
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                installPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "请求安装权限失败", e)
                continuePermissionChain()
            }
        } else {
            continuePermissionChain()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            AppLog.e(TAG, "请求悬浮窗权限失败", e)
            continuePermissionChain()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                AppLog.e(TAG, "请求通知权限失败", e)
                continuePermissionChain()
            }
        } else {
            continuePermissionChain()
        }
    }

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
        continuePermissionChain()
    }

    private fun onAllEssentialPermissionsGranted() {
        if (isNavigating) return
        isNavigating = true
        isRequestingPermissions = false
        AppLog.i(TAG, "所有必要权限已获取，准备进入主页")
        try {
            binding.tvStatus.text = getString(R.string.permission_ready)
        } catch (_: Exception) {}
        markFirstLaunchComplete()
        navigateToMain()
    }

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

    private fun skipToMain() {
        if (isNavigating) return
        isNavigating = true
        isRequestingPermissions = false
        markFirstLaunchComplete()
        navigateToMain()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(EXTRA_FROM_SPLASH, true)
        startActivity(intent)
        finish()
    }

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
}
