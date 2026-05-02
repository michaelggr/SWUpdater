package com.swupdater.ui

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.swupdater.R
import com.swupdater.databinding.ActivitySplashBinding
import com.swupdater.util.AppLog
import com.swupdater.util.WallpaperManager

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (WallpaperManager.hasStoragePermission(this)) {
            AppLog.i(TAG, "存储权限获取成功")
            onStoragePermissionGranted()
        } else {
            AppLog.w(TAG, "存储权限被拒绝")
            showStorageDeniedDialog()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            AppLog.i(TAG, "悬浮窗权限获取成功")
            requestStoragePermission()
        } else {
            AppLog.w(TAG, "悬浮窗权限被拒绝")
            showOverlayDeniedDialog()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            AppLog.i(TAG, "通知权限获取成功")
        } else {
            AppLog.w(TAG, "通知权限被拒绝")
        }
        onAllEssentialPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        updatePermissionStatus()

        if (isRootUser()) {
            binding.tvRootHint.visibility = View.VISIBLE
        }
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
        val storageGranted = WallpaperManager.hasStoragePermission(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        val installGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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

        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.permission_start) { _, _ ->
                requestPermissionsStepByStep()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestPermissionsStepByStep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                requestInstallPermission()
                return
            }
        }
        requestOverlayPermission()
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_INSTALL_APPS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "请求安装权限失败", e)
                requestOverlayPermission()
            }
        } else {
            requestOverlayPermission()
        }
    }

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

    private fun requestStoragePermission() {
        val intent = WallpaperManager.getStoragePermissionIntent(this)
        if (intent != null) {
            storagePermissionLauncher.launch(intent)
        } else {
            onStoragePermissionGranted()
        }
    }

    private fun onStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBatteryOptimization()
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
        onAllEssentialPermissionsGranted()
    }

    private val batteryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onAllEssentialPermissionsGranted()
    }

    private fun onAllEssentialPermissionsGranted() {
        AppLog.i(TAG, "所有必要权限已获取，准备进入主页")
        binding.tvStatus.text = getString(R.string.permission_ready)
        skipToMain()
    }

    private fun showStorageDeniedDialog() {
        AlertDialog.Builder(this)
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

    private fun showOverlayDeniedDialog() {
        AlertDialog.Builder(this)
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

    private fun showSkipConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_skip_title)
            .setMessage(R.string.permission_skip_message)
            .setPositiveButton(R.string.confirm_skip) { _, _ ->
                skipToMain()
            }
            .setNegativeButton(R.string.continue_permission, null)
            .show()
    }

    private fun skipToMain() {
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

    override fun onResume() {
        super.onResume()
        if (binding.btnRequestPermission.text != getString(R.string.permissions_all_granted)) {
            updatePermissionStatus()
            val batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            } else true

            if (WallpaperManager.hasStoragePermission(this) &&
                Settings.canDrawOverlays(this) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) &&
                batteryOptimized) {
                binding.tvStatus.text = getString(R.string.permission_ready)
                skipToMain()
            }
        }
    }

    companion object {
        private const val TAG = "SplashActivity"
        const val EXTRA_FROM_SPLASH = "extra_from_splash"
    }
}
