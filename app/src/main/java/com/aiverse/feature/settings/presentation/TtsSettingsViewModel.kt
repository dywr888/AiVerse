package com.aiverse.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiverse.core.tts.TtsController
import com.aiverse.feature.settings.data.repository.TtsCodeBlockMode
import com.aiverse.feature.settings.data.repository.TtsSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val repo: TtsSettingsRepository,
    val ttsController: TtsController
) : ViewModel() {

    val rate = repo.rateFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val pitch = repo.pitchFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val autoRead = repo.autoReadFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val codeBlockAnnounce = repo.codeBlockModeFlow
        .map { it == TtsCodeBlockMode.ANNOUNCE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val onlineEnabled = repo.onlineEnabledFlow.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val baseUrl = repo.baseUrlFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apiKey = repo.apiKeyFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val model = repo.modelFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "tts-1")
    val voice = repo.voiceFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "alloy")
    val providerType = repo.providerTypeFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    fun setRate(value: Float) = viewModelScope.launch { repo.setRate(value) }
    fun setPitch(value: Float) = viewModelScope.launch { repo.setPitch(value) }
    fun setAutoRead(enabled: Boolean) = viewModelScope.launch { repo.setAutoRead(enabled) }
    fun setCodeBlockAnnounce(enabled: Boolean) = viewModelScope.launch {
        repo.setCodeBlockMode(if (enabled) TtsCodeBlockMode.ANNOUNCE else TtsCodeBlockMode.SKIP)
    }
    fun setOnlineEnabled(enabled: Boolean) = viewModelScope.launch { repo.setOnlineEnabled(enabled) }
    fun setBaseUrl(url: String) = viewModelScope.launch { repo.setBaseUrl(url) }
    fun setApiKey(key: String) = viewModelScope.launch { repo.setApiKey(key) }
    fun setModel(model: String) = viewModelScope.launch { repo.setModel(model) }
    fun setVoice(voice: String) = viewModelScope.launch { repo.setVoice(voice) }
    fun setProviderType(type: String) = viewModelScope.launch { repo.setProviderType(type) }
}
