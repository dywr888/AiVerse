package com.aiverse.feature.plugins.domain

import com.aiverse.feature.plugins.data.PluginRepository
import com.aiverse.feature.plugins.data.remote.PluginRepoClient
import com.aiverse.feature.plugins.domain.model.PluginRepoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件仓库更新检查：启动时 / 进入插件页时拉取 index，比较本地已装插件版本，
 * 产出「可更新插件」集合（本地插件 id → 仓库条目）。
 *
 * 检查失败静默（不打扰用户），保留上一次结果；角标与「更新」按钮据此显示。
 */
@Singleton
class PluginUpdateChecker @Inject constructor(
    private val repository: PluginRepository,
    private val client: PluginRepoClient
) {
    private val _updates = MutableStateFlow<Map<String, PluginRepoEntry>>(emptyMap())
    val updates: StateFlow<Map<String, PluginRepoEntry>> = _updates.asStateFlow()

    /** 拉取仓库并计算可更新插件；网络/解析失败时静默返回。 */
    suspend fun check() = withContext(Dispatchers.IO) {
        val entries = try {
            client.fetchIndex()
        } catch (e: Exception) {
            return@withContext
        }
        val map = mutableMapOf<String, PluginRepoEntry>()
        repository.listInstalled().forEach { inst ->
            entries.firstOrNull { matches(it, inst.manifest.packageName, inst.manifest.name) }?.let { entry ->
                if (compareVersions(entry.version, inst.manifest.version) > 0) {
                    map[inst.manifest.id] = entry
                }
            }
        }
        _updates.value = map
    }

    /** 仓库条目与本地插件匹配：条目带 package 用 package 精确匹配，否则回退按 name。 */
    fun matches(entry: PluginRepoEntry, pkg: String, name: String): Boolean =
        if (entry.packageName.isNotBlank()) entry.packageName == pkg else entry.name == name

    companion object {
        /** 比较版本号：按 `.` 数字段逐段比（1.10 > 1.9 > 1.3），非数字段按 0 计。 */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').mapNotNull { it.toIntOrNull() }
            val pb = b.split('.').mapNotNull { it.toIntOrNull() }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
