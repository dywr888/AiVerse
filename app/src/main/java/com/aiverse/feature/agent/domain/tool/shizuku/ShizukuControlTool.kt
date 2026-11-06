package com.aiverse.feature.agent.domain.tool.shizuku

import com.aiverse.core.util.FileLogger
import com.aiverse.feature.agent.domain.model.AgentImage
import com.aiverse.feature.agent.domain.shizuku.ShizukuManager
import com.aiverse.feature.agent.domain.shizuku.ShizukuState
import com.aiverse.feature.agent.domain.tool.AgentTool
import com.aiverse.feature.agent.domain.tool.ParameterType
import com.aiverse.feature.agent.domain.tool.PendingToolPermission
import com.aiverse.feature.agent.domain.tool.ToolCapability
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolPermissionPolicy
import com.aiverse.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * 通过 Shizuku 控制宿主手机的工具：打开应用、截屏、模拟点击。
 *
 * 与通用 [ShizukuTool]（任意 shell 命令）不同，本工具限定为对手机屏幕 / 应用的操作，
 * 属超高危险指令：即使授权页开启了「禁用安全拦截」或「一键允许所有工具」，权限引擎仍
 * 强制逐次确认（见 ToolPermissionPolicyEngine）。截屏输出走大输出上限，其余命令为短输出。
 */
