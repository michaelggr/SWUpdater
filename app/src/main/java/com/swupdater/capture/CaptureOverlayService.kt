package com.swupdater.capture

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.swupdater.R
import com.swupdater.util.AppLog
import java.io.File

/**
 * 抓取悬浮窗服务
 * 在游戏上层显示抓取状态，不影响玩家操作
 * - 状态指示：等待中/抓取中/成功
 * - 成功后显示分享按钮
 * - 可拖动，不拦截触摸事件（登录时不影响）
 */
class CaptureOverlayService : Service() {

    companion object {
        private const val TAG = "CaptureOverlay"

        const val ACTION_SHOW = "com.swupdater.action.OVERLAY_SHOW"
        const val ACTION_UPDATE = "com.swupdater.action.OVERLAY_UPDATE"
        const val ACTION_SUCCESS = "com.swupdater.action.OVERLAY_SUCCESS"
        const val ACTION_HIDE = "com.swupdater.action.OVERLAY_HIDE"

        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DETAIL = "extra_detail"
        const val EXTRA_FILE_PATH = "extra_file_path"

        const val STATUS_WAITING = "waiting"
        const val STATUS_CAPTURING = "capturing"
        const val STATUS_SUCCESS = "success"

        fun show(context: Context) {
            val intent = Intent(context, CaptureOverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun updateStatus(context: Context, status: String, detail: String = "") {
            val intent = Intent(context, CaptureOverlayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DETAIL, detail)
            }
            context.startService(intent)
        }

        fun showSuccess(context: Context, filePath: String) {
            val intent = Intent(context, CaptureOverlayService::class.java).apply {
                action = ACTION_SUCCESS
                putExtra(EXTRA_FILE_PATH, filePath)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, CaptureOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var capturedFilePath: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_UPDATE -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: STATUS_WAITING
                val detail = intent.getStringExtra(EXTRA_DETAIL) ?: ""
                updateOverlay(status, detail)
            }
            ACTION_SUCCESS -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: ""
                capturedFilePath = filePath
                showSuccessOverlay(filePath)
            }
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        val params = createLayoutParams()

        // 创建悬浮窗布局
        overlayView = createOverlayView(STATUS_WAITING, "等待游戏数据...")

        // 拖动逻辑
        setupDrag(overlayView!!, params)

        try {
            windowManager?.addView(overlayView, params)
            AppLog.i(TAG, "悬浮窗已显示")
        } catch (e: Exception) {
            AppLog.e(TAG, "悬浮窗显示失败: ${e.message}")
        }
    }

    private fun updateOverlay(status: String, detail: String) {
        if (overlayView == null) {
            showOverlay()
        }

        overlayView?.let { view ->
            val tvStatus = view.findViewById<TextView>(R.id.tv_overlay_status)
            val tvDetail = view.findViewById<TextView>(R.id.tv_overlay_detail)
            val ivDot = view.findViewById<ImageView>(R.id.iv_overlay_dot)
            val btnShare = view.findViewById<LinearLayout>(R.id.btn_overlay_share)

            tvStatus?.text = getStatusText(status)
            tvDetail?.text = detail

            // 根据状态切换指示灯颜色
            val dotRes = when (status) {
                STATUS_CAPTURING -> R.drawable.bg_status_dot_capturing
                STATUS_SUCCESS -> R.drawable.bg_status_dot_success
                else -> R.drawable.bg_status_dot_waiting
            }
            ivDot?.setImageResource(dotRes)

            // 抓取中不显示分享按钮
            btnShare?.visibility = View.GONE
        }
    }

    private fun showSuccessOverlay(filePath: String) {
        if (overlayView == null) {
            showOverlay()
        }

        overlayView?.let { view ->
            val tvStatus = view.findViewById<TextView>(R.id.tv_overlay_status)
            val tvDetail = view.findViewById<TextView>(R.id.tv_overlay_detail)
            val ivDot = view.findViewById<ImageView>(R.id.iv_overlay_dot)
            val btnShare = view.findViewById<LinearLayout>(R.id.btn_overlay_share)

            tvStatus?.text = "抓取成功"
            ivDot?.setImageResource(R.drawable.bg_status_dot_success)

            val file = File(filePath)
            tvDetail?.text = if (file.exists()) "已保存: ${file.name}" else "数据已保存"

            // 显示分享按钮
            btnShare?.visibility = View.VISIBLE
            btnShare?.setOnClickListener {
                shareCaptureFile(filePath)
            }
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView(status: String, detail: String): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundResource(R.drawable.bg_capture_overlay)

            // 状态指示灯
            val dotRes = when (status) {
                STATUS_CAPTURING -> R.drawable.bg_status_dot_capturing
                STATUS_SUCCESS -> R.drawable.bg_status_dot_success
                else -> R.drawable.bg_status_dot_waiting
            }
            val dot = ImageView(context).apply {
                id = R.id.iv_overlay_dot
                setImageResource(dotRes)
                layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                    marginEnd = 8
                }
            }
            addView(dot)

            // 文字区域
            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusText = TextView(context).apply {
                id = R.id.tv_overlay_status
                text = getStatusText(status)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
            }
            textContainer.addView(statusText)

            val detailText = TextView(context).apply {
                id = R.id.tv_overlay_detail
                text = detail
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 10f
                setSingleLine(true)
            }
            textContainer.addView(detailText)

            addView(textContainer)

            // 分享按钮（默认隐藏）
            val shareBtn = LinearLayout(context).apply {
                id = R.id.btn_overlay_share
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 6, 12, 6)
                setBackgroundResource(R.drawable.bg_overlay_share_btn)
                visibility = View.GONE
                isClickable = true
                isFocusable = true

                val shareIcon = TextView(context).apply {
                    text = "分享"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 11f
                }
                addView(shareIcon)
            }
            addView(shareBtn)
        }
        return container
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // FLAG_NOT_TOUCH_MODAL: 不拦截窗口外的触摸事件
            // FLAG_NOT_FOCUSABLE: 不获取焦点，不影响游戏输入
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 100
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // 短按不处理（让子View的点击事件正常触发）
                }
            }
            // 不消费事件，让触摸穿透到下层（游戏）
            !isDragging
        }
    }

    private fun shareCaptureFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                AppLog.e(TAG, "分享文件不存在: $filePath")
                return
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(Intent.createChooser(shareIntent, "分享抓取数据").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            AppLog.i(TAG, "已打开分享: ${file.name}")
        } catch (e: Exception) {
            AppLog.e(TAG, "分享失败: ${e.message}")
        }
    }

    private fun getStatusText(status: String): String {
        return when (status) {
            STATUS_WAITING -> "等待抓取"
            STATUS_CAPTURING -> "抓取中..."
            STATUS_SUCCESS -> "抓取成功"
            else -> "抓取中"
        }
    }
}