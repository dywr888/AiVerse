package com.aiverse.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.feature.agent.data.repository.FavoriteItem
import com.aiverse.feature.agent.data.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoriteViewModel @Inject constructor(
    private val repository: FavoriteRepository
) : ViewModel() {
    val favorites: StateFlow<List<FavoriteItem>> =
        repository.favoritesFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(content: String, sessionTitle: String = "") {
        viewModelScope.launch { repository.add(content, sessionTitle) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun update(id: String, content: String) {
        viewModelScope.launch { repository.update(id, content) }
    }
}
