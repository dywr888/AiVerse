package com.aiverse.feature.plugins.domain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aiverse.MainActivity
import com.aiverse.feature.agent.domain.permission.PermissionChoice
import com.aiverse.feature.agent.domain.tool.AgentTool
import com.aiverse.feature.agent.domain.tool.PendingToolPermission
import com.aiverse.feature.agent.domain.tool.ToolPermissionManager
import com.aiverse.feature.agent.domain.tool.ToolRegistry
import com.aiverse.feature.agent.domain.tool.ToolResult
import com.aiverse.feature.agent.domain.tool.toTransportString
import com.aiverse.feature.plugins.data.PluginRepository
import com.aiverse.feature.plugins.domain.model.PluginManifest
import com.aiverse.feature.plugins.domain.model.PluginPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

/**
 * 注入到 JS 全局 `ctx` 对象的宿主能力桥。插件脚本通过 `ctx.showToast()` 等调用。
 *
 * 所有方法由 QuickJS 的 EventQueue 线程同步调用（taoweiji 库为同步 API）；耗时操作
 * （http、callTool）用 runBlocking 在该线程内完成——每插件独立运行时，阻塞仅影响本插件。
 *
 * 权限由 [PluginManifest.permissions] 声明，危险操作无权限时抛异常，JS 侧捕获。
 * 文件沙箱限制在插件目录内，防目录穿越。
 */
