package com.aiverse.feature.plugins.domain

import com.aiverse.feature.agent.domain.tool.AgentTool
import com.aiverse.feature.agent.domain.tool.ToolParameter
import com.aiverse.feature.agent.domain.tool.ToolPermissionPolicy
import com.aiverse.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 「列出脚本插件」工具：AI 先调用本工具查看已安装插件的介绍、权限及各工具用途，
 * 再按需调用具体插件工具（类似技能/MCP 的查看入口）。只读，AUTO_APPROVE。
 */
class PluginListTool(
    private val manager: PluginManager
) : AgentTool() {
    override val name: String = "plugins_list"
    override val description: String =
        "列出已安装的脚本插件：名称、版本、介绍、权限及每个工具的用途。AI 先看介绍再决定调用哪个插件工具。"
    override val parameters: Map<String, ToolParameter> = emptyMap()
    override val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val plugins = buildJsonArray {
            manager.describe().forEach { p ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(p.id))
                    put("name", JsonPrimitive(p.name))
                    put("version", JsonPrimitive(p.version))
                    put("description", JsonPrimitive(p.description))
                    put("author", JsonPrimitive(p.author))
                    put("enabled", JsonPrimitive(p.enabled))
                    put("permissions", buildJsonArray { p.permissions.forEach { add(JsonPrimitive(it)) } })
                    put("skills", buildJsonArray { p.skills.forEach { add(JsonPrimitive(it)) } })
                    put("mcpServers", buildJsonArray { p.mcpServers.forEach { add(JsonPrimitive(it)) } })
                    put("tools", buildJsonArray {
                        p.tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(t.name))
                                put("description", JsonPrimitive(t.description))
                            })
                        }
                    })
                })
            }
        }
        return ToolResult.Success(buildJsonObject { put("plugins", plugins) })
    }
}
