package com.aiverse.feature.search.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.feature.search.data.SearchServiceSettingsRepository
import com.aiverse.feature.search.data.SearchServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 搜索提供商管理页的 ViewModel：订阅 [SearchServiceSettingsRepository] 的快照，
 * 增删/排序/选择/改公共参数都写回仓库（DataStore 持久化 + 即时发布）。
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
@HiltViewModel
class SearchProviderViewModel @Inject constructor(
    private val repository: SearchServiceSettingsRepository
) : ViewModel() {

    val state: StateFlow<SearchServiceState> = repository.state

    /** 在列表头部添加一个新服务（type 由 AddProviderDialog 选择）。 */
    fun addService(service: com.aiverse.feature.search.domain.SearchServiceOptions) {
        viewModelScope.launch { repository.updateServices { listOf(service) + it } }
    }

    fun removeService(id: kotlin.uuid.Uuid) {
        viewModelScope.launch { repository.updateServices { list -> list.filter { it.id != id } } }
    }

    /** 拖拽排序：把 from 移到 to。 */
    fun moveService(from: Int, to: Int) {
        viewModelScope.launch {
            repository.updateServices { list ->
                if (from !in list.indices || to !in list.indices) return@updateServices list
                list.toMutableList().apply { add(to, removeAt(from)) }
            }
        }
    }

    fun selectService(index: Int) {
        viewModelScope.launch { repository.select(index) }
    }

    fun updateResultSize(size: Int) {
        viewModelScope.launch {
            repository.updateCommon { it.copy(resultSize = size.coerceIn(1, 20)) }
        }
    }

    fun updateService(service: com.aiverse.feature.search.domain.SearchServiceOptions) {
        viewModelScope.launch {
            repository.updateServices { list -> list.map { if (it.id == service.id) service else it } }
        }
    }
}