class ShizukuControlTool @Inject constructor(
    private val shizukuManager: ShizukuManager,
    @ApplicationContext private val context: Context
) : AgentTool() {
    private companion object {
        const val TAG = "ShizukuControlTool"
        const val ACTION_OPEN_APP = "open_app"
        const val ACTION_SCREENSHOT = "screenshot"
        const val ACTION_TAP = "tap"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_INPUT_TEXT = "input_text"
        const val ACTION_KEY_EVENT = "key_event"
        const val ACTION_LONG_PRESS = "long_press"
        const val ACTION_LIST_APPS = "list_apps"

        const val DEFAULT_TIMEOUT_SECONDS = 60L
        const val MAX_TIMEOUT_SECONDS = 1_800L

        /** 应用包名白名单字符：防 shell 命令注入。 */
        private val PACKAGE_NAME_REGEX = Regex("[a-zA-Z0-9._]+")

        /** input_text 文本中允许的字符（防注入）。 */
        private val SAFE_TEXT_REGEX = Regex("[^;&|`$\\\\]+")

        /** key_event 支持的按键名白名单。 */
        private val KEYCODE_MAP = mapOf(
            "BACK" to 4, "HOME" to 3, "MENU" to 82, "ENTER" to 66,
            "DEL" to 67, "TAB" to 61, "ESC" to 111, "POWER" to 26,
            "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "RECENT" to 187,
            "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22
        )
    }

    override val name = "ShizukuControl"

    override val description =
        "通过 Shizuku 控制宿主手机：打开应用（open_app）、截屏（screenshot）、模拟点击（tap）、滑动（swipe）、长按（long_press）、输入文字（input_text）、按键（key_event）、列出已安装应用（list_apps）。截屏会返回屏幕尺寸供坐标校准。" +
            "超高危险操作，每次执行都需用户确认。需已安装、运行并授权 Shizuku。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：open_app 打开应用 / screenshot 截屏 / tap 点击 / swipe 滑动 / long_press 长按 / input_text 输入文字 / key_event 按键 / list_apps 列出已安装应用",
            required = true,
            enum = listOf(ACTION_OPEN_APP, ACTION_SCREENSHOT, ACTION_TAP, ACTION_SWIPE, ACTION_LONG_PRESS, ACTION_INPUT_TEXT, ACTION_KEY_EVENT, ACTION_LIST_APPS)
        ),
        "package_name" to ToolParameter(
            name = "package_name",
            type = ParameterType.STRING,
            description = "open_app 时必填：要打开的应用包名（如 com.tencent.mm）",
            required = false
        ),
        "x" to ToolParameter(
            name = "x",
            type = ParameterType.INTEGER,
            description = "tap/long_press 时必填：屏幕 x 坐标；swipe 时为起点 x",
            required = false
        ),
        "y" to ToolParameter(
            name = "y",
            type = ParameterType.INTEGER,
            description = "tap/long_press 时必填：屏幕 y 坐标；swipe 时为起点 y",
            required = false
        ),
        "x2" to ToolParameter(
            name = "x2",
            type = ParameterType.INTEGER,
            description = "swipe 时必填：终点 x 坐标",
            required = false
        ),
        "y2" to ToolParameter(
            name = "y2",
            type = ParameterType.INTEGER,
            description = "swipe 时必填：终点 y 坐标",
            required = false
        ),
        "duration" to ToolParameter(
            name = "duration",
            type = ParameterType.INTEGER,
            description = "swipe 滑动时长（毫秒），默认 300；long_press 长按时长（毫秒），默认 1000",
            required = false
        ),
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "input_text 时必填：要输入的文字（不支持特殊字符 ;&|`$\\\\）",
            required = false
        ),
        "keycode" to ToolParameter(
            name = "keycode",
            type = ParameterType.STRING,
            description = "key_event 时必填：按键名，支持 BACK/HOME/MENU/ENTER/DEL/TAB/ESC/POWER/VOLUME_UP/VOLUME_DOWN/RECENT/UP/DOWN/LEFT/RIGHT",
            required = false
        ),
        "third_party_only" to ToolParameter(
            name = "third_party_only",
            type = ParameterType.BOOLEAN,
            description = "list_apps 时可选：仅列出第三方应用（默认 true）",
            required = false
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: ""
        val detail = when (action) {
            ACTION_OPEN_APP ->
                "打开应用：${args["package_name"]?.jsonPrimitive?.contentOrNull ?: "（未指定）"}"
            ACTION_SCREENSHOT -> "截屏当前手机屏幕（图片返回给 AI）"
            ACTION_TAP ->
                "模拟点击：(${args["x"]?.jsonPrimitive?.intOrNull ?: "?"} , ${args["y"]?.jsonPrimitive?.intOrNull ?: "?"})"
            ACTION_SWIPE ->
                "滑动：(${args["x"]?.jsonPrimitive?.intOrNull ?: "?"},${args["y"]?.jsonPrimitive?.intOrNull ?: "?"}) → (${args["x2"]?.jsonPrimitive?.intOrNull ?: "?"},${args["y2"]?.jsonPrimitive?.intOrNull ?: "?"})"
            ACTION_LONG_PRESS ->
                "长按：(${args["x"]?.jsonPrimitive?.intOrNull ?: "?"} , ${args["y"]?.jsonPrimitive?.intOrNull ?: "?"})"
            ACTION_INPUT_TEXT ->
                "输入文字：${args["text"]?.jsonPrimitive?.contentOrNull ?: "（未指定）"}"
            ACTION_KEY_EVENT ->
                "按键：${args["keycode"]?.jsonPrimitive?.contentOrNull ?: "（未指定）"}"
            ACTION_LIST_APPS -> "列出已安装应用"
            else -> "未知操作"
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认控制手机",
            summary = detail,
            details = "超高危险操作，每次必须手动确认。\n操作：$detail",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：action")

        val state = shizukuManager.state.value
        if (state != ShizukuState.READY) {
            return ToolResult.Error("Shizuku 未就绪：${stateHint(state)}", code = "SHIZUKU_NOT_READY")
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            when (action) {
                ACTION_OPEN_APP -> openApp(args, timeoutMs)
                ACTION_SCREENSHOT -> screenshot(timeoutMs)
                ACTION_TAP -> tap(args, timeoutMs)
                ACTION_SWIPE -> swipe(args, timeoutMs)
                ACTION_LONG_PRESS -> longPress(args, timeoutMs)
                ACTION_INPUT_TEXT -> inputText(args, timeoutMs)
                ACTION_KEY_EVENT -> keyEvent(args, timeoutMs)
                ACTION_LIST_APPS -> listApps(args, timeoutMs)
                else -> ToolResult.Error("未知操作：$action")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "ShizukuControl $action 失败", e)
            ToolResult.Error("执行 Shizuku 控制操作失败：${e.message}")
        }
    }

    private suspend fun openApp(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val pkg = args["package_name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：package_name")
        if (!PACKAGE_NAME_REGEX.matches(pkg)) {
            return ToolResult.Error("非法的包名：$pkg")
        }
        val result = shizukuManager.runCommand(
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
            timeoutMs
        )
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("已打开应用 $pkg"))
        } else {
            ToolResult.Error("打开应用失败（退出码 ${result.exitCode}）：${result.output}")
        }
    }

    private suspend fun tap(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：x")
        val y = args["y"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：y")
        val result = shizukuManager.runCommand("input tap $x $y", timeoutMs)
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("已模拟点击 ($x, $y)"))
        } else {
            ToolResult.Error("模拟点击失败（退出码 ${result.exitCode}）：${result.output}")
        }
    }

    private suspend fun screenshot(timeoutMs: Long): ToolResult {
        val cacheDir = context.externalCacheDir
            ?: return ToolResult.Error("无法访问外部缓存目录")
        val screenshotFile = File(cacheDir, "aiverse_screenshot.png")
        screenshotFile.delete()
        val path = screenshotFile.absolutePath

        // 同时获取屏幕尺寸，供 AI 校准点击坐标
        val sizeResult = shizukuManager.runCommand("wm size", timeoutMs)
        val screenSize = sizeResult.output.lineSequence()
            .firstOrNull { it.contains("Physical size") }
            ?.substringAfter(":")
            ?.trim()
            ?: "unknown"

        val result = shizukuManager.runCommand("screencap -p $path", timeoutMs)
        if (result.exitCode != 0) {
            return ToolResult.Error("截屏失败（退出码 ${result.exitCode}）：${result.output}")
        }
        if (!screenshotFile.exists() || screenshotFile.length() == 0L) {
            return ToolResult.Error("截屏文件未生成或为空")
        }
        return try {
            val bytes = screenshotFile.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ToolResult.Success(
                data = JsonPrimitive("已截屏（${bytes.size} 字节，屏幕尺寸 $screenSize，坐标系：左上角为 0,0）"),
                images = listOf(AgentImage(mimeType = "image/png", base64Data = base64))
            )
        } catch (e: Exception) {
            ToolResult.Error("读取截屏文件失败：${e.message}")
        } finally {
            screenshotFile.delete()
        }
    }

    private suspend fun swipe(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val x1 = args["x"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：x（起点 x）")
        val y1 = args["y"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：y（起点 y）")
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：x2（终点 x）")
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：y2（终点 y）")
        val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 300
        val result = shizukuManager.runCommand("input swipe $x1 $y1 $x2 $y2 $duration", timeoutMs)
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("已滑动 ($x1,$y1) → ($x2,$y2)，时长 ${duration}ms"))
        } else {
            ToolResult.Error("滑动失败（退出码 ${result.exitCode}）：${result.output}")
        }
    }

    private suspend fun longPress(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val x = args["x"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：x")
        val y = args["y"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("缺少必需参数：y")
        val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 1000
        // 长按 = swipe 到同一点
        val result = shizukuManager.runCommand("input swipe $x $y $x $y $duration", timeoutMs)
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("已长按 ($x, $y)，时长 ${duration}ms"))
        } else {
            ToolResult.Error("长按失败（退出码 ${result.exitCode}）：${result.output}")
        }
    }

    private suspend fun inputText(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：text")
        if (!SAFE_TEXT_REGEX.matches(text)) {
            return ToolResult.Error("输入文本包含不允许的字符（;&|`$\\\\）")
        }
        val result = shizukuManager.runCommand("input text \"$text\"", timeoutMs)
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("已输入文字：$text"))
        } else {
            ToolResult.Error("输入文字失败（退出码 ${result.exitCode}）：${result.output}")
        }
    }

    private suspend fun keyEvent(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val keycodeName = args["keycode"]?.jsonPrimitive?.contentOrNull?.uppercase()
            ?: return ToolResult.Error("缺少必需参数：keycode")
        val keycode = KEYCODE_MAP[keycodeName]
            ?: return ToolResult.Error("不支持的按键：$keycodeName（支持：${KEYCODE_MAP.keys.joinToString("/")}）")
        val result = shizukuManager.runCommand("input keyevent $keycode", timeoutMs)
        return if (result.exitCode == 0) {
            ToolResult.Success(JsonPrimitive("已按下按键：$keycodeName"))
        } else {
            ToolResult.Error("按键失败（退出码 ${result.exitCode}）：${result.output}")
        }
    }

    private suspend fun listApps(args: Map<String, JsonElement>, timeoutMs: Long): ToolResult {
        val thirdParty = args["third_party_only"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) ?: true
        val cmd = if (thirdParty) "pm list packages -3" else "pm list packages"
        val result = shizukuManager.runCommand(cmd, timeoutMs)
        if (result.exitCode != 0) {
            return ToolResult.Error("列出应用失败（退出码 ${result.exitCode}）：${result.output}")
        }
        // 输出格式：package:com.xxx.xxx，提取包名
        val packages = result.output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toList()
        return ToolResult.Success(JsonPrimitive("已安装应用（${packages.size} 个）：\n${packages.joinToString("\n")}"))
    }

    private fun stateHint(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "未安装 Shizuku，请先安装并启动 Shizuku 服务"
        ShizukuState.NOT_RUNNING -> "Shizuku 服务未运行，请在 Shizuku 应用中启动服务"
        ShizukuState.PERMISSION_DENIED -> "本应用尚未获得 Shizuku 授权，请在设置中授予"
        ShizukuState.READY -> ""
    }
}
