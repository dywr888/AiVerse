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
import android.widget.Switch
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
 * 悬浮球使用 App 图标，可拖动到屏幕任意位置；单击从底部拉出控制面板（小半个区域），
 * 面板含「启用」开关与「大小」滑块（默认 42dp）。开关与大小持久化到
 * [ToolSafetySettingsRepository]；App 重启后由 MainActivity 自动恢复（开关开启时）。
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
        // 读取持久化开关与大小；开关已关（如系统重启拉起残留）时直接退出。
        scope.launch {
            val enabled = toolSafety.overlayBallEnabledFlow.first()
            if (!enabled) {
                stopSelf()
            } else {
                ballSizeDp = toolSafety.overlayBallSizeFlow.first().coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
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

        // 拖动：ACTION_MOVE 更新坐标；单击（位移小于阈值）启动 AiVerse；长按（按住未移动）循环切换大小档位。
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            cycleBallSize()
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
                    // 长按计时：按住未移动达到阈值即切换大小档位
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
                        // 单击：启动 AiVerse 主界面（球保留，可继续使用）
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

    /** 单击：启动 AiVerse 主界面。 */
    private fun launchMainActivity() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }

    /** 长按：循环切换大小档位，持久化并即时更新球尺寸。 */
    private fun cycleBallSize() {
        val next = SIZE_STEPS.firstOrNull { it > ballSizeDp } ?: SIZE_STEPS.first()
        ballSizeDp = next
        scope.launch { toolSafety.setOverlayBallSize(next) }
        val sp = dp(next)
        ballParams?.let { p ->
            p.width = sp
            p.height = sp
            ballView?.let { v -> runCatching { windowManager?.updateViewLayout(v, p) } }
        }
    }

    /** 底部控制面板：半透明遮罩 + 底部小半屏，含「启用」开关与「大小」滑块。 */
    private fun showPanel() {
        if (panelView != null) return
        val wm = windowManager ?: return
        val context = this

        // 遮罩 + 面板根
        val root = FrameLayout(context).apply {
            setBackgroundColor(0x55000000.toInt())
        }

        // 底部面板
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(30))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        // 标题
        panel.addView(TextView(context).apply {
            text = getString(R.string.overlay_ball_panel_title)
            textSize = 17f
            setTextColor(0xFF1A1A1A.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        // 启用开关行
        val switchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(12))
        }
        switchRow.addView(
            TextView(context).apply {
                text = getString(R.string.overlay_ball_switch_label)
                textSize = 16f
                setTextColor(0xFF1A1A1A.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val switch = Switch(context).apply { isChecked = true } // 服务在跑即已启用
        switchRow.addView(switch)
        panel.addView(switchRow)

        // 大小行：标签 + 滑块 + 数值
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        sizeRow.addView(
            TextView(context).apply {
                text = getString(R.string.overlay_ball_size_label)
                textSize = 16f
                setTextColor(0xFF1A1A1A.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sizeValue = TextView(context).apply {
            text = getString(R.string.overlay_ball_size_value, ballSizeDp)
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(12), 0, 0, 0)
        }
        val seek = SeekBar(context).apply {
            max = MAX_SIZE_DP - MIN_SIZE_DP
            progress = (ballSizeDp - MIN_SIZE_DP).coerceIn(0, max)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newSize = MIN_SIZE_DP + progress
                ballSizeDp = newSize
                sizeValue.text = getString(R.string.overlay_ball_size_value, newSize)
                scope.launch { toolSafety.setOverlayBallSize(newSize) }
                // 面板打开时球隐藏，尺寸在恢复球时生效；若球恰在显示则实时调整
                ballView?.let { v ->
                    val p = ballParams ?: return@let
                    val sp = dp(newSize)
                    p.width = sp
                    p.height = sp
                    runCatching { wm.updateViewLayout(v, p) }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sizeRow.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
        sizeRow.addView(sizeValue)
        panel.addView(sizeRow)

        // 面板贴底
        root.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply {
                gravity = Gravity.BOTTOM
            }
        )

        // 遮罩点击关闭；面板区域拦截（空监听）防止误关
        root.setOnClickListener { closePanel() }
        panel.setOnClickListener { /* 拦截面板内点击，不关闭 */ }

        // 开关关闭：停用悬浮球（持久化 + 关闭服务，onDestroy 移除球与面板）
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                scope.launch {
                    toolSafety.setOverlayBallEnabled(false)
                    stopSelf()
                }
            }
        }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { wm.addView(root, params) }
            .onSuccess { panelView = root }
            .onFailure { /* 添加失败不致命：球已隐藏，下次可重开 */ }
    }

    private fun closePanel() {
        panelView?.let { view -> runCatching { windowManager?.removeView(view) } }
        panelView = null
        // 开关仍开时恢复悬浮球
        scope.launch {
            if (toolSafety.overlayBallEnabledFlow.first()) {
                ballSizeDp = toolSafety.overlayBallSizeFlow.first().coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
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
        /** 长按循环切换的大小档位（dp）。 */
        val SIZE_STEPS = intArrayOf(36, 44, 52, 62, 74)
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
