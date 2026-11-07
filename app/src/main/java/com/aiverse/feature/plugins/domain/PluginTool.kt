package com.aiverse.feature.plugins.domain

import com.aiverse.feature.agent.domain.tool.AgentTool
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolPermissionPolicy
import com.aiverse.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 插件工具：把 [$ptools] 条目包装成 [AgentTool] 注册进 ToolRegistry。
 * 命名约定「工具名前缀=包名」→ [name] = "${pluginId}_${entry.name}"。
 * 权限在插件安装时已由用户确认（危险权限橙色提示），执行不再逐次询问 → AUTO_APPROVE；
 * handler 内部的 ctx 调用（http/file 等）由 [CtxApi] 按 manifest.permissions 校验。
 */
class PluginTool(
    private val pluginId: String,
    private val entry: PluginToolEntry,
    private val runtime: PluginRuntime
) : AgentTool() {
    override val name: String = "${PluginTool.sanitizeName(pluginId)}_${PluginTool.sanitizeName(entry.name)}"
    override val description: String = entry.description
    override val parameters: Map<String, ToolParameter> = entry.parameters
    override val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val argsJson = JsonObject(args).toString()
        return runtime.invokeTool(entry, argsJson)
    }

    companion object {
        /** 工具名须为 [A-Za-z0-9_-]（Anthropic/OpenAI/Gemini 均禁止点号等字符），包名含点统一转下划线。 */
        fun sanitizeName(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
