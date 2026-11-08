package com.aiverse.feature.plugins.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 插件仓库 index.json 条目。
 *
 * 仓库根 `https://raw.githubusercontent.com/yhdmgf/aiverseRules/main/`，每个插件按 `{name}.zip` 下载。
 * [package] 对应插件 plugin.json 的 package（用于匹配本地已安装插件）；为空时回退按 [name] 匹配。
 */
@Serializable
data class PluginRepoEntry(
    /** 插件显示名，兼作 zip 文件名（`{name}.zip`）。 */
    val name: String,
    /** 仓库里的最新版本号（与本地 plugin.json 的 version 比较判更新）。 */
    val version: String,
    val creator: String = "",
    /** 最后更新时间的毫秒时间戳。 */
    val lastUpdate: Long = 0,
    /** 权限标识（固定英文集合，见 [PluginPermissions]）。 */
    val permission: List<String> = emptyList(),
    /** 对应插件 plugin.json 的 package；非空时用于精确匹配本地已装插件，空则用 [name]。 */
    @SerialName("package")
    val packageName: String = ""
)
