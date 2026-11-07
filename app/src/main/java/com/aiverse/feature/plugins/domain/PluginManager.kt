package com.aiverse.feature.plugins.domain

import android.content.Context
import android.util.Log
import com.aiverse.feature.agent.domain.tool.ToolPermissionManager
import com.aiverse.feature.agent.domain.tool.ToolRegistry
import com.aiverse.feature.plugins.data.PluginRepository
import com.aiverse.feature.plugins.domain.model.PluginManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 插件概览（供 plugins_list 工具返回给 AI）。 */
data class PluginOverview(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val enabled: Boolean,
    val permissions: List<String>,
    val skills: List<String>,
    val mcpServers: List<String>,
    val tools: List<PluginToolOverview>
)

/** 单个插件工具概览。 */
data class PluginToolOverview(val name: String, val description: String)

/**
 * 脚本插件编排：安装/卸载/启用/禁用，并把启用插件的工具注册进 [ToolRegistry]。
 *
 * 工具注册后 AIAgentViewModel 每条消息都会从 [ToolRegistry.getAvailableTools] 重新拉取，
 * 故插件启用后下一条消息即可被 AI 调用，无需重启。禁用/卸载时注销工具并释放 QuickJS 运行时。
 */
@Singleton
class PluginManager @Inject constructor(
    private val repository: PluginRepository,
    private val toolRegistry: ToolRegistry,
    private val toolPermissionManager: ToolPermissionManager,
    @ApplicationContext private val context: Context
) {
    private val runtimes = ConcurrentHashMap<String, PluginRuntime>()
    private val registeredTools = ConcurrentHashMap<String, List<String>>()

    @Volatile
    private var synced = false

    /** 应用启动同步：为每个已启用插件注册工具（由 AIEditorApp 在 IO 线程调用，避免阻塞首屏）。 */
    @Synchronized
    fun syncEnabled() {
        if (synced) return
        synced = true
        // 常驻：注册「列出插件」工具，AI 先查看介绍再决定调用具体插件工具。
        toolRegistry.register("plugins_list", PluginListTool(this))
        repository.listInstalled().filter { it.enabled }.forEach { inst ->
            try {
                enable(inst.manifest)
            } catch (e: Exception) {
                Log.w(TAG, "插件 ${inst.manifest.id} 启用失败", e)
                repository.setEnabled(inst.manifest.id, false)
            }
        }
    }

    /** 安装 zip 插件包（仅解压登记，不自动启用；由 UI 确认权限后调 [enable]）。 */
    fun install(zipFile: File): PluginManifest = repository.install(zipFile)

    /** 卸载：先注销工具与释放运行时，再删目录。 */
    @Synchronized
    fun uninstall(id: String) {
        disable(id)
        repository.uninstall(id)
    }

    /** 启用插件：加载 main.js、注入 ctx、解析 $ptools，注册工具到 ToolRegistry。失败抛异常。 */
    @Synchronized
    fun enable(manifest: PluginManifest) {
        if (runtimes.containsKey(manifest.id)) return
        val dir = repository.pluginDir(manifest.id)
        val ctxApi = CtxApi(context, manifest, dir, toolRegistry, toolPermissionManager, repository)
        val runtime = PluginRuntime(dir, manifest, ctxApi)
        val entries = try {
            runtime.load()
        } catch (e: Exception) {
            runtime.close()
            throw e
        }
        val tools = entries.map { PluginTool(manifest.id, it, runtime) }
        tools.forEach { toolRegistry.register(it.name, it) }
        runtimes[manifest.id] = runtime
        registeredTools[manifest.id] = tools.map { it.name }
        repository.setEnabled(manifest.id, true)
        Log.i(TAG, "插件 ${manifest.id} 已启用，注册工具 ${tools.map { it.name }}")
    }

    /** 禁用插件：注销其工具并释放运行时。 */
    @Synchronized
    fun disable(id: String) {
        registeredTools.remove(id)?.forEach { toolRegistry.unregister(it) }
        runtimes.remove(id)?.close()
        repository.setEnabled(id, false)
    }

    fun isEnabled(id: String): Boolean = runtimes.containsKey(id)

    /** 返回全部已安装插件概览（供 plugins_list 工具）。 */
    fun describe(): List<PluginOverview> {
        return repository.listInstalled().map { inst ->
            val rt = runtimes[inst.manifest.id]
            val tools = if (rt != null) {
                rt.getEntries().map { e ->
                    PluginToolOverview(
                        name = "${PluginTool.sanitizeName(inst.manifest.id)}_${PluginTool.sanitizeName(e.name)}",
                        description = e.description
                    )
                }
            } else emptyList()
            PluginOverview(
                id = inst.manifest.id,
                name = inst.manifest.name,
                version = inst.manifest.version,
                description = inst.manifest.description,
                author = inst.manifest.author,
                enabled = inst.enabled,
                permissions = inst.manifest.permissions,
                skills = inst.manifest.skills,
                mcpServers = inst.manifest.mcpServers,
                tools = tools
            )
        }
    }

    companion object {
        private const val TAG = "PluginManager"
    }
}
