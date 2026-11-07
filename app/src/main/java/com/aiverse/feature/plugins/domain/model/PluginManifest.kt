package com.aiverse.feature.plugins.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 脚本插件的 plugin.json 清单，字段与 OpenX 插件规范对齐。
 * 解析用 Json { ignoreUnknownKeys = true }，未知字段不报错。
 */
@Serializable
data class PluginManifest(
    /** 唯一 id（= package 字段，小写标识符，兼作工具命名前缀）。 */
    @SerialName("package")
    val packageName: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    /** 入口脚本，相对插件根目录。 */
    val main: String = "main.js",
    /** 插件规范版本，供宿主判断兼容性。 */
    val api: String = "0.1.0",
    /** 声明的权限标识，见 [PluginPermissions]。危险权限安装时橙色提示。 */
    val permissions: List<String> = emptyList(),
    /** 配套的技能名列表：声明该插件推荐/依赖的技能（多值），供 AI 参考并按需委托执行。 */
    val skills: List<String> = emptyList(),
    /** 配套的 MCP server 名列表：声明该插件依赖的 MCP server（多值），插件可经 ctx.callTool 调用其工具。 */
    val mcpServers: List<String> = emptyList(),
    /** 可选图标资源路径（相对插件根目录），MVP 未渲染，预留。 */
    val icon: String? = null
) {
    val id: String get() = packageName
}

/** 插件权限标识。 */
object PluginPermissions {
    /** 发起网络请求（危险）。 */
    const val NETWORK = "network"

    /** 读沙箱文件。 */
    const val FILE = "file"

    /** 写沙箱文件（危险）。 */
    const val FILE_WRITE = "file:write"

    /** 读写剪贴板（危险）。 */
    const val CLIPBOARD = "clipboard"

    /** 发系统通知。 */
    const val NOTIFY = "notify"

    /** 服务型插件（监听本地端口，MVP 未实现，仅预留）。 */
    const val SERVER = "server"

    /** 危险权限集合：安装时需用户确认。 */
    val DANGEROUS: Set<String> = setOf(NETWORK, FILE_WRITE, CLIPBOARD)

    /** 权限标识 → 中文说明，用于 UI 展示。 */
    val LABELS: Map<String, String> = mapOf(
        NETWORK to "网络访问",
        FILE to "读取插件文件",
        FILE_WRITE to "写入插件文件",
        CLIPBOARD to "读写剪贴板",
        NOTIFY to "发送通知",
        SERVER to "监听本地端口"
    )
}