class CtxApi(
    private val appContext: Context,
    private val manifest: PluginManifest,
    private val pluginDir: File,
    private val toolRegistry: ToolRegistry?,
    private val toolPermissionManager: ToolPermissionManager,
    private val repository: PluginRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val storeProps = Properties().apply {
        val f = File(pluginDir, "store.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    private val storeFile = File(pluginDir, "store.properties")

    // ---- 工具内/插件级状态（useState 简化：插件级 key-value，跨调用共享） ----
    private val stateMap = mutableMapOf<String, String>()

    private fun require(perm: String) {
        if (perm in manifest.permissions) return
        if (perm in repository.approvedPermissions(manifest.id)) return
        // 缺权限自动申请：弹窗等用户选择（始终/一次/拒绝），复用工具授权链路。
        val label = PluginPermissions.LABELS[perm] ?: perm
        val request = PendingToolPermission(
            id = "plugin_perm_${manifest.id}_$perm",
            toolName = manifest.id,
            title = "插件请求权限：$label",
            summary = "「${manifest.name}」需要权限：$label",
            details = "授权后插件可调用该能力；「始终允许」会记住（等同在 plugin.json 声明），「仅本次」临时放行。",
            argsPreview = perm,
            sessionId = ""
        )
        when (runBlocking { toolPermissionManager.awaitApproval(request) }) {
            PermissionChoice.ALWAYS -> repository.grantPermission(manifest.id, perm)
            PermissionChoice.ONCE -> Unit
            PermissionChoice.REJECT -> throw IllegalStateException("用户拒绝插件权限: $label")
        }
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun notify(title: String, body: String) {
        require(PluginPermissions.NOTIFY)
        if (!ensureChannel()) return
        if (!canPostNotifications()) {
            showToast("通知权限未开启：$title")
            return
        }
        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(appContext).notify(notificationId(title), notification)
        } catch (e: SecurityException) {
            showToast("通知失败：${e.message ?: "无权限"}")
        }
    }

    /** 创建插件通知渠道（幂等）。返回是否成功（API < 26 也视为成功，用兼容通道）。 */
    private fun ensureChannel(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "插件通知", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "脚本插件发送的通知"
                    setShowBadge(false)
                }
            )
        }
        return true
    }

    /** Android 13+ 需 POST_NOTIFICATIONS 运行时权限，且系统通知总开关开启。 */
    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun notificationId(title: String): Int = title.hashCode() and 0x7fffffff

    // ---- 环境变量（会话级内存） ----
    private val envMap = mutableMapOf<String, String>()

    @JavascriptInterface
    fun getEnv(key: String): String? = envMap[key]

    @JavascriptInterface
    fun setEnv(key: String, value: String) {
        envMap[key] = value
    }

    // ---- 状态（useState 简化） ----
    @JavascriptInterface
    fun stateGet(key: String): String? = stateMap[key]

    @JavascriptInterface
    fun stateSet(key: String, value: String) {
        stateMap[key] = value
    }

    // ---- store.*（持久化，插件私有） ----
    @JavascriptInterface
    fun storeGet(key: String): String? = storeProps.getProperty(key)

    @JavascriptInterface
    fun storeSet(key: String, value: String) {
        storeProps.setProperty(key, value)
        persistStore()
    }

    @JavascriptInterface
    fun storeRemove(key: String) {
        storeProps.remove(key)
        persistStore()
    }

    private fun persistStore() {
        storeFile.outputStream().use { storeProps.store(it, null) }
    }

    // ---- http/fetch（同步 OkHttp，MVP 用 HttpURLConnection） ----
    @JavascriptInterface
    fun httpFetch(url: String, optionsJson: String): String {
        require(PluginPermissions.NETWORK)
        return try {
            val opts = if (optionsJson.isNotBlank()) Json.parseToJsonElement(optionsJson).jsonObject
            else JsonObject(emptyMap())
            val method = (opts["method"] as? JsonPrimitive)?.content ?: "GET"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.uppercase()
                connectTimeout = 15000
                readTimeout = 30000
                opts["headers"]?.let { h ->
                    (h as? JsonObject)?.forEach { (k, v) ->
                        setRequestProperty(k, (v as? JsonPrimitive)?.content ?: v.toString())
                    }
                }
            }
            (opts["body"] as? JsonPrimitive)?.content?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            buildJsonObject {
                put("status", JsonPrimitive(code))
                put("body", JsonPrimitive(body))
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("status", JsonPrimitive(0))
                put("error", JsonPrimitive(e.message ?: e.javaClass.simpleName))
            }.toString()
        }
    }

    // ---- file 沙箱（限制在插件目录内） ----
    private fun sandbox(path: String): File {
        val base = pluginDir.canonicalFile
        val target = File(pluginDir, path).canonicalFile
        if (!target.path.startsWith(base.path + File.separator) && target != base) {
            throw SecurityException("文件路径越界: $path")
        }
        return target
    }

    @JavascriptInterface
    fun fileRead(path: String): String {
        require(PluginPermissions.FILE)
        val f = sandbox(path)
        return if (f.exists()) f.readText() else ""
    }

    @JavascriptInterface
    fun fileWrite(path: String, content: String) {
        require(PluginPermissions.FILE_WRITE)
        val f = sandbox(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    @JavascriptInterface
    fun fileDelete(path: String) {
        require(PluginPermissions.FILE_WRITE)
        val f = sandbox(path)
        if (f.exists()) f.delete()
    }

    @JavascriptInterface
    fun fileList(path: String): String {
        require(PluginPermissions.FILE)
        val f = sandbox(path)
        return if (f.isDirectory) {
            JsonArray(f.listFiles()?.map { JsonPrimitive(it.name) } ?: emptyList()).toString()
        } else "[]"
    }

    // ---- all-files read（读任意路径，需所有文件访问权限） ----
    @JavascriptInterface
    fun readSystemFile(path: String): String {
        require(PluginPermissions.ALL_FILES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return err("需要「所有文件访问」权限，请在系统设置中开启")
        }
        val f = File(path)
        if (f.isDirectory) return err("是目录，请指定文件路径: $path")
        if (!f.exists()) return err("文件不存在: $path")
        return try {
            buildJsonObject {
                put("status", JsonPrimitive("success"))
                put("data", JsonPrimitive(f.readText(Charsets.UTF_8)))
            }.toString()
        } catch (e: Exception) {
            err("读取失败（非文本或编码不支持）: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @JavascriptInterface
    fun writeSystemFile(path: String, content: String): String {
        require(PluginPermissions.ALL_FILES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return err("需要「所有文件访问」权限，请在系统设置中开启")
        }
        val f = File(path)
        if (f.isDirectory) return err("是目录，请指定文件路径: $path")
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            buildJsonObject {
                put("status", JsonPrimitive("success"))
                put("data", JsonPrimitive("已保存: $path"))
            }.toString()
        } catch (e: Exception) {
            err("保存失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- clipboard ----
    @JavascriptInterface
    fun clipboardGet(): String {
        require(PluginPermissions.CLIPBOARD)
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    @JavascriptInterface
    fun clipboardSet(text: String) {
        require(PluginPermissions.CLIPBOARD)
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("plugin", text))
    }

    // ---- callTool（调 AiVerse 已有工具；仅 AUTO_APPROVE，避免绕过权限） ----
    @JavascriptInterface
    fun callTool(name: String, argsJson: String): String {
        val registry = toolRegistry ?: return err("工具系统不可用")
        val tool: AgentTool = registry.getTool(name) ?: return err("工具不存在: $name")
        if (tool.permissionPolicy != com.aiverse.feature.agent.domain.tool.ToolPermissionPolicy.AUTO_APPROVE) {
            return err("工具需要授权，插件不能直接调用: $name")
        }
        val args = try {
            Json.parseToJsonElement(argsJson).jsonObject.mapValues { it.value }
        } catch (e: Exception) {
            return err("参数解析失败: ${e.message}")
        }
        return try {
            val result = runBlocking(Dispatchers.IO) { tool.execute(args) }
            when (result) {
                is ToolResult.Success -> result.toTransportString()
                is ToolResult.Error -> buildJsonObject {
                    put("status", JsonPrimitive("error"))
                    put("error", JsonPrimitive(result.message))
                }.toString()
                is ToolResult.Partial -> buildJsonObject {
                    put("status", JsonPrimitive("partial"))
                    put("message", JsonPrimitive(result.message))
                }.toString()
            }
        } catch (e: Exception) {
            err("执行失败: ${e.message}")
        }
    }

    private fun err(msg: String): String = buildJsonObject {
        put("status", JsonPrimitive("error"))
        put("message", JsonPrimitive(msg))
    }.toString()

    private companion object {
        const val CHANNEL_ID = "plugin_notify"
    }
}
