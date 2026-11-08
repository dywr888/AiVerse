package com.aiverse.core.ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.aiverse.R
import com.aiverse.feature.settings.data.repository.ToolSafetySettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 系统级悬浮球（工具授权页「悬浮球」开关开启后显示）。
 *
 * 悬浮球使用 App 图标，可拖动到屏幕任意位置；单击快速切换到当前 App，长按在球旁弹出
 * 正方形控制面板（含「大小」与「隐藏透明度」滑块，默认 42dp、100% 不透明）。大小与
 * 透明度持久化到 [ToolSafetySettingsRepository]；App 重启后由 MainActivity 自动恢复（开关开启时）。
 *
 * 需要 [Settings.ACTION_MANAGE_OVERLAY_PERMISSION] 悬浮窗权限，未授予时关闭自身。
 */
@AndroidEntryPoint
class OverlayBallService : Service() {

    @Inject
    lateinit var toolSafety: ToolSafetySettingsRepository

    private var windowManager: WindowManager? = null
    private var ballView: ImageView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var ballSizeDp: Int = DEFAULT_SIZE_DP
    private var ballAlphaPercent: Int = ToolSafetySettingsRepository.DEFAULT_ALPHA_PERCENT
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 前台服务必须在 onStartCommand 后尽快 startForeground（Android 8+ 强制）。
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 读取持久化开关、大小与透明度；开关已关（如系统重启拉起残留）时直接退出。
        scope.launch {
            val enabled = toolSafety.overlayBallEnabledFlow.first()
            if (!enabled) {
                stopSelf()
            } else {
                ballSizeDp = toolSafety.overlayBallSizeFlow.first().coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
                ballAlphaPercent = toolSafety.overlayBallAlphaFlow.first().coerceIn(
                    ToolSafetySettingsRepository.MIN_ALPHA_PERCENT,
                    ToolSafetySettingsRepository.MAX_ALPHA_PERCENT
                )
                showBall()
            }
        }
        return START_STICKY
    }

    private fun showBall() {
        if (ballView != null) return
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        windowManager = wm

        val sizePx = dp(ballSizeDp)
        val imageView = ImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(packageName))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6FFFFFF.toInt())
                setStroke(dp(2), 0x33000000)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            alpha = ballAlphaPercent / 100f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(280)
        }

        // 拖动：ACTION_MOVE 更新坐标；单击（位移小于阈值）快速切换当前 App；长按（按住未移动）在球旁弹出控制面板。
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            showPanel()
        }
        imageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    longPressTriggered = false
                    // 长按计时：按住未移动达到阈值即弹出控制面板
                    imageView.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > TOUCH_SLOP || abs(dy) > TOUCH_SLOP) {
                        moved = true
                        // 拖动后取消长按
                        imageView.removeCallbacks(longPressRunnable)
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { wm.updateViewLayout(imageView, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    imageView.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered) {
                        // 单击：快速切换当前 App（球保留，可继续使用）
                        launchMainActivity()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    imageView.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        runCatching { wm.addView(imageView, params) }
            .onSuccess {
                ballView = imageView
                ballParams = params
            }
            .onFailure {
                stopSelf()
            }
    }

    private fun hideBall() {
        ballView?.let { view -> runCatching { windowManager?.removeView(view) } }
        ballView = null
        ballParams = null
    }

    /** 单击：快速切换到当前 App（AiVerse 主界面，球保留可继续使用）。 */
    private fun launchMainActivity() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    /** 长按：在球旁弹出正方形控制面板，含「大小」与「隐藏透明度」滑块，改动即时生效并持久化。 */
    private fun showPanel() {
        if (panelView != null) return
        val wm = windowManager ?: return
        val context = this
        val ballP = ballParams ?: return

        val panelSize = dp(PANEL_SIZE_DP)
        // 正方形根容器
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x22000000)
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }

        // 标题行：标题 + 关闭按钮
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = getString(R.string.overlay_ball_panel_title)
                textSize = 16f
                setTextColor(0xFF1A1A1A.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(TextView(context).apply {
            text = "\u00d7"
            textSize = 20f
            setTextColor(0xFF8A8A8A.toInt())
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener { closePanel() }
        })
        content.addView(titleRow)

        // 大小滑块
        content.addView(
            sliderRow(
                getString(R.string.overlay_ball_size_label),
                MAX_SIZE_DP - MIN_SIZE_DP,
                ballSizeDp - MIN_SIZE_DP,
                getString(R.string.overlay_ball_size_value, ballSizeDp)
            ) { progress ->
                val newSize = MIN_SIZE_DP + progress
                ballSizeDp = newSize
                scope.launch { toolSafety.setOverlayBallSize(newSize) }
                ballView?.let { v ->
                    val p = ballParams ?: return@let
                    val sp = dp(newSize)
                    p.width = sp
                    p.height = sp
                    runCatching { wm.updateViewLayout(v, p) }
                }
                getString(R.string.overlay_ball_size_value, newSize)
            }
        )

        // 隐藏透明度滑块
        content.addView(
            sliderRow(
                getString(R.string.overlay_ball_alpha_label),
                ToolSafetySettingsRepository.MAX_ALPHA_PERCENT - ToolSafetySettingsRepository.MIN_ALPHA_PERCENT,
                ballAlphaPercent - ToolSafetySettingsRepository.MIN_ALPHA_PERCENT,
                getString(R.string.overlay_ball_alpha_value, ballAlphaPercent)
            ) { progress ->
                val newAlpha = ToolSafetySettingsRepository.MIN_ALPHA_PERCENT + progress
                ballAlphaPercent = newAlpha
                scope.launch { toolSafety.setOverlayBallAlpha(newAlpha) }
                ballView?.alpha = newAlpha / 100f
                getString(R.string.overlay_ball_alpha_value, newAlpha)
            }
        )

        root.addView(content)

        // 定位：优先放球右侧，放不下放左侧；垂直对齐球顶并夹在屏内
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val gap = dp(12)
        val x = if (ballP.x + ballP.width + panelSize + gap <= screenW) {
            ballP.x + ballP.width + gap
        } else {
            (ballP.x - panelSize - gap).coerceAtLeast(dp(8))
        }
        val yMax = (screenH - panelSize - dp(8)).coerceAtLeast(dp(8))
        val y = ballP.y.coerceIn(dp(8), yMax)

        val params = WindowManager.LayoutParams(
            panelSize,
            panelSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        runCatching { wm.addView(root, params) }
            .onSuccess { panelView = root }
            .onFailure { /* 添加失败不致命：球仍在，可再次长按 */ }
    }

    /** 构建「标签 + 滑块 + 数值」行；拖动时回调 [onProgress]，以其返回值刷新数值文本。 */
    private fun sliderRow(
        label: String,
        max: Int,
        progress: Int,
        initialValue: String,
        onProgress: (Int) -> String
    ): LinearLayout {
        val context = this
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        row.addView(
            TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF1A1A1A.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val value = TextView(context).apply {
            text = initialValue
            textSize = 15f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(10), 0, 0, 0)
        }
        val seek = SeekBar(context).apply {
            this.max = max
            this.progress = progress.coerceIn(0, max)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                value.text = onProgress(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        row.addView(value)
        return row
    }

    private fun closePanel() {
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        // 开关仍开时保持悬浮球（重读最新大小与透明度）
        scope.launch {
            if (toolSafety.overlayBallEnabledFlow.first()) {
                ballSizeDp = toolSafety.overlayBallSizeFlow.first().coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
                ballAlphaPercent = toolSafety.overlayBallAlphaFlow.first().coerceIn(
                    ToolSafetySettingsRepository.MIN_ALPHA_PERCENT,
                    ToolSafetySettingsRepository.MAX_ALPHA_PERCENT
                )
                showBall()
            }
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.overlay_ball_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_ball_notification))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
    }

    override fun onDestroy() {
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        ballView?.let { view -> runCatching { windowManager?.removeView(view) } }
        ballView = null
        ballParams = null
        windowManager = null
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "OverlayBallService"
        private const val NOTIFICATION_ID = 10
        private const val CHANNEL_ID = "overlay_ball"
        private const val TOUCH_SLOP = 10
        private const val LONG_PRESS_TIMEOUT_MS = 500L
        const val DEFAULT_SIZE_DP = 42
        const val MIN_SIZE_DP = 32
        const val MAX_SIZE_DP = 80
        /** 控制面板边长（dp），正方形。 */
        private const val PANEL_SIZE_DP = 260
        const val ACTION_SHOW = "com.aiverse.action.SHOW_OVERLAY_BALL"
        const val ACTION_HIDE = "com.aiverse.action.HIDE_OVERLAY_BALL"

        /** 开启悬浮球（幂等；需已授予悬浮窗权限，否则服务自动退出）。 */
        fun show(context: Context) {
            val intent = Intent(context, OverlayBallService::class.java).apply { action = ACTION_SHOW }
            runCatching { context.startForegroundService(intent) }
        }

        /** 关闭悬浮球。 */
        fun hide(context: Context) {
            val intent = Intent(context, OverlayBallService::class.java).apply { action = ACTION_HIDE }
            runCatching { context.startForegroundService(intent) }
        }
    }
}
