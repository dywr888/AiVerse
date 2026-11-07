package com.aiverse.feature.settings.data.repository

import android.content.Context
import com.aiverse.feature.settings.domain.model.BalanceConfig
import com.aiverse.feature.settings.domain.model.AIProviderConfig
import com.aiverse.feature.settings.domain.model.defaultBalanceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 余额查询配置（按提供商 id 存储）。
 *
 * 单一真源：[configs] StateFlow；主页输入框快捷栏与设置页订阅同一流，改动即时同步。
 * 任何提供商都有默认配置（默认关闭，仅 deepseek 默认开启，见 [defaultBalanceConfig]）。
 */
@Singleton
class BalanceSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("balance_prefs", Context.MODE_PRIVATE)

    private val _configs = MutableStateFlow(loadAll())

    /** 全部余额配置（providerId -> config）。 */
    val configs: StateFlow<Map<String, BalanceConfig>> = _configs.asStateFlow()

    /**
     * 取某提供商的余额配置。
     * 已自定义过（存储里有）返回存储值；否则按提供商标识返回默认配置（deepseek 默认开启，其余默认关闭）。
     */
    fun configFor(provider: AIProviderConfig): BalanceConfig {
        _configs.value[provider.id]?.let { return it }
        return defaultBalanceConfig(provider)
    }

    /** 写回某提供商的余额配置（持久化 + 即时发布）。 */
    fun updateConfig(providerId: String, config: BalanceConfig) {
        prefs.edit().putString("balance_$providerId", config.toJson().toString()).apply()
        _configs.value = _configs.value + (providerId to config)
    }

    private fun loadAll(): Map<String, BalanceConfig> {
        val map = mutableMapOf<String, BalanceConfig>()
        prefs.all.forEach { (k, v) ->
            if (k.startsWith("balance_") && v is String) {
                val id = k.removePrefix("balance_")
                runCatching { BalanceConfig.fromJson(JSONObject(v)) }.getOrNull()?.let { map[id] = it }
            }
        }
        return map
    }
}
