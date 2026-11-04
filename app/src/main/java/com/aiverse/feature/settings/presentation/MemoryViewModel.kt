package com.aiverse.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.feature.agent.domain.memory.Memory
import com.aiverse.feature.agent.domain.memory.MemoryRepository
import com.aiverse.feature.agent.domain.memory.MemoryScope
import com.aiverse.feature.agent.domain.prompt.SystemPromptProvider
import com.aiverse.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 记忆页状态：全局与项目两层分开列，方便设置页分组展示。 */
data class MemoryUiState(
    val global: List<Memory> = emptyList(),
    val project: List<Memory> = emptyList(),
    val projectPath: String = "",
    val loading: Boolean = true
) {
    val hasWorkspace: Boolean get() = projectPath.isNotBlank()
    val projectName: String get() = projectPath.trimEnd('/').substringAfterLast('/')
}

/**
 * 记忆设置页：读写 `~/.aiverse/memory`（全局）与项目目录下的 `.aiverse/memory`（项目）。
 *
 * 与提示词页一样，磁盘 IO 统一放 IO 线程；保存后重新扫描，避免本地列表与磁盘不一致。
 */
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val systemPromptProvider: SystemPromptProvider
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                MemoryUiState(
                    global = repository.listGlobalMemories(),
                    project = repository.listProjectMemories(projectRoot),
                    projectPath = projectRoot,
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    fun save(
        name: String,
        description: String,
        content: String,
        scope: MemoryScope,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val ok = withContext(Dispatchers.IO) {
                repository.saveMemory(name, description, content, scope, projectRoot)
            }
            // 保存后让当前会话的 system prompt 立即带上新记忆。
            if (ok) systemPromptProvider.invalidateMemoryCache()
            refresh()
            onResult(ok)
        }
    }

    fun delete(memory: Memory, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val ok = withContext(Dispatchers.IO) {
                repository.deleteMemory(memory.name, memory.scope, projectRoot)
            }
            if (ok) systemPromptProvider.invalidateMemoryCache()
            refresh()
            onResult(ok)
        }
    }
}
