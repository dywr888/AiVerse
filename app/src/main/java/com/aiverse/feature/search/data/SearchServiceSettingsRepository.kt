package com.aiverse.feature.search.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aiverse.core.datastore.preferencesCorruptionHandler
import com.aiverse.feature.search.domain.SearchCommonOptions
import com.aiverse.feature.search.domain.SearchServiceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchServiceDataStore by preferencesDataStore(
    name = "search_service_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** 搜索服务配置的当前状态：服务列表、通用选项、当前选中服务下标。 */
data class SearchServiceState(
    val services: List<SearchServiceOptions>,
    val common: SearchCommonOptions,
    val selected: Int,
)

/**
 * 持久化搜索服务配置：多态服务列表（[SearchServiceOptions]）、通用选项（[SearchCommonOptions]）、
 * 当前选中服务下标。DataStore 用法与 [AgentSoundSettingsRepository] 一致。
 */
@Singleton
class SearchServiceSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val SERVICES_KEY = stringPreferencesKey("search_services")
        val COMMON_KEY = stringPreferencesKey("search_common")
        val SELECTED_KEY = intPreferencesKey("search_selected")

        /** 存储用 JSON：忽略未知字段、写出默认值，保证配置可跨版本迁移。 */
        val storageJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前搜索服务配置状态流；未设置时回退到默认（Bing 单服务）。 */
    val state: StateFlow<SearchServiceState> = context.searchServiceDataStore.data
        .map { prefs ->
            val services = prefs[SERVICES_KEY]?.let { raw ->
                runCatching {
                    storageJson.decodeFromString(ListSerializer(SearchServiceOptions.serializer()), raw)
                }.getOrNull()
            }?.takeIf { it.isNotEmpty() } ?: listOf(SearchServiceOptions.DEFAULT)
            val common = prefs[COMMON_KEY]?.let { raw ->
                runCatching {
                    storageJson.decodeFromString(SearchCommonOptions.serializer(), raw)
                }.getOrDefault(SearchCommonOptions())
            } ?: SearchCommonOptions()
            val selected = (prefs[SELECTED_KEY] ?: 0).coerceIn(0, services.size - 1)
            SearchServiceState(services = services, common = common, selected = selected)
        }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            SearchServiceState(
                services = listOf(SearchServiceOptions.DEFAULT),
                common = SearchCommonOptions(),
                selected = 0,
            )
        )

    /** 更新服务列表（空列表时回退为默认单元素）。 */
    suspend fun updateServices(fn: (List<SearchServiceOptions>) -> List<SearchServiceOptions>) {
        context.searchServiceDataStore.edit { prefs ->
            val current = prefs[SERVICES_KEY]?.let { raw ->
                runCatching {
                    storageJson.decodeFromString(ListSerializer(SearchServiceOptions.serializer()), raw)
                }.getOrNull()
            } ?: emptyList()
            val updated = fn(current.ifEmpty { listOf(SearchServiceOptions.DEFAULT) })
            prefs[SERVICES_KEY] = storageJson.encodeToString(ListSerializer(SearchServiceOptions.serializer()), updated)
        }
    }

    /** 更新通用搜索选项。 */
    suspend fun updateCommon(fn: (SearchCommonOptions) -> SearchCommonOptions) {
        context.searchServiceDataStore.edit { prefs ->
            val current = prefs[COMMON_KEY]?.let { raw ->
                runCatching {
                    storageJson.decodeFromString(SearchCommonOptions.serializer(), raw)
                }.getOrDefault(SearchCommonOptions())
            } ?: SearchCommonOptions()
            prefs[COMMON_KEY] = storageJson.encodeToString(SearchCommonOptions.serializer(), fn(current))
        }
    }

    /** 选中指定下标的服务，越界时收拢到有效区间。 */
    suspend fun select(index: Int) {
        context.searchServiceDataStore.edit { prefs ->
            val size = prefs[SERVICES_KEY]?.let { raw ->
                runCatching {
                    storageJson.decodeFromString(ListSerializer(SearchServiceOptions.serializer()), raw)
                }.getOrNull()?.size
            } ?: 1
            prefs[SELECTED_KEY] = index.coerceIn(0, (size - 1).coerceAtLeast(0))
        }
    }
}
