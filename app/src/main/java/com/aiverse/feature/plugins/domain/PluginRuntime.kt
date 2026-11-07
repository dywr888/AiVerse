package com.aiverse.feature.plugins.domain

import com.aiverse.feature.agent.domain.tool.ParameterType
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolResult
import com.aiverse.feature.plugins.domain.model.PluginManifest
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSObject
import com.quickjs.QuickJS
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 从插件 $ptools 提取的工具描述（handler 为 JS 函数引用，随 runtime 存活）。 */
class PluginToolEntry(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>,
    private val handler: JSObject,
    val requiresPermission: String?
) {
    @Suppress("unused")
    fun getHandler(): JSObject = handler
}

/**
 * 单个插件的 QuickJS 运行时：加载 main.js、注入 ctx、暴露 $ptools 工具、调用 handler。
 *
 * 每插件一个独立 [QuickJS] 实例（隔离上下文），跨线程调用由 [Mutex] 串行化；
 * QuickJS 用 createRuntimeWithEventQueue 保证跨线程安全。插件禁用/卸载时 [close]。
 */
class PluginRuntime(
    private val pluginDir: File,
    private val manifest: PluginManifest,
    private val ctxApi: CtxApi
) {
    private val mutex = Mutex()
    private val quickJS: QuickJS = QuickJS.createRuntimeWithEventQueue()
    private val context: JSContext = quickJS.createContext()
    private var closed = false
    private val entries = mutableListOf<PluginToolEntry>()

    private val json = Json { ignoreUnknownKeys = true }

    /** 加载入口脚本并解析 $ptools。返回工具描述列表（供注册进 ToolRegistry）。 */
    fun load(): List<PluginToolEntry> {
        context.addJavascriptInterface(ctxApi, "ctx")
        // 桥接函数：调用 handler(argsJson)（参数/返回均为 JSON 字符串），非字符串结果 JSON 序列化。
        context.executeVoidScript(
            "globalThis.__plugin_invoke = function(h, a) { " +
                "var r = h(a); " +
                "return (typeof r === 'string') ? r : JSON.stringify(r === undefined ? null : r); " +
                "};",
            "bootstrap.js"
        )
        val mainJs = File(pluginDir, manifest.main)
        if (!mainJs.exists()) throw IllegalStateException("入口脚本不存在: ${manifest.main}")
        context.executeVoidScript(mainJs.readText(), manifest.main)

        val ptools = context.getArray("\$ptools")
        if (ptools != null) {
            val count = ptools.length()
            for (i in 0 until count) {
                val entry = ptools.getObject(i) ?: continue
                val toolName = entry.getString("name") ?: continue
                val handler = entry.getObject("handler") ?: continue
                val description = entry.getString("description") ?: ""
                val params = parseParameters(entry.getObject("parameters"))
                val requiresPermission = entry.getString("permission")?.takeIf { it.isNotBlank() }
                entries += PluginToolEntry(toolName, description, params, handler, requiresPermission)
            }
        }
        return entries
    }

    private fun parseParameters(obj: JSObject?): Map<String, ToolParameter> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, ToolParameter>()
        obj.getKeys()?.forEach { key ->
            val p = obj.getObject(key) ?: return@forEach
            val type = p.getString("type") ?: "string"
            val paramType = when (type.lowercase()) {
                "integer", "number" -> ParameterType.INTEGER
                "boolean" -> ParameterType.BOOLEAN
                "array" -> ParameterType.ARRAY
                "object" -> ParameterType.OBJECT
                else -> ParameterType.STRING
            }
            out[key] = ToolParameter(
                name = key,
                type = paramType,
                description = p.getString("description") ?: "",
                required = p.getBoolean("required") ?: false
            )
        }
        return out
    }

    /** 调用插件工具：handler(argsJson)，argsJson 为工具参数的 JSON 对象字符串。 */
    suspend fun invokeTool(entry: PluginToolEntry, argsJson: String): ToolResult {
        return mutex.withLock {
            if (closed) return@withLock ToolResult.Error("插件已关闭")
            try {
                val raw = context.executeStringFunction(
                    "__plugin_invoke",
                    JSArray(context).push(entry.getHandler()).push(argsJson)
                ) ?: return@withLock ToolResult.Success(JsonNull)
                toToolResult(raw)
            } catch (e: Exception) {
                ToolResult.Error(e.message ?: "插件执行失败")
            }
        }
    }

    private fun toToolResult(raw: String): ToolResult {
        val el: JsonElement = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return ToolResult.Success(JsonPrimitive(raw))
        }
        return when {
            el is JsonObject && el["status"]?.jsonPrimitive?.content == "error" -> {
                val msg = el["message"]?.jsonPrimitive?.content ?: "插件错误"
                val code = el["code"]?.jsonPrimitive?.content ?: "PLUGIN_ERROR"
                ToolResult.Error(msg, code)
            }
            el is JsonObject && el["status"]?.jsonPrimitive?.content == "partial" -> {
                val msg = el["message"]?.jsonPrimitive?.content ?: ""
                val data = el["data"] ?: JsonNull
                ToolResult.Partial(data, msg)
            }
            el is JsonObject && el["status"]?.jsonPrimitive?.content == "success" -> {
                ToolResult.Success(el["data"] ?: JsonNull)
            }
            else -> ToolResult.Success(el)
        }
    }

    /** 已加载的工具描述（供 plugins_list 展示）。 */
    fun getEntries(): List<PluginToolEntry> = entries

    fun close() {
        if (closed) return
        closed = true
        try {
            context.close()
            quickJS.close()
        } catch (e: Exception) {
            // 关闭失败忽略
        }
    }
}
