package com.aiverse.core.ui.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aiverse.R
import com.aiverse.feature.agent.domain.permission.PermissionChoice
import com.aiverse.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aiverse.feature.agent.domain.tool.PendingToolPermission
import com.aiverse.feature.agent.domain.tool.ToolPermissionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shizuku 控制手机（ShizukuControl）的超高危险确认悬浮窗。
 *
 * 普通工具的授权由聊天输入框上方的授权面板（前台）与后台审批通知兜底；本服务专责
 * ShizukuControl——无论应用在前台还是用户停留在其他页面，都以系统悬浮窗的形式弹出
 * 「拒绝 / 允许」确认，避免高风险指令在无人看管时被静默放行。
 *
 * 常驻于应用进程（应用启动时由 AIEditorApp 拉起），仅在有 ShizukuControl 待决请求时
 * 展示窗口，其余时间不占用任何屏幕资源。依赖悬浮窗权限；未授予时直接停止（权限引擎
 * 会先于请求评估拒绝该调用并提示原因）。
 */
@AndroidEntryPoint
class OverlayApprovalService : Service() {

    @Inject
    lateinit var toolPermissionManager: ToolPermissionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var windowView: View? = null
    private var titleView: TextView? = null
    private var summaryView: TextView? = null
    private var currentRequest: PendingToolPermission? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch {
            toolPermissionManager.pendingRequest.collect { request ->
                if (request?.toolName == ToolPermissionPolicyEngine.SHIZUKU_CONTROL_TOOL) {
                    showWindow(request)
                } else {
                    hideWindow()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideWindow()
        scope.cancel()
        super.onDestroy()
    }

    private fun showWindow(request: PendingToolPermission) {
        if (windowView == null) {
            buildWindow()
        }
        currentRequest = request
        titleView?.text = request.title
        summaryView?.text = request.summary
        windowView?.let { view ->
            if (view.parent == null) {
                windowManager?.addView(view, buildParams())
            }
        }
    }

    private fun hideWindow() {
        currentRequest = null
        windowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        windowView = null
        titleView = null
        summaryView = null
    }

    private fun buildWindow() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            elevation = dp(16).toFloat()
            background = GradientDrawable().apply {
                setColor(0xF2FFFFFF.toInt())
                cornerRadius = dp(20).toFloat()
            }
        }
        val title = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF111827.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        val summary = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4B5563.toInt())
            maxLines = 3
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(buildActionButton(getString(R.string.notification_approval_action_reject), 0xFFDC2626.toInt()) {
            resolve(PermissionChoice.REJECT)
        })
        buttons.addView(buildActionButton(getString(R.string.notification_approval_action_allow), 0xFF2563EB.toInt()) {
            resolve(PermissionChoice.ONCE)
        })

        root.addView(title)
        root.addView(
            summary,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        )
        root.addView(buttons)

        titleView = title
        summaryView = summary
        windowView = root
    }

    private fun buildActionButton(text: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextColor(color)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

    private fun resolve(choice: PermissionChoice) {
        val request = currentRequest ?: return
        toolPermissionManager.resolve(request.id, choice)
        hideWindow()
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(32),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }
        return params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
